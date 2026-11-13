package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线导演台的**空隙计算**。
 *
 * 这是整个片段编辑器里唯一有"算错也不报错"风险的逻辑：空隙算多了，界面上会出现一段
 * 实际不存在的可生成区（点了生成出来的是贴着原片段的画面）；算少了，用户看不到
 * 真正能补的那一段。所以这里把边界情况逐个钉死 —— 相邻、重叠、包含、擦边、
 * 完全在选区外。
 */
class TimelineGapTest {

    private fun clip(
        start: Double,
        duration: Double,
        id: String = "c"
    ) = TimelineClip(
        id = id,
        file = "clip.mp4",
        name = "clip.mp4",
        start = start,
        duration = duration,
        trimStart = 0.0,
        sourceDuration = 60.0,
        hasAudio = true,
        referenceMode = TimelineReferenceMode.GUIDE
    )

    private fun gaps(selectionStart: Double, selectionDuration: Double, vararg clips: TimelineClip) =
        uncoveredIntervals(selectionStart, selectionDuration, clips.toList())

    @Test
    fun `没有片段时整段选区都是空隙`() {
        val result = gaps(0.0, 5.0)
        assertEquals(1, result.size)
        assertEquals(0.0, result[0].start, 1e-9)
        assertEquals(5.0, result[0].end, 1e-9)
    }

    @Test
    fun `片段完全覆盖选区时没有空隙`() {
        assertTrue(gaps(0.0, 5.0, clip(0.0, 5.0)).isEmpty())
        // 片段比选区长、两端都超出，同样没有空隙
        assertTrue(gaps(2.0, 3.0, clip(0.0, 10.0)).isEmpty())
    }

    @Test
    fun `片段只盖住选区前半时后半是空隙`() {
        val result = gaps(0.0, 5.0, clip(0.0, 2.0))
        assertEquals(1, result.size)
        assertEquals(2.0, result[0].start, 1e-9)
        assertEquals(5.0, result[0].end, 1e-9)
    }

    @Test
    fun `片段只盖住选区后半时前半是空隙`() {
        val result = gaps(0.0, 5.0, clip(3.0, 2.0))
        assertEquals(1, result.size)
        assertEquals(0.0, result[0].start, 1e-9)
        assertEquals(3.0, result[0].end, 1e-9)
    }

    @Test
    fun `两段之间的缝就是空隙`() {
        // 这就是这台导演台最常用的用法：前段 0-2、后段 5-7，中间 2-5 是要生成的内容
        val result = gaps(0.0, 7.0, clip(0.0, 2.0, "a"), clip(5.0, 2.0, "b"))
        assertEquals(1, result.size)
        assertEquals(2.0, result[0].start, 1e-9)
        assertEquals(5.0, result[0].end, 1e-9)
    }

    @Test
    fun `首尾相接的两段之间没有空隙`() {
        // 贴边放（前段结束 == 后段开始）按一帧的容差判定为连续 ——
        // 否则界面上会出现一个 0.0001 秒的"空隙"，点了生成却是贴边固定，读数自相矛盾
        assertTrue(gaps(0.0, 5.0, clip(0.0, 2.0, "a"), clip(2.0, 3.0, "b")).isEmpty())
    }

    @Test
    fun `重叠的两段不会把重合部分算成空隙`() {
        assertTrue(gaps(0.0, 5.0, clip(0.0, 3.0, "a"), clip(2.0, 3.0, "b")).isEmpty())
    }

    @Test
    fun `选区两侧都有片段时只列出中间那段`() {
        val result = gaps(1.0, 8.0, clip(0.0, 3.0, "a"), clip(6.0, 5.0, "b"))
        assertEquals(1, result.size)
        assertEquals(3.0, result[0].start, 1e-9)
        assertEquals(6.0, result[0].end, 1e-9)
    }

    @Test
    fun `完全在选区外的片段不影响空隙`() {
        val before = gaps(5.0, 5.0, clip(0.0, 3.0))
        assertEquals(1, before.size)
        assertEquals(5.0, before[0].start, 1e-9)
        assertEquals(10.0, before[0].end, 1e-9)

        val after = gaps(0.0, 5.0, clip(8.0, 3.0))
        assertEquals(1, after.size)
        assertEquals(0.0, after[0].start, 1e-9)
        assertEquals(5.0, after[0].end, 1e-9)
    }

    @Test
    fun `零长度选区没有空隙`() {
        assertTrue(gaps(3.0, 0.0, clip(0.0, 2.0)).isEmpty())
        assertTrue(gaps(3.0, -1.0).isEmpty())
    }

    @Test
    fun `多个空隙按时间升序返回`() {
        val result = gaps(0.0, 10.0, clip(2.0, 1.0, "a"), clip(6.0, 1.0, "b"))
        assertEquals(3, result.size)
        assertEquals(0.0, result[0].start, 1e-9)
        assertEquals(2.0, result[0].end, 1e-9)
        assertEquals(3.0, result[1].start, 1e-9)
        assertEquals(6.0, result[1].end, 1e-9)
        assertEquals(7.0, result[2].start, 1e-9)
        assertEquals(10.0, result[2].end, 1e-9)
    }

    @Test
    fun `覆盖时长与空隙互补且不超过选区长度`() {
        val clips = listOf(clip(0.0, 2.0, "a"), clip(6.0, 10.0, "b"))
        val covered = coveredSeconds(0.0, 5.0, clips)
        val uncovered = uncoveredIntervals(0.0, 5.0, clips).sumOf { it.duration }
        assertEquals(5.0, covered + uncovered, 1e-9)
        assertEquals(2.0, covered, 1e-9)
    }

    @Test
    fun `时长的下界按插件的一帧容差判定`() {
        // 空隙比一帧还窄（0.02 秒）：按插件 1/24 的容差不算空隙
        assertTrue(gaps(0.0, 5.0, clip(0.0, 2.0, "a"), clip(2.02, 3.0, "b")).isEmpty())
        // 空隙超过一帧：算一个空隙
        assertEquals(1, gaps(0.0, 5.0, clip(0.0, 2.0, "a"), clip(2.1, 3.0, "b")).size)
    }
}
