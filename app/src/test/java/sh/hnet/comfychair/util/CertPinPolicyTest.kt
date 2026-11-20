package sh.hnet.comfychair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证书指纹匹配策略（P0-3 复核 R2-L2）。
 *
 * 背景：WebView 的 SSL 错误处理只按「私网放行 / 公网拒绝」分档，会把用户已经在
 * A5 指纹确认流程里显式信任过的公网自签服务器一起挡掉。放行条件必须同时满足
 * 「同一主机」+「证书指纹与保存的 pin 一致」，任何一个不成立都不放行。
 */
class CertPinPolicyTest {

    /** 一个合法的 64 位小写十六进制指纹 */
    private val pin = "0123456789abcdef".repeat(4)

    // ---------- 指纹归一化 ----------

    @Test
    fun `合法指纹归一化为小写无分隔符`() {
        assertTrue(pin == CertPinPolicy.normalize(pin))
        assertTrue(pin == CertPinPolicy.normalize(pin.uppercase()))
        // 从系统界面复制出来的常见形态：冒号分隔
        val colonSeparated = pin.chunked(2).joinToString(":")
        assertTrue(pin == CertPinPolicy.normalize(colonSeparated))
        assertTrue(pin == CertPinPolicy.normalize("  $pin  "))
    }

    @Test
    fun `非法指纹返回 null`() {
        assertNull(CertPinPolicy.normalize(null))
        assertNull(CertPinPolicy.normalize(""))
        assertNull(CertPinPolicy.normalize("   "))
        assertNull(CertPinPolicy.normalize("abc123"))
        assertNull(CertPinPolicy.normalize("z".repeat(64)))
        assertNull(CertPinPolicy.normalize("0".repeat(63)))
    }

    // ---------- 指纹比对 ----------

    @Test
    fun `指纹一致才放行`() {
        assertTrue(CertPinPolicy.matches(pin, pin))
        // 大小写/分隔符差异不影响判定
        assertTrue(CertPinPolicy.matches(pin, pin.uppercase()))
        assertTrue(CertPinPolicy.matches(pin, pin.chunked(2).joinToString(":")))
    }

    @Test
    fun `指纹不一致或缺失一律拒绝`() {
        assertFalse(CertPinPolicy.matches("f".repeat(64), pin))
        assertFalse(CertPinPolicy.matches(pin, null))
        assertFalse(CertPinPolicy.matches(pin, ""))
        assertFalse(CertPinPolicy.matches(null, pin))
        assertFalse(CertPinPolicy.matches(pin, "not-a-fingerprint"))
    }

    // ---------- 主机匹配 ----------

    @Test
    fun `只有同一主机才认 pin`() {
        assertTrue(CertPinPolicy.isSameHost("comfy.example.com", "comfy.example.com"))
        assertTrue(CertPinPolicy.isSameHost("Comfy.Example.COM", "comfy.example.com"))
        assertTrue(CertPinPolicy.isSameHost("  comfy.example.com  ", "comfy.example.com"))
        // 子域/CDN/身份提供方都不算本服务器
        assertFalse(CertPinPolicy.isSameHost("cdn.example.com", "comfy.example.com"))
        assertFalse(CertPinPolicy.isSameHost("evil-example.com", "comfy.example.com"))
        assertFalse(CertPinPolicy.isSameHost("", "comfy.example.com"))
        assertFalse(CertPinPolicy.isSameHost("   ", "comfy.example.com"))
        // 一侧缺失时不算匹配（fail-closed）
        assertFalse(CertPinPolicy.isSameHost(null, "comfy.example.com"))
        assertFalse(CertPinPolicy.isSameHost("comfy.example.com", null))
    }
}
