package sh.hnet.comfychair.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 色板变换规则。
 *
 * 用户在调色盘里只调**一套**颜色（强调色 / 底色系 / 文字色），深色与浅色两套色板自动适配。
 * 这里的规则决定了「用户选的颜色」如何落到每一个 M3 色槽上：
 *
 * 1. **色相跟随**：用户换色相，整组色槽的相对色相关系保持不变（不是整片涂成同一个色）。
 * 2. **明度只平移、不重排**：原色板的明暗阶梯是设计过的，卡片的明度必须始终高于背景，
 *    否则零阴影的界面会「糊成一片」——这是本文件最要紧的不变量。
 * 3. **可读性兜底**：用户可能选出「深灰字配深灰底」这种组合，引擎负责把结果拉回可读区间，
 *    而不是任由界面变成看不清的样子。
 *
 * 关于锚点：用户选的颜色是与「原厂锚点色」比对后求差值的。
 * 用户选了原厂色 → 差值为零 → 结果与原色板一致，这条恒等性是最基本的回归保证。
 */
class ThemePaletteTransformTest {

    // 原厂锚点
    private val darkAccentAnchor = 0xFF38BDF8.toInt() // 深色 primary 青蓝
    private val darkBaseAnchor = 0xFF0F1419.toInt()   // 深色 background
    private val darkTextAnchor = 0xFFE6EDF3.toInt()   // 深色 onSurface 正文
    private val lightAccentAnchor = 0xFF0369A1.toInt()
    private val lightBaseAnchor = 0xFFEDF1F7.toInt()
    private val lightTextAnchor = 0xFF16202B.toInt()

    // 原厂色板的槽位样本
    private val darkBackground = 0xFF0F1419.toInt()
    private val darkCard = 0xFF1A2028.toInt()
    private val darkContainerHigh = 0xFF222A34.toInt()
    private val darkPrimary = 0xFF38BDF8.toInt()
    private val darkOnPrimaryContainer = 0xFFA5E3FB.toInt()
    private val lightBackground = 0xFFEDF1F7.toInt()
    private val lightCard = 0xFFFFFFFF.toInt()

