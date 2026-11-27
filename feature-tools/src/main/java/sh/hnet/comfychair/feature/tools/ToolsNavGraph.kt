package sh.hnet.comfychair.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

/**
 * 小工具模块内的路由常量。
 *
 * app 侧的 MainNavHost 直接注册这两个屏幕时也引用这里，避免两处各写一遍字符串。
 */
object ToolsRoutes {
    /** 小工具列表（app 底栏 SmallTools tab 的落点内容）。 */
    const val LIST = "tools/list"

    /**
     * 标签库（小工具二级页面）。
     *
     * 字符串与 app 侧 `MainRoute.TagLibrary.route` 保持一致（"tag_library"）：
     * 标签库屏幕在 app 模块，模块内引用不到 MainRoute，所以这里另开一个常量；
     * app 的 MainNavHost 直接用 `MainRoute.TagLibrary.route` 即可，两边落到同一路由。
     */
    const val TAG_LIBRARY = "tag_library"

    /** 提示词增强（小工具二级页面）。 */
    const val PROMPT_ENHANCE = "tools/prompt_enhance"
}

/**
 * 小工具模块自带的导航图：模块将来单独成容器（独立 Activity / 独立 Scaffold）时直接挂它。
 *
 * 当前 app 内**没有**使用这个函数 —— MainNavHost 直接注册 [ToolsListScreen] 与
 * [PromptEnhanceScreen] 两个 composable，避免在主容器里再嵌一层 NavHost
 * （嵌套 NavHost 会让返回栈/底栏路由判定各管一套，容易踩坑）。
 */
@Composable
fun ToolsNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ToolsRoutes.LIST,
        modifier = modifier,
    ) {
        composable(ToolsRoutes.LIST) {
            ToolsListScreen(
                onOpenTagLibrary = { navController.navigate(ToolsRoutes.TAG_LIBRARY) },
                onOpenPromptEnhance = { navController.navigate(ToolsRoutes.PROMPT_ENHANCE) },
            )
        }
        composable(ToolsRoutes.TAG_LIBRARY) {
            // 标签库屏幕在 app 模块（sh.hnet.comfychair.taglibrary.ui），本模块引用不到；
            // 这里给一条明确说明，避免导航到未注册的 destination 直接抛异常。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.tools_tag_library_requires_main),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { navController.popBackStack() }) {
                    Text(stringResource(R.string.tools_back))
                }
            }
        }
        composable(ToolsRoutes.PROMPT_ENHANCE) {
            PromptEnhanceScreen(onBack = { navController.popBackStack() })
        }
    }
}