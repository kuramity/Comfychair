package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.VideoOptions
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.viewmodel.base.BaseVideoGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.VideoGenerationState
import sh.hnet.comfychair.viewmodel.base.VideoStateSnapshot
import sh.hnet.comfychair.viewmodel.base.VideoWorkflowItem

/**
 * B4.2-B 视频对合并：原 data class 与 ITV 侧逐字节相同，归一为共享的
 * [VideoWorkflowItem]；typealias 保持既有调用点零改动。
 */
typealias TtvWorkflowItem = VideoWorkflowItem

/**
 * UI state for the Text-to-Video screen
 */
data class TextToVideoUiState(
    // Preview state
    override val previewBitmap: android.graphics.Bitmap? = null,
    override val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    override val selectedWorkflow: String = "",
    override val selectedWorkflowId: String = "",  // Workflow ID for storage
    override val availableWorkflows: List<TtvWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    override val workflowPlaceholders: Set<String> = emptySet(),

    // 动态节点参数（通用节点参数引擎）：工作流里「未识别」的 {{占位符}}。
    // dynamicParams 是控件清单（UI 依 control 渲染），dynamicParamValues 是用户当前值（按占位符名索引）。
    override val dynamicParams: List<DynamicParam> = emptyList(),
    override val dynamicParamValues: Map<String, String> = emptyMap(),
    // 推导 dynamicParams 时 /object_info 注册表是否已就绪（与 dynamicParams 同一次快照，UI 提示条用）
    override val dynamicParamsRegistryReady: Boolean = false,

    // Model selections - single-model patterns (e.g., LTX 2.0)
    override val selectedCheckpoint: String = "",
    override val selectedUnet: String = "",
    override val selectedLoraName: String = "",  // Mandatory LoRA dropdown
    /** 主 LoRA 权重（{{lora_strength}}）：只有工作流带该占位符时配置面板才显示滑块 */
    override val loraStrength: Float = 1.0f,
    // Model selections - dual-model patterns (e.g., Wan 2.2)
    override val selectedHighnoiseUnet: String = "",
    override val selectedLownoiseUnet: String = "",
    override val selectedHighnoiseLora: String = "",
    override val selectedLownoiseLora: String = "",
    // Model selections - common
    override val selectedVae: String = "",
    override val selectedClip: String = "",
    override val selectedClip1: String = "",
    override val selectedClip2: String = "",
    override val selectedClip3: String = "",
    override val selectedClip4: String = "",
    override val selectedTextEncoder: String = "",
    override val selectedLatentUpscaleModel: String = "",

    // Available models
    override val availableCheckpoints: List<String> = emptyList(),
    override val availableUnets: List<String> = emptyList(),
    override val availableLoras: List<String> = emptyList(),
    override val availableVaes: List<String> = emptyList(),
    override val availableClips: List<String> = emptyList(),
    override val availableUpscaleMethods: List<String> = emptyList(),
    override val availableTextEncoders: List<String> = emptyList(),
    override val availableLatentUpscaleModels: List<String> = emptyList(),
    override val availableSamplers: List<String> = emptyList(),
    override val availableSchedulers: List<String> = emptyList(),
    // 视频分辨率选择器 / 加速模式（按节点类型取，见 ConnectionManager.extractModelLists）
    override val availableAspectRatios: List<String> = emptyList(),
    override val availableSpeedModes: List<String> = emptyList(),

    // Workflow-specific filtered options (from actual node type in workflow)
    override val filteredCheckpoints: List<String>? = null,
    override val filteredUnets: List<String>? = null,
    override val filteredLoras: List<String>? = null,  // For mandatory LoRA dropdown
    override val filteredVaes: List<String>? = null,
    override val filteredClips: List<String>? = null,
    override val filteredClips1: List<String>? = null,
    override val filteredClips2: List<String>? = null,
    override val filteredClips3: List<String>? = null,
    override val filteredClips4: List<String>? = null,
    override val filteredTextEncoders: List<String>? = null,
    override val filteredLatentUpscaleModels: List<String>? = null,

    // Generation parameters
    override val width: String = "848",
    override val height: String = "480",
    override val megapixels: String = "1.0",
    override val length: String = "33",
    override val fps: String = "16",
    override val steps: String = "20",
    override val cfg: String = "7.0",
    override val sampler: String = "euler",
    override val scheduler: String = "normal",
    // 视频分辨率选择器 / 加速模式：缺省值取服务器选项的第一项（加载时以工作流 defaults 为准）
    override val aspectRatio: String = VideoOptions.ASPECT_RATIOS.first(),
    override val speedMode: String = VideoOptions.SPEED_MODES.first(),
    override val seedMode: SeedMode = SeedMode.RANDOM,
    override val seed: String = "0",
    override val denoise: String = "1.0",
    override val batchSize: String = "1",
    override val upscaleMethod: String = "nearest-exact",
    override val scaleBy: String = "1.5",
    override val stopAtClipLayer: String = "-1",

    // Validation errors
    override val widthError: String? = null,
    override val heightError: String? = null,
    override val megapixelsError: String? = null,
    override val lengthError: String? = null,
    override val fpsError: String? = null,
    override val stepsError: String? = null,
    override val cfgError: String? = null,
    override val seedError: String? = null,
    override val denoiseError: String? = null,
    override val batchSizeError: String? = null,
    override val scaleByError: String? = null,
    override val stopAtClipLayerError: String? = null,

    // Positive prompt (global)
    override val positivePrompt: String = "",

    // Negative prompt (per-workflow)
    override val negativePrompt: String = "",

    // Deferred model selections (for restoring after models load)
    // Single-model patterns
    override val deferredCheckpoint: String? = null,
    override val deferredUnet: String? = null,
    override val deferredLoraName: String? = null,
    // Dual-model patterns
    override val deferredHighnoiseUnet: String? = null,
    override val deferredLownoiseUnet: String? = null,
    override val deferredHighnoiseLora: String? = null,
    override val deferredLownoiseLora: String? = null,
    // Common
    override val deferredVae: String? = null,
    override val deferredClip: String? = null,
    override val deferredClip1: String? = null,
    override val deferredClip2: String? = null,
    override val deferredClip3: String? = null,
    override val deferredClip4: String? = null,
    override val deferredTextEncoder: String? = null,
    override val deferredLatentUpscaleModel: String? = null,

    // Additional LoRA chains (optional, 0-5 LoRAs on top of mandatory LightX2V LoRAs)
    override val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    override val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Primary LoRA chain (for CommonGenerationState interface - video screens use dual chains)
    override val loraChain: List<LoraSelection> = emptyList(),

    // Workflow capabilities (unified flags derived from placeholders)
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Fetch state
    override val isFetching: Boolean = false
) : VideoGenerationState

