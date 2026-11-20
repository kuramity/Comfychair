package sh.hnet.comfychair.util

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * P2-13 复核（P2-13-redirect）：明文闸门必须是**网络拦截器**。
 *
 * OkHttp 的 30x 跳转由 `RetryAndFollowUpInterceptor` 内部重新发起，只重走
 * 「连接 + 网络拦截器」这一段；应用拦截器（`addInterceptor`）对初始请求只执行一次。
 * 所以「私网/公网 https 主机 302 到公网 http://」这一跳，只有网络拦截器看得到。
 *
 * 测试不联网：本地起一个只应答 HTTP 的服务（127.0.0.1，私网，本身允许明文），
 * 通过 `Dns` 把「公网形状的主机名」解析到 127.0.0.1 —— 主机分类看的是 URL 里的
 * host 字符串，所以 `public.example` 会被判为公网、命中闸门，而连接仍然落在本机。
 *
 * 两条用例：
 * 1. 网络拦截器：跳转那一跳进入闸门，抛「拒绝明文连接公网主机 public.example」；
 * 2. 应用拦截器：同样的跳转不经过闸门（请求真的发出去了，服务器收到第 2 个请求）——
 *    这正是复核指出的缝，用测试把它钉成「必须用网络拦截器」的理由。
 */
class CleartextRedirectGateTest {

    private val publicLookingHost = "public.example"

    /** 把所有主机名解析到回环：主机分类看 URL 里的 host 字符串，连接却落在本机，测试不联网 */
    private val fakeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    }

    /**
     * 启动一个只认「/start → 302 到公网形状主机」的本地 HTTP 服务。
     * @return 端口与「已收到的请求数」计数器（用于证明第 2 跳是否真的到达）
     */
    private fun startRedirectServer(acceptCount: Int): Triple<ServerSocket, AtomicInteger, Thread> {
        val server = ServerSocket(0, acceptCount)
        val hits = AtomicInteger(0)
        val worker = thread(isDaemon = true, name = "redirect-test-server") {
            runCatching {
                repeat(acceptCount) {
                    val socket = server.accept()
                    socket.use { s ->
                        hits.incrementAndGet()
                        val reader = s.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        // 读掉请求头（读到空行为止）
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val isStart = requestLine.contains("/start")
                        val response = if (isStart) {
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: http://$publicLookingHost:${server.localPort}/blocked\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                        }
                        s.getOutputStream().let { out ->
                            out.write(response.toByteArray())
                            out.flush()
                        }
                    }
                }
            }
            runCatching { server.close() }
        }
        return Triple(server, hits, worker)
    }

    private fun client(networkInterceptor: Boolean, server: ServerSocket): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(fakeDns)
            .followRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        if (networkInterceptor) {
            builder.addNetworkInterceptor(CleartextGuardInterceptor)
        } else {
            builder.addInterceptor(CleartextGuardInterceptor)
        }
        return builder.build()
    }

    @Test
    fun `网络拦截器能看到 30x 跳转后的公网明文请求并拦下`() {
        val (server, _, worker) = startRedirectServer(acceptCount = 2)
        try {
            client(networkInterceptor = true, server = server).newCall(
                Request.Builder().url("http://127.0.0.1:${server.localPort}/start").build()
            ).execute().close()
            fail("从私网 http 跳转到公网 http 必须被拒绝")
        } catch (e: IOException) {
            assertTrue(
                "跳转后的明文请求必须命中闸门，实际错误：${e.javaClass.simpleName} ${e.message}",
                e.message?.contains("拒绝明文连接公网主机 $publicLookingHost") == true
            )
        } finally {
            runCatching { server.close() }
            worker.join(3000)
        }
    }

    @Test
    fun `应用拦截器看不到跳转后的请求_故不能用它挂闸门`() {
        val (server, hits, worker) = startRedirectServer(acceptCount = 2)
        try {
            client(networkInterceptor = false, server = server).newCall(
                Request.Builder().url("http://127.0.0.1:${server.localPort}/start").build()
            ).execute().use { response ->
                // 跳转被自动跟随，闸门没参与 → 第 2 跳的明文请求真的发出去了
                assertEquals("第 2 跳应当真的到达服务器（说明闸门漏了这一跳）", 2, hits.get())
                assertTrue("跳转应成功返回 200", response.isSuccessful)
            }
        } catch (e: IOException) {
            throw AssertionError(
                "应用拦截器版本不应被闸门拦下（正因如此才必须用网络拦截器）：${e.message}",
                e
            )
        } finally {
            runCatching { server.close() }
            worker.join(3000)
        }
    }
}