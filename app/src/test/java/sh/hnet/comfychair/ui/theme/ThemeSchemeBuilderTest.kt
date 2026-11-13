package sh.hnet.comfychair.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色板映射表的端到端验证。
 *
 * [ThemePaletteTransform] 的单测钉住了单个色槽的变换规则，
 * 但"哪个槽归哪一组、兜底拿谁当基准"是在这里决定的 —— 归错组不会崩，
 * 只会出现「换了底色，卡片还是原厂蓝灰」这种花屏，所以要有测试盯着整张表。
 *
 * 基准色板必须用生产的那两套（[DarkColorScheme]/[LightColorScheme]），
 * 不能用 M3 默认色板：变换锚点是按生产色板定的，拿默认色板来测会与锚点错配，
 * 测出一堆假失败（首轮就踩过这个坑）。
 */
class ThemeSchemeBuilderTest {

    private val purpleAccent = 0xFFA855F7.toInt()
    private val warmBase = 0xFF2A1B12.toInt()
    private val softText = 0xFFBFC8D4.toInt()

    private fun customScheme(mode: ThemeMode): ColorScheme {
        val base = if (mode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
        return buildCustomColorScheme(
            base,
            ThemeCustomization(accent = purpleAccent, base = warmBase, text = softText),
            mode
        )
    }

    @Test
    fun `锚点必须与生产色板的对应槽位一致`() {
        // 恒等性是整套调色盘的地基（选原厂色 = 什么都不改），
        // 而恒等性成立的前提是"锚点 == 该模式下对应色槽的原值"。
        // 将来谁改了色板却忘了改锚点，这条会立刻红。
        assertTrue(
            "深色锚点与色板 primary 不一致：${ColorMath.toHexString(ThemePaletteTransform.DARK_ACCENT_ANCHOR)} != ${ColorMath.toHexString(DarkColorScheme.primary.toArgb())}",
            ThemePaletteTransform.DARK_ACCENT_ANCHOR == DarkColorScheme.primary.toArgb()
        )
        assertTrue(
            "浅色锚点与色板 primary 不一致",
            ThemePaletteTransform.LIGHT_ACCENT_ANCHOR == LightColorScheme.primary.toArgb()
        )
        assertTrue(
            "底色锚点与色板 background 不一致",
            ThemePaletteTransform.DARK_BASE_ANCHOR == DarkColorScheme.background.toArgb()
        )
        assertTrue(
            "浅色底色锚点与色板 background 不一致",
            ThemePaletteTransform.LIGHT_BASE_ANCHOR == LightColorScheme.background.toArgb()
        )
        assertTrue(
            "文字锚点与色板 onSurface 不一致",
            ThemePaletteTransform.DARK_TEXT_ANCHOR == DarkColorScheme.onSurface.toArgb()
        )
        assertTrue(
            "浅色文字锚点与色板 onSurface 不一致",
            ThemePaletteTransform.LIGHT_TEXT_ANCHOR == LightColorScheme.onSurface.toArgb()
        )
    }

    @Test
    fun `深色模式_强调色整组跟着用户走`() {
        val scheme = customScheme(ThemeMode.DARK)
        val hue = ColorMath.toHsl(scheme.primary.toArgb()).h
        assertTrue("主色色相应接近用户选的紫（实际 $hue）", kotlin.math.abs(hue - 271f) < 12f)

        val containerHue = ColorMath.toHsl(scheme.primaryContainer.toArgb()).h
        assertTrue("选中态容器也要跟着换调（实际 $containerHue）", kotlin.math.abs(containerHue - 271f) < 30f)
    }

    @Test
    fun `深色模式_卡片必须比背景亮`() {
        // M3 的 Card 默认取 surfaceContainerHighest，它不跟着走的话
        // 用户换完底色，卡片会保持原厂蓝灰，界面立刻花掉
        val scheme = customScheme(ThemeMode.DARK)
        val background = ColorMath.toHsl(scheme.background.toArgb()).l
        val card = ColorMath.toHsl(scheme.surfaceContainerHighest.toArgb()).l
        assertTrue("卡片($card) 必须比背景($background) 亮", card > background)
    }

    @Test
    fun `深色模式_正文与背景对比度达标`() {
        val scheme = customScheme(ThemeMode.DARK)
        val ratio = ColorMath.contrastRatio(scheme.onSurface.toArgb(), scheme.background.toArgb())
        assertTrue("正文对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun `深色模式_次要文字仍可读`() {
        val scheme = customScheme(ThemeMode.DARK)
        val ratio = ColorMath.contrastRatio(scheme.onSurfaceVariant.toArgb(), scheme.background.toArgb())
        assertTrue("次要文字对比度 $ratio 必须 >= 3.0", ratio >= 3.0f)
    }

    @Test
    fun `深色模式_按钮文字在其底色上可读`() {
        val scheme = customScheme(ThemeMode.DARK)
        val ratio = ColorMath.contrastRatio(scheme.onPrimary.toArgb(), scheme.primary.toArgb())
        assertTrue("按钮文字对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun `深色模式_选中态文字在其底色上可读`() {
        val scheme = customScheme(ThemeMode.DARK)
        val ratio = ColorMath.contrastRatio(scheme.onSecondaryContainer.toArgb(), scheme.secondaryContainer.toArgb())
        assertTrue("选中态文字对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun `浅色模式_卡片仍比背景亮且正文可读`() {
        val scheme = customScheme(ThemeMode.LIGHT)
        val background = ColorMath.toHsl(scheme.background.toArgb()).l
        val card = ColorMath.toHsl(scheme.surfaceContainerHighest.toArgb()).l
        assertTrue("浅色模式卡片($card) 必须比背景($background) 亮", card > background)

        val ratio = ColorMath.contrastRatio(scheme.onSurface.toArgb(), scheme.background.toArgb())
        assertTrue("浅色模式正文对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun `浅色模式_强调色在白底上压得住`() {
        val scheme = customScheme(ThemeMode.LIGHT)
        val ratio = ColorMath.contrastRatio(scheme.primary.toArgb(), scheme.background.toArgb())
        assertTrue("浅色模式强调色对比度 $ratio 必须 >= 4.5（否则按钮文字/链接看不清）", ratio >= 4.5f)
    }

    @Test
    fun `只改强调色时底色与文字保持原厂`() {
        // 最常见的操作路径：用户只挑一个强调色就保存。
        // 首版把"没动过的项"当成了"用户选了原厂色值"写进存储，
        // 而那个原厂色值是**深色模式**的，浅色模式读到的意思变成"我要近乎全黑的底"，
        // 整个浅色主题被压成中灰。三项独立之后这条路才安全。
        val base = LightColorScheme
        val scheme = buildCustomColorScheme(
            base,
            ThemeCustomization(accent = purpleAccent), // base / text 都没动
            ThemeMode.LIGHT
        )

        assertTrue("底色不该被动", scheme.background.toArgb() == base.background.toArgb())
        assertTrue(
            "卡片色不该被动（M3 的 Card 取这一级）",
            scheme.surfaceContainerHighest.toArgb() == base.surfaceContainerHighest.toArgb()
        )
        assertTrue("正文色不该被动", scheme.onSurface.toArgb() == base.onSurface.toArgb())
        assertTrue("次要文字不该被动", scheme.onSurfaceVariant.toArgb() == base.onSurfaceVariant.toArgb())
        assertTrue("强调色必须真的生效", scheme.primary.toArgb() != base.primary.toArgb())
    }

    @Test
    fun `浅色模式选中态仍是浅底深字`() {
        // 容器类槽位若用深色口径的明度区间，浅色下会反转成深底浅字：
        // 底部导航选中项、选中芯片会变成白卡上一个深色方块，与浅色主题的层次语言相反
        val scheme = customScheme(ThemeMode.LIGHT)
        val container = scheme.secondaryContainer.toArgb()
        val onContainer = scheme.onSecondaryContainer.toArgb()

        assertTrue(
            "选中底色必须比其上的文字亮（容器 ${ColorMath.toHsl(container).l} / 文字 ${ColorMath.toHsl(onContainer).l}）",
            ColorMath.toHsl(container).l > ColorMath.toHsl(onContainer).l
        )
        val ratio = ColorMath.contrastRatio(onContainer, container)
        assertTrue("选中态文字对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun `深色模式选浅色系强调色时选中态文字仍可读`() {
        // 深色模式 + 黄~青一带的浅色（取色面板上半部）时，容器即使在 HSL 明度 0.35 处
        // 相对亮度仍有 0.33，容器文字被顶到 0.96 也只有 2.7:1。
        // 容器文字的对比度必须对着**容器自己**校验 —— 对着页面底色校验会判它合格。
        val base = DarkColorScheme
        val scheme = buildCustomColorScheme(
            base,
            ThemeCustomization(accent = 0xFFFFFEE6.toInt()),
            ThemeMode.DARK
        )

        val primaryRatio = ColorMath.contrastRatio(
            scheme.onPrimaryContainer.toArgb(), scheme.primaryContainer.toArgb()
        )
        assertTrue("主选中态文字对容器 $primaryRatio 必须 >= 4.5", primaryRatio >= 4.5f)

        val secondaryRatio = ColorMath.contrastRatio(
            scheme.onSecondaryContainer.toArgb(), scheme.secondaryContainer.toArgb()
        )
        assertTrue("次选中态文字对容器 $secondaryRatio 必须 >= 4.5", secondaryRatio >= 4.5f)
    }

    @Test
    fun `文字在实际落点上也达标_不只是页面背景`() {
        // 正文与次要文字大量画在卡片、顶栏、输入槽这些与页面背景不同的表面上。
        // 兜底若只对着 background 校验，就会出现"校验通过、画上去却不达标"。
        // 这里用一个浅色底（深色模式下会被推到明度上限）把差距放大到可判定的程度。
        val base = DarkColorScheme
        val scheme = buildCustomColorScheme(
            base,
            ThemeCustomization(base = 0xFFFFCC.toInt()),
            ThemeMode.DARK
        )

        listOf(
            "顶栏" to scheme.surfaceContainerHigh.toArgb(),
            "卡片" to scheme.surfaceContainerHighest.toArgb(),
            "输入槽" to scheme.surfaceVariant.toArgb()
        ).forEach { (name, surfaceArgb) ->
            val ratio = ColorMath.contrastRatio(scheme.onSurface.toArgb(), surfaceArgb)
            assertTrue("正文在「$name」上的对比度 $ratio 必须 >= 4.5", ratio >= 4.5f)
        }

        val variantRatio = ColorMath.contrastRatio(
            scheme.onSurfaceVariant.toArgb(), scheme.surfaceContainerHigh.toArgb()
        )
        assertTrue("次要文字在顶栏上的对比度 $variantRatio 必须 >= 3.0", variantRatio >= 3.0f)
    }

    @Test
    fun `状态色兜底真的接上了线`() {
        // 状态色不跟用户色相走，但用户把底色调亮/调暗之后，
        // 原本为原厂底色配的琥珀/绿/红会掉到看不清 —— 接线必须真的把它们推回可读区间。
        // （只测 statusColor 函数本身是不够的：接线被摘掉时函数测试照样绿。）
        val base = DarkColorScheme
        val scheme = buildCustomColorScheme(
            base,
            ThemeCustomization(base = 0xFFFFCC.toInt()),
            ThemeMode.DARK
        )
        val panel = scheme.surfaceContainerHigh.toArgb()

        listOf(
            "警告" to scheme.secondary.toArgb(),
            "成功" to scheme.tertiary.toArgb(),
            "错误" to scheme.error.toArgb()
        ).forEach { (name, color) ->
            val ratio = ColorMath.contrastRatio(color, panel)
            assertTrue("$name 色在顶栏上的对比度 $ratio 必须 >= 3.0", ratio >= 3.0f)
        }
    }

    @Test
    fun `状态色不受调色盘影响`() {
        // 警告/成功/错误不在开放范围内，必须原样保留，
        // 否则用户调完色会分不清"警告"和"选中"
        val base = DarkColorScheme
        val scheme = customScheme(ThemeMode.DARK)
        assertTrue("警告色不该变", scheme.secondary.toArgb() == base.secondary.toArgb())
        assertTrue("成功色不该变", scheme.tertiary.toArgb() == base.tertiary.toArgb())
        assertTrue("错误色不该变", scheme.error.toArgb() == base.error.toArgb())
    }
}
