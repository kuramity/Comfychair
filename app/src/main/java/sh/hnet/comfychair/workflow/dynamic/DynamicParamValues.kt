package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject

/**
 * JSON 字符串字面量转义（不含外层引号）。
 *
 * 与 [sh.hnet.comfychair.WorkflowManager.escapeForJson] 是同一份实现——后者委托到这里，
 * 避免两处各写一套导致行为分叉。
 */
internal fun escapeJsonString(input: String): String {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\b", "\\b")
        .replace("\u000C", "\\f")
}

/**
 * 取值与替换（纯逻辑，可 JVM 单测）。
 */
object DynamicParamValues {

    /** 匹配 `"{{name}}"`（整体值形态）或裸 `{{name}}`（嵌在更大字符串里） */
    private val TOKEN_REGEX = Regex("\"\\{\\{(\\w+)\\}\\}\"|\\{\\{(\\w+)\\}\\}")

    // ---------------------------------------------------------------- 取值

    /**
     * 取值顺序（设计约束 5）：
     * `已有保存值 → defaults.<占位符名> → 节点定义的 default → 空`
     *
     * @param savedValue 用户已保存的值（null / 空串视为没有）
     * @param defaultsJson 工作流 JSON 里 `defaults` 那个对象（以**占位符名**为键，如 `"my_steps": 30`）
     * @param control 控件描述，提供节点定义的 default
     * @return 字符串形态的值（数字也转成字符串，替换时再按控件类型还原成 JSON 字面量）
     */
    fun resolveValue(
        control: DynamicParamControl,
        savedValue: String?,
        defaultsJson: JSONObject?,
        placeholder: String
    ): String {
        savedValue?.takeIf { it.isNotEmpty() }?.let { return it }
        defaultsJson?.opt(placeholder)?.let { raw -> asDisplayString(raw)?.let { return it } }
        control.default?.let { raw -> asDisplayString(raw)?.let { return it } }
        return ""
    }

    /** 批量取值。只返回参数清单里出现过的占位符。 */
    fun resolveAll(
        params: List<DynamicParam>,
        savedValues: Map<String, String>,
        defaultsJson: JSONObject?
    ): List<DynamicParamValue> {
        return params.map { param ->
            DynamicParamValue(
                param = param,
                value = resolveValue(
                    control = param.control,
                    savedValue = savedValues[param.placeholder],
                    defaultsJson = defaultsJson,
                    placeholder = param.placeholder
                )
            )
        }
    }

    /**
     * 把服务器定义里的任意 JSON 值转成字符串形态。布尔转 `"true"` / `"false"`，
     * 数字丢掉多余的 `.0`（`30.0` → `"30"`），其余 `toString()`。
     */
    fun asDisplayString(raw: Any?): String? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is Boolean -> raw.toString()
            is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong().toString() else raw.toString()
            is Float -> asDisplayString(raw.toDouble())
            else -> raw.toString()
        }
    }

    // ---------------------------------------------------------------- 替换

    /**
     * 把工作流 JSON 里的占位符统一替换成带类型的 JSON 字面量。
     *
     * - **整体值形态** `"{{my_steps}}"` → `30`（数字控件）、`true`（开关）、`"文本"`（文本/下拉）
     * - **嵌入形态** `"out_{{my_text}}.png"` 里的 `{{my_text}}` → 转义后的裸文本
     *
     * 单趟正则扫描完成，避免「第一遍插进去的值被第二遍再替换一次」的连锁替换。
     * 值无法表示成该控件类型的字面量时（如数字控件拿到空串）**保留占位符不替换**，
     * 让服务器报出明确的校验错误，而不是塞一个错值进去。
     *
     * **数字控件在写出字面量的这一刻会做范围夹紧**（见 [DynamicParamRange]）：
     * 服务器 `validate_inputs()` 沿全部连线递归校验，越界值（哪怕是挂在"已关掉"的分支上的
     * 补采轮次，如 `hires_steps1 = 0`）会让整单 400 `value_smaller_than_min`。
     * 夹紧只影响**发出去的值**，不改 UiState 里用户输入的内容（界面另有即时提示）。
     */
    fun applyDynamicParams(json: String, values: List<DynamicParamValue>): String {
        if (values.isEmpty()) return json
        val byPlaceholder = values.associateBy { it.param.placeholder }

        return TOKEN_REGEX.replace(json) { match ->
            val isQuoted = match.groupValues[1].isNotEmpty()
            val name = if (isQuoted) match.groupValues[1] else match.groupValues[2]
            val entry = byPlaceholder[name] ?: return@replace match.value
            val literal = if (isQuoted) {
                toJsonLiteral(entry.value, entry.param.control)
            } else {
                toBareLiteral(entry.value, entry.param.control)
            }
            literal ?: match.value
        }
    }

    /**
     * 整体值形态要替换成的 JSON 字面量（含引号）。无法表示时返回 null。
     */
    fun toJsonLiteral(value: String, control: DynamicParamControl): String? {
        val text = value.trim()
        return when (control) {
            is DynamicParamControl.NumberField -> numberLiteral(text, control)
            is DynamicParamControl.BoolField -> boolLiteral(text)
            is DynamicParamControl.TextField -> "\"${escapeJsonString(value)}\""
            is DynamicParamControl.ComboField -> comboLiteral(value, control)
        }
    }

    /**
     * 嵌入形态要替换成的裸文本（不含引号，因为它落在已有的 JSON 字符串内部）。
     */
    fun toBareLiteral(value: String, control: DynamicParamControl): String? {
        val text = value.trim()
        return when (control) {
            is DynamicParamControl.NumberField -> numberLiteral(text, control)
            is DynamicParamControl.BoolField -> boolLiteral(text)
            is DynamicParamControl.TextField -> escapeJsonString(value)
            is DynamicParamControl.ComboField -> comboLiteral(value, control)
        }
    }

    /**
     * 下拉字面量。**值为空时保留占位符不替换**（与数字/布尔的"响亮失败"一致）。
     *
     * 下拉的空值不可能是合法选项（服务器 `default=null` 时用户还没选，如 `KSampler.sampler_name`），
     * 若照样发 `""`，服务器只回一句 `Value not in list` 而不说是哪个参数；
     * 保留 `{{名字}}` 则错误信息里直接带着占位符名，用户一眼知道该填哪一项。
     * 选项里真的含空串时按原值发出。
     */
    private fun comboLiteral(value: String, control: DynamicParamControl.ComboField): String? {
        if (value.isEmpty() && "" !in control.options) return null
        return "\"${escapeJsonString(value)}\""
    }

    /**
     * 数字字面量：原样保留用户输入（只要它能解析成数字），避免重新格式化带来的精度/形态变化。
     * 整数控件额外要求没有小数部分，否则视为不可表示。
     *
     * 越界（`< min` / `> max`）的值**先夹到最近的合法值再发**（[DynamicParamRange]）——
     * 夹紧后的字符串形态与数值严格可逆，不会因为格式化又把值弄出界。
     */
    private fun numberLiteral(text: String, control: DynamicParamControl.NumberField): String? {
        val clamped = DynamicParamRange.clampForSubmit(text, control)
        if (clamped.isEmpty()) return null
        val parsed = clamped.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        if (control.isInteger && parsed != Math.floor(parsed)) return null
        return clamped
    }

    /** 布尔字面量：接受 true/false（大小写不敏感）与 1/0 */
    private fun boolLiteral(text: String): String? {
        return when (text.lowercase()) {
            "true", "1" -> "true"
            "false", "0" -> "false"
            else -> null
        }
    }
}
