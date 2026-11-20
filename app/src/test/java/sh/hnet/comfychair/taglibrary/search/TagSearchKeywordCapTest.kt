package sh.hnet.comfychair.taglibrary.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-33：关键词长度上限（超长输入会放大 LIKE 全表扫描的代价）。
 */
class TagSearchKeywordCapTest {

    @Test
    fun `超长关键词截断到 64 字符`() {
        val raw = "a".repeat(200)
        assertEquals(64, TagSearcher.normalizeKeyword(raw).length)
        assertEquals("a".repeat(64), TagSearcher.normalizeKeyword(raw))
    }

    @Test
    fun `首尾空白先去掉再截断`() {
        assertEquals("long_hair", TagSearcher.normalizeKeyword("  long_hair \n"))
    }

    @Test
    fun `纯空白归一化为空串`() {
        assertEquals("", TagSearcher.normalizeKeyword("   \t "))
    }
}