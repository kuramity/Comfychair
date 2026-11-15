package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 预览区底圆角（我推荐项规格）：16dp，介于卡片 medium(16) 与面板 large(20) 之间取统一值。 */
private val PREVIEW_CORNER_RADIUS = 16.dp

/** 渐变终点相对 surfaceContainer 的提亮幅度：向白 lerp 10%，深浅主题都"略亮一档"且不刺眼。 */
private const val PREVIEW_GRADIENT_LIGHTEN_FRACTION = 0.10f

/** 渐变角度（CSS 口径：0° 向上、顺时针）：160° = 主要向下、略偏右 20°。 */
private const val PREVIEW_GRADIENT_ANGLE_DEG = 160.0

/**
 * 四个生成页预览区共用的底样式（我推荐项）：直角纯色 → 圆角 16 + 160° 双色渐变
 * （`surfaceContainer` → 向白 lerp 10% 的同色系）。
 *
 * 为什么用 drawRoundRect 而不是 `clip + background`：预览图是 `fillMaxSize + Crop` 铺满
 * 容器，clip 会把图片四角裁掉 —— 任务约束是"内容不受影响、不裁图片"。drawRoundRect
 * 只画圆角渐变背景，内容层完全不动：空态时渐变圆角底可见，有图时图片照旧矩形铺满。
 *
 * 渐变端点在 draw 时按实际尺寸沿 160° 方向从中心向两端取对角线长度，保证任何
 * 宽高比下渐变都铺满整个区域且方向一致。
 *
 * 四个页面（文生图/图生图/文生视频/图生视频）共用本函数，替换原先各写一份的
 * `.background(colorScheme.surfaceContainer)` 直角纯色。
 */
@Composable
fun Modifier.previewSurfaceBackground(): Modifier {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val brighter = lerp(container, Color.White, PREVIEW_GRADIENT_LIGHTEN_FRACTION)
    val radiusPx = with(LocalDensity.current) { PREVIEW_CORNER_RADIUS.toPx() }
    return this.drawBehind {
        val angleRad = Math.toRadians(PREVIEW_GRADIENT_ANGLE_DEG)
        // 方向向量（屏幕坐标 y 向下为正）：160° → 主要向下、略偏右
        val dirX = sin(angleRad).toFloat()
        val dirY = (-cos(angleRad)).toFloat()
        val diagonal = hypot(size.width, size.height)
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val brush = Brush.linearGradient(
            colors = listOf(container, brighter),
            start = Offset(centerX - dirX * diagonal / 2f, centerY - dirY * diagonal / 2f),
            end = Offset(centerX + dirX * diagonal / 2f, centerY + dirY * diagonal / 2f),
        )
        drawRoundRect(brush = brush, cornerRadius = CornerRadius(radiusPx))
    }
}