/**
 * One-time events for Text-to-Video screen
 */
sealed class TextToVideoEvent {
    data class ShowToast(val messageResId: Int) : TextToVideoEvent()
    data class ShowToastMessage(val message: String) : TextToVideoEvent()
}

/**
 * 共享快照 → 文生视频 UiState 的回拷（B4.2-B）。
 *
 * 拷贝**全部**构造参数：本页 UiState 的所有字段都已搬进 [VideoGenerationState]，
 * 漏任何一个，基类共享函数对这个字段的写入就会丢（由 VideoStateSnapshotRoundTripTest 钉住）。
 * 抽成顶级 internal 纯函数是为了能在 JVM 单测里直接做快照回转测试。
 */
internal fun TextToVideoUiState.withVideoSnapshot(snapshot: VideoStateSnapshot): TextToVideoUiState =
    copy(
        previewBitmap = snapshot.previewBitmap,
        currentVideoUri = snapshot.currentVideoUri,
        selectedWorkflow = snapshot.selectedWorkflow,
        selectedWorkflowId = snapshot.selectedWorkflowId,
        availableWorkflows = snapshot.availableWorkflows,
        workflowPlaceholders = snapshot.workflowPlaceholders,
        dynamicParams = snapshot.dynamicParams,
        dynamicParamValues = snapshot.dynamicParamValues,
        dynamicParamsRegistryReady = snapshot.dynamicParamsRegistryReady,
        selectedCheckpoint = snapshot.selectedCheckpoint,
        selectedUnet = snapshot.selectedUnet,
        selectedLoraName = snapshot.selectedLoraName,
        loraStrength = snapshot.loraStrength,
        selectedHighnoiseUnet = snapshot.selectedHighnoiseUnet,
        selectedLownoiseUnet = snapshot.selectedLownoiseUnet,
        selectedHighnoiseLora = snapshot.selectedHighnoiseLora,
        selectedLownoiseLora = snapshot.selectedLownoiseLora,
        selectedVae = snapshot.selectedVae,
        selectedClip = snapshot.selectedClip,
        selectedClip1 = snapshot.selectedClip1,
        selectedClip2 = snapshot.selectedClip2,
        selectedClip3 = snapshot.selectedClip3,
        selectedClip4 = snapshot.selectedClip4,
        selectedTextEncoder = snapshot.selectedTextEncoder,
        selectedLatentUpscaleModel = snapshot.selectedLatentUpscaleModel,
        availableCheckpoints = snapshot.availableCheckpoints,
        availableUnets = snapshot.availableUnets,
        availableLoras = snapshot.availableLoras,
        availableVaes = snapshot.availableVaes,
        availableClips = snapshot.availableClips,
        availableUpscaleMethods = snapshot.availableUpscaleMethods,
        availableTextEncoders = snapshot.availableTextEncoders,
        availableLatentUpscaleModels = snapshot.availableLatentUpscaleModels,
        availableSamplers = snapshot.availableSamplers,
        availableSchedulers = snapshot.availableSchedulers,
        availableAspectRatios = snapshot.availableAspectRatios,
        availableSpeedModes = snapshot.availableSpeedModes,
        filteredCheckpoints = snapshot.filteredCheckpoints,
        filteredUnets = snapshot.filteredUnets,
        filteredLoras = snapshot.filteredLoras,
        filteredVaes = snapshot.filteredVaes,
        filteredClips = snapshot.filteredClips,
        filteredClips1 = snapshot.filteredClips1,
        filteredClips2 = snapshot.filteredClips2,
        filteredClips3 = snapshot.filteredClips3,
        filteredClips4 = snapshot.filteredClips4,
        filteredTextEncoders = snapshot.filteredTextEncoders,
        filteredLatentUpscaleModels = snapshot.filteredLatentUpscaleModels,
        width = snapshot.width,
        height = snapshot.height,
        megapixels = snapshot.megapixels,
        length = snapshot.length,
        fps = snapshot.fps,
        steps = snapshot.steps,
        cfg = snapshot.cfg,
        sampler = snapshot.sampler,
        scheduler = snapshot.scheduler,
        aspectRatio = snapshot.aspectRatio,
        speedMode = snapshot.speedMode,
        seedMode = snapshot.seedMode,
        seed = snapshot.seed,
        denoise = snapshot.denoise,
        batchSize = snapshot.batchSize,
        upscaleMethod = snapshot.upscaleMethod,
        scaleBy = snapshot.scaleBy,
        stopAtClipLayer = snapshot.stopAtClipLayer,
        widthError = snapshot.widthError,
        heightError = snapshot.heightError,
        megapixelsError = snapshot.megapixelsError,
        lengthError = snapshot.lengthError,
        fpsError = snapshot.fpsError,
        stepsError = snapshot.stepsError,
        cfgError = snapshot.cfgError,
        seedError = snapshot.seedError,
        denoiseError = snapshot.denoiseError,
        batchSizeError = snapshot.batchSizeError,
        scaleByError = snapshot.scaleByError,
        stopAtClipLayerError = snapshot.stopAtClipLayerError,
        positivePrompt = snapshot.positivePrompt,
        negativePrompt = snapshot.negativePrompt,
        deferredCheckpoint = snapshot.deferredCheckpoint,
        deferredUnet = snapshot.deferredUnet,
        deferredLoraName = snapshot.deferredLoraName,
        deferredHighnoiseUnet = snapshot.deferredHighnoiseUnet,
        deferredLownoiseUnet = snapshot.deferredLownoiseUnet,
        deferredHighnoiseLora = snapshot.deferredHighnoiseLora,
        deferredLownoiseLora = snapshot.deferredLownoiseLora,
        deferredVae = snapshot.deferredVae,
        deferredClip = snapshot.deferredClip,
        deferredClip1 = snapshot.deferredClip1,
        deferredClip2 = snapshot.deferredClip2,
        deferredClip3 = snapshot.deferredClip3,
        deferredClip4 = snapshot.deferredClip4,
        deferredTextEncoder = snapshot.deferredTextEncoder,
        deferredLatentUpscaleModel = snapshot.deferredLatentUpscaleModel,
        highnoiseLoraChain = snapshot.highnoiseLoraChain,
        lownoiseLoraChain = snapshot.lownoiseLoraChain,
        loraChain = snapshot.loraChain,
        capabilities = snapshot.capabilities,
        isFetching = snapshot.isFetching
    )

