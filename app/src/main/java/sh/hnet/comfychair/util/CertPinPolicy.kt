package sh.hnet.comfychair.util

import java.security.MessageDigest

/**
 * 证书指纹（pin）与主机匹配策略（P0-3 复核 R2-L2）。
 *
 * 用途：浏览器 SSO 的 WebView 在收到 SSL 错误时，除了「私网放行 / 公网拒绝」之外，
 * 还要能识别**用户已经显式信任过的公网自签服务器**——即连接层在 A5 指纹确认流程里
 * 写进 ServerStorage 的那条 SHA-256 指纹。否则这类服务器的浏览器登录会被硬阻断。
 *
 * 安全边界（必须同时满足，缺一不可）：
 * 1. SSL 错误所在的 host 与我们要登录的服务器 host 完全相同（大小写不敏感）；
 * 2. 呈现的证书 SHA-256 与已保存的 pin 一致（指纹比对用常量时间比较）。
 * 任何一项不满足都返回 false，由调用方继续按「私网放行 / 其余拒绝」处理。
 *
 * 纯 Kotlin（只用 `java.security.MessageDigest`），可直接 JVM 单测。
 */
object CertPinPolicy {

    /** 归一化后的指纹形态：64 位小写十六进制 */
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /**
     * 归一化证书指纹：去掉冒号与空格、转小写；不是 64 位十六进制则返回 null。
     *
     * 与 [sh.hnet.comfychair.PinnedTrustManager.normalizeFingerprint] 同口径
     * （存盘的都是 `fingerprintOf` 产出的无分隔小写十六进制，这里再做一次兜底）。
     */
    fun normalize(fingerprint: String?): String? {
        val cleaned = fingerprint
            ?.replace(":", "")
            ?.replace(" ", "")
            ?.trim()
            ?.lowercase()
            ?: return null
        if (!SHA256_HEX.matches(cleaned)) return null
        return cleaned
    }

    /**
     * 实际证书指纹是否与已保存的 pin 一致。
     *
     * 两边都先归一化；任一非法（null/空/非 64 位十六进制）直接判不一致。
     */
    fun matches(actualFingerprintHex: String?, pinnedFingerprint: String?): Boolean {
        val actual = normalize(actualFingerprintHex) ?: return false
        val pinned = normalize(pinnedFingerprint) ?: return false
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            pinned.toByteArray(Charsets.US_ASCII)
        )
    }

    /** SSL 错误所在的 host 是否就是我们要登录的那台服务器（大小写不敏感，空值不算匹配）。 */
    fun isSameHost(host: String?, serverHost: String?): Boolean {
        val a = host?.trim()?.lowercase().orEmpty()
        val b = serverHost?.trim()?.lowercase().orEmpty()
        return a.isNotEmpty() && a == b
    }
}
