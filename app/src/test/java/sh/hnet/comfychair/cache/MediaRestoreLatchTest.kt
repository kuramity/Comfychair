package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 钉住 5.2「启动期媒体恢复闩锁」的语义：按**本次恢复**判断，而不是整个进程只认一次。
 *
 * 旧实现是一只进程内一次性布尔闩锁：恢复完成置 true 后永不复位。同一进程里第二次创建
 * MainContainerActivity（登出→重新登录、切换服务器）时，onCreate 的 loadFromDisk 还是异步的，
 * 界面却已经按上一轮的 true 跳过了占位分支 —— 屏幕初始化一次性读媒体（不重试），读到的是空。
 *
 * 这里用可控时序把「新一轮开始 → 只认本轮令牌 → 清空收敛」拆开逐帧断言，
 * 不依赖协程调度器（本项目没有 kotlinx-coroutines-test 依赖），也不碰 Android。
 */
class MediaRestoreLatchTest {

    @Test
    fun `初始为未就绪，本轮完成后置就绪`() {
        val latch = MediaRestoreLatch()

        assertFalse("冷启动：没有任何媒体可读，界面必须先走占位分支", latch.isRestored)
        assertFalse(latch.restored.value)

        val token = latch.beginRestore()
        assertFalse("恢复进行中", latch.isRestored)

        latch.markRestored(token)
        assertTrue("本轮恢复完成，界面可以组合导航图", latch.isRestored)
        assertTrue(latch.restored.value)
    }

    @Test
    fun `同一进程里第二次创建界面必须重新等一轮（闩锁复位不再是一次性）`() {
        val latch = MediaRestoreLatch()

        // 第一次创建 MainContainerActivity：恢复完成
        latch.markRestored(latch.beginRestore())
        assertTrue(latch.isRestored)

        // 登出→重新登录 / 切换服务器：第二次 onCreate 在组合之前同步复位
        latch.resetForNewSession()
        assertFalse(
            "复位后必须回到未就绪，否则占位分支被跳过、异步 loadFromDisk 还没跑就读到空媒体",
            latch.isRestored
        )

        // 新一轮恢复：置就绪只由本轮的完成决定
        val token = latch.beginRestore()
        assertFalse(latch.isRestored)
        latch.markRestored(token)
        assertTrue(latch.isRestored)
    }

    @Test
    fun `上一轮迟到的完成不会把新一轮误标成就绪`() {
        val latch = MediaRestoreLatch()

        val oldRound = latch.beginRestore()
        val newRound = latch.beginRestore()

        // 旧一轮（比如上个服务器的 loadFromDisk）迟到完成
        latch.markRestored(oldRound)
        assertFalse("旧令牌已作废，不能把新一轮标成就绪", latch.isRestored)

        latch.markRestored(newRound)
        assertTrue("只有本轮令牌能置就绪", latch.isRestored)
    }

    @Test
    fun `没有在途恢复时清空媒体收敛到就绪，不会让界面无限占位`() {
        val latch = MediaRestoreLatch()

        // 一轮恢复已经结束，之后才清缓存 / 断线重连
        latch.markRestored(latch.beginRestore())
        assertTrue(latch.isRestored)

        latch.settleReady()

        assertTrue(
            "清空之后没有任何等待中的内容：置未就绪会让界面永远停在占位分支（不会再有新一轮恢复）",
            latch.isRestored
        )
        // 重复清空同样保持就绪
        latch.settleReady()
        assertTrue(latch.isRestored)
    }

