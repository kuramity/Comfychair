package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词预设的**正负双字段**往返。
 *
 * 最要紧的一条是向后兼容：v0.9.3 之前存的预设没有 `negativePrompt` 键，
 * 读出来必须是空串（=「这条预设没有负面」），而不是让整条预设解析失败 ——
 * 用户提示词库里可能已经攒了几十条，一条都不能丢。
 */
class PromptPresetTest {

    private fun base(id: String = "p1", name: String = "猫咪") = PromptPreset(
        id = id,
        screenType = ScreenType.TEXT_TO_IMAGE,
        name = name,
        prompt = "1girl, cat ears",
        tags = listOf("cat"),
        isFavorite = true,
        createdAt = 1700000000000L,
        negativePrompt = "lowres, bad anatomy"
    )

    @Test
    fun `正负两段各自往返`() {
        val restored = PromptPreset.fromJson(base().toJson())
        assertEquals("1girl, cat ears", restored?.prompt)
        assertEquals("lowres, bad anatomy", restored?.negativePrompt)
        assertEquals("p1", restored?.id)
        assertEquals(listOf("cat"), restored?.tags)
        assertTrue(restored?.isFavorite == true)
    }

    @Test
    fun `负面可以为空而不丢失字段`() {
        // "有字段"与"有内容"是两件事：工作流不需要负面时它就是空的
        val preset = base().copy(negativePrompt = "")
        val json = preset.toJson()
        // 键始终写出（不是"没有才算空"）
        assertTrue(json.has("negativePrompt"))
        assertEquals("", PromptPreset.fromJson(json)?.negativePrompt)
    }

    @Test
    fun `老数据没有负面键时读成空串`() {
        // 模拟 v0.9.2 及更早写出来的 JSON：整份里没有 negativePrompt
        val legacy = JSONObject()
            .put("id", "old")
            .put("screenType", "TEXT_TO_IMAGE")
            .put("name", "老预设")
            .put("prompt", "old prompt")
            .put("tags", org.json.JSONArray(listOf("x")))
            .put("isFavorite", false)
            .put("createdAt", 1600000000000L)

        assertTrue("老数据里确实没有这个键", !legacy.has("negativePrompt"))

        val restored = PromptPreset.fromJson(legacy)
        assertEquals("老预设", restored?.name)
        assertEquals("old prompt", restored?.prompt)
        assertEquals("老数据读成空串", "", restored?.negativePrompt)
    }

    @Test
    fun `create 会带上负面`() {
        val created = PromptPreset.create(
            screenType = ScreenType.IMAGE_TO_VIDEO,
            name = "n",
            prompt = "pos",
            tags = emptyList(),
            negativePrompt = "neg"
        )
        assertEquals("neg", created.negativePrompt)
        assertEquals(ScreenType.IMAGE_TO_VIDEO, created.screenType)
    }

    @Test
    fun `create 不传负面时是空串`() {
        val created = PromptPreset.create(
            screenType = ScreenType.TEXT_TO_VIDEO,
            name = "n",
            prompt = "pos"
        )
        assertEquals("", created.negativePrompt)
    }

    @Test
    fun `缺 id 或 screenType 的条目仍然被判为无效`() {
        // 这两条是硬性要求，不能因为多了一个可选字段就放松
        assertNull(PromptPreset.fromJson(JSONObject().put("screenType", "TEXT_TO_IMAGE")))
        assertNull(PromptPreset.fromJson(JSONObject().put("id", "x")))
    }

    @Test
    fun `负面里的换行与引号能正确往返`() {
        val tricky = base().copy(negativePrompt = "line1\nline2 \"quoted\" \\ back")
        val restored = PromptPreset.fromJson(tricky.toJson())
        assertEquals("line1\nline2 \"quoted\" \\ back", restored?.negativePrompt)
    }
}
