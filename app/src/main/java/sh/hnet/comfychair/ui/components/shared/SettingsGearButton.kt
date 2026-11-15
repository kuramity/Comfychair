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
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_V2_DARK
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_V2_LIGHT
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback

/** 齿轮按钮规格（v2 皮肤）：56×56 圆形，底色深色 White 5%、按压升到统一档（深 13% / 浅 15%）。 */
private const val GEAR_BASE_ALPHA = 0.05f
private const val GEAR_PRESSED_ALPHA = 0.10f
/**
 * 按压终值统一档（模块 2/D2 + B2b 拆深浅，internal 供单测钉规格）：与顶栏图标/导航
 * tab **编译期同源** —— 直接引用皮肤层常量，两侧漂移会直接编译失败；深色 White 13% /
 * 浅色 Black 15%（实测对比度 ≈1.45 / ≈1.41）；常驻 5% 底保留（v4.14.0 定稿的层级设计）。
 */
internal const val GEAR_PRESSED_ALPHA_UNIFIED_DARK = PRESS_BG_ALPHA_V2_DARK
internal const val GEAR_PRESSED_ALPHA_UNIFIED_LIGHT = PRESS_BG_ALPHA_V2_LIGHT
/** 打开态底色（模块 2/S1）：`primary@0.18`，与底部导航选中态同语言。 */
internal const val GEAR_OPEN_BG_ALPHA = 0.18f

/** 齿轮白边 alpha：深色 White 18% / 浅色 Black 18%（颜色在 rippleFeedback 内按主题自适应）。 */
private const val GEAR_BORDER_ALPHA = 0.18f

/**
 * 四个生成页底部操作行共用的「节点参数」齿轮按钮（v5 从四页内联的
 * `OutlinedIconButton(56dp)` + `Spacer(8dp)` 抽出，C6）。
 *
 * v2 皮肤（AppSettings.isUiSkinV2Enabled，默认开）：56×56 **圆形**、底 `White 5%`
 * （浅色主题 Black 5%）、描边 `White 18%` 1dp、图标 `onSurfaceVariant`（U3：不比
 * 下拉/主按钮抢眼，不用纯白/纯黑）、无渐变无投影、**无点阵**（U1：点阵只留在
 * 生成主按钮）；按压 = 底色升到统一档（深色 White 13% / 浅色 Black 15%）+ scale 0.94。
 * 打开态（模块 2/S1）：底色替换为 `primary@0.18`、图标换 `primary`（与导航选中态
 * 同语言）；模块 2 安全阀关闭时打开态只旋转、无颜色变化。
 * 层级定位：齿轮 < 下拉 < 主按钮（三者不同重量）。
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
    // 模块 2 安全阀（组合时读一次）：开 = 按压终值统一 13% + 打开态 primary@0.18 底；
    // 关 = 改动前形态（按压 10%、打开只旋转无颜色变化）
    val pressUnified = remember(context) { AppSettings.isPressFeedbackUnified(context) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseColor = if (isDark) Color.White else Color.Black
    // S1 打开态：底色从常驻 5% **替换为** primary@0.18（不是叠加）、图标 tint 换 primary
    //（与导航选中态同语言）。颜色在组合期现取、不进 remember，切调色盘下一帧跟随；
    // 打开态恒 primary@0.18，按压不再升档（同导航选中态"选中恒 selectedBg"的语义）。
    val openSkin = pressUnified && open
    // 底色用"双色同色渐变"承载（rippleFeedback 的渐变层画在内容之下，无渐变观感）；
    // 按压时升档到统一档（深 13% / 浅 15%，模块 2/D2 + B2b），配合 scale 0.94（rippleFeedback 内部）。
    val gearBg = if (openSkin) {
        MaterialTheme.colorScheme.primary.copy(alpha = GEAR_OPEN_BG_ALPHA)
    } else {
        val targetAlpha = if (pressed) {
            if (pressUnified) {
                if (isDark) GEAR_PRESSED_ALPHA_UNIFIED_DARK else GEAR_PRESSED_ALPHA_UNIFIED_LIGHT
            } else {
                GEAR_PRESSED_ALPHA
            }
        } else {
            GEAR_BASE_ALPHA
        }
        baseColor.copy(alpha = targetAlpha)
    }

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .rippleFeedback(
                interactionSource = interactionSource,
                shape = CircleShape,
                // 白边 18%（颜色按主题自适应：深色 White / 浅色 Black），线宽随皮肤层走 1dp
                borderAlphaOverride = GEAR_BORDER_ALPHA,
                // 无渐变观感（纯色底）、无辉光无投影：层级最低的齿轮不发光
                showHalo = false,
                // U1 收敛：齿轮不带点阵（点阵只留在生成主按钮）
                showDots = false,
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
            // U3：图标降为 onSurfaceVariant —— 纯 onSurface（深色近白）在底部操作行里太跳，
            // 齿轮必须是三者中最轻的。打开态换 primary（S1：与导航选中态同语言）
            tint = if (openSkin) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.rotate(rotation)
        )
    }
}
