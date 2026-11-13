package sh.hnet.comfychair.ui.theme

import kotlin.math.abs

/**
 * 深浅两套模式。
 *
 * 用户只调一套颜色，引擎按模式各自适配：同一个「紫」，深色模式取它的亮版本、
 * 浅色模式取暗版本，这样两套主题都不需要用户再调第二遍。
 */
enum class ThemeMode(val dark: Boolean) {
    DARK(true),
    LIGHT(false)
}

/**
 * 把「用户在调色盘里选的颜色」落到每一个 M3 色槽上。
 *
 * 三条铁律（对应 ThemePaletteTransformTest 里的用例）：
 *
 * 1. **恒等**：选原厂色 = 不改变任何东西。用户想反悔时，把取色器拨回原色即可复原。
 *    注意"恒等"是按**模式**成立的：用户选原厂色时，深浅两套色板都必须原样不动，
 *    所以下面每一个明度区间都要把该模式的原值包在里面。
 * 2. **保结构**：明度只做受限平移，绝不重排。本项目零阴影，界面层次全靠底色深浅差表达，
 *    一旦卡片不比背景亮，整个界面就会糊成一片 —— 所以明度自由度刻意收窄
 *    （见 [DARK_SURFACE_DELTA]/[LIGHT_SURFACE_DELTA]），用户主要靠色相和饱和度获得变化。
 * 3. **可读兜底**：用户可能选出「深灰字配深灰底」，引擎负责把结果推回可读区间，
 *    而不是任由界面变成看不清的样子。
 *
 * 色相的处理分两种，别混用：
 * - 强调色与文字色用**差值**（相对原厂锚点），保留原色板里各色槽之间的微妙色相差异；
 * - 底色系用**替换**，因为用户选的就是"我要这个色调的界面"，整片跟着走才对。
 */
object ThemePaletteTransform {

    // ---------- 原厂锚点：用户选的颜色与它们比对求差 ----------
    // 深色模式的那三个同时充当取色器的默认位置（见 ThemeCustomization.DEFAULT）
    val DARK_ACCENT_ANCHOR = 0xFF38BDF8.toInt()
    val LIGHT_ACCENT_ANCHOR = 0xFF0369A1.toInt()
    val DARK_BASE_ANCHOR = 0xFF0F1419.toInt()
    val LIGHT_BASE_ANCHOR = 0xFFEDF1F7.toInt()
    val DARK_TEXT_ANCHOR = 0xFFE6EDF3.toInt()
    val LIGHT_TEXT_ANCHOR = 0xFF16202B.toInt()

    // ---------- 明度安全区间 ----------
    // 必须按模式分开定。浅色模式的选中底色是浅蓝（#CDE9FB，明度 0.894）、
    // 其上的文字是深字（#0B3A52，明度 0.182），与深色模式正好相反；
    // 拿一套深色口径的区间去管浅色槽位，会把浅底压成深底、深字提成浅字 ——
    // 界面不崩，但"选中"的语义整个反过来，用户会以为坏了。

    /** 主色（生成按钮、选中边框、链接）：浅色模式下必须压暗才在白底上读得出 */
    fun accentMainLightness(mode: ThemeMode): ClosedFloatingPointRange<Float> =
        if (mode.dark) 0.28f..0.86f else 0.25f..0.62f

    /** 强调色容器（选中态底色）：深色模式是暗底，浅色模式是浅底 */
    fun accentContainerLightness(mode: ThemeMode): ClosedFloatingPointRange<Float> =
        if (mode.dark) 0.06f..0.35f else 0.72f..0.95f

    /** 容器之上的文字：与容器相反，深色模式亮字、浅色模式深字 */
    fun accentOnContainerLightness(mode: ThemeMode): ClosedFloatingPointRange<Float> =
        if (mode.dark) 0.66f..0.96f else 0.08f..0.35f

    /** 正文与次要文字：浅色模式下是深字 */
    private fun textLightness(mode: ThemeMode): ClosedFloatingPointRange<Float> =
        if (mode.dark) 0.30f..0.98f else 0.02f..0.45f

    /**
     * 底色明度的平移幅度。
     *
     * 上限由「阶梯里最亮的那个槽不能越过安全线」反推：
     * 深色模式的描边已经到 0.276，再亮就分不出层次了，所以只给 +0.14；
     * 浅色模式的卡片本身是纯白（明度 1.0），往上一点空间都没有，所以上限锁死为 0。
     *
     * 下限刻意收得很窄，原因在浅色模式：底色一旦明显压暗，同一份 HSL 饱和度的观感会
     * 成倍变强 —— 深色模式下选的高饱和暖棕（低明度下只是"暗棕"），压到浅色就成了土黄。
     * 明度不给太多自由度，用户改底色色调的意图仍然完全满足，界面却不会跑偏。
     */
    private val DARK_SURFACE_DELTA = -0.07f..0.14f
    private val LIGHT_SURFACE_DELTA = -0.08f..0.0f

