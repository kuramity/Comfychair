package sh.hnet.comfychair.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [withStableKeys] 的单元测试（第 8 章 8.3 列表与布局）。
 *
 * 这个函数是 Lazy 列表 key 的唯一来源，一旦它生成的 key 撞车，列表会在组合期直接抛
 * `Key was already used`；或者把 key 掺进下标，行状态就会在增删/重排后串行。
 * 下面几条用例分别守住这两件事。
 */
class StableKeyedItemsTest {

    @Test
    fun `空列表返回空结果`() {
        val result = emptyList<String>().withStableKeys { it }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `basis 唯一时 key 等于 basis 且保持原顺序`() {
        val versions = listOf("v4.16.0", "v4.15.0", "v4.14.0")

        val result = versions.withStableKeys { it }

        assertEquals(versions, result.map { it.value })
        assertEquals(versions, result.map { it.key })
    }

    @Test
    fun `重复 basis 追加序号后缀且全部唯一`() {
        val chunks = listOf("A", "B", "A", "A")

        val keys = chunks.withStableKeys { it }.map { it.key }

        assertEquals(listOf("A", "B", "A#dup2", "A#dup3"), keys)
        assertEquals("同一列表内 key 不得重复", keys.size, keys.toSet().size)
    }

    @Test
    fun `key 只由数据决定 与列表位置无关`() {
        val full = listOf("head", "A", "B").withStableKeys { it }
        val withoutHead = listOf("A", "B").withStableKeys { it }

        // 去掉前面的元素后，后面元素的 key 不该变（用下标当 key 的写法这里就会错位）
        assertEquals(full.drop(1).map { it.key }, withoutHead.map { it.key })
    }

    @Test
    fun `value 原样保留 引用不变`() {
        val items = listOf(Item(1, "prompt"), Item(2, "seed"))

        val result = items.withStableKeys { it.id.toString() }

        assertEquals(listOf("1", "2"), result.map { it.key })
        assertEquals(items, result.map { it.value })
    }

    @Test
    fun `不同数据即便长度相同也不会共用 key`() {
        val a = listOf("v1", "v2").withStableKeys { it }
        val b = listOf("v1", "v3").withStableKeys { it }

        assertNotEquals(a[1].key, b[1].key)
    }

    private data class Item(val id: Int, val name: String)
}
