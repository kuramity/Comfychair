package sh.hnet.comfychair.connection

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import sh.hnet.comfychair.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import java.util.UUID

/**
 * Connection state representing whether the app is connected to a ComfyUI server.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connected(
        val serverId: String,
        val hostname: String,
        val port: Int,
        val protocol: String,
        val clientId: String
    ) : ConnectionState()
    /** Offline mode - using cached data without actual server connection */
    data class OfflineConnected(
        val serverId: String
    ) : ConnectionState()
}

/**
 * WebSocket 二进制预览帧的策略（P1-27）。
 *
 * 独立成顶层对象（而不是挂在 [ConnectionManager] 里）是为了让 JVM 单测能直接调用，
 * 不必初始化整个连接管理器（它会建协程作用域、状态流等）。
 */
internal object PreviewFramePolicy {

    /** 预览图片数据的字节上限：超过直接丢弃（见 ConnectionManager.MAX_PREVIEW_BYTES 的说明） */
    const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024

    /** 解码后的最大边长：预览只用于进度展示，1024 已足够清晰 */
    const val DECODE_DIMENSION = 1024

    /** 去掉 8 字节帧头后的图片数据是否在可解码范围内 */
    fun shouldDecode(payloadBytes: Int, maxBytes: Int = MAX_PREVIEW_BYTES): Boolean =
        payloadBytes in 1..maxBytes
}

/**
 * Central manager for ComfyUI server connection.
 *
 * This singleton provides:
 * - Single source of truth for connection state
 * - Shared ComfyUIClient instance for all components
 * - Centralized WebSocket lifecycle management with event broadcasting
 * - Automatic cache invalidation on disconnect
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket listener and broadcasts parsed events via SharedFlow
 * - Multiple GenerationViewModels can subscribe without conflict
 * - Handles keepalive pings and automatic reconnection
 *
 * Usage:
 * 1. Call [connect] after successful connection test in LoginScreen
 * 2. Call [openWebSocket] to establish WebSocket connection
 * 3. Subscribe to [webSocketMessages] for parsed events
 * 4. Subscribe to [webSocketState] for connection status
 * 5. Call [disconnect] on logout or connection change
 *
 * B4-W2 委托拆分：模型列表子系统（节点注册表 / 模型缓存 / `/object_info` 网络装配 /
 * 列表提取）已整体迁入 [ModelListCatalog]（取证批次 E/T/V）。本类保留该子系统的
 * 对外 API 并转发（签名不变，全仓调用方零改动）；会话侧资源（client / context /
 * serverId）在调用点显式传给 catalog，连接建立 / 断开时调 [ModelListCatalog.bumpGeneration]
 * 作废旧会话在飞的装配结果。
 */
object ConnectionManager {
    private const val TAG = "Connection"

    /** P2 观测日志：写入应用内缓冲，并在调试日志开启时同步到 logcat。 */
    private fun logWsInfo(message: String) {
        DebugLogger.i(TAG, message)
        if (DebugLogger.isEnabled()) android.util.Log.i("ComfyChair", message)
    }

    private fun logWsError(message: String) {
        DebugLogger.e(TAG, message)
        if (DebugLogger.isEnabled()) android.util.Log.e("ComfyChair", message)
    }

    /**
     * 应用层心跳间隔（发送 `{"type":"ping"}`）。
     *
     * v4.18.x 后台省流量：前台维持 30 秒现状；进入降级态（小窗失焦 / 真后台）由
     * [sh.hnet.comfychair.lifecycle.BackgroundDegradationCoordinator] 通过
     * [updateKeepaliveInterval] 放宽（UNMETERED 2 分钟 / 蜂窝 4 分钟，配合 OkHttp
     * 协议层 pingInterval 同步放宽，见 ComfyUIClient.updateWebSocketPingInterval）。
     */
    private const val KEEPALIVE_INTERVAL_MS = 30000L

    /** 当前生效的心跳间隔（@Volatile：写侧在协调器协程，读侧在心跳协程循环） */
    @Volatile
    private var keepaliveIntervalMs = KEEPALIVE_INTERVAL_MS

    /** 当前 WebSocket 的建立时刻（elapsedRealtime）；P2 仅用于观测连接存活时长。 */
    @Volatile
    private var connectedAtElapsedMs = 0L

    /** 当前连接已存活多久；从未建立或已经关闭时返回 0。 */
    private fun connectedElapsedMs(): Long =
        if (connectedAtElapsedMs > 0L) SystemClock.elapsedRealtime() - connectedAtElapsedMs else 0L

    /**
     * 后台降级是否已挂起 WS（与 [reconnectBlocked] 分开的独立标记）：
     * 只有降级挂起自己置的 reconnectBlocked 才允许在回前台时解除 ——
     * 登出 / 认证失败设下的阻断不受回前台影响。
     */
    @Volatile
    private var backgroundSuspended = false

    /** 重连退避封顶：2s→4s→8s→16s→30s，之后保持 30s 一次，直到连上或被显式阻断。 */
    private const val MAX_RECONNECT_BACKOFF_MS = 30_000L

    /** 传输停滞横幅的自动消失时长（P3）：不是断线，不能一直挂在顶部。 */
    private const val NOTICE_AUTO_DISMISS_MS = 6_000L

    /**
     * WS 二进制预览的**字节上限**（P1-27）。
     *
     * 二进制帧 = 8 字节头 + 图片数据。修复前对图片数据无任何上限，
     * 直接 `decodeByteArray` 全尺寸展开：服务端在放大类工作流下推送的预览可达几十 MB，
     * 几千像素的图一解码就是上百 MB 位图 —— 内存不足时整个进程被系统杀掉。
     *
     * 预览只用于生成过程中的进度展示，8MB 压缩数据足够覆盖 1024 长边的高质量图；
     * 超过上限的帧直接丢弃（下一帧马上会到），不值得为它冒 OOM 风险。
     */
    internal const val MAX_PREVIEW_BYTES = PreviewFramePolicy.MAX_PREVIEW_BYTES

    /**
     * 预览解码后的最大边长（P1-27）：预览显示区域最大也就全屏宽度量级，
     * 1024 足够清晰；按 2 的幂采样比原图省一个数量级的内存。
     */
    internal const val PREVIEW_DECODE_DIMENSION = 1024

    /** 二进制帧头长度（ComfyUI 自定义预览协议） */
    private const val BINARY_HEADER_BYTES = 8

    /**
     * 二进制预览帧是否值得解码（P1-27 纯判定，便于单测）。
     *
     * @param payloadBytes 去掉 8 字节头之后的图片数据长度
     */
    internal fun shouldDecodePreview(
        payloadBytes: Int,
        maxBytes: Int = MAX_PREVIEW_BYTES
    ): Boolean = PreviewFramePolicy.shouldDecode(payloadBytes, maxBytes)

    // Connection state
    // B4.2-A：_connectionState 对离线控制器开放 internal 写入（loadFromOfflineCache 置离线态）
    internal val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // WebSocket state - observable by subscribers
    private val _webSocketState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val webSocketState: StateFlow<WebSocketState> = _webSocketState.asStateFlow()

    // WebSocket messages - parsed and broadcast to all subscribers
    private val _webSocketMessages = MutableSharedFlow<WebSocketMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val webSocketMessages: SharedFlow<WebSocketMessage> = _webSocketMessages.asSharedFlow()

    // Connection alert dialog state - single source of truth
    private val _connectionAlertState = MutableStateFlow<ConnectionAlertState?>(null)
    val connectionAlertState: StateFlow<ConnectionAlertState?> = _connectionAlertState.asStateFlow()

    /**
     * 非阻塞连接提示（顶部细横幅），与 [connectionAlertState] 互斥使用。
     *
     * 背景（P3）：弱网下「切离线失败 → 模态框」会把用户困住，唯一出路是按返回，
     * 而返回被解释成"回连接页"。现在网络类失败只写这里，界面保持当前数据并原位重连。
     */
    // B4.2-A：离线控制器要写自动切换横幅（enterOfflineModeInternal）
    internal val _connectionNotice = MutableStateFlow<ConnectionNotice?>(null)
    val connectionNotice: StateFlow<ConnectionNotice?> = _connectionNotice.asStateFlow()

    // Connection check in progress (for "Connecting..." button state)
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // Reconnection in progress (for "Reconnecting..." dialog button state)
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    // Session expired signal for browser-based (Cookie) authentication.
    // Set by the AuthInterceptor when a 401/403 or an SSO redirect is detected.
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * 显存占用（GB，未装 crystools 插件 / 断开 / 无 GPU 时为 null）。
     *
     * 数据源是 crystools 插件每秒推送的 `crystools.monitor` WebSocket 消息
     * （`data.gpus[0].vram_used`，见 F:\ComfyUI\custom_nodes\ComfyUI-Crystools\general\hardware.py
     * 的 getStatus()）。断开与重连时会清空，避免界面显示过期数据。
     *
     * **单位注意**：本机安装的 crystools 直接透传 NVML 的 `mem.used`（字节，
     * 见 general/gpu.py:273-283；其前端用 formatBytes 按 1024 进制显示），
     * 与任务书假设的「GB 的 float」不符。这里按要求原样取 `vram_used`，
     * 接入显存曲线前需按 1024³ 换算（或改用 `vram_used_percent`）。
     */
    private val _vramUsageFraction = MutableStateFlow<Float?>(null)
    val vramUsageFraction: StateFlow<Float?> = _vramUsageFraction.asStateFlow()

    // 跨线程读写：尝试静默重连（[attemptSilentReconnect]）在无锁情况下直接读这两个字段，
    // 写侧在连接/断开流程里。不加 @Volatile 可能读到旧引用（旧 client / 旧 clientId）导致重连失败。
    @Volatile
    private var _client: ComfyUIClient? = null
    @Volatile
    private var _clientId: String? = null
    // B4.2-A：离线控制器要写它（enterOfflineMode / loadFromOfflineCache 的缓存操作上下文）
    internal var _applicationContext: Context? = null

    // ── 模型列表子系统（B4-W2 拆出，委托给 ModelListCatalog）──────────────────────
    //
    // 取证批次 E（状态与闸门）/ T（网络装配）/ V（提取）已整体搬入 ModelListCatalog。
    // 这里只保留：
    // 1. catalog 实例与三个对外转发属性（nodeTypeRegistry / modelCache /
    //    nodeTypeRegistryReady，签名不变，全仓调用方与 JVM 单测零改动）；
    // 2. 会话生命周期接线：connect / disconnect 调 catalog.bumpGeneration()，
    //    invalidateAll 调 catalog.clear()，loadFromOfflineCache 调
    //    catalog.applyOfflineObjectInfo()；
    // 3. 对外转发方法（refreshServerData / ensureModelListLoadedAfterResume /
    //    retryCoreCategory，见下方 T 块原位置），会话侧资源（client / context /
    //    serverId）在调用点显式传参 —— catalog 不持 ConnectionManager 引用。
    // B4.2-A：loadFromOfflineCache 迁入离线控制器，catalog 对其开放 internal
    internal val catalog = ModelListCatalog()

