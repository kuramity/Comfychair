package sh.hnet.comfychair.storage

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.util.ServerUrlUtils
import sh.hnet.comfychair.util.ValidationUtils

/**
 * Validation utilities for backup/restore operations.
 * Validates JSON structure, field values, and sanitizes strings.
 */
class BackupValidator {

    companion object {
        // Regex patterns from LoginScreen
        private val IP_ADDRESS_PATTERN = Regex(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
            "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
        )
        private val HOSTNAME_PATTERN = Regex(
            "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*" +
            "([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$"
        )

        /** 控制字符（保留 \n \t）——sanitizeString 每次调用都要用，提升为常量避免重复编译 */
        private val CONTROL_CHARS_PATTERN = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

        /** 节点 id / 输入名允许的字符集——原先在双层 while 循环里逐个现场编译 */
        private val IDENTIFIER_PATTERN = Regex("^[a-zA-Z0-9_]+$")

        // Valid config modes for Image-to-Image
        private val VALID_CONFIG_MODES = setOf("CHECKPOINT", "UNET")

        // Max lengths for strings
        const val MAX_PROMPT_LENGTH = 10000
        val MAX_WORKFLOW_NAME_LENGTH = ValidationUtils.MAX_WORKFLOW_NAME_LENGTH
        val MAX_WORKFLOW_DESCRIPTION_LENGTH = ValidationUtils.MAX_WORKFLOW_DESCRIPTION_LENGTH

        // Numeric ranges
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 4096
        const val MIN_STEPS = 1
        const val MAX_STEPS = 255
        const val MIN_CFG = 0.0f
        const val MAX_CFG = 100.0f
        const val MIN_MEGAPIXELS = 0.1f
        const val MAX_MEGAPIXELS = 8.3f
        const val MIN_LENGTH = 1
        const val MAX_LENGTH = 129
        const val MIN_FRAME_RATE = 1
        const val MAX_FRAME_RATE = 120

        // Advanced generation parameters
        const val MIN_SEED = 0L
        const val MAX_SEED = Long.MAX_VALUE
        const val MIN_DENOISE = 0.0f
        const val MAX_DENOISE = 1.0f
        const val MIN_BATCH_SIZE = 1
        const val MAX_BATCH_SIZE = 16
        const val MIN_SCALE_BY = 0.1f
        const val MAX_SCALE_BY = 8.0f
        const val MIN_STOP_AT_CLIP_LAYER = -24
        const val MAX_STOP_AT_CLIP_LAYER = 0

        // Prompt preset validation
        const val MAX_PRESET_NAME_LENGTH = 100
        const val MAX_TAG_LENGTH = 50
        const val MAX_TAGS_PER_PRESET = 20
    }

    /**
     * Validate overall backup JSON structure.
     * Returns true if the structure has all required top-level keys.
     */
    fun validateStructure(json: JSONObject): Boolean {
        // Version is required
        if (!json.has("version")) return false

        // Version must be a valid number
        val version = json.optInt("version", -1)
        if (version < 1) return false

        // Servers array is required
        val servers = json.optJSONArray("servers")
        if (servers == null || servers.length() == 0) return false

        return true
    }

    /**
     * Validate hostname format (IP address or hostname).
     *
     * P2-24：备份里的地址可能带着用户粘贴时残留的 `http://` / `https://` 前缀，
     * 先按 [ServerUrlUtils.splitProtocol] 剥掉再校验，否则合法条目会被误判成非法。
     */
    fun validateHostname(hostname: String): Boolean {
        val bare = ServerUrlUtils.splitProtocol(hostname).second
        if (bare.isBlank()) return false
        // 形如 IPv4（全是数字和点，且含点）却不是一个合法 IPv4：192.168.1 / 999.1.1.1
        // 这类「缺段/越界」的地址不能靠主机名正则蒙混过关（P2-24）
        val looksLikeIpv4 = bare.contains('.') && bare.all { it.isDigit() || it == '.' }
        if (looksLikeIpv4) return IP_ADDRESS_PATTERN.matches(bare)
        return HOSTNAME_PATTERN.matches(bare)
    }

