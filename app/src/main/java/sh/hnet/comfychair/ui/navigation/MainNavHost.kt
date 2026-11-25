package sh.hnet.comfychair.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.TagLibraryViewModel
import sh.hnet.comfychair.taglibrary.ui.TagDetailScreen
import sh.hnet.comfychair.taglibrary.ui.TagLibraryScreen
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.screens.ModelIntroDetailScreen
import sh.hnet.comfychair.ui.screens.ModelIntroEditorScreen
import sh.hnet.comfychair.ui.screens.ModelIntroHiddenScreen
import sh.hnet.comfychair.ui.screens.ModelIntroScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel

/**
 * Main navigation host that contains all the generation screens.
 * Uses a Scaffold with bottom navigation bar.
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onLogout: () -> Unit,
    startDestination: String = MainRoute.TextToImage.route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    // 标签库 ViewModel 提升到 NavHost 外层，使主页面与详情页共享同一实例
    // （详情页添加标签需要更新构建器记录，故必须共用状态）
    val tagLibraryViewModel: TagLibraryViewModel = viewModel()

    // 跟随导航路由更新标签库的同步目标。
    // 注意：不能用 generationState.ownerId —— 它只在生成任务运行时才有值，
    // 空闲时为 null，会导致标签库永远找不到可写入的页面。
    // 这里按当前所在路由判断；处于标签库/详情页时保持上一个生成页面不变。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    LaunchedEffect(currentRoute) {
        val ownerId = when (currentRoute) {
            MainRoute.TextToImage.route -> TextToImageViewModel.OWNER_ID
            MainRoute.ImageToImage.route -> ImageToImageViewModel.OWNER_ID
            MainRoute.TextToVideo.route -> TextToVideoViewModel.OWNER_ID
            MainRoute.ImageToVideo.route -> ImageToVideoViewModel.OWNER_ID
            else -> null
        }
        if (ownerId != null) {
            PromptBridge.setActive(ownerId)
        }
    }

    Scaffold(
        bottomBar = {
            MainNavigationBar(
                navController = navController,
                onNavigateToGallery = onNavigateToGallery
            )
        },
        modifier = modifier.imePadding()
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(MainRoute.TextToImage.route) {
                val textToImageViewModel: TextToImageViewModel = viewModel()
                TextToImageScreen(
                    generationViewModel = generationViewModel,
                    textToImageViewModel = textToImageViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.ImageToImage.route) {
                ImageToImageScreen(
                    generationViewModel = generationViewModel,
                    imageToImageViewModel = imageToImageViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.TextToVideo.route) {
                val textToVideoViewModel: TextToVideoViewModel = viewModel()
                TextToVideoScreen(
                    generationViewModel = generationViewModel,
                    textToVideoViewModel = textToVideoViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.ImageToVideo.route) {
                ImageToVideoScreen(
                    generationViewModel = generationViewModel,
                    imageToVideoViewModel = imageToVideoViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            // ---------- 标签库（独立 Tab） ----------
            composable(MainRoute.TagLibrary.route) {
                TagLibraryScreen(
                    viewModel = tagLibraryViewModel,
                    onTagClick = { tagName ->
                        navController.navigate(MainRoute.TagDetail.createRoute(tagName))
                    }
                )
            }

            // ---------- 标签详情（二级页面） ----------
            composable(
                route = MainRoute.TagDetail.route,
                arguments = listOf(
                    navArgument(MainRoute.TagDetail.ARG_TAG_NAME) { type = NavType.StringType }
                )
            ) { entry ->
                val tagName = entry.arguments
                    ?.getString(MainRoute.TagDetail.ARG_TAG_NAME)
                    .orEmpty()
                TagDetailScreen(
                    tagName = tagName,
                    onBack = { navController.popBackStack() },
                    onAddPositive = {
                        tagLibraryViewModel.addTag(PromptBridge.Channel.POSITIVE, it)
                    },
                    onAddNegative = {
                        tagLibraryViewModel.addTag(PromptBridge.Channel.NEGATIVE, it)
                    }
                )
            }

            // ---------- 模型介绍（与生成页共用主容器导航图） ----------
            composable(MainRoute.ModelIntro.route) {
                ModelIntroScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { intro ->
                        navController.navigate(MainRoute.ModelIntroDetail.createRoute(intro.file))
                    },
                    onOpenHidden = {
                        navController.navigate(MainRoute.ModelIntroHidden.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenEditor = {
                        // 顶栏「＋」= 新建模式（不带 file 参数）
                        navController.navigate(MainRoute.ModelIntroEditor.ROUTE_NEW)
                    },
                    onEditEntry = { intro ->
                        navController.navigate(MainRoute.ModelIntroEditor.createRoute(intro.file))
                    }
                )
            }

            // 已卸载的介绍（v4.21 二级页面）：逐条恢复（只去掉覆盖层里的隐藏标记）
            composable(MainRoute.ModelIntroHidden.route) {
                ModelIntroHiddenScreen(onBack = { navController.popBackStack() })
            }

            // 模型介绍详情页（v4.17 二级页面）：转场只做淡入淡出（200/150ms），不做 slide
            composable(
                MainRoute.ModelIntroDetail.route,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(150)) },
            ) { backStackEntry ->
                val file = backStackEntry.arguments
                    ?.getString(MainRoute.ModelIntroDetail.ARG_FILE).orEmpty()
                ModelIntroDetailScreen(file = file, onBack = { navController.popBackStack() })
            }

            // 介绍文档编辑器（v4.21）：file 缺省 = 新建（先选模板），有值 = 编辑该条目。
            // 转场与详情页同一套淡入淡出（编辑器同样是详情页级别的二级页面）。
            composable(
                MainRoute.ModelIntroEditor.route,
                arguments = listOf(
                    navArgument(MainRoute.ModelIntroEditor.ARG_FILE) { defaultValue = "" },
                ),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(150)) },
            ) { backStackEntry ->
                val file = backStackEntry.arguments?.getString(MainRoute.ModelIntroEditor.ARG_FILE)
                    ?.takeIf { it.isNotBlank() }
                ModelIntroEditorScreen(file = file, onBack = { navController.popBackStack() })
            }
        }
    }
}
