package sh.hnet.comfychair.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.SingleDeliveryGuard

/**
 * P0「二次 resume 闪退」护栏的单元测试（任务书 2.3）。
 *
 * 旧实现：`suspendCoroutine { cont -> fetch { cont.resumeWith(...) } }`。
 * 回调被调用两次时第二次 `resumeWith` 抛 `IllegalStateException: Already resumed`，
 * 且发生在网络回调线程 → 外层 try/catch 捕不到 → 进程崩溃。
 *
 * 现在的两道防线：
 * 1. [awaitCallbackOnce] 里 `continuation.isActive` 判定（仓储层）；
 * 2. [SingleDeliveryGuard] 投递前置位、重复投递直接丢弃（client 层）。
 *
 * 反证：把任一处改回"无条件 resume / 无条件执行"，本类用例都会变红。
 */
class GalleryCallbackOnceTest {

    @Test
    fun `回调被调用两次时延续只恢复一次且不抛异常`() = runBlocking {
        var deliveries = 0
        val value = awaitCallbackOnce<Int> { deliver ->
            deliver(1)
            deliveries++
            // 模拟 OkHttp 回调的二次投递（旧实现在这一行抛 Already resumed）
            deliver(2)
            deliveries++
        }

        assertEquals("延续只能拿到第一次投递的值", 1, value)
        assertEquals("回调本身确实被调用了两次（模拟成立）", 2, deliveries)
    }

    @Test
    fun `跨线程二次投递也只恢复一次且不抛异常`() = runBlocking {
        val value = awaitCallbackOnce<Int> { deliver ->
            val worker = Thread { deliver(7) }
            worker.start()
            worker.join()
            // 延续已恢复；这次投递必须被静默丢弃，而不是抛 Already resumed
            deliver(8)
        }

        assertEquals(7, value)
    }

    @Test
    fun `重复投递只执行一次回调（SingleDeliveryGuard）`() {
        var invoked = 0
        var duplicates = 0
        val guard = SingleDeliveryGuard { duplicates++ }

        guard.deliver { invoked++ }
        guard.deliver { invoked++ }
        guard.deliver { invoked++ }

        assertEquals("业务回调只能执行一次", 1, invoked)
        assertEquals("重复投递只记日志", 2, duplicates)
        assertTrue(guard.hasDelivered())
    }

    @Test
    fun `首次投递抛异常后第二次投递不会再执行（投递前置位）`() {
        var invoked = 0
        val guard = SingleDeliveryGuard()

        // 模拟"投递过程中抛异常"：旧写法先执行后置位，这里会让下一次投递重复执行
        try {
            guard.deliver {
                invoked++
                throw IllegalStateException("首次投递内部异常")
            }
        } catch (e: IllegalStateException) {
            // 预期：异常照常向外抛（不吞），但状态必须已经标记为已投递
        }
        guard.deliver { invoked++ }

        assertEquals("异常之后不允许二次投递", 1, invoked)
        assertTrue(guard.hasDelivered())
    }

    @Test
    fun `未投递前 hasDelivered 为 false`() {
        assertFalse(SingleDeliveryGuard().hasDelivered())
    }
}