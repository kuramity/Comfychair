package sh.hnet.comfychair.model

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType

/**
 * Unified workflow capability flags derived from workflow placeholders.
 *
 * This data class provides a single source of truth for all capability flags,
 * eliminating duplicate placeholder-to-flag mapping code across ViewModels.
 *
 * Naming convention:
 * - `has*Name` → dropdown visibility (checks placeholder existence)
 * - `has*` (no Name suffix) → LoRA injection chain (checks target model loader)
 */
data class WorkflowCapabilities(
    // Parameter flags
    val hasPositivePrompt: Boolean = false,
    val hasNegativePrompt: Boolean = false,
    val hasCfg: Boolean = false,
    val hasWidth: Boolean = false,
    val hasHeight: Boolean = false,
    val hasMegapixels: Boolean = false,
    val hasLength: Boolean = false,
    val hasFrameRate: Boolean = false,
    val hasSteps: Boolean = false,
    val hasSamplerName: Boolean = false,
    val hasScheduler: Boolean = false,
    val hasSeed: Boolean = false,
    val hasDenoise: Boolean = false,
    val hasBatchSize: Boolean = false,
    val hasUpscaleMethod: Boolean = false,
    val hasScaleBy: Boolean = false,
    val hasStopAtClipLayer: Boolean = false,
    // 视频分辨率选择器 / 加速模式（ResolutionSelector、TESpeedMiniMaxH3）
    val hasAspectRatio: Boolean = false,
    val hasSpeedMode: Boolean = false,

    // Model dropdown flags (*Name pattern)
    val hasCheckpointName: Boolean = false,
    val hasUnetName: Boolean = false,
    val hasHighnoiseUnetName: Boolean = false,
    val hasLownoiseUnetName: Boolean = false,
    val hasVaeName: Boolean = false,
    val hasClipName: Boolean = false,
    val hasClipName1: Boolean = false,
    val hasClipName2: Boolean = false,
    val hasClipName3: Boolean = false,
    val hasClipName4: Boolean = false,
    val hasTextEncoderName: Boolean = false,
    val hasLatentUpscaleModel: Boolean = false,
    val hasUpscaleModelName: Boolean = false,

    // Mandatory LoRA dropdown flags (*Name pattern)
    val hasLoraName: Boolean = false,
    val hasHighnoiseLoraName: Boolean = false,
    val hasLownoiseLoraName: Boolean = false,
    // 主 LoRA 权重（LoraLoaderModelOnly.strength_model，占位符 {{lora_strength}}）
    val hasLoraStrength: Boolean = false,

    // LoRA injection chain flags (no *Name suffix)
    // These are triggered by model loader placeholders, not LoRA placeholders
    val hasLora: Boolean = false,           // Triggered by ckpt_name OR unet_name
    val hasHighnoiseLora: Boolean = false,  // Triggered by highnoise_unet_name
    val hasLownoiseLora: Boolean = false,   // Triggered by lownoise_unet_name

    // 图片输入位（甲类具名 / 乙类编号），空列表表示该工作流不需要图片输入
    // 注意：本字段不遵循上面的 has* 命名约定，它的内容由 ImageSlotDetector 从工作流 JSON
    // 识别得出（占位符 + 下游消费口），不能只靠占位符集合推断
    val imageSlots: List<ImageSlot> = emptyList(),

    /**
     * 工作流类型（切换工作流时由 ViewModel 写入）。
     *
     * 为什么必须暴露成属性（v4.3.0 真机 bug 修复）：
     * 3D 结果曾由**全局单例** [sh.hnet.comfychair.model.ThreeDResultHolder] 持有，
     * 而生成页只判"holder 有没有值"就显示 3D 分段 —— 于是只要生成过一次 3D，
     * 切到任何普通出图流都还留着「3D 模型/渲染图/原图」三段，把原有的
     * 「原图/预览」两段挤掉，反复切换还会闪退。
     * 判据必须是"**当前工作流本身是不是 3D 流**"，而不是"全局有没有 3D 结果"。
     */
    val workflowType: WorkflowType? = null
) {
    /** 当前工作流是否具备 3D 产物（只有 TTI_3D / ITI_3D 才有） */
    val is3DWorkflow: Boolean
        get() = workflowType == WorkflowType.TTI_3D || workflowType == WorkflowType.ITI_3D

    companion object {
        /**
         * Creates WorkflowCapabilities from a set of workflow placeholders.
         *
         * @param placeholders Set of placeholder names found in the workflow
         * @param imageSlots 图位识别结果（见 ImageSlotDetector.detect），拿不到工作流 JSON 时传空列表
         * @param workflowType 工作流类型：3D 流（TTI_3D/ITI_3D）的 LoRA 面板按**流里是否真有
         *   LoRA 节点**决定去留（见 [hasLoraNodes]）——v4.0.0 的文生3D 流（prebuilt_29）的
         *   文生图参考图子段真有 LoraLoaderModelOnly，一刀切按类型隐藏是功能回退；
         *   而图生3D 流（prebuilt_28/32）没有 LoRA 节点，面板只会空转，继续隐藏
         * @param workflowJson 工作流 JSON（内部格式，含 `nodes` 对象）。3D 流用它扫描真实
         *   LoRA 节点；拿不到时按"无节点"保守处理（3D 流隐藏面板）
         * @return WorkflowCapabilities with all flags set based on placeholder presence
         */
        fun fromPlaceholders(
            placeholders: Set<String>,
            imageSlots: List<ImageSlot> = emptyList(),
            workflowType: WorkflowType? = null,
            workflowJson: String? = null
        ): WorkflowCapabilities {
            val is3D = workflowType == WorkflowType.TTI_3D || workflowType == WorkflowType.ITI_3D
            // 只对 3D 流按真实节点判定：有 LoraLoader 节点（prebuilt_29）保留面板，
            // 没有的（prebuilt_28/32）隐藏；非 3D 流维持原占位符推断不变（2026-11-09 v4.1.0）
            val suppressLora = is3D && !hasLoraNodes(workflowJson)
            return WorkflowCapabilities(
                // Parameters
                hasPositivePrompt = "positive_prompt" in placeholders,
                hasNegativePrompt = "negative_prompt" in placeholders,
                hasCfg = "cfg" in placeholders,
                hasWidth = "width" in placeholders,
                hasHeight = "height" in placeholders,
                hasMegapixels = "megapixels" in placeholders,
                hasLength = "length" in placeholders,
                hasFrameRate = "frame_rate" in placeholders,
                hasSteps = "steps" in placeholders,
                hasSamplerName = "sampler_name" in placeholders,
                hasScheduler = "scheduler" in placeholders,
                hasSeed = "seed" in placeholders,
                hasDenoise = "denoise" in placeholders,
                hasBatchSize = "batch_size" in placeholders,
                hasUpscaleMethod = "upscale_method" in placeholders,
                hasScaleBy = "scale_by" in placeholders,
                hasStopAtClipLayer = "stop_at_clip_layer" in placeholders,
                hasAspectRatio = "aspect_ratio" in placeholders,
                hasSpeedMode = "speed_mode" in placeholders,

                // Model dropdowns
                hasCheckpointName = "ckpt_name" in placeholders,
                hasUnetName = "unet_name" in placeholders,
                hasHighnoiseUnetName = "highnoise_unet_name" in placeholders,
                hasLownoiseUnetName = "lownoise_unet_name" in placeholders,
                hasVaeName = "vae_name" in placeholders,
                hasClipName = "clip_name" in placeholders,
                hasClipName1 = "clip_name1" in placeholders,
                hasClipName2 = "clip_name2" in placeholders,
                hasClipName3 = "clip_name3" in placeholders,
                hasClipName4 = "clip_name4" in placeholders,
                hasTextEncoderName = "text_encoder_name" in placeholders,
                hasLatentUpscaleModel = "latent_upscale_model" in placeholders,
                hasUpscaleModelName = "upscale_model" in placeholders,

                // Mandatory LoRA dropdowns
                hasLoraName = !suppressLora && "lora_name" in placeholders,
                hasHighnoiseLoraName = "highnoise_lora_name" in placeholders,
                hasLownoiseLoraName = "lownoise_lora_name" in placeholders,
                hasLoraStrength = !suppressLora && "lora_strength" in placeholders,

                // LoRA injection chains (triggered by model loader placeholders)
                hasLora = !suppressLora && ("ckpt_name" in placeholders || "unet_name" in placeholders),
                hasHighnoiseLora = "highnoise_unet_name" in placeholders,
                hasLownoiseLora = "lownoise_unet_name" in placeholders,

                // 图片输入位：由调用方传入识别结果（不再从占位符集合推断）
                imageSlots = imageSlots,

                // 流类型：UI 用它判断"3D 分段该不该出现"（见字段注释）
                workflowType = workflowType
            )
        }

        /**
         * 工作流里是否真有 LoRA 节点（`class_type` 含 `LoraLoader`：LoraLoader /
         * LoraLoaderModelOnly / 各模型家族变体都命中）。占位符推断只看"模型加载器"，
         * 3D 流的 unet_name 会让注入链误显示；真实节点扫描才是"面板有活干"的判据。
         * JSON 缺失或解析失败一律返回 false（保守：3D 流宁可隐藏也不空转）。
         */
        fun hasLoraNodes(workflowJson: String?): Boolean {
            if (workflowJson.isNullOrEmpty()) return false
            return try {
                val nodes = JSONObject(workflowJson).optJSONObject("nodes") ?: return false
                nodes.keys().asSequence().any { id ->
                    nodes.optJSONObject(id)?.optString("class_type", "").orEmpty().contains("LoraLoader")
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
