package sh.hnet.comfychair.connection

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.ObjectInfoCache
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.util.DebugLogger
import kotlin.coroutines.resume

/**
 * 离线模式控制器（B4.2-A 件二）：离线自动切换 / 强制离线 / 恢复在线 / 离线看门狗的
 * 整体动作，从 [ConnectionManager] 原地迁到这里（函数体逐字迁移，见各方法 KDoc）。
 *
 * 协作形态（无 DI，手动装配）：
 * - controller 持 [ConnectionManager] 单例引用（`manager`）；连接状态、客户端、
 *   WebSocket 生命周期、判定器等**仍是 manager 的职责**，controller 通过 manager
 *   暴露的 `internal` 协作面（字段 + 少量 internal 方法）读写它们；
 * - 对外 API（[enterOfflineMode] / [setForcedOffline] / [loadFromOfflineCache] /
 *   [hasOfflineCache]）由 manager 以**签名不变的转发方法**保留，全仓调用方零改动；
 * - [isOffline] 只是转发判定：口径仍是 connectionState 的 OfflineConnected
 *   （[OfflineUiState] 的唯一判定层不并入、不动）。
 *
 * 锁语义（与迁移前完全一致）：迁移前这些方法靠 @Synchronized 锁在
 * ConnectionManager 单例 monitor 上；迁移后 manager 的两个统一入口保持
 * @Synchronized（锁同一 monitor、可重入），而会被 [ConnectionManager.reportProbeOutcome]
 * 的 `scope.launch` 在**无外层锁**下调用的 [enterOfflineModeInternal] /
 * [resumeOnlineInternal] 改为方法体 `synchronized(manager)`——同一 monitor，
 * 互斥与重入语义均不变。
 */
internal class OfflineModeController(private val manager: ConnectionManager) {

    private val TAG = "Connection"

    /** 离线态下的周期探测任务（原 ConnectionManager.offlineProbeJob，随探测循环一并迁入） */
    private var offlineProbeJob: Job? = null

    /**
     * 当前是否处于离线态（转发判定）。
     *
     * 口径与迁移前的 `ConnectionManager.isOffline` 逐字一致：读 connectionState
     * 是否为 OfflineConnected——connectionState 是唯一状态源，controller 不另设状态。
     */
    val isOffline: Boolean
        get() = manager.connectionState.value is ConnectionState.OfflineConnected

