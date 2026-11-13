package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer

/**
 * Reusable scaffold for settings screens.
 * Provides consistent layout with TopAppBar, SettingsMenuDropdown, and scrollable content.
 *
 * @param title The title displayed in the TopAppBar
 * @param onNavigateToGeneration Callback when user navigates to generation screen
 * @param onLogout Callback when user logs out
 * @param scrollable Whether the content is scrollable (default true)
 * @param horizontalPadding Horizontal padding for content (default 16.dp)
 * @param onNavigateBack 传了就在顶栏左侧显示返回箭头。二级页面（不在底部导航里、没有
 *        自己的底栏入口）必须传，否则用户只能靠系统返回键退出，容易以为走进了死路。
 * @param content The composable content to display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenScaffold(
    title: String,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    scrollable: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                onNavigateBack?.let { back ->
                    IconButton(onClick = back) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                SettingsMenuDropdown(
                    onGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }
        )

        NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = horizontalPadding),
                content = content
            )
        }
    }
}
