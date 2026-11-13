package sh.hnet.comfychair

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 证书问题判定的回归测试。
 *
 * 背景（2026-09-18 用户报障）：域名连接时**每次**都弹「未知的证书颁发机构」。
 * 原因是自定义 TrustManager 直接把"非自签名"一律判成 UNKNOWN_CA，
 * 从不检查系统是否真的信任——Cloudflare 这类合法证书因此被误报。
 *
 * 修复后：先交给系统信任库校验，通过则判定为 NONE（不告警），
 * 只有系统**明确拒绝**时才按自签名/未知 CA 分类并提示。
 *
 * 测试数据：`public-ca-chain.pem` 是公开 CA 签发的真实证书链
 * （叶子 www.mozilla.org + Let's Encrypt YR1 + ISRG Root YR，2026-11 抓取；
 * 中间证书轮换或到期后重新抓取即可）；`self-signed.pem` 是本地生成的十年期自签证书。
 */
class SelfSignedCertHelperTest {

    @Before
    fun setUp() {
        SelfSignedCertHelper.reset()
    }

    @After
    fun tearDown() {
        SelfSignedCertHelper.reset()
    }

    @Test
    fun `公开 CA 签发的合法证书不再被判为未知颁发机构`() {
        val chain = loadCertificates("public-ca-chain.pem")
        assertEquals("测试数据应包含完整链（叶子+中间+根）", 3, chain.size)

        val trustManager = SelfSignedCertHelper.createTrustManager()
        // authType 取证书自己的公钥算法（JSSE 传的是 "EC"/"RSA"，与 OkHttp 实际握手一致）
        trustManager.checkServerTrusted(chain, chain[0].publicKey.algorithm)

        assertEquals(
            "系统信任的公开 CA 证书必须判定为 NONE，否则域名连接会误报证书警告",
            CertificateIssue.NONE,
            SelfSignedCertHelper.certificateIssue
        )
    }

    @Test
    fun `自签名证书仍被识别并告警`() {
        val chain = loadCertificates("self-signed.pem")

        val trustManager = SelfSignedCertHelper.createTrustManager()
        trustManager.checkServerTrusted(chain, chain[0].publicKey.algorithm)

        assertEquals(
            "局域网自签名证书应判定为 SELF_SIGNED，界面照旧提示但允许连接",
            CertificateIssue.SELF_SIGNED,
            SelfSignedCertHelper.certificateIssue
        )
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
