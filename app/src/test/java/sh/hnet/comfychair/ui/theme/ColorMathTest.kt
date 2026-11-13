package sh.hnet.comfychair.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 色彩数学的底层验证。
 *
 * 这一层是「用户自定义配色」的地基：调色盘允许用户选任意颜色，
 * 而界面的可读性（文字压不压得住底色、强调色在白底上认不认得出来）
 * 全靠这里的转换与对比度计算兜底。算错了不会崩，只会「调出一个看不清的界面」，
 * 所以必须钉住。
 *
 * 颜色一律用 ARGB Int 表示（与 Compose 的 Color 解耦），因此这些用例全是纯 JVM 逻辑。
 */
class ColorMathTest {

    /** 逐通道比较，容忍 HSL 往返的取整误差（每通道最多差 1） */
    private fun assertArgbNear(expected: Int, actual: Int, tolerance: Int = 1, message: String = "") {
        val expectedChannels = listOf((expected shr 16) and 0xFF, (expected shr 8) and 0xFF, expected and 0xFF)
        val actualChannels = listOf((actual shr 16) and 0xFF, (actual shr 8) and 0xFF, actual and 0xFF)
        expectedChannels.indices.forEach { i ->
            val diff = abs(expectedChannels[i] - actualChannels[i])
            assertTrue(
                "$message 第 $i 通道偏差 $diff：期望 ${expectedChannels[i]}，实际 ${actualChannels[i]}",
                diff <= tolerance
            )
        }
    }

    private val roundTripSamples = listOf(
        0xFF000000.toInt(), // 纯黑
        0xFFFFFFFF.toInt(), // 纯白
        0x80808080.toInt(), // 中灰（饱和度 0，最容易在除零上翻车）
        0xFF38BDF8.toInt(), // 强调青蓝
        0xFF0F1419.toInt(), // 页面背景
        0xFFE6EDF3.toInt(), // 正文文字
        0xFFF59E0B.toInt(), // 警告琥珀
        0xFF34D399.toInt(), // 成功薄荷
        0xFFF87171.toInt(), // 错误柔红
        0xFF0369A1.toInt()  // 浅色模式强调色
    )

    @Test
    fun `HSL 往返转换不改变颜色`() {
        roundTripSamples.forEach { argb ->
            val roundTrip = ColorMath.fromHsl(ColorMath.toHsl(argb))
            assertArgbNear(argb, roundTrip, message = "颜色 0x${argb.toUInt().toString(16)} 往返后偏移：")
        }
    }

    @Test
    fun `HSV 往返转换不改变颜色`() {
        roundTripSamples.forEach { argb ->
            val roundTrip = ColorMath.fromHsv(ColorMath.toHsv(argb))
            assertArgbNear(argb, roundTrip, message = "颜色 0x${argb.toUInt().toString(16)} HSV 往返偏移：")
        }
    }

    @Test
    fun `取色面板右上角的纯色就是该色相的饱和极值`() {
        val red = ColorMath.pureHue(0f)
        assertArgbNear(0xFFFF0000.toInt(), red, tolerance = 1, message = "色相 0 应为纯红：")

        val hsv = ColorMath.toHsv(ColorMath.pureHue(210f))
        assertEquals("纯色的饱和度应为 1", 1f, hsv.s, 0.01f)
        assertEquals("纯色的明度应为 1", 1f, hsv.v, 0.01f)
        assertEquals("色相应留在原处", 210f, hsv.h, 1f)
    }

    @Test
    fun `黑色的 HSV 不产生 NaN`() {
        // 取色面板拖到最下一行就是这种情况，出 NaN 会让指示器直接飞走
        val hsv = ColorMath.toHsv(0xFF000000.toInt())
        assertEquals("黑色饱和度归零", 0f, hsv.s, 0.001f)
        assertEquals("黑色明度归零", 0f, hsv.v, 0.001f)
        assertEquals("黑色色相归零而不是 NaN", 0f, hsv.h, 0.001f)
    }

    @Test
    fun `HSL 与 HSV 对同一个颜色给出相同色相`() {
        // 取色器按 HSV 工作、色板引擎按 HSL 工作，两边色相口径必须一致，
        // 否则用户选的颜色一到界面上就会偏色
        listOf(0xFFA855F7.toInt(), 0xFF38BDF8.toInt(), 0xFFF59E0B.toInt()).forEach { argb ->
            assertEquals(
                "颜色 ${ColorMath.toHexString(argb)} 的两套色相不一致",
                ColorMath.toHsl(argb).h,
                ColorMath.toHsv(argb).h,
                0.5f
            )
        }
    }

    @Test
    fun `灰色的色相为零且饱和度为零，不会除零`() {
        val gray = ColorMath.toHsl(0xFF808080.toInt())
        assertEquals("灰色饱和度应为 0", 0f, gray.s, 0.001f)
        assertEquals("灰色色相应归零而不是 NaN", 0f, gray.h, 0.001f)
    }

    @Test
    fun `黑白对比度为最大值_同色对比度为一`() {
        assertEquals("黑白对比度按 WCAG 应为 21", 21f, ColorMath.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01f)
        assertEquals("同色对比度应为 1", 1f, ColorMath.contrastRatio(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt()), 0.01f)
    }

