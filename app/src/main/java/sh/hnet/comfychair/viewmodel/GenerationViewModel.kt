package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.queue.resolveExecutionStartOwner
import sh.hnet.comfychair.model.ContentType
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.notify.GenerationKeepAliveService
import sh.hnet.comfychair.notify.GenerationNotifier
import sh.hnet.comfychair.prompt.SkillTextResult
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.viewmodel.base.GenerationCompletionRelay
import sh.hnet.comfychair.workflow.WorkflowPreflight
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Generation state data class
 */
data class GenerationState(
    val isGenerating: Boolean = false,
    val promptId: String? = null,
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val ownerId: String? = null,
    val contentType: ContentType = ContentType.IMAGE
)

/**
 * Connection state enum
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * One-time events emitted during generation
 */
sealed class GenerationEvent {
    data class ImageGenerated(val promptId: String) : GenerationEvent()
    data class VideoGenerated(val promptId: String) : GenerationEvent()
    data class PreviewImage(val bitmap: Bitmap) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
    data object GenerationCancelled : GenerationEvent()

    /**
     * 取消被拒绝（v4.6.1）。
     *
     * 场景：当前执行的是别的客户端/别的模式提交的任务，而本客户端没有自己的任务在跑。
     * 以前这种情况会直接发 `/interrupt`，把别人的任务打断；现在改为拒绝并提示。
     */
    data class CancelRejected(@StringRes val messageResId: Int) : GenerationEvent()
    data object ConnectionLostDuringGeneration : GenerationEvent()
    data object ClearPreviewForResume : GenerationEvent()
}

/**
 * 生成完成后触发图库刷新的防抖阀（H5，纯逻辑抽出便于 JVM 单测）。
 *
 * 两条规则缺一不可：
 * - **同一任务只算一次**：完成事件有多条路径（WS 双事件 + 断线轮询兜底）会重复到达；
 * - **防抖窗口内合并**：批量出图时多个任务接连完成，每个都刷一遍等于对 /history 连发，
 *   窗口内合并成一次即可（后一次刷新本来就会把前面的新条目一起带出来）。
 */
internal class GalleryRefreshThrottle(private val debounceMs: Long) {

    private var lastPromptId: String? = null
    private var lastFiredAtMs = 0L
    private var hasFired = false

    /** 返回 true 表示这次应该真的触发刷新 */
    fun shouldFire(promptId: String, nowMs: Long): Boolean {
        // 同一任务的重复完成事件：无论隔多久都只算一次
        if (promptId == lastPromptId) return false
        lastPromptId = promptId
        // 首次触发没有"上次时间"可比，直接放行。
        // 不能拿 Long.MIN_VALUE 当"久远的过去"：nowMs - Long.MIN_VALUE 会溢出成负数，
        // 首次触发就永远被窗口拦下（单测抓过）
        if (!hasFired) {
            hasFired = true
            lastFiredAtMs = nowMs
            return true
        }
        // 防抖窗口内的不同任务：合并进上一次刷新
        if (nowMs - lastFiredAtMs < debounceMs) return false
        lastFiredAtMs = nowMs
        return true
    }
}

/**
 * P1-4：提交单飞闸门（纯 JVM 逻辑，供单测钉住）。
 *
 * 缺陷：`startGeneration` 没有任何在途标记，连点两次生成、或"生成"和"添加到队首"
 * 几乎同时按下去，会各自发出一份 `/prompt` —— 服务器上是两个任务，用户只想要一个，
 * 出图数量翻倍、队列计数也翻倍。
 *
 * 闸门的语义刻意做成"**先到者独占，迟到者直接失败**"（而不是排队）：
 * 用户的第二次点击本来就是一个误触，静默丢弃最符合预期；返回值让调用方可以
 * 直接忽略而不是再报一次错。
 *
 * 线程模型与**持有者令牌**（R2-L4）：
 * - `tryAcquire` 用 [AtomicLong.compareAndSet] 把"空闲(0) → 本次令牌"一次做成，
 *   并发调用只有一次能成功；令牌全局单调递增，不会复用。
 * - `release(ticket)` 只在"当前持有者仍是这个令牌"时才清空。没有令牌时，30 秒看门狗
 *   或迟到的鉴权失败回调会把**下一次**提交刚抢到的闸门误释放（极端时序下多放行一次
 *   重复提交）；带令牌后这类迟到调用退化为无害的空操作。
 * - 同一令牌重复释放仍然幂等（看门狗与正常回调可能都调一次）。
 */
internal class SubmitGate {

    /** 0 = 空闲；非 0 = 当前持有者的令牌 */
    private val holder = AtomicLong(0L)

    /** 令牌发号器（与 holder 分开，保证失败的那次尝试也不会回流票号） */
    private val ticketSource = AtomicLong(0L)

    /** 当前是否有提交在途（供界面把「提交中」并入 enabled 条件） */
    val isInFlight: Boolean get() = holder.get() != 0L

    /**
     * @return 本次持有者令牌（> 0）= 抢到闸门，调用方必须在成功/失败/异常三路
     *   用同一令牌 [release]；null = 已有提交在途，本次必须放弃
     */
    fun tryAcquire(): Long? {
        val ticket = ticketSource.incrementAndGet()
        return if (holder.compareAndSet(0L, ticket)) ticket else null
    }

    /**
     * 释放闸门。只有令牌仍是当前持有者时才生效（迟到/过期的释放不会误清别人的闸门）。
     */
    fun release(ticket: Long) {
        holder.compareAndSet(ticket, 0L)
    }

    /** 该令牌是否仍是当前持有者（看门狗用，避免为过期令牌白打日志） */
    fun isHeldBy(ticket: Long): Boolean = holder.get() == ticket
}

/**
 * P1-3 / F5：一条**瞬时**事件（预览、清预览）是否仍属于「当前这一轮生成」。
 *
 * 背景：`resetGenerationState` 的缓冲清理与事件投递都是主线程任务，两者入队顺序
 * 由调用线程决定。若清理先入队、某条上一轮的 `PreviewImage` 投递后入队，那张图会被
 * 重新写进 `bufferedPreviews` —— 下一次该 owner 注册 handler 时会被当成"缓冲预览"
 * 再放一帧上一轮的旧画面（界面闪一下，数据无影响）。
 *
 * 判定用「当前 GenerationState 是否还在为同一个 owner 生成」：复位后 `GenerationState()`
 * 的 `isGenerating=false`、`ownerId=null`，上一轮的瞬时事件一律判为残留。
 *
 * 注意**只适用于瞬时事件**：完成事件（ImageGenerated / VideoGenerated）的正常路径就是
 * "先进缓冲、随后状态复位"，它们必须能活到 handler 注册，不能用这条判定丢弃。
 *
 * @param isGenerating 当前 GenerationState.isGenerating
 * @param stateOwnerId 当前 GenerationState.ownerId
 * @param eventOwnerId 事件产生时定格下来的 owner
 */
internal fun isCurrentGenerationEvent(
    isGenerating: Boolean,
    stateOwnerId: String?,
    eventOwnerId: String?,
): Boolean = isGenerating && stateOwnerId != null && stateOwnerId == eventOwnerId

/**
 * Central ViewModel for managing generation state.
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket connection and broadcasts parsed events
 * - This ViewModel subscribes to ConnectionManager.webSocketMessages SharedFlow
 * - Multiple instances can safely coexist (e.g., MainContainer + GalleryContainer)
 * - Only the instance whose ownerId matches the generation state processes events
 */
class GenerationViewModel : ViewModel(), GenerationCompletionRelay {

    /**
     * 各页面注册的"提交成功"回调，**按 ownerId 存**。
     *
     * 为什么不是单个属性：底部导航的多个页面会同时留在组合里（切 Tab 时旧的并不立即
     * dispose），各自都会注册一次 —— 用单个属性的话后注册的会覆盖前一个，
     * 结果在文生图页提交却调用了图生视频页的回调。按 ownerId 索引才能精确命中提交方。
     */
    private val submittedCallbacks = mutableMapOf<String, () -> Unit>()

    /**
     * 注册"提交成功"回调（种子的「每次+1 / 每次−1」在这里落地）。
     *
     * 刻意挂在**提交成功**而不是"生成完成"上：用户排队多个任务时每个任务应该用到
     * 不同的种子；等出图完成再挪会让大家挤在同一个种子上。
     */
    fun registerSubmittedCallback(ownerId: String, callback: () -> Unit) {
        submittedCallbacks[ownerId] = callback
    }

    /** 注销（页面离开时调；与 [registerSubmittedCallback] 成对） */
    override fun unregisterSubmittedCallback(ownerId: String) {
        submittedCallbacks.remove(ownerId)
    }

    // Application context for gallery repository
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Connection state (derived from WebSocket state)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Generation state
    private val _generationState = MutableStateFlow(GenerationState())
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    // B1：GenerationCompletionRelay 的窄接口实现——base 包只看这一个布尔，不再触碰完整 state
    override val isGeneratingNow: Boolean
        get() = generationState.value.isGenerating

    // One-time events
    private val _events = MutableSharedFlow<GenerationEvent>()
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    // Active event handler for screen-specific event delivery
    //
    // P1-3：这四个字段会被 OkHttp 线程（`pollForCompletion` / `checkServerForCompletion`
    // 的回调）读，写入在主线程（Compose 注册处理器、提交回调、状态复位）。
    // 不加 @Volatile 时读方可能长期看到旧值 —— 典型症状是"轮询明明已经拿到完成，
    // dispatchEvent 却按旧 owner 判断，事件被塞进错误的缓冲/直接丢弃"。
    @Volatile
    private var activeEventHandler: ((GenerationEvent) -> Unit)? = null
    @Volatile
    private var activeEventHandlerOwnerId: String? = null  // Track who registered the handler
    @Volatile
    private var generationOwnerId: String? = null

    // Content type for current generation (used in submitWorkflow)
    @Volatile
    private var generationContentType: ContentType = ContentType.IMAGE

    /**
     * 主线程 Handler（P1-3）。所有对 [pendingCompletions] / [bufferedPreviews] /
     * [pendingClearPreviews] 的**写入与排空**都经它串行化到主线程。
     *
     * 为什么必须串行：这三个容器是普通 `mutableMap`/`mutableSet`（不是线程安全的）。
     * OkHttp 回调线程写、主线程（[registerEventHandler]）读并删除，两条线程同时操作
     * 会让 HashMap 的内部结构错乱（丢条目、甚至死循环）。统一到主线程后：
     * - `Handler.post` 保证入队前的写入对后续执行可见（happens-before）；
     * - 主线程单写者，`containsKey → remove` 这类"检查后动作"不再有交错。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 提交单飞闸门（P1-4） */
    private val submitGate = SubmitGate()

