package sh.hnet.comfychair.util

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 明文（http / ws）出口闸门（P2-13）。
 *
 * `res/xml/network_security_config.xml` 原先 `cleartextTrafficPermitted="true"` 全域放行，
 * 任何主机都可能被降级成明文传输。平台配置无法收窄到私网网段（`<domain>` 不支持 CIDR），
 * 详见 [NetworkPolicy.isCleartextAllowed] 的说明；因此边界放在这里，接在
 * [sh.hnet.comfychair.ComfyUIClient] 的 `secured()` 上，对全部 API / WebSocket / 传输 client 生效。
 *
 * 行为：
 * - https / wss：一律放行（TLS 由 SelfSignedCertHelper 分场景校验）；
 * - http / ws：仅允许私网/本机主机（10/8、172.16/12、192.168/16、127/8、169.254/16、
 *   IPv6 链路本地与 ULA、localhost、*.local、*.lan、无点短名）；
 * - 公网主机的明文连接直接抛 IOException，不发出请求。
 *
 * 纯策略判据在 [NetworkPolicy] 里，本类只负责把它接到 OkHttp 链路上。
 *
 * **必须以网络拦截器注册**（`addNetworkInterceptor`），不能挂在 `addInterceptor` 的应用层：
 * OkHttp 的 30x 跳转由 `RetryAndFollowUpInterceptor` 内部重新发起，只重走
 * 「连接 + 网络拦截器」这一段，应用拦截器对初始请求只执行一次。挂在应用层的效果是
 * 「https 主机 302 到公网 http:// 地址」这跳不会被检查（见
 * `CleartextRedirectGateTest` 的行为测试）。
 *
 * ⚠️ 本闸门只覆盖经 [sh.hnet.comfychair.ComfyUIClient.secured] 构造的 OkHttp client。
 * 平台层（network_security_config.xml）仍是「允许明文」，因此 WebView 登录页与
 * 将来新增的 HttpURLConnection / 第三方网络库**不在**本闸门内 —— 这是已知残余面，
 * 新增网络链路时必须复用 secured() 或自行调用 [NetworkPolicy.isCleartextAllowed]。
 * 完整说明见仓库根目录 SECURITY.md 第 1 节。
 */
object CleartextGuardInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps && !NetworkPolicy.isCleartextAllowed(url.host)) {
            // 文案不带完整 URL（可能有查询串）；主机名足够定位
            throw IOException("拒绝明文连接公网主机 ${url.host}：明文只允许发往局域网/本机地址")
        }
        return chain.proceed(request)
    }
}