    // Shared node type registry (populated from /object_info)
    val nodeTypeRegistry: NodeTypeRegistry
        get() = catalog.nodeTypeRegistry

    // Shared model cache (populated from /object_info)
    val modelCache: StateFlow<ModelCache>
        get() = catalog.modelCache

    // 节点类型注册表是否已就绪（/object_info 已解析完成；语义详见 ModelListCatalog 的成员注释）。
    val nodeTypeRegistryReady: StateFlow<Boolean>
        get() = catalog.nodeTypeRegistryReady

    // WebSocket management
    /**
     * 自动重连的尝试计数（仅用于状态描述与退避时长，界面不读）。
     *
     * 跨线程读写（NEW-5）：onOpen（OkHttp 线程）与 scheduleReconnect（可能由
     * onFailure/onClosed 的 OkHttp 线程进入）会写，retrySingleAttempt 等主线程流程也会写。
     * 不加 @Volatile 时主线程可能一直读到旧值，退避时长计算与状态描述会滞后。
     */
    @Volatile
    internal var reconnectAttempts = 0

    /**
     * 自动重连是否被显式阻断（登出 / 认证失败 / 切离线）。
     *
     * 取代原先"把 attempts 顶到上限"的隐式做法。重连本身**不再设次数上限**：
     * 移动网络与隧道环境下 WebSocket 可能被中断较久，原来 3 次上限（约 12 秒）
     * 会在网络抖动期用完后彻底放弃，用户回来时就没有实时预览了。
     * 现在只有上述三种明确终止条件才停；网络恢复后自动接上。
     *
     * 跨线程读写（NEW-5）：onFailure（OkHttp 线程）写 true，onOpen / 手动重连（主线程或
     * OkHttp 回调线程）写 false，scheduleReconnect 读。不加 @Volatile 时可能漏看阻断标记，
     * 401 之后仍多排一轮自动重连。
     */
    @Volatile
    internal var reconnectBlocked = false
    /**
     * 心跳协程句柄。跨线程读写（与模型列表加载看门狗句柄同族，后者已随 B4-W2
     * 拆入 [ModelListCatalog]）：startKeepalive 在
     * 连接成功回调里写入，stopKeepalive 在 onFailure / onClosed / disconnect 里取消并
     * 置空，不同 OkHttp 回调线程之间没有 happens-before，不加 @Volatile 时可能读到
     * 旧句柄、把已停掉的心跳再取消一遍而漏掉现行的那份。
     */
    @Volatile
    private var keepaliveJob: Job? = null
    // B4.2-A：离线看门狗协程从这里 launch（OfflineModeController.startOfflineProbeIfAllowed）
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Reconnection coordination - prevents race conditions between multiple reconnection paths
    private var reconnectionSessionId = 0L
    // B4.2-A：切离线时由 OfflineModeController 取消待执行重连
    internal var pendingReconnectJob: Job? = null

    /**
     * 离线模式控制器（B4.2-A 件二）：离线自动切换 / 强制离线 / 恢复在线 / 离线看门狗的
     * 整体动作迁入 [OfflineModeController]；本类保留对外转发 API（签名不变，调用方
     * 零改动）与连接/WebSocket 生命周期。lazy 装配：首次访问时单例必已完成初始化。
     */
    internal val offlineController: OfflineModeController by lazy { OfflineModeController(this) }

    /**
     * 本轮 Connecting 的起始时刻（elapsedRealtime）；未处于 Connecting 时为 0。
     *
     * 用于识别"握手永远不回调"的僵尸状态：旧实现只要看到 Connecting 就直接跳过，
     * 一旦 OkHttp 在弱网/隧道半开时既不 onOpen 也不 onFailure，状态就永久卡住
     * （条目 4.1）。超过 [WebSocketRecoveryPolicy.CONNECTING_TIMEOUT_MS] 即强制重建。
     *
     * 跨线程读写（D2）：写侧在主线程的连接/断开流程，读侧在 OkHttp 回调线程
     * （onOpen/onFailure/onClosed）与入口判定里。没有 happens-before 时可能读到旧值，
     * 把刚重建的连接误判成"仍在握手"或把僵尸判定漏掉，因此加 @Volatile。
     */
    @Volatile
    private var connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE

    /**
     * WebSocket 监听代次：只有最新一次 [openWebSocket] 创建的监听器能改状态。
     *
     * 强制重建僵尸连接时，旧 socket 被 close 后仍可能回调 onFailure/onClosed；
     * 没有代次校验的话，这些迟到回调会把刚建立的新连接状态又打回 Failed/Disconnected。
     *
     * 跨线程读写（D2/NEW-2）：写侧既可能在主线程，也可能在 OkHttp 回调线程
     * （`attemptSilentReconnect` / `reconnectWebSocket` 的 `testConnection` 回调会直接
     * 调 [openWebSocket]），而递增本身是"读-改-写"。所以这里用
     * [WebSocketGenerationCounter]：递增在锁内完成（并发调用拿到的代次两两不同），
     * 字段本身是 @Volatile（读侧无锁也能看到最新值）。
     */
    private val webSocketGeneration = WebSocketGenerationCounter()

    // ── 离线自动切换 ────────────────────────────────────────────────────
    // 判定逻辑刻意抽到 ConnectionStability（零 Android 依赖，可在 JVM 单测里钉住）：
    // 连续 N 次失败才切离线、连续 N 次成功才切回、切换后有冷却。
    /** 判定器状态：连续计数与冷却起点（B4.2-A：离线控制器切换态时写它，internal） */
    internal var stabilityState = StabilityState()

    /** 用户是否通过设置页「强制离线」明确要求不联网（此时不建 WebSocket、不做周期探测） */
    internal var forcedOffline = false

    /** 最近一次会话的地址与凭据；离线态重建客户端、恢复在线、发起探测都要用 */
    internal var lastSession: OnlineSession? = null

    /** 一次会话的参数快照（不可变；B4.2-A：离线控制器 enterOfflineMode 也要构造它） */
    internal data class OnlineSession(
        val serverId: String,
        val hostname: String,
        val port: Int,
        val protocol: String,
        val credentials: AuthCredentials
    )

    /**
     * Flag indicating that the user explicitly logged out.
     * Used to prevent auto-connect on the login screen after logout.
     */
    var isUserInitiatedLogout: Boolean = false
        private set

    /**
     * 「抑制自动连」请求的代次：每次 [logout] / [suppressAutoConnectOnce] 置位时递增。
     *
     * 连接页（LoginScreen）用「记住自己处理过的代次」代替「读完即清」来消费抑制请求 ——
     * 旧做法（读标志后立刻 [clearLogoutFlag]）在配置变更（旋转/深色模式）导致 Compose 重建时，
     * LaunchedEffect 会再跑一遍而标志已被清掉，抑制失效、自动连了回去。
     * 代次是单调递增的：连接页重建后从 savedInstanceState 恢复「已处理代次」，
     * 与当前代次相等就跳过重判，抑制判定得以跨重建保留。
     */
    var autoConnectSuppressionEpoch: Long = 0
        private set

    /** 置位抑制请求并推进代次（[logout] 与 [suppressAutoConnectOnce] 的共同入口）。 */
    private fun armAutoConnectSuppression() {
        isUserInitiatedLogout = true
        autoConnectSuppressionEpoch++
    }

    /**
     * Clear the logout flag. Called after login screen checks it.
     */
    fun clearLogoutFlag() {
        isUserInitiatedLogout = false
    }

    /**
     * 只置「抑制连接页自动重连」这一个标志，别的什么都不做（**不改连接、不清缓存、不清队列**）。
     *
     * 使用场景：主界面在起始 tab 按返回键回连接页（[sh.hnet.comfychair.MainContainerActivity]
     * 的返回键回调）。此时用户并没有登出 —— 生成任务可能还在跑，队列、生成状态、连接都要保留，
     * 所以不能调 [logout]（它会 `reconnectBlocked = true` + `disconnect()` + 清 lastSession）。
     *
     * 复用 [isUserInitiatedLogout] 是刻意的：连接页只认这一个标志
     * （`LoginScreen.kt:448` 的 `shouldAutoConnect`），且它紧接着第 449 行就会
     * [clearLogoutFlag]，所以本标志只生效一次 —— 既保证「回连接页后停在连接页」，
     * 又不会让用户以后再也自动连不上。`private set` 的封装也靠这个入口保住：
     * 外部只能「请求抑制一次」，不能把标志长期置真。
     */
    fun suppressAutoConnectOnce() {
        armAutoConnectSuppression()
        DebugLogger.d(TAG, "suppressAutoConnectOnce: 回连接页，抑制一次性自动重连（不登出）")
    }

    /**
     * Acknowledge that the session-expiry event has been handled.
     * Call this after presenting the re-authentication UI to the user.
     * Also re-arms the AuthInterceptor so it can detect the next expiry.
     */
    fun resetSessionExpired() {
        _sessionExpired.value = false
        _client?.resetSessionExpiredFlag()
        DebugLogger.d(TAG, "Session expiry acknowledged, detection re-armed")
    }

    /**
     * Update the credentials used for subsequent requests without reconnecting.
     * Intended for refreshing browser-session cookies after re-authentication.
     * Also re-arms session-expiry detection.
     *
     * @param credentials New credentials (typically [AuthCredentials.Cookie])
     */
    fun updateCredentials(credentials: AuthCredentials) {
        _client?.setCredentials(credentials)
        _client?.resetSessionExpiredFlag()
        DebugLogger.d(TAG, "Credentials updated")
    }

    /**
     * Get the shared ComfyUIClient instance.
     * @throws IllegalStateException if not connected
     */
    val client: ComfyUIClient
        get() = _client ?: throw IllegalStateException("ConnectionManager: Not connected to server")

    /**
     * Get the shared ComfyUIClient instance, or null if not connected.
     */
    val clientOrNull: ComfyUIClient?
        get() = _client

    /**
     * 当前 hostname。
     *
     * v4.6.1（模块 A3 修复）：**未连接时不再返回空串**。原实现只看 `Connected` 状态，
     * 一旦断线/离线，监控页会显示空 hostname，用户以为配置丢了。现在按
     * 「当前连接 → 上次会话 → 已保存的服务器」逐级回退，只有全都拿不到才返回空。
     */
    val hostname: String
        get() = (connectionState.value as? ConnectionState.Connected)?.hostname
            ?: lastSession?.hostname
            ?: _applicationContext?.let { ServerStorage(it).getSelectedServer()?.hostname }
            ?: ""

