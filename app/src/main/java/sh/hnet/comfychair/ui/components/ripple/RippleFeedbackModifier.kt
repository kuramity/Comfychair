package sh.hnet.comfychair.ui.components.ripple

import android.app.ActivityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import sh.hnet.comfychair.storage.AppSettings

/** 纳秒 → 秒（波纹模型的统一时间单位）。 */
private const val NANOS_PER_SEC = 1_000_000_000.0

/** 按压回弹的缩放默认值（完整皮肤形态）；顶栏裸图标态单独传 0.94f。 */
internal const val DEFAULT_PRESS_SCALE = 0.96f

// ============================================================
// 顶栏按压时序（v4.16.1，规格 A1/A2）：快进 → 定格 → 慢出
// ============================================================

/**
 * 顶栏按压覆盖层的时序规格（v4.16.1）：三段直给动画。
 *
 * 现状问题（R-A1 取证）：pressBg / pressScale 走 animateColorAsState / animateFloatAsState
 * **未传 spec = 默认 spring（带惯性）**，快速点击（<0.1 秒）时动画还没爬到峰值就反向，
 * 视觉上像"白框迟到冒出来"。改为三段：
 * - 快进 [PRESS_FEEDBACK_IN_MS]：按下后 ≤60ms 到满强度；
 * - 定格 [PRESS_FEEDBACK_HOLD_MS]：松手后保持满强度 150ms（这是"定格"，不是淡出的一部分）；
 * - 慢出 [PRESS_FEEDBACK_OUT_MS]：之后 150ms 淡回静置。
 * 全程 tween（无惯性无过冲），禁用 spring。internal 供单测钉住规格。
 */
internal const val PRESS_FEEDBACK_IN_MS = 60
internal const val PRESS_FEEDBACK_HOLD_MS = 150
internal const val PRESS_FEEDBACK_OUT_MS = 150

/**
 * 按压时序的归一化进度（0 = 静置，1 = 满强度）——"快进 → 定格 → 慢出"的唯一驱动源。
 *
 * 时序（规格 A1/A2）：
 * - 按下：[PRESS_FEEDBACK_IN_MS] 内从 0 快进到 1；
 * - 松手：先把可能未走完的快进段补完（快击 <60ms 松手时白框也必须出现——
 *   这是修复"快击迟到"的关键），再满强度定格 [PRESS_FEEDBACK_HOLD_MS]，
 *   然后 [PRESS_FEEDBACK_OUT_MS] 淡回 0（慢出）；
 * - 淡出中途再按（快击连点）：LaunchedEffect 重启，进度从当前值重新快进到 1，直给不打折。
 *
 * 只输出进度、不做插值：调用方对各自的目标值 lerp（pressBg 用 Color lerp、
 * pressScale 用 Float lerp），时序逻辑全 App 只此一份。禁用带惯性的 spring。
 *
 * B-A1b 加固：补完段的时长不再用 System.nanoTime（真实时钟）倒推剩余时间——
 * 真机掉帧/页面繁忙时动画帧钟（Choreographer）会滞后于真实时钟，把"剩余时间"
 * 算成 0 而跳过补完，定格强度不满（白框半亮就定格）。改为直接读进度值本身：
 * 快进没走完（value<1）就按剩余进度比例给时长补到 1（恒 ≤60ms），松手后的
 * 时间线恒定为"补完 ≤60ms + 定格 150ms + 慢出 150ms"，与时钟源无关。
 */
@Composable
internal fun rememberPressTimelineProgress(pressed: Boolean): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            progress.animateTo(1f, tween(PRESS_FEEDBACK_IN_MS))
        } else {
            if (progress.value <= 0f) return@LaunchedEffect  // 首组合（从未按下过），无序列可跑
            // 补完快进段：快击松手时快进可能只走了一部分（value<1），按剩余进度比例
            // 给时长补到满强度（直给契约：白框必须出现并定格，时长恒 ≤60ms）
            if (progress.value < 1f) {
                progress.animateTo(
                    1f,
                    tween(((1f - progress.value) * PRESS_FEEDBACK_IN_MS).roundToInt()),
                )
            }
            // 定格：松手后保持满强度 150ms（这是"定格"，不是淡出的一部分）
            delay(PRESS_FEEDBACK_HOLD_MS.toLong())
            // 慢出：150ms 淡回静置
            progress.animateTo(0f, tween(PRESS_FEEDBACK_OUT_MS))
        }
    }
    return progress
}

/** 皮肤视觉要素开关默认值：默认全开 = 旧版完整皮肤（白边 + 辉光 + 触点辉光）。 */
internal const val DEFAULT_SHOW_BORDER = true
internal const val DEFAULT_SHOW_HALO = true
internal const val DEFAULT_SHOW_TOUCH_GLOW = true
/** 点阵开关默认值：默认开（生成主按钮是唯一不传 false 的调用点，U1 收敛后的事实）。 */
internal const val DEFAULT_SHOW_DOTS = true

/**
 * 白边（基准 box-shadow 的内描边项）：贴内缘细描边，内缩 0.75dp 让线外缘正好贴住按钮边缘。
 * 深色主题旧版 White 50%、v2 皮肤 White 22%；浅色主题 Black 12%（深浅判断按 background 亮度，
 * 见 [rippleFeedback]）；按压时 alpha ×1.25。线宽旧版 1.5dp、v2 皮肤 1dp。
 */
