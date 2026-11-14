package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成完成后触发图库刷新的防抖阀单测（C-B1-a，H5）。
 *
 * 规则回顾（见 [GalleryRefreshThrottle]）：
 * - 同一任务的重复完成事件（WS 双事件 + 轮询兜底）只算一次；
 * - 防抖窗口内不同任务的完成事件合并为一次刷新。
 */
class GalleryRefreshThrottleTest {

    @Test
    fun `first completion fires immediately`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
    }

    @Test
    fun `duplicate completion event for the same task never fires again`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
        // 完成事件的多条路径（ExecutionComplete / execution_success / 轮询兜底）
        // 会重复报同一个 promptId：无论隔多久都只算第一次
        assertFalse(throttle.shouldFire("task-a", nowMs = 1500L))
        assertFalse(throttle.shouldFire("task-a", nowMs = 60000L))
    }

    @Test
    fun `second task within debounce window is merged`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
        // 批量出图：1 秒后另一个任务完成，窗口内合并（上一次刷新会把新条目一起带出来）
        assertFalse(throttle.shouldFire("task-b", nowMs = 2000L))
    }

    @Test
    fun `task outside debounce window fires again`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
        // 3 秒后（> 2 秒窗口）：正常的第二次刷新
        assertTrue(throttle.shouldFire("task-b", nowMs = 4001L))
    }

    @Test
    fun `debounce window boundary is inclusive`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
        // 正好 2000ms：now - last == debounce 不算"窗口内"，允许刷新
        assertTrue(throttle.shouldFire("task-b", nowMs = 3000L))
    }

    @Test
    fun `throttled task still counts as handled to block its own duplicates`() {
        val throttle = GalleryRefreshThrottle(debounceMs = 2000L)
        assertTrue(throttle.shouldFire("task-a", nowMs = 1000L))
        assertFalse(throttle.shouldFire("task-b", nowMs = 1500L))
        // task-b 被窗口合并后，它自己的重复完成事件（哪怕晚到）也不该再触发
        assertFalse(throttle.shouldFire("task-b", nowMs = 60000L))
    }
}
