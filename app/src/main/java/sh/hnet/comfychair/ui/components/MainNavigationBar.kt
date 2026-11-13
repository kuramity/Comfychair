package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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

    BottomAppBar(
        actions = {
            // Add left padding to align with screen content (16dp - default 4dp = 12dp extra)
            Spacer(modifier = Modifier.width(12.dp))

            // Text to Image
            if (currentRoute == MainRoute.TextToImage.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.text_to_image_24px),
                        contentDescription = stringResource(R.string.nav_text_to_image)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToImage.route) {
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
                }) {
                    Icon(
                        painter = painterResource(R.drawable.text_to_image_24px),
                        contentDescription = stringResource(R.string.nav_text_to_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Image to Image
            if (currentRoute == MainRoute.ImageToImage.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.image_to_image_24px),
                        contentDescription = stringResource(R.string.nav_image_to_image)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.ImageToImage.route) {
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
                }) {
                    Icon(
                        painter = painterResource(R.drawable.image_to_image_24px),
                        contentDescription = stringResource(R.string.nav_image_to_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Text to Video
            if (currentRoute == MainRoute.TextToVideo.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.text_to_video_24px),
                        contentDescription = stringResource(R.string.nav_text_to_video)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToVideo.route) {
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
                }) {
                    Icon(
                        painter = painterResource(R.drawable.text_to_video_24px),
                        contentDescription = stringResource(R.string.nav_text_to_video),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Image to Video
            if (currentRoute == MainRoute.ImageToVideo.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.image_to_video_24px),
                        contentDescription = stringResource(R.string.nav_image_to_video)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.ImageToVideo.route) {
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
                }) {
                    Icon(
                        painter = painterResource(R.drawable.image_to_video_24px),
                        contentDescription = stringResource(R.string.nav_image_to_video),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Tag Library（标签库，独立 Tab）
            if (currentRoute == MainRoute.TagLibrary.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Tag,
                        contentDescription = stringResource(R.string.nav_tag_library)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TagLibrary.route) {
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
                }) {
                    Icon(
                        Icons.Default.Tag,
                        contentDescription = stringResource(R.string.nav_tag_library),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium),
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
