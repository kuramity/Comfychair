package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import org.json.JSONObject
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.nextSeed
import sh.hnet.comfychair.model.NUMBERED_SLOT_PLACEHOLDER_PREFIX
import sh.hnet.comfychair.model.NumberedSlotContainer
import sh.hnet.comfychair.model.MAX_TIMELINE_CLIPS
import sh.hnet.comfychair.model.TimelineClip
import sh.hnet.comfychair.model.TimelineClipDraft
import sh.hnet.comfychair.model.TimelineDocument
import sh.hnet.comfychair.model.TimelineReferenceMode
import sh.hnet.comfychair.model.UncoveredInterval
import sh.hnet.comfychair.model.VideoOptions
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.audioContainerOf
import sh.hnet.comfychair.model.coveredSeconds
import sh.hnet.comfychair.model.hasRoomForAudioSlot
import sh.hnet.comfychair.model.hasRoomForNumberedSlot
import sh.hnet.comfychair.model.nextAudioSlot
import sh.hnet.comfychair.model.nextClipStart
import sh.hnet.comfychair.model.nextNumberedSlot
import sh.hnet.comfychair.model.numberedContainerOf
import sh.hnet.comfychair.model.toDraft
import sh.hnet.comfychair.model.uncoveredIntervals
import sh.hnet.comfychair.model.withAudioSlotAdded
import sh.hnet.comfychair.model.withClipRemoved
import sh.hnet.comfychair.model.withClipUpdated
import sh.hnet.comfychair.model.withSlotAdded
import sh.hnet.comfychair.workflow.TimelineSlotDetector
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.PromptTargetFactory
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.util.BitmapDecodeUtils
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.UploadImageEncoder
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.AudioSlotDetector
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * Represents a workflow item with display name
 */
data class ItvWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name
    val type: WorkflowType      // ITV
) : WorkflowItemBase

/**
 * UI state for the Image-to-Video screen
 */
data class ImageToVideoUiState(
    // View state
    val viewMode: ImageToVideoViewMode = ImageToVideoViewMode.SOURCE,
    /**
     * 图位占位符 -> 当前选中的图片。按占位符为键独立存储，删除某个图位不会影响其他图位。
     *
     * 主图（`image_filename`）也在这里，键为 [ImageToVideoViewModel.PLACEHOLDER_MAIN_IMAGE]：
     * 它没有第二个来源，图位里没有它就是"主图缺失"。
     */
    val slotImages: Map<String, Bitmap> = emptyMap(),
    /** 当前工作流的图位定义（甲类 + 乙类） */
    val imageSlots: List<ImageSlot> = emptyList(),
    /**
     * 检测阶段识别出的乙类（编号）容器信息；工作流没有编号图位时为 null。
     *
     * 刻意与 [imageSlots] 分开保存：编号图位可以被用户一路删到 0 张（设计 §3），
     * 那时从列表里再也推不出容器信息，但「+ 添加」入口（[canAddNumberedSlot]）与
     * `addImageSlot` 都还要靠它工作。换工作流时随新工作流重算。
     */
    val numberedSlotContainer: NumberedSlotContainer? = null,

    /**
     * 音频槽定义（参考音频）。空列表 = 该工作流没接音频入口（16 个预置流里只有「多参视频」接了）。
     *
     * 与 [numberedSlotContainer] 同理，容器信息单独存：音频槽可以被用户删到一个不剩，
     * 那时列表为空但「+ 添加音频」入口还要能用。
     */
    val audioSlots: List<AudioSlot> = emptyList(),
    /** 音频槽占位符 -> 用户选中的本地文件（提交时才真正上传，与图片同一条路径） */
    val slotAudioFiles: Map<String, AudioFileChoice> = emptyMap(),
    /** 检测阶段识别出的音频容器信息；工作流没有音频槽时为 null */
    val numberedAudioContainer: NumberedSlotContainer? = null,

    /**
     * 时间线导演台的探测结果；非 null 表示当前工作流是导演台流。
     *
     * 与图位/音频槽一样是**检测阶段的持久事实**，不随用户增删片段改变 ——
     * 片段被删光时界面仍要显示编辑区（否则再也加不回来）。
     */
    val timelineSlot: TimelineSlotDetector.TimelineSlot? = null,
    /** 用户正在编辑的片段列表（含还没上传的本地文件） */
    val timelineClips: List<TimelineClipDraft> = emptyList(),
    /** 生成选区起点（秒） */
    val timelineSelectionStart: Double = 0.0,
    /** 生成选区长度（秒）—— 与节点的 `generation_seconds` 同源 */
    val timelineSelectionDuration: Double = 5.0,
    /** 电脑端已有时间线带过来的片段（只在加载的那一次为 true，用来给一句提示） */
    val timelineLoadedFromWorkflow: Boolean = false,

    val previewBitmap: Bitmap? = null,
    val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<ItvWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    val workflowPlaceholders: Set<String> = emptySet(),

    // 动态节点参数（通用节点参数引擎）：工作流里「未识别」的 {{占位符}}。
    // dynamicParams 是控件清单（UI 依 control 渲染），dynamicParamValues 是用户当前值（按占位符名索引）。
    val dynamicParams: List<DynamicParam> = emptyList(),
    val dynamicParamValues: Map<String, String> = emptyMap(),
    // 推导 dynamicParams 时 /object_info 注册表是否已就绪（与 dynamicParams 同一次快照，UI 提示条用）
    val dynamicParamsRegistryReady: Boolean = false,

    // Model selections - single-model patterns (e.g., LTX 2.0)
    override val selectedCheckpoint: String = "",
    override val selectedUnet: String = "",
    override val selectedLoraName: String = "",  // Mandatory LoRA dropdown
    /** 主 LoRA 权重（{{lora_strength}}）：只有工作流带该占位符时配置面板才显示滑块 */
    val loraStrength: Float = 1.0f,
    // Model selections - dual-model patterns (e.g., Wan 2.2)
    val selectedHighnoiseUnet: String = "",
    val selectedLownoiseUnet: String = "",
    val selectedHighnoiseLora: String = "",
    val selectedLownoiseLora: String = "",
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
    val width: String = "848",
    val height: String = "480",
    override val megapixels: String = "1.0",
    val length: String = "33",
    val fps: String = "16",
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
    val negativePrompt: String = "",

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
    val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Primary LoRA chain (for CommonGenerationState interface - video screens use dual chains)
    override val loraChain: List<LoraSelection> = emptyList(),

    // Workflow capabilities (unified flags derived from placeholders)
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Upload/fetch state
    val isUploading: Boolean = false,
    /** 上传进度 0–100（-1 = 当前没有上传）；v4.6.1 模块 D */
    val uploadProgress: Int = -1,
    val isFetching: Boolean = false
) : CommonGenerationState {

    /**
     * 界面是否显示「+ 添加」。判据只看"工作流有没有乙类容器"这个持久事实，
     * 不看当前列表里还剩几个编号图位——删光后必须仍为 true，否则入口消失、图位加不回来。
     */
    val canAddNumberedSlot: Boolean
        get() = hasRoomForNumberedSlot(imageSlots, numberedSlotContainer)

    /**
     * 该工作流是否走图位机制——判据是**检测阶段留下的事实**，不随用户增删图位改变。
     *
     * 与 [imageSlots] 的瞬时状态必须分开：乙类图位可以被用户一路删到 0 张（设计 §3），
     * 那时 [imageSlots] 一个也不剩，但"这个工作流用图位"这件事没变——
     *
     * - 界面：主区域仍要显示图位区，否则「+ 添加」入口跟着消失，图位再也加不回来
     *   （等价于 `imageSlots.isNotEmpty() || canAddNumberedSlot`，外加"具名图位也被删光"的情形）
     * - 提交：仍要走图位注入，否则工作流里剩下的 `{{reference_image_N}}` 占位符
     *   既不被替换也不被剪掉，会被当字面量文件名发给 ComfyUI
     *
     * 两个反例（判据退回 [imageSlots] 的瞬时状态）见 Task 8 审查发现 1 与发现 2：
     * 同一处错误模式引出了"入口消失"与"残留占位符字面量"两个用户可见缺陷。
     */
    val usesImageSlots: Boolean
        get() = capabilities.imageSlots.isNotEmpty() || numberedSlotContainer != null

    /**
     * 界面是否显示「+ 添加音频」。判据与图位侧同源：只看"工作流有没有音频容器"这个持久事实，
     * 删光后仍要为 true，否则入口消失、音频再也加不回来。
     */
    val canAddAudioSlot: Boolean
        get() = hasRoomForAudioSlot(audioSlots, numberedAudioContainer)

    /**
     * 该工作流是否走音频槽机制——判据与图位侧同源：看检测阶段留下的事实，不随用户增删改变。
     * 音频槽也能被删到一个不剩，那时 [audioSlots] 为空但界面仍要显示音频区（否则加不回来）。
     */
    val usesAudioSlots: Boolean
        get() = audioSlots.isNotEmpty() || numberedAudioContainer != null

    /** 当前工作流是不是时间线导演台流（决定界面显示片段编辑器、以及放开「必须有一张图」） */
    val isTimelineWorkflow: Boolean
        get() = timelineSlot != null

    /** 还能不能再加一个片段 */
    val canAddTimelineClip: Boolean
        get() = timelineClips.size < MAX_TIMELINE_CLIPS

    /**
     * 选区的结束时刻（秒）。片段默认落点与空隙提示都以它为参照。
     */
    val timelineSelectionEnd: Double
        get() = timelineSelectionStart + timelineSelectionDuration

    /**
     * 片段列表的「要发给插件的形态」。
     *
     * 间隙读数与提交都走这一个入口（文件名此时还没上传，用空串占位——时长计算不依赖它），
     * 避免"界面按一种口径算、提交按另一种口径算"这种对不上账的缺陷。
     */
    val timelineClipsAsTimeline: List<TimelineClip>
        get() = timelineClips.map { it.toClip(it.uploadedFile.orEmpty()) }

    /**
     * 选区内**被原画面固定**的秒数。
     *
     * 这是导演台最该让用户看见的读数：只有没被固定的那部分才是模型真正要"想"出来的内容，
     * 自由生成长度太短意味着模型几乎没有发挥空间（画面会贴着原片段走）。
     */
    val timelineCoveredSeconds: Double
        get() = coveredSeconds(
            selectionStart = timelineSelectionStart,
            selectionDuration = timelineSelectionDuration,
            clips = timelineClipsAsTimeline
        )

    /**
     * 选区内没有被任何片段覆盖的空隙，升序（界面据此列出"可生成的空隙"）。
     *
     * 比一帧还窄的空隙被滤掉：插件按 1/24 秒的容差判定"是不是空隙"，而生成时长的下界是
     * 5 帧 —— 列出来也点不出可用结果（点了会被夹到 5 帧，反而伸进两边的片段里）。
     */
    val timelineGaps: List<UncoveredInterval>
        get() = uncoveredIntervals(
            selectionStart = timelineSelectionStart,
            selectionDuration = timelineSelectionDuration,
            clips = timelineClipsAsTimeline
        ).filter { it.duration >= TimelineDocument.MIN_SELECTION_SECONDS }
}

