package sh.hnet.comfychair.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台降级决策表的单测（v4.18.x 后台省流量的核心）。
 *
 * [BackgroundDegradationPolicy.decideWsPolicy] 是「可见性 × 网络 × 任务 × 开关」
 * 四因子决策的**唯一权威表**，这里逐格钉住 —— 协调器只执行路由结果，
 * 决策分支不留第二份（与 GalleryTrashLogicTest 钉 planPurgeRoute 同一纪律）。
 */
class BackgroundDegradationPolicyTest {

    // ---------- 决策表：前台 ----------

    @Test
    fun `前台恒为全速心跳（四因子其余维度均不影响）`() {
        for (network in BackgroundDegradationPolicy.NetworkClass.entries) {
            for (task in listOf(true, false)) {
                for (keep in listOf(true, false)) {
                    assertEquals(
                        "network=$network task=$task keep=$keep",
                        BackgroundDegradationPolicy.WsPolicy.KEEP_FULL_HEARTBEAT,
                        BackgroundDegradationPolicy.decideWsPolicy(
                            AppVisibility.FOREGROUND, network, task, keep
                        )
                    )
                }
            }
        }
    }

    // ---------- 决策表：降级 × UNMETERED ----------

    @Test
    fun `降级且不限量网络保持连接放宽心跳（开关与任务均不影响）`() {
        for (task in listOf(true, false)) {
            for (keep in listOf(true, false)) {
                assertEquals(
                    "task=$task keep=$keep",
                    BackgroundDegradationPolicy.WsPolicy.KEEP_RELAXED_HEARTBEAT,
                    BackgroundDegradationPolicy.decideWsPolicy(
                        AppVisibility.BACKGROUND,
                        BackgroundDegradationPolicy.NetworkClass.UNMETERED,
                        task, keep
                    )
                )
                assertEquals(
                    "task=$task keep=$keep (小窗失焦)",
                    BackgroundDegradationPolicy.WsPolicy.KEEP_RELAXED_HEARTBEAT,
                    BackgroundDegradationPolicy.decideWsPolicy(
                        AppVisibility.MULTI_WINDOW_UNFOCUSED,
                        BackgroundDegradationPolicy.NetworkClass.UNMETERED,
                        task, keep
                    )
                )
            }
        }
    }

    // ---------- 决策表：降级 × METERED ----------

    @Test
    fun `降级且蜂窝但开关打开保持连接（用户知情选择）`() {
        for (task in listOf(true, false)) {
            assertEquals(
                BackgroundDegradationPolicy.WsPolicy.KEEP_RELAXED_HEARTBEAT,
                BackgroundDegradationPolicy.decideWsPolicy(
                    AppVisibility.BACKGROUND,
                    BackgroundDegradationPolicy.NetworkClass.METERED,
                    task, keepConnectedWhenBackground = true
                )
            )
        }
    }

    @Test
    fun `降级且蜂窝开关关有任务断连改轮询`() {
        assertEquals(
            BackgroundDegradationPolicy.WsPolicy.DROP_AND_POLL,
            BackgroundDegradationPolicy.decideWsPolicy(
                AppVisibility.BACKGROUND,
                BackgroundDegradationPolicy.NetworkClass.METERED,
                hasActiveTask = true, keepConnectedWhenBackground = false
            )
        )
        assertEquals(
            BackgroundDegradationPolicy.WsPolicy.DROP_AND_POLL,
            BackgroundDegradationPolicy.decideWsPolicy(
                AppVisibility.MULTI_WINDOW_UNFOCUSED,
                BackgroundDegradationPolicy.NetworkClass.METERED,
                hasActiveTask = true, keepConnectedWhenBackground = false
            )
        )
    }

    @Test
    fun `降级且蜂窝开关关无任务断连静默`() {
        assertEquals(
            BackgroundDegradationPolicy.WsPolicy.DROP_AND_IDLE,
            BackgroundDegradationPolicy.decideWsPolicy(
                AppVisibility.BACKGROUND,
                BackgroundDegradationPolicy.NetworkClass.METERED,
                hasActiveTask = false, keepConnectedWhenBackground = false
            )
        )
    }

    // ---------- 决策表：UNKNOWN 视同 METERED（保守） ----------

    @Test
    fun `网络未知视同计量（开关关时省流量方向走）`() {
        assertEquals(
            BackgroundDegradationPolicy.WsPolicy.DROP_AND_IDLE,
            BackgroundDegradationPolicy.decideWsPolicy(
                AppVisibility.BACKGROUND,
                BackgroundDegradationPolicy.NetworkClass.UNKNOWN,
                hasActiveTask = false, keepConnectedWhenBackground = false
            )
        )
        assertEquals(
            BackgroundDegradationPolicy.WsPolicy.DROP_AND_POLL,
            BackgroundDegradationPolicy.decideWsPolicy(
                AppVisibility.BACKGROUND,
                BackgroundDegradationPolicy.NetworkClass.UNKNOWN,
                hasActiveTask = true, keepConnectedWhenBackground = false
            )
        )
    }

    // ---------- 心跳间隔 ----------

    @Test
    fun `心跳间隔_不限量2分钟_计量4分钟`() {
        assertEquals(
            120_000L,
            BackgroundDegradationPolicy.keepaliveIntervalMs(BackgroundDegradationPolicy.NetworkClass.UNMETERED)
        )
        assertEquals(
            240_000L,
            BackgroundDegradationPolicy.keepaliveIntervalMs(BackgroundDegradationPolicy.NetworkClass.METERED)
        )
        // UNKNOWN 走计量侧（保守）
        assertEquals(
            240_000L,
            BackgroundDegradationPolicy.keepaliveIntervalMs(BackgroundDegradationPolicy.NetworkClass.UNKNOWN)
        )
    }

    // ---------- /history 周期刷新 ----------

    @Test
    fun `history周期刷新只在前台允许`() {
        assertTrue(BackgroundDegradationPolicy.shouldPeriodicHistoryRefresh(AppVisibility.FOREGROUND))
        assertFalse(BackgroundDegradationPolicy.shouldPeriodicHistoryRefresh(AppVisibility.BACKGROUND))
        assertFalse(
            BackgroundDegradationPolicy.shouldPeriodicHistoryRefresh(AppVisibility.MULTI_WINDOW_UNFOCUSED)
        )
    }

    // ---------- 常量钉住（防止被无意改坏量化目标） ----------

    @Test
    fun `关键常量与设计口径一致`() {
        assertEquals(60_000L, BackgroundDegradationPolicy.DEGRADATION_DELAY_MS)
        assertEquals(30_000L, BackgroundDegradationPolicy.KEEPALIVE_FOREGROUND_MS)
        assertEquals(15L, BackgroundDegradationPolicy.QUEUE_POLL_INTERVAL_MINUTES)
        assertEquals(100, BackgroundDegradationPolicy.HISTORY_MAX_ITEMS)
    }
}
