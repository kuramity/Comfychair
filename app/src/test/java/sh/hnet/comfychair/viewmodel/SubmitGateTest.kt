package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 钉住 P1-4 的单飞闸门语义（含 R2-L4 的持有者令牌）。
 *
 * 缺陷：`startGeneration` 没有在途标记，连点两次「生成」（或「生成」与「添加到队首」
 * 几乎同时按下）会各自发出一次 `/prompt`，服务器上多出一个任务。
 *
 * 这里不能直接构造 [GenerationViewModel]（需要 Android 运行时），所以只测抽出来的
 * [SubmitGate]：它就是这个缺陷唯一的判定点，`startGeneration` 里做的只是
 * "抢到才提交、三路带令牌释放"。
 *
 * 令牌的作用（R2-L4）：30s 看门狗或迟到的鉴权失败回调可能在闸门**已经易主**之后才到达，
 * 没有令牌时会把下一次提交刚抢到的闸门误释放；带令牌后这类调用退化为空操作。
 */
class SubmitGateTest {

    @Test
    fun `空闲时第一次提交能抢到闸门并拿到令牌`() {
        val gate = SubmitGate()

        assertFalse("初始应为空闲", gate.isInFlight)
        val ticket = gate.tryAcquire()

        assertNotNull("空闲时第一次点击必须放行", ticket)
        assertTrue("令牌必须是正号（0 保留给空闲）", ticket!! > 0L)
        assertTrue("抢到后必须标记为在途", gate.isInFlight)
        assertTrue("闸门必须认这个令牌", gate.isHeldBy(ticket))
    }

    @Test
    fun `在途时的重复点击必须被拒且拿不到令牌`() {
        val gate = SubmitGate()
        val first = gate.tryAcquire()
        assertNotNull(first)

        assertNull("上一次提交还没回来，第二次点击必须丢弃", gate.tryAcquire())
        assertTrue("被拒的点击不得改变在途状态", gate.isInFlight)
        assertTrue("持有者仍是第一次的令牌", gate.isHeldBy(first!!))
    }

    @Test
    fun `带令牌释放后可以再次提交`() {
        val gate = SubmitGate()
        val first = gate.tryAcquire()!!

        gate.release(first)

        assertFalse(gate.isInFlight)
        val second = gate.tryAcquire()
        assertNotNull("上一次提交已结束（成功/失败/异常三路都走 release）", second)
        assertTrue("令牌单调递增，旧令牌不能复用", second!! > first)
    }

    @Test
    fun `同一令牌重复释放是幂等且无害的`() {
        val gate = SubmitGate()
        val ticket = gate.tryAcquire()!!

        gate.release(ticket)
        gate.release(ticket)

        assertFalse(gate.isInFlight)
        assertNotNull("看门狗与正常回调可能各释放一次，之后仍要能提交", gate.tryAcquire())
    }

    @Test
    fun `过期令牌不得释放新提交的闸门`() {
        // R2-L4 的核心：旧提交的看门狗/迟到回调带着旧令牌到达时，
        // 闸门可能已经属于下一次提交 —— 必须原样忽略。
        val gate = SubmitGate()
        val oldTicket = gate.tryAcquire()!!
        gate.release(oldTicket)          // 旧提交正常结束
        val newTicket = gate.tryAcquire()!!  // 新提交抢到闸门

        gate.release(oldTicket)          // 迟到的看门狗/鉴权回调

        assertTrue("新提交的闸门不能被旧令牌捅开", gate.isInFlight)
        assertTrue("持有者仍是新令牌", gate.isHeldBy(newTicket))
        assertNull("闸门仍被占用，再来一次必须被拒", gate.tryAcquire())
    }

    @Test
    fun `过期令牌不会影响后续待释放的新令牌`() {
        val gate = SubmitGate()
        val oldTicket = gate.tryAcquire()!!
        gate.release(oldTicket)
        val newTicket = gate.tryAcquire()!!

        gate.release(oldTicket)
        gate.release(newTicket)

        assertFalse("新令牌自己的释放必须照常生效", gate.isInFlight)
    }

    @Test(timeout = 10_000)
    fun `并发点击只有一个能抢到闸门`() {
        // 复刻"连点"的最坏情况：多个线程几乎同时进入 startGeneration。
        // 用 AtomicLong 的 CAS 才能保证只有一个通过；普通布尔标记会让多个线程同时读到空闲。
        val gate = SubmitGate()
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val acquired = AtomicInteger(0)

        repeat(threads) {
            Thread {
                start.await()
                if (gate.tryAcquire() != null) acquired.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发抢闸门未在超时内完成", done.await(8, TimeUnit.SECONDS))

        assertEquals("同一时刻只能有一次提交在途", 1, acquired.get())
        assertTrue(gate.isInFlight)
    }

    @Test(timeout = 10_000)
    fun `连续抢与释放的循环里同一时刻至多一个持有者`() {
        // 提交成功/失败是异步的，闸门会被"抢 → 释放 → 再抢"反复使用。
        // 这里验证不变式：任一时刻持有者数量不超过 1，且释放用的都是自己那次的令牌。
        val gate = SubmitGate()
        val threads = 8
        val rounds = 300
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val violations = AtomicInteger(0)
        val holders = AtomicInteger(0)

        repeat(threads) {
            Thread {
                start.await()
                repeat(rounds) {
                    val ticket = gate.tryAcquire() ?: return@repeat
                    if (holders.incrementAndGet() != 1) violations.incrementAndGet()
                    holders.decrementAndGet()
                    gate.release(ticket)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发循环未在超时内完成", done.await(8, TimeUnit.SECONDS))

        assertEquals("闸门出现了同时持有者", 0, violations.get())
        assertFalse("全部释放后闸门应回到空闲", gate.isInFlight)
    }
}
