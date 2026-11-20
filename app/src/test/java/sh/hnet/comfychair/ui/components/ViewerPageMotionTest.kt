package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器横向翻页位移模型（G2 惯性 + G3 手势中途回抖）的纯逻辑测试（v4.16.12）。
 * 实现见 ViewerPageMotion.kt，UI 侧只在 MediaViewerScreen 里喂速度/取口径。
 *
 * 钉住五件事：
 * 1. **速度 → 跨页数**：慢速保持逐张、中速跨 1 张、快速跨多张、速度为 0 不动、
 *    负速度与正速度严格镜像；
 * 2. **衰减模型**：超线性（高速度按指数放量）、单调、异常输入不出 NaN；
 * 3. **吸附目标**：滑行终点附近可见面积最大的那一页（四舍五入 = 过半才翻），
 *    且被跨页上限与页数两端夹住；
 * 4. **拖动期间的口径**：实时进度原样返回（手势进度与显示单调一致），
 *    静止残留一律按 0 —— 静止时唯一可能出现非零值的情况是 N5 残留；
 * 5. **残留归零的准入**：手势中 / 滚动中一律不许重置位移（G3 规则 1/2）。
 */
class ViewerPageMotionTest {

    /** 常见手机页宽（px）。模型按"页宽"标定摩擦尺度，测试都用它当基准 */
    private val pitch = 1080f

    // ============================================================
    // 1. 速度 → 跨页数
    // ============================================================

    @Test
    fun `速度为 0 不产生惯性跨页`() {
        assertEquals(0, ViewerPageMotion.flingPageCount(0f, pitch))
        // 滑行距离本身也必须是 0（没有速度就没有位移）
        assertEquals(0f, ViewerPageMotion.decayDistancePx(0f, pitch), 0f)
    }

    @Test
    fun `慢速保持逐张`() {
        // 低于惯性门槛（320px/s）：一页都不跨，松手交给"过半才翻"的最近一张规则
        assertEquals(0, ViewerPageMotion.flingPageCount(80f, pitch))
        assertEquals(0, ViewerPageMotion.flingPageCount(300f, pitch))
        // 刚过门槛但滑行不足半页：仍然不跨（逐张手感不被破坏）
        assertEquals(0, ViewerPageMotion.flingPageCount(1000f, pitch))
    }

    @Test
    fun `中速跨 1 张`() {
        // 3000px/s：滑行约 0.6 页 → 四舍五入 1 张
        assertEquals(1, ViewerPageMotion.flingPageCount(3000f, pitch))
    }

    @Test
    fun `快速跨多张`() {
        // 6000px/s ≈ 2 页；12000px/s ≈ 7 页（系统相册同档的"一甩跨多张"）
        assertEquals(2, ViewerPageMotion.flingPageCount(6000f, pitch))
        val fast = ViewerPageMotion.flingPageCount(12000f, pitch)
        assertTrue("12000px/s 应跨多张，实际 $fast", fast >= 3)
        assertTrue(fast <= ViewerPageMotion.MAX_INERTIA_PAGES)
    }

    @Test
    fun `负速度方向与正速度严格镜像`() {
        for (velocity in listOf(400f, 1000f, 3000f, 6000f, 12000f, 50000f)) {
            assertEquals(
                ViewerPageMotion.flingPageCount(velocity, pitch),
                -ViewerPageMotion.flingPageCount(-velocity, pitch)
            )
        }
        // 方向本身：负速度 = 往上一张方向（页坐标减小）
        assertTrue(ViewerPageMotion.flingPageCount(-6000f, pitch) < 0)
    }

    @Test
    fun `极端速度被跨页上限夹住`() {
        val huge = ViewerPageMotion.flingPageCount(200000f, pitch)
        assertEquals(ViewerPageMotion.MAX_INERTIA_PAGES, huge)
        assertEquals(-ViewerPageMotion.MAX_INERTIA_PAGES, ViewerPageMotion.flingPageCount(-200000f, pitch))
    }

    @Test
    fun `跨页数随速度单调不减`() {
        var previous = 0
        for (velocity in listOf(0f, 300f, 1000f, 3000f, 6000f, 9000f, 12000f, 20000f)) {
            val span = ViewerPageMotion.flingPageCount(velocity, pitch)
            assertTrue("速度 $velocity 的跨页数 $span 不应小于前一档 $previous", span >= previous)
            previous = span
        }
    }

    // ============================================================
    // 2. 摩擦衰减模型
    // ============================================================

    @Test
    fun `衰减距离单调递增且超线性`() {
        val d1 = ViewerPageMotion.decayDistancePx(1000f, pitch)
        val d2 = ViewerPageMotion.decayDistancePx(2000f, pitch)
        val d4 = ViewerPageMotion.decayDistancePx(4000f, pitch)
        assertTrue(d1 > 0f)
        assertTrue(d2 > d1)
        assertTrue(d4 > d2)
        // 超线性：速度翻倍拿到的滑行距离不止翻倍（系统相册"越甩越远"的手感来源）
        assertTrue("应满足 d(2v) > 2·d(v)，实际 $d2 vs $d1", d2 > 2f * d1)
        assertTrue("应满足 d(4v) > 2·d(2v)，实际 $d4 vs $d2", d4 > 2f * d2)
    }

