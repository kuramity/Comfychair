package sh.hnet.comfychair.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图库「加载更早」分页计算的纯函数单测（config-audit-report 事项2）。
 *
 * 钉住三件事：
 * 1. **窗口档位计算**（olderWindowSize）：每档 = pageSize×(taps+2)，
 *    第 5 档后返回 null（上限截断，防无限翻）；
 * 2. **到底判定**（olderHistoryExhausted）：返回条数 < 请求窗口 = 整段历史已在手；
 * 3. **与首屏单页常量的关系**：档位步进取 HISTORY_MAX_ITEMS（100），
 *    但**不改动**它本身的语义（BackgroundDegradationPolicyTest:171 钉着 100）。
 *
 * 为什么分页不用 offset：F:\ComfyUI\execution.py:1423-1439 实测 offset 从**最旧**
 * 一端计数、响应无 total，客户端算不出"最新端再往更早"的步进 ——
 * 详见 olderWindowSize 的 KDoc（含 L=150 误判到底的反例）。
 */
class GalleryPaginationTest {

    private val pageSize = GalleryRepository.OLDER_WINDOW_STEP_ITEMS
    private val maxTaps = GalleryRepository.MAX_OLDER_WINDOW_TAPS

    // ============================================================
    // 1. 窗口档位计算（含上限截断）
    // ============================================================

    @Test
    fun `步进取值与首屏单页上限同值`() {
        assertEquals(100, pageSize)
    }

    @Test
    fun `第0档之后窗口放宽到两档首屏`() {
        // 首屏已拿最新 100 条 history；第一次"加载更早"请求最新 200 条，
        // 与索引重叠的 100 条由合并去重吃掉，净增约 100 条更早历史。
        assertEquals(200, olderWindowSize(0, pageSize, maxTaps))
    }

    @Test
    fun `窗口逐档线性放宽`() {
        assertEquals(300, olderWindowSize(1, pageSize, maxTaps))
        assertEquals(400, olderWindowSize(2, pageSize, maxTaps))
        assertEquals(500, olderWindowSize(3, pageSize, maxTaps))
        assertEquals(600, olderWindowSize(4, pageSize, maxTaps))
    }

    @Test
    fun `到达最大档数后截断返回null`() {
        // 上限截断：第 5 档已满（0..4），下一档必须拒绝 —— 服务端 history 是内存态
        // （重启即清，MAXIMUM_HISTORY_SIZE=10000），值得翻的更早段有限，不能无限要窗口。
        assertNull(olderWindowSize(5, pageSize, maxTaps))
        assertNull(olderWindowSize(6, pageSize, maxTaps))
        assertNull(olderWindowSize(Int.MAX_VALUE, pageSize, maxTaps))
    }

    @Test
    fun `非法参数一律拒绝`() {
        assertNull(olderWindowSize(-1, pageSize, maxTaps))
        assertNull(olderWindowSize(0, 0, maxTaps))
        assertNull(olderWindowSize(0, pageSize, 0))
    }

    @Test
    fun `任何一档的窗口不超过上限总条数`() {
        val maxWindow = pageSize * (maxTaps + 1)
        for (taps in 0 until maxTaps) {
            val window = olderWindowSize(taps, pageSize, maxTaps)!!
            assertTrue("窗口 $window 超出上限 $maxWindow", window <= maxWindow)
        }
    }

    // ============================================================
    // 2. 到底判定
    // ============================================================

    @Test
    fun `返回条数小于请求窗口即到底`() {
        assertTrue(olderHistoryExhausted(pageEntryCount = 199, requestedWindow = 200))
        assertTrue(olderHistoryExhausted(pageEntryCount = 0, requestedWindow = 200))
    }

    @Test
    fun `满页不算到底还有更早可翻`() {
        assertFalse(olderHistoryExhausted(pageEntryCount = 200, requestedWindow = 200))
        assertFalse(olderHistoryExhausted(pageEntryCount = 600, requestedWindow = 600))
    }
}
