package sh.hnet.comfychair.connection

/**
 * Centralized cache for model lists fetched from ComfyUI server.
 * Populated from /object_info response on connection.
 *
 * This cache is shared across all generation screens via ConnectionManager,
 * eliminating redundant API calls and enabling user-triggered refresh.
 */
data class ModelCache(
    val checkpoints: List<String> = emptyList(),
    val unets: List<String> = emptyList(),
    val vaes: List<String> = emptyList(),
    val clips: List<String> = emptyList(),
    val loras: List<String> = emptyList(),
    val upscaleMethods: List<String> = emptyList(),
    val textEncoders: List<String> = emptyList(),
    val latentUpscaleModels: List<String> = emptyList(),
    val upscaleModels: List<String> = emptyList(),
    val samplers: List<String> = emptyList(),
    val schedulers: List<String> = emptyList(),
    // 视频分辨率选择器 / 加速模式（按节点类型取，见 ConnectionManager.extractModelLists）
    val aspectRatios: List<String> = emptyList(),
    val speedModes: List<String> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val lastError: String? = null,

    // ── 三类核心模型分别上报完成状态（G8 指令 1，v4.16.10 起）────────────────
    //
    // 背景（历史 Q3 复发 + G8）：isLoaded/isLoading/lastError 是**整体一份**，
    // 退出重连后「UNET 空、VAE/CLIP 正常」时整体仍是「成功」，UNET 的单类提取失败被
    // 掩盖成一个沉默的空下拉；而 E7 的「正在加载长期驻留」也是整体 isLoading 不收敛、
    // 连带把未选中值的 UNET 永远显示成「正在加载模型列表…」。
    //
    // 这三个状态各自独立收敛：请求失败时三类一起置 FAILED(请求失败)；请求成功时按
    // 各类来源逐一诊断（节点缺失 / 字段缺失 / 服务器真没模型）。判定逻辑见
    // [ModelListRecovery.kt] 的 [diagnoseCategory]，纯函数可单测。
    val unetState: CategoryLoadState = CategoryLoadState.PENDING,
    val vaeState: CategoryLoadState = CategoryLoadState.PENDING,
    val clipState: CategoryLoadState = CategoryLoadState.PENDING,
    // 单类失败的具体原因，仅在对应 *State == FAILED 时非 NONE（G8 指令 3，便于定位）
    val unetFailure: CategoryFailureReason = CategoryFailureReason.NONE,
    val vaeFailure: CategoryFailureReason = CategoryFailureReason.NONE,
    val clipFailure: CategoryFailureReason = CategoryFailureReason.NONE,
    // 本次列表装配对应的连接**代次**（见 ConnectionManager.fetchGeneration）：
    // 后台返回 / 退出重连时旧请求的迟到回调必须按代次丢弃，否则旧服务器（或旧过渡态）
    // 的响应会盖掉新连接刚装好的列表，正是「重拉又被吞」的一种表现
    val generation: Long = 0L
)
