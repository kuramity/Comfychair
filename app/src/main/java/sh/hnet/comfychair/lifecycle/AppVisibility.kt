package sh.hnet.comfychair.lifecycle

/**
 * App 的可见性三态（后台降级的判定基础，v4.18.x 后台省流量）。
 *
 * 为什么不能只有「前台 / 后台」两态：小米等厂商的**小窗收成悬浮球**不触发
 * `Activity.onStop`（Activity 仍处于 Resumed），只触发 `onWindowFocusChanged(false)`。
 * 只靠 onStart/onStop 计数会把「小窗收球」误判成前台 —— WS 心跳与画廊刷新照常跑，
 * 这正是安全管家报「后台耗流量」的主场景之一。所以把「App 可见但焦点不在」单独成态，
 * 与真后台共用同一套降级行为（见 [BackgroundDegradationPolicy]）。
 */
enum class AppVisibility {
    /** 前台：有可见 Activity 且持有窗口焦点 —— 全速行为，不做任何限制 */
    FOREGROUND,

    /**
     * 小窗/分屏且失去焦点：Activity 仍可见（onStop 没触发），但用户焦点不在本 App
     * （小窗收球、分屏点到了另一半）。**按降级态处理**（与 [BACKGROUND] 同一套策略）。
     */
    MULTI_WINDOW_UNFOCUSED,

    /** 真后台：所有 Activity 均已 onStop（ProcessLifecycleOwner 收到 ON_STOP） */
    BACKGROUND,
    ;

    /** 是否属于「降级态」（[MULTI_WINDOW_UNFOCUSED] 与 [BACKGROUND] 行为一致）。 */
    val isDegraded: Boolean
        get() = this != FOREGROUND
}

/**
 * 三重信号 → 可见性状态的**纯合成**（JVM 单测钉住，AppLifecycleMonitor 只负责喂信号）。
 *
 * 三重信号来源（任一命中降级，任一恢复信号恢复）：
 * 1. `ProcessLifecycleOwner` 的 ON_START/ON_STOP（[processStarted]）—— 真正移出屏幕；
 * 2. `Activity.onWindowFocusChanged(false)` 且 `isInMultiWindowMode == true`
 *    （[multiWindowUnfocused]）—— 小窗收成悬浮球（小米场景的关键信号）；
 * 3. `Activity.onTopResumedActivityChanged(false)`（API 29+，minSdk 33 直接可用）——
 *    也并入 [multiWindowUnfocused]（Activity 内上报时已按多窗口态过滤）。
 *
 * 合成规则：
 * - 进程未 Started（任一 Activity 可见都没有）→ [AppVisibility.BACKGROUND]，**一票定**：
 *   这是唯一能拍板「真后台」的信号，优先级最高；
 * - 进程 Started 且处于「多窗口失焦」→ [AppVisibility.MULTI_WINDOW_UNFOCUSED]；
 * - 其余 → [AppVisibility.FOREGROUND]。
 *
 * @param processStarted ProcessLifecycleOwner 是否处于 Started（有任一可见 Activity）
 * @param multiWindowUnfocused 是否处于「可见但失去焦点」的小窗/分屏态
 */
fun resolveAppVisibility(
    processStarted: Boolean,
    multiWindowUnfocused: Boolean,
): AppVisibility = when {
    !processStarted -> AppVisibility.BACKGROUND
    multiWindowUnfocused -> AppVisibility.MULTI_WINDOW_UNFOCUSED
    else -> AppVisibility.FOREGROUND
}