    /**
     * 复核第 2 轮 NEW-4：清空媒体时若恢复还在跑，不能提前置就绪。
     *
     * 真实路径：startup 的 loadFromDisk 正在把图/视频写回内存，此时离线看门狗恢复在线会走
     * `resumeOnlineInternal → connect() → invalidateAll() → clearAll()`。若此刻置就绪，
     * 界面立刻按「内存已被清空」的状态组合导航图，而那一轮随即又把数据写回内存 ——
     * 屏幕只读一次媒体、不会补刷，D3 的症状就从这条窄路径重现。
     */
    @Test
    fun `恢复在途时清空媒体保持未就绪，等该轮完成才置就绪`() {
        val latch = MediaRestoreLatch()

        val inFlight = latch.beginRestore()
        latch.settleReady()                       // clearAll()

        assertFalse(
            "恢复线程马上还会把数据写回内存，此时置就绪会让界面先按空媒体组合",
            latch.isRestored
        )

        // 该轮没有被作废，完成时照常收敛到就绪（否则闩锁会永远停在未就绪）
        latch.markRestored(inFlight)
        assertTrue("本轮完成后界面才组合导航图", latch.isRestored)
    }

    @Test
    fun `在途恢复被新一轮取代后，旧轮完成不会置就绪`() {
        val latch = MediaRestoreLatch()

        val oldRound = latch.beginRestore()
        latch.settleReady()                       // 在途，不置就绪
        val newRound = latch.beginRestore()       // 新一轮开始（旧轮被作废）

        assertFalse("新一轮还没完成", latch.isRestored)
        latch.markRestored(oldRound)
        assertFalse("旧轮令牌已作废，不能把新一轮标成就绪", latch.isRestored)
        assertFalse("旧轮过期之后也不应再被当成在途", latch.isCurrent(oldRound))
        assertTrue(latch.isCurrent(newRound))

        latch.markRestored(newRound)
        assertTrue(latch.isRestored)
    }

    @Test
    fun `复位之后旧令牌同样不能把状态改回就绪`() {
        val latch = MediaRestoreLatch()

        val oldRound = latch.beginRestore()
        latch.resetForNewSession()      // 换服务器 / 新一轮开始
        latch.markRestored(oldRound)    // 旧一轮迟到完成

        assertFalse(latch.isRestored)
    }

    /**
     * NEW-4：`markRestored` 的「判令牌 + 置状态」必须是原子步骤。
     *
     * 两个线程的交错是真实存在的：beginRestore 可能来自 IO 线程
     * （`persistScope.launch { loadFromDisk(...) }`），markRestored 来自主线程
     * （`lifecycleScope.launch`）。若比较与写入分开，上一轮迟到的完成会插在
     * 「新一轮已复位」之后把闩锁又置成就绪。
     *
     * 说明：这里断言的是**后置条件**（无论两个线程怎么交错，结果都只能是「未就绪」），
     * 而不是确定性复现旧 bug —— 那个竞态窗口只有几条指令宽，没有注入缝隙无法稳定触发；
     * 但仍能把「比较与写入被拆开」这类改动钉红（多轮循环会撞上窗口）。
     */
    @Test(timeout = 10_000)
    fun `上一轮迟到的完成不会在新一轮复位之后把闩锁置成就绪（跨线程）`() {
        val latch = MediaRestoreLatch()

        repeat(500) {
            val previousRound = latch.beginRestore()   // 上一轮恢复（此刻它是当前轮次）

            val lateDone = CountDownLatch(1)
            val lateThread = Thread {
                latch.markRestored(previousRound)      // 上一轮的迟到完成
                lateDone.countDown()
            }
            lateThread.start()

            // 新一轮在另一个线程完成迟到的同时开始：两种先后顺序都合法
            val currentRound = latch.beginRestore()
            val finished = lateDone.await(1, TimeUnit.SECONDS)
            lateThread.join(1_000)

            assertTrue("上一轮的迟到完成没有跑完（线程卡死）", finished)
            assertFalse(
                "上一轮的完成不能把新一轮标成就绪（判令牌与置状态必须原子）",
                latch.isRestored
            )

            latch.markRestored(currentRound)           // 本轮真正完成
            assertTrue("本轮令牌必须仍然有效", latch.isRestored)
        }
    }
}