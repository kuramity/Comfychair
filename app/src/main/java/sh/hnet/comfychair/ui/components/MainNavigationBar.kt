package sh.hnet.comfychair.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository

/**
 * Main navigation bar for the app with 4 destinations:
 * - Text-to-Image (icon button)
 * - Image-to-Image (icon button)
 * - Text-to-Video (icon button)
 * - Image-to-Video (icon button)
 *
 * FAB launches Gallery activity.
 *
 * Uses BottomAppBar with navigation buttons on left and FAB on right.
 */
@Composable
fun MainNavigationBar(
    navController: NavController,
    onNavigateToGallery: () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Observe gallery items to show latest thumbnail in FAB
    val galleryItems by GalleryRepository.getInstance().galleryItems.collectAsState()
    val latestItem = galleryItems.firstOrNull()

    // Lazy load thumbnail for FAB - keep previous thumbnail until new one is ready
    val displayedThumbnail = rememberRetainedBitmap(
        cacheKey = latestItem?.toCacheKey(),
        isVideo = latestItem?.isVideo ?: false,
        subfolder = latestItem?.subfolder ?: "",
        type = latestItem?.type ?: "",
        // 底栏上是个几十 dp 的小图标，没有理由为它去拉原图
        thumbnail = true
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
            // Add left padding to align with screen content (16dp - default 4dp = 12dp extra)
            Spacer(modifier = Modifier.width(12.dp))

            NavBarTab(
                selected = currentRoute == MainRoute.TextToImage.route,
                label = stringResource(R.string.nav_text_to_image),
                onClick = { navigateTo(MainRoute.TextToImage.route) },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.text_to_image_24px),
                    contentDescription = stringResource(R.string.nav_text_to_image),
                    tint = tint,
                )
            }
            NavBarTab(
                selected = currentRoute == MainRoute.ImageToImage.route,
                label = stringResource(R.string.nav_image_to_image),
                onClick = { navigateTo(MainRoute.ImageToImage.route) },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.image_to_image_24px),
                    contentDescription = stringResource(R.string.nav_image_to_image),
                    tint = tint,
                )
            }
            NavBarTab(
                selected = currentRoute == MainRoute.TextToVideo.route,
                label = stringResource(R.string.nav_text_to_video),
                onClick = { navigateTo(MainRoute.TextToVideo.route) },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.text_to_video_24px),
                    contentDescription = stringResource(R.string.nav_text_to_video),
                    tint = tint,
                )
            }
            NavBarTab(
                selected = currentRoute == MainRoute.ImageToVideo.route,
                label = stringResource(R.string.nav_image_to_video),
                onClick = { navigateTo(MainRoute.ImageToVideo.route) },
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.image_to_video_24px),
                    contentDescription = stringResource(R.string.nav_image_to_video),
                    tint = tint,
                )
            }
            // Tag Library（标签库，独立 Tab）
            NavBarTab(
                selected = currentRoute == MainRoute.TagLibrary.route,
                label = stringResource(R.string.nav_tag_library),
                onClick = { navigateTo(MainRoute.TagLibrary.route) },
            ) { tint ->
                Icon(
                    Icons.Default.Tag,
                    contentDescription = stringResource(R.string.nav_tag_library),
                    tint = tint,
                )
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
                        // 缩略图圆角 12（我推荐项：与 tab 选中态圆角一致，原 shapes.medium=16）
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
 * - 选中态：40×40、圆角 12、`primary@18%` 底 + 图标 `primary`（替代原 FilledIconButton
 *   的圆形 + secondaryContainer —— 与预览区圆角/顶栏圆角 12 同一套圆角语言）；
 * - 未选中态：图标 `onSurfaceVariant`，点击时自带"轻底色"按压反馈
 *   （深色 White 8% / 浅色 Black 6%，与顶栏裸图标按压底色同规格），替代 M3 默认 ripple。
 *
 * 原实现选中态用 FilledIconButton、未选中态用 IconButton，两套组件的形状/底色语义
 * 互相对不上；统一为一个 Box + drawBehind 后，两种状态共用同一几何（40×40 圆角 12）。
 *
 * @param selected 当前 tab 是否选中（选中态不可点，与原 FilledIconButton(onClick={}) 等效）
 * @param label 无障碍文案
 * @param onClick 未选中时的导航动作
 * @param icon 图标内容，参数为按选中态解析出的 tint
 */
@Composable
private fun NavBarTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 深浅判断与皮肤层同口径：按页面底色亮度（不用 isSystemInDarkTheme，App 有独立主题模式）
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 未选中按压反馈：轻底色动画过渡，松手淡回透明；选中态不可点，恒透明
    val pressBg by animateColorAsState(
        targetValue = if (pressed && !selected) {
            (if (isDark) Color.White else Color.Black).copy(alpha = if (isDark) 0.08f else 0.06f)
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
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(
                    color = if (selected) selectedBg else pressBg,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = interactionSource,
                // 按压反馈由上面的轻底色承担，不再叠加 M3 ripple（双层反馈会互相打架）
                indication = null,
                onClick = onClick,
                enabled = !selected,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(iconTint)
    }
}
