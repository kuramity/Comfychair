package sh.hnet.comfychair.ui.components

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 查看器横向翻页的位移模型（G2 惯性滑行 + G3 手势中途回抖，v4.16.12）。
 *
 * ## 为什么 G2 与 G3 必须是同一套
 *
 * 两者描述的是**同一个位移量**——pager 的翻页进度
 * `currentPage + currentPageOffsetFraction`（单位：页）——在不同阶段的口径：
 *
 * | 阶段 | 位移由谁写 | 本模块提供什么 |
 * |---|---|---|
 * | 手指拖动中（G3） | 只有 pager 自己的 drag | [displayPageFraction]：拖动期间**必须原样用实时值**，任何平滑/归零/吸附都会造成"越过一半突然弹回" |
 * | 松手后的滑行（G2） | pager 的 fling（摩擦衰减） | [decayDistancePx] / [flingPageCount] / [snapTargetPage]：滑行多远、最多跨几张、最终吸附到哪一页 |
 * | pager 静止但进度非零（N5 残留） | 谁也不该写；pager 的 drag 被双指接管取消、又没有 settle | [isStalePagerFraction] / [mayNormalizeStalePager]：只在**完全静止**时归零一次，绝不在手势中途重置 |
 *
 * 三条位移通道互不越界（禁止两套动画抢同一个量）：
 * 1. 翻页位移：pager 独占（拖动 / fling / 程序化滚动），本模块只决定"渲染取哪个值"与"吸附到哪"；
 * 2. 越界位移：G1 的 [ViewerOverscroll]，写在 pager 的 graphicsLayer 平移上（与翻页位移正交）；
 * 3. 并排档位进度：ViewerSideBySide（与翻页位移无关）。
 *
 * ## 衰减模型
 *
 * 与 Android `OverScroller`（系统相册用的就是这一套）同源的 spline 摩擦模型：
 * `d(v) = f · (INFLEXION · v / f) ^ (R / (R - 1))`，其中 `R = ln(0.78) / ln(0.9)`，
 * `f` 是摩擦尺度。摩擦尺度按**页宽**换算（`f = 页宽 × FRICTION_PER_PAGE`）：
 * OverScroller 的 `f` 正比于屏幕密度，而页宽也正比于密度，按页宽换算等于
 * 用"页"做摩擦尺度，不必在纯函数里碰 Density。标定见 [FRICTION_PER_PAGE]。
 *
 * 该公式是超线性的（指数 ≈ 1.736）：低速下几乎不滑（保持逐张手感），
 * 高速下一甩可跨多张——这正是系统相册的 fling 手感来源。
 *
 * 纯函数：不依赖 Compose / Android，JVM 单测直接钉住全部边界
 * （见 ViewerPageMotionTest）。
 */
object ViewerPageMotion {

    // ============================================================
    // 一、摩擦衰减模型
    // ============================================================

    /**
     * Android Scroller 的减速率常数 `ln(0.78) / ln(0.9)`（≈ 2.3582）。
     * 与 `OverScroller.DECELERATION_RATE` 同值：滑行轨迹与系统控件同一套物理。
     */
    const val DECELERATION_RATE = 2.3582f

    /** 拐点比例（`OverScroller.INFLEXION`） */
    const val INFLEXION = 0.35f

    /**
     * 摩擦尺度（以"页宽"为单位）。标定方式：1080px 页宽（≈ 420dpi 手机的整屏宽）
     * 下 `f = 1080 × 1.89 ≈ 2041`，与 OverScroller 在 420dpi 上的物理摩擦力
     * （`0.015 × 重力常数 × 39.37 × ppi × 0.84 ≈ 2043`）几乎一致；
     * 由此 6000px/s 甩速滑行约 2 页、12000px/s 约 7 页，与系统相册观感同档。
     */
    const val FRICTION_PER_PAGE = 1.89f

    /** 低于该速度（px/s）不产生任何跨页惯性——保持逐张手感 */
    const val MIN_INERTIA_VELOCITY_PX_PER_S = 320f

