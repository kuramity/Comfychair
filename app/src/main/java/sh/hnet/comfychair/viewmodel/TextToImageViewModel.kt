package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
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
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ShareFileStore
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.PhotoManager
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel
import java.io.IOException

/**
 * Represents a workflow item in the unified workflow dropdown
 */
data class WorkflowItem(
    val id: String,             // Workflow ID for editor (e.g., "tti_checkpoint_sdxl")
    override val name: String,           // User-friendly workflow name (e.g., "SDXL")
    override val displayName: String,    // Display name with type prefix (e.g., "[Checkpoint] SDXL")
    val type: WorkflowType      // Workflow type for mode detection
) : WorkflowItemBase

/**
 * UI state for the Text-to-Image screen
 *
 * Architecture: Unified Field Visibility
 * - ALL workflows use the SAME set of fields
 * - Field VISIBILITY is controlled by WorkflowCapabilities (derived from placeholders)
 * - No separate checkpoint/UNET field sets - a QuadrupleCLIPLoader checkpoint workflow
 *   can use clip1-4 just like a UNET workflow
 */
data class TextToImageUiState(
    // Workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<WorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    // Used to determine which fields should be visible in the UI
    val workflowPlaceholders: Set<String> = emptySet(),

    // 动态节点参数（通用节点参数引擎）：工作流里「未识别」的 {{占位符}}。
    // dynamicParams 是控件清单（UI 依 control 渲染），dynamicParamValues 是用户当前值（按占位符名索引）。
    val dynamicParams: List<DynamicParam> = emptyList(),
    val dynamicParamValues: Map<String, String> = emptyMap(),
    // 推导 dynamicParams 时 /object_info 注册表是否已就绪。与 dynamicParams **同一次快照**：
    // false 表示控件类型可能已退化（INT 变文本框），UI 提示条据此提示用户（不能读全局单例，
    // 普通 map 读取不建立订阅，注册表就绪后不会触发重组）
    val dynamicParamsRegistryReady: Boolean = false,

    // Workflow capabilities (unified flags derived from placeholders)
    // Controls field visibility - ANY workflow can use ANY field if placeholder exists
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Prompts
    val positivePrompt: String = "",
    val negativePrompt: String = "",
    /**
     * 正面提示词锁：锁上后切换工作流**完全不动正面提示词**（不保存也不载入）。
     *
     * 用途是"这一份词我要跨工作流一直用"（例如在几个工作流之间反复比较同一个构图）。
     * 取值策略见 [sh.hnet.comfychair.model.PositivePromptPolicy]；只锁正面，负面照旧跟随工作流。
     */
    val positivePromptLocked: Boolean = false,

    // Model selections - visibility driven by capabilities (hasCheckpointName, hasUnetName, etc.)
    override val selectedCheckpoint: String = "",
    override val selectedUnet: String = "",
    override val selectedVae: String = "",
    override val selectedClip: String = "",   // For single CLIP (clip_name placeholder)
    override val selectedClip1: String = "",  // For multi-CLIP slot 1 (clip_name1 placeholder)
    override val selectedClip2: String = "",  // For multi-CLIP slot 2 (clip_name2 placeholder)
    override val selectedClip3: String = "",  // For multi-CLIP slot 3 (clip_name3 placeholder)
    override val selectedClip4: String = "",  // For multi-CLIP slot 4 (clip_name4 placeholder)
    override val selectedTextEncoder: String = "",  // For text encoder (text_encoder_name placeholder)
    override val selectedLatentUpscaleModel: String = "",  // For latent upscale model (latent_upscale_model placeholder)

    // Unified generation parameters - single set, visibility controlled by capabilities
    val width: String = "1024",
    val height: String = "1024",
    override val steps: String = "20",
    override val cfg: String = "7.0",
    override val sampler: String = "euler",
    override val scheduler: String = "normal",
    override val seedMode: SeedMode = SeedMode.RANDOM,
    override val seed: String = "0",
    override val denoise: String = "1.0",
    override val batchSize: String = "1",
    // Qwen2.1 这类流的尺寸由 ResolutionSelector（宽高比 + 百万像素）决定，
    // 不看 width/height —— 不接这两个字段，前端改了尺寸也到不了工作流里
    override val megapixels: String = "1.0",
    override val megapixelsError: String? = null,
    override val aspectRatio: String = "",
    override val upscaleMethod: String = "nearest-exact",
    override val scaleBy: String = "1.5",
    override val stopAtClipLayer: String = "-1",

    /**
     * 输出端保存为 WebP（v4.6.1 模块 C）。
     *
     * 只对图片类工作流生效；3D 工作流（TTI_3D）不显示该开关、也不做节点替换。
     */
    val saveAsWebp: Boolean = false,

    // Unified LoRA chain - visibility controlled by capabilities.hasLora
    override val loraChain: List<LoraSelection> = emptyList(),

    // Mandatory LoRA (single selection dropdown) - visibility controlled by capabilities.hasLoraName
    override val selectedLoraName: String = "",
    val deferredLoraName: String? = null,
    override val filteredLoras: List<String>? = null,

    // Deferred model selections (for restoring after models load)
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

    // Available models (loaded from server)
    override val availableCheckpoints: List<String> = emptyList(),
    override val availableUnets: List<String> = emptyList(),
    override val availableVaes: List<String> = emptyList(),
    override val availableClips: List<String> = emptyList(),
    override val availableLoras: List<String> = emptyList(),
    override val availableUpscaleMethods: List<String> = emptyList(),
    override val availableTextEncoders: List<String> = emptyList(),
    override val availableLatentUpscaleModels: List<String> = emptyList(),
    override val availableSamplers: List<String> = emptyList(),
    override val availableSchedulers: List<String> = emptyList(),

    // Workflow-specific filtered options (from actual node type)
    override val filteredCheckpoints: List<String>? = null,
    override val filteredUnets: List<String>? = null,
    override val filteredVaes: List<String>? = null,
    override val filteredClips: List<String>? = null,      // For single CLIP
    override val filteredClips1: List<String>? = null,     // For multi-CLIP slot 1
    override val filteredClips2: List<String>? = null,     // For multi-CLIP slot 2
    override val filteredClips3: List<String>? = null,     // For multi-CLIP slot 3
    override val filteredClips4: List<String>? = null,     // For multi-CLIP slot 4
    override val filteredTextEncoders: List<String>? = null,
    override val filteredLatentUpscaleModels: List<String>? = null,

    // Generated image (preview)
    val previewBitmap: Bitmap? = null,

    // Current image file info (for metadata extraction)
    val currentImageFilename: String? = null,
    val currentImageSubfolder: String? = null,
    val currentImageType: String? = null,

    // Loading states
    val isLoadingModels: Boolean = false,
    val modelsLoaded: Boolean = false,
    val isFetching: Boolean = false,

    // Validation errors
    val widthError: String? = null,
    val heightError: String? = null,
    override val stepsError: String? = null,
    override val cfgError: String? = null,
    override val seedError: String? = null,
    override val denoiseError: String? = null,
    override val batchSizeError: String? = null,
    override val scaleByError: String? = null,
    override val stopAtClipLayerError: String? = null
) : CommonGenerationState

