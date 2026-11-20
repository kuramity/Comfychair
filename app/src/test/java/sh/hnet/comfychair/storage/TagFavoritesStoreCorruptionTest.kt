package sh.hnet.comfychair.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-34（同类问题）：标签收藏存储损坏后不得用空表整份覆盖。
 */
class TagFavoritesStoreCorruptionTest {

    private class FakeIo(var content: String?) : TagFavoritesIo {
        var writeCount = 0
        var backupCalls = 0

        override fun exists(): Boolean = content != null
        override fun readText(): String = content ?: ""
        override fun writeText(text: String) {
            content = text
            writeCount++
        }

        override fun backupCorrupt(): String? {
            backupCalls++
            return "tag_favorites.json.corrupt-20261119-000000"
        }
    }

    private fun store(io: FakeIo): TagFavoritesStore =
        TagFavoritesStore(io = io, logWarn = { _, _ -> })

    @Test
    fun `损坏文件读取为空 备份一次 且拒绝写入`() {
        val io = FakeIo("{不是合法 JSON")
        val target = store(io)

        assertTrue(target.loadFavorites().isEmpty())
        assertTrue(target.loadRecents().isEmpty())
        assertTrue(target.didLastParseFail())
        assertEquals(1, io.backupCalls)

        assertFalse(target.saveFavorites(setOf("1girl")))
        assertTrue(target.pushRecent("1girl").isEmpty())
        assertEquals("{不是合法 JSON", io.content)
        assertEquals(0, io.writeCount)
    }

    @Test
    fun `正常文件可读写`() {
        val io = FakeIo(JSONObject().apply {
            put("favorites", listOf("1girl"))
            put("recents", listOf("solo", "1girl"))
        }.toString())
        val target = store(io)

        assertEquals(setOf("1girl"), target.loadFavorites())
        assertEquals(listOf("solo", "1girl"), target.loadRecents())
        assertFalse(target.didLastParseFail())
        assertEquals(0, io.backupCalls)

        assertTrue(target.saveFavorites(setOf("1girl", "long_hair")))
        assertEquals(1, io.writeCount)

        val saved = JSONObject(io.content!!)
        assertEquals(2, saved.getJSONArray("favorites").length())
    }

    @Test
    fun `正常文件下 pushRecent 去重并置顶`() {
        val io = FakeIo(JSONObject().apply {
            put("favorites", listOf<String>())
            put("recents", listOf("solo", "1girl"))
        }.toString())
        val target = store(io)

        assertEquals(listOf("1girl", "solo"), target.pushRecent("1girl"))
        assertEquals(1, io.writeCount)
    }
}