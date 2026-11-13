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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.ModelIntroSync
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ObjectInfoCache
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SingleFlight
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
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
 */
object ConnectionManager {
    private const val TAG = "Connection"
    private const val KEEPALIVE_INTERVAL_MS = 30000L

    /** 重连退避封顶：2s→4s→8s→16s→30s，之后保持 30s 一次，直到连上或被显式阻断。 */
    private const val MAX_RECONNECT_BACKOFF_MS = 30_000L

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
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

    private var _client: ComfyUIClient? = null
    private var _clientId: String? = null
    private var _applicationContext: Context? = null

    // Shared node type registry (populated from /object_info)
    val nodeTypeRegistry = NodeTypeRegistry()

    // Shared model cache (populated from /object_info)
    private val _modelCache = MutableStateFlow(ModelCache())
    val modelCache: StateFlow<ModelCache> = _modelCache.asStateFlow()

    // 节点类型注册表是否已就绪（/object_info 已解析完成）。
    // 通用节点参数引擎的类型推导依赖它：冷启动时 /object_info 是异步拉的（约数秒），
    // 首次解析动态参数时注册表还是空的 → 控件全部退化成文本框；
    // 订阅这个信号可以在就绪后**重算**当前工作流的动态参数，无需用户切走再切回。
    private val _nodeTypeRegistryReady = MutableStateFlow(false)
    val nodeTypeRegistryReady: StateFlow<Boolean> = _nodeTypeRegistryReady.asStateFlow()

    /**
     * 模型介绍数据同步的并发闸门。
     *
     * 连接成功与用户手点刷新都会走到 fetchServerData，两条链路可能在几毫秒内先后触发；
     * 拉取本身幂等，重复做一遍没有任何收益，所以用跳过式闸门保证同一时刻至多一个在飞
     * （顺带也就实现了"防连点"）。
     */
    private val introSyncInFlight = SingleFlight()

    /**
     * 介绍数据热更新的执行编排（闸门 + 异常兜底 + 收尾都在里面）。
     *
     * 为什么不直接在 [syncModelIntros] 里写 try/catch：`fetchModelIntros` 是异步的，
     * 异常边界必须同时覆盖"回调内部"与"发起阶段同步抛出"两条路径，而后者一旦漏掉
     * 就会**卡死闸门 + 中断 `/object_info` 回调的模型列表装配**。这段逻辑抽到
     * [ModelIntroSyncRunner] 后可以在 JVM 单测里逐条钉住（见 `ModelIntroSyncRunnerTest`）。
     */
    private val introSyncRunner = ModelIntroSyncRunner(
        gate = introSyncInFlight,
        logInfo = { DebugLogger.i(TAG, it) },
        logWarn = { DebugLogger.w(TAG, it) },
    )

    // WebSocket management
    private var reconnectAttempts = 0

    /**
     * 自动重连是否被显式阻断（登出 / 认证失败 / 切离线）。
     *
     * 取代原先"把 attempts 顶到上限"的隐式做法。重连本身**不再设次数上限**：
     * 移动网络与隧道环境下 WebSocket 可能被中断较久，原来 3 次上限（约 12 秒）
     * 会在网络抖动期用完后彻底放弃，用户回来时就没有实时预览了。
     * 现在只有上述三种明确终止条件才停；网络恢复后自动接上。
     */
    private var reconnectBlocked = false
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Reconnection coordination - prevents race conditions between multiple reconnection paths
    private var reconnectionSessionId = 0L
    private var pendingReconnectJob: Job? = null

    // ── 离线自动切换 ────────────────────────────────────────────────────
    // 判定逻辑刻意抽到 ConnectionStability（零 Android 依赖，可在 JVM 单测里钉住）：
    // 连续 N 次失败才切离线、连续 N 次成功才切回、切换后有冷却。
    /** 判定器状态：连续计数与冷却起点 */
    private var stabilityState = StabilityState()

    /** 离线态下的周期探测任务 */
    private var offlineProbeJob: Job? = null

    /** 用户是否通过设置页「强制离线」明确要求不联网（此时不建 WebSocket、不做周期探测） */
    private var forcedOffline = false

    /** 最近一次会话的地址与凭据；离线态重建客户端、恢复在线、发起探测都要用 */
    private var lastSession: OnlineSession? = null

