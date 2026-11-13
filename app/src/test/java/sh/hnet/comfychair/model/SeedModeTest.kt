package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 种子取值方式（四态）与推进算法。
 *
 * [nextSeed] 的边界是"算错也不报错"的那类：种子挪到负值、或超过节点上限，
 * 提交时会被服务器拒（或静默换一个种子），而界面上看不出哪里不对。
 */
class SeedModeTest {

    // ------------------------------------------------------------ 模式语义

    @Test
    fun `只有随机模式才让引擎随机取值`() {
        assertTrue(SeedMode.RANDOM.usesRandomValue)
        // 递增/递减必须用输入框里的当前值当起点，否则每次都随机，"递增"就没有意义了
        assertFalse(SeedMode.FIXED.usesRandomValue)
        assertFalse(SeedMode.INCREMENT.usesRandomValue)
        assertFalse(SeedMode.DECREMENT.usesRandomValue)
    }

    @Test
    fun `只有递增递减会在生成后挪种子`() {
        assertTrue(SeedMode.INCREMENT.advances)
        assertTrue(SeedMode.DECREMENT.advances)
        assertFalse(SeedMode.FIXED.advances)
        assertFalse(SeedMode.RANDOM.advances)
        assertEquals(1, SeedMode.INCREMENT.step)
        assertEquals(-1, SeedMode.DECREMENT.step)
    }

    @Test
    fun `随机模式下种子输入框不可编辑其余三种可以`() {
        assertFalse(SeedMode.RANDOM.isSeedEditable)
        assertTrue(SeedMode.FIXED.isSeedEditable)
        assertTrue(SeedMode.INCREMENT.isSeedEditable)
        assertTrue(SeedMode.DECREMENT.isSeedEditable)
    }

    // ------------------------------------------------------ 旧数据兼容

    @Test
    fun `旧数据只有 randomSeed 布尔时按 true随机 false固定 还原`() {
        assertEquals(SeedMode.RANDOM, SeedMode.fromSaved(null, true))
        assertEquals(SeedMode.FIXED, SeedMode.fromSaved(null, false))
        // 两个键都没有时退回默认（与改动前 randomSeed = true 一致）
        assertEquals(SeedMode.RANDOM, SeedMode.fromSaved(null, null))
    }

    @Test
    fun `新键存在时以新键为准`() {
        // 老键还写着 true（随机），新键明确是递增 —— 必须听新键的
        assertEquals(SeedMode.INCREMENT, SeedMode.fromSaved("INCREMENT", true))
        assertEquals(SeedMode.DECREMENT, SeedMode.fromSaved("DECREMENT", null))
        assertEquals(SeedMode.FIXED, SeedMode.fromSaved("FIXED", true))
    }

    @Test
    fun `认不出的模式名退回随机`() {
        assertEquals(SeedMode.RANDOM, SeedMode.fromName("WHATEVER"))
        assertEquals(SeedMode.RANDOM, SeedMode.fromName(null))
        assertEquals(SeedMode.RANDOM, SeedMode.fromSaved("WHATEVER", false))
    }

    // ------------------------------------------------------------ 推进算法

    @Test
    fun `递增与递减各走一格`() {
        assertEquals(101L, nextSeed(current = 100L, step = 1, min = 0L, max = 1000L))
        assertEquals(99L, nextSeed(current = 100L, step = -1, min = 0L, max = 1000L))
    }

    @Test
    fun `递增超过上限时回绕到下限`() {
        assertEquals(0L, nextSeed(current = 1000L, step = 1, min = 0L, max = 1000L))
    }

    @Test
    fun `递减低于下限时停在下限而不是回绕到上限`() {
        // 刻意不回绕：上限值来自 /object_info 的 Float 精度，回绕过去可能正好落在
        // 一个超出节点的值上，服务器直接 400
        assertEquals(0L, nextSeed(current = 0L, step = -1, min = 0L, max = 1000L))
    }

    @Test
    fun `下限不为零时同样守住`() {
        assertEquals(5L, nextSeed(current = 5L, step = -1, min = 5L, max = 100L))
        assertEquals(5L, nextSeed(current = 100L, step = 1, min = 5L, max = 100L))
    }

    @Test
    fun `起点已在区间外时先夹进区间再挪`() {
        // 手工输入过大的种子（输入框允许）之后点递增：先夹回上限，再按规则处理
        assertEquals(0L, nextSeed(current = 99999L, step = 1, min = 0L, max = 1000L))
        // 过小的先夹到下限，再正常 +1（所以是 1 而不是 0 —— 夹取发生在挪动之前）
        assertEquals(1L, nextSeed(current = -50L, step = 1, min = 0L, max = 1000L))
    }

    @Test
    fun `约束畸形时不吐越界值`() {
        // max < min 是畸形约束（理论上不该出现）：退回"夹到下限"，
        // 而不是吐一个连下限都不满足的原值出去
        assertEquals(100L, nextSeed(current = 7L, step = 1, min = 100L, max = 0L))
    }
}