private const val BORDER_INSET_DP = 0.75f
// 旧版（回退态）与 v2 皮肤的白边线宽/深色 alpha：internal 供单测钉住两套规格
internal const val BORDER_STROKE_DP = 1.5f
internal const val BORDER_STROKE_DP_V2 = 1f
internal const val BORDER_ALPHA_DARK = 0.5f
internal const val BORDER_ALPHA_DARK_V2 = 0.22f
private const val BORDER_ALPHA_LIGHT = 0.12f
private const val BORDER_PRESS_BOOST = 1.25f

// v4.16.6（Q2）禁用降档白边：禁用态皮肤只保留这一项（渐变/辉光投影/点阵/按压回弹仍关），
// alpha 约为启用档一半，让禁用灰块的胶囊轮廓可辨（修复"禁用后形状看起来变了"）。
// 深色 White 10% 叠禁用容器（onSurface@12% 合成 RGB(41,46,51)）对比 1.37:1、叠背景 1.85:1；
// 浅色 Black 8% 叠禁用容器（合成 RGB(211,216,223)）对比 1.19:1 —— 规格建议的 6% 实测只有
// 1.14:1，糊进灰块轮廓仍不可辨，按对比度实测提一档。回退态深色启用 50% → 禁用 25%。
// internal 供单测钉住规格。
internal const val BORDER_ALPHA_DARK_V2_DISABLED = 0.10f
internal const val BORDER_ALPHA_LIGHT_DISABLED = 0.08f
internal const val BORDER_ALPHA_DARK_DISABLED = 0.25f

/**
 * 外辉光（基准 box-shadow 的 16px color-a 辉光项）：不用 BlurMaskFilter（硬件层会忽略），
 * 改用 3 层外扩描边模拟柔光 —— 外扩 2/4/6dp、alpha 逐层递减，深色主题更亮、浅色主题偏冷更淡。
 * 描边线宽 2dp：比白边粗一档，叠加后看起来是"晕"而不是"圈"。
 */
private val HALO_EXPAND_DP = floatArrayOf(2f, 4f, 6f)
private val HALO_ALPHAS_DARK = floatArrayOf(0.45f, 0.18f, 0.08f)
private val HALO_ALPHAS_LIGHT = floatArrayOf(0.20f, 0.08f, 0.03f)
private const val HALO_STROKE_DP = 2f

/**
 * 向下投影（v2 皮肤，主按钮）：不用 BlurMaskFilter，2 层与按钮同尺寸的圆角矩形
 * 向下偏移 2/4dp、alpha 递减，画在内容**之前**（渐变/按钮本体盖住上层重叠区，
 * 只有按钮下缘露出的条带可见 → 叠出向下渐弱的投影感）。颜色 = glowColor（主色）。
 * 深浅主题两套 alpha（U3：深色下 6dp/0.22 首层会发脏，减到 2 层 0.16/0.08；浅色更轻 0.10/0.05）。
 * internal 供单测钉住规格。
 */
internal val SHADOW_OFFSETS_DP = floatArrayOf(2f, 4f)
internal val SHADOW_ALPHAS_DARK = floatArrayOf(0.16f, 0.08f)
internal val SHADOW_ALPHAS_LIGHT = floatArrayOf(0.10f, 0.05f)

/** 触点辉光：触点处一小团柔光（实心圆，非描边环），峰值半径 8dp，随寿命淡出。 */
private const val TOUCH_GLOW_RADIUS_DP = 8f
private const val TOUCH_GLOW_MAX_ALPHA = 0.30f

/** 点阵点：基准口径，半径 1.3dp + 1.1dp×boost，最高 0.55 透明度（乘 boost）；上凸高度由采样直接给出。 */
private const val DOT_MIN_RADIUS_DP = 1.3f
private const val DOT_BOOST_RADIUS_DP = 1.1f
private const val DOT_MAX_ALPHA = 0.55f

/**
 * 按压缩放目标值（纯函数，便于单测）：按下取 [pressScale]，松手回 1。
 * [pressScale] 由调用方传入（完整皮肤 0.96 / 顶栏裸图标 0.94），本函数不持有默认值。
 */
internal fun resolvePressScale(pressed: Boolean, pressScale: Float): Float =
    if (pressed) pressScale else 1f

/**
 * 反馈层裁剪形状选择（纯函数，便于单测）：[cornerRadius] 非 null 时改用该圆角矩形，
 * 否则沿用传入的 [fallback] 形状。顶栏裸图标态传 12dp（圆角矩形裁点阵），
 * 回退态传 null（保持传入的圆形）。
 */
internal fun resolveFeedbackShape(cornerRadius: Dp?, fallback: Shape): Shape =
    if (cornerRadius == null) fallback else RoundedCornerShape(cornerRadius)

/**
 * 点阵点是否绘制（纯函数，便于单测）：[showDots] 关闭时任何 boost 都不画 ——
 * 「U1 收敛：点阵只留在生成主按钮」的零绘制契约钉在这里，绘制循环的 continue 判定走本函数。
 */
internal fun shouldDrawDot(showDots: Boolean, boost: Float): Boolean =
    showDots && boost > 0f

/**
 * 触点辉光是否绘制（纯函数，便于单测）：辉光是点阵皮肤的一部分，[showDots] 关闭时
 * 一并关闭（U1：顶栏按钮不得残留触点辉光），[showTouchGlow] 只在点阵开着时才有意义。
 */
internal fun shouldDrawTouchGlow(showDots: Boolean, showTouchGlow: Boolean): Boolean =
    showDots && showTouchGlow

