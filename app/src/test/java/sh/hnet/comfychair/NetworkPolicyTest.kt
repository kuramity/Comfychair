package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.util.NetworkPolicy

/**
 * 私网/公网主机判定。
 *
 * 这条判定是 TLS 分场景信任的开关：误判为私网 → 公网主机放行任意自签证书；
 * 误判为公网 → 局域网自签证书无法连接。两类边界都必须钉死。
 */
class NetworkPolicyTest {

    @Test
    fun `IPv4 私网地址`() {
        val privateHosts = listOf(
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.100",
            "192.168.0.1",
            "127.0.0.1",
            "127.255.255.255",
            "169.254.10.20"
        )
        for (host in privateHosts) {
            assertTrue("$host 应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `IPv4 私网区间的相邻公网地址`() {
        val publicHosts = listOf(
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
            "11.0.0.1",
            "169.253.0.1",
            "126.0.0.1"
        )
        for (host in publicHosts) {
            assertFalse("$host 不应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `CGNAT 段按私网处理`() {
        // 100.64.0.0/10：Tailscale / 运营商 CGNAT / 部分 VPN 常用。
        // Android 16 的 Local Network 定义也把它列为局域网段；漏掉会让这些用户
        // 无法用 http 连自建 ComfyUI。
        val privateHosts = listOf(
            "100.64.0.0",
            "100.64.0.1",
            "100.100.100.100",
            "100.127.255.255"
        )
        for (host in privateHosts) {
            assertTrue("$host 属于 100.64.0.0/10，应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `CGNAT 段前后相邻的公网地址`() {
        val publicHosts = listOf(
            "100.63.255.255",
            "100.128.0.0",
            "99.64.0.1",
            "101.64.0.1"
        )
        for (host in publicHosts) {
            assertFalse("$host 不属于 100.64.0.0/10，不应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `IPv4 映射的 IPv6 地址按内嵌 IPv4 判定`() {
        val privateHosts = listOf(
            "::ffff:192.168.1.5",
            "::ffff:100.64.0.1",
            "::ffff:127.0.0.1"
        )
        for (host in privateHosts) {
            assertTrue("$host 内嵌私网 IPv4，应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
        assertFalse(
            "::ffff:8.8.8.8 内嵌公网 IPv4，不应判定为私网",
            NetworkPolicy.isPrivateHost("::ffff:8.8.8.8")
        )
    }

    @Test
    fun `主机名形式的私网地址`() {
        val privateHosts = listOf(
            "localhost",
            "LOCALHOST",
            "myserver",
            "nas.local",
            "printer.lan",
            "comfyui.local."
        )
        for (host in privateHosts) {
            assertTrue("$host 应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `IPv6 私网地址`() {
        val privateHosts = listOf(
            "fe80::1",
            "FE80::abcd",
            "fc00::1",
            "fd12:3456:789a::1",
            "::1"
        )
        for (host in privateHosts) {
            assertTrue("$host 应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `公网地址不判定为私网`() {
        val publicHosts = listOf(
            "8.8.8.8",
            "1.1.1.1",
            "example.com",
            "api.example.com",
            "2001:4860:4860::8888",
            "2606:4700::1111"
        )
        for (host in publicHosts) {
            assertFalse("$host 不应判定为私网", NetworkPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `非法与空地址不判定为私网`() {
        assertFalse(NetworkPolicy.isPrivateHost(""))
        assertFalse(NetworkPolicy.isPrivateHost("   "))
        assertFalse(NetworkPolicy.isPrivateHost("999.1.1.1"))
        assertFalse(NetworkPolicy.isPrivateHost("1.2.3"))
    }
}