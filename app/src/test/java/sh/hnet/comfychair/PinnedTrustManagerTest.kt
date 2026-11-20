package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 分场景 TLS 信任的信任管理器：系统校验 / 指纹匹配 / TOFU 三条放行路径。
 *
 * 关键回归点：**已有指纹但不匹配时必须失败**。把这条改成"永远放行"，
 * `指纹不一致必须抛异常` 一定变红 —— 这是本条安全属性的反证。
 */
class PinnedTrustManagerTest {

    private val selfSignedChain: Array<X509Certificate> by lazy { loadCertificates("self-signed.pem") }
    private val leaf: X509Certificate get() = selfSignedChain[0]

    private class StatusSystemTrustManager(private val trusted: Boolean) : X509TrustManager {
        var callCount = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            callCount++
            if (!trusted) throw CertificateException("系统不受信")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `系统信任通过时直接放行且不记录指纹`() {
        val system = StatusSystemTrustManager(trusted = true)
        var firstSeen: String? = null
        var issue: CertificateIssue? = null
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { null },
            onFirstSeen = { firstSeen = it },
            onIssue = { issue = it },
            allowTofu = true
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertEquals("系统信任时不应记录证书问题", CertificateIssue.NONE, issue)
        assertEquals("系统信任时不应触发 TOFU 记录", null, firstSeen)
    }

    @Test
    fun `首次见到自签证书记录指纹并放行`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeen: String? = null
        var issue: CertificateIssue? = null
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { null },
            onFirstSeen = { firstSeen = it },
            onIssue = { issue = it },
            allowTofu = true
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertEquals("自签证书应标记为 SELF_SIGNED", CertificateIssue.SELF_SIGNED, issue)
        assertNotNull("首次信任必须把指纹交给上层持久化", firstSeen)
        assertEquals("记录下来的指纹必须是叶子证书的 SHA-256", PinnedTrustManager.fingerprintOf(leaf), firstSeen)
        assertEquals("SHA-256 十六进制串长度应为 64", 64, firstSeen!!.length)
    }

    @Test
    fun `已有指纹匹配时放行且不重复记录`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeenCount = 0
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { PinnedTrustManager.fingerprintOf(leaf) },
            onFirstSeen = { firstSeenCount++ },
            allowTofu = true
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertEquals("指纹匹配时不应再次触发 TOFU 写入", 0, firstSeenCount)
    }

    @Test
    fun `指纹带分隔符或大写时也能匹配`() {
        val formatted = PinnedTrustManager.fingerprintOf(leaf)
            .uppercase()
            .chunked(2)
            .joinToString(":")
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeenCount = 0
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { formatted },
            onFirstSeen = { firstSeenCount++ },
            allowTofu = true
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertEquals("归一化后匹配时不应触发 TOFU 写入", 0, firstSeenCount)
    }

    @Test
    fun `指纹不一致必须抛异常`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeenCount = 0
        var issue: CertificateIssue? = null
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { "0000000000000000000000000000000000000000000000000000000000000000" },
            onFirstSeen = { firstSeenCount++ },
            onIssue = { issue = it },
            allowTofu = true
        )

        assertThrows(
            "已有指纹不匹配时必须失败，绝不能回退到 TOFU 放行",
            CertificateException::class.java
        ) {
            tm.checkServerTrusted(selfSignedChain, "RSA")
        }
        assertEquals("指纹不匹配时不应覆盖已保存的指纹", 0, firstSeenCount)
        assertEquals("指纹不匹配必须上报 PIN_MISMATCH 供界面提示", CertificateIssue.PIN_MISMATCH, issue)
    }

    @Test
    fun `公网不允许TOFU时系统不信任且无指纹必须抛异常且不写盘`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeenCount = 0
        var untrusted: String? = null
        var issue: CertificateIssue? = null
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { null },
            onFirstSeen = { firstSeenCount++ },
            onIssue = { issue = it },
            allowTofu = false,
            onUntrusted = { untrusted = it }
        )

        assertThrows(
            "公网主机系统不信任且无指纹时必须失败，等待用户显式确认",
            CertificateException::class.java
        ) {
            tm.checkServerTrusted(selfSignedChain, "RSA")
        }
        assertEquals("公网场景绝不能自动写盘信任", 0, firstSeenCount)
        assertEquals("必须上报 UNTRUSTED_PUBLIC 供界面提示", CertificateIssue.UNTRUSTED_PUBLIC, issue)
        assertNotNull("必须把待确认指纹交给上层", untrusted)
        assertEquals("待确认指纹必须是叶子证书的 SHA-256", PinnedTrustManager.fingerprintOf(leaf), untrusted)
        assertEquals("待确认指纹长度应为 64", 64, untrusted!!.length)
    }

    @Test
    fun `公网不允许TOFU但已有指纹匹配时放行`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeenCount = 0
        var untrustedCount = 0
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { PinnedTrustManager.fingerprintOf(leaf) },
            onFirstSeen = { firstSeenCount++ },
            allowTofu = false,
            onUntrusted = { untrustedCount++ }
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertEquals("指纹匹配时不应再次触发 TOFU 写入", 0, firstSeenCount)
        assertEquals("指纹匹配时不应进入待用户确认状态", 0, untrustedCount)
    }

    @Test
    fun `私网允许TOFU时系统不信任且无指纹直接放行`() {
        val system = StatusSystemTrustManager(trusted = false)
        var firstSeen: String? = null
        var untrustedCount = 0
        val tm = PinnedTrustManager(
            systemTrustManager = system,
            pinnedFingerprint = { null },
            onFirstSeen = { firstSeen = it },
            allowTofu = true,
            onUntrusted = { untrustedCount++ }
        )

        tm.checkServerTrusted(selfSignedChain, "RSA")

        assertNotNull("私网首次连接应记录指纹（TOFU）", firstSeen)
        assertEquals("私网不应进入待用户确认状态", 0, untrustedCount)
    }

    private fun loadCertificates(resourceName: String): Array<X509Certificate> {
        val stream = javaClass.classLoader!!.getResourceAsStream(resourceName)
            ?: error("测试资源缺失: $resourceName")
        val factory = CertificateFactory.getInstance("X.509")
        return stream.use { input ->
            factory.generateCertificates(input).filterIsInstance<X509Certificate>().toTypedArray()
        }
    }
}