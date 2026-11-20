package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 钉住 D1 / D2 / D4 / D10 以及第 1 轮复核 NEW-1 / NEW-2 的状态机契约。
 *
 * 真实的状态写入发生在 [ConnectionManager] 的 OkHttp 回调里（需要 Android 运行时），
 * 所以这里只测可以抽出来的逻辑：
 * - D1/D2/NEW-2：代次递增与"旧代次必被判陈旧"的契约
 *   （[WebSocketGenerationCounter] + [WebSocketRecoveryPolicy.nextGeneration] /
 *   [WebSocketRecoveryPolicy.isStaleCallback]）；
 * - NEW-1：退避重连到点时若已 Connected 必须跳过（[WebSocketRecoveryPolicy.shouldSkipReconnect]）；
 * - D4：等待握手超时后的状态收敛（[WebSocketRecoveryPolicy.afterConnectTimeout]）；
 * - D10：失败是否可恢复（[WebSocketRecoveryPolicy.isRecoverableFailure]）。
 *
 * 注意：`@Volatile` 的内存可见性本身无法用 JVM 单测"证明"（没有可移植的断言手段），
 * 这里只能测它保护的判定逻辑；可见性靠 [WebSocketGenerationCounter] 内的注解保证。
 */
class WebSocketGenerationAndTimeoutTest {

    // ── D1 / D2 / NEW-2：代次递增与陈旧回调判定 ─────────────────────

    @Test
    fun `当前代次的回调不算陈旧`() {
        assertFalse(WebSocketRecoveryPolicy.isStaleCallback(7L, 7L))
    }

    @Test
    fun `代次递增一次后旧代次立即陈旧`() {
        val captured = 7L
        val next = WebSocketRecoveryPolicy.nextGeneration(captured)

        assertEquals("递增必须恰好 +1", captured + 1, next)
        assertTrue(
            "D1：主动关闭/重建前递增代次，旧监听器的迟到回调必须被判陈旧",
            WebSocketRecoveryPolicy.isStaleCallback(captured, next)
        )
    }

    @Test
    fun `多次递增后只有最后一次捕获的代次有效`() {
        var generation = 0L
        val captured = mutableListOf<Long>()
        repeat(3) {
            generation = WebSocketRecoveryPolicy.nextGeneration(generation)
            captured += generation
        }

        val newest = captured.last()
        captured.dropLast(1).forEach { old ->
            assertTrue("旧代次 $old 必须被判陈旧（当前 $newest）", WebSocketRecoveryPolicy.isStaleCallback(old, newest))
        }
        assertFalse("最新代次不能自判陈旧", WebSocketRecoveryPolicy.isStaleCallback(newest, newest))
    }

    @Test
    fun `三条主动关闭路径递增代次后旧回调都不会再改状态`() {
        // 复刻 D1 的三个入口：retrySingleAttempt / attemptSilentReconnect / reconnectWebSocket。
        // 每次"关闭旧 socket"都必须先递增代次，否则旧回调会被当成当前连接。
        var generation = 0L

        repeat(3) { path ->
            val listenerGeneration = generation          // 旧监听器捕获的代次
            generation = WebSocketRecoveryPolicy.nextGeneration(generation)  // 关闭前递增

            assertTrue(
                "第 ${path + 1} 条关闭路径未作废旧监听器：它的迟到回调会被当成当前连接",
                WebSocketRecoveryPolicy.isStaleCallback(listenerGeneration, generation)
            )
        }
    }

    @Test(timeout = 10_000)
    fun `并发递增代次两两不同_不会有两个监听器同时有效`() {
        // NEW-2：openWebSocket 会从主线程与 OkHttp 回调线程两侧进入，
        // 递增若是普通 @Volatile 上的"读-改-写"，两个并发调用可能拿到同一代次，
        // 于是两个监听器都被认成"当前连接"，D1 的作废机制失效。
        val counter = WebSocketGenerationCounter()
        val threads = 8
        val perThread = 500
        val issued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) { issued.add(counter.next()) }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发递增未在超时内完成", done.await(8, TimeUnit.SECONDS))

