package sh.hnet.comfychair.platform

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.util.DebugLogger

/**
 * 进程级前后台监视器（v4.18.x 后台省流量的**信号源**，三重信号合成 [AppVisibility]）。
 *
 * 只有一件事：把三类信号喂进 [resolveAppVisibility] 并以 StateFlow 对外发布；
 * **降级动作（停刷新 / 断 WS / 心跳放宽）不在这里**，由
 * [sh.hnet.comfychair.lifecycle.BackgroundDegradationCoordinator] 收集 [visibility] 执行。
 *
 * 信号接线（对照小米官方文档「小窗收球不触发 onStop」的说明）：
 * 1. **[ProcessLifecycleOwner]** 的 ON_START / ON_STOP：真正移出屏幕 / 回到屏幕的一票信号；
 * 2. **Activity.onWindowFocusChanged(false)** 且 `isInMultiWindowMode == true`：
 *    小窗收成悬浮球 / 分屏失焦 —— 这些场景 Activity 仍在 Resumed，ON_STOP 不会来；
 * 3. **Activity.onTopResumedActivityChanged(false)**（API 29+，minSdk 33 直接可用）：
 *    作为第 2 条的辅助信号，同样只在多窗口态下生效。
 *
 * 信号上报入口：各主要 Activity 覆写 onWindowFocusChanged / onTopResumedActivityChanged
 * 调 [reportWindowFocus] / [reportTopResumedChanged]（当前覆盖主容器、连接页、图库容器、
 * 媒体查看器 —— 用户驻留最久的界面；短时编辑页不覆盖，由 ON_STOP 兜底）。
 *
 * 前后台判定历史上散在 ComfyChairApp 的 onStart/onStop 计数里（它感知不到小窗收球），
 * 本类接管后 [GenerationNotifier.setAppInForeground] 由装配根 collect [visibility] 统一驱动
 * （B1 起接线在 ComfyChairApp，本类不依赖 notify 包）。
 */
object AppLifecycleMonitor {

    private const val TAG = "AppLifecycle"

    private val _visibility = MutableStateFlow(AppVisibility.BACKGROUND)
    val visibility: StateFlow<AppVisibility> = _visibility.asStateFlow()

    /** 当前合成出的可见性是否前台（高频读，避免每次都走 StateFlow.value 比较）。 */
    fun isForeground(): Boolean = _visibility.value == AppVisibility.FOREGROUND

    /**
     * 三重信号在内存里的落点。
     *
     * 跨线程说明：ON_START/ON_STOP 与 ActivityLifecycleCallbacks 回调都在主线程；
     * reportWindowFocus（onWindowFocusChanged）也是主线程 —— 字段本身无需 @Volatile，
     * 但 recompute 走 StateFlow（内部 CAS），读方（后台协程）拿到的永远是完整状态。
     */
    private var processStarted = false
    private var multiWindowUnfocused = false

    @Volatile
    private var attached = false

    /**
     * 安装信号源（Application.onCreate 调用一次，重复调用幂等）。
     *
     * B1 起本类回归「只喂信号」职责：可见性变化由装配根（ComfyChairApp）collect
     * [visibility] 后驱动 [sh.hnet.comfychair.notify.GenerationNotifier.setAppInForeground]，
     * 本类不再直接依赖 notify 包（解除 notify↔lifecycle 双向环）。
     */
    fun attach(app: Application) {
        if (attached) {
            DebugLogger.w(TAG, "attach: 已安装，跳过重复注册")
            return
        }
        attached = true

        // 信号 1：进程级 Started/Stopped（真正移出屏幕 / 回到屏幕）
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    processStarted = true
                    recompute("ON_START")
                }
                Lifecycle.Event.ON_STOP -> {
                    // 真后台：焦点信号一并清掉（回前台时以「无失焦」的干净状态起步）
                    processStarted = false
                    multiWindowUnfocused = false
                    recompute("ON_STOP")
                }
                else -> Unit
            }
        })

        // Activity 级兜底：resume 时若不在多窗口态，清掉可能残留的失焦标记
        //（小窗里叠开全屏页再返回等时序，onWindowFocusChanged 可能不会再来一对完整信号）
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!activity.isInMultiWindowMode && multiWindowUnfocused) {
                    multiWindowUnfocused = false
                    recompute("onActivityResumed(非多窗口，清失焦标记)")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // B1：通知前台判定接线已移至 ComfyChairApp（装配根）——
        // 本类只负责合成 visibility，不再依赖 notify 包
    }

    /**
     * 信号 2：窗口焦点变化（各主要 Activity 的 onWindowFocusChanged 覆写调用）。
     *
     * 只在「失去焦点 **且** 处于多窗口模式」时置失焦标记 —— 非多窗口的失焦是
     * 下拉通知栏 / 系统对话框这类瞬时遮挡，不该降级；拿到焦点一律清除标记。
     *
     * @param hasFocus Activity.onWindowFocusChanged 的原样参数
     * @param inMultiWindowMode Activity.isInMultiWindowMode（调用时点的值）
     */
    fun reportWindowFocus(hasFocus: Boolean, inMultiWindowMode: Boolean) {
        if (hasFocus) {
            if (multiWindowUnfocused) {
                multiWindowUnfocused = false
                recompute("onWindowFocusChanged(true)")
            }
        } else if (inMultiWindowMode && !multiWindowUnfocused) {
            multiWindowUnfocused = true
            recompute("onWindowFocusChanged(false, multiWindow)")
        }
    }

    /**
     * 信号 3（辅助）：TopResumedActivity 变化（API 29+，主容器上报）。
     *
     * 与信号 2 同口径：只在多窗口态下把「不再是 top resumed」并入失焦标记。
     * 非多窗口下丢 top-resumed 是正常叠页（下层仍可见、ON_STOP 语义兜底），不降级。
     */
    fun reportTopResumedChanged(isTopResumed: Boolean, inMultiWindowMode: Boolean) {
        if (!isTopResumed) {
            if (inMultiWindowMode && !multiWindowUnfocused) {
                multiWindowUnfocused = true
                recompute("onTopResumedActivityChanged(false, multiWindow)")
            }
        } else if (multiWindowUnfocused) {
            multiWindowUnfocused = false
            recompute("onTopResumedActivityChanged(true)")
        }
    }

    /** 把当前两路信号合成进 StateFlow（值不变时 StateFlow 自身去重，下游不会重复触发）。 */
    private fun recompute(reason: String) {
        val next = resolveAppVisibility(processStarted, multiWindowUnfocused)
        val changed = next != _visibility.value
        _visibility.value = next
        if (changed) {
            DebugLogger.d(TAG, "可见性: ${_visibility.value}（$reason）")
        }
    }
}
