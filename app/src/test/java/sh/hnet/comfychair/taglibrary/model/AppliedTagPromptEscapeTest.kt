package sh.hnet.comfychair.taglibrary.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.comfychair.taglibrary.state.PromptBridge

/**
 * P1-31：标签插入的提示词语法转义。
 *
 * 背景：Danbooru 标签名本身可能含圆括号（`papyrus_(salon_pixiv)`），不转义会被
 * ComfyUI 的 `parse_parentheses` 当成权重分组拆开。所有插入路径（追加、指定位置
 * 插入、改权重）都经 [AppliedTag.toPromptToken]。
 *
 * 转义集合按 ComfyUI 实际解析面收敛为**仅圆括号**：`comfy/sd1_clip.py` 的
 * `escape_important` / `unescape_important` 只还原 `\(` `\)`，冒号/方括号/反斜杠
 * 前的反斜杠会原样进入 tokenizer，反而破坏标签文本（F2 复核结论）。
 */
class AppliedTagPromptEscapeTest {

    @Test
    fun `普通标签原样输出`() {
        assertEquals("long_hair", AppliedTag("long_hair").toPromptToken())
        assertEquals("1girl", AppliedTag("1girl", 1.0f).toPromptToken())
    }

    @Test
    fun `圆括号被转义`() {
        assertEquals(
            "papyrus_\\(salon_pixiv\\)",
            AppliedTag("papyrus_(salon_pixiv)").toPromptToken()
        )
    }

    @Test
    fun `冒号原样保留 不追加反斜杠`() {
        assertEquals("honkai:_star_rail", AppliedTag("honkai:_star_rail").toPromptToken())
    }

    @Test
    fun `方括号原样保留 不追加反斜杠`() {
        assertEquals("[left]right", AppliedTag("[left]right").toPromptToken())
    }

    @Test
    fun `反斜杠原样保留 不追加反斜杠`() {
        assertEquals("a\\b", AppliedTag("a\\b").toPromptToken())
    }

    @Test
    fun `反斜杠加圆括号只转义圆括号`() {
        // 输入 a\(b；输出 a\\(b —— ComfyUI 的 escape_important 会把其中的 \( 还原成
        // 字面括号，最终 tokenizer 看到的是原始标签 a\(b
        assertEquals("a\\\\(b", AppliedTag("a\\(b").toPromptToken())
    }

    @Test
    fun `混合字符只转义圆括号`() {
        // 输入：a(b)c:d[e]f\g
        // 输出：a\(b\)c:d[e]f\g（只有圆括号前多出反斜杠）
        assertEquals(
            "a\\(b\\)c:d[e]f\\g",
            AppliedTag("a(b)c:d[e]f\\g").toPromptToken()
        )
    }

    @Test
    fun `带权重时只转义标签名 不转义权重括号`() {
        assertEquals(
            "(papyrus_\\(salon_pixiv\\):1.2)",
            AppliedTag("papyrus_(salon_pixiv)", 1.2f).toPromptToken()
        )
    }

    @Test
    fun `改权重能命中已转义的标签`() {
        val text = "1girl, papyrus_\\(salon_pixiv\\), solo, "
        assertEquals(
            "1girl, (papyrus_\\(salon_pixiv\\):1.3), solo, ",
            PromptBridge.replaceFirstWeight(text, "papyrus_(salon_pixiv)", 1.3f)
        )
    }

    @Test
    fun `改权重能命中已转义且带权重的标签`() {
        val text = "1girl, (papyrus_\\(salon_pixiv\\):1.2), solo, "
        assertEquals(
            "1girl, (papyrus_\\(salon_pixiv\\):1.5), solo, ",
            PromptBridge.replaceFirstWeight(text, "papyrus_(salon_pixiv)", 1.5f)
        )
    }

    @Test
    fun `改权重兼容未转义的历史写法`() {
        val text = "1girl, papyrus_(salon_pixiv), solo, "
        assertEquals(
            "1girl, (papyrus_\\(salon_pixiv\\):1.3), solo, ",
            PromptBridge.replaceFirstWeight(text, "papyrus_(salon_pixiv)", 1.3f)
        )
    }

    @Test
    fun `提示词里找不到标签时返回 null`() {
        assertNull(PromptBridge.replaceFirstWeight("1girl, solo, ", "papyrus_(salon_pixiv)", 1.2f))
    }
}