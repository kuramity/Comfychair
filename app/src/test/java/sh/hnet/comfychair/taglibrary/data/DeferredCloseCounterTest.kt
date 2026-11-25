package sh.hnet.comfychair.taglibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 方案 A（引用计数延迟物理关闭）的纯逻辑单测。
 *
 * 只测 [DeferredCloseCounter] 这台状态机：计数增减、关闭被推迟的时机、
 * 归零后由「最后一个离开者」执行物理关闭、closeRequested 期间拒绝新查询、
 * 排空后重新放行。不碰 Android SQLite —— `TagDao` / `TagDatabase` 的接线
 * 由 [TagCloseGuardWiringTest] 以源码级钉子覆盖。
 */
class DeferredCloseCounterTest {

    /** 记录物理关闭被调用了几次、以及每次调用时的在途记账。 */
    private class Recorder(val closeCalls: AtomicInteger = AtomicInteger()) {
        /**
         * 测试侧独立记账的在途引用数。
         * 注意顺序：先把这里减 1，再调 `counter.release()` —— 与计数机自身的
         * 「refs 先归零、再在锁外执行物理关闭」是同一个次序，否则回调里看到的
         * 记账会比真实状态多 1（第一轮测试就踩了这个）。
         */
        val outstanding = AtomicInteger()

        /**
         * 物理关闭回调执行时读到的**计数机自身**的 refCount（-1 = 还没执行过）。
         * 这是最直接的证据：物理关闭必须发生在计数归零之后。
         */
        val refsWhenClosed = AtomicInteger(-1)

        /** 回调里要读计数机状态，但计数机是回调的宿主，只能构造后回填。 */
        var counter: DeferredCloseCounter? = null

        /** 物理关闭被执行的次数。 */
        val calls: Int get() = closeCalls.get()

        fun callback(): () -> Unit = {
            closeCalls.incrementAndGet()
            refsWhenClosed.set(counter?.refCount ?: -1)
        }
    }

    /** 建一个把关闭次数与关闭时机都记下来的计数机。 */
    private fun instrumented(): Pair<DeferredCloseCounter, Recorder> {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        recorder.counter = counter
        return counter to recorder
    }

    // ------------------------------------------------------------ 计数增减

