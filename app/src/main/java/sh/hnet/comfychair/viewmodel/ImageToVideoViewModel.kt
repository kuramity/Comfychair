package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import org.json.JSONObject
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
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
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.NUMBERED_SLOT_PLACEHOLDER_PREFIX
import sh.hnet.comfychair.model.NumberedSlotContainer
import sh.hnet.comfychair.model.VideoOptions
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.audioContainerOf
import sh.hnet.comfychair.model.hasRoomForAudioSlot
import sh.hnet.comfychair.model.hasRoomForNumberedSlot
import sh.hnet.comfychair.model.nextAudioSlot
import sh.hnet.comfychair.model.nextNumberedSlot
import sh.hnet.comfychair.model.numberedContainerOf
import sh.hnet.comfychair.model.withAudioSlotAdded
import sh.hnet.comfychair.model.withSlotAdded
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.util.BitmapDecodeUtils
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.UploadImageEncoder
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.AudioSlotDetector
import sh.hnet.comfychair.workflow.ImageSlotDetector
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.viewmodel.base.BaseVideoGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.VideoGenerationState
import sh.hnet.comfychair.viewmodel.base.VideoSlotsDetection
import sh.hnet.comfychair.viewmodel.base.VideoStateSnapshot
import sh.hnet.comfychair.viewmodel.base.VideoWorkflowItem

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * B4.2-B 视频对合并：原 data class 与 TTV 侧逐字节相同，归一为共享的
 * [VideoWorkflowItem]；typealias 保持既有调用点零改动。
 */
