package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.content.Intent
import java.io.IOException
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.MaskPathData
import sh.hnet.comfychair.model.PositivePromptPolicy
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.nextSeed
import sh.hnet.comfychair.model.contract.CommonGenerationState
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.PromptTargetFactory
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.util.BitmapDecodeUtils
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.PhotoManager
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ShareFileStore
import sh.hnet.comfychair.util.UploadImageEncoder
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.prompt.SkillTextResult
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

/**
 * Represents a workflow item in the unified workflow dropdown for Image-to-Image
 */
data class ItiWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name with type prefix
    val type: WorkflowType      // Workflow type for mode detection
) : WorkflowItemBase

/**
 * UI state for the view mode toggle
 */
enum class ImageToImageViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Image-to-Image mode (Inpainting vs Editing)
 */
enum class ImageToImageMode {
    INPAINTING,  // Existing: requires mask
    EDITING      // New: no mask, optional reference images
}

/**
 * Represents a workflow item for Image Editing
 */
data class IteWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name for dropdown
    val type: WorkflowType      // ITI_EDITING
) : WorkflowItemBase

/**
 * UI state for the Image-to-image screen
 *
 * Architecture: Unified Field Visibility
 * - Inpainting mode uses unified fields (no checkpoint/UNET distinction)
 * - Editing mode has its own set of fields (different workflow type)
 * - Field visibility is controlled by WorkflowCapabilities (derived from placeholders)
 */
data class ImageToImageUiState(
    // View state
    val viewMode: ImageToImageViewMode = ImageToImageViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val previewImage: Bitmap? = null,
    val maskPaths: List<MaskPathData> = emptyList(),
    val brushSize: Float = 50f,
    val isEraserMode: Boolean = false,

    // Preview image file info (for metadata extraction)
    val previewImageFilename: String? = null,
    val previewImageSubfolder: String? = null,
    val previewImageType: String? = null,

    // Inpainting workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<ItiWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    val workflowPlaceholders: Set<String> = emptySet(),

    // 动态节点参数（通用节点参数引擎）：工作流里「未识别」的 {{占位符}}。
    // 本页（图生图）与编辑页各一份，与 editingXxx 系列的既有分法一致。
    val dynamicParams: List<DynamicParam> = emptyList(),
    val dynamicParamValues: Map<String, String> = emptyMap(),
    val editingDynamicParams: List<DynamicParam> = emptyList(),
    val editingDynamicParamValues: Map<String, String> = emptyMap(),
    // 各自推导时 /object_info 注册表是否已就绪（与上面两套控件清单**各自同一次快照**，UI 提示条用）
    val dynamicParamsRegistryReady: Boolean = false,
    val editingDynamicParamsRegistryReady: Boolean = false,

    // Workflow capabilities (unified flags derived from placeholders)
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Available models (from server)
    override val availableCheckpoints: List<String> = emptyList(),
    override val availableUnets: List<String> = emptyList(),
    override val availableVaes: List<String> = emptyList(),
    override val availableClips: List<String> = emptyList(),
    override val availableLoras: List<String> = emptyList(),
    override val availableUpscaleMethods: List<String> = emptyList(),
    override val availableTextEncoders: List<String> = emptyList(),
    override val availableLatentUpscaleModels: List<String> = emptyList(),
    override val availableUpscaleModels: List<String> = emptyList(),
    override val availableSamplers: List<String> = emptyList(),
    override val availableSchedulers: List<String> = emptyList(),

    // Inpainting mode - unified model selections (visibility driven by capabilities)
    override val selectedCheckpoint: String = "",
    override val selectedUnet: String = "",
    override val selectedVae: String = "",
    override val selectedClip: String = "",
    override val selectedClip1: String = "",
    override val selectedClip2: String = "",
    override val selectedClip3: String = "",
    override val selectedClip4: String = "",
    override val selectedTextEncoder: String = "",
    override val selectedLatentUpscaleModel: String = "",
    override val selectedLoraName: String = "",  // Mandatory LoRA (for CommonGenerationState interface)

    // Workflow-specific filtered options (from actual node type in workflow)
    override val filteredCheckpoints: List<String>? = null,
    override val filteredUnets: List<String>? = null,
    override val filteredVaes: List<String>? = null,
    override val filteredClips: List<String>? = null,
    override val filteredClips1: List<String>? = null,
    override val filteredClips2: List<String>? = null,
    override val filteredClips3: List<String>? = null,
    override val filteredClips4: List<String>? = null,
    override val filteredTextEncoders: List<String>? = null,
    override val filteredLatentUpscaleModels: List<String>? = null,
    override val filteredLoras: List<String>? = null,

    // Inpainting mode - unified generation parameters
    // 正面提示词只有一个输入框（两个模式共用），负面则是每模式一个（negativePrompt / editingNegativePrompt）
    val positivePrompt: String = "",
    /**
     * 正面提示词锁：锁上后切换工作流**完全不动正面提示词**（不保存也不载入）。
     * 取值策略见 [sh.hnet.comfychair.model.PositivePromptPolicy]；只锁正面，负面照旧。
     */
    val positivePromptLocked: Boolean = false,
    val negativePrompt: String = "",
    override val megapixels: String = "1.0",
    override val steps: String = "20",
    override val cfg: String = "7.0",
    override val sampler: String = "euler",
    override val scheduler: String = "normal",
    override val seedMode: SeedMode = SeedMode.RANDOM,
    override val seed: String = "0",
    override val denoise: String = "1.0",
    override val batchSize: String = "1",
    override val upscaleMethod: String = "nearest-exact",
    override val scaleBy: String = "1.5",
    override val stopAtClipLayer: String = "-1",

    /**
     * 输出端保存为 WebP（v4.6.1 模块 C）。
     *
     * 修复流与编辑流共用一份偏好（都走 `WorkflowValues.saveAsWebp`）；
     * 3D 工作流（ITI_3D）不显示开关、也不做节点替换。
     */
    val saveAsWebp: Boolean = false,

    // Inpainting mode - unified LoRA chain
    override val loraChain: List<LoraSelection> = emptyList(),

    // Validation errors (shared between modes)
    override val megapixelsError: String? = null,
    override val cfgError: String? = null,
    override val stepsError: String? = null,
    override val seedError: String? = null,
    override val denoiseError: String? = null,
    override val batchSizeError: String? = null,
    override val scaleByError: String? = null,
    override val stopAtClipLayerError: String? = null,

    // Deferred model selections for inpainting (for restoring after models load)
    val deferredCheckpoint: String? = null,
    val deferredUnet: String? = null,
    val deferredVae: String? = null,
    val deferredClip: String? = null,
    val deferredClip1: String? = null,
    val deferredClip2: String? = null,
    val deferredClip3: String? = null,
    val deferredClip4: String? = null,
    val deferredTextEncoder: String? = null,
    val deferredLatentUpscaleModel: String? = null,

    // ========== EDITING MODE STATE ==========
    // (Editing is a different workflow type, so it has its own set of fields)

    // Mode selection (Editing vs Inpainting)
    val mode: ImageToImageMode = ImageToImageMode.EDITING,

    // Editing mode workflows (ITI_EDITING)
    val editingWorkflows: List<IteWorkflowItem> = emptyList(),
    val selectedEditingWorkflow: String = "",
    val selectedEditingWorkflowId: String = "",  // Editing workflow ID for storage

    // Editing mode models
    val selectedEditingUnet: String = "",
    val selectedEditingLora: String = "",  // Mandatory LoRA for editing
    val selectedEditingVae: String = "",
    val selectedEditingClip: String = "",
    val selectedEditingClip1: String = "",
    val selectedEditingClip2: String = "",
    val selectedEditingClip3: String = "",
    val selectedEditingClip4: String = "",
    val selectedEditingTextEncoder: String = "",
    val selectedEditingLatentUpscaleModel: String = "",
    val selectedEditingUpscale: String = "",  // UpscaleModelLoader model ({{upscale_model}})

    // Editing mode - checkpoint (for checkpoint-based workflows)
    val selectedEditingCheckpoint: String = "",

    // Editing mode - dual-model patterns (highnoise/lownoise for video-style workflows)
    val selectedEditingHighnoiseUnet: String = "",
    val selectedEditingLownoiseUnet: String = "",
    val selectedEditingHighnoiseLora: String = "",
    val selectedEditingLownoiseLora: String = "",

    // Editing mode - dual LoRA chains
    val editingHighnoiseLoraChain: List<LoraSelection> = emptyList(),
    val editingLownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Editing parameters
    val editingMegapixels: String = "2.0",
    val editingSteps: String = "4",
    val editingCfg: String = "1.0",
    val editingSampler: String = "euler",
    val editingScheduler: String = "simple",
    val editingNegativePrompt: String = "",
    /** 编辑模式的种子取值方式（与修复模式那套独立，见 CommonGenerationState.seedMode 的说明） */
    val editingSeedMode: SeedMode = SeedMode.RANDOM,
    val editingSeed: String = "0",
    val editingDenoise: String = "1.0",
    val editingBatchSize: String = "1",
    val editingUpscaleMethod: String = "nearest-exact",
    val editingScaleBy: String = "1.5",
    val editingStopAtClipLayer: String = "-1",

    // Reference images (optional, for editing mode)
    val referenceImage1: Bitmap? = null,
    val referenceImage2: Bitmap? = null,

    // Optional LoRA chain for editing (in addition to mandatory LoRA)
    val editingLoraChain: List<LoraSelection> = emptyList(),

    // Deferred model selections for editing
    val deferredEditingUnet: String? = null,
    val deferredEditingVae: String? = null,
    val deferredEditingClip: String? = null,
    val deferredEditingClip1: String? = null,
    val deferredEditingClip2: String? = null,
    val deferredEditingClip3: String? = null,
    val deferredEditingClip4: String? = null,
    val deferredEditingLora: String? = null,
    val deferredEditingTextEncoder: String? = null,
    val deferredEditingLatentUpscaleModel: String? = null,
    val deferredEditingUpscale: String? = null,

    // Deferred model selections for editing - checkpoint and dual-model patterns
    val deferredEditingCheckpoint: String? = null,
    val deferredEditingHighnoiseUnet: String? = null,
    val deferredEditingLownoiseUnet: String? = null,
    val deferredEditingHighnoiseLora: String? = null,
    val deferredEditingLownoiseLora: String? = null,

    // Upload/fetch state
    val isUploading: Boolean = false,
    /** 上传进度 0–100（-1 = 当前没有上传）；v4.6.1 模块 D */
    val uploadProgress: Int = -1,
    val isFetching: Boolean = false
) : CommonGenerationState

/**
 * Events emitted by the Image-to-image screen
 */
sealed class ImageToImageEvent {
    data class ShowToast(val messageResId: Int) : ImageToImageEvent()
    data class ShowToastMessage(val message: String) : ImageToImageEvent()
}

/**
 * ViewModel for the Image-to-image screen
 */
class ImageToImageViewModel : BaseGenerationViewModel<ImageToImageUiState, ImageToImageEvent>() {

    override val initialState = ImageToImageUiState()

    /** 本页在 GenerationViewModel 里的注册键（基类 stopListening 用） */
    override val generationOwnerId = OWNER_ID

    /**
     * 注册到标签库桥接（需求 5.2）。
     *
     * 注意：图生图有两个模式，负向提示词字段不同——
     * 编辑模式用 editingNegativePrompt，修复模式用 negativePrompt，
     * 桥接层按当前模式分发，避免写错字段（与界面输入框保持一致）。
     */
    init {
        PromptBridge.register(
            PromptTargetFactory.create(
                targetOwnerId = OWNER_ID,
                workflowId = uiState.map { state ->
                    if (state.mode == ImageToImageMode.EDITING) state.selectedEditingWorkflowId
                    else state.selectedWorkflowId
                }.stateIn(viewModelScope, SharingStarted.Eagerly, ""),
                workflowName = uiState.map { state ->
                    if (state.mode == ImageToImageMode.EDITING) state.selectedEditingWorkflow
                    else state.selectedWorkflow
                }.stateIn(viewModelScope, SharingStarted.Eagerly, ""),
                readPositive = { uiState.value.positivePrompt },
                writePositive = { onPositivePromptChange(it) },
                readNegative = {
                    val state = uiState.value
                    if (state.mode == ImageToImageMode.EDITING) state.editingNegativePrompt
                    else state.negativePrompt
                },
                writeNegative = { text ->
                    if (uiState.value.mode == ImageToImageMode.EDITING) {
                        onEditingNegativePromptChange(text)
                    } else {
                        onNegativePromptChange(text)
                    }
                }
            )
        )
    }

