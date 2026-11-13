package sh.hnet.comfychair.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 把用户自定义的基准色展开成一整套 M3 色板。
 *
 * 这里是「规则」与「色槽」的接合点：变换数学在 [ThemePaletteTransform]（纯函数、有单测），
 * 本文件只负责决定**哪个色槽归哪一组、需要多高的对比度门槛、兜底拿谁当基准**。
 *
 * 几个约定，改之前先看清楚：
 * - **三项各自独立**：某一项为 null 表示用户没动过，对应那组色槽原样保留原厂值。
 *   绝不能把"没动过"当成"用户选了原厂色"去跑一遍变换（会与所选模式的原值错配）。
 * - 深色模式的 `surfaceContainerHighest` 就是卡片色（M3 的 Card 默认取这一级），
 *   所以它必须跟着底色走，否则用户换了底色、卡片还是原厂蓝灰，界面会花。
 * - 选中态（secondaryContainer / onSecondaryContainer）在本项目里一律走强调色，
 *   这是既有约定，不是 M3 教科书里的写法，别"顺手改正"。
 * - primaryFixed 那一组是"深浅模式恒定"的语义，只换色相、不动明度，
 *   动了就等于把它们变成普通色槽，跨模式的稳定性就没了。
 * - 状态色（警告琥珀 / 成功薄荷 / 错误红）不跟用户色相走，但要保证在最终底色上仍读得出。
 */
