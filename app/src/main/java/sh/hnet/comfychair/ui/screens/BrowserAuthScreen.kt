package sh.hnet.comfychair.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import sh.hnet.comfychair.PinnedTrustManager
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.CertPinPolicy
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.NetworkPolicy
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "BrowserAuth"

/**
 * Full-screen WebView for browser-based SSO authentication.
 *
 * Loads [serverUrl] in a WebView so the user can complete any required
 * sign-in flow (Authentik, Authelia, Cloudflare Access, OAuth2-proxy, etc.).
 * When the user taps "Done", the session cookies for [serverUrl] are captured
 * and returned via [onAuthComplete].
 *
 * TLS 错误按主机分档（与 [NetworkPolicy] 保持同一口径，见任务书 P0-3）：
 * - 用户显式信任过的公网自签服务器（连接层 A5 指纹确认流程写入 ServerStorage 的 pin）：
 *   仅当 SSL 错误所在 host 就是本服务器、且呈现的证书 SHA-256 与保存的 pin 一致时放行
 *   （[CertPinPolicy]），否则照下面的规则处理。没有这一档，这类服务器的浏览器登录会被硬阻断。
 * - 私网/本机主机（10. 段、172.16-31. 段、192.168. 段、127. 段、本地域名 mDNS 短名等）：
 *   沿用既有「信任自签证书」策略，[SslErrorHandler.proceed] 放行，老用户无感；
 * - 其余公网主机：一律 [SslErrorHandler.cancel] 并提示用户。放行等价于把 SSO Cookie
 *   拱手交给任意中间人——登录流程拿到的正是会话凭据，比普通接口更不能让步。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAuthScreen(
    serverUrl: String,
    onAuthComplete: (cookies: String) -> Unit,
    onCancel: () -> Unit,
    /**
     * 该服务器已保存的证书 SHA-256 指纹（用户在指纹确认流程里显式信任过才有值）。
     * null 表示没有信任记录，公网 SSL 错误一律拒绝。
     */
    trustedCertPin: String? = null,
    /** WebView 创建回调：宿主 Activity 保存引用，onDestroy 时兜底释放 */
    onWebViewCreated: (WebView) -> Unit = {}
) {
    // 只用 Activity 上下文弹 Toast（WebView 自身持有的也是同一个 context）
    val context = LocalContext.current
    // 本次要登录的服务器主机名：pin 只对这一台生效
    val serverHostName = runCatching { Uri.parse(serverUrl).host }.getOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_browser_auth)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_description_close)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val cookies = CookieManager.getInstance()
                                .getCookie(serverUrl)
                                .orEmpty()
                            onAuthComplete(cookies)
                        }
                    ) {
                        Text(stringResource(R.string.button_browser_auth_done))
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = { viewContext ->
                // 同一个主机只提示一次：一次页面加载可能触发多条子资源 SSL 错误，
                // 重复 Toast 会把提示刷成噪音
                val notifiedHosts = mutableSetOf<String>()

                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    with(CookieManager.getInstance()) {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false  // Let WebView handle all navigation

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError
                        ) {
                            val host = resolveSslHost(view, error)

                            // 1) 用户显式信任过的公网自签服务器（A5 指纹确认流程）：
                            //    必须是「同一主机」且「证书指纹与保存值一致」才放行。
                            //    没有这一档，这类服务器的浏览器 SSO 会被硬阻断（R2-L2）。
                            if (trustedCertPin != null &&
                                CertPinPolicy.isSameHost(host, serverHostName) &&
                                CertPinPolicy.matches(presentedFingerprint(error), trustedCertPin)
                            ) {
                                DebugLogger.i(TAG, "证书指纹与已信任记录一致，放行：host=$host")
                                handler.proceed()
                                return
                            }

                            // 2) 私网：自签/未知 CA 沿用既有信任策略（与 OkHttp 侧一致）
                            if (host.isNotEmpty() && NetworkPolicy.isPrivateHost(host)) {
                                handler.proceed()
                                return
                            }

                            // 3) 公网（或主机解析不出来，fail-closed）：拒绝加载并明确提示
                            handler.cancel()
                            val label = host.ifEmpty { serverUrl }
                            DebugLogger.w(
                                TAG,
                                "公网主机证书不受信，已拒绝加载：host=$host " +
                                    "primaryError=${error.primaryError}"
                            )
                            if (notifiedHosts.add(label)) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.error_browser_auth_insecure_host,
                                        label
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    loadUrl(serverUrl)
                    onWebViewCreated(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // 组合销毁（返回/取消/退出）时必须释放 WebView：否则后台媒体、定时器
            // 与 Activity 上下文一直挂着，既泄漏内存也让已登出的会话继续存活
            onRelease = { webView -> WebViewReleaser.release(webView) }
        )
    }
}

/**
 * 取出 SSL 错误里呈现的证书 SHA-256 指纹（小写十六进制，无分隔符）。
 * 取不到（证书对象缺失 / 无 X509 视图）时返回 null，调用方按「指纹不匹配」处理。
 */
private fun presentedFingerprint(error: SslError): String? =
    runCatching {
        error.certificate.x509Certificate?.let { PinnedTrustManager.fingerprintOf(it) }
    }.getOrNull()

/**
 * 解析 SSL 错误对应的主机名。
 *
 * 优先用 [SslError.getUrl]（出错的那个资源），拿不到再退回 WebView 当前地址。
 * 两者都解析不出主机名时返回空串，调用方按「非私网」处理（fail-closed）。
 */
private fun resolveSslHost(webView: WebView, error: SslError): String {
    val candidates = listOfNotNull(
        runCatching { error.url }.getOrNull(),
        runCatching { webView.url }.getOrNull()
    )
    for (candidate in candidates) {
        val host = runCatching { Uri.parse(candidate).host }.getOrNull()
        if (!host.isNullOrBlank()) return host
    }
    return ""
}

/**
 * WebView 释放器（P0-3）。
 *
 * 全 App 原先没有任何一处调用 `WebView.destroy()`；`AndroidView.onRelease` 与
 * Activity 的 `onDestroy` 都调这里，因此必须幂等——同一个实例只销毁一次
 * （重复 destroy 会抛异常/告警）。销毁前先把它从父容器摘下，
 * 避免「窗口上直接 destroy」留下的渲染进程泄漏。
 */
internal object WebViewReleaser {

    /** 已释放的实例（弱引用，不阻止 WebView 被回收） */
    private val released: MutableSet<WebView> =
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    fun release(webView: WebView?) {
        if (webView == null) return
        if (!released.add(webView)) return
        runCatching {
            webView.stopLoading()
            // 换成一个空客户端：断掉对匿名内部类（以及其捕获的 Activity 上下文）的引用。
            // webViewClient 属性是非空的，不能置 null。
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            DebugLogger.i(TAG, "WebView 已释放")
        }.onFailure {
            DebugLogger.w(TAG, "WebView 释放失败：${it.javaClass.simpleName} ${it.message}")
        }
    }
}