/**
 * 用户为一个音频槽选中的本地文件。
 *
 * 音频不像图片那样能显示缩略图，所以这里同时记住 uri（提交时读字节上传）与显示名（界面上给人看）。
 */
data class AudioFileChoice(
    val uri: android.net.Uri,
    val displayName: String
)

/**
 * 提交时交给 `WorkflowManager.prepareImageToVideoWorkflowById` 的图位表。
 *
 * 抽成 internal 纯函数是为了能在 JVM 单测里钉住这个判据（先例见 `WorkflowManager.resolveImageSlotFiles`）：
 * 两个分支的用户可见差别很大——走图位注入时工作流里 `{{reference_image_N}}` 会被替换、留空的会被剪掉；
 * 传空表则退回字符串替换分支，这些占位符会原样留下、被当字面量文件名发给 ComfyUI。
 * 判据取"工作流是不是图位型"这个**持久事实**，不能用当前图位列表是否为空（Task 8 审查发现 2）。
 */
/**
 * 合法 MIME 形状：`type/subtype`，只含 RFC 允许的 token 字符。
 *
 * 用来过滤内容提供者返回的 MIME —— 那是外部输入，畸形值会让 OkHttp 的解析抛异常。
 */
internal val MIME_TYPE_PATTERN = Regex("""^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$""")

internal fun imageSlotFilesForInjection(
    state: ImageToVideoUiState,
    uploadedByPlaceholder: Map<String, String>
): Map<String, String> = if (state.usesImageSlots) uploadedByPlaceholder else emptyMap()

/**
 * 顶部上传入口该写进哪个图位：**第一个空图位** → 否则第一个图位 → 都没有时新建一个（乙类最小编号）。
 *
 * 抽成 internal 纯函数以便 JVM 单测（先例见 `WorkflowManager.resolveImageSlotFiles`）。
 * 参数只取需要的三样（不传整个 UiState），单测就不用去造 `Bitmap`。
 *
 * 为什么不能像单图时代那样直接写主图键：图位是图片的唯一来源，图位被删光后主图键在界面上
 * **没有任何格子承载**，用户会"传了图却看不见"，而生成又把这张看不见的图算进有效配置
 * （Task 8 复审新问题 2）。所以图位型工作流一律选一个看得见的图位；乙类删光时新建一个，
 * 走的是与「+ 添加」同一条编号分配路径。
 *
 * @param filledPlaceholders 已填图的图位占位符名（即 `slotImages` 的键）
 * @return 目标图位；识别不出图位的历史工作流（也建不出新图位）返回 null，调用方回退到主图键
 */
internal fun uploadTargetSlot(
    slots: List<ImageSlot>,
    filledPlaceholders: Set<String>,
    container: NumberedSlotContainer?
): ImageSlot? = slots.firstOrNull { it.placeholder !in filledPlaceholders }
    ?: slots.firstOrNull()
    ?: container?.let { nextNumberedSlot(slots, it) }

