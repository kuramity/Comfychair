package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager

/**
 * 钉住 [WorkflowManager.clearStaleAndUnmappedPlaceholders] 的 Case 1 判定粒度：
 *
 * 生成期是**全局字符串替换**，同一占位符合法出现在多个节点上（如某些工作流里
 * `{{seed}}` 6 处、`{{width}}` 5 处、`{{positive_prompt}}` 5 处）。保存工作流时只能删
 * 「塞进了不相干输入」的那一项，不能因为节点不是映射目标就整键删掉——否则用户保存一次，
 * 其余节点的占位符就永久丢失，之后提交报 `required_input_missing`。
 */
class PlaceholderCleanupTest {

    private fun nodesOf(json: String): JSONObject =
        JSONObject(json).getJSONObject("nodes")

    private fun inputsOf(nodes: JSONObject, nodeId: String): JSONObject =
        nodes.getJSONObject(nodeId).getJSONObject("inputs")

    private fun cleanup(
        nodes: JSONObject,
        placeholderToTargetNodeId: Map<String, String>,
        unmappedOptionalKeys: Set<String> = emptySet()
    ) = WorkflowManager.clearStaleAndUnmappedPlaceholders(
        nodes,
        placeholderToTargetNodeId,
        unmappedOptionalKeys
    )

    // ---------- 多节点共用：必须全部保留 ----------