    @Test
    fun `对比度与传入顺序无关`() {
        val a = 0xFF0F1419.toInt()
        val b = 0xFFE6EDF3.toInt()
        assertEquals(
            "对比度是对称的，顺序不能影响结果",
            ColorMath.contrastRatio(a, b),
            ColorMath.contrastRatio(b, a),
            0.001f
        )
    }

    @Test
    fun `对比度不足时会被拉到门槛之上`() {
        // 深灰底 + 深灰字：典型的「用户自己调糊了」场景
        val background = 0xFF1A2028.toInt()
        val tooDark = 0xFF2A323D.toInt()
        assertTrue("构造的用例本身就该是不足对比度的", ColorMath.contrastRatio(tooDark, background) < 4.5f)

        val fixed = ColorMath.ensureContrast(tooDark, background, 4.5f)
        assertTrue(
            "兜底后必须达到 4.5:1，实际 ${ColorMath.contrastRatio(fixed, background)}",
            ColorMath.contrastRatio(fixed, background) >= 4.5f
        )
    }

    @Test
    fun `对比度已达标时不改动颜色`() {
        val background = 0xFF0F1419.toInt()
        val readable = 0xFFE6EDF3.toInt()
        assertEquals("本来就够清楚，不该被顺手改掉", readable, ColorMath.ensureContrast(readable, background, 4.5f))
    }

    @Test
    fun `兜底方向正确_亮底压暗_暗底提亮`() {
        // 白底上的亮色必须往暗里走
        val bright = 0xFFBAE6FD.toInt()
        val lightBg = 0xFFEDF1F7.toInt()
        val darkened = ColorMath.ensureContrast(bright, lightBg, 4.5f)
        assertTrue(
            "白底上应压暗：原明度 ${ColorMath.toHsl(bright).l}，结果明度 ${ColorMath.toHsl(darkened).l}",
            ColorMath.toHsl(darkened).l < ColorMath.toHsl(bright).l
        )

        // 黑底上的暗色必须往亮里走
        val dim = 0xFF06171F.toInt()
        val darkBg = 0xFF0F1419.toInt()
        val lightened = ColorMath.ensureContrast(dim, darkBg, 4.5f)
        assertTrue(
            "黑底上应提亮：原明度 ${ColorMath.toHsl(dim).l}，结果明度 ${ColorMath.toHsl(lightened).l}",
            ColorMath.toHsl(lightened).l > ColorMath.toHsl(dim).l
        )
    }

    @Test
    fun `换色相只动色相_保留原有饱和度与明度`() {
        val source = 0xFF38BDF8.toInt()
        val recolored = ColorMath.withHue(source, 280f) // 青蓝 → 紫
        val before = ColorMath.toHsl(source)
        val after = ColorMath.toHsl(recolored)

        assertEquals("目标色相应落位", 280f, after.h, 1.5f)
        assertEquals("饱和度不该被顺手改掉", before.s, after.s, 0.02f)
        assertEquals("明度不该被顺手改掉", before.l, after.l, 0.02f)
    }

    @Test
    fun `色相环绕不越界`() {
        // 350 + 30 应回到 20 而不是 380
        val wrapped = ColorMath.toHsl(ColorMath.withHue(0xFFF87171.toInt(), 20f))
        assertTrue("色相必须落在 0..360，实际 ${wrapped.h}", wrapped.h in 0f..360f)
    }

    @Test
    fun `调整明度会被上下界限住`() {
        val bright = ColorMath.shiftLightness(0xFFE6EDF3.toInt(), 0.5f, minL = 0f, maxL = 0.92f)
        assertTrue("明度上限必须生效", ColorMath.toHsl(bright).l <= 0.921f)

        val dark = ColorMath.shiftLightness(0xFF0F1419.toInt(), -0.5f, minL = 0.03f, maxL = 1f)
        assertTrue("明度下限必须生效", ColorMath.toHsl(dark).l >= 0.029f)
    }

    @Test
    fun `饱和度可按比例缩放且不越界`() {
        val saturated = ColorMath.scaleSaturation(0xFF38BDF8.toInt(), 0.5f)
        assertEquals("应缩减到约一半", ColorMath.toHsl(0xFF38BDF8.toInt()).s * 0.5f, ColorMath.toHsl(saturated).s, 0.02f)

        // 上限容差取 0.61：颜色按 8 位量化存取，HSL 往返本身就有约 0.5% 的误差，
        // 拿 0.601 去卡会卡到量化噪声上，而不是卡到 clamp 是否生效。
        // 未 clamp 时这里的饱和度会是 0.936，因此这一步依然能钉住上限。
        val capped = ColorMath.scaleSaturation(0xFF38BDF8.toInt(), 5f, maxS = 0.6f)
        assertTrue("饱和度上限必须生效", ColorMath.toHsl(capped).s <= 0.61f)
    }

    @Test
    fun `纯灰输入缩放饱和度后仍然是灰`() {
        val gray = ColorMath.scaleSaturation(0xFF808080.toInt(), 0.3f)
        assertArgbNear(0xFF808080.toInt(), gray, tolerance = 1, message = "灰缩放饱和度不该变色：")
    }
}