    /** 一次会话的参数快照（不可变） */
    private data class OnlineSession(
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
     * Clear the logout flag. Called after login screen checks it.
     */
    fun clearLogoutFlag() {
        isUserInitiatedLogout = false
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
     * 同样按「当前连接 → 上次会话 → 已保存配置」回退；已保存的 Server 没有协议字段，
     * 因此最后一级只能是 "http"（与项目原有默认一致）。
     */
    val protocol: String
        get() = (connectionState.value as? ConnectionState.Connected)?.protocol
            ?: lastSession?.protocol
            ?: "http"

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
        get() = connectionState.value is ConnectionState.OfflineConnected

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
        _client = ComfyUIClient(context.applicationContext, hostname, port, credentials).apply {
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
        DebugLogger.i(TAG, "Connected successfully")

        // 回到在线：清掉强制离线标记、重置防抖动判定、停止离线探测
        forcedOffline = false
        stabilityState = StabilityState()
        stopOfflineProbe()

        // Fetch server data (node types and model lists) after connection
        fetchServerData()
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
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        stopOfflineProbe()
        stabilityState = StabilityState()
        closeWebSocketInternal()
        invalidateAll()
        _client?.shutdown()
        _client = null
        _clientId = null
        _sessionExpired.value = false
        _connectionState.value = ConnectionState.Disconnected
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
        isUserInitiatedLogout = true
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
     * Show the connection alert dialog.
     * This is the single entry point for showing connection dialogs from anywhere in the app.
     *
     * 弹窗只负责告知失败与提供「重连 / 回登录页」；进离线由自动切换负责，不再问用户
     * （所以也不再需要按缓存是否存在决定按钮，`context` 仅为兼容调用方保留）。
     *
     * @param context Context（保留以兼容调用方签名）
     * @param failureType The type of connection failure
     */
    fun showConnectionAlert(context: Context, failureType: ConnectionFailure) {
        DebugLogger.d(TAG, "showConnectionAlert: failureType=$failureType")
        _connectionAlertState.value = ConnectionAlertState(failureType)
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

        // Always verify with HTTP test - don't trust cached state
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
                        val finalState = withTimeoutOrNull(10000L) {
                            _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                        }
                        _isConnecting.value = false
                        when (finalState) {
                            is WebSocketState.Connected -> {
                                DebugLogger.i(TAG, "ensureConnection: WebSocket connected")
                                onResult(true)
                            }
                            else -> {
                                DebugLogger.w(TAG, "ensureConnection: WebSocket failed")
                                showConnectionAlert(context, ConnectionFailure.NETWORK)
                                onResult(false)
                            }
                        }
                    }
                } else {
                    _isConnecting.value = false
                    DebugLogger.w(TAG, "ensureConnection: server unreachable, failureType=$failureType")
                    reportConnectivityFailure(failureType)
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
            Toast.makeText(context, R.string.msg_reconnect_failed, Toast.LENGTH_SHORT).show()
            return
        }

        DebugLogger.i(TAG, "User initiated single retry attempt (session $sessionId)")
        _webSocketState.value = WebSocketState.Disconnected

        client.closeWebSocket()
        DebugLogger.d(TAG, "retrySingleAttempt: closed WebSocket, calling testConnection")
        client.testConnection { success, errorMessage, _, failureType ->
            scope.launch {
                // Ignore stale callback if session has changed
                if (!isSessionValid(sessionId)) {
                    DebugLogger.d(TAG, "retrySingleAttempt: ignoring stale callback (session $sessionId)")
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
                    val finalState = withTimeoutOrNull(10000L) {
                        _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                    }

                    _isReconnecting.value = false
                    if (finalState is WebSocketState.Connected) {
                        DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                        clearConnectionAlert()
                    } else {
                        DebugLogger.w(TAG, "WebSocket failed to connect after successful HTTP test")
                        Toast.makeText(context, R.string.msg_reconnect_failed, Toast.LENGTH_SHORT).show()
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
        // Already connected or connecting - don't interfere
        if (_webSocketState.value is WebSocketState.Connected ||
            _webSocketState.value is WebSocketState.Connecting) {
            DebugLogger.d(TAG, "attemptSilentReconnect: state=${_webSocketState.value}, skipping")
            return
        }

        val client = _client ?: run {
            DebugLogger.w(TAG, "attemptSilentReconnect: client is null")
            return
        }

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        DebugLogger.i(TAG, "Attempting silent reconnect on resume")
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
        // Clear model cache and node registry
        _modelCache.value = ModelCache()
        nodeTypeRegistry.clear()
        _nodeTypeRegistryReady.value = false
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

        // Already connected or connecting
        if (_webSocketState.value is WebSocketState.Connected ||
            _webSocketState.value is WebSocketState.Connecting) {
            DebugLogger.d(TAG, "WebSocket already connected/connecting - skipping open")
            return true
        }

        DebugLogger.i(TAG, "Opening WebSocket connection")
        _webSocketState.value = WebSocketState.Connecting

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.i(TAG, "WebSocket connected")
                reconnectAttempts = 0
                reconnectBlocked = false
                reportConnectivitySuccess()
                _webSocketState.value = WebSocketState.Connected

                // Clear any pending connection dialog and reset flags
                // This handles the case where auto-reconnect succeeds while dialog is open
                if (_connectionAlertState.value != null) {
                    DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                    _connectionAlertState.value = null
                }
                _isReconnecting.value = false
                _isConnecting.value = false

                startKeepalive()
                GalleryRepository.getInstance().startBackgroundPreload()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmitMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parseAndEmitBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLogger.e(TAG, "WebSocket connection failed: ${t::class.simpleName}")
                stopKeepalive()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogger.i(TAG, "WebSocket closed (code: $code)")
                stopKeepalive()
                if (code != 1000) {
                    scheduleReconnect()
                } else {
                    _webSocketState.value = WebSocketState.Disconnected
                }
            }
        }

        return client.openWebSocket(id, listener)
    }

    /**
     * Close the active WebSocket connection.
     */
    fun closeWebSocket() {
        DebugLogger.i(TAG, "Closing WebSocket connection")
        closeWebSocketInternal()
    }

    /**
     * Internal close without logging (used during disconnect)
     */
    private fun closeWebSocketInternal() {
        stopKeepalive()
        _client?.closeWebSocket()
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
     */
    private fun parseAndEmitBinaryMessage(bytes: ByteString) {
        if (bytes.size > 8) {
            try {
                val pngBytes = bytes.substring(8).toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bitmap != null) {
                    scope.launch { _webSocketMessages.emit(WebSocketMessage.PreviewImage(bitmap)) }
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "WS: failed to decode preview: ${e.message}")
            }
        }
    }

    /**
     * Start keepalive ping mechanism
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && _webSocketState.value is WebSocketState.Connected) {
                delay(KEEPALIVE_INTERVAL_MS)
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
     * Schedule WebSocket reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        // 已切到离线态：不重连（离线由看门狗负责探测恢复）
        if (isOffline) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (offline mode)")
            return
        }

        // Don't auto-reconnect if manual retry or ensureConnection is in progress
        if (_isReconnecting.value || _isConnecting.value) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (manual reconnection in progress)")
            return
        }

        if (reconnectBlocked) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (blocked: logout / auth failure / offline)")
            return
        }

        reconnectAttempts++
        // 第二个参数 0 表示"无次数上限"（该字段仅用于状态描述，界面未读取）
        _webSocketState.value = WebSocketState.Reconnecting(reconnectAttempts, 0)

        // 指数退避：2s, 4s, 8s, 16s, 30s（封顶），之后保持 30s 一次，
        // 网络恢复后自动接上，不再于 12 秒后彻底放弃
        val delayMs = minOf(2000L shl minOf(reconnectAttempts - 1, 4), MAX_RECONNECT_BACKOFF_MS)
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
    }

    /**
     * Attempt to reconnect WebSocket
     * @param sessionId The reconnection session ID for stale callback detection
     */
    private fun reconnectWebSocket(sessionId: Long) {
        val client = _client ?: return

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
     */
    fun pollQueueStatus() {
        val client = _client ?: return
        client.fetchQueue { queueJson ->
            if (queueJson != null) {
                val running = queueJson.optJSONArray("queue_running")
                val pending = queueJson.optJSONArray("queue_pending")
                JobRegistry.updateFromServerQueue(running, pending)
            }
        }
    }

    // Model cache and node registry management

    /**
     * Fetch server data (node types and model lists) from /object_info.
     * Called automatically on connection and can be triggered manually via [refreshServerData].
     */
    private fun fetchServerData() {
        val client = _client ?: run {
            DebugLogger.w(TAG, "fetchServerData: Client not available")
            return
        }

        DebugLogger.i(TAG, "Fetching server data from /object_info")
        _modelCache.value = _modelCache.value.copy(isLoading = true, lastError = null)

        // 【秒出】先用磁盘缓存把列表铺上，网络最新版回来后再静默替换
        primeModelCacheFromDisk()

        client.fetchFullObjectInfo { objectInfo ->
            if (objectInfo != null) {
                // Parse node definitions for workflow editor
                nodeTypeRegistry.parseObjectInfo(objectInfo)
                // 注册表就绪信号（动态参数类型推导的可用前提），订阅者据此重算
                _nodeTypeRegistryReady.value = nodeTypeRegistry.isPopulated()

                // 模型介绍表：与模型列表同批加载（刷新模型列表也就等于刷新介绍）。
                // 表本身随 APK 内置、之后可被服务器上的版本覆盖，加载失败只是没有介绍，
                // 不影响模型选择。
                _applicationContext?.let { ModelIntroTable.ensureLoaded(it) }

                // 模型介绍**热更新**：把服务器上的介绍数据取回来（新下载的模型不必等发版）。
                // 必须排在 ensureLoaded 之后 —— 同步要用刚装好的本地表做比对基准；
                // 放在回调里而不是 fetchServerData 开头，是为了不跟 8MB 的 /object_info 抢带宽，
                // 也避免列表拉取失败时白拉一次介绍。
                syncModelIntros()

                // Cache object_info to disk for offline mode
                val ctx = _applicationContext
                val serverId = currentServerId
                if (ctx != null && serverId != null) {
                    ObjectInfoCache.saveObjectInfo(ctx, serverId, objectInfo)
                }

                // Extract model lists for generation screens (field-name-based discovery)
                val models = extractModelLists()
                _modelCache.value = models.copy(isLoaded = true, isLoading = false)
                DebugLogger.i(TAG, "Server data loaded: ${models.checkpoints.size} checkpoints, " +
                        "${models.unets.size} unets, ${models.vaes.size} vaes, " +
                        "${models.clips.size} clips, ${models.loras.size} loras")
                // 同时打到 logcat：DebugLogger 只存在内存里，现场排查「模型下拉为空」时
                // 需要能直接从 adb logcat 看到节点定义与各列表的规模
                android.util.Log.i(
                    "ComfyChairModels",
                    "节点定义已加载: nodes=${nodeTypeRegistry.getAllNodeTypes().size}, " +
                            "ckpt=${models.checkpoints.size}, unet=${models.unets.size}, " +
                            "vae=${models.vaes.size}, clip=${models.clips.size}, lora=${models.loras.size}"
                )
            } else {
                DebugLogger.w(TAG, "Failed to fetch server data")
                _modelCache.value = _modelCache.value.copy(
                    isLoading = false,
                    lastError = "Failed to fetch server data"
                )
            }
        }
    }

    /**
     * 把服务器上的模型介绍数据同步到本地（热更新）。
     *
     * 触发点只有一个：`fetchServerData` 的 `/object_info` 回调里（连接成功、用户手点刷新都汇到这里），
     * 满足"刷新模型列表时顺带刷新介绍"这条需求，又不必额外引入后台定时任务。
     *
     * 三条不打扰主流程的约定（前两条由 [ModelIntroSyncRunner] 兑现，见其类注释）：
     * 1. **跳过式并发保护**：同一时刻至多一个在飞，重复触发直接返回；
     * 2. **失败只记日志**：拉不到、装不上、甚至发起阶段抛出，都继续用本地那份（缓存 / 内置），
     *    绝不抛出、绝不弹错误，也绝不影响调用点后续的模型列表装配；
     * 3. **在这里解析并落盘是安全的**：回调运行在 OkHttp 的调度线程，不是主线程
     *    （`ModelIntroSync.apply` 要解析 250KB JSON 并写盘，放主线程会卡顿）。
     */
    private fun syncModelIntros() {
        val ctx = _applicationContext ?: return
        val client = _client ?: return

        // 闸门、异常兜底与收尾全在 runner 里（见 [ModelIntroSyncRunner] 的类注释）：
        // 无论拉取失败、数据不可用，还是发起阶段/回调内部抛出，都只记日志、绝不冒泡 ——
        // 本函数的调用点在 `/object_info` 回调中，那里紧接着还要装配模型列表。
        introSyncRunner.run(
            fetch = { callback -> client.fetchModelIntros(callback) },
            apply = { json ->
                val result = ModelIntroSync.apply(json, ModelIntroTable.cacheFile(ctx))
                DebugLogger.i(TAG, "介绍数据同步结果：$result")
            },
        )
    }

    /**
     * 用磁盘上的 `/object_info` 缓存**预填**模型列表（不联网、不阻塞）。
     *
     * 为什么需要它：这个接口实测 **8.1MB** —— 局域网上要十几秒，经 Cloudflare 隧道在移动网络
     * 下可能半分钟。这段时间里模型下拉是空的、LoRA 只剩「不使用」、采样器/调度器退化成纯文本
     * 输入框，用户会以为功能坏了。缓存里就是上次成功拉取的同一份数据，先用它把界面点亮。
     *
     * 只填一次（当前已有数据就跳过，也防旧缓存覆盖刚回来的新数据）；`isLoading` 保持 true，
     * 界面据此知道后台仍在刷新。
     */
    private fun primeModelCacheFromDisk() {
        if (_modelCache.value.isLoaded) return
        val ctx = _applicationContext ?: return
        val serverId = currentServerId ?: return
        if (!ObjectInfoCache.hasCache(ctx, serverId)) {
            DebugLogger.i(TAG, "无缓存可预填，等待网络返回")
            return
        }
        scope.launch(Dispatchers.IO) {
            val cached = ObjectInfoCache.loadObjectInfo(ctx, serverId)
            if (cached == null) {
                DebugLogger.w(TAG, "缓存读取失败，等待网络返回")
                return@launch
            }
            // 读盘期间网络可能已经回来了 —— 别用旧缓存盖掉新数据
            if (_modelCache.value.isLoaded) return@launch

            nodeTypeRegistry.parseObjectInfo(cached)
            _nodeTypeRegistryReady.value = nodeTypeRegistry.isPopulated()
            ModelIntroTable.ensureLoaded(ctx)
            val models = extractModelLists()
            _modelCache.value = models.copy(isLoaded = true, isLoading = true)
            android.util.Log.i(
                "ComfyChairModels",
                "已用磁盘缓存预填模型列表（后台仍在拉最新）: ckpt=${models.checkpoints.size}, " +
                        "unet=${models.unets.size}, vae=${models.vaes.size}, " +
                        "clip=${models.clips.size}, lora=${models.loras.size}"
            )
        }
    }

    /**
     * Refresh server data (node types and model lists).
     * Can be called by the user to update model lists without reconnecting.
     */
    fun refreshServerData() {
        if (_client == null) {
            DebugLogger.w(TAG, "refreshServerData: Not connected")
            return
        }
        DebugLogger.i(TAG, "Refreshing server data")
        fetchServerData()
    }

    // ── 离线自动切换（看门狗）────────────────────────────────────────────
    // 设计要点（用户已确认的三条决策）：
    // 1. 连不上自动进离线、恢复后自动回在线，不再弹窗询问；
    // 2. 没有可用缓存时不自动进离线——进去也是空白，应明确报连接失败；
    // 3. 防抖动由 ConnectionStability 的"连续 N 次 + 冷却"决定，界面不反复横跳。

    /**
     * 进入离线模式（自动切换 / 登录页 / 弹窗按钮 / 设置页强制离线 的统一入口）。
     *
     * 与旧实现的区别：不只是改标志位，而是**真的**切过去——加载离线缓存、
     * 关闭 WebSocket、按需启动周期探测。
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
    ): Boolean {
        val ctx = context.applicationContext
        _applicationContext = ctx

        if (server != null) {
            // 离线态没有握手，协议沿用"上次成功的"；从未连上过时按 https 起步，
            // 恢复在线时 testConnection 会重新探测并自动纠正
            lastSession = OnlineSession(
                serverId = server.id,
                hostname = server.hostname,
                port = server.port,
                protocol = protocol ?: _client?.getWorkingProtocol() ?: "https",
                credentials = credentials
            )
        }

        return enterOfflineModeInternal(ctx, server?.id)
    }

    /**
     * 设置「强制离线」（设置页开关）。
     *
     * 打开：真的切到离线态——加载缓存、关闭 WebSocket、**不启动周期探测**
     *       （用户明确说了不想联网，后台不得再有任何请求）。
     * 关闭：恢复在线并重连；此后若又断网，看门狗会重新开始探测。
     *
     * @return 打开时返回是否成功进入离线态（没有可用缓存时为 false，调用方应提示用户）
     */
    @Synchronized
    fun setForcedOffline(context: Context, forced: Boolean): Boolean {
        val ctx = context.applicationContext
        forcedOffline = forced
        AppSettings.setOfflineMode(ctx, forced)

        if (forced) {
            stopOfflineProbe()
            val entered = enterOfflineModeInternal(ctx)
            if (!entered) {
                // 没有可用缓存，强制离线无从生效：回滚持久化标志，避免"开关开着却仍是在线"
                DebugLogger.w(TAG, "强制离线未生效：没有可用离线缓存")
                forcedOffline = false
                AppSettings.setOfflineMode(ctx, false)
            }
            return entered
        }

        reconnectAttempts = 0
        stabilityState = StabilityState()
        return resumeOnlineInternal(ctx)
    }

    /**
     * 进入离线态的实际动作（自动切换与手动入口共用）：
     * 加载离线缓存 → 关闭 WebSocket / 取消重连 → 启动周期探测。
     *
     * @param serverId 目标服务器；null 时依次取当前服务器、已选服务器
     * @return true 表示已进入离线态
     */
    @Synchronized
    private fun enterOfflineModeInternal(context: Context?, serverId: String? = null): Boolean {
        val ctx = (context ?: _applicationContext)?.applicationContext ?: run {
            DebugLogger.w(TAG, "enterOfflineMode: 缺少 Context，放弃切换")
            return false
        }
        _applicationContext = ctx

        val targetServerId = serverId
            ?: currentServerId
            ?: ServerStorage(ctx).getSelectedServer()?.id
            ?: run {
                DebugLogger.w(TAG, "enterOfflineMode: 无法确定目标服务器")
                return false
            }

        // 没有可用缓存不进离线：进去也是空白，应当明确报连接失败（用户确认的决策）
        if (!hasOfflineCache(ctx, targetServerId)) {
            DebugLogger.w(TAG, "enterOfflineMode: 该服务器无离线缓存，保持连接失败状态")
            return false
        }

        if (!loadFromOfflineCache(ctx, targetServerId)) {
            return false
        }

        // 离线态不联网：关掉 WebSocket、掐掉待执行的重连
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        reconnectBlocked = true  // 离线态由看门狗负责恢复探测，残留重连一律阻断
        closeWebSocketInternal()
        clearConnectionAlert()

        // 判定器同步到离线，并记下切换时刻（冷却起点）
        stabilityState = StabilityState(
            mode = StabilityMode.OFFLINE,
            lastSwitchAtMs = SystemClock.elapsedRealtime()
        )

        // 准备好客户端（**不**打开 WebSocket）：周期探测与恢复在线都要用它
        ensureClientForSession(ctx)

        startOfflineProbeIfAllowed()
        return true
    }

    /**
     * 从离线态恢复在线：重建客户端、打开 WebSocket、刷新服务端数据。
     *
     * @return true 表示已发起恢复；false 表示拿不到服务器信息，只能留在离线
     */
    @Synchronized
    private fun resumeOnlineInternal(context: Context? = null): Boolean {
        val ctx = (context ?: _applicationContext)?.applicationContext ?: return false
        val session = resolveSession(ctx) ?: run {
            DebugLogger.w(TAG, "resumeOnline: 没有可用的服务器信息，保持离线")
            return false
        }

        DebugLogger.i(TAG, "从离线态恢复在线")
        stopOfflineProbe()
        forcedOffline = false
        reconnectAttempts = 0

        connect(
            context = ctx,
            serverId = session.serverId,
            hostname = session.hostname,
            port = session.port,
            protocol = session.protocol,
            credentials = session.credentials
        )

        // 恢复在线走的是**不重建 Activity** 的路径（离线看门狗自动恢复、关闭强制离线），
        // 而 connect() 内部的 invalidateAll() 会清掉 MediaCache 的上下文与 JobRegistry 的跟踪——
        // 这两样平时靠 Activity.onCreate 补回来。不在这里补的后果：
        // - 停留在画廊/查看器时，之后所有走 MediaCache 的读取直接返回 null，缩略图/原图一直空白
        //   （LazyBitmap 只取一次、不重试，不会自愈），直到用户退出重进该 Activity；
        // - 断线前在跑的任务恢复后丢失进度跟踪。
        MediaCache.ensureInitialized(ctx)
        JobRegistry.restoreState(ctx)

        // 记下这次切换的时刻：恢复在线后的冷却窗口内，不会被刚建连时的连续失败
        // 立刻掀回离线（否则半死链路会让界面在这两个状态之间来回跳）
        stabilityState = StabilityState(
            mode = StabilityMode.ONLINE,
            lastSwitchAtMs = SystemClock.elapsedRealtime()
        )
        // 离线期间 WebSocket 是关着的，恢复后要重新挂上才能收进度/预览
        openWebSocket()
        // 队列状态同步一次（与 Activity 初始化同一套动作）：补回总排队数与执行中任务，
        // 并顺势剪掉"恢复出来但服务器队列里已经没有"的陈旧任务
        pollQueueStatus()
        return true
    }

    /**
     * 确保存在可用的客户端实例（不打开 WebSocket）。
     * 离线态下的周期探测与恢复在线都要用它；启动即离线时这里才第一次建客户端。
     */
    @Synchronized
    private fun ensureClientForSession(context: Context): Boolean {
        if (_client != null) return true

        val session = resolveSession(context) ?: run {
            DebugLogger.w(TAG, "ensureClientForSession: 没有可用的服务器信息")
            return false
        }

        _clientId = _clientId ?: "comfychair_android_${UUID.randomUUID()}"
        _client = ComfyUIClient(context, session.hostname, session.port, session.credentials).apply {
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
    @Synchronized
    private fun resolveSession(context: Context): OnlineSession? {
        lastSession?.let { return it }

        val server = ServerStorage(context).getSelectedServer() ?: return null
        val credentials = CredentialStorage(context).getCredentials(server.id, server.authType)
        return OnlineSession(
            serverId = server.id,
            hostname = server.hostname,
            port = server.port,
            protocol = _client?.getWorkingProtocol() ?: "https",
            credentials = credentials
        )
    }

    /**
     * 把一次连通性结果喂给判定器，并在满足条件时执行自动切换。
     *
     * 可被任意线程调用（WebSocket 回调也在其中）：判定与计数在同步块内完成，
     * 真正的切换动作投递到主线程执行——它要碰 MediaCache / GalleryRepository。
     */
    private fun reportProbeOutcome(outcome: ProbeOutcome) {
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
                scope.launch { enterOfflineModeInternal(_applicationContext) }
            }
            StabilitySwitch.TO_ONLINE -> {
                DebugLogger.i(TAG, "连续 ${ConnectionStability.REQUIRED_SUCCESSES} 次成功：自动回到在线")
                scope.launch { resumeOnlineInternal() }
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

    /**
     * 启动离线态周期探测（看门狗）。
     *
     * 强制离线时**不启动**：用户在设置页明确表示不想联网，这是设置开关的语义，
     * 也意味着这种情况下不会产生任何后台请求。
     */
    private fun startOfflineProbeIfAllowed() {
        val ctx = _applicationContext
        if (forcedOffline || (ctx != null && AppSettings.isOfflineMode(ctx))) {
            forcedOffline = true
            DebugLogger.i(TAG, "强制离线：不启动周期探测")
            stopOfflineProbe()
            return
        }

        if (_connectionState.value !is ConnectionState.OfflineConnected) return

        offlineProbeJob?.cancel()
        offlineProbeJob = scope.launch {
            DebugLogger.i(TAG, "离线看门狗启动（短超时 /system_stats，先快后稳）")
            var probes = 0
            while (isActive && _connectionState.value is ConnectionState.OfflineConnected) {
                delay(ConnectionStability.nextProbeDelayMs(probes))
                if (!isActive || _connectionState.value !is ConnectionState.OfflineConnected) break
                val reachable = probeOnce()
                probes++
                reportProbeOutcome(if (reachable) ProbeOutcome.SUCCESS else ProbeOutcome.FAILURE)
            }
            DebugLogger.d(TAG, "离线看门狗退出")
        }
    }

    /** 停止离线周期探测。 */
    private fun stopOfflineProbe() {
        offlineProbeJob?.cancel()
        offlineProbeJob = null
    }

    /** 单次轻量探测（挂起到有结果或短超时为止）。 */
    private suspend fun probeOnce(): Boolean {
        val client = _client ?: return false
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                client.probeReachability(ConnectionStability.PROBE_TIMEOUT_MS) { reachable ->
                    if (continuation.isActive) {
                        continuation.resume(reachable)
                    }
                }
            }
        }
    }

    /**
     * Load server data from offline cache.
     *
     * 用于离线态（用户强制离线、启动时连不上而自动切换、运行中断线自动切换）：
     * 把 `/object_info` 的磁盘缓存灌进节点注册表与模型列表，并把连接状态置为
     * [ConnectionState.OfflineConnected]（此后 [currentServerId] 依然可用）。
     *
     * @param context Application context for cache access
     * @param serverId Server ID to load cache for
     * @return true if cache was loaded successfully, false otherwise
     */
    fun loadFromOfflineCache(context: Context, serverId: String): Boolean {
        DebugLogger.i(TAG, "Loading server data from offline cache for server: $serverId")

        if (!ObjectInfoCache.hasCache(context, serverId)) {
            DebugLogger.w(TAG, "No offline cache available for server: $serverId")
            return false
        }

        val objectInfo = ObjectInfoCache.loadObjectInfo(context, serverId)
        if (objectInfo == null) {
            DebugLogger.e(TAG, "Failed to load offline cache for server: $serverId")
            return false
        }

        // Store application context for cache operations
        _applicationContext = context.applicationContext

        // Set offline connected state so currentServerId is available
        _connectionState.value = ConnectionState.OfflineConnected(serverId = serverId)

        // Parse node definitions for workflow editor
        nodeTypeRegistry.parseObjectInfo(objectInfo)
        // 离线缓存同样让注册表就绪（动态参数可正常推导类型）
        _nodeTypeRegistryReady.value = nodeTypeRegistry.isPopulated()

        // Extract model lists for generation screens
        val models = extractModelLists()
        _modelCache.value = models.copy(isLoaded = true, isLoading = false)

        DebugLogger.i(TAG, "Offline cache loaded: ${models.checkpoints.size} checkpoints, " +
                "${models.unets.size} unets, ${models.vaes.size} vaes, " +
                "${models.clips.size} clips, ${models.loras.size} loras")

        // Initialize MediaCache with context and set cache mode from user preferences
        // This must happen BEFORE gallery preload so images can be fetched from disk cache
        val isMemoryFirst = AppSettings.isMemoryFirstCache(context)
        MediaCache.ensureInitialized(context)
        MediaCache.setMemoryFirstMode(isMemoryFirst)
        DebugLogger.d(TAG, "MediaCache initialized for offline mode (memoryFirst=$isMemoryFirst)")

        // Trigger gallery preload from cache
        GalleryRepository.getInstance().apply {
            initialize(context)
            startBackgroundPreload()
        }

        return true
    }

    /**
     * Check if offline cache is available for a server.
     *
     * @param context Application context for cache access
     * @param serverId Server ID to check
     * @return true if cache exists, false otherwise
     */
    fun hasOfflineCache(context: Context, serverId: String): Boolean {
        return ObjectInfoCache.hasCache(context, serverId)
    }

    /**
     * Extract model lists from NodeTypeRegistry by field name.
     * Automatically discovers models from any loader node (standard, GGUF, future plugins).
     *
     * Uses TemplateKeyRegistry to translate placeholder names to actual ComfyUI field names.
     * For example, "text_encoder_name" placeholder maps to "text_encoder" field in LTXAVTextEncoderLoader.
     */
    private fun extractModelLists(): ModelCache {
        return ModelCache(
            checkpoints = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("ckpt_name")
            ),
            unets = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("unet_name")
            ),
            vaes = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("vae_name")
            ),
            clips = nodeTypeRegistry.getOptionsForFieldPrefix("clip_name"),
            // 这里取的是**所有**节点 lora_name 选项的并集，其中 CR Load LoRA /
            // Lora Loader / Efficient Loader / easy *_Loader 等节点都带一个字面量 "None"
            // 表示"不加 LoRA"。它并进 LoRA 下拉后会被用户当成一个真实模型选中，
            // 提交给 LoraLoaderModelOnly 时它并不在服务器的 66 项里，直接 400：
            //   Value not in list: lora_name: 'None' not in (list of length 66)
            // 过滤器里的这类"未选择"取值一律剔除（真正的 LoRA 文件名带扩展名，不受影响）。
            loras = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("lora_name")
            ).filterNot { LoraSelection.normalizeName(it).isEmpty() },
            upscaleMethods = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("upscale_method")
            ),
            textEncoders = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("text_encoder_name")
            ),
            // Use node-specific extraction for latent upscale models because "model_name"
            // is a generic field used by multiple unrelated nodes (UpscaleModelLoader, KlingOmniPro*, etc.)
            latentUpscaleModels = nodeTypeRegistry.getOptionsForNodeInput(
                "LatentUpscaleModelLoader",
                TemplateKeyRegistry.getJsonKeyForPlaceholder("latent_upscale_model")
            ),
            // Same for regular upscale models (UpscaleModelLoader) - "model_name" is generic
            upscaleModels = nodeTypeRegistry.getOptionsForNodeInput(
                "UpscaleModelLoader",
                TemplateKeyRegistry.getJsonKeyForPlaceholder("upscale_model")
            ),
            samplers = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("sampler_name")
            ),
            schedulers = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("scheduler")
            ),
            // "aspect_ratio" 与 "mode" 都是通用字段名（多个节点都有 mode），必须按节点类型取，
            // 否则会把无关节点的选项并进来（同 upscaleModels 的先例）
            aspectRatios = nodeTypeRegistry.getOptionsForNodeInput("ResolutionSelector", "aspect_ratio"),
            speedModes = nodeTypeRegistry.getOptionsForNodeInput("TESpeedMiniMaxH3", "mode")
        )
    }
}
