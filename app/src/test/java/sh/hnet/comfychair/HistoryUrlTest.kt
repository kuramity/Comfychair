package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `/history` URL 构造的单测（v4.18.x 阶段 E：max_items 限制）。
 *
 * 服务端支持（F:\ComfyUI\server.py:1139）：`max_items = request.rel_url.query.get("max_items")`。
 * 带错参数会让图库拉不到数据或退回全量 —— 钉住两种形态的精确拼法。
 */
class HistoryUrlTest {

    @Test
    fun `带上限拼max_items查询参数`() {
        assertEquals(
            "http://192.168.1.10:8188/history?max_items=100",
            historyUrl("http://192.168.1.10:8188", 100)
        )
    }

    @Test
    fun `null上限保持全量旧行为`() {
        assertEquals(
            "http://192.168.1.10:8188/history",
            historyUrl("http://192.168.1.10:8188", null)
        )
    }

    @Test
    fun `https主机同样适用`() {
        assertEquals(
            "https://comfy.example.com:8443/history?max_items=50",
            historyUrl("https://comfy.example.com:8443", 50)
        )
    }

    // ============================================================
    // offset 拼接（图库「加载更早」分页；实测语义见下方第 4 条用例）
    // ============================================================

    @Test
    fun `max_items 与 offset 同时传时按顺序拼两个参数`() {
        assertEquals(
            "http://192.168.1.10:8188/history?max_items=200&offset=100",
            historyUrl("http://192.168.1.10:8188", 200, 100)
        )
    }

    @Test
    fun `仅 offset 也能拼`() {
        assertEquals(
            "http://192.168.1.10:8188/history?offset=300",
            historyUrl("http://192.168.1.10:8188", null, 300)
        )
    }

    @Test
    fun `不传 offset 与旧行为逐字节一致`() {
        // 图库首屏与"加载更早"都走这一形态：省略 offset 时服务端置 -1，
        // 由 execution.py 换算 offset = len - max_items 取**最新端**窗口。
        assertEquals(
            "http://192.168.1.10:8188/history?max_items=100",
            historyUrl("http://192.168.1.10:8188", 100, null)
        )
    }

    @Test
    fun `offset 0 必须原样拼出不能当无 offset 吞掉`() {
        // 实测（F:\ComfyUI\execution.py:1423-1439）：offset=0 是"从最旧一端取"，
        // 与省略 offset（取最新端）语义**相反**。把 0 吞掉会让分页悄悄翻向另一头。
        assertEquals(
            "http://192.168.1.10:8188/history?max_items=100&offset=0",
            historyUrl("http://192.168.1.10:8188", 100, 0)
        )
    }
}
