package sh.hnet.comfychair

import android.app.Application
import sh.hnet.comfychair.lifecycle.AppLifecycleMonitor
import sh.hnet.comfychair.lifecycle.BackgroundDegradationCoordinator
import sh.hnet.comfychair.lifecycle.NetworkClassMonitor
import sh.hnet.comfychair.notify.GenerationNotifier
import sh.hnet.comfychair.util.CrashLogger

/**
 * 全局 Application（AndroidManifest 的 `<application android:name=".ComfyChairApp">`）。
 *
 * 做两件事：
 * 1. **最先**安装崩溃日志处理器。它必须早于任何业务初始化 ——
 *    启动期崩溃同样要被记录下来，晚一步就可能漏掉最关键的现场。
 * 2. 安装进程级前后台监视器（[AppLifecycleMonitor]）：三重信号（ProcessLifecycleOwner
 *    ON_START/ON_STOP + 小窗失焦 + top-resumed）合成可见性状态，供生成通知的前后台判定
 *   （[GenerationNotifier.setAppInForeground]，由监视器内部订阅驱动）与后台降级协调器
 *   （停画廊刷新 / 分网络断连）统一消费。旧版在这里用 onStart/onStop 计数，感知不到
 *   小窗收球（不触发 onStop），已整体迁入监视器。
 */
class ComfyChairApp : Application() {

    override fun onCreate() {
        // 崩溃捕获必须最先安装（早于 super.onCreate 之后的任何初始化）
        CrashLogger.install(this)
        super.onCreate()

        // 生成通知渠道（幂等，App 启动建一次；minSdk 33 ≥ O 无需版本判断）。
        // 随后清掉一次通知：上一进程被杀时进度条停在最后快照，恢复出的生成态会在
        // GenerationViewModel.initialize → restoreGenerationState 里重新挂。
        GenerationNotifier.ensureChannels(this)
        GenerationNotifier.cancel(this)

        // 前后台监视（幂等）：注册 ProcessLifecycleOwner 与 ActivityLifecycleCallbacks，
        // 并以可见性状态驱动生成通知的前后台判定
        AppLifecycleMonitor.attach(this)
        // 网络计量类别监视（幂等）：默认网络 WiFi/蜂窝 分类，降级协调器的第二信号源
        NetworkClassMonitor.attach(this)
        // 后台降级协调器（幂等）：可见性/网络/任务 × 开关 → 停刷新、断连/放宽心跳、轮询兜底
        BackgroundDegradationCoordinator.attach(this)
    }
}
