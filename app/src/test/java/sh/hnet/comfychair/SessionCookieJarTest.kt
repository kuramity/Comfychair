package sh.hnet.comfychair

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 作用域：跨主机读取必须为空，避免重定向把凭据带到第三方主机。
 */
class SessionCookieJarTest {

    private val expectedHost = "192.168.1.100"
    private val serverUrl = "https://192.168.1.100:8188/".toHttpUrl()
    private val otherUrl = "https://evil.example.com/".toHttpUrl()

    @Test
    fun `同主机返回已播种的 Cookie`() {
        val jar = SessionCookieJar(expectedHost)
        jar.seed("session=abc; cf_auth=xyz")

        val loaded = jar.loadForRequest(serverUrl)

        assertEquals(2, loaded.size)
        assertEquals("abc", loaded.first { it.name == "session" }.value)
        assertEquals("xyz", loaded.first { it.name == "cf_auth" }.value)
    }

    @Test
    fun `跨主机不返回任何 Cookie`() {
        val jar = SessionCookieJar(expectedHost)
        jar.seed("session=abc; cf_auth=xyz")

        assertTrue("跨主机读取必须为空", jar.loadForRequest(otherUrl).isEmpty())
    }

    @Test
    fun `跨主机下发的 Cookie 不写入仓库`() {
        val jar = SessionCookieJar(expectedHost)
        jar.saveFromResponse(otherUrl, listOf(Cookie.parse(otherUrl, "leak=1")!!))

        assertTrue("第三方主机写入的 Cookie 必须被丢弃", jar.loadForRequest(serverUrl).isEmpty())
    }

    @Test
    fun `同主机下发的 Cookie 会被保存`() {
        val jar = SessionCookieJar(expectedHost)
        jar.saveFromResponse(serverUrl, listOf(Cookie.parse(serverUrl, "fresh=2")!!))

        val loaded = jar.loadForRequest(serverUrl)
        assertEquals(1, loaded.size)
        assertEquals("fresh", loaded[0].name)
        assertEquals("2", loaded[0].value)
    }

    @Test
    fun `粘贴串里的属性段不会被当成 Cookie`() {
        val jar = SessionCookieJar(expectedHost)
        jar.seed("session=abc; Path=/; Secure; HttpOnly")

        val loaded = jar.loadForRequest(serverUrl)

        assertEquals("只应保留真正的 Cookie", 1, loaded.size)
        assertEquals("session", loaded[0].name)
    }

    @Test
    fun `清空后不再返回 Cookie`() {
        val jar = SessionCookieJar(expectedHost)
        jar.seed("session=abc")
        jar.clear()

        assertTrue(jar.loadForRequest(serverUrl).isEmpty())
    }
}