/**
 * 按钮级「水面波纹点阵」点击反馈（v4.9.x，v5.x 皮肤对齐 HTML 观感基准）。
 *
 * ## 为什么挂在控件自己身上，而不是全局覆盖层
 *
 * v4.8.0 踩过：Compose 命中测试 `sharePointerInputWithSiblings = false`，覆盖层一旦
 * 挂了 pointerInput 会抢走全部点击。所以本 modifier 只能挂在**控件自己的包装层**上；
 * 所有指针监听走 [androidx.compose.ui.input.pointer.PointerEventPass.Initial]
 * 且**绝不 consume** —— Initial pass 只比 Main pass 早拿到事件做观察，事件原样放行，
 * 按钮自身的 clickable 收到的与没有本 modifier 时完全一致，点击行为零改动。
 *
 * ## 与业务按钮的配合
 *
 * 按压回弹（scale 0.96）依赖 [interactionSource] 里的 PressInteraction，所以调用方必须
 * 把**同一个** interactionSource 同时传给本 modifier 和被包裹的按钮；不传时内部自建一个，
 * 此时回弹不可用、触点波纹仍正常（波纹由 Initial pass 的 Press 事件驱动，与 source 无关）。
 *
 * ## v5.x 皮肤（双色渐变 + 白边 + 外辉光）
 *
 * - [gradientColors] 非 null 时在本层**最底**画 shape 区域内的 135° 双色渐变。
 *   可见性靠调用方配合：enabled 时把 M3 按钮的 containerColor 设为 [Color.Transparent]，
 *   渐变从透明容器下露出来；disabled 时调用方传 null 且恢复默认容器色，特效整体归零。
 * - 白边/外辉光跟随主题自适应（surface 亮度判断深浅，每次重组重算、不缓存颜色），
 *   调色盘换 accent 后下一帧渐变与辉光立即跟随 —— 颜色一律不进 remember。
 * - 外辉光是画在控件边界之外的溢出绘制，依赖父链无 clip；若某容器把溢出裁掉了，
 *   降级点就是 `haloOutlines` 绘制块（删掉即可，白边不受影响）。
 *
 * @param interactionSource 与被包裹按钮共享的交互源（观察按压态）
 * @param shape 裁剪形状：波纹/点阵被裁进该形状，防止溢出圆角
 * @param enabled false 时皮肤**降级**而非整条关闭（v4.16.6 Q2）：渐变/辉光投影/点阵/
 *   按压回弹全部关闭，但保留一圈**降档白边**（约启用档一半 alpha）勾出禁用灰块的
 *   轮廓——深色底上半透明灰块没白边勾勒时边缘糊化，用户感知为"按钮形状变了"
 * @param gradientColors 双色渐变（null = 不画；disabled 态必须传 null）
 * @param glowColor 外辉光/投影颜色（accent，跟随调色盘）
 * @param rippleColor 触点辉光颜色（触点处一小团实心柔光，随寿命淡出）
 * @param dotColor 点阵点颜色
 * @param showBorder 是否画白边（v5.x 皮肤拆分开关，默认开；顶栏裸图标态传 false）
 * @param showHalo 是否画外辉光/投影（默认开；顶栏裸图标态与 v2 下拉按钮传 false）
 * @param showTouchGlow 是否画触点辉光（默认开；顶栏裸图标态传 false）
 * @param showDots 是否画点阵（U1 收敛：只有生成主按钮保持默认 true；顶栏图标/下拉/齿轮
 *   等传 false —— 不登记波纹、不画点阵与触点辉光，但按压回弹/渐变/白边等皮肤结构保留）
 * @param pressScale 按压缩放目标值（默认 0.96 完整皮肤；顶栏裸图标态 0.94）
 * @param quickPressTimeline true 时按压缩放走"快进 60ms → 定格 150ms → 慢出 150ms"时序
 *   （v4.16.1 规格 A1/A2，修复快击时 spring 惯性导致的"白框迟到"；见
 *   [rememberPressTimelineProgress]）。只有顶栏图标（RippleIconButton）传 true；
 *   生成主按钮 / 齿轮等不传 → 默认 spring，手感不变（波及范围严格控制）
 * @param cornerRadius 非 null 时裁剪形状改用该圆角矩形（顶栏裸图标态 12dp；null = 用 [shape]）
 * @param haloDownward true 时辉光改为向下投影（v2 主按钮：向下偏移 2/4dp 的递减层，
 *   颜色 = glowColor）；false 保持旧版环形辉光
 * @param borderAlphaOverride 覆盖白边 alpha（v2 下拉按钮 0.18；null = 按主题默认值）
 */