    @Test
    fun `衰减距离与页宽同倍缩放`() {
        // 摩擦尺度按页宽标定：页宽翻倍时，同一 px 速度的滑行距离按 f^(-0.736) 缩短，
        // 但换算成"页"后仍落在同一量级（下拉不会因为屏幕宽就甩不动）
        val pagesOnPhone = ViewerPageMotion.decayDistancePx(6000f, 1080f) / 1080f
        val pagesOnTablet = ViewerPageMotion.decayDistancePx(6000f, 1600f) / 1600f
        assertTrue(pagesOnPhone > 1f)
        assertTrue("大屏同速应少跨一点页，实际 $pagesOnTablet vs $pagesOnPhone", pagesOnTablet < pagesOnPhone)
        assertTrue(pagesOnTablet > 0.5f)
    }

    @Test
    fun `衰减模型的异常输入不出 NaN`() {
        assertEquals(0f, ViewerPageMotion.decayDistancePx(Float.NaN, pitch), 0f)
        assertEquals(0f, ViewerPageMotion.decayDistancePx(Float.POSITIVE_INFINITY, pitch), 0f)
        assertEquals(0f, ViewerPageMotion.decayDistancePx(3000f, 0f), 0f)
        assertEquals(0f, ViewerPageMotion.decayDistancePx(3000f, -1080f), 0f)
        assertEquals(0f, ViewerPageMotion.decayDistancePx(3000f, Float.NaN), 0f)
        // 页宽未测得：跨页数一律 0（宁可不滑，也不写 NaN）
        assertEquals(0, ViewerPageMotion.flingPageCount(9000f, 0f))
        assertEquals(0, ViewerPageMotion.flingPageCount(Float.NaN, pitch))
        assertEquals(0, ViewerPageMotion.flingPageCount(9000f, Float.NaN))
    }

    // ============================================================
    // 3. 吸附目标（可见面积最大 + 跨页上限 + 页数夹取）
    // ============================================================

    @Test
    fun `吸附到终点附近可见面积最大的那张`() {
        // 终点差 0.4 页：当前页仍占多数面积 → 留在当前页
        assertEquals(3, ViewerPageMotion.snapTargetPage(3, 3.4f, 0f, pitch, 9))
        // 终点差 0.6 页：下一张已占多数面积 → 翻过去（"过半才翻"的逐张手感）
        assertEquals(4, ViewerPageMotion.snapTargetPage(3, 3.6f, 0f, pitch, 9))
        // 往上一张方向同理（负方向过半才翻）
        assertEquals(3, ViewerPageMotion.snapTargetPage(3, 2.6f, 0f, pitch, 9))
        assertEquals(2, ViewerPageMotion.snapTargetPage(3, 2.4f, 0f, pitch, 9))
    }

    @Test
    fun `慢速松手最多吸附相邻一张`() {
        // 零速/慢速时跨页上限 = 1：衰减终点再远也只允许挪一张（逐张手感的硬保证）
        assertEquals(4, ViewerPageMotion.snapTargetPage(3, 9f, 0f, pitch, 9))
        assertEquals(2, ViewerPageMotion.snapTargetPage(3, -9f, 0f, pitch, 9))
        assertEquals(4, ViewerPageMotion.snapTargetPage(3, 9f, 300f, pitch, 9))
    }

    @Test
    fun `快速甩动吸附到终点页且不超跨页上限`() {
        // 12000px/s 允许 7 页：终点 6 页外 → 吸附到第 9 页（3 + 6）
        assertEquals(9, ViewerPageMotion.snapTargetPage(3, 9f, 12000f, pitch, 12))
        // 终点 20 页外：被跨页上限（7）挡住
        assertEquals(
            3 + ViewerPageMotion.flingPageCount(12000f, pitch),
            ViewerPageMotion.snapTargetPage(3, 23f, 12000f, pitch, 40)
        )
    }

    @Test
    fun `吸附目标被页数两端夹住`() {
        // 末页：任何速度都不能吸附到页数之外
        assertEquals(8, ViewerPageMotion.snapTargetPage(8, 20f, 20000f, pitch, 9))
        // 首张往上一张方向：夹在 0
        assertEquals(0, ViewerPageMotion.snapTargetPage(0, -5f, -20000f, pitch, 9))
        // 单页 / 空列表
        assertEquals(0, ViewerPageMotion.snapTargetPage(0, 5f, 9000f, pitch, 1))
        assertEquals(0, ViewerPageMotion.snapTargetPage(0, 5f, 9000f, pitch, 0))
        // 起点页本身越界（删除条目瞬间的旧值）按最近端点处理
        assertEquals(8, ViewerPageMotion.snapTargetPage(99, 99f, 0f, pitch, 9))
    }