    /** 底色饱和度上限：避免大面积高饱和底色糊住视线 */
    private val DARK_SURFACE_MAX_SATURATION = 0.55f
    private val LIGHT_SURFACE_MAX_SATURATION = 0.42f

    /** 色相差到多少度算"用户明确改了色调"，用于决定纯灰槽位是否跟着染色 */
    private const val HUE_TINT_FULL_ANGLE = 30f

    /** 饱和度做除法时的保护量：让「纯灰输入」也能平滑地推向低饱和，而不是除零 */
    private const val SATURATION_EPSILON = 0.05f

    /**
     * 饱和度低于此值就当作"无彩色"。
     *
     * 纯灰（含黑与白）的色相是**反推不出来**的 —— HSL/HSV 一律归零，
     * 照用的话"用户选了中性灰"会被当成"用户要色相 0 的红调"，
     * 整片底色会莫名其妙偏红/偏粉。所以无彩色输入按锚点色相处理。
     */
    private const val ACHROMATIC_SATURATION = 0.02f

    private fun saturationRatio(customS: Float, anchorS: Float): Float =
        (customS + SATURATION_EPSILON) / (anchorS + SATURATION_EPSILON)

    /** 用户的色相；无彩色输入回落到锚点色相，见 [ACHROMATIC_SATURATION] */
    private fun effectiveHue(customHsl: ColorMath.Hsl, anchorHsl: ColorMath.Hsl): Float =
        if (customHsl.s < ACHROMATIC_SATURATION) anchorHsl.h else customHsl.h

    /**
     * 锚点类别：强调色 / 底色系 / 文字色。
     *
     * 除了给变换当比对基准，[anchorColor] 还有第二个用途：**某项用户没自定义时，
     * 拿该模式的锚点色当"零差值"的输入**。这样该组色槽的色相/饱和度/明度全都不动，
     * 但对比度兜底仍然会跑 —— 用户只改了底色、把底调亮之后，原厂文字在新底色上
     * 可能已经看不清，这一步必须校验，否则"可读兜底"就只管了用户改过的那些项。
     */
    enum class AnchorKind { ACCENT, BASE, TEXT }

    /** 该模式下的原厂锚点色 */
    fun anchorColor(kind: AnchorKind, mode: ThemeMode): Int = when (kind) {
        AnchorKind.ACCENT -> if (mode.dark) DARK_ACCENT_ANCHOR else LIGHT_ACCENT_ANCHOR
        AnchorKind.BASE -> if (mode.dark) DARK_BASE_ANCHOR else LIGHT_BASE_ANCHOR
        AnchorKind.TEXT -> if (mode.dark) DARK_TEXT_ANCHOR else LIGHT_TEXT_ANCHOR
    }

    /**
     * 强调系色槽。
     *
     * @param background 用户调整后的实际底色（兜底对比度要拿它当基准，
     *                   而不是硬编码原厂背景 —— 用户可能同时改了底色）
     * @param minRatio   对比度门槛；不需要约束的槽位传 0
     * @param lightnessRange 该槽位允许的明度区间；留空则按 [accentMainLightness]。
     *                   容器与容器文字必须显式传各自的区间（见本对象顶部的说明）
     */
    fun accent(
        originalSlot: Int,
        custom: Int,
        mode: ThemeMode,
        background: Int,
        minRatio: Float = 0f,
        lightnessRange: ClosedFloatingPointRange<Float>? = null
    ): Int {
        val range = lightnessRange ?: accentMainLightness(mode)
        val anchor = anchorColor(AnchorKind.ACCENT, mode)
        val original = ColorMath.toHsl(originalSlot)
        val anchorHsl = ColorMath.toHsl(anchor)
        val customHsl = ColorMath.toHsl(custom)

        val hue = original.h + (effectiveHue(customHsl, anchorHsl) - anchorHsl.h)
        val saturation = (original.s * saturationRatio(customHsl.s, anchorHsl.s)).coerceIn(0f, 1f)
        val lightness = (original.l + (customHsl.l - anchorHsl.l))
            .coerceIn(range.start, range.endInclusive)

        val shifted = ColorMath.fromHsl(ColorMath.Hsl(hue, saturation, lightness))
        return if (minRatio > 0f) ColorMath.ensureContrast(shifted, background, minRatio) else shifted
    }

