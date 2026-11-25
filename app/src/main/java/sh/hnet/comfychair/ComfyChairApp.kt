package sh.hnet.comfychair

import android.app.Application
import android.os.Handler
import android.os.Looper
import sh.hnet.comfychair.lifecycle.AppLifecycleMonitor
import sh.hnet.comfychair.lifecycle.BackgroundDegradationCoordinator
import sh.hnet.comfychair.lifecycle.NetworkClassMonitor
import sh.hnet.comfychair.notify.GenerationNotifier
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.util.CrashLogger
import sh.hnet.comfychair.util.DebugLogger

/**
 * 全局 Application（AndroidManifest 的 `<application android:name=".ComfyChairApp">`）。
 *
 * 做三件事：
 * 1. **最先**安装崩溃日志处理器。它必须早于任何业务初始化 ——
 *    启动期崩溃同样要被记录下来，晚一步就可能漏掉最关键的现场。
 * 2. 恢复任务注册表（[JobRegistry.restoreState]）。进程可能是被 WorkManager 拉起的
 *    （后台轮询），也可能是用户打开界面；两种都要有持久化的任务表，否则
 *    「完成提醒必达」在进程重启后就没有对照面。
 * 3. 安装进程级前后台监视器（[AppLifecycleMonitor]）：三重信号（ProcessLifecycleOwner
 *    ON_START/ON_STOP + 小窗失焦 + top-resumed）合成可见性状态，供生成通知的前后台判定
 *    （[GenerationNotifier.setAppInForeground]，由监视器内部订阅驱动）与后台降级协调器
 *    （停画廊刷新 / 分网络断连）统一消费。旧版在这里用 onStart/onStop 计数，感知不到
 *    小窗收球（不触发 onStop），已整体迁入监视器。
 */
class ComfyChairApp : Application() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        // 崩溃捕获必须最先安装（早于 super.onCreate 之后的任何初始化）
        CrashLogger.install(this)
        super.onCreate()

        // 生成通知渠道（幂等，App 启动建一次；minSdk 33 ≥ O 无需版本判断）
        GenerationNotifier.ensureChannels(this)

        // P4：先恢复注册表，再决定通知的初始状态。
        // 修复前这里无条件 cancel：进程被 Worker 拉起时上一条进度通知被撤掉，
        // 而 Worker 不发新通知（client/注册表都空），通知栏直接清空 ——
        // 「进程重启后完成提醒丢失」的第一环。
        JobRegistry.restoreState(this)
        val activeOwnerId = firstActiveOwnerId()
        if (activeOwnerId == null) {
            // 没有活跃任务：清掉上一进程遗留的进度通知（幽灵通知）
            GenerationNotifier.cancel(this)
        } else {
            // 有活跃任务：延迟补挂一条「生成中」兜底通知。
            // 为什么延迟：Application.onCreate 早于 Activity ON_START，此刻无法区分
            // 「用户打开界面」与「Worker 拉起进程」；3 秒后仍不在前台才补挂，
            // 避免正常启动时闪一条通知。界面恢复会在 ViewModel 里接管进度。
            mainHandler.postDelayed({
                if (!AppLifecycleMonitor.isForeground()) {
                    val owner = firstActiveOwnerId() ?: return@postDelayed
                    DebugLogger.i(TAG, "进程启动时仍有后台任务，补挂生成进度兜底通知")
                    GenerationNotifier.onGenerationStarted(this, owner)
                }
            }, PROCESS_START_NOTIFY_DELAY_MS)
        }

        // 前后台监视（幂等）：注册 ProcessLifecycleOwner 与 ActivityLifecycleCallbacks，
        // 并以可见性状态驱动生成通知的前后台判定
        AppLifecycleMonitor.attach(this)
        // 网络计量类别监视（幂等）：默认网络 WiFi/蜂窝 分类，降级协调器的第二信号源
        NetworkClassMonitor.attach(this)
        // 后台降级协调器（幂等）：可见性/网络/任务 × 开关 → 停刷新、断连/放宽心跳、轮询兜底
        BackgroundDegradationCoordinator.attach(this)
    }

    private fun firstActiveOwnerId(): String? = JobRegistry.queueState.value.ownJobs.values
        .firstOrNull { it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING }
        ?.ownerId

    private companion object {
        private const val TAG = "ComfyChairApp"

        /** 进程启动后判定「是否真的有界面起来」的宽限期（见 onCreate 注释） */
        private const val PROCESS_START_NOTIFY_DELAY_MS = 3000L
    }
}
