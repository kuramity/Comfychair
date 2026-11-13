package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONArray
import org.json.JSONObject

/**
 * **CLIP 加载器格式门控的"物化"**：把 `{{clip_gguf}}` 开关的未选中分支从图上断开。
 *
 * 背景（服务器实测，`temp/gguf-gate/execution-report.md`）：
 * 原生 `CLIPLoader` 的 `clip_name` 候选里没有 `.gguf`，`CLIPLoaderGGUF` 的候选是它的**超集**；
 * 两者的适用格式又互斥（GGUF 加载器用 GGMLOps，加载 fp8 safetensors 会崩），
 * 所以图上同时挂两个加载器、由一个 bool 开关选谁生效。
 *
 * **为什么还要断开**（三条实测结论，缺一条都会误判）：
 * 1. `CRZSwitch` 的惰性**只在执行期生效**：未选中的分支不进执行表（实测 `/history` 的
 *    `execution_cached` 里只有被选中的那个加载器）；
 * 2. 但 `validate_inputs()` **不看 lazy**：当下游节点**声明了**对应的输入类型时
 *    （如 `CLIPTextEncode.clip`），未选中分支的 COMBO 字面量照样被校验 ——
 *    把 `.gguf` 挂在原生加载器上（哪怕它是未选中分支）会直接 400 `value_not_in_list`；
 * 3. 反过来，当下游是 `Lora Loader (LoraManager)` 时，它的 `clip` 输入**不在 `/object_info`
 *    的输入声明里**（插件以 `**kwargs` 接收），那条支链对 validate 不可达 —— 实测同一个
 *    非法值**不报错**。也就是说"要不要断开"取决于下游节点怎么写 `INPUT_TYPES`，
 *    是别人的实现细节。
 *
 * 所以本类把未选中分支断成**没有任何引用的孤岛**：让"未选中的加载器不被校验、也不被执行"
 * 成为**结构上的事实**，而不是依赖下游节点的输入声明方式或 lazy 的实现细节。
 * （两个加载器因此可以共用同一个 `{{clip_name}}` —— 用户只选一次文件。）
 *
 * 判据刻意收得很窄（三条同时成立才动手），避免误伤别的惰性开关：
 * - 节点是 `CRZSwitch`；
 * - `value` 已是 **JSON 布尔**（说明 `{{clip_gguf}}` 已被动态参数引擎替换；仍是字符串时不动手，
 *   让服务器报出明确的类型错误，而不是凭猜测断开某一支）；
 * - `true_input` / `false_input` 指向的节点恰好是 `CLIPLoaderGGUF` 与 `CLIPLoader` 各一个
 *   —— 竖叉流 `prebuilt_11` 的补采开关（指向 KSampler/VAEEncode）因此天然不受影响。
 *
 * 纯逻辑、无 Android 依赖，可 JVM 单测。
 */
object ClipGateMaterializer {

    private const val GATE_TYPE = "CRZSwitch"
    private const val NATIVE_LOADER = "CLIPLoader"
    private const val GGUF_LOADER = "CLIPLoaderGGUF"

    /**
     * 物化一份**已经替换完占位符**的工作流 JSON。
     *
     * @return 有门控时返回改动后的 JSON；没有门控（或值还没被替换）时**原样返回入参字符串**
     *         —— 没有改动的资产一个字节都不该变（幂等，且不影响别的预置流）。
     */
    fun materialize(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return json
        }
        // 与 DynamicParamScanner 同口径：有 `nodes` 对象就用它，否则把根对象当裸节点表
        val nodes = root.optJSONObject("nodes") ?: root

        var modified = false
        for (gateId in nodes.keys().asSequence().toList()) {
            val gate = nodes.optJSONObject(gateId) ?: continue
            if (gate.optString("class_type") != GATE_TYPE) continue

            val inputs = gate.optJSONObject("inputs") ?: continue
            // value 不是布尔 = 占位符还没被替换（注册表未就绪等）→ 不动手
            val selected = inputs.opt("value") as? Boolean ?: continue
            val trueTarget = targetOf(inputs.opt("true_input"), nodes) ?: continue
            val falseTarget = targetOf(inputs.opt("false_input"), nodes) ?: continue

            val verified = (trueTarget.second == GGUF_LOADER && falseTarget.second == NATIVE_LOADER) ||
                (trueTarget.second == NATIVE_LOADER && falseTarget.second == GGUF_LOADER)
            if (!verified) continue

            // 保留被选中的那一路，把另一路改指到同一个节点 → 未选中的加载器失去全部引用
            val keep = inputs.optJSONArray(if (selected) "true_input" else "false_input") ?: continue
            val dropKey = if (selected) "false_input" else "true_input"
            val drop = inputs.optJSONArray(dropKey) ?: continue
            if (sameLink(keep, drop)) continue

            inputs.put(dropKey, copyLink(keep))
            modified = true
        }

        return if (modified) root.toString() else json
    }

    /** 连线的目标节点与它的 class_type；不是连线形态（widget 值）或目标不存在时返回 null。 */
    private fun targetOf(raw: Any?, nodes: JSONObject): Pair<String, String>? {
        val array = raw as? JSONArray ?: return null
        if (array.length() < 2) return null
        val id = array.opt(0) as? String ?: return null
        val node = nodes.optJSONObject(id) ?: return null
        return id to node.optString("class_type")
    }

    private fun sameLink(a: JSONArray, b: JSONArray): Boolean =
        a.length() == b.length() && (0 until a.length()).all { a.opt(it) == b.opt(it) }

    private fun copyLink(source: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until source.length()) put(source.opt(index))
    }
}
