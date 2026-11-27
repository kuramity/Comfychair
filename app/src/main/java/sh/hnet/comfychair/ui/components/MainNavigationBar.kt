package sh.hnet.comfychair.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.navigation.MODEL_INTRO_ROUTES
import sh.hnet.comfychair.navigation.SMALL_TOOLS_ROUTES
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_DARK_V2_LEGACY
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_LIGHT_V2_LEGACY
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_V2_DARK
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_ALPHA_V2_LIGHT

/**
 * Main navigation bar for the app with 6 destinations (left → right):
 * - Text-to-Image (icon button)
 * - Image-to-Image (icon button)
 * - Text-to-Video (icon button)
 * - Image-to-Video (icon button)
 * - Model info (book icon)
 * - Small tools (wrench icon, 图库 FAB 左边最后一个 tab)
 *
 * 标签库**不在**底栏：它收进「小工具」列表当二级页（路由 [MainRoute.TagLibrary] 仍保留，
 * 从工具列表点卡片进入，返回先回工具列表）。
 * FAB launches Gallery activity.
 * 模型介绍入口与生成页共用本容器导航图；介绍模块的列表、详情、隐藏页和编辑器
 * 都保持书图标选中态。
 *
 * Uses BottomAppBar with navigation buttons on left and FAB on right.
 */
