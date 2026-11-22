package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.VideoOptions
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.PromptTargetFactory
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.PlayingVideoRegistry
import sh.hnet.comfychair.viewmodel.base.VideoGenerationState
import sh.hnet.comfychair.viewmodel.base.VideoGenerationSupport

/**
 * Represents a workflow item with display name
 */
data class TtvWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name
    val type: WorkflowType      // TTV
) : WorkflowItemBase

/**
 * UI state for the Text-to-Video screen
 */
data class TextToVideoUiState(
    // Preview state
    val previewBitmap: android.graphics.Bitmap? = null,
    val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    override val selectedWorkflow: String = "",
    override val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<TtvWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    val workflowPlaceholders: Set<String> = emptySet(),

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
    val widthError: String? = null,
    val heightError: String? = null,
    override val megapixelsError: String? = null,
    val lengthError: String? = null,
    val fpsError: String? = null,
    override val stepsError: String? = null,
    override val cfgError: String? = null,
    override val seedError: String? = null,
    override val denoiseError: String? = null,
    override val batchSizeError: String? = null,
    override val scaleByError: String? = null,
    override val stopAtClipLayerError: String? = null,

    // Positive prompt (global)
    val positivePrompt: String = "",

    // Negative prompt (per-workflow)
    override val negativePrompt: String = "",

    // Deferred model selections (for restoring after models load)
    // Single-model patterns
    val deferredCheckpoint: String? = null,
    val deferredUnet: String? = null,
    val deferredLoraName: String? = null,
    // Dual-model patterns
    val deferredHighnoiseUnet: String? = null,
    val deferredLownoiseUnet: String? = null,
    val deferredHighnoiseLora: String? = null,
    val deferredLownoiseLora: String? = null,
    // Common
    val deferredVae: String? = null,
    val deferredClip: String? = null,
    val deferredClip1: String? = null,
    val deferredClip2: String? = null,
    val deferredClip3: String? = null,
    val deferredClip4: String? = null,
    val deferredTextEncoder: String? = null,
    val deferredLatentUpscaleModel: String? = null,

    // Additional LoRA chains (optional, 0-5 LoRAs on top of mandatory LightX2V LoRAs)
    override val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    override val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Primary LoRA chain (for CommonGenerationState interface - video screens use dual chains)
    override val loraChain: List<LoraSelection> = emptyList(),

    // Workflow capabilities (unified flags derived from placeholders)
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Fetch state
    val isFetching: Boolean = false
) : VideoGenerationState

/**
 * One-time events for Text-to-Video screen
 */
sealed class TextToVideoEvent {
    data class ShowToast(val messageResId: Int) : TextToVideoEvent()
    data class ShowToastMessage(val message: String) : TextToVideoEvent()
}

/**
 * ViewModel for the Text-to-Video screen
 */
class TextToVideoViewModel : BaseGenerationViewModel<TextToVideoUiState, TextToVideoEvent>() {

    override val initialState = TextToVideoUiState()

    /** 本页在 GenerationViewModel 里的注册键（基类 stopListening 用） */
    override val generationOwnerId = OWNER_ID

    /**
     * 当前正在播放的视频键（5.2）登记器：拿到 URI 时登记、切走 / 清空时注销。
     * 语义见 [PlayingVideoRegistry]（与原先本类的字段 + 两个私有方法完全一致）。
     */
    private val playingVideo = PlayingVideoRegistry()

    override fun onCleared() {
        super.onCleared()
        playingVideo.clear()
        // P2-20：注销标签库桥接，静态 map 不再钉住已销毁的 ViewModel
        PromptBridge.unregister(OWNER_ID)
    }