/**
 * One-time events for Text-to-Image screen
 */
sealed class TextToImageEvent {
    data class ShowToast(val messageResId: Int) : TextToImageEvent()
    data class ShowToastMessage(val message: String) : TextToImageEvent()
}

/**
 * ViewModel for the Text-to-Image screen.
 * Manages configuration state, model selection, and image generation.
 */
class TextToImageViewModel : BaseGenerationViewModel<TextToImageUiState, TextToImageEvent>() {

    override val initialState = TextToImageUiState()

    /** 本页在 GenerationViewModel 里的注册键（基类 stopListening 用） */
    override val generationOwnerId = OWNER_ID

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

    // Constants
    companion object {
        private const val TAG = "TextToImage"
        const val OWNER_ID = "TEXT_TO_IMAGE"
        private const val PREFS_NAME = "TextToImageFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        //
        // 仅用于**老数据迁移**：升级前的正面提示词是全局一份（`{serverId}_positivePrompt`），
        // 改成每工作流一份后，首次启动会把这一个值迁移给当时选中的那个工作流，随后删键。
        // 新代码不得再往这个键写入（两份数据源必然再次产生"正面不变"的漂移，正是本次要修的 bug）。
        private const val PREF_POSITIVE_PROMPT = "positivePrompt"
        private const val PREF_SELECTED_WORKFLOW_ID = "selectedWorkflowId"

        /** 正面提示词锁（每服务器一份；默认未锁，保持既有"跟随工作流"行为）。 */
        private const val PREF_POSITIVE_PROMPT_LOCKED = "positivePromptLocked"
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
                    // P1-7：断线/重连的 invalidateAll() 会把 ModelCache 整体置空
                    //（isLoaded=false、所有列表为空）。那份快照不是"服务器上没有模型"的证据，
                    // 照常校验会把已选模型/LoRA 全部清空、deferred 恢复值一并抹掉并写回磁盘。
                    // 直接跳过本次更新：保留现有选择与 deferred 值，等真实列表回来再校验。
                    if (!ValidationUtils.isUsableModelCache(cache)) {
                        DebugLogger.d(
                            TAG,
                            "跳过空模型缓存快照（isLoaded=${cache.isLoaded}），保留已选模型与 deferred 值"
                        )
                        // 原样返回当前状态（不写状态 = 不触发重组、不落盘空选择）
                        return@update state
                    }
                    // CLIP 候选按「格式门控」过滤：工作流带 `clip_gguf` 开关时，打开只留 .gguf、
                    // 关闭只留非 .gguf（两种加载器互斥，见 filterClipsByGate 注释）；无开关则原样。
                    val clipOptions = filterClipsByGate(cache.clips, state.dynamicParamValues)
                    // Apply deferred selections first, then validate or fall back to first available
                    val checkpoint = state.deferredCheckpoint?.takeIf { it in cache.checkpoints }
                        ?: validateModelSelection(state.selectedCheckpoint, cache.checkpoints)
                    val unet = state.deferredUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedUnet, cache.unets)
                    val vae = state.deferredVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedVae, cache.vaes)
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
                    val loraName = state.deferredLoraName?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedLoraName, cache.loras)

                    state.copy(
                        availableCheckpoints = cache.checkpoints,
                        availableUnets = cache.unets,
                        availableVaes = cache.vaes,
                        availableClips = clipOptions,
                        availableLoras = cache.loras,
                        availableUpscaleMethods = cache.upscaleMethods,
                        availableTextEncoders = cache.textEncoders,
                        availableLatentUpscaleModels = cache.latentUpscaleModels,
                        availableSamplers = cache.samplers,
                        availableSchedulers = cache.schedulers,
                        isLoadingModels = cache.isLoading,
                        modelsLoaded = cache.isLoaded,
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
                        selectedLoraName = loraName,
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
                        deferredLoraName = null,
                        loraChain = LoraChainManager.filterUnavailable(state.loraChain, cache.loras)
                    )
                }
            }
        }

        // Observe workflow changes to refresh list when workflows are added/updated/deleted
        viewModelScope.launch {
            WorkflowManager.workflowsVersion.collect {
                loadWorkflows()
            }
        }

        // 冷启动时序：/object_info（数 MB）是异步拉的，进主界面时注册表还是空的，
        // 动态参数会全部退化成文本框。注册表就绪后**重算当前工作流**，让类型自愈
        // （否则用户只能切走再切回工作流）。
        viewModelScope.launch {
            ConnectionManager.nodeTypeRegistryReady.collect { ready ->
                if (ready) refreshDynamicParamsIfNeeded()
            }
        }
    }

    /**
     * 注册表就绪后重算当前工作流的动态参数（控件清单 + 值）。
     *
     * **不覆盖用户已输入的值**：以当前 UiState 里的值为「已有保存值」重算，空串会被
     * [sh.hnet.comfychair.workflow.dynamic.DynamicParamValues.resolveValue] 视作"没有"
     * 而回退到 defaults / 节点默认值。类型与值都没变时不写状态（避免无谓重组）。
     */
    private fun refreshDynamicParamsIfNeeded() {
        val state = _uiState.value
        val workflowId = state.selectedWorkflowId
        if (workflowId.isEmpty()) return
        val snapshot = WorkflowManager.loadDynamicParams(workflowId, state.dynamicParamValues)
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        if (snapshot.params == state.dynamicParams &&
            snapshot.values == state.dynamicParamValues &&
            ready == state.dynamicParamsRegistryReady
        ) {
            return
        }
        DebugLogger.d(TAG, "注册表就绪，重算动态参数：${snapshot.params.size} 个控件")
        _uiState.value = state.copy(
            dynamicParams = snapshot.params,
            dynamicParamValues = snapshot.values,
            dynamicParamsRegistryReady = ready
        )
    }

    /**
     * Called after base initialization is complete.
     */
    override fun onInitialize() {
        DebugLogger.i(TAG, "Initializing")

        // Load workflows from resources
        loadWorkflows()

        // Load saved configuration
        restorePreferences()

        // Restore last generated image
        restoreLastGeneratedImage()
    }

    /**
     * Load available workflows from WorkflowManager and create unified list
     */
    private fun loadWorkflows() {
        val ctx = applicationContext ?: run {
            DebugLogger.w(TAG, "loadWorkflows: Context not available")
            return
        }

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        // 文生3D（TTI_3D）共用「提示词 + 参数」这套交互，一起列出来；
        // item.type 保留真实类型 —— 生成完成后要靠它把结果路由到 3D 专属结果页
        val workflows = (
            WorkflowManager.getWorkflowsByType(WorkflowType.TTI) +
                WorkflowManager.getWorkflowsByType(WorkflowType.TTI_3D)
            ).filter { showBuiltIn || !it.isBuiltIn }

        // Create workflow list
        val unifiedWorkflows = workflows.map { workflow ->
            WorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = workflow.type
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
     * Fetch models from the server.
     * Models are now loaded automatically via ConnectionManager on connection.
     * This method is kept for API compatibility but is effectively a no-op.
     */
    @Suppress("unused")
    fun fetchModels() {
        // Models are now loaded automatically via ConnectionManager.modelCache
        // which is observed in the init block above.
    }

    // State management

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        saveConfiguration()
    }

    /**
     * 切换正面提示词锁。
     *
     * 锁上：此后切换工作流完全不动正面提示词（不保存也不载入）。
     * 解锁：顺手把界面上当前这个词存进当前工作流（"解开就当我的"），避免解锁瞬间的值悬空。
     */
    fun onPromptLockToggle() {
        val locked = !_uiState.value.positivePromptLocked
        _uiState.value = _uiState.value.copy(positivePromptLocked = locked)
        DebugLogger.d(TAG, "正面提示词锁：${if (locked) "已锁定" else "已解锁"}")
        saveConfiguration()
    }

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        saveConfiguration()
    }

    /**
     * Reset prompt to seasonal default.
     *
     * 重置对象是**当前工作流的正面提示词**（本次改动）：正面词已改为每工作流一份，
     * 若仍去删那个老全局键，会变成"删了一个没人读的键、界面上却恢复不了"。
     * 负面提示词不在此列（它有各自的"清空"按钮，重置语义只覆盖正面）。
     */
    fun resetPromptToDefault() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            val defaultPrompt = SeasonalPrompts.getTextToImagePrompt()
            val workflowId = _uiState.value.selectedWorkflowId

            withContext(Dispatchers.IO) {
                // 老全局键已废弃，顺手清掉（避免下次启动把它当成迁移源再迁一次）
                removeLegacyPositivePrompt(ctx, serverId)

                val storage = workflowValuesStorage
                if (storage != null && workflowId.isNotEmpty()) {
                    // 保留该工作流其它字段，只把正面词写回季节默认词 —— 于是"重启后仍是这个默认词"
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
            _events.emit(TextToImageEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    /**
     * Unified workflow change - loads values for the new workflow
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find workflow item
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

        saveConfiguration()
    }

    // Unified model selection callbacks

    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
        saveConfiguration()
    }

    fun onUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedUnet = unet)
        saveConfiguration()
    }

    fun onVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedVae = vae)
        saveConfiguration()
    }

    fun onClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip = clip)
        saveConfiguration()
    }

    fun onClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip1 = clip)
        saveConfiguration()
    }

    fun onClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip2 = clip)
        saveConfiguration()
    }

    fun onClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip3 = clip)
        saveConfiguration()
    }

    fun onClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip4 = clip)
        saveConfiguration()
    }

    fun onTextEncoderChange(textEncoder: String) {
        _uiState.value = _uiState.value.copy(selectedTextEncoder = textEncoder)
        saveConfiguration()
    }

    fun onLatentUpscaleModelChange(model: String) {
        _uiState.value = _uiState.value.copy(selectedLatentUpscaleModel = model)
        saveConfiguration()
    }

    fun onMandatoryLoraChange(loraName: String) {
        _uiState.value = _uiState.value.copy(selectedLoraName = loraName)
        saveConfiguration()
    }

    // Unified parameter callbacks

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        _uiState.value = _uiState.value.copy(width = width, widthError = error)
        saveConfiguration()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        _uiState.value = _uiState.value.copy(height = height, heightError = error)
        saveConfiguration()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        _uiState.value = _uiState.value.copy(steps = steps, stepsError = error)
        saveConfiguration()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(cfg = cfg, cfgError = error)
        saveConfiguration()
    }

    fun onSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(sampler = sampler)
        saveConfiguration()
    }

    fun onSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(scheduler = scheduler)
        saveConfiguration()
    }

    fun onSeedModeChange(mode: SeedMode) {
        _uiState.value = _uiState.value.copy(seedMode = mode)
        saveConfiguration()
    }

    /**
     * 每次生成提交成功后调用（由 GenerationViewModel 的回调触发）。
     *
     * 只在「每次+1 / 每次−1」两种模式下动作：把种子挪一格并**存下来** ——
     * 存下来是刻意的，用户能看见数字在走，下次进来也是新值；
     * 只挪不存的话重启就回到原点，与"每次递增"的预期不符。
     *
     * 边界由 [nextSeed] 处理（超上限回绕到下限、低于下限停住），
     * 上下限取自工作流里种子节点的真实约束。
     */
    fun advanceSeedIfNeeded() {
        val state = _uiState.value
        if (!state.seedMode.advances) return
        val constraints = WorkflowConstraintsProvider.getConstraints("seed", state.selectedWorkflow)
        val next = nextSeed(
            current = state.seed.toLongOrNull() ?: 0L,
            step = state.seedMode.step,
            min = constraints.min.toLong(),
            max = constraints.max.toLong()
        )
        _uiState.value = state.copy(seed = next.toString(), seedError = null)
        saveConfiguration()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(seed = seed, seedError = error)
        saveConfiguration()
    }

    fun onRandomizeSeed() {
        // 随机种子限制在 UINT32 范围（与 ComfyUI 官方前端一致），避免超出部分节点
        // 的 INT 上限（如 SeedVR2VideoUpscaler seed max=4294967295）触发 400 校验失败
        val randomSeed = kotlin.random.Random.nextLong(0, 4294967296L).toString()
        _uiState.value = _uiState.value.copy(seed = randomSeed, seedError = null)
        saveConfiguration()
    }

    /** 百万像素（ResolutionSelector.megapixels）：Qwen2.1 流用它 + 宽高比定尺寸。 */
    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(
            megapixels = megapixels,
            megapixelsError = error
        )
    }

    /** 宽高比（ResolutionSelector.aspect_ratio）：只对带这个字段的工作流生效。 */
    fun onAspectRatioChange(aspectRatio: String) {
        _uiState.value = _uiState.value.copy(aspectRatio = aspectRatio)
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(denoise = denoise, denoiseError = error)
        saveConfiguration()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(batchSize = batchSize, batchSizeError = error)
        saveConfiguration()
    }

    fun onUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(upscaleMethod = method)
        saveConfiguration()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(scaleBy = scaleBy, scaleByError = error)
        saveConfiguration()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(stopAtClipLayer = layer, stopAtClipLayerError = error)
        saveConfiguration()
    }

    /**
     * 切换"保存为 WebP"（v4.6.1 模块 C）。
     *
     * 只影响图片类工作流：提交前由 SaveFormatMaterializer 把 SaveImage 换成插件节点
     * BatchImageSaverCN（输出 .webp）；关闭则保持原生 SaveImage（输出 .png）。
     * 偏好按工作流持久化（saveConfiguration → saveWorkflowValues）。
     */
    fun onSaveAsWebpChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(saveAsWebp = enabled)
        saveConfiguration()
    }

    /**
     * 更新某个动态节点参数的值并持久化（通用节点参数引擎）。
     * 占位符清单由 [WorkflowManager.getDynamicParams] 在工作流加载时得到，见 dynamicParams。
     */
    fun onDynamicParamValueChange(placeholder: String, value: String) {
        val state = _uiState.value
        val newValues = state.dynamicParamValues + (placeholder to value)
        // 格式门控开关一变，CLIP 候选要**立刻**跟着换：
        // 候选过滤本来只挂在 modelCache 刷新链上，而拨开关不会触发它 ——
        // 那样用户打开了「使用 GGUF 格式」却看不到列表变化（2026-09-24 用户报障）。
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
        saveConfiguration()
    }

    // Unified LoRA chain management

    override fun withLoraChain(state: TextToImageUiState, chain: List<LoraSelection>) =
        state.copy(loraChain = chain)

    fun onAddLora() =
        updateLoraChain(::saveConfiguration) { LoraChainManager.addLora(it.loraChain, it.availableLoras) }

    fun onRemoveLora(index: Int) =
        updateLoraChain(::saveConfiguration) { LoraChainManager.removeLora(it.loraChain, index) }

    fun onLoraNameChange(index: Int, name: String) =
        updateLoraChain(::saveConfiguration) { LoraChainManager.updateLoraName(it.loraChain, index, name) }

    fun onLoraStrengthChange(index: Int, strength: Float) =
        updateLoraChain(::saveConfiguration) { LoraChainManager.updateLoraStrength(it.loraChain, index, strength) }

    /**
     * Update the current bitmap (e.g., from preview or final image)
     */
    fun onPreviewBitmapChange(bitmap: Bitmap?) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        bitmap?.let { saveLastGeneratedImage(it) }
    }

    // Preview management
    /**
     * Clear the preview image when starting a new generation.
     */
    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return // Already cleared for this promptId
        }
        lastClearedForPromptId = promptId
        // Evict from cache so restoreLastGeneratedImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.TtiPreview)
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
            currentImageFilename = null,
            currentImageSubfolder = null,
            currentImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
            currentImageFilename = null,
            currentImageSubfolder = null,
            currentImageType = null
        )
    }

    /**
     * Update the current bitmap with file info for metadata extraction.
     */
    private fun setCurrentImage(bitmap: Bitmap, filename: String, subfolder: String, type: String) {
        _uiState.value = _uiState.value.copy(
            previewBitmap = bitmap,
            currentImageFilename = filename,
            currentImageSubfolder = subfolder,
            currentImageType = type
        )
        saveLastGeneratedImage(bitmap)
    }

    // Event handling

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel
        // 种子「每次+1 / 每次−1」的落点：提交成功后由 GenerationViewModel 回调
        generationViewModel.registerSubmittedCallback(OWNER_ID) { advanceSeedIfNeeded() }
        generationViewModel.registerEventHandler(OWNER_ID) { event ->
            handleGenerationEvent(event)
        }
    }

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
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                DebugLogger.w(TAG, "ConnectionLostDuringGeneration")
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToImageEvent.ShowToastMessage(message))
                }
            }
            is GenerationEvent.Error -> {
                DebugLogger.e(TAG, "Generation error")
                viewModelScope.launch {
                    _events.emit(TextToImageEvent.ShowToastMessage(event.message))
                }
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
                    _events.emit(TextToImageEvent.ShowToast(event.messageResId))
                }
            }
            else -> {}
        }
    }

    // Validation

    /**
     * Validate current configuration before generation.
     * Only checks for validation errors - model selections are optional.
     */
    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) {
            return false
        }

        // Only check for validation errors in numeric fields
        return state.widthError == null &&
               state.heightError == null &&
               state.stepsError == null &&
               state.cfgError == null &&
               state.seedError == null &&
               state.denoiseError == null &&
               state.batchSizeError == null &&
               state.scaleByError == null &&
               state.stopAtClipLayerError == null
    }

    /**
     * Prepare workflow JSON for generation.
     *
     * Uses unified fields - WorkflowManager.prepareWorkflowById() only substitutes
     * placeholders that exist in the workflow JSON, so we can pass ALL selections
     * and let the placeholder replacement handle which ones are actually used.
     */
    fun prepareWorkflowJson(): String? {
        val state = _uiState.value

        DebugLogger.i(TAG, "Preparing workflow: ${Obfuscator.workflowName(state.selectedWorkflow)}")

        // 提交前兜底（1.3）：进占位符替换之前再清洗一次 sampler / scheduler。
        // 读取阶段在「服务器清单未知」（冷启动）时是原样放行的，所以非法值可能还留在状态里，
        // 而它会被 ComfyUI 的 validate_inputs() 判成 "Value not in list" 直接 400。
        // 这里只改「取值」，替换逻辑本身不动；改了值就同步回界面（下拉框不该继续显示脏值）
        val safeSampler = SamplerOptions.sanitizeSampler(state.sampler, state.availableSamplers)
        val safeScheduler = SamplerOptions.sanitizeScheduler(state.scheduler, state.availableSchedulers)
        if (safeSampler != state.sampler || safeScheduler != state.scheduler) {
            DebugLogger.w(TAG, "采样参数非法，已纠正为 sampler=$safeSampler, scheduler=$safeScheduler")
            _uiState.update { it.copy(sampler = safeSampler, scheduler = safeScheduler) }
        }

        // Pass ALL model selections and parameters - only those with matching
        // placeholders in the workflow will be substituted
        val baseWorkflow = WorkflowManager.prepareWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Model selections - workflow placeholders determine which are used
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            vae = state.selectedVae,
            clip = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoder = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            lora = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Unified generation parameters
            width = state.width.toIntOrNull() ?: 1024,
            height = state.height.toIntOrNull() ?: 1024,
            steps = state.steps.toIntOrNull() ?: 20,
            cfg = state.cfg.toFloatOrNull() ?: 7.0f,
            samplerName = safeSampler,
            scheduler = safeScheduler,
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            denoise = state.denoise.toFloatOrNull(),
            batchSize = state.batchSize.toIntOrNull(),
            megapixels = state.megapixels.toFloatOrNull(),
            aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull(),
            // 输出端格式（v4.6.1 模块 C）：true 时把 SaveImage 换成插件 WebP 保存节点
            saveAsWebp = state.saveAsWebp,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        ) ?: return null

        // Inject LoRA chain if present
        return WorkflowManager.injectLoraChain(baseWorkflow, state.loraChain, WorkflowType.TTI)
    }

    /**
     * Fetch the generated image after completion
     * @param promptId The prompt ID to fetch
     * @param onComplete Callback with success boolean (true if image was fetched and set)
     */
    fun fetchGeneratedImage(promptId: String, onComplete: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onComplete(false)
            return
        }

        _uiState.update { it.copy(isFetching = true) }

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            // 同一张图可能已被"完成事件即时取图"那条链路取过一次（两条链路落到同一个
                            // 媒体键）：确认缓存里正是这张图时就别再下载 + 解码一遍（动辄几十 MB）。
                            // 判据是 promptId 精确匹配，没有记录时一律照常下载，不会误用旧图。
                            if (MediaStateHolder.imageMatchesPromptId(MediaStateHolder.MediaKey.TtiPreview, promptId)) {
                                val cached = MediaStateHolder.getBitmap(
                                    MediaStateHolder.MediaKey.TtiPreview, applicationContext
                                )
                                if (cached != null) {
                                    viewModelScope.launch {
                                        _uiState.update { it.copy(isFetching = false) }
                                        setCurrentImage(cached, filename, subfolder, type)
                                        onComplete(true)
                                    }
                                    return@fetchHistory
                                }
                            }

                            client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                                viewModelScope.launch {
                                    _uiState.update { it.copy(isFetching = false) }
                                    if (bitmap != null) {
                                        setCurrentImage(bitmap, filename, subfolder, type)
                                        MediaStateHolder.markImagePromptId(MediaStateHolder.MediaKey.TtiPreview, promptId)
                                        onComplete(true)
                                    } else if (failureType == ConnectionFailure.STALLED) {
                                        applicationContext?.let { ctx ->
                                            ConnectionManager.showConnectionAlert(ctx, failureType)
                                        }
                                        onComplete(false)
                                    } else {
                                        onComplete(false)
                                    }
                                }
                            }
                            return@fetchHistory
                        }
                    }
                    _uiState.update { it.copy(isFetching = false) }
                    onComplete(false)
                } catch (_: Exception) {
                    _uiState.update { it.copy(isFetching = false) }
                    onComplete(false)
                }
            } else {
                _uiState.update { it.copy(isFetching = false) }
                onComplete(false)
            }
        }
    }

    // Persistence

    /**
     * Save current workflow values to per-workflow storage.
     *
     * Saves ALL field values unconditionally - WorkflowValues is the unified storage format.
     * Uses checkpoint OR unet model field based on which is set (capabilities determine which
     * is visible in UI, so only one should be populated at a time).
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val state = _uiState.value

        // Use workflow ID as storage key (UUID-based)
        val serverId = ConnectionManager.currentServerId ?: return

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        // Save unified field values - model is whichever one is set (checkpoint or unet)
        val values = WorkflowValues(
            width = state.width.toIntOrNull(),
            height = state.height.toIntOrNull(),
            // Qwen2.1 的尺寸走这两个（ResolutionSelector），与 width/height 并存互不干扰
            megapixels = state.megapixels.toFloatOrNull(),
            aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            samplerName = state.sampler,
            scheduler = state.scheduler,
            // 正面提示词按工作流各存一份：取值规则（锁定 / 空串 / 非激活模式）统一走策略函数。
            // 未锁定时保存界面上的词；锁定时写回原值（= 锁定期间手打的词不落盘，见策略类注释）
            positivePrompt = PositivePromptPolicy.resolveOnSave(
                locked = state.positivePromptLocked,
                current = state.positivePrompt,
                existing = existingValues?.positivePrompt,
                isActiveMode = true,
            ),
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Save checkpoint OR unet - whichever is set (capabilities control which is visible)
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
            // Mandatory LoRA (single selection dropdown)
            loraModel = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Unified LoRA chain
            loraChain = LoraSelection.toJsonString(state.loraChain).takeIf { state.loraChain.isNotEmpty() },
            // Unified generation parameters
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 新键与上面那个布尔一起写：老版本读布尔，新版本优先读这个（见 SeedMode.fromSaved）
            seedMode = state.seedMode.name,
            denoise = state.denoise.toFloatOrNull(),
            batchSize = state.batchSize.toIntOrNull(),
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull(),
            nodeAttributeEdits = existingValues?.nodeAttributeEdits,
            // 输出格式偏好（v4.6.1 模块 C）：按工作流持久化
            saveAsWebp = state.saveAsWebp,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        )

        storage.saveValues(serverId, workflowId, values)
    }

    private fun saveConfiguration() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences with serverId prefix
        // 注意：正面提示词**不再**在这里写全局键（它已改为每工作流一份，见 saveWorkflowValues）；
        // 全局键只作为老数据迁移的来源读取一次，迁移后即删
        prefs.edit().apply {
            putString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            // 锁状态是"跨工作流"的偏好，与工作流无关，所以留在全局偏好里
            putBoolean("${serverId}_$PREF_POSITIVE_PROMPT_LOCKED", state.positivePromptLocked)
            apply()
        }

        // Save per-workflow values for the currently selected workflow (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }
    }

    private fun restorePreferences() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 老版本的全局正面词：只当**迁移来源**读一次，不再是"默认正面词"。
        // 迁移完成后删键（见 migrateLegacyPositivePrompt）
        val legacyPositivePrompt = prefs.getString("${serverId}_$PREF_POSITIVE_PROMPT", null)
        val positivePromptLocked = prefs.getBoolean("${serverId}_$PREF_POSITIVE_PROMPT_LOCKED", false)
        val savedWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", null)

        // Determine which workflow to select (by ID)
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

        // 迁移必须发生在载入之前：载入读的就是刚迁移进去的那份值
        if (workflowToLoad != null) {
            migrateLegacyPositivePrompt(ctx, serverId, workflowToLoad.id, legacyPositivePrompt)
        }

        // Load workflow and its values (this sets mode, capability flags, and values)
        if (workflowToLoad != null) {
            // Load workflow values without saving (to avoid circular save)
            loadWorkflowValues(workflowToLoad)
        }

        // 冷启动视为**首次装载**（工作流的值照常载入，否则重启后正面词会是一片空白），
        // 持久化的锁状态在载入之后生效：此后切换工作流才按"完全不动"处理
        _uiState.update { it.copy(positivePromptLocked = positivePromptLocked) }
    }

    /**
     * 把老版本的**全局**正面提示词迁移给「当前选中的那个工作流」（一次性）。
     *
     * 为什么需要：升级前用户只有一份全局正面词，若直接弃用全局键，他会看到正面词变成
     * 当前工作流的默认值，像是数据丢了。
     *
     * 为什么只迁移给一个工作流：全局键里没有"这是给哪个工作流写的"这一信息，
     * 迁给当前选中的那个是唯一可解释的近似；其它工作流回退各自的默认词（prebuilt 大多自带）。
     *
     * 安全约束（两条都不能破）：
     * - 目标工作流**已有**正面词时不覆盖（用户的既有数据优先于迁移）；
     * - 删全局键放在写入**之后**。
     */
    private fun migrateLegacyPositivePrompt(
        ctx: Context,
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
        removeLegacyPositivePrompt(ctx, serverId)
    }

    private fun removeLegacyPositivePrompt(ctx: Context, serverId: String) {
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("${serverId}_$PREF_POSITIVE_PROMPT").apply()
        } catch (e: Exception) {
            // 删不掉最坏后果是下次启动再迁一次（有"已有值不覆盖"兜底），不值得影响启动
            DebugLogger.w(TAG, "清理老版全局正面提示词失败")
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization).
     *
     * Uses unified fields - capabilities (derived from placeholders) control which
     * fields are visible in the UI. The saved model value is loaded into the appropriate
     * selection field (checkpoint OR unet) based on capabilities.
     */
    private fun loadWorkflowValues(workflow: WorkflowItem) {
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

        val state = _uiState.value

        // 动态节点参数（通用节点参数引擎）：扫描 + 类型推导，并按
        // 「已有保存值 → defaults.<占位符名> → 节点定义的 default → 空」取值
        val dynamicParamsSnapshot = WorkflowManager.loadDynamicParams(
            workflow.id,
            savedValues?.dynamicParams ?: emptyMap()
        )

        // Get saved model selections（cache 已在函数开头取过，同一快照用于两处校验）
        val savedModel = savedValues?.model
        val savedVae = savedValues?.vaeModel
        val savedClip = savedValues?.clipModel
        val savedClip1 = savedValues?.clip1Model
        val savedClip2 = savedValues?.clip2Model
        val savedClip3 = savedValues?.clip3Model
        val savedClip4 = savedValues?.clip4Model
        val savedTextEncoder = savedValues?.textEncoderModel
        val savedLatentUpscaleModel = savedValues?.latentUpscaleModel
        val savedLoraName = savedValues?.loraModel

        // Load model into appropriate field based on capabilities
        // If workflow has ckpt_name placeholder, savedModel is a checkpoint
        // If workflow has unet_name placeholder, savedModel is a UNET
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

            // 动态节点参数（通用节点参数引擎）
            dynamicParams = dynamicParamsSnapshot.params,
            dynamicParamValues = dynamicParamsSnapshot.values,
            dynamicParamsRegistryReady = ConnectionManager.nodeTypeRegistry.isPopulated(),

            // Unified generation parameters
            width = savedValues?.width?.toString()
                ?: defaults?.width?.toString() ?: "1024",
            height = savedValues?.height?.toString()
                ?: defaults?.height?.toString() ?: "1024",
            steps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: "20",
            cfg = savedValues?.cfg?.toString()
                ?: defaults?.cfg?.toString() ?: "7.0",
            // sampler / scheduler：已保存值 → 工作流默认值 → 缺省，最后再过一道清洗。
            // 清洗走「读取口径」（见 sanitize*OnLoad）：服务器清单还没同步（空）时原样放行，
            // 避免把服务器新增、内置清单还没收录的合法值误清成默认值
            sampler = SamplerOptions.sanitizeSamplerOnLoad(
                savedValues?.samplerName ?: defaults?.samplerName ?: SamplerOptions.DEFAULT_SAMPLER,
                cache.samplers,
            ),
            scheduler = SamplerOptions.sanitizeSchedulerOnLoad(
                savedValues?.scheduler ?: defaults?.scheduler ?: SamplerOptions.DEFAULT_SCHEDULER,
                cache.schedulers,
            ),
            // 正面提示词跟随工作流：没存过就回退该工作流的默认词（defaults.positive_prompt），
            // 存过空串则用空串（用户明确清空过）。锁定时保持界面上的词不动
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
            selectedTextEncoder = savedTextEncoder?.takeIf { it in cache.textEncoders }
                ?: validateModelSelection("", cache.textEncoders),
            selectedLatentUpscaleModel = savedLatentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
                ?: validateModelSelection("", cache.latentUpscaleModels),
            selectedLoraName = savedLoraName?.takeIf { it in cache.loras }
                ?: defaults?.loraModel?.takeIf { it in cache.loras }
                ?: validateModelSelection("", cache.loras),

            // Deferred values - applied when model cache updates
            deferredCheckpoint = if (capabilities.hasCheckpointName) savedModel ?: defaults?.checkpointModel else null,
            deferredUnet = if (capabilities.hasUnetName) savedModel ?: defaults?.unetModel else null,
            deferredVae = savedVae ?: defaults?.vaeModel,
            deferredClip = savedClip ?: defaults?.clipModel,
            deferredClip1 = savedClip1 ?: defaults?.clip1Model,
            deferredClip2 = savedClip2 ?: defaults?.clip2Model,
            deferredClip3 = savedClip3 ?: defaults?.clip3Model,
            deferredClip4 = savedClip4,
            deferredTextEncoder = savedTextEncoder,
            deferredLatentUpscaleModel = savedLatentUpscaleModel,
            deferredLoraName = savedLoraName,

            // Unified LoRA chain
            loraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),

            // Unified generation parameters
            seedMode = SeedMode.fromSaved(savedValues?.seedMode, savedValues?.randomSeed),
            seed = savedValues?.seed?.toString()
                ?: defaults?.seed?.toString() ?: "0",
            denoise = savedValues?.denoise?.toString()
                ?: defaults?.denoise?.toString() ?: "1.0",
            megapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: "1.0",
            aspectRatio = savedValues?.aspectRatio
                ?: defaults?.aspectRatio ?: "",
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
            filteredLatentUpscaleModels = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "latent_upscale_model"),
            filteredLoras = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "lora_name")
        )
    }

    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        // Store in memory (or disk if disk-first mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtiPreview, bitmap, applicationContext)
    }

    private fun restoreLastGeneratedImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtiPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    // Image operations

    fun saveToGallery(onResult: (success: Boolean) -> Unit) {
        val ctx = applicationContext ?: run { onResult(false); return }
        val bitmap = _uiState.value.previewBitmap ?: run { onResult(false); return }

        // P1-22：IS_PENDING 写入 + 真实结果。修复前 insert/openOutputStream 失败也会报成功
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                PhotoManager.saveBitmap(ctx, bitmap)
            }
            DebugLogger.i(TAG, "saveToGallery: saved=$saved")
            onResult(saved)
        }
    }

    fun saveToUri(uri: Uri, onResult: (success: Boolean) -> Unit) {
        val ctx = applicationContext ?: run { onResult(false); return }
        val bitmap = _uiState.value.previewBitmap ?: run { onResult(false); return }

        try {
            ctx.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            onResult(true)
        } catch (e: IOException) {
            onResult(false)
        }
    }

    fun getShareIntent(): Intent? {
        val ctx = applicationContext ?: return null
        val bitmap = _uiState.value.previewBitmap ?: return null

        // P2-12：分享载荷只能写进 cacheDir/share/（FileProvider 唯一声明的缓存子目录）
        val filename = "share_image_${System.currentTimeMillis()}.png"
        val file = ShareFileStore.file(ctx, filename)

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
        } catch (e: Exception) {
            null
        }
    }
}
