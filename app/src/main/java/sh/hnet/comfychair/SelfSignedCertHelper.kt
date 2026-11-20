package sh.hnet.comfychair

import okhttp3.OkHttpClient
import sh.hnet.comfychair.util.NetworkPolicy
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate issue types that can be detected
 */
enum class CertificateIssue {
    NONE,               // No issue - certificate is valid and trusted
    SELF_SIGNED,        // Certificate is self-signed (issuer == subject)
    UNKNOWN_CA,         // Certificate is signed by an unknown/untrusted CA
    PIN_MISMATCH,       // 证书与此前信任过的指纹不一致（证书更换或中间人劫持）
    UNTRUSTED_PUBLIC    // 公网主机的证书系统不信任且从未信任过：必须用户显式确认，不得静默放行
}

/**
 * SelfSignedCertHelper - Helps handle SSL certificate issues
 *
 * In production environments, SSL certificates are issued by trusted Certificate Authorities (CAs).
 * However, many local ComfyUI servers use:
 * 1. Self-signed certificates (not signed by any CA)
 * 2. Certificates from custom/private CAs (not in device's trusted CA store)
 *
 * This helper allows the app to connect to such servers while
 * tracking what type of certificate issue was detected.
 *
 * **校验顺序（2026-09-18 修正）**：先交给系统信任库校验，只有系统**明确拒绝**时才判定为
 * "证书有问题"并进入兼容模式。此前的实现在自定义 TrustManager 里直接把"非自签名"一律
 * 判成 UNKNOWN_CA，于是 Cloudflare 这类**完全合法的公开 CA 证书也会每次误报**
 * "未知的证书颁发机构"（用户反馈的现象），根因就在这里。
 *
 * **分场景信任（v4.17.0）**：信任管理器改为 [PinnedTrustManager]（系统校验 → 指纹匹配 →
 * TOFU 三选一）；主机名校验只对私网放行，公网必须通过系统默认校验 —— 不再"任意证书 +
 * 任意主机名全放行"。
 */
object SelfSignedCertHelper {

    // Track what type of certificate issue was detected
    var certificateIssue = CertificateIssue.NONE
        private set

    /** 系统信任库（设备内置 CA 列表）。懒加载，取不到时退化为"全部按兼容处理"。 */
    private val systemTrustManager: X509TrustManager? by lazy {
        try {
            val factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 创建分场景信任管理器（无指纹固定版本，供单测与无持久化的调用方使用）。
     *
     * 系统信任的证书直接放行；系统拒绝的自签/私有 CA 证书按 TOFU 放行并记录问题类型。
     */
    internal fun createTrustManager(): X509TrustManager {
        return PinnedTrustManager(
            systemTrustManager = systemTrustManager,
            pinnedFingerprint = { null },
            onFirstSeen = { },
            onIssue = { certificateIssue = it },
            // 该入口只用于单测/无持久化场景，保持原有 TOFU 语义；
            // 生产路径一律走 configureToAcceptSelfSigned，由 hostname 决定 allowTofu
            allowTofu = true
        )
    }

    /**
     * Configure an OkHttpClient.Builder to accept certificates with issues
     * (self-signed certificates and unknown CA certificates)
     *
     * @param builder The OkHttpClient.Builder to configure
     * @param hostname 目标主机名/IP：私网主机允许 TOFU，公网主机必须由用户显式确认
     * @param pinnedFingerprint 已保存的证书指纹（SHA-256 十六进制）；null 表示尚未信任过
     * @param onFirstSeen 首次见到证书时的回调，用于把指纹交给上层持久化（TOFU）
     * @param onUntrusted 公网主机的证书不被系统信任且无指纹时的回调：只交指纹，不写盘
     * @return The configured builder (for method chaining)
     */
    fun configureToAcceptSelfSigned(
        builder: OkHttpClient.Builder,
        hostname: String = "",
        pinnedFingerprint: () -> String? = { null },
        onFirstSeen: (String) -> Unit = { },
        onUntrusted: (String) -> Unit = { }
    ): OkHttpClient.Builder {
        try {
            // Reset the certificate issue before each connection attempt
            certificateIssue = CertificateIssue.NONE

            // Create our custom trust manager
            val trustManager = PinnedTrustManager(
                systemTrustManager = systemTrustManager,
                pinnedFingerprint = pinnedFingerprint,
                onFirstSeen = onFirstSeen,
                onIssue = { certificateIssue = it },
                // 私网沿用 TOFU；公网一律要求用户显式确认（A5-1）
                allowTofu = NetworkPolicy.isPrivateHost(hostname),
                onUntrusted = onUntrusted
            )

            // Create an SSL context with our trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())

            // Configure the builder to use our SSL context
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)

            // 主机名校验分场景：私网主机（局域网 IP / localhost / 短名）放行 —— 内网证书的
            // CN 通常是域名，严格校验会让内网连接全部失败；公网主机必须走系统默认校验，
            // 与私网区分开，避免任意自签证书冒充公网域名。
            builder.hostnameVerifier { hostname, session ->
                NetworkPolicy.isPrivateHost(hostname) ||
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }

        } catch (e: Exception) {
            // If SSL configuration fails, the builder will use default SSL settings
        }

        return builder
    }

    /**
     * Reset the certificate issue detection
     * Should be called before each new connection attempt
     */
    fun reset() {
        certificateIssue = CertificateIssue.NONE
    }
}
