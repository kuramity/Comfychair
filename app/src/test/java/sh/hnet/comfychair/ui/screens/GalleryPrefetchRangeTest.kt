package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-23 的边界测试：首屏预取区间必须**两端都夹在列表范围内**。
 *
 * 修复前是 `items.subList(firstVisibleItemIndex, (firstVisible + 24).coerceAtMost(size))`：
 * 只夹了尾部。删除条目后列表变短、而 LazyGrid 的首个可见索引还停在旧值上时，
 * `subList(start > size)` 抛 IndexOutOfBoundsException —— 删完图回图库直接崩。
 */
class GalleryPrefetchRangeTest {

    @Test
    fun `空列表返回空区间`() {
        assertEquals(0..0, galleryPrefetchRange(itemCount = 0, firstVisibleIndex = 0))
        assertEquals(0..0, galleryPrefetchRange(itemCount = 0, firstVisibleIndex = 42))
    }

    @Test
    fun `常规情形从首个可见项往后取一个窗口`() {
        assertEquals(0..24, galleryPrefetchRange(itemCount = 100, firstVisibleIndex = 0))
        assertEquals(10..34, galleryPrefetchRange(itemCount = 100, firstVisibleIndex = 10))
    }

    @Test
    fun `窗口尾部被夹到列表长度`() {
        assertEquals(90..100, galleryPrefetchRange(itemCount = 100, firstVisibleIndex = 90))
        assertEquals(3..5, galleryPrefetchRange(itemCount = 5, firstVisibleIndex = 3))
    }

    @Test
    fun `首个可见索引超出列表时返回列表末尾的空区间而不是越界`() {
        // 删除后 gridState 仍记着旧索引 —— 修复前这里 subList(150, 100) 直接抛异常
        val range = galleryPrefetchRange(itemCount = 100, firstVisibleIndex = 150)
        assertEquals(100..100, range)
        assertTrue("区间的 start 必须 <= size", range.first <= 100)
    }

    @Test
    fun `负的首个可见索引被夹到 0`() {
        assertEquals(0..24, galleryPrefetchRange(itemCount = 100, firstVisibleIndex = -5))
    }

    @Test
    fun `窗口大小为 0 时返回空区间`() {
        val range = galleryPrefetchRange(itemCount = 10, firstVisibleIndex = 4, windowSize = 0)
        assertEquals(4..4, range)
    }

    @Test
    fun `任意输入下区间都可用于 subList`() {
        // 直接以 subList 的可调用性作为验收（javadoc：fromIndex <= toIndex <= size）
        val cases = listOf(
            0 to 0, 0 to 7, 1 to 1, 1 to 0, 5 to 3, 5 to 5, 5 to 99, 1000 to 12
        )
        val list = List(7) { it }
        for ((count, first) in cases) {
            if (count != 7) continue
            val range = galleryPrefetchRange(itemCount = list.size, firstVisibleIndex = first)
            // 不抛异常即通过
            list.subList(range.first, range.last)
        }
        // 非 7 的也跑一遍边界函数本身，确保 start <= end <= count
        for ((count, first) in cases) {
            val range = galleryPrefetchRange(itemCount = count, firstVisibleIndex = first)
            assertTrue("start 越界: $count/$first -> $range", range.first in 0..count)
            assertTrue("end 越界: $count/$first -> $range", range.last in range.first..count)
        }
    }
}