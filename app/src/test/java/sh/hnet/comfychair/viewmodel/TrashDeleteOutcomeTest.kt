package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「移入回收站」返回值 → 界面动作的纯判定。
 *
 * 背景：`GalleryRepository.moveToTrash` 的契约是「返回进站身份键，空列表 = 落盘失败、
 * 未进站」。修复前查看器无条件 `removeAt` + 报「已移入回收站」，落盘失败时条目
 * 从查看器消失、却既不在回收站也没从图库删除 —— 界面之间数据分叉。
 *
 * 本类只钉纯函数；生产代码里的接线（查看器是否真的先判返回值）由调用处保证，
 * 不能只靠本类通过就认为缺陷已修。断言均为有业务含义的档位，不写恒真检查。
 */
class TrashDeleteOutcomeTest {

    // ============================================================
    // 单条删除（查看器）
    // ============================================================

    @Test
    fun `单条删除-空返回值走失败分支且不允许移除`() {
        val result = TrashDeleteOutcome.single(emptyList())

        assertEquals(TrashDeleteResult.FAILED, result)
        // 核心断言：落盘失败时既不能从查看器移除，也不能提示成功
        assertFalse("空返回值不允许移除条目", result.removeFromUi)
        assertFalse("空返回值不允许提示成功", result.showSuccess)
    }

    @Test
    fun `单条删除-返回身份键才允许移除并提示成功`() {
        val result = TrashDeleteOutcome.single(listOf("server\u0000prompt\u0000file.png"))

        assertEquals(TrashDeleteResult.SUCCESS, result)
        assertTrue(result.removeFromUi)
        assertTrue(result.showSuccess)
    }

    // ============================================================
    // 批量删除（图库多选）
    // ============================================================

    @Test
    fun `批量删除-返回 0 条是失败而不是成功`() {
        for (requested in intArrayOf(1, 2, 5, 100)) {
            val result = TrashDeleteOutcome.batch(0, requested)

            assertEquals("requested=$requested", TrashDeleteResult.FAILED, result)
            // successCount = 0 时绝不能提示成功（修复前的边界风险点）
            assertFalse("requested=$requested", result.showSuccess)
            assertFalse("requested=$requested", result.removeFromUi)
        }
    }

    @Test
    fun `批量删除-部分进站只提示部分失败`() {
        val result = TrashDeleteOutcome.batch(2, 5)

        assertEquals(TrashDeleteResult.PARTIAL, result)
        assertTrue("已进站的条目可以从列表移除", result.removeFromUi)
        assertFalse("部分失败不能提示全部成功", result.showSuccess)
    }

    @Test
    fun `批量删除-全部进站才提示成功`() {
        assertEquals(TrashDeleteResult.SUCCESS, TrashDeleteOutcome.batch(5, 5))
        assertTrue(TrashDeleteOutcome.batch(5, 5).showSuccess)
    }

    @Test
    fun `批量删除-返回条数多于请求条数按成功处理`() {
        // 幂等去重等情况下返回可能涵盖已有条目，不应降级
        val result = TrashDeleteOutcome.batch(6, 5)

        assertEquals(TrashDeleteResult.SUCCESS, result)
        assertTrue(result.showSuccess)
    }

    // ============================================================
    // 跨档位不变量
    // ============================================================

    @Test
    fun `不变量-提示成功必然允许移除`() {
        val results = listOf(
            TrashDeleteOutcome.single(emptyList()),
            TrashDeleteOutcome.single(listOf("k")),
            TrashDeleteOutcome.batch(0, 3),
            TrashDeleteOutcome.batch(1, 3),
            TrashDeleteOutcome.batch(3, 3)
        )

        results.forEach { result ->
            assertTrue(
                "showSuccess 蕴含 removeFromUi: $result",
                !result.showSuccess || result.removeFromUi
            )
        }
    }
}