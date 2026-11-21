package sh.hnet.comfychair.ui.components.ripple

/**
 * 皮肤可见性判定（纯函数，v4.16.14 修复「点生成后水波动画突然看不见」）。
 *
 * ## 为什么不能只看 enabled
 *
 * 生成主按钮的 `isEnabled` 里混着两类语义完全不同的状态：
 * 1. **真禁用**：提示词为空 / 未选图 / 离线 —— 按钮本来就不能点，应该灰底、零特效；
 * 2. **忙碌**：提交闸门在途（`isSubmitting`）、上传、取回、连接中 —— 按钮暂时不可点，
 *    但用户刚按下的那一下水波还在扩散。此前把 `enabled` 直接当皮肤总门控，忙碌瞬间
 *    渐变 / 点阵 / 白边整层被拆掉，观感就是"点下去按钮变深灰、动画凭空消失"。
 *
 * 所以皮肤开关 = 皮肤总开关开着 且（可点 或 忙碌）。只有 `enabled=false` 与
 * `busy=false` 同时成立才是"真禁用"，此时必须保持灰底、无渐变、无点阵。
 *
 * **busy 的语义已收窄为"本页正在忙"**（v4.16.14 复核）：调用方传入前已经剔除离线
 * 与"别页提交"（提交闸门是 Activity 级共享的，A 页提交期间切到 B 页，B 页必须仍是
 * 真禁用灰底）。本对象只做布尔组合，不猜来源。
 *
 * 只有生成主按钮会传 `busy=true`；其它调用点（顶栏图标 / 下拉 / 齿轮）不传，
 * 判定退化为原来的 `enabled`，行为与改动前逐像素一致。
 */
internal object RippleSkinState {

    /**
     * 皮肤是否启用：皮肤总开关开着，并且按钮不是"真禁用"（可点或忙碌都算有皮肤）。
     *
     * @param dotFeedbackEnabled 波纹点阵皮肤总开关（用户设置）
     * @param enabled 按钮是否可点（已剔除离线）
     * @param busy 是否处于"正在和服务器传输"的忙碌态（提交 / 上传 / 取回 / 连接）
     */
    fun skinActive(dotFeedbackEnabled: Boolean, enabled: Boolean, busy: Boolean): Boolean =
        dotFeedbackEnabled && (enabled || busy)

    /**
     * 点阵是否绘制：与 [skinActive] 同口径 —— 点阵是皮肤的一部分，忙碌态继续播
     * （这正是修复点：让已扩散的水波在提交往返期间继续可见）。
     *
     * @param showDots 本调用点是否要画点阵（U1 收敛：只有生成主按钮为 true）
     */
    fun dotsVisible(showDots: Boolean, enabled: Boolean, busy: Boolean): Boolean =
        showDots && (enabled || busy)

    /**
     * 是否**登记新波纹**（v4.16.14 复核 BUSY-01）：与 [dotsVisible] 拆开。
     *
     * 忙碌态（提交闸门在途、按钮 disabled）只继续推进**已扩散**的波纹（帧循环 + 绘制走
     * [dotsVisible]），但新按下**不登记** —— 否则 disabled 按钮仍会播放一整圈水波与触点
     * 辉光，给出"这次点击被接收"的假反馈（submitGate 只保证不会重复提交，不是交互反馈）。
     * 顶栏按钮的 `disabledPressObserver` 先例也说明：`clickable(enabled=false)` 收不到
     * PressInteraction，但 Initial pass 的原始指针事件照样能到 —— 必须显式门控。
     */
    fun dotsRegister(showDots: Boolean, enabled: Boolean): Boolean =
        showDots && enabled

    /**
     * 忙碌态的禁用容器是否改成透明：透明是为了让下层渐变露出来。
     *
     * 只有"忙碌 + 皮肤开着"才透明；皮肤关着时保持 M3 禁用灰 —— 没有渐变可露，
     * 透明容器会只剩文字，真禁用/皮肤关闭的观察者会以为按钮消失了。
     */
    fun busyContainerTransparent(busy: Boolean, dotFeedbackEnabled: Boolean): Boolean =
        busy && dotFeedbackEnabled
}