@Composable
fun Modifier.rippleFeedback(
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = MaterialTheme.shapes.large,
    enabled: Boolean = true,
    gradientColors: List<Color>? = null,
    glowColor: Color = MaterialTheme.colorScheme.primary,
    // 触点辉光颜色（v4.12.x 起唯一用途：触点处一小团实心柔光，随寿命淡出）
    rippleColor: Color = MaterialTheme.colorScheme.primary,
    // 点阵点颜色：默认 primary（深色主题亮青蓝 / 浅色主题深青蓝，两种底上都够对比）。
    // 不能用 primaryContainer —— 浅蓝压深蓝渐变几乎不可见。深色渐变底的调用点
    // 显式传 Color.White；透明底调用点用默认或显式传 primary。
    dotColor: Color = MaterialTheme.colorScheme.primary,
    showBorder: Boolean = DEFAULT_SHOW_BORDER,
    showHalo: Boolean = DEFAULT_SHOW_HALO,
    showTouchGlow: Boolean = DEFAULT_SHOW_TOUCH_GLOW,
    showDots: Boolean = DEFAULT_SHOW_DOTS,
    pressScale: Float = DEFAULT_PRESS_SCALE,
    quickPressTimeline: Boolean = false,
    cornerRadius: Dp? = null,
    haloDownward: Boolean = false,
    borderAlphaOverride: Float? = null,
): Modifier {
    // v4.16.6（Q2）：禁用不再整条 return —— 皮肤降级：白边保留但降档（禁用灰块轮廓可辨），
    // 渐变/辉光投影/点阵/按压回弹仍然关闭。禁用按钮收不到 PressInteraction（clickable
    // enabled=false），下面的 pressed 已按 enabled 门控 → scale 与白边增压恒为静置值，
    // 即使调用方在禁用瞬间残留按压态也不会闪出回弹。
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val sourcePressed by source.collectIsPressedAsState()
    val pressed = sourcePressed && enabled
    // v4.16.1（A2）：quickPressTimeline=true（顶栏图标）的按压缩放走"快进→定格→慢出"时序
    // （graphicsLayer 里按进度 lerp 出 scale）；其余调用点（生成主按钮 / 齿轮）不传该参数
    // → 保持默认 spring（现状手感不变，波及范围严格控制，见派工单块 2.3）
    val pressProgress = if (quickPressTimeline) {
        rememberPressTimelineProgress(pressed)
    } else {
        null
    }
    val pressScaleAnim: State<Float> = if (quickPressTimeline) {
        remember { mutableStateOf(1f) }  // 时序态不消费 spring 值：占位保持组合树稳定
    } else {
        animateFloatAsState(
            targetValue = resolvePressScale(pressed, pressScale),
            label = "rippleFeedbackPressScale",
        )
    }

    // 按压时白边 alpha ×1.25（基准里的按压反馈只调 scale，这里是皮肤层的轻量增强）；
    // 走动画过渡而不是硬切，松手后沿同一条曲线淡回
    val borderBoost by animateFloatAsState(
        targetValue = if (pressed) BORDER_PRESS_BOOST else 1f,
        label = "rippleFeedbackBorderBoost",
    )

    // 深浅主题判断：按当前实际生效配色的**页面底色**（Scaffold 的 containerColor 取
    // background 槽位）亮度判定 —— 不用 isSystemInDarkTheme()（那只反映系统设置，
    // App 有独立主题模式，会不一致）。不 remember：换主题或换 accent 后重组时立即
    // 拿到新值，白边与辉光色实时跟随。
    val isDarkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderColor = if (isDarkScheme) Color.White else Color.Black

    // 皮肤 v2（AppSettings.isUiSkinV2Enabled）：白边降为 1dp / 深色 22%；回退时保持
    // 1.5dp / 50% 旧观感。视觉常量归皮肤层内部读取，调用方只管结构（尺寸/间距/投影模式）。
    val context = LocalContext.current
    val skinV2 = remember(context) { AppSettings.isUiSkinV2Enabled(context) }
    // Q2 禁用降档：白边是禁用态唯一保留的皮肤要素，alpha 换禁用档；忽略 override
    //（override 的三个场景——下拉打开提亮 32% / 回退 18% / 齿轮 18%——都只在启用态有意义，
    // 禁用按钮打不开菜单、齿轮恒启用，不存在"禁用还要 override"的调用点）
    val borderAlpha = if (!enabled) {
        if (isDarkScheme) {
            if (skinV2) BORDER_ALPHA_DARK_V2_DISABLED else BORDER_ALPHA_DARK_DISABLED
        } else {
            BORDER_ALPHA_LIGHT_DISABLED
        }
    } else {
        borderAlphaOverride ?: if (isDarkScheme) {
            if (skinV2) BORDER_ALPHA_DARK_V2 else BORDER_ALPHA_DARK
        } else {
            BORDER_ALPHA_LIGHT
        }
    }
    val borderStrokeWidth = if (skinV2) BORDER_STROKE_DP_V2 else BORDER_STROKE_DP
    val haloAlphas = if (isDarkScheme) HALO_ALPHAS_DARK else HALO_ALPHAS_LIGHT
    // 投影 alpha 同样深浅两套（U3：深色首层 0.22 发脏 → 0.16；浅色更轻）
    val shadowAlphas = if (isDarkScheme) SHADOW_ALPHAS_DARK else SHADOW_ALPHAS_LIGHT

    // Q2：点阵禁用即关（禁用不该有点阵，与按压回弹同批关闭）——帧循环/绘制/波纹登记
    // 三处以本开关为准，禁用时整条点阵链（协程 + 指针监听）都不挂
    val dotsActive = showDots && enabled

    // 低端机只在组合时判定一次，决定点阵间距是否加倍
    val isLowRamDevice = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val grid = remember { RippleDotGrid() }
    val ripples = remember { mutableStateListOf<RippleDotGrid.RippleCenter>() }
    val sampleBuffer = remember { FloatArray(RippleDotGrid.MAX_DOTS * RippleDotGrid.FLOATS_PER_DOT) }
    var frameTick by remember { mutableIntStateOf(0) }
    var nodeSizePx by remember { mutableStateOf(IntSize.Zero) }

    // 裁剪形状解析：cornerRadius 非空时改用该圆角矩形（顶栏裸图标态 12dp），否则沿用传入 shape
    val feedbackShape = resolveFeedbackShape(cornerRadius, shape)

    // 裁剪路径 / 白边 / 外辉光 / 投影的轮廓：只在形状/尺寸变化时重建（几何可以缓存，颜色不行），
    // 绘制热路径零分配
    val clipPath = remember(feedbackShape, nodeSizePx, layoutDirection, density) {
        if (nodeSizePx.width <= 0 || nodeSizePx.height <= 0) {
            Path()
        } else {
            Path().apply {
                addOutline(
                    feedbackShape.createOutline(
                        size = Size(nodeSizePx.width.toFloat(), nodeSizePx.height.toFloat()),
                        layoutDirection = layoutDirection,
                        density = density,
                    )
                )
            }
        }
    }
    val borderOutline = remember(feedbackShape, nodeSizePx, layoutDirection, density) {
        if (nodeSizePx.width <= 0 || nodeSizePx.height <= 0) return@remember null
        val insetPx = with(density) { BORDER_INSET_DP.dp.toPx() }
        if (nodeSizePx.width <= insetPx * 2 || nodeSizePx.height <= insetPx * 2) return@remember null
        feedbackShape.createOutline(
            size = Size(nodeSizePx.width - insetPx * 2, nodeSizePx.height - insetPx * 2),
            layoutDirection = layoutDirection,
            density = density,
        )
    }
    val haloOutlines = remember(feedbackShape, nodeSizePx, layoutDirection, density) {
        if (nodeSizePx.width <= 0 || nodeSizePx.height <= 0) return@remember null
        HALO_EXPAND_DP.map { expandDp ->
            val expandPx = with(density) { expandDp.dp.toPx() }
            feedbackShape.createOutline(
                size = Size(nodeSizePx.width + expandPx * 2, nodeSizePx.height + expandPx * 2),
                layoutDirection = layoutDirection,
                density = density,
            )
        }
    }
    // 投影层轮廓：与按钮同形同尺寸（只平移、不外扩），v2 主按钮向下投影用
    val shadowOutline = remember(feedbackShape, nodeSizePx, layoutDirection, density) {
        if (nodeSizePx.width <= 0 || nodeSizePx.height <= 0) return@remember null
        feedbackShape.createOutline(
            size = Size(nodeSizePx.width.toFloat(), nodeSizePx.height.toFloat()),
            layoutDirection = layoutDirection,
            density = density,
        )
    }

    // 帧驱动：没有活跃波纹时挂在 snapshotFlow 上休眠，不空转烧帧；
    // showDots=false（U1 收敛态）或禁用态（Q2）连这个协程都不挂 —— 无波纹来源，整条帧链没有存在意义
    if (dotsActive) {
        LaunchedEffect(Unit) {
            while (true) {
                if (ripples.isEmpty()) {
                    snapshotFlow { ripples.size }.first { it > 0 }
                }
                withFrameNanos { /* 只对齐帧信号，时间取自单调时钟 */ }
                val now = System.nanoTime() / NANOS_PER_SEC
                val hadRipples = ripples.isNotEmpty()
                ripples.removeAll { now - it.bornAtSec >= RippleDotGrid.RIPPLE_LIFE_SEC }
                if (ripples.isNotEmpty() || hadRipples) {
                    // 全部过期后仍采样一帧（空列表 → 全 0），把上一帧的残影刷掉
                    grid.sample(ripples, now, sampleBuffer)
                    frameTick++
                }
            }
        }
    }

    return this
        .graphicsLayer {
            // v4.16.1：顶栏图标按时序进度 lerp 出 scale（快进/定格/慢出），其余调用点 spring 值直用
            val scale = if (pressProgress != null) {
                lerpFloat(1f, resolvePressScale(pressed, pressScale), pressProgress.value)
            } else {
                pressScaleAnim.value
            }
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            // 订阅帧计数：帧循环每推进一帧就 ++ 它，draw 阶段**必须读一次**才会在下一帧
            // 重新执行。否则只有 ripples 列表结构变化（增删元素）时才重绘，
            // 而波纹在寿命期内不会增删 —— 结果是波纹卡在第一帧、完全看不出在扩散。
            @Suppress("UNUSED_VARIABLE")
            val frameSignal = frameTick

            // 0) 向下投影（v2 主按钮）：2 层同尺寸圆角矩形向下偏移 2/4dp、alpha 递减，
            //    画在**最底层** —— 渐变与按钮本体盖住上层重叠区，只有按钮下缘露出的
            //    条带可见，叠出向下渐弱的投影感。画在控件边界之外的溢出绘制，依赖父链无 clip
            //    （与旧外辉光同机制；旧辉光 6dp 全向溢出已长期在线上正常绘制）。
            // Q2：禁用态投影不画（皮肤降级只保留白边；渐变调用方传 null 时本就不画，
            // 这里再按 enabled 门控一道，防御"禁用还传渐变"的调用点）
            if (showHalo && haloDownward && enabled) {
                shadowOutline?.let { outline ->
                    SHADOW_OFFSETS_DP.forEachIndexed { idx, offDp ->
                        translate(0f, offDp.dp.toPx()) {
                            drawOutline(outline = outline, color = glowColor, alpha = shadowAlphas[idx])
                        }
                    }
                }
            }

            // 0.5) 双色渐变底（v5.x）：画在 drawContent 之前、裁进 shape。
            //    可见性机制：enabled 时调用方把 M3 容器色设为透明，渐变从下层露出；
            //    不缓存 Brush —— 每帧从参数现取，accent 切换后下一帧立即换色。
            val colors = gradientColors
            if (colors != null && colors.size >= 2 && enabled) {
                val gradient = Brush.linearGradient(
                    colors = colors,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                clipPath(clipPath) {
                    drawRect(brush = gradient)
                }
            }

            // 1) 原按钮内容（渐变态下容器是透明的，只剩文字/图标叠在渐变上）
            drawContent()

            // 2) 外辉光：3 层外扩描边模拟柔光，画在控件边界之外（父链无 clip 时可见）。
            //    静置常显（基准 box-shadow 本来就是静置属性，不是按压特效）。
            //    v2 的向下投影已在第 0 层画过，这里只走旧环形辉光。Q2：禁用态不画。
            if (showHalo && !haloDownward && enabled) {
                haloOutlines?.let { outlines ->
                    outlines.forEachIndexed { idx, outline ->
                        val expandPx = HALO_EXPAND_DP[idx].dp.toPx()
                        translate(-expandPx, -expandPx) {
                            drawOutline(
                                outline = outline,
                                color = glowColor,
                                alpha = haloAlphas[idx],
                                style = Stroke(width = HALO_STROKE_DP.dp.toPx()),
                            )
                        }
                    }
                }
            }

            // 3) 白边：贴内缘的细描边（旧版 1.5dp / v2 皮肤 1dp），**必须画在 drawContent 之后**
            //    （渐变态下按钮容器透明，白边压在渐变上沿内缘一圈）。Q2：禁用态也画
            //    （alpha 走禁用降档档位）——这是禁用灰块唯一的轮廓来源。
            if (showBorder && borderOutline != null) {
                val insetPx = BORDER_INSET_DP.dp.toPx()
                translate(insetPx, insetPx) {
                    drawOutline(
                        outline = borderOutline,
                        color = borderColor,
                        alpha = (borderAlpha * borderBoost).coerceAtMost(1f),
                        style = Stroke(width = borderStrokeWidth.dp.toPx()),
                    )
                }
            }

            // 4) 触点辉光 + 点阵，全部裁进按钮形状，防止溢出圆角。
            //    涟漪完全由点阵的点亮 + 上凸体现；触点处保留一小团实心柔光（点，不是环）。
            //    showDots=false 或禁用态（Q2）整层不画（U1 收敛：点阵只留在生成主按钮，其余按钮零残留）。
            if (dotsActive) {
                clipPath(clipPath) {
                    val now = System.nanoTime() / NANOS_PER_SEC
                    if (shouldDrawTouchGlow(showDots, showTouchGlow)) {
                        for (ripple in ripples) {
                            val age = ripple.ageAt(now)
                            if (age <= 0.0 || age >= RippleDotGrid.RIPPLE_LIFE_SEC) continue
                            drawCircle(
                                color = rippleColor,
                                radius = TOUCH_GLOW_RADIUS_DP.dp.toPx(),
                                center = Offset(ripple.x.dp.toPx(), ripple.y.dp.toPx()),
                                alpha = ripple.lifeFadeAt(now).toFloat() * TOUCH_GLOW_MAX_ALPHA,
                            )
                        }
                    }
                    for (i in 0 until grid.dotCount) {
                        val base = i * RippleDotGrid.FLOATS_PER_DOT
                        val boost = sampleBuffer[base + 2]
                        if (!shouldDrawDot(showDots, boost)) continue
                        drawCircle(
                            color = dotColor,
                            radius = (DOT_MIN_RADIUS_DP + DOT_BOOST_RADIUS_DP * boost).dp.toPx(),
                            // 上凸（v5.x）：采样层直接给出按道累加后的 lift（总封顶 6dp），
                            // 波前扫过的点抬升，波过随累加值回落
                            center = Offset(
                                sampleBuffer[base].dp.toPx(),
                                sampleBuffer[base + 1].dp.toPx() - sampleBuffer[base + 3].dp.toPx(),
                            ),
                            alpha = DOT_MAX_ALPHA * boost,
                        )
                    }
                }
            }
        }
        .onSizeChanged { size ->
            nodeSizePx = size
            if (size.width > 0 && size.height > 0) {
                with(density) {
                    grid.rebuildGrid(
                        widthDp = size.width.toDp().value,
                        heightDp = size.height.toDp().value,
                        lowRam = isLowRamDevice,
                    )
                }
            }
        }
        .then(
            // 波纹登记只在点阵开着且启用时挂：showDots=false 或禁用态的按钮连 Press 观察都不做，
            // 保证零波纹（不登记 → ripples 恒空 → 帧循环休眠、无残影可画）。
            if (dotsActive) {
                Modifier.pointerInput(grid) {
                    // Initial pass 只观察不消费：Press 时以触点为圆心登记一道波纹
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Press) continue
                            val change = event.changes.firstOrNull() ?: continue
                            if (!change.pressed) continue
                            grid.pushRipple(
                                centers = ripples,
                                x = with(density) { change.position.x.toDp().value },
                                y = with(density) { change.position.y.toDp().value },
                                timeSec = System.nanoTime() / NANOS_PER_SEC,
                            )
                            frameTick++
                        }
                    }
                }
            } else {
                Modifier
            }
        )
}

