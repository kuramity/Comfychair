package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PlaceholderPipeline 等价性钉子（B3）：两 API 必须分别复现原 WorkflowManager 文本级
 * 与原 feature-tools 结构级通道的行为——含历史上真实踩过的坑（带引号数值模板）。
 */
class PlaceholderPipelineTest {

    // ---------- substituteText：文本级（PlaceholderLiterals 语义） ----------

    @Test
    fun `数值占位符带引号模板被整体吃掉产出JSON数字`() {
        val json = """{"steps": "{{steps}}", "name": "x"}"""
        val out = PlaceholderPipeline.substituteText(json, listOf("steps" to 30))
        assertEquals("""{"steps": 30, "name": "x"}""", out)
    }

    @Test
    fun `数值占位符裸形态同样产出数字`() {
        val json = """{"steps": {{steps}}}"""
        val out = PlaceholderPipeline.substituteText(json, listOf("steps" to 30))
        assertEquals("""{"steps": 30}""", out)
    }

    @Test
    fun `字符串占位符两种形态都得到带引号转义字面量`() {
        val json = """{"s": "{{sampler_name}}", "raw": {{sampler_name}}}"""
        val out = PlaceholderPipeline.substituteText(json, listOf("sampler_name" to "euler"))
        assertEquals("""{"s": "euler", "raw": "euler"}""", out)
    }

    @Test
    fun `字符串值中的引号与反斜杠被JSON转义`() {
        val json = """{"p": "{{positive_prompt}}"}"""
        val out = PlaceholderPipeline.substituteText(json, listOf("positive_prompt" to "a \"b\" \\c"))
        // escapeJsonString: " -> \" , \ -> \\
        assertEquals("""{"p": "a \"b\" \\c"}""", out)
    }

    @Test
    fun `null值跳过不替换`() {
        val json = """{"w": "{{width}}"}"""
        val out = PlaceholderPipeline.substituteText(json, listOf("width" to null))
        assertEquals("""{"w": "{{width}}"}""", out)
    }

    @Test
    fun `Float与Long同样走数字策略`() {
        val json = """{"cfg": "{{cfg}}", "seed": "{{seed}}"}"""
        val out = PlaceholderPipeline.substituteText(
            json, listOf("cfg" to 7.5f, "seed" to 42L)
        )
        assertEquals("""{"cfg": 7.5, "seed": 42}""", out)
    }

    @Test
    fun `替换顺序敏感_后值中的已替换占位符字面量不被回代`() {
        // 语义与原两通道一致：值里含「排在前面已换完」的占位符 → 存活；
        // 含「排在后面」的占位符 → 会被回代（原行为如此，本测试钉住方向性）
        val json = """{"rule": "{{skill_file_content}}", "p": "{{positive_prompt}}"}"""
        val values = listOf(
            "positive_prompt" to "一只猫",
            "skill_file_content" to "请把 {{positive_prompt}} 改写好"
        )
        val out = PlaceholderPipeline.substituteText(json, values)
        assertEquals("""{"rule": "请把 {{positive_prompt}} 改写好", "p": "一只猫"}""", out)
    }

    // ---------- substituteTree：结构级（原 feature-tools 语义） ----------

    @Test
    fun `树级只替换字符串值_数值布尔连线不碰`() {
        val root = JSONObject(
            """{"nodes": {"1": {"inputs": {"text": "{{p}}", "seed": 1, "on": true,
                "link": [1, 2]}, "class_type": "KSampler"}}}"""
        )
        PlaceholderPipeline.substituteTree(root, linkedMapOf("p" to "一只猫"))
        val inputs = root.getJSONObject("nodes").getJSONObject("1").getJSONObject("inputs")
        assertEquals("一只猫", inputs.getString("text"))
        assertEquals(1, inputs.getInt("seed"))
        assertEquals(true, inputs.getBoolean("on"))
        assertEquals(JSONArray(listOf(1, 2)).toString(), inputs.getJSONArray("link").toString())
    }

    @Test
    fun `树级嵌套rgthree形态被覆盖`() {
        val root = JSONObject(
            """{"lora_1": {"on": "{{on}}", "strength": 0.8}}"""
        )
        PlaceholderPipeline.substituteTree(root, linkedMapOf("on" to "true"))
        assertEquals("true", root.getJSONObject("lora_1").getString("on"))
        assertEquals(0.8, root.getJSONObject("lora_1").getDouble("strength"), 1e-9)
    }

    @Test
    fun `树级替换顺序_后值含已换完占位符存活`() {
        val root = JSONObject("""{"rule": "{{rule}}", "p": "{{p}}"}""")
        PlaceholderPipeline.substituteTree(
            root,
            linkedMapOf(
                "p" to "一只猫",
                "rule" to "改写 {{p}}"
            )
        )
        assertEquals("改写 {{p}}", root.getString("rule"))
        assertEquals("一只猫", root.getString("p"))
    }
}
