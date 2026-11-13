package sh.hnet.comfychair

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate issue types that can be detected
 */
enum class CertificateIssue {
    NONE,           // No issue - certificate is valid and trusted
    SELF_SIGNED,    // Certificate is self-signed (issuer == subject)
    UNKNOWN_CA      // Certificate is signed by an unknown/untrusted CA
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
     * Create a TrustManager that accepts certificates the system would reject
     * (self-signed / private CA), while still reporting them as an issue.
     *
     * SECURITY NOTE: 系统不信任的证书仍会被放行——这是为局域网自签名 ComfyUI 服务的
     * 既有设计。区别在于：**合法证书现在会正常通过系统校验**，不再被误判。
     */
    internal fun createTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            /**
             * Check if client certificates are trusted
             * For our use case, we accept all client certificates
             */
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept all client certificates
            }

            /**
             * Check if server certificates are trusted
             *
             * @param chain The certificate chain from the server
             * @param authType The authentication type (e.g., "RSA", "ECDSA")
             */
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) return

                // 1) 先走系统校验：链合法（公开 CA 签发的正式证书）就直接放行，
                //    不记录任何问题 —— 域名连接不会再弹出莫须有的证书警告。
                if (verifyWithSystem(chain, authType)) {
                    certificateIssue = CertificateIssue.NONE
                    return
                }

                // 2) 系统不信任（自签名 / 私有 CA）——仍然接受连接，但记录问题类型，
                //    由界面提示"无法验证证书，仍然继续连接"。
                val cert = chain[0]
                certificateIssue = if (cert.issuerDN.name == cert.subjectDN.name) {
                    CertificateIssue.SELF_SIGNED
                } else {
                    CertificateIssue.UNKNOWN_CA
                }
            }

            /**
             * Return the list of accepted certificate issuers
             * We accept all, so return empty array
             */
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * 用系统信任库校验证书链。
     *
     * `authType` 由调用方的 TLS 栈给出，**各家取值并不一致**（JSSE 传 "ECDHE_ECDSA"，
     * 部分实现传 "EC"/"RSA"，JDK 甚至会对不认识的值直接抛 "Unknown authType"）。
     * 所以这里按证书公钥类型补齐候选值逐个试：只要有一个通过就算系统信任；
     * 只有当系统**明确判定证书不受信**（而非"不认识 authType"）时才返回 false。
     */
    private fun verifyWithSystem(chain: Array<out X509Certificate>, authType: String?): Boolean {
        val system = systemTrustManager ?: return false

        val candidates = LinkedHashSet<String>()
        if (!authType.isNullOrEmpty()) candidates.add(authType)
        when (chain[0].publicKey.algorithm.uppercase()) {
            "EC", "ECDSA" -> candidates.addAll(listOf("ECDHE_ECDSA", "EC"))
            "RSA" -> candidates.addAll(listOf("RSA", "ECDHE_RSA", "DHE_RSA"))
            else -> candidates.add("RSA")
        }

        for (candidate in candidates) {
            try {
                system.checkServerTrusted(chain, candidate)
                return true
            } catch (e: CertificateException) {
                // "Unknown authType" 只是取值不被该系统实现接受，换下一个候选继续试；
                // 其它异常（找不到信任路径、过期、域名不匹配…）即"确实不受信"。
                if (e.message?.startsWith("Unknown authType") == true) continue
                return false
            }
        }
        return false
    }

    /**
     * Configure an OkHttpClient.Builder to accept certificates with issues
     * (self-signed certificates and unknown CA certificates)
     *
     * @param builder The OkHttpClient.Builder to configure
     * @return The configured builder (for method chaining)
     */
    fun configureToAcceptSelfSigned(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            // Reset the certificate issue before each connection attempt
            certificateIssue = CertificateIssue.NONE

            // Create our custom trust manager
            val trustManager = createTrustManager()
            val trustManagers = arrayOf<TrustManager>(trustManager)

            // Create an SSL context with our trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, java.security.SecureRandom())

            // Configure the builder to use our SSL context
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)

            // 主机名校验保持放行：局域网里用的是 IP（192.168.x.x），
            // 而证书 CN 通常是域名，严格校验会让内网连接全部失败。
            builder.hostnameVerifier { _, _ -> true }

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