    /**
     * 当前端口。
     *
     * v4.6.1（模块 A3 修复，对应现象「监控界面端口从 443 退化为 8188」）：
     * 原实现未连接时直接返回硬编码 8188，导致断线后监控页把真实端口显示成默认值，
     * 用户误以为配置被重置。现在优先用真实配置回退，8188 只在连服务器记录都没有时兜底。
     */
    val port: Int
        get() = (connectionState.value as? ConnectionState.Connected)?.port
            ?: lastSession?.port
            ?: _applicationContext?.let { ServerStorage(it).getSelectedServer()?.port }
            ?: 8188

    /**
     * 当前协议（"http" 或 "https"）。
     *
     * 同样按「当前连接 → 上次会话 → 已保存配置」回退；已保存配置里的协议为空（自动）时
     * 最后一级用 "https"（安全默认：明文只应来自探测成功或用户显式选择）。
     */
    val protocol: String
        get() = (connectionState.value as? ConnectionState.Connected)?.protocol
            ?: lastSession?.protocol
            ?: _applicationContext?.let { ServerStorage(it).getSelectedServer()?.protocol }
            ?: "https"

    /**
     * Current server ID, or null if not connected (includes offline mode).
     */
    val currentServerId: String?
        get() = when (val state = connectionState.value) {
            is ConnectionState.Connected -> state.serverId
            is ConnectionState.OfflineConnected -> state.serverId
            else -> null
        }

    /**
     * Current client ID for WebSocket communication.
     */
    val clientId: String
        get() = _clientId ?: ""

    /**
     * Whether currently connected to a server.
     */
    val isConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    /**
     * 当前是否处于离线态（自动切换进入或用户强制离线）。
     *
     * 与 [AppSettings.isOfflineMode] 的区别：那个是用户"强制离线"的**持久化意图**，
     * 这个是**实际状态**——网络断了自动切过来的离线同样为 true。界面判定"能不能联网"
     * 应该看这个，判定"用户是不是要求别联网"才看设置项。
     */
    val isOffline: Boolean
        get() = offlineController.isOffline

    /**
     * Whether WebSocket is currently connected.
     */
    val isWebSocketConnected: Boolean
        get() = webSocketState.value is WebSocketState.Connected

    /**
     * Establish connection to a ComfyUI server.
     *
     * This should be called after a successful connection test in LoginScreen.
     * If already connected to the same server, this is a no-op.
     * If connected to a different server, caches are invalidated first.
     *
     * @param context Application context
     * @param serverId Server UUID for per-server storage scoping
     * @param hostname Server hostname
     * @param port Server port
     * @param protocol Detected protocol ("http" or "https")
     * @param credentials Authentication credentials for the server
     */
    @Synchronized
    fun connect(
        context: Context,
        serverId: String,
        hostname: String,
        port: Int,
        protocol: String,
        credentials: AuthCredentials = AuthCredentials.None
    ) {
        DebugLogger.i(TAG, "Connecting to ${Obfuscator.hostname(hostname)} (protocol: $protocol, serverId: $serverId)")
        // 主动连接入口：解除此前因登出/认证失败/离线设下的重连阻断
        reconnectBlocked = false
        // G7/G8：进入新会话即推进模型列表代次 —— 上一个会话（含退出登录前）任何在飞的
        // /object_info 回调与磁盘预填都会因代次不符被丢弃，不会把新会话的列表盖回旧数据
        catalog.bumpGeneration()
        val current = connectionState.value

        // Check if already connected to the same server
        if (current is ConnectionState.Connected &&
            current.serverId == serverId) {
            DebugLogger.d(TAG, "Already connected to same server, skipping")
            return
        }

        // 记录会话参数：离线态重建客户端、恢复在线都依赖它
        lastSession = OnlineSession(serverId, hostname, port, protocol, credentials)

        // Disconnect from previous server if any (离线态 → 在线 也走这里，清掉残留客户端)
        if (current is ConnectionState.Connected || current is ConnectionState.OfflineConnected) {
            DebugLogger.d(TAG, "Disconnecting from previous server")
            closeWebSocketInternal()
            invalidateAll()
            _client?.shutdown()
        }

        // Generate unique client ID for this session
        _clientId = "comfychair_android_${UUID.randomUUID()}"

        // Store application context for offline cache operations
        _applicationContext = context.applicationContext

        // Create shared client with detected protocol, shared client ID, and credentials
        _client = ComfyUIClient(
            context.applicationContext,
            hostname,
            port,
            credentials,
            serverId,
            preferredProtocol = protocol
        ).apply {
            setWorkingProtocol(protocol)
            setClientId(_clientId!!)
            setOnSessionExpired {
                DebugLogger.w(TAG, "Browser auth session expired — notifying UI")
                _sessionExpired.value = true
            }
        }

        _connectionState.value = ConnectionState.Connected(
            serverId = serverId,
            hostname = hostname,
            port = port,
            protocol = protocol,
            clientId = _clientId!!
        )
        // 新连接建立：上一次会话遗留的断线横幅不再成立
        clearConnectionNotice()
        DebugLogger.i(TAG, "Connected successfully")

        // 回到在线：清掉强制离线标记、重置防抖动判定、停止离线探测
        forcedOffline = false
        stabilityState = StabilityState()
        offlineController.stopOfflineProbe()

        // Fetch server data (node types and model lists) after connection
        // B4-W2：装配委托给 catalog；client / context / serverId 在调用点显式传参
        catalog.fetchServerData(_client, _applicationContext, currentServerId)
    }

    /**
     * Disconnect from the current server and clear all caches.
     * Called on logout or when connection needs to be reset.
     */
    @Synchronized
    fun disconnect() {
        if (connectionState.value is ConnectionState.Disconnected) {
            return
        }

        DebugLogger.i(TAG, "Disconnecting")
        // 断开即作废当前模型列表会话：在飞的 /object_info 回调与磁盘预填都按代次丢弃
        catalog.bumpGeneration()
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        offlineController.stopOfflineProbe()
        stabilityState = StabilityState()
        closeWebSocketInternal()
        invalidateAll()
        _client?.shutdown()
        _client = null
        _clientId = null
        _sessionExpired.value = false
        _connectionState.value = ConnectionState.Disconnected
        clearConnectionNotice()
        DebugLogger.i(TAG, "Disconnected")
    }

    /**
     * Called when backup restore changes connection settings.
     * Clears everything, reloads workflows, and returns to login.
     */
    fun invalidateForRestore() {
        DebugLogger.i(TAG, "Invalidating for restore")
        // Reload workflows to pick up restored user workflows
        WorkflowManager.reloadWorkflows()
        disconnect()
    }

    /**
     * Logout from the server. Sets the logout flag to prevent auto-reconnect.
     * Called when user explicitly logs out via the UI.
     */
    fun logout() {
        DebugLogger.i(TAG, "User initiated logout")
        armAutoConnectSuppression()
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        reconnectBlocked = true  // 登出后不得自动回连
        // 登出后不应再自动回连，也不该带着上一个服务器的会话信息做离线恢复
        lastSession = null
        forcedOffline = false
        disconnect()
    }

    /**
     * Reset reconnection attempt counter and state.
     * Called when user chooses to retry connection from the connection alert dialog.
     */
    fun resetReconnectAttempts() {
        DebugLogger.i(TAG, "Resetting reconnect attempts")
        reconnectAttempts = 0
        reconnectBlocked = false  // 用户手动重试：解除阻断
        _webSocketState.value = WebSocketState.Disconnected
    }

    /**
     * Start a new reconnection session.
     * Cancels any pending auto-reconnect and returns a new session ID.
     * Call at the start of any reconnection entry point (manual retry, silent reconnect, or ensureConnection).
     */
    private fun startNewReconnectionSession(): Long {
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        return ++reconnectionSessionId
    }

    /**
     * Check if a reconnection session is still valid.
     * Used in callbacks to detect and ignore stale results.
     */
    private fun isSessionValid(sessionId: Long): Boolean {
        return sessionId == reconnectionSessionId
    }

    /**
     * 报告一次连接失败。
     *
     * P3：这里是**唯一的弹窗分流入口**。只有认证失效需要用户立刻处理，才弹模态；
     * 网络中断 / 传输停滞等「后台重试就能自己好」的失败一律降级为非阻塞横幅
     * （[connectionNotice]）。这样图库浏览、生成中等待等场景不会被一个关不掉的
     * 对话框挡住，用户也就不必靠按返回逃离（按返回会被当作"回连接页"）。
     *
     * @param context Context（保留以兼容调用方签名）
     * @param failureType The type of connection failure
     */
    fun showConnectionAlert(context: Context, failureType: ConnectionFailure) {
        DebugLogger.d(TAG, "showConnectionAlert: failureType=$failureType")
        if (shouldBlockWithDialog(failureType)) {
            _connectionAlertState.value = ConnectionAlertState(failureType)
        } else {
            showConnectionNotice(failureType)
        }
    }

    /**
     * 只有必须由用户处理、且无法在后台静默恢复的失败才值得模态拦截。
     * 目前仅认证失效（凭据错误 / 会话过期）符合：不处理的话重试多少次都没用。
     */
    private fun shouldBlockWithDialog(failureType: ConnectionFailure): Boolean =
        failureType == ConnectionFailure.AUTHENTICATION

    /**
     * 显示非阻塞连接提示（顶部横幅），并确保网络类失败仍在后台继续重连。
     *
     * 不切页面、不清数据：调用方界面保持原样，横幅在 [WebSocketState.Connected]
     * 或进入离线态时自动清除。
     */
    fun showConnectionNotice(failureType: ConnectionFailure) {
        DebugLogger.d(TAG, "showConnectionNotice: failureType=$failureType")
        _connectionNotice.value = ConnectionNotice(failureType)
        ensureBackgroundRetry(failureType)
        // 传输停滞是"这一次请求卡住了"，不是连接断了：WebSocket 可能一直是 Connected，
        // 没有 onOpen 来清横幅，所以给一个短时自动消失，避免顶部一直挂着提示。
        if (failureType == ConnectionFailure.STALLED) {
            scope.launch {
                delay(NOTICE_AUTO_DISMISS_MS)
                if (_connectionNotice.value?.failureType == ConnectionFailure.STALLED) {
                    _connectionNotice.value = null
                }
            }
        }
    }

    // B4.2-A：离线控制器切离线（手动入口）要清提示，开放 internal
    internal fun clearConnectionNotice() {
        if (_connectionNotice.value != null) {
            _connectionNotice.value = null
        }
    }

