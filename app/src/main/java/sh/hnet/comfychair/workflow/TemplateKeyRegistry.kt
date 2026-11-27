package sh.hnet.comfychair.workflow

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType

/**
 * Centralized registry of template keys used in workflow JSON files.
 * Maps placeholder names (e.g., "positive_prompt") to actual JSON input keys (e.g., "text").
 */
object TemplateKeyRegistry {

    /**
     * Map from placeholder name to the JSON input key it targets.
     * Example: "{{positive_prompt}}" placeholder maps to "positive_text" key in CLIPTextEncode inputs
     */
    private val PLACEHOLDER_TO_KEY = mapOf(
        "positive_prompt" to "positive_text",
        "negative_prompt" to "negative_text",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
        "clip_name1" to "clip_name1",
        "clip_name2" to "clip_name2",
        "clip_name3" to "clip_name3",
        "clip_name4" to "clip_name4",
        "width" to "width",
        "height" to "height",
        "steps" to "steps",
        "cfg" to "cfg",
        "sampler_name" to "sampler_name",
        "scheduler" to "scheduler",
        "megapixels" to "megapixels",
        "highnoise_unet_name" to "unet_name",
        "lownoise_unet_name" to "unet_name",
        "highnoise_lora_name" to "lora_name",
        "lownoise_lora_name" to "lora_name",
        "length" to "length",
        "frame_rate" to "fps",
        "image_filename" to "image",
        // 编号参考图位同样落在 LoadImage.image 上（N 从 1 起，与 image_filename 同组）
        "reference_image_1" to "image",
        "reference_image_2" to "image",
        "seed" to "seed",
        "denoise" to "denoise",
        "batch_size" to "batch_size",
        "upscale_method" to "upscale_method",
        "scale_by" to "scale_by",
        "stop_at_clip_layer" to "stop_at_clip_layer",
        "text_encoder_name" to "text_encoder",
        "latent_upscale_model" to "model_name",
        "upscale_model" to "model_name",
        // 视频流的可调参数：speed_mode 的节点输入键叫 mode，两者不同名必须登记。
        // 注意：反向表（KEY_TO_PLACEHOLDER）【不】登记 mode —— 服务器上有 140+ 个节点
        // 都有名为 mode 的输入，反向映射会把它们全部误推成 speed_mode。
        "aspect_ratio" to "aspect_ratio",
        "speed_mode" to "mode",
        // 视频流的主 LoRA 权重：占位符叫 lora_strength，节点输入键叫 strength_model，两者不同名必须登记。
        // 注意：反向表（KEY_TO_PLACEHOLDER）【不】登记 strength_model —— 它是 LoraLoader /
        // LoraLoaderModelOnly 家族的通用字段名，且与 lownoise_lora_name 的 lora_name 类似，
        // 反向映射会把别的节点输入误推成 lora_strength。
        "lora_strength" to "strength_model"
    )

    /**
     * Map from JSON input key to the primary placeholder name.
     * Note: Some keys have multiple placeholders (e.g., "unet_name" can be "unet_name",
     * "highnoise_unet_name", or "lownoise_unet_name"). This map provides the primary/canonical
     * placeholder name for each input key.
     */
    private val KEY_TO_PLACEHOLDER = mapOf(
        "positive_text" to "positive_prompt",
        "negative_text" to "negative_prompt",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
        "clip_name1" to "clip_name1",
        "clip_name2" to "clip_name2",
        "clip_name3" to "clip_name3",
        "clip_name4" to "clip_name4",
        "width" to "width",
        "height" to "height",
        "steps" to "steps",
        "cfg" to "cfg",
        "sampler_name" to "sampler_name",
        "scheduler" to "scheduler",
        "megapixels" to "megapixels",
        "lora_name" to "lora_name",
        "length" to "length",
        "fps" to "frame_rate",
        "image" to "image_filename",
        "seed" to "seed",
        "denoise" to "denoise",
        "batch_size" to "batch_size",
        "upscale_method" to "upscale_method",
        "scale_by" to "scale_by",
        "stop_at_clip_layer" to "stop_at_clip_layer",
        "text_encoder" to "text_encoder_name"
        // Note: "model_name" is used by multiple unrelated node types, skip reverse mapping
    )