    /**
     * 提交在途状态（P1-4），供界面把「提交中」并入 enabled 条件。
     *
     * 已接入的消费点（四个生成页的 `GenerationButton`）：
     * `isEnabled = 参数合法 && !isSubmitting`。两层保护同时存在：
     * 界面在闸门持有期间禁用按钮（视觉/交互），[submitGate] 在 ViewModel 层兜底
     *（即使按钮未及时重组，也不会发出第二份 `/prompt`）。
     */
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    /**
     * 当前持有提交闸门的页面 ownerId（v4.16.14 复核 BUSY-03）：null = 无提交在途。
     *
     * 为什么需要：`isSubmitting` 是四个生成页**共享**的 Activity 级状态，只表达"有提交在途"，
     * 不表达"是谁在提交"。界面的忙碌皮肤必须只出现在提交方那页 —— 否则 A 页提交期间切到
     * B 页，B 页哪怕提示词为空/未选图（isEnabled=false），按钮也会显示忙碌皮肤而不是真禁用
     * 灰底。页面侧据此过滤：`isSubmitting && submitOwnerId == 本页 OWNER_ID`。
     */
    private val _submitOwnerId = MutableStateFlow<String?>(null)
    val submitOwnerId: StateFlow<String?> = _submitOwnerId.asStateFlow()

    /** 提交闸门的超时看门狗（P1-4）：网络层回调丢失时兜底释放，避免按钮永久卡死 */
    private var submitGateWatchdog: Job? = null

    // Per-owner buffered events (keyed by ownerId)
    // This prevents events from one owner overwriting another's when jobs overlap
    //
    // 线程约束（P1-3）：只允许在主线程读写，入口是 dispatchEvent（经 runOnMain 转到
    // deliverOrBuffer）/ registerEventHandler / resetGenerationState 的清理段。
    // 不要在别的线程直接碰这三个容器。
    private val pendingCompletions = mutableMapOf<String, GenerationEvent>()  // ImageGenerated or VideoGenerated
    private val bufferedPreviews = mutableMapOf<String, Bitmap>()  // Preview when no handler registered
    private val pendingClearPreviews = mutableSetOf<String>()  // ClearPreviewForResume when no handler

    // Polling for completion when resuming a generation
    private var completionPollingJob: Job? = null

    /**
     * 连续多少次「history 没产物 + 队列里也没有」才算任务丢失。
     *
     * 间隔是 [COMPLETION_POLLING_INTERVAL_MS]（3s），40 次约 2 分钟。
     * 只要任务还在队列里这个计数就清零，所以排队/慢任务不会被误杀；
     * 真正会累计的只有「服务器已经不认这个 prompt」的情况（重启、队列被清）。
     */
    // 跨线程读写：OkHttp 回调线程自增，viewModelScope 轮询协程清零，
    // 不加 @Volatile 时另一线程可能长期读到旧值（漏失计数判断/误清零）
    @Volatile
    private var pollMissCount = 0

    // Subscription jobs
    private var webSocketMessageJob: Job? = null
    private var webSocketStateJob: Job? = null

    companion object {
        private const val TAG = "Generation"
        private const val PREFS_NAME = "GenerationViewModelPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
        private const val PREF_OWNER_ID = "ownerId"
        private const val PREF_CONTENT_TYPE = "contentType"
        private const val COMPLETION_POLLING_INTERVAL_MS = 3000L
        private const val MAX_POLL_MISSES_WITHOUT_QUEUE = 40

        /**
         * 取消后与服务器对齐队列的延迟（v4.8.2）。
         *
         * 两个用途：
         * 1. `/interrupt` 返回后 ComfyUI 会立刻抓起下一个 pending 任务，
         *    稍等片刻再拉 `/queue` 才能看到真实状态；
         * 2. 本地 executingPromptId 滞后时，先等这次同步回来再决定走中断还是清排队。
         */
        private const val QUEUE_SYNC_DELAY_MS = 500L

        /** 生成完成后触发图库刷新的防抖窗口（H5）：批量任务接连完成时合并为一次刷新 */
        private const val GALLERY_REFRESH_DEBOUNCE_MS = 2000L

        /**
         * 提交闸门的最长持有时间（P1-4）。
         *
         * `/prompt` 是一个很快的 POST（服务器收下即返回 promptId），正常在几百毫秒内回来；
         * 30 秒还没释放只可能是回调丢了（ensureConnection 的 stale 分支、网络层异常）。
         * 到点强制释放，宁可漏过一次重复提交，也不让生成按钮永久禁用。
         */
        private const val SUBMIT_GATE_TIMEOUT_MS = 30_000L
    }

