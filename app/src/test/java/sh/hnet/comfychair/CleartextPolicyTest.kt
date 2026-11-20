package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.util.NetworkPolicy

/**
 * P2-13：明文出口策略（`NetworkPolicy.isCleartextAllowed`）。
 *
 * 平台 network_security_config 无法用 `<domain>` 表达 CIDR 网段，所以「明文只允许私网」
 * 的边界在网络层执行。这里把边界钉死：
 * - 私网/本机主机（含 10/8、172.16/12、192.168/16、127/8、链路本地、IPv6 ULA、
 *   localhost、*.local、*.lan、无点短名）→ 允许明文，局域网连接不受影响；
 * - 公网 IP / 公网域名 → 禁止明文（必须 https），防降级与明文中间人。
 */
class CleartextPolicyTest {

    @Test
    fun `私网与局域网主机允许明文`() {
        val allowed = listOf(
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.20",
            "127.0.0.1",
            "169.254.1.1",
            "localhost",
            "comfyui.local",
            "nas.lan",
            "myserver",
            "fe80::1",
            "fd12:3456:789a::1"
        )
        for (host in allowed) {
            assertTrue("$host 应允许明文", NetworkPolicy.isCleartextAllowed(host))
        }
    }

    @Test
    fun `公网主机禁止明文`() {
        val denied = listOf(
            "8.8.8.8",
            "1.1.1.1",
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
            "11.0.0.1",
            "comfy.example.com",
            "mycomfy.duckdns.org",
            "2606:4700:4700::1111"
        )
        for (host in denied) {
            assertFalse("$host 不应允许明文", NetworkPolicy.isCleartextAllowed(host))
        }
    }
}