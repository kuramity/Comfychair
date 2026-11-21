package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空列表 / 索引失效边界的纯函数测试（P0：删空图库后再进图库闪退）。
 *
 * 覆盖 size = 0 / 1 / 中间 / 末尾 / 越界 / 负数六类输入，逐条钉住：
 * - 查看器首屏页永远落在合法区间；
 * - 空列表时打开查看器返回 null（调用方必须拦截，不能启动 Activity）；
 * - 网格恢复初值在空列表下为 0；
 * - 撤销插回位置允许等于 size（List.add 的合法上界）。
 *
 * 注意：本类只证明**纯函数**的边界行为。若把生产代码里的空列表保护删掉，
 * 由 [sh.hnet.comfychair.ViewerEmptyStateWiringTest] 的源码级接线钉子负责变红；
 * 本类不写「0.coerceIn(0, -1) 会抛」这类只证明 stdlib 行为的恒真断言（复核 L5）。
 */
class ViewerIndexBoundsTest {

    // ============================================================
    // viewerInitialPage
    // ============================================================

    @Test
    fun `查看器首屏页-空列表返回 0 且不抛`() {
        assertEquals(0, viewerInitialPage(0, 0))
        assertEquals(0, viewerInitialPage(7, 0))
        assertEquals(0, viewerInitialPage(-7, 0))
    }

    @Test
    fun `查看器首屏页-单元素都夹到 0`() {
        assertEquals(0, viewerInitialPage(0, 1))
        assertEquals(0, viewerInitialPage(1, 1))
        assertEquals(0, viewerInitialPage(99, 1))
        assertEquals(0, viewerInitialPage(-99, 1))
    }

    @Test
    fun `查看器首屏页-中间原样返回`() {
        assertEquals(0, viewerInitialPage(0, 10))
        assertEquals(3, viewerInitialPage(3, 10))
        assertEquals(9, viewerInitialPage(9, 10))
    }

    @Test
    fun `查看器首屏页-末尾与越界都被夹到最后一个`() {
        assertEquals(9, viewerInitialPage(10, 10))
        assertEquals(9, viewerInitialPage(1000, 10))
        assertEquals(0, viewerInitialPage(-1, 10))
    }

    @Test
    fun `查看器首屏页-任意输入都落在 0 到 itemCount 减 1`() {
        val itemCounts = listOf(0, 1, 2, 7, 100)
        val indices = listOf(-1000, -1, 0, 1, 3, 50, 99, 100, 1000)
        for (count in itemCounts) {
            for (index in indices) {
                val page = viewerInitialPage(index, count)
                if (count == 0) {
                    assertEquals("空列表只能返回 0", 0, page)
                } else {
                    assertTrue(
                        "page=$page 越界: index=$index count=$count",
                        page in 0 until count
                    )
                }
            }
        }
    }

    // ============================================================
    // galleryOpenIndex
    // ============================================================

    @Test
    fun `打开查看器索引-空列表返回 null`() {
        assertNull(galleryOpenIndex(0, 0))
        assertNull(galleryOpenIndex(5, 0))
        assertNull(galleryOpenIndex(-5, 0))
    }

    @Test
    fun `打开查看器索引-单元素都夹到 0`() {
        assertEquals(0, galleryOpenIndex(0, 1))
        assertEquals(0, galleryOpenIndex(8, 1))
        assertEquals(0, galleryOpenIndex(-8, 1))
    }

    @Test
    fun `打开查看器索引-中间原样返回`() {
        assertEquals(3, galleryOpenIndex(3, 10))
    }

    @Test
    fun `打开查看器索引-越界被夹到合法区间`() {
        assertEquals(0, galleryOpenIndex(-1, 10))
        assertEquals(9, galleryOpenIndex(10, 10))
        assertEquals(9, galleryOpenIndex(999, 10))
    }

    // ============================================================
    // galleryInitialFirstVisibleItemIndex
    // ============================================================

    @Test
    fun `网格恢复初值-空列表返回 0 且不抛`() {
        assertEquals(0, galleryInitialFirstVisibleItemIndex(0, 0))
        assertEquals(0, galleryInitialFirstVisibleItemIndex(0, 42))
        assertEquals(0, galleryInitialFirstVisibleItemIndex(0, -1))
    }

    @Test
    fun `网格恢复初值-单元素夹到 0`() {
        assertEquals(0, galleryInitialFirstVisibleItemIndex(1, 0))
        assertEquals(0, galleryInitialFirstVisibleItemIndex(1, 5))
    }

    @Test
    fun `网格恢复初值-中间原样返回`() {
        assertEquals(4, galleryInitialFirstVisibleItemIndex(20, 4))
    }

    @Test
    fun `网格恢复初值-删除后旧索引大于新长度时夹到末尾`() {
        // 删除前 firstVisibleItemIndex=30，删到只剩 5 张
        assertEquals(4, galleryInitialFirstVisibleItemIndex(5, 30))
        assertEquals(4, galleryInitialFirstVisibleItemIndex(5, 5))
        assertEquals(0, galleryInitialFirstVisibleItemIndex(5, -3))
    }

    // ============================================================
    // viewerInsertIndex
    // ============================================================

    @Test
    fun `撤销插回-空列表插到 0`() {
        assertEquals(0, viewerInsertIndex(0, 0))
        assertEquals(0, viewerInsertIndex(5, 0))
        assertEquals(0, viewerInsertIndex(-5, 0))
    }

    @Test
    fun `撤销插回-中间原样返回`() {
        assertEquals(3, viewerInsertIndex(3, 10))
    }

    @Test
    fun `撤销插回-允许插到末尾`() {
        assertEquals(9, viewerInsertIndex(9, 9))
        assertEquals(9, viewerInsertIndex(10, 9))
    }

    @Test
    fun `撤销插回-负索引夹到 0`() {
        assertEquals(0, viewerInsertIndex(-1, 3))
    }

    @Test
    fun `撤销插回-任意输入都可用于 List_add`() {
        val itemCounts = listOf(0, 1, 5, 20)
        val indices = listOf(-100, -1, 0, 1, 19, 20, 21, 100)
        for (count in itemCounts) {
            for (index in indices) {
                val at = viewerInsertIndex(index, count)
                val list = MutableList(count) { it }
                // 不抛异常即通过（add 的合法区间是 0..size）
                list.add(at, -1)
                assertEquals(count + 1, list.size)
            }
        }
    }
}