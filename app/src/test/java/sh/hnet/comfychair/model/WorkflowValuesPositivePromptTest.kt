package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正面提示词改为每工作流一份」的存储层测试。
 *
 * 守三件事：
 * 1. **往返保真**：写进去什么，读出来就是什么；
 * 2. **老数据不炸**：升级前存下的 JSON 里没有 `positivePrompt` 键，
 *    读出来必须是 `null`（=「这个工作流还没保存过正面词」）而不是抛异常、更不是空串
 *    ——`null` 会回退到工作流默认提示词，空串却会被当成"用户明确清空过"；
 * 3. **语义可区分**：「存了空串」与「压根没这个字段」是两种意思，不能被解析成同一个结果。
 */
class WorkflowValuesPositivePromptTest {

    // ---------------- ① 往返 ----------------

    @Test
    fun `正面提示词往返保真`() {
        val values = WorkflowValues(positivePrompt = "a photo of a cat", negativePrompt = "blurry")
        val restored = WorkflowValues.fromJson(WorkflowValues.toJson(values))
        assertEquals("a photo of a cat", restored.positivePrompt)
        assertEquals("blurry", restored.negativePrompt)
    }

    @Test
    fun `正面提示词为 null 时不写该键`() {
        val json = JSONObject(WorkflowValues.toJson(WorkflowValues(positivePrompt = null)))
        assertFalse("null 表示「没存过」，不该占一个键位", json.has("positivePrompt"))
    }

    @Test
    fun `存了空串时键存在且读回空串`() {
        val json = JSONObject(WorkflowValues.toJson(WorkflowValues(positivePrompt = "")))
        assertTrue("空串是「用户明确清空过」，必须写进 JSON", json.has("positivePrompt"))

        val restored = WorkflowValues.fromJson(WorkflowValues.toJson(WorkflowValues(positivePrompt = "")))
        assertEquals("", restored.positivePrompt)
    }

    @Test
    fun `存了空串与没存过是两种语义`() {
        val cleared = WorkflowValues.fromJson("""{"positivePrompt":""}""")
        val neverSaved = WorkflowValues.fromJson("""{"steps":20}""")
        assertEquals("", cleared.positivePrompt)
        assertNull(neverSaved.positivePrompt)
    }

    // ---------------- ② 老数据兼容 ----------------

    @Test
    fun `老 JSON 无正面字段时读为 null 且其它字段照常解析`() {
        // 升级前的工作流记录长这样：只有负面、没有正面
        val legacy = """
        {
          "width": 1024, "height": 1536, "steps": 28, "cfg": 6.5,
          "samplerName": "dpmpp_2m", "scheduler": "karras",
          "negativePrompt": "lowres, blurry",
          "model": "sd_xl_base_1.0.safetensors",
          "seed": 12345
        }
        """.trimIndent()

        val restored = WorkflowValues.fromJson(legacy)
        assertNull("老记录必须读成 null（回退默认词），而不是空串", restored.positivePrompt)
        assertEquals(1024, restored.width)
        assertEquals("lowres, blurry", restored.negativePrompt)
        assertEquals("sd_xl_base_1.0.safetensors", restored.model)
        assertEquals(12345L, restored.seed)
    }

    @Test
    fun `正面字段为 JSON null 时读为空串_与负面行为一致`() {
        // 本 App 从不写出显式 null（toJson 对 null 是省略键），所以这条只防"外部塞进来的 JSON"。
        // 取值口径刻意与 negativePrompt 一字不差（同样是 has + optString），
        // 免得两个同级字段对同一种坏数据给出不同解释。
        assertEquals("", WorkflowValues.fromJson("""{"positivePrompt": null}""").positivePrompt)
        assertEquals("", WorkflowValues.fromJson("""{"negativePrompt": null}""").negativePrompt)
    }

    @Test
    fun `老数据往返不丢字段`() {
        // 读→写一轮后，老记录里原有的字段必须还在（写的时候不能"只剩新字段"）
        val legacy = """{"negativePrompt":"x","steps":30,"model":"a.safetensors"}"""
        val roundTrip = JSONObject(WorkflowValues.toJson(WorkflowValues.fromJson(legacy)))
        assertEquals(30, roundTrip.optInt("steps"))
        assertEquals("a.safetensors", roundTrip.optString("model"))
        assertEquals("x", roundTrip.optString("negativePrompt"))
        assertFalse(roundTrip.has("positivePrompt"))
    }

    @Test
    fun `含特殊字符的提示词往返不坏`() {
        val tricky = "带\"引号\"、换行\n、反斜杠\\ 与 emoji 🎨 的中文提示词"
        val restored = WorkflowValues.fromJson(WorkflowValues.toJson(WorkflowValues(positivePrompt = tricky)))
        assertEquals(tricky, restored.positivePrompt)
    }
}