    // ── 对外统一入口（manager 转发到这里）──────────────────────────────

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
    fun enterOfflineMode(
        context: Context,
        server: Server? = null,
        credentials: AuthCredentials = AuthCredentials.None,
        protocol: String? = null
    ): Boolean {
        val ctx = context.applicationContext
        manager._applicationContext = ctx

        if (server != null) {
            // 离线态没有握手，协议沿用"上次成功的"；从未连上过时按 https 起步，
            // 恢复在线时 testConnection 会重新探测并自动纠正
            manager.lastSession = ConnectionManager.OnlineSession(
                serverId = server.id,
                hostname = server.hostname,
                port = server.port,
                protocol = protocol ?: server.protocol ?: manager.clientOrNull?.getWorkingProtocol() ?: "https",
                credentials = credentials
            )
        }

        return enterOfflineModeInternal(ctx, server?.id, notifyOnFailure = false)
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
    fun setForcedOffline(context: Context, forced: Boolean): Boolean {
        val ctx = context.applicationContext
        manager.forcedOffline = forced
        AppSettings.setOfflineMode(ctx, forced)

        if (forced) {
            stopOfflineProbe()
            val entered = enterOfflineModeInternal(ctx, notifyOnFailure = false)
            if (!entered) {
                // 没有可用缓存，强制离线无从生效：回滚持久化标志，避免"开关开着却仍是在线"
                DebugLogger.w(TAG, "强制离线未生效：没有可用离线缓存")
                manager.forcedOffline = false
                AppSettings.setOfflineMode(ctx, false)
            }
            return entered
        }

        manager.reconnectAttempts = 0
        manager.stabilityState = StabilityState()
        return resumeOnlineInternal(ctx)
    }

    // ── 切换与恢复的实际动作 ──────────────────────────────────────────

    /**
     * 进入离线态的实际动作（自动切换与手动入口共用）：
     * 加载离线缓存 → 关闭 WebSocket / 取消重连 → 启动周期探测。
     *
     * 时序红线（八步，顺序不得重排）：
     * 1. 定 targetServerId；2. 无离线缓存则失败；3. loadFromOfflineCache；
     * 4. 取消 pendingReconnectJob；5. reconnectBlocked + 关 WS + 清横幅；
     * 6. 连接横幅/清理；7. 判定器 OFFLINE；8. 备客户端 + 启动探测。
     *
     * @param serverId 目标服务器；null 时依次取当前服务器、已选服务器
     * @param notifyOnFailure 切换失败时是否给用户非阻塞提示（仅自动切换传 true）。
     *        用户主动点「离线 / 强制离线」失败由各自界面负责提示，不应留下全局横幅。
     * @return true 表示已进入离线态
     */
    internal fun enterOfflineModeInternal(
        context: Context?,
        serverId: String? = null,
        notifyOnFailure: Boolean = true
    ): Boolean = synchronized(manager) {
        val ctx = (context ?: manager._applicationContext)?.applicationContext ?: run {
            DebugLogger.w(TAG, "enterOfflineMode: 缺少 Context，放弃切换")
            return false
        }
        manager._applicationContext = ctx

        val targetServerId = serverId
            ?: manager.currentServerId
            ?: ServerStorage(ctx).getSelectedServer()?.id
            ?: run {
                DebugLogger.w(TAG, "enterOfflineMode: 无法确定目标服务器")
                return false
            }

        // 没有可用缓存不进离线：进去也是空白，应当明确报连接失败（用户确认的决策）
        if (!hasOfflineCache(ctx, targetServerId)) {
            DebugLogger.w(TAG, "enterOfflineMode: 该服务器无离线缓存，保持连接失败状态")
            if (notifyOnFailure) onOfflineSwitchFailed()
            return false
        }

        if (!loadFromOfflineCache(ctx, targetServerId)) {
            if (notifyOnFailure) onOfflineSwitchFailed()
            return false
        }

        // 离线态不联网：关掉 WebSocket、掐掉待执行的重连
        manager.pendingReconnectJob?.cancel()
        manager.pendingReconnectJob = null
        manager.reconnectBlocked = true  // 离线态由看门狗负责恢复探测，残留重连一律阻断
        manager.closeWebSocketInternal()
        manager.clearConnectionAlert()
        if (notifyOnFailure) {
            // 自动切离线（断网降级）：横幅继续提示"后台正在重试"，离线看门狗恢复在线后
            // 由 connect() 清除；用户主动进离线则不显示任何"正在重连"字样。
            manager._connectionNotice.value = ConnectionNotice(ConnectionFailure.NETWORK)
        } else {
            manager.clearConnectionNotice()
        }

        // 判定器同步到离线，并记下切换时刻（冷却起点）
        manager.stabilityState = StabilityState(
            mode = StabilityMode.OFFLINE,
            lastSwitchAtMs = SystemClock.elapsedRealtime()
        )

        // 准备好客户端（**不**打开 WebSocket）：周期探测与恢复在线都要用它
        manager.ensureClientForSession(ctx)

        startOfflineProbeIfAllowed()
        true
    }

    /**
     * 「切离线失败」的兜底（P3，自动切换专用）。
     *
     * 没有离线缓存时进不去离线态，但**绝不能把用户困在失败终态**：
     * 1. 判定器拉回 ONLINE —— 否则它仍记着 OFFLINE，下一次成功信号会被误判成
     *    「离线恢复」而多触发一次 connect；
     * 2. 只发非阻塞横幅，不弹模态 —— 当前界面的数据原样保留；
     * 3. 若后台没有在途重连，补排一条退避重连，横幅上的"正在自动重试"才名副其实。
     *
     * 冷却时间沿用判定器已有的 lastSwitchAtMs，避免刚失败就立刻再切一次离线。
     */
    private fun onOfflineSwitchFailed() {
        val lastSwitchAt = manager.stabilityState.lastSwitchAtMs
        manager.stabilityState = StabilityState(
            mode = StabilityMode.ONLINE,
            lastSwitchAtMs = lastSwitchAt
        )
        manager.showConnectionNotice(ConnectionFailure.NETWORK)
    }

    /**
     * 从离线态恢复在线：重建客户端、打开 WebSocket、刷新服务端数据。
     *
     * 恢复序（不得重排）：resolveSession → stopOfflineProbe → forcedOffline=false
     * → connect(...) → MediaCache.ensureInitialized → JobRegistry.restoreState。
     *
     * @return true 表示已发起恢复；false 表示拿不到服务器信息，只能留在离线
     */
    internal fun resumeOnlineInternal(context: Context? = null): Boolean = synchronized(manager) {
        val ctx = (context ?: manager._applicationContext)?.applicationContext ?: return false
        val session = manager.resolveSession(ctx) ?: run {
            DebugLogger.w(TAG, "resumeOnline: 没有可用的服务器信息，保持离线")
            return false
        }

        DebugLogger.i(TAG, "从离线态恢复在线")
        stopOfflineProbe()
        manager.forcedOffline = false
        manager.reconnectAttempts = 0

        manager.connect(
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
        manager.stabilityState = StabilityState(
            mode = StabilityMode.ONLINE,
            lastSwitchAtMs = SystemClock.elapsedRealtime()
        )
        // 离线期间 WebSocket 是关着的，恢复后要重新挂上才能收进度/预览
        manager.openWebSocket()
        // 队列状态同步一次（与 Activity 初始化同一套动作）：补回总排队数与执行中任务，
        // 并顺势剪掉"恢复出来但服务器队列里已经没有"的陈旧任务
        manager.pollQueueStatus()
        true
    }

    // ── 离线看门狗（周期探测）──────────────────────────────────────────

    /**
     * 启动离线态周期探测（看门狗）。
     *
     * 强制离线时**不启动**：用户在设置页明确表示不想联网，这是设置开关的语义，
     * 也意味着这种情况下不会产生任何后台请求。
     */
    private fun startOfflineProbeIfAllowed() {
        val ctx = manager._applicationContext
        if (manager.forcedOffline || (ctx != null && AppSettings.isOfflineMode(ctx))) {
            manager.forcedOffline = true
            DebugLogger.i(TAG, "强制离线：不启动周期探测")
            stopOfflineProbe()
            return
        }

        if (manager._connectionState.value !is ConnectionState.OfflineConnected) return

        offlineProbeJob?.cancel()
        offlineProbeJob = manager.scope.launch {
            DebugLogger.i(TAG, "离线看门狗启动（短超时 /system_stats，先快后稳）")
            var probes = 0
            while (isActive && manager._connectionState.value is ConnectionState.OfflineConnected) {
                delay(ConnectionStability.nextProbeDelayMs(probes))
                if (!isActive || manager._connectionState.value !is ConnectionState.OfflineConnected) break
                val reachable = probeOnce()
                probes++
                manager.reportProbeOutcome(if (reachable) ProbeOutcome.SUCCESS else ProbeOutcome.FAILURE)
            }
            DebugLogger.d(TAG, "离线看门狗退出")
        }
    }

    /** 停止离线周期探测（controller 内部与 manager 的 connect/disconnect 都会用）。 */
    internal fun stopOfflineProbe() {
        offlineProbeJob?.cancel()
        offlineProbeJob = null
    }

    /** 单次轻量探测（挂起到有结果或短超时为止）。 */
    private suspend fun probeOnce(): Boolean {
        val client = manager.clientOrNull ?: return false
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

    // ── 离线缓存装载与检查 ────────────────────────────────────────────

    /**
     * Load server data from offline cache.
     *
     * 用于离线态（用户强制离线、启动时连不上而自动切换、运行中断线自动切换）：
     * 把 `/object_info` 的磁盘缓存灌进节点注册表与模型列表，并把连接状态置为
     * [ConnectionState.OfflineConnected]（此后 currentServerId 依然可用）。
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
        manager._applicationContext = context.applicationContext

        // Set offline connected state so currentServerId is available
        manager._connectionState.value = ConnectionState.OfflineConnected(serverId = serverId)

        // Parse node definitions for workflow editor + 让注册表就绪（动态参数可正常推导类型）
        // + Extract model lists for generation screens 并按离线语义发布
        // （B4-W2：四步整体委托给 catalog，parse → ready → extract 走同一把原子段锁）
        val models = manager.catalog.applyOfflineObjectInfo(objectInfo)

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
}
