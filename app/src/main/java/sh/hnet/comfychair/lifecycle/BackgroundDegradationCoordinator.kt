package sh.hnet.comfychair.lifecycle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.lifecycle.BackgroundDegradationPolicy.NetworkClass
import sh.hnet.comfychair.lifecycle.BackgroundDegradationPolicy.WsPolicy
import sh.hnet.comfychair.queue.BackgroundQueuePollWorker
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger

/**
 * 后台降级的**执行器**（v4.18.x 后台省流量）。
 *
 * 三路信号（可见性 / 网络计量类别 / 任务队列）+ 用户开关 →
 * [BackgroundDegradationPolicy] 纯决策表 → 落地动作：
 * - 停 /history 周期刷新（判定在 [sh.hnet.comfychair.repository.GalleryRepository] 内查
 *   [AppLifecycleMonitor]，这里不管）；
 * - WS 连接：保持（放宽双心跳）/ 挂起（蜂窝 + 未开开关），见 [WsPolicy]；
 * - 蜂窝 + 有任务在跑：断 WS 但排 [BackgroundQueuePollWorker] 15 分钟轮询兜底。
 *
 * 容忍期：进入降级态**先等 60 秒**再动手 —— 用户「切出去马上回来」不被惩罚；
 * 期间恢复前台就取消定时器，什么都不发生。定时器只起一次（不随信号抖动重启），
 * 到点后若已降级则立即评估；此后**每次**网络切换 / 开关变化都即时重评。
 */
object BackgroundDegradationCoordinator {

    private const val TAG = "BgDegradation"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 容忍期定时器（同一时刻至多一个；恢复前台即取消） */
    private var degradationTimerJob: Job? = null

    /** 容忍期是否已经到点（到点后网络/开关变化才走即时重评） */
    @Volatile
    private var degradationTimerExpired = false

    @Volatile
    private var appContext: Context? = null

