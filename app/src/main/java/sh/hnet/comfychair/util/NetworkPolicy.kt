package sh.hnet.comfychair.util

/**
 * 网络主机分类策略（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 用途：TLS 信任需要分场景 —— 局域网/本机地址（用户自己的 ComfyUI 常是自签证书）
 * 可以沿用宽松策略；公网主机必须走系统证书校验，不能放行任意自签证书。
 *
 * 覆盖范围：
 * - IPv4：10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、127.0.0.0/8、169.254.0.0/16
 *   以及 100.64.0.0/10（CGNAT；Tailscale 等 VPN 与部分运营商网络常用，
 *   Android 16 的 Local Network 定义也把它列为局域网段）
 * - IPv6：fe80::/10（链路本地）、fc00::/7（唯一本地地址，fc/fd 开头）、::1（回环），
 *   以及 IPv4 映射写法 ::ffff:a.b.c.d（按内嵌 IPv4 判定）
 * - 主机名：localhost、*.local（mDNS）、*.lan、无点短名（如 myserver）
 */
object NetworkPolicy {

    /**
     * 是否允许与该主机建立**明文**（http / ws）连接（P2-13）。
     *
     * 为什么必须在代码里判定，而不是只写 network_security_config：
     * Android 的 `<domain>` 只接受具体主机名或 IP 字面量，**不支持 CIDR 网段**，
     * 无法表达「10/172.16-31/192.168 私网段放行、其余一律禁止」。
     * 若把 base-config 改成 cleartextTrafficPermitted="false"，任意 IP 的局域网
     * ComfyUI（本应用的核心场景）会在 OkHttp 建立 socket 时直接抛
     * `CLEARTEXT communication ... not permitted by network security policy` —— 所有
     * 局域网用户都连不上。因此平台层保持放行，真正的边界在这里：
     * 明文只发给私网/本机主机（[isPrivateHost]），其余主机必须走 https。
     */
    fun isCleartextAllowed(host: String): Boolean = isPrivateHost(host)

    fun isPrivateHost(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return false

        // 去掉 IPv6 方括号、末尾根域点、IPv6 区域标识（fe80::1%wlan0）
        val normalized = trimmed
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase()
        val withoutZone = normalized.substringBefore('%')
        if (withoutZone.isEmpty()) return false

        val ipv4 = parseIpv4(withoutZone)
        if (ipv4 != null) return isPrivateIpv4(ipv4)

        if (withoutZone.contains(':')) return isPrivateIpv6(withoutZone)

        if (withoutZone == "localhost") return true
        if (withoutZone.endsWith(".local") || withoutZone.endsWith(".lan")) return true
        // 无点短名（如 myserver）：只能在内网解析，按私网处理
        if (!withoutZone.contains('.')) {
            // 无点但可能是 IP 字面量的其他写法：InetAddress 接受十进制整数（3689964177）
            // 与十六进制（0x...）形式并能路由到公网，不能当内网短名放行。
            // 注意：单字符 "0" 等纯数字串也会被拒绝——"0" 不是合法主机名，
            // 且主机名不允许全数字，拒绝方向安全，这是有意为之。
            if (withoutZone.all { it.isDigit() }) return false
            if (withoutZone.startsWith("0x", ignoreCase = true)) return false
            return true
        }

        return false
    }

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            // 前导零拒绝：传统解析按八进制（"010" = 8，公网段），本类按十进制会误判
            // 成私网 → 放行型安全判据必须收紧；标准写法不受影响（单字符 "0" 仍合法）。
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toIntOrNull() ?: return null
            if (octet > 255) return null
            octets[i] = octet
        }
        return octets
    }

    private fun isPrivateIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            a == 127 ||
            (a == 169 && b == 254) ||
            // 100.64.0.0/10（CGNAT）：a==100 且 b 在 64..127。
            // 漏掉这段会让「Tailscale / 运营商 CGNAT / 部分 VPN 后面自建 ComfyUI」的
            // 用户从可用变成抛 IOException —— 他们的地址对 App 而言就是局域网。
            (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(value: String): Boolean {
        if (value == "::1") return true

        // IPv4 映射写法（::ffff:192.168.1.5）：按内嵌的 IPv4 判定。
        // 不处理的话这种写法会被当成"公网 IPv6"拒掉明文连接。
        if (value.startsWith("::ffff:")) {
            parseIpv4(value.substringAfterLast(':'))?.let { return isPrivateIpv4(it) }
            return false
        }

        // 只取第一个 16 位组；"::" 开头表示首组为 0（回环 ::1 已单独处理）
        val firstGroup = value.substringBefore(':')
        if (firstGroup.isEmpty()) return false
        val first = firstGroup.toIntOrNull(16) ?: return false

        // fe80::/10：前 10 位固定
        if (first and 0xffc0 == 0xfe80) return true
        // fc00::/7：fc 与 fd 开头
        if (first and 0xfe00 == 0xfc00) return true

        return false
    }
}