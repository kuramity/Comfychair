package sh.hnet.comfychair.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 色彩数学：HSL 转换、WCAG 对比度、可读性兜底。
 *
 * 刻意不依赖 Compose 的 `Color`，全部用 ARGB Int 进出，原因有两个：
 * 1. 纯 JVM 逻辑可以在单元测试里直接跑，不需要起模拟器；
 * 2. 调色盘的规则（尤其是对比度兜底）必须能被钉住 —— 算错了不会崩，
 *    只会「调出一个看不清的界面」，那种缺陷靠肉眼很难在测试里复现。
 */
object ColorMath {

    /** 色相 0..360，饱和度与明度 0..1（与 CSS/HSL 口径一致） */
    data class Hsl(val h: Float, val s: Float, val l: Float)

    /**
     * HSV/HSB：取色器用的口径。
     *
     * 取色面板为什么不用 HSL：HSL 在明度两端会"吃掉"饱和度
     * （L=1 时无论 S 多少都是纯白、L=0 时都是纯黑），
     * 画出来的方块两端会出现整条死区，拖起来莫名其妙。
     * HSV 则是右上角永远是该色相的纯色、最下一整行是黑，符合直觉。
     */
    data class Hsv(val h: Float, val s: Float, val v: Float)

    /** 把色相规整到 [0, 360)，负数与超过一圈的输入都能正确落位 */
    fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

    fun toHsl(argb: Int): Hsl {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val lightness = (maxChannel + minChannel) / 2f
        val delta = maxChannel - minChannel

        // 灰色（含纯黑纯白）没有色相可言，直接归零，避免后面除零算出 NaN
        if (delta < 1e-6f) return Hsl(0f, 0f, lightness)

        val saturation = if (lightness > 0.5f) {
            delta / (2f - maxChannel - minChannel)
        } else {
            delta / (maxChannel + minChannel)
        }

        val hue = when (maxChannel) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }

        return Hsl(normalizeHue(hue), saturation.coerceIn(0f, 1f), lightness)
    }

    fun fromHsl(hsl: Hsl): Int {
        val hue = normalizeHue(hsl.h)
        val saturation = hsl.s.coerceIn(0f, 1f)
        val lightness = hsl.l.coerceIn(0f, 1f)

        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val hueSector = hue / 60f
        val second = chroma * (1f - abs(hueSector % 2f - 1f))

        val (r1, g1, b1) = when {
            hueSector < 1f -> Triple(chroma, second, 0f)
            hueSector < 2f -> Triple(second, chroma, 0f)
            hueSector < 3f -> Triple(0f, chroma, second)
            hueSector < 4f -> Triple(0f, second, chroma)
            hueSector < 5f -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }

        val match = lightness - chroma / 2f
        val r = ((r1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + match) * 255f).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun toHsv(argb: Int): Hsv {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val delta = maxChannel - minChannel

        val saturation = if (maxChannel < 1e-6f) 0f else delta / maxChannel
        val hue = if (delta < 1e-6f) {
            0f
        } else {
            normalizeHue(
                when (maxChannel) {
                    r -> 60f * (((g - b) / delta) % 6f)
                    g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }
            )
        }

        return Hsv(hue, saturation.coerceIn(0f, 1f), maxChannel.coerceIn(0f, 1f))
    }

    fun fromHsv(hsv: Hsv): Int {
        val hue = normalizeHue(hsv.h)
        val saturation = hsv.s.coerceIn(0f, 1f)
        val value = hsv.v.coerceIn(0f, 1f)

        val chroma = value * saturation
        val hueSector = hue / 60f
        val second = chroma * (1f - abs(hueSector % 2f - 1f))

        val (r1, g1, b1) = when {
            hueSector < 1f -> Triple(chroma, second, 0f)
            hueSector < 2f -> Triple(second, chroma, 0f)
            hueSector < 3f -> Triple(0f, chroma, second)
            hueSector < 4f -> Triple(0f, second, chroma)
            hueSector < 5f -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }

        val match = value - chroma
        val r = ((r1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + match) * 255f).roundToInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 该色相的纯色（S=1、V=1），取色面板右上角的锚点色 */
    fun pureHue(hue: Float): Int = fromHsv(Hsv(hue, 1f, 1f))

    /** WCAG 相对亮度（不是 HSL 的明度：人眼对绿最敏感，对蓝最不敏感） */
    fun relativeLuminance(argb: Int): Float {
        fun linearize(channel: Int): Float {
            val c = channel / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        val r = linearize((argb shr 16) and 0xFF)
        val g = linearize((argb shr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG 对比度，取值 1（同色）~ 21（黑白） */
    fun contrastRatio(a: Int, b: Int): Float {
        val luminanceA = relativeLuminance(a)
        val luminanceB = relativeLuminance(b)
        val lighter = max(luminanceA, luminanceB)
        val darker = min(luminanceA, luminanceB)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * 保证 [color] 在 [background] 上至少达到 [minRatio] 的对比度，不够就推明度。
     *
     * 背景偏暗就往亮里推、偏亮就往暗里推；万一这个方向推到头也不达标
     * （背景恰好卡在中灰附近时会出现），再换反方向推一遍。
     * 已经达标时原样返回 —— 不能"顺手"把用户选的颜色改掉。
     */
    fun ensureContrast(color: Int, background: Int, minRatio: Float): Int {
        if (minRatio <= 0f) return color
        if (contrastRatio(color, background) >= minRatio) return color

        val hsl = toHsl(color)
        val towardLight = relativeLuminance(background) < 0.5f
        return searchContrast(hsl, background, minRatio, towardLight)
            ?: searchContrast(hsl, background, minRatio, !towardLight)
            ?: color
    }

    private fun searchContrast(hsl: Hsl, background: Int, minRatio: Float, towardLight: Boolean): Int? {
        val steps = 64
        for (step in 1..steps) {
            val progress = step / steps.toFloat()
            val lightness = if (towardLight) {
                hsl.l + (1f - hsl.l) * progress
            } else {
                hsl.l * (1f - progress)
            }
            val candidate = fromHsl(Hsl(hsl.h, hsl.s, lightness.coerceIn(0f, 1f)))
            if (contrastRatio(candidate, background) >= minRatio) return candidate
        }
        return null
    }

    fun withHue(argb: Int, hue: Float): Int {
        val hsl = toHsl(argb)
        return fromHsl(hsl.copy(h = normalizeHue(hue)))
    }

    /**
     * HSV 口径的色相偏移：h + delta 后归一化，S/V 原样保留。
     * 用 HSV 而不是 HSL 的原因：按钮渐变的辅色派生自"强调色色相 +35°"（决策 1），
     * 要求的是纯色相旋转 —— HSV 的 S/V 与取色器口径一致，往返量化误差可被单测钉住。
     */
    fun shiftHueHsv(argb: Int, delta: Float): Int {
        val hsv = toHsv(argb)
        return fromHsv(hsv.copy(h = normalizeHue(hsv.h + delta)))
    }

    fun shiftLightness(argb: Int, delta: Float, minL: Float = 0f, maxL: Float = 1f): Int {
        val hsl = toHsl(argb)
        return fromHsl(hsl.copy(l = (hsl.l + delta).coerceIn(minL, maxL)))
    }

    fun scaleSaturation(argb: Int, factor: Float, maxS: Float = 1f): Int {
        val hsl = toHsl(argb)
        return fromHsl(hsl.copy(s = (hsl.s * factor).coerceIn(0f, maxS)))
    }

    /** 调试与 UI 展示用：ARGB → `#RRGGBB` */
    fun toHexString(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