/** v2 顶栏裸图标规格：44dp 触控区、圆角 12（不是圆形）、按压缩放 0.94。 */
private val ICON_TOUCH_SIZE_DP_V2 = 44.dp
private val ICON_CORNER_DP_V2 = 12.dp
private const val ICON_PRESS_SCALE_V2 = 0.94f

/**
 * v2 按压底色统一档（模块 2/D2，B2b 拆深浅）：按压覆盖层与页面底色的 WCAG 对比度
 * 按深浅分别钉档 —— 深色 White 13% ≈ 1.45:1（实测 1.435），浅色 Black 15% ≈ 1.41:1
 *（实测 1.409；Black 13% 的数学天花板只有 1.343，到不了 1.35 判据，B2b 提档）。
 * 顶栏图标 / 导航 tab / 齿轮三处按压终值共用本组常量。回退态不用（无底座）。
 * internal 供单测钉住规格。
 */
internal const val PRESS_BG_ALPHA_V2_DARK = 0.13f
internal const val PRESS_BG_ALPHA_V2_LIGHT = 0.15f

/** 回退档（[AppSettings.isPressFeedbackUnified] 关闭）：改动前的深色 8% / 浅色 6%。 */
internal const val PRESS_BG_ALPHA_DARK_V2_LEGACY = 0.08f
internal const val PRESS_BG_ALPHA_LIGHT_V2_LEGACY = 0.06f