    /**
     * 横幅承诺"正在自动重试"就必须真的有重试在跑。
     *
     * `ensureConnection` 等入口在失败时不会自己排重连，若只显示横幅不排重试，
     * 用户会一直停在"正在重试"却永远等不到。已有重连 job / 正在重连时保持不动，
     * 避免同一时刻叠两条退避链。
     */
    private fun ensureBackgroundRetry(failureType: ConnectionFailure) {
        if (failureType != ConnectionFailure.NETWORK) return
        if (isOffline || reconnectBlocked || _client == null) return
        val ws = _webSocketState.value
        if (ws is WebSocketState.Connected ||
            ws is WebSocketState.Connecting ||
            ws is WebSocketState.Reconnecting
        ) {
            return
        }
        if (pendingReconnectJob?.isActive == true) return
        scheduleReconnect()
    }

    /**
     * Clear the connection alert dialog.
     * Called when user dismisses the dialog or takes an action (retry, return to login, etc.).
     */
    fun clearConnectionAlert() {
        DebugLogger.d(TAG, "clearConnectionAlert")
        _connectionAlertState.value = null
    }

    /**
     * Ensure connection is ready before an operation.
     * Always verifies with HTTP test - don't trust cached WebSocket state.
     * Sets isConnecting state for UI feedback (e.g., "Connecting..." button).
     *
     * @param context Context for dialog
     * @param onResult Callback with success (true) or failure (false).
     *                 On failure, dialog is already shown via showConnectionAlert.
     */
    fun ensureConnection(context: Context, onResult: (success: Boolean) -> Unit) {
        DebugLogger.d(TAG, "ensureConnection: verifying connection")
        _isConnecting.value = true

        val client = _client
        if (client == null) {
            DebugLogger.w(TAG, "ensureConnection: no client")
            _isConnecting.value = false
            // 客户端都没了，等价于一次网络失败：喂给自动切换判定
            reportConnectivityFailure(ConnectionFailure.NETWORK)
            if (!isOffline) {
                showConnectionAlert(context, ConnectionFailure.NETWORK)
            }
            onResult(false)
            return
        }

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        // v4.8.2（模块一·入队迟缓）：已连接且 WebSocket 在线时直接放行。
        //
        // 原来这里无条件走 client.testConnection（注释原文 "Always verify with HTTP test -
        // don't trust cached state"）：每次点"生成"都要先打一次真实 HTTP 往返，弱网/隧道下
        // 3–5 秒，而 JobRegistry.registerJob（按钮变"添加到队列 (N)"的触发点）在这之后 ——
        // 用户看到的就是"点了生成先显示连接中、几秒后才排队"。
        // 真断线由 WebSocket 状态与自动重连兜底；提交本身失败也会立刻回报，不会静默。
        if (isWebSocketConnected) {
            DebugLogger.d(TAG, "ensureConnection: WS online, skip HTTP probe")
            _isConnecting.value = false
            reportConnectivitySuccess()
            onResult(true)
            return
        }

        client.testConnection { success, _, _, failureType ->
            scope.launch {
                // Ignore stale callback if another reconnection started
                if (!isSessionValid(sessionId)) {
                    DebugLogger.d(TAG, "ensureConnection: ignoring stale callback (session $sessionId)")
                    _isConnecting.value = false
                    return@launch
                }

                if (success) {
                    DebugLogger.d(TAG, "ensureConnection: server reachable")
                    reportConnectivitySuccess()
                    // Server reachable - ensure WebSocket is connected
                    if (isWebSocketConnected) {
                        _isConnecting.value = false
                        onResult(true)
                    } else {
                        DebugLogger.i(TAG, "ensureConnection: opening WebSocket")
                        openWebSocket()
                        val finalState = withTimeoutOrNull(WebSocketRecoveryPolicy.CONNECT_WAIT_TIMEOUT_MS) {
                            _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                        }
                        _isConnecting.value = false
                        // NEW-1：这 10 秒等待期间可能已有新的重连会话接手（startNewReconnectionSession
                        // 只取消 pendingReconnectJob，不会取消本协程）。旧协程若继续写，会把新会话
                        // 刚建立的 Connecting 收敛成 Failed、清掉它的握手起始时刻，并对已被取代的
                        // 请求弹告警/回调 false。所以等待结束后必须重新校验会话，再做任何写入。
                        if (!isSessionValid(sessionId)) {
                            DebugLogger.d(
                                TAG,
                                "ensureConnection: 等待结束后会话已变更，忽略旧结果（session $sessionId）"
                            )
                            return@launch
                        }
                        if (finalState is WebSocketState.Connected) {
                            DebugLogger.i(TAG, "ensureConnection: WebSocket connected")
                            onResult(true)
                        } else {
                            // D4：超时（finalState == null）时没有任何自治定时器会再来收尾，
                            // 状态会永久停在 Connecting。这里收敛成失败终态：已 Failed 的保持
                            // 原对象（可能带更具体的失败原因），仍在 Connecting 的落 NETWORK 失败。
                            //
                            // NEW-3：必须用 StateFlow 的原子 update，不能"读到 Connecting 再写回"。
                            // onOpen 可能在读与写之间把状态改成 Connected（OkHttp 线程），
                            // 检查-写入会把刚连上的状态又打回 Failed。update 的 lambda 拿到的是
                            // 最新值，afterConnectTimeout 对非 Connecting 原样返回，天然免疫这个竞态。
                            val before = _webSocketState.value
                            _webSocketState.update { WebSocketRecoveryPolicy.afterConnectTimeout(it) }
                            val after = _webSocketState.value
                            connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
                            if (after is WebSocketState.Connected) {
                                // 原子 update 生效后才连上的情况：按成功处理，不能再弹失败对话框
                                DebugLogger.i(TAG, "ensureConnection: 超时判定期间 WebSocket 已连上")
                                onResult(true)
                            } else {
                                if (after != before) {
                                    DebugLogger.w(
                                        TAG,
                                        "ensureConnection: WebSocket 等待超时，收敛状态 $before -> $after"
                                    )
                                } else {
                                    DebugLogger.w(TAG, "ensureConnection: WebSocket failed（状态保持 $after）")
                                }
                                showConnectionAlert(context, ConnectionFailure.NETWORK)
                                onResult(false)
                            }
                        }
                    }
                } else {
                    _isConnecting.value = false
                    DebugLogger.w(TAG, "ensureConnection: server unreachable, failureType=$failureType")
                    reportConnectivityFailure(failureType)
                    // 本次探测已失败；若状态还停在 Connecting/Reconnecting（上一轮重连留下的
                    // 过渡态，其 pending job 已被 startNewReconnectionSession 取消），必须收尾，
                    // 否则会永久停在"连接中/重连中"（条目 4.1）
                    val collapsed = WebSocketRecoveryPolicy.collapseTransitional(_webSocketState.value)
                    if (collapsed != _webSocketState.value) {
                        DebugLogger.d(TAG, "ensureConnection: 收敛过渡态 ${_webSocketState.value} -> $collapsed")
                        _webSocketState.value = collapsed
                    }
                    // 已自动切到离线时不再弹"连接失败"对话框（离线看缓存是正常状态）
                    if (!isOffline) {
                        showConnectionAlert(context, failureType)
                    }
                    onResult(false)
                }
            }
        }
    }

