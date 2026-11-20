package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-10：解析器不得把「显示用的 30 字符截断」写进图模型。
 *
 * 旧实现 `WorkflowParser.formatLiteralValue` 把超过 30 字符的字符串截成 `前 27 + "..."`，
 * 而 `WorkflowSerializer` 会把 `WorkflowNode.inputs` 原样写回文件：用户只要在编辑器里保存
 * 一次工作流，磁盘上的长提示词就永久变成省略号——**即使他根本没动过那个节点**。
 *
 * 这里钉三件事：
 * 1. 解析结果保留完整原文；
 * 2. 序列化（写盘）后文件里仍是完整原文，没有省略号；
 * 3. 显示用的截断仍然存在（只是换到了 `formatLiteralValueForDisplay`，不再污染模型）。
 */
class WorkflowLiteralIntegrityTest {

    /** 故意超过 30 字符，且带逗号空格，贴近真实提示词 */
    private val longPrompt =
        "masterpiece, best quality, ultra detailed, 8k resolution, cinematic lighting, soft shadow"

    private val workflowJson = """
        {
          "nodes": {
            "3": {
              "class_type": "CLIPTextEncode",
              "inputs": {
                "text": "$longPrompt",
                "clip": ["4", 1]
              }
            },
            "4": {
              "class_type": "CheckpointLoaderSimple",
              "inputs": {
                "ckpt_name": "model.safetensors"
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `解析后字面量保留完整原文`() {
        val graph = WorkflowParser().parse(workflowJson, "test", "")

        val encoder = graph.nodes.first { it.id == "3" }
        val literal = encoder.inputs.getValue("text") as InputValue.Literal

        assertEquals("图模型必须保存完整原文", longPrompt, literal.value)
        assertFalse("模型里不允许出现解析期加的省略号", (literal.value as String).endsWith("..."))
    }

    @Test
    fun `连字符不丢失`() {
        val graph = WorkflowParser().parse(workflowJson, "test", "")
        val encoder = graph.nodes.first { it.id == "3" }
        val text = (encoder.inputs.getValue("text") as InputValue.Literal).value as String

        assertTrue("原文里的 clip 连线不应影响文本，且文本首尾与原文一致", text.startsWith("masterpiece"))
        assertEquals(longPrompt.length, text.length)
    }

    @Test
    fun `序列化写回时字符串仍是完整原文`() {
        val graph = WorkflowParser().parse(workflowJson, "test", "")
        val serialized = WorkflowSerializer().serialize(graph)

        val written = JSONObject(serialized)
            .getJSONObject("3")
            .getJSONObject("inputs")
            .getString("text")

        assertEquals("写盘内容必须与原文逐字相同", longPrompt, written)
        assertFalse("写盘内容里不允许出现省略号", written.contains("..."))
    }

    @Test
    fun `显示用格式化仍按 30 字符截断`() {
        val display = WorkflowParser.formatLiteralValueForDisplay(longPrompt)

        assertEquals(WorkflowParser.DISPLAY_MAX_LENGTH, display.length)
        assertTrue(display.endsWith("..."))
        assertEquals("短文本原样显示", "short", WorkflowParser.formatLiteralValueForDisplay("short"))
    }
}
