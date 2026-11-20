package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住条目 4.1 的收尾规则：**任何过渡态都必须有人收尾，不能永久停在 Connecting**。
 *
 * 这些判定被抽成 [WebSocketRecoveryPolicy] 里的纯函数（零 Android 依赖），
 * 因此可以在 JVM 上用假时钟逐条复现"离线时 onFailure 后重连被跳过"这类时序。
 */
class WebSocketRecoveryPolicyTest {

    /** 任意基准时刻，只用来做相对时间运算 */
    private val t0 = 1_000_000L

    // ── onFailure ────────────────────────────────────────────────────

    @Test
    fun `连接失败必须落成 Failed 而不能再停留在 Connecting`() {
        val next = WebSocketRecoveryPolicy.afterFailure(
            current = WebSocketState.Connecting,
            responseCode = 503,
            reason = "connect timed out"
        )

        assertTrue("旧写法只排重连、不写状态，这里会仍是 Connecting", next is WebSocketState.Failed)
        assertEquals("connect timed out", (next as WebSocketState.Failed).reason)
    }

    @Test
    fun `401 与 403 归类为认证失败`() {
        listOf(401, 403).forEach { code ->
            val next = WebSocketRecoveryPolicy.afterFailure(WebSocketState.Connecting, code, null)
            assertEquals(
                "HTTP $code 应归认证失败",
                ConnectionFailure.AUTHENTICATION,
                (next as WebSocketState.Failed).failureType
            )
        }
    }

    @Test
    fun `其余响应码与无响应码归类为网络失败`() {
        listOf(null, 0, 200, 500).forEach { code ->
            val next = WebSocketRecoveryPolicy.afterFailure(WebSocketState.Connecting, code, null)
            assertEquals(
                "HTTP $code 应归网络失败",
                ConnectionFailure.NETWORK,
                (next as WebSocketState.Failed).failureType
            )
        }
    }

    @Test
    fun `已处于完全相同的失败终态时不重复写入`() {
        val existing = WebSocketState.Failed("boom", ConnectionFailure.NETWORK)

        val next = WebSocketRecoveryPolicy.afterFailure(existing, 500, "boom")

        assertSame("同值终态应保持原对象，避免多余的状态发射", existing, next)
    }

    @Test
    fun `即便当前是 Connected 也要落成失败终态`() {
        val next = WebSocketRecoveryPolicy.afterFailure(
            current = WebSocketState.Connected,
            responseCode = null,
            reason = "EOF"
        )

        assertTrue(next is WebSocketState.Failed)
    }

    // ── onClosed ─────────────────────────────────────────────────────

    @Test
    fun `非 1000 关闭码也要先置 Disconnected 并排重连`() {
        val outcome = WebSocketRecoveryPolicy.afterClosed(
            current = WebSocketState.Connecting,
            code = 1006
        )

        assertTrue("旧写法此时保持 Connecting，交给 scheduleReconnect 收尾", outcome.state is WebSocketState.Disconnected)
        assertTrue("异常关闭应自动重连", outcome.reconnect)
    }

    @Test
    fun `1000 正常关闭置 Disconnected 且不重连`() {
        val outcome = WebSocketRecoveryPolicy.afterClosed(
            current = WebSocketState.Connecting,
            code = 1000
        )

        assertTrue(outcome.state is WebSocketState.Disconnected)
        assertFalse(outcome.reconnect)
    }

    @Test
    fun `关闭码与是否重连的对照表`() {
        assertFalse(WebSocketRecoveryPolicy.shouldReconnectAfterClose(1000))
        assertTrue(WebSocketRecoveryPolicy.shouldReconnectAfterClose(1001))
        assertTrue(WebSocketRecoveryPolicy.shouldReconnectAfterClose(1006))
    }

    // ── scheduleReconnect 入口的过渡态收敛 ───────────────────────────