@Composable
fun MainNavigationBar(
    navController: NavController,
    onNavigateToGallery: () -> Unit,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Observe gallery items to show latest thumbnail in FAB
    val galleryItems by GalleryRepository.getInstance().galleryItems.collectAsStateWithLifecycle()
    val latestItem = galleryItems.firstOrNull()
    // 「源已不存在」：持久标记在索引里，本次会话的 404 在 missingKeys 里，两者任一成立
    // 都只读本机缓存 —— 冷启动时 missingKeys 为空，只看它会让已标记条目再发一次 /view。
    val latestSourceMissing = latestItem?.let { item ->
        item.sourceMissing || MediaCache.isFileMissing(item.toCacheKey())
    } ?: false

    // Lazy load thumbnail for FAB - keep previous thumbnail until new one is ready
    val displayedThumbnail = rememberRetainedBitmap(
        cacheKey = latestItem?.toCacheKey(),
        isVideo = latestItem?.isVideo ?: false,
        subfolder = latestItem?.subfolder ?: "",
        type = latestItem?.type ?: "",
        // 底栏上是个几十 dp 的小图标，没有理由为它去拉原图
        thumbnail = true,
        // 源已不存在：只读本机缩略图，绝不发网络请求
        localOnly = latestSourceMissing
    )

    // 各 tab 的导航动作（起始页是**动态**的，见任一 tab 的 popUpTo 注释）
    fun navigateTo(route: String) {
        navController.navigate(route) {
            // 起始页是**动态**的（MainContainerActivity 按上次生成任务的
            // ownerId 决定），所以这里不能写死某个 tab 的 route：
            // 写死之后一旦起始页不是它，popUpTo 在回退栈里找不到目标就
            // **一个都不弹**，切 tab 反而会一层层累积历史（用户实测：
            // 文生图→图生图→文生视频，按两次返回只退到文生图）。
            // findStartDestination() 跟随真实起始页，栈深度恒定 ≤ 2：
            // 非起始 tab 上第 1 次返回回起始页，第 2 次退出到连接界面。
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BottomAppBar(
        actions = {
            // 左侧留白：原为 12dp（对齐页面内容 16dp - BottomAppBar 默认 4dp），
            // 底栏整体缩小后同步减半到 6dp
            Spacer(modifier = Modifier.width(6.dp))

            // 6 个 tab 包进一层 Row：BottomAppBar 的 actions 只给 RowScope，
            // FAB 仍然走 floatingActionButton 槽位，结构不变。
            //
            // 尺寸取舍（360dp 屏口径）：
            // - 视觉方块固定 34dp，不为宽度妥协；只有 <204dp 的非手机极端宽度才会缩（6×34）；
            // - 间距最多 6dp，宽度不够时先压间距（6 个 tab 比 7 个时更宽松，正常屏恒为 6dp）；
            // - 触控格 = 视觉方块 + 一段间距（相邻格首尾相接、没有点击死区）。
            // 本轮沿用 7 个 tab 那轮定下的 34dp / 圆角 10dp / 图标 20dp / 间距 6dp，
            // 6 个 tab 空出来的宽度先留白，不放大尺寸（避免与其它联调改动互相干扰）。
            // 高度单独满足 Material 的 48dp 最小高度。
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                // 极端窄才收缩视觉方块；正常宽度下恒为 34dp
                val tabSize = (maxWidth / 6).coerceAtMost(34.dp)
                // 间距最多 6dp：宽度不够时先吃间距，不缩 tab
                val tabGap = ((maxWidth - tabSize * 6) / 6).coerceIn(0.dp, 6.dp)
                val touchWidth = tabSize + tabGap
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute == MainRoute.TextToImage.route,
                        label = stringResource(R.string.nav_text_to_image),
                        onClick = { navigateTo(MainRoute.TextToImage.route) },
                    ) { tint ->
                        Icon(
                            painter = painterResource(R.drawable.text_to_image_24px),
                            contentDescription = stringResource(R.string.nav_text_to_image),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute == MainRoute.ImageToImage.route,
                        label = stringResource(R.string.nav_image_to_image),
                        onClick = { navigateTo(MainRoute.ImageToImage.route) },
                    ) { tint ->
                        Icon(
                            painter = painterResource(R.drawable.image_to_image_24px),
                            contentDescription = stringResource(R.string.nav_image_to_image),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute == MainRoute.TextToVideo.route,
                        label = stringResource(R.string.nav_text_to_video),
                        onClick = { navigateTo(MainRoute.TextToVideo.route) },
                    ) { tint ->
                        Icon(
                            painter = painterResource(R.drawable.text_to_video_24px),
                            contentDescription = stringResource(R.string.nav_text_to_video),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute == MainRoute.ImageToVideo.route,
                        label = stringResource(R.string.nav_image_to_video),
                        onClick = { navigateTo(MainRoute.ImageToVideo.route) },
                    ) { tint ->
                        Icon(
                            painter = painterResource(R.drawable.image_to_video_24px),
                            contentDescription = stringResource(R.string.nav_image_to_video),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 模型介绍（小工具左边、图生视频右边）：走本容器导航图，
                    // 列表、详情、隐藏页和编辑器都保持同一书图标选中态。
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute in MODEL_INTRO_ROUTES,
                        label = stringResource(R.string.nav_model_intro),
                        onClick = { navigateTo(MainRoute.ModelIntro.route) },
                    ) { tint ->
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = stringResource(R.string.nav_model_intro),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 小工具（底栏第 6 个 tab，图库 FAB 左边最后一个）：页面在 :feature-tools 模块；
                    // 提示词增强是二级页，用 SMALL_TOOLS_ROUTES 维持本 tab 选中态（与模型介绍同一口径）
                    NavBarTab(
                        tabSize = tabSize,
                        touchWidth = touchWidth,
                        selected = currentRoute in SMALL_TOOLS_ROUTES,
                        label = stringResource(R.string.nav_small_tools),
                        onClick = { navigateTo(MainRoute.SmallTools.route) },
                    ) { tint ->
                        Icon(
                            Icons.Outlined.Build,
                            contentDescription = stringResource(R.string.nav_small_tools),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToGallery,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                if (displayedThumbnail != null) {
                    Image(
                        bitmap = displayedThumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.nav_gallery),
                        // 缩略图圆角 12：FAB 里的 48dp 预览图单独一档，不跟随 tab 选中底
                        // （tab 选中底现在是 10dp）；原注释「与 tab 选中态圆角一致」已不成立
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Filled.Collections,
                        contentDescription = stringResource(R.string.nav_gallery),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    )
}

/**
 * 底部导航栏单个 tab（我推荐项，收敛原先 5 处 if/else 的选中/未选中两套写法）。
 *
 * - 选中态：[tabSize] 见方、圆角 [cornerRadius]、`primary@18%` 底 + 图标 `primary`
 *   （替代原 FilledIconButton 的圆形 + secondaryContainer —— 与预览区圆角/顶栏圆角同一套圆角语言）；
 * - 未选中态：图标 `onSurfaceVariant`，点击时自带"轻底色"按压反馈
 *   （统一档：深色 White 13% / 浅色 Black 15%，与顶栏裸图标/齿轮按压终值同源常量），
 *   替代 M3 默认 ripple。
 *
 * 原实现选中态用 FilledIconButton、未选中态用 IconButton，两套组件的形状/底色语义
 * 互相对不上；统一为一个 Box + drawBehind 后，两种状态共用同一几何。
 * 默认值保持 34dp / 圆角 10dp（7 个 tab 那一轮从 40dp / 12dp 收紧而来），图标由调用方
 * 显式 20dp；现在底栏是 6 个 tab，空间更宽松，但本轮不放大尺寸。
 *
 * 触控尺寸：视觉方块是 [tabSize]，可点击容器是「[touchWidth] 宽 × [touchTargetHeight] 高」。
 * [touchWidth] 由底栏算成「视觉方块 + 一段间距」—— 相邻 tab 的触控格首尾相接、没有死区；
 * 6 个 tab 与图库 FAB 同排时触控宽仍按公式走（正常屏 40dp），高度单独满足 Material 的 48dp 下限。
 *
 * @param selected 当前 tab 是否选中（选中态不可点，与原 FilledIconButton(onClick={}) 等效）
 * @param label 无障碍文案
 * @param onClick 未选中时的导航动作
 * @param tabSize 视觉方块的边长（默认 34dp，由底栏按可用宽度算好后传入；正常屏恒为 34dp）
 * @param touchWidth 可点击区域宽度（默认 34dp；底栏传入 视觉方块 + 间距 的格宽）
 * @param cornerRadius 选中底/按压底的圆角（默认 10dp）
 * @param touchTargetHeight 可点击区域高度（默认 48dp，Material 最小触控尺寸）
 * @param icon 图标内容，参数为按选中态解析出的 tint
 */
@Composable
private fun NavBarTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    tabSize: Dp = 34.dp,
    touchWidth: Dp = 34.dp,
    cornerRadius: Dp = 10.dp,
    touchTargetHeight: Dp = 48.dp,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 模块 2 安全阀（组合时读一次）：开 = 按压终值统一档（深 13% / 浅 15%，与顶栏图标/齿轮同源常量）
    val context = LocalContext.current
    val pressUnified = remember(context) { AppSettings.isPressFeedbackUnified(context) }
    // 深浅判断与皮肤层同口径：按页面底色亮度（不用 isSystemInDarkTheme，App 有独立主题模式）
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 未选中按压反馈：轻底色动画过渡，松手淡回透明；选中态不可点，恒透明。
    // 统一档（B2b 拆深浅，与皮肤层注释同口径）：深色 White 13% ≈1.45:1 /
    // 浅色 Black 15% ≈1.41:1（实测）；开关关闭回 8%/6% 旧档
    val pressBg by animateColorAsState(
        targetValue = if (pressed && !selected) {
            val pressAlpha = if (pressUnified) {
                if (isDark) PRESS_BG_ALPHA_V2_DARK else PRESS_BG_ALPHA_V2_LIGHT
            } else if (isDark) {
                PRESS_BG_ALPHA_DARK_V2_LEGACY
            } else {
                PRESS_BG_ALPHA_LIGHT_V2_LEGACY
            }
            (if (isDark) Color.White else Color.Black).copy(alpha = pressAlpha)
        } else {
            Color.Transparent
        },
        label = "navTabPressBg",
    )
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 选中底色在组合期解析（draw 阶段拿不到 MaterialTheme，颜色不进 remember、
    // 换调色盘后下一帧跟随）
    val selectedBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    Box(
        modifier = Modifier
            .width(touchWidth)
            .height(touchTargetHeight)
            .clickable(
                interactionSource = interactionSource,
                // 按压反馈由内层轻底色承担，不再叠加 M3 ripple（双层反馈会互相打架）
                indication = null,
                onClick = onClick,
                enabled = !selected,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 视觉选中底/按压底仍限制在 tabSize 的圆角方块内；外层只负责触控区
        Box(
            modifier = Modifier
                .size(tabSize)
                .clip(RoundedCornerShape(cornerRadius))
                .drawBehind {
                    drawRoundRect(
                        color = if (selected) selectedBg else pressBg,
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            icon(iconTint)
        }
    }
}
