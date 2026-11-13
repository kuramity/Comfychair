package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「工作流自带默认正面提示词」的解析测试（`defaults.positive_prompt`）。
 *
 * 背景：prebuilt 资产里**早就有** `positive_prompt` 字段（如 `RAW photo, stunningly
 * beautiful 22-year-old Asian girl…`），但 `WorkflowDefaults.fromJson` 一直没读它，
 * 于是它被静默丢弃、"草稿为空时回退该工作流默认词"这条路走不通。
 *
 * 这里的改动是**只读不写坏**的：把已有字段读进来；`toJson` 顺带写回，
 * 修掉「复制工作流 / 编辑器保存时把默认词丢掉」的不一致。
 */
class WorkflowDefaultsPositivePromptTest {

    // ---------------- ① 解析 ----------------

    @Test
    fun `从 defaults 解析出 positive_prompt`() {
        val defaults = WorkflowDefaults.fromJson(
            JSONObject("""{"positive_prompt":"RAW photo, stunningly beautiful 22-year-old Asian girl","negative_prompt":"lowres"}""")
        )
        assertEquals("RAW photo, stunningly beautiful 22-year-old Asian girl", defaults.positivePrompt)
        assertEquals("lowres", defaults.negativePrompt)
    }

    @Test
    fun `缺 positive_prompt 字段时读出 null`() {
        assertNull(WorkflowDefaults.fromJson(JSONObject("""{"negative_prompt":"lowres"}""")).positivePrompt)
    }

    @Test
    fun `positive_prompt 为空串时读出空串`() {
        // 与 negative_prompt 的处理保持一致：有键就取，空串也照收（不当作"缺字段"）
        assertEquals("", WorkflowDefaults.fromJson(JSONObject("""{"positive_prompt":""}""")).positivePrompt)
    }

    @Test
    fun `null 的 JSON 对象返回空默认值`() {
        assertNull(WorkflowDefaults.fromJson(null).positivePrompt)
    }

    // ---------------- ② 回写 ----------------

    @Test
    fun `toJson 写回 positive_prompt`() {
        val json = WorkflowDefaults.toJson(WorkflowDefaults(positivePrompt = "hello", negativePrompt = "world"))
        assertEquals("hello", json.optString("positive_prompt"))
        assertEquals("world", json.optString("negative_prompt"))
    }

    @Test
    fun `toJson 在正面为 null 时不写键`() {
        val json = WorkflowDefaults.toJson(WorkflowDefaults(positivePrompt = null))
        assertFalse(json.has("positive_prompt"))
    }

    @Test
    fun `defaults 往返保真`() {
        val original = WorkflowDefaults(
            positivePrompt = "中文提示词，含标点。",
            negativePrompt = "lowres",
            steps = 28,
            cfg = 6.5f,
        )
        val restored = WorkflowDefaults.fromJson(WorkflowDefaults.toJson(original))
        assertEquals(original.positivePrompt, restored.positivePrompt)
        assertEquals(original.negativePrompt, restored.negativePrompt)
        assertEquals(28, restored.steps)
        assertEquals(6.5f, restored.cfg)
    }

    @Test
    fun `prebuilt 资产的 defaults 形态能被解析`() {
        // 取自 app/src/main/assets/prebuilt_workflows 的真实结构（外层包 name/description/defaults）
        val prebuilt = JSONObject(
            """
            {
              "name": "Krea2 极致高清",
              "description": "…",
              "defaults": {
                "positive_prompt": "RAW photo, stunningly beautiful 22-year-old Asian girl, ultra detailed",
                "negative_prompt": "blurry, low quality",
                "steps": 30,
                "cfg": 4.5
              }
            }
            """.trimIndent()
        )
        val defaults = WorkflowDefaults.fromJson(prebuilt.optJSONObject("defaults"))
        assertEquals(
            "RAW photo, stunningly beautiful 22-year-old Asian girl, ultra detailed",
            defaults.positivePrompt,
        )
        assertEquals(30, defaults.steps)
    }
}
