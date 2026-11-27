package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * 占位符替换统一管线（B3 双通道合一）。
 *
 * 历史上替换机制存在两份平行实现：
 * - WorkflowManager 通道：**文本级** `String.replace`（[PlaceholderLiterals] 处理引号/数值字面量）；
 * - feature-tools 通道：**结构级** JSON 树遍历（只改字符串值，转义交给序列化）。
 *
 * v4.23.1 的线上 400 正是「新增占位符只改了一条通道」的结构性事故。本对象把两种
 * 语义收进同一个入口：替换**机制**只有这一份，调用方按场景选 API；替换**编排**
 * （谁在什么时候换什么值）仍由各入口持有——见 B3 计划的 Architecture 裁决。
 *
 * 注意：本管线只做「占位符 → 值」的纯替换。涉及**结构操作**的占位符不在其管辖内：
 * `{{lora_name}}`（空值删节点重连）、图位/音频注入器（injectImageSlots/injectAudioSlots）、
 * 门控物化器（ClipGate/PeGate）——这些继续由 WorkflowManager 与各 Materializer 负责。
 */
object PlaceholderPipeline {

    /**
     * 文本级替换（原 WorkflowManager 通道语义）。
     *
     * 对整段 JSON 文本按 [values] 顺序逐项替换；值类型决定字面量策略：
     * - [Number]（Int/Long/Float/Double）→ [PlaceholderLiterals.number]（吃掉模板引号产出 JSON 数字，
     *   防 `"steps": "30"` 被 KSampler INT 校验拒绝的历史坑）；
     * - [String] → [PlaceholderLiterals.string]（自带引号 + JSON 转义）；
     * - null → 跳过（调用方以 null 表达「本次不替换该占位符」）。
     *
     * 顺序敏感：调用方按「先派生值、后用户值」排列，防止用户正文里的 `{{xxx}}` 被二次替换。
     */
    fun substituteText(json: String, values: List<Pair<String, Any?>>): String {
        var result = json
        for ((key, value) in values) {
            if (value == null) continue
            val placeholder = "{{$key}}"
            result = when (value) {
                is Number -> PlaceholderLiterals.number(result, placeholder, value.toString())
                else -> PlaceholderLiterals.string(result, placeholder, value.toString())
            }
        }
        return result
    }

    /**
     * 结构级替换（原 feature-tools 通道语义）。
     *
     * 递归遍历 [root] 树，只对**字符串值**（JSONObject/JSONArray 的字符串叶子）做纯文本替换，
     * 数值/布尔/连线数组一概不碰；转义不需要——调用方最终经 `JSONObject.toString()` 序列化。
     *
     * 顺序敏感：values 迭代序即替换序，与 [substituteText] 相同的防二次替换约定。
     */
    fun substituteTree(root: JSONObject, values: LinkedHashMap<String, String>) {
        substituteNode(root, values)
    }

    private fun substituteNode(node: Any?, values: Map<String, String>) {
        when (node) {
            is JSONObject -> {
                for (key in node.keys().asSequence().toList()) {
                    val value = node.opt(key)
                    if (value is String) {
                        node.put(key, replaceAll(value, values))
                    } else if (value is JSONObject || value is JSONArray) {
                        substituteNode(value, values)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val value = node.opt(index)
                    if (value is String) {
                        node.put(index, replaceAll(value, values))
                    } else if (value is JSONObject || value is JSONArray) {
                        substituteNode(value, values)
                    }
                }
            }
        }
    }

    /** 单个字符串值内的全占位符替换（无引号策略——结构级场景转义由序列化统一处理）。 */
    private fun replaceAll(text: String, values: Map<String, String>): String {
        if (!text.contains("{{")) return text
        var result = text
        for ((key, value) in values) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
