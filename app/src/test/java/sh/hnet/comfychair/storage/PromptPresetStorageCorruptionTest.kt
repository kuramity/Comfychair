package sh.hnet.comfychair.storage

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.model.ScreenType

/**
 * P1-34：预设存储损坏后不得整库覆盖。
 *
 * 原实现：`getPresets()` 解析失败返回空列表 → 随后新增/修改/删除都按
 * 「空 + 1 条」整份写回，用户原有预设被彻底覆盖。
 * 修复后：解析失败先备份原文件（`.corrupt-<时间戳>`），本次运行内写操作一律拒绝。
 */
class PromptPresetStorageCorruptionTest {

    private class FakeStore(var raw: String?) : PromptPresetKeyValueStore {
        var writeCount = 0

        override fun getString(key: String): String? = raw

        override fun putString(key: String, value: String) {
            raw = value
            writeCount++
        }

        override fun remove(key: String) {
            raw = null
            writeCount++
        }
    }

    private class FakeBackup(
        private val name: String? = "PromptPresetPrefs.xml.corrupt-20261119-000000"
    ) : PromptPresetCorruptBackup {
        var calls = 0

        override fun backup(stamp: String): String? {
            calls++
            return name
        }
    }

    private fun preset(id: String): PromptPreset = PromptPreset(
        id = id,
        screenType = ScreenType.TEXT_TO_IMAGE,
        name = "预设-$id",
        prompt = "1girl, solo",
        tags = emptyList(),
        isFavorite = false,
        createdAt = 0L
    )

    private fun storage(store: FakeStore, backup: FakeBackup): PromptPresetStorage =
        PromptPresetStorage(
            store = store,
            backupCorrupt = backup,
            stampProvider = { "20261119-000000" },
            logWarn = { _, _ -> }
        )

    @Test
    fun `损坏数据读取返回空并备份原文件`() {
        val store = FakeStore("{不是合法 JSON")
        val backup = FakeBackup()
        val target = storage(store, backup)

        assertTrue(target.getPresets().isEmpty())
        assertTrue(target.didLastParseFail())
        assertEquals(1, backup.calls)
        assertEquals("{不是合法 JSON", store.raw)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `损坏后新增被拒绝且原数据未被覆盖`() {
        val store = FakeStore("{不是合法 JSON")
        val backup = FakeBackup()
        val target = storage(store, backup)

        // 不先显式读取，直接新增：内部会先读，触发损坏检测后必须拒绝
        assertFalse(target.addPreset(preset("a")))
        assertEquals("{不是合法 JSON", store.raw)
        assertEquals(0, store.writeCount)
        assertEquals(1, backup.calls)
        assertTrue(target.didLastParseFail())
    }

    @Test
    fun `损坏后修改与删除都不写入`() {
        val store = FakeStore("[{\"id\":\"a\"}")
        val backup = FakeBackup()
        val target = storage(store, backup)

        assertFalse(target.updatePreset(preset("a")))
        assertFalse(target.deletePreset("a"))
        assertFalse(target.toggleFavorite("a"))
        assertEquals("[{\"id\":\"a\"}", store.raw)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `同一时间戳只备份一次`() {
        val store = FakeStore("{坏数据")
        val backup = FakeBackup()
        val target = storage(store, backup)

        repeat(3) { target.getPresets() }
        assertEquals(1, backup.calls)
    }

    @Test
    fun `正常数据可读写且不触发备份`() {
        val store = FakeStore(JSONArray().put(preset("a").toJson()).toString())
        val backup = FakeBackup()
        val target = storage(store, backup)

        assertEquals(1, target.getPresets().size)
        assertFalse(target.didLastParseFail())
        assertEquals(0, backup.calls)

        assertTrue(target.addPreset(preset("b")))
        assertEquals(1, store.writeCount)
        assertEquals(2, JSONArray(store.raw!!).length())
        assertEquals("预设-b", target.getPreset("b")?.name)
    }

    @Test
    fun `清空全部是显式重置 允许执行并解除拒绝状态`() {
        val store = FakeStore("{坏数据")
        val backup = FakeBackup()
        val target = storage(store, backup)

        assertTrue(target.getPresets().isEmpty())
        assertTrue(target.didLastParseFail())

        target.clearAll()
        assertNull(store.raw)
        assertFalse(target.didLastParseFail())

        // 清空后可以正常写入
        assertTrue(target.addPreset(preset("c")))
        assertEquals(1, JSONArray(store.raw!!).length())
    }
}