    /**
     * All unique input keys that could be template targets
     */
    val ALL_TEMPLATE_KEYS: Set<String> = PLACEHOLDER_TO_KEY.values.toSet()

    /**
     * 所有「已识别」的占位符名 —— 即已有专用逻辑接管、**不属于**通用节点参数引擎的占位符。
     *
     * 动态节点参数引擎（`workflow/dynamic/`）必须跳过这些占位符：它们各自有打磨过的专用 UI
     * 与替换逻辑，若被引擎重复接管，同一个参数会在配置面板里出现两遍。
     * 清单与设计文档约束 1 一致（`docs/superpowers/specs/2026-09-13-dynamic-node-params-design.md`）。
     */
    val RECOGNIZED_PLACEHOLDERS: Set<String> = PLACEHOLDER_TO_KEY.keys + setOf(
        // 主 LoRA 名称：由 replaceLoraPlaceholder() 专用处理，不在 PLACEHOLDER_TO_KEY 里
        "lora_name",
        // 种子别名：replaceCommonPlaceholders() 里与 seed 同源（RandomNoise 等节点用这个键）
        "noise_seed",
        // 参考音频位：由 AudioSlotDetector + WorkflowManager.injectAudioSlots 专用处理，
        // 不登记的话动态参数引擎会把它渲染成一个多余的文本框（界面上出现两遍）
        "audio_filename",
        // 技能流的两个 md 字段：由 WorkflowManager.applySkillPrompt 读 assets 后整体替换，
        // 真正的选择器是配置面板底部的「技能提示词」下拉。不登记的话引擎会把它们当成普通
        // 文本框渲染出来（值就是占位符字面量），用户一滚动面板就被持久化成空串，
        // 提交时先行替换掉占位符 —— applySkillPrompt 从此认不出它、静默注入空内容
        // （实测：队列里 加载文件名 为空、正文 0 字符，模型只能瞎猜）
        "skill_file_name",
        "skill_file_content",
        // 技能流的两个模型字段：与 skill_file_name / skill_file_content 同属技能流，由
        // WorkflowManager.applySkillPrompt 从工作流 defaults 里读取并替换（技能流模板各配一对）
        "skill_model",
        "skill_mmproj"
    )

    /**
     * 编号型占位符（N 可变，无法穷举进上面的 Set），同样视为「已识别」。
     */
    private val RECOGNIZED_PLACEHOLDER_PATTERNS: List<Regex> = listOf(
        Regex("""reference_image_\d+"""),
        Regex("""reference_audio_\d+""")
    )

    /**
     * 判断某个占位符是否已被专用逻辑识别（= 动态参数引擎必须跳过）。
     */
    fun isRecognizedPlaceholder(placeholder: String): Boolean {
        return placeholder in RECOGNIZED_PLACEHOLDERS ||
            RECOGNIZED_PLACEHOLDER_PATTERNS.any { it.matches(placeholder) }
    }

    /**
     * Keys that require graph tracing to find (not direct JSON key lookups).
     * These are excluded from simple key validation but included in field mapping.
     */
    val GRAPH_TRACED_KEYS: Set<String> = setOf("positive_text", "negative_text")

    /**
     * STRICTLY required keys per workflow type - workflow MUST have mappings for these.
     * These are the minimum fields needed for a workflow to function.
     */
    private val REQUIRED_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = mapOf(
        WorkflowType.TTI to setOf("positive_text"),
        WorkflowType.ITI_INPAINTING to setOf("positive_text", "image"),
        WorkflowType.ITI_EDITING to setOf("positive_text", "image"),
        WorkflowType.TTV to setOf("positive_text"),
        WorkflowType.ITV to setOf("positive_text", "image")
    )

