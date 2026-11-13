package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents user-saved values for a specific workflow.
 * These values are persisted per-workflow and restored when switching workflows.
 * Note: workflowId is stored externally in the storage key, not in this class.
 */
data class WorkflowValues(
    // Generation parameters (nullable = use workflow default)
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val samplerName: String? = null,
    val scheduler: String? = null,

    // Prompt parameters
    //
    // 正面提示词与负面同级**按工作流各存一份**（2026-09-23 改动）：
    // 它以前是全局偏好（`{serverId}_positivePrompt`），切工作流时不变 ——
    // 于是写实流的提示词会跟着带进二次元流、方图流的构图描述带进竖图流，每次切流都要手动改。
    //
    // 三种取值含义，别的字段都没有这层区别，改动时务必分清：
    // - `null`：这个工作流**还没保存过**正面词 → 载入时回退到该工作流的默认提示词；
    // - `""`：用户**明确清空过** → 就用空串，不回退默认；
    // - 非空：用户自己写的词。
    val positivePrompt: String? = null,
    val negativePrompt: String? = null,

    // Image-to-image specific
    val megapixels: Float? = null,

    // 视频分辨率选择器 / 加速模式（ResolutionSelector、TESpeedMiniMaxH3）
    val aspectRatio: String? = null,
    val speedMode: String? = null,

    // 主 LoRA 权重（LoraLoaderModelOnly.strength_model，占位符 {{lora_strength}}）
    val loraStrength: Float? = null,

    // Video specific
    val length: Int? = null,
    val frameRate: Int? = null,

    // Model selections (always saved, never in defaults)
    val model: String? = null,      // Checkpoint ({{ckpt_name}})
    val unetModel: String? = null,  // Single UNET ({{unet_name}})
    val loraModel: String? = null,  // Mandatory LoRA ({{lora_name}})
    val vaeModel: String? = null,
    val clipModel: String? = null,
    val clip1Model: String? = null,  // For multi-CLIP slot 1
    val clip2Model: String? = null,  // For multi-CLIP slot 2
    val clip3Model: String? = null,  // For multi-CLIP slot 3
    val clip4Model: String? = null,  // For multi-CLIP slot 4
    val textEncoderModel: String? = null,  // Text encoder ({{text_encoder_name}})
    val latentUpscaleModel: String? = null,  // Latent upscale model ({{latent_upscale_model}})
    val upscaleModel: String? = null,  // Upscale model ({{upscale_model}})
    val highnoiseUnetModel: String? = null,
    val lownoiseUnetModel: String? = null,
    val highnoiseLoraModel: String? = null,
    val lownoiseLoraModel: String? = null,

    // LoRA chains (JSON serialized using LoraSelection.toJsonString/fromJsonString)
    val loraChain: String? = null,
    val highnoiseLoraChain: String? = null,
    val lownoiseLoraChain: String? = null,

    // Node attribute edits (JSON serialized using NodeAttributeEdits.toJson/fromJson)
    val nodeAttributeEdits: String? = null,

    // 动态节点参数（通用节点参数引擎）：占位符名 -> 用户填的字符串值。
    // 只装「未识别」的占位符（见 TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS）；
    // 已硬编码的参数继续走各自的专有字段，不进这里。
    val dynamicParams: Map<String, String> = emptyMap(),

    // Advanced generation parameters
    val seed: Long? = null,
    val randomSeed: Boolean? = null,  // null = use default (true)
    /**
     * 种子取值方式（[sh.hnet.comfychair.model.SeedMode] 的名字）。
     *
     * 与 [randomSeed] 并存而不是替换它：`randomSeed` 是旧版留下的键，
     * 新版写的时候两个都写（老版本读得懂），读的时候以这个为准、缺失才回退到旧键。
     */
    val seedMode: String? = null,
    val denoise: Float? = null,
    val batchSize: Int? = null,
    val upscaleMethod: String? = null,
    val scaleBy: Float? = null,
    val stopAtClipLayer: Int? = null,

    /**
     * 输出端是否保存为 WebP（v4.6.1，模块 C）。
     *
     * - `null`：该工作流还没设置过 → 按"关闭"处理（保持 PNG，兼容旧行为）
     * - `true`：把 SaveImage 换成插件节点 BatchImageSaverCN，只落一份 .webp
     * - `false`：明确选择 PNG
     *
     * 只对图片类工作流（TTI / ITI 修复 / ITI 编辑）生效；视频与 3D 不读这个字段。
     */
    val saveAsWebp: Boolean? = null
) {
    companion object {
        fun fromJson(jsonString: String): WorkflowValues {
            val json = JSONObject(jsonString)
            return WorkflowValues(
                width = json.optInt("width").takeIf { it > 0 },
                height = json.optInt("height").takeIf { it > 0 },
                steps = json.optInt("steps").takeIf { it > 0 },
                cfg = json.optDouble("cfg").takeIf { !it.isNaN() }?.toFloat(),
                samplerName = json.optString("samplerName").takeIf { it.isNotEmpty() },
                scheduler = json.optString("scheduler").takeIf { it.isNotEmpty() },
                // 用 has 而不是裸 optString：要区分「存了空串（用户明确清空）」与「压根没这个字段（回退默认）」
                positivePrompt = if (json.has("positivePrompt")) json.optString("positivePrompt") else null,
                negativePrompt = if (json.has("negativePrompt")) json.optString("negativePrompt") else null,
                megapixels = json.optDouble("megapixels").takeIf { !it.isNaN() }?.toFloat(),
                aspectRatio = json.optString("aspectRatio").takeIf { it.isNotEmpty() },
                speedMode = json.optString("speedMode").takeIf { it.isNotEmpty() },
                loraStrength = json.optDouble("loraStrength").takeIf { !it.isNaN() }?.toFloat(),
                length = json.optInt("length").takeIf { it > 0 },
                frameRate = json.optInt("frameRate").takeIf { it > 0 },
                model = json.optString("model").takeIf { it.isNotEmpty() },
                unetModel = json.optString("unetModel").takeIf { it.isNotEmpty() },
                loraModel = json.optString("loraModel").takeIf { it.isNotEmpty() },
                vaeModel = json.optString("vaeModel").takeIf { it.isNotEmpty() },
                clipModel = json.optString("clipModel").takeIf { it.isNotEmpty() },
                clip1Model = json.optString("clip1Model").takeIf { it.isNotEmpty() },
                clip2Model = json.optString("clip2Model").takeIf { it.isNotEmpty() },
                clip3Model = json.optString("clip3Model").takeIf { it.isNotEmpty() },
                clip4Model = json.optString("clip4Model").takeIf { it.isNotEmpty() },
                textEncoderModel = json.optString("textEncoderModel").takeIf { it.isNotEmpty() },
                latentUpscaleModel = json.optString("latentUpscaleModel").takeIf { it.isNotEmpty() },
                upscaleModel = json.optString("upscaleModel").takeIf { it.isNotEmpty() },
                highnoiseUnetModel = json.optString("highnoiseUnetModel").takeIf { it.isNotEmpty() },
                lownoiseUnetModel = json.optString("lownoiseUnetModel").takeIf { it.isNotEmpty() },
                highnoiseLoraModel = json.optString("highnoiseLoraModel").takeIf { it.isNotEmpty() },
                lownoiseLoraModel = json.optString("lownoiseLoraModel").takeIf { it.isNotEmpty() },
                loraChain = json.optString("loraChain").takeIf { it.isNotEmpty() },
                highnoiseLoraChain = json.optString("highnoiseLoraChain").takeIf { it.isNotEmpty() },
                lownoiseLoraChain = json.optString("lownoiseLoraChain").takeIf { it.isNotEmpty() },
                nodeAttributeEdits = json.optString("nodeAttributeEdits").takeIf { it.isNotEmpty() },
                dynamicParams = json.optJSONObject("dynamicParams")?.let { obj ->
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            // JSON null / 空串都不是有效值，跳过（取值顺序会回退到 defaults / 节点默认值）
                            val value = obj.optString(key, "")
                            if (value.isNotEmpty()) put(key, value)
                        }
                    }
                } ?: emptyMap(),
                seed = json.optLong("seed", -1).takeIf { it >= 0 },
                randomSeed = if (json.has("randomSeed")) json.optBoolean("randomSeed") else null,
                seedMode = json.optString("seedMode").takeIf { it.isNotEmpty() },
                denoise = json.optDouble("denoise").takeIf { !it.isNaN() }?.toFloat(),
                batchSize = json.optInt("batchSize").takeIf { it > 0 },
                upscaleMethod = json.optString("upscaleMethod").takeIf { it.isNotEmpty() },
                scaleBy = json.optDouble("scaleBy").takeIf { !it.isNaN() }?.toFloat(),
                stopAtClipLayer = json.optInt("stopAtClipLayer", 0).takeIf { it != 0 },
                saveAsWebp = if (json.has("saveAsWebp")) json.optBoolean("saveAsWebp") else null
            )
        }

        fun toJson(values: WorkflowValues): String {
            return JSONObject().apply {
                values.width?.let { put("width", it) }
                values.height?.let { put("height", it) }
                values.steps?.let { put("steps", it) }
                values.cfg?.let { put("cfg", it.toDouble()) }
                values.samplerName?.let { put("samplerName", it) }
                values.scheduler?.let { put("scheduler", it) }
                values.positivePrompt?.let { put("positivePrompt", it) }
                values.negativePrompt?.let { put("negativePrompt", it) }
                values.megapixels?.let { put("megapixels", it.toDouble()) }
                values.aspectRatio?.let { put("aspectRatio", it) }
                values.speedMode?.let { put("speedMode", it) }
                values.loraStrength?.let { put("loraStrength", it.toDouble()) }
                values.length?.let { put("length", it) }
                values.frameRate?.let { put("frameRate", it) }
                values.model?.let { put("model", it) }
                values.unetModel?.let { put("unetModel", it) }
                values.loraModel?.let { put("loraModel", it) }
                values.vaeModel?.let { put("vaeModel", it) }
                values.clipModel?.let { put("clipModel", it) }
                values.clip1Model?.let { put("clip1Model", it) }
                values.clip2Model?.let { put("clip2Model", it) }
                values.clip3Model?.let { put("clip3Model", it) }
                values.clip4Model?.let { put("clip4Model", it) }
                values.textEncoderModel?.let { put("textEncoderModel", it) }
                values.latentUpscaleModel?.let { put("latentUpscaleModel", it) }
                values.upscaleModel?.let { put("upscaleModel", it) }
                values.highnoiseUnetModel?.let { put("highnoiseUnetModel", it) }
                values.lownoiseUnetModel?.let { put("lownoiseUnetModel", it) }
                values.highnoiseLoraModel?.let { put("highnoiseLoraModel", it) }
                values.lownoiseLoraModel?.let { put("lownoiseLoraModel", it) }
                values.loraChain?.let { put("loraChain", it) }
                values.highnoiseLoraChain?.let { put("highnoiseLoraChain", it) }
                values.lownoiseLoraChain?.let { put("lownoiseLoraChain", it) }
                values.nodeAttributeEdits?.let { put("nodeAttributeEdits", it) }
                if (values.dynamicParams.isNotEmpty()) put("dynamicParams", JSONObject(values.dynamicParams))
                values.seed?.let { put("seed", it) }
                values.randomSeed?.let { put("randomSeed", it) }
                // 新键与旧键都写：老版本读 randomSeed、新版本优先读 seedMode
                values.seedMode?.let { put("seedMode", it) }
                values.denoise?.let { put("denoise", it.toDouble()) }
                values.batchSize?.let { put("batchSize", it) }
                values.upscaleMethod?.let { put("upscaleMethod", it) }
                values.scaleBy?.let { put("scaleBy", it.toDouble()) }
                values.stopAtClipLayer?.let { put("stopAtClipLayer", it) }
                values.saveAsWebp?.let { put("saveAsWebp", it) }
            }.toString()
        }
    }
}
