package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「启动期等媒体恢复」的放行判据。
 *
 * 背景（用户反馈）：点连接后会出现一段极短的转圈，之后才进正常 UI；如果恢复卡住
 * （磁盘慢、媒体多、IO 被其它任务抢占），转圈就可能一直挂着。现在的行为是：
 * 到上限（[MEDIA_RESTORE_TIMEOUT_MS]）就放行 UI —— 只放行、不取消恢复。
 *
 * 判定被抽成纯函数 [shouldGiveUp]，因为真实的等待跑在 Compose 的 LaunchedEffect + 系统时钟上，
 * 单测碰不到；能单测的只有「到点没到点」这一条边界。
 *
 * 反证：把 [shouldGiveUp] 改成恒 `false`，`到点必须放行` 那条会红；
 * 改成恒 `true`，`未到点不许放行` 那几条会红。两侧都有断言，不存在恒真写法。
 */
class MediaRestoreTimeoutTest {

    @Test
    fun `等待上限是 3 秒`() {
        // 需求给出的建议值就是 3 秒；这条把常量钉住，防止被顺手改成无意义的长时间
        assertEquals(3_000L, MEDIA_RESTORE_TIMEOUT_MS)
    }

    @Test
    fun `恰好到上限就放行`() {
        assertTrue("等于上限即放行（包含边界）", shouldGiveUp(MEDIA_RESTORE_TIMEOUT_MS, MEDIA_RESTORE_TIMEOUT_MS))
    }

    @Test
    fun `超过上限继续放行`() {
        assertTrue(shouldGiveUp(MEDIA_RESTORE_TIMEOUT_MS + 1, MEDIA_RESTORE_TIMEOUT_MS))
        assertTrue(shouldGiveUp(10_000L, MEDIA_RESTORE_TIMEOUT_MS))
    }

    @Test
    fun `未到上限不许放行`() {
        assertFalse("刚启动第一帧不能直接放行", shouldGiveUp(0L, MEDIA_RESTORE_TIMEOUT_MS))
        assertFalse(shouldGiveUp(MEDIA_RESTORE_TIMEOUT_MS - 1, MEDIA_RESTORE_TIMEOUT_MS))
        assertFalse("恢复通常几十毫秒完成，绝大多数时刻都在等待中", shouldGiveUp(500L, MEDIA_RESTORE_TIMEOUT_MS))
    }

    @Test
    fun `判据与轮询间隔无关且不依赖系统时钟`() {
        // 轮询间隔只影响判定精度：按它逐拍推进，跨过上限的那一拍开始返回 true
        var elapsed = 0L
        var firstGiveUpAt = -1L
        while (elapsed <= MEDIA_RESTORE_TIMEOUT_MS + MEDIA_RESTORE_POLL_INTERVAL_MS) {
            if (shouldGiveUp(elapsed, MEDIA_RESTORE_TIMEOUT_MS)) {
                firstGiveUpAt = elapsed
                break
            }
            elapsed += MEDIA_RESTORE_POLL_INTERVAL_MS
        }
        assertTrue("按 ${MEDIA_RESTORE_POLL_INTERVAL_MS}ms 轮询时必须能在上限后一拍内放行", firstGiveUpAt >= 0)
        assertTrue(
            "放行时刻不应早于上限（实际 $firstGiveUpAt）",
            firstGiveUpAt >= MEDIA_RESTORE_TIMEOUT_MS
        )
        assertTrue(
            "放行时刻不应迟于「上限 + 一拍」（实际 $firstGiveUpAt）",
            firstGiveUpAt <= MEDIA_RESTORE_TIMEOUT_MS + MEDIA_RESTORE_POLL_INTERVAL_MS
        )
    }
}