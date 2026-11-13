package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共参数占位符的字面量替换。
 *
 * 这里钉的是一个**只在提交时才暴露**的坑：模板里 `"{{steps}}"` 带引号时，普通字符串替换
 * 会留下引号、发出字符串 `"30"`，而 KSampler 的 steps 是 INT —— 服务端只回一句
 * `prompt_outputs_failed_validation`，看不出是哪个参数。所以断言必须落在**解析后的类型**上，
 * 而不是字符串长相。
 */
class PlaceholderLiteralsTest {

    @Test
    fun `带引号形态的数值占位符产出 JSON 数字`() {
        val out = PlaceholderLiterals.number("""{"steps": "{{steps}}"}""", "{{steps}}", "30")
        // 断言**运行时类型**：org.json 的 getInt 会把字符串 "30" 也转成 30，
        // 用它断言会漏掉这个 bug（反证实验抓到过一次）。
        assertTrue("steps 应是 JSON 数字，实际: ${JSONObject(out).get("steps")::class.simpleName}",
            JSONObject(out).get("steps") is Number)
        assertEquals(30, JSONObject(out).getInt("steps"))
        assertFalse("不应留下字符串形态", out.contains("\"30\""))
    }

    @Test
    fun `裸形态的数值占位符产出 JSON 数字`() {
        val out = PlaceholderLiterals.number("""{"steps": {{steps}}}""", "{{steps}}", "30")
        assertTrue(JSONObject(out).get("steps") is Number)
        assertEquals(30, JSONObject(out).getInt("steps"))
    }

    @Test
    fun `带引号形态的字符串占位符产出 JSON 字符串`() {
        val out = PlaceholderLiterals.string("""{"sampler_name": "{{sampler_name}}"}""", "{{sampler_name}}", "euler")
        assertEquals("euler", JSONObject(out).getString("sampler_name"))
    }

    @Test
    fun `裸形态的字符串占位符产出 JSON 字符串`() {
        val out = PlaceholderLiterals.string("""{"sampler_name": {{sampler_name}}}""", "{{sampler_name}}", "euler")
        assertEquals("euler", JSONObject(out).getString("sampler_name"))
    }

    @Test
    fun `字符串值里的引号与反斜杠按 JSON 规则转义`() {
        val value = """a"b\c"""
        val out = PlaceholderLiterals.string("""{"v": "{{v}}"}""", "{{v}}", value)
        assertEquals(value, JSONObject(out).getString("v"))
    }

    @Test
    fun `嵌入在更长字符串里的占位符只替换占位符本身`() {
        val out = PlaceholderLiterals.number("""{"filename_prefix": "QW21_{{seed}}"}""", "{{seed}}", "12345")
        assertEquals("QW21_12345", JSONObject(out).getString("filename_prefix"))
    }

    @Test
    fun `多处出现的同一个占位符全部替换`() {
        val json = """{"a": "{{steps}}", "b": "{{steps}}"}"""
        val out = PlaceholderLiterals.number(json, "{{steps}}", "8")
        val obj = JSONObject(out)
        assertEquals(8, obj.getInt("a"))
        assertEquals(8, obj.getInt("b"))
    }

    /** 真实形态：来自 prebuilt 工作流的 KSampler 片段（数值带引号 + 字符串带引号混排）。 */
    @Test
    fun `KSampler 片段替换后各参数类型正确`() {
        val json = """
            {"515": {"class_type": "KSampler", "inputs": {
              "seed": "{{seed}}", "steps": "{{steps}}", "cfg": "{{cfg}}",
              "sampler_name": "{{sampler_name}}", "scheduler": "{{scheduler}}"}}}
        """.trimIndent()
        var out = PlaceholderLiterals.number(json, "{{seed}}", "12345")
        out = PlaceholderLiterals.number(out, "{{steps}}", "30")
        out = PlaceholderLiterals.number(out, "{{cfg}}", "1.0")
        out = PlaceholderLiterals.string(out, "{{sampler_name}}", "euler")
        out = PlaceholderLiterals.string(out, "{{scheduler}}", "simple")

        val inputs = JSONObject(out).getJSONObject("515").getJSONObject("inputs")
        assertTrue("seed 应是数字", inputs.get("seed") is Number)
        assertTrue("steps 应是数字", inputs.get("steps") is Number)
        assertTrue("cfg 应是数字", inputs.get("cfg") is Number)
        assertEquals(12345L, inputs.getLong("seed"))
        assertEquals(30, inputs.getInt("steps"))
        assertEquals(1.0, inputs.getDouble("cfg"), 1e-6)
        assertEquals("euler", inputs.getString("sampler_name"))
        assertEquals("simple", inputs.getString("scheduler"))
        assertTrue("占位符应已全部替换", !out.contains("{{"))
    }
}