    /**
     * P2-20：ViewModel 销毁时从标签库桥接注销。
     *
     * 修复前只注册不注销，[PromptBridge] 的静态 map 一直钉着已销毁的 ViewModel
     * （连同它的 uiState），页面来回切换时条目越积越多，标签库还可能读到旧页面。
     */
    override fun onCleared() {
        super.onCleared()
        PromptBridge.unregister(OWNER_ID)
    }

    // ---- 图库导出（v4.9.0）----
    //
    // 图生图此前是四个模式里唯一"出了图却导不出去"的：ViewModel 里连保存/分享方法都没有，
    // 顶栏也就从来没出现过软盘和分享图标。这里照搬 TextToImageViewModel 的同一套实现
    // （MediaStore 写入 Pictures/ComfyChair + FileProvider 分享），只是取图字段换成
    // 本页的 previewImage。

    /** 把当前预览图写入系统相册（Pictures/ComfyChair） */
    fun saveToGallery(onResult: (success: Boolean) -> Unit) {
        val ctx = applicationContext ?: run { onResult(false); return }
        val bitmap = _uiState.value.previewImage ?: run { onResult(false); return }

        // P1-22：IS_PENDING 写入 + 真实结果。修复前 insert/openOutputStream 失败也会报成功
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                PhotoManager.saveBitmap(ctx, bitmap)
            }
            DebugLogger.i(TAG, "saveToGallery: saved=$saved")
            onResult(saved)
        }
    }

    /** 分享当前预览图：先写进 cacheDir/share/（P2-12 专用子目录），再经 FileProvider 授时 URI 交给系统选择器 */
    fun getShareIntent(): Intent? {
        val ctx = applicationContext ?: return null
        val bitmap = _uiState.value.previewImage ?: return null

        val file = ShareFileStore.file(ctx, "share_image_${System.currentTimeMillis()}.png")
        return try {
            file.outputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            val uri = ShareFileStore.uriFor(ctx, file)
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: IOException) {
            DebugLogger.e(TAG, "getShareIntent failed: ${e.message}")
            null
        }
    }

    // Constants
    companion object {
        private const val TAG = "ImageToImage"
        const val OWNER_ID = "IMAGE_TO_IMAGE"

        /** 等待 ensureConnection 回调的超时（对齐 GenerationViewModel 的提交闸门 30s） */
        private const val CONNECTION_WAIT_TIMEOUT_MS = 30_000L
        private const val PREFS_NAME = "ImageToImageFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        //
        // 仅用于**老数据迁移**：升级前的正面提示词是全局一份，改成每工作流一份后
        // 首次启动把它迁给当时选中的那个工作流，随后删键。新代码不得再写入此键。
        private const val PREF_POSITIVE_PROMPT = "positivePrompt"
        private const val PREF_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val PREF_MODE = "mode"
        private const val PREF_SELECTED_EDITING_WORKFLOW_ID = "selectedEditingWorkflowId"

        /** 正面提示词锁（每服务器一份；默认未锁）。 */
        private const val PREF_POSITIVE_PROMPT_LOCKED = "positivePromptLocked"

        private const val FEATHER_RADIUS = 8
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
                    // P1-7：断线/重连的 invalidateAll() 会把 ModelCache 整体置空
                    //（isLoaded=false、所有列表为空）。那份快照不是"服务器上没有模型"的证据，
                    // 照常校验会把已选模型/LoRA（含编辑页两套）全部清空、deferred 恢复值
                    // 一并抹掉并写回磁盘。直接跳过本次更新，等真实列表回来再校验。
                    if (!ValidationUtils.isUsableModelCache(cache)) {
                        DebugLogger.d(
                            TAG,
                            "跳过空模型缓存快照（isLoaded=${cache.isLoaded}），保留已选模型与 deferred 值"
                        )
                        // 原样返回当前状态（不写状态 = 不触发重组、不落盘空选择）
                        return@update state
                    }
                    // Apply deferred selections first, then validate or fall back to first available
                    val checkpoint = state.deferredCheckpoint?.takeIf { it in cache.checkpoints }
                        ?: validateModelSelection(state.selectedCheckpoint, cache.checkpoints)
                    val unet = state.deferredUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedUnet, cache.unets)
                    val vae = state.deferredVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedVae, cache.vaes)
                    // CLIP 候选按「格式门控」过滤（带 clip_gguf 开关的工作流：开=只留 .gguf，关=只留非 .gguf）
                    val clipOptions = filterClipsByGate(cache.clips, state.dynamicParamValues)
                    val clip = state.deferredClip?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedClip, clipOptions)
                    val clip1 = state.deferredClip1?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedClip1, clipOptions)
                    val clip2 = state.deferredClip2?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedClip2, clipOptions)
                    val clip3 = state.deferredClip3?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedClip3, clipOptions)
                    val clip4 = state.deferredClip4?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedClip4, clipOptions)
                    val textEncoder = state.deferredTextEncoder?.takeIf { it in cache.textEncoders }
                        ?: validateModelSelection(state.selectedTextEncoder, cache.textEncoders)
                    val latentUpscaleModel = state.deferredLatentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                        ?: validateModelSelection(state.selectedLatentUpscaleModel, cache.latentUpscaleModels)
                    val editingUnet = state.deferredEditingUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedEditingUnet, cache.unets)
                    val editingVae = state.deferredEditingVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedEditingVae, cache.vaes)
                    val editingClip = state.deferredEditingClip?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedEditingClip, clipOptions)
                    val editingClip1 = state.deferredEditingClip1?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedEditingClip1, clipOptions)
                    val editingClip2 = state.deferredEditingClip2?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedEditingClip2, clipOptions)
                    val editingClip3 = state.deferredEditingClip3?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedEditingClip3, clipOptions)
                    val editingClip4 = state.deferredEditingClip4?.takeIf { it in clipOptions }
                        ?: validateModelSelection(state.selectedEditingClip4, clipOptions)
                    val editingLora = state.deferredEditingLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedEditingLora, cache.loras)
                    val editingTextEncoder = state.deferredEditingTextEncoder?.takeIf { it in cache.textEncoders }
                        ?: validateModelSelection(state.selectedEditingTextEncoder, cache.textEncoders)
                    val editingLatentUpscaleModel = state.deferredEditingLatentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                        ?: validateModelSelection(state.selectedEditingLatentUpscaleModel, cache.latentUpscaleModels)
                    val editingUpscale = state.deferredEditingUpscale?.takeIf { it in cache.upscaleModels }
                        ?: validateModelSelection(state.selectedEditingUpscale, cache.upscaleModels)
                    val editingCheckpoint = state.deferredEditingCheckpoint?.takeIf { it in cache.checkpoints }
                        ?: validateModelSelection(state.selectedEditingCheckpoint, cache.checkpoints)
                    val editingHighnoiseUnet = state.deferredEditingHighnoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedEditingHighnoiseUnet, cache.unets)
                    val editingLownoiseUnet = state.deferredEditingLownoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedEditingLownoiseUnet, cache.unets)
                    val editingHighnoiseLora = state.deferredEditingHighnoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedEditingHighnoiseLora, cache.loras)
                    val editingLownoiseLora = state.deferredEditingLownoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedEditingLownoiseLora, cache.loras)

                    state.copy(
                        availableCheckpoints = cache.checkpoints,
                        availableUnets = cache.unets,
                        availableVaes = cache.vaes,
                        availableClips = clipOptions,
                        availableLoras = cache.loras,
                        availableTextEncoders = cache.textEncoders,
                        availableLatentUpscaleModels = cache.latentUpscaleModels,
                        availableUpscaleModels = cache.upscaleModels,
                        availableSamplers = cache.samplers,
                        availableSchedulers = cache.schedulers,
                        // Apply validated model selections
                        selectedCheckpoint = checkpoint,
                        selectedUnet = unet,
                        selectedVae = vae,
                        selectedClip = clip,
                        selectedClip1 = clip1,
                        selectedClip2 = clip2,
                        selectedClip3 = clip3,
                        selectedClip4 = clip4,
                        selectedTextEncoder = textEncoder,
                        selectedLatentUpscaleModel = latentUpscaleModel,
                        selectedEditingUnet = editingUnet,
                        selectedEditingVae = editingVae,
                        selectedEditingClip = editingClip,
                        selectedEditingClip1 = editingClip1,
                        selectedEditingClip2 = editingClip2,
                        selectedEditingClip3 = editingClip3,
                        selectedEditingClip4 = editingClip4,
                        selectedEditingLora = editingLora,
                        selectedEditingTextEncoder = editingTextEncoder,
                        selectedEditingLatentUpscaleModel = editingLatentUpscaleModel,
                        selectedEditingUpscale = editingUpscale,
                        selectedEditingCheckpoint = editingCheckpoint,
                        selectedEditingHighnoiseUnet = editingHighnoiseUnet,
                        selectedEditingLownoiseUnet = editingLownoiseUnet,
                        selectedEditingHighnoiseLora = editingHighnoiseLora,
                        selectedEditingLownoiseLora = editingLownoiseLora,
                        // Clear deferred values once applied
                        deferredCheckpoint = null,
                        deferredUnet = null,
                        deferredVae = null,
                        deferredClip = null,
                        deferredClip1 = null,
                        deferredClip2 = null,
                        deferredClip3 = null,
                        deferredClip4 = null,
                        deferredTextEncoder = null,
                        deferredLatentUpscaleModel = null,
                        deferredEditingUnet = null,
                        deferredEditingVae = null,
                        deferredEditingClip = null,
                        deferredEditingClip1 = null,
                        deferredEditingClip2 = null,
                        deferredEditingClip3 = null,
                        deferredEditingClip4 = null,
                        deferredEditingLora = null,
                        deferredEditingTextEncoder = null,
                        deferredEditingLatentUpscaleModel = null,
                        deferredEditingUpscale = null,
                        deferredEditingCheckpoint = null,
                        deferredEditingHighnoiseUnet = null,
                        deferredEditingLownoiseUnet = null,
                        deferredEditingHighnoiseLora = null,
                        deferredEditingLownoiseLora = null,
                        loraChain = LoraChainManager.filterUnavailable(state.loraChain, cache.loras),
                        editingLoraChain = LoraChainManager.filterUnavailable(state.editingLoraChain, cache.loras),
                        editingHighnoiseLoraChain = LoraChainManager.filterUnavailable(state.editingHighnoiseLoraChain, cache.loras),
                        editingLownoiseLoraChain = LoraChainManager.filterUnavailable(state.editingLownoiseLoraChain, cache.loras)
                    )
                }
            }
        }

        // 冷启动时序：/object_info 异步拉取，注册表就绪后重算两页的动态参数（类型自愈）
        viewModelScope.launch {
            ConnectionManager.nodeTypeRegistryReady.collect { ready ->
                if (ready) refreshDynamicParamsIfNeeded()
            }
        }

        // Observe workflow changes to refresh list when workflows are added/updated/deleted
        viewModelScope.launch {
            WorkflowManager.workflowsVersion.collect {
                loadWorkflows()
            }
        }
    }

    override fun onInitialize() {
        DebugLogger.i(TAG, "Initializing")

        loadWorkflows()
        restorePreferences()
        loadSavedImages()
    }

    /**
     * 注册表就绪后重算**两页**（图生图 / 编辑页）当前工作流的动态参数。
     *
     * 用户已输入的值优先保留：以当前 UiState 里的值为「已有保存值」重算，空串在
     * [sh.hnet.comfychair.workflow.dynamic.DynamicParamValues.resolveValue] 里视为"没有"
     * 而回退到 defaults / 节点默认值；两页都没变化时不写状态（避免无谓重组）。
     */
    private fun refreshDynamicParamsIfNeeded() {
        val state = _uiState.value
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()

        val snapshot = state.selectedWorkflowId.takeIf { it.isNotEmpty() }?.let {
            WorkflowManager.loadDynamicParams(it, state.dynamicParamValues)
        }
        val editingSnapshot = state.selectedEditingWorkflowId.takeIf { it.isNotEmpty() }?.let {
            WorkflowManager.loadDynamicParams(it, state.editingDynamicParamValues)
        }

        val params = snapshot?.params ?: state.dynamicParams
        val values = snapshot?.values ?: state.dynamicParamValues
        val editingParams = editingSnapshot?.params ?: state.editingDynamicParams
        val editingValues = editingSnapshot?.values ?: state.editingDynamicParamValues

        if (params == state.dynamicParams &&
            values == state.dynamicParamValues &&
            editingParams == state.editingDynamicParams &&
            editingValues == state.editingDynamicParamValues &&
            (snapshot == null || ready == state.dynamicParamsRegistryReady) &&
            (editingSnapshot == null || ready == state.editingDynamicParamsRegistryReady)
        ) {
            return
        }

        DebugLogger.d(TAG, "注册表就绪，重算动态参数：图生图 ${params.size} 个 / 编辑页 ${editingParams.size} 个控件")
        _uiState.value = state.copy(
            dynamicParams = params,
            dynamicParamValues = values,
            dynamicParamsRegistryReady = if (snapshot != null) ready else state.dynamicParamsRegistryReady,
            editingDynamicParams = editingParams,
            editingDynamicParamValues = editingValues,
            editingDynamicParamsRegistryReady = if (editingSnapshot != null) ready else state.editingDynamicParamsRegistryReady
        )
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        // Load inpainting workflows (ITI_INPAINTING)
        val inpaintingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_INPAINTING)
            .filter { showBuiltIn || !it.isBuiltIn }
        // Load editing workflows (ITI_EDITING)
        // 图生3D（ITI_3D）共用「传图 + 提示词 + 参数」这套交互，一起列出来；
        // 但 item.type 保留真实类型 —— 提交后要靠它把结果路由到 3D 专属结果页
        val editingWorkflows = (
            WorkflowManager.getWorkflowsByType(WorkflowType.ITI_EDITING) +
                WorkflowManager.getWorkflowsByType(WorkflowType.ITI_3D)
            ).filter { showBuiltIn || !it.isBuiltIn }

        // Create inpainting workflow list
        val unifiedWorkflows = inpaintingWorkflows.map { workflow ->
            ItiWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.ITI_INPAINTING
            )
        }

        // Create editing workflow list
        val editingWorkflowItems = editingWorkflows.map { workflow ->
            IteWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = workflow.type
            )
        }.sortedBy { it.displayName }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        val currentEditingSelection = _uiState.value.selectedEditingWorkflow
        val selectedEditingItem = if (currentEditingSelection.isEmpty())
            editingWorkflowItems.firstOrNull()
        else
            editingWorkflowItems.find { it.name == currentEditingSelection } ?: editingWorkflowItems.firstOrNull()

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflowItem?.name ?: "",
            selectedWorkflowId = selectedWorkflowItem?.id ?: "",
            editingWorkflows = editingWorkflowItems,
            selectedEditingWorkflow = selectedEditingItem?.name ?: "",
            selectedEditingWorkflowId = selectedEditingItem?.id ?: ""
        )

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        // IMPORTANT: Load the NON-active mode's workflow first, then the active mode's workflow.
        // Both functions set the shared currentWorkflowHas* capability flags, so the active
        // mode's workflow must be loaded LAST to ensure its flags are not overwritten.
        val currentMode = _uiState.value.mode
        if (currentMode == ImageToImageMode.INPAINTING) {
            // Inpainting is active - load editing first, then inpainting (so inpainting flags win)
            if (selectedEditingItem != null) {
                loadEditingWorkflowValues(selectedEditingItem)
            }
            if (selectedWorkflowItem != null) {
                loadWorkflowValues(selectedWorkflowItem)
            }
        } else {
            // Editing is active - load inpainting first, then editing (so editing flags win)
            if (selectedWorkflowItem != null) {
                loadWorkflowValues(selectedWorkflowItem)
            }
            if (selectedEditingItem != null) {
                loadEditingWorkflowValues(selectedEditingItem)
            }
        }
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 老版本的全局正面词：只当**迁移来源**读一次，不再是"默认正面词"
        val legacyPositivePrompt = prefs.getString("${serverId}_$PREF_POSITIVE_PROMPT", null)
        val positivePromptLocked = prefs.getBoolean("${serverId}_$PREF_POSITIVE_PROMPT_LOCKED", false)
        val savedWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", null)
        val savedMode = prefs.getString("${serverId}_$PREF_MODE", ImageToImageMode.EDITING.name)
        val savedEditingWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_EDITING_WORKFLOW_ID", null)

        // Restore mode
        val mode = try {
            ImageToImageMode.valueOf(savedMode ?: ImageToImageMode.EDITING.name)
        } catch (e: Exception) {
            ImageToImageMode.EDITING
        }

        // Update mode first（正面词不再在这里赋值：它由两条载入路径按工作流载入）
        _uiState.value = _uiState.value.copy(mode = mode)

        // Determine which inpainting workflow to select (by ID)
        val state = _uiState.value
        val workflowToLoad = when {
            // Use saved workflow if it exists in available workflows (by ID)
            savedWorkflowId != null && state.availableWorkflows.any { it.id == savedWorkflowId } ->
                state.availableWorkflows.find { it.id == savedWorkflowId }
            // Otherwise use the current selection (set by loadWorkflows)
            state.selectedWorkflow.isNotEmpty() ->
                state.availableWorkflows.find { it.name == state.selectedWorkflow }
            // Fallback to first available
            state.availableWorkflows.isNotEmpty() -> state.availableWorkflows.first()
            else -> null
        }

        // Determine which editing workflow to select (by ID)
        val editingWorkflowToLoad = when {
            savedEditingWorkflowId != null && state.editingWorkflows.any { it.id == savedEditingWorkflowId } ->
                state.editingWorkflows.find { it.id == savedEditingWorkflowId }
            state.selectedEditingWorkflow.isNotEmpty() ->
                state.editingWorkflows.find { it.name == state.selectedEditingWorkflow }
            state.editingWorkflows.isNotEmpty() -> state.editingWorkflows.first()
            else -> null
        }

        // Load workflow values - load the NON-active mode's workflow first, then the active mode's.
        // Both functions set the shared currentWorkflowHas* capability flags, so the active
        // mode's workflow must be loaded LAST to ensure its flags are not overwritten.
        // 正面提示词同理：最后载入的（激活模式的那一条）才是最终显示的值，
        // 所以"激活模式的工作流必须最后载入"这条约束同时保证了正面词属于正确的模式
        // 老版本的全局正面词：迁移给**激活模式**当前选中的那个工作流（两份记录里只有一个是
        // 用户此刻在用的），迁移必须发生在载入之前
        val activeWorkflowId = if (mode == ImageToImageMode.INPAINTING) {
            workflowToLoad?.id
        } else {
            editingWorkflowToLoad?.id
        }
        if (activeWorkflowId != null) {
            migrateLegacyPositivePrompt(context, serverId, activeWorkflowId, legacyPositivePrompt)
        }

        if (mode == ImageToImageMode.INPAINTING) {
            // Inpainting is active - load editing first, then inpainting (so inpainting flags win)
            if (editingWorkflowToLoad != null) {
                loadEditingWorkflowValues(editingWorkflowToLoad)
            }
            if (workflowToLoad != null) {
                loadWorkflowValues(workflowToLoad)
            }
        } else {
            // Editing is active - load inpainting first, then editing (so editing flags win)
            if (workflowToLoad != null) {
                loadWorkflowValues(workflowToLoad)
            }
            if (editingWorkflowToLoad != null) {
                loadEditingWorkflowValues(editingWorkflowToLoad)
            }
        }

        // 冷启动视为**首次装载**（否则重启后正面词会是一片空白），持久化的锁状态在载入之后
        // 生效：此后切换工作流/切模式才按"完全不动"处理
        _uiState.update { it.copy(positivePromptLocked = positivePromptLocked) }
    }

    /**
     * 把老版本的**全局**正面提示词迁移给当前激活模式的选中工作流（一次性）。
     *
     * 与文生图那条同构（那边的注释更详细）：全局键里没有"这是给哪个工作流写的"这一信息，
     * 迁给此刻正在用的那一个是唯一可解释的近似；目标工作流已有正面词则不覆盖；
     * 删全局键放在写入之后。
     */
    private fun migrateLegacyPositivePrompt(
        context: Context,
        serverId: String,
        workflowId: String,
        legacyPrompt: String?,
    ) {
        if (legacyPrompt == null) return
        val storage = workflowValuesStorage ?: return

        val existing = storage.loadValues(serverId, workflowId)
        if (PositivePromptPolicy.shouldMigrateLegacy(legacyPrompt, existing?.positivePrompt)) {
            storage.saveValues(serverId, workflowId, (existing ?: WorkflowValues()).copy(positivePrompt = legacyPrompt))
            DebugLogger.d(TAG, "已把老版全局正面提示词迁移到当前工作流")
        }
        // 无论是否写入都清掉老键：留着只会在每次启动重复走一遍迁移判断
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("${serverId}_$PREF_POSITIVE_PROMPT").apply()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "清理老版全局正面提示词失败")
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization).
     *
     * Uses unified fields - capabilities (derived from placeholders) control which
     * fields are visible in the UI.
     */
    private fun loadWorkflowValues(workflow: ItiWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // sampler / scheduler 的「服务器可用清单」：读持久化时就要用它清洗，所以缓存必须先拿到
        // （持久化里可能残留非法值，用户实测 'dpm++' 一提交就 400）
        val cache = ConnectionManager.modelCache.value

        // Load saved values and defaults by workflow ID (not name, to avoid duplicate name issues)
        // 读取即清洗：非法 sampler / scheduler 就地改写为默认值并写回磁盘（脏数据自愈）
        val savedValues = storage.loadValuesSanitized(
            serverId = serverId,
            workflowId = workflow.id,
            availableSamplers = cache.samplers,
            availableSchedulers = cache.schedulers,
        )
        val defaults = WorkflowManager.getWorkflowDefaultsById(workflow.id)

        // Get workflow placeholders - these determine field visibility via capabilities
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflow.id)
        val capabilities = WorkflowCapabilities.fromPlaceholders(
            placeholders = placeholders,
            // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
            imageSlots = ImageSlotDetector.detect(
                workflowJson = WorkflowManager.getWorkflowById(workflow.id)?.jsonContent.orEmpty(),
                objectInfo = null
            ),
            // 3D 流按真实节点判定 LoRA 面板去留（prebuilt_29 有节点保留，28/32 无节点隐藏）
            workflowType = workflow.type,
            workflowJson = WorkflowManager.getWorkflowById(workflow.id)?.jsonContent
        )

        // 动态节点参数（通用节点参数引擎）：扫描 + 类型推导，并按
        // 「已有保存值 → defaults.<占位符名> → 节点定义的 default → 空」取值
        val dynamicParamsSnapshot = WorkflowManager.loadDynamicParams(
            workflow.id,
            savedValues?.dynamicParams ?: emptyMap()
        )

        val state = _uiState.value

        // Get saved model selections（cache 已在函数开头取过，同一快照用于两处校验）
        val savedModel = savedValues?.model
        val savedVae = savedValues?.vaeModel
        val savedClip = savedValues?.clipModel
        val savedClip1 = savedValues?.clip1Model
        val savedClip2 = savedValues?.clip2Model
        val savedClip3 = savedValues?.clip3Model
        val savedClip4 = savedValues?.clip4Model

        // Load model into appropriate field based on capabilities
        val selectedCheckpoint = if (capabilities.hasCheckpointName) {
            savedModel?.takeIf { it in cache.checkpoints }
                ?: defaults?.checkpointModel?.takeIf { it in cache.checkpoints }
                ?: validateModelSelection("", cache.checkpoints)
        } else ""

        val selectedUnet = if (capabilities.hasUnetName) {
            savedModel?.takeIf { it in cache.unets }
                ?: defaults?.unetModel?.takeIf { it in cache.unets }
                ?: validateModelSelection("", cache.unets)
        } else ""

        _uiState.value = state.copy(
            // Workflow info
            selectedWorkflow = workflow.name,
            selectedWorkflowId = workflow.id,
            workflowPlaceholders = placeholders,
            capabilities = capabilities,

            // 动态节点参数（通用节点参数引擎）：扫描 + 类型推导，并按
            // 「已有保存值 → defaults.<占位符名> → 节点定义的 default → 空」取值
            dynamicParams = dynamicParamsSnapshot.params,
            dynamicParamValues = dynamicParamsSnapshot.values,
            dynamicParamsRegistryReady = ConnectionManager.nodeTypeRegistry.isPopulated(),

            // Unified generation parameters
            megapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: "1.0",
            steps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: "20",
            cfg = savedValues?.cfg?.toString()
                ?: defaults?.cfg?.toString() ?: "7.0",
            // sampler / scheduler：已保存值 → 工作流默认值 → 缺省，最后再过一道清洗。
            // 清洗走「读取口径」（见 sanitize*OnLoad）：服务器清单还没同步（空）时原样放行
            sampler = SamplerOptions.sanitizeSamplerOnLoad(
                savedValues?.samplerName ?: defaults?.samplerName ?: SamplerOptions.DEFAULT_SAMPLER,
                cache.samplers,
            ),
            scheduler = SamplerOptions.sanitizeSchedulerOnLoad(
                savedValues?.scheduler ?: defaults?.scheduler ?: SamplerOptions.DEFAULT_SCHEDULER,
                cache.schedulers,
            ),
            // 正面提示词跟随工作流（修复流自己的那份）；没存过回退该工作流的默认词。
            // 两条载入路径都正常赋值：模式切换本身就会切换激活工作流，正面词跟着走才符合
            // "跟随工作流"语义（互不污染由保存侧的模式隔离保证，见 saveWorkflowValues）
            positivePrompt = PositivePromptPolicy.resolveOnLoad(
                locked = state.positivePromptLocked,
                current = state.positivePrompt,
                saved = savedValues?.positivePrompt,
                default = defaults?.positivePrompt,
            ),
            negativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",

            // Model selections - load into appropriate fields based on capabilities
            selectedCheckpoint = selectedCheckpoint,
            selectedUnet = selectedUnet,
            selectedVae = savedVae?.takeIf { it in cache.vaes }
                ?: defaults?.vaeModel?.takeIf { it in cache.vaes }
                ?: validateModelSelection("", cache.vaes),
            selectedClip = savedClip?.takeIf { it in cache.clips }
                ?: defaults?.clipModel?.takeIf { it in cache.clips }
                ?: validateModelSelection("", cache.clips),
            selectedClip1 = savedClip1?.takeIf { it in cache.clips }
                ?: defaults?.clip1Model?.takeIf { it in cache.clips }
                ?: validateModelSelection("", cache.clips),
            selectedClip2 = savedClip2?.takeIf { it in cache.clips }
                ?: defaults?.clip2Model?.takeIf { it in cache.clips }
                ?: validateModelSelection("", cache.clips),
            selectedClip3 = savedClip3?.takeIf { it in cache.clips }
                ?: defaults?.clip3Model?.takeIf { it in cache.clips }
                ?: validateModelSelection("", cache.clips),
            selectedClip4 = savedClip4?.takeIf { it in cache.clips }
                ?: validateModelSelection("", cache.clips),
            selectedTextEncoder = savedValues?.textEncoderModel?.takeIf { it in cache.textEncoders }
                ?: validateModelSelection("", cache.textEncoders),
            selectedLatentUpscaleModel = savedValues?.latentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                ?: validateModelSelection("", cache.latentUpscaleModels),

            // Deferred values - applied when model cache updates
            deferredCheckpoint = if (capabilities.hasCheckpointName) savedModel ?: defaults?.checkpointModel else null,
            deferredUnet = if (capabilities.hasUnetName) savedModel ?: defaults?.unetModel else null,
            deferredVae = savedVae ?: defaults?.vaeModel,
            deferredClip = savedClip ?: defaults?.clipModel,
            deferredClip1 = savedClip1 ?: defaults?.clip1Model,
            deferredClip2 = savedClip2 ?: defaults?.clip2Model,
            deferredClip3 = savedClip3 ?: defaults?.clip3Model,
            deferredClip4 = savedClip4,
            deferredTextEncoder = savedValues?.textEncoderModel,
            deferredLatentUpscaleModel = savedValues?.latentUpscaleModel,

            // Unified LoRA chain
            loraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),

            // Unified generation parameters
            seedMode = SeedMode.fromSaved(savedValues?.seedMode, savedValues?.randomSeed),
            seed = savedValues?.seed?.toString()
                ?: defaults?.seed?.toString() ?: "0",
            denoise = savedValues?.denoise?.toString()
                ?: defaults?.denoise?.toString() ?: "1.0",
            batchSize = savedValues?.batchSize?.toString()
                ?: defaults?.batchSize?.toString() ?: "1",
            upscaleMethod = savedValues?.upscaleMethod
                ?: defaults?.upscaleMethod ?: "nearest-exact",
            scaleBy = savedValues?.scaleBy?.toString()
                ?: defaults?.scaleBy?.toString() ?: "1.5",
            stopAtClipLayer = savedValues?.stopAtClipLayer?.toString()
                ?: defaults?.stopAtClipLayer?.toString() ?: "-1",
            // 输出格式偏好（缺省 = 关闭，保持旧版的 PNG 行为）
            saveAsWebp = savedValues?.saveAsWebp ?: false,

            // Workflow-specific filtered options (query for ALL possible fields)
            filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "ckpt_name"),
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "unet_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name4"),
            filteredTextEncoders = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "text_encoder_name"),
            filteredLatentUpscaleModels = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "latent_upscale_model")
        )
    }

    /**
     * Load editing workflow values without triggering save (used during initialization)
     */
    private fun loadEditingWorkflowValues(workflow: IteWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // sampler / scheduler 的「服务器可用清单」：读持久化时就要用它清洗，所以缓存必须先拿到
        // （编辑流与修复流各存一套值，两条载入路径都要过清洗）
        val cache = ConnectionManager.modelCache.value

        // Load saved values and defaults by workflow ID (not name, to avoid duplicate name issues)
        // 读取即清洗：非法 sampler / scheduler 就地改写为默认值并写回磁盘（脏数据自愈）
        val savedValues = storage.loadValuesSanitized(
            serverId = serverId,
            workflowId = workflow.id,
            availableSamplers = cache.samplers,
            availableSchedulers = cache.schedulers,
        )
        val defaults = WorkflowManager.getWorkflowDefaultsById(workflow.id)

        // Get workflow placeholders - these determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflow.id)

        // 动态节点参数（编辑页，通用节点参数引擎）：扫描 + 类型推导 + 取值
        val dynamicParamsSnapshot = WorkflowManager.loadDynamicParams(
            workflow.id,
            savedValues?.dynamicParams ?: emptyMap()
        )

        // Apply saved model selections - use deferred mechanism to handle race condition
        val savedEditingUnet = savedValues?.model  // unified field for editing UNET
        val savedEditingVae = savedValues?.vaeModel
        val savedEditingClip = savedValues?.clipModel
        val savedEditingClip1 = savedValues?.clip1Model
        val savedEditingClip2 = savedValues?.clip2Model
        val savedEditingClip3 = savedValues?.clip3Model
        val savedEditingClip4 = savedValues?.clip4Model
        val savedEditingLora = savedValues?.loraModel
        val savedEditingTextEncoder = savedValues?.textEncoderModel
        val savedEditingLatentUpscaleModel = savedValues?.latentUpscaleModel
        val savedEditingUpscale = savedValues?.upscaleModel
        // Checkpoint and dual-model patterns (for checkpoint-based and video-style workflows)
        val savedEditingCheckpoint = savedValues?.model  // same field as UNET, workflow type determines usage
        val savedEditingHighnoiseUnet = savedValues?.highnoiseUnetModel
        val savedEditingLownoiseUnet = savedValues?.lownoiseUnetModel
        val savedEditingHighnoiseLora = savedValues?.highnoiseLoraModel
        val savedEditingLownoiseLora = savedValues?.lownoiseLoraModel

        val state = _uiState.value
        _uiState.value = state.copy(
            selectedEditingWorkflow = workflow.name,
            selectedEditingWorkflowId = workflow.id,
            workflowPlaceholders = placeholders,
            // 输出格式偏好（缺省 = 关闭）
            saveAsWebp = savedValues?.saveAsWebp ?: false,
            editingMegapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: "2.0",
            editingSteps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: "4",
            editingCfg = savedValues?.cfg?.toString()
                ?: defaults?.cfg?.toString() ?: "1.0",
            // 种子：跟随已保存值，其次工作流默认值，最后回退到"随机"
            editingSeed = savedValues?.seed?.toString()
                ?: defaults?.seed?.toString() ?: "0",
            editingSeedMode = SeedMode.fromSaved(savedValues?.seedMode, savedValues?.randomSeed),
            // 动态节点参数（编辑页，通用节点参数引擎）
            editingDynamicParams = dynamicParamsSnapshot.params,
            editingDynamicParamValues = dynamicParamsSnapshot.values,
            editingDynamicParamsRegistryReady = ConnectionManager.nodeTypeRegistry.isPopulated(),
            // 编辑面板的 sampler / scheduler：同样"已保存值 → 默认值 → 缺省"再清洗。
            // 注意缺省 scheduler 这里是 simple（与修复面板的 normal 不同，是本页既有行为，保持不动）；
            // 清洗只在值非法时把它换成 normal，合法值原样保留。清单走读取口径（见 sanitize*OnLoad）
            editingSampler = SamplerOptions.sanitizeSamplerOnLoad(
                savedValues?.samplerName ?: defaults?.samplerName ?: "euler",
                cache.samplers,
            ),
            editingScheduler = SamplerOptions.sanitizeSchedulerOnLoad(
                savedValues?.scheduler ?: defaults?.scheduler ?: "simple",
                cache.schedulers,
            ),
            // 正面提示词跟随工作流（编辑流自己的那份）—— 与修复流互不干扰
            positivePrompt = PositivePromptPolicy.resolveOnLoad(
                locked = state.positivePromptLocked,
                current = state.positivePrompt,
                saved = savedValues?.positivePrompt,
                default = defaults?.positivePrompt,
            ),
            editingNegativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",
            // Apply model selections immediately if models are loaded, otherwise use validated empty
            selectedEditingUnet = savedEditingUnet?.takeIf { it in cache.unets }
                ?: defaults?.unetModel?.takeIf { it in cache.unets }
                ?: validateModelSelection(state.selectedEditingUnet, cache.unets),
            selectedEditingLora = savedEditingLora?.takeIf { it in cache.loras }
                ?: defaults?.loraModel?.takeIf { it in cache.loras }
                ?: validateModelSelection(state.selectedEditingLora, cache.loras),
            selectedEditingVae = savedEditingVae?.takeIf { it in cache.vaes }
                ?: defaults?.vaeModel?.takeIf { it in cache.vaes }
                ?: validateModelSelection(state.selectedEditingVae, cache.vaes),
            selectedEditingClip = savedEditingClip?.takeIf { it in cache.clips }
                ?: defaults?.clipModel?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip, cache.clips),
            selectedEditingClip1 = savedEditingClip1?.takeIf { it in cache.clips }
                ?: defaults?.clip1Model?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip1, cache.clips),
            selectedEditingClip2 = savedEditingClip2?.takeIf { it in cache.clips }
                ?: defaults?.clip2Model?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip2, cache.clips),
            selectedEditingClip3 = savedEditingClip3?.takeIf { it in cache.clips }
                ?: defaults?.clip3Model?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip3, cache.clips),
            selectedEditingClip4 = savedEditingClip4?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip4, cache.clips),
            selectedEditingTextEncoder = savedEditingTextEncoder?.takeIf { it in cache.textEncoders }
                ?: validateModelSelection(state.selectedEditingTextEncoder, cache.textEncoders),
            selectedEditingLatentUpscaleModel = savedEditingLatentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                ?: validateModelSelection(state.selectedEditingLatentUpscaleModel, cache.latentUpscaleModels),
            selectedEditingUpscale = savedEditingUpscale?.takeIf { it in cache.upscaleModels }
                ?: defaults?.upscalerModel?.takeIf { it in cache.upscaleModels }
                ?: validateModelSelection(state.selectedEditingUpscale, cache.upscaleModels),
            // Checkpoint and dual-model patterns (for checkpoint-based and video-style workflows)
            selectedEditingCheckpoint = savedEditingCheckpoint?.takeIf { it in cache.checkpoints }
                ?: defaults?.checkpointModel?.takeIf { it in cache.checkpoints }
                ?: validateModelSelection(state.selectedEditingCheckpoint, cache.checkpoints),
            selectedEditingHighnoiseUnet = savedEditingHighnoiseUnet?.takeIf { it in cache.unets }
                ?: validateModelSelection(state.selectedEditingHighnoiseUnet, cache.unets),
            selectedEditingLownoiseUnet = savedEditingLownoiseUnet?.takeIf { it in cache.unets }
                ?: validateModelSelection(state.selectedEditingLownoiseUnet, cache.unets),
            selectedEditingHighnoiseLora = savedEditingHighnoiseLora?.takeIf { it in cache.loras }
                ?: validateModelSelection(state.selectedEditingHighnoiseLora, cache.loras),
            selectedEditingLownoiseLora = savedEditingLownoiseLora?.takeIf { it in cache.loras }
                ?: validateModelSelection(state.selectedEditingLownoiseLora, cache.loras),
            // Set deferred values - these will be applied when model cache updates
            deferredEditingUnet = savedEditingUnet ?: defaults?.unetModel,
            deferredEditingVae = savedEditingVae ?: defaults?.vaeModel,
            deferredEditingClip = savedEditingClip ?: defaults?.clipModel,
            deferredEditingClip1 = savedEditingClip1 ?: defaults?.clip1Model,
            deferredEditingClip2 = savedEditingClip2 ?: defaults?.clip2Model,
            deferredEditingClip3 = savedEditingClip3 ?: defaults?.clip3Model,
            deferredEditingClip4 = savedEditingClip4,
            deferredEditingLora = savedEditingLora ?: defaults?.loraModel,
            deferredEditingTextEncoder = savedEditingTextEncoder,
            deferredEditingLatentUpscaleModel = savedEditingLatentUpscaleModel,
            deferredEditingUpscale = savedEditingUpscale ?: defaults?.upscalerModel,
            deferredEditingCheckpoint = savedEditingCheckpoint ?: defaults?.checkpointModel,
            deferredEditingHighnoiseUnet = savedEditingHighnoiseUnet,
            deferredEditingLownoiseUnet = savedEditingLownoiseUnet,
            deferredEditingHighnoiseLora = savedEditingHighnoiseLora,
            deferredEditingLownoiseLora = savedEditingLownoiseLora,
            editingLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            editingHighnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            editingLownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Workflow-specific filtered options
            filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "ckpt_name"),
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "unet_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name4"),
            filteredTextEncoders = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "text_encoder_name"),
            filteredLatentUpscaleModels = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "latent_upscale_model"),
            // Workflow capabilities from placeholders
            capabilities = WorkflowCapabilities.fromPlaceholders(
                placeholders = placeholders,
                // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
                imageSlots = ImageSlotDetector.detect(
                    workflowJson = WorkflowManager.getWorkflowById(workflow.id)?.jsonContent.orEmpty(),
                    objectInfo = null
                ),
                // 3D 流按真实节点判定 LoRA 面板去留（prebuilt_29 有节点保留，28/32 无节点隐藏）
                workflowType = workflow.type,
                workflowJson = WorkflowManager.getWorkflowById(workflow.id)?.jsonContent
            )
        )
    }

    /**
     * Save current workflow values to per-workflow storage.
     *
     * Saves ALL field values unconditionally - WorkflowValues is the unified storage format.
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        // Save unified field values
        val values = WorkflowValues(
            // 输出格式偏好（v4.6.1 模块 C）
            saveAsWebp = state.saveAsWebp,
            megapixels = state.megapixels.toFloatOrNull(),
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            samplerName = state.sampler,
            scheduler = state.scheduler,
            // 正面提示词按工作流各存一份。
            // isActiveMode 是**本次最容易写错的一点**：图生图只有一个正面输入框，却有两套
            // 工作流记录（修复流 / 编辑流），而 savePreferences 无条件调用两套保存 ——
            // 不做模式判断的话，在编辑模式打字会同时把词抄进修复流记录（反之亦然），
            // "每个工作流独立"当场失效。非激活的那一套一律原样保留。
            positivePrompt = PositivePromptPolicy.resolveOnSave(
                locked = state.positivePromptLocked,
                current = state.positivePrompt,
                existing = existingValues?.positivePrompt,
                isActiveMode = state.mode == ImageToImageMode.INPAINTING,
            ),
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Save checkpoint OR unet - whichever is set
            model = state.selectedCheckpoint.takeIf { it.isNotEmpty() }
                ?: state.selectedUnet.takeIf { it.isNotEmpty() },
            // Save ALL model selections unconditionally
            vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoderModel = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            // Unified LoRA chain
            loraChain = LoraSelection.toJsonString(state.loraChain).takeIf { state.loraChain.isNotEmpty() },
            // Unified generation parameters
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 新键（四态取值方式）；老版本读上面那个布尔
            seedMode = state.seedMode.name,
            denoise = state.denoise.toFloatOrNull(),
            batchSize = state.batchSize.toIntOrNull(),
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull(),
            nodeAttributeEdits = existingValues?.nodeAttributeEdits,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        )

        storage.saveValues(serverId, workflowId, values)
    }

    /**
     * Save current editing workflow values to per-workflow storage
     */
    private fun saveEditingWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        val values = WorkflowValues(
            // 输出格式偏好（v4.6.1 模块 C）
            saveAsWebp = state.saveAsWebp,
            megapixels = state.editingMegapixels.toFloatOrNull(),
            steps = state.editingSteps.toIntOrNull(),
            cfg = state.editingCfg.toFloatOrNull(),
            samplerName = state.editingSampler,
            scheduler = state.editingScheduler,
            // 种子现在会真的传进工作流，是有意义的用户输入，必须随工作流持久化
            seed = state.editingSeed.toLongOrNull(),
            randomSeed = state.editingSeedMode.usesRandomValue,
            // 新键（四态取值方式）；老版本读上面那个布尔
            seedMode = state.editingSeedMode.name,
            // 正面提示词：只有当前确实处于编辑模式时才写入（理由见 saveWorkflowValues）
            positivePrompt = PositivePromptPolicy.resolveOnSave(
                locked = state.positivePromptLocked,
                current = state.positivePrompt,
                existing = existingValues?.positivePrompt,
                isActiveMode = state.mode == ImageToImageMode.EDITING,
            ),
            negativePrompt = state.editingNegativePrompt.takeIf { it.isNotEmpty() },
            // model field stores checkpoint or UNET (whichever is set)
            model = state.selectedEditingCheckpoint.takeIf { it.isNotEmpty() }
                ?: state.selectedEditingUnet.takeIf { it.isNotEmpty() },
            loraModel = state.selectedEditingLora.takeIf { it.isNotEmpty() },
            vaeModel = state.selectedEditingVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedEditingClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedEditingClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedEditingClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedEditingClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedEditingClip4.takeIf { it.isNotEmpty() },
            textEncoderModel = state.selectedEditingTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedEditingLatentUpscaleModel.takeIf { it.isNotEmpty() },
            upscaleModel = state.selectedEditingUpscale.takeIf { it.isNotEmpty() },
            // Dual-model patterns (for video-style workflows in editing mode)
            highnoiseUnetModel = state.selectedEditingHighnoiseUnet.takeIf { it.isNotEmpty() },
            lownoiseUnetModel = state.selectedEditingLownoiseUnet.takeIf { it.isNotEmpty() },
            highnoiseLoraModel = state.selectedEditingHighnoiseLora.takeIf { it.isNotEmpty() },
            lownoiseLoraModel = state.selectedEditingLownoiseLora.takeIf { it.isNotEmpty() },
            loraChain = LoraSelection.toJsonString(state.editingLoraChain).takeIf { state.editingLoraChain.isNotEmpty() },
            highnoiseLoraChain = LoraSelection.toJsonString(state.editingHighnoiseLoraChain).takeIf { state.editingHighnoiseLoraChain.isNotEmpty() },
            lownoiseLoraChain = LoraSelection.toJsonString(state.editingLownoiseLoraChain).takeIf { state.editingLownoiseLoraChain.isNotEmpty() },
            nodeAttributeEdits = existingValues?.nodeAttributeEdits,
            // 动态节点参数（编辑页，通用节点参数引擎）
            dynamicParams = state.editingDynamicParamValues
        )

        storage.saveValues(serverId, workflowId, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences with serverId prefix
        // 注意：正面提示词**不再**在这里写全局键（已改为每工作流一份，见两套 saveXxxWorkflowValues）
        prefs.edit()
            .putString("${serverId}_$PREF_MODE", state.mode.name)
            .putString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$PREF_SELECTED_EDITING_WORKFLOW_ID", state.selectedEditingWorkflowId)
            // 锁状态与工作流无关，留在全局偏好里
            .putBoolean("${serverId}_$PREF_POSITIVE_PROMPT_LOCKED", state.positivePromptLocked)
            .apply()

        // Save per-workflow values for the currently selected inpainting workflow (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Save per-workflow values for the currently selected editing workflow (using workflow ID)
        if (state.selectedEditingWorkflowId.isNotEmpty()) {
            saveEditingWorkflowValues(state.selectedEditingWorkflowId)
        }
    }

    private fun loadSavedImages() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiSource, applicationContext)
        val previewImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiPreview, applicationContext)
        val referenceImage1 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, applicationContext)
        val referenceImage2 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, applicationContext)

        _uiState.value = _uiState.value.copy(
            sourceImage = sourceImage,
            previewImage = previewImage,
            referenceImage1 = referenceImage1,
            referenceImage2 = referenceImage2
        )
    }

    /**
     * Fetch models from the server.
     * Models are now loaded automatically via ConnectionManager on connection.
     * This method is kept for API compatibility but is effectively a no-op.
     */
    @Suppress("unused")
    fun fetchModels() {
        // Models are now loaded automatically via ConnectionManager.modelCache
        // which is observed in the init block above.
    }

    // View mode
    fun onViewModeChange(mode: ImageToImageViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    // Image-to-Image mode (Inpainting vs Editing)
    fun onModeChange(mode: ImageToImageMode) {
        val state = _uiState.value
        _uiState.value = state.copy(mode = mode)

        // Reload workflow values to restore field visibility flags for the new mode's workflow
        when (mode) {
            ImageToImageMode.EDITING -> {
                val editingWorkflow = state.editingWorkflows.find { it.name == state.selectedEditingWorkflow }
                if (editingWorkflow != null) {
                    loadEditingWorkflowValues(editingWorkflow)
                }
            }
            ImageToImageMode.INPAINTING -> {
                val workflow = state.availableWorkflows.find { it.name == state.selectedWorkflow }
                if (workflow != null) {
                    loadWorkflowValues(workflow)
                }
            }
        }

        savePreferences()
    }

    // Reference image handlers (for Editing mode)
    fun onReferenceImage1Change(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按最长边上限采样解码：相册原图可能是 8000×6000（192MB），全幅解码会 OOM
                val bitmap = BitmapDecodeUtils.decodeSampledFromUri(context, uri)

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, bitmap, context)
                    _uiState.value = _uiState.value.copy(referenceImage1 = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    fun onReferenceImage2Change(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按最长边上限采样解码：相册原图可能是 8000×6000（192MB），全幅解码会 OOM
                val bitmap = BitmapDecodeUtils.decodeSampledFromUri(context, uri)

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, bitmap, context)
                    _uiState.value = _uiState.value.copy(referenceImage2 = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    fun onClearReferenceImage1() {
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.IteReferenceImage1)
            _uiState.value = _uiState.value.copy(referenceImage1 = null)
        }
    }

    fun onClearReferenceImage2() {
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.IteReferenceImage2)
            _uiState.value = _uiState.value.copy(referenceImage2 = null)
        }
    }

    /**
     * 清空主图（图位区里删掉主图那格时调用）。
     *
     * 甲类图位数量由工作流固定、不允许增减（设计 §3），所以"删除"落在**清空**上：
     * 主图位本身留在原地，只是没有图；提交时该链路由注入器按"留空"处理。
     * 遮罩是画在这张图上的，一并清掉，避免留下对不上新图的遮罩。
     */
    fun onClearSourceImage() {
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.ItiSource)
            _uiState.value = _uiState.value.copy(sourceImage = null, maskPaths = emptyList())
        }
    }

    // Source image
    fun onSourceImageChange(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按最长边上限采样解码：相册原图可能是 8000×6000（192MB），全幅解码会 OOM
                val bitmap = BitmapDecodeUtils.decodeSampledFromUri(context, uri)

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiSource, bitmap, context)

                    _uiState.value = _uiState.value.copy(
                        sourceImage = bitmap,
                        maskPaths = emptyList() // Clear mask when new image is loaded
                    )
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    // Mask operations
    fun addMaskPath(path: Path, isEraser: Boolean, brushSize: Float) {
        val pathData = MaskPathData(path, isEraser, brushSize)
        _uiState.value = _uiState.value.copy(
            maskPaths = _uiState.value.maskPaths + pathData
        )
    }

    fun onBrushSizeChange(size: Float) {
        _uiState.value = _uiState.value.copy(brushSize = size)
    }

    fun onEraserModeChange(isEraser: Boolean) {
        _uiState.value = _uiState.value.copy(isEraserMode = isEraser)
    }

    fun clearMask() {
        _uiState.value = _uiState.value.copy(maskPaths = emptyList())
    }

    fun invertMask() {
        // 蒙版取反 = 「全图覆盖」+「原有笔迹转成橡皮」。
        //
        // P1-25：整图覆盖这一笔必须是**填充**（isFill=true）。修复前是
        // MaskPathData(fullPath, false, brushSize = 1f)，生成蒙版时按 STROKE 语义
        // 只描出 1px 边框 —— 反相后图里几乎没有白色，等于反相失败。
        val sourceImage = _uiState.value.sourceImage ?: return

        viewModelScope.launch {
            // Create a full-coverage path and toggle all existing paths
            val fullPath = Path().apply {
                addRect(RectF(0f, 0f, sourceImage.width.toFloat(), sourceImage.height.toFloat()), Path.Direction.CW)
            }

            // Add inverted background（整块填充，笔宽无意义）
            val invertedPaths = mutableListOf<MaskPathData>()
            invertedPaths.add(MaskPathData(fullPath, isEraser = false, brushSize = 0f, isFill = true))

            // Add existing paths as erasers (to remove painted areas)
            _uiState.value.maskPaths.forEach { pathData ->
                invertedPaths.add(pathData.copy(isEraser = !pathData.isEraser))
            }

            _uiState.value = _uiState.value.copy(maskPaths = invertedPaths)
        }
    }

    fun hasMask(): Boolean {
        return _uiState.value.maskPaths.isNotEmpty()
    }

    /**
     * Generate the mask bitmap from current paths
     * Returns black/white bitmap where white = inpaint area
     */
    fun generateMaskBitmap(): Bitmap? {
        val sourceImage = _uiState.value.sourceImage ?: return null
        if (_uiState.value.maskPaths.isEmpty()) return null

        // Create mask at source image size
        val maskBitmap = Bitmap.createBitmap(sourceImage.width, sourceImage.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)

        // Start with black background
        canvas.drawColor(Color.BLACK)

        // Draw white for painted areas
        val paintPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        val erasePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        _uiState.value.maskPaths.forEach { pathData ->
            val paint = if (pathData.isEraser) erasePaint else paintPaint
            // P1-25：填充笔按 FILL 画（整块覆盖），描边笔才设 strokeWidth
            paint.style = if (pathData.isFill) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = pathData.brushSize
            canvas.drawPath(pathData.path, paint)
        }

        // 还原共享画笔的默认语义（两支笔在循环间被复用）
        paintPaint.style = Paint.Style.STROKE
        erasePaint.style = Paint.Style.STROKE

        // Apply feathering
        return applyFeathering(maskBitmap, FEATHER_RADIUS)
    }

    private fun applyFeathering(mask: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return mask

        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale values (0-255)
        val values = IntArray(width * height) { i ->
            Color.red(pixels[i]) // Since it's black/white, R=G=B
        }

        // Horizontal pass
        val tempValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until width) {
                        sum += values[y * width + nx]
                        count++
                    }
                }
                tempValues[y * width + x] = sum / count
            }
        }

        // Vertical pass
        val blurredValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until height) {
                        sum += tempValues[ny * width + x]
                        count++
                    }
                }
                blurredValues[y * width + x] = sum / count
            }
        }

        // Convert back to pixels
        for (i in pixels.indices) {
            val v = blurredValues[i]
            pixels[i] = Color.rgb(v, v, v)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        return result
    }

    // Unified workflow change

    /**
     * Unified workflow selection - automatically determines mode from workflow type
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find the workflow item to determine type
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

        savePreferences()
    }

    // Model selection callbacks

    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
        savePreferences()
    }

    fun onUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedUnet = unet)
        savePreferences()
    }

    fun onVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedVae = vae)
        savePreferences()
    }

    fun onClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip = clip)
        savePreferences()
    }

    fun onClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip1 = clip)
        savePreferences()
    }

    fun onClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip2 = clip)
        savePreferences()
    }

    fun onClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip3 = clip)
        savePreferences()
    }

    fun onClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip4 = clip)
        savePreferences()
    }

    fun onTextEncoderChange(textEncoder: String) {
        _uiState.value = _uiState.value.copy(selectedTextEncoder = textEncoder)
        savePreferences()
    }

    fun onLatentUpscaleModelChange(model: String) {
        _uiState.value = _uiState.value.copy(selectedLatentUpscaleModel = model)
        savePreferences()
    }

    // Unified parameter callbacks

    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(
            megapixels = megapixels,
            megapixelsError = error
        )
        savePreferences()
    }

    // Unified parameter callbacks for inpainting mode

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        savePreferences()
    }

    /**
     * Reset prompt to seasonal default.
     *
     * 重置对象是**当前激活模式那个工作流**的正面提示词（本次改动）：正面词已改为每工作流一份，
     * 若仍去删那个老全局键，会变成"删了一个没人读的键、界面上却恢复不了"。
     */
    fun resetPromptToDefault() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            val defaultPrompt = SeasonalPrompts.getImageToImagePrompt()
            val state = _uiState.value
            // 编辑模式与修复模式各有一套工作流记录，重置的是"此刻正在用的那一个"
            val workflowId = if (state.mode == ImageToImageMode.EDITING) {
                state.selectedEditingWorkflowId
            } else {
                state.selectedWorkflowId
            }

            withContext(Dispatchers.IO) {
                // 老全局键已废弃，顺手清掉
                try {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove("${serverId}_$PREF_POSITIVE_PROMPT").apply()
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "清理老版全局正面提示词失败")
                }

                val storage = workflowValuesStorage
                if (storage != null && workflowId.isNotEmpty()) {
                    // 保留该工作流其它字段，只把正面词写回季节默认词
                    val existing = storage.loadValues(serverId, workflowId)
                    storage.saveValues(
                        serverId,
                        workflowId,
                        (existing ?: WorkflowValues()).copy(positivePrompt = defaultPrompt),
                    )
                }
            }

            // Set prompt to seasonal default
            _uiState.update { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(ImageToImageEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        _uiState.value = _uiState.value.copy(steps = steps, stepsError = error)
        savePreferences()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(cfg = cfg, cfgError = error)
        savePreferences()
    }

    fun onSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(sampler = sampler)
        savePreferences()
    }

    fun onSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(scheduler = scheduler)
        savePreferences()
    }

    fun onSeedModeChange(mode: SeedMode) {
        _uiState.value = _uiState.value.copy(seedMode = mode)
        savePreferences()
    }

    /**
     * 生成提交成功后调用（由 GenerationViewModel 的回调触发）。
     *
     * 按当前模式分发：编辑模式推进 editingSeed、修复模式推进 seed ——
     * 这两个模式各有一套种子，用同一个入口才不会把另一个模式的种子挪走。
     */
    fun advanceSeedIfNeeded() {
        val state = _uiState.value
        val isEditing = state.mode == ImageToImageMode.EDITING
        val mode = if (isEditing) state.editingSeedMode else state.seedMode
        if (!mode.advances) return
        val workflowName = if (isEditing) state.selectedEditingWorkflow else state.selectedWorkflow
        val constraints = WorkflowConstraintsProvider.getConstraints("seed", workflowName)
        val currentSeed = (if (isEditing) state.editingSeed else state.seed).toLongOrNull() ?: 0L
        val next = nextSeed(
            current = currentSeed,
            step = mode.step,
            min = constraints.min.toLong(),
            max = constraints.max.toLong()
        ).toString()
        _uiState.value = if (isEditing) {
            state.copy(editingSeed = next, seedError = null)
        } else {
            state.copy(seed = next, seedError = null)
        }
        savePreferences()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(seed = seed, seedError = error)
        if (error == null) savePreferences()
    }

    fun onRandomizeSeed() {
        // 随机种子限制在 UINT32 范围（与 ComfyUI 官方前端一致），避免超出部分节点
        // 的 INT 上限（如 SeedVR2VideoUpscaler seed max=4294967295）触发 400 校验失败
        val randomSeed = kotlin.random.Random.nextLong(0, 4294967296L).toString()
        _uiState.value = _uiState.value.copy(seed = randomSeed, seedError = null)
        savePreferences()
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(denoise = denoise, denoiseError = error)
        if (error == null) savePreferences()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(batchSize = batchSize, batchSizeError = error)
        if (error == null) savePreferences()
    }

    fun onUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(upscaleMethod = method)
        savePreferences()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(scaleBy = scaleBy, scaleByError = error)
        if (error == null) savePreferences()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(stopAtClipLayer = layer, stopAtClipLayerError = error)
        if (error == null) savePreferences()
    }

    /**
     * 切换"保存为 WebP"（v4.6.1 模块 C）。
     *
     * 修复流与编辑流共用这一个布尔；savePreferences() 内部按当前模式写到对应的
     * 每工作流记录（saveWorkflowValues / saveEditingWorkflowValues）。
     */
    fun onSaveAsWebpChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(saveAsWebp = enabled)
        savePreferences()
    }

    // Positive prompt
    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    /**
     * 切换正面提示词锁。
     *
     * 锁上：此后切换工作流 / 切换模式完全不动正面提示词（不保存也不载入）。
     * 解锁：顺手把当前这个词存进当前激活模式的工作流（"解开就当我的"）。
     */
    fun onPromptLockToggle() {
        val locked = !_uiState.value.positivePromptLocked
        _uiState.value = _uiState.value.copy(positivePromptLocked = locked)
        DebugLogger.d(TAG, "正面提示词锁：${if (locked) "已锁定" else "已解锁"}")
        savePreferences()
    }

    // Unified LoRA chain callbacks

    override fun withLoraChain(state: ImageToImageUiState, chain: List<LoraSelection>) =
        state.copy(loraChain = chain)

    fun onAddLora() =
        updateLoraChain(::savePreferences) { LoraChainManager.addLora(it.loraChain, it.availableLoras) }

    fun onRemoveLora(index: Int) =
        updateLoraChain(::savePreferences) { LoraChainManager.removeLora(it.loraChain, index) }

    fun onLoraNameChange(index: Int, name: String) =
        updateLoraChain(::savePreferences) { LoraChainManager.updateLoraName(it.loraChain, index, name) }

    fun onLoraStrengthChange(index: Int, strength: Float) =
        updateLoraChain(::savePreferences) { LoraChainManager.updateLoraStrength(it.loraChain, index, strength) }

    // Editing mode callbacks

    fun onEditingWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find the workflow item to get its ID
        val workflowItem = state.editingWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onEditingWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current editing workflow values before switching (using workflow ID)
        if (state.selectedEditingWorkflowId.isNotEmpty()) {
            saveEditingWorkflowValues(state.selectedEditingWorkflowId)
        }

        // Load new editing workflow values (single source of truth)
        loadEditingWorkflowValues(workflowItem)

        savePreferences()
    }

    fun onEditingUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedEditingUnet = unet)
        savePreferences()
    }

    fun onEditingLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedEditingLora = lora)
        savePreferences()
    }

    fun onEditingVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedEditingVae = vae)
        savePreferences()
    }

    fun onEditingClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip = clip)
        savePreferences()
    }

    fun onEditingClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip1 = clip)
        savePreferences()
    }

    fun onEditingClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip2 = clip)
        savePreferences()
    }

    fun onEditingClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip3 = clip)
        savePreferences()
    }

    fun onEditingClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip4 = clip)
        savePreferences()
    }

    fun onEditingTextEncoderChange(textEncoder: String) {
        _uiState.value = _uiState.value.copy(selectedEditingTextEncoder = textEncoder)
        savePreferences()
    }

    fun onEditingLatentUpscaleModelChange(model: String) {
        _uiState.value = _uiState.value.copy(selectedEditingLatentUpscaleModel = model)
        savePreferences()
    }

    fun onEditingUpscaleModelChange(model: String) {
        _uiState.value = _uiState.value.copy(selectedEditingUpscale = model)
    }

    fun onEditingCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedEditingCheckpoint = checkpoint)
        savePreferences()
    }

    fun onEditingHighnoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedEditingHighnoiseUnet = unet)
        savePreferences()
    }

    fun onEditingLownoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedEditingLownoiseUnet = unet)
        savePreferences()
    }

    fun onEditingHighnoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedEditingHighnoiseLora = lora)
        savePreferences()
    }

    fun onEditingLownoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedEditingLownoiseLora = lora)
        savePreferences()
    }

    fun onEditingMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(
            editingMegapixels = megapixels,
            megapixelsError = error
        )
        savePreferences()
    }

    fun onEditingStepsChange(steps: String) {
        _uiState.value = _uiState.value.copy(editingSteps = steps)
        savePreferences()
    }

    fun onEditingCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(editingCfg = cfg, cfgError = error)
        savePreferences()
    }

    /**
     * 更新某个动态节点参数的值并持久化（图生图页，通用节点参数引擎）。
     * 占位符清单由 [WorkflowManager.getDynamicParams] 在工作流加载时得到，见 dynamicParams。
     */
    fun onDynamicParamValueChange(placeholder: String, value: String) {
        val state = _uiState.value
        val newValues = state.dynamicParamValues + (placeholder to value)
        // 格式门控开关一变，CLIP 候选要立刻跟着换（理由见 TextToImageViewModel 同名方法）
        if (placeholder == "clip_gguf") {
            val gated = filterClipsByGate(ConnectionManager.modelCache.value.clips, newValues)
            _uiState.value = state.copy(
                dynamicParamValues = newValues,
                availableClips = gated,
                selectedClip = pickClipsAfterGate(state.selectedClip, gated),
                selectedClip1 = pickClipsAfterGate(state.selectedClip1, gated),
                selectedClip2 = pickClipsAfterGate(state.selectedClip2, gated),
                selectedClip3 = pickClipsAfterGate(state.selectedClip3, gated),
                selectedClip4 = pickClipsAfterGate(state.selectedClip4, gated),
            )
        } else {
            _uiState.value = state.copy(dynamicParamValues = newValues)
        }
        savePreferences()
    }

    /**
     * 更新某个动态节点参数的值并持久化（编辑页，通用节点参数引擎）。
     */
    fun onEditingDynamicParamValueChange(placeholder: String, value: String) {
        val state = _uiState.value
        val newValues = state.editingDynamicParamValues + (placeholder to value)
        // 编辑流有自己独立的开关值，过滤要用它（不能用修复流那份）
        if (placeholder == "clip_gguf") {
            val gated = filterClipsByGate(ConnectionManager.modelCache.value.clips, newValues)
            _uiState.value = state.copy(
                editingDynamicParamValues = newValues,
                availableClips = gated,
                selectedEditingClip = pickClipsAfterGate(state.selectedEditingClip, gated),
                selectedEditingClip1 = pickClipsAfterGate(state.selectedEditingClip1, gated),
                selectedEditingClip2 = pickClipsAfterGate(state.selectedEditingClip2, gated),
                selectedEditingClip3 = pickClipsAfterGate(state.selectedEditingClip3, gated),
                selectedEditingClip4 = pickClipsAfterGate(state.selectedEditingClip4, gated),
            )
        } else {
            _uiState.value = state.copy(editingDynamicParamValues = newValues)
        }
        savePreferences()
    }

    fun onEditingSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(editingSampler = sampler)
        savePreferences()
    }

    fun onEditingSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(editingScheduler = scheduler)
        savePreferences()
    }

    fun onEditingNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(editingNegativePrompt = negativePrompt)
        savePreferences()
    }

    fun onEditingSeedModeChange(mode: SeedMode) {
        _uiState.value = _uiState.value.copy(editingSeedMode = mode)
        savePreferences()
    }

    fun onEditingSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(editingSeed = seed, seedError = error)
        if (error == null) savePreferences()
    }

    fun onEditingRandomizeSeed() {
        // 随机种子限制在 UINT32 范围（与 ComfyUI 官方前端一致），避免超出部分节点
        // 的 INT 上限（如 SeedVR2VideoUpscaler seed max=4294967295）触发 400 校验失败
        val randomSeed = kotlin.random.Random.nextLong(0, 4294967296L).toString()
        _uiState.value = _uiState.value.copy(editingSeed = randomSeed, seedError = null)
        savePreferences()
    }

    fun onEditingDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(editingDenoise = denoise, denoiseError = error)
        if (error == null) savePreferences()
    }

    fun onEditingBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(editingBatchSize = batchSize, batchSizeError = error)
        if (error == null) savePreferences()
    }

    fun onEditingUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(editingUpscaleMethod = method)
        savePreferences()
    }

    fun onEditingScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(editingScaleBy = scaleBy, scaleByError = error)
        if (error == null) savePreferences()
    }

    fun onEditingStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(editingStopAtClipLayer = layer, stopAtClipLayerError = error)
        if (error == null) savePreferences()
    }

    // Editing mode LoRA chain callbacks

    fun onAddEditingLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.editingLoraChain, state.availableLoras)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveEditingLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.editingLoraChain, index)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.editingLoraChain, index, name)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.editingLoraChain, index, strength)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    // Editing mode dual LoRA chain callbacks (highnoise/lownoise)

    fun onAddEditingHighnoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.editingHighnoiseLoraChain, state.availableLoras)
        if (newChain === state.editingHighnoiseLoraChain) return // No change

        _uiState.value = state.copy(editingHighnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveEditingHighnoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.editingHighnoiseLoraChain, index)
        if (newChain === state.editingHighnoiseLoraChain) return // No change

        _uiState.value = state.copy(editingHighnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onEditingHighnoiseLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.editingHighnoiseLoraChain, index, name)
        if (newChain === state.editingHighnoiseLoraChain) return // No change

        _uiState.value = state.copy(editingHighnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onEditingHighnoiseLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.editingHighnoiseLoraChain, index, strength)
        if (newChain === state.editingHighnoiseLoraChain) return // No change

        _uiState.value = state.copy(editingHighnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onAddEditingLownoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.editingLownoiseLoraChain, state.availableLoras)
        if (newChain === state.editingLownoiseLoraChain) return // No change

        _uiState.value = state.copy(editingLownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveEditingLownoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.editingLownoiseLoraChain, index)
        if (newChain === state.editingLownoiseLoraChain) return // No change

        _uiState.value = state.copy(editingLownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLownoiseLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.editingLownoiseLoraChain, index, name)
        if (newChain === state.editingLownoiseLoraChain) return // No change

        _uiState.value = state.copy(editingLownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLownoiseLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.editingLownoiseLoraChain, index, strength)
        if (newChain === state.editingLownoiseLoraChain) return // No change

        _uiState.value = state.copy(editingLownoiseLoraChain = newChain)
        savePreferences()
    }

    // Validation

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        // Only enforce positive prompt when the workflow actually uses it (per placeholders)
        val promptRequired = state.mode != ImageToImageMode.EDITING || state.capabilities.hasPositivePrompt
        if (promptRequired && state.positivePrompt.isBlank()) {
            return false
        }

        return when (state.mode) {
            ImageToImageMode.EDITING -> {
                // Editing mode: source image required, plus validation errors
                state.sourceImage != null &&
                state.stepsError == null &&
                state.megapixelsError == null &&
                state.cfgError == null
            }
            ImageToImageMode.INPAINTING -> {
                // Inpainting mode: source image with mask required, plus validation errors
                state.sourceImage != null &&
                state.megapixelsError == null &&
                state.stepsError == null &&
                state.cfgError == null
            }
        }
    }

    /**
     * Upload source image (with mask for inpainting, without for editing) to ComfyUI and prepare workflow
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        // Check connection before uploading
        // 超时兜底：ensureConnection 在"另一个重连会话已开始"的分支里**不会回调**
        // （见 GenerationViewModel.armSubmitGateWatchdog 的注释），不设超时会永久挂起。
        val connected = withTimeoutOrNull(CONNECTION_WAIT_TIMEOUT_MS) {
            suspendCoroutine<Boolean> { cont ->
                ConnectionManager.ensureConnection(context) { success ->
                    cont.resumeWith(Result.success(success))
                }
            }
        } ?: run {
            // 超时视为连接失败。此场景 ensureConnection 未回调、不会弹重连失败对话框，
            // 主动提示，避免静默失败
            DebugLogger.w(TAG, "等待连接超时（${CONNECTION_WAIT_TIMEOUT_MS}ms），视为连接失败")
            _events.emit(ImageToImageEvent.ShowToast(R.string.msg_connection_lost))
            return null
        }
        if (!connected) return null  // Dialog already shown by ensureConnection

        val state = _uiState.value
        val sourceImage = state.sourceImage ?: return null

        DebugLogger.i(TAG, "Preparing workflow (mode: ${state.mode})")

        _uiState.update { it.copy(isUploading = true) }
        return try {
            when (state.mode) {
                ImageToImageMode.EDITING -> prepareEditingWorkflow(client, sourceImage, state)
                ImageToImageMode.INPAINTING -> prepareInpaintingWorkflow(client, sourceImage, state)
            }
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    /**
     * Prepare workflow for Editing mode (no mask, optional reference images)
     */
    private suspend fun prepareEditingWorkflow(
        client: ComfyUIClient,
        sourceImage: Bitmap,
        state: ImageToImageUiState
    ): String? {
        // Convert source image to WebP bytes
        // v4.6.1 模块 D：上传前转 WebP（保持原分辨率），剥离 PNG 内嵌元数据，体积通常降到 10%–30%
        val sourceBytes = withContext(Dispatchers.IO) {
            UploadImageEncoder.encode(sourceImage, preserveAlpha = false)
        }
        if (sourceBytes == null) {
            // WebP 编码失败（compress 返回 false）：空字节不能推进上传链路
            _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            return null
        }

        // Upload source image
        data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
        val sourceResult: UploadResult = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(
                    sourceBytes,
                    UuidUtils.generateUniqueUploadFilename("editing_source", UploadImageEncoder.EXTENSION),
                    mimeType = UploadImageEncoder.MIME,
                    onProgress = { written, total ->
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                            _uiState.update { it.copy(uploadProgress = pct) }
                        }
                    }
                ) { success, filename, _, failureType ->
                    continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                }
            }
        }

        if (sourceResult.filename == null) {
            // Check for stall or auth failure - show dialog instead of toast
            if (sourceResult.failureType == ConnectionFailure.STALLED ||
                sourceResult.failureType == ConnectionFailure.AUTHENTICATION) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, sourceResult.failureType)
                }
            } else {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            }
            return null
        }
        _uiState.update { it.copy(uploadProgress = -1) }
        val uploadedSource = sourceResult.filename

        // Upload reference image 1 (if present)
        var uploadedRef1: String? = null
        if (state.referenceImage1 != null) {
            val ref1Bytes = withContext(Dispatchers.IO) {
                // v4.6.1 模块 D：参考图同样转 WebP（保持分辨率）
                UploadImageEncoder.encode(state.referenceImage1, preserveAlpha = false)
            }
            if (ref1Bytes == null) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
                return null
            }
            val ref1Result: UploadResult = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.uploadImage(
                        ref1Bytes,
                        UuidUtils.generateUniqueUploadFilename("reference_1", UploadImageEncoder.EXTENSION),
                        mimeType = UploadImageEncoder.MIME
                    ) { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }
            if (ref1Result.filename == null && (ref1Result.failureType == ConnectionFailure.STALLED ||
                ref1Result.failureType == ConnectionFailure.AUTHENTICATION)) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, ref1Result.failureType)
                }
                return null
            }
            uploadedRef1 = ref1Result.filename
        }

        // Upload reference image 2 (if present)
        var uploadedRef2: String? = null
        if (state.referenceImage2 != null) {
            val ref2Bytes = withContext(Dispatchers.IO) {
                // v4.6.1 模块 D：参考图同样转 WebP（保持分辨率）
                UploadImageEncoder.encode(state.referenceImage2, preserveAlpha = false)
            }
            if (ref2Bytes == null) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
                return null
            }
            val ref2Result: UploadResult = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.uploadImage(
                        ref2Bytes,
                        UuidUtils.generateUniqueUploadFilename("reference_2", UploadImageEncoder.EXTENSION),
                        mimeType = UploadImageEncoder.MIME
                    ) { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }
            if (ref2Result.filename == null && (ref2Result.failureType == ConnectionFailure.STALLED ||
                ref2Result.failureType == ConnectionFailure.AUTHENTICATION)) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, ref2Result.failureType)
                }
                return null
            }
            uploadedRef2 = ref2Result.filename
        }

        // Prepare editing workflow JSON
        // 提交前兜底（1.3）：替换占位符之前再清洗一次。读取阶段在「服务器清单未知」（冷启动）
        // 时是原样放行的，所以这里是脏数据第一次被纠正的地方；顺手同步回界面状态，
        // 免得下拉框继续显示一个提交不出去的值（下一次保存会把它落盘）
        val safeEditingSampler = SamplerOptions.sanitizeSampler(state.editingSampler, state.availableSamplers)
        val safeEditingScheduler = SamplerOptions.sanitizeScheduler(state.editingScheduler, state.availableSchedulers)
        if (safeEditingSampler != state.editingSampler || safeEditingScheduler != state.editingScheduler) {
            DebugLogger.w(
                TAG,
                "编辑流采样参数非法，已纠正为 sampler=$safeEditingSampler, scheduler=$safeEditingScheduler"
            )
            _uiState.update {
                it.copy(editingSampler = safeEditingSampler, editingScheduler = safeEditingScheduler)
            }
        }

        val baseWorkflow = WorkflowManager.prepareImageEditingWorkflowById(
            workflowId = state.selectedEditingWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.editingNegativePrompt,
            checkpoint = state.selectedEditingCheckpoint,
            unet = state.selectedEditingUnet,
            lora = state.selectedEditingLora,
            vae = state.selectedEditingVae,
            clip = state.selectedEditingClip,
            clip1 = state.selectedEditingClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedEditingClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedEditingClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedEditingClip4.takeIf { it.isNotEmpty() },
            textEncoder = state.selectedEditingTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedEditingLatentUpscaleModel.takeIf { it.isNotEmpty() },
            upscaleModel = state.selectedEditingUpscale.takeIf { it.isNotEmpty() },
            highnoiseUnet = state.selectedEditingHighnoiseUnet,
            lownoiseUnet = state.selectedEditingLownoiseUnet,
            highnoiseLora = state.selectedEditingHighnoiseLora,
            lownoiseLora = state.selectedEditingLownoiseLora,
            megapixels = state.editingMegapixels.toFloatOrNull() ?: 2.0f,
            steps = state.editingSteps.toIntOrNull() ?: 4,
            cfg = state.editingCfg.toFloatOrNull() ?: 1.0f,
            // 提交前兜底（1.3）：用上面清洗后的取值，替换逻辑本身不动
            samplerName = safeEditingSampler,
            scheduler = safeEditingScheduler,
            denoise = state.editingDenoise.toFloatOrNull() ?: 1.0f,
            sourceImageFilename = uploadedSource,
            referenceImage1Filename = uploadedRef1,
            referenceImage2Filename = uploadedRef2,
            // 编辑模式的面板绑的是 editingSeed / editingSeedMode，必须传这一对，
            // 否则用户在编辑面板改的种子会被丢弃（走 state.seed 会读到另一份状态）。
            seed = state.editingSeed.toLongOrNull(),
            randomSeed = state.editingSeedMode.usesRandomValue,
            // 动态节点参数（编辑页，通用节点参数引擎）
            dynamicParams = state.editingDynamicParamValues,
            // 输出端格式（v4.6.1 模块 C）：只在图片路径生效，3D 流不走这里
            saveAsWebp = state.saveAsWebp
        ) ?: return null

        // Inject additional LoRA chain if configured
        return WorkflowManager.injectLoraChain(baseWorkflow, state.editingLoraChain, WorkflowType.ITI_EDITING)
    }

    /**
     * Prepare workflow for Inpainting mode (requires mask)
     */
    private suspend fun prepareInpaintingWorkflow(
        client: ComfyUIClient,
        sourceImage: Bitmap,
        state: ImageToImageUiState
    ): String? {
        // 生成遮罩 + 与源图在 alpha 通道合成：纯 CPU 的逐像素运算（4000×3000 时约 4 亿次
        // 采样、6~7 个 48MB 级缓冲），留在主线程会让点"生成"后界面直接冻住数秒。
        // 挪到 Default 线程池执行，运算逻辑与结果完全不变。
        val imageWithMask: Bitmap? = withContext(Dispatchers.Default) {
            val mask = generateMaskBitmap() ?: return@withContext null
            combineImageWithMask(sourceImage, mask).also { mask.recycle() }
        }
        if (imageWithMask == null) {
            _events.emit(ImageToImageEvent.ShowToast(R.string.hint_paint_mask))
            return null
        }

        // Convert bitmap to WebP byte array
        // v4.6.1 模块 D：修复模式的合成图把遮罩写进 alpha 通道，**必须无损**，否则遮罩会被压坏
        val imageBytes = withContext(Dispatchers.IO) {
            UploadImageEncoder.encode(imageWithMask, preserveAlpha = true)
        }
        if (imageBytes == null) {
            _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            imageWithMask.recycle()
            return null
        }

        imageWithMask.recycle()

        // Upload to ComfyUI
        data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
        val uploadResult: UploadResult = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(
                    imageBytes,
                    UuidUtils.generateUniqueUploadFilename("inpaint_source", UploadImageEncoder.EXTENSION),
                    mimeType = UploadImageEncoder.MIME,
                    onProgress = { written, total ->
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                            _uiState.update { it.copy(uploadProgress = pct) }
                        }
                    }
                ) { success, filename, _, failureType ->
                    continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                }
            }
        }

        _uiState.update { it.copy(uploadProgress = -1) }

        if (uploadResult.filename == null) {
            // Check for stall or auth failure - show dialog instead of toast
            if (uploadResult.failureType == ConnectionFailure.STALLED ||
                uploadResult.failureType == ConnectionFailure.AUTHENTICATION) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, uploadResult.failureType)
                }
            } else {
                _events.emit(ImageToImageEvent.ShowToast(R.string.error_save_image))
            }
            return null
        }
        val uploadedFilename = uploadResult.filename

        // Prepare workflow JSON using unified fields
        // 提交前兜底（1.3）：替换占位符之前再清洗一次（理由同编辑流：读取时清单未知不判废）
        val safeInpaintSampler = SamplerOptions.sanitizeSampler(state.sampler, state.availableSamplers)
        val safeInpaintScheduler = SamplerOptions.sanitizeScheduler(state.scheduler, state.availableSchedulers)
        if (safeInpaintSampler != state.sampler || safeInpaintScheduler != state.scheduler) {
            DebugLogger.w(
                TAG,
                "修复流采样参数非法，已纠正为 sampler=$safeInpaintSampler, scheduler=$safeInpaintScheduler"
            )
            _uiState.update { it.copy(sampler = safeInpaintSampler, scheduler = safeInpaintScheduler) }
        }

        val baseWorkflow = WorkflowManager.prepareImageToImageWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Model selections - pass all, placeholder substitution handles which are used
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            vae = state.selectedVae,
            clip = state.selectedClip,
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoder = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            // Unified parameters
            megapixels = state.megapixels.toFloatOrNull() ?: 1.0f,
            steps = state.steps.toIntOrNull() ?: 20,
            cfg = state.cfg.toFloatOrNull() ?: 7.0f,
            // 提交前兜底（1.3）：用上面清洗后的取值，替换逻辑本身不动
            samplerName = safeInpaintSampler,
            scheduler = safeInpaintScheduler,
            denoise = state.denoise.toFloatOrNull() ?: 1.0f,
            imageFilename = uploadedFilename,
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues,
            // 输出端格式（v4.6.1 模块 C）：true 时把 SaveImage 换成插件 WebP 保存节点
            saveAsWebp = state.saveAsWebp
        ) ?: return null

        // Inject unified LoRA chain
        return WorkflowManager.injectLoraChain(baseWorkflow, state.loraChain, WorkflowType.ITI_INPAINTING)
    }

    /**
     * Combine source image with mask in alpha channel (mask white = transparent)
     */
    private fun combineImageWithMask(source: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        val sourcePixels = IntArray(source.width * source.height)
        val maskPixels = IntArray(mask.width * mask.height)

        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)

        for (i in sourcePixels.indices) {
            val r = Color.red(sourcePixels[i])
            val g = Color.green(sourcePixels[i])
            val b = Color.blue(sourcePixels[i])

            // Mask brightness - white = inpaint area = transparent alpha
            val maskBrightness = Color.red(maskPixels[i])
            val alpha = 255 - maskBrightness // Invert: white mask -> 0 alpha (transparent)

            sourcePixels[i] = Color.argb(alpha, r, g, b)
        }

        result.setPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    /**
     * Fetch and save generated image from history
     * @param promptId The prompt ID to fetch
     * @param onComplete Callback with success boolean (true if image was fetched and set)
     */
    fun fetchGeneratedImage(promptId: String, onComplete: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onComplete(false)
            return
        }
        val context = applicationContext ?: run {
            onComplete(false)
            return
        }

        _uiState.update { it.copy(isFetching = true) }

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) {
                _uiState.update { it.copy(isFetching = false) }
                onComplete(false)
                return@fetchHistory
            }

            val promptHistory = historyJson.optJSONObject(promptId)
            val outputs = promptHistory?.optJSONObject("outputs")

            // 技能流（提示词强化）不出图：把文本产物单独发布（普通出图流这里是空操作）
            if (outputs != null) {
                SkillTextResult.publish(SkillTextResult.extract(outputs))
            }

            if (outputs == null) {
                _uiState.update { it.copy(isFetching = false) }
                onComplete(false)
                return@fetchHistory
            }

            // Find image in outputs
            val outputKeys = outputs.keys()
            while (outputKeys.hasNext()) {
                val nodeId = outputKeys.next()
                val nodeOutput = outputs.optJSONObject(nodeId)
                val images = nodeOutput?.optJSONArray("images")

                if (images != null && images.length() > 0) {
                    val imageInfo = images.optJSONObject(0)
                    val filename = imageInfo?.optString("filename") ?: continue
                    val subfolder = imageInfo.optString("subfolder", "")
                    val type = imageInfo.optString("type", "output")

                    client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                        _uiState.update { it.copy(isFetching = false) }
                        if (bitmap != null) {
                            // Store in cache (memory or disk based on mode)
                            MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap, context)

                            _uiState.value = _uiState.value.copy(
                                previewImage = bitmap,
                                previewImageFilename = filename,
                                previewImageSubfolder = subfolder,
                                previewImageType = type,
                                viewMode = ImageToImageViewMode.PREVIEW
                            )
                            onComplete(true)
                        } else if (failureType == ConnectionFailure.STALLED) {
                            ConnectionManager.showConnectionAlert(context, failureType)
                            onComplete(false)
                        } else {
                            onComplete(false)
                        }
                    }
                    return@fetchHistory
                }
            }

            _uiState.update { it.copy(isFetching = false) }
            onComplete(false)
        }
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewImage = bitmap)
        saveLastPreviewImage(bitmap)
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return // Already cleared for this promptId
        }
        lastClearedForPromptId = promptId
        // Evict from cache so loadSavedImages() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItiPreview)
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    // Event handling

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel
        // 种子「每次+1 / 每次−1」的落点：提交成功后由 GenerationViewModel 回调。
        // 图生图内部再按模式分发到 editingSeed / seed
        generationViewModel.registerSubmittedCallback(OWNER_ID) { advanceSeedIfNeeded() }

        // If generation is running for this screen, switch to preview mode
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToImageViewMode.PREVIEW)
        }

        generationViewModel.registerEventHandler(OWNER_ID) { event ->
            handleGenerationEvent(event)
        }
    }

    /**
     * Handle generation events from the GenerationViewModel.
     */
    private fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.PreviewImage -> {
                onPreviewBitmapChange(event.bitmap)
            }
            is GenerationEvent.ImageGenerated -> {
                val promptId = event.promptId
                DebugLogger.i(TAG, "ImageGenerated: ${Obfuscator.promptId(promptId)}")
                fetchGeneratedImage(promptId) { success ->
                    if (success) {
                        generationViewModelRef?.completeGeneration(promptId)
                    }
                    // If not successful, don't complete - will retry on next return
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                DebugLogger.w(TAG, "ConnectionLostDuringGeneration")
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(ImageToImageEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(ImageToImageEvent.ShowToastMessage(event.message))
                }
                // DON'T call completeGeneration() here - this may just be a connection error
                // The server might still complete the generation
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Don't clear - keep the preview visible during navigation
                // New live previews will naturally replace the current one
            }
            is GenerationEvent.GenerationCancelled -> {
                // v4.6.1：取消后清掉最后一帧，避免"已经取消了但画面还停在半张图"
                clearPreview()
            }
            is GenerationEvent.CancelRejected -> {
                viewModelScope.launch {
                    _events.emit(ImageToImageEvent.ShowToast(event.messageResId))
                }
            }
            else -> {}
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap, applicationContext)
    }
}
