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
    val lastError: String? = null
)
