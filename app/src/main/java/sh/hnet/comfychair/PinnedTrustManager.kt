package sh.hnet.comfychair

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 带指纹固定（TOFU）的 TLS 服务端信任管理器。
 *
 * 放行路径：
 * 1. 系统信任库校验通过 —— 合法证书直接放行，不记录问题；
 * 2. 系统校验失败但已有指纹且**匹配** —— 放行（用户此前已信任过这张证书）；
 * 3. 系统校验失败且**没有**指纹：
 *    - [allowTofu] 为 true（私网主机）—— 记录指纹后放行（TOFU）；
 *    - [allowTofu] 为 false（公网主机）—— 只把指纹交给 [onUntrusted]（**不写盘**）后抛异常，
 *      必须由用户在界面上显式确认才能建立信任。
 *
 * 已有指纹但**不匹配**时抛 [CertificateException]：证书被替换或连接被中间人劫持，
 * 公网场景必须失败；私网场景由界面提供「重新信任」（清除指纹后重连一次）作为恢复路径。
 *
 * 注意：`checkServerTrusted` 的签名拿不到目标主机名，因此私网/公网判断由调用方
 * （[SelfSignedCertHelper]）通过 [allowTofu] 传入；主机名匹配一侧仍由 hostnameVerifier 负责。
 */
class PinnedTrustManager(
    private val systemTrustManager: X509TrustManager?,
    private val pinnedFingerprint: () -> String?,
    private val onFirstSeen: (String) -> Unit,
    private val onIssue: (CertificateIssue) -> Unit = {},
    /**
     * 系统校验失败且没有指纹时是否自动信任；公网主机必须为 false（需用户显式确认）。
     *
     * **不设默认值**：这是安全开关，新增调用点必须显式声明意图（漏传会编译失败，
     * 而不是悄悄落回 TOFU）。
     */
    private val allowTofu: Boolean,
    /** 不允许自动信任时把待确认指纹交给上层（仅内存保存，用户确认后才写盘） */
    private val onUntrusted: (String) -> Unit = {}
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // 本应用不校验客户端证书
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("服务器未提供证书链")
        }

        if (verifyWithSystem(chain, authType)) {
            onIssue(CertificateIssue.NONE)
            return
        }

        val leaf = chain[0]
        val issue = if (leaf.issuerDN.name == leaf.subjectDN.name) {
            CertificateIssue.SELF_SIGNED
        } else {
            CertificateIssue.UNKNOWN_CA
        }
        onIssue(issue)

        val fingerprint = fingerprintOf(leaf)
        val pinned = pinnedFingerprint()
        if (!pinned.isNullOrBlank()) {
            if (constantTimeEquals(normalizeFingerprint(pinned), fingerprint)) {
                return
            }
            // 指纹不一致：先按 PIN_MISMATCH 上报，便于界面提示；随后直接失败（不能降级信任）
            onIssue(CertificateIssue.PIN_MISMATCH)
            throw CertificateException(
                "服务器证书指纹与已保存的不一致：证书可能已更换或连接被劫持"
            )
        }

        if (!allowTofu) {
            // 公网主机：系统不信任且没有 pin 时不能静默信任（自签证书 + 匹配 SAN 足以冒充域名），
            // 只把指纹交给上层做待确认状态，等用户显式确认后再写盘
            onIssue(CertificateIssue.UNTRUSTED_PUBLIC)
            onUntrusted(fingerprint)
            throw CertificateException("服务器证书不被系统信任，需要用户显式确认后重试")
        }

        // 首次见到该证书：交给上层持久化后放行（TOFU）
        onFirstSeen(fingerprint)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

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
                if (e.message?.startsWith("Unknown authType") == true) continue
                return false
            }
        }
        return false
    }

    companion object {
        /** 叶子证书的 SHA-256 指纹（小写十六进制，无分隔符） */
        fun fingerprintOf(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** 归一化指纹：去掉分隔符、统一小写 */
        fun normalizeFingerprint(raw: String): String =
            raw.replace(":", "").replace(" ", "").lowercase()

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                actual.toByteArray(Charsets.US_ASCII)
            )
    }
}