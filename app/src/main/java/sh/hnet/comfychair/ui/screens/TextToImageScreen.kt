package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.model.ThreeDVisibility
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.GenerationTopBarActions
import sh.hnet.comfychair.ui.components.GenerationTopBarTitle
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.previewSurfaceBackground
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.SkillTextResultButton
import sh.hnet.comfychair.ui.components.shared.SkillTextResultDialog
import sh.hnet.comfychair.ui.components.shared.FreeMemoryButton
import sh.hnet.comfychair.ui.components.shared.SettingsGearButton
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.TextToImageEvent
import sh.hnet.comfychair.viewmodel.TextToImageViewModel

/**
 * Text-to-Image generation screen
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextToImageScreen(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    // Prompt preset ViewModel
    val presetViewModel: PromptPresetViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Initialize ViewModels
    LaunchedEffect(Unit) {
        val client = generationViewModel.getClient()
        if (client != null) {
            textToImageViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.TEXT_TO_IMAGE)
    }

    // Refresh presets when screen resumes (catches external changes from Media Viewer)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presetViewModel.refreshPresets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by textToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()

    // 3D 产物（文生3D）。入口的显隐只看工作流类型（`capabilities.is3DWorkflow`），
    // 与有没有生成结果无关 —— 详见 ThreeDResultButton 的 KDoc。
    //
    // 但**传给按钮的 result 必须按归属过滤**（2026-11-10 修）：ThreeDResultHolder 是
    // 全局单例，图生图页生成的 glb 在这里同样读得到。不滤的话，文生图页选中 TTI_3D 流
    // 点开顶部按钮，看到的是**图生3D 那个流**的模型（也可反过来：本页还没出结果时，
    // 点开直接显示别人的结果）。判据与图生图页一致：结果必须属于"当前选中的流"。
    val threeDResult by ThreeDResultHolder.result.collectAsState()
    val currentWorkflowId = uiState.selectedWorkflowId
    var boundWorkflowId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(threeDResult?.filename) {
        boundWorkflowId = if (threeDResult == null) null else currentWorkflowId
    }
    val visible3D = ThreeDVisibility.visible(
        result = threeDResult,
        is3DWorkflow = uiState.capabilities.is3DWorkflow,
        boundWorkflowId = boundWorkflowId,
        currentWorkflowId = currentWorkflowId
    )

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == TextToImageViewModel.OWNER_ID

    // 离线置灰由 GenerationButton 自己从连接状态派生（强制离线与自动离线一视同仁）
    var spellCheckEnabled by remember { mutableStateOf(AppSettings.isPromptSpellCheckEnabled(context)) }
    var promptExpandEnabled by remember { mutableStateOf(AppSettings.isPromptExpandEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                spellCheckEnabled = AppSettings.isPromptSpellCheckEnabled(context)
                promptExpandEnabled = AppSettings.isPromptExpandEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val positivePromptTransformation = rememberSpellCheckVisualTransformation(
        text = uiState.positivePrompt,
        enabled = spellCheckEnabled
    )
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    var prevImeHeight by remember { mutableStateOf(imeHeight) }
    SideEffect {
        // S2Probe（v4.16.7 取证）：键盘高度变化时间线（弹起/收起各一条，含前后值）。
        // 与 focus / onClick 日志的时间戳对照，还原"收键盘 → 布局位移 → 点击"时序。
        if (BuildConfig.DEBUG && imeHeight != prevImeHeight) {
            Log.d(
                "S2Probe",
                "ime ${prevImeHeight}px->${imeHeight}px ts=${SystemClock.uptimeMillis()}"
            )
        }
        prevImeHeight = imeHeight
    }
    var promptFocused by remember { mutableStateOf(false) }
    val expandPrompt = promptExpandEnabled && promptFocused && imeHeight > 0 && imeHeight >= prevImeHeight

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            textToImageViewModel.fetchModels()
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        textToImageViewModel.startListening(generationViewModel)
        onDispose {
            textToImageViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == TextToImageViewModel.OWNER_ID && promptId != null) {
            textToImageViewModel.clearPreviewForExecution(promptId)
            textToImageViewModel.startListening(generationViewModel)
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        textToImageViewModel.events.collect { event ->
            when (event) {
                is TextToImageEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is TextToImageEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Preset event handling
    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> {
                    textToImageViewModel.onPositivePromptChange(event.prompt)
                    // 预设里带了负面才写回。空负面既可能是"这条预设本来就没有负面"
                    // （有些工作流不需要），也可能是 v0.9.3 之前存的预设（那时还没有这个字段）——
                    // 两种情况都不该把用户当前手写的负面无声清掉
                    if (event.negativePrompt.isNotBlank()) {
                        textToImageViewModel.onNegativePromptChange(event.negativePrompt)
                    }
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    textToImageViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // UI composition
    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with save/share actions (outside content Box)
        TopAppBar(
            // v4.16.6（Q6）：四屏标题统一走 GenerationTopBarTitle —— 单行硬约束 +
            // 窄屏自动降字号（顶栏加 3D 入口后窄屏会把标题挤成两行）
            title = {
                GenerationTopBarTitle(text = stringResource(R.string.nav_text_to_image))
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // v4.9.0：四个模式共用同一套顶栏图标组（四段式：模式入口→输入→输出→全局）
                // v5：保存/分享常驻（修复"出图后出现、再生成闪没"），无结果时按钮禁用置灰
                GenerationTopBarActions(
                    is3DWorkflow = uiState.capabilities.is3DWorkflow,
                    // 用 visible3D（已按归属过滤），不是全局 threeDResult
                    threeDResult = visible3D,
                    hasResult = uiState.previewBitmap != null,
                    // saveToGallery/getShareIntent 内部对空预览位自兜底（onResult(false)/null），
                    // 且按钮仅在 hasResult 时可点，恒定传回调不改变原行为
                    onSave = {
                        textToImageViewModel.saveToGallery { success ->
                            val messageRes = if (success) R.string.msg_image_saved_to_gallery else R.string.error_save_image
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = {
                        textToImageViewModel.getShareIntent()?.let { intent ->
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_image)))
                        }
                    },
                    onSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        )

        // Progress indicator - below app bar, only show if THIS screen's job is executing
        if (isThisScreenExecuting) {
            GenerationProgressBar(
                progress = { generationState.progress },
                maxProgress = { generationState.maxProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Image Preview Area — collapses when typing in the prompt field
        AnimatedVisibility(
            visible = !expandPrompt,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(150)),
            exit = ExitTransition.None
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 150.dp)
                    // 我推荐项：直角纯色 → 圆角 16 + 160° 渐变底（图片内容不受影响，见 helper 注释）
                    .previewSurfaceBackground()
                    .clickable(enabled = uiState.previewBitmap != null && !isThisScreenExecuting) {
                        // Launch MediaViewer for single image
                        uiState.previewBitmap?.let { bitmap ->
                            val intent = MediaViewerActivity.createSingleImageIntent(
                                context = context,
                                bitmap = bitmap,
                                hostname = generationViewModel.getHostname(),
                                port = generationViewModel.getPort(),
                                filename = uiState.currentImageFilename,
                                subfolder = uiState.currentImageSubfolder,
                                type = uiState.currentImageType
                            )
                            context.startActivity(intent)
                            // 与图库页一致：打开时放大淡入，关闭时的缩小淡出在 MediaViewerActivity.finish() 里
                            (context as? Activity)?.overridePendingTransition(
                                R.anim.viewer_fade_in_grow, R.anim.gallery_stay
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (uiState.previewBitmap != null) {
                    Image(
                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.content_description_generated_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Placeholder - app logo
                    Image(
                        painter = painterResource(R.drawable.ic_comfychair_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // v4.9.0：提示词结果入口从底部操作行迁到这里（输入框上方、左对齐），
        // 不再和生成按钮、齿轮挤在同一行；没有隐藏结果时按钮不渲染、不占位。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            SkillTextResultButton(workflowName = uiState.selectedWorkflow)
        }

        // Prompt Input — expands to fill screen above keyboard when focused
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = {
                textToImageViewModel.onPositivePromptChange(it)
                presetViewModel.clearActivePreset()
            },
            label = { Text(stringResource(R.string.hint_prompt)) },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandPrompt) Modifier.weight(1f) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged {
                    // S2Probe（v4.16.7 取证）：输入框焦点变化（onClick 到达与否的旁证：
                    // 无效击时焦点没被清除，说明 onClick 整个没执行）
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "S2Probe",
                            "focus=${it.isFocused} ts=${SystemClock.uptimeMillis()}"
                        )
                    }
                    promptFocused = it.isFocused
                },
            minLines = 2,
            maxLines = if (expandPrompt) Int.MAX_VALUE else 4,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = spellCheckEnabled),
            visualTransformation = positivePromptTransformation,
            leadingIcon = {
                PromptPresetDropdown(
                    favorites = presetUiState.favorites,
                    activePresetId = presetUiState.activePresetId,
                    currentPromptIsEmpty = uiState.positivePrompt.isEmpty(),
                    onPresetSelected = { presetViewModel.onPresetSelected(it) },
                    onOpenLibrary = { presetViewModel.showLibrary() },
                    onSaveCurrentPrompt = {
                        presetViewModel.showSaveDialog(uiState.positivePrompt, uiState.negativePrompt)
                    },
                    onResetPrompt = { presetViewModel.resetPrompt() }
                )
            },
            trailingIcon = {
                // 锁定按钮放在清空按钮**左侧**：锁是"持续生效的状态"，清空是"一次性动作"，状态靠里更顺手
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { textToImageViewModel.onPromptLockToggle() }) {
                        Icon(
                            imageVector = if (uiState.positivePromptLocked) {
                                Icons.Default.Lock
                            } else {
                                Icons.Default.LockOpen
                            },
                            contentDescription = stringResource(
                                if (uiState.positivePromptLocked) {
                                    R.string.content_description_prompt_unlock
                                } else {
                                    R.string.content_description_prompt_lock
                                }
                            ),
                            // 锁定时用主题主色：这是"非默认状态"，需要一眼看出为什么切工作流提示词不变
                            tint = if (uiState.positivePromptLocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            }
                        )
                    }
                    if (uiState.positivePrompt.isNotEmpty()) {
                        IconButton(onClick = { textToImageViewModel.onPositivePromptChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
                        }
                    }
                }
            }
        )

        // Generate and Options buttons
        // U3：三件套（主按钮组/齿轮）等高 56dp，显式声明垂直居中保持视觉对齐
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GenerationButton(
                // v4.8.2（模块四）：只算本模式自己的活跃任务，避免四个模式一起显示队列数
                queueSize = queueState.activeCountFor(TextToImageViewModel.OWNER_ID),
                isExecuting = queueState.isExecuting,
                // v4.6.1：只有本页面自己的任务才允许取消，避免跨模式误中断
                canCancelCurrent = queueState.executingOwnerId == TextToImageViewModel.OWNER_ID ||
                    queueState.ownJobs.values.any { it.ownerId == TextToImageViewModel.OWNER_ID },
                // 内嵌提示词的工作流不要求填写（是否必填由占位符决定）
                isEnabled = !uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank(),
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    // S2Probe（v4.16.7 取证）：onGenerate 确实被调（区分"onClick 到达但
                    // 校验失败"与"onClick 根本没到达"两条路径）
                    if (BuildConfig.DEBUG) {
                        Log.d("S2Probe", "onGenerate entered ts=${SystemClock.uptimeMillis()}")
                    }
                    if (textToImageViewModel.hasValidConfiguration()) {
                        val workflowJson = textToImageViewModel.prepareWorkflowJson()
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "S2Probe",
                                "onGenerate valid=true workflowJson=${workflowJson != null} " +
                                    "ts=${SystemClock.uptimeMillis()}"
                            )
                        }
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                TextToImageViewModel.OWNER_ID
                            ) { success, _, errorMessage ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        errorMessage ?: context.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_failed_load_workflow),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "S2Probe",
                                "onGenerate valid=false ts=${SystemClock.uptimeMillis()}"
                            )
                        }
                    }
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    if (textToImageViewModel.hasValidConfiguration()) {
                        val workflowJson = textToImageViewModel.prepareWorkflowJson()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                TextToImageViewModel.OWNER_ID,
                                front = true
                            ) { success, _, errorMessage ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        errorMessage ?: context.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_failed_load_workflow),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onClearQueue = {
                    // v4.8.3：清空队列 = 先清排队再中断正在跑的，并同步本地计数
                    generationViewModel.clearQueueAndStopCurrent { success ->
                        val messageRes = if (success) R.string.msg_queue_cleared_success
                                       else R.string.error_queue_clear
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // 文生3D 的入口已迁到顶部导航栏（v4.4.0），底部这里不再占位。
            // v5：齿轮抽成共用 SettingsGearButton（v2 皮肤浅底圆形；打开态旋转动画收进组件），
            // 与生成按钮组间距统一 10dp。
            SettingsGearButton(
                open = showOptionsBottomSheet,
                onClick = { showOptionsBottomSheet = true },
            )
        }
    } // End of outer Column

    // Options Bottom Sheet
    if (showOptionsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(textToImageViewModel) {
                UnifiedCallbacks(
                    onWorkflowChange = textToImageViewModel::onWorkflowChange,
                    // 输出端格式开关（v4.6.1 模块 C）
                    onSaveAsWebpChange = textToImageViewModel::onSaveAsWebpChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = textToImageViewModel::onNegativePromptChange,
                    // 通用节点参数引擎：「节点参数」分组改值（改值即持久化）
                    onDynamicParamValueChange = textToImageViewModel::onDynamicParamValueChange,
                    onCheckpointChange = textToImageViewModel::onCheckpointChange,
                    onUnetChange = textToImageViewModel::onUnetChange,
                    onVaeChange = textToImageViewModel::onVaeChange,
                    onClipChange = textToImageViewModel::onClipChange,
                    onClip1Change = textToImageViewModel::onClip1Change,
                    onClip2Change = textToImageViewModel::onClip2Change,
                    onClip3Change = textToImageViewModel::onClip3Change,
                    onClip4Change = textToImageViewModel::onClip4Change,
                    onTextEncoderChange = textToImageViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = textToImageViewModel::onLatentUpscaleModelChange,
                    onMandatoryLoraChange = textToImageViewModel::onMandatoryLoraChange,
                    onWidthChange = textToImageViewModel::onWidthChange,
                    onHeightChange = textToImageViewModel::onHeightChange,
                    onStepsChange = textToImageViewModel::onStepsChange,
                    onCfgChange = textToImageViewModel::onCfgChange,
                    onSamplerChange = textToImageViewModel::onSamplerChange,
                    onSchedulerChange = textToImageViewModel::onSchedulerChange,
                    onSeedModeChange = textToImageViewModel::onSeedModeChange,
                    onSeedChange = textToImageViewModel::onSeedChange,
                    onRandomizeSeed = textToImageViewModel::onRandomizeSeed,
                    onDenoiseChange = textToImageViewModel::onDenoiseChange,
                    onBatchSizeChange = textToImageViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = textToImageViewModel::onUpscaleMethodChange,
                    onScaleByChange = textToImageViewModel::onScaleByChange,
                    onStopAtClipLayerChange = textToImageViewModel::onStopAtClipLayerChange,
                    onAddLora = textToImageViewModel::onAddLora,
                    onRemoveLora = textToImageViewModel::onRemoveLora,
                    onLoraNameChange = textToImageViewModel::onLoraNameChange,
                    onLoraStrengthChange = textToImageViewModel::onLoraStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = uiState.selectedWorkflow,
                spellCheckEnabled = spellCheckEnabled
            )
        }
    }

    // 技能流（提示词强化）的文本结果弹窗：产物是一个字符串而不是图片，
    // 非技能流时它自己什么都不画
    SkillTextResultDialog()

    // Prompt Library Dialog
    if (presetUiState.showLibrarySideSheet) {
        // 过滤结果按 uiState 缓存：对话框开着时本页每次重组（键盘动画/生成进度）都会重跑全量过滤
        val filteredPresets = remember(presetUiState) { presetViewModel.getFilteredPresets() }
        PromptLibraryDialog(
            presets = filteredPresets,
            availableTags = presetUiState.availableTags,
            searchQuery = presetUiState.searchQuery,
            selectedTags = presetUiState.selectedTags,
            filterFavoritesOnly = presetUiState.filterFavoritesOnly,
            activePresetId = presetUiState.activePresetId,
            onSearchQueryChange = { presetViewModel.onSearchQueryChange(it) },
            onTagToggle = { presetViewModel.onTagToggle(it) },
            onToggleFavoritesFilter = { presetViewModel.onToggleFavoritesFilter() },
            onPresetSelected = { presetViewModel.onPresetSelected(it) },
            onToggleFavorite = { presetViewModel.onToggleFavorite(it) },
            onEditPreset = { presetViewModel.showEditDialog(it) },
            onDuplicatePreset = { presetViewModel.onDuplicatePreset(it) },
            onDeletePreset = { presetViewModel.onDeletePreset(it) },
            onDismiss = { presetViewModel.dismissLibrary() }
        )
    }

    // Prompt Preset Save/Edit Dialog
    if (presetUiState.showSaveDialog) {
        PromptPresetDialog(
            editingPreset = presetUiState.editingPreset,
            currentPrompt = presetUiState.currentPromptForSave,
            currentNegativePrompt = presetUiState.currentNegativePromptForSave,
            existingTags = presetUiState.availableTags,
            isNameTaken = { name, excludeId -> presetViewModel.isNameTaken(name, excludeId) },
            onDismiss = { presetViewModel.dismissSaveDialog() },
            onSave = { name, prompt, negativePrompt, tags ->
                presetViewModel.onSavePreset(name, prompt, negativePrompt, tags)
            }
        )
    }
}
