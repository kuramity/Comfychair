package sh.hnet.comfychair.ui.navigation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.feature.tools.PromptEnhanceImage
import sh.hnet.comfychair.feature.tools.PromptEnhanceScreen
import sh.hnet.comfychair.feature.tools.ToolsListScreen
import sh.hnet.comfychair.feature.tools.ToolsRoutes
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.prompt.SkillPromptRepository
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.TagLibraryViewModel
import sh.hnet.comfychair.taglibrary.ui.TagDetailScreen
import sh.hnet.comfychair.taglibrary.ui.TagLibraryScreen
import sh.hnet.comfychair.tools.PromptEnhanceGatewayImpl
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.screens.ModelIntroDetailScreen
import sh.hnet.comfychair.ui.screens.ModelIntroEditorScreen
import sh.hnet.comfychair.ui.screens.ModelIntroHiddenScreen
import sh.hnet.comfychair.ui.screens.ModelIntroScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.util.DebugLogger
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

            // ---------- 标签库（小工具二级页：工具列表 → 标签库） ----------
            // 不再占底栏；注册保留在这里，供 ToolsListScreen 的标签库卡片导航进来。
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

            // ---------- 小工具（底栏第 6 个 tab，:feature-tools 模块） ----------
            // 直接注册模块暴露的两个屏幕，不在这里嵌第二层 NavHost：
            // 返回栈、底栏 currentRoute 判定和外层导航图共用一套，省掉嵌套 NavHost 的坑。
            // 模块自带的 ToolsNavGraph 是给「小工具独立成容器」时用的入口，这里不用它。
            // 标签库是本 tab 的二级页：工具列表 → 标签库 → 标签详情，返回逐级退回。
            composable(MainRoute.SmallTools.route) {
                ToolsListScreen(
                    onOpenTagLibrary = {
                        navController.navigate(MainRoute.TagLibrary.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenPromptEnhance = {
                        navController.navigate(ToolsRoutes.PROMPT_ENHANCE) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            // 提示词增强（小工具二级页面，UI 等设计稿）：顶栏返回与系统返回键都退回列表页。
            //
            // 该屏幕在 :feature-tools 里，读不到 app 的 assets，也没有网络/相册依赖，
            // 所以下面这些全部由这里注入：
            //   gateway          → PromptEnhanceGatewayImpl（内部每次调用都取 ConnectionManager 当前 client，
            //                      避免重连换了 client 之后还握着旧实例）
            //   ruleFiles / 正文  → assets/prompts 下的技能规则文件（「不注入」不是文件，天然不会出现）
            //   text/image 工作流 → assets/prebuilt_workflows 的 25 / 24 两套模板原文
            //   onPickImage      → 系统相册（PickVisualMedia）；App 图库来源本轮未接
            //   vramUsageFlow    → ConnectionManager.vramUsageFraction（crystools 的 WebSocket 推送）
            composable(ToolsRoutes.PROMPT_ENHANCE) {
                val context = LocalContext.current
                val appContext = context.applicationContext ?: context

                val gateway = remember(appContext) { PromptEnhanceGatewayImpl(appContext) }
                // 空文件名的「（不注入）」项过滤掉：选它会走 ComfyUI 的自动选择分支并报错
                val ruleFiles = remember(appContext) {
                    SkillPromptRepository.list(appContext.assets)
                        .filter { it.fileName.isNotBlank() }
                        .map { it.displayName to it.fileName }
                }
                // 屏幕侧是同步回调（点「开始生成」时立即取正文），规则 md 只有几 KB
                val ruleFileContent: (String) -> String? = remember(appContext) {
                    { fileName: String ->
                        SkillPromptRepository.read(appContext.assets, fileName)
                    }
                }
                val textWorkflowJson = remember(appContext) {
                    readAssetText(appContext, TEXT_WORKFLOW_ASSET)
                }
                val imageWorkflowJson = remember(appContext) {
                    readAssetText(appContext, IMAGE_WORKFLOW_ASSET)
                }

                var pickedImage by remember { mutableStateOf<PromptEnhanceImage?>(null) }
                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        pickedImage = PromptEnhanceImage(
                            displayName = queryDisplayName(appContext, uri),
                            uri = uri.toString(),
                        )
                    }
                }

                PromptEnhanceScreen(
                    onBack = { navController.popBackStack() },
                    ruleFiles = ruleFiles,
                    ruleFileContent = ruleFileContent,
                    textWorkflowJson = textWorkflowJson,
                    imageWorkflowJson = imageWorkflowJson,
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    pickedImage = pickedImage,
                    gateway = gateway,
                    vramUsageFlow = ConnectionManager.vramUsageFraction,
                )
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

/** 提示词增强「文字→文字」模板（assets 原文，含 defaults / nodes）。 */
private const val TEXT_WORKFLOW_ASSET = "prebuilt_workflows/prebuilt_25_qwen21_skill_t2i.json"

/** 提示词增强「文字+图片→文字」模板（同上）。 */
private const val IMAGE_WORKFLOW_ASSET = "prebuilt_workflows/prebuilt_24_qwen21_skill_edit.json"

private const val TAG = "MainNavHost"

/**
 * 读 assets 文本（用于提示词增强的两份预置工作流）。
 *
 * 读失败返回空串：[sh.hnet.comfychair.feature.tools.PromptEnhanceScreen] 用「模板为空白」
 * 判定禁用生成按钮，所以宁可禁用也不要发一份缺 nodes 的工作流出去。
 */
private fun readAssetText(context: Context, assetPath: String): String = try {
    context.assets.open(assetPath).bufferedReader().use { it.readText() }
} catch (e: Exception) {
    DebugLogger.w(TAG, "读取 assets 失败: $assetPath (${e.message})")
    ""
}

/** 相册选图后取用户看得懂的文件名（内容提供者的显示名，拿不到时退回路径末段）。 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    val fallback = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "image"
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}