    /**
     * Retry connection with a single attempt, triggered by user tapping "Reconnect" in the dialog.
     * Sets isReconnecting state for UI feedback (spinner on button).
     * Shows Toast and keeps dialog open on failure.
     * Closes dialog on success.
     */
    fun retrySingleAttempt(context: Context) {
        DebugLogger.d(TAG, "retrySingleAttempt: called, _client=${if (_client != null) "exists" else "NULL"}")
        _isReconnecting.value = true

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        val client = _client ?: run {
            DebugLogger.w(TAG, "retrySingleAttempt: aborting - client is null")
            _isReconnecting.value = false
            // 待重连 job 已在上一步被取消，若状态还是 Reconnecting 就没人收尾了
            _webSocketState.value = WebSocketRecoveryPolicy.collapseTransitional(_webSocketState.value)
            Toast.makeText(context, R.string.msg_reconnect_failed, Toast.LENGTH_SHORT).show()
            return
        }

        DebugLogger.i(TAG, "User initiated single retry attempt (session $sessionId)")
        // D1：先作废旧监听器（递增代次）再改状态、关 socket，
        // 旧 socket 的迟到 onFailure/onClosed 不会再把状态打回 Failed
        invalidateWebSocketCallbacks()
        _webSocketState.value = WebSocketState.Disconnected
        client.closeWebSocket()
        DebugLogger.d(TAG, "retrySingleAttempt: closed WebSocket, calling testConnection")
        client.testConnection { success, errorMessage, _, failureType ->
            scope.launch {
                // Ignore stale callback if session has changed
                if (!isSessionValid(sessionId)) {
                    DebugLogger.d(TAG, "retrySingleAttempt: ignoring stale callback (session $sessionId)")
                    // 本次调用留在 _isReconnecting 上的 true 必须清掉：scheduleReconnect 见到它
                    // 会一直跳过，"重连中"标记若无人复位，自动重连就永久停摆。
                    _isReconnecting.value = false
                    return@launch
                }

                DebugLogger.d(TAG, "retrySingleAttempt: testConnection callback - success=$success, failureType=$failureType")
                if (success) {
                    DebugLogger.i(TAG, "Single retry succeeded, opening WebSocket")
                    reconnectAttempts = 0
                    reconnectBlocked = false
                    reportConnectivitySuccess()
                    openWebSocket()

                    // Wait for WebSocket to actually connect before clearing dialog
                    val finalState = withTimeoutOrNull(WebSocketRecoveryPolicy.CONNECT_WAIT_TIMEOUT_MS) {
                        _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                    }

                    _isReconnecting.value = false
                    // NEW-1：10 秒等待期间可能已有新的重连会话接手（startNewReconnectionSession
                    // 只取消 pendingReconnectJob，不会取消本协程）。旧协程若继续写，会把新会话
                    // 刚建立的 Connecting 收敛成 Failed、清掉握手起始时刻并弹失败 Toast。
                    if (!isSessionValid(sessionId)) {
                        DebugLogger.d(
                            TAG,
                            "retrySingleAttempt: 等待结束后会话已变更，忽略旧结果（session $sessionId）"
                        )
                        return@launch
                    }
                    if (finalState is WebSocketState.Connected) {
                        DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                        clearConnectionAlert()
                    } else {
                        // D4：等待超时时状态还停在 Connecting，必须收敛，否则界面永久"连接中"
                        // NEW-3：同上，用原子 update 避免把并发 onOpen 写好的 Connected 打回 Failed
                        val before = _webSocketState.value
                        _webSocketState.update { WebSocketRecoveryPolicy.afterConnectTimeout(it) }
                        val after = _webSocketState.value
                        connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
                        if (after is WebSocketState.Connected) {
                            // 原子 update 生效后才连上的情况：按成功处理，不弹失败 Toast
                            DebugLogger.i(TAG, "retrySingleAttempt: 超时判定期间 WebSocket 已连上")
                            clearConnectionAlert()
                        } else {
                            if (after != before) {
                                DebugLogger.w(
                                    TAG,
                                    "retrySingleAttempt: WebSocket 等待超时，收敛状态 $before -> $after"
                                )
                            } else {
                                DebugLogger.w(TAG, "WebSocket failed to connect after successful HTTP test（状态保持 $after）")
                            }
                            Toast.makeText(context, R.string.msg_reconnect_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    _isReconnecting.value = false
                    DebugLogger.w(TAG, "Single retry failed: $failureType")
                    reportConnectivityFailure(failureType)
                    _webSocketState.value = WebSocketState.Failed(
                        reason = errorMessage,
                        failureType = failureType
                    )
                    Toast.makeText(context, R.string.msg_reconnect_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Attempt a silent reconnection when app returns from background.
     * Unlike retrySingleAttempt(), this is designed to recover gracefully:
     * - If already connected, does nothing
     * - If reconnect succeeds, app continues normally
     * - If reconnect fails, sets Failed state (dialog will appear via normal ViewModel flow)
     */
    fun attemptSilentReconnect() {
        // 已连接：不打扰。
        val state = _webSocketState.value
        if (state is WebSocketState.Connected) {
            DebugLogger.d(TAG, "attemptSilentReconnect: state=$state, skipping")
            return
        }
        // Connecting：只在 20 秒握手窗口内跳过。超过窗口的僵尸 Connecting 必须放行到
        // 下面的重建流程，否则 onResume 是这条状态唯一的自愈机会（条目 4.1）。
        if (state is WebSocketState.Connecting &&
            WebSocketRecoveryPolicy.openDecision(
                state = state,
                connectingSinceMs = connectingSinceElapsedMs,
                nowMs = SystemClock.elapsedRealtime()
            ) == OpenWebSocketDecision.STILL_CONNECTING
        ) {
            DebugLogger.d(TAG, "attemptSilentReconnect: still connecting, skipping")
            return
        }

        val client = _client ?: run {
            DebugLogger.w(TAG, "attemptSilentReconnect: client is null")
            return
        }

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        DebugLogger.i(TAG, "Attempting silent reconnect on resume")
        // D1：自愈僵尸 Connecting 时旧监听器还活着，不先作废代次的话，它的迟到回调
        // 会把状态写成 Failed 并再排一次重连，随后又把下面新建的连接关掉
        invalidateWebSocketCallbacks()
        // Set to Disconnected so openWebSocket() will work and StateFlow will emit on failure
        _webSocketState.value = WebSocketState.Disconnected
        client.closeWebSocket()
        client.testConnection { success, errorMessage, _, failureType ->
            // Ignore stale callback if another reconnection started
            if (!isSessionValid(sessionId)) {
                DebugLogger.d(TAG, "attemptSilentReconnect: ignoring stale callback (session $sessionId)")
                return@testConnection
            }

            DebugLogger.d(TAG, "attemptSilentReconnect: testConnection result - success=$success, failureType=$failureType")
            if (success) {
                DebugLogger.i(TAG, "Silent reconnect succeeded, opening WebSocket")
                reconnectAttempts = 0
                reconnectBlocked = false
                reportConnectivitySuccess()
                openWebSocket()
            } else {
                DebugLogger.w(TAG, "Silent reconnect failed: $failureType")
                reportConnectivityFailure(failureType)
                _webSocketState.value = WebSocketState.Failed(
                    reason = errorMessage,
                    failureType = failureType
                )
                // Dialog will appear via existing GenerationViewModel observation
            }
        }
    }

    /**
     * Clear all connection-dependent caches.
     */
    private fun invalidateAll() {
        GalleryRepository.getInstance().reset()
        MediaStateHolder.clearAll()
        MediaCache.reset()
        JobRegistry.clear()
        // Clear model cache and node registry（B4-W2：三件套的清空委托给 catalog）
        catalog.clear()
    }

    // WebSocket management

    /**
     * Open WebSocket connection with centralized event handling.
     * Events are parsed and broadcast via [webSocketMessages] SharedFlow.
     * @return true if connection attempt started
     */
    fun openWebSocket(): Boolean {
        DebugLogger.d(TAG, "openWebSocket: called, currentState=${_webSocketState.value}")
        // 离线态（自动切换或用户强制）不联网：任何残留路径都不得打开 WebSocket
        if (isOffline) {
            DebugLogger.d(TAG, "openWebSocket: skipped (offline mode)")
            return false
        }
        val client = _client ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: client is null")
            return false
        }
        val id = _clientId ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: clientId is null")
            return false
        }

        // 已连接 / 仍在握手窗口内的 Connecting：不重复建连。
        // 超过 20 秒（或没有起始时刻）的 Connecting 视为僵尸状态，强制重建（条目 4.1）。
        val decision = WebSocketRecoveryPolicy.openDecision(
            state = _webSocketState.value,
            connectingSinceMs = connectingSinceElapsedMs,
            nowMs = SystemClock.elapsedRealtime()
        )
        when (decision) {
            OpenWebSocketDecision.ALREADY_CONNECTED -> {
                DebugLogger.d(TAG, "WebSocket already connected - skipping open")
                return true
            }
            OpenWebSocketDecision.STILL_CONNECTING -> {
                DebugLogger.d(TAG, "WebSocket still connecting - skipping open")
                return true
            }
            OpenWebSocketDecision.REBUILD_STALE -> {
                DebugLogger.w(
                    TAG,
                    "检测到陈旧 Connecting（超过 ${WebSocketRecoveryPolicy.CONNECTING_TIMEOUT_MS}ms），强制重建 WebSocket"
                )
            }
            OpenWebSocketDecision.OPEN -> Unit
        }

        // 先递增代次让旧监听器的回调作废，再关掉旧 socket：
        // close() 可能立刻触发旧监听器的 onFailure，不先作废会把刚写的新状态打回去。
        val generation = invalidateWebSocketCallbacks()
        if (decision == OpenWebSocketDecision.REBUILD_STALE) {
            client.closeWebSocket()
        }
        connectingSinceElapsedMs = SystemClock.elapsedRealtime()
        DebugLogger.i(TAG, "Opening WebSocket connection")
        _webSocketState.value = WebSocketState.Connecting

        val listener = object : WebSocketListener() {
            /** 旧连接（或已被强制重建的连接）的迟到回调：直接丢弃，不得改状态。 */
            private fun isStaleCallback(): Boolean {
                // 只读一次：判断与日志必须是同一个快照，否则日志可能自相矛盾
                val currentGeneration = webSocketGeneration.current
                if (!WebSocketRecoveryPolicy.isStaleCallback(generation, currentGeneration)) return false
                DebugLogger.d(TAG, "忽略旧 WebSocket 回调（代次 $generation / 当前 $currentGeneration）")
                return true
            }

            /**
             * NEW-2：把"判代次 + 写状态"合成一个原子步骤（锁与代次递增共用）。
             *
             * 只做 [isStaleCallback] 再写是不够的：通过检查之后、写状态之前，另一线程
             * （例如 retrySingleAttempt 作废代次并关闭 socket）可以把这条监听器作废，
             * 旧 onOpen 仍会把状态写回 Connected 并启动 keepalive —— UI 显示已连接、
             * 底层 socket 却已经没了。
             */
            private fun runIfCurrent(block: () -> Unit): Boolean {
                val applied = webSocketGeneration.runIfCurrent(generation, block)
                if (!applied) {
                    DebugLogger.d(TAG, "忽略旧 WebSocket 回调（代次 $generation / 已被作废）")
                }
                return applied
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val applied = runIfCurrent {
                    connectedAtElapsedMs = SystemClock.elapsedRealtime()
                    logWsInfo(
                        "[WS] connected generation=$generation elapsed=0.000s " +
                            "appPing=${keepaliveIntervalMs / 1000}s (effective)"
                    )
                    connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
                    reconnectAttempts = 0
                    reconnectBlocked = false
                    _webSocketState.value = WebSocketState.Connected

                    // Clear any pending connection dialog and reset flags
                    // This handles the case where auto-reconnect succeeds while dialog is open
                    if (_connectionAlertState.value != null) {
                        DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                        _connectionAlertState.value = null
                    }
                    // P3：横幅同理 —— 重连成功后"连接中断"提示必须自动消失
                    if (_connectionNotice.value != null) {
                        DebugLogger.i(TAG, "WebSocket connected, clearing notice banner")
                        _connectionNotice.value = null
                    }
                    _isReconnecting.value = false
                    _isConnecting.value = false
                }
                if (!applied) return

                // 下面两步只影响心跳与预加载，代次在此之后被推进也无害：
                // keepalive 循环每轮都会检查状态，状态被改走后自动退出。
                reportConnectivitySuccess()
                startKeepalive()
                GalleryRepository.getInstance().startBackgroundPreload()
                // G7 指令 1/3：此刻连接状态机已收敛到 Connected —— 这是「长时间后台后
                // 静默重连成功」路径里模型列表必检的正确时机。onResume 那次很可能撞在
                // 过渡态上被判 SKIP_TRANSIENT_CONNECTION，没有这一步，E5/E6/E7 就再也
                // 没有人来救（历史 Q3「修了但没生效」缺的正是这条收敛后的复查）。
                ensureModelListLoadedAfterResume()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isStaleCallback()) return
                parseAndEmitMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (isStaleCallback()) return
                parseAndEmitBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // NEW-2：判代次与整段状态机写入在同一个锁内完成，避免"通过检查后又被作废"
                val applied = runIfCurrent {
                    val elapsedMs = connectedElapsedMs()
                    logWsError(
                        "[WS] onFailure code=${response?.code ?: "none"} " +
                            "reason=${t.message ?: t::class.simpleName} elapsed=${elapsedMs / 1000.0}s"
                    )
                    connectedAtElapsedMs = 0L
                    // 顺序很重要（条目 4.1）：先停心跳 → 再落终态或排重连。
                    // 若先排重连，scheduleReconnect 在离线/手动重连中/被阻断时会提前 return，
                    // 状态就永久停在 Connecting（界面一直显示"连接中"）。
                    stopKeepalive()
                    _vramUsageFraction.value = null
                    connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE

                    val failureType = WebSocketRecoveryPolicy.failureTypeFor(response?.code)
                    if (!WebSocketRecoveryPolicy.isRecoverableFailure(failureType)) {
                        // D10：认证失败等不可恢复的失败——重试没有意义，阻断自动重连并落失败终态，
                        // 这种 Failed 才是该弹模态告警的
                        DebugLogger.w(TAG, "onFailure: 不可恢复失败（$failureType），阻断自动重连")
                        reconnectBlocked = true
                        _webSocketState.value = WebSocketRecoveryPolicy.afterFailure(
                            current = _webSocketState.value,
                            responseCode = response?.code,
                            reason = t.message
                        )
                    } else if (scheduleReconnect()) {
                        // D10：能自动重连时**不写 Failed**。否则 GenerationViewModel 会先弹一次
                        // 模态告警，紧接着 scheduleReconnect 又把状态写成 Reconnecting，
                        // 一次瞬时失败就闪一下对话框（StateFlow 合并下时有时无）。
                        DebugLogger.d(TAG, "onFailure: 已排自动重连，跳过模态失败态")
                    } else if (isOffline) {
                        // 离线态本来就没有 WebSocket（由离线看门狗负责恢复探测），
                        // 这里落 Failed 只会在界面闪一个"连接失败"，对用户毫无意义。
                        DebugLogger.d(TAG, "onFailure: 离线态忽略 WebSocket 失败")
                    } else {
                        // 排不上重连（手动重连中 / 被阻断）：这才是需要用户知道的失败终态
                        DebugLogger.w(TAG, "onFailure: 自动重连被跳过，落失败终态")
                        _webSocketState.value = WebSocketRecoveryPolicy.afterFailure(
                            current = _webSocketState.value,
                            responseCode = response?.code,
                            reason = t.message
                        )
                    }
                }
                if (!applied) return
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // NEW-2：同上，判代次 + 收尾 + 排重连整体在锁内
                runIfCurrent {
                    val elapsedMs = connectedElapsedMs()
                    logWsInfo(
                        "[WS] onClosed code=$code reason=${reason.ifBlank { "<empty>" }} " +
                            "elapsed=${elapsedMs / 1000.0}s"
                    )
                    connectedAtElapsedMs = 0L
                    stopKeepalive()
                    _vramUsageFraction.value = null
                    connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
                    // 无论 code 是多少都先收尾为 Disconnected，再决定要不要重连（条目 4.1）
                    val outcome = WebSocketRecoveryPolicy.afterClosed(
                        current = _webSocketState.value,
                        code = code
                    )
                    _webSocketState.value = outcome.state
                    if (outcome.reconnect) {
                        scheduleReconnect()
                    }
                }
            }
        }

        val started = client.openWebSocket(id, listener)
        if (!started) {
            // 没建起来（例如没有可用协议）也必须收尾，不能把状态丢在 Connecting
            DebugLogger.w(TAG, "WebSocket 启动失败（无可用协议），收敛为 Failed")
            connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
            _webSocketState.value = WebSocketRecoveryPolicy.afterFailure(
                current = _webSocketState.value,
                responseCode = null,
                reason = "无法建立 WebSocket（无可用协议）"
            )
        }
        return started
    }

    /**
     * Close the active WebSocket connection.
     */
    fun closeWebSocket() {
        logWsInfo("[WS] close reason=manual elapsed=${connectedElapsedMs() / 1000.0}s")
        closeWebSocketInternal()
    }

    /**
     * 主动关闭/重建前作废旧监听器（D1）。
     *
     * 只有 [openWebSocket]（新建）与 [closeWebSocketInternal]（主动关闭）会在代次上留痕
     * 是不够的：`retrySingleAttempt` / `attemptSilentReconnect` / `reconnectWebSocket`
     * 也会直接 `client.closeWebSocket()`，旧 socket 的 onFailure/onClosed 仍可能在
     * close() 之后才回调。没有递增代次时，这些迟到回调会被当成"当前连接"的失败，
     * 把状态写成 Failed 并再排一次重连，随后把刚建立的新连接关掉 —— 多跑一整轮重连、
     * 界面状态闪断。
     *
     * 写侧可能来自主线程，也可能来自 OkHttp 回调线程（见字段 KDoc），因此递增走
     * [WebSocketGenerationCounter.next]（锁内"读-改-写"），保证并发调用两两不同号。
     *
     * @return 本次递增后的代次（交给新监听器捕获）
     */
    private fun invalidateWebSocketCallbacks(): Long {
        val generation = webSocketGeneration.next()
        connectingSinceElapsedMs = WebSocketRecoveryPolicy.NO_CONNECTING_SINCE
        return generation
    }

    /**
     * Internal close without logging (used during disconnect)
     */
    // B4.2-A：离线控制器切离线要关 WebSocket，开放 internal
    internal fun closeWebSocketInternal() {
        stopKeepalive()
        _vramUsageFraction.value = null
        // 主动关闭：先让当前监听器的回调作废，避免 close() 触发的 onFailure
        // 又把状态写成 Failed 并排一次多余的重连
        invalidateWebSocketCallbacks()
        _client?.closeWebSocket()
        connectedAtElapsedMs = 0L
        _webSocketState.value = WebSocketState.Disconnected
    }

    /**
     * Parse text WebSocket message and emit typed event
     */
    private fun parseAndEmitMessage(text: String) {
        try {
            val message = JSONObject(text)
            val messageType = message.optString("type")

            val wsMessage: WebSocketMessage? = when (messageType) {
                "executing" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id")
                    val node = data?.optString("node")
                    val isComplete = data?.isNull("node") == true

                    if (isComplete && !promptId.isNullOrEmpty()) {
                        DebugLogger.i(TAG, "WS: executing complete (promptId: ${Obfuscator.promptId(promptId)})")
                        // Notify JobRegistry of completion (JobRegistry handles gallery refresh)
                        JobRegistry.markCompleted(promptId)
                        WebSocketMessage.ExecutionComplete(promptId)
                    } else {
                        WebSocketMessage.Executing(promptId, node)
                    }
                }
                "progress" -> {
                    val data = message.optJSONObject("data")
                    val value = data?.optInt("value", 0) ?: 0
                    val max = data?.optInt("max", 0) ?: 0
                    if (max > 0) WebSocketMessage.Progress(value, max) else null
                }
                "execution_start" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id") ?: ""
                    DebugLogger.i(TAG, "WS: execution_start (promptId: ${Obfuscator.promptId(promptId)})")
                    // Notify JobRegistry that this job is now executing
                    if (promptId.isNotEmpty()) {
                        JobRegistry.markExecuting(promptId)
                    }
                    WebSocketMessage.ExecutionStart(promptId)
                }
                "execution_error" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id")
                    val errorMsg = data?.optString("exception_message", "Unknown error") ?: "Unknown error"
                    DebugLogger.e(TAG, "WS: execution_error (promptId: ${Obfuscator.promptId(promptId)})")
                    // Notify JobRegistry of the failure
                    if (!promptId.isNullOrEmpty()) {
                        JobRegistry.markFailed(promptId)
                    }
                    WebSocketMessage.ExecutionError(promptId, errorMsg)
                }
                "execution_success" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id") ?: ""
                    DebugLogger.i(TAG, "WS: execution_success (promptId: ${Obfuscator.promptId(promptId)})")
                    // Also notify JobRegistry - execution_success is another completion signal
                    if (promptId.isNotEmpty()) {
                        JobRegistry.markCompleted(promptId)
                    }
                    WebSocketMessage.ExecutionSuccess(promptId)
                }
                "execution_cached" -> {
                    val data = message.optJSONObject("data")
                    val nodes = data?.optJSONArray("nodes")?.length() ?: 0
                    WebSocketMessage.ExecutionCached(nodes)
                }
                "status" -> {
                    val data = message.optJSONObject("data")
                    val status = data?.optJSONObject("status")
                    val execInfo = status?.optJSONObject("exec_info")
                    val queueRemaining = execInfo?.optInt("queue_remaining") ?: 0
                    // Update JobRegistry with queue size
                    JobRegistry.updateFromStatus(queueRemaining)
                    WebSocketMessage.Status(queueRemaining)
                }
                "previewing", "executed" -> null  // Don't emit for these types
                "crystools.monitor" -> {
                    // crystools 的监控推送：按「已用 / 总量」算成 0..1 的占用比例。
                    //
                    // 2026-11-27 修正：crystools 的 vram_used / vram_total 是 NVML 原始**字节数**
                    // （见 ComfyUI-Crystools/general/gpu.py），不是 GB。之前原样当 GB 传出去，
                    // 而消费方（提示词增强页的显存曲线）按 0..1 比例使用 —— 只要有占用就恒满格。
                    // 这里直接算比例，字段名也改成 vramUsageFraction 以免再被误用。
                    val gpu = message.optJSONObject("data")
                        ?.optJSONArray("gpus")
                        ?.optJSONObject(0)
                    val used = gpu?.optDouble("vram_used", -1.0) ?: -1.0
                    val total = gpu?.optDouble("vram_total", -1.0) ?: -1.0
                    _vramUsageFraction.value = if (used >= 0.0 && total > 0.0) {
                        (used / total).toFloat().coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    null
                }
                else -> {
                    if (messageType.isNotEmpty()) WebSocketMessage.Unknown(messageType) else null
                }
            }

            wsMessage?.let {
                scope.launch { _webSocketMessages.emit(it) }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "WS: failed to parse message: ${e.message}")
        }
    }

    /**
     * Parse binary WebSocket message (preview images) and emit
     *
     * P1-27：数据量有上限、解码有采样。修复前这里是无条件
     * `BitmapFactory.decodeByteArray` 全尺寸解码 —— 大图预览会直接把内存打爆。
     */
    private fun parseAndEmitBinaryMessage(bytes: ByteString) {
        if (bytes.size <= BINARY_HEADER_BYTES) return

        val payloadSize = bytes.size - BINARY_HEADER_BYTES
        if (!shouldDecodePreview(payloadSize)) {
            DebugLogger.w(
                TAG,
                "WS: 丢弃超限预览帧（${payloadSize / (1024 * 1024)}MB > " +
                    "${MAX_PREVIEW_BYTES / (1024 * 1024)}MB）"
            )
            return
        }

        try {
            val pngBytes = bytes.substring(BINARY_HEADER_BYTES).toByteArray()
            val bitmap = decodePreviewSampled(pngBytes)
            if (bitmap != null) {
                scope.launch { _webSocketMessages.emit(WebSocketMessage.PreviewImage(bitmap)) }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "WS: failed to decode preview: ${e.message}")
        } catch (e: OutOfMemoryError) {
            // 采样之后仍然 OOM：这一帧放弃，绝不让预览链路把进程拖垮
            DebugLogger.w(TAG, "WS: preview decode OOM, frame dropped")
        }
    }

    /**
     * 预览图按长边上限采样解码（P1-27）。
     *
     * 两步走：先只读尺寸（inJustDecodeBounds），再按 2 的幂采样解码，
     * 保证解码后长边不超过 [PREVIEW_DECODE_DIMENSION]。
     */
    private fun decodePreviewSampled(imageBytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sh.hnet.comfychair.util.BitmapDecodeUtils.sampleSizeFor(
                longest, PREVIEW_DECODE_DIMENSION
            )
        }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    }

    /**
     * Start keepalive ping mechanism
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        logWsInfo("[WS] ping interval = ${keepaliveIntervalMs / 1000}s (effective app-layer)")
        keepaliveJob = scope.launch {
            while (isActive && _webSocketState.value is WebSocketState.Connected) {
                // 每轮读当前间隔（v4.18.x：降级放宽 / 回前台收紧，值由 updateKeepaliveInterval 维护）
                delay(keepaliveIntervalMs)
                if (_webSocketState.value is WebSocketState.Connected) {
                    _client?.sendWebSocketMessage("{\"type\":\"ping\"}")
                }
            }
        }
    }

    /**
     * Stop keepalive ping mechanism
     */
    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /**
     * 更新应用层心跳间隔（v4.18.x 后台省流量）。
     *
     * 放宽（30s → 120s/240s）：写变量即可，心跳循环每轮读值，旧 delay 最多 30 秒后生效，
     * 行为平滑；收紧（120s/240s → 30s，即回前台）：立即重启心跳协程，否则最长要等
     * 一个旧间隔（2-4 分钟）才恢复前台节奏。
     */
    fun updateKeepaliveInterval(intervalMs: Long) {
        val previous = keepaliveIntervalMs
        keepaliveIntervalMs = intervalMs
        logWsInfo(
            "[WS] ping interval = ${intervalMs / 1000}s (effective app-layer, previous=${previous / 1000}s)"
        )
        if (intervalMs < previous && keepaliveJob?.isActive == true) {
            startKeepalive()
        }
    }

    /**
     * 后台降级：主动挂起 WebSocket（v4.18.x，蜂窝 + 未开「后台保持连接」时）。
     *
     * 与 [disconnect] 的本质区别：**只断 WS 通道，连接会话（client / 凭据 / 状态流）原样保留**
     * —— 解除挂起（[resumeWebSocketFromSuspension]）直接 [openWebSocket] 秒回，无需重走握手。
     *
     * 处理顺序：置阻断（拦新重连）→ 取消已在退避睡眠的重连 job（否则它到点仍会
     * testConnection + openWebSocket，把刚挂起的连接又建回来）→ 走 [closeWebSocketInternal]
     * 干净收尾（停心跳 + 作废回调 + 关 socket + Disconnected，不落 Failed —— 挂起不是
     * 故障，不该触发模态告警）。
     */
    @Synchronized
    fun suspendWebSocketForBackground(reason: String) {
        if (backgroundSuspended) return
        backgroundSuspended = true
        logWsInfo("[WS] suspend reason=$reason elapsed=${connectedElapsedMs() / 1000.0}s")
        reconnectBlocked = true
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        closeWebSocketInternal()
    }

    /**
     * 解除降级挂起：恢复被 [suspendWebSocketForBackground] 断开的 WebSocket。
     *
     * 触发场景：回前台，或降级态中策略切回「保持连接」（WiFi 恢复 / 用户打开开关）。
     * 只处理降级挂起（[backgroundSuspended]）；登出 / 认证失败设下的 [reconnectBlocked]
     * 不动。连接会话仍在（HTTP 层 Connected）且 WS 不在 Connected/Connecting 时直接重建；
     * 若期间 WS 因别的原因已断，重建失败的 onFailure 会照常走自动重连兜底。
     */
    fun resumeWebSocketFromSuspension() {
        if (!backgroundSuspended) return
        backgroundSuspended = false
        reconnectBlocked = false
        reconnectAttempts = 0
        if (_client == null) return
        if (connectionState.value !is ConnectionState.Connected) return
        val wsState = _webSocketState.value
        if (wsState is WebSocketState.Connected || wsState is WebSocketState.Connecting) return
        logWsInfo("[WS] resume reason=background-suspension-cleared")
        openWebSocket()
    }

    /**
     * Schedule WebSocket reconnection with exponential backoff.
     *
     * @return true 表示本次已排上重连（状态已写成 [WebSocketState.Reconnecting]）；
     *         false 表示被离线/手动重连中/被阻断拦下，**没有**任何状态写入，
     *         调用方需要自己收尾（见 [onFailure] 的失败终态）。
     */
    private fun scheduleReconnect(): Boolean {
        // 收尾过渡态（条目 4.1）：下面三个提前 return 分支都不写状态，
        // 若此时还停在 Connecting / Reconnecting，界面会永久显示"连接中/重连中"。
        val collapsed = WebSocketRecoveryPolicy.collapseTransitional(_webSocketState.value)
        if (collapsed != _webSocketState.value) {
            DebugLogger.d(TAG, "scheduleReconnect: 收敛过渡态 ${_webSocketState.value} -> $collapsed")
            _webSocketState.value = collapsed
        }

        // 已切到离线态：不重连（离线由看门狗负责探测恢复）
        if (isOffline) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (offline mode)")
            return false
        }

        // Don't auto-reconnect if manual retry or ensureConnection is in progress
        if (_isReconnecting.value || _isConnecting.value) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (manual reconnection in progress)")
            return false
        }