    @Test
    fun `两个节点共用 seed 时保存后都保留`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"KSampler","inputs":{"seed":"{{seed}}","steps":20}},
              "B":{"class_type":"KSampler","inputs":{"seed":"{{seed}}","steps":10}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("seed" to "A"))

        assertEquals("目标节点 A 的 seed 必须保留", "{{seed}}", inputsOf(nodes, "A").getString("seed"))
        assertEquals("非目标节点 B 的 seed 也必须保留（多节点共用）", "{{seed}}", inputsOf(nodes, "B").getString("seed"))
    }

    @Test
    fun `三个节点共用 width 时保存后都保留`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"EmptyLatentImage","inputs":{"width":"{{width}}","height":"{{height}}"}},
              "B":{"class_type":"EmptyLatentImage","inputs":{"width":"{{width}}","height":1024}},
              "C":{"class_type":"ImageScale","inputs":{"width":"{{width}}","upscale_method":"lanczos"}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("width" to "B"))

        assertEquals("目标节点 B 的 width 必须保留", "{{width}}", inputsOf(nodes, "B").getString("width"))
        assertEquals("非目标节点 A 的 width 也必须保留", "{{width}}", inputsOf(nodes, "A").getString("width"))
        assertEquals("非目标节点 C 的 width 也必须保留", "{{width}}", inputsOf(nodes, "C").getString("width"))
    }

    // ---------- 真正失效：只能删这一项 ----------

    @Test
    fun `塞进不相干输入键的占位符被删除而同节点同名键保留`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"KSampler","inputs":{"seed":"{{seed}}"}},
              "X":{"class_type":"CheckpointLoader","inputs":{
                  "model_name":"{{seed}}","seed":"{{seed}}","steps":20}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("seed" to "A"))

        val xInputs = inputsOf(nodes, "X")
        assertFalse("model_name 与 seed 语义不符，必须删除", xInputs.has("model_name"))
        assertEquals("X 自己的 seed 字段是合法共用，必须保留", "{{seed}}", xInputs.getString("seed"))
        assertEquals("普通数值输入不受影响", 20, xInputs.getInt("steps"))
    }

    @Test
    fun `目标节点上的占位符原样保留且不改动普通值`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"KSampler","inputs":{"seed":"{{seed}}","cfg":7.5}},
              "B":{"class_type":"KSampler","inputs":{"seed":12345}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("seed" to "A"))

        assertEquals("目标节点占位符原样保留", "{{seed}}", inputsOf(nodes, "A").getString("seed"))
        assertEquals("目标节点普通值原样保留", 7.5, inputsOf(nodes, "A").getDouble("cfg"), 0.0)
        assertEquals("非占位符的值不能被改动", 12345, inputsOf(nodes, "B").getInt("seed"))
    }

    // ---------- 未知占位符：字段名查不到时回落到占位符名 ----------

    @Test
    fun `未知占位符按输入键归属判定`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"SomeNode","inputs":{"foo":"{{nonexistent_thing}}"}},
              "B":{"class_type":"SomeNode","inputs":{"bar":"{{nonexistent_thing}}"}},
              "C":{"class_type":"SomeNode","inputs":{"nonexistent_thing":"{{nonexistent_thing}}"}}
            }}
            """.trimIndent()
        )

        // getJsonKeyForPlaceholder 对未知占位符回落到占位符名本身，因此「同名输入键」= 自己该落的位置
        cleanup(nodes, mapOf("nonexistent_thing" to "A"))

        assertEquals("目标节点 A 的 foo 保留", "{{nonexistent_thing}}", inputsOf(nodes, "A").getString("foo"))
        assertFalse("B 的 bar 与占位符名字不符，删除", inputsOf(nodes, "B").has("bar"))
        assertEquals(
            "C 的同名输入键视作自己在该字段上，倾向保留",
            "{{nonexistent_thing}}", inputsOf(nodes, "C").getString("nonexistent_thing")
        )
    }

    // ---------- 健壮性 ----------

    @Test
    fun `空 inputs 无 inputs 与无占位符的节点都不受影响`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "EMPTY":{"class_type":"NoInputs"},
              "ZERO":{"class_type":"Empty","inputs":{}},
              "LITERAL":{"class_type":"KSampler","inputs":{"seed":42,"text":"hello"}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("seed" to "LITERAL"))

        assertFalse("空 inputs 不应被创建出键", inputsOf(nodes, "ZERO").has("seed"))
        assertEquals("字面量 seed 不受清理影响", 42, inputsOf(nodes, "LITERAL").getInt("seed"))
        assertEquals("普通字符串不受清理影响", "hello", inputsOf(nodes, "LITERAL").getString("text"))
        assertTrue("无 inputs 的节点应原样保留", nodes.getJSONObject("EMPTY").has("class_type"))
    }

    // ---------- 真实预置流里的别名/改名 ----------

    @Test
    fun `seed 落在 noise_seed 上时视作同一字段保留`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"KSampler","inputs":{"seed":"{{seed}}"}},
              "B":{"class_type":"RandomNoise","inputs":{"noise_seed":"{{seed}}"}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("seed" to "A"))

        assertEquals(
            "RandomNoise 的 noise_seed 是 seed 的别名（实际工作流用法）",
            "{{seed}}", inputsOf(nodes, "B").getString("noise_seed")
        )
    }

    @Test
    fun `提示词的 text 与 prompt 输入键都视作同一字段保留`() {
        val nodes = nodesOf(
            """
            {"nodes":{
              "A":{"class_type":"CLIPTextEncode","inputs":{"text":"{{positive_prompt}}"}},
              "B":{"class_type":"CLIPTextEncode","inputs":{"text":"{{positive_prompt}}"}},
              "C":{"class_type":"SomePromptNode","inputs":{"prompt":"{{positive_prompt}}"}},
              "D":{"class_type":"CheckpointLoader","inputs":{"model_name":"{{positive_prompt}}"}}
            }}
            """.trimIndent()
        )

        cleanup(nodes, mapOf("positive_prompt" to "A"))

        assertEquals("目标节点 A 保留", "{{positive_prompt}}", inputsOf(nodes, "A").getString("text"))
        assertEquals("B 的 text 是合法共用（实际流里有 5 处）", "{{positive_prompt}}", inputsOf(nodes, "B").getString("text"))
        assertEquals("C 的 prompt 同样是提示词字段", "{{positive_prompt}}", inputsOf(nodes, "C").getString("prompt"))
        assertFalse("D 的 model_name 与提示词语义不符，删除", inputsOf(nodes, "D").has("model_name"))
    }
}
