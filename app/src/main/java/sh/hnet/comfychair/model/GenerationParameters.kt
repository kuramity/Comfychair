package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable

/**
 * Single source of truth for ALL generation parameters.
 * Used by all generation modes (TTI, ITI, TTV, ITV).
 *
 * Field visibility in the UI is controlled by WorkflowCapabilities,
 * which is derived from workflow placeholders.
 *
 * All fields are stored as Strings to match UI input fields.
 */
@Immutable
data class GenerationParameters(
    // Image dimensions
    val width: String = "1024",
    val height: String = "1024",
    val megapixels: String = "1.0",
    /**
     * ResolutionSelector 的宽高比（如 `9:16 (Portrait Widescreen)`）。
     *
     * Qwen2.1 这类流的尺寸**不看 width/height**，而是由「宽高比 + 百万像素」两件套决定
     * （节点是 ResolutionSelector）。空串表示该工作流不用这个字段。
     */
    val aspectRatio: String = "",

    // Video parameters
    val length: String = "49",
    val fps: String = "24",

    // Sampling parameters
    val steps: String = "20",
    val cfg: String = "7.0",
    val sampler: String = "euler",
    val scheduler: String = "normal",
    val denoise: String = "1.0",

    // Seed
    val seed: String = "0",
    val randomSeed: Boolean = true,

    // Batch & scaling
    val batchSize: String = "1",
    val upscaleMethod: String = "nearest-exact",
    val scaleBy: String = "1.5",

    // CLIP
    val stopAtClipLayer: String = "-1",

    // Mandatory LoRA (single selection dropdown, not injection chain)
    val selectedLoraName: String = ""
)
