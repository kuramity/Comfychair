package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器横向越界（rubber-band）的纯逻辑测试（G1，v4.16.11）。
 * 实现见 ViewerDismissGestureLogic.kt 的 ViewerOverscroll，UI 侧只在
 * MediaViewerScreen 的嵌套滚动连接里喂增量、写平移。
 *
 * 钉住四件事：
 * 1. **两端越界方向对称**——首张只能往"上一张"方向越界、末张只能往"下一张"
 *    方向越界，页间正常翻页一律不算越界；
 * 2. 未越界的输入（零增量 / 非有限 / 空列表）一律返回 false，不产生位移；
 * 3. 位移映射非线性衰减、严格小于上限（超上限夹取的数学保证）；
 * 4. 反解函数可把位移还原成原始拖拽量（回弹中再按下时的无缝接管依赖它）。
 */
class ViewerOverscrollTest {

    // ---------- 1. 两端越界方向（对称） ----------

    @Test
    fun `首张往上一张方向越界`() {
        // deltaX > 0 = 内容右移 = 看上一张；首张没有上一张 → 越界
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(0, 4, 48f))
    }

    @Test
    fun `首张往下一张方向不越界`() {
        // 同一页反方向是正常翻页：pager 自己消费增量，不该出现越界位移
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(0, 4, -48f))
    }

    @Test
    fun `末张往下一张方向越界`() {
        // 末张继续往左拉（内容左移 = 看下一张）正是 E2 的穿帮方向
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(3, 4, -48f))
    }

    @Test
    fun `末张往上一张方向不越界`() {
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(3, 4, 48f))
    }

    @Test
    fun `中间页两个方向都不越界`() {
        // 中间页左右都有邻页：任何水平增量都该被 pager 吃进翻页
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(1, 4, 48f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(1, 4, -48f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(2, 4, 48f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(2, 4, -48f))
    }

    @Test
    fun `两端判定严格镜像`() {
        // 把"首张往右拉"镜像成"末张往左拉"，结论必须一致——两端对称生效的实现保证
        val pageCount = 9
        for (delta in listOf(1f, 40f, 500f)) {
            assertEquals(
                ViewerOverscroll.isOutOfBoundsDirection(0, pageCount, delta),
                ViewerOverscroll.isOutOfBoundsDirection(pageCount - 1, pageCount, -delta)
            )
            assertTrue(ViewerOverscroll.isOutOfBoundsDirection(0, pageCount, delta))
        }
    }

    @Test
    fun `单页两端都越界`() {
        // 只有一张图：左右两边都没有邻页，两个方向都是边界
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(0, 1, 30f))
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(0, 1, -30f))
    }

    @Test
    fun `空列表与非有限增量不算越界`() {
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(0, 0, 30f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(-1, 0, -30f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(0, 4, 0f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(0, 4, Float.NaN))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(0, 4, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `页码越界按最近端点处理`() {
        // 删除条目的瞬间 currentPage 可能短暂越界：钳到最近端点后只认该端点的外拉方向，
        // 不会因为越界页码把另一端也判成边界（避免越界位移乱蹦）
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(9, 4, -30f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(9, 4, 30f))
        assertTrue(ViewerOverscroll.isOutOfBoundsDirection(-2, 4, 30f))
        assertFalse(ViewerOverscroll.isOutOfBoundsDirection(-2, 4, -30f))
    }

    // ---------- 2. 位移映射：非线性衰减 + 上限 ----------

    @Test
    fun `起步与手指一比一`() {
        // damping = 1 时，原始拖拽 2px 的位移误差应在可忽略范围内（跟手不滞后）
        val displacement = ViewerOverscroll.rubberBandDisplacement(2f, 200f, 1f)
        assertTrue("起步应接近 1:1，实际 $displacement", displacement > 2f * 0.98f)
        assertTrue(displacement <= 2f)
    }

    @Test
    fun `位移随拖拽距离非线性衰减`() {
        val max = 200f
        val d100 = ViewerOverscroll.rubberBandDisplacement(100f, max, 1f)
        val d200 = ViewerOverscroll.rubberBandDisplacement(200f, max, 1f)
        // 单调递增
        assertTrue(d100 < d200)
        // 但增长慢于线性：拖两倍距离拿不到两倍位移
        assertTrue("非线性应满足 d(2x) < 2·d(x)，实际 $d200 vs $d100", d200 < 2f * d100)
        // 线性部分被压掉：d(100) 明显小于 100
        assertTrue(d100 < 100f)
    }

    @Test
    fun `超上限夹取`() {
        val max = 200f
        val huge = ViewerOverscroll.rubberBandDisplacement(1_000_000f, max, 1f)
        assertTrue("位移必须小于上限，实际 $huge", huge < max)
        assertTrue("极远拖拽应逼近上限，实际 $huge", huge > max * 0.99f)
        // 反向同理
        val hugeNegative = ViewerOverscroll.rubberBandDisplacement(-1_000_000f, max, 1f)
        assertTrue(hugeNegative > -max)
        assertTrue(hugeNegative < -max * 0.99f)
    }

    @Test
    fun `符号保持`() {
        val max = 200f
        for (raw in listOf(1f, 30f, 300f, 5000f)) {
            assertEquals(
                ViewerOverscroll.rubberBandDisplacement(raw, max, 1f),
                -ViewerOverscroll.rubberBandDisplacement(-raw, max, 1f),
                0.0001f
            )
        }
    }

    @Test
    fun `零位移与异常输入一律为零`() {
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(0f, 200f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(Float.NaN, 200f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(50f, 0f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(50f, -20f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(50f, Float.NaN, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(50f, 200f, 0f), 0f)
        assertEquals(0f, ViewerOverscroll.rubberBandDisplacement(50f, 200f, Float.NaN), 0f)
    }

    @Test
    fun `阻尼系数越小位移越短`() {
        val max = 200f
        val soft = ViewerOverscroll.rubberBandDisplacement(120f, max, 1f)
        val stiff = ViewerOverscroll.rubberBandDisplacement(120f, max, 0.5f)
        assertTrue(stiff < soft)
        assertTrue(stiff > 0f)
    }

    // ---------- 3. 反解（回弹中再次按下的无缝接管） ----------

    @Test
    fun `反解能把位移还原成原始拖拽量`() {
        val max = ViewerOverscroll.maxDisplacementPx(1080f)
        for (raw in listOf(20f, 120f, 400f, 1500f)) {
            val displacement = ViewerOverscroll.rubberBandDisplacement(
                raw, max, ViewerOverscroll.DAMPING
            )
            val restored = ViewerOverscroll.rawDistanceForDisplacement(
                displacement, max, ViewerOverscroll.DAMPING
            )
            assertEquals(raw.toDouble(), restored.toDouble(), raw * 0.001 + 0.01)
        }
        // 负向同样往返
        val negative = ViewerOverscroll.rubberBandDisplacement(-400f, max, 1f)
        val restoredNegative = ViewerOverscroll.rawDistanceForDisplacement(negative, max, 1f)
        assertEquals(-400f.toDouble(), restoredNegative.toDouble(), 0.5)
    }

    @Test
    fun `反解的异常输入为零且不炸出无穷`() {
        assertEquals(0f, ViewerOverscroll.rawDistanceForDisplacement(0f, 200f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rawDistanceForDisplacement(Float.NaN, 200f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rawDistanceForDisplacement(50f, 0f, 1f), 0f)
        assertEquals(0f, ViewerOverscroll.rawDistanceForDisplacement(50f, 200f, 0f), 0f)
        // 位移贴着上限：反解结果必须有限（不会除零）
        val atCap = ViewerOverscroll.rawDistanceForDisplacement(200f, 200f, 1f)
        assertTrue(atCap.isFinite())
    }

    // ---------- 4. 视口宽度换算 ----------

    @Test
    fun `越界上限按视口宽度换算`() {
        assertEquals(
            1080f * ViewerOverscroll.MAX_DISPLACEMENT_RATIO,
            ViewerOverscroll.maxDisplacementPx(1080f),
            0.001f
        )
        // 尺寸未测得 / 异常：0（宁可不跟手，也不把位移写到 NaN 宽度上）
        assertEquals(0f, ViewerOverscroll.maxDisplacementPx(0f), 0f)
        assertEquals(0f, ViewerOverscroll.maxDisplacementPx(-100f), 0f)
        assertEquals(0f, ViewerOverscroll.maxDisplacementPx(Float.NaN), 0f)
    }

    @Test
    fun `上限比例保持一屏以内的手感`() {
        // 并排两端各露 15% 视口，越界上限只比它略宽：拖到顶时黑色带不至于喧宾夺主
        assertTrue(ViewerOverscroll.MAX_DISPLACEMENT_RATIO > ViewerSideBySide.EDGE_FRACTION_PER_PROGRESS)
        assertTrue(ViewerOverscroll.MAX_DISPLACEMENT_RATIO < 0.35f)
    }

    // ---------- 5. 增量累计（外推 / 回拖越过零点） ----------

    @Test
    fun `外推累计全额消费且位移非线性`() {
        val max = 200f
        val first = ViewerOverscroll.accumulateOutward(0f, 40f, max, 1f)
        assertEquals(40f, first.rawDrag, 0.0001f)
        // 全额消费：平台 overscroll 已关闭，不消费就会被父级重复计算
        assertEquals(40f, first.consumed, 0.0001f)
        assertEquals(
            ViewerOverscroll.rubberBandDisplacement(40f, max, 1f),
            first.displacement,
            0.0001f
        )
        // 同样再来 40px：第二段换到的位移比第一段少（非线性衰减）
        val second = ViewerOverscroll.accumulateOutward(first.rawDrag, 40f, max, 1f)
        assertEquals(80f, second.rawDrag, 0.0001f)
        val gainFirst = first.displacement
        val gainSecond = second.displacement - first.displacement
        assertTrue("同样增量应越拉越沉，实际 $gainSecond vs $gainFirst", gainSecond < gainFirst)
    }

    @Test
    fun `回拖未越过零点全段用于回弹`() {
        val max = 200f
        val step = ViewerOverscroll.reverseStep(
            rawDrag = 100f, delta = -30f, maxDistance = max, damping = 1f
        )
        assertEquals(70f, step.rawDrag, 0.0001f)
        // 整段被回弹吃掉：这一帧 pager 拿不到任何可翻页的量
        assertEquals(-30f, step.consumed, 0.0001f)
        assertEquals(
            ViewerOverscroll.rubberBandDisplacement(70f, max, 1f),
            step.displacement,
            0.0001f
        )
    }

    @Test
    fun `回拖越过零点只消费到零点`() {
        // 已越界 100px 原始累计，回拖 150px：先回弹 100，剩余 50 交给 pager 翻页
        val step = ViewerOverscroll.reverseStep(100f, -150f, 200f, 1f)
        assertEquals(0f, step.rawDrag, 0f)
        assertEquals(0f, step.displacement, 0f)
        assertEquals(-100f, step.consumed, 0.0001f)
        assertEquals(-50f, -150f - step.consumed, 0.0001f)
    }

    @Test
    fun `回拖恰好回到零点`() {
        val step = ViewerOverscroll.reverseStep(80f, -80f, 200f, 1f)
        assertEquals(0f, step.rawDrag, 0f)
        assertEquals(0f, step.displacement, 0f)
        assertEquals(-80f, step.consumed, 0.0001f)
    }

    @Test
    fun `无累计或同向增量不消费`() {
        // 没有越界位移：增量全归 pager
        assertEquals(0f, ViewerOverscroll.reverseStep(0f, -30f, 200f, 1f).consumed, 0f)
        // 同向（继续外拉）：不属于回拖，不消费（由 accumulateOutward 接管）
        val sameSign = ViewerOverscroll.reverseStep(100f, 30f, 200f, 1f)
        assertEquals(0f, sameSign.consumed, 0f)
        assertEquals(100f, sameSign.rawDrag, 0f)
    }

    @Test
    fun `累计的异常输入不产生 NaN`() {
        val nan = ViewerOverscroll.accumulateOutward(0f, Float.NaN, 200f, 1f)
        assertEquals(0f, nan.consumed, 0f)
        assertEquals(0f, nan.displacement, 0f)
        // 视口宽未测得（上限 0）：位移恒 0，累计照记（尺寸就绪后能接上）
        val zeroMax = ViewerOverscroll.accumulateOutward(50f, 30f, 0f, 1f)
        assertEquals(0f, zeroMax.displacement, 0f)
        assertEquals(80f, zeroMax.rawDrag, 0f)
        val nanReverse = ViewerOverscroll.reverseStep(100f, Float.NaN, 200f, 1f)
        assertEquals(0f, nanReverse.consumed, 0f)
        assertTrue(nanReverse.displacement.isFinite())
    }

    // ---------- 7. 松手甩速的方向判定（G2 补漏，v4.16.13） ----------
    //
    // 被钉住的缺陷：MediaViewerScreen 的 onPreFling 原先只要处在越界状态就
    // `return available`（吞掉全部甩速），不看方向。于是"越界后快速回甩"
    // ——甩向页内、pager 本来可以惯性跨页 —— 也被吞干净，只能靠拖动过半吸附。
    // 拖拽路径的 reverseStep 早就按方向区分了，甩速这边必须同口径。

    @Test
    fun `往外甩吞掉速度`() {
        // 首张往"上一张"方向拉出越界（rawDrag > 0），继续同向甩 → 两端没有可翻的页
        assertTrue(
            ViewerOverscroll.shouldSwallowFlingVelocity(
                rawDrag = 80f, displacement = 40f, velocityX = 1200f
            )
        )
    }

    @Test
    fun `回甩向页内把速度交给 pager`() {
        // 同一越界状态，甩速指向页内（符号相反）→ 不吞，pager 走正常惯性跨页
        assertFalse(
            ViewerOverscroll.shouldSwallowFlingVelocity(
                rawDrag = 80f, displacement = 40f, velocityX = -1200f
            )
        )
    }

    @Test
    fun `甩速方向判定两端严格对称`() {
        // 末张往"下一张"方向拉出越界（rawDrag < 0）：同向（负）吞、反向（正）不吞
        assertTrue(
            ViewerOverscroll.shouldSwallowFlingVelocity(-80f, -40f, -1200f)
        )
        assertFalse(
            ViewerOverscroll.shouldSwallowFlingVelocity(-80f, -40f, 1200f)
        )
    }

    @Test
    fun `甩速判定与拖拽 reverseStep 的方向口径一致`() {
        // 同向输入：拖拽不消费（consumed = 0，增量留给 accumulateOutward），
        // 甩速则必须吞掉 —— 两者对"同向 = 继续往外"的判定必须一致
        val outwardRaw = 100f
        assertEquals(0f, ViewerOverscroll.reverseStep(outwardRaw, 30f, 200f, 1f).consumed, 0f)
        assertTrue(ViewerOverscroll.shouldSwallowFlingVelocity(outwardRaw, 60f, 30f))
        // 反向输入：拖拽按回拖处理（消费到零点或全段回弹），甩速则整份交回 pager
        assertEquals(-30f, ViewerOverscroll.reverseStep(outwardRaw, -30f, 200f, 1f).consumed, 0f)
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(outwardRaw, 60f, -30f))
    }

    @Test
    fun `没有越界状态时不吞甩速`() {
        // 正常翻页途中（无越界）：判定必须返回 false，否则 G2 惯性翻页整体失效
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(0f, 0f, -2000f))
        // 只有一项非零也算"有越界状态"（回弹动画在途：rawDrag 已被 settle 清零）
        assertTrue(ViewerOverscroll.shouldSwallowFlingVelocity(0f, 40f, 800f))
    }

    @Test
    fun `raw 已归零时用位移符号补位`() {
        // 抬手与 fling 在同一帧时 settle 已把 rawDrag 清零，方向只能由位移给出
        assertTrue(ViewerOverscroll.shouldSwallowFlingVelocity(0f, -50f, -900f))
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(0f, -50f, 900f))
        // rawDrag 非有限（异常）时同样退回位移
        assertTrue(ViewerOverscroll.shouldSwallowFlingVelocity(Float.NaN, 50f, 900f))
    }

    @Test
    fun `零速与非有限速度一律不吞`() {
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(80f, 40f, 0f))
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(80f, 40f, Float.NaN))
        assertFalse(ViewerOverscroll.shouldSwallowFlingVelocity(80f, 40f, Float.POSITIVE_INFINITY))
    }
}