package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PE（提示词强化）门控的"物化"契约。
 *
 * 背景（2026-09-25 服务器实测，两步对照都复现）：
 * `CRZSwitch` 的惰性在这条链路上**不生效** —— 提交前把 `value` 设成 JSON 布尔 `false`，
 * 服务器日志里 `TE Qwen Image 2.1 execute entered` 照样出现、9B 模型照样加载。
 * 同一个开关在 VOSR2（IMAGE 链路）上是真惰性，差异原因未查明（怀疑与那个
 * `AnyTypeStr` 类型 hack 或 STRING 输出的求值路径有关），但用户看到的现象是明确的：
 * **关掉提示词强化，本地大模型还是白跑几十秒**。
 *
 * 所以改走"提交前物化"：把未选中的那一支**从图上摘掉**，让"不执行"成为结构事实，
 * 而不是依赖节点实现细节。判据刻意收窄，绝不能误伤 VOSR2 那个同型号开关。
 */
class PeGateMaterializerTest {

    /** 一个最小的 Qwen2.1 链路：文本源 → [PE → 开关] → 文本编码器。 */
    private fun workflow(value: Any, enhancerType: String = "TE_Qwen_Image_2_1_Prompt_Enhancer"): String {
        val valueLiteral = when (value) {
            is Boolean -> value.toString()
            else -> "\"$value\""
        }
        return """
        {
          "517": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": "原始提示词"}},
          "675": {"class_type": "$enhancerType", "inputs": {"输入提示词": ["517", 0], "任务模式": "文生图"}},
          "676": {"class_type": "CRZSwitch", "inputs": {"true_input": ["675", 0], "false_input": ["517", 0], "value": $valueLiteral}},
          "522": {"class_type": "TextEncodeQwenImage21", "inputs": {"prompt": ["676", 0], "clip": ["518", 0]}},
          "518": {"class_type": "CLIPLoader", "inputs": {"clip_name": "x.safetensors"}}
        }
        """.trimIndent()
    }

    @Test
    fun `关闭时把强化节点整段摘掉`() {
        val out = JSONObject(PeGateMaterializer.materialize(workflow(false)))
        assertFalse("PE 节点应被摘掉", out.has("675"))
        assertFalse("开关也应被摘掉（它只为这条链路存在）", out.has("676"))
        assertEquals("文本编码器应直连原始提示词源",
            "517", out.getJSONObject("522").getJSONObject("inputs").getJSONArray("prompt").getString(0))
    }

    @Test
    fun `开启时一个字节都不改`() {
        val json = workflow(true)
        assertEquals(json, PeGateMaterializer.materialize(json))
    }

    @Test
    fun `占位符还没替换时不动手`() {
        // value 仍是字符串 "{{pe_on}}"：交给服务器报明确的类型错误，不凭猜测断开某一支
        val json = workflow("{{pe_on}}")
        assertEquals(json, PeGateMaterializer.materialize(json))
    }

    @Test
    fun `开关另一端不是强化节点时不误伤`() {
        // VOSR2 的开关：true_input 指向 VOSR2Image —— 同样关着，但绝不能被摘掉结构
        val json = """
        {
          "520": {"class_type": "VAEDecode", "inputs": {"samples": ["515", 0]}},
          "673": {"class_type": "TESpeedVOSR2Image", "inputs": {"images": ["520", 0]}},
          "674": {"class_type": "CRZSwitch", "inputs": {"true_input": ["673", 0], "false_input": ["520", 0], "value": false}},
          "508": {"class_type": "SaveImage", "inputs": {"images": ["674", 0]}}
        }
        """.trimIndent()
        assertEquals(json, PeGateMaterializer.materialize(json))
    }

    @Test
    fun `没有任何门控的工作流原样返回`() {
        val json = """{"1": {"class_type": "KSampler", "inputs": {"steps": 30}}}"""
        assertEquals(json, PeGateMaterializer.materialize(json))
    }

    @Test
    fun `坏 JSON 不抛异常`() {
        assertEquals("{not json", PeGateMaterializer.materialize("{not json"))
    }

    @Test
    fun `节点表在 nodes 包裹层里时也能处理`() {
        val wrapped = """{"nodes": ${workflow(false)}}"""
        val out = JSONObject(PeGateMaterializer.materialize(wrapped))
        assertFalse(out.getJSONObject("nodes").has("675"))
        assertTrue(out.getJSONObject("nodes").has("522"))
    }
}
