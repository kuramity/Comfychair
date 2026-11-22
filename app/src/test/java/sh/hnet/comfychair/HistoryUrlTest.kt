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
}