/**
 * 禁用态按压底色的强度系数（v4.16.6 S1）：禁用**仍画底色但减半**（深 13%→6.5%、
 * 浅 15%→7.5%；回退档 8%→4%、6%→3%）——四个顶栏按钮在任意状态下按下去都有可见
 * 反馈，强弱差异仍能一眼区分"能点/不能点"。缩放不跟随：禁用态 scale 恒 1（缩放会
 * 给"能点"的错觉，由 interactionSource 收不到禁用按压自动保证，见 RippleIconButton）。
 * internal 供单测钉住规格。
 */
internal const val PRESS_BG_DISABLED_FACTOR = 0.5f

/**
 * 按压底色强度解析（纯函数，供 JVM 单测）：启用取满强度，禁用乘 [PRESS_BG_DISABLED_FACTOR]
 * 减半。派工单判据：禁用强度 = 启用 × 0.5（允许 ±0.01）。
 */
internal fun resolvePressBgAlpha(enabled: Boolean, fullAlpha: Float): Float =
    if (enabled) fullAlpha else fullAlpha * PRESS_BG_DISABLED_FACTOR

/**
 * 带波纹点阵反馈的图标按钮包装（顶栏图标用）。
 *
 * 结构固定为 `Box(rippleFeedback, shape, size) { IconButton(fillMaxSize) }`：
 * - 反馈层挂在 Box（按钮自己的包装层）上，不碰任何父容器；
 * - interactionSource 由这里创建并共享给 IconButton，按压回弹与波纹同源；
 * - IconButton 填满整个 Box。
 *
 * v2 皮肤（AppSettings.isUiSkinV2Enabled，默认开）：**裸图标化** —— 44×44 触控区、
 * 圆角 12 矩形、无圆形底座 / 无白边 / 无外辉光 / 无触点辉光 / **无点阵**
 * （U1 收敛：点阵只留在生成主按钮，顶栏图标一律 showDots=false），按压缩放 0.94；
 * 按压 = 圆角矩形底色（统一档：深色 White 13% / 浅色 Black 15%，模块 2 安全阀关闭
 * 回 8%/6%）+ scale 动画过渡，**无 M3 圆形 ripple**（自绘 clickable(indication = null)，
 * 见下方实现注释）。
 * 图标 22dp 由调用方的 Icon 自己定尺寸。
 *
 * 回退态（开关关闭）：48dp 圆形 + 白边 + 外辉光 + 触点辉光 + 缩放 0.96（v4.12.x 形态），
 * 全部走同一套 Box 与参数分支，没有复制两套 UI。
 *
 * v4.12.x：内部读 `AppSettings.isRippleDotFeedbackEnabled`（默认 true）——
 * 所有经本包装的图标按钮（顶栏加图/蒙版/清除/保存/分享、释放显存、菜单）
 * 一并受该总开关控制，置 false 时恢复裸 IconButton 行为（按压底色一并关闭）。
 */