    /**
     * Initialize the ViewModel with context.
     * Connection is managed by ConnectionManager.
     */
    fun initialize(context: Context) {
        if (this.applicationContext != null) {
            // Already initialized
            DebugLogger.d(TAG, "Already initialized, skipping")
            return
        }

        DebugLogger.i(TAG, "Initializing")
        this.applicationContext = context.applicationContext

        // Restore JobRegistry state from persistence
        JobRegistry.restoreState(context)

        // Restore generation state
        restoreGenerationState(context)

        // Subscribe to ConnectionManager's WebSocket flows
        subscribeToWebSocketMessages()
        subscribeToWebSocketState()

        // Open WebSocket connection via ConnectionManager
        if (ConnectionManager.isConnected) {
            ConnectionManager.openWebSocket()
            // Poll queue to validate restored jobs against server
            ConnectionManager.pollQueueStatus()
        } else {
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        // If we restored an active generation, check completion immediately
        if (_generationState.value.isGenerating) {
            DebugLogger.d(TAG, "Restored active generation, checking completion immediately")
            checkServerForCompletion()
        }
    }

    /**
     * Subscribe to WebSocket messages from ConnectionManager
     */
    private fun subscribeToWebSocketMessages() {
        webSocketMessageJob?.cancel()
        webSocketMessageJob = viewModelScope.launch {
            ConnectionManager.webSocketMessages.collect { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    /**
     * Subscribe to WebSocket state changes from ConnectionManager
     */
    private fun subscribeToWebSocketState() {
        webSocketStateJob?.cancel()
        webSocketStateJob = viewModelScope.launch {
            ConnectionManager.webSocketState.collect { state ->
                when (state) {
                    is WebSocketState.Connected -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        // Check for pending completion when reconnecting
                        if (_generationState.value.isGenerating) {
                            DebugLogger.d(TAG, "WebSocket reconnected, checking for completion")
                            checkServerForCompletion()
                        }
                    }
                    is WebSocketState.Connecting -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTING
                    }
                    is WebSocketState.Reconnecting -> {
                        _connectionStatus.value = ConnectionStatus.RECONNECTING
                        // Notify user if generation is in progress
                        if (_generationState.value.isGenerating) {
                            applicationContext?.let { saveGenerationState(it) }
                            dispatchEvent(GenerationEvent.ConnectionLostDuringGeneration)
                        }
                    }
                    is WebSocketState.Failed -> {
                        _connectionStatus.value = ConnectionStatus.FAILED
                        // P3：这里不再直接决定"弹不弹模态"，只把失败交给统一分流入口。
                        // `ConnectionManager.showConnectionAlert` 现在按失败类型处理：
                        // 网络 / 传输停滞 → 非阻塞横幅（生成中也不拦截界面，进度与数据都保留）；
                        // 只有认证失效才是模态，且对话框本身可随时关闭。
                        // D10：瞬时失败不会走到这里——ConnectionManager 在排上自动重连时不再写 Failed。
                        if (_generationState.value.isGenerating) {
                            applicationContext?.let { ctx ->
                                ConnectionManager.showConnectionAlert(ctx, state.failureType)
                            }
                        }
                    }
                    is WebSocketState.Disconnected -> {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                }
            }
        }
    }

    /**
     * Handle parsed WebSocket message from ConnectionManager
     */
    private fun handleWebSocketMessage(message: WebSocketMessage) {
        val currentState = _generationState.value

        when (message) {
            is WebSocketMessage.ExecutionComplete -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.ExecutionSuccess -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete via execution_success (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.Progress -> {
                if (currentState.isGenerating) {
                    _generationState.value = currentState.copy(
                        progress = message.value,
                        maxProgress = message.max
                    )
                    // 通知内部有节流与前后台判定：前台时零通知开销，后台时静默更新
                    val notifyContext = applicationContext
                    if (notifyContext != null) {
                        GenerationNotifier.onProgress(
                            notifyContext,
                            currentState.ownerId,
                            message.value,
                            message.max
                        )
                    }
                }
            }
            is WebSocketMessage.PreviewImage -> {
                // Only process previews if we're generating and live preview is enabled
                val context = applicationContext
                if (currentState.isGenerating && context != null && AppSettings.isLivePreviewEnabled(context)) {
                    dispatchEvent(GenerationEvent.PreviewImage(message.bitmap))
                }
            }
            is WebSocketMessage.ExecutionError -> {
                // 与 ExecutionComplete/ExecutionSuccess 同口径：不在生成态一律忽略，
                // 否则 promptId == null 的广播错误会把空闲页面的状态误重置
                if ((message.promptId == currentState.promptId || message.promptId == null) && currentState.isGenerating) {
                    // Dispatch error event BEFORE resetting state, so ownerId is still available
                    // for handler matching in dispatchEvent()
                    val errorMessage = applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                    // 后台时把进度通知换成失败横幅（前台时撤掉，界面承担提示）
                    applicationContext?.let {
                        GenerationNotifier.onGenerationFailed(it, currentState.ownerId, errorMessage)
                    }
                    GenerationNotifier.registerCancelHandler(null)
                    dispatchEvent(GenerationEvent.Error(errorMessage))
                    resetGenerationState()
                }
            }
            // When a job starts executing, update generation state to track it
            is WebSocketMessage.ExecutionStart -> {
                val promptId = message.promptId
                if (promptId.isNotEmpty()) {
                    // P1-1：`execution_start` 是服务器**广播给所有客户端**的，不能靠
                    // "注册表查不到 + 本机有在途提交"就认领 —— 那样外部客户端的任务会被
                    // 记到本机名下：本机进度条被外部任务推动、预览串页、外部任务结束时
                    // 还会把本机的 executing 指针清掉。
                    //
                    // 归属证据（见 resolveExecutionStartOwner）：
                    // 1. 注册表里有这个 promptId（提交成功时登记的）；
                    // 2. 这个 promptId 在本机提交记录里（promptId 是服务器返回给我们的）。
                    //
                    // P6：第三条证据在更早的 `JobRegistry.markExecuting` 里已经处理 ——
                    // 弱网下 HTTP 响应丢失时，注册表/提交记录都查不到，但本机有"已发出、
                    // 结果未知"的在途提交；`markExecuting` 会按 FIFO 认领并 registerJob。
                    // 所以走到这里时，被 P6 接管的任务已经能在 registeredOwner 里查到了。
                    //
                    // R2-L5：第 2 条**覆盖不到**"execution_start 早于 registerJob"这个时序，
                    // P6 之前由 markExecuting 记 executingPromptId + registerJob 回填补正；
                    // P6 之后在途意图还在时会直接认领，最终状态一致。
                    // 第 2 条真正覆盖的是：条目已被完成/对账清掉之后，同一个 promptId 的
                    // 事件被重放（WS 重连补发、乱序到达）时仍能认出是自己的。
                    val registeredOwner = JobRegistry.findOwner(promptId)
                    val submitted = JobRegistry.findSubmittedPrompt(promptId)
                    val owner = resolveExecutionStartOwner(
                        eventPromptId = promptId,
                        registeredOwnerId = registeredOwner,
                        submittedOwnerId = submitted?.ownerId,
                    )
                    val contentType = if (registeredOwner != null) {
                        JobRegistry.findContentType(promptId)
                    } else {
                        submitted?.contentType
                    }

                    if (owner == null || contentType == null) {
                        DebugLogger.d(
                            TAG,
                            "忽略外部任务的 execution_start (promptId: ${Obfuscator.promptId(promptId)})"
                        )
                    } else {
                        // 注册表条目已被清理（完成/取消/对账），但本机提交记录还在：
                        // 补登记一次，让后续 progress / completion 事件仍能找到归属。
                        // 注意这里**不是**在修"早于登记到达"——那个时序走 markExecuting 回填。
                        if (registeredOwner == null) {
                            DebugLogger.w(
                                TAG,
                                "注册表条目已清理，按本机提交记录补登记 (owner: $owner)"
                            )
                            JobRegistry.registerJob(promptId, owner, contentType)
                            applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                        }

                        val previousOwner = generationOwnerId
                        val ownerChanged = previousOwner != null && previousOwner != owner

                        // This is one of our jobs - update state to track it
                        DebugLogger.d(TAG, "Tracking execution start for our job (promptId: ${Obfuscator.promptId(promptId)}, owner: $owner)")
                        if (ownerChanged) {
                            DebugLogger.i(TAG, "OWNER CHANGE: $previousOwner -> $owner, currentHandler=$activeEventHandlerOwnerId")
                        }

                        generationOwnerId = owner
                        generationContentType = contentType
                        _generationState.value = GenerationState(
                            isGenerating = true,
                            promptId = promptId,
                            progress = 0,
                            maxProgress = 100,
                            ownerId = owner,
                            contentType = contentType
                        )

                        // 任务进入生成态：登记通知快照 + 注册通知「取消」按钮的处理器
                        // （复位路径统一注销）。内部有前后台与开关判定，前台零开销。
                        val notifyContext = applicationContext
                        if (notifyContext != null) {
                            GenerationNotifier.onGenerationStarted(notifyContext, owner)
                            // P4：任务开始即尝试拉起任务级保活（仅 WiFi + 通知开启时生效）。
                            // 必须在 App 仍可见时启动 —— 后台启动前台服务会被系统拒绝。
                            GenerationKeepAliveService.ensureRunning(notifyContext)
                        }
                        GenerationNotifier.registerCancelHandler { cancelGeneration { _ -> } }

                        // If handler doesn't match new owner, clear it so correct screen can register
                        if (activeEventHandlerOwnerId != null && activeEventHandlerOwnerId != owner) {
                            DebugLogger.w(TAG, "Handler mismatch after ExecutionStart: handler=$activeEventHandlerOwnerId, newOwner=$owner - clearing handler")
                            activeEventHandler = null
                            activeEventHandlerOwnerId = null
                        }
                    }
                }
            }
            is WebSocketMessage.Executing -> {}
            is WebSocketMessage.ExecutionCached -> {}
            is WebSocketMessage.Status -> {}
            is WebSocketMessage.Unknown -> {}
        }
    }

    /**
     * Get the ComfyUI client instance
     */
    fun getClient(): ComfyUIClient? = comfyUIClient

    /**
     * Get the hostname from ConnectionManager
     */
    fun getHostname(): String = ConnectionManager.hostname

    /**
     * Get the port from ConnectionManager
     */
    fun getPort(): Int = ConnectionManager.port

    /**
     * Register an event handler for a specific owner (screen).
     * Only the owner that started generation will receive events.
     * If there's a buffered preview or pending completion, it will be delivered immediately.
     *
     * 线程约束（P1-3）：**必须在主线程调用**（Compose 的 DisposableEffect/LaunchedEffect 天然满足）。
     * 这里对 `pendingCompletions` / `bufferedPreviews` / `pendingClearPreviews` 做
     * "检查 → 注册处理器 → 排空"三步；只有与 [deliverOrBuffer]（同样在主线程）串行时，
     * 这三步才是原子的 —— 否则一个在 OkHttp 线程产生的事件可能插在"检查"与"排空"之间，
     * 既没被排空、又因为处理器已注册而不再重投，等于永久丢失。
     */
    fun registerEventHandler(ownerId: String, handler: (GenerationEvent) -> Unit) {
        val state = _generationState.value
        val executingOwner = state.ownerId

        // Check if this owner has pending buffered events that need delivery
        val hasPendingEvents = pendingCompletions.containsKey(ownerId) ||
                               bufferedPreviews.containsKey(ownerId) ||
                               pendingClearPreviews.contains(ownerId)

        DebugLogger.d(TAG, "registerEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "generationOwnerId=$generationOwnerId, currentHandler=$activeEventHandlerOwnerId, hasPending=$hasPendingEvents")

        // Allow registration if:
        // 1. This owner matches the executing job, OR
        // 2. No generation is active, OR
        // 3. This owner has pending buffered events that need delivery
        if (ownerId == executingOwner || executingOwner == null || hasPendingEvents) {
            DebugLogger.d(TAG, "Handler registration ACCEPTED for $ownerId (reason: ${
                when {
                    ownerId == executingOwner -> "matches executing"
                    executingOwner == null -> "no active generation"
                    hasPendingEvents -> "has pending events"
                    else -> "unknown"
                }
            })")
            activeEventHandler = handler
            activeEventHandlerOwnerId = ownerId

            // Deliver pending ClearPreviewForResume if exists for THIS owner (must be before preview delivery)
            if (pendingClearPreviews.remove(ownerId)) {
                DebugLogger.d(TAG, "Delivering buffered ClearPreviewForResume to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.ClearPreviewForResume) }
            }

            // Deliver buffered preview if exists for THIS owner
            bufferedPreviews.remove(ownerId)?.let { bitmap ->
                DebugLogger.d(TAG, "Delivering buffered PreviewImage to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.PreviewImage(bitmap)) }
            }

            // Deliver pending completion if exists for THIS owner
            pendingCompletions.remove(ownerId)?.let { completion ->
                DebugLogger.d(TAG, "Delivering buffered ${completion::class.simpleName} to $ownerId")
                viewModelScope.launch { handler(completion) }
            }

            // If generation is active but completion wasn't detected yet,
            // trigger a server check in case completion happened before handler registered
            if (state.isGenerating && !pendingCompletions.containsKey(ownerId) && ownerId == state.ownerId) {
                DebugLogger.d(TAG, "Handler registered during active generation, verifying status")
                checkServerForCompletion()
            }
        } else {
            DebugLogger.d(TAG, "Handler registration REJECTED for $ownerId (executing owner is $executingOwner)")
        }
    }

    /**
     * Unregister the event handler for a specific owner.
     * Only clears the handler if this owner actually registered it.
     * If the owner started generation that's still running, keep handler active for pending completion.
     */
    override fun unregisterEventHandler(ownerId: String) {
        val state = _generationState.value
        val executingOwner = state.ownerId

        DebugLogger.d(TAG, "unregisterEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "currentHandler=$activeEventHandlerOwnerId, isGenerating=${state.isGenerating}")

        // Only clear handler if THIS owner registered it
        if (activeEventHandlerOwnerId != ownerId) {
            DebugLogger.d(TAG, "Unregister SKIPPED: another owner ($activeEventHandlerOwnerId) registered the handler")
            return  // Another screen registered the handler, don't clear it
        }

        // Don't clear handler if this owner's job is currently executing
        if (executingOwner == ownerId && state.isGenerating) {
            DebugLogger.d(TAG, "Unregister SKIPPED: $ownerId's job is still executing")
            return
        }

        DebugLogger.d(TAG, "Unregister ACCEPTED: clearing handler for $ownerId")
        activeEventHandler = null
        activeEventHandlerOwnerId = null
    }

    /**
     * 已经向界面发放过完成事件的 promptId（v4.8.2 去重）。
     *
     * 同一个任务有三条完成路径都会走到 [dispatchEvent]：
     * 1. WS `executing` 事件里 `node == null`；
     * 2. WS `execution_success`；
     * 3. 断线/重启后的 `pollForCompletion` 轮询。
     *
     * 原实现每条路径都发一次 `ImageGenerated`，下游各 ViewModel 于是重复
     * `fetchGeneratedImage`（`isFetching` 反复 true/false → 按钮在"获取中"与
     * "添加到队列 (N)"之间跳变），`publishSkillText` 也重复拉 `/history` 并
     * `SkillTextResult.publish`（→ "已保存 N 张图片到…"弹窗反复弹出、需要点两次才能关）。
     * 同一个 promptId 的结果是唯一的，发一次就够。
     *
     * P1-3：三条路径可能来自不同线程（WS 协程在主线程、轮询回调在 OkHttp 线程），
     * 普通 String 的"读 → 比较 → 写"会让两条路径同时通过检查。改用 [AtomicReference]
     * + getAndSet：并发下只有一条能把值换成自己的 promptId，另一条读到相同值即跳过。
     */
    private val lastImageGeneratedPromptId = AtomicReference<String?>(null)

    /** 生成完成后的图库刷新防抖阀（多条完成路径共用一把，规则见 [GalleryRefreshThrottle]） */
    private val galleryRefreshThrottle = GalleryRefreshThrottle(GALLERY_REFRESH_DEBOUNCE_MS)

    /**
     * Dispatch event to active handler, falling back to SharedFlow.
     * If no handler is active OR handler doesn't match executing owner, buffer events.
     * Handler is invoked on the main thread to ensure proper UI state updates.
     *
     * P1-3 线程模型：
     * - **前置副作用**（完成去重、技能流/3D 结果发布、图库刷新）留在调用线程执行；
     * - **投递 / 入缓冲的判定与写入**统一经 [runOnMain] 串行到主线程
     *   （[deliverOrBuffer]）。`pendingCompletions` 等都是普通 `mutableMap`，
     *   原实现里 OkHttp 线程写、主线程读并删除，会出现丢条目/结构错乱；
     *   统一到主线程后由单写者保证一致性，`Handler.post` 提供入队前的写入可见性。
     * - `executingOwner` 必须**在调用线程先取好**再投递：延迟到主线程再读的话，
     *   `dispatchCompletionEvent` 紧随其后的 `resetGenerationState` 可能已经把
     *   `ownerId` 清空，完成事件就会丢（"轮询拿到了图但界面没反应"）。
     */
    private fun dispatchEvent(event: GenerationEvent) {
        // 技能流（提示词强化）不出图，结果是一段文本：在这里统一取回来发布给
        // SkillTextResultDialog。必须落在事件分发**之前** —— 两条完成路径（轮询 /
        // WebSocket 回调）都汇到这里，而各屏幕的处理器只认图片，技能流在那边是完全
        // 静默的（实测：服务器已产出 1860 字符提示词，界面上一片空白、用户以为没输出）。
        if (event is GenerationEvent.ImageGenerated) {
            // v4.8.2：同一任务只处理一次，打断"多路径重复完成"引发的界面抖动
            if (lastImageGeneratedPromptId.getAndSet(event.promptId) == event.promptId) {
                DebugLogger.d(TAG, "Skip duplicate ImageGenerated (${Obfuscator.promptId(event.promptId)})")
                return
            }

            publishSkillText(event.promptId)
            // 图生3D 的产物是 .glb：与技能流同理，两条完成路径都汇到这里，
            // 在分发前统一发布给 ThreeDResultHolder（常驻 3D 按钮的数据源）。
            // 只挂兜底路径的话，前台正常生成（有 handler）时按钮永远不会出现。
            publishThreeD(event.promptId)
        }

        // 生成成功完成 → 触发图库刷新（H5）。失败（Error）与取消（GenerationCancelled）
        // 不是这两类事件，天然不会走到这里；防抖与跨路径去重交给 [galleryRefreshThrottle]。
        // 两个分支分开写：Kotlin 的合并分支（is A, is B ->）推不出公共的 promptId 属性
        when (event) {
            is GenerationEvent.ImageGenerated ->
                maybeRefreshGalleryAfterGeneration(event.promptId)
            is GenerationEvent.VideoGenerated ->
                maybeRefreshGalleryAfterGeneration(event.promptId)
            else -> {}
        }

        // 先在调用线程把 owner 定格（见上方线程模型说明）
        val executingOwner = _generationState.value.ownerId
        runOnMain { deliverOrBuffer(event, executingOwner) }

        // Also emit to SharedFlow for backwards compatibility
        viewModelScope.launch { _events.emit(event) }
    }

    /**
     * 把动作串行到主线程执行（P1-3）。
     *
     * 已经在主线程时**同步执行**，不改变原有顺序语义（主线程上的 WS 事件仍然是即时投递）；
     * 否则 post 到主线程队列，由 [mainHandler] 提供 happens-before。
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 投递或缓冲一条事件（**只允许在主线程执行**）。
     *
     * @param executingOwner 调用方在事件产生那一刻读到的执行归属；null = 当时没有在执行业务，
     *   事件无处投递（保持原行为：只走 SharedFlow）
     */
    private fun deliverOrBuffer(event: GenerationEvent, executingOwner: String?) {
        val handler = activeEventHandler
        val eventName = event::class.simpleName

        // F5：预览与"清预览"是瞬时事件。复位之后 state 已被清空，此时再投递或缓冲
        // 上一轮的预览，界面会闪一帧旧画面；缓冲版更糟 —— 下次该 owner 注册 handler
        // 时会被当作"缓冲预览"再放一次。完成事件不走这条判定（理由见函数注释）。
        if (event is GenerationEvent.PreviewImage || event is GenerationEvent.ClearPreviewForResume) {
            val state = _generationState.value
            if (!isCurrentGenerationEvent(state.isGenerating, state.ownerId, executingOwner)) {
                DebugLogger.d(
                    TAG,
                    "丢弃上一轮生成的残留 $eventName（已复位，executingOwner=$executingOwner）"
                )
                return
            }
        }

        // Check if handler matches executing job owner
        val handlerMatchesExecuting = activeEventHandlerOwnerId == executingOwner

        // Only log non-preview events to avoid spam
        if (event !is GenerationEvent.PreviewImage) {
            DebugLogger.d(TAG, "dispatchEvent: $eventName, executingOwner=$executingOwner, " +
                    "handlerOwner=$activeEventHandlerOwnerId, hasHandler=${handler != null}, matches=$handlerMatchesExecuting")
        }

        // Only dispatch to handler if it matches the executing job's owner
        if (handler != null && handlerMatchesExecuting) {
            viewModelScope.launch { handler(event) }
            if (event is GenerationEvent.PreviewImage && executingOwner != null) {
                bufferedPreviews.remove(executingOwner)
            }
        } else {
            // No matching handler - buffer for later delivery to the correct owner
            // Use executingOwner as the key so this owner can retrieve it later
            if (executingOwner != null) {
                if (event !is GenerationEvent.PreviewImage) {
                    if (handler != null && !handlerMatchesExecuting) {
                        DebugLogger.d(TAG, "Handler mismatch, buffering $eventName for $executingOwner (current handler: $activeEventHandlerOwnerId)")
                    } else {
                        DebugLogger.d(TAG, "No handler, buffering $eventName for $executingOwner")
                    }
                }
                when (event) {
                    is GenerationEvent.PreviewImage -> bufferedPreviews[executingOwner] = event.bitmap
                    is GenerationEvent.ImageGenerated, is GenerationEvent.VideoGenerated -> {
                        pendingCompletions[executingOwner] = event
                        // Immediately fetch and store the result to ensure persistence
                        // even if the user doesn't return to this screen before app is killed
                        fetchAndStoreResultImmediately(event, executingOwner)
                    }
                    is GenerationEvent.ClearPreviewForResume -> pendingClearPreviews.add(executingOwner)
                    else -> {}
                }
            }
        }
    }

    /**
     * 生成成功完成后触发图库刷新（H5）。
     *
     * 以前图库只能靠 5 分钟一次的周期刷新看到新图 —— 用户生成完切到图库，
     * 刚出的图最长要等 5 分钟才出现。用 [GalleryRepository.refresh]（后台静默）
     * 而不是 manualRefresh()：新条目本来就不在任何媒体缓存里，不需要清缓存，
     * 也不该让图库弹"手动下拉刷新"的指示器。refresh() 自带并发保护与"无客户端跳过"，
     * 内部的 /history 拉取跑在 IO 线程，这里只是启动协程、不在主线程做 IO。
     */
    private fun maybeRefreshGalleryAfterGeneration(promptId: String) {
        if (galleryRefreshThrottle.shouldFire(promptId, System.currentTimeMillis())) {
            DebugLogger.d(TAG, "Refreshing gallery after generation (${Obfuscator.promptId(promptId)})")
            GalleryRepository.getInstance().refresh()
        }
    }

    /**
     * 技能流（提示词强化）的结果是文本而非图片：把服务器产出的那段文字取回来发布给
     * [SkillTextResult]，由 `SkillTextResultDialog` 弹窗展示。普通出图流的 outputs 里
     * 没有文本字段，[SkillTextResult.extract] 返回 null，这里就是一次安静的空操作。
     */
    private fun publishSkillText(promptId: String) {
        val client = comfyUIClient ?: return
        client.fetchHistory(promptId) { historyJson ->
            val outputs = historyJson?.optJSONObject(promptId)?.optJSONObject("outputs")
            SkillTextResult.publish(SkillTextResult.extract(outputs))
        }
    }

    /**
     * 图生3D（IDOL 流）的产物是 .glb 文件而非图片：把服务器 `/history` outputs 里的
     * `3d` 字段提取出来发布给 [ThreeDResultHolder]（常驻 3D 按钮的数据源）。
     * 普通出图流的 outputs 没有 `3d` 字段，[ThreeDResultHolder.extract]
     * 返回 null，这里就是一次安静的空操作。
     */
    private fun publishThreeD(promptId: String) {
        val client = comfyUIClient ?: return
        client.fetchHistory(promptId) { historyJson ->
            val outputs = historyJson?.optJSONObject(promptId)?.optJSONObject("outputs")
            // **只有真的解析出 3D 产物才发布**（v4.3.0 修复）。
            // 原写法无条件 publish(extract(...))，普通出图流 extract 返回 null 时
            // 等于 publish(null) —— 不是"空操作"，而是把已有 3D 结果**清空**：
            // 用户生成过 3D 后再用别的流出一次图，切回 3D 流模型就没了。
            ThreeDResultHolder.extract(outputs)?.let { ThreeDResultHolder.publish(it) }
        }
    }

    /**
     * Immediately fetch and store the generated result to ensure persistence.
     * Called when a completion event is dispatched but no handler is registered.
     * This ensures results are stored in MediaStateHolder even if the app is killed
     * before the user returns to the generating screen.
     */
    private fun fetchAndStoreResultImmediately(event: GenerationEvent, ownerId: String) {
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "Cannot fetch immediately: no client")
            return
        }
        val context = applicationContext ?: run {
            DebugLogger.w(TAG, "Cannot fetch immediately: no context")
            return
        }

        when (event) {
            is GenerationEvent.ImageGenerated -> {
                val mediaKey = when (ownerId) {
                    "TEXT_TO_IMAGE" -> MediaStateHolder.MediaKey.TtiPreview
                    "IMAGE_TO_IMAGE" -> MediaStateHolder.MediaKey.ItiPreview
                    else -> {
                        DebugLogger.w(TAG, "Unknown image owner: $ownerId")
                        return
                    }
                }
                DebugLogger.i(TAG, "Fetching image immediately for $ownerId (promptId=${Obfuscator.promptId(event.promptId)})")
                fetchAndStoreImage(client, context, event.promptId, mediaKey)
            }
            is GenerationEvent.VideoGenerated -> {
                val filePrefix = when (ownerId) {
                    "TEXT_TO_VIDEO" -> VideoUtils.FilePrefix.TEXT_TO_VIDEO
                    "IMAGE_TO_VIDEO" -> VideoUtils.FilePrefix.IMAGE_TO_VIDEO
                    else -> {
                        DebugLogger.w(TAG, "Unknown video owner: $ownerId")
                        return
                    }
                }
                DebugLogger.i(TAG, "Fetching video immediately for $ownerId (promptId=${Obfuscator.promptId(event.promptId)})")
                VideoUtils.fetchVideoFromHistory(context, client, event.promptId, filePrefix) { uri ->
                    if (uri != null) {
                        DebugLogger.i(TAG, "Immediate video fetch successful for $ownerId")
                    } else {
                        DebugLogger.w(TAG, "Immediate video fetch failed for $ownerId")
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Fetch image from server and store in MediaStateHolder.
     */
    private fun fetchAndStoreImage(
        client: ComfyUIClient,
        context: Context,
        promptId: String,
        mediaKey: MediaStateHolder.MediaKey
    ) {
        client.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    // 技能流（提示词强化）不出图：先把它那一段文本单独发布出去。
                    // 普通出图流的 outputs 里只有 images，这里是安静的空操作。
                    SkillTextResult.publish(SkillTextResult.extract(outputs))

                    // 3D 流（图生3D）：产物是 .glb 文件，写在独立的 `3d` 字段里 ——
                    // 下面的 images 分支找不到任何东西，不在这里接一手的话，
                    // 用户生成完在 App 里看到的会是"什么都没有"。
                    // 同样只在真的解析出 3D 产物时才发布（否则会把已有结果清空）。
                    ThreeDResultHolder.extract(outputs)?.let { ThreeDResultHolder.publish(it) }

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                                if (bitmap != null) {
                                    MediaStateHolder.putBitmap(mediaKey, bitmap, context)
                                    // 记下这张图属于哪个 promptId：屏幕侧稍后收到缓冲的完成事件时
                                    // 会再走一遍取图链路，靠这条记录就能确认缓存命中、不必重复下载
                                    MediaStateHolder.markImagePromptId(mediaKey, promptId)
                                    DebugLogger.i(TAG, "Immediate image fetch successful, stored to ${mediaKey::class.simpleName}")
                                } else if (failureType == ConnectionFailure.STALLED) {
                                    DebugLogger.w(TAG, "Immediate image fetch stalled")
                                    ConnectionManager.showConnectionAlert(context, failureType)
                                } else {
                                    DebugLogger.w(TAG, "Immediate image fetch failed: bitmap is null")
                                }
                            }
                            return@fetchHistory
                        }
                    }
                    DebugLogger.w(TAG, "Immediate image fetch: no images in history output")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Immediate image fetch failed: ${e.message}")
                }
            } else {
                DebugLogger.w(TAG, "Immediate image fetch: history is null")
            }
        }
    }

    /**
     * Start generation with the given workflow JSON
     * @param workflowJson The workflow JSON to submit
     * @param ownerId The ID of the screen/ViewModel that owns this generation (e.g., "TEXT_TO_IMAGE")
     * @param contentType The type of content being generated (IMAGE or VIDEO)
     * @param onResult Callback with result
     */
    fun startGeneration(
        workflowJson: String,
        ownerId: String,
        contentType: ContentType = ContentType.IMAGE,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        DebugLogger.i(TAG, "Starting generation (owner: $ownerId, type: $contentType${if (front) ", front" else ""})")

        // P1-4：单飞闸门。重复点击（连点、或"生成"与"添加到队首"同时按下）只放行第一次。
        //
        // **被拒时刻意不回调 onResult**：所有生成页的失败回调都是
        // `errorMessage ?: error_generation_failed` 弹 Toast，传 null 会弹一条
        // 名为"生成失败"的提示 —— 那是在指责用户误触，而误触本身不该产生任何提示。
        // 界面侧的「提交中」禁用态由 [isSubmitting] 承担（四个生成页已接入），
        // 这里只留一条调试日志，静默丢弃。
        //
        // R2-L4：拿到的令牌必须沿调用链传下去（finish / 看门狗 / 鉴权失败分支），
        // 释放带令牌，迟到的释放才不会误释下一次提交刚抢到的闸门。
        val ticket = submitGate.tryAcquire()
        if (ticket == null) {
            DebugLogger.d(TAG, "提交被拒：上一次提交仍在途，静默丢弃（owner=$ownerId）")
            return
        }
        // 先登记提交方 ownerId，再置 isSubmitting：两态都由页面收集，先 owner 后
        // isSubmitting 可避免"已提交但 owner 还没到"的一帧里忙碌皮肤闪回灰底。
        _submitOwnerId.value = ownerId
        _isSubmitting.value = true
        armSubmitGateWatchdog(ticket)

        // 释放闸门并回报结果：所有失败/异常路径都汇到这里，保证不会漏释放。
        val finish: (Boolean, String?, String?) -> Unit = { success, promptId, errorMessage ->
            releaseSubmitGate(ticket)
            onResult(success, promptId, errorMessage)
        }

        try {
            comfyUIClient ?: run {
                DebugLogger.e(TAG, "Cannot start generation: client not initialized")
                finish(false, null, applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized")
                return
            }

            val context = applicationContext ?: run {
                DebugLogger.e(TAG, "Cannot start generation: no application context")
                finish(false, null, "No application context")
                return
            }

            // Store the owner and content type before starting generation
            generationOwnerId = ownerId
            generationContentType = contentType

            // v4.6.1（模块 A4）：提交前参数完整性预检。
            // 放在 ensureConnection 之前：参数本身就不完整时，没必要再走一次网络往返；
            // 也避免"连接成功但必然 400"这种最让用户困惑的组合。
            WorkflowPreflight.check(workflowJson)?.let { problem ->
                DebugLogger.w(TAG, "Preflight rejected: $problem")
                finish(false, null, problem)
                return
            }

            // Ensure connection before starting - will try to reconnect if needed
            // Dialog is shown by ensureConnection if reconnection fails
            ConnectionManager.ensureConnection(context) { connected ->
                if (connected) {
                    submitWorkflow(workflowJson, front, finish, ticket)
                } else {
                    // Connection failed - dialog already shown by ensureConnection
                    // Don't show Toast (no error message) to avoid duplicate notifications
                    finish(false, null, null)
                }
            }
        } catch (e: Exception) {
            // 同步抛出的异常（例如 workflowJson 不是合法 JSON 时 submitPrompt 内部
            // JSONObject(...) 直接抛）也必须释放闸门，否则按钮永久卡在禁用态。
            DebugLogger.e(TAG, "提交前置阶段异常：${e.message}")
            finish(false, null, e.message)
        }
    }

    /**
     * 武装提交闸门的超时看门狗（P1-4）。
     *
     * 为什么需要：`ensureConnection` 在"另一个重连会话已经开始"的分支里会**不回调**
     * （见 `ConnectionManager.ensureConnection` 的 stale callback 分支），
     * 网络层也可能出现回调丢失。没有看门狗时，闸门一旦静默泄漏，生成按钮就永久禁用，
     * 用户只能杀进程。超时释放的代价是极端情况下可能漏过一次重复提交 —— 远小于按钮卡死。
     *
     * R2-L4：看门狗到期只释放**自己那次**的令牌。若期间闸门已经易主（旧提交正常结束、
     * 新提交又抢到），`isHeldBy` 为 false，这里什么都不做 —— 否则一次迟到的超时
     * 会把新提交的闸门捅开。
     */
    private fun armSubmitGateWatchdog(ticket: Long) {
        submitGateWatchdog?.cancel()
        submitGateWatchdog = viewModelScope.launch {
            delay(SUBMIT_GATE_TIMEOUT_MS)
            if (submitGate.isHeldBy(ticket)) {
                DebugLogger.w(TAG, "提交闸门超时（${SUBMIT_GATE_TIMEOUT_MS}ms），强制释放")
                releaseSubmitGate(ticket)
            }
        }
    }

    /**
     * 释放提交闸门（P1-4）：成功 / 失败 / 异常三路共用。
     *
     * R2-L4：**带令牌**释放。令牌过期（闸门已属于下一次提交）时直接忽略，连看门狗
     * 也不去取消 —— 那个看门狗属于新提交，取消掉会让新提交失去超时兜底。
     */
    private fun releaseSubmitGate(ticket: Long) {
        if (!submitGate.isHeldBy(ticket)) {
            DebugLogger.d(TAG, "忽略过期的提交闸门释放（ticket=$ticket）")
            return
        }
        submitGate.release(ticket)
        submitGateWatchdog?.cancel()
        submitGateWatchdog = null
        // 用闸门真实状态回写，而不是硬编码 false：极端时序下这里可能刚好又有人在途
        val stillInFlight = submitGate.isInFlight
        _isSubmitting.value = stillInFlight
        // v4.16.14（复核 BUSY-03）：闸门已空 → 清 owner；仍在途说明新提交已抢到闸门，
        // 保留现值（新提交紧接着会写入自己的 owner），避免把 B 页的忙碌皮肤清掉。
        if (!stillInFlight) {
            _submitOwnerId.value = null
        }
    }

    /**
     * Submit workflow to server
     *
     * @param ticket P1-4 的提交令牌：鉴权失败分支要显式释放它（该分支不能回调
     *   onResult，否则页面会叠一个通用失败 Toast）。
     */
    private fun submitWorkflow(
        workflowJson: String,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit,
        ticket: Long
    ) {
        val client = comfyUIClient ?: run {
            // 走到这里说明客户端在 ensureConnection 与提交之间被替换/清空：
            // 必须回报结果，否则 P1-4 的闸门要靠看门狗才能释放。
            onResult(
                false,
                null,
                applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized"
            )
            return
        }

        // P6：提交**发出之前**登记在途意图。promptId 只有成功响应里才有，弱网下响应可能
        // 永远到不了；先记下"发过一次提交"，等 execution_start 到达时按 FIFO 认领。
        val submitOwnerId = generationOwnerId
        val submitContentType = generationContentType
        val submissionToken = submitOwnerId?.let { owner ->
            JobRegistry.beginSubmission(
                workflowName = owner,
                ownerId = owner,
                contentType = submitContentType
            )
        }

        DebugLogger.d(TAG, "Submitting workflow to server")
        client.submitPrompt(workflowJson, front) { success, promptId, errorMessage, failureType ->
            if (success && promptId != null) {
                DebugLogger.i(TAG, "Workflow submitted successfully (promptId: ${Obfuscator.promptId(promptId)})")

                // Register job with JobRegistry for queue tracking.
                // P1-1：这里同时把"本机提交返回的 promptId"记进注册表，之后 execution_start
                // 的归属兜底只认这条记录，不再"查不到就当成自己的"。
                // P6：走 completeSubmission —— 它会先移除在途意图；若在途意图已被
                // execution_start 提前认领，则不重复登记。
                if (submissionToken != null && submitOwnerId != null) {
                    JobRegistry.completeSubmission(
                        token = submissionToken,
                        promptId = promptId,
                        ownerId = submitOwnerId,
                        contentType = submitContentType
                    )
                } else {
                    generationOwnerId?.let { ownerId ->
                        JobRegistry.registerJob(promptId, ownerId, generationContentType)
                    }
                }
                // Save JobRegistry state for persistence
                applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                // P4：提交成功即有「本机活跃任务」，趁着 App 可见把保活服务拉起来；
                // 等 execution_start 再拉可能已经切到后台（Android 12+ 不允许后台启动 FGS）。
                applicationContext?.let { GenerationKeepAliveService.ensureRunning(it) }

                // Determine if we should set up GenerationState now
                // There's a race condition: ExecutionStart might fire before we register the job.
                // Check both:
                // 1. Nothing executing (first job or queue was empty)
                // 2. This job is already executing (race: ExecutionStart fired before registration)
                val queueState = JobRegistry.queueState.value
                val isThisJobExecuting = queueState.executingPromptId == promptId
                val shouldSetState = isThisJobExecuting || !queueState.isExecuting

                if (shouldSetState) {
                    _generationState.value = GenerationState(
                        isGenerating = true,
                        promptId = promptId,
                        progress = 0,
                        maxProgress = 100,
                        ownerId = generationOwnerId,
                        contentType = generationContentType
                    )
                    // Save state immediately after starting
                    applicationContext?.let { saveGenerationState(it) }
                }
                // 服务器已经收下这次生成 —— 「每次+1 / 每次−1」到这一刻才该挪种子。
                // 按 ownerId 找回调：只有提交方那个页面该挪自己的种子。
                // 提交失败时挪的话，用户下一次会白白跳过一个种子（而且是无声的）
                generationOwnerId?.let { owner -> submittedCallbacks[owner]?.invoke() }
                mainHandler.post { onResult(true, promptId, null) }
            } else {
                DebugLogger.e(TAG, "Workflow submission failed: $errorMessage")

                // P6：响应失败，但 execution_start 可能早已把这个任务认领回来了
                //（弱网下 WS 比 HTTP 快，日志实测早 9 秒）。此时服务器确实收下并在跑，
                // 绝不能再报"提交超时"误导用户 —— 按提交成功处理，进度由后续 WS 事件推进。
                val claimedPromptId = submissionToken?.let { JobRegistry.findClaimedPromptId(it) }
                if (claimedPromptId != null) {
                    DebugLogger.i(
                        TAG,
                        "P6：提交响应失败，但任务已由 execution_start 接管（promptId: ${Obfuscator.promptId(claimedPromptId)}），不再上报提交失败"
                    )
                    generationOwnerId?.let { owner -> submittedCallbacks[owner]?.invoke() }
                    mainHandler.post { onResult(true, claimedPromptId, null) }
                    return@submitPrompt
                }

                // P6：区分"服务器可能已收下"与"明确未接收"。
                // 前者保留在途意图等认领；后者立即丢弃，避免误吃后续的 execution_start。
                if (submissionToken != null) {
                    if (submissionMayHaveReachedServer(failureType, errorMessage)) {
                        JobRegistry.markSubmissionUnresolved(submissionToken)
                    } else {
                        JobRegistry.abandonSubmission(submissionToken)
                    }
                }

                // For authentication errors, show the connection alert dialog instead of Toast
                if (failureType == ConnectionFailure.AUTHENTICATION) {
                    mainHandler.post {
                        // P1-4：鉴权失败的提示由弹窗承担，**不能**走 onResult
                        //（页面收到 !success 会再弹一个通用失败 Toast），
                        // 但闸门必须在这里显式释放，否则按钮要等看门狗才恢复。
                        // R2-L4：带本次提交的令牌 —— 这条回调可能迟到，届时闸门
                        // 已属于下一次提交，过期令牌会被忽略而不是误释。
                        releaseSubmitGate(ticket)
                        applicationContext?.let { ctx ->
                            ConnectionManager.showConnectionAlert(ctx, ConnectionFailure.AUTHENTICATION)
                        }
                    }
                } else {
                    mainHandler.post { onResult(false, null, errorMessage) }
                }
            }
        }
    }

    /**
     * P6：提交失败时判断"服务器是否**可能**已经收下了这次提交"。
     *
     * 无法从回调的 failureType 区分（ComfyUIClient 只给 NONE/AUTHENTICATION/NETWORK），
     * 所以按它自己产出的错误文案归类（这些前缀是 ComfyUIClient.kt 里的硬编码英文，
     * 不受系统语言影响，见 `submitPrompt`）：
     * - `Failed to submit prompt`：连接/读写超时、连接被重置等 IO 失败 —— 请求可能
     *   已经到达服务器，只是响应没回来 → 保留在途意图等 `execution_start` 认领；
     * - `Failed to parse response`：服务器回了 200，但响应体解析失败 —— 任务大概率已入队
     *   → 同样保留；
     * - 其余（`Server error 4xx/5xx`、未连接、认证失败）：明确未接收 → 丢弃意图。
     */
    private fun submissionMayHaveReachedServer(
        failureType: ConnectionFailure,
        errorMessage: String?
    ): Boolean {
        if (failureType == ConnectionFailure.AUTHENTICATION) return false
        val message = errorMessage.orEmpty()
        return message.startsWith("Failed to submit prompt") ||
            message.startsWith("Failed to parse response")
    }

    /**
     * Cancel the current generation
     *
     * v4.6.1 修复「取消错位」（模块 A2/A3）：
     * - ComfyUI 的 `/interrupt` **没有 promptId 参数**，只能中断服务器当前正在跑的那个。
     *   不校验归属就点取消，会误伤其他模式/其他客户端正在跑的任务，而自己的任务还在排队 ——
     *   这正是"点取消没成功、预览还被截断"的根因之一。
     * - 取消成功后必须同步 [JobRegistry]：原实现只清 GenerationState，`executingPromptId`
     *   仍指着被取消的任务，进度条/队列角标不回落，重启还会从 Prefs 恢复成幽灵任务。
     */
    fun cancelGeneration(onResult: (success: Boolean) -> Unit) {
        DebugLogger.i(TAG, "Cancelling generation")
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "Cannot cancel: client not initialized")
            onResult(false)
            return
        }

        val queue = JobRegistry.queueState.value
        val executingId = queue.executingPromptId
        val isOwnExecuting = executingId != null && queue.ownJobs.containsKey(executingId)
        val ownPendingIds = queue.ownJobs
            .filterValues { it.status == JobStatus.PENDING }
            .keys
        val mainHandler = Handler(Looper.getMainLooper())

        // 情形 1：服务器在跑别人的任务 → 拒绝，不打断别人的图
        if (executingId != null && !isOwnExecuting) {
            DebugLogger.w(TAG, "Cancel rejected: executing job is not ours")
            dispatchEvent(GenerationEvent.CancelRejected(R.string.generation_cancel_not_owner))
            mainHandler.post { onResult(false) }
            return
        }

        // 情形 2：自己没有在跑、也没有排队 → 无事可做
        if (executingId == null && ownPendingIds.isEmpty()) {
            DebugLogger.w(TAG, "Cancel rejected: no active job")
            dispatchEvent(GenerationEvent.CancelRejected(R.string.generation_cancel_no_active))
            mainHandler.post { onResult(false) }
            return
        }

        // 情形 3：本地指针说"没有在跑"，但排队集合里有任务
        //
        // v4.8.2：这里原先直接 clearQueue —— 但如果 executingPromptId 因为 WS 事件
        // 丢包/重连而滞后，服务器其实正跑着我们的任务，此时 clearQueue **只清排队、
        // 不停当前**，用户看到的就是"点了取消，电脑上任务照旧在跑"。
        // 所以先向服务器对一次队列，再决定走中断还是清排队。
        // （ComfyUI 没有"按 promptId 删除单个排队任务"的接口，只有 /queue {"clear": true}，
        //   单用户场景下清掉整个 pending 队列是可接受语义，但绝不误发 /interrupt 打别人。）
        if (executingId == null) {
            if (queue.totalQueueSize > 0) {
                DebugLogger.i(TAG, "Cancel: executing pointer stale, syncing with server first")
                ConnectionManager.pollQueueStatus()
                mainHandler.postDelayed({
                    val refreshed = JobRegistry.queueState.value
                    val freshExecuting = refreshed.executingPromptId
                    if (freshExecuting != null && refreshed.ownJobs.containsKey(freshExecuting)) {
                        interruptOwnJob(client, freshExecuting, mainHandler, onResult)
                    } else {
                        clearPendingOnly(client, ownPendingIds, mainHandler, onResult)
                    }
                }, QUEUE_SYNC_DELAY_MS)
            } else {
                clearPendingOnly(client, ownPendingIds, mainHandler, onResult)
            }
            return
        }

        // 情形 4：自己的任务正在跑 → 中断，并同步注册表
        interruptOwnJob(client, executingId, mainHandler, onResult)
    }

    /**
     * 中断当前执行的任务（v4.8.2 抽出）。
     *
     * **关键修复**：原实现在 `interruptExecution` 返回 false 时**也**会 `resetGenerationState()`
     * 并广播 `GenerationCancelled` —— 界面显示"已取消"，服务器上的任务却还在跑，
     * 用户看到的就是"取消没生效、后端继续出图、最后只拿到一张半成品"。
     * 现在失败时只提示，不动状态，让界面如实反映"取消失败"。
     */
    private fun interruptOwnJob(
        client: ComfyUIClient,
        executingId: String,
        mainHandler: Handler,
        onResult: (success: Boolean) -> Unit
    ) {
        client.interruptExecution { success ->
            if (success) {
                DebugLogger.i(TAG, "Generation cancelled successfully")
                JobRegistry.markCancelled(executingId)
                // 用户主动取消：撤掉进度通知并注销「取消」处理器（通知上已无任务可取消）
                applicationContext?.let { GenerationNotifier.cancel(it) }
                GenerationNotifier.registerCancelHandler(null)
                resetGenerationState()
                dispatchEvent(GenerationEvent.GenerationCancelled)
                // 主动与服务器对齐：/interrupt 之后 ComfyUI 会立刻抓起下一个 pending 任务，
                // 只等 WS 的 status 事件会让队列角标滞后几百毫秒到几秒
                mainHandler.postDelayed({ ConnectionManager.pollQueueStatus() }, QUEUE_SYNC_DELAY_MS)
            } else {
                DebugLogger.w(TAG, "Failed to cancel generation - state kept unchanged")
                dispatchEvent(GenerationEvent.CancelRejected(R.string.generation_cancel_failed))
            }
            mainHandler.post { onResult(success) }
        }
    }

    /** 只清掉自己排队中的任务（不动服务器上正在执行的那个） */
    private fun clearPendingOnly(
        client: ComfyUIClient,
        ownPendingIds: Set<String>,
        mainHandler: Handler,
        onResult: (success: Boolean) -> Unit
    ) {
        client.clearQueue { success ->
            if (success) {
                ownPendingIds.forEach { JobRegistry.removeJob(it) }
                // 清排队（可能连同中断当前任务）：撤通知 + 注销取消处理器
                applicationContext?.let { GenerationNotifier.cancel(it) }
                GenerationNotifier.registerCancelHandler(null)
                resetGenerationState()
                dispatchEvent(GenerationEvent.GenerationCancelled)
                mainHandler.postDelayed({ ConnectionManager.pollQueueStatus() }, QUEUE_SYNC_DELAY_MS)
            } else {
                dispatchEvent(GenerationEvent.CancelRejected(R.string.generation_cancel_failed))
            }
            mainHandler.post { onResult(success) }
        }
    }

    /**
     * 「清空队列」的完整语义（v4.8.3）：**停掉正在执行的任务 + 清空所有排队任务**。
     *
     * 原实现只调 `client.clearQueue`，而 ComfyUI 的 `/queue {"clear":true}` **只清排队、
     * 不动正在执行的那个** —— 用户点了"清空队列"，前两条排队没了、第一条照旧跑完，
     * 界面上队列计数还停在 (3)（因为本地 `ownJobs` 里的 PENDING 从来没被移除）。
     *
     * **顺序必须是"先清排队、再中断"**：反过来的话 `/interrupt` 一返回，ComfyUI 会立刻
     * 抓起下一个 pending 任务开始跑，等于永远停不干净。
     *
     * @param onResult true = 服务器已确认（清排队 + 需要时中断都成功）
     */
    fun clearQueueAndStopCurrent(onResult: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onResult(false)
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val queue = JobRegistry.queueState.value
        val executingId = queue.executingPromptId
        val isOwnExecuting = executingId != null && queue.ownJobs.containsKey(executingId)

        client.clearQueue { cleared ->
            if (!cleared) {
                DebugLogger.w(TAG, "clearQueueAndStopCurrent: server refused clear")
                mainHandler.post { onResult(false) }
                return@clearQueue
            }

            // 排队已清空，接下来停正在执行的那个（如果是我们自己的）
            if (isOwnExecuting && executingId != null) {
                client.interruptExecution { interrupted ->
                    if (interrupted) {
                        JobRegistry.markCancelled(executingId)
                        DebugLogger.i(TAG, "clearQueueAndStopCurrent: interrupted own job")
                    } else {
                        DebugLogger.w(TAG, "clearQueueAndStopCurrent: interrupt failed")
                    }
                    // 清空队列 + 停当前任务：撤通知 + 注销取消处理器
                    applicationContext?.let { GenerationNotifier.cancel(it) }
                    GenerationNotifier.registerCancelHandler(null)
                    purgeLocalPending()
                    resetGenerationState()
                    dispatchEvent(GenerationEvent.GenerationCancelled)
                    mainHandler.postDelayed({ ConnectionManager.pollQueueStatus() }, QUEUE_SYNC_DELAY_MS)
                    mainHandler.post { onResult(interrupted) }
                }
            } else {
                purgeLocalPending()
                mainHandler.postDelayed({ ConnectionManager.pollQueueStatus() }, QUEUE_SYNC_DELAY_MS)
                mainHandler.post { onResult(true) }
            }
        }
    }

    /**
     * 把本地登记里所有"排队中"的任务摘掉。
     *
     * 按钮文案由 `QueueState.activeCountFor(ownerId)` 派生，它统计的是 `ownJobs` 里
     * PENDING/EXECUTING 的条数 —— 服务器队列清空了但这里不清，角标就会一直显示旧数字。
     * 正在执行的那条不在这里移除（由 [JobRegistry.markCancelled] 单独处理）。
     */
    private fun purgeLocalPending() {
        JobRegistry.queueState.value.ownJobs
            .filterValues { it.status == JobStatus.PENDING }
            .keys
            .forEach { JobRegistry.removeJob(it) }
    }

    /**
     * Complete generation (called after image is fetched)
     * @param promptId Optional promptId to verify - only resets if matches current generation.
     *                 This prevents late completion calls from clearing state for a different job.
     */
    override fun completeGeneration(promptId: String?) {
        val currentPromptId = _generationState.value.promptId

        // If promptId specified, only reset if it matches current generation
        // This prevents race condition where a late completion clears a new job's state
        if (promptId != null && promptId != currentPromptId) {
            DebugLogger.d(TAG, "Ignoring completion for old job (${Obfuscator.promptId(promptId)}) - current is (${Obfuscator.promptId(currentPromptId ?: "")})")
            return
        }

        DebugLogger.i(TAG, "Generation completed")
        resetGenerationState()
    }

    /**
     * Reset generation state
     * Clears transient buffers (previews, clear flags) but preserves pending completions
     * since they need to survive until the handler registers to receive them
     */
    private fun resetGenerationState() {
        stopCompletionPolling()

        // Clear transient buffers (previews can be discarded, completion events cannot)
        //
        // P1-3：复位可能从 OkHttp 线程进入（轮询的 handleJobLost / 完成回调），
        // 而这两个容器只允许主线程改（见字段声明处的线程约束），所以清理也走 runOnMain。
        val currentOwner = generationOwnerId
        if (currentOwner != null) {
            runOnMain {
                // DON'T clear pendingCompletions - they must survive until handler retrieves them
                bufferedPreviews.remove(currentOwner)
                pendingClearPreviews.remove(currentOwner)
            }
        }

        _generationState.value = GenerationState()
        generationOwnerId = null
        // Clear persisted state to prevent stale "generating" state on app restart
        applicationContext?.let { saveGenerationState(it) }
    }

    /**
     * Start polling for generation completion.
     * Used when resuming a generation after app restart, since WebSocket
     * won't receive progress updates for jobs submitted with a different client ID.
     */
    private fun startCompletionPolling(promptId: String, contentType: ContentType) {
        if (completionPollingJob?.isActive == true) return
        // 新一轮轮询从零开始计数：上一个任务残留的 miss 数不能算到这次头上
        pollMissCount = 0

        completionPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(COMPLETION_POLLING_INTERVAL_MS)
                if (!isActive) break
                pollForCompletion(promptId, contentType)
            }
        }
    }

    /**
     * Stop polling for generation completion
     */
    private fun stopCompletionPolling() {
        completionPollingJob?.cancel()
        completionPollingJob = null
    }

    /**
     * Poll the server to check if generation has completed
     */
    private fun pollForCompletion(promptId: String, contentType: ContentType) {
        val client = comfyUIClient ?: return

        client.fetchHistory(promptId) { historyJson ->
            // 网络层拿不到结果（超时/断线）：既不算完成也不算丢失，直接等下一轮。
            // 这条必须排在最前面 —— 否则一次网络抖动就会被当成"任务丢了"。
            if (historyJson == null) return@fetchHistory

            val entry = historyJson.optJSONObject(promptId)
            val outputs = entry?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                pollMissCount = 0
                val event = if (contentType == ContentType.VIDEO) {
                    GenerationEvent.VideoGenerated(promptId)
                } else {
                    GenerationEvent.ImageGenerated(promptId)
                }
                dispatchEvent(event)
                completeGeneration(promptId)
                // 轮询是 WebSocket 的兜底通道：走这条路完成时也要清 JobRegistry，
                // 否则 ownJobs / executingOwnerId 会一直挂着这个任务 —— 界面上的
                // "谁在生成"就停在错的页面，队列角标也不会回落（P2 修）。
                JobRegistry.markCompleted(promptId)
                return@fetchHistory
            }

            // 服务端在 history 里明确写了失败（status=error）：不用等超时，立刻收尾。
            // 这条只有轮询能看到 —— WebSocket 的 execution_error 在断线/重启时收不到，
            // 而轮询正是为那种场景准备的。
            val statusStr = entry?.optJSONObject("status")?.optString("status_str").orEmpty()
            if (statusStr == "error") {
                pollMissCount = 0
                handleJobLost(
                    promptId,
                    applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                )
                return@fetchHistory
            }

            // 既没产物也没报错：可能还在排队/执行，也可能服务端已经不认这个 prompt 了。
            // 查一次队列分辨这两种情况：
            //   · 还在队列里 → 计数清零，继续等（排队、慢任务都走这条）；
            //   · 不在队列 → 累计"疑似丢失"，连续 N 次就判定丢失并复位界面。
            // checkIfPromptInQueue 在拿不到队列时会保守地回调 true，所以网络抖动不会误判。
            checkIfPromptInQueue(client, promptId) { inQueue ->
                if (inQueue) {
                    pollMissCount = 0
                } else {
                    pollMissCount++
                    if (pollMissCount >= MAX_POLL_MISSES_WITHOUT_QUEUE) {
                        handleJobLost(
                            promptId,
                            applicationContext?.getString(R.string.error_job_lost)
                                ?: "Task lost: the server no longer has this job. Please generate again."
                        )
                    }
                }
            }
        }
    }