    @Test
    fun `吸附目标的异常输入回到起点页`() {
        assertEquals(3, ViewerPageMotion.snapTargetPage(3, Float.NaN, 0f, pitch, 9))
        assertEquals(3, ViewerPageMotion.snapTargetPage(3, Float.NEGATIVE_INFINITY, 0f, pitch, 9))
        // 页宽异常：跨页数按 0 处理，仍不越出 ±1
        assertEquals(4, ViewerPageMotion.snapTargetPage(3, 8f, 9000f, 0f, 9))
    }

    // ============================================================
    // 4. 拖动期间的位移口径（G3）
    // ============================================================

    @Test
    fun `拖动期间原样返回实时进度`() {
        // 单调一致：手势喂进来的每个值都必须原样出现在显示口径里，不许平滑/归零
        for (fraction in listOf(-0.9f, -0.5f, -0.13f, 0f, 0.13f, 0.5f, 0.9f)) {
            assertEquals(
                fraction,
                ViewerPageMotion.displayPageFraction(fraction, scrollInProgress = true),
                0f
            )
        }
    }

    @Test
    fun `拖动进度严格按手势递增`() {
        // 模拟"拖到一半停顿、再继续"：口径值不得出现回退（G3 的回抖就是这里被破坏）
        var previous = Float.NEGATIVE_INFINITY
        val gesture = listOf(0.02f, 0.15f, 0.15f, 0.31f, 0.31f, 0.62f, 0.88f)
        for (fraction in gesture) {
            val displayed = ViewerPageMotion.displayPageFraction(fraction, scrollInProgress = true)
            assertTrue("显示值 $displayed 不应小于上一帧 $previous", displayed >= previous)
            previous = displayed
        }
    }

    @Test
    fun `静止时残留进度不参与显示`() {
        // N5 残留：静止却带着 0.22 的进度 → 显示口径必须是 0（否则整页被推出中心）
        assertEquals(0f, ViewerPageMotion.displayPageFraction(0.22f, scrollInProgress = false), 0f)
        assertEquals(0f, ViewerPageMotion.displayPageFraction(-0.22f, scrollInProgress = false), 0f)
        assertEquals(0f, ViewerPageMotion.displayPageFraction(0f, scrollInProgress = false), 0f)
    }

    @Test
    fun `显示口径的异常值与越界值被兜住`() {
        assertEquals(0f, ViewerPageMotion.displayPageFraction(Float.NaN, scrollInProgress = true), 0f)
        assertEquals(0f, ViewerPageMotion.displayPageFraction(Float.POSITIVE_INFINITY, scrollInProgress = true), 0f)
        // pager 的进度契约区间是 [-1, 1]：越界值夹回，绝不让它进 graphicsLayer
        assertEquals(1f, ViewerPageMotion.displayPageFraction(1.7f, scrollInProgress = true), 0f)
        assertEquals(-1f, ViewerPageMotion.displayPageFraction(-1.7f, scrollInProgress = true), 0f)
    }

    // ============================================================
    // 5. 静止残留判定与归零准入（N5 治本 + G3 规则 1/2）
    // ============================================================

    @Test
    fun `静止残留的判定`() {
        assertTrue(ViewerPageMotion.isStalePagerFraction(0.22f, scrollInProgress = false))
        assertTrue(ViewerPageMotion.isStalePagerFraction(-0.22f, scrollInProgress = false))
        // 滚动中带进度是正常位移，不是残留
        assertFalse(ViewerPageMotion.isStalePagerFraction(0.22f, scrollInProgress = true))
        // 静止且进度在 1% 视口宽以内：视为已归零（与 N5 的阈值同一口径）
        assertFalse(ViewerPageMotion.isStalePagerFraction(0.005f, scrollInProgress = false))
        // NaN 属于坏状态：静止时判为需要归零（scrollToPage 会复位一切）
        assertTrue(ViewerPageMotion.isStalePagerFraction(Float.NaN, scrollInProgress = false))
        assertFalse(ViewerPageMotion.isStalePagerFraction(Float.NaN, scrollInProgress = true))
    }

    @Test
    fun `手势中一律不许重置翻页位移`() {
        // G3 规则 1/2：手指还按着（哪怕 pager 已经停住）就不许写回翻页位移
        assertFalse(
            ViewerPageMotion.mayNormalizeStalePager(
                gestureActive = true, scrollInProgress = false, pagerFraction = 0.22f
            )
        )
        // 惯性/程序化滚动进行中同理（会与在途动画抢同一个位移）
        assertFalse(
            ViewerPageMotion.mayNormalizeStalePager(
                gestureActive = false, scrollInProgress = true, pagerFraction = 0.22f
            )
        )
    }

    @Test
    fun `完全静止的残留才允许归零一次`() {
        assertTrue(
            ViewerPageMotion.mayNormalizeStalePager(
                gestureActive = false, scrollInProgress = false, pagerFraction = 0.22f
            )
        )
        // 没有残留就不碰 pager（避免无意义的 scrollToPage 打断渲染）
        assertFalse(
            ViewerPageMotion.mayNormalizeStalePager(
                gestureActive = false, scrollInProgress = false, pagerFraction = 0f
            )
        )
        assertFalse(
            ViewerPageMotion.mayNormalizeStalePager(
                gestureActive = false, scrollInProgress = false, pagerFraction = 0.004f
            )
        )
    }
}