typealias ItvWorkflowItem = VideoWorkflowItem

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
     * 当前工作流是旧版「时间线导演台」流（带 `MiniMaxH3TimelineDirector` 节点）。
     *
     * 该功能已整体移除：这类工作流的 `timeline_data` 输入不再有人维护，若按普通图生视频
     * 路径提交，服务器只会回一个看不懂的 400。加载时记下这个事实，界面给出明确提示并禁用生成。
     */
    val legacyTimelineUnsupported: Boolean = false,

    override val previewBitmap: Bitmap? = null,
    override val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    override val selectedWorkflow: String = "",
    override val selectedWorkflowId: String = "",  // Workflow ID for storage
    override val availableWorkflows: List<ItvWorkflowItem> = emptyList(),

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

    // Upload/fetch state
    val isUploading: Boolean = false,
    /** 上传进度 0–100（-1 = 当前没有上传）；v4.6.1 模块 D */
    val uploadProgress: Int = -1,
    override val isFetching: Boolean = false
) : VideoGenerationState {

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
 * 旧版「时间线导演台」工作流判定：功能已移除，加载时给出明确提示，避免提交后看不懂的 400。
 *
 * 判据是工作流里有没有 `MiniMaxH3TimelineDirector` 节点 —— 那是那条插件链唯一的入口节点。
 * 独立实现而不复用已删除的检测器：这里只需要一个"是不是这类流"的布尔判断，不读任何内容。
 *
 * 格式兼容 `WorkflowParser` 的两种结构：app 自定义格式（`{"nodes": {...}}` 包一层）与
 * 标准 ComfyUI API 格式（节点直接挂在根对象上）。裸 API 格式漏判会让旧时间线副本
 * 绕过友好提示、提交后撞看不懂的 400（LEGACY-FORMAT-01）。
 * 节点表判据取 `class_type`：根对象上的 LiteGraph 元数据（`last_node_id` 等标量）不是
 * JSONObject 自然跳过；LiteGraph 格式的 `nodes` 是数组（节点用 `type` 而非 `class_type`），
 * 也不会命中，保持返回 false。
 */
internal fun isLegacyTimelineWorkflow(workflowJson: JSONObject): Boolean {
    val nodes = workflowJson.optJSONObject("nodes") ?: workflowJson
    for (nodeId in nodes.keys()) {
        val node = nodes.optJSONObject(nodeId) ?: continue
        if (node.optString("class_type") == "MiniMaxH3TimelineDirector") return true
    }
    return false
}

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
 * 共享快照 → 图生视频 UiState 的回拷（B4.2-B）。
 *
 * 只拷贝搬进 [VideoGenerationState] 的共享字段；本页独有字段（视图模式 / 图位 / 音频 /
 * 上传状态等）不在快照里，直接沿用 [state] 原值——这正是"基类共享写入不丢独有状态"的关键。
 * 拷贝清单由 VideoStateSnapshotRoundTripTest 钉住（快照回转后逐字段相等）。
 */
internal fun ImageToVideoUiState.withVideoSnapshot(snapshot: VideoStateSnapshot): ImageToVideoUiState =
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
 * ViewModel for the Image-to-Video screen
 */
class ImageToVideoViewModel :
    BaseVideoGenerationViewModel<ImageToVideoUiState, ImageToVideoEvent>(OWNER_ID) {

    override val initialState = ImageToVideoUiState()

    // Constants
    companion object {
        const val OWNER_ID = "IMAGE_TO_VIDEO"

        /** 等待 ensureConnection 回调的超时（对齐 GenerationViewModel 的提交闸门 30s） */
        private const val CONNECTION_WAIT_TIMEOUT_MS = 30_000L

        /** 主图占位符：沿用 ItvSource 缓存档（与单图时代的主图缓存文件是同一个） */
        const val PLACEHOLDER_MAIN_IMAGE = "image_filename"

        /** 编号图位占位符前缀，编号紧跟其后（如 reference_image_1） */
        private const val PLACEHOLDER_REFERENCE_PREFIX = NUMBERED_SLOT_PLACEHOLDER_PREFIX
    }

    // ---- 两侧语义分叉点（见基类各类成员 KDoc） ----

    override val logTag = "ImageToVideo"
    override val prefsName = "ImageToVideoFragmentPrefs"
    override val videoWorkflowType = WorkflowType.ITV
    override val defaultPositivePrompt: String get() = SeasonalPrompts.getImageToVideoPrompt()
    override val videoFilePrefix = VideoUtils.FilePrefix.IMAGE_TO_VIDEO

    override fun currentVideoPromptId(): String? = MediaStateHolder.getCurrentItvPromptId()

    override fun clearCurrentVideoPromptId() {
        MediaStateHolder.clearCurrentItvPromptId()
    }

    override fun videoMediaKey(promptId: String): MediaStateHolder.MediaKey =
        MediaStateHolder.MediaKey.ItvVideo(promptId)

    override val previewMediaKey: MediaStateHolder.MediaKey = MediaStateHolder.MediaKey.ItvPreview

    override fun showToastEvent(messageResId: Int) {
        viewModelScope.launch { _events.emit(ImageToVideoEvent.ShowToast(messageResId)) }
    }

    override fun showToastMessageEvent(message: String) {
        viewModelScope.launch { _events.emit(ImageToVideoEvent.ShowToastMessage(message)) }
    }

    override fun applyVideoSnapshot(
        state: ImageToVideoUiState,
        snapshot: VideoStateSnapshot
    ): ImageToVideoUiState = state.withVideoSnapshot(snapshot)

    /**
     * 生成配置检查的页面特有早退（原两段早退原样保留）：
     * 旧版「时间线导演台」工作流 + 图位无图。语义与原先一致（纯合取，先后顺序不影响结果）。
     */
    override fun videoSpecificInvalidConfiguration(state: ImageToVideoUiState): Boolean {
        // 旧版「时间线导演台」工作流：功能已移除，这条路永远不可提交
        // （界面同时有一条可见提示说明原因，见 ImageToVideoScreen）
        if (state.legacyTimelineUnsupported) {
            return true
        }

        // 至少要有一张图。判断落在图位本身：有图位定义时看"有没有哪个图位填了图"
        // （首尾帧各自独立可选，只填首帧也算配置完整），没有图位定义的历史工作流退回原来的主图判断。
        val hasImage = if (state.imageSlots.isEmpty()) {
            state.slotImages[PLACEHOLDER_MAIN_IMAGE] != null
        } else {
            state.imageSlots.any { state.slotImages[it.placeholder] != null }
        }
        return !hasImage
    }

    /**
     * startListening 的页面级钩子：If generation is running for this screen, switch to preview mode。
     */
    override fun onListeningStarted(generationViewModel: GenerationViewModel) {
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToVideoViewMode.PREVIEW)
        }
    }

    /**
     * 图位 / 音频 / 旧时间线探测：用已解析的 root 一并探（原实现；TTV 默认走字符串重载只探图位）。
     */
    override fun detectVideoSlots(item: VideoWorkflowItem, slotsRoot: JSONObject?): VideoSlotsDetection {
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

        // 旧版「时间线导演台」工作流：该功能已整体移除，这里把"这条流不能用"的事实
        // 记进状态，界面据此提示并禁用生成 —— 否则它会走普通图生视频路径，
        // 提交时因为 timeline_data 没人维护只拿到一句看不懂的 400
        val legacyTimelineUnsupported = slotsRoot?.let { isLegacyTimelineWorkflow(it) } == true

        return VideoSlotsDetection(imageSlots, audioSlots, legacyTimelineUnsupported)
    }

    /**
     * loadWorkflowValues 的子类增量（原 copy 里 ITV 独有的 7 个字段，原样保留）：
     * 图位定义与已填的图位图、编号/音频容器、音频文件（不落盘恢复）、旧时间线标志。
     * 在基类的同一次原子写入里完成（见 amendWorkflowValuesState KDoc）。
     */
    override fun amendWorkflowValuesState(
        state: ImageToVideoUiState,
        detection: VideoSlotsDetection
    ): ImageToVideoUiState = state.copy(
        // 图位定义与已填的图位图：换工作流时一起换成新工作流的图位，不留上一个工作流的残留键
        imageSlots = detection.imageSlots,
        slotImages = restoreSlotImages(detection.imageSlots),
        // 容器信息同样随新工作流重算：新工作流没有编号图位时显式为 null，不沿用上一个工作流的容器
        numberedSlotContainer = numberedContainerOf(detection.imageSlots),
        // 音频槽同样随新工作流重算：不沿用上一个工作流的音频（换流后残留会导致提交注入到错误的容器）
        audioSlots = detection.audioSlots,
        // 音频不落盘恢复：它动辄几十 MB，为"切页回来还在"而复制一份不值当
        // （图片那套落盘恢复同时承担着预览缩略图的职责）。切到别的页再回来不会丢——
        // ViewModel 还活着；只有进程结束才需要重新选一次。
        slotAudioFiles = emptyMap(),
        numberedAudioContainer = audioContainerOf(detection.audioSlots),
        // 旧版「时间线导演台」判定同样随新工作流重算：不沿用上一个工作流的结论
        legacyTimelineUnsupported = detection.legacyTimelineUnsupported
    )

    // Primary LoRA chain operations (for single-model workflows like LTX 2.0)
    override fun withLoraChain(state: ImageToVideoUiState, chain: List<LoraSelection>) =
        state.copy(loraChain = chain)

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
            DebugLogger.w(logTag, "等待连接超时（${CONNECTION_WAIT_TIMEOUT_MS}ms），视为连接失败")
            _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_connection_lost))
            return null
        }
        if (!connected) return null  // Dialog already shown by ensureConnection

        val state = _uiState.value

        // 旧版「时间线导演台」工作流：功能已移除，直接明确告知，
        // 不让用户白等一次因为 timeline_data 没人维护而必然失败的提交
        if (state.legacyTimelineUnsupported) {
            _events.emit(ImageToVideoEvent.ShowToast(R.string.msg_workflow_feature_removed))
            return null
        }

        // Validate all fields
        val dims = validatedVideoDims(state) ?: return null

        // 提交前兜底（1.3）：与文生视频同理，取值来自缺省常量，仍在进占位符替换前统一清洗。
        // 改了值就同步回界面（与文生图 prepareWorkflowJson 同口径：下拉框不该继续显示脏值）
        val (safeSampler, safeScheduler) = sanitizeVideoSamplers(state)

        _uiState.update { it.copy(isUploading = true) }
        return try {
            // 逐张上传已填图位。一张都没填时直接放弃：注入器会把整条图片链路删光，
            // 出片必然失败，不如在这里给一句能读懂的提示。
            val uploadedByPlaceholder = mutableMapOf<String, String>()
            for ((placeholder, bitmap) in state.slotImages) {
                val filename = uploadBitmap(client, bitmap, "itv_$placeholder") ?: return null
                uploadedByPlaceholder[placeholder] = filename
            }
            if (uploadedByPlaceholder.isEmpty()) {
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
                width = dims.width,
                height = dims.height,
                length = dims.length,
                fps = dims.fps,
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
                // 提交前兜底（1.3）：与文生视频同理，取值来自缺省常量，仍在进占位符替换前统一清洗
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

            injectVideoLoraChains(baseWorkflow, state)
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
        if (imageBytes == null) {
            // WebP 编码失败（compress 返回 false）：空字节不能推进上传链路
            _events.emit(ImageToVideoEvent.ShowToast(R.string.error_save_image))
            return null
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
     * 读本地文件字节并上传到服务器的 input 目录（图片/音频共用这一条路径）。
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
}
