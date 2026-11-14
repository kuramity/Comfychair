package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MaskEditorActivity
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.GenerationTopBarActions
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.SkillTextResultButton
import sh.hnet.comfychair.ui.components.shared.ThreeDInlinePreview
import sh.hnet.comfychair.ui.components.shared.ThreeDResultButton
import sh.hnet.comfychair.ui.components.shared.ThreeDViewerSheet
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.model.ThreeDVisibility
import sh.hnet.comfychair.ui.components.shared.SkillTextResultDialog
import sh.hnet.comfychair.ui.components.shared.FreeMemoryButton
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.ImageSlotArea
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageEvent
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

/** 主图占位符（与 `MediaStateHolder.MediaKey.ItiSource` 对应） */
private const val PLACEHOLDER_SOURCE_IMAGE = "image_filename"

/** 参考图占位符（与 `IteReferenceImage1/2` 两个缓存档对应） */
private const val PLACEHOLDER_REFERENCE_IMAGE_1 = "reference_image_1"
private const val PLACEHOLDER_REFERENCE_IMAGE_2 = "reference_image_2"

/**
 * 图生图能承载的图位占位符。
 *
 * 图生图用固定字段承载这三张图（主图 + 参考图 1/2），上传时传给
 * `prepareImageEditingWorkflowById` 的名字也是这三个，所以图位区只渲染这一批：
 * 多出来的图位渲染成格子也存不进去，不如不显示。
 */
private val ITI_SLOT_PLACEHOLDERS =
    setOf(PLACEHOLDER_SOURCE_IMAGE, PLACEHOLDER_REFERENCE_IMAGE_1, PLACEHOLDER_REFERENCE_IMAGE_2)

/**
 * 图位占位符 -> 当前图片。
 *
 * 按占位符名与 ViewModel 的三个字段对齐，因此**删除某个图位只影响它自己**：
 * 参考图位为空时只写自己的字段，主图不受影响（设计 §4.2 的位置稳定性）。
 */