    /**
     * Universal optional keys - any field can appear on any workflow type if mapped.
     * Field visibility is determined by WorkflowDefaults capability flags at runtime.
     */
    private val UNIVERSAL_OPTIONAL_KEYS: Set<String> = setOf(
        "negative_text", "ckpt_name", "unet_name", "vae_name",
        "clip_name", "clip_name1", "clip_name2", "clip_name3", "clip_name4",
        "width", "height", "steps", "cfg", "sampler_name", "scheduler",
        "megapixels", "lora_name",
        "highnoise_unet_name", "lownoise_unet_name",
        "highnoise_lora_name", "lownoise_lora_name",
        "length", "fps",
        "seed", "denoise", "batch_size", "upscale_method", "scale_by", "stop_at_clip_layer",
        "text_encoder_name", "latent_upscale_model", "upscale_model",
        // 参考图位（工作流编辑器里可映射到 LoadImage 节点）
        "reference_image_1", "reference_image_2"
    )

    /**
     * Optional keys per workflow type - now universal for all types.
     * Field visibility is controlled by WorkflowDefaults at runtime.
     */
    private val OPTIONAL_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = mapOf(
        WorkflowType.TTI to UNIVERSAL_OPTIONAL_KEYS,
        WorkflowType.ITI_INPAINTING to UNIVERSAL_OPTIONAL_KEYS,
        WorkflowType.ITI_EDITING to UNIVERSAL_OPTIONAL_KEYS,
        WorkflowType.TTV to UNIVERSAL_OPTIONAL_KEYS,
        WorkflowType.ITV to UNIVERSAL_OPTIONAL_KEYS
    )