    /**
     * 注册到标签库桥接：使标签库页面可以读写本页的正/负向提示词（需求 5.2）。
     */
    init {
        PromptBridge.register(
            PromptTargetFactory.create(
                targetOwnerId = OWNER_ID,
                workflowId = uiState.map { it.selectedWorkflowId }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, ""),
                workflowName = uiState.map { it.selectedWorkflow }
                    .stateIn(viewModelScope, SharingStarted.Eagerly, ""),
                readPositive = { uiState.value.positivePrompt },
                writePositive = { onPositivePromptChange(it) },
                readNegative = { uiState.value.negativePrompt },
                writeNegative = { onNegativePromptChange(it) }
            )
        )
    }

    // Constants
    companion object {
        private const val TAG = "TextToVideo"
        const val OWNER_ID = "TEXT_TO_VIDEO"
        private const val PREFS_NAME = "TextToVideoFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val KEY_POSITIVE_PROMPT = "positivePrompt"
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
                    // P1-7（R2-M1）：断线/重连的 invalidateAll() 会把 ModelCache 整体置空
                    //（isLoaded=false、所有列表为空）。那份快照不是"服务器上没有模型"的证据：
                    // 照常走下面的校验会把已选模型清空、deferred 恢复值一并抹掉，
                    // `LoraChainManager.filterUnavailable(chain, emptyList)` 更会把整条
                    // high/low-noise LoRA 链清空，随后 saveWorkflowValues 以
                    // takeIf { isNotEmpty() } 把它写成 null 落盘 —— 视频双模型流的 LoRA 就永久丢了。
                    // 直接跳过本次更新，等真实列表回来再校验。
                    if (!ValidationUtils.isUsableModelCache(cache)) {
                        DebugLogger.d(
                            TAG,
                            "跳过空模型缓存快照（isLoaded=${cache.isLoaded}），保留已选模型、LoRA 链与 deferred 值"
                        )
                        // 原样返回当前状态（不写状态 = 不触发重组、不落盘空选择）
                        return@update state
                    }
                    // Apply deferred selections first, then validate or fall back to first available
                    // Single-model patterns
                    val checkpoint = state.deferredCheckpoint?.takeIf { it in cache.checkpoints }
                        ?: validateModelSelection(state.selectedCheckpoint, cache.checkpoints)
                    val unet = state.deferredUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedUnet, cache.unets)
                    val loraName = state.deferredLoraName?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedLoraName, cache.loras)
                    // Dual-model patterns
                    val highnoiseUnet = state.deferredHighnoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedHighnoiseUnet, cache.unets)
                    val lownoiseUnet = state.deferredLownoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedLownoiseUnet, cache.unets)
                    val highnoiseLora = state.deferredHighnoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedHighnoiseLora, cache.loras)
                    val lownoiseLora = state.deferredLownoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedLownoiseLora, cache.loras)
                    // Common
                    val vae = state.deferredVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedVae, cache.vaes)
                    val clip = state.deferredClip?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip, cache.clips)
                    val clip1 = state.deferredClip1?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip1, cache.clips)
                    val clip2 = state.deferredClip2?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip2, cache.clips)
                    val clip3 = state.deferredClip3?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip3, cache.clips)
                    val clip4 = state.deferredClip4?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip4, cache.clips)
                    val textEncoder = state.deferredTextEncoder?.takeIf { it in cache.textEncoders }
                        ?: validateModelSelection(state.selectedTextEncoder, cache.textEncoders)
                    val latentUpscaleModel = state.deferredLatentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                        ?: validateModelSelection(state.selectedLatentUpscaleModel, cache.latentUpscaleModels)

                    state.copy(
                        availableCheckpoints = cache.checkpoints,
                        availableUnets = cache.unets,
                        availableLoras = cache.loras,
                        availableVaes = cache.vaes,
                        availableClips = cache.clips,
                        availableTextEncoders = cache.textEncoders,
                        availableLatentUpscaleModels = cache.latentUpscaleModels,
                        availableSamplers = cache.samplers,
                        availableSchedulers = cache.schedulers,
                        availableAspectRatios = cache.aspectRatios,
                        availableSpeedModes = cache.speedModes,
                        // Single-model selections
                        selectedCheckpoint = checkpoint,
                        selectedUnet = unet,
                        selectedLoraName = loraName,
                        // Dual-model selections
                        selectedHighnoiseUnet = highnoiseUnet,
                        selectedLownoiseUnet = lownoiseUnet,
                        selectedHighnoiseLora = highnoiseLora,
                        selectedLownoiseLora = lownoiseLora,
                        // Common selections
                        selectedVae = vae,
                        selectedClip = clip,
                        selectedClip1 = clip1,
                        selectedClip2 = clip2,
                        selectedClip3 = clip3,
                        selectedClip4 = clip4,
                        selectedTextEncoder = textEncoder,
                        selectedLatentUpscaleModel = latentUpscaleModel,
                        // Clear deferred values once applied
                        deferredCheckpoint = null,
                        deferredUnet = null,
                        deferredLoraName = null,
                        deferredHighnoiseUnet = null,
                        deferredLownoiseUnet = null,
                        deferredHighnoiseLora = null,
                        deferredLownoiseLora = null,
                        deferredVae = null,
                        deferredClip = null,
                        deferredClip1 = null,
                        deferredClip2 = null,
                        deferredClip3 = null,
                        deferredClip4 = null,
                        deferredTextEncoder = null,
                        deferredLatentUpscaleModel = null,
                        // Filter LoRA chains
                        highnoiseLoraChain = LoraChainManager.filterUnavailable(state.highnoiseLoraChain, cache.loras),
                        lownoiseLoraChain = LoraChainManager.filterUnavailable(state.lownoiseLoraChain, cache.loras)
                    )
                }
            }
        }

        // 冷启动时序：/object_info 异步拉取，注册表就绪后重算当前工作流的动态参数（类型自愈）
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
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
        // Models are now loaded automatically via ConnectionManager
    }

    /**
     * Load the last generated video from cache.
     * This restores the video preview when the screen is recreated.
     * Uses runBlocking to ensure synchronous restoration like TTI/ITI.
     */
    private fun loadLastGeneratedVideo() {
        val context = applicationContext ?: return
        val promptId = MediaStateHolder.getCurrentTtvPromptId() ?: return

        val key = MediaStateHolder.MediaKey.TtvVideo(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            val uri = runBlocking {
                MediaStateHolder.getVideoUri(context, key)
            }
            if (uri != null) {
                _uiState.value = _uiState.value.copy(currentVideoUri = uri)
                // 播放方登记（5.2）：裁剪不要删掉这个文件（磁盘优先模式下 URI 就指向它）
                playingVideo.mark(key)
            }
        }
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        val workflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTV)
            .filter { showBuiltIn || !it.isBuiltIn }

        val unifiedWorkflows = workflows.map { workflow ->
            TtvWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.TTV
            )
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflowItem?.name ?: "",
            selectedWorkflowId = selectedWorkflowItem?.id ?: ""
        )

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        if (selectedWorkflowItem != null) {
            loadWorkflowValues(selectedWorkflowItem)
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization and workflow changes)
     */
    private fun loadWorkflowValues(workflowItem: TtvWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values and defaults by workflow ID (not name, to avoid duplicate name issues)
        val savedValues = storage.loadValues(serverId, workflowItem.id)
        val defaults = WorkflowManager.getWorkflowDefaultsById(workflowItem.id)

        // Get workflow placeholders - these determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflowItem.id)

        val cache = ConnectionManager.modelCache.value

        // 动态节点参数（通用节点参数引擎）：扫描 + 类型推导，并按
        // 「已有保存值 → defaults.<占位符名> → 节点定义的 default → 空」取值
        val dynamicParamsSnapshot = WorkflowManager.loadDynamicParams(
            workflowItem.id,
            savedValues?.dynamicParams ?: emptyMap()
        )

        val state = _uiState.value
        _uiState.value = state.copy(
            selectedWorkflow = workflowItem.name,
            selectedWorkflowId = workflowItem.id,
            workflowPlaceholders = placeholders,
            dynamicParams = dynamicParamsSnapshot.params,
            dynamicParamValues = dynamicParamsSnapshot.values,
            dynamicParamsRegistryReady = ConnectionManager.nodeTypeRegistry.isPopulated(),
            negativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",
            width = savedValues?.width?.toString()
                ?: defaults?.width?.toString() ?: "848",
            height = savedValues?.height?.toString()
                ?: defaults?.height?.toString() ?: "480",
            length = savedValues?.length?.toString()
                ?: defaults?.length?.toString() ?: "33",
            fps = savedValues?.frameRate?.toString()
                ?: defaults?.frameRate?.toString() ?: "16",
            // 步数：已保存值 → 工作流默认值 → 状态缺省。
            // 视频流此前完全不读 defaults.steps，工作流里写死的 4/8 会被缺省 20 覆盖，
            // 把 steps 做成占位符后必须在这里预填，否则默认值等于被改掉。
            steps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: state.steps,
            // 主 LoRA 权重：已保存值 → 工作流默认值 → 状态缺省
            loraStrength = savedValues?.loraStrength
                ?: defaults?.loraStrength ?: state.loraStrength,
            // 百万像素 / 宽高比 / 加速模式：已保存值 → 工作流默认值 → 状态缺省（服务器选项首项）
            megapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: state.megapixels,
            aspectRatio = savedValues?.aspectRatio
                ?: defaults?.aspectRatio ?: state.aspectRatio,
            speedMode = savedValues?.speedMode
                ?: defaults?.speedMode ?: state.speedMode,
            // 种子：优先上次保存值，其次工作流默认值（与文生图一致），否则 0
            seed = savedValues?.seed?.toString()
                ?: defaults?.seed?.toString() ?: "0",
            seedMode = SeedMode.fromSaved(savedValues?.seedMode, savedValues?.randomSeed),
            // Apply single-model selections with deferred mechanism
            selectedCheckpoint = savedValues?.model?.takeIf { it in cache.checkpoints }
                ?: defaults?.checkpointModel?.takeIf { it in cache.checkpoints }
                ?: state.selectedCheckpoint,
            selectedUnet = savedValues?.unetModel?.takeIf { it in cache.unets }
                ?: defaults?.unetModel?.takeIf { it in cache.unets }
                ?: state.selectedUnet,
            // 主 LoRA：历史保存值里可能存着字面量 "None"（旧版"未选择"的写法），
            // 先归一化为空 = 不使用，否则它会被当成真实 LoRA 名提交并 400
            selectedLoraName = LoraSelection.normalizeName(savedValues?.loraModel).takeIf { it in cache.loras }
                ?: LoraSelection.normalizeName(defaults?.loraModel).takeIf { it in cache.loras }
                ?: state.selectedLoraName,
            // Apply dual-model selections with deferred mechanism
            selectedHighnoiseUnet = savedValues?.highnoiseUnetModel?.takeIf { it in cache.unets }
                ?: defaults?.unetModel?.takeIf { it in cache.unets }
                ?: state.selectedHighnoiseUnet,
            selectedLownoiseUnet = savedValues?.lownoiseUnetModel?.takeIf { it in cache.unets }
                ?: defaults?.unetModel?.takeIf { it in cache.unets }
                ?: state.selectedLownoiseUnet,
            selectedHighnoiseLora = savedValues?.highnoiseLoraModel?.takeIf { it in cache.loras }
                ?: state.selectedHighnoiseLora,
            selectedLownoiseLora = savedValues?.lownoiseLoraModel?.takeIf { it in cache.loras }
                ?: state.selectedLownoiseLora,
            // Apply common model selections
            selectedVae = savedValues?.vaeModel?.takeIf { it in cache.vaes }
                ?: defaults?.vaeModel?.takeIf { it in cache.vaes }
                ?: state.selectedVae,
            selectedClip = savedValues?.clipModel?.takeIf { it in cache.clips }
                ?: defaults?.clipModel?.takeIf { it in cache.clips }
                ?: state.selectedClip,
            selectedClip1 = savedValues?.clip1Model?.takeIf { it in cache.clips }
                ?: defaults?.clip1Model?.takeIf { it in cache.clips }
                ?: state.selectedClip1,
            selectedClip2 = savedValues?.clip2Model?.takeIf { it in cache.clips }
                ?: defaults?.clip2Model?.takeIf { it in cache.clips }
                ?: state.selectedClip2,
            selectedClip3 = savedValues?.clip3Model?.takeIf { it in cache.clips }
                ?: defaults?.clip3Model?.takeIf { it in cache.clips }
                ?: state.selectedClip3,
            selectedClip4 = savedValues?.clip4Model?.takeIf { it in cache.clips }
                ?: state.selectedClip4,
            selectedTextEncoder = savedValues?.textEncoderModel?.takeIf { it in cache.textEncoders }
                ?: state.selectedTextEncoder,
            selectedLatentUpscaleModel = savedValues?.latentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                ?: state.selectedLatentUpscaleModel,
            // Deferred values for when cache updates - single-model
            deferredCheckpoint = savedValues?.model,
            deferredUnet = savedValues?.unetModel ?: defaults?.unetModel,
            deferredLoraName = savedValues?.loraModel,
            // Deferred values for when cache updates - dual-model
            deferredHighnoiseUnet = savedValues?.highnoiseUnetModel,
            deferredLownoiseUnet = savedValues?.lownoiseUnetModel,
            deferredHighnoiseLora = savedValues?.highnoiseLoraModel,
            deferredLownoiseLora = savedValues?.lownoiseLoraModel,
            // Deferred values for when cache updates - common
            deferredVae = savedValues?.vaeModel ?: defaults?.vaeModel,
            deferredClip = savedValues?.clipModel ?: defaults?.clipModel,
            deferredClip1 = savedValues?.clip1Model ?: defaults?.clip1Model,
            deferredClip2 = savedValues?.clip2Model ?: defaults?.clip2Model,
            deferredClip3 = savedValues?.clip3Model ?: defaults?.clip3Model,
            deferredClip4 = savedValues?.clip4Model,
            deferredTextEncoder = savedValues?.textEncoderModel,
            deferredLatentUpscaleModel = savedValues?.latentUpscaleModel,
            highnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Workflow-specific filtered options
            filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "ckpt_name"),
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "unet_name")
                ?: WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "highnoise_unet_name"),
            filteredLoras = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "lora_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name4"),
            filteredTextEncoders = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "text_encoder_name"),
            filteredLatentUpscaleModels = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "latent_upscale_model"),
            // Workflow capabilities from placeholders
            capabilities = WorkflowCapabilities.fromPlaceholders(
                placeholders = placeholders,
                // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
                imageSlots = ImageSlotDetector.detect(
                    workflowJson = WorkflowManager.getWorkflowById(workflowItem.id)?.jsonContent.orEmpty(),
                    objectInfo = null
                ),
                // 3D 流按真实节点判定 LoRA 面板去留（prebuilt_29 有节点保留，28/32 无节点隐藏）
                workflowType = workflowItem.type,
                workflowJson = WorkflowManager.getWorkflowById(workflowItem.id)?.jsonContent
            )
        )
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load global preferences with serverId prefix
        val savedWorkflowId = prefs.getString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", "") ?: ""
        val defaultPositivePrompt = SeasonalPrompts.getTextToVideoPrompt()
        val savedPositivePrompt = prefs.getString("${serverId}_$KEY_POSITIVE_PROMPT", null) ?: defaultPositivePrompt

        // Update positive prompt first
        _uiState.value = _uiState.value.copy(positivePrompt = savedPositivePrompt)

        // Find workflow by ID
        val workflowItem = if (savedWorkflowId.isNotEmpty()) {
            _uiState.value.availableWorkflows.find { it.id == savedWorkflowId }
        } else {
            _uiState.value.availableWorkflows.find { it.name == _uiState.value.selectedWorkflow }
                ?: _uiState.value.availableWorkflows.firstOrNull()
        }

        // Load workflow values (single source of truth)
        if (workflowItem != null) {
            loadWorkflowValues(workflowItem)
        }
    }

    /**
     * Save current workflow values to per-workflow storage
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)
        // Use workflow ID as storage key (UUID-based)
        val values = VideoGenerationSupport.buildWorkflowValues(state, existingValues)

        storage.saveValues(serverId, workflowId, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Save global preferences with serverId prefix
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$KEY_POSITIVE_PROMPT", state.positivePrompt)
            .apply()

        // Save per-workflow values using workflow ID
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }
    }


    fun onWorkflowChange(workflow: String) {
        val state = _uiState.value

        // Find workflow item to get its ID
        val workflowItem = state.availableWorkflows.find { it.name == workflow } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflow)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

        savePreferences()
    }

    fun onHighnoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedHighnoiseUnet = unet)
        savePreferences()
    }

    fun onLownoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedLownoiseUnet = unet)
        savePreferences()
    }

    fun onHighnoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedHighnoiseLora = lora)
        savePreferences()
    }

    fun onLownoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedLownoiseLora = lora)
        savePreferences()
    }

    // Single-model callbacks
    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
        savePreferences()
    }

    fun onUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedUnet = unet)
        savePreferences()
    }

    fun onMandatoryLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedLoraName = lora)
        savePreferences()
    }

    /**
     * 主 LoRA 权重（{{lora_strength}}）变化。滑块本身把值限制在
     * LoraSelection.MIN_STRENGTH..MAX_STRENGTH，无需再做校验。
     */
    fun onMandatoryLoraStrengthChange(strength: Float) {
        _uiState.value = _uiState.value.copy(loraStrength = strength)
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

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        _uiState.value = _uiState.value.copy(width = width, widthError = error)
        if (error == null) savePreferences()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        _uiState.value = _uiState.value.copy(height = height, heightError = error)
        if (error == null) savePreferences()
    }

    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(megapixels = megapixels, megapixelsError = error)
        if (error == null) savePreferences()
    }

    fun onLengthChange(length: String) {
        val error = ValidationUtils.validateVideoLength(length, applicationContext)
        _uiState.value = _uiState.value.copy(length = length, lengthError = error)
        if (error == null) savePreferences()
    }

    fun onFpsChange(fps: String) {
        val error = ValidationUtils.validateFrameRate(fps, applicationContext)
        _uiState.value = _uiState.value.copy(fps = fps, fpsError = error)
        if (error == null) savePreferences()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        _uiState.value = _uiState.value.copy(steps = steps, stepsError = error)
        if (error == null) savePreferences()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(cfg = cfg, cfgError = error)
        if (error == null) savePreferences()
    }

    fun onSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(sampler = sampler)
        savePreferences()
    }

    fun onSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(scheduler = scheduler)
        savePreferences()
    }

    fun onAspectRatioChange(aspectRatio: String) {
        _uiState.value = _uiState.value.copy(aspectRatio = aspectRatio)
        savePreferences()
    }

    fun onSpeedModeChange(speedMode: String) {
        _uiState.value = _uiState.value.copy(speedMode = speedMode)
        savePreferences()
    }

    /**
     * 更新某个动态节点参数的值并持久化（通用节点参数引擎）。
     * 占位符清单由 [WorkflowManager.getDynamicParams] 在工作流加载时得到，见 dynamicParams。
     */
    fun onDynamicParamValueChange(placeholder: String, value: String) {
        _uiState.value = _uiState.value.copy(
            dynamicParamValues = _uiState.value.dynamicParamValues + (placeholder to value)
        )
        savePreferences()
    }

    /**
     * 注册表就绪后重算当前工作流的动态参数（控件清单 + 值，见
     * [ConnectionManager.nodeTypeRegistryReady]）。用户已输入的值优先保留：空串在
     * [sh.hnet.comfychair.workflow.dynamic.DynamicParamValues.resolveValue] 里视为"没有"，
     * 会回退到 defaults / 节点默认值；类型与值都没变时不写状态。
     */
    private fun refreshDynamicParamsIfNeeded() {
        val state = _uiState.value
        val update = VideoGenerationSupport.refreshDynamicParams(state) ?: return
        DebugLogger.d(TAG, "注册表就绪，重算动态参数：${update.params.size} 个控件")
        _uiState.value = state.copy(
            dynamicParams = update.params,
            dynamicParamValues = update.values,
            dynamicParamsRegistryReady = update.registryReady
        )
    }

    fun onSeedModeChange(mode: SeedMode) {
        _uiState.value = _uiState.value.copy(seedMode = mode)
        savePreferences()
    }

    /**
     * 生成提交成功后调用（由 GenerationViewModel 的回调触发）。
     * 只在「每次+1 / 每次−1」下动作：把种子挪一格并存下来。
     */
    fun advanceSeedIfNeeded() {
        val state = _uiState.value
        val next = VideoGenerationSupport.advanceSeed(state) ?: return
        _uiState.value = state.copy(seed = next, seedError = null)
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

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        savePreferences()
    }

    /**
     * Reset prompt to seasonal default.
     * Clears saved positive prompt and reloads with seasonal default.
     */
    fun resetPromptToDefault() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear saved positive prompt
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove("${serverId}_$KEY_POSITIVE_PROMPT").apply()
            }

            // Set prompt to seasonal default
            val defaultPrompt = SeasonalPrompts.getTextToVideoPrompt()
            _uiState.update { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(TextToVideoEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    // Primary LoRA chain operations (for single-model workflows like LTX 2.0)
    override fun withLoraChain(state: TextToVideoUiState, chain: List<LoraSelection>) =
        state.copy(loraChain = chain)

    fun onAddLora() =
        updateLoraChain(::savePreferences) { LoraChainManager.addLora(it.loraChain, it.availableLoras) }

    fun onRemoveLora(index: Int) =
        updateLoraChain(::savePreferences) { LoraChainManager.removeLora(it.loraChain, index) }

    fun onLoraNameChange(index: Int, name: String) =
        updateLoraChain(::savePreferences) { LoraChainManager.updateLoraName(it.loraChain, index, name) }

    fun onLoraStrengthChange(index: Int, strength: Float) =
        updateLoraChain(::savePreferences) { LoraChainManager.updateLoraStrength(it.loraChain, index, strength) }

    // High noise LoRA chain operations
    fun onAddHighnoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.highnoiseLoraChain, state.availableLoras)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveHighnoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.highnoiseLoraChain, index)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onHighnoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.highnoiseLoraChain, index, name)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onHighnoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.highnoiseLoraChain, index, strength)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    // Low noise LoRA chain operations
    fun onAddLownoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.lownoiseLoraChain, state.availableLoras)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveLownoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.lownoiseLoraChain, index)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onLownoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.lownoiseLoraChain, index, name)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onLownoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.lownoiseLoraChain, index, strength)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        saveLastPreviewImage(bitmap)
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return
        }
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.TtvPreview)
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // 播放已清空：注销裁剪保护（5.2）
        playingVideo.clear()
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentTtvPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // 播放已清空：注销裁剪保护（5.2）
        playingVideo.clear()
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentTtvPromptId()
    }

    fun prepareWorkflow(): String? {
        val state = _uiState.value

        // Validate all fields
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null

        // 提交前兜底（1.3）：视频页的 sampler / scheduler 不落盘（见 VideoGenerationSupport.buildWorkflowValues），
        // 取值来自缺省常量，但备份恢复 / 节点编辑等旁路仍可能把它们写脏，这里统一拦一道。
        // 改了值就同步回界面（与文生图 prepareWorkflowJson 同口径：下拉框不该继续显示脏值，
        // 下次提交也不必再清洗一遍）
        val safeSampler = SamplerOptions.sanitizeSampler(state.sampler, state.availableSamplers)
        val safeScheduler = SamplerOptions.sanitizeScheduler(state.scheduler, state.availableSchedulers)
        if (safeSampler != state.sampler || safeScheduler != state.scheduler) {
            DebugLogger.w(TAG, "采样参数非法，已纠正为 sampler=$safeSampler, scheduler=$safeScheduler")
            _uiState.update { it.copy(sampler = safeSampler, scheduler = safeScheduler) }
        }

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
            width = width,
            height = height,
            length = length,
            fps = fps,
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

        // Inject LoRAs if configured
        var workflow = baseWorkflow

        // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
        if (state.loraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectLoraChain(workflow, state.loraChain, sh.hnet.comfychair.WorkflowType.TTV)
        }

        // Dual-model LoRA chains for workflows like Wan 2.2
        if (state.highnoiseLoraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.highnoiseLoraChain, isHighNoise = true)
        }
        if (state.lownoiseLoraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.lownoiseLoraChain, isHighNoise = false)
        }
        return workflow
    }

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) {
            return false
        }

        // Only check for validation errors in numeric fields
        return state.widthError == null &&
                state.heightError == null &&
                state.lengthError == null &&
                state.fpsError == null
    }

    // Event listener management

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     * @param generationViewModel The shared GenerationViewModel
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel
        // 种子「每次+1 / 每次−1」的落点：提交成功后由 GenerationViewModel 回调
        generationViewModel.registerSubmittedCallback(OWNER_ID) { advanceSeedIfNeeded() }

        // Retry loading video if not loaded during initialize()
        // This handles the race condition where MediaStateHolder.loadFromDisk()
        // completes after initialize() but before startListening()
        if (_uiState.value.currentVideoUri == null) {
            loadLastGeneratedVideo()
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
            is GenerationEvent.VideoGenerated -> {
                DebugLogger.i(TAG, "VideoGenerated: ${Obfuscator.promptId(event.promptId)}")
                fetchGeneratedVideo(event.promptId)
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToVideoEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(TextToVideoEvent.ShowToastMessage(event.message))
                }
                // DON'T call completeGeneration() here - this may just be a connection error
                // The server might still complete the generation
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Don't clear - we want to keep the restored preview visible until video loads
            }
            is GenerationEvent.GenerationCancelled -> {
                // v4.6.1：取消后清掉最后一帧，避免"已经取消了但画面还停在半张图"
                clearPreview()
            }
            is GenerationEvent.CancelRejected -> {
                viewModelScope.launch {
                    _events.emit(TextToVideoEvent.ShowToast(event.messageResId))
                }
            }
            else -> {}
        }
    }

    /**
     * Fetch the generated video from the server and update the UI state.
     * Called when VideoGenerated event is received.
     */
    private fun fetchGeneratedVideo(promptId: String) {
        val context = applicationContext ?: return
        val client = comfyUIClient ?: return

        _uiState.update { it.copy(isFetching = true) }

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = VideoUtils.FilePrefix.TEXT_TO_VIDEO
        ) { uri ->
            _uiState.update { it.copy(isFetching = false) }
            if (uri != null) {
                DebugLogger.i(TAG, "Video fetch successful")
                // Clear preview bitmap so video player takes display precedence
                _uiState.value = _uiState.value.copy(currentVideoUri = uri, previewBitmap = null)
                // 播放方登记（5.2）：新视频开始播放，旧的那份同时注销
                playingVideo.mark(MediaStateHolder.MediaKey.TtvVideo(promptId))
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            } else {
                DebugLogger.w(TAG, "Video fetch failed")
            }
            // If uri is null, don't complete generation - will retry on next return
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtvPreview, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtvPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.TtvPreview)
        }
    }
}