    /** 单次甩动允许跨过的最大页数（防极端速度下"一甩到底"） */
    const val MAX_INERTIA_PAGES = 8

    /**
     * 摩擦衰减滑行距离（px，无符号）。
     *
     * @param velocityPxPerSec 松手瞬时速度（px/s，取绝对值——方向由调用处另行决定）
     * @param pagePitchPx 单页步距（页宽 + 页间距，px）。≤0（尺寸未测得）→ 0：
     *                    宁可不滑，也不把 NaN / 无穷写进位移。
     * @return 该速度下摩擦衰减滑行的总距离（px，≥0）；异常输入一律 0
     */
    fun decayDistancePx(velocityPxPerSec: Float, pagePitchPx: Float): Float {
        if (!velocityPxPerSec.isFinite() || !pagePitchPx.isFinite()) return 0f
        if (pagePitchPx <= 0f) return 0f
        val speed = abs(velocityPxPerSec)
        if (speed <= 0f) return 0f
        val friction = pagePitchPx * FRICTION_PER_PAGE
        if (!friction.isFinite() || friction <= 0f) return 0f
        if (DECELERATION_RATE <= 1f) return 0f
        val exponent = DECELERATION_RATE / (DECELERATION_RATE - 1f)
        val distance = friction * (INFLEXION * speed / friction).pow(exponent)
        if (!distance.isFinite() || distance <= 0f) return 0f
        return distance
    }

    /**
     * 速度 → 惯性跨页数（带符号，0 = 不产生额外跨页）。
     *
     * 语义：
     * - 慢速（< [MIN_INERTIA_VELOCITY_PX_PER_S]，或滑行距离不足半页）→ 0：
     *   保持逐张手感，最终吸附仍由"过半才翻"的最近一张规则决定；
     * - 中速 → 1；快速 → 多张，上限 [MAX_INERTIA_PAGES]；
     * - 负速度与正速度严格镜像（方向跟着甩动方向）。
     *
     * 用四舍五入而不是向上取整：滑行距离 1.4 页时吸附到 1 张（终点附近可见
     * 面积最大的那一张），1.6 页时到 2 张——与"吸附目标"的取整口径一致。
     */
    fun flingPageCount(velocityPxPerSec: Float, pagePitchPx: Float): Int {
        if (!velocityPxPerSec.isFinite() || !pagePitchPx.isFinite() || pagePitchPx <= 0f) return 0
        if (abs(velocityPxPerSec) < MIN_INERTIA_VELOCITY_PX_PER_S) return 0
        val pages = (decayDistancePx(velocityPxPerSec, pagePitchPx) / pagePitchPx).roundToInt()
        if (pages <= 0) return 0
        val bounded = pages.coerceAtMost(MAX_INERTIA_PAGES)
        return if (velocityPxPerSec < 0f) -bounded else bounded
    }

    // ============================================================
    // 二、吸附目标计算
    // ============================================================

