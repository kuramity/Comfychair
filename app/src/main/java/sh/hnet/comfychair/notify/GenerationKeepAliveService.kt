package sh.hnet.comfychair.notify

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sh.hnet.comfychair.platform.AppLifecycleMonitor
import sh.hnet.comfychair.platform.BackgroundDegradationPolicy
import sh.hnet.comfychair.platform.NetworkClassMonitor
import sh.hnet.comfychair.queue.BackgroundQueuePollWorker
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.util.DebugLogger

/**
 * **任务级**生成保活前台服务（P4 · 路线 A）。
 *
 * 只解决一件事：App 退到后台后，进程不再作为 cached process 被 Android 冻结 / 回收，
 * WebSocket 心跳与进度推送得以继续，通知栏的百分比能真实向前走。
 *
 * **边界（用户明确要求，不可扩成常驻）**：
 * - 只在「不限量网络（WiFi）+ 注册表里有本机活跃任务」时启动；
 * - 注册表一归零（完成 / 失败 / 取消 / 登出）立即 `stopSelf`；
 * - 没有任务时不存在该服务 —— `dumpsys activity services` 里看不到它；
 * - 通知复用 [GenerationNotifier.NOTIFICATION_ID] 与进度渠道，不新增渠道、
 *   不新增第二类通知；内容仍只有模式名 + 进度。
 *
 * **为什么在「任务开始」而不是「进入后台」时启动**：Android 12+ 禁止应用在后台
 * 启动前台服务（`ForegroundServiceStartNotAllowedException`）。任务总是从可见界面
 * 提交/恢复的，在那一刻启动是唯一合法且可靠的时机；进入后台时服务已经在跑，
 * 由系统维持前台级别。
 *
 * **前台时不显示通知**：界面本身有进度条。`startForeground` 之后若 App 仍在前台，
 * 立刻撤掉这条通知（Android 13+ 允许撤 FGS 通知且服务继续运行）；进后台时
 * [GenerationNotifier.setAppInForeground] 会按原行为把进度通知补挂回来。
 *
 * 无 wakelock：CPU 深睡与 Doze 网络限制仍会生效，这是平台边界，不是本服务能消除的。
 * Android 15+ 的 `dataSync` 前台服务有 6 小时/24 小时配额，超时由系统回调
 * [onTimeout]，这里选择停服务回落到 15 分钟轮询。
 */
class GenerationKeepAliveService : Service() {