/**
 * ViewModel for the Text-to-Video screen
 */
class TextToVideoViewModel :
    BaseVideoGenerationViewModel<TextToVideoUiState, TextToVideoEvent>(OWNER_ID) {

    override val initialState = TextToVideoUiState()

    // Constants
    companion object {
        const val OWNER_ID = "TEXT_TO_VIDEO"
    }

    // ---- 两侧语义分叉点（见基类各类成员 KDoc） ----

    override val logTag = "TextToVideo"
    override val prefsName = "TextToVideoFragmentPrefs"
    override val videoWorkflowType = WorkflowType.TTV
    override val defaultPositivePrompt: String get() = SeasonalPrompts.getTextToVideoPrompt()
    override val videoFilePrefix = VideoUtils.FilePrefix.TEXT_TO_VIDEO

    override fun currentVideoPromptId(): String? = MediaStateHolder.getCurrentTtvPromptId()

    override fun clearCurrentVideoPromptId() {
        MediaStateHolder.clearCurrentTtvPromptId()
    }

    override fun videoMediaKey(promptId: String): MediaStateHolder.MediaKey =
        MediaStateHolder.MediaKey.TtvVideo(promptId)

    override val previewMediaKey: MediaStateHolder.MediaKey = MediaStateHolder.MediaKey.TtvPreview

    override fun showToastEvent(messageResId: Int) {
        viewModelScope.launch { _events.emit(TextToVideoEvent.ShowToast(messageResId)) }
    }

    override fun showToastMessageEvent(message: String) {
        viewModelScope.launch { _events.emit(TextToVideoEvent.ShowToastMessage(message)) }
    }

    override fun applyVideoSnapshot(
        state: TextToVideoUiState,
        snapshot: VideoStateSnapshot
    ): TextToVideoUiState = state.withVideoSnapshot(snapshot)

    /**
     * selectedClip3 的 defaults 中间级：文生视频保持三级回退口径
     * （saved → defaults?.clip3Model → state）；ITV 侧是有意分歧的两级口径（saved → state），
     * 不在此处静默统一。
     */
    override fun clip3DefaultFromDefaults(defaults: WorkflowDefaults?, clips: List<String>): String? =
        defaults?.clip3Model?.takeIf { it in clips }

    // Primary LoRA chain operations (for single-model workflows like LTX 2.0)
    override fun withLoraChain(state: TextToVideoUiState, chain: List<LoraSelection>) =
        state.copy(loraChain = chain)

    fun prepareWorkflow(): String? {
        val state = _uiState.value

        // Validate all fields
        val dims = validatedVideoDims(state) ?: return null

        // 提交前兜底（1.3）：视频页的 sampler / scheduler 不落盘（见 VideoGenerationSupport.buildWorkflowValues），
        // 取值来自缺省常量，但备份恢复 / 节点编辑等旁路仍可能把它们写脏，这里统一拦一道。
        // 改了值就同步回界面（与文生图 prepareWorkflowJson 同口径：下拉框不该继续显示脏值，
        // 下次提交也不必再清洗一遍）
        val (safeSampler, safeScheduler) = sanitizeVideoSamplers(state)

        val baseWorkflow = WorkflowManager.prepareVideoWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Single-model patterns (e.g., LTX 2.0)
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            lora = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = state.selectedHighnoiseUnet,
            lownoiseUnet = state.selectedLownoiseUnet,
            highnoiseLora = state.selectedHighnoiseLora,
            lownoiseLora = state.selectedLownoiseLora,
            // Common models
            vae = state.selectedVae,
            clip = state.selectedClip,
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoder = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            width = dims.width,
            height = dims.height,
            length = dims.length,
            fps = dims.fps,
            // Generation parameters
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            // 提交前兜底（1.3）：视频页的 sampler / scheduler 不落盘（见 VideoGenerationSupport.buildWorkflowValues），
            // 取值来自缺省常量，但备份恢复 / 节点编辑等旁路仍可能把它们写脏，这里统一拦一道
            samplerName = safeSampler,
            scheduler = safeScheduler,
            denoise = state.denoise.toFloatOrNull(),
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 视频分辨率选择器 / 加速模式
            megapixels = state.megapixels.toFloatOrNull(),
            aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
            speedMode = state.speedMode.takeIf { it.isNotEmpty() },
            // 主 LoRA 权重（工作流没有 {{lora_strength}} 时不会被替换）
            loraStrength = state.loraStrength,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        ) ?: return null

        return injectVideoLoraChains(baseWorkflow, state)
    }
}
