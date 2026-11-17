package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.GenerationTopBarActions
import sh.hnet.comfychair.ui.components.GenerationTopBarTitle
import sh.hnet.comfychair.ui.components.AudioSlotArea
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.ImageSlotArea
import sh.hnet.comfychair.ui.components.TimelineClipArea
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.previewSurfaceBackground
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.FreeMemoryButton
import sh.hnet.comfychair.ui.components.shared.SettingsGearButton
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoEvent
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToVideoScreen(
    generationViewModel: GenerationViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val uiState by imageToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == ImageToVideoViewModel.OWNER_ID

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
    SideEffect { prevImeHeight = imeHeight }
    var promptFocused by remember { mutableStateOf(false) }
    val expandPrompt = promptExpandEnabled && promptFocused && imeHeight > 0 && imeHeight >= prevImeHeight

    var showOptionsSheet by remember { mutableStateOf(false) }

    // Track screen visibility for video playback control
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video URI from ViewModel state
    val videoUri = uiState.currentVideoUri

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToVideoViewModel.onSourceImageChange(context, it)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
        }
    }

    // Observe lifecycle to control video playback during navigation transitions
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            isScreenVisible = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            imageToVideoViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.IMAGE_TO_VIDEO)

        // Check if there's a pending generation that may have completed while we were away
        if (generationViewModel.generationState.value.isGenerating &&
            generationViewModel.generationState.value.ownerId == ImageToVideoViewModel.OWNER_ID) {
            generationViewModel.checkServerForCompletion { _, _ -> }
        }
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

    // Event handling
    LaunchedEffect(Unit) {
        imageToVideoViewModel.events.collect { event ->
            when (event) {
                is ImageToVideoEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToVideoEvent.ShowToastMessage -> {
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
                    imageToVideoViewModel.onPositivePromptChange(event.prompt)
                    // 预设里带了负面才写回（空负面可能是"本来就没有"，也可能是旧预设）
                    if (event.negativePrompt.isNotBlank()) {
                        imageToVideoViewModel.onNegativePromptChange(event.negativePrompt)
                    }
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    imageToVideoViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        imageToVideoViewModel.startListening(generationViewModel)
        onDispose {
            imageToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == ImageToVideoViewModel.OWNER_ID && promptId != null) {
            imageToVideoViewModel.clearPreviewForExecution(promptId)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW)
            imageToVideoViewModel.startListening(generationViewModel)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with upload and save/share actions
        TopAppBar(
            // v4.16.6（Q6）：360dp 屏本页 5 控件占 276dp、标题槽只剩 84dp，22sp 四个汉字
            // 必换行（截图折成「图生视 / 频」）——统一走 GenerationTopBarTitle（单行 + 降字号）
            title = { GenerationTopBarTitle(text = stringResource(R.string.title_image_to_video)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // v4.9.0：四段式顶栏（加图 → 保存/分享 → 全局）
                // v5：保存/分享常驻；原 canExport 的"非预览视图不给导出"改为编辑态
                // isEditing（按钮禁用置灰而非隐藏，位置稳定）
                GenerationTopBarActions(
                    is3DWorkflow = false,
                    threeDResult = null,
                    hasResult = videoUri != null,
                    isEditing = uiState.viewMode != ImageToVideoViewMode.PREVIEW,
                    // 导演台流不显示加图：那条流没有任何图片输入口，
                    // 选了图会被上传到服务器却无处注入 —— 用户以为照片是参考素材，实际零效果
                    onUploadImage = if (!uiState.isTimelineWorkflow) {
                        ({ imagePickerLauncher.launch("image/*") })
                    } else null,
                    // 产物 URI 非空才执行（按钮仅在 hasResult 且非编辑态时可点，恒定传回调不改变原行为）
                    onSave = {
                        videoUri?.let { uri ->
                            scope.launch {
                                VideoUtils.saveVideoToGallery(context, uri, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO)
                            }
                        }
                    },
                    onShare = {
                        videoUri?.let { uri -> VideoUtils.shareVideo(context, uri) }
                    },
                    onSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        )

        // Progress indicator - below app bar, only show if THIS screen's job is executing
        if (isThisScreenExecuting && generationState.maxProgress > 0) {
            GenerationProgressBar(
                progress = { generationState.progress },
                maxProgress = { generationState.maxProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Preview area + view mode toggle — collapse when typing in the prompt field
        AnimatedVisibility(
            visible = !expandPrompt,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(150)),
            exit = ExitTransition.None
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 判据是"这个工作流是不是图位型"（检测阶段的事实，见 ImageToVideoUiState.usesImageSlots），
                // **不是**"当前还剩几个图位"：乙类图位可以被用户一路删到 0 张（设计 §3），那时图位定义
                // 一个都不剩，但图位区必须还在——不然「+ 添加」入口跟着消失，删掉的图位再也加不回来。
                // 只有真正识别不出图位的历史写法才走旧的单图路径（图位是图片的唯一来源，没有第二份源图状态）
                val usesImageSlots = uiState.usesImageSlots
                val legacyImage = if (usesImageSlots) {
                    null
                } else {
                    uiState.slotImages[ImageToVideoViewModel.PLACEHOLDER_MAIN_IMAGE]
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 150.dp)
                        // 我推荐项：直角纯色 → 圆角 16 + 160° 渐变底（图片内容不受影响，见 helper 注释）
                        .previewSurfaceBackground()
                        .clickable(
                            // 图位区自己处理点击（点空位选图 / 点已填位放大），整块区域的点击只留给
                            // 预览模式与旧的单图路径
                            enabled = when (uiState.viewMode) {
                                ImageToVideoViewMode.PREVIEW -> videoUri != null
                                ImageToVideoViewMode.SOURCE -> legacyImage != null
                            },
                            onClick = {
                                when (uiState.viewMode) {
                                    ImageToVideoViewMode.PREVIEW -> {
                                        videoUri?.let { uri ->
                                            val intent = MediaViewerActivity.createSingleVideoIntent(
                                                context = context,
                                                videoUri = uri,
                                                hostname = generationViewModel.getHostname(),
                                                port = generationViewModel.getPort()
                                            )
                                            context.startActivity(intent)
                                            // 与图库页一致：打开时放大淡入，关闭时的缩小淡出在 MediaViewerActivity.finish() 里
                                            (context as? Activity)?.overridePendingTransition(
                                                R.anim.viewer_fade_in_grow, R.anim.gallery_stay
                                            )
                                        }
                                    }
                                    ImageToVideoViewMode.SOURCE -> {
                                        // Source image is user-provided, no ComfyUI metadata
                                        legacyImage?.let { bitmap ->
                                            val intent = MediaViewerActivity.createSingleImageIntent(context, bitmap)
                                            context.startActivity(intent)
                                            // 与图库页一致：打开时放大淡入，关闭时的缩小淡出在 MediaViewerActivity.finish() 里
                                            (context as? Activity)?.overridePendingTransition(
                                                R.anim.viewer_fade_in_grow, R.anim.gallery_stay
                                            )
                                        }
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (uiState.viewMode) {
                        ImageToVideoViewMode.SOURCE -> {
                            if (uiState.isTimelineWorkflow) {
                                // 时间线导演台：这一整块换成片段编辑器。
                                // 导演台流的素材全在 timeline_data 里，没有图位也没有音频槽，
                                // 所以下面那两个分支在它身上永远不会命中。
                                TimelineClipArea(
                                    clips = uiState.timelineClips,
                                    selectionStart = uiState.timelineSelectionStart,
                                    selectionDuration = uiState.timelineSelectionDuration,
                                    gaps = uiState.timelineGaps,
                                    coveredSeconds = uiState.timelineCoveredSeconds,
                                    canAdd = uiState.canAddTimelineClip,
                                    loadedFromWorkflow = uiState.timelineLoadedFromWorkflow,
                                    onAddClip = imageToVideoViewModel::addTimelineClip,
                                    onClipStartChange = imageToVideoViewModel::setTimelineClipStart,
                                    onClipDurationChange = imageToVideoViewModel::setTimelineClipDuration,
                                    onClipTrimStartChange = imageToVideoViewModel::setTimelineClipTrimStart,
                                    onClipReferenceModeChange = imageToVideoViewModel::setTimelineClipReferenceMode,
                                    onRemoveClip = imageToVideoViewModel::removeTimelineClip,
                                    onSelectionChange = imageToVideoViewModel::setTimelineSelection,
                                    onUseGap = imageToVideoViewModel::selectTimelineGap,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp)
                                )
                            } else if (usesImageSlots) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // 图位区：单图位按整块渲染，多图位分格，多于 4 张可纵向滚动。
                                    // slots 用实时列表（可能被删到空），删空时图位区只剩「+ 添加」入口。
                                    ImageSlotArea(
                                        slots = uiState.imageSlots,
                                        images = uiState.slotImages,
                                        canAddNumbered = uiState.canAddNumberedSlot,
                                        onImageSelected = { placeholder, uri ->
                                            imageToVideoViewModel.replaceSlotImage(placeholder, uri)
                                        },
                                        onAddSlot = imageToVideoViewModel::addImageSlot,
                                        onDeleteSelected = imageToVideoViewModel::deleteSlots,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    )
                                    // 参考音频区：接在图位区下方、同一片区域里。
                                    // 只有接了 ref_audios 的工作流才有音频槽，其余工作流这里整块不出现。
                                    if (uiState.usesAudioSlots) {
                                        AudioSlotArea(
                                            slots = uiState.audioSlots,
                                            files = uiState.slotAudioFiles,
                                            canAdd = uiState.canAddAudioSlot,
                                            onAudioSelected = { placeholder, uri ->
                                                imageToVideoViewModel.replaceSlotAudio(placeholder, uri)
                                            },
                                            onAddSlot = imageToVideoViewModel::addAudioSlot,
                                            onRemoveSlot = { placeholder ->
                                                imageToVideoViewModel.removeAudioSlots(setOf(placeholder))
                                            },
                                            modifier = Modifier.padding(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = 6.dp,
                                                bottom = 8.dp
                                            )
                                        )
                                    }
                                }
                            } else if (legacyImage != null) {
                                Image(
                                    bitmap = legacyImage.asImageBitmap(),
                                    contentDescription = stringResource(R.string.tab_source_image),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                // Placeholder - app logo with tap to upload hint
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_comfychair_foreground),
                                        contentDescription = null,
                                        modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                                        contentScale = ContentScale.Fit
                                    )
                                    Text(
                                        text = stringResource(R.string.msg_no_source_image),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        ImageToVideoViewMode.PREVIEW -> {
                            when {
                                // Show preview bitmap during generation
                                uiState.previewBitmap != null -> {
                                    Image(
                                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                                        contentDescription = stringResource(R.string.content_description_preview),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                // Show video player when video is available
                                videoUri != null -> {
                                    VideoPlayer(
                                        videoUri = videoUri,
                                        modifier = Modifier.fillMaxSize(),
                                        isActive = isScreenVisible
                                    )
                                }
                                // Show placeholder - app logo
                                else -> {
                                    Image(
                                        painter = painterResource(R.drawable.ic_comfychair_foreground),
                                        contentDescription = stringResource(R.string.placeholder_video),
                                        modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                }

                // View mode toggle
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToVideoViewMode.SOURCE,
                        onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_source_image))
                    }
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToVideoViewMode.PREVIEW,
                        onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_preview))
                    }
                }
            }
        }

        // Prompt Input — expands to fill screen above keyboard when focused
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = {
                imageToVideoViewModel.onPositivePromptChange(it)
                presetViewModel.clearActivePreset()
            },
            label = { Text(stringResource(R.string.hint_prompt)) },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandPrompt) Modifier.weight(1f) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { promptFocused = it.isFocused },
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
                if (uiState.positivePrompt.isNotEmpty()) {
                    IconButton(onClick = {
                        imageToVideoViewModel.onPositivePromptChange("")
                        presetViewModel.clearActivePreset()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
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
                queueSize = queueState.activeCountFor(ImageToVideoViewModel.OWNER_ID),
                isExecuting = queueState.isExecuting,
                // v4.6.1：只有本页面自己的任务才允许取消，避免跨模式误中断
                canCancelCurrent = queueState.executingOwnerId == ImageToVideoViewModel.OWNER_ID ||
                    queueState.ownJobs.values.any { it.ownerId == ImageToVideoViewModel.OWNER_ID },
                isEnabled = imageToVideoViewModel.hasValidConfiguration() &&
                    (!uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank()),
                isUploading = uiState.isUploading,
                // v4.6.1 模块 D：上传进度（-1 = 无上传）
                uploadProgress = uiState.uploadProgress,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    scope.launch {
                        val workflowJson = imageToVideoViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToVideoViewModel.OWNER_ID,
                                ContentType.VIDEO
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
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    scope.launch {
                        val workflowJson = imageToVideoViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToVideoViewModel.OWNER_ID,
                                ContentType.VIDEO,
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

            // v5：齿轮抽成共用 SettingsGearButton（v2 皮肤浅底圆形；打开态旋转动画收进组件），
            // 与生成按钮组间距统一 10dp。
            SettingsGearButton(
                open = showOptionsSheet,
                onClick = { showOptionsSheet = true },
            )
        }
    } // End of outer Column

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = configSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(imageToVideoViewModel) {
                UnifiedCallbacks(
                    onWorkflowChange = imageToVideoViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = imageToVideoViewModel::onNegativePromptChange,
                    // 通用节点参数引擎：「节点参数」分组改值（改值即持久化）
                    onDynamicParamValueChange = imageToVideoViewModel::onDynamicParamValueChange,
                    // Single-model patterns
                    onCheckpointChange = imageToVideoViewModel::onCheckpointChange,
                    onUnetChange = imageToVideoViewModel::onUnetChange,
                    onMandatoryLoraChange = imageToVideoViewModel::onMandatoryLoraChange,
                    onMandatoryLoraStrengthChange = imageToVideoViewModel::onMandatoryLoraStrengthChange,
                    // Dual-model patterns
                    onHighnoiseUnetChange = imageToVideoViewModel::onHighnoiseUnetChange,
                    onLownoiseUnetChange = imageToVideoViewModel::onLownoiseUnetChange,
                    onHighnoiseLoraChange = imageToVideoViewModel::onHighnoiseLoraChange,
                    onLownoiseLoraChange = imageToVideoViewModel::onLownoiseLoraChange,
                    onVaeChange = imageToVideoViewModel::onVaeChange,
                    onClipChange = imageToVideoViewModel::onClipChange,
                    onClip1Change = imageToVideoViewModel::onClip1Change,
                    onClip2Change = imageToVideoViewModel::onClip2Change,
                    onClip3Change = imageToVideoViewModel::onClip3Change,
                    onClip4Change = imageToVideoViewModel::onClip4Change,
                    onTextEncoderChange = imageToVideoViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = imageToVideoViewModel::onLatentUpscaleModelChange,
                    onWidthChange = imageToVideoViewModel::onWidthChange,
                    onHeightChange = imageToVideoViewModel::onHeightChange,
                    onMegapixelsChange = imageToVideoViewModel::onMegapixelsChange,
                    onLengthChange = imageToVideoViewModel::onLengthChange,
                    onFpsChange = imageToVideoViewModel::onFpsChange,
                    onStepsChange = imageToVideoViewModel::onStepsChange,
                    onCfgChange = imageToVideoViewModel::onCfgChange,
                    onSamplerChange = imageToVideoViewModel::onSamplerChange,
                    onSchedulerChange = imageToVideoViewModel::onSchedulerChange,
                    // 视频分辨率选择器 / 加速模式
                    onAspectRatioChange = imageToVideoViewModel::onAspectRatioChange,
                    onSpeedModeChange = imageToVideoViewModel::onSpeedModeChange,
                    onSeedModeChange = imageToVideoViewModel::onSeedModeChange,
                    onSeedChange = imageToVideoViewModel::onSeedChange,
                    onRandomizeSeed = imageToVideoViewModel::onRandomizeSeed,
                    onDenoiseChange = imageToVideoViewModel::onDenoiseChange,
                    onBatchSizeChange = imageToVideoViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = imageToVideoViewModel::onUpscaleMethodChange,
                    onScaleByChange = imageToVideoViewModel::onScaleByChange,
                    onStopAtClipLayerChange = imageToVideoViewModel::onStopAtClipLayerChange,
                    // Primary LoRA chain (for single-model workflows like LTX 2.0)
                    onAddLora = imageToVideoViewModel::onAddLora,
                    onRemoveLora = imageToVideoViewModel::onRemoveLora,
                    onLoraNameChange = imageToVideoViewModel::onLoraNameChange,
                    onLoraStrengthChange = imageToVideoViewModel::onLoraStrengthChange,
                    // Dual-model LoRA chains
                    onAddHighnoiseLora = imageToVideoViewModel::onAddHighnoiseLora,
                    onRemoveHighnoiseLora = imageToVideoViewModel::onRemoveHighnoiseLora,
                    onHighnoiseLoraNameChange = imageToVideoViewModel::onHighnoiseLoraChainNameChange,
                    onHighnoiseLoraStrengthChange = imageToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                    onAddLownoiseLora = imageToVideoViewModel::onAddLownoiseLora,
                    onRemoveLownoiseLora = imageToVideoViewModel::onRemoveLownoiseLora,
                    onLownoiseLoraNameChange = imageToVideoViewModel::onLownoiseLoraChainNameChange,
                    onLownoiseLoraStrengthChange = imageToVideoViewModel::onLownoiseLoraChainStrengthChange
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

    // Prompt Preset Dialog
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