    /**
     * Validate port number is in valid range.
     */
    fun validatePort(port: Int): Boolean {
        return port in MIN_PORT..MAX_PORT
    }

    /**
     * 备份里的服务器条目是否可用（P2-24）。
     *
     * 恢复时逐台调用：hostname 格式不合法或端口越界即整条跳过，不写进服务器列表 ——
     * 修复前 `Server.fromJson` 只保证「非空、端口 > 0」，畸形地址（含空格、带路径、
     * 端口 70000）会被原样恢复，后续连接/展示都会出问题。这两个校验函数原先是死代码。
     */
    fun validateServer(hostname: String, port: Int): Boolean =
        validateHostname(hostname) && validatePort(port)

    /**
     * Validate workflow type against WorkflowType enum values.
     */
    fun validateWorkflowType(type: String): Boolean {
        return try {
            WorkflowType.valueOf(type)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Validate sampler against allowed list.
     */
    fun validateSampler(sampler: String): Boolean {
        return sampler in SamplerOptions.SAMPLERS
    }

    /**
     * Validate scheduler against allowed list.
     */
    fun validateScheduler(scheduler: String): Boolean {
        return scheduler in SamplerOptions.SCHEDULERS
    }

    /**
     * Validate config mode for Image-to-Image (CHECKPOINT or UNET).
     */
    fun validateConfigMode(mode: String): Boolean {
        return mode.uppercase() in VALID_CONFIG_MODES
    }

    /**
     * Validate dimension (width or height).
     */
    fun validateDimension(value: Int): Boolean {
        return value in MIN_DIMENSION..MAX_DIMENSION
    }

    /**
     * Validate steps value.
     */
    fun validateSteps(value: Int): Boolean {
        return value in MIN_STEPS..MAX_STEPS
    }

    /**
     * Validate CFG value.
     */
    fun validateCfg(value: Float): Boolean {
        return value in MIN_CFG..MAX_CFG
    }

    /**
     * Validate megapixels value.
     */
    fun validateMegapixels(value: Float): Boolean {
        return value in MIN_MEGAPIXELS..MAX_MEGAPIXELS
    }

    /**
     * Validate video length value.
     */
    fun validateLength(value: Int): Boolean {
        return value in MIN_LENGTH..MAX_LENGTH
    }

    /**
     * Validate frame rate value.
     */
    fun validateFrameRate(value: Int): Boolean {
        return value in MIN_FRAME_RATE..MAX_FRAME_RATE
    }

    /**
     * Validate seed value.
     */
    fun validateSeed(value: Long): Boolean {
        return value in MIN_SEED..MAX_SEED
    }

    /**
     * Validate denoise value.
     */
    fun validateDenoise(value: Float): Boolean {
        return value in MIN_DENOISE..MAX_DENOISE
    }

    /**
     * Validate batch size value.
     */
    fun validateBatchSize(value: Int): Boolean {
        return value in MIN_BATCH_SIZE..MAX_BATCH_SIZE
    }

    /**
     * Validate scale by value.
     */
    fun validateScaleBy(value: Float): Boolean {
        return value in MIN_SCALE_BY..MAX_SCALE_BY
    }

    /**
     * Validate stop at clip layer value.
     */
    fun validateStopAtClipLayer(value: Int): Boolean {
        return value in MIN_STOP_AT_CLIP_LAYER..MAX_STOP_AT_CLIP_LAYER
    }

    /**
     * Validate workflow name format.
     * Matches ValidationUtils.validateWorkflowName - no character restrictions,
     * just checks blank and length.
     */
    fun validateWorkflowName(name: String): Boolean {
        return name.isNotBlank() && name.length <= MAX_WORKFLOW_NAME_LENGTH
    }

    /**
     * Validate workflow description.
     */
    fun validateWorkflowDescription(description: String): Boolean {
        return description.length <= MAX_WORKFLOW_DESCRIPTION_LENGTH
    }

    /**
     * Sanitize string input by removing control characters and limiting length.
     */
    fun sanitizeString(input: String, maxLength: Int = MAX_PROMPT_LENGTH): String {
        // Remove control characters except newlines and tabs
        val sanitized = input.replace(CONTROL_CHARS_PATTERN, "")
        // Limit length
        return if (sanitized.length > maxLength) {
            sanitized.substring(0, maxLength)
        } else {
            sanitized
        }
    }

    /**
     * Validate and sanitize a prompt string.
     */
    fun validateAndSanitizePrompt(prompt: String): String? {
        if (prompt.length > MAX_PROMPT_LENGTH * 2) {
            // Way too long, reject entirely
            return null
        }
        return sanitizeString(prompt, MAX_PROMPT_LENGTH)
    }

    /**
     * Validate and sanitize node attribute edits JSON.
     * Structure: {"nodeId": {"inputName": value, ...}, ...}
     * Returns sanitized JSON or null if invalid.
     */
    fun sanitizeNodeAttributeEdits(json: String?): String? {
        if (json.isNullOrBlank()) return null

        return try {
            val root = JSONObject(json)
            val sanitized = JSONObject()

            val nodeIds = root.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()

                // Validate nodeId format (non-empty, alphanumeric + underscore)
                if (nodeId.isBlank() || nodeId.length > 100) continue
                if (!nodeId.matches(IDENTIFIER_PATTERN)) continue

                val nodeEdits = root.optJSONObject(nodeId) ?: continue
                val sanitizedEdits = JSONObject()

                val inputNames = nodeEdits.keys()
                while (inputNames.hasNext()) {
                    val inputName = inputNames.next()

                    // Validate inputName format (non-empty, alphanumeric + underscore)
                    if (inputName.isBlank() || inputName.length > 100) continue
                    if (!inputName.matches(IDENTIFIER_PATTERN)) continue

                    val value = nodeEdits.opt(inputName)
                    if (value == null || value == JSONObject.NULL) continue

                    // Validate and sanitize value based on type
                    when (value) {
                        is String -> {
                            val sanitizedValue = sanitizeString(value, MAX_PROMPT_LENGTH)
                            if (sanitizedValue.isNotEmpty()) {
                                sanitizedEdits.put(inputName, sanitizedValue)
                            }
                        }
                        is Int -> {
                            // Allow any reasonable int range
                            if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                                sanitizedEdits.put(inputName, value)
                            }
                        }
                        is Long -> {
                            // Coerce to int if in range
                            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                sanitizedEdits.put(inputName, value.toInt())
                            }
                        }
                        is Double -> {
                            // Allow reasonable float ranges, reject NaN/Infinity
                            if (!value.isNaN() && !value.isInfinite()) {
                                sanitizedEdits.put(inputName, value)
                            }
                        }
                        is Boolean -> {
                            sanitizedEdits.put(inputName, value)
                        }
                        // Skip other types
                    }
                }

                if (sanitizedEdits.length() > 0) {
                    sanitized.put(nodeId, sanitizedEdits)
                }
            }

            if (sanitized.length() > 0) sanitized.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate preset name format.
     */
    fun validatePresetName(name: String): Boolean {
        return name.isNotBlank() && name.length <= MAX_PRESET_NAME_LENGTH
    }

    /**
     * Validate preset tag format.
     */
    fun validateTag(tag: String): Boolean {
        return tag.isNotBlank() && tag.length <= MAX_TAG_LENGTH
    }

    /**
     * Validate screen type against ScreenType enum values.
     */
    fun validateScreenType(type: String): Boolean {
        return try {
            ScreenType.valueOf(type)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