    /**
     * All keys per workflow type (required + optional) - for backwards compatibility.
     */
    private val ALL_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = WorkflowType.entries.associateWith { type ->
        (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()) + (OPTIONAL_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Get the set of STRICTLY required keys for simple key validation (excludes graph-traced keys).
     */
    fun getDirectKeysForType(type: WorkflowType): Set<String> {
        return (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()) - GRAPH_TRACED_KEYS
    }

    /**
     * Get the set of STRICTLY required input keys for a workflow type.
     */
    fun getRequiredKeysForType(type: WorkflowType): Set<String> {
        return REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get the set of optional input keys for a workflow type.
     */
    fun getOptionalKeysForType(type: WorkflowType): Set<String> {
        return OPTIONAL_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get all keys (required + optional) for a workflow type.
     */
    fun getAllKeysForType(type: WorkflowType): Set<String> {
        return ALL_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get the set of all input keys for a workflow type (backwards compatibility alias).
     */
    fun getKeysForType(type: WorkflowType): Set<String> {
        return getAllKeysForType(type)
    }

    /**
     * Get the actual JSON input key for a placeholder name.
     * Example: getJsonKeyForPlaceholder("highnoise_unet_name") returns "unet_name"
     * Returns the placeholder name itself if no mapping exists.
     */
    fun getJsonKeyForPlaceholder(placeholder: String): String {
        return PLACEHOLDER_TO_KEY[placeholder] ?: placeholder
    }

    /**
     * Check if an input key is a known template key
     */
    fun isTemplateKey(key: String): Boolean {
        return key in ALL_TEMPLATE_KEYS
    }

    /**
     * Check if an input key is known for a specific workflow type (required or optional)
     */
    fun isTemplateKeyForType(key: String, type: WorkflowType): Boolean {
        return key in (ALL_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Check if an input key is STRICTLY required for a specific workflow type.
     */
    fun isRequiredKeyForType(key: String, type: WorkflowType): Boolean {
        return key in (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Get the placeholder name for a given input key.
     * Returns the input key itself if no mapping exists.
     * Example: getPlaceholderForKey("text") returns "positive_prompt"
     */
    fun getPlaceholderForKey(key: String): String {
        return KEY_TO_PLACEHOLDER[key] ?: key
    }

    /**
     * Analyze workflow JSON and determine actual STRICTLY required keys based on workflow structure.
     * This returns only the minimum required fields for a workflow to function.
     */
    fun getStrictlyRequiredKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        val base = REQUIRED_KEYS_BY_TYPE[workflowType] ?: return emptySet()

        // For strictly required keys, we don't need to adjust for DualCLIPLoader or BasicGuider
        // since CLIP, CFG, and negative_text are optional now
        return base
    }

    /**
     * Analyze workflow JSON and determine actual optional keys based on workflow structure.
     * This handles:
     * - BasicGuider (no CFG, no negative prompt) in TTI workflows
     * Note: clip_name* fields are now always included in the base set and visibility
     * is determined at runtime by checking for placeholders in the workflow JSON.
     */
    fun getOptionalKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        val baseKeys = OPTIONAL_KEYS_BY_TYPE[workflowType]?.toMutableSet() ?: return emptySet()

        if (workflowType == WorkflowType.TTI) {
            val nodesJson = workflowJson.optJSONObject("nodes") ?: return baseKeys

            // Check for BasicGuider (no CFG, no negative prompt)
            val hasBasicGuider = nodesJson.keys().asSequence().any { nodeId ->
                nodesJson.optJSONObject(nodeId)?.optString("class_type") == "BasicGuider"
            }
            if (hasBasicGuider) {
                baseKeys.remove("cfg")
                baseKeys.remove("negative_text")
            }
        }

        return baseKeys
    }

    /**
     * Analyze workflow JSON and determine all keys (required + optional) based on workflow structure.
     * This handles Flux-style workflows with DualCLIPLoader and BasicGuider.
     */
    fun getRequiredKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        return getStrictlyRequiredKeysForWorkflow(workflowType, workflowJson) +
               getOptionalKeysForWorkflow(workflowType, workflowJson)
    }

    /**
     * Get direct keys for validation (excludes graph-traced keys), adjusted for workflow structure.
     * Only checks strictly required keys.
     */
    fun getDirectKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        return getStrictlyRequiredKeysForWorkflow(workflowType, workflowJson) - GRAPH_TRACED_KEYS
    }

    // ===========================================
    // Field Mapping Matching Functions
    // ===========================================

    /**
     * Alternative input keys that should also match a field.
     * For example, "seed" field should match both "seed" and "noise_seed" input keys.
     * This supports nodes like RandomNoise that use "noise_seed" instead of "seed".
     */
    private val ALTERNATIVE_INPUT_KEYS: Map<String, Set<String>> = mapOf(
        "seed" to setOf("seed", "noise_seed")
    )

    /**
     * Check if an input key matches a field key.
     * Handles key translation (e.g., "highnoise_unet_name" → "unet_name")
     * and alternative keys (e.g., "seed" matches both "seed" and "noise_seed").
     *
     * @param fieldKey The template field key (e.g., "highnoise_unet_name")
     * @param inputKey The actual input key from the node (e.g., "unet_name")
     * @return true if the input key corresponds to this field
     */
    fun doesInputKeyMatchField(fieldKey: String, inputKey: String): Boolean {
        val expectedJsonKey = getJsonKeyForPlaceholder(fieldKey)
        // Check primary key match
        if (inputKey == expectedJsonKey) return true
        // Check alternative keys
        val alternatives = ALTERNATIVE_INPUT_KEYS[fieldKey]
        return alternatives?.contains(inputKey) == true
    }

    /**
     * Check if an input value matches a field's placeholder pattern.
     * For highnoise/lownoise variants, verifies the specific placeholder is present.
     *
     * @param fieldKey The template field key (e.g., "highnoise_unet_name")
     * @param inputValue The current value of the input (may be "{{highnoise_unet_name}}")
     * @return true if the value matches the expected placeholder pattern
     */
    fun doesValueMatchPlaceholder(fieldKey: String, inputValue: Any?): Boolean {
        // For highnoise/lownoise variants, we need to check the specific placeholder
        // to distinguish between them (both map to the same JSON key like "unet_name")
        return when {
            fieldKey.startsWith("highnoise_") || fieldKey.startsWith("lownoise_") -> {
                val placeholderPattern = "{{$fieldKey}}"
                inputValue?.toString()?.contains(placeholderPattern) ?: false
            }
            // For all other fields, accept any node with the correct JSON input key
            else -> true
        }
    }

    /**
     * Combined check: does this (inputKey, inputValue) pair match the fieldKey?
     */
    fun isFieldMatch(fieldKey: String, inputKey: String, inputValue: Any?): Boolean {
        return doesInputKeyMatchField(fieldKey, inputKey) &&
               doesValueMatchPlaceholder(fieldKey, inputValue)
    }
}