/**
 * Events emitted by the image-to-video screen
 */
sealed class ImageToVideoEvent {
    data class ShowToast(val messageResId: Int) : ImageToVideoEvent()
    data class ShowToastMessage(val message: String) : ImageToVideoEvent()
}

/**
 * ViewModel for the Image-to-Video screen
 */
class ImageToVideoViewModel : BaseGenerationViewModel<ImageToVideoUiState, ImageToVideoEvent>() {

    override val initialState = ImageToVideoUiState()

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
        private const val TAG = "ImageToVideo"
        const val OWNER_ID = "IMAGE_TO_VIDEO"
        private const val PREFS_NAME = "ImageToVideoFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val KEY_POSITIVE_PROMPT = "positivePrompt"

        /** 主图占位符：沿用 ItvSource 缓存档（与单图时代的主图缓存文件是同一个） */
        const val PLACEHOLDER_MAIN_IMAGE = "image_filename"

        /** 编号图位占位符前缀，编号紧跟其后（如 reference_image_1） */
        private const val PLACEHOLDER_REFERENCE_PREFIX = NUMBERED_SLOT_PLACEHOLDER_PREFIX

        /**
         * 时间线导演台的默认生成长度（秒）。
         *
         * 取 5 与桌面插件的默认一致（`emptyState()` 里的 `duration: 5`）：手机上不动这一项，
         * 生成出来的时长就与在电脑上打开这条流时一样。
         */
        const val DEFAULT_TIMELINE_SELECTION_SECONDS = 5.0

        /** 片段 id：只要求本机内唯一，插件不解释它的内容 */
        fun newTimelineClipId(): String =
            "clip_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
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
        val promptId = MediaStateHolder.getCurrentItvPromptId() ?: return