    /**
     * 「任务已经不在服务器上了」的统一收尾。
     *
     * 两个触发条件：history 里明确报错，或连续 [MAX_POLL_MISSES_WITHOUT_QUEUE] 次
     * 既查不到产物、又不在队列里。后者是兜底里的兜底 —— 服务端重启 / 队列被清时，
     * 没有它界面会**永远**停在"正在生成"，进度条一直转，用户只能手动取消。
     *
     * dispatchEvent 必须在 resetGenerationState **之前**：dispatchEvent 靠 ownerId
     * 匹配页面 handler，reset 会把 ownerId 清掉（与 ExecutionError 分支同一条约束）。
     */
    private fun handleJobLost(promptId: String, message: String) {
        DebugLogger.w(
            TAG,
            "Job lost after $pollMissCount polls: ${Obfuscator.promptId(promptId)}"
        )
        pollMissCount = 0
        // 后台时把进度通知换成失败横幅（前台时撤掉）；注销通知「取消」处理器
        val notifyContext = applicationContext
        if (notifyContext != null) {
            GenerationNotifier.onGenerationFailed(
                notifyContext,
                generationOwnerId,
                message
            )
        }
        GenerationNotifier.registerCancelHandler(null)
        dispatchEvent(GenerationEvent.Error(message))
        JobRegistry.markFailed(promptId)
        resetGenerationState()
    }

