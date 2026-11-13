package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 片段草稿的两个长度口径。
 *
 * 这两条都是"夹取口径不一致"会静默毁掉用户设置的地方：用户点一下输入框、什么都没改，
 * 片段就被压到 5 帧、或者长度被算成 0 秒。而界面上只会看到一个变小的数字，没有任何提示。
 */
class TimelineClipDraftTest {

    private fun draft(
        duration: Double = 5.0,
        trimStart: Double = 0.0,
        sourceDuration: Double = 0.0,
        start: Double = 0.0
    ) = TimelineClipDraft(
        id = "c1",
        uri = null,
        displayName = "a.mp4",
        start = start,
        duration = duration,
        trimStart = trimStart,
        sourceDuration = sourceDuration,
        hasAudio = true,
        referenceMode = TimelineReferenceMode.GUIDE,
        uploadedFile = "a.mp4"
    )

    @Test
    fun `源时长缺失时按入点加占用长度兜底`() {
        // 电脑端旧数据里 sourceDuration 可能是 0。此时源的"总长"至少是"入点 + 用到的那段"
        val d = draft(duration = 5.0, trimStart = 2.0, sourceDuration = 0.0)
        assertEquals(7.0, d.sourceLengthSeconds, 1e-9)
        assertEquals(5.0, d.availableSourceSeconds, 1e-9)
    }

    @Test
    fun `源时长存在时以它为准`() {
        val d = draft(duration = 5.0, trimStart = 2.0, sourceDuration = 20.0)
        assertEquals(20.0, d.sourceLengthSeconds, 1e-9)
        assertEquals(18.0, d.availableSourceSeconds, 1e-9)
    }

    @Test
    fun `源时长缺失时不会把片段长度算成零`() {
        // 这就是那个坑的直接表现：拿裸 sourceDuration（0）去算可用长度，
        // 结果是 0 秒，片段会被夹到下限
        val d = draft(duration = 5.0, trimStart = 3.0, sourceDuration = 0.0)
        assertTrue("可用长度不该是 0", d.availableSourceSeconds > 0.0)
    }

    @Test
    fun `片段的长度下限用插件的五帧而不是生成时长下限`() {
        // clampSelectionDuration 的下限是 0.21（节点 generation_seconds 的 min），
        // 拿它去夹片段会让"最短片段"比插件认的窗口长一点点 —— 两件事的下限不能混用
        val clip = draft(duration = 0.05, sourceDuration = 10.0).toClip("a.mp4")
        assertEquals(TimelineDocument.MIN_CLIP_SECONDS, clip.duration, 1e-12)
        assertTrue(clip.duration < TimelineDocument.MIN_SELECTION_SECONDS)
    }

    @Test
    fun `长度不超过源的剩余部分`() {
        val clip = draft(duration = 8.0, trimStart = 1.0, sourceDuration = 4.0).toClip("a.mp4")
        // 源只剩 3 秒，时长要缩到 3 秒（否则界面显示 8 秒、插件实际只取到 3 秒）
        assertEquals(3.0, clip.duration, 1e-9)
    }

    @Test
    fun `新片段接在已有片段之后`() {
        val clips = listOf(
            draft(duration = 2.0, start = 0.0),
            draft(duration = 3.5, start = 4.0).copy(id = "c2")
        )
        assertEquals(7.5, nextClipStart(clips), 1e-9)
        assertEquals(0.0, nextClipStart(emptyList()), 1e-9)
    }
}