        assertEquals("并发递增必须两两不同号，总数 = 线程数 × 次数", threads * perThread, issued.size)
        assertEquals(threads * perThread.toLong(), counter.current)
    }

    @Test(timeout = 5_000)
    fun `跨线程递增后旧代次同样被判陈旧_可见性冒烟`() {
        val counter = WebSocketGenerationCounter()
        val old = counter.current
        val started = CountDownLatch(1)
        val observed = AtomicReference(false)

        val reader = Thread {
            started.countDown()
            // 有界自旋：看到"旧代次已陈旧"即记下；超时则留 false 由断言报错
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
            var seen = false
            while (System.nanoTime() < deadline) {
                if (WebSocketRecoveryPolicy.isStaleCallback(old, counter.current)) {
                    seen = true
                    break
                }
            }
            observed.set(seen)
        }
        reader.start()
        started.await(1, TimeUnit.SECONDS)
        counter.next()
        reader.join(3_000)

        assertFalse("读线程未在超时内退出", reader.isAlive)
        assertTrue(
            "跨线程递增后读线程必须看到新代次（冒烟；@Volatile 本身无法被单测证明）",
            observed.get() == true
        )
    }

    @Test
    fun `当前代次的写入会执行`() {
        val counter = WebSocketGenerationCounter()
        val generation = counter.next()
        var ran = false

        val applied = counter.runIfCurrent(generation) { ran = true }

        assertTrue("当前代次必须放行写入", applied)
        assertTrue(ran)
    }

    @Test
    fun `已被作废的代次不得执行任何写入`() {
        // NEW-2：监听器通过陈旧检查之后、写状态之前，另一线程可能作废代次并关闭 socket。
        // runIfCurrent 把"判代次 + 执行"放进同一把锁，旧回合一进来就被拒。
        val counter = WebSocketGenerationCounter()
        val listenerGeneration = counter.next()
        counter.next()  // 另一条重建路径作废了这条监听器
        var ran = false

        val applied = counter.runIfCurrent(listenerGeneration) { ran = true }

        assertFalse("作废后的旧回调不能再改状态", applied)
        assertFalse("block 不得被执行", ran)
    }

    @Test
    fun `写入成功之后才被作废的照样失效`() {
        // 锁只保证"判代次与执行"原子，不阻止之后的作废：下一轮递增后，
        // 这条监听器手里的号必须立刻陈旧，后续回调一律被丢弃。
        val counter = WebSocketGenerationCounter()
        val listenerGeneration = counter.next()
        var ran = 0

        assertTrue(counter.runIfCurrent(listenerGeneration) { ran++ })
        counter.next()
        assertFalse(counter.runIfCurrent(listenerGeneration) { ran++ })

        assertEquals("只有作废前那一次写入生效", 1, ran)
        assertTrue(WebSocketRecoveryPolicy.isStaleCallback(listenerGeneration, counter.current))
    }

    // ── NEW-1：退避到点时已 Connected 必须跳过本轮重连 ───────────────

    @Test
    fun `已 Connected 时退避重连必须跳过`() {
        assertTrue(
            "退避期间别的入口已把连接建好：再关掉它会让 onClosed 被代次丢弃、状态永久停在 Connected",
            WebSocketRecoveryPolicy.shouldSkipReconnect(WebSocketState.Connected)
        )
    }

    @Test
    fun `其余状态仍需继续本轮重连`() {
        val others = listOf(
            WebSocketState.Disconnected,
            WebSocketState.Connecting,
            WebSocketState.Reconnecting(2, 0),
            WebSocketState.Failed("boom", ConnectionFailure.NETWORK)
        )

        others.forEach { state ->
            assertFalse("$state 不是 Connected，重连目标未达成，不能跳过", WebSocketRecoveryPolicy.shouldSkipReconnect(state))
        }
    }

    // ── D4：等待 WebSocket 握手的超时收敛 ────────────────────────────

    @Test
    fun `等待超时时把 Connecting 收敛为 Failed 并带上网络失败类型`() {
        val collapsed = WebSocketRecoveryPolicy.afterConnectTimeout(WebSocketState.Connecting)

        assertTrue("D4：超时后不得继续停在 Connecting（没有自治定时器会再来收尾）", collapsed is WebSocketState.Failed)
        val failed = collapsed as WebSocketState.Failed
        assertEquals(ConnectionFailure.NETWORK, failed.failureType)
        assertTrue("失败原因应说明是握手超时，便于现场排查", failed.reason?.contains("超时") == true)
    }

    @Test
    fun `等待超时但已落成 Failed 时保持原对象`() {
        val existed = WebSocketState.Failed("401 Unauthorized", ConnectionFailure.AUTHENTICATION)

        assertSame(
            "已有的失败终态可能带着更具体的原因，不能被超时默认值覆盖",
            existed,
            WebSocketRecoveryPolicy.afterConnectTimeout(existed)
        )
    }

    @Test
    fun `等待超时但连接已在边缘建立时不打断连接`() {
        assertSame(
            "超时判定的瞬间可能刚好 onOpen，不能把已建立的连接打回失败",
            WebSocketState.Connected,
            WebSocketRecoveryPolicy.afterConnectTimeout(WebSocketState.Connected)
        )
        assertSame(
            WebSocketState.Disconnected,
            WebSocketRecoveryPolicy.afterConnectTimeout(WebSocketState.Disconnected)
        )
    }

    // ── D10：可恢复失败不弹模态告警 ─────────────────────────────────

    @Test
    fun `网络类与停滞类失败可恢复`() {
        assertTrue(WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.NETWORK))
        assertTrue(WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.STALLED))
    }

    @Test
    fun `认证与无效服务器失败不可恢复`() {
        assertFalse(
            "401/403 需要用户重新登录，不能让自动重连把错误一直吞掉",
            WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.AUTHENTICATION)
        )
        assertFalse(WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.INVALID_SERVER))
        assertFalse(WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.FILE_TOO_LARGE))
        assertFalse(WebSocketRecoveryPolicy.isRecoverableFailure(ConnectionFailure.FILE_MISSING))
    }

    @Test
    fun `网络失败落 Failed 时不会与自动重连的 Reconnecting 冲突`() {
        // D10 的行为推导：可恢复失败 → isRecoverableFailure = true。
        // 真正决定「要不要弹窗」的分流在 `ConnectionManager.onFailure`（openWebSocket 里的
        // WebSocketListener，三路分支：isRecoverableFailure / scheduleReconnect / 落失败终态）：
        //   · 可恢复且 scheduleReconnect() 排上 → **不写 Failed**，直接进 Reconnecting；
        //   · 不可恢复（认证失败等）→ 阻断自动重连并落 Failed 终态（该弹窗的那一类）；
        //   · 可恢复但排不上重连（离线 / 手动重连中 / 被阻断 / 静默重连失败）→ 同样落 Failed 终态。
        // 注意：GenerationViewModel 的 `WebSocketState.Failed` 分支只判断 isGenerating，
        // **不做**可恢复性过滤（该分支自己的注释也写明「不做额外屏蔽」）——
        // 不该弹的那一类 Failed 是在 ConnectionManager 侧就不会产生的。
        // 本用例只钉住下面的分类判定（纯函数，可在 JVM 跑）；onFailure 的三路分流属
        // Android 运行时路径，JVM 单测够不着，只能靠上面两处函数对照与代码审查。
        val networkFailure = WebSocketRecoveryPolicy.failureTypeFor(responseCode = 503)
        val authFailure = WebSocketRecoveryPolicy.failureTypeFor(responseCode = 401)

        assertEquals(ConnectionFailure.NETWORK, networkFailure)
        assertEquals(ConnectionFailure.AUTHENTICATION, authFailure)
        assertTrue(
            "可恢复失败在 ConnectionManager 侧走自动重连、不落 Failed，因此不会弹窗",
            WebSocketRecoveryPolicy.isRecoverableFailure(networkFailure)
        )
        assertFalse(
            "认证失败才是需要弹窗的那一类",
            WebSocketRecoveryPolicy.isRecoverableFailure(authFailure)
        )
        assertNotEquals(
            "同一份判定不能把两类失败混为一谈",
            WebSocketRecoveryPolicy.isRecoverableFailure(networkFailure),
            WebSocketRecoveryPolicy.isRecoverableFailure(authFailure)
        )
    }

    // ── 结构性检查：afterFailure 仍保留原契约（回归保护） ────────────

    @Test
    fun `超时收敛不会把 Reconnecting 变成第二套状态`() {
        // Reconnecting 表示"正在等重连 job"，不能因为某处等待超时被判成 Failed，
        // 否则调度中的 job 会与 Failed 终态并存（界面显示失败但后台仍在重连）。
        val reconnecting = WebSocketState.Reconnecting(2, 0)

        assertSame(reconnecting, WebSocketRecoveryPolicy.afterConnectTimeout(reconnecting))
    }
}