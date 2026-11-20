package sh.hnet.comfychair

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 只服务单一主机的 Cookie 仓库。
 *
 * 背景：以前 Cookie 认证是把整串 Cookie 直接塞进 `Cookie` 请求头，OkHttp 会原样带在
 * 每一次请求上 —— 包括被重定向到的**第三方主机**（SSO、CDN），凭据因此外泄。
 *
 * 现在凭据交给 OkHttp 按作用域管理：
 * - [loadForRequest] 只对 [expectedHost] 返回 Cookie，其它主机一律空列表；
 * - [saveFromResponse] 只接受 [expectedHost] 下发的 Set-Cookie，跨主机写入直接丢弃。
 */
class SessionCookieJar(private val expectedHost: String) : CookieJar {

    private val cookies = CopyOnWriteArrayList<Cookie>()

    /** 上一次播种的用户 Cookie 串；相同内容不重复解析，也避免覆盖服务端刷新过的会话 Cookie */
    private val lastSeeded = AtomicReference<String?>(null)

    /**
     * 把用户粘贴的 Cookie 串（如 `"session=abc; cf_auth=xyz"`）逐条解析后播种进仓库。
     */
    fun seed(cookieHeader: String, scheme: String = "https") {
        val trimmed = cookieHeader.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        if (lastSeeded.getAndSet(trimmed) == trimmed) return

        val hostForUrl = if (expectedHost.contains(':') && !expectedHost.startsWith("[")) {
            "[$expectedHost]"
        } else {
            expectedHost
        }
        val baseUrl = "$scheme://$hostForUrl/".toHttpUrlOrNull() ?: return

        val parsed = trimmed.split(';')
            .mapNotNull { segment -> Cookie.parse(baseUrl, segment.trim()) }
            .filterNot { it.name.lowercase() in COOKIE_ATTRIBUTE_NAMES }

        cookies.clear()
        cookies.addAll(parsed)
    }

    fun clear() {
        cookies.clear()
        lastSeeded.set(null)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.equals(expectedHost, ignoreCase = true)) return

        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > now) {
                this.cookies.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.equals(expectedHost, ignoreCase = true)) return emptyList()

        val now = System.currentTimeMillis()
        return cookies.filter { it.expiresAt > now }
    }

    private companion object {
        /** 用户粘贴的内容里可能混入的属性段，不能当成 Cookie 名写入 */
        val COOKIE_ATTRIBUTE_NAMES = setOf(
            "path", "domain", "expires", "max-age", "secure", "httponly", "samesite"
        )
    }
}

/**
 * 认证凭据兜底拦截器：使用 Cookie 认证时，拒绝把凭据发往非预期主机。
 *
 * Cookie 的常规外发已由 [SessionCookieJar] 的作用域限制挡住；这里再兜一层，
 * 覆盖重定向后仍带有 `Cookie` / `Authorization` 头的异常路径。
 * 抛出 [IOException] 会进入 OkHttp 的 onFailure 回调（既有错误处理），不会崩溃。
 */
internal class HostScopedAuthGuard(
    private val expectedHost: String,
    private val credentialsProvider: () -> sh.hnet.comfychair.model.AuthCredentials
) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val targetHost = request.url.host
        val isCookieAuth = credentialsProvider() is sh.hnet.comfychair.model.AuthCredentials.Cookie
        val carriesCredential = request.header("Cookie") != null || request.header("Authorization") != null

        if (isCookieAuth && carriesCredential && !targetHost.equals(expectedHost, ignoreCase = true)) {
            throw IOException("已阻止向非预期主机 $targetHost 发送认证凭据")
        }
        return chain.proceed(request)
    }
}