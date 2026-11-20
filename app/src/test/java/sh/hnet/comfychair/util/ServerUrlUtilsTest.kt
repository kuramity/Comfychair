package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址解析与输入校验。
 *
 * A4-1 回归点：设置页曾经拒绝带 `http://` 的地址，导致 HTTP-only 服务器
 * 从「自动降级可用」变成「完全无法连接」。这里钉住「剥离 scheme 后再校验」的行为。
 */
class ServerUrlUtilsTest {

    @Test
    fun `http 前缀拆分`() {
        assertEquals("http" to "x", ServerUrlUtils.splitProtocol("http://x"))
    }

    @Test
    fun `https 前缀拆分`() {
        assertEquals("https" to "x", ServerUrlUtils.splitProtocol("https://x"))
    }

    @Test
    fun `无前缀保持自动`() {
        assertEquals(null to "x", ServerUrlUtils.splitProtocol("x"))
    }

    @Test
    fun `去空白与末尾斜杠`() {
        assertEquals(null to "x", ServerUrlUtils.splitProtocol(" x/ "))
        assertEquals("http" to "x", ServerUrlUtils.splitProtocol("http://x/"))
    }

    @Test
    fun `空串返回空主机`() {
        assertEquals(null to "", ServerUrlUtils.splitProtocol(""))
    }

    @Test
    fun `带 scheme 的 IP 地址校验通过`() {
        assertTrue(ServerUrlUtils.isValidHostname("http://192.168.1.5"))
        assertTrue(ServerUrlUtils.isValidHostname("https://192.168.1.5"))
    }

    @Test
    fun `带 scheme 的域名校验通过`() {
        assertTrue(ServerUrlUtils.isValidHostname("http://comfy.example.com"))
    }

    @Test
    fun `只有 scheme 没有主机名时校验失败`() {
        assertFalse(ServerUrlUtils.isValidHostname("http://"))
        assertFalse(ServerUrlUtils.isValidHostname(""))
    }

    @Test
    fun `拼 URL 时剥离主机名上残留的 scheme`() {
        assertEquals(
            "http://1.2.3.4:8188",
            ServerUrlUtils.buildServerUrl("http", "http://1.2.3.4", 8188)
        )
        assertEquals(
            "https://comfy.example.com:8188",
            ServerUrlUtils.buildServerUrl("https", "https://comfy.example.com", 8188)
        )
        assertEquals(
            "https://1.2.3.4:8188",
            ServerUrlUtils.buildServerUrl("https", "1.2.3.4", 8188)
        )
    }
}