    private fun assertArgbNear(expected: Int, actual: Int, tolerance: Int = 2, message: String = "") {
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

    private fun hueDistance(a: Float, b: Float): Float {
        val raw = abs(a - b) % 360f
        return if (raw > 180f) 360f - raw else raw
    }

    // ---------- 恒等性：选原厂色 = 什么都不改 ----------

    @Test
    fun `深色模式选原厂锚点等于不修改`() {
        assertArgbNear(
            darkPrimary,
            ThemePaletteTransform.accent(darkPrimary, darkAccentAnchor, ThemeMode.DARK, darkBackground, 4.5f),
            message = "强调色恒等性："
        )
        assertArgbNear(
            darkBackground,
            ThemePaletteTransform.surface(darkBackground, darkBaseAnchor, ThemeMode.DARK),
            message = "底色恒等性："
        )
        assertArgbNear(
            darkCard,
            ThemePaletteTransform.surface(darkCard, darkBaseAnchor, ThemeMode.DARK),
            message = "卡片恒等性："
        )
        assertArgbNear(
            darkTextAnchor,
            ThemePaletteTransform.text(darkTextAnchor, darkTextAnchor, ThemeMode.DARK, darkBackground, 4.5f),
            message = "文字恒等性："
        )
    }

    @Test
    fun `浅色模式选原厂锚点等于不修改`() {
        assertArgbNear(
            lightBackground,
            ThemePaletteTransform.surface(lightBackground, lightBaseAnchor, ThemeMode.LIGHT),
            message = "浅色底色恒等性："
        )
        assertArgbNear(
            lightCard,
            ThemePaletteTransform.surface(lightCard, lightBaseAnchor, ThemeMode.LIGHT),
            message = "浅色卡片恒等性："
        )
    }

    @Test
    fun `浅色模式选原厂色时容器与文字也必须恒等`() {
        // 首版把容器、容器文字、正文的明度区间写成了与模式无关的深色口径，
        // 于是浅色模式下浅底（#CDE9FB，明度 0.894）被压成深底、
        // 深字（#0B3A52，明度 0.182）被提成浅字 —— "选中"的语义整个反过来。
        // 这条用例专门钉住浅色模式的这几类槽位（首版只测了底色，整块漏掉）。
        val lightPrimaryContainer = 0xFFCDE9FB.toInt()
        val lightOnPrimaryContainer = 0xFF0B3A52.toInt()
        val lightSecondaryContainer = 0xFFCFE6F5.toInt()
        val lightOnSurface = 0xFF16202B.toInt()
        val lightPrimarySlot = 0xFF0369A1.toInt()

        assertArgbNear(
            lightPrimaryContainer,
            ThemePaletteTransform.accent(
                lightPrimaryContainer, lightAccentAnchor, ThemeMode.LIGHT, lightBackground, 0f,
                ThemePaletteTransform.accentContainerLightness(ThemeMode.LIGHT)
            ),
            message = "浅色选中底色恒等性："
        )
        assertArgbNear(
            lightSecondaryContainer,
            ThemePaletteTransform.accent(
                lightSecondaryContainer, lightAccentAnchor, ThemeMode.LIGHT, lightBackground, 0f,
                ThemePaletteTransform.accentContainerLightness(ThemeMode.LIGHT)
            ),
            message = "浅色选中容器恒等性："
        )
        assertArgbNear(
            lightOnPrimaryContainer,
            ThemePaletteTransform.accent(
                lightOnPrimaryContainer, lightAccentAnchor, ThemeMode.LIGHT, lightPrimaryContainer, 4.5f,
                ThemePaletteTransform.accentOnContainerLightness(ThemeMode.LIGHT)
            ),
            message = "浅色选中态文字恒等性："
        )
        assertArgbNear(
            lightPrimarySlot,
            ThemePaletteTransform.accent(lightPrimarySlot, lightAccentAnchor, ThemeMode.LIGHT, lightBackground, 4.5f),
            message = "浅色主色恒等性："
        )
        assertArgbNear(
            lightOnSurface,
            ThemePaletteTransform.text(lightOnSurface, lightTextAnchor, ThemeMode.LIGHT, lightBackground, 4.5f),
            message = "浅色正文恒等性："
        )
    }

    // ---------- 色相跟随 ----------

    @Test
    fun `换强调色相后整组槽位一起跟随`() {
        val purple = 0xFFA855F7.toInt() // 紫，色相约 271
        val purplePrimary = ThemePaletteTransform.accent(darkPrimary, purple, ThemeMode.DARK, darkBackground, 4.5f)
        assertEquals(
            "主色色相应落到用户选的紫色上",
            271f,
            ColorMath.toHsl(purplePrimary).h,
            8f
        )

        // 容器文字原本是浅青蓝，换色相后应变成浅紫，而不是原地不动
        val purpleOnContainer = ThemePaletteTransform.accent(
            darkOnPrimaryContainer, purple, ThemeMode.DARK, darkBackground, 4.5f,
            ThemePaletteTransform.accentOnContainerLightness(ThemeMode.DARK)
        )
        assertTrue(
            "容器文字的色相应跟着走（实际 ${ColorMath.toHsl(purpleOnContainer).h}）",
            hueDistance(ColorMath.toHsl(purpleOnContainer).h, 271f) < 25f
        )
        assertTrue(
            "容器文字必须仍然是浅色（实际明度 ${ColorMath.toHsl(purpleOnContainer).l}）",
            ColorMath.toHsl(purpleOnContainer).l > 0.6f
        )
    }

    @Test
    fun `换底色色相后表面阶梯整体换调`() {
        val warmBase = 0xFF2A1B12.toInt() // 暖棕底，色相约 22
        val warmBackground = ThemePaletteTransform.surface(darkBackground, warmBase, ThemeMode.DARK)
        assertEquals(
            "页面背景应变成用户选的暖色调",
            22f,
            ColorMath.toHsl(warmBackground).h,
            10f
        )
        assertTrue(
            "背景仍必须是深色（实际明度 ${ColorMath.toHsl(warmBackground).l}）",
            ColorMath.toHsl(warmBackground).l < 0.15f
        )
    }

    @Test
    fun `改色调时纯白槽位也要跟着染色_整片保持同一色调`() {
        // 浅色模式的卡片与最亮表面是纯白（饱和度恰好为 0），
        // 按比例缩放永远还是 0 —— 整片跟着走的底色会在这里断掉，
        // 出现一块没有色调的灰（实测就是主内容区灰着、顶栏暖着）。
        val warmBase = 0xFF704A26.toInt()
        val background = ThemePaletteTransform.surface(0xFFEDF1F7.toInt(), warmBase, ThemeMode.LIGHT)
        val card = ThemePaletteTransform.surface(0xFFFFFFFF.toInt(), warmBase, ThemeMode.LIGHT)

        val backgroundHsl = ColorMath.toHsl(background)
        val cardHsl = ColorMath.toHsl(card)

        assertEquals("白色卡片也该带上用户的暖色调", 28f, cardHsl.h, 12f)
        assertTrue("卡片必须真的染上色，不能还是一块灰（实际饱和度 ${cardHsl.s}）", cardHsl.s > 0.2f)
        assertTrue(
            "卡片与背景的色度应基本一致（背景 ${backgroundHsl.s} / 卡片 ${cardHsl.s}）",
            abs(backgroundHsl.s - cardHsl.s) < 0.15f
        )
        assertTrue(
            "但卡片仍要比背景亮，层次不能塌（背景 ${backgroundHsl.l} / 卡片 ${cardHsl.l}）",
            cardHsl.l > backgroundHsl.l
        )
    }

    @Test
    fun `选原厂色调时明度不做平移`() {
        // 恒等性的另一半：色相差为 0 时明度也不该被平移。
        //
        // 这里刻意**不**用"白卡片保持纯白"当断言：HSL 在 l=1 时无论饱和度多大都是纯白
        // （chroma = (1-|2l-1|)·s 恒为 0），染色再重也看不出来，
        // 把它当保护等于没有保护（复审实测：把染色强度硬写成 1 该断言照样通过）。
        val card = ThemePaletteTransform.surface(0xFFFFFFFF.toInt(), lightBaseAnchor, ThemeMode.LIGHT)
        assertEquals("原厂色调下卡片明度不该被平移", 1.0f, ColorMath.toHsl(card).l, 0.01f)

        val background = ThemePaletteTransform.surface(lightBackground, lightBaseAnchor, ThemeMode.LIGHT)
        assertEquals(
            "原厂色调下背景明度不该被平移",
            ColorMath.toHsl(lightBackground).l,
            ColorMath.toHsl(background).l,
            0.01f
        )
    }

    @Test
    fun `选中性灰不会让底色偏红`() {
        // 纯灰的色相是反推不出来的（HSL/HSV 一律归零），照用会把"用户要中性灰"
        // 当成"用户要色相 0 的红调"，整片底色莫名其妙偏红/偏粉。
        // 无彩色输入必须按锚点色相处理。
        val gray = 0xFF808080.toInt()
        val background = ThemePaletteTransform.surface(darkBackground, gray, ThemeMode.DARK)
        val hsl = ColorMath.toHsl(background)

        assertTrue(
            "中性灰输入应保持锚点色相（实际色相 ${hsl.h}，锚点 210）",
            abs(hsl.h - ColorMath.toHsl(darkBackground).h) < 12f
        )
        assertTrue("中性灰输入不该有明显色度（实际饱和度 ${hsl.s}）", hsl.s < 0.06f)
    }

    // ---------- 结构保持：层次不能塌 ----------

    @Test
    fun `深色模式改底色后卡片依旧比背景亮`() {
        val warmBase = 0xFF2A1B12.toInt()
        val newBackground = ThemePaletteTransform.surface(darkBackground, warmBase, ThemeMode.DARK)
        val newCard = ThemePaletteTransform.surface(darkCard, warmBase, ThemeMode.DARK)
        val newContainerHigh = ThemePaletteTransform.surface(darkContainerHigh, warmBase, ThemeMode.DARK)

        val lBg = ColorMath.toHsl(newBackground).l
        val lCard = ColorMath.toHsl(newCard).l
        val lHigh = ColorMath.toHsl(newContainerHigh).l

        assertTrue("卡片必须比背景亮，否则界面糊成一片（背景 $lBg / 卡片 $lCard）", lCard > lBg)
        assertTrue("高层容器必须比卡片亮，顶栏才分得出来（卡片 $lCard / 高层 $lHigh）", lHigh > lCard)
    }

    @Test
    fun `浅色模式改底色后卡片依旧比背景亮`() {
        val coolBase = 0xFFDDE6F0.toInt()
        val newBackground = ThemePaletteTransform.surface(lightBackground, coolBase, ThemeMode.LIGHT)
        val newCard = ThemePaletteTransform.surface(lightCard, coolBase, ThemeMode.LIGHT)
        assertTrue(
            "浅色模式下卡片同样要比背景亮（背景 ${ColorMath.toHsl(newBackground).l} / 卡片 ${ColorMath.toHsl(newCard).l}）",
            ColorMath.toHsl(newCard).l > ColorMath.toHsl(newBackground).l
        )
    }

    // ---------- 防呆：极端输入不能做废界面 ----------

    @Test
    fun `深色模式选近乎全黑的背景也不会把底色提亮到看不清`() {
        val pitchBlack = 0xFF000000.toInt()
        val newBackground = ThemePaletteTransform.surface(darkBackground, pitchBlack, ThemeMode.DARK)
        assertTrue(
            "背景明度必须被压在下限附近（实际 ${ColorMath.toHsl(newBackground).l}）",
            ColorMath.toHsl(newBackground).l <= 0.30f
        )
    }

    @Test
    fun `深色模式选纯白背景会被压在安全区间内`() {
        val pureWhite = 0xFFFFFFFF.toInt()
        val newBackground = ThemePaletteTransform.surface(darkBackground, pureWhite, ThemeMode.DARK)
        assertTrue(
            "深色模式的背景不能被用户调成白底（实际明度 ${ColorMath.toHsl(newBackground).l}）",
            ColorMath.toHsl(newBackground).l <= 0.30f
        )
        val newCard = ThemePaletteTransform.surface(darkCard, pureWhite, ThemeMode.DARK)
        assertTrue(
            "卡片同样受同一上限约束（实际 ${ColorMath.toHsl(newCard).l}）",
            ColorMath.toHsl(newCard).l <= 0.35f
        )
    }

    @Test
    fun `浅色模式选深色背景会被抬到安全区间内`() {
        val darkChoice = 0xFF101418.toInt()
        val newBackground = ThemePaletteTransform.surface(lightBackground, darkChoice, ThemeMode.LIGHT)
        assertTrue(
            "浅色模式的背景不能被调成深色（实际明度 ${ColorMath.toHsl(newBackground).l}）",
            ColorMath.toHsl(newBackground).l >= 0.70f
        )
    }

    @Test
    fun `深色模式选极暗强调色会被提亮到可读`() {
        val nearBlack = 0xFF050510.toInt()
        val result = ThemePaletteTransform.accent(darkPrimary, nearBlack, ThemeMode.DARK, darkBackground, 4.5f)
        assertTrue(
            "强调色必须与页面背景达到 4.5:1 才认得出（实际 ${ColorMath.contrastRatio(result, darkBackground)}）",
            ColorMath.contrastRatio(result, darkBackground) >= 4.5f
        )
    }

    @Test
    fun `浅色模式选极亮强调色会被压暗到可读`() {
        val nearWhite = 0xFFFDFDFD.toInt()
        val result = ThemePaletteTransform.accent(lightAccentAnchor, nearWhite, ThemeMode.LIGHT, lightBackground, 4.5f)
        assertTrue(
            "白底上的强调色必须压暗（实际对比度 ${ColorMath.contrastRatio(result, lightBackground)}）",
            ColorMath.contrastRatio(result, lightBackground) >= 4.5f
        )
    }

    @Test
    fun `深色模式选深色文字会被兜底到可读`() {
        val darkChoice = 0xFF141414.toInt()
        val result = ThemePaletteTransform.text(darkTextAnchor, darkChoice, ThemeMode.DARK, darkBackground, 4.5f)
        assertTrue(
            "正文必须与背景达到 4.5:1（实际 ${ColorMath.contrastRatio(result, darkBackground)}）",
            ColorMath.contrastRatio(result, darkBackground) >= 4.5f
        )
    }

    @Test
    fun `次要文字对比度门槛更低但仍有下限`() {
        val midChoice = 0xFF808080.toInt()
        val result = ThemePaletteTransform.text(0xFF8B98A5.toInt(), midChoice, ThemeMode.DARK, darkBackground, 3.0f)
        assertTrue(
            "次要文字至少要有 3:1（实际 ${ColorMath.contrastRatio(result, darkBackground)}）",
            ColorMath.contrastRatio(result, darkBackground) >= 3.0f
        )
    }

    @Test
    fun `用户选纯灰时强调色也应去饱和而不是保留青蓝`() {
        val gray = 0xFF808080.toInt()
        val result = ThemePaletteTransform.accent(darkPrimary, gray, ThemeMode.DARK, darkBackground, 4.5f)
        assertTrue(
            "选灰就该得到灰强调色（实际饱和度 ${ColorMath.toHsl(result).s}）",
            ColorMath.toHsl(result).s < 0.25f
        )
        assertTrue(
            "依然要保证可读（实际 ${ColorMath.contrastRatio(result, darkBackground)}）",
            ColorMath.contrastRatio(result, darkBackground) >= 4.5f
        )
    }

    // ---------- 中灰底：兜底必须会换方向 ----------

    @Test
    fun `中灰底色上兜底会转向另一侧搜索`() {
        // 中灰底（相对亮度约 0.47）会被判成"暗底"→ 先往亮里推，
        // 但往白推最多只有约 2:1，永远达不到 4.5，唯一的出路是切到反向搜索。
        // 首版这条分支完全没有测试覆盖（删掉那行也没有用例变红）。
        val midGray = 0xFFB4B9C0.toInt()
        val source = 0xFF8B98A5.toInt()
        assertTrue(
            "构造的用例本身就该不达标（实际 ${ColorMath.contrastRatio(source, midGray)}）",
            ColorMath.contrastRatio(source, midGray) < 4.5f
        )

        val fixed = ColorMath.ensureContrast(source, midGray, 4.5f)
        assertTrue(
            "必须换方向后达标（实际 ${ColorMath.contrastRatio(fixed, midGray)}）",
            ColorMath.contrastRatio(fixed, midGray) >= 4.5f
        )
    }

    // ---------- 状态色兜底 ----------

    @Test
    fun `状态色在用户调暗的底色上仍读得出`() {
        // 状态色不跟用户色相走，但用户把底色压暗之后，
        // 原本为原厂底色配的琥珀色会掉到看不清（实测约 2.9:1）
        val dimmedLightBackground = 0xFFA8BACC.toInt()
        val secondary = 0xFFB45309.toInt()
        assertTrue(
            "构造的用例本身就该不达标（实际 ${ColorMath.contrastRatio(secondary, dimmedLightBackground)}）",
            ColorMath.contrastRatio(secondary, dimmedLightBackground) < 3f
        )

        val fixed = ThemePaletteTransform.statusColor(secondary, dimmedLightBackground, 3f)
        assertTrue(
            "兜底后必须 >=3:1（实际 ${ColorMath.contrastRatio(fixed, dimmedLightBackground)}）",
            ColorMath.contrastRatio(fixed, dimmedLightBackground) >= 3f
        )
    }

    // ---------- 配对文字色 ----------

    @Test
    fun `在深底上自动配浅字_浅底上自动配深字`() {
        val onDark = ThemePaletteTransform.readableOn(darkBackground)
        assertTrue(
            "深底应配浅字（实际明度 ${ColorMath.toHsl(onDark).l}）",
            ColorMath.toHsl(onDark).l > 0.6f
        )
        val onLight = ThemePaletteTransform.readableOn(lightCard)
        assertTrue(
            "浅底应配深字（实际明度 ${ColorMath.toHsl(onLight).l}）",
            ColorMath.toHsl(onLight).l < 0.45f
        )
    }
}
