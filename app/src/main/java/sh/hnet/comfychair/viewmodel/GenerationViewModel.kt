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
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.prompt.SkillTextResult
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.workflow.WorkflowPreflight

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
 * Content type for generation (determines which event to dispatch on completion)
 */
enum class ContentType {
    IMAGE,
    VIDEO
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
 * Central ViewModel for managing generation state.
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket connection and broadcasts parsed events
 * - This ViewModel subscribes to ConnectionManager.webSocketMessages SharedFlow
 * - Multiple instances can safely coexist (e.g., MainContainer + GalleryContainer)
 * - Only the instance whose ownerId matches the generation state processes events
 */
class GenerationViewModel : ViewModel() {

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
    fun unregisterSubmittedCallback(ownerId: String) {
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

    // One-time events
    private val _events = MutableSharedFlow<GenerationEvent>()
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    // Active event handler for screen-specific event delivery
    private var activeEventHandler: ((GenerationEvent) -> Unit)? = null
    private var activeEventHandlerOwnerId: String? = null  // Track who registered the handler
    private var generationOwnerId: String? = null

    // Per-owner buffered events (keyed by ownerId)
    // This prevents events from one owner overwriting another's when jobs overlap
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
                        // Only show dialog if actively generating - otherwise user doesn't need to know
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
                if (message.promptId == currentState.promptId || message.promptId == null) {
                    // Dispatch error event BEFORE resetting state, so ownerId is still available
                    // for handler matching in dispatchEvent()
                    val errorMessage = applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                    dispatchEvent(GenerationEvent.Error(errorMessage))
                    resetGenerationState()
                }
            }
            // When a job starts executing, update generation state to track it
            is WebSocketMessage.ExecutionStart -> {
                val promptId = message.promptId
                if (promptId.isNotEmpty()) {
                    // Look up job info from JobRegistry
                    var owner = JobRegistry.findOwner(promptId)
                    var contentType = JobRegistry.findContentType(promptId)

                    // Fallback: If not found in JobRegistry but we have a pending submission,
                    // use the pending owner/contentType (execution_start arrived before registerJob completed)
                    if (owner == null && generationOwnerId != null) {
                        val pendingOwner = generationOwnerId!!
                        val pendingContentType = generationContentType
                        owner = pendingOwner
                        contentType = pendingContentType
                        DebugLogger.w(TAG, "ExecutionStart arrived before registerJob, using pending owner: $pendingOwner")

                        // Register the job now since we know the promptId
                        JobRegistry.registerJob(promptId, pendingOwner, pendingContentType)
                        applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                    }

                    if (owner != null && contentType != null) {
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
    fun unregisterEventHandler(ownerId: String) {
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
     * Dispatch event to active handler, falling back to SharedFlow.
     * If no handler is active OR handler doesn't match executing owner, buffer events.
     * Handler is invoked on the main thread to ensure proper UI state updates.
     */
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
     */
    private var lastImageGeneratedPromptId: String? = null

    private fun dispatchEvent(event: GenerationEvent) {
        // 技能流（提示词强化）不出图，结果是一段文本：在这里统一取回来发布给
        // SkillTextResultDialog。必须落在事件分发**之前** —— 两条完成路径（轮询 /
        // WebSocket 回调）都汇到这里，而各屏幕的处理器只认图片，技能流在那边是完全
        // 静默的（实测：服务器已产出 1860 字符提示词，界面上一片空白、用户以为没输出）。
        if (event is GenerationEvent.ImageGenerated) {
            // v4.8.2：同一任务只处理一次，打断"多路径重复完成"引发的界面抖动
            if (event.promptId == lastImageGeneratedPromptId) {
                DebugLogger.d(TAG, "Skip duplicate ImageGenerated (${Obfuscator.promptId(event.promptId)})")
                return
            }
            lastImageGeneratedPromptId = event.promptId

            publishSkillText(event.promptId)
            // 图生3D 的产物是 .glb：与技能流同理，两条完成路径都汇到这里，
            // 在分发前统一发布给 ThreeDResultHolder（常驻 3D 按钮的数据源）。
            // 只挂兜底路径的话，前台正常生成（有 handler）时按钮永远不会出现。
            publishThreeD(event.promptId)
        }

        val handler = activeEventHandler
        val state = _generationState.value
        val eventName = event::class.simpleName
        val executingOwner = state.ownerId

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
        // Also emit to SharedFlow for backwards compatibility
        viewModelScope.launch { _events.emit(event) }
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

    // Content type for current generation (used in submitWorkflow)
    private var generationContentType: ContentType = ContentType.IMAGE

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
        comfyUIClient ?: run {
            DebugLogger.e(TAG, "Cannot start generation: client not initialized")
            onResult(false, null, applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized")
            return
        }

        val context = applicationContext ?: run {
            DebugLogger.e(TAG, "Cannot start generation: no application context")
            onResult(false, null, "No application context")
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
            onResult(false, null, problem)
            return
        }

        // Ensure connection before starting - will try to reconnect if needed
        // Dialog is shown by ensureConnection if reconnection fails
        ConnectionManager.ensureConnection(context) { connected ->
            if (connected) {
                submitWorkflow(workflowJson, front, onResult)
            } else {
                // Connection failed - dialog already shown by ensureConnection
                // Don't show Toast (no error message) to avoid duplicate notifications
                onResult(false, null, null)
            }
        }
    }

    /**
     * Submit workflow to server
     */
    private fun submitWorkflow(
        workflowJson: String,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val client = comfyUIClient ?: return

        DebugLogger.d(TAG, "Submitting workflow to server")
        val mainHandler = Handler(Looper.getMainLooper())
        client.submitPrompt(workflowJson, front) { success, promptId, errorMessage, failureType ->
            if (success && promptId != null) {
                DebugLogger.i(TAG, "Workflow submitted successfully (promptId: ${Obfuscator.promptId(promptId)})")

                // Register job with JobRegistry for queue tracking
                generationOwnerId?.let { ownerId ->
                    JobRegistry.registerJob(promptId, ownerId, generationContentType)
                    // Save JobRegistry state for persistence
                    applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                }

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
                // For authentication errors, show the connection alert dialog instead of Toast
                if (failureType == ConnectionFailure.AUTHENTICATION) {
                    mainHandler.post {
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
    fun completeGeneration(promptId: String? = null) {
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
        val currentOwner = generationOwnerId
        if (currentOwner != null) {
            // DON'T clear pendingCompletions - they must survive until handler retrieves them
            bufferedPreviews.remove(currentOwner)
            pendingClearPreviews.remove(currentOwner)
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
     * If not in history, checks the queue to determine if still running or truly stale.
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
                            val retryOutputs = retryHistoryJson?.optJSONObject(state.promptId)?.optJSONObject("outputs")
                            if (retryOutputs != null && retryOutputs.length() > 0) {
                                dispatchCompletionEvent(state.promptId, state.contentType)
                                onResult(true, state.promptId)
                            } else {
                                resetGenerationState()
                                onResult(false, null)
                            }
                        }
                    }
                }
                return@fetchHistory
            }

            val outputs = historyJson.optJSONObject(state.promptId)?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                dispatchCompletionEvent(state.promptId, state.contentType)
                onResult(true, state.promptId)
            } else {
                // Not in history with outputs - check if still in queue
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        resetGenerationState()
                        onResult(false, null)
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
        }
    }

    /**
     * Logout - close connections and reset state
     */
    fun logout() {
        // Clear JobRegistry persisted state
        applicationContext?.let { JobRegistry.clearPersistedState(it) }

        // Logout from server (sets flag to prevent auto-reconnect, clears all caches including JobRegistry)
        ConnectionManager.logout()

        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketMessageJob?.cancel()
        webSocketStateJob?.cancel()
        // WebSocket is managed by ConnectionManager, don't close it here
        // as other ViewModels may still need it
    }
}
