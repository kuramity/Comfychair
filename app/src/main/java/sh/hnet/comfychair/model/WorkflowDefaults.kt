package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents default values for a workflow.
 * These values are stored in the workflow JSON and used when no user-saved values exist.
 *
 * Note: Field visibility is determined automatically by checking for {{placeholder}} patterns
 * in the workflow JSON using WorkflowManager.getWorkflowPlaceholders(), not by has* flags.
 */
data class WorkflowDefaults(
    // Image generation parameters
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val samplerName: String? = null,
    val scheduler: String? = null,

    // Prompt parameters
    //
    // 正面提示词的工作流默认值：prebuilt 资产的 `defaults.positive_prompt` 里早就有它
    // （如写实流的 `RAW photo, …`），只是以前一直没读，被静默丢掉了。
    // 回合点是「草稿为空时用该工作流的默认词」——只要这个工作流还没保存过正面词
    // （`WorkflowValues.positivePrompt == null`）就取这里的值。
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

    // Advanced generation parameter defaults
    val seed: Long? = null,
    val denoise: Float? = null,
    val batchSize: Int? = null,
    val upscaleMethod: String? = null,
    val scaleBy: Float? = null,
    val stopAtClipLayer: Int? = null,

    // Model defaults (auto-mapped from the workflow, still user-adjustable)
    val checkpointModel: String? = null,
    val unetModel: String? = null,
    val clipModel: String? = null,
    val vaeModel: String? = null,
    val clip1Model: String? = null,
    val clip2Model: String? = null,
    val clip3Model: String? = null,
    val loraModel: String? = null,
    val upscalerModel: String? = null
) {
    companion object {
        fun fromJson(jsonObject: JSONObject?): WorkflowDefaults {
            if (jsonObject == null) return WorkflowDefaults()

            return WorkflowDefaults(
                width = jsonObject.optInt("width").takeIf { it > 0 },
                height = jsonObject.optInt("height").takeIf { it > 0 },
                steps = jsonObject.optInt("steps").takeIf { it > 0 },
                cfg = jsonObject.optDouble("cfg").takeIf { !it.isNaN() }?.toFloat(),
                samplerName = jsonObject.optString("sampler_name").takeIf { it.isNotEmpty() },
                scheduler = jsonObject.optString("scheduler").takeIf { it.isNotEmpty() },
                positivePrompt = if (jsonObject.has("positive_prompt")) jsonObject.optString("positive_prompt") else null,
                negativePrompt = if (jsonObject.has("negative_prompt")) jsonObject.optString("negative_prompt") else null,
                megapixels = jsonObject.optDouble("megapixels").takeIf { !it.isNaN() }?.toFloat(),
                aspectRatio = jsonObject.optString("aspect_ratio").takeIf { it.isNotEmpty() },
                speedMode = jsonObject.optString("speed_mode").takeIf { it.isNotEmpty() },
                loraStrength = jsonObject.optDouble("lora_strength").takeIf { !it.isNaN() }?.toFloat(),
                length = jsonObject.optInt("length").takeIf { it > 0 },
                frameRate = jsonObject.optInt("frame_rate").takeIf { it > 0 },
                // Advanced generation parameter defaults
                seed = jsonObject.optLong("seed", -1).takeIf { it >= 0 },
                denoise = jsonObject.optDouble("denoise").takeIf { !it.isNaN() }?.toFloat(),
                batchSize = jsonObject.optInt("batch_size").takeIf { it > 0 },
                upscaleMethod = jsonObject.optString("upscale_method").takeIf { it.isNotEmpty() },
                scaleBy = jsonObject.optDouble("scale_by").takeIf { !it.isNaN() }?.toFloat(),
                stopAtClipLayer = jsonObject.optInt("stop_at_clip_layer", 0).takeIf { it != 0 },
                checkpointModel = jsonObject.optString("ckpt_name").takeIf { it.isNotEmpty() },
                unetModel = jsonObject.optString("unet_name").takeIf { it.isNotEmpty() },
                clipModel = jsonObject.optString("clip_name").takeIf { it.isNotEmpty() },
                vaeModel = jsonObject.optString("vae_name").takeIf { it.isNotEmpty() },
                clip1Model = jsonObject.optString("clip_name1").takeIf { it.isNotEmpty() },
                clip2Model = jsonObject.optString("clip_name2").takeIf { it.isNotEmpty() },
                clip3Model = jsonObject.optString("clip_name3").takeIf { it.isNotEmpty() },
                loraModel = jsonObject.optString("lora_name").takeIf { it.isNotEmpty() },
                upscalerModel = jsonObject.optString("upscale_model").takeIf { it.isNotEmpty() }
            )
        }

        fun toJson(defaults: WorkflowDefaults): JSONObject {
            return JSONObject().apply {
                defaults.width?.let { put("width", it) }
                defaults.height?.let { put("height", it) }
                defaults.steps?.let { put("steps", it) }
                defaults.cfg?.let { put("cfg", it.toDouble()) }
                defaults.samplerName?.let { put("sampler_name", it) }
                defaults.scheduler?.let { put("scheduler", it) }
                defaults.positivePrompt?.let { put("positive_prompt", it) }
                defaults.negativePrompt?.let { put("negative_prompt", it) }
                defaults.megapixels?.let { put("megapixels", it.toDouble()) }
                defaults.aspectRatio?.let { put("aspect_ratio", it) }
                defaults.speedMode?.let { put("speed_mode", it) }
                defaults.loraStrength?.let { put("lora_strength", it.toDouble()) }
                defaults.length?.let { put("length", it) }
                defaults.frameRate?.let { put("frame_rate", it) }
                // Advanced generation parameter defaults
                defaults.seed?.let { put("seed", it) }
                defaults.denoise?.let { put("denoise", it.toDouble()) }
                defaults.batchSize?.let { put("batch_size", it) }
                defaults.upscaleMethod?.let { put("upscale_method", it) }
                defaults.scaleBy?.let { put("scale_by", it.toDouble()) }
                defaults.stopAtClipLayer?.let { put("stop_at_clip_layer", it) }
                defaults.checkpointModel?.let { put("ckpt_name", it) }
                defaults.unetModel?.let { put("unet_name", it) }
                defaults.clipModel?.let { put("clip_name", it) }
                defaults.vaeModel?.let { put("vae_name", it) }
                defaults.clip1Model?.let { put("clip_name1", it) }
                defaults.clip2Model?.let { put("clip_name2", it) }
                defaults.clip3Model?.let { put("clip_name3", it) }
                defaults.loraModel?.let { put("lora_name", it) }
                defaults.upscalerModel?.let { put("upscale_model", it) }
            }
        }
    }
}
