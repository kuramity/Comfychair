package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.screens.BrowserAuthScreen
import sh.hnet.comfychair.ui.screens.WebViewReleaser
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ServerUrlUtils

private const val TAG = "BrowserAuthActivity"

/**
 * Activity that presents a WebView for browser-based SSO authentication.
 *
 * Expects [EXTRA_SERVER_URL] in the launching Intent.
 * On success, returns [Activity.RESULT_OK] with [RESULT_COOKIES] set to the captured
 * cookie string for the server URL. On cancellation (back press or close icon),
 * returns [Activity.RESULT_CANCELED].
 */
class BrowserAuthActivity : ComponentActivity() {

    /**
     * 当前 WebView 实例（P0-3）。
     *
     * `AndroidView.onRelease` 覆盖组合销毁的正常路径；配置变更/进程回收等场景下
     * onRelease 不保证执行，因此在 [onDestroy] 里兜底释放一次
     * （[WebViewReleaser] 幂等，重复调用安全）。
     */
    private var authWebView: WebView? = null

    companion object {
        private const val EXTRA_SERVER_URL = "server_url"
        const val RESULT_COOKIES = "cookies"

        /**
         * Build a launch intent for this activity.
         * @param context Calling context
         * @param serverUrl Full server URL to load (e.g. "https://myserver.example.com:8188")
         */
        fun createIntent(context: Context, serverUrl: String): Intent =
            Intent(context, BrowserAuthActivity::class.java)
                .putExtra(EXTRA_SERVER_URL, serverUrl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        if (serverUrl.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // R2-L2：公网自签服务器若已被用户在指纹确认流程里显式信任，
        // 浏览器 SSO 不能被硬阻断 —— 进界面之前取一次该服务器保存的证书指纹
        // （只读一次，避免放进 Composable 后每次重组都读 prefs）
        val trustedPin = resolveTrustedCertificatePin(serverUrl)

        setContent {
            ComfyChairTheme {
                BrowserAuthScreen(
                    serverUrl = serverUrl,
                    trustedCertPin = trustedPin,
                    onAuthComplete = { cookies ->
                        val resultIntent = Intent().putExtra(RESULT_COOKIES, cookies)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onWebViewCreated = { webView -> authWebView = webView }
                )
            }
        }
    }

    /**
     * 按 [serverUrl] 反查对应服务器并取出它已保存的证书指纹（P0-3 复核 R2-L2）。
     *
     * 调用方（登录页 / 会话过期重新登录）只传 URL，没有传 serverId，因此这里按
     * 「主机 + 端口」匹配服务器列表 —— 与 [ServerUrlUtils.buildServerUrl] 的构造方式
     * 一一对应（其格式固定为 `scheme://host:port`）。匹配不到或读取异常都返回 null，
     * 表示没有信任记录，公网 SSL 错误按拒绝处理（fail-closed）。
     *
     * 读的是本机 SharedPreferences 里几条服务器配置，量级极小，放在主线程可接受。
     */
    private fun resolveTrustedCertificatePin(serverUrl: String): String? = try {
        val uri = Uri.parse(serverUrl)
        val host = uri.host?.trim()?.lowercase()
        val port = uri.port
        if (host.isNullOrEmpty() || port <= 0) {
            null
        } else {
            val storage = ServerStorage(this)
            val matched = storage.getServers().firstOrNull { server ->
                ServerUrlUtils.splitProtocol(server.hostname).second.trim().lowercase() == host &&
                    server.port == port
            }
            matched?.let { storage.getCertificatePin(it.id) }
        }
    } catch (e: Exception) {
        DebugLogger.w(TAG, "读取已信任证书指纹失败：${e.javaClass.simpleName} ${e.message}")
        null
    }

    override fun onDestroy() {
        // 兜底释放：组合销毁路径已释放过则这里是一次空操作
        WebViewReleaser.release(authWebView)
        authWebView = null
        super.onDestroy()
    }
}
