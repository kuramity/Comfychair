package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.workflow.dynamic.escapeJsonString

/**
 * 公共参数占位符的字面量替换。
 *
 * 工作流模板里的占位符有**两种写法**，取决于它在 JSON 里占据的位置：
 *
 * ```
 * "steps": "{{steps}}"     // 带引号：占位符整体是一个 JSON 字符串
 * "steps": {{steps}}       // 裸形态：占位符就在数值位置
 * ```
 *
 * 前者若按普通字符串替换（`json.replace("{{steps}}", "30")`）会得到 `"steps": "30"`
 * —— 一个**字符串**，而 KSampler 的 steps 是 INT，服务端校验直接拒绝
 * （`prompt_outputs_failed_validation`）。裸形态按同一方式替换却是对的。
 *
 * 所以统一走这里：先整体替换带引号形态（连同引号一起吃掉，写出真正的 JSON 字面量），
 * 再兜底替换裸形态与嵌入形态。两种写法都能得到类型正确的值。
 */
object PlaceholderLiterals {

    /**
     * 数值占位符 → JSON 数字。
     *
     * `"{{steps}}"` 与 `{{steps}}` 都得到 `30`；嵌在更长字符串里的
     * `prefix_{{steps}}` 只替换占位符本身（那类位置本来就要求字符串，由调用方决定）。
     */
    fun number(json: String, placeholder: String, value: String): String =
        json.replace("\"$placeholder\"", value).replace(placeholder, value)

    /**
     * 字符串占位符 → JSON 字符串（**自带引号**）。
     *
     * `"{{sampler_name}}"` 与 `{{sampler_name}}` 都得到 `"euler"`。
     * 值里的引号与反斜杠按 JSON 规则转义。
     */
    fun string(json: String, placeholder: String, value: String): String {
        val literal = "\"${escapeJsonString(value)}\""
        return json.replace("\"$placeholder\"", literal).replace(placeholder, literal)
    }
}
