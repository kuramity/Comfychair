package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住离线自动切换的防抖动判定：**连续 N 次才切、切换后有冷却**。
 *
 * 这里全部用假时钟推进，不依赖真实时间；判定器本身零 Android 依赖，
 * 因此可以在 JVM 上把"校园网抽风"这类时序逐条复现。
 */
class ConnectionStabilityTest {

    /** 任意基准时刻，只用来做相对时间运算 */
    private val t0 = 1_000_000L

    private fun online() = StabilityState(mode = StabilityMode.ONLINE)

    private fun offline(since: Long) = StabilityState(mode = StabilityMode.OFFLINE, lastSwitchAtMs = since)

    // ── ONLINE → OFFLINE ─────────────────────────────────────────────

    @Test
    fun `单次失败不切离线`() {
        val decision = ConnectionStability.evaluate(online(), ProbeOutcome.FAILURE, t0)

        assertNull("仅一次失败不得切离线", decision.switch)
        assertEquals(StabilityMode.ONLINE, decision.nextState.mode)
        assertEquals(1, decision.nextState.consecutiveFailures)
    }

    @Test
    fun `连续两次失败才切离线`() {
        val first = ConnectionStability.evaluate(online(), ProbeOutcome.FAILURE, t0).nextState
        val second = ConnectionStability.evaluate(first, ProbeOutcome.FAILURE, t0 + 1_000)

        assertEquals(StabilitySwitch.TO_OFFLINE, second.switch)
        assertEquals(StabilityMode.OFFLINE, second.nextState.mode)
        assertEquals("切换后计数必须清零", 0, second.nextState.consecutiveFailures)
        assertEquals("切换时刻要记进状态（用于冷却）", t0 + 1_000, second.nextState.lastSwitchAtMs)
    }

    @Test
    fun `失败之间夹一次成功则重新计数`() {
        val afterFailure = ConnectionStability.evaluate(online(), ProbeOutcome.FAILURE, t0).nextState
        val afterSuccess = ConnectionStability.evaluate(afterFailure, ProbeOutcome.SUCCESS, t0 + 1_000).nextState
        assertEquals("成功必须清零失败计数", 0, afterSuccess.consecutiveFailures)

        // 再失败一次仍是"第 1 次"，不得切换
        val secondFailure = ConnectionStability.evaluate(afterSuccess, ProbeOutcome.FAILURE, t0 + 2_000)
        assertNull("断-通-断 不得判定为连续两次失败", secondFailure.switch)
        assertEquals(1, secondFailure.nextState.consecutiveFailures)
    }

    // ── OFFLINE → ONLINE ─────────────────────────────────────────────

    @Test
    fun `离线态单次成功不切回在线`() {
        val decision = ConnectionStability.evaluate(offline(t0), ProbeOutcome.SUCCESS, t0 + 1_000)

        assertNull("仅一次成功不得切回在线", decision.switch)
        assertEquals(StabilityMode.OFFLINE, decision.nextState.mode)
        assertEquals(1, decision.nextState.consecutiveSuccesses)
    }

    @Test
    fun `连续两次成功才切回在线`() {
        val first = ConnectionStability.evaluate(offline(t0), ProbeOutcome.SUCCESS, t0 + 10_000).nextState
        val second = ConnectionStability.evaluate(first, ProbeOutcome.SUCCESS, t0 + 20_000)

        assertEquals(StabilitySwitch.TO_ONLINE, second.switch)
        assertEquals(StabilityMode.ONLINE, second.nextState.mode)
        assertEquals(0, second.nextState.consecutiveSuccesses)
    }

