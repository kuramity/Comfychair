package sh.hnet.comfychair.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可见性三态合成的单测（v4.18.x 后台省流量，阶段 A 的纯函数面）。
 *
 * 覆盖点：ProcessLifecycleOwner 与「多窗口失焦」两路信号的合成规则 ——
 * 小窗收球（ON_STOP 不触发、只有 onWindowFocusChanged(false)）是本方案的
 * 核心场景，合成错了整条降级链路就失效。
 */
class AppVisibilityTest {

    // ---------- 合成规则 ----------

    @Test
    fun `进程未Started一律判真后台`() {
        // 即使残留失焦标记（异常时序兜底），没有可见 Activity 就是后台
        assertEquals(AppVisibility.BACKGROUND, resolveAppVisibility(processStarted = false, multiWindowUnfocused = false))
        assertEquals(AppVisibility.BACKGROUND, resolveAppVisibility(processStarted = false, multiWindowUnfocused = true))
    }

    @Test
    fun `进程Started且多窗口失焦判小窗降级态`() {
        assertEquals(
            AppVisibility.MULTI_WINDOW_UNFOCUSED,
            resolveAppVisibility(processStarted = true, multiWindowUnfocused = true)
        )
    }

    @Test
    fun `进程Started无失焦判前台`() {
        assertEquals(AppVisibility.FOREGROUND, resolveAppVisibility(processStarted = true, multiWindowUnfocused = false))
    }

    // ---------- isDegraded 语义 ----------

    @Test
    fun `降级态为小窗失焦与真后台两种`() {
        assertTrue(AppVisibility.MULTI_WINDOW_UNFOCUSED.isDegraded)
        assertTrue(AppVisibility.BACKGROUND.isDegraded)
        assertFalse(AppVisibility.FOREGROUND.isDegraded)
    }
}