    @Test
    fun `acquire 增加计数 release 减少计数`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())

        assertTrue(counter.acquire())
        assertEquals(1, counter.refCount)
        assertTrue(counter.acquire())
        assertTrue(counter.acquire())
        assertEquals(3, counter.refCount)

        counter.release()
        assertEquals(2, counter.refCount)
        counter.release()
        assertEquals(1, counter.refCount)
        counter.release()
        assertEquals(0, counter.refCount)

        assertEquals("没有关闭请求时绝不能物理关闭", 0, recorder.calls)
        assertFalse(counter.isClosePending)
    }

    @Test
    fun `嵌套引用（外层事务加内层查询）逐层退出`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())

        assertTrue(counter.acquire())   // 外层：ensureReady 的建索引事务
        assertTrue(counter.acquire())   // 内层：某一条 rawQuery
        assertEquals(2, counter.refCount)
        counter.release()
        assertEquals(1, counter.refCount)
        assertEquals("外层还持着引用，绝不能关闭", 0, recorder.calls)
        counter.release()
        assertEquals(0, counter.refCount)
        assertEquals(0, recorder.calls)
    }

    @Test
    fun `多余 release 不吃成负数也不误触发关闭`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())

        repeat(3) { counter.release() }   // 没有配对的 acquire（finally 写重了的兜底）
        assertEquals(0, counter.refCount)
        assertEquals(0, recorder.calls)

        // 记账没被多余的 release 偷走：一次真实查询 + 关闭请求仍然能正常收尾
        assertTrue(counter.acquire())
        counter.requestClose()
        assertEquals(0, recorder.calls)
        counter.release()
        assertEquals(1, recorder.calls)
        assertEquals(0, counter.refCount)
    }

    // ------------------------------------------------------------ 关闭时机

    @Test
    fun `无在途查询时关闭请求立即执行物理关闭`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())

        counter.requestClose()

        assertEquals(1, recorder.calls)
        assertFalse("关闭已完成，禁入门必须放行", counter.isClosePending)
        assertEquals(0, counter.refCount)
    }

    @Test
    fun `有在途查询时关闭只置标志不执行物理关闭`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        counter.acquire()
        counter.acquire()

        counter.requestClose()

        assertEquals("在途查询尚未离开，close 必须是惰性的", 0, recorder.calls)
        assertTrue(counter.isClosePending)
        assertEquals(2, counter.refCount)
    }

    @Test
    fun `物理关闭由最后一个离开的查询执行且只执行一次`() {
        val (counter, recorder) = instrumented()
        repeat(3) {
            assertTrue(counter.acquire())
            recorder.outstanding.incrementAndGet()
        }

        counter.requestClose()

        // 前两个离开：还不该关
        recorder.outstanding.decrementAndGet(); counter.release()
        assertEquals(0, recorder.calls)
        recorder.outstanding.decrementAndGet(); counter.release()
        assertEquals(0, recorder.calls)
        assertTrue(counter.isClosePending)

        // 最后一个离开：由它执行物理关闭
        recorder.outstanding.decrementAndGet(); counter.release()
        assertEquals(1, recorder.calls)
        assertEquals("关闭动作必须在所有在途引用离开之后", 0, recorder.refsWhenClosed.get())
        assertEquals("测试侧记账同步归零", 0, recorder.outstanding.get())
        assertFalse(counter.isClosePending)
        assertEquals(0, counter.refCount)

        // 后续 release 不会再触发第二次关闭
        counter.release()
        assertEquals(1, recorder.calls)
    }

    @Test
    fun `closeRequested 期间拒绝新的查询`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        counter.acquire()
        counter.requestClose()

        repeat(5) {
            assertFalse("排空期间必须挡住新查询，否则窗口只是缩小不是消除", counter.acquire())
        }
        assertEquals("被拒绝的 acquire 不能留下计数", 1, counter.refCount)

        counter.release()
        assertEquals(1, recorder.calls)
        assertEquals(0, counter.refCount)
    }

    @Test
    fun `排空完成后重新放行以便 retry 重新打开`() {
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        counter.acquire()
        counter.requestClose()
        counter.release()

        assertEquals(1, recorder.calls)
        assertFalse(counter.isClosePending)
        // 上一轮关闭彻底落地后，门要重新放行（retryInitialize 会重新 open）
        assertTrue(counter.acquire())
        counter.release()
        assertEquals("重新放行不应再触发旧句柄关闭", 1, recorder.calls)
    }

    @Test
    fun `物理关闭抛异常时不卡死禁入门也不外抛`() {
        val calls = AtomicInteger()
        val counter = DeferredCloseCounter {
            calls.incrementAndGet()
            throw IllegalStateException("模拟 db.close 失败")
        }

        // 主线程调用链不能因为关闭失败而崩
        counter.requestClose()

        assertEquals(1, calls.get())
        assertFalse("标志必须归位，否则标签库从此不可用", counter.isClosePending)
        assertTrue("关闭失败后依然要能重新 acquire", counter.acquire())

        // 在途路径同样不许把异常抛给查询线程
        counter.release()
        counter.acquire()
        counter.requestClose()
        counter.release()
        assertEquals(2, calls.get())
        assertFalse(counter.isClosePending)
    }

    // ------------------------------------------------------------ 并发

    @Test
    fun `并发下八个在途查询把 close 推迟到最后且只关一次`() {
        val holders = 8
        val (counter, recorder) = instrumented()
        val inside = CountDownLatch(holders)
        val proceed = CountDownLatch(1)
        val acquiredCount = AtomicInteger()

        repeat(holders) {
            thread(isDaemon = true) {
                if (counter.acquire()) {
                    acquiredCount.incrementAndGet()
                    recorder.outstanding.incrementAndGet()
                    inside.countDown()
                    proceed.await(5, TimeUnit.SECONDS)
                    // 先了结本线程的记账，再归还引用：与计数机内部次序一致
                    recorder.outstanding.decrementAndGet()
                    counter.release()
                }
            }
        }

        assertTrue("在途线程未按时进入", inside.await(5, TimeUnit.SECONDS))
        assertEquals(holders, acquiredCount.get())
        assertEquals(holders, counter.refCount)

        // 全部查询仍在途时请求关闭
        counter.requestClose()
        assertEquals("有在途查询时 close 必须惰性", 0, recorder.calls)
        assertTrue(counter.isClosePending)
        repeat(4) { assertFalse(counter.acquire()) }
        assertEquals(holders, counter.refCount)

        // 放行使全部查询离开：物理关闭由最后一个 release 执行
        proceed.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (recorder.calls == 0 && System.currentTimeMillis() < deadline) Thread.yield()

        assertEquals("物理关闭必须恰好执行一次", 1, recorder.calls)
        assertEquals("关闭时计数机自身的在途数必须已归零", 0, recorder.refsWhenClosed.get())
        assertEquals(0, counter.refCount)
        assertFalse(counter.isClosePending)
    }

    @Test
    fun `关闭挂起期间所有线程的 acquire 一律被拒`() {
        val workers = 16
        val iterations = 200
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        val start = CountDownLatch(workers)
        val done = CountDownLatch(workers)
        val grants = AtomicInteger()
        val refusals = AtomicInteger()

        // 主线程先占住一个引用：关闭请求因此只能挂起，物理关闭决不会在 worker 跑动期间落地
        assertTrue(counter.acquire())
        counter.requestClose()
        assertTrue(counter.isClosePending)
        assertEquals("还有一个引用在途，绝不能关闭", 0, recorder.calls)

        val bodies = List(workers) {
            thread(isDaemon = true) {
                start.await(10, TimeUnit.SECONDS)
                repeat(iterations) {
                    if (counter.acquire()) {
                        grants.incrementAndGet()
                        counter.release()
                    } else {
                        refusals.incrementAndGet()
                    }
                }
                done.countDown()
            }
        }

        // 所有 worker 就位后再一起放行，保证「挂起窗口」覆盖全部 3200 次尝试
        repeat(workers) { start.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        bodies.forEach { it.join(5_000) }

        assertEquals("排空期间不允许任何新查询进入", 0, grants.get())
        assertEquals(workers * iterations, refusals.get())
        assertEquals("只有主线程那一个引用在途", 1, counter.refCount)
        assertEquals("worker 全部退出后仍未关闭，关闭责任还在主线程手上", 0, recorder.calls)

        counter.release()
        assertEquals("主线程离开即归零 → 物理关闭一次", 1, recorder.calls)
        assertFalse(counter.isClosePending)
        assertEquals(0, counter.refCount)
        // 关闭落地后新查询重新放行（retryInitialize 重新 open 的前提）
        assertTrue(counter.acquire())
        counter.release()
        assertEquals(1, recorder.calls)
    }

    @Test
    fun `无关闭请求时多线程并发计数收支平衡`() {
        val workers = 16
        val iterations = 500
        val recorder = Recorder()
        val counter = DeferredCloseCounter(recorder.callback())
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val grants = AtomicInteger()

        val bodies = List(workers) {
            thread(isDaemon = true) {
                start.await(10, TimeUnit.SECONDS)
                repeat(iterations) {
                    if (counter.acquire()) {
                        grants.incrementAndGet()
                        recorder.outstanding.incrementAndGet()
                        counter.release()
                        recorder.outstanding.decrementAndGet()
                    }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        bodies.forEach { it.join(5_000) }

        assertEquals("没有关闭请求时所有 acquire 都应放行", workers * iterations, grants.get())
        assertEquals("并发收支必须归零", 0, counter.refCount)
        assertEquals("测试侧记账也要归零", 0, recorder.outstanding.get())
        assertEquals("没人请求关闭就不许物理关闭", 0, recorder.calls)
    }
}