        if (reconnectBlocked) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (blocked: logout / auth failure / offline)")
            return false
        }

        reconnectAttempts++
        // 第二个参数 0 表示"无次数上限"（该字段仅用于状态描述，界面未读取）
        _webSocketState.value = WebSocketState.Reconnecting(reconnectAttempts, 0)

        // 指数退避：2s, 4s, 8s, 16s, 30s（封顶），之后保持 30s 一次，
        // 网络恢复后自动接上，不再于 12 秒后彻底放弃
        val delayMs = minOf(2000L shl minOf(reconnectAttempts - 1, 4), MAX_RECONNECT_BACKOFF_MS)
        logWsInfo("[WS] reconnect attempt=$reconnectAttempts delay=${delayMs}ms")
        val sessionId = reconnectionSessionId  // Capture current session

        pendingReconnectJob = scope.launch {
            delay(delayMs)
            // Only proceed if session still valid (wasn't cancelled by manual retry)
            if (isSessionValid(sessionId)) {
                reconnectWebSocket(sessionId)
            } else {
                DebugLogger.d(TAG, "scheduleReconnect: cancelled (session invalidated)")
            }
        }
        return true
    }

    /**
     * Attempt to reconnect WebSocket
     * @param sessionId The reconnection session ID for stale callback detection
     */
    private fun reconnectWebSocket(sessionId: Long) {
        val client = _client ?: run {
            // 没有客户端就没法重连；不能把状态丢在 Reconnecting 里等一个永远不会来的回调
            DebugLogger.w(TAG, "reconnectWebSocket: client 为空，收敛状态")
            _webSocketState.value = WebSocketRecoveryPolicy.collapseTransitional(_webSocketState.value)
            return
        }

        // NEW-1：退避期间可能已有别的入口把 WebSocket 建好（图库/主容器的
        // generationViewModel.initialize 会直接 openWebSocket，且不取消这个退避 job）。
        // 重连目标已经达成，绝不能再走下面的"作废代次 + 关 socket"：本次关闭触发的
        // onClosed 会因代次已作废被丢弃，状态永久停在 Connected，而 openWebSocket
        // 又会因 ALREADY_CONNECTED 拒绝重建 —— 不重启 App 无法自愈。
        if (WebSocketRecoveryPolicy.shouldSkipReconnect(_webSocketState.value)) {
            DebugLogger.d(TAG, "reconnectWebSocket: 已处于 Connected，跳过本轮重连")
            return
        }

        // D1：每次重连都会换一条 socket，先作废旧监听器再关，避免旧回调把新一轮的
        // 结果打回去（否则会多跑一整轮重连并造成状态闪断）
        invalidateWebSocketCallbacks()
        // NEW-1：代次已作废，本次关闭的 onClosed/onFailure 都会被丢弃，所以必须由这里
        // 自己把状态从原值挪开。写 Reconnecting（而不是留给后面）有两个作用：
        // 1) 后续 openWebSocket 不会命中 ALREADY_CONNECTED，一定会重建；
        // 2) 即使 testConnection 回调被 session 校验丢掉，界面也停在"重连中"而不是
        //    "已连接"这种假象（transitional 由调用方/下一轮收敛）。
        _webSocketState.value = WebSocketState.Reconnecting(reconnectAttempts, 0)
        client.closeWebSocket()

        client.testConnection { success, errorMessage, _, failureType ->
            // Ignore stale callback if session has changed
            if (!isSessionValid(sessionId)) {
                DebugLogger.d(TAG, "reconnectWebSocket: ignoring stale callback (session $sessionId)")
                return@testConnection
            }

            if (success) {
                reportConnectivitySuccess()
                openWebSocket()
            } else if (failureType == ConnectionFailure.AUTHENTICATION) {
                // Auth failure - don't retry, notify UI immediately
                DebugLogger.w(TAG, "Authentication failed during reconnection")
                reconnectBlocked = true  // 认证失败重试无意义，等用户手动重试
                _webSocketState.value = WebSocketState.Failed(
                    reason = errorMessage,
                    failureType = ConnectionFailure.AUTHENTICATION
                )
            } else {
                // Network error - 先喂给自动切换判定（连续 2 次失败即切离线），再决定是否重试
                reportConnectivityFailure(failureType)
                scheduleReconnect()
            }
        }
    }

    /**
     * Poll the server queue and update JobRegistry.
     * Used to validate jobs on app restart and sync state.
     *
     * P1-5：`/queue` 的响应是**请求发出那一刻**的快照。必须先取登记水位再发请求，
     * 否则请求在飞期间刚 registerJob 的任务（必然不在这份快照里）会被对账逻辑
     * 当成"服务器上已不存在"删掉 —— 表现就是"刚提交的任务从本地列表消失、
     * 按钮计数回退，而服务器其实正在跑"。
     */
    fun pollQueueStatus() {
        val client = _client ?: return
        val snapshotSeq = JobRegistry.beginQueueSnapshot()
        client.fetchQueue { queueJson ->
            if (queueJson != null) {
                val running = queueJson.optJSONArray("queue_running")
                val pending = queueJson.optJSONArray("queue_pending")
                JobRegistry.updateFromServerQueue(running, pending, snapshotSeq)
            }
        }
    }

    // Model cache and node registry management（B4-W2：装配/提取实现已迁入 ModelListCatalog，此处仅转发）

    /**
     * Refresh server data (node types and model lists).
     * Can be called by the user to update model lists without reconnecting.
     *
     * B4-W2：实现已迁入 [ModelListCatalog.fetchServerData]；这里保留会话侧判定
     * （client 是否存在）与原日志，随后转发。
     */
    fun refreshServerData() {
        if (_client == null) {
            DebugLogger.w(TAG, "refreshServerData: Not connected")
            return
        }
        DebugLogger.i(TAG, "Refreshing server data")
        catalog.fetchServerData(_client, _applicationContext, currentServerId)
    }

    /**
     * G7 指令 1 的「必检」入口：后台返回 / 重连收敛后，按纯函数判定要不要重拉模型列表。
     *
     * 调用点有两个，缺一不可（这正是历史 Q3「修了没生效」的补位）：
     * 1. [MainContainerActivity] 的 onResume —— 进程存活、连接没断的普通回前台；
     * 2. WebSocket `onOpen`（重连收敛后）—— 长时间后台后 WebSocket 被掐、需要静默重连的路径。
     *    只在 onResume 检会有时序洞：此刻连接状态机常常还在过渡态（重连在飞），
     *    直接发请求要么被闸门挡掉、要么打到即将被 [connect] 重建的旧 client 上（再次被吞）。
     *    所以过渡态下这里**不发起**，留到 onOpen 收敛后再检一次。
     *
     * 判据全部交给 ModelListRecovery 的 reloadDecision（纯函数、有单测）：
     * - 已有数据且三类健康 → SKIP_HEALTHY，**绝不清空已有列表**；
     * - 三类未全终态（E7 僵尸加载）或任一 FAILED 或核心列表全空 → RELOAD 一次。
     *
     * B4-W2：判定与实现已迁入 [ModelListCatalog.ensureModelListLoadedAfterResume]；
     * 这里只计算会话侧的 connectionSettled 并转发（catalog 不持 ConnectionManager 引用）。
     */
    fun ensureModelListLoadedAfterResume() {
        // 连接是否收敛到稳定态：离线态不算（它靠离线缓存填充，不该打在线请求）
        val connectionSettled = _client != null &&
            _connectionState.value is ConnectionState.Connected
        catalog.ensureModelListLoadedAfterResume(
            client = _client,
            connectionSettled = connectionSettled,
            context = _applicationContext,
            serverId = currentServerId
        )
    }

    /**
     * 单独重试某一类核心模型（G8 指令 1「任一失败必须可见并可单独重试」）。
     *
     * 三类同源（都来自同一次 /object_info 提取），因此「单独重试 UNET」在实现上等价于
     * 重新拉一次 /object_info —— 但**调用语义是单类**：UI 只在 UNET 行给出重试入口，
     * 拉取成功后三类各自按 [diagnoseCategory] 重新上报终态，UNET 若仍失败会带着
     * 节点缺失/字段缺失的原因继续可见。用 [refreshServerData] 相同的连接前置条件。
     *
     * B4-W2：实现已迁入 [ModelListCatalog.fetchServerData]（category 当前仅用于观测日志，
     * 三类同源重拉一次即等价）；这里保留会话侧判定与原日志，随后转发。
     */
    fun retryCoreCategory(category: CoreCategory?) {
        DebugLogger.i(TAG, "单独重试模型类别：$category")
        if (_client == null) {
            DebugLogger.w(TAG, "retryCoreCategory: Not connected")
            return
        }
        catalog.fetchServerData(_client, _applicationContext, currentServerId)
    }

    // ── 离线自动切换（看门狗）────────────────────────────────────────────
    // 设计要点（用户已确认的三条决策）：
    // 1. 连不上自动进离线、恢复后自动回在线，不再弹窗询问；
    // 2. 没有可用缓存时不自动进离线——进去也是空白，应明确报连接失败；
    // 3. 防抖动由 ConnectionStability 的"连续 N 次 + 冷却"决定，界面不反复横跳。

    /**
     * 进入离线模式（自动切换 / 登录页 / 弹窗按钮 / 设置页强制离线 的统一入口）。
     *
     * B4.2-A：实现迁入 [OfflineModeController.enterOfflineMode]，本方法保留签名
     * 并转发（全仓调用方零改动）；@Synchronized 锁本单例，与迁移前一致。
     *
     * @param context 上下文
     * @param server 目标服务器（提供 serverId 与恢复在线所需的地址）；null 时用已记住的会话
     * @param credentials 该服务器的凭据
     * @param protocol 上次可用的协议；null 时取当前客户端记录的协议
     * @return true 表示已进入离线态；false 表示没有可用缓存（调用方应报连接失败）
     */
    @Synchronized
    fun enterOfflineMode(
        context: Context,
        server: Server? = null,
        credentials: AuthCredentials = AuthCredentials.None,
        protocol: String? = null
    ): Boolean = offlineController.enterOfflineMode(context, server, credentials, protocol)

    /**
     * 设置「强制离线」（设置页开关）。
     *
     * B4.2-A：实现迁入 [OfflineModeController.setForcedOffline]，本方法保留签名
     * 并转发；失败回滚（置标志失败即回滚）与 @Synchronized 语义均保持不变。
     *
     * @return 打开时返回是否成功进入离线态（没有可用缓存时为 false，调用方应提示用户）
     */
    @Synchronized
    fun setForcedOffline(context: Context, forced: Boolean): Boolean =
        offlineController.setForcedOffline(context, forced)

    /**
     * 确保存在可用的客户端实例（不打开 WebSocket）。
     * 离线态下的周期探测与恢复在线都要用它；启动即离线时这里才第一次建客户端。
     */
    // B4.2-A：离线控制器 enterOfflineModeInternal 第八步要调，开放 internal（锁语义不变）
    @Synchronized
    internal fun ensureClientForSession(context: Context): Boolean {
        if (_client != null) return true

        val session = resolveSession(context) ?: run {
            DebugLogger.w(TAG, "ensureClientForSession: 没有可用的服务器信息")
            return false
        }

        _clientId = _clientId ?: "comfychair_android_${UUID.randomUUID()}"
        _client = ComfyUIClient(
            context,
            session.hostname,
            session.port,
            session.credentials,
            session.serverId,
            preferredProtocol = session.protocol
        ).apply {
            setWorkingProtocol(session.protocol)
            setClientId(_clientId!!)
            setOnSessionExpired {
                DebugLogger.w(TAG, "Browser auth session expired — notifying UI")
                _sessionExpired.value = true
            }
        }
        DebugLogger.d(TAG, "离线态客户端已就绪（未连接）")
        return true
    }

    /**
     * 取当前会话参数；没有记录过会话时回落到"已选服务器 + 其凭据"。
     * 协议沿用上次成功的那个，恢复在线时 testConnection 会重新校验并纠正。
     */
    // B4.2-A：离线控制器恢复在线 / 备客户端要调，开放 internal（锁语义不变）
    @Synchronized
    internal fun resolveSession(context: Context): OnlineSession? {
        lastSession?.let { return it }

        val server = ServerStorage(context).getSelectedServer() ?: return null
        val credentials = CredentialStorage(context).getCredentials(server.id, server.authType)
        return OnlineSession(
            serverId = server.id,
            hostname = server.hostname,
            port = server.port,
            protocol = server.protocol ?: _client?.getWorkingProtocol() ?: "https",
            credentials = credentials
        )
    }

    /**
     * 把一次连通性结果喂给判定器，并在满足条件时执行自动切换。
     *
     * 可被任意线程调用（WebSocket 回调也在其中）：判定与计数在同步块内完成，
     * 真正的切换动作投递到主线程执行——它要碰 MediaCache / GalleryRepository。
     */
    // B4.2-A：离线看门狗的探测结果要从 controller 汇报进来，开放 internal
    internal fun reportProbeOutcome(outcome: ProbeOutcome) {
        val decision = synchronized(this) {
            val evaluated = ConnectionStability.evaluate(
                stabilityState,
                outcome,
                SystemClock.elapsedRealtime()
            )
            stabilityState = evaluated.nextState
            evaluated
        }

        when (decision.switch) {
            StabilitySwitch.TO_OFFLINE -> {
                DebugLogger.i(TAG, "连续 ${ConnectionStability.REQUIRED_FAILURES} 次失败：自动切到离线")
                scope.launch { offlineController.enterOfflineModeInternal(_applicationContext) }
            }
            StabilitySwitch.TO_ONLINE -> {
                DebugLogger.i(TAG, "连续 ${ConnectionStability.REQUIRED_SUCCESSES} 次成功：自动回到在线")
                scope.launch { offlineController.resumeOnlineInternal() }
            }
            null -> Unit
        }
    }

    /** 一次"服务器可达"的正面信号。 */
    private fun reportConnectivitySuccess() {
        reportProbeOutcome(ProbeOutcome.SUCCESS)
    }

    /**
     * 一次连接失败信号。
     *
     * 只有 [ConnectionFailure.NETWORK] 计入自动切换：认证失败说明服务器是活的
     * （网络通、只是凭据不对），无效服务器/传输停滞也不代表"网络不可达"，
     * 切到离线都帮不上忙，反而会让用户莫名其妙失去连接。
     */
    private fun reportConnectivityFailure(failureType: ConnectionFailure) {
        if (failureType != ConnectionFailure.NETWORK) {
            DebugLogger.d(TAG, "自动切换：忽略 $failureType（非网络不可达）")
            return
        }
        reportProbeOutcome(ProbeOutcome.FAILURE)
    }

    // B4.2-A：startOfflineProbeIfAllowed / stopOfflineProbe / probeOnce（离线看门狗）
    // 已整体迁入 OfflineModeController（探测循环与 reportProbeOutcome 判定器衔接不变）。

    /**
     * Load server data from offline cache.
     *
     * B4.2-A：实现迁入 [OfflineModeController.loadFromOfflineCache]，本方法保留
     * 签名并转发（全仓调用方零改动）。
     *
     * @param context Application context for cache access
     * @param serverId Server ID to load cache for
     * @return true if cache was loaded successfully, false otherwise
     */
    fun loadFromOfflineCache(context: Context, serverId: String): Boolean =
        offlineController.loadFromOfflineCache(context, serverId)

    /**
     * Check if offline cache is available for a server.
     *
     * B4.2-A：实现迁入 [OfflineModeController.hasOfflineCache]，本方法保留签名并转发。
     *
     * @param context Application context for cache access
     * @param serverId Server ID to check
     * @return true if cache exists, false otherwise
     */
    fun hasOfflineCache(context: Context, serverId: String): Boolean =
        offlineController.hasOfflineCache(context, serverId)
}