    /**
     * 底色系色槽：背景、卡片、面板、描边。
     *
     * 色相整体替换成用户选的色调，饱和度按比例跟随（封顶），明度只做受限平移。
     * 平移量对所有槽位一致，所以槽与槽之间的深浅差（也就是界面的层次）原样保留。
     */
    fun surface(originalSlot: Int, custom: Int, mode: ThemeMode): Int {
        val anchor = anchorColor(AnchorKind.BASE, mode)
        val original = ColorMath.toHsl(originalSlot)
        val anchorHsl = ColorMath.toHsl(anchor)
        val customHsl = ColorMath.toHsl(custom)

        val deltaRange = if (mode.dark) DARK_SURFACE_DELTA else LIGHT_SURFACE_DELTA
        val maxSaturation = if (mode.dark) DARK_SURFACE_MAX_SATURATION else LIGHT_SURFACE_MAX_SATURATION

        val delta = (customHsl.l - anchorHsl.l).coerceIn(deltaRange.start, deltaRange.endInclusive)
        val lightness = (original.l + delta).coerceIn(0.0f, 1.0f)

        // 纯灰槽位（浅色模式的卡片/最亮表面是纯白，饱和度恰好为 0）按比例缩放永远还是 0，
        // 整片跟着走的底色会在这里断掉：顶栏已经暖了，主内容区还是灰的。
        // 所以这类槽位改成"跟着背景的色度走"，让整片界面统一在同一色调里 ——
        // 层次交给明度差去表达（卡片仍然比背景亮），色调则整片一致。
        //
        // 只在用户确实改了色调时才染色：色相差为 0 说明用户选的就是原厂色，
        // 此时不施加任何染色，恒等性与"恢复原始配色零偏差"得以保留。
        val hueDelta = effectiveHue(customHsl, anchorHsl) - anchorHsl.h
        val tintStrength = (abs(hueDelta) / HUE_TINT_FULL_ANGLE).coerceIn(0f, 1f)
        val tintTarget = (anchorHsl.s * saturationRatio(customHsl.s, anchorHsl.s)).coerceAtMost(maxSaturation)

        val saturation = (original.s * saturationRatio(customHsl.s, anchorHsl.s))
            .coerceAtLeast(tintStrength * tintTarget)
            .coerceAtMost(maxSaturation)

        return ColorMath.fromHsl(ColorMath.Hsl(effectiveHue(customHsl, anchorHsl), saturation, lightness))
    }

    /**
     * 文字色槽：正文与次要文字。
     *
     * 饱和度增量刻意打对折 —— 文字一旦染得太浓，在深色底上会"发光"，
     * 长时间看不舒服。明度调完还必须过对比度这一关。
     */
    fun text(
        originalSlot: Int,
        custom: Int,
        mode: ThemeMode,
        background: Int,
        minRatio: Float
    ): Int {
        val anchor = anchorColor(AnchorKind.TEXT, mode)
        val range = textLightness(mode)
        val original = ColorMath.toHsl(originalSlot)
        val anchorHsl = ColorMath.toHsl(anchor)
        val customHsl = ColorMath.toHsl(custom)

        val hue = original.h + (effectiveHue(customHsl, anchorHsl) - anchorHsl.h)
        val saturationDelta = (customHsl.s - anchorHsl.s) * 0.5f
        val saturation = (original.s + saturationDelta).coerceIn(0f, 0.6f)
        val lightness = (original.l + (customHsl.l - anchorHsl.l))
            .coerceIn(range.start, range.endInclusive)

        val shifted = ColorMath.fromHsl(ColorMath.Hsl(hue, saturation, lightness))
        return ColorMath.ensureContrast(shifted, background, minRatio)
    }

    /**
     * 状态色（警告 / 成功 / 错误）：色相不跟用户走，但明度要够。
     *
     * 这三个在本项目里是当前景色用的（"连接中"文字、成功图标等），
     * 用户把底色调暗之后，原本为原厂底色配的琥珀/绿/红就可能掉到看不清。
     * 所以只做一件事：在需要时推明度，保证仍然读得出来。
     */
    fun statusColor(originalSlot: Int, background: Int, minRatio: Float): Int =
        ColorMath.ensureContrast(originalSlot, background, minRatio)

    /**
     * 在给定底色上挑一个读得清的文字色（深底配浅字、浅底配深字）。
     *
     * 用在「按钮上的文字」这类配对色上：底色是引擎算出来的，
     * 配套的文字色就不该再让用户去猜，直接取对比度更高的那端。
     */
    fun readableOn(background: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        return if (ColorMath.contrastRatio(white, background) >= ColorMath.contrastRatio(black, background)) {
            white
        } else {
            black
        }
    }

    /** 把只做色相跟随的槽位（固定色、反色）算出来：明度饱和度原样保留 */
    fun followHue(originalSlot: Int, custom: Int, mode: ThemeMode, kind: AnchorKind): Int {
        val anchor = anchorColor(kind, mode)
        val original = ColorMath.toHsl(originalSlot)
        val hueDelta = ColorMath.toHsl(custom).h - ColorMath.toHsl(anchor).h
        return ColorMath.fromHsl(original.copy(h = original.h + hueDelta))
    }
}
