package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONArray
import org.json.JSONObject

/**
 * **PE（提示词强化）门控的"物化"**：关掉强化时，把强化节点整段从图上摘掉。
 *
 * 背景（2026-09-25 服务器实测，两步对照）：
 * `CRZSwitch` 的惰性在这条链路上**不生效** —— 把 `value` 提交成 JSON 布尔 `false`
 * 之后，服务器日志里 `TE Qwen Image 2.1 execute entered` 照样出现、本地 9B 模型照样加载、
 * 照样跑完再卸载（用户原话："我没开启提示词强化为什么还是有这个模型加载"）。
 * 同一个开关在 VOSR2（IMAGE 链路）上是**真惰性**（关掉时出图快一倍），差异原因未查明
 * —— 怀疑与插件那个 `AnyTypeStr` 类型 hack 或 STRING 输出的求值路径有关 —— 但结论明确：
 * **不能靠 lazy 省这次推理**。
 *
 * 所以改走"提交前物化"，与 [ClipGateMaterializer] 同一思路：把未选中的那一支断成孤岛，
 * 让"不执行"成为**结构上的事实**，而不是依赖节点实现细节。
 *
 * 判据刻意收得很窄（三条同时成立才动手），绝不误伤 VOSR2 那个同型号开关：
 * - 节点是 `CRZSwitch`；
 * - `value` 已是 **JSON 布尔 false**（说明 `{{pe_on}}` 被替换过；仍是字符串时不动手，
 *   让服务器报出明确的类型错误，而不是凭猜测断开某一支）；
 * - `true_input` 指向的节点恰好是 `TE_Qwen_Image_2_1_Prompt_Enhancer`。
 *
 * 纯逻辑、无 Android 依赖，可 JVM 单测。
 */
object PeGateMaterializer {

    private const val GATE_TYPE = "CRZSwitch"
    private const val ENHANCER_TYPE = "TE_Qwen_Image_2_1_Prompt_Enhancer"

    /**
     * 物化一份**已经替换完占位符**的工作流 JSON。
     *
     * @return 有可摘的门控时返回改动后的 JSON；否则**原样返回入参字符串**
     *         —— 没有改动的资产一个字节都不该变（幂等，也不影响别的预置流）。
     */
    fun materialize(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return json
        }
        val nodes = root.optJSONObject("nodes") ?: root

        var modified = false
        for (gateId in nodes.keys().asSequence().toList()) {
            val gate = nodes.optJSONObject(gateId) ?: continue
            if (gate.optString("class_type") != GATE_TYPE) continue
            val inputs = gate.optJSONObject("inputs") ?: continue

            val value = inputs.opt("value")
            // 判据一：必须是"已被替换成 JSON 布尔 false"。字符串（含未替换的 {{pe_on}}）
            // 一律不动手 —— 那时服务器的类型校验会给出更清楚的报错。
            if (value !is Boolean || value) continue

            val trueRef = inputs.optJSONArray("true_input") ?: continue
            val falseRef = inputs.optJSONArray("false_input") ?: continue
            if (trueRef.length() < 2 || falseRef.length() < 2) continue

            // 判据二：被开关的必须是提示词强化节点（VOSR2 那条开关的 true_input 是
            // TESpeedVOSR2Image，天然不匹配，因此不受影响）
            val enhancer = nodes.optJSONObject(trueRef.optString(0)) ?: continue
            if (enhancer.optString("class_type") != ENHANCER_TYPE) continue

            // 动作：先让下游改接 false 支的来源，再把开关与强化节点摘掉
            rewireConsumers(nodes, gateId, falseRef)
            nodes.remove(gateId)
            nodes.remove(trueRef.optString(0))
            modified = true
        }
        return if (modified) root.toString() else json
    }

    /** 把所有"输入指向开关"的边改成指向 [source]（`false_input` 的来源）。 */
    private fun rewireConsumers(nodes: JSONObject, gateId: String, source: JSONArray) {
        for (nodeId in nodes.keys().asSequence().toList()) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val ref = inputs.optJSONArray(key) ?: continue
                if (ref.length() < 2 || ref.optString(0) != gateId) continue
                inputs.put(key, JSONArray().apply {
                    put(source.optString(0))
                    put(source.optInt(1))
                })
            }
        }
    }
}