private fun itiSlotImages(state: ImageToImageUiState): Map<String, Bitmap> = buildMap {
    state.sourceImage?.let { put(PLACEHOLDER_SOURCE_IMAGE, it) }
    state.referenceImage1?.let { put(PLACEHOLDER_REFERENCE_IMAGE_1, it) }
    state.referenceImage2?.let { put(PLACEHOLDER_REFERENCE_IMAGE_2, it) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Prompt preset ViewModel
    val presetViewModel: PromptPresetViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by imageToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()

    // ── 3D 产物状态（v4.4.0 提到页面顶层：顶部入口与结果区都要用）──
    //
    // 两个概念必须分清，v4.3.0 的闪退就是混在一起导致的：
    //  · **入口**（顶部那个立方体按钮）—— 只看「当前是不是 3D 流」，
    //    选中 3D 流立刻出现、切回普通流立刻消失，与有没有结果无关；
    //  · **内容**（结果区分段/内嵌预览）—— 必须是**这个流自己**的产物，
    //    否则会在没生成过的 3D 流里冒出一个空 3D 分段，把「原图/预览」挤掉。
    // 当前工作流 id **必须按模式取**（v4.4.1 修的真机 bug）：
    // 图生图页有两个独立的选择器，3D 流都挂在编辑模式下，取错就是空串 →
    // ThreeDVisibility 判为"不属于当前流"，生成完的 3D 结果被丢掉。
    val threeDResult by ThreeDResultHolder.result.collectAsState()
    val currentWorkflowId = ThreeDVisibility.activeWorkflowId(
        isEditingMode = uiState.mode == ImageToImageMode.EDITING,
        plainId = uiState.selectedWorkflowId,
        editingId = uiState.selectedEditingWorkflowId
    )
    var boundWorkflowId by remember { mutableStateOf<String?>(null) }
    // 每来一个新结果，它就归属"此刻正在生成它的这个工作流"；
    // holder 被清空时归属一并清掉，避免旧绑定阻碍下一次展示。
    LaunchedEffect(threeDResult?.filename) {
        boundWorkflowId = if (threeDResult == null) null else currentWorkflowId
    }
    val visible3D = ThreeDVisibility.visible(
        result = threeDResult,
        is3DWorkflow = uiState.capabilities.is3DWorkflow,
        boundWorkflowId = boundWorkflowId,
        currentWorkflowId = currentWorkflowId
    )

    // Initialize preset ViewModel (shared for both inpainting and editing modes)
    LaunchedEffect(Unit) {
        presetViewModel.initialize(context, ScreenType.IMAGE_TO_IMAGE)
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

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID

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

    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher for source image
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToImageViewModel.onSourceImageChange(context, it)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            imageToImageViewModel.initialize(context, client)
        }
    }

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            imageToImageViewModel.fetchModels()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        imageToImageViewModel.events.collect { event ->
            when (event) {
                is ImageToImageEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.ShowToastMessage -> {
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
                    imageToImageViewModel.onPositivePromptChange(event.prompt)
                    // 负面按当前模式写回对应字段（编辑模式与修复模式各有一套负面），
                    // 与配置面板里负面输入框的分发保持一致
                    if (event.negativePrompt.isNotBlank()) {
                        if (uiState.mode == ImageToImageMode.EDITING) {
                            imageToImageViewModel.onEditingNegativePromptChange(event.negativePrompt)
                        } else {
                            imageToImageViewModel.onNegativePromptChange(event.negativePrompt)
                        }
                    }
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    imageToImageViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        imageToImageViewModel.startListening(generationViewModel)
        onDispose {
            imageToImageViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID && promptId != null) {
            imageToImageViewModel.clearPreviewForExecution(promptId)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW)
            imageToImageViewModel.startListening(generationViewModel)
        }
    }

    // UI composition
    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        TopAppBar(
            // 标题不换行：顶栏多了一个 3D 入口后，窄屏（或英文长标题）会把
            // "Image to Image" 挤成两行、把顶栏撑高。截断比换行干净。
            title = {
                Text(
                    text = stringResource(R.string.title_image_to_image),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // v4.9.0：四段式顶栏（模式入口→输入→输出→全局）。
                // 本页原来缺保存/分享（四个模式里唯一"出了图导不出去"的），这轮补上。
                val maskEditing = uiState.sourceImage != null &&
                    uiState.mode == ImageToImageMode.INPAINTING
                GenerationTopBarActions(
                    is3DWorkflow = uiState.capabilities.is3DWorkflow,
                    threeDResult = visible3D,
                    onUploadImage = { imagePickerLauncher.launch("image/*") },
                    onEditMask = if (maskEditing) ({
                        // Initialize state holder and launch mask editor activity
                        MaskEditorStateHolder.initialize(
                            sourceImage = uiState.sourceImage!!,
                            maskPaths = uiState.maskPaths,
                            brushSize = uiState.brushSize,
                            isEraserMode = uiState.isEraserMode,
                            onPathAdded = { path, isEraser, brushSize ->
                                imageToImageViewModel.addMaskPath(path, isEraser, brushSize)
                                // Update state holder with new paths
                                MaskEditorStateHolder.updateMaskPaths(imageToImageViewModel.uiState.value.maskPaths)
                            },
                            onClearMask = {
                                imageToImageViewModel.clearMask()
                                MaskEditorStateHolder.updateMaskPaths(emptyList())
                            },
                            onInvertMask = {
                                imageToImageViewModel.invertMask()
                                MaskEditorStateHolder.updateMaskPaths(imageToImageViewModel.uiState.value.maskPaths)
                            },
                            onBrushSizeChange = { imageToImageViewModel.onBrushSizeChange(it) },
                            onEraserModeChange = { imageToImageViewModel.onEraserModeChange(it) }
                        )
                        context.startActivity(MaskEditorActivity.createIntent(context))
                    }) else null,
                    onClearMask = if (maskEditing) ({
                        imageToImageViewModel.clearMask()
                    }) else null,
                    onSave = uiState.previewImage?.let {
                        {
                            imageToImageViewModel.saveToGallery { success ->
                                val messageRes = if (success) R.string.msg_image_saved_to_gallery else R.string.error_save_image
                                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = uiState.previewImage?.let {
                        {
                            imageToImageViewModel.getShareIntent()?.let { intent ->
                                context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_image)))
                            }
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

        // Image Preview Area + view mode toggle — collapse when typing in the prompt field
        AnimatedVisibility(
            visible = !expandPrompt,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(150)),
            exit = ExitTransition.None
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 3D 产物：结果区默认进 3D 视图（v4.1.1）。
                // 老问题：3D 流同时产出 GLB 和一张渲染图 PNG，App 只展示图片，
                // 用户点开看到的是"能缩放但转不动的图片"——那不是 3D 查看器。
                //
                // 状态的来源已上移到页面顶层（v4.4.0：顶部入口和结果区共用一份，
                // 避免两处各自判一遍导致显隐打架）。这里只保留「结果区要不要按 3D 呈现」：
                // 必须真有**属于当前流**的产物，否则空 3D 分段会把「原图/预览」挤掉
                // （v4.3.0 修的真机 bug 就是这个）。
                val has3DResult = visible3D != null
                var show3DView by remember { mutableStateOf(has3DResult) }
                var show3DFullscreen by remember { mutableStateOf(false) }
                // 结果出现就自动进 3D 视图（用户生成图生3D，想看的是模型而不是一张快照）；
                // 结果没了（切流/重启）就同步收起来，别卡在空 3D 视图上。
                // 切到非 3D 流时 has3DResult 变 false，这里也会把 show3DView 收回，
                // 保证 `show3DView` 与 `has3DResult` 不会再出现"true 但无结果"的错位状态。
                LaunchedEffect(has3DResult) {
                    show3DView = has3DResult
                    if (!has3DResult) show3DFullscreen = false
                }

                // 编辑模式的图位：主图 + 工作流里识别到的参考图。
                // 修复模式的主图区是"带遮罩的画布"，不是图位，继续走原来的 MaskPreview 渲染。
                val editingSlots = if (uiState.mode == ImageToImageMode.EDITING) {
                    uiState.capabilities.imageSlots.filter { it.placeholder in ITI_SLOT_PLACEHOLDERS }
                } else {
                    emptyList()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(
                            // 图位区自己处理点击（点空位选图 / 点已填位放大）；整块区域的点击留给预览模式
                            // （修复模式下主图区仍是画布，点击照旧打开大图）
                            // 3D 预览自己消费手势，这里必须关掉，否则点模型会弹出图片查看器
                            enabled = if (has3DResult && show3DView) {
                                false
                            } else when (uiState.viewMode) {
                                ImageToImageViewMode.PREVIEW ->
                                    uiState.previewImage != null && !isThisScreenExecuting

                                ImageToImageViewMode.SOURCE ->
                                    editingSlots.isEmpty() && uiState.sourceImage != null
                            },
                            onClick = {
                                when (uiState.viewMode) {
                                    ImageToImageViewMode.PREVIEW -> {
                                        // Launch MediaViewer for generated image
                                        uiState.previewImage?.let { bitmap ->
                                            val intent = MediaViewerActivity.createSingleImageIntent(
                                                context = context,
                                                bitmap = bitmap,
                                                hostname = generationViewModel.getHostname(),
                                                port = generationViewModel.getPort(),
                                                filename = uiState.previewImageFilename,
                                                subfolder = uiState.previewImageSubfolder,
                                                type = uiState.previewImageType
                                            )
                                            context.startActivity(intent)
                                        }
                                    }
                                    ImageToImageViewMode.SOURCE -> {
                                        // Launch MediaViewer for source image (without mask)
                                        uiState.sourceImage?.let { bitmap ->
                                            val intent = MediaViewerActivity.createSingleImageIntent(context, bitmap)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (has3DResult && show3DView) {
                        // 结果区直接渲染真 3D（可旋转缩放），右上角的立方体按钮切全屏。
                        // 用 visible3D 而不是 threeDResult!! —— 后者在状态切换的瞬间可能
                        // 已被回收（collectAsState 异步更新），!! 会直接抛 NPE 闪退。
                        visible3D?.let { r ->
                            ThreeDInlinePreview(
                                result = r,
                                modifier = Modifier.fillMaxSize(),
                                onRequestFullscreen = { show3DFullscreen = true }
                            )
                        }
                    } else when (uiState.viewMode) {
                        ImageToImageViewMode.SOURCE -> {
                            if (editingSlots.isNotEmpty()) {
                                // 编辑模式：主图与参考图统一到同一套图位机制，入口从底部配置面板移到这里
                                // （单个图位按整块渲染，与接入前的主图区域一致）
                                ImageSlotArea(
                                    slots = editingSlots,
                                    images = itiSlotImages(uiState),
                                    canAddNumbered = false,
                                    onImageSelected = { placeholder, uri ->
                                        when (placeholder) {
                                            PLACEHOLDER_SOURCE_IMAGE ->
                                                imageToImageViewModel.onSourceImageChange(context, uri)

                                            PLACEHOLDER_REFERENCE_IMAGE_1 ->
                                                imageToImageViewModel.onReferenceImage1Change(context, uri)

                                            else ->
                                                imageToImageViewModel.onReferenceImage2Change(context, uri)
                                        }
                                    },
                                    // 图生图能承载的图片只有上面三张（主图 + 参考图 1/2），
                                    // 没有乙类容器可增长，所以这里永远不显示「+ 添加」
                                    onAddSlot = {},
                                    onDeleteSelected = { placeholders ->
                                        placeholders.forEach { placeholder ->
                                            when (placeholder) {
                                                PLACEHOLDER_SOURCE_IMAGE ->
                                                    imageToImageViewModel.onClearSourceImage()

                                                PLACEHOLDER_REFERENCE_IMAGE_1 ->
                                                    imageToImageViewModel.onClearReferenceImage1()

                                                else ->
                                                    imageToImageViewModel.onClearReferenceImage2()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (uiState.sourceImage != null) {
                                if (uiState.mode == ImageToImageMode.INPAINTING) {
                                    // Read-only preview of source image with mask overlay
                                    MaskPreview(
                                        sourceImage = uiState.sourceImage,
                                        maskPaths = uiState.maskPaths,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    // Editing mode: show plain source image without mask
                                    Image(
                                        bitmap = uiState.sourceImage!!.asImageBitmap(),
                                        contentDescription = stringResource(R.string.content_description_source_image),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                // Placeholder - app logo
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
                        ImageToImageViewMode.PREVIEW -> {
                            if (uiState.previewImage != null) {
                                Image(
                                    bitmap = uiState.previewImage!!.asImageBitmap(),
                                    contentDescription = stringResource(R.string.content_description_preview),
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
                }

                // View mode toggle
                // 有 3D 产物时多一段「3D 模型」：3D 流的结果本来就该以 3D 呈现，
                // 渲染图只是它的一个角度快照（IDOL 的 RenderSplat 输出），两者独立切换。
                val modeCount = if (has3DResult) 3 else 2
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    if (has3DResult) {
                        SegmentedButton(
                            selected = show3DView,
                            onClick = { show3DView = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = modeCount)
                        ) {
                            Text(stringResource(R.string.three_d_tab_model))
                        }
                        SegmentedButton(
                            selected = !show3DView && uiState.viewMode == ImageToImageViewMode.PREVIEW,
                            onClick = {
                                show3DView = false
                                imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = modeCount)
                        ) {
                            Text(stringResource(R.string.three_d_tab_render))
                        }
                        SegmentedButton(
                            selected = !show3DView && uiState.viewMode == ImageToImageViewMode.SOURCE,
                            onClick = {
                                show3DView = false
                                imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = modeCount)
                        ) {
                            Text(stringResource(R.string.tab_source_image))
                        }
                    } else {
                        SegmentedButton(
                            selected = uiState.viewMode == ImageToImageViewMode.SOURCE,
                            onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = modeCount)
                        ) {
                            Text(stringResource(R.string.tab_source_image))
                        }
                        SegmentedButton(
                            selected = uiState.viewMode == ImageToImageViewMode.PREVIEW,
                            onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = modeCount)
                        ) {
                            Text(stringResource(R.string.tab_preview))
                        }
                    }
                }

                // 3D 全屏查看器（双击/单指拖动都在全屏 Dialog 里，不会被页面手势抢）。
                // 同样用 visible3D 而不是 threeDResult!!：切流时 holder 可能已被清空，
                // 旧写法会在重组那一刻解包 null 直接闪退。
                val fullscreen3D = visible3D
                if (show3DFullscreen && fullscreen3D != null) {
                    ThreeDViewerSheet(
                        result = fullscreen3D,
                        onDismiss = { show3DFullscreen = false }
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
                imageToImageViewModel.onPositivePromptChange(it)
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
                        // 正面取主区域那一个；负面按当前模式取配置面板里正在编辑的那个
                        // （编辑/修复模式的负面是两个独立字段，取错就会存下另一个模式的负面）
                        val negative = if (uiState.mode == ImageToImageMode.EDITING) {
                            uiState.editingNegativePrompt
                        } else {
                            uiState.negativePrompt
                        }
                        presetViewModel.showSaveDialog(uiState.positivePrompt, negative)
                    },
                    onResetPrompt = { presetViewModel.resetPrompt() }
                )
            },
            trailingIcon = {
                // 锁定按钮在清空按钮左侧（与文生图页一致）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { imageToImageViewModel.onPromptLockToggle() }) {
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
                            // 锁定时用主题主色标示"非默认状态"
                            tint = if (uiState.positivePromptLocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            }
                        )
                    }
                    if (uiState.positivePrompt.isNotEmpty()) {
                        IconButton(onClick = { imageToImageViewModel.onPositivePromptChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
                        }
                    }
                }
            }
        )

        // Generate and Options buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            GenerationButton(
                // v4.8.2（模块四）：只算本模式自己的活跃任务，避免四个模式一起显示队列数
                queueSize = queueState.activeCountFor(ImageToImageViewModel.OWNER_ID),
                isExecuting = queueState.isExecuting,
                // v4.6.1：只有本页面自己的任务才允许取消，避免跨模式误中断
                canCancelCurrent = queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID ||
                    queueState.ownJobs.values.any { it.ownerId == ImageToImageViewModel.OWNER_ID },
                isEnabled = imageToImageViewModel.hasValidConfiguration() &&
                    // 内嵌提示词的工作流（四视图、4x 放大等）不要求填写
                    (!uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank()) &&
                    uiState.sourceImage != null,
                isUploading = uiState.isUploading,
                // v4.6.1 模块 D：上传进度（-1 = 无上传）
                uploadProgress = uiState.uploadProgress,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.hint_paint_mask),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToImageViewModel.OWNER_ID
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
                                context.getString(R.string.error_generation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.hint_paint_mask),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToImageViewModel.OWNER_ID,
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
                                context.getString(R.string.error_generation_failed),
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

            Spacer(modifier = Modifier.width(8.dp))

            // 图生3D 的入口已迁到顶部导航栏（v4.4.0），底部这里不再占位。
            // 搬走后底部一行只剩「生成按钮组 + [技能结果] + 齿轮」，
            // 生成按钮拿回它该有的宽度，两个圆形按钮也不再被挤到贴边。

            // Animate gear icon rotation when options sheet is shown
            val optionsIconRotation by animateFloatAsState(
                targetValue = if (showOptionsSheet) 90f else 0f,
                label = "options icon rotation"
            )

            OutlinedIconButton(
                onClick = { showOptionsSheet = true },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.button_options),
                    modifier = Modifier.rotate(optionsIconRotation)
                )
            }
        }
    } // End of outer Column

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(imageToImageViewModel) {
                UnifiedCallbacks(
                    // Mode selection
                    onModeChange = imageToImageViewModel::onModeChange,
                    // 参考图（编辑模式）不在这里：已并入主区域的图位区，见 ImageSlotArea 接线
                    // Inpainting workflow callback
                    onWorkflowChange = imageToImageViewModel::onWorkflowChange,
                    // 输出端格式开关（v4.6.1 模块 C）
                    onSaveAsWebpChange = imageToImageViewModel::onSaveAsWebpChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    // Editing workflow callback
                    onEditingWorkflowChange = imageToImageViewModel::onEditingWorkflowChange,
                    // 通用节点参数引擎：「节点参数」分组改值（改值即持久化）
                    onDynamicParamValueChange = imageToImageViewModel::onDynamicParamValueChange,
                    // 编辑页那套动态参数（图生图页 / 编辑页各一份状态）
                    onEditingDynamicParamValueChange = imageToImageViewModel::onEditingDynamicParamValueChange,
                    onViewEditingWorkflow = {
                        val workflowId = uiState.editingWorkflows
                            .find { it.name == uiState.selectedEditingWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    // Negative prompt (route to editing/inpainting field based on mode)
                    onNegativePromptChange = { text ->
                        if (uiState.mode == ImageToImageMode.EDITING) {
                            imageToImageViewModel.onEditingNegativePromptChange(text)
                        } else {
                            imageToImageViewModel.onNegativePromptChange(text)
                        }
                    },
                    // Inpainting model selection callbacks
                    onCheckpointChange = imageToImageViewModel::onCheckpointChange,
                    onUnetChange = imageToImageViewModel::onUnetChange,
                    onVaeChange = imageToImageViewModel::onVaeChange,
                    onClipChange = imageToImageViewModel::onClipChange,
                    onClip1Change = imageToImageViewModel::onClip1Change,
                    onClip2Change = imageToImageViewModel::onClip2Change,
                    onClip3Change = imageToImageViewModel::onClip3Change,
                    onClip4Change = imageToImageViewModel::onClip4Change,
                    onTextEncoderChange = imageToImageViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = imageToImageViewModel::onLatentUpscaleModelChange,
                    // Editing model selection callbacks
                    onEditingUnetChange = imageToImageViewModel::onEditingUnetChange,
                    onEditingLoraChange = imageToImageViewModel::onEditingLoraChange,
                    onEditingVaeChange = imageToImageViewModel::onEditingVaeChange,
                    onEditingClipChange = imageToImageViewModel::onEditingClipChange,
                    onEditingClip1Change = imageToImageViewModel::onEditingClip1Change,
                    onEditingClip2Change = imageToImageViewModel::onEditingClip2Change,
                    onEditingClip3Change = imageToImageViewModel::onEditingClip3Change,
                    onEditingClip4Change = imageToImageViewModel::onEditingClip4Change,
                    onEditingTextEncoderChange = imageToImageViewModel::onEditingTextEncoderChange,
                    onEditingLatentUpscaleModelChange = imageToImageViewModel::onEditingLatentUpscaleModelChange,
                    onEditingUpscaleModelChange = imageToImageViewModel::onEditingUpscaleModelChange,
                    // Inpainting parameter callbacks
                    onMegapixelsChange = imageToImageViewModel::onMegapixelsChange,
                    onStepsChange = imageToImageViewModel::onStepsChange,
                    onCfgChange = imageToImageViewModel::onCfgChange,
                    onSamplerChange = imageToImageViewModel::onSamplerChange,
                    onSchedulerChange = imageToImageViewModel::onSchedulerChange,
                    onSeedModeChange = imageToImageViewModel::onSeedModeChange,
                    onSeedChange = imageToImageViewModel::onSeedChange,
                    onRandomizeSeed = imageToImageViewModel::onRandomizeSeed,
                    onDenoiseChange = imageToImageViewModel::onDenoiseChange,
                    onBatchSizeChange = imageToImageViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = imageToImageViewModel::onUpscaleMethodChange,
                    onScaleByChange = imageToImageViewModel::onScaleByChange,
                    onStopAtClipLayerChange = imageToImageViewModel::onStopAtClipLayerChange,
                    // Editing parameter callbacks
                    onEditingMegapixelsChange = imageToImageViewModel::onEditingMegapixelsChange,
                    onEditingStepsChange = imageToImageViewModel::onEditingStepsChange,
                    onEditingCfgChange = imageToImageViewModel::onEditingCfgChange,
                    onEditingSamplerChange = imageToImageViewModel::onEditingSamplerChange,
                    onEditingSchedulerChange = imageToImageViewModel::onEditingSchedulerChange,
                    onEditingSeedModeChange = imageToImageViewModel::onEditingSeedModeChange,
                    onEditingSeedChange = imageToImageViewModel::onEditingSeedChange,
                    onEditingRandomizeSeed = imageToImageViewModel::onEditingRandomizeSeed,
                    onEditingDenoiseChange = imageToImageViewModel::onEditingDenoiseChange,
                    onEditingBatchSizeChange = imageToImageViewModel::onEditingBatchSizeChange,
                    onEditingUpscaleMethodChange = imageToImageViewModel::onEditingUpscaleMethodChange,
                    onEditingScaleByChange = imageToImageViewModel::onEditingScaleByChange,
                    onEditingStopAtClipLayerChange = imageToImageViewModel::onEditingStopAtClipLayerChange,
                    // Inpainting LoRA chain callbacks
                    onAddLora = imageToImageViewModel::onAddLora,
                    onRemoveLora = imageToImageViewModel::onRemoveLora,
                    onLoraNameChange = imageToImageViewModel::onLoraNameChange,
                    onLoraStrengthChange = imageToImageViewModel::onLoraStrengthChange,
                    // Editing LoRA chain callbacks
                    onAddEditingLora = imageToImageViewModel::onAddEditingLora,
                    onRemoveEditingLora = imageToImageViewModel::onRemoveEditingLora,
                    onEditingLoraNameChange = imageToImageViewModel::onEditingLoraNameChange,
                    onEditingLoraStrengthChange = imageToImageViewModel::onEditingLoraStrengthChange,
                    // Editing mode checkpoint (for checkpoint-based workflows)
                    onEditingCheckpointChange = imageToImageViewModel::onEditingCheckpointChange,
                    // Editing mode dual-model patterns (for video-style workflows)
                    onEditingHighnoiseUnetChange = imageToImageViewModel::onEditingHighnoiseUnetChange,
                    onEditingLownoiseUnetChange = imageToImageViewModel::onEditingLownoiseUnetChange,
                    onEditingHighnoiseLoraChange = imageToImageViewModel::onEditingHighnoiseLoraChange,
                    onEditingLownoiseLoraChange = imageToImageViewModel::onEditingLownoiseLoraChange,
                    // Editing mode dual LoRA chain callbacks
                    onAddEditingHighnoiseLora = imageToImageViewModel::onAddEditingHighnoiseLora,
                    onRemoveEditingHighnoiseLora = imageToImageViewModel::onRemoveEditingHighnoiseLora,
                    onEditingHighnoiseLoraNameChange = imageToImageViewModel::onEditingHighnoiseLoraNameChange,
                    onEditingHighnoiseLoraStrengthChange = imageToImageViewModel::onEditingHighnoiseLoraStrengthChange,
                    onAddEditingLownoiseLora = imageToImageViewModel::onAddEditingLownoiseLora,
                    onRemoveEditingLownoiseLora = imageToImageViewModel::onRemoveEditingLownoiseLora,
                    onEditingLownoiseLoraNameChange = imageToImageViewModel::onEditingLownoiseLoraNameChange,
                    onEditingLownoiseLoraStrengthChange = imageToImageViewModel::onEditingLownoiseLoraStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = if (uiState.mode == ImageToImageMode.EDITING)
                    uiState.selectedEditingWorkflow else uiState.selectedWorkflow,
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