    companion object {
        private const val TAG = "GenKeepAlive"

        private const val ACTION_START = "sh.hnet.comfychair.action.GEN_KEEPALIVE_START"

        /**
         * 总开关（执行书 §九 回退点 A）：置 false 即完全回到「纯心跳 + 轮询」，
         * 不启动任何前台服务，其余 B/C 修复不受影响。
         */
        private const val ENABLED = true

        /** 进程内是否已有实例（仅用于避免重复 startForegroundService，不作为跨进程状态） */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        /**
         * 按需拉起保活服务（幂等）。
         *
         * 门槛（全部满足才启动）：
         * 1. 生成通知开关未关闭（关闭时不挂任何生成通知，也就不做前台服务）；
         * 2. 注册表里有本机活跃任务；
         * 3. 当前默认网络是 UNMETERED（WiFi）—— 蜂窝按用户选定方向走轮询，不做保活。
         *
         * 失败（后台启动被系统拒绝等）不抛异常：调用方各自还有心跳 / 轮询兜底。
         *
         * @return true 表示服务已在运行或本次启动指令已发出
         */
        fun ensureRunning(context: Context): Boolean {
            if (!ENABLED) return false
            val app = context.applicationContext
            if (running) return true
            if (!GenerationNotifier.isNotificationSettingEnabled(app)) {
                DebugLogger.d(TAG, "生成通知已关闭，跳过前台服务")
                return false
            }
            if (JobRegistry.queueState.value.ownActiveJobCount == 0) {
                DebugLogger.d(TAG, "没有本机活跃任务，跳过前台服务")
                return false
            }
            if (NetworkClassMonitor.networkClass.value !=
                BackgroundDegradationPolicy.NetworkClass.UNMETERED
            ) {
                DebugLogger.d(TAG, "当前不是不限量网络，跳过前台服务（蜂窝走轮询）")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, GenerationKeepAliveService::class.java).setAction(ACTION_START)
                )
                true
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务限制 / 厂商拦截：记录后回落到心跳 + 轮询。
                // 已经在后台时轮询必须补上，否则 WiFi 下进程被冻结就没有第二个完成通道。
                DebugLogger.w(TAG, "启动前台服务失败，回落到心跳/轮询: ${t.javaClass.simpleName} ${t.message}")
                if (!AppLifecycleMonitor.isForeground()) {
                    BackgroundQueuePollWorker.schedule(app)
                }
                false
            }
        }

        /** 主动停止（登出等显式收尾；任务自然结束由服务自身订阅注册表停止）。 */
        fun stop(context: Context) {
            if (!running) return
            context.applicationContext.stopService(
                Intent(context.applicationContext, GenerationKeepAliveService::class.java)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        running = true
        // 任务归零即退：覆盖完成/失败/取消/对账删除/登出等所有收尾路径，
        // 不依赖调用方记得 stop。unique 的 StateFlow 会在每次状态变化时回调。
        scope.launch {
            JobRegistry.queueState.collect { state ->
                if (state.ownActiveJobCount == 0) {
                    DebugLogger.i(TAG, "本机任务已全部结束，停止前台服务")
                    stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (JobRegistry.queueState.value.ownActiveJobCount == 0) {
            // 被系统重启（START_STICKY）时任务可能早已结束：不要留下空服务
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (NetworkClassMonitor.networkClass.value !=
            BackgroundDegradationPolicy.NetworkClass.UNMETERED
        ) {
            // 系统重启服务时网络可能已经切到蜂窝：保活只对 WiFi 有意义，别在流量上常驻
            DebugLogger.i(TAG, "系统重启保活服务时已不是不限量网络，停止")
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val notification = GenerationNotifier.buildProgressNotification(
            this,
            ownerId = firstActiveOwnerId(),
            percent = null
        )
        try {
            ServiceCompat.startForeground(
                this,
                GenerationNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (t: Throwable) {
            // 厂商 ROM 拒绝 / 类型配额 / 通知权限异常：停服务，回落到心跳 + 轮询
            DebugLogger.w(TAG, "startForeground 失败，停止保活服务: ${t.javaClass.simpleName} ${t.message}")
            stopSelfSafely()
            BackgroundQueuePollWorker.schedule(applicationContext)
            return START_NOT_STICKY
        }

        // 前台生成时界面已有进度条：撤掉这条通知，保持「前台不挂通知」的既有行为。
        // Android 13+ 允许撤销前台服务通知而服务继续运行；进后台时监视器会重新补挂。
        if (AppLifecycleMonitor.isForeground()) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(GenerationNotifier.NOTIFICATION_ID)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+ `dataSync` 前台服务超时回调：配额用尽时主动退出，回落到轮询。 */
    override fun onTimeout(startId: Int) {
        DebugLogger.w(TAG, "dataSync 前台服务超时（系统配额），停止服务并回落到轮询")
        stopSelfSafely()
        // 还有任务在跑就必须有第二个完成通道，否则这一轮的通知只能靠进程不被冻结的运气
        if (JobRegistry.queueState.value.ownActiveJobCount > 0) {
            BackgroundQueuePollWorker.schedule(applicationContext)
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        // DETACH：不要连带撤掉通知 —— 完成横幅/进度条是任务级状态，由 GenerationNotifier 管
        stopForeground(STOP_FOREGROUND_DETACH)
        running = false
        stopSelf()
    }

    /** 前台服务通知只展示「模式名 + 生成中…」，任取一个本机活跃任务的归属即可。 */
    private fun firstActiveOwnerId(): String? = JobRegistry.queueState.value.ownJobs.values
        .firstOrNull { it.status == sh.hnet.comfychair.queue.JobStatus.PENDING || it.status == sh.hnet.comfychair.queue.JobStatus.EXECUTING }
        ?.ownerId
}