    @Test
    fun `排重连前把 Connecting 收敛为 Disconnected`() {
        assertEquals(
            WebSocketState.Disconnected,
            WebSocketRecoveryPolicy.collapseTransitional(WebSocketState.Connecting)
        )
    }

    @Test
    fun `排重连前把 Reconnecting 收敛为 Disconnected`() {
        assertEquals(
            WebSocketState.Disconnected,
            WebSocketRecoveryPolicy.collapseTransitional(WebSocketState.Reconnecting(3, 0))
        )
    }

    @Test
    fun `终态不会被收敛改写`() {
        val failed = WebSocketState.Failed("x", ConnectionFailure.AUTHENTICATION)

        assertEquals(WebSocketState.Connected, WebSocketRecoveryPolicy.collapseTransitional(WebSocketState.Connected))
        assertEquals(WebSocketState.Disconnected, WebSocketRecoveryPolicy.collapseTransitional(WebSocketState.Disconnected))
        assertEquals(failed, WebSocketRecoveryPolicy.collapseTransitional(failed))
    }

    @Test
    fun `离线导致重连被跳过时状态不会停在 Connecting`() {
        var state: WebSocketState = WebSocketState.Connecting

        // onFailure 的收尾
        state = WebSocketRecoveryPolicy.afterFailure(state, 503, "EOF")
        // scheduleReconnect 因离线提前 return：只执行入口处的过渡态收敛
        state = WebSocketRecoveryPolicy.collapseTransitional(state)

        assertTrue("收尾逻辑缺失时这里会停在 Connecting", state is WebSocketState.Failed)
    }

    // ── openWebSocket 的僵尸 Connecting 判定 ─────────────────────────

    @Test
    fun `已连接时直接跳过`() {
        assertEquals(
            OpenWebSocketDecision.ALREADY_CONNECTED,
            WebSocketRecoveryPolicy.openDecision(WebSocketState.Connected, 0, t0)
        )
    }

    @Test
    fun `20 秒内的 Connecting 保持等待`() {
        assertEquals(
            OpenWebSocketDecision.STILL_CONNECTING,
            WebSocketRecoveryPolicy.openDecision(WebSocketState.Connecting, t0, t0)
        )
        assertEquals(
            OpenWebSocketDecision.STILL_CONNECTING,
            WebSocketRecoveryPolicy.openDecision(
                WebSocketState.Connecting,
                t0,
                t0 + WebSocketRecoveryPolicy.CONNECTING_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun `满 20 秒的 Connecting 视为僵尸并强制重建`() {
        assertEquals(
            OpenWebSocketDecision.REBUILD_STALE,
            WebSocketRecoveryPolicy.openDecision(
                WebSocketState.Connecting,
                t0,
                t0 + WebSocketRecoveryPolicy.CONNECTING_TIMEOUT_MS
            )
        )
        assertEquals(
            OpenWebSocketDecision.REBUILD_STALE,
            WebSocketRecoveryPolicy.openDecision(
                WebSocketState.Connecting,
                t0,
                t0 + WebSocketRecoveryPolicy.CONNECTING_TIMEOUT_MS * 3
            )
        )
    }

    @Test
    fun `没有起始时刻的 Connecting 视为僵尸`() {
        assertEquals(
            OpenWebSocketDecision.REBUILD_STALE,
            WebSocketRecoveryPolicy.openDecision(
                WebSocketState.Connecting,
                WebSocketRecoveryPolicy.NO_CONNECTING_SINCE,
                t0
            )
        )
    }

    @Test
    fun `其余状态正常发起连接`() {
        val others = listOf(
            WebSocketState.Disconnected,
            WebSocketState.Failed(null),
            WebSocketState.Reconnecting(3, 0)
        )

        others.forEach { state ->
            assertEquals(
                "$state 应走正常建连分支",
                OpenWebSocketDecision.OPEN,
                WebSocketRecoveryPolicy.openDecision(state, 0, t0)
            )
        }
    }
}
