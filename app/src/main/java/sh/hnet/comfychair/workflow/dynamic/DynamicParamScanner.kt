package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject
import sh.hnet.comfychair.workflow.TemplateKeyRegistry

/**
 * 扫描与索引（纯逻辑，可 JVM 单测）。
 *
 * 遍历工作流节点，找出所有**值恰好为** `{{xxx}}` 的输入，建立
 * `占位符 → (class_type, 输入名)` 映射，并过滤掉「已识别」的占位符（设计约束 1）。
 *
 * 输入值可以是两种形态：
 * - 直接子键的字符串（`"steps": "{{my_steps}}"`）
 * - 嵌套**一层** dict 的字符串子值（rgthree Power Lora Loader 形态：
 *   `"lora_1": {"on": "{{lora1_on}}", ...}`）——此时 `inputName` 记为「父键.子键」路径
 *
 * 只认「整个值就是占位符」的形态（`"steps": "{{my_steps}}"`）：只有这种形态才能唯一确定
 * `(class_type, 输入名)`，进而查到真实类型。嵌在更大字符串里的 `{{x}}` 不参与索引
 * （它的类型无从推导），但 [sh.hnet.comfychair.workflow.dynamic.DynamicParamValues.applyDynamicParams]
 * 仍会按文本把它替换掉。
 */
object DynamicParamScanner {

    /** 值恰好是整个占位符时才索引 */
    private val WHOLE_VALUE_PLACEHOLDER = Regex("""^\{\{(\w+)\}\}$""")

    /**
     * 扫描工作流 JSON 字符串。支持两种顶层形态：
     * - 本项目封装格式 `{"name":..., "nodes": {"<nodeId>": {...}}}`
     * - 裸节点表 `{"<nodeId>": {...}}`
     */
    fun scan(workflowJson: String): List<DynamicParamRef> {
        val root = try {
            JSONObject(workflowJson)
        } catch (e: Exception) {
            return emptyList()
        }
        return scanNodes(nodesOf(root))
    }

    /**
     * 从节点表扫描。
     *
     * 同一占位符命中多个节点时，取**节点 id 排序最前**的那个（数字感知排序，如 30 在 100 之前），
     * 保证结果确定；因为替换是按占位符名做的全局文本替换，取哪个节点的类型描述都不影响替换结果。
     */
    fun scanNodes(nodesJson: JSONObject): List<DynamicParamRef> {
        val found = LinkedHashMap<String, DynamicParamRef>()
        for (nodeId in nodesJson.keys().asSequence().toList().sortedWith(::compareNodeIds)) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            if (classType.isEmpty()) continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputName in inputs.keys().asSequence().toList()) {
                val raw = inputs.opt(inputName)
                // 直接子键的字符串值（原行为）；或嵌套一层 dict 的字符串子值（rgthree Power Lora Loader 形态）
                val candidates: List<Pair<String, String>> = when (raw) {
                    is String -> listOf(inputName to raw)
                    is JSONObject -> raw.keys().asSequence().toList().mapNotNull { sub ->
                        // 只接字符串子值；嵌套里的数组/数字/布尔/null 一律跳过
                        val v = raw.opt(sub)
                        if (v is String) "$inputName.$sub" to v else null
                    }
                    // 连线（JSONArray）/ 数值 / 布尔 / null 不参与索引
                    else -> emptyList()
                }
                for ((path, value) in candidates) {
                    val match = WHOLE_VALUE_PLACEHOLDER.find(value) ?: continue
                    val placeholder = match.groupValues[1]
                    // 设计约束 1：只接管「未识别」的占位符
                    if (TemplateKeyRegistry.isRecognizedPlaceholder(placeholder)) continue
                    if (found.containsKey(placeholder)) continue
                    found[placeholder] = DynamicParamRef(
                        placeholder = placeholder,
                        nodeId = nodeId,
                        classType = classType,
                        // 嵌套命中时是「父键.子键」路径（如 lora_1.on）；类型推导对查不到的
                        // 输入名一律退化为文本框（设计约束 2），路径形式不会破坏 lookup
                        inputName = path
                    )
                }
            }
        }
        return found.values.toList()
    }

    /**
     * 取节点表：有 `nodes` 对象就用它，否则把根对象当裸节点表。
     */
    fun nodesOf(root: JSONObject): JSONObject {
        return root.optJSONObject("nodes") ?: root
    }

    /**
     * 节点 id 排序：纯数字按数值比，其余按字典序。仅用于让扫描结果确定，与业务无关。
     * 同为 `internal` 供 UI 层的静默问题扫描复用，保证两处提示顺序一致。
     */
    internal fun compareNodeIds(a: String, b: String): Int {
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            else -> a.compareTo(b)
        }
    }
}