    @Test
    fun `离线态成功之间夹一次失败则重新计数`() {
        val afterSuccess = ConnectionStability.evaluate(offline(t0), ProbeOutcome.SUCCESS, t0 + 10_000).nextState
        val afterFailure = ConnectionStability.evaluate(afterSuccess, ProbeOutcome.FAILURE, t0 + 20_000).nextState
        assertEquals("失败必须清零成功计数", 0, afterFailure.consecutiveSuccesses)

        val secondSuccess = ConnectionStability.evaluate(afterFailure, ProbeOutcome.SUCCESS, t0 + 30_000)
        assertNull("通-断-通 不得判定为连续两次成功", secondSuccess.switch)
        assertEquals(1, secondSuccess.nextState.consecutiveSuccesses)
    }

    // ── 冷却窗口 ─────────────────────────────────────────────────────

    @Test
    fun `从未切换时冷却视为已耗尽`() {
        assertTrue(ConnectionStability.isCooldownElapsed(StabilityState.NEVER_SWITCHED, t0))
    }

    @Test
    fun `冷却期内即使连续两次失败也不切离线`() {
        // 模拟"刚切回在线就遇到失败"：切换时刻为 t0，两次失败都落在冷却窗口内
        var state = StabilityState(mode = StabilityMode.ONLINE, lastSwitchAtMs = t0)
        state = ConnectionStability.evaluate(state, ProbeOutcome.FAILURE, t0 + 1_000).nextState
        val decision = ConnectionStability.evaluate(state, ProbeOutcome.FAILURE, t0 + 2_000)

        assertNull("冷却期内不得切换", decision.switch)
        assertEquals(StabilityMode.ONLINE, decision.nextState.mode)
        assertEquals("冷却期内计数照常累积", 2, decision.nextState.consecutiveFailures)
    }

    @Test
    fun `冷却期过后的第一次失败立即切换`() {
        // 承接上一条：冷却期内已累积到 2 次，冷却一过无需重新数两遍
        var state = StabilityState(mode = StabilityMode.ONLINE, consecutiveFailures = 2, lastSwitchAtMs = t0)
        val decision = ConnectionStability.evaluate(
            state,
            ProbeOutcome.FAILURE,
            t0 + ConnectionStability.SWITCH_COOLDOWN_MS
        )

        assertEquals(StabilitySwitch.TO_OFFLINE, decision.switch)
        assertEquals(StabilityMode.OFFLINE, decision.nextState.mode)
    }

    @Test
    fun `冷却期内即使连续两次成功也不切回在线`() {
        var state = offline(t0)
        state = ConnectionStability.evaluate(state, ProbeOutcome.SUCCESS, t0 + 1_000).nextState
        val decision = ConnectionStability.evaluate(state, ProbeOutcome.SUCCESS, t0 + 2_000)

        assertNull("冷却期内不得切回在线", decision.switch)
        assertEquals(2, decision.nextState.consecutiveSuccesses)
    }

    // ── 探测节奏 ─────────────────────────────────────────────────────

    @Test
    fun `离线探测先快后稳`() {
        assertEquals(
            "刚进离线的第 1 次探测要快",
            ConnectionStability.PROBE_INTERVAL_FAST_MS,
            ConnectionStability.nextProbeDelayMs(0)
        )
        assertEquals(
            ConnectionStability.PROBE_INTERVAL_FAST_MS,
            ConnectionStability.nextProbeDelayMs(ConnectionStability.FAST_PROBE_COUNT - 1)
        )
        assertEquals(
            "之后转入常规间隔，避免长离线时频繁轮询耗电",
            ConnectionStability.PROBE_INTERVAL_STEADY_MS,
            ConnectionStability.nextProbeDelayMs(ConnectionStability.FAST_PROBE_COUNT)
        )
        assertTrue(
            "常规间隔必须明显大于快速间隔",
            ConnectionStability.PROBE_INTERVAL_STEADY_MS > ConnectionStability.PROBE_INTERVAL_FAST_MS
        )
        assertTrue(
            "探测超时必须短于探测间隔，否则会一轮压一轮",
            ConnectionStability.PROBE_TIMEOUT_MS < ConnectionStability.PROBE_INTERVAL_FAST_MS
        )
    }
}
