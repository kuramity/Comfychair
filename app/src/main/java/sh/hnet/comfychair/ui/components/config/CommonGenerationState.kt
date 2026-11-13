package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.WorkflowCapabilities

/**
 * Interface for common generation state properties shared across all UiState types.
 *
 * This enables shared helper functions for BottomSheetConfig construction.
 * Each screen's UiState data class implements this interface.
 *
 * Note: Kotlin data classes automatically satisfy interface properties
 * if they have matching property names, so implementing this interface
 * requires no additional code in the UiState classes.
 */
interface CommonGenerationState {
    // Workflow capabilities (controls field visibility)
    val capabilities: WorkflowCapabilities

    // Model selections
    val selectedCheckpoint: String
    val selectedUnet: String
    val selectedVae: String
    val selectedClip: String
    val selectedClip1: String
    val selectedClip2: String
    val selectedClip3: String
    val selectedClip4: String
    val selectedTextEncoder: String
    val selectedLatentUpscaleModel: String
    val selectedLoraName: String  // Mandatory LoRA (single selection)

    // Available model lists (from server)
    val availableCheckpoints: List<String>
    val availableUnets: List<String>
    val availableVaes: List<String>
    val availableClips: List<String>
    val availableLoras: List<String>
    val availableTextEncoders: List<String>
    val availableLatentUpscaleModels: List<String>
    // Default empty - only ITI editing mode implements upscale model selection for now
    val availableUpscaleModels: List<String>
        get() = emptyList()
    val availableUpscaleMethods: List<String>
    val availableSamplers: List<String>
    val availableSchedulers: List<String>
    // 视频分辨率选择器 / 加速模式（默认空：只有视频页实现这两个字段）
    val availableAspectRatios: List<String>
        get() = emptyList()
    val availableSpeedModes: List<String>
        get() = emptyList()

    // Workflow-filtered model options
    val filteredCheckpoints: List<String>?
    val filteredUnets: List<String>?
    val filteredVaes: List<String>?
    val filteredClips: List<String>?
    val filteredClips1: List<String>?
    val filteredClips2: List<String>?
    val filteredClips3: List<String>?
    val filteredClips4: List<String>?
    val filteredTextEncoders: List<String>?
    val filteredLatentUpscaleModels: List<String>?
    val filteredLoras: List<String>?

    // Generation parameters
    val steps: String
    val cfg: String
    val sampler: String
    val scheduler: String
    /**
     * 种子的取值方式（固定 / 随机 / 每次+1 / 每次−1）。
     *
     * 取代了原来的 `randomSeed: Boolean` 二态：加了递增递减之后，"随机"与"递增"是
     * 互斥的几种行为，用两个布尔会出现"既随机又递增"这种非法组合。
     */
    val seedMode: SeedMode
    /**
     * 派生属性：提交时要不要让引擎随机生成种子。
     *
     * 保留这个名字是因为 `WorkflowManager` 的 5 个 prepare 函数都吃 `randomSeed: Boolean` ——
     * 它们只关心"要不要随机"，不关心是固定还是递增递减（那由调用方算好 `seed` 值传进来）。
     */
    val randomSeed: Boolean
        get() = seedMode.usesRandomValue
    val seed: String
    val denoise: String
    val batchSize: String
    /**
     * ResolutionSelector 的百万像素（Qwen2.1 这类流的尺寸 = 宽高比 + 百万像素）。
     *
     * 带默认值：只有真正需要它的页面（文生图 / 图生图）覆盖，其余页面取默认即可。
     */
    val megapixels: String
        get() = "1.0"
    val upscaleMethod: String
    val scaleBy: String
    val stopAtClipLayer: String
    // 视频分辨率选择器 / 加速模式（默认空：只有视频页实现这两个字段）
    val aspectRatio: String        get() = ""
    val speedMode: String
        get() = ""

    // Validation errors
    val stepsError: String?
    val cfgError: String?
    val seedError: String?
    val denoiseError: String?
    val batchSizeError: String?
    /** 百万像素的校验错误；不实现这个字段的页面取默认（无错误）。 */
    val megapixelsError: String?
        get() = null
    val scaleByError: String?
    val stopAtClipLayerError: String?

    // LoRA chain (for screens that support it)
    val loraChain: List<LoraSelection>
}