@Composable
internal fun RippleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 总开关在组合时读一次：置 false 后全部反馈（波纹/上凸/白边/辉光/回弹/按压底色）一键关闭
    val context = LocalContext.current
    val dotFeedbackEnabled = remember(context) {
        AppSettings.isRippleDotFeedbackEnabled(context)
    }
    val skinV2 = remember(context) { AppSettings.isUiSkinV2Enabled(context) }
    // 模块 2 安全阀（组合时读一次）：开 = 去圆形 ripple + 按压底色统一 13%；关 = 改动前形态
    val pressUnified = remember(context) { AppSettings.isPressFeedbackUnified(context) }
    // 深浅判断与 rippleFeedback 同口径：按页面底色亮度（不用 isSystemInDarkTheme，见其 KDoc）
    val isDarkScheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // v2 按压底色（模板规格：按压反馈只有"底色 + scale"两样）。
    // v4.16.1（A1/A2）：从"默认 spring 的 animateColorAsState"改为"快进→定格→慢出"时序进度
    // lerp。spring 的惯性是"快击时白框迟到冒出来"的根源：动画还没爬到峰值就被松手反向。
    // 新时序（rememberPressTimelineProgress）：按下 ≤60ms 到满强度、松手补完快进段并定格
    // 150ms、再 150ms 淡出 —— 直给，无惯性。
    // 统一档（模块 2/D2，B2b 拆深浅）：深色 White 13% / 浅色 Black 15%（实测对比度
    // ≈1.45 / ≈1.41）；模块 2 开关关闭回 8%/6% 旧档。
    // v4.16.6（S1）：pressFeedbackOn 不再要求 enabled —— 禁用时仍画底色但**减半**
    //（resolvePressBgAlpha，深 13%→6.5% / 浅 15%→7.5%），保存/分享在无生成结果时按下去
    // 也有可见响应（半强度弱反馈），强弱差异仍能区分"能点/不能点"；缩放不跟随（禁用
    // 态 interactionSource 收不到按压 → scale 恒 1，缩放会给"能点"的错觉）。
    // 禁用态按压检测：clickable(enabled=false) 不产生 PressInteraction，interactionSource
    // 收不到按压 —— 由 Initial pass 的指针观察者驱动（只观察、绝不 consume，与
    // rippleFeedback 的波纹登记同一纪律）。
    var disabledPressed by remember { mutableStateOf(false) }
    val pressed by interactionSource.collectIsPressedAsState()
    val quickPress = skinV2 && dotFeedbackEnabled
    val pressFeedbackOn = quickPress
    // 启用态按压走 interactionSource（clickable 的 PressInteraction）；禁用态 clickable
    // 不发任何交互事件，只能靠下面的指针观察者驱动半强度底色
    val pressDriver = if (enabled) pressed else disabledPressed
    val pressProgress = rememberPressTimelineProgress(pressDriver && pressFeedbackOn)
    // 禁用态底色减半（S1）：深 13%→6.5% / 浅 15%→7.5%（回退档同比例）
    val pressAlphaFactor = if (enabled) 1f else PRESS_BG_DISABLED_FACTOR
    val pressBgActive = if (pressFeedbackOn) {
        val pressAlpha = resolvePressBgAlpha(
            enabled = true,
            fullAlpha = if (pressUnified) {
                if (isDarkScheme) PRESS_BG_ALPHA_V2_DARK else PRESS_BG_ALPHA_V2_LIGHT
            } else if (isDarkScheme) {
                PRESS_BG_ALPHA_DARK_V2_LEGACY
            } else {
                PRESS_BG_ALPHA_LIGHT_V2_LEGACY
            },
        ) * pressAlphaFactor
        if (isDarkScheme) {
            Color.White.copy(alpha = pressAlpha)
        } else {
            Color.Black.copy(alpha = pressAlpha)
        }
    } else {
        Color.Transparent
    }

    // 皮肤结构层（v2/回退两分支共用）：反馈 → 尺寸 → 按压底色。
    // v2 按压底色：drawBehind 属于 rippleFeedback 的 content，点阵在其后的
    // drawWithContent 里叠加 → 底色在图标之下、点阵之下。回退态颜色恒透明（不画）。
    // v4.16.6（S1）：禁用按压观察者只挂在自绘分支（M3 IconButton 回退态保持旧形态、
    // 禁用零反馈）——clickable(enabled=false) 不消费事件，Initial pass 仍能观察到按下/抬起。
    val disabledPressObserver = if (!enabled && pressFeedbackOn) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial)
                disabledPressed = true
                // 抬起或手势被取消（移出边界）都复位：取消时 waitForUpOrCancellation 返回 null
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                disabledPressed = false
            }
        }
    } else {
        Modifier
    }
    val feedbackModifier = modifier
        .rippleFeedback(
            interactionSource = interactionSource,
            shape = CircleShape,
            // v2：圆角 12 矩形（回退态 null → 沿用传入的 CircleShape）
            cornerRadius = if (skinV2) ICON_CORNER_DP_V2 else null,
            // v2：白边/辉光/触点辉光全部关掉（裸图标态）
            showBorder = !skinV2,
            showHalo = !skinV2,
            showTouchGlow = !skinV2,
            // U1 收敛：点阵只留在生成主按钮，经本包装的顶栏图标（含 3D/释放显存/菜单）
            // 一律不带点阵；皮肤结构（触控区/圆角/按压底色/scale）原样保留
            showDots = false,
            pressScale = if (skinV2) ICON_PRESS_SCALE_V2 else DEFAULT_PRESS_SCALE,
            // v4.16.1：v2 皮肤生效时按压缩放走"快进→定格→慢出"时序（回退态保持旧 spring 手感）
            quickPressTimeline = quickPress,
            enabled = enabled && dotFeedbackEnabled,
            // 不传 gradientColors：顶栏图标不加渐变
            // 点阵用 primary：透明底（浅色主题下=页面近白底色）上白点会消失，必须跟强调色走
            dotColor = MaterialTheme.colorScheme.primary,
        )
        .size(if (skinV2) ICON_TOUCH_SIZE_DP_V2 else 48.dp)
        .drawBehind {
            // 按压底色按时序进度插值：0=透明（静置）→ 1=满强度（快进/定格）→ 0（慢出）
            drawRoundRect(
                color = lerp(Color.Transparent, pressBgActive, pressProgress.value),
                cornerRadius = CornerRadius(ICON_CORNER_DP_V2.toPx()),
            )
        }

    if (skinV2 && dotFeedbackEnabled && pressUnified) {
        // v2 + 反馈开启 + 模块 2 开关开启：自绘 Box + clickable(indication = null) 替代
        // M3 IconButton。为什么不能用 CompositionLocal：M3 1.5.0-alpha11 的 IconButton →
        // Surface(onClick) → clickable 链路里 indication 是硬编码的 material3.ripple()
        //（SurfaceKt 反编译确认 LocalIndication 引用数为 0），IconButton 签名也不暴露
        // indication 参数，CompositionLocalProvider(LocalIndication provides ...) 覆盖无效
        // —— 只能像 MainNavigationBar.NavBarTab 一样自己画。语义/行为保持：clickable 自带
        // mergeDescendants=true 的合并边界（content 图标的 contentDescription 照常并入）、
        // 不传 role（与 M3 内部 Surface→clickable 一致）、触控区 = Box 的 44dp 规格。
        Box(
            modifier = feedbackModifier
                .clickable(
                    interactionSource = interactionSource,
                    // 按压反馈由上面的底色 + scale 承担，不再叠加 M3 圆形 ripple（双层反馈打架）
                    indication = null,
                    onClick = onClick,
                    enabled = enabled,
                )
                .then(disabledPressObserver),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    } else {
        // 回退态（或皮肤关闭，或模块 2 开关关闭）：保留 M3 IconButton
        //（自带圆形 ripple 与最小触控区扩展 —— 开关关闭时 ripple 必须回归）
        Box(modifier = feedbackModifier) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxSize(),
            ) {
                content()
            }
        }
    }
}