fun buildCustomColorScheme(
    base: ColorScheme,
    custom: ThemeCustomization,
    mode: ThemeMode
): ColorScheme {

    // ---------- 底色系 ----------
    fun surfaceSlot(slot: Color): Int = custom.base?.let {
        ThemePaletteTransform.surface(slot.toArgb(), it, mode)
    } ?: slot.toArgb()

    val background = surfaceSlot(base.background)
    val surfaceColor = surfaceSlot(base.surface)
    val surfaceVariant = surfaceSlot(base.surfaceVariant)
    val surfaceDim = surfaceSlot(base.surfaceDim)
    val surfaceBright = surfaceSlot(base.surfaceBright)
    val surfaceContainerLowest = surfaceSlot(base.surfaceContainerLowest)
    val surfaceContainerLow = surfaceSlot(base.surfaceContainerLow)
    val surfaceContainer = surfaceSlot(base.surfaceContainer)
    val surfaceContainerHigh = surfaceSlot(base.surfaceContainerHigh)
    val surfaceContainerHighest = surfaceSlot(base.surfaceContainerHighest)
    val outline = surfaceSlot(base.outline)
    val outlineVariant = surfaceSlot(base.outlineVariant)

    // 对比度基准：文字与强调色实际大量落在卡片、顶栏、输入槽这些与页面背景不同的容器上，
    // 只对着 background 校验会出现"校验过了、画到卡片上却不达标"（实测差 0.4~1.0）。
    // 取实际会用到的、对该类前景最不利的那一级：
    // 深色模式的前景是亮色 → 最亮的那级容器最不利；浅色模式反之。
    val worstSurface = if (mode.dark) surfaceContainerHigh else surfaceVariant

    // 用户没自定义该项时，拿该模式的**原厂锚点**当输入：色相/饱和度/明度的差值全为 0，
    // 所以颜色本身一点不变，但对比度兜底照样会跑。
    // 这不是多此一举 —— 用户只改了底色、把底调亮之后，原厂文字/强调色在新底色上
    // 可能已经看不清了，不能因为"这一项用户没动"就不校验。
    fun accentSlot(
        slot: Color,
        minRatio: Float,
        range: ClosedFloatingPointRange<Float>? = null
    ): Int = ThemePaletteTransform.accent(
        slot.toArgb(),
        custom.accent ?: ThemePaletteTransform.anchorColor(ThemePaletteTransform.AnchorKind.ACCENT, mode),
        mode,
        worstSurface,
        minRatio,
        range
    )

    fun textSlot(slot: Color, minRatio: Float): Int = ThemePaletteTransform.text(
        slot.toArgb(),
        custom.text ?: ThemePaletteTransform.anchorColor(ThemePaletteTransform.AnchorKind.TEXT, mode),
        mode,
        worstSurface,
        minRatio
    )

    // ---------- 强调色系 ----------
    // 容器与"容器之上的文字"是**一对**，对比度必须对着彼此校验，不能对着页面底色：
    // 深色模式下用户选了浅色系强调色（黄~青一带）时，容器即使在 HSL 明度 0.35 处
    // 相对亮度仍有 0.33，配上被顶到 0.96 的容器文字只有 2.7:1（实测值），
    // 而拿页面底色当基准会判它"合格"，缺陷就漏过去了。
    // 顺序：先取文字的意图色 → 让容器对着它调明度（容器区间宽、动得起）→ 文字再补一次。
    val primary = accentSlot(base.primary, minRatio = 4.5f)
    val onPrimaryContainerIntent = accentSlot(
        base.onPrimaryContainer, minRatio = 0f,
        range = ThemePaletteTransform.accentOnContainerLightness(mode)
    )
    val primaryContainer = ColorMath.ensureContrast(
        accentSlot(
            base.primaryContainer, minRatio = 0f,
            range = ThemePaletteTransform.accentContainerLightness(mode)
        ),
        onPrimaryContainerIntent,
        CONTAINER_TEXT_MIN_RATIO
    )
    val onPrimaryContainer = ColorMath.ensureContrast(
        onPrimaryContainerIntent, primaryContainer, CONTAINER_TEXT_MIN_RATIO
    )
    val inversePrimary = accentSlot(base.inversePrimary, minRatio = 0f)

    // 选中态容器（底部导航选中项、选中卡片/芯片）沿用强调色，同样成对处理
    val onSecondaryContainerIntent = accentSlot(
        base.onSecondaryContainer, minRatio = 0f,
        range = ThemePaletteTransform.accentOnContainerLightness(mode)
    )
    val secondaryContainer = ColorMath.ensureContrast(
        accentSlot(
            base.secondaryContainer, minRatio = 0f,
            range = ThemePaletteTransform.accentContainerLightness(mode)
        ),
        onSecondaryContainerIntent,
        CONTAINER_TEXT_MIN_RATIO
    )
    val onSecondaryContainer = ColorMath.ensureContrast(
        onSecondaryContainerIntent, secondaryContainer, CONTAINER_TEXT_MIN_RATIO
    )

    // 固定色只跟色相：它们的明度是"跨深浅模式恒定"的语义，参与明度变换会被调反
    val primaryFixed = base.primaryFixed.followHue(custom.accent, mode, ThemePaletteTransform.AnchorKind.ACCENT)
    val primaryFixedDim = base.primaryFixedDim.followHue(custom.accent, mode, ThemePaletteTransform.AnchorKind.ACCENT)
    val onPrimaryFixed = base.onPrimaryFixed.followHue(custom.accent, mode, ThemePaletteTransform.AnchorKind.ACCENT)
    val onPrimaryFixedVariant = base.onPrimaryFixedVariant.followHue(custom.accent, mode, ThemePaletteTransform.AnchorKind.ACCENT)

    // ---------- 状态色 ----------
    // 不跟用户色相走，但用户把底色调暗（浅色模式）之后，原为原厂底色配的琥珀/绿/红
    // 可能掉到看不清，所以给一道"仍读得出"的明度兜底。
    val secondary = ThemePaletteTransform.statusColor(base.secondary.toArgb(), worstSurface, 3.0f)
    val tertiary = ThemePaletteTransform.statusColor(base.tertiary.toArgb(), worstSurface, 3.0f)
    val error = ThemePaletteTransform.statusColor(base.error.toArgb(), worstSurface, 3.0f)

    // ---------- 文字色系 ----------
    // 正文 4.5:1（WCAG AA 正文门槛）；次要文字 3:1（说明性文字可以宽松一档，
    // 但仍然要一眼能读，否则用户调完发现"说明文字消失了"）
    val onSurface = textSlot(base.onSurface, minRatio = 4.5f)
    val onSurfaceVariant = textSlot(base.onSurfaceVariant, minRatio = 3.0f)

    // ---------- 反色（Snackbar 等）----------
    // 只跟色相：这两个色的明度是"与当前模式相反"的语义，参与明度变换会被调反。
    val inverseSurface = base.inverseSurface.followHue(custom.base, mode, ThemePaletteTransform.AnchorKind.BASE)
    val inverseOnSurface = base.inverseOnSurface.followHue(custom.text, mode, ThemePaletteTransform.AnchorKind.TEXT)

    return base.copy(
        // 底色
        background = Color(background),
        surface = Color(surfaceColor),
        surfaceVariant = Color(surfaceVariant),
        surfaceDim = Color(surfaceDim),
        surfaceBright = Color(surfaceBright),
        surfaceContainerLowest = Color(surfaceContainerLowest),
        surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),

        // 强调
        primary = Color(primary),
        // 按钮文字交给引擎挑：底色是算出来的，配套文字色不该让用户去猜
        onPrimary = Color(ThemePaletteTransform.readableOn(primary)),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        // surfaceTint 在有 tonalElevation 的地方生效，跟着强调色走
        surfaceTint = Color(primary),
        primaryFixed = Color(primaryFixed),
        primaryFixedDim = Color(primaryFixedDim),
        onPrimaryFixed = Color(onPrimaryFixed),
        onPrimaryFixedVariant = Color(onPrimaryFixedVariant),

        // 状态色（色相不变，只做可读性兜底）
        secondary = Color(secondary),
        tertiary = Color(tertiary),
        error = Color(error),

        // 文字
        onSurface = Color(onSurface),
        // onBackground 与 onSurface 同值：Scaffold 默认用 onBackground 当全站文字色
        onBackground = Color(onSurface),
        onSurfaceVariant = Color(onSurfaceVariant),

        // 反色
        inverseSurface = Color(inverseSurface),
        inverseOnSurface = Color(inverseOnSurface)
    )
}

/** 容器与其上文字的对比度门槛（WCAG AA 正文） */
private const val CONTAINER_TEXT_MIN_RATIO = 4.5f

// ---------- 针对 Color 的小包装，让上面的映射表读起来是一张表、而不是一堆调用 ----------

/** 只做色相跟随，明度与饱和度原样保留（用于"明度不可动"的色槽）；未自定义时原样返回 */
private fun Color.followHue(
    custom: Int?,
    mode: ThemeMode,
    kind: ThemePaletteTransform.AnchorKind
): Int = custom?.let { ThemePaletteTransform.followHue(toArgb(), it, mode, kind) } ?: toArgb()