    /**
     * 安装信号收集（Application.onCreate 调用一次，重复调用幂等）。
     * 两个 StateFlow 的首值会立即发射：冷启动直接进后台（进程被 WorkManager 拉起等）
     * 的场景也会在容忍期后被正确降级；未连接时评估是 no-op，无副作用。
     */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            AppLifecycleMonitor.visibility.collect { onVisibilityChanged(it) }
        }
        scope.launch {
            NetworkClassMonitor.networkClass.collect { onNetworkChanged(it) }
        }
    }

    // ---------- 信号处理 ----------

    private fun onVisibilityChanged(visibility: AppVisibility) {
        if (!visibility.isDegraded) {
            // 恢复前台：取消容忍期定时器 + 全面恢复
            degradationTimerJob?.cancel()
            degradationTimerJob = null
            degradationTimerExpired = false
            onReturnedToForeground()
        } else {
            armDegradationTimer()
        }
    }

    /** 起容忍期定时器（已在计时则保持原到期时刻 —— 不随信号抖动重启）。 */
    private fun armDegradationTimer() {
        if (degradationTimerJob?.isActive == true) return
        degradationTimerExpired = false
        degradationTimerJob = scope.launch {
            delay(BackgroundDegradationPolicy.DEGRADATION_DELAY_MS)
            degradationTimerExpired = true
            evaluate("容忍期到点")
        }
    }

    private fun onNetworkChanged(network: NetworkClass) {
        // 未过容忍期：定时器到点自动评估（保持原到期时刻，不重启）；
        // 已过容忍期的降级态下网络切换 → 立即重评（WiFi→蜂窝 断连；蜂窝→WiFi 恢复保连）
        if (degradationTimerExpired && AppLifecycleMonitor.visibility.value.isDegraded) {
            evaluate("网络切换")
        }
    }

    /** 设置开关变化时立即重评（前台时策略恒为全速不受影响；降级态中切换即刻生效）。 */
    fun onKeepConnectedSettingChanged() {
        if (degradationTimerExpired && AppLifecycleMonitor.visibility.value.isDegraded) {
            evaluate("开关变化")
        }
    }

    // ---------- 决策执行 ----------

    /** 读四因子 → 查决策表 → 执行。未连接 / 离线时降级无意义，直接跳过。 */
    private fun evaluate(reason: String) {
        val context = appContext ?: return
        val visibility = AppLifecycleMonitor.visibility.value
        if (!visibility.isDegraded) return
        if (!ConnectionManager.isConnected) {
            DebugLogger.d(TAG, "降级评估（$reason）跳过：未连接（离线/未登录无降级意义）")
            return
        }

        val queue = JobRegistry.queueState.value
        val hasActiveTask = queue.ownActiveJobCount > 0 || queue.isExecuting
        val network = NetworkClassMonitor.networkClass.value
        val keepConnected = AppSettings.isBackgroundKeepConnectedEnabled(context)
        val policy = BackgroundDegradationPolicy.decideWsPolicy(
            visibility = visibility,
            network = network,
            hasActiveTask = hasActiveTask,
            keepConnectedWhenBackground = keepConnected
        )
        DebugLogger.i(
            TAG,
            "降级评估（$reason）：visibility=$visibility network=$network " +
                "activeTask=$hasActiveTask keepConnected=$keepConnected -> $policy"
        )
        apply(policy, network)
    }

    /** 执行策略结果。幂等：重复评估出同一策略时，底层入口自身有挂起/取消的幂等守卫。 */
    private fun apply(policy: WsPolicy, network: NetworkClass) {
        val context = appContext ?: return
        when (policy) {
            WsPolicy.KEEP_FULL_HEARTBEAT -> {
                // 前台恒定路径，evaluate 已拦截降级态，这里只做防御性恢复
                restoreForegroundRhythm()
            }

            WsPolicy.KEEP_RELAXED_HEARTBEAT -> {
                // 保持连接：取消轮询兜底，双心跳放宽；若此前被挂起（策略切换而来）则恢复连接
                BackgroundQueuePollWorker.cancel(context)
                val intervalMs = BackgroundDegradationPolicy.keepaliveIntervalMs(network)
                ConnectionManager.updateKeepaliveInterval(intervalMs)
                ConnectionManager.clientOrNull
                    ?.updateWebSocketPingInterval(intervalMs / 1000)
                ConnectionManager.resumeWebSocketFromSuspension()
            }

            WsPolicy.DROP_AND_POLL -> {
                // 断 WS 省流量，但任务在跑：15 分钟轮询 /queue 兜底 + 完成通知
                ConnectionManager.suspendWebSocketForBackground("蜂窝降级且有任务在跑，改周期轮询")
                BackgroundQueuePollWorker.schedule(context)
            }

            WsPolicy.DROP_AND_IDLE -> {
                // 断 WS，无任务在跑：静默，不排轮询
                ConnectionManager.suspendWebSocketForBackground("蜂窝降级且无任务在跑")
                BackgroundQueuePollWorker.cancel(context)
            }
        }
    }

    // ---------- 恢复前台 ----------

    private fun onReturnedToForeground() {
        restoreForegroundRhythm()
        // 决策 2：回前台主动拉一次 /history 恢复状态
        // （refresh 内部有 in-flight 守卫与 client 判空；离线/未连接时是安全 no-op）
        GalleryRepository.getInstance().refresh()
    }

    /** 双心跳恢复前台节奏（30 秒）+ 解除可能的挂起 + 取消轮询兜底。 */
    private fun restoreForegroundRhythm() {
        val context = appContext ?: return
        BackgroundQueuePollWorker.cancel(context)
        ConnectionManager.updateKeepaliveInterval(BackgroundDegradationPolicy.KEEPALIVE_FOREGROUND_MS)
        ConnectionManager.clientOrNull
            ?.updateWebSocketPingInterval(BackgroundDegradationPolicy.KEEPALIVE_FOREGROUND_MS / 1000)
        ConnectionManager.resumeWebSocketFromSuspension()
    }
}