        val key = MediaStateHolder.MediaKey.ItvVideo(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            val uri = runBlocking {
                MediaStateHolder.getVideoUri(context, key)
            }
            if (uri != null) {
                _uiState.value = _uiState.value.copy(currentVideoUri = uri)
            }
        }
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        val workflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV)
            .filter { showBuiltIn || !it.isBuiltIn }

        val unifiedWorkflows = workflows.map { workflow ->
            ItvWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.ITV
            )
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val previousWorkflowId = _uiState.value.selectedWorkflowId
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
            // 还是同一条工作流时保留时间线片段：workflowsVersion 会因为"去工作流管理里改个
            // 名字/导入导出"而触发一次这里，那种情况下用户摆好的时间线不该被重置。
            // 真换了工作流则不保留 —— 片段属于那条流的 timeline_data。
            loadWorkflowValues(
                selectedWorkflowItem,
                preserveTimelineEdits = selectedWorkflowItem.id == previousWorkflowId
            )
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization and workflow changes)
     *
     * @param preserveTimelineEdits 还是同一条工作流（列表刷新、元数据编辑触发的重载）时置 true：
     *   保留用户已经摆好的时间线片段与生成范围。false 表示真的换了工作流，时间线按新工作流重算。
     */
    private fun loadWorkflowValues(
        workflowItem: ItvWorkflowItem,
        preserveTimelineEdits: Boolean = false
    ) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values and defaults by workflow ID (not name, to avoid duplicate name issues)
        val savedValues = storage.loadValues(serverId, workflowItem.id)
        val defaults = WorkflowManager.getWorkflowDefaultsById(workflowItem.id)
        val cache = ConnectionManager.modelCache.value

        // Get placeholders from workflow JSON to determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflowItem.id)

        // 图位定义：只识别一次，能力标志与 UiState 共用同一份结果
        val workflowJsonForSlots = WorkflowManager.getWorkflowById(workflowItem.id)?.jsonContent.orEmpty()
        // 三个探测器共用同一份已解析的工作流（原先各自 JSONObject(...) 把同一份 JSON 解了 3 遍）
        val slotsRoot = runCatching { JSONObject(workflowJsonForSlots) }.getOrNull()

        val imageSlots = slotsRoot?.let {
            ImageSlotDetector.detect(
                root = it,
                // 容器上限暂用回退值 9：/object_info 读取为后续项（唯一含自动增长容器的工作流上限恰为 9，回退值正确）
                objectInfo = null
            )
        }.orEmpty()

        // 参考音频位：只有接了 ref_audios 的工作流（当前是「多参视频」）才识别得出，
        // 其余工作流拿到的永远是空列表，界面上也就不会出现音频区
        val audioSlots = slotsRoot?.let {
            AudioSlotDetector.detect(root = it, objectInfo = null)
        }.orEmpty()

        // 时间线导演台：识别到就说明这是导演台流，界面换成片段编辑器。
        // 节点里已有的 timeline_data 一并读出来当初值 —— 用户在电脑上摆好的时间线
        // 在手机上打开就能看见，不用重摆一遍
        val timelineSlot = slotsRoot?.let { TimelineSlotDetector.detect(it) }
        val keepTimeline = preserveTimelineEdits && timelineSlot != null

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
            // 图位定义与已填的图位图：换工作流时一起换成新工作流的图位，不留上一个工作流的残留键
            imageSlots = imageSlots,
            slotImages = restoreSlotImages(imageSlots),
            // 容器信息同样随新工作流重算：新工作流没有编号图位时显式为 null，不沿用上一个工作流的容器
            numberedSlotContainer = numberedContainerOf(imageSlots),
            // 音频槽同样随新工作流重算：不沿用上一个工作流的音频（换流后残留会导致提交注入到错误的容器）
            audioSlots = audioSlots,
            // 音频不落盘恢复：它动辄几十 MB，为"切页回来还在"而复制一份不值当
            // （图片那套落盘恢复同时承担着预览缩略图的职责）。切到别的页再回来不会丢——
            // ViewModel 还活着；只有进程结束才需要重新选一次。
            slotAudioFiles = emptyMap(),
            numberedAudioContainer = audioContainerOf(audioSlots),
            // 时间线导演台：同样随新工作流重算，不沿用上一个工作流的片段
            // （沿用会让新工作流的 timeline_data 里塞进另一条流的视频）。
            // 例外是"同一条工作流被重新加载"（preserveTimelineEdits），那时保留用户已摆好的内容。
            // 必须同时确认新工作流也是导演台流，否则会留下一个永远显示不出来的片段列表
            timelineSlot = timelineSlot,
            timelineClips = if (keepTimeline) state.timelineClips
                else timelineSlot?.document?.clips?.map { it.toDraft() }.orEmpty(),
            timelineSelectionStart = if (keepTimeline) state.timelineSelectionStart
                else timelineSlot?.document?.selectionStart ?: 0.0,
            timelineSelectionDuration = if (keepTimeline) state.timelineSelectionDuration
                else timelineSlot?.document?.selectionDuration
                    ?: DEFAULT_TIMELINE_SELECTION_SECONDS,
            timelineLoadedFromWorkflow = if (keepTimeline) state.timelineLoadedFromWorkflow
                else timelineSlot?.hasInitialClips == true,
            // Workflow capabilities from placeholders
            capabilities = WorkflowCapabilities.fromPlaceholders(
                placeholders = placeholders,
                imageSlots = imageSlots,
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
        val defaultPositivePrompt = SeasonalPrompts.getImageToVideoPrompt()
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
        val values = WorkflowValues(
            width = state.width.toIntOrNull(),
            height = state.height.toIntOrNull(),
            length = state.length.toIntOrNull(),
            frameRate = state.fps.toIntOrNull(),
            // 步数 / 主 LoRA 权重按工作流持久化（否则切工作流就丢回缺省）
            steps = state.steps.toIntOrNull(),
            loraStrength = state.loraStrength,
            // 百万像素 / 宽高比 / 加速模式按工作流持久化
            megapixels = state.megapixels.toFloatOrNull(),
            aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
            speedMode = state.speedMode.takeIf { it.isNotEmpty() },
            // 种子手动控制：与文生图一样按工作流持久化
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 新键（四态取值方式）；老版本读上面那个布尔
            seedMode = state.seedMode.name,
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Single-model patterns
            model = state.selectedCheckpoint.takeIf { it.isNotEmpty() },
            unetModel = state.selectedUnet.takeIf { it.isNotEmpty() },
            loraModel = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Dual-model patterns
            highnoiseUnetModel = state.selectedHighnoiseUnet.takeIf { it.isNotEmpty() },
            lownoiseUnetModel = state.selectedLownoiseUnet.takeIf { it.isNotEmpty() },
            highnoiseLoraModel = state.selectedHighnoiseLora.takeIf { it.isNotEmpty() },
            lownoiseLoraModel = state.selectedLownoiseLora.takeIf { it.isNotEmpty() },
            // Common models
            vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoderModel = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            highnoiseLoraChain = LoraSelection.toJsonString(state.highnoiseLoraChain).takeIf { state.highnoiseLoraChain.isNotEmpty() },
            lownoiseLoraChain = LoraSelection.toJsonString(state.lownoiseLoraChain).takeIf { state.lownoiseLoraChain.isNotEmpty() },
            nodeAttributeEdits = existingValues?.nodeAttributeEdits,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        )

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

    /**
     * 按图位定义从缓存恢复图位图（内存优先模式读内存，磁盘优先模式读盘）。
     *
     * 键一律取当前图位的占位符，所以换工作流后 map 里不会留下上一个工作流的孤儿键。
     * 缓存里没有的图位（用户还没选图）不出现在 map 里。主图也走这里（ItvSource 档）。
     */
    private fun restoreSlotImages(imageSlots: List<ImageSlot>): Map<String, Bitmap> =
        imageSlots.mapNotNull { slot ->
            val key = mediaKeyForSlot(slot.placeholder) ?: return@mapNotNull null
            MediaStateHolder.getBitmap(key, applicationContext)?.let { slot.placeholder to it }
        }.toMap()

    /**
     * 图位占位符 -> 落盘档位。
     *
     * - 主图（`image_filename`）沿用现有的 ItvSource 档，与单图时代的缓存文件是同一个
     * - 编号图位（`reference_image_N`）按占位符里的编号各自开档。用占位符里的编号而不是
     *   `ImageSlot.Numbered.index`，是为了和注入器接线时用的编号（`NUMBERED_SLOT_PLACEHOLDER`）
     *   保持同一个口径——两者在异常工作流里可能不一致，那时以注入器的为准更不易串档
     * - 认不出编号的具名占位符不落盘（当前工作流的图片占位符只有上面两种）
     */
    private fun mediaKeyForSlot(placeholder: String): MediaStateHolder.MediaKey? = when {
        placeholder == PLACEHOLDER_MAIN_IMAGE -> MediaStateHolder.MediaKey.ItvSource
        placeholder.startsWith(PLACEHOLDER_REFERENCE_PREFIX) ->
            placeholder.removePrefix(PLACEHOLDER_REFERENCE_PREFIX).toIntOrNull()
                ?.let { MediaStateHolder.MediaKey.ItvReference(it) }
        else -> null
    }

    /**
     * 乙类图位（编号参考图）：占用编号里最小的空位；已到上限则什么都不做。
     *
     * 甲类图位（首帧/尾帧/主图）不在增加范围内——它们由工作流固定，请求增加时直接返回。
     */
    fun addImageSlot() {
        val state = _uiState.value
        // 容器信息取检测阶段留下的那份，不能从当前列表推：编号图位被删光后列表里一个都不剩，
        // 但用户仍要能把图位加回来（判据同 UiState.canAddNumberedSlot）
        val container = state.numberedSlotContainer ?: return
        val newSlot = nextNumberedSlot(state.imageSlots, container) ?: return
        // 新图位按 order 入列，而不是简单追加：它占的是最小空编号，位置可能本就在已有图位之前
        // （删掉编号 0 后新加的图位要回到第一格），否则界面标签序号会与编号序错位
        _uiState.value = state.copy(imageSlots = withSlotAdded(state.imageSlots, newSlot))
    }

    /**
     * 按占位符批量删除图位。
     * 只从映射里摘掉传入的那些，其余图位原地不动——甲类图位因此物理上不可能补位。
     */
    fun removeImageSlots(placeholders: Set<String>) {
        val state = _uiState.value
        _uiState.value = state.copy(
            imageSlots = state.imageSlots.filterNot { it.placeholder in placeholders },
            slotImages = state.slotImages.filterKeys { it !in placeholders }
        )

        // 落盘的那几份也要删掉：留在盘上的话，重启后会被当成"没删干净"的图位恢复回来
        val context = applicationContext ?: return
        val keys = placeholders.mapNotNull { mediaKeyForSlot(it) }
        if (keys.isEmpty()) return
        viewModelScope.launch {
            keys.forEach { MediaStateHolder.evictAndDeleteFromDisk(context, it) }
        }
    }

    /**
     * 选择模式的「删除所选」，按图位类型分开处理：
     *
     * - **乙类（编号）图位**：摘掉图位定义——数量本来就由用户决定，少了可以用「+ 添加」要回来
     * - **甲类（具名）图位**：只清空图片，图位定义、标签、占位符映射原地保留。甲类数量由工作流固定、
     *   不允许增减（设计 §3），摘掉定义会让格子消失且加不回来；清空才是设计 §4.2 的"删除某个图位只清空该图位"，
     *   提交时该链路由注入器按"留空"整条剪掉
     */
    fun deleteSlots(placeholders: Set<String>) {
        if (placeholders.isEmpty()) return
        val numbered = _uiState.value.imageSlots
            .filter { it is ImageSlot.Numbered && it.placeholder in placeholders }
            .map { it.placeholder }
            .toSet()
        val named = placeholders - numbered

        if (numbered.isNotEmpty()) removeImageSlots(numbered)
        if (named.isNotEmpty()) clearSlotImages(named)
    }

    /** 只清空这几个图位的图片（内存 + 磁盘），图位定义原样保留。 */
    private fun clearSlotImages(placeholders: Set<String>) {
        val state = _uiState.value
        _uiState.value = state.copy(slotImages = state.slotImages.filterKeys { it !in placeholders })

        // 落盘的那几份也要删掉：留在盘上的话，重启后会被当成"没删干净"的图位恢复回来
        val context = applicationContext ?: return
        val keys = placeholders.mapNotNull { mediaKeyForSlot(it) }
        if (keys.isEmpty()) return
        viewModelScope.launch {
            keys.forEach { MediaStateHolder.evictAndDeleteFromDisk(context, it) }
        }
    }

    /** 替换单个图位的图片（解码 → 落盘 → 更新状态）。 */
    fun replaceSlotImage(placeholder: String, uri: Uri) {
        val context = applicationContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按最长边上限采样解码（相册原图可能远大于需要的尺寸）
                val bitmap = BitmapDecodeUtils.decodeSampledFromUri(context, uri) ?: return@launch

                // 落盘一份，重启后由 restoreSlotImages 按图位恢复
                mediaKeyForSlot(placeholder)?.let { MediaStateHolder.putBitmap(it, bitmap, context) }

                _uiState.value = _uiState.value.copy(
                    slotImages = _uiState.value.slotImages + (placeholder to bitmap)
                )
            } catch (e: Exception) {
                _events.emit(ImageToVideoEvent.ShowToast(R.string.error_save_image))
            }
        }
    }


    // ---------- 参考音频槽 ----------

    /**
     * 为一个音频槽选中本地文件。
     *
     * 音频不做落盘、也不预解码：界面上只需要一个文件名，真正读字节上传发生在提交时
     * （与图片同一条路径）。这样选一个几十 MB 的音频不会占内存，也不会白等一次上传。
     */
    fun replaceSlotAudio(placeholder: String, uri: Uri) {
        val context = applicationContext ?: return
        _uiState.value = _uiState.value.copy(
            slotAudioFiles = _uiState.value.slotAudioFiles +
                (placeholder to AudioFileChoice(uri, queryAudioDisplayName(context, uri)))
        )
    }

    /** 新增一个音频槽（占最小空编号，与图位同一套编号规则） */
    fun addAudioSlot() {
        val state = _uiState.value
        // 容器取检测阶段留下的那份：音频槽被删光后列表里一个都不剩，但入口还要能用
        val container = state.numberedAudioContainer ?: return
        val newSlot = nextAudioSlot(state.audioSlots, container) ?: return
        _uiState.value = state.copy(audioSlots = withAudioSlotAdded(state.audioSlots, newSlot))
    }

    /**
     * 删除音频槽：音频槽的数量本来就由用户决定（与乙类图位同规矩），摘掉定义即可。
     * 同时清掉已选文件，否则删了槽再加回来会带着上一份音频。
     */
    fun removeAudioSlots(placeholders: Set<String>) {
        val state = _uiState.value
        _uiState.value = state.copy(
            audioSlots = state.audioSlots.filterNot { it.placeholder in placeholders },
            slotAudioFiles = state.slotAudioFiles - placeholders
        )
    }

    /** 取用户看得懂的文件名（内容提供者给的显示名，拿不到时退回路径末段） */
    private fun queryAudioDisplayName(context: android.content.Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "audio"
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    // ---------- 时间线导演台 ----------

    /**
     * 给时间线添一段视频（手机本地文件）。
     *
     * 选完文件**立刻**读时长与音轨：这两个值决定片段能摆多长、会不会带原声参考，
     * 界面上的三个数字框都依赖它们。读不出来就直接拒绝 —— 一个时长未知的片段
     * 摆到时间线上会让选区与空隙的读数全是错的，那比"加不进去"更糟。
     *
     * 文件本身不读字节（几十 MB 的视频读进来只会占内存），真正上传发生在提交时，
     * 与图片/音频同一条路径。
     */
    fun addTimelineClip(uri: Uri) {
        val context = applicationContext ?: return
        val state = _uiState.value
        if (!state.canAddTimelineClip) return

        val meta = probeVideoMeta(context, uri)
        if (meta == null) {
            viewModelScope.launch { _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_timeline_video_unreadable)) }
            return
        }

        val draft = TimelineClipDraft(
            id = newTimelineClipId(),
            uri = uri,
            displayName = queryAudioDisplayName(context, uri),
            // 默认接在已有片段之后（见 nextClipStart 的说明）
            start = nextClipStart(state.timelineClips),
            duration = meta.durationSeconds.coerceAtLeast(TimelineDocument.MIN_CLIP_SECONDS),
            trimStart = 0.0,
            sourceDuration = meta.durationSeconds,
            hasAudio = meta.hasAudio,
            // 默认 guide：与桌面插件的默认一致，手机上不动这一项就等于桌面上的行为
            referenceMode = TimelineReferenceMode.GUIDE,
            uploadedFile = null
        )
        _uiState.value = state.copy(timelineClips = state.timelineClips + draft)
    }

    /** 改片段在时间轴上的落点 */
    fun setTimelineClipStart(id: String, seconds: Double) {
        _uiState.value = _uiState.value.copy(
            timelineClips = withClipUpdated(_uiState.value.timelineClips, id) {
                it.copy(start = TimelineDocument.clampStart(seconds))
            }
        )
    }

    /**
     * 改片段在时间轴上占的长度。
     *
     * 上界取"源里还剩多少"而不是一个固定值：超出源长度的部分插件根本取不到画面，
     * 界面上却会显示那么长，用户会以为生成区里有原画面在托底。
     */
    fun setTimelineClipDuration(id: String, seconds: Double) {
        _uiState.value = _uiState.value.copy(
            timelineClips = withClipUpdated(_uiState.value.timelineClips, id) { draft ->
                val maxSeconds = draft.availableSourceSeconds
                    .coerceAtLeast(TimelineDocument.MIN_CLIP_SECONDS)
                draft.copy(duration = seconds.coerceIn(TimelineDocument.MIN_CLIP_SECONDS, maxSeconds))
            }
        )
    }

    /**
     * 改片段从源视频的第几秒开始取。
     *
     * 与桌面插件同一套联动：入点往后推之后，长度要跟着缩到源的剩余长度以内，
     * 否则"入点 8 秒 + 长度 5 秒"会去读一个只有 10 秒的源的第 8→13 秒。
     *
     * 源长度一律走 [TimelineClipDraft.sourceLengthSeconds]（带缺失兜底），不读裸
     * `sourceDuration`：那份字段在电脑端旧数据里可能是 0，会让"点一下入点框"
     * 就把片段压到 5 帧。
     */
    fun setTimelineClipTrimStart(id: String, seconds: Double) {
        _uiState.value = _uiState.value.copy(
            timelineClips = withClipUpdated(_uiState.value.timelineClips, id) { draft ->
                val sourceLength = draft.sourceLengthSeconds
                val maxTrim = (sourceLength - TimelineDocument.MIN_CLIP_SECONDS).coerceAtLeast(0.0)
                val trim = seconds.coerceIn(0.0, maxTrim)
                val maxDuration = (sourceLength - trim)
                    .coerceAtLeast(TimelineDocument.MIN_CLIP_SECONDS)
                draft.copy(trimStart = trim, duration = draft.duration.coerceAtMost(maxDuration))
            }
        )
    }

    /** 改片段与生成选区的关系（固定得多死，见 [TimelineReferenceMode]） */
    fun setTimelineClipReferenceMode(id: String, mode: TimelineReferenceMode) {
        _uiState.value = _uiState.value.copy(
            timelineClips = withClipUpdated(_uiState.value.timelineClips, id) {
                it.copy(referenceMode = mode)
            }
        )
    }

    /** 从时间线上移走一段 */
    fun removeTimelineClip(id: String) {
        val remaining = withClipRemoved(_uiState.value.timelineClips, id)
        _uiState.value = _uiState.value.copy(
            timelineClips = remaining,
            // 片段被删光后"已载入工作流里摆好的时间线"这句提示就不再成立了：
            // 它说的是节点里带过来的内容，而用户已经把它们全删了
            timelineLoadedFromWorkflow = _uiState.value.timelineLoadedFromWorkflow && remaining.isNotEmpty()
        )
    }

    /** 改生成选区的起点与长度（长度会夹到节点承认的 0.21–150 秒） */
    fun setTimelineSelection(start: Double, duration: Double) {
        _uiState.value = _uiState.value.copy(
            timelineSelectionStart = TimelineDocument.clampStart(start),
            timelineSelectionDuration = TimelineDocument.clampSelectionDuration(duration)
        )
    }

    /**
     * 把生成选区对准一段空隙（界面上的「生成本段」按钮）。
     *
     * 长度超过上限时从空隙起点截取前 150 秒，而不是把整段空隙缩到 150 秒 ——
     * 截取得到的选区一定是空隙的**子区间**，不会跑到片段上去；
     * 缩放则会让选区两端伸进两边的片段里，变成"固定原画面 + 生成"的另一种结果。
     */
    fun selectTimelineGap(gap: UncoveredInterval) {
        setTimelineSelection(
            start = gap.start,
            duration = gap.duration.coerceAtMost(TimelineDocument.MAX_DURATION_SECONDS)
        )
    }

    /** 视频时长与有没有音轨 */
    private data class VideoMeta(val durationSeconds: Double, val hasAudio: Boolean)

    /**
     * 读视频的时长与音轨信息。
     *
     * 只读元数据不解码画面：`METADATA_KEY_DURATION` 对绝大多数容器都是头部信息，
     * 读一个几百 MB 的文件也是毫秒级。读不出来（文件损坏、容器不支持）返回 null，
     * 由调用方拒绝这次选择。
     */
    private fun probeVideoMeta(context: android.content.Context, uri: Uri): VideoMeta? =
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: return null
                if (durationMs <= 0L) return null
                VideoMeta(
                    durationSeconds = durationMs / 1000.0,
                    // 这个键"存在"就表示有音轨（没有音轨时 extractMetadata 返回 null）
                    hasAudio = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                    ) != null
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()

    // View mode
    fun onViewModeChange(mode: ImageToVideoViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }
    /**
     * 主图换图入口（顶部上传按钮）：写进"第一个可填的图位"，见 [uploadTargetSlot]。
     *
     * 只有识别不出图位的历史工作流才回退到主图键（那时也没有格子可显示，与改动前的单图页一致）。
     * 注意 [uploadTargetSlot] 返回的新建图位要先落进状态，后续的 [replaceSlotImage] 才找得到这个占位符。
     */
    fun onSourceImageChange(context: Context, uri: Uri) {
        val state = _uiState.value
        val target = uploadTargetSlot(state.imageSlots, state.slotImages.keys, state.numberedSlotContainer)
        if (target == null) {
            replaceSlotImage(PLACEHOLDER_MAIN_IMAGE, uri)
            return
        }
        if (state.imageSlots.none { it.placeholder == target.placeholder }) {
            // 同上：新建的图位可能属于最前面的空编号，要按 order 插到正确位置
            _uiState.value = state.copy(imageSlots = withSlotAdded(state.imageSlots, target))
        }
        replaceSlotImage(target.placeholder, uri)
    }

    fun onWorkflowChange(workflow: String) {
        val state = _uiState.value

        // Find workflow item to get its ID
        val workflowItem = state.availableWorkflows.find { it.name == workflow } ?: return

        // 重选当前这条流：直接返回，不能往下走。下拉里当前项是可以点的，而
        // loadWorkflowValues 会把界面上调过的值全部重置回工作流默认（时间线片段、
        // 生成范围也一样）——"手滑点一下自己"会让刚摆好的时间线无声消失。
        // 工作流管理里改元数据也会触发一次重载，这里一并挡掉。
        if (workflowItem.id == state.selectedWorkflowId) return

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
        val error = validateVideoLength(length)
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
        if (state.selectedWorkflowId.isEmpty()) return
        val snapshot = WorkflowManager.loadDynamicParams(state.selectedWorkflowId, state.dynamicParamValues)
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
        if (!state.seedMode.advances) return
        val constraints = WorkflowConstraintsProvider.getConstraints("seed", state.selectedWorkflow)
        val next = nextSeed(
            current = state.seed.toLongOrNull() ?: 0L,
            step = state.seedMode.step,
            min = constraints.min.toLong(),
            max = constraints.max.toLong()
        )
        _uiState.value = state.copy(seed = next.toString(), seedError = null)
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
            val defaultPrompt = SeasonalPrompts.getImageToVideoPrompt()
            _uiState.update { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    // Primary LoRA chain operations (for single-model workflows like LTX 2.0)
    fun onAddLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.loraChain, state.availableLoras)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onRemoveLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.loraChain, index)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.loraChain, index, name)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.loraChain, index, strength)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

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

    /**
     * Video length validation with special requirement for "steps of 4" (1, 5, 9, 13...).
     * This is specific to video generation workflows.
     */
    private fun validateVideoLength(value: String): String? {
        val num = value.toIntOrNull()
        return when {
            value.isEmpty() -> applicationContext?.getString(R.string.error_required)
                ?: "Required"
            num == null -> applicationContext?.getString(R.string.error_invalid_number)
                ?: "Invalid number"
            num !in 1..129 -> applicationContext?.getString(R.string.error_length_range)
                ?: "Must be 1-129"
            (num - 1) % 4 != 0 -> applicationContext?.getString(R.string.error_length_step)
                ?: "Must be 1, 5, 9, 13... (steps of 4)"
            else -> null
        }
    }

    /**
     * 上传各图位图片并准备工作流。
     *
     * 图位是图片的唯一来源：用户删掉/留空某个图位，这里就不会给它落键，
     * 提交时注入器按"留空"把那条链路整条剪掉（设计 §4 的留空语义）。
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        // Check connection before uploading
        val connected = suspendCoroutine { cont ->
            ConnectionManager.ensureConnection(context) { success ->
                cont.resumeWith(Result.success(success))
            }
        }
        if (!connected) return null  // Dialog already shown by ensureConnection

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

        _uiState.update { it.copy(isUploading = true) }
        return try {
            // 逐张上传已填图位。一张都没填时直接放弃：注入器会把整条图片链路删光，
            // 出片必然失败，不如在这里给一句能读懂的提示。
            //
            // 导演台流整段跳过：那条流没有图片输入口，上传了也无处注入（白占流量与内存）。
            // 正常路径下它的图位表本来就是空的（切流时重置），这里是兜底。
            val uploadedByPlaceholder = mutableMapOf<String, String>()
            if (!state.isTimelineWorkflow) {
                for ((placeholder, bitmap) in state.slotImages) {
                    val filename = uploadBitmap(client, bitmap, "itv_$placeholder") ?: return null
                    uploadedByPlaceholder[placeholder] = filename
                }
            }
            // 素材要求两类流各一条：非导演台流要至少一张图（一张都没有时注入器会把整条图片
            // 链路删光，出片必然失败）；导演台流要至少一段视频（没有片段时节点会抛
            // "导演台没有可用参考"，那是个服务器侧异常，不如在这里给一句能读懂的提示）。
            if (state.isTimelineWorkflow) {
                if (state.timelineClips.isEmpty()) {
                    _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_timeline_needs_clip))
                    return null
                }
            } else if (uploadedByPlaceholder.isEmpty()) {
                _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_no_source_image))
                return null
            }

            // 参考音频是可选输入（用户不放就正常生成），但"选了却传失败"必须终止本次生成：
            // 把失败当成"这个槽没填"，注入器会把这个槽整条链剪掉，
            // 用户明明选了音频，拿到的却是一段没有参考音频的片子，且毫无提示。
            val uploadedAudio = mutableMapOf<String, String>()
            for ((placeholder, choice) in state.slotAudioFiles) {
                val filename = uploadAudio(client, choice) ?: return null
                uploadedAudio[placeholder] = filename
            }

            // 时间线导演台：逐段上传本地视频，再把整份时间线序列化成一个字符串。
            // 已经在服务器上的片段（从电脑端时间线读回来的）直接用原名，不重传 ——
            // 否则"电脑上摆好、手机上生成"会被迫把每个文件重传一遍。
            val timelineData: String
            val timelineSeconds: Double?
            if (state.isTimelineWorkflow) {
                val clips = mutableListOf<TimelineClip>()
                for (draft in state.timelineClips) {
                    val file = if (draft.needsUpload) {
                        uploadVideo(client, draft) ?: return null
                    } else {
                        val existing = draft.uploadedFile
                        if (existing.isNullOrBlank()) {
                            _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_timeline_video_unreadable))
                            return null
                        }
                        existing
                    }
                    clips += draft.toClip(file)
                }
                val document = TimelineDocument(
                    selectionStart = state.timelineSelectionStart,
                    selectionDuration = state.timelineSelectionDuration,
                    clips = clips,
                    // 素材箱与「视频原声」开关 App 不编辑，但必须原样带给节点：
                    // 用户在电脑上摆好的图片/音频参考、与提示词里写的 <Picture N>/<Audio N>
                    // 是一一对应的，丢掉它们会让提示词引用一个不存在的参考 ——
                    // 节点不报错，只是生成结果与用户在电脑上看到的安排不一致。
                    videoAudioEnabled = state.timelineSlot?.document?.videoAudioEnabled ?: true,
                    rawImages = state.timelineSlot?.document?.rawImages,
                    rawAudios = state.timelineSlot?.document?.rawAudios
                )
                timelineData = document.toJson()
                // 与 selection.duration 同源：节点用 generation_seconds 算帧数、
                // 用 selection 算原画面固定到第几帧，两者不一致会让 guide 落到错误的帧上
                timelineSeconds = document.selectionDuration
            } else {
                timelineData = ""
                timelineSeconds = null
            }

            val baseWorkflow = WorkflowManager.prepareImageToVideoWorkflowById(
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
                // 主图同样只认图位：图位里没有它（被删/被清空）就是一个空串，
                // 注入器按"留空"处理 —— 尾帧删了就是删了，不会再拿旧图兜底。
                // 绝不在这里回填别的图片来源（那正是"尾帧删了还在用旧图"的来源）。
                imageFilename = uploadedByPlaceholder[PLACEHOLDER_MAIN_IMAGE].orEmpty(),
                // 图位表：键为占位符名，值为已上传文件名；留空的图位不在表里，其节点会被注入器删掉。
                // 判据（"工作流是不是图位型"）见 imageSlotFilesForInjection：乙类被删光时列表为空，
                // 但工作流里仍留着 `{{reference_image_N}}` 节点，传空表就等于把它们当字面量文件名发出去。
                imageSlotFiles = imageSlotFilesForInjection(state, uploadedByPlaceholder),
                // 音频表无条件传：工作流没有音频槽时注入器是空操作；有槽而表为空时
                // 正好把那些 LoadAudio 节点删干净（否则占位符会被当字面量文件名提交）
                audioSlotFiles = uploadedAudio,
                // Generation parameters
                steps = state.steps.toIntOrNull(),
                cfg = state.cfg.toFloatOrNull(),
                samplerName = state.sampler,
                scheduler = state.scheduler,
                denoise = state.denoise.toFloatOrNull(),
                seed = state.seed.toLongOrNull(),
                randomSeed = state.randomSeed,
                // 视频分辨率选择器 / 加速模式
                megapixels = state.megapixels.toFloatOrNull(),
                aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
                speedMode = state.speedMode.takeIf { it.isNotEmpty() },
                // 主 LoRA 权重（工作流没有 {{lora_strength}} 时不会被替换）
                loraStrength = state.loraStrength,
                // 时间线导演台：整份时间线 + 与它同源的生成时长（非导演台流下注入器是空操作）
                timelineData = timelineData,
                generationSeconds = timelineSeconds,
                // 动态节点参数（通用节点参数引擎）
                dynamicParams = state.dynamicParamValues
            ) ?: return null

            // Inject LoRAs if configured
            var workflow = baseWorkflow

            // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
            if (state.loraChain.isNotEmpty()) {
                workflow = WorkflowManager.injectLoraChain(workflow, state.loraChain, sh.hnet.comfychair.WorkflowType.ITV)
            }

            // Dual-model LoRA chains for workflows like Wan 2.2
            if (state.highnoiseLoraChain.isNotEmpty()) {
                workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.highnoiseLoraChain, isHighNoise = true)
            }
            if (state.lownoiseLoraChain.isNotEmpty()) {
                workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.lownoiseLoraChain, isHighNoise = false)
            }
            workflow
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    /**
     * 把 Bitmap 编码成 PNG 后上传到 ComfyUI，成功时返回服务器上的文件名。
     *
     * 失败返回 null，并按原有主图路径的约定提示：连接停滞/鉴权失败弹连接对话框，其余弹 toast。
     * 调用方拿到 null 一律放弃本次生成——图位表少一张图会被注入器静默当成"该图位留空"，
     * 宁可不出片也不能悄悄丢掉用户选的图。
     */
    private suspend fun uploadBitmap(client: ComfyUIClient, bitmap: Bitmap, filenamePrefix: String): String? {
        // 统一切到 ARGB_8888，PNG 编码才正确
        val imageBytes = withContext(Dispatchers.IO) {
            val bitmapToUpload = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            // v4.6.1 模块 D：上传前转 WebP（保持原分辨率，剥离 PNG 元数据）
            val bytes = UploadImageEncoder.encode(bitmapToUpload, preserveAlpha = false)
            if (bitmapToUpload !== bitmap) {
                bitmapToUpload.recycle()
            }
            bytes
        }

        data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
        val uploadResult: UploadResult = withContext(Dispatchers.IO) {
            suspendCoroutine { continuation ->
                client.uploadImage(
                    imageBytes,
                    UuidUtils.generateUniqueUploadFilename(filenamePrefix, UploadImageEncoder.EXTENSION),
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

        if (uploadResult.filename.isNullOrBlank()) {
            // 空名只在服务端异常响应时才可能出现，但它和"失败"一样不能往下传：
            // 空串进图位表会被注入器当成"该图位留空"而删掉整条链路，等于静默丢图。
            // Check for stall or auth failure - show dialog instead of toast
            if (uploadResult.failureType == ConnectionFailure.STALLED ||
                uploadResult.failureType == ConnectionFailure.AUTHENTICATION) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, uploadResult.failureType)
                }
            } else {
                _events.emit(ImageToVideoEvent.ShowToast(R.string.error_save_image))
            }
            return null
        }
        return uploadResult.filename
    }

    /**
     * 读取用户选的音频并上传，成功时返回服务器上的文件名。
     *
     * 走的是与图片同一个上传端点：ComfyUI 的 `/upload/image` 不校验上传内容，只按文件名落盘到
     * input 目录，而 `LoadAudio` 节点读的正是那个目录里的文件。差别只有 MIME 类型与扩展名。
     */
    private suspend fun uploadAudio(client: ComfyUIClient, choice: AudioFileChoice): String? =
        uploadLocalFile(
            client = client,
            uri = choice.uri,
            displayName = choice.displayName,
            fallbackMimeType = "application/octet-stream",
            fallbackExtension = "mp3",
            namePrefix = "itv_audio",
            errorRes = R.string.error_save_audio
        )

    /**
     * 上传一段时间线视频，成功时返回服务器上的文件名。
     *
     * 与音频同一条路径（`/upload/image` 不校验内容，只按文件名落盘到 input 目录），
     * 差别只有 MIME 类型与扩展名。
     */
    private suspend fun uploadVideo(client: ComfyUIClient, draft: TimelineClipDraft): String? {
        val uri = draft.uri ?: return null
        return uploadLocalFile(
            client = client,
            uri = uri,
            displayName = draft.displayName,
            fallbackMimeType = "video/mp4",
            fallbackExtension = "mp4",
            namePrefix = "itv_clip",
            errorRes = R.string.error_upload_video
        )
    }

    /**
     * 读本地文件字节并上传到服务器的 input 目录（图片/音频/视频共用这一条路径）。
     *
     * @param fallbackMimeType 内容提供者没给出合法 MIME 时的兜底
     * @param fallbackExtension 文件名没有扩展名时的兜底
     */
    private suspend fun uploadLocalFile(
        client: ComfyUIClient,
        uri: Uri,
        displayName: String,
        fallbackMimeType: String,
        fallbackExtension: String,
        namePrefix: String,
        errorRes: Int
    ): String? {
        val context = applicationContext ?: return null
        val bytes = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null || bytes.isEmpty()) {
            _events.emit(ImageToVideoEvent.ShowToast(errorRes))
            return null
        }

        // 内容提供者给的 MIME 属于**外部输入**：畸形值（空串、"unknown"、带尾空格、
        // 逗号分隔的多个值）会让 OkHttp 的 toMediaType() 抛 IllegalArgumentException。
        // 而这条调用链没有 catch（prepareWorkflow 只有 try/finally、调用点是裸 launch），
        // 异常会直达主线程 → 闪退。所以既不空手信它，也不让畸形值往下走。
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { MIME_TYPE_PATTERN.matches(it) }
            ?: fallbackMimeType
        // 保留原扩展名：LoadAudio 是把后缀交给解码器决定怎么读的，
        // 统一改成 .bin 之类会让它认不出格式
        val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?: fallbackExtension
        val filename = UuidUtils.generateUniqueUploadFilename(namePrefix, extension)

        data class LocalUploadResult(val filename: String?, val failureType: ConnectionFailure)
        val result: LocalUploadResult = withContext(Dispatchers.IO) {
            suspendCoroutine { continuation ->
                client.uploadImage(bytes, filename, mimeType = mimeType) { success, name, _, failureType ->
                    continuation.resumeWith(Result.success(LocalUploadResult(if (success) name else null, failureType)))
                }
            }
        }

        if (result.filename.isNullOrBlank()) {
            if (result.failureType == ConnectionFailure.STALLED ||
                result.failureType == ConnectionFailure.AUTHENTICATION) {
                applicationContext?.let { ConnectionManager.showConnectionAlert(it, result.failureType) }
            } else {
                _events.emit(ImageToVideoEvent.ShowToast(errorRes))
            }
            return null
        }
        return result.filename
    }

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        // 至少要有一张图。判断落在图位本身：有图位定义时看"有没有哪个图位填了图"
        // （首尾帧各自独立可选，只填首帧也算配置完整），没有图位定义的历史工作流退回原来的主图判断。
        // 导演台流不看图：它的素材是时间线上的视频片段，图位在那个流里根本不存在。
        val hasImage = if (state.isTimelineWorkflow) {
            state.timelineClips.isNotEmpty()
        } else if (state.imageSlots.isEmpty()) {
            state.slotImages[PLACEHOLDER_MAIN_IMAGE] != null
        } else {
            state.imageSlots.any { state.slotImages[it.placeholder] != null }
        }
        if (!hasImage) {
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

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
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
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItvPreview)
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentItvPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentItvPromptId()
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

        // If generation is running for this screen, switch to preview mode
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToVideoViewMode.PREVIEW)
        }

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
     * Stop listening for generation events.
     * Note: We keep the refs if generation is still running,
     * as the handler may still be called for completion events.
     */
    fun stopListening(generationViewModel: GenerationViewModel) {
        generationViewModel.unregisterSubmittedCallback(OWNER_ID)
        generationViewModel.unregisterEventHandler(OWNER_ID)
        // Only clear refs if no generation is active (handler was actually unregistered)
        // If generation is running, the handler is kept and needs the refs
        if (!generationViewModel.generationState.value.isGenerating) {
            if (generationViewModelRef == generationViewModel) {
                generationViewModelRef = null
            }
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
                    _events.emit(ImageToVideoEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(ImageToVideoEvent.ShowToastMessage(event.message))
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
                    _events.emit(ImageToVideoEvent.ShowToast(event.messageResId))
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
            filePrefix = VideoUtils.FilePrefix.IMAGE_TO_VIDEO
        ) { uri ->
            _uiState.update { it.copy(isFetching = false) }
            if (uri != null) {
                // Clear preview bitmap so video player takes display precedence
                _uiState.value = _uiState.value.copy(currentVideoUri = uri, previewBitmap = null)
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            }
            // If uri is null, don't complete generation - will retry on next return
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvPreview, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItvPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.ItvPreview)
        }
    }
}
