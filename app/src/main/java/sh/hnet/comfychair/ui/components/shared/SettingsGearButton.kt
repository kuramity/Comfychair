package sh.hnet.comfychair.ui.components.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback

/** 齿轮按钮规格（v2 皮肤）：56×56 圆形，底色深色 White 5%、按压升到 10%。 */
private const val GEAR_BASE_ALPHA = 0.05f
private const val GEAR_PRESSED_ALPHA = 0.10f

/** 齿轮白边 alpha：深色 White 22% / 浅色 Black 22%（颜色在 rippleFeedback 内按主题自适应）。 */
private const val GEAR_BORDER_ALPHA = 0.22f

/**
 * 四个生成页底部操作行共用的「节点参数」齿轮按钮（v5 从四页内联的
 * `OutlinedIconButton(56dp)` + `Spacer(8dp)` 抽出，C6）。
 *
 * v2 皮肤（AppSettings.isUiSkinV2Enabled，默认开）：56×56 **圆形**、底 `White 5%`
 * （浅色主题 Black 5%）、描边 `White 22%` 1dp、图标 `onSurface`、无渐变无投影；
 * 按压 = 底色升到 10% + scale 0.94。层级定位：齿轮 < 下拉 < 主按钮（三者不同重量）。
 *
 * 回退态：恢复旧版 `OutlinedIconButton`（56dp 描边圆），与四页原有形态一致。
 * 两条形态走同一组件的参数分支，不复制两套 UI。
 *
 * 打开态图标旋转 90° 的动画收进本组件（原先四页各自内联 animateFloatAsState）。
 *
 * @param open 「节点参数」面板是否打开（true 时图标转 90°）
 * @param onClick 点击回调（打开面板；关闭走面板自己的 onDismissRequest）
 */
@Composable
fun SettingsGearButton(
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val skinV2 = remember(context) { AppSettings.isUiSkinV2Enabled(context) }
    val rotation by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        label = "settingsGearRotation",
    )

    if (!skinV2) {
        // 回退形态：v4.12.x 的内联 OutlinedIconButton（行为与视觉都原样保留）
        OutlinedIconButton(
            onClick = onClick,
            modifier = modifier.size(56.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.button_options),
                modifier = Modifier.rotate(rotation)
            )
        }
        return
    }

    // v2 皮肤：底色/描边深浅自适应（与 rippleFeedback 同口径：按页面底色亮度判断）
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseColor = if (isDark) Color.White else Color.Black
    // 底色用"双色同色渐变"承载（rippleFeedback 的渐变层画在内容之下，无渐变观感）；
    // 按压时动画升档到 10%，配合 scale 0.94（rippleFeedback 内部）。
    val targetAlpha = if (pressed) GEAR_PRESSED_ALPHA else GEAR_BASE_ALPHA
    val gearBg = baseColor.copy(alpha = targetAlpha)

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .rippleFeedback(
                interactionSource = interactionSource,
                shape = CircleShape,
                // 白边 22%（颜色按主题自适应：深色 White / 浅色 Black），线宽随皮肤层走 1dp
                borderAlphaOverride = GEAR_BORDER_ALPHA,
                // 无渐变观感（纯色底）、无辉光无投影：层级最低的齿轮不发光
                showHalo = false,
                // 按压缩放与顶栏裸图标一致（0.94），比主按钮族（0.96）轻一档
                pressScale = 0.94f,
                gradientColors = listOf(gearBg, gearBg),
                // 点阵用 primary：浅色主题下 White 点在 5% 底上不可见
                dotColor = MaterialTheme.colorScheme.primary,
            )
            .size(56.dp)
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = stringResource(R.string.button_options),
            // 图标 onSurface（规格）：比顶栏图标的 onSurfaceVariant 更实，区分"进面板"与"结果操作"
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.rotate(rotation)
        )
    }
}
