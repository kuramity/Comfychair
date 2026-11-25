package sh.hnet.comfychair.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [GenerationNotifier] 的四个纯判定：
 * - [GenerationNotifier.titleResFor]：ownerId → 模式名资源（通知标题的数据源）；
 * - [GenerationNotifier.percentOf]：value/max → 百分比（max<=0 必须返回 null，不许假数字）；
 * - [GenerationNotifier.shouldEmitProgress]：进度通知节流（ComfyUI 每步一条，直传太耗电）；
 * - [GenerationNotifier.summarizeFailure]：失败原因摘要截断。
 *
 * 真实的 notify / 渠道 / PendingIntent 是 Android 框架对象，JVM 单测够不着；
 * 这四个函数覆盖了通知内容的全部决策逻辑（显示什么 / 显示多少 / 何时更新）。
 */
class GenerationNotifierPolicyTest {

    // ── ownerId → 模式名 ──────────────────────────────────────────

    @Test
    fun `四个页面 ownerId 映射到对应模式名`() {
        assertEquals(sh.hnet.comfychair.R.string.nav_text_to_image, GenerationNotifier.titleResFor("TEXT_TO_IMAGE"))
        assertEquals(sh.hnet.comfychair.R.string.nav_image_to_image, GenerationNotifier.titleResFor("IMAGE_TO_IMAGE"))
        assertEquals(sh.hnet.comfychair.R.string.nav_text_to_video, GenerationNotifier.titleResFor("TEXT_TO_VIDEO"))
        assertEquals(sh.hnet.comfychair.R.string.nav_image_to_video, GenerationNotifier.titleResFor("IMAGE_TO_VIDEO"))
    }

    @Test
    fun `未知 ownerId 与 null 回落到泛化标题`() {
        // 外部任务 / 未登记时不显示具体模式名，避免把别人的任务挂上错误标题
        assertEquals(sh.hnet.comfychair.R.string.notify_generation_generic_title, GenerationNotifier.titleResFor("SOMETHING_ELSE"))
        assertEquals(sh.hnet.comfychair.R.string.notify_generation_generic_title, GenerationNotifier.titleResFor(null))
    }

    // ── 百分比计算 ────────────────────────────────────────────────

    @Test
    fun `百分比按 value 和 max 折算并夹在 0-100`() {
        assertEquals(0, GenerationNotifier.percentOf(0, 20))
        assertEquals(50, GenerationNotifier.percentOf(10, 20))
        assertEquals(100, GenerationNotifier.percentOf(20, 20))
    }

    @Test
    fun `max 小于等于 0 时返回 null 不显示假百分比`() {
        // ComfyUI 个别节点的 progress max 为 0：显示"生成中…"而不是 0% 假数字
        assertNull(GenerationNotifier.percentOf(5, 0))
        assertNull(GenerationNotifier.percentOf(5, -1))
    }

    @Test
    fun `value 超过 max 时百分比夹到 100`() {
        // 个别节点上报 value > max（步数统计偏差）：夹上限，不显示 >100%
        assertEquals(100, GenerationNotifier.percentOf(25, 20))
    }

    // ── 进度通知节流 ──────────────────────────────────────────────

    @Test
    fun `从未发过通知时首次必发`() {
        assertTrue(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 0L, nowMs = 5_000L, minIntervalMs = 1_000L,
                lastPercent = -1, newPercent = 10,
            )
        )
    }

    @Test
    fun `百分比没变时不发`() {
        // ComfyUI 高频重复同一百分比（长时间同一节点）：每次都 notify 是纯耗电
        assertFalse(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 15_000L, minIntervalMs = 1_000L,
                lastPercent = 42, newPercent = 42,
            )
        )
    }

    @Test
    fun `间隔不足时不发`() {
        assertFalse(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 5_800L, minIntervalMs = 1_000L,
                lastPercent = 41, newPercent = 42,
            )
        )
    }

    @Test
    fun `百分比变化且到间隔时才发`() {
        assertTrue(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 6_200L, minIntervalMs = 1_000L,
                lastPercent = 41, newPercent = 42,
            )
        )
    }

    @Test
    fun `无进度数据期间按时间窗节流`() {
        // max=0 → percent 占位 -1：短窗口内不重复发；但每 60 秒要刷新一次（P4）
        assertFalse(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 6_500L, minIntervalMs = 1_000L,
                lastPercent = -1, newPercent = -1,
            )
        )
        assertFalse(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 64_000L, minIntervalMs = 1_000L,
                lastPercent = -1, newPercent = -1,
            )
        )
        assertTrue(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 66_000L, minIntervalMs = 1_000L,
                lastPercent = -1, newPercent = -1,
            )
        )
        assertTrue(
            GenerationNotifier.shouldEmitProgress(
                lastEmitAtMs = 5_000L, nowMs = 6_500L, minIntervalMs = 1_000L,
                lastPercent = -1, newPercent = 30,
            )
        )
    }

    // ── 失败摘要 ──────────────────────────────────────────────────

    @Test
    fun `空消息返回 null 不显示摘要段`() {
        assertNull(GenerationNotifier.summarizeFailure(null))
        assertNull(GenerationNotifier.summarizeFailure("   "))
    }

    @Test
    fun `短消息原样保留`() {
        assertEquals("CUDA out of memory", GenerationNotifier.summarizeFailure("CUDA out of memory"))
    }

    @Test
    fun `长消息截断并加省略号`() {
        val long = "x".repeat(500)
        val summary = GenerationNotifier.summarizeFailure(long)
        assertEquals(GenerationNotifier.FAILURE_SUMMARY_MAX_CHARS + 1, summary!!.length)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `摘要不含提示词内容的方法契约——只处理传入的异常文本`() {
        // 契约钉住：summarizeFailure 的输入只来自 WS execution_error 的异常消息
        // （ConnectionManager 解析的 exception_message），不是提示词；调用方只传这一个来源
        assertEquals("a".repeat(GenerationNotifier.FAILURE_SUMMARY_MAX_CHARS) + "…",
            GenerationNotifier.summarizeFailure("a".repeat(300)))
    }
}
