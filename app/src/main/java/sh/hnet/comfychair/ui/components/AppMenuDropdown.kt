package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.ripple.RippleIconButton

/**
 * Reusable menu dropdown for navigation and Logout actions.
 * Used in TopAppBar across all screens.
 *
 * @param onNavigate First action (Settings or Generation)
 * @param onLogout Logout action
 * @param navigateLabel Label for the first action (defaults to Settings)
 * @param navigateIcon Icon for the first action (defaults to Settings icon)
 * @param useRippleFeedback 波纹点阵反馈开关（v4.9.x）：只有生成页顶栏打开；
 *   默认 false，设置页等旧调用点行为零变化。
 * @param iconSize 菜单图标尺寸（默认 24dp 保持旧观感；生成页顶栏传 22dp 对齐裸图标规格）
 */
@Composable
fun AppMenuDropdown(
    onNavigate: () -> Unit,
    onLogout: () -> Unit,
    navigateLabel: String = stringResource(R.string.action_settings),
    navigateIcon: ImageVector = Icons.Default.Settings,
    useRippleFeedback: Boolean = false,
    iconSize: Dp = 24.dp,
) {
    var showMenu by remember { mutableStateOf(false) }

    val menuIcon: @Composable () -> Unit = {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.content_description_menu),
            modifier = Modifier.size(iconSize)
        )
    }

    Box {
        if (useRippleFeedback) {
            RippleIconButton(onClick = { showMenu = true }, content = menuIcon)
        } else {
            IconButton(onClick = { showMenu = true }, content = menuIcon)
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(navigateLabel) },
                onClick = {
                    showMenu = false
                    onNavigate()
                },
                leadingIcon = {
                    Icon(navigateIcon, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_logout)) },
                onClick = {
                    showMenu = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                }
            )
        }
    }
}

/**
 * Menu dropdown for generation screens (Settings + Logout).
 *
 * [useRippleFeedback] 默认 false：本重载还被相册页等旧调用点复用，波纹反馈只在
 * 生成页顶栏显式打开（`GenerationTopBarActions` 里传 true），避免动到无关页面。
 */
@Composable
fun AppMenuDropdown(
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    useRippleFeedback: Boolean = false,
    iconSize: Dp = 24.dp
) {
    AppMenuDropdown(
        onNavigate = onSettings,
        onLogout = onLogout,
        navigateLabel = stringResource(R.string.action_settings),
        navigateIcon = Icons.Default.Settings,
        useRippleFeedback = useRippleFeedback,
        iconSize = iconSize
    )
}

/**
 * Menu dropdown for settings screens (Generation + Logout).
 */
@Composable
fun SettingsMenuDropdown(
    onGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    AppMenuDropdown(
        onNavigate = onGeneration,
        onLogout = onLogout,
        navigateLabel = stringResource(R.string.menu_generation),
        navigateIcon = Icons.Default.AutoAwesome
    )
}
