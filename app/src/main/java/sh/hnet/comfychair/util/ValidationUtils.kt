package sh.hnet.comfychair.util

import android.content.Context
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ModelCache

/**
 * Centralized validation utilities for generation parameters.
 * Reduces duplicate validation logic across ViewModels.
 */
object ValidationUtils {

    /**
     * Validate dimension value (width/height) for image generation.
     * @param value The dimension value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 4096)
     * @return Error message if invalid, null if valid
     */
    fun validateDimension(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 4096
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_dimension_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate steps value for generation.
     * @param value The steps value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 255)
     * @return Error message if invalid, null if valid
     */
    fun validateSteps(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 255
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_steps_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate CFG (Classifier-Free Guidance) value.
     * @param value The CFG value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.0)
     * @param max Maximum allowed value (default 100.0)
     * @return Error message if invalid, null if valid
     */
    fun validateCfg(
        value: String,
        context: Context? = null,
        min: Float = 0.0f,
        max: Float = 100.0f
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in min..max) {
            context?.getString(R.string.error_cfg_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate megapixels value for image-to-image generation.
     * @param value The megapixels value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.1)
     * @param max Maximum allowed value (default 8.3)
     * @return Error message if invalid, null if valid
     */
    fun validateMegapixels(
        value: String,
        context: Context? = null,
        min: Float = 0.1f,
        max: Float = 8.3f
    ): String? {
        val floatValue = value.toFloatOrNull()
            ?: return context?.getString(R.string.error_invalid_number) ?: "Invalid number"
        return if (floatValue !in min..max) {
            context?.getString(R.string.error_megapixels_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate video length in frames.
     * @param value The length value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 500)
     * @return Error message if invalid, null if valid
     */
    fun validateLength(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 500
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_length_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate frame rate value.
     * @param value The frame rate value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 120)
     * @return Error message if invalid, null if valid
     */
    fun validateFrameRate(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 120
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_fps_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate model selection against available models.
     * Returns current if valid and exists in available list, otherwise empty.
     *
     * P1-7：**候选列表为空时保留原值**。
     *
     * 空列表与"这个值不存在于列表里"不是一回事：
     * - 列表为空只说明这一类候选当前不可用 —— 缓存未加载、断线重连把缓存清空、
     *   或该工作流根本不用这一类模型；
     * - 把它当成"选择无效"清空，会让用户在断线一次之后丢失已保存的模型/LoRA 选择，
     *   而且下一次保存会把空串写回磁盘，重启也找不回来。
     * 所以空列表一律原样返回，等服务端给出真实候选再校验。
     *
     * @param current The currently selected model
     * @param available List of available models
     * @return Valid model selection or empty string
     */
    fun validateModelSelection(current: String, available: List<String>): String {
        if (current.isEmpty()) return ""
        // 空候选 ≠ 无效选择：保留原值（理由见上）
        if (available.isEmpty()) return current
        return if (available.contains(current)) current else ""
    }

    /**
     * P1-7 守卫：模型缓存快照是否足以用于"重新校验已选模型"。
     *
     * `ConnectionManager.invalidateAll()`（断线 / 重连 / 退出登录）会把 `ModelCache`
     * 整体替换成空对象（isLoaded=false、所有列表为空）。此时如果照常跑各 ViewModel
     * 的 `modelCache.collect`，会把所有已选模型清空、把 deferred 恢复值一并抹掉，
     * 随后落盘 —— 用户的模型选择就这么没了。
     *
     * 判定沿用 `MainContainerActivity.onResume` 里"要不要补拉 /object_info"的口径
     * （未加载完、或核心模型列表全空 → 视为不可用），并把判定范围放宽到全部模型列表：
     * 只要服务器真的给出了任何一类候选，就让校验照常进行。
     *
     * @return true = 可以据此刷新已选模型；false = 跳过本次更新，保留现有选择与 deferred 值
     */
    fun isUsableModelCache(cache: ModelCache): Boolean {
        if (!cache.isLoaded) return false
        return cache.checkpoints.isNotEmpty() ||
            cache.unets.isNotEmpty() ||
            cache.vaes.isNotEmpty() ||
            cache.clips.isNotEmpty() ||
            cache.loras.isNotEmpty() ||
            cache.textEncoders.isNotEmpty() ||
            cache.latentUpscaleModels.isNotEmpty() ||
            cache.upscaleModels.isNotEmpty()
    }

    /**
     * CLIP 候选的「格式门控」（工作流里带 `clip_gguf` 开关时生效）。
     *
     * 这类工作流同时挂了原生 `CLIPLoader`（只吃 .safetensors）与 `CLIPLoaderGGUF`（只吃 .gguf），
     * 两者互斥——实测：GGUF 节点加载 fp8 会形状错乱，原生节点的候选里压根没有 .gguf。
     *
     * **候选不过滤**：目录本身就是认路的路标。早先按格式过滤掉整个列表项，结果只含 .gguf
     * 的目录（如 Z-Image）在关掉开关时连文件夹都不显示了，用户在列表里找不到路（实测反馈）。
     * 现在一律显示全部候选，格式是否配套由服务器校验兜底（配错会明确报 Value not in list）。
     *
     * @param values 动态参数当前值（键为占位符名）；本函数已不再使用，仅为调用方签名稳定保留
     */
    fun filterClipsByGate(clips: List<String>, @Suppress("UNUSED_PARAMETER") values: Map<String, String>): List<String> {
        return clips
    }

    /**
     * 门控切换后为新列表挑一个合法选中值：原值还在就保留，否则落到第一项（避免空框）。
     * 列表为空时返回空串。
     *
     * 注意这里**不能**直接用 [validateModelSelection] 的空列表语义（P1-7 让它"保留原值"）：
     * 那是为了断线清空缓存时保住用户选择；而门控切换给出空列表 = 当前开关下确实没有候选，
     * 此时保留一个不在候选里的值会让下拉框显示一个选不中的幽灵项。所以空列表仍返回空串。
     */
    fun pickClipsAfterGate(current: String, clips: List<String>): String {
        if (clips.isEmpty()) return ""
        return validateModelSelection(current, clips).ifEmpty { clips.first() }
    }

    /**
     * Validate seed value for generation.
     * @param value The seed value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0)
     * @return Error message if invalid, null if valid
     */
    fun validateSeed(
        value: String,
        context: Context? = null,
        min: Long = 0
    ): String? {
        if (value.isEmpty()) return null
        val longValue = value.toLongOrNull()
        return if (longValue == null || longValue < min) {
            context?.getString(R.string.error_seed_range) ?: "Must be $min or greater"
        } else null
    }

    /**
     * Validate denoise value for generation.
     * @param value The denoise value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.0)
     * @param max Maximum allowed value (default 1.0)
     * @return Error message if invalid, null if valid
     */
    fun validateDenoise(
        value: String,
        context: Context? = null,
        min: Float = 0.0f,
        max: Float = 1.0f
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in min..max) {
            context?.getString(R.string.error_denoise_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate batch size value for generation.
     * @param value The batch size value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 64)
     * @return Error message if invalid, null if valid
     */
    fun validateBatchSize(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 64
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_batch_size_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate scale by value for upscaling.
     * @param value The scale by value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.01)
     * @param max Maximum allowed value (default 8.0)
     * @return Error message if invalid, null if valid
     */
    fun validateScaleBy(
        value: String,
        context: Context? = null,
        min: Float = 0.01f,
        max: Float = 8.0f
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in min..max) {
            context?.getString(R.string.error_scale_by_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate stop at CLIP layer value.
     * @param value The layer value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default -24)
     * @param max Maximum allowed value (default -1)
     * @return Error message if invalid, null if valid
     */
    fun validateStopAtClipLayer(
        value: String,
        context: Context? = null,
        min: Int = -24,
        max: Int = -1
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_clip_layer_range) ?: "Must be $min to $max"
        } else null
    }

    // ===========================================
    // Node Attribute Editor Validation Functions
    // ===========================================

    /**
     * Validate an integer value with min/max constraints.
     * @param value The value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (nullable = no limit)
     * @param max Maximum allowed value (nullable = no limit)
     * @return Error message if invalid, null if valid
     */
    fun validateInt(
        value: String,
        context: Context? = null,
        min: Int? = null,
        max: Int? = null
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
            ?: return context?.getString(R.string.error_invalid_integer) ?: "Must be a whole number"

        if (min != null && intValue < min) {
            return context?.getString(R.string.error_value_min, min) ?: "Minimum: $min"
        }
        if (max != null && intValue > max) {
            return context?.getString(R.string.error_value_max, max) ?: "Maximum: $max"
        }
        return null
    }

    /**
     * Validate a float/double value with min/max constraints.
     * @param value The value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (nullable = no limit)
     * @param max Maximum allowed value (nullable = no limit)
     * @return Error message if invalid, null if valid
     */
    fun validateFloat(
        value: String,
        context: Context? = null,
        min: Float? = null,
        max: Float? = null
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
            ?: return context?.getString(R.string.error_invalid_number) ?: "Must be a number"

        // NaN / ±Infinity 会被 Kotlin 的 toFloatOrNull 正常解析（"NaN"、"Infinity"、"-Infinity"），
        // 而它们与 min/max 的比较**永远为 false**，于是 min/max 两处范围检查全部形同虚设。
        // 这类值一旦写进工作流 JSON，org.json 会抛 "Forbidden numeric value: NaN"，
        // 或者在服务端被当成合法数字提交；必须在输入校验这一层就拒掉。
        if (!floatValue.isFinite()) {
            return context?.getString(R.string.error_invalid_number) ?: "Must be a number"
        }

        if (min != null && floatValue < min) {
            return context?.getString(R.string.error_value_min_float, min) ?: "Minimum: $min"
        }
        if (max != null && floatValue > max) {
            return context?.getString(R.string.error_value_max_float, max) ?: "Maximum: $max"
        }
        return null
    }

    /**
     * Validate a value against a list of allowed options.
     * @param value The value to validate
     * @param options List of valid options
     * @param context Optional context for localized error message
     * @return Error message if invalid, null if valid
     */
    fun validateEnum(
        value: String,
        options: List<String>,
        context: Context? = null
    ): String? {
        if (value.isEmpty()) return null
        return if (value !in options) {
            context?.getString(R.string.error_invalid_option) ?: "Invalid option"
        } else null
    }

    /**
     * Validate a string value with max length constraint.
     * @param value The value to validate
     * @param maxLength Maximum allowed length (default 10000)
     * @param context Optional context for localized error message
     * @return Error message if invalid, null if valid
     */
    fun validateString(
        value: String,
        maxLength: Int = 10000,
        context: Context? = null
    ): String? {
        return if (value.length > maxLength) {
            context?.getString(R.string.error_string_too_long, maxLength)
                ?: "Maximum length: $maxLength"
        } else null
    }

    // ===========================================
    // Workflow Name/Description Validation
    // ===========================================

    /** Maximum length for workflow names */
    const val MAX_WORKFLOW_NAME_LENGTH = 40

    /** Maximum length for workflow descriptions */
    const val MAX_WORKFLOW_DESCRIPTION_LENGTH = 80

    /**
     * Validate workflow name format.
     * No character restrictions (matches server name validation approach).
     * Filename generation sanitizes names for filesystem safety.
     *
     * @param name The workflow name to validate
     * @param context Optional context for localized error messages
     * @return Error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String, context: Context? = null): String? {
        if (name.isBlank()) {
            return context?.getString(R.string.error_required) ?: "Required"
        }
        if (name.length > MAX_WORKFLOW_NAME_LENGTH) {
            return context?.getString(R.string.error_workflow_name_too_long)
                ?: "Name too long (max $MAX_WORKFLOW_NAME_LENGTH characters)"
        }
        return null
    }

    /**
     * Validate workflow description format.
     * @param description The workflow description to validate
     * @param context Optional context for localized error messages
     * @return Error message if invalid, null if valid
     */
    fun validateWorkflowDescription(description: String, context: Context? = null): String? {
        if (description.length > MAX_WORKFLOW_DESCRIPTION_LENGTH) {
            return context?.getString(R.string.error_workflow_description_too_long)
                ?: "Description too long (max $MAX_WORKFLOW_DESCRIPTION_LENGTH characters)"
        }
        return null
    }

    /**
     * Truncate workflow name to maximum allowed length.
     */
    fun truncateWorkflowName(name: String): String = name.take(MAX_WORKFLOW_NAME_LENGTH)

    /**
     * Truncate workflow description to maximum allowed length.
     */
    fun truncateWorkflowDescription(description: String): String = description.take(MAX_WORKFLOW_DESCRIPTION_LENGTH)
}
