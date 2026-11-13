package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single LoRA selection with name and strength.
 * Used in LoRA chain configurations across all workflow types.
 */
@Immutable
data class LoraSelection(
    val name: String,
    val strength: Float = DEFAULT_STRENGTH
) {
    companion object {
        const val MIN_STRENGTH = 0.0f

        /**
         * 权重上限。放在 10.0 是因为滑块要覆盖到「刻意压过曝」这类用法：
         * 上限 2.0 时只能靠拖滑块凑近似值，且滑块一大半行程都落在常用区间之外。
         */
        const val MAX_STRENGTH = 10.0f
        const val DEFAULT_STRENGTH = 1.0f
        const val MAX_CHAIN_LENGTH = 5

        /** 量化精度：两位小数。拖动与手动输入都收敛到这里，显示值才等于提交值。 */
        private const val STRENGTH_SCALE = 100f

        /**
         * 把任意来源的浮点收进合法区间并量化到两位小数。
         *
         * NaN 落到 [DEFAULT_STRENGTH]：[后续注入](LoraInjectionUtils)会把它写进 JSON，
         * NaN 序列化出来是非法字面量，服务器直接 400。
         */
        fun sanitizeStrength(strength: Float): Float {
            if (strength.isNaN()) return DEFAULT_STRENGTH
            val clamped = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
            return Math.round(clamped * STRENGTH_SCALE) / STRENGTH_SCALE
        }

        /** 合法区间判定，含两端。NaN / Infinity 一律不算合法。 */
        fun isStrengthInRange(strength: Float): Boolean =
            strength.isFinite() && strength >= MIN_STRENGTH && strength <= MAX_STRENGTH

        /**
         * 显示用文本：最多两位小数，去掉无意义的尾随零（`1`、`0.5`、`1.25`、`3.74`、`10`）。
         *
         * 滑块右侧的数字与手动输入框的初始文本都用它 —— 同一份值在任何地方长一个样。
         */
        fun formatStrength(strength: Float): String {
            val value = sanitizeStrength(strength)
            if (value == value.toLong().toFloat()) return value.toLong().toString()
            return String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

        /**
         * 解析手动输入的数字文本，量化到两位小数；**不夹紧**。
         *
         * 只回答"这是不是一个数"：非数字、空串、NaN/Infinity 一律 `null`。
         * 越界与否交给 [isStrengthInRange]，对话框才能把「你填了 15」和「你填了 abc」分开提示；
         * 真正的夹紧在提交前由 [sanitizeStrength] 完成。
         */
        fun parseStrength(text: String): Float? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val value = trimmed.toFloatOrNull() ?: return null
            if (!value.isFinite()) return null
            return Math.round(value * STRENGTH_SCALE) / STRENGTH_SCALE
        }

        /**
         * 归一化 LoRA 名里的"未选择"占位值。
         *
         * 旧版本把"不使用 LoRA"序列化成字面量 "None" 存进偏好（也见过 "null" 写法），
         * 加载后既会在下拉里显示成一个不存在的选项，又会在提交时被当成真实 LoRA 名发出去，
         * 服务器直接 400：
         *   Value not in list: lora_name: 'None' not in (list of length 66)
         *
         * 加载（[fromJsonString]）、编辑与注入统一走这里：这些值一律视为空串 = 不使用 LoRA。
         */
        fun normalizeName(name: String?): String {
            val trimmed = name?.trim().orEmpty()
            return if (trimmed.equals("none", ignoreCase = true) ||
                trimmed.equals("null", ignoreCase = true)
            ) {
                ""
            } else {
                trimmed
            }
        }

        /**
         * Deserialize a LoRA chain from JSON array string.
         * Format: [{"name": "lora1.safetensors", "strength": 1.0}, ...]
         *
         * 名为空（或历史遗留的 "None"）的项在链条里没有意义：不注入、不提交，
         * 直接丢弃（等价于"该项不使用 LoRA"）。
         */
        fun fromJsonString(jsonString: String?): List<LoraSelection> {
            if (jsonString.isNullOrBlank()) return emptyList()
            return try {
                val jsonArray = JSONArray(jsonString)
                (0 until jsonArray.length()).mapNotNull { i ->
                    val obj = jsonArray.optJSONObject(i)
                    val name = normalizeName(obj?.optString("name", ""))
                    val strength = obj?.optDouble("strength", DEFAULT_STRENGTH.toDouble())?.toFloat()
                        ?: DEFAULT_STRENGTH
                    if (name.isNotEmpty()) LoraSelection(name, strength) else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Serialize a LoRA chain to JSON array string.
         */
        fun toJsonString(loraChain: List<LoraSelection>): String {
            val jsonArray = JSONArray()
            loraChain.forEach { lora ->
                jsonArray.put(JSONObject().apply {
                    put("name", lora.name)
                    put("strength", lora.strength.toDouble())
                })
            }
            return jsonArray.toString()
        }
    }
}