    /**
     * Save generation state to SharedPreferences
     */
    fun saveGenerationState(context: Context) {
        val state = _generationState.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_GENERATING, state.isGenerating)
            putString(PREF_CURRENT_PROMPT_ID, state.promptId)
            putString(PREF_OWNER_ID, state.ownerId)
            putString(PREF_CONTENT_TYPE, state.contentType.name)
            apply()
        }
    }

    /**
     * Check server for generation completion.
     * Called when returning from background or reconnecting after connection loss.
     * Queries the server's history API to see if the generation completed while we were away.
     *
     * 处置按 [resolveReconcileOutcome] 四分：有产物 → 完成收尾；history 明确报错 →
     * 失败收尾（提示 + markFailed）；还在队列 → 继续轮询；都不满足 → 任务丢失，同样
     * 按失败收尾（原实现是静默 reset，界面无提示——2026-11 修复）。
     */
    fun checkServerForCompletion(onResult: (completed: Boolean, promptId: String?) -> Unit = { _, _ -> }) {
        val state = _generationState.value
        if (!state.isGenerating || state.promptId == null) {
            onResult(false, null)
            return
        }

        val client = comfyUIClient ?: run {
            onResult(false, null)
            return
        }

        client.fetchHistory(state.promptId) { historyJson ->
            if (historyJson == null) {
                // History fetch failed - check queue to detect stale state
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        // Not in queue - retry history check in case it just completed
                        client.fetchHistory(state.promptId) { retryHistoryJson ->
                            val retryEntry = retryHistoryJson?.optJSONObject(state.promptId)
                            val retryOutputs = retryEntry?.optJSONObject("outputs")
                            val retryStatusStr = retryEntry?.optJSONObject("status")
                                ?.optString("status_str").orEmpty()
                            // 重试那一次给了「刚跑完还没落 history」的宽限；第二次仍查不到
                            // 且不在队列，就只能是失败或丢失了 —— 原实现这里静默
                            // resetGenerationState()，进度条无声消失且不留任何提示。
                            when (
                                resolveReconcileOutcome(
                                    outputsPresent = retryOutputs != null && retryOutputs.length() > 0,
                                    historyStatusStr = retryStatusStr,
                                    inQueue = false
                                )
                            ) {
                                ReconcileOutcome.COMPLETED -> {
                                    dispatchCompletionEvent(state.promptId, state.contentType)
                                    onResult(true, state.promptId)
                                }
                                ReconcileOutcome.FAILED_REPORTED -> {
                                    handleJobLost(
                                        state.promptId,
                                        applicationContext?.getString(R.string.error_generation_failed)
                                            ?: "Generation failed"
                                    )
                                    onResult(false, null)
                                }
                                else -> {
                                    handleJobLost(
                                        state.promptId,
                                        applicationContext?.getString(R.string.error_job_lost)
                                            ?: "Task lost: the server no longer has this job. Please generate again."
                                    )
                                    onResult(false, null)
                                }
                            }
                        }
                    }
                }
                return@fetchHistory
            }

            val entry = historyJson.optJSONObject(state.promptId)
            val outputs = entry?.optJSONObject("outputs")
            val statusStr = entry?.optJSONObject("status")?.optString("status_str").orEmpty()

            // 断线期间失败的任务在 history 里有条目（status_str=error）但 outputs 为空。
            // 原实现不查 status_str：这类任务会落进 else 分支查队列 → 失败任务已不在
            // 队列 → 静默 resetGenerationState()，用户得不到任何失败提示。现在显式判定。
            when (
                resolveReconcileOutcome(
                    outputsPresent = outputs != null && outputs.length() > 0,
                    historyStatusStr = statusStr,
                    inQueue = false
                )
            ) {
                ReconcileOutcome.COMPLETED -> {
                    dispatchCompletionEvent(state.promptId, state.contentType)
                    onResult(true, state.promptId)
                }
                ReconcileOutcome.FAILED_REPORTED -> {
                    handleJobLost(
                        state.promptId,
                        applicationContext?.getString(R.string.error_generation_failed)
                            ?: "Generation failed"
                    )
                    onResult(false, null)
                }
                else -> {
                    // Not in history with outputs - check if still in queue
                    checkIfPromptInQueue(client, state.promptId) { inQueue ->
                        when (
                            resolveReconcileOutcome(
                                outputsPresent = false,
                                historyStatusStr = statusStr,
                                inQueue = inQueue
                            )
                        ) {
                            ReconcileOutcome.STILL_RUNNING -> {
                                dispatchEvent(GenerationEvent.ClearPreviewForResume)
                                startCompletionPolling(state.promptId, state.contentType)
                                onResult(false, state.promptId)
                            }
                            else -> {
                                // 不在队列也不在 history：任务已丢失（服务器重启/队列被清）。
                                // 原实现静默 resetGenerationState() —— 改为与
                                // pollForCompletion 同口径的 handleJobLost（提示 + markFailed）。
                                handleJobLost(
                                    state.promptId,
                                    applicationContext?.getString(R.string.error_job_lost)
                                        ?: "Task lost: the server no longer has this job. Please generate again."
                                )
                                onResult(false, null)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper to dispatch completion event and clean up state
     */
    private fun dispatchCompletionEvent(promptId: String, contentType: ContentType) {
        val event = if (contentType == ContentType.VIDEO) {
            GenerationEvent.VideoGenerated(promptId)
        } else {
            GenerationEvent.ImageGenerated(promptId)
        }
        // 后台时把进度通知更新为「已完成」（点击回 App）；前台时撤掉进度条。
        // 完成即注销通知「取消」处理器 —— 通知上已经没有可取消的东西了。
        val notifyContext = applicationContext
        if (notifyContext != null) {
            GenerationNotifier.onGenerationCompleted(notifyContext, generationOwnerId)
        }
        GenerationNotifier.registerCancelHandler(null)
        dispatchEvent(event)
        completeGeneration(promptId)  // Pass promptId to prevent clearing state for a different job
        // Clear JobRegistry state - ensures executingOwnerId is reset even when
        // WebSocket events were missed (app was in background during completion)
        JobRegistry.markCompleted(promptId)
    }

    /**
     * Check if a prompt is in the ComfyUI queue (either running or pending)
     */
    private fun checkIfPromptInQueue(
        client: ComfyUIClient,
        promptId: String,
        callback: (inQueue: Boolean) -> Unit
    ) {
        client.fetchQueue { queueJson ->
            if (queueJson == null) {
                // Couldn't fetch queue, assume still running to be safe
                callback(true)
                return@fetchQueue
            }

            // Check queue_running array: [[number, prompt_id, {...}], ...]
            val queueRunning = queueJson.optJSONArray("queue_running")
            if (queueRunning != null) {
                for (i in 0 until queueRunning.length()) {
                    val entry = queueRunning.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            // Check queue_pending array: [[number, prompt_id, {...}], ...]
            val queuePending = queueJson.optJSONArray("queue_pending")
            if (queuePending != null) {
                for (i in 0 until queuePending.length()) {
                    val entry = queuePending.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            callback(false)
        }
    }

    /**
     * Restore generation state from SharedPreferences
     */
    private fun restoreGenerationState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isGenerating = prefs.getBoolean(PREF_IS_GENERATING, false)
        val promptId = prefs.getString(PREF_CURRENT_PROMPT_ID, null)
        val ownerId = prefs.getString(PREF_OWNER_ID, null)
        val contentTypeName = prefs.getString(PREF_CONTENT_TYPE, null)
        val contentType = try {
            contentTypeName?.let { ContentType.valueOf(it) } ?: ContentType.IMAGE
        } catch (e: IllegalArgumentException) {
            ContentType.IMAGE
        }

        if (isGenerating && promptId != null) {
            generationOwnerId = ownerId
            generationContentType = contentType
            _generationState.value = GenerationState(
                isGenerating = true,
                promptId = promptId,
                ownerId = ownerId,
                contentType = contentType
            )
            // 重启恢复的生成态：登记通知快照（进后台时可展示），并接回「取消」处理器
            GenerationNotifier.onGenerationStarted(context, ownerId)
            GenerationNotifier.registerCancelHandler { cancelGeneration { _ -> } }
            // P4：恢复出的任务同样需要保活（打开 App 这一刻是可见的，启动合法）
            GenerationKeepAliveService.ensureRunning(context)
        }
    }

    /**
     * Logout - close connections and reset state
     */
    fun logout() {
        // Clear JobRegistry persisted state
        applicationContext?.let {
            JobRegistry.clearPersistedState(it)
            GenerationNotifier.cancel(it)
        }
        GenerationNotifier.registerCancelHandler(null)

        // Logout from server (sets flag to prevent auto-reconnect, clears all caches including JobRegistry)
        ConnectionManager.logout()

        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketMessageJob?.cancel()
        webSocketStateJob?.cancel()
        // 本实例持有通知「取消」处理器的话一并释放（多实例共存时防止转发给已销毁实例）
        if (generationOwnerId != null) {
            GenerationNotifier.registerCancelHandler(null)
        }
        // WebSocket is managed by ConnectionManager, don't close it here
        // as other ViewModels may still need it
    }
}