    /**
     * 吸附目标页（"滑行终点附近占据视口中心面积最大的条目"）。
     *
     * 三步：
     * ① 衰减终点 → 最近整页（四舍五入 = 可见面积最大；并列时取终点方向）；
     * ② 跨页数按速度封顶：[flingPageCount] 给出惯性页数，且**至少允许 1 张**
     *   ——"逐张手感"的全部含义就是"最多吸附到相邻那张"，慢速拖过半仍然翻一张；
     * ③ 夹到 [0, pageCount-1]：两端没有可翻的页，吸附不能越界
     *   （越界位移是 G1 的事，与吸附目标无关）。
     *
     * @param startPage 起点页（UI 侧传 Compose Pager 调用 `PagerSnapDistance.calculateTargetPage`
     *                  时给的 startPage —— 衰减终点是相对它算的，换成 currentPage 会错一页）
     * @param decayEndPage 衰减终点（页坐标，允许小数）。UI 侧传入框架算出的终点页
     *                    （= `currentPage + round(滑行距离 / 页距)`，即"终点附近最近的一页"）
     * @param velocityPxPerSec 松手瞬时速度（px/s，只用来取跨页上限，方向以 [decayEndPage] 为准）
     * @param pagePitchPx 单页步距（px）
     * @param pageCount 总页数
     * @return 目标页（必在 [0, pageCount-1]；pageCount ≤ 0 时返回 0）
     */
    fun snapTargetPage(
        startPage: Int,
        decayEndPage: Float,
        velocityPxPerSec: Float,
        pagePitchPx: Float,
        pageCount: Int
    ): Int {
        if (pageCount <= 0) return 0
        val start = startPage.coerceIn(0, pageCount - 1)
        val end = if (decayEndPage.isFinite()) decayEndPage else start.toFloat()
        val inertia = abs(flingPageCount(velocityPxPerSec, pagePitchPx))
        // 至少 1：慢速/零速松手也要能吸附到"过半的相邻那张"
        val allowed = maxOf(1, inertia)
        val delta = (end - start).roundToInt()
        val bounded = delta.coerceIn(-allowed, allowed)
        return (start + bounded).coerceIn(0, pageCount - 1)
    }

    // ============================================================
    // 三、拖动期间的位移口径（G3：不回抖、不重置）
    // ============================================================

    /**
     * 翻页进度的显示口径（页平移公式里唯一允许使用的进度值）。
     *
     * 规则只有两条：
     * - pager 正在拖动 / 惯性滑行 / 程序化滚动（[scrollInProgress]）→ **原样返回实时进度**。
     *   这是 G3 的硬要求：手势进度与显示必须单调一致，任何"归零/平滑/吸附"都会在
     *   手势中途制造位移跳变（历史现象：越过约一半时前一图弹回、后图被挤回）。
     * - pager 已静止 → 一律 0。静止的 pager 按契约就该停在整页上（进度 = 0），
     *   非零只可能是 N5 那种"drag 被双指接管取消、又没有 settle"的残留值；
     *   让残留值参与显示会把整页推出视口中心（"图片只占半边、另一侧黑区"）。
     *
     * 异常值（NaN / 无穷）→ 0，并夹到 [-1, 1]（pager 的进度契约区间），
     * 绝不让 NaN 进 graphicsLayer（那一层会整层消失）。
     */
    fun displayPageFraction(pagerFraction: Float, scrollInProgress: Boolean): Float {
        if (!pagerFraction.isFinite()) return 0f
        if (!scrollInProgress) return 0f
        return pagerFraction.coerceIn(-1f, 1f)
    }

    /**
     * 是否处于"静止残留"状态（N5）：pager 没在滚动，却带着非零进度。
     *
     * 阈值与 N5 的归零判定共用 [ViewerSideBySide.shouldNormalizePager]（1% 视口宽），
     * 保证"什么时候该归零"只有一个口径。滚动中永远不算残留——那是正常位移。
     */
    fun isStalePagerFraction(pagerFraction: Float, scrollInProgress: Boolean): Boolean {
        if (scrollInProgress) return false
        return ViewerSideBySide.shouldNormalizePager(pagerFraction)
    }

    /**
     * 残留值归零的准入判定（唯一允许"重置翻页位移"的入口，N5 治本）。
     *
     * 三个条件同时成立才允许：
     * 1. 手指已全部抬起（[gestureActive] = false）——手势中途重置位移正是 G3 禁止的事；
     * 2. pager 不在拖动/惯性/程序化滚动中——mutator 空闲，`scrollToPage` 才不会挂在
     *    队列上（N5Probe 实测：拖动未释放时该调用可以挂十几秒）；
     * 3. 确实有残留（见 [isStalePagerFraction]）——没残留就不碰 pager。
     */
    fun mayNormalizeStalePager(
        gestureActive: Boolean,
        scrollInProgress: Boolean,
        pagerFraction: Float
    ): Boolean {
        if (gestureActive || scrollInProgress) return false
        return isStalePagerFraction(pagerFraction, scrollInProgress = false)
    }
}