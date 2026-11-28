package sh.hnet.comfychair.viewmodel.base

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.PromptTargetFactory
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.GenerationEvent
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.dynamic.DynamicParam

/**
 * Represents a workflow item with display name
 *
 * B4.2-B 视频对合并：原 `TtvWorkflowItem` / `ItvWorkflowItem` 归一后逐字节相同，
 * 提取为共享类型；两侧用 `typealias TtvWorkflowItem = VideoWorkflowItem` /
 * `typealias ItvWorkflowItem = VideoWorkflowItem` 保持调用点零改动。
 */
data class VideoWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name
    val type: WorkflowType      // TTV / ITV
) : WorkflowItemBase

/**
 * 图位 / 音频 / 旧时间线探测结果（[BaseVideoGenerationViewModel.detectVideoSlots] 的返回值）。
 *
 * 文生视频默认只探图位（字符串重载，与原实现一致）；图生视频覆写为用已解析的 root
 * 一并探音频与旧版「时间线导演台」。loadWorkflowValues 的公共骨架用它装配 capabilities，
 * 并把增量交给子类的 amendWorkflowValuesState 写进各自 UiState。
 */
data class VideoSlotsDetection(
    val imageSlots: List<ImageSlot>,
    val audioSlots: List<AudioSlot>,
    val legacyTimelineUnsupported: Boolean
)

/**
 * 视频页共享状态的可变快照（B4.2-B）。
 *
 * 为什么需要它：Kotlin 不为接口生成 `copy()`，基类共享函数拿到的是接口类型的
 * [VideoGenerationState]，无法直接 `_uiState.value = state.copy(...)`。
 * 解法：共享函数先把要写的字段改到本快照上（快照由当前 UiState 全量初始化），
 * 再由子类的 `applyVideoSnapshot` 一次性 copy 回具体 UiState。
 * 子类未搬进接口的独有字段（图位 / 音频 / 上传状态等）不经过快照，
 * copy 时直接沿用 `state` 的原值，天然保留。
 */
class VideoStateSnapshot() : VideoGenerationState {

    // ---- Workflow selection ----
    override var selectedWorkflow: String = ""
    override var selectedWorkflowId: String = ""
    override var availableWorkflows: List<VideoWorkflowItem> = emptyList()
    override var workflowPlaceholders: Set<String> = emptySet()

    // ---- 动态节点参数（通用节点参数引擎） ----
    override var dynamicParams: List<DynamicParam> = emptyList()
    override var dynamicParamValues: Map<String, String> = emptyMap()
    override var dynamicParamsRegistryReady: Boolean = false

    // ---- Model selections - single-model patterns ----
    override var selectedCheckpoint: String = ""
    override var selectedUnet: String = ""
    override var selectedLoraName: String = ""
    override var loraStrength: Float = 1.0f

    // ---- Model selections - dual-model patterns ----
    override var selectedHighnoiseUnet: String = ""
    override var selectedLownoiseUnet: String = ""
    override var selectedHighnoiseLora: String = ""
    override var selectedLownoiseLora: String = ""

    // ---- Model selections - common ----
    override var selectedVae: String = ""
    override var selectedClip: String = ""
    override var selectedClip1: String = ""
    override var selectedClip2: String = ""
    override var selectedClip3: String = ""
    override var selectedClip4: String = ""
    override var selectedTextEncoder: String = ""
    override var selectedLatentUpscaleModel: String = ""

    // ---- Available models ----
    override var availableCheckpoints: List<String> = emptyList()
    override var availableUnets: List<String> = emptyList()
    override var availableLoras: List<String> = emptyList()
    override var availableVaes: List<String> = emptyList()
    override var availableClips: List<String> = emptyList()
    override var availableUpscaleMethods: List<String> = emptyList()
    override var availableTextEncoders: List<String> = emptyList()
    override var availableLatentUpscaleModels: List<String> = emptyList()
    override var availableSamplers: List<String> = emptyList()
    override var availableSchedulers: List<String> = emptyList()
    override var availableAspectRatios: List<String> = emptyList()
    override var availableSpeedModes: List<String> = emptyList()

    // ---- Workflow-specific filtered options ----
    override var filteredCheckpoints: List<String>? = null
    override var filteredUnets: List<String>? = null
    override var filteredLoras: List<String>? = null
    override var filteredVaes: List<String>? = null
    override var filteredClips: List<String>? = null
    override var filteredClips1: List<String>? = null
    override var filteredClips2: List<String>? = null
    override var filteredClips3: List<String>? = null
    override var filteredClips4: List<String>? = null
    override var filteredTextEncoders: List<String>? = null
    override var filteredLatentUpscaleModels: List<String>? = null

    // ---- Generation parameters ----
    override var width: String = "848"
    override var height: String = "480"
    override var megapixels: String = "1.0"
    override var length: String = "33"
    override var fps: String = "16"
    override var steps: String = "20"
    override var cfg: String = "7.0"
    override var sampler: String = "euler"
    override var scheduler: String = "normal"
    override var aspectRatio: String = ""
    override var speedMode: String = ""
    override var seedMode: SeedMode = SeedMode.RANDOM
    override var seed: String = "0"
    override var denoise: String = "1.0"
    override var batchSize: String = "1"
    override var upscaleMethod: String = "nearest-exact"
    override var scaleBy: String = "1.5"
    override var stopAtClipLayer: String = "-1"

    // ---- Validation errors ----
    override var widthError: String? = null
    override var heightError: String? = null
    override var megapixelsError: String? = null
    override var lengthError: String? = null
    override var fpsError: String? = null
    override var stepsError: String? = null
    override var cfgError: String? = null
    override var seedError: String? = null
    override var denoiseError: String? = null
    override var batchSizeError: String? = null
    override var scaleByError: String? = null
    override var stopAtClipLayerError: String? = null

    // ---- Prompts ----
    override var positivePrompt: String = ""
    override var negativePrompt: String = ""

    // ---- Deferred model selections ----
    override var deferredCheckpoint: String? = null
    override var deferredUnet: String? = null
    override var deferredLoraName: String? = null
    override var deferredHighnoiseUnet: String? = null
    override var deferredLownoiseUnet: String? = null
    override var deferredHighnoiseLora: String? = null
    override var deferredLownoiseLora: String? = null
    override var deferredVae: String? = null
    override var deferredClip: String? = null
    override var deferredClip1: String? = null
    override var deferredClip2: String? = null
    override var deferredClip3: String? = null
    override var deferredClip4: String? = null
    override var deferredTextEncoder: String? = null
    override var deferredLatentUpscaleModel: String? = null

    // ---- Additional LoRA chains ----
    override var highnoiseLoraChain: List<LoraSelection> = emptyList()
    override var lownoiseLoraChain: List<LoraSelection> = emptyList()

    // ---- Primary LoRA chain ----
    override var loraChain: List<LoraSelection> = emptyList()

    // ---- Workflow capabilities / fetch state / media ----
    override var capabilities: WorkflowCapabilities = WorkflowCapabilities()
    override var previewBitmap: Bitmap? = null
    override var currentVideoUri: Uri? = null
    override var isFetching: Boolean = false

    companion object {
        /** 从当前状态全量初始化快照（所有接口字段逐个拷贝，缺一个共享写入就会丢值） */
        fun of(state: VideoGenerationState): VideoStateSnapshot = VideoStateSnapshot().also { s ->
            s.selectedWorkflow = state.selectedWorkflow
            s.selectedWorkflowId = state.selectedWorkflowId
            s.availableWorkflows = state.availableWorkflows
            s.workflowPlaceholders = state.workflowPlaceholders
            s.dynamicParams = state.dynamicParams
            s.dynamicParamValues = state.dynamicParamValues
            s.dynamicParamsRegistryReady = state.dynamicParamsRegistryReady
            s.selectedCheckpoint = state.selectedCheckpoint
            s.selectedUnet = state.selectedUnet
            s.selectedLoraName = state.selectedLoraName
            s.loraStrength = state.loraStrength
            s.selectedHighnoiseUnet = state.selectedHighnoiseUnet
            s.selectedLownoiseUnet = state.selectedLownoiseUnet
            s.selectedHighnoiseLora = state.selectedHighnoiseLora
            s.selectedLownoiseLora = state.selectedLownoiseLora
            s.selectedVae = state.selectedVae
            s.selectedClip = state.selectedClip
            s.selectedClip1 = state.selectedClip1
            s.selectedClip2 = state.selectedClip2
            s.selectedClip3 = state.selectedClip3
            s.selectedClip4 = state.selectedClip4
            s.selectedTextEncoder = state.selectedTextEncoder
            s.selectedLatentUpscaleModel = state.selectedLatentUpscaleModel
            s.availableCheckpoints = state.availableCheckpoints
            s.availableUnets = state.availableUnets
            s.availableLoras = state.availableLoras
            s.availableVaes = state.availableVaes
            s.availableClips = state.availableClips
            s.availableUpscaleMethods = state.availableUpscaleMethods
            s.availableTextEncoders = state.availableTextEncoders
            s.availableLatentUpscaleModels = state.availableLatentUpscaleModels
            s.availableSamplers = state.availableSamplers
            s.availableSchedulers = state.availableSchedulers
            s.availableAspectRatios = state.availableAspectRatios
            s.availableSpeedModes = state.availableSpeedModes
            s.filteredCheckpoints = state.filteredCheckpoints
            s.filteredUnets = state.filteredUnets
            s.filteredLoras = state.filteredLoras
            s.filteredVaes = state.filteredVaes
            s.filteredClips = state.filteredClips
            s.filteredClips1 = state.filteredClips1
            s.filteredClips2 = state.filteredClips2
            s.filteredClips3 = state.filteredClips3
            s.filteredClips4 = state.filteredClips4
            s.filteredTextEncoders = state.filteredTextEncoders
            s.filteredLatentUpscaleModels = state.filteredLatentUpscaleModels
            s.width = state.width
            s.height = state.height
            s.megapixels = state.megapixels
            s.length = state.length
            s.fps = state.fps
            s.steps = state.steps
            s.cfg = state.cfg
            s.sampler = state.sampler
            s.scheduler = state.scheduler
            s.aspectRatio = state.aspectRatio
            s.speedMode = state.speedMode
            s.seedMode = state.seedMode
            s.seed = state.seed
            s.denoise = state.denoise
            s.batchSize = state.batchSize
            s.upscaleMethod = state.upscaleMethod
            s.scaleBy = state.scaleBy
            s.stopAtClipLayer = state.stopAtClipLayer
            s.widthError = state.widthError
            s.heightError = state.heightError
            s.megapixelsError = state.megapixelsError
            s.lengthError = state.lengthError
            s.fpsError = state.fpsError
            s.stepsError = state.stepsError
            s.cfgError = state.cfgError
            s.seedError = state.seedError
            s.denoiseError = state.denoiseError
            s.batchSizeError = state.batchSizeError
            s.scaleByError = state.scaleByError
            s.stopAtClipLayerError = state.stopAtClipLayerError
            s.positivePrompt = state.positivePrompt
            s.negativePrompt = state.negativePrompt
            s.deferredCheckpoint = state.deferredCheckpoint
            s.deferredUnet = state.deferredUnet
            s.deferredLoraName = state.deferredLoraName
            s.deferredHighnoiseUnet = state.deferredHighnoiseUnet
            s.deferredLownoiseUnet = state.deferredLownoiseUnet
            s.deferredHighnoiseLora = state.deferredHighnoiseLora
            s.deferredLownoiseLora = state.deferredLownoiseLora
            s.deferredVae = state.deferredVae
            s.deferredClip = state.deferredClip
            s.deferredClip1 = state.deferredClip1
            s.deferredClip2 = state.deferredClip2
            s.deferredClip3 = state.deferredClip3
            s.deferredClip4 = state.deferredClip4
            s.deferredTextEncoder = state.deferredTextEncoder
            s.deferredLatentUpscaleModel = state.deferredLatentUpscaleModel
            s.highnoiseLoraChain = state.highnoiseLoraChain
            s.lownoiseLoraChain = state.lownoiseLoraChain
            s.loraChain = state.loraChain
            s.capabilities = state.capabilities
            s.previewBitmap = state.previewBitmap
            s.currentVideoUri = state.currentVideoUri
            s.isFetching = state.isFetching
        }
    }
}

/**
 * 文生视频 / 图生视频两个 ViewModel 的中间基类（B4.2-B 视频对合并）。
 *
 * 两个视频页原先各有约 700 行逐字相同的实现（onXxxChange 系列、init 的 modelCache
 * 监听、工作流加载 / 保存 / 恢复、预览与视频取回、LoRA 链操作、事件处理），全部收敛到这里。
 * 两侧的语义分叉（媒体档位 Ttv 与 Itv、prefs 文件名、季节提示词、事件类型、图位增量等）
 * 用构造参数 / 抽象属性 / open 钩子表达，见各类成员的 KDoc。
 *
 * 状态写入约束：data class 的 `copy()` 无法跨接口调用，所以共享函数统一走
 * [updateVideo]（快照改字段 → 子类 [applyVideoSnapshot] 一次性回拷）。
 * 时序红线：init 里 modelCache 监听的「空快照守卫 + deferred 清 null」同监听体迁移；
 * onWorkflowChange 的 saveWorkflowValues(旧流) → loadWorkflowValues(新流) 链序不变；
 * restorePreferences 三步序不变；loadValues 在前、cache 快照在后的原序不变。
 *
 * @param videoOwnerId 本页在 GenerationViewModel 里的注册键。必须走构造参数：
 *        基类 init 要拿它注册标签库桥接，抽象属性在基类构造期读不到（子类属性尚未初始化）。
 */
abstract class BaseVideoGenerationViewModel<UiState : VideoGenerationState, Event>(
    private val videoOwnerId: String
) : BaseGenerationViewModel<UiState, Event>() {

    /** 本页在 [GenerationViewModel] 里的注册键（构造参数初始化，见类 KDoc） */
    override val generationOwnerId: String = videoOwnerId

    /**
     * 当前正在播放的视频键（5.2）登记器：拿到 URI 时登记、切走 / 清空时注销。
     * 语义见 [PlayingVideoRegistry]。
     */
    protected val playingVideo = PlayingVideoRegistry()

    // ---- 两侧语义分叉点（子类覆写） ----

    /** 日志 TAG（原两侧 companion 的 TAG 常量） */
    protected abstract val logTag: String
    /** SharedPreferences 文件名（原两侧 PREFS_NAME 常量） */
    protected abstract val prefsName: String
    /** 页面的工作流类型（工作流枚举 / LoRA 链注入用） */
    protected abstract val videoWorkflowType: WorkflowType
    /** 正向提示词的季节默认值（TTV/ITV 各自的 SeasonalPrompts 取值） */
    protected abstract val defaultPositivePrompt: String
    /** 从服务器历史取回视频时的文件名前缀（原 VideoUtils.FilePrefix 两侧常量） */
    protected abstract val videoFilePrefix: String

    /** 当前「最近一次生成的视频」promptId（原 MediaStateHolder.getCurrentTtv/ItvPromptId） */
    protected abstract fun currentVideoPromptId(): String?
    /** 清掉「最近一次生成的视频」登记（原 clearCurrentTtv/ItvPromptId） */
    protected abstract fun clearCurrentVideoPromptId()
    /** 视频 media 档位（原 MediaKey.TtvVideo / ItvVideo） */
    protected abstract fun videoMediaKey(promptId: String): MediaStateHolder.MediaKey
    /** 预览帧 media 档位（原 MediaKey.TtvPreview / ItvPreview） */
    protected abstract val previewMediaKey: MediaStateHolder.MediaKey

    /** Toast 事件（resId）：两侧事件类型不同（TextToVideoEvent / ImageToVideoEvent） */
    protected abstract fun showToastEvent(messageResId: Int)
    /** Toast 事件（文本）：两侧事件类型不同 */
    protected abstract fun showToastMessageEvent(message: String)

    /**
     * 把共享快照写回具体 UiState（子类一行全字段 copy）。
     * 子类独有字段不在快照里，直接沿用 [state] 的原值——这个函数必须拷贝**全部**
     * 接口字段，漏一个共享写入就会丢值（由 VideoStateSnapshotRoundTripTest 钉住）。
     */
    protected abstract fun applyVideoSnapshot(state: UiState, snapshot: VideoStateSnapshot): UiState

    /**
     * 生成配置检查的页面特有早退（open hook，默认无）。
     * ITV 覆写：旧版「时间线导演台」流 + 图位无图两段早退；TTV 无额外检查。
     */
    protected open fun videoSpecificInvalidConfiguration(state: UiState): Boolean = false

    /**
     * startListening 的页面级钩子（open，默认空）。
     * ITV 覆写：本页生成进行中时切到 PREVIEW 视图；TTV 无此动作。
     */
    protected open fun onListeningStarted(generationViewModel: GenerationViewModel) = Unit

    /**
     * loadWorkflowValues 里 selectedClip3 的 defaults 中间级（open hook）。
     * 两侧回退口径有意分歧、不许静默统一：TTV 三级（saved → defaults?.clip3Model → state）
     * 覆写返回 defaults 值；ITV 二级（saved → state）用默认实现（返回 null）。
     */
    protected open fun clip3DefaultFromDefaults(defaults: WorkflowDefaults?, clips: List<String>): String? = null

    /**
     * 图位 / 音频 / 旧时间线探测（open hook）。TTV 默认：字符串重载只探图位（原实现）；
     * ITV 覆写：用已解析 root 一并探音频与旧时间线（原实现）。
     */
    protected open fun detectVideoSlots(item: VideoWorkflowItem, slotsRoot: JSONObject?): VideoSlotsDetection =
        VideoSlotsDetection(
            imageSlots = ImageSlotDetector.detect(
                workflowJson = WorkflowManager.getWorkflowById(item.id)?.jsonContent.orEmpty(),
                // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
                objectInfo = null
            ),
            audioSlots = emptyList(),
            legacyTimelineUnsupported = false
        )

    /**
     * loadWorkflowValues 的一次原子写入里，子类对独有字段的增量（open hook，默认原样返回）。
     * ITV 覆写：图位定义 / 已填图位图 / 编号与音频容器 / 旧时间线标志。
     * 拿到的 [state] 已含公共字段的更新值；返回值作为最终状态一次写入。
     */
    protected open fun amendWorkflowValuesState(state: UiState, detection: VideoSlotsDetection): UiState = state

    companion object {
        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val KEY_POSITIVE_PROMPT = "positivePrompt"
    }

    init {
        // 注册到标签库桥接：使标签库页面可以读写本页的正/负向提示词（需求 5.2）。
        PromptBridge.register(
            PromptTargetFactory.create(
                targetOwnerId = generationOwnerId,
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
                            logTag,
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

                    val snapshot = VideoStateSnapshot.of(state)
                    snapshot.availableCheckpoints = cache.checkpoints
                    snapshot.availableUnets = cache.unets
                    snapshot.availableLoras = cache.loras
                    snapshot.availableVaes = cache.vaes
                    snapshot.availableClips = cache.clips
                    snapshot.availableTextEncoders = cache.textEncoders
                    snapshot.availableLatentUpscaleModels = cache.latentUpscaleModels
                    snapshot.availableSamplers = cache.samplers
                    snapshot.availableSchedulers = cache.schedulers
                    snapshot.availableAspectRatios = cache.aspectRatios
                    snapshot.availableSpeedModes = cache.speedModes
                    // Single-model selections
                    snapshot.selectedCheckpoint = checkpoint
                    snapshot.selectedUnet = unet
                    snapshot.selectedLoraName = loraName
                    // Dual-model selections
                    snapshot.selectedHighnoiseUnet = highnoiseUnet
                    snapshot.selectedLownoiseUnet = lownoiseUnet
                    snapshot.selectedHighnoiseLora = highnoiseLora
                    snapshot.selectedLownoiseLora = lownoiseLora
                    // Common selections
                    snapshot.selectedVae = vae
                    snapshot.selectedClip = clip
                    snapshot.selectedClip1 = clip1
                    snapshot.selectedClip2 = clip2
                    snapshot.selectedClip3 = clip3
                    snapshot.selectedClip4 = clip4
                    snapshot.selectedTextEncoder = textEncoder
                    snapshot.selectedLatentUpscaleModel = latentUpscaleModel
                    // Clear deferred values once applied
                    snapshot.deferredCheckpoint = null
                    snapshot.deferredUnet = null
                    snapshot.deferredLoraName = null
                    snapshot.deferredHighnoiseUnet = null
                    snapshot.deferredLownoiseUnet = null
                    snapshot.deferredHighnoiseLora = null
                    snapshot.deferredLownoiseLora = null
                    snapshot.deferredVae = null
                    snapshot.deferredClip = null
                    snapshot.deferredClip1 = null
                    snapshot.deferredClip2 = null
                    snapshot.deferredClip3 = null
                    snapshot.deferredClip4 = null
                    snapshot.deferredTextEncoder = null
                    snapshot.deferredLatentUpscaleModel = null
                    // Filter LoRA chains
                    snapshot.highnoiseLoraChain = LoraChainManager.filterUnavailable(state.highnoiseLoraChain, cache.loras)
                    snapshot.lownoiseLoraChain = LoraChainManager.filterUnavailable(state.lownoiseLoraChain, cache.loras)
                    applyVideoSnapshot(state, snapshot)
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

    override fun onCleared() {
        super.onCleared()
        playingVideo.clear()
        // P2-20：注销标签库桥接，静态 map 不再钉住已销毁的 ViewModel
        PromptBridge.unregister(generationOwnerId)
    }

    override fun onInitialize() {
        DebugLogger.i(logTag, "Initializing")

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
        val promptId = currentVideoPromptId() ?: return

        val key = videoMediaKey(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            val uri = runBlocking {
                MediaStateHolder.getVideoUri(context, key)
            }
            if (uri != null) {
                updateVideo { it.currentVideoUri = uri }
                // 播放方登记（5.2）：裁剪不要删掉这个文件（磁盘优先模式下 URI 就指向它）
                playingVideo.mark(key)
            }
        }
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        val workflows = WorkflowManager.getWorkflowsByType(videoWorkflowType)
            .filter { showBuiltIn || !it.isBuiltIn }

        val unifiedWorkflows = workflows.map { workflow ->
            VideoWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = videoWorkflowType
            )
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        updateVideo {
            it.availableWorkflows = sortedWorkflows
            it.selectedWorkflow = selectedWorkflowItem?.name ?: ""
            it.selectedWorkflowId = selectedWorkflowItem?.id ?: ""
        }

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        if (selectedWorkflowItem != null) {
            loadWorkflowValues(selectedWorkflowItem)
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization and workflow changes)
     */
    private fun loadWorkflowValues(item: VideoWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values and defaults by workflow ID (not name, to avoid duplicate name issues)
        val savedValues = storage.loadValues(serverId, item.id)
        val defaults = WorkflowManager.getWorkflowDefaultsById(item.id)
        val cache = ConnectionManager.modelCache.value

        // Get workflow placeholders - these determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(item.id)

        // 图位探测用的工作流 JSON：两侧能力装配共用（TTV 默认探测走字符串重载、
        // ITV 覆写用已解析的 root 一并探音频与旧时间线，见 detectVideoSlots）
        val workflowJsonForSlots = WorkflowManager.getWorkflowById(item.id)?.jsonContent.orEmpty()
        val slotsRoot = runCatching { JSONObject(workflowJsonForSlots) }.getOrNull()

        val detection = detectVideoSlots(item, slotsRoot)

        // 动态节点参数（通用节点参数引擎）：扫描 + 类型推导，并按
        // 「已有保存值 → defaults.<占位符名> → 节点定义的 default → 空」取值
        val dynamicParamsSnapshot = WorkflowManager.loadDynamicParams(
            item.id,
            savedValues?.dynamicParams ?: emptyMap()
        )

        val state = _uiState.value
        val snapshot = VideoStateSnapshot.of(state)
        snapshot.selectedWorkflow = item.name
        snapshot.selectedWorkflowId = item.id
        snapshot.workflowPlaceholders = placeholders
        snapshot.dynamicParams = dynamicParamsSnapshot.params
        snapshot.dynamicParamValues = dynamicParamsSnapshot.values
        snapshot.dynamicParamsRegistryReady = ConnectionManager.nodeTypeRegistry.isPopulated()
        snapshot.negativePrompt = savedValues?.negativePrompt
            ?: defaults?.negativePrompt ?: ""
        snapshot.width = savedValues?.width?.toString()
            ?: defaults?.width?.toString() ?: "848"
        snapshot.height = savedValues?.height?.toString()
            ?: defaults?.height?.toString() ?: "480"
        snapshot.length = savedValues?.length?.toString()
            ?: defaults?.length?.toString() ?: "33"
        snapshot.fps = savedValues?.frameRate?.toString()
            ?: defaults?.frameRate?.toString() ?: "16"
        // 步数：已保存值 → 工作流默认值 → 状态缺省。
        // 视频流此前完全不读 defaults.steps，工作流里写死的 4/8 会被缺省 20 覆盖，
        // 把 steps 做成占位符后必须在这里预填，否则默认值等于被改掉。
        snapshot.steps = savedValues?.steps?.toString()
            ?: defaults?.steps?.toString() ?: state.steps
        // 主 LoRA 权重：已保存值 → 工作流默认值 → 状态缺省
        snapshot.loraStrength = savedValues?.loraStrength
            ?: defaults?.loraStrength ?: state.loraStrength
        // 百万像素 / 宽高比 / 加速模式：已保存值 → 工作流默认值 → 状态缺省（服务器选项首项）
        snapshot.megapixels = savedValues?.megapixels?.toString()
            ?: defaults?.megapixels?.toString() ?: state.megapixels
        snapshot.aspectRatio = savedValues?.aspectRatio
            ?: defaults?.aspectRatio ?: state.aspectRatio
        snapshot.speedMode = savedValues?.speedMode
            ?: defaults?.speedMode ?: state.speedMode
        // 种子：优先上次保存值，其次工作流默认值（与文生图一致），否则 0
        snapshot.seed = savedValues?.seed?.toString()
            ?: defaults?.seed?.toString() ?: "0"
        snapshot.seedMode = SeedMode.fromSaved(savedValues?.seedMode, savedValues?.randomSeed)
        // Apply single-model selections with deferred mechanism
        snapshot.selectedCheckpoint = savedValues?.model?.takeIf { it in cache.checkpoints }
            ?: defaults?.checkpointModel?.takeIf { it in cache.checkpoints }
            ?: state.selectedCheckpoint
        snapshot.selectedUnet = savedValues?.unetModel?.takeIf { it in cache.unets }
            ?: defaults?.unetModel?.takeIf { it in cache.unets }
            ?: state.selectedUnet
        // 主 LoRA：历史保存值里可能存着字面量 "None"（旧版"未选择"的写法），
        // 先归一化为空 = 不使用，否则它会被当成真实 LoRA 名提交并 400
        snapshot.selectedLoraName = LoraSelection.normalizeName(savedValues?.loraModel).takeIf { it in cache.loras }
            ?: LoraSelection.normalizeName(defaults?.loraModel).takeIf { it in cache.loras }
            ?: state.selectedLoraName
        // Apply dual-model selections with deferred mechanism
        snapshot.selectedHighnoiseUnet = savedValues?.highnoiseUnetModel?.takeIf { it in cache.unets }
            ?: defaults?.unetModel?.takeIf { it in cache.unets }
            ?: state.selectedHighnoiseUnet
        snapshot.selectedLownoiseUnet = savedValues?.lownoiseUnetModel?.takeIf { it in cache.unets }
            ?: defaults?.unetModel?.takeIf { it in cache.unets }
            ?: state.selectedLownoiseUnet
        snapshot.selectedHighnoiseLora = savedValues?.highnoiseLoraModel?.takeIf { it in cache.loras }
            ?: state.selectedHighnoiseLora
        snapshot.selectedLownoiseLora = savedValues?.lownoiseLoraModel?.takeIf { it in cache.loras }
            ?: state.selectedLownoiseLora
        // Apply common model selections
        snapshot.selectedVae = savedValues?.vaeModel?.takeIf { it in cache.vaes }
            ?: defaults?.vaeModel?.takeIf { it in cache.vaes }
            ?: state.selectedVae
        snapshot.selectedClip = savedValues?.clipModel?.takeIf { it in cache.clips }
            ?: defaults?.clipModel?.takeIf { it in cache.clips }
            ?: state.selectedClip
        snapshot.selectedClip1 = savedValues?.clip1Model?.takeIf { it in cache.clips }
            ?: defaults?.clip1Model?.takeIf { it in cache.clips }
            ?: state.selectedClip1
        snapshot.selectedClip2 = savedValues?.clip2Model?.takeIf { it in cache.clips }
            ?: defaults?.clip2Model?.takeIf { it in cache.clips }
            ?: state.selectedClip2
        // selectedClip3 的回退口径两侧有意分歧（TTV 三级 saved→defaults?.clip3Model→state，
        // ITV 二级 saved→state），不许静默统一——defaults 中间级由子类 hook 给出
        snapshot.selectedClip3 = savedValues?.clip3Model?.takeIf { it in cache.clips }
            ?: clip3DefaultFromDefaults(defaults, cache.clips)
            ?: state.selectedClip3
        snapshot.selectedClip4 = savedValues?.clip4Model?.takeIf { it in cache.clips }
            ?: state.selectedClip4
        snapshot.selectedTextEncoder = savedValues?.textEncoderModel?.takeIf { it in cache.textEncoders }
            ?: state.selectedTextEncoder
        snapshot.selectedLatentUpscaleModel = savedValues?.latentUpscaleModel?.takeIf { it in cache.latentUpscaleModels }
            ?: state.selectedLatentUpscaleModel
        // Deferred values for when cache updates - single-model
        snapshot.deferredCheckpoint = savedValues?.model
        snapshot.deferredUnet = savedValues?.unetModel ?: defaults?.unetModel
        snapshot.deferredLoraName = savedValues?.loraModel
        // Deferred values for when cache updates - dual-model
        snapshot.deferredHighnoiseUnet = savedValues?.highnoiseUnetModel
        snapshot.deferredLownoiseUnet = savedValues?.lownoiseUnetModel
        snapshot.deferredHighnoiseLora = savedValues?.highnoiseLoraModel
        snapshot.deferredLownoiseLora = savedValues?.lownoiseLoraModel
        // Deferred values for when cache updates - common
        snapshot.deferredVae = savedValues?.vaeModel ?: defaults?.vaeModel
        snapshot.deferredClip = savedValues?.clipModel ?: defaults?.clipModel
        snapshot.deferredClip1 = savedValues?.clip1Model ?: defaults?.clip1Model
        snapshot.deferredClip2 = savedValues?.clip2Model ?: defaults?.clip2Model
        snapshot.deferredClip3 = savedValues?.clip3Model ?: defaults?.clip3Model
        snapshot.deferredClip4 = savedValues?.clip4Model
        snapshot.deferredTextEncoder = savedValues?.textEncoderModel
        snapshot.deferredLatentUpscaleModel = savedValues?.latentUpscaleModel
        snapshot.highnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
        snapshot.lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
        // Workflow-specific filtered options
        snapshot.filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(item.id, "ckpt_name")
        snapshot.filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(item.id, "unet_name")
            ?: WorkflowManager.getNodeSpecificOptionsForField(item.id, "highnoise_unet_name")
        snapshot.filteredLoras = WorkflowManager.getNodeSpecificOptionsForField(item.id, "lora_name")
        snapshot.filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(item.id, "vae_name")
        snapshot.filteredClips = WorkflowManager.getNodeSpecificOptionsForField(item.id, "clip_name")
        snapshot.filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(item.id, "clip_name1")
        snapshot.filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(item.id, "clip_name2")
        snapshot.filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(item.id, "clip_name3")
        snapshot.filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(item.id, "clip_name4")
        snapshot.filteredTextEncoders = WorkflowManager.getNodeSpecificOptionsForField(item.id, "text_encoder_name")
        snapshot.filteredLatentUpscaleModels = WorkflowManager.getNodeSpecificOptionsForField(item.id, "latent_upscale_model")
        // Workflow capabilities from placeholders
        snapshot.capabilities = WorkflowCapabilities.fromPlaceholders(
            placeholders = placeholders,
            // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
            imageSlots = detection.imageSlots,
            // 3D 流按真实节点判定 LoRA 面板去留（prebuilt_29 有节点保留，28/32 无节点隐藏）
            workflowType = item.type,
            workflowJson = WorkflowManager.getWorkflowById(item.id)?.jsonContent
        )
        // 一次原子写入：公共字段经快照回拷，子类独有字段（图位/音频等）由 amend 钩子补齐
        _uiState.value = amendWorkflowValuesState(applyVideoSnapshot(state, snapshot), detection)
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Load global preferences with serverId prefix
        val savedWorkflowId = prefs.getString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", "") ?: ""
        val savedPositivePrompt = prefs.getString("${serverId}_$KEY_POSITIVE_PROMPT", null) ?: defaultPositivePrompt

        // Update positive prompt first
        updateVideo { it.positivePrompt = savedPositivePrompt }

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
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$KEY_POSITIVE_PROMPT", state.positivePrompt)
            .apply()

        // Save per-workflow values using workflow ID
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }
    }

    /**
     * 共享状态写入原语：读当前状态 → 快照全量初始化 → 调用方改字段 → 一次回拷。
     * lambda 参数风格（`it.` 前缀）而非接收者风格，避免快照字段遮蔽外层函数参数。
     */
    protected fun updateVideo(mutate: (VideoStateSnapshot) -> Unit) {
        val state = _uiState.value
        val snapshot = VideoStateSnapshot.of(state)
        mutate(snapshot)
        _uiState.value = applyVideoSnapshot(state, snapshot)
    }

    fun onWorkflowChange(workflow: String) {
        val state = _uiState.value

        // Find workflow item to get its ID
        val workflowItem = state.availableWorkflows.find { it.name == workflow } ?: return

        // 重选当前这条流：直接返回，不能往下走。下拉里当前项是可以点的，而
        // loadWorkflowValues 会把界面上调过的值全部重置回工作流默认（参数与
        // 生成范围也一样）——"手滑点一下自己"会让刚调好的值无声消失。
        // 工作流管理里改元数据也会触发一次重载，这里一并挡掉。
        // （B4.2-B 起 TTV 与 ITV 统一带这道守卫——这是行为增强：TTV 原先没有。）
        if (workflowItem.id == state.selectedWorkflowId) return

        DebugLogger.d(logTag, "onWorkflowChange: ${Obfuscator.workflowName(workflow)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

        savePreferences()
    }

    fun onHighnoiseUnetChange(unet: String) {
        updateVideo { it.selectedHighnoiseUnet = unet }
        savePreferences()
    }

    fun onLownoiseUnetChange(unet: String) {
        updateVideo { it.selectedLownoiseUnet = unet }
        savePreferences()
    }

    fun onHighnoiseLoraChange(lora: String) {
        updateVideo { it.selectedHighnoiseLora = lora }
        savePreferences()
    }

    fun onLownoiseLoraChange(lora: String) {
        updateVideo { it.selectedLownoiseLora = lora }
        savePreferences()
    }

    // Single-model callbacks
    fun onCheckpointChange(checkpoint: String) {
        updateVideo { it.selectedCheckpoint = checkpoint }
        savePreferences()
    }

    fun onUnetChange(unet: String) {
        updateVideo { it.selectedUnet = unet }
        savePreferences()
    }

    fun onMandatoryLoraChange(lora: String) {
        updateVideo { it.selectedLoraName = lora }
        savePreferences()
    }

    /**
     * 主 LoRA 权重（{{lora_strength}}）变化。滑块本身把值限制在
     * LoraSelection.MIN_STRENGTH..MAX_STRENGTH，无需再做校验。
     */
    fun onMandatoryLoraStrengthChange(strength: Float) {
        updateVideo { it.loraStrength = strength }
        savePreferences()
    }

    fun onVaeChange(vae: String) {
        updateVideo { it.selectedVae = vae }
        savePreferences()
    }

    fun onClipChange(clip: String) {
        updateVideo { it.selectedClip = clip }
        savePreferences()
    }

    fun onClip1Change(clip: String) {
        updateVideo { it.selectedClip1 = clip }
        savePreferences()
    }

    fun onClip2Change(clip: String) {
        updateVideo { it.selectedClip2 = clip }
        savePreferences()
    }

    fun onClip3Change(clip: String) {
        updateVideo { it.selectedClip3 = clip }
        savePreferences()
    }

    fun onClip4Change(clip: String) {
        updateVideo { it.selectedClip4 = clip }
        savePreferences()
    }

    fun onTextEncoderChange(textEncoder: String) {
        updateVideo { it.selectedTextEncoder = textEncoder }
        savePreferences()
    }

    fun onLatentUpscaleModelChange(model: String) {
        updateVideo { it.selectedLatentUpscaleModel = model }
        savePreferences()
    }

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        updateVideo {
            it.width = width
            it.widthError = error
        }
        if (error == null) savePreferences()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        updateVideo {
            it.height = height
            it.heightError = error
        }
        if (error == null) savePreferences()
    }

    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        updateVideo {
            it.megapixels = megapixels
            it.megapixelsError = error
        }
        if (error == null) savePreferences()
    }

    fun onLengthChange(length: String) {
        val error = ValidationUtils.validateVideoLength(length, applicationContext)
        updateVideo {
            it.length = length
            it.lengthError = error
        }
        if (error == null) savePreferences()
    }

    fun onFpsChange(fps: String) {
        val error = ValidationUtils.validateFrameRate(fps, applicationContext)
        updateVideo {
            it.fps = fps
            it.fpsError = error
        }
        if (error == null) savePreferences()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        updateVideo {
            it.steps = steps
            it.stepsError = error
        }
        if (error == null) savePreferences()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        updateVideo {
            it.cfg = cfg
            it.cfgError = error
        }
        if (error == null) savePreferences()
    }

    fun onSamplerChange(sampler: String) {
        updateVideo { it.sampler = sampler }
        savePreferences()
    }

    fun onSchedulerChange(scheduler: String) {
        updateVideo { it.scheduler = scheduler }
        savePreferences()
    }

    fun onAspectRatioChange(aspectRatio: String) {
        updateVideo { it.aspectRatio = aspectRatio }
        savePreferences()
    }

    fun onSpeedModeChange(speedMode: String) {
        updateVideo { it.speedMode = speedMode }
        savePreferences()
    }

    /**
     * 更新某个动态节点参数的值并持久化（通用节点参数引擎）。
     * 占位符清单由 [WorkflowManager.getDynamicParams] 在工作流加载时得到，见 dynamicParams。
     */
    fun onDynamicParamValueChange(placeholder: String, value: String) {
        updateVideo { it.dynamicParamValues = it.dynamicParamValues + (placeholder to value) }
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
        DebugLogger.d(logTag, "注册表就绪，重算动态参数：${update.params.size} 个控件")
        updateVideo {
            it.dynamicParams = update.params
            it.dynamicParamValues = update.values
            it.dynamicParamsRegistryReady = update.registryReady
        }
    }

    fun onSeedModeChange(mode: SeedMode) {
        updateVideo { it.seedMode = mode }
        savePreferences()
    }

    /**
     * 生成提交成功后调用（由 GenerationViewModel 的回调触发）。
     * 只在「每次+1 / 每次−1」下动作：把种子挪一格并存下来。
     */
    fun advanceSeedIfNeeded() {
        val state = _uiState.value
        val next = VideoGenerationSupport.advanceSeed(state) ?: return
        updateVideo {
            it.seed = next
            it.seedError = null
        }
        savePreferences()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        updateVideo {
            it.seed = seed
            it.seedError = error
        }
        if (error == null) savePreferences()
    }

    fun onRandomizeSeed() {
        // 随机种子限制在 UINT32 范围（与 ComfyUI 官方前端一致），避免超出部分节点
        // 的 INT 上限（如 SeedVR2VideoUpscaler seed max=4294967295）触发 400 校验失败
        val randomSeed = kotlin.random.Random.nextLong(0, 4294967296L).toString()
        updateVideo {
            it.seed = randomSeed
            it.seedError = null
        }
        savePreferences()
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        updateVideo {
            it.denoise = denoise
            it.denoiseError = error
        }
        if (error == null) savePreferences()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        updateVideo {
            it.batchSize = batchSize
            it.batchSizeError = error
        }
        if (error == null) savePreferences()
    }

    fun onUpscaleMethodChange(method: String) {
        updateVideo { it.upscaleMethod = method }
        savePreferences()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        updateVideo {
            it.scaleBy = scaleBy
            it.scaleByError = error
        }
        if (error == null) savePreferences()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        updateVideo {
            it.stopAtClipLayer = layer
            it.stopAtClipLayerError = error
        }
        if (error == null) savePreferences()
    }

    fun onPositivePromptChange(positivePrompt: String) {
        updateVideo { it.positivePrompt = positivePrompt }
        savePreferences()
    }

    fun onNegativePromptChange(negativePrompt: String) {
        updateVideo { it.negativePrompt = negativePrompt }
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
                val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().remove("${serverId}_$KEY_POSITIVE_PROMPT").apply()
            }

            // Set prompt to seasonal default
            val defaultPrompt = defaultPositivePrompt
            updateVideo { it.positivePrompt = defaultPrompt }

            // Emit toast event
            showToastEvent(R.string.msg_prompt_preset_reset_success)
        }
    }

    // Primary LoRA chain operations (for single-model workflows like LTX 2.0)
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

        updateVideo { it.highnoiseLoraChain = newChain }
        savePreferences()
    }

    fun onRemoveHighnoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.highnoiseLoraChain, index)
        if (newChain === state.highnoiseLoraChain) return // No change

        updateVideo { it.highnoiseLoraChain = newChain }
        savePreferences()
    }

    fun onHighnoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.highnoiseLoraChain, index, name)
        if (newChain === state.highnoiseLoraChain) return // No change

        updateVideo { it.highnoiseLoraChain = newChain }
        savePreferences()
    }

    fun onHighnoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.highnoiseLoraChain, index, strength)
        if (newChain === state.highnoiseLoraChain) return // No change

        updateVideo { it.highnoiseLoraChain = newChain }
        savePreferences()
    }

    // Low noise LoRA chain operations
    fun onAddLownoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.lownoiseLoraChain, state.availableLoras)
        if (newChain === state.lownoiseLoraChain) return // No change

        updateVideo { it.lownoiseLoraChain = newChain }
        savePreferences()
    }

    fun onRemoveLownoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.lownoiseLoraChain, index)
        if (newChain === state.lownoiseLoraChain) return // No change

        updateVideo { it.lownoiseLoraChain = newChain }
        savePreferences()
    }

    fun onLownoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.lownoiseLoraChain, index, name)
        if (newChain === state.lownoiseLoraChain) return // No change

        updateVideo { it.lownoiseLoraChain = newChain }
        savePreferences()
    }

    fun onLownoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.lownoiseLoraChain, index, strength)
        if (newChain === state.lownoiseLoraChain) return // No change

        updateVideo { it.lownoiseLoraChain = newChain }
        savePreferences()
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        updateVideo { it.previewBitmap = bitmap }
        saveLastPreviewImage(bitmap)
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) return
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(previewMediaKey)
        updateVideo {
            it.previewBitmap = null
            it.currentVideoUri = null
        }
        // 播放已清空：注销裁剪保护（5.2）
        playingVideo.clear()
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        clearCurrentVideoPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        updateVideo {
            it.previewBitmap = null
            it.currentVideoUri = null
        }
        // 播放已清空：注销裁剪保护（5.2）
        playingVideo.clear()
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        clearCurrentVideoPromptId()
    }

    // ---- prepareWorkflow 的公共段（两侧签名各自保留：TTV 非 suspend 直通、ITV suspend 上传） ----

    /** 宽/高/时长/帧率的一揽子解析值 */
    data class VideoDims(val width: Int, val height: Int, val length: Int, val fps: Int)

    /**
     * 提交前的字段校验：数值字段校验错误检查 + 四项解析。
     * 任一不过返回 null（与原两侧实现逐行一致）。
     */
    protected fun validatedVideoDims(state: UiState): VideoDims? {
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null
        return VideoDims(width, height, length, fps)
    }

    /**
     * 提交前兜底（1.3）：视频页的 sampler / scheduler 不落盘（见 VideoGenerationSupport.buildWorkflowValues），
     * 取值来自缺省常量，但备份恢复 / 节点编辑等旁路仍可能把它们写脏，这里统一拦一道。
     * 改了值就同步回界面（与文生图 prepareWorkflowJson 同口径：下拉框不该继续显示脏值，
     * 下次提交也不必再清洗一遍）。
     *
     * @return safeSampler to safeScheduler
     */
    protected fun sanitizeVideoSamplers(state: UiState): Pair<String, String> {
        val safeSampler = SamplerOptions.sanitizeSampler(state.sampler, state.availableSamplers)
        val safeScheduler = SamplerOptions.sanitizeScheduler(state.scheduler, state.availableSchedulers)
        if (safeSampler != state.sampler || safeScheduler != state.scheduler) {
            DebugLogger.w(logTag, "采样参数非法，已纠正为 sampler=$safeSampler, scheduler=$safeScheduler")
            updateVideo {
                it.sampler = safeSampler
                it.scheduler = safeScheduler
            }
        }
        return safeSampler to safeScheduler
    }

    /**
     * base workflow 装配完成后的三条 LoRA 链注入（主链 / 高噪 / 低噪）。
     * 逐条与原两侧实现一致，主链的 WorkflowType 用 [videoWorkflowType] 分叉。
     */
    protected fun injectVideoLoraChains(workflow: String, state: UiState): String {
        // Inject LoRAs if configured
        var result = workflow

        // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
        if (state.loraChain.isNotEmpty()) {
            result = WorkflowManager.injectLoraChain(result, state.loraChain, videoWorkflowType)
        }

        // Dual-model LoRA chains for workflows like Wan 2.2
        if (state.highnoiseLoraChain.isNotEmpty()) {
            result = WorkflowManager.injectAdditionalVideoLoras(result, state.highnoiseLoraChain, isHighNoise = true)
        }
        if (state.lownoiseLoraChain.isNotEmpty()) {
            result = WorkflowManager.injectAdditionalVideoLoras(result, state.lownoiseLoraChain, isHighNoise = false)
        }
        return result
    }

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        // 页面特有早退（ITV：旧时间线流 + 图位无图；TTV 无）
        if (videoSpecificInvalidConfiguration(state)) {
            return false
        }

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
        generationViewModel.registerSubmittedCallback(generationOwnerId) { advanceSeedIfNeeded() }

        // 页面级钩子（ITV：本页生成进行中时切到 PREVIEW 视图；TTV 无动作）
        onListeningStarted(generationViewModel)

        // Retry loading video if not loaded during initialize()
        // This handles the race condition where MediaStateHolder.loadFromDisk()
        // completes after initialize() but before startListening()
        if (_uiState.value.currentVideoUri == null) {
            loadLastGeneratedVideo()
        }

        generationViewModel.registerEventHandler(generationOwnerId) { event ->
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
                DebugLogger.i(logTag, "VideoGenerated: ${Obfuscator.promptId(event.promptId)}")
                fetchGeneratedVideo(event.promptId)
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                    ?: "Connection lost. Will check for completion when reconnected."
                showToastMessageEvent(message)
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                showToastMessageEvent(event.message)
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
                showToastEvent(event.messageResId)
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

        updateVideo { it.isFetching = true }

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = videoFilePrefix
        ) { uri ->
            updateVideo { it.isFetching = false }
            if (uri != null) {
                DebugLogger.i(logTag, "Video fetch successful")
                // Clear preview bitmap so video player takes display precedence
                updateVideo {
                    it.currentVideoUri = uri
                    it.previewBitmap = null
                }
                // 播放方登记（5.2）：新视频开始播放，旧的那份同时注销
                playingVideo.mark(videoMediaKey(promptId))
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            } else {
                DebugLogger.w(logTag, "Video fetch failed")
            }
            // If uri is null, don't complete generation - will retry on next return
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(previewMediaKey, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(previewMediaKey, applicationContext)
        if (bitmap != null) {
            updateVideo { it.previewBitmap = bitmap }
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, previewMediaKey)
        }
    }
}
