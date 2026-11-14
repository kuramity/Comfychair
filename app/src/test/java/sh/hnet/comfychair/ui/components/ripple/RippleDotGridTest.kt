package sh.hnet.comfychair.ui.components.ripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水面波纹点阵纯逻辑层的几何/时间模型测试。
 *
 * 这些断言钉住的是"视觉契约"：点阵密度（短边÷4 自适应）、波前扫过时的亮度节奏、
 * 波过回落、多道波纹的上凸累加。任何一个被破坏，用户看到的要么是糊成一片、
 * 要么是波纹永远不会停。
 */
class RippleDotGridTest {

    private val grid = RippleDotGrid()

    private fun sampleBuffer() = FloatArray(RippleDotGrid.MAX_DOTS * RippleDotGrid.FLOATS_PER_DOT)

    @Test
    fun `采样缓冲每点4个字段`() {
        // v5.x 起输出 x / y / boost / lift 四元组，绘制层按它偏移与点亮
        assertEquals(4, RippleDotGrid.FLOATS_PER_DOT)
    }

    @Test
    fun `点阵坐标都在控件范围内且不超过400点`() {
        // 200x56：间距 = 56/4 = 14dp → cols=14, rows=4，共 56 点
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        assertEquals(56, grid.dotCount)
        val out = sampleBuffer()
        grid.sample(emptyList(), 0.0, out)
        for (i in 0 until grid.dotCount) {
            val x = out[i * RippleDotGrid.FLOATS_PER_DOT]
            val y = out[i * RippleDotGrid.FLOATS_PER_DOT + 1]
            assertTrue("点($x,$y) 超出控件 200x56", x in 0f..200f && y in 0f..56f)
        }

        // 超大控件：间距自动加大，点数封顶
        grid.rebuildGrid(widthDp = 800f, heightDp = 600f, lowRam = false)
        assertTrue("点数 ${grid.dotCount} 超过上限 400", grid.dotCount <= RippleDotGrid.MAX_DOTS)
        grid.sample(emptyList(), 0.0, out)
        for (i in 0 until grid.dotCount) {
            val x = out[i * RippleDotGrid.FLOATS_PER_DOT]
            val y = out[i * RippleDotGrid.FLOATS_PER_DOT + 1]
            assertTrue("点($x,$y) 超出控件 800x600", x in 0f..800f && y in 0f..600f)
        }
    }

    @Test
    fun `间距等于短边除以4`() {
        // 决策 5：48dp 圆按钮 → 12dp（4×4 点），56dp 高胶囊 → 短边 56/4 = 14dp
        grid.rebuildGrid(widthDp = 48f, heightDp = 48f, lowRam = false)
        assertEquals(12f, grid.currentSpacingDp, 0.0001f)
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        assertEquals(14f, grid.currentSpacingDp, 0.0001f)
    }

    @Test
    fun `间距clamp到10到18dp`() {
        // 极小控件：20/4 = 5 → 抬到下限 10，避免点糊成一片
        grid.rebuildGrid(widthDp = 20f, heightDp = 20f, lowRam = false)
        assertEquals(10f, grid.currentSpacingDp, 0.0001f)
        // 极大按钮：100/4 = 25 → 压到上限 18，保持"点阵是皮肤"而不是"点阵是像素"
        grid.rebuildGrid(widthDp = 100f, heightDp = 100f, lowRam = false)
        assertEquals(18f, grid.currentSpacingDp, 0.0001f)
    }

    @Test
    fun `波前经过时该点boost大于0`() {
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        // 200x56 的网格：间距 14、offset (9,7)，(107,21) 是确定的网格点（col=7,row=1 → idx=21）
        val idx = 1 * 14 + 7
        val ripple = RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0)
        val out = sampleBuffer()
        grid.sample(listOf(ripple), timeSec = 10.001, out)
        val boost = out[idx * RippleDotGrid.FLOATS_PER_DOT + 2]
        assertTrue("波前刚扫过点 (107,21) 时 boost 应 > 0，实际 $boost", boost > 0f)
        assertTrue("boost 不应超过 1", boost <= 1f)
    }

    @Test
    fun `波完全越过该点后boost回落为0`() {
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        val idx = 1 * 14 + 7
        val ripple = RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0)
        val out = sampleBuffer()
        // 波前越过该点 40dp（> 30dp 波前带宽）；速度 150dp/s 时 age≈0.267s，寿命 1.2s
        // 还剩约 78% —— 回落是"波过"而非"寿命到"
        grid.sample(listOf(ripple), timeSec = 10.0 + 40.0 / 150.0, out)
        assertEquals(0f, out[idx * RippleDotGrid.FLOATS_PER_DOT + 2], 0.0001f)
    }

    @Test
    fun `波纹超过寿命后所有点boost为0`() {
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        val ripple = RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0)
        val out = sampleBuffer()
        // age = 1.3 > LIFE = 1.2，整道作废
        grid.sample(listOf(ripple), timeSec = 11.3, out)
        for (i in 0 until grid.dotCount) {
            assertEquals(
                "寿命外点 $i 的 boost 应为 0",
                0f,
                out[i * RippleDotGrid.FLOATS_PER_DOT + 2],
                0.0001f,
            )
            assertEquals(
                "寿命外点 $i 的 lift 应为 0",
                0f,
                out[i * RippleDotGrid.FLOATS_PER_DOT + 3],
                0.0001f,
            )
        }
    }

    @Test
    fun `lowRam设备点数明显少于普通设备`() {
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        val normal = grid.dotCount
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = true)
        val low = grid.dotCount
        assertTrue("lowRam 点数($low) 应明显少于 normal($normal)", low * 2 < normal)
    }

    @Test
    fun `活跃波纹超过4道时丢弃最旧的`() {
        val centers = mutableListOf<RippleDotGrid.RippleCenter>()
        repeat(6) { i ->
            grid.pushRipple(centers, x = i * 10f, y = 0f, timeSec = 1.0 + i)
        }
        assertEquals(4, centers.size)
        // 最旧的两次（x=0、x=10）被挤出，剩下的是第 3~6 次
        assertEquals(20f, centers.first().x, 0.0001f)
        assertEquals(50f, centers.last().x, 0.0001f)
    }

    @Test
    fun `寿命衰减全程线性_寿命中点恰为一半`() {
        val ripple = RippleDotGrid.RippleCenter(x = 0f, y = 0f, bornAtSec = 10.0)
        assertEquals(1.0, ripple.lifeFadeAt(10.0), 0.0001)
        // LIFE = 1.2，半寿命处（age=0.6）线性衰减正好一半
        assertEquals(0.5, ripple.lifeFadeAt(10.6), 0.0001)
        assertTrue(
            "寿命末段继续线性衰减（0 < fade < 1），实际 ${ripple.lifeFadeAt(10.9)}",
            ripple.lifeFadeAt(10.9) in 0.0..1.0,
        )
        assertFalse(ripple.lifeFadeAt(10.44) == ripple.lifeFadeAt(10.50)) // 单调递减
        assertEquals(0.0, ripple.lifeFadeAt(11.2), 0.0001)
        assertEquals(0.0, ripple.lifeFadeAt(9.99), 0.0001) // 尚未出生
    }

    @Test
    fun `上凸按道累加且总峰值封顶6dp`() {
        grid.rebuildGrid(widthDp = 200f, heightDp = 56f, lowRam = false)
        val idx = 1 * 14 + 7
        val out = sampleBuffer()

        // 单道：boost≈0.995 的波前贴着点 → lift ≈ 2.2
        val one = listOf(RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0))
        grid.sample(one, timeSec = 10.001, out)
        val liftSingle = out[idx * RippleDotGrid.FLOATS_PER_DOT + 3]
        assertTrue("单道 lift 应在 (1, 2.2] 区间，实际 $liftSingle", liftSingle in 1f..2.2f)

        // 两道同刻波纹叠同一点：lift 必须高于单道（按道累加，不是取最大）
        val two = listOf(
            RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0),
            RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0),
        )
        grid.sample(two, timeSec = 10.001, out)
        val liftDouble = out[idx * RippleDotGrid.FLOATS_PER_DOT + 3]
        assertTrue(
            "两道 lift($liftDouble) 必须大于单道($liftSingle)",
            liftDouble > liftSingle,
        )

        // 四道连点：4 × ~2.2 ≈ 8.8 → 总上限 6dp 必须生效
        val four = List(4) {
            RippleDotGrid.RippleCenter(x = 107f, y = 21f, bornAtSec = 10.0)
        }
        grid.sample(four, timeSec = 10.001, out)
        val liftQuad = out[idx * RippleDotGrid.FLOATS_PER_DOT + 3]
        assertEquals("四道叠加后必须封顶在 6dp", RippleDotGrid.DOT_LIFT_TOTAL_MAX_DP, liftQuad, 0.0001f)
    }
}
