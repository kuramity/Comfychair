package sh.hnet.comfychair.workflow

import org.json.JSONObject

/**
 * 提交前的参数完整性预检（v4.6.1，模块 A4）。
 *
 * 背景：`ValidationUtils.validateModelSelection()` 在模型列表为空时（`/object_info` 没拉到、
 * 离线缓存缺失、刚切工作流还没加载完）会把选中值清成 `""`；而 `WorkflowManager.prepare*`
 * 对 `{{ckpt_name}}` / `{{unet_name}}` / `{{vae_name}}` / `{{clip_name}}` 是无条件替换 ——
 * 空串会直接进 prompt JSON，服务端回 400，用户只看到"生成失败"，必须手动重连才恢复。
 *
 * 这里在提交前做两道**纯本地、零网络**的检查：
 * 1. 还有 `{{...}}` 占位符残留 → 说明有参数没注入（例如 CLIP 为空时保留了字面量）；
 * 2. `nodes[*].inputs` 里模型类字段是空字符串 → 说明模型选择被清空。
 *
 * 返回可读中文原因，调用方直接展示；返回 `null` 表示通过。
 */
object WorkflowPreflight {

    /** 提交时必须已全部替换的占位符 */
    private val PLACEHOLDER_REGEX = Regex("""\{\{[A-Za-z0-9_]+\}\}""")

    /**
     * 模型/权重类输入键。只查这些：`prompt`、`filename_prefix` 等允许空串，不在名单里。
     * 名单覆盖 ComfyUI 常见加载器 + 本项目工作流实际用到的字段。
     */
    private val MODEL_INPUT_KEYS = setOf(
        "ckpt_name", "unet_name", "vae_name",
        "clip_name", "clip_name1", "clip_name2", "clip_name3", "clip_name4",
        "text_encoder", "text_encoder_name",
        "lora_name", "control_net_name", "upscale_model_name",
        "model_name", "latent_upscale_model"
    )

    /**
     * @return 失败原因（可直接展示给用户）；`null` 表示预检通过
     */
    fun check(preparedJson: String): String? {
        PLACEHOLDER_REGEX.find(preparedJson)?.let { match ->
            return "工作流参数未就绪：${match.value} 没有被替换。请重新选择模型，或点「重连」后再试。"
        }

        val nodes = try {
            JSONObject(preparedJson).optJSONObject("nodes")
        } catch (_: Exception) {
            null
        } ?: return null // 非 UI 格式（没有 nodes）：交给服务端自行校验

        val emptyKeys = LinkedHashSet<String>()
        val ids = nodes.keys()
        while (ids.hasNext()) {
            val node = nodes.optJSONObject(ids.next()) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val keys = inputs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in MODEL_INPUT_KEYS) continue
                // 只校验字符串形态的模型名；数组形态是节点连线，跳过
                if (inputs.opt(key) is String && inputs.optString(key).isEmpty()) {
                    emptyKeys.add(key)
                }
            }
        }

        if (emptyKeys.isEmpty()) return null
        return "模型参数为空（${emptyKeys.joinToString("、")}）。请等模型列表加载完成，或点「重连」后重试。"
    }
}
