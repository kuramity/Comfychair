package sh.hnet.comfychair.ui.components.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.ui.theme.ColorMath
import kotlin.math.abs

/**
 * HSV 取色器：上方一块饱和度/明度渐变面板，下方一条光谱色相带，底部一个十六进制输入框。
 *
 * 关于渐变：项目为了流畅度一直避免渐变/模糊/阴影，这里是例外 ——
 * 渐变就是取色器要显示的内容本身（面板的"白→纯色→黑"、色相带的光谱），
 * 而且只在这个页面上存在，不进主界面。画法是 Canvas 矩形填充，不是 Modifier 效果，没有额外开销。
 *
 * 关于内部状态：拖动过程中色相、饱和度、明度三个分量各自记在组件内部，
 * 而不是每次都由外部传进来的颜色反推。原因是纯黑与灰阶反推不出色相（HSV 会归零），
 * 那样用户把明度拖到 0 再拖回来，色相会莫名其妙跳回红色。
 */
@Composable
fun HsvColorPicker(
    color: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = remember { ColorMath.toHsv(color) }
    var hue by remember { mutableFloatStateOf(initial.h) }
    var saturation by remember { mutableFloatStateOf(initial.s) }
    var value by remember { mutableFloatStateOf(initial.v) }

    // 手势回调通过 rememberUpdatedState 取最新一份：pointerInput 的 lambda 只装载一次，
    // 直接捕获参数会一直抓着首次组合时的那个 lambda
    val currentOnColorChange by rememberUpdatedState(onColorChange)

    // 外部颜色被换掉时（点重置、或切到另一个待调项）同步回来。
    // 拖动产生的量化误差在 ±1 通道以内，用容差滤掉，免得拖一次就被自己的回声打断。
    val derived = ColorMath.fromHsv(ColorMath.Hsv(hue, saturation, value))
    LaunchedEffect(color) {
        if (!differsVisibly(color, derived)) return@LaunchedEffect
        val hsv = ColorMath.toHsv(color)
        // 灰阶/纯黑反推不出色相，这种情况下保留用户当前所在色相
        if (hsv.s > 0.01f && hsv.v > 0.01f) hue = hsv.h
        saturation = hsv.s
        value = hsv.v
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SaturationValuePanel(
            hue = hue,
            saturation = saturation,
            value = value,
            panelHeight = 176.dp,
            onSaturationValueChange = { newSaturation, newValue ->
                saturation = newSaturation
                value = newValue
                currentOnColorChange(ColorMath.fromHsv(ColorMath.Hsv(hue, newSaturation, newValue)))
            }
        )

        HueStrip(
            hue = hue,
            stripHeight = 26.dp,
            onHueChange = { newHue ->
                hue = newHue
                currentOnColorChange(ColorMath.fromHsv(ColorMath.Hsv(newHue, saturation, value)))
            }
        )

        HexField(color = color, onColorChange = { currentOnColorChange(it) })
    }
}

/** 面板：横轴饱和度、纵轴明度。左上角是白、右上角是该色相的纯色、最下一整行是黑。 */
@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    panelHeight: Dp,
    onSaturationValueChange: (Float, Float) -> Unit
) {
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnChange by rememberUpdatedState(onSaturationValueChange)
    val pureHue = Color(ColorMath.pureHue(hue))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .onSizeChanged { panelSize = it }
            .onPointerPosition { position ->
                val size = panelSize
                if (size.width <= 0 || size.height <= 0) return@onPointerPosition
                currentOnChange(
                    (position.x / size.width).coerceIn(0f, 1f),
                    (1f - position.y / size.height).coerceIn(0f, 1f)
                )
            }
    ) {
        // 底层：白 → 纯色（饱和度）
        drawRect(brush = Brush.horizontalGradient(colors = listOf(Color.White, pureHue)))
        // 上层：透明 → 黑（明度）
        drawRect(brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black)))

        val centerX = saturation * size.width
        val centerY = (1f - value) * size.height
        // 双层描边：白圈在暗部看得见，黑圈在亮部看得见
        drawCircle(Color.Black, radius = 11.dp.toPx(), center = Offset(centerX, centerY), style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(centerX, centerY), style = Stroke(2.dp.toPx()))
    }
}

/** 光谱色相带：红→黄→绿→青→蓝→品红，横轴对应 0..360 度 */
@Composable
private fun HueStrip(
    hue: Float,
    stripHeight: Dp,
    onHueChange: (Float) -> Unit
) {
    var stripWidth by remember { mutableIntStateOf(0) }
    val currentOnChange by rememberUpdatedState(onHueChange)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
            .onSizeChanged { stripWidth = it.width }
            .onPointerPosition { position ->
                if (stripWidth <= 0) return@onPointerPosition
                currentOnChange(((position.x / stripWidth) * 360f).coerceIn(0f, 360f))
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(colors = hueSpectrum()))

        val markerX = (hue / 360f) * size.width
        val markerWidth = 4.dp.toPx()
        val corner = CornerRadius(markerWidth / 2f)
        // 同样双层：白标在浅色区、黑标在深色区各保证一边可见
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(markerX - markerWidth / 2f - 1.dp.toPx(), 0f),
            size = Size(markerWidth + 2.dp.toPx(), size.height),
            cornerRadius = CornerRadius(corner.x + 1.dp.toPx())
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(markerX - markerWidth / 2f, 2.dp.toPx()),
            size = Size(markerWidth, size.height - 4.dp.toPx()),
            cornerRadius = corner
        )
    }
}

/** 十六进制输入：边输边预览，输满 6 位有效数字立刻应用 */
@Composable
private fun HexField(color: Int, onColorChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(ColorMath.toHexString(color)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 只在输入框没有焦点时同步：用户正在敲字的时候把它覆盖掉，等于边打字边被擦掉
    LaunchedEffect(color, focused) {
        if (!focused) text = ColorMath.toHexString(color)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val trimmed = raw.take(7)
                text = trimmed
                parseHexColor(trimmed)?.let(onColorChange)
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            label = { Text("十六进制") },
            placeholder = { Text("#38BDF8") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            isError = parseHexColor(text) == null
        )
    }
}

/** 光谱色标（0/60/…/360 度各取纯色），首尾都是红，保证渐变能接上 */
private fun hueSpectrum(): List<Color> =
    (0..360 step 60).map { hue -> Color(ColorMath.pureHue(hue.toFloat())) }

/**
 * 解析 `#RRGGBB`（也接受不带 `#` 的写法）。
 * 位数不够或含非法字符时返回 null —— 调用方靠它判断"是不是还在输入中"。
 */
internal fun parseHexColor(raw: String): Int? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return 0xFF000000.toInt() or hex.toLong(16).toInt()
}

/** 通道差超过 2 才算"看起来不一样"，用来滤掉 8 位量化的回声 */
private fun differsVisibly(a: Int, b: Int): Boolean =
    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > 2 ||
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > 2 ||
        abs((a and 0xFF) - (b and 0xFF)) > 2

/**
 * 「按下即响应 + 持续跟手」的手势。
 *
 * 没有用 `detectDragGestures`：它要等手指移动超过触摸阈值才回调，
 * 点一下面板不动就没反应，取色器上这种手感很别扭。
 */
private fun Modifier.onPointerPosition(onPosition: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onPosition(down.position)
            drag(down.id) { change ->
                onPosition(change.position)
                change.consume()
            }
        }
    }
