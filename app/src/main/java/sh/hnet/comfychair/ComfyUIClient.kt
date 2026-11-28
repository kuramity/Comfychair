package sh.hnet.comfychair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import sh.hnet.comfychair.cache.MediaGateway
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.ModelIntroFetch
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.util.CleartextGuardInterceptor
import sh.hnet.comfychair.util.ComfyViewUrl
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ExifOrientation
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.ProgressTrackingRequestBody
import sh.hnet.comfychair.util.ProgressTrackingResponseBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ComfyUIClient - Handles communication with ComfyUI server
 *
 * This class is responsible for:
 * 1. Testing HTTP/HTTPS connection to the server
 * 2. Auto-detecting whether to use HTTP or HTTPS
 * 3. Opening WebSocket connection for real-time updates
 *
 * @param context Application context for string resources
 * @param hostname The server hostname or IP address (e.g., "192.168.1.100")
 * @param port The server port number (default: 8188)
 * @param credentials Authentication credentials for the server
 */
class ComfyUIClient(
    private val context: Context? = null,
    rawHostname: String,
    private val port: Int,
    credentials: AuthCredentials = AuthCredentials.None,
    /** 服务器 ID：证书指纹（TOFU）按它持久化；null 时退化为仅本实例内存保存 */
    private val serverId: String? = null,
    /** 用户为该服务器显式选择的协议（"http" / "https"）；null 时回落到地址前缀解析 */
    preferredProtocol: String? = null
) : MediaGateway {
    companion object {
        private const val TAG = "API"
        private const val STALL_TIMEOUT_MS = 30_000L          // 30 seconds without progress = stalled
        private const val STALL_CHECK_INTERVAL_MS = 5_000L    // Check every 5 seconds

        /** P2 观测日志：写入应用内缓冲，并在调试日志开启时同步到 logcat。 */
        private fun logWsInfo(message: String) {
            DebugLogger.i(TAG, message)
            if (DebugLogger.isEnabled()) android.util.Log.i("ComfyChair", message)
        }

        private fun logWsWarn(message: String) {
            DebugLogger.w(TAG, message)
            if (DebugLogger.isEnabled()) android.util.Log.w("ComfyChair", message)
        }

        /**
         * 图片解码的最大边长。
         *
         * 放大类工作流可能产出上万像素的图片（解码后可达数百 MB），
         * 既容易 OOM，也会触发 "Canvas: trying to draw too large bitmap" 崩溃。
         * 这里统一按需采样解码，6144 边长足够覆盖手机屏幕显示需求（长边超过它的图按比例降采样）。
         *
         * 为什么不是 4096：6144 与 [CACHEABLE_BITMAP_BYTES] 的「大图不进内存缓存」预算配套 ——
         * 6144² × 4B ≈ 151MB，远超 32MB 缓存阈值，所以这个尺寸的图解码后直接交给界面、
         * 不进 LRU。把常量改小会让这条推理失效，缓存行为也跟着变。
         */
        private const val MAX_DECODE_DIMENSION = 6144

        /**
         * 超过这个大小的位图不放进内存缓存（32 MB）。
         *
         * 缓存预算按 maxMemory/3 算（512 MB 堆约 170 MB），而一张 6144 边的图就要约 151 MB ——
         * 放进去几乎吃满整个预算，会把整屏的缩略图与其他原图全挤出去，
         * 于是"看过一张大图之后列表也变慢"。大图不缓存，重看时重解一次即可
         * （列表走的是另一套小的缩略图缓存，不受影响）。
         */
        const val CACHEABLE_BITMAP_BYTES = 32 * 1024 * 1024

        /**
         * 单次下载的字节上限（48 MB）。
         *
         * 放大类工作流的输出可能是 200 MB 级的 PNG：一次性读进内存的峰值接近文件大小的
         * 两倍，堆撑不住时只能整个失败，而**失败之后没有任何标记** —— 于是每次滚回那一格
         * 都会重新下载一遍，把整条链路占满（这正是"生成一张大图后图库一直转圈"的成因）。
         *
         * 48 MB 是"不误伤正常高清图"与"拦住异常大文件"之间的折中：4K PNG 通常 10~30 MB，
         * 8192 级的放大图才会稳定超过它。列表用的缩略图（服务端 preview 压缩）不受这个限制。
         */
        private const val MAX_DOWNLOAD_BYTES = 48L * 1024 * 1024

        /**
         * 视频的单次下载上限（256 MB）。
         *
         * 比图片宽得多 —— 短视频本来就有几十 MB，卡到 48 MB 会把正常视频也挡掉；
         * 但也不能不要上限，否则一个异常大的文件同样会把链路占满（与图片那条同一个理由）。
         */
        private const val MAX_VIDEO_DOWNLOAD_BYTES = 256L * 1024 * 1024

        /** 流式读取的块大小 */
        private const val DOWNLOAD_CHUNK_BYTES = 64L * 1024

        /**
         * 列表缩略图请求用的服务端压缩参数（`/view?preview=jpeg;75`）。
         *
         * ComfyUI 的 `/view` 原生支持这个参数（server.py 里解析 `preview=格式;质量`），
         * 它会把原图压成小图再返回 —— 实测一张 204 MB 的 PNG 压到 7.9 MB。
         * 代价是服务端要现场压一次（那张耗时约 9 秒），所以只用在列表这类"小图就够"的场景。
         */
        const val LIST_THUMBNAIL_PREVIEW = "jpeg;75"

        /**
         * 查看器对大图降级时用的压缩参数（质量高一档：同分辨率、看得出细节，但仍是几 MB）。
         */
        const val FALLBACK_PREVIEW = "jpeg;90"

        /** 列表缩略图解码后的最大边长（比原图小得多，专门给格子用） */
        const val THUMBNAIL_DECODE_DIMENSION = 512

        /**
         * 计算「解码后长边不超过 [maxDimension]」所需的采样倍率（2 的幂次）。
         *
         * 抽成纯函数是为了能单测 —— 这里写错过一次：条件用了
         * `longest / (sampleSize * 2) >= maxDimension`，退出时 `longest / sampleSize` 仍可达
         * 2 倍上限，于是"4096 的上限"实际会解出 8191×8191（约 268 MB）的位图。
         * 这类"越界也静默"的逻辑必须有测试钉住。
         */
        internal fun computeSampleSize(longest: Int, maxDimension: Int): Int {
            if (maxDimension <= 0 || longest <= maxDimension) return 1
            var sampleSize = 1
            while (longest / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            return sampleSize
        }

        /**
         * **唯一**的「源已不存在」判定：只有 HTTP 404。
         *
         * 为什么不能把"加载失败"都算进来：超时、断网（-1 / IOException）、5xx、403
         * 都可能是暂时的 —— 地铁里打开图库不能满屏「已删除」。只有服务端明确回答
         * "这个文件没有"，才允许进入第三态并持久化标记。
         *
         * 抽成纯函数便于 JVM 单测覆盖（404 → true；200/403/500/网络异常 → false）。
         */
        internal fun isSourceMissingStatus(code: Int): Boolean = code == 404

        /**
         * 把 /view 的 HTTP 状态码映射成失败类型（v4.6.0）。
         *
         * 404 单独分类（[ConnectionFailure.FILE_MISSING]）：电脑端清理 output 后，
         * /history 索引里残留的条目会一直打到这个码 —— 重试永远不会成功，
         * 图库对它们应该显示"文件不存在"并停止请求。抽成纯函数便于单测覆盖。
         */
        internal fun connectionFailureForHttpCode(code: Int): ConnectionFailure = when {
            // 判定口径唯一来源：[isSourceMissingStatus]，不允许在这里另写一份 `== 404`
            isSourceMissingStatus(code) -> ConnectionFailure.FILE_MISSING
            code == 401 || code == 403 -> ConnectionFailure.AUTHENTICATION
            else -> ConnectionFailure.NETWORK
        }

        /**
         * 统一主机名：去掉用户可能手填的协议前缀与末尾斜杠。
         * 协议由 [explicitProtocol] 单独解析，这里只留下纯主机名/IP。
         */
        internal fun normalizeHostname(raw: String): String {
            var value = raw.trim()
            if (value.startsWith("http://", ignoreCase = true)) {
                value = value.substring(7)
            } else if (value.startsWith("https://", ignoreCase = true)) {
                value = value.substring(8)
            }
            return value.trimEnd('/')
        }
    }

    /**
     * 用户显式选择的协议；null 表示未指定，默认走 HTTPS。
     *
     * 取值顺序：服务器配置里持久化的协议 > 地址前缀（`http://` / `https://`）> null。
     * 明文 HTTP 只能由用户显式选择，不再由 HTTPS 失败后自动降级得到。
     */
    private val explicitProtocol: String? =
        preferredProtocol?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: when {
                rawHostname.trim().startsWith("http://", ignoreCase = true) -> "http"
                rawHostname.trim().startsWith("https://", ignoreCase = true) -> "https"
                else -> null
            }

    /** 去掉协议前缀与末尾斜杠后的纯主机名/IP；所有 URL 与 Cookie 作用域都用它 */
    private val hostname: String = normalizeHostname(rawHostname)

    // Auth interceptor for adding auth headers and detecting session expiry
    private val authInterceptor = AuthInterceptor(credentials, hostname)

    /** 证书指纹持久化（按 serverId）；没有 Context 时退化为内存保存 */
    private val pinStorage: ServerStorage? = context?.applicationContext?.let { ServerStorage(it) }
    private val memoryPins = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun readPinnedFingerprint(): String? =
        if (serverId != null && pinStorage != null) pinStorage.getCertificatePin(serverId)
        else memoryPins[hostname]

    private fun savePinnedFingerprint(fingerprint: String) {
        if (serverId != null && pinStorage != null) {
            pinStorage.setCertificatePin(serverId, fingerprint)
        } else {
            memoryPins[hostname] = fingerprint
        }
    }

    /** 公网自签证书待用户确认的指纹：只留在内存，用户显式确认后才写盘 */
    @Volatile
    private var pendingUntrustedFingerprint: String? = null

    /** 待确认的证书指纹（null 表示没有待确认的公网自签证书） */
    fun getPendingCertificateFingerprint(): String? = pendingUntrustedFingerprint

    /**
     * 用户显式确认后把待确认指纹写入信任存储。
     * @return true 表示已保存，false 表示没有待确认指纹或保存失败
     */
    fun trustPendingCertificate(): Boolean {
        val fingerprint = pendingUntrustedFingerprint ?: return false
        return try {
            savePinnedFingerprint(fingerprint)
            pendingUntrustedFingerprint = null
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "保存用户确认的证书指纹失败：${e.message}")
            false
        }
    }

    /** 公网主机的证书不被系统信任且无指纹：记录下来等用户确认，绝不自动写盘 */
    private fun onUntrustedCertificate(fingerprint: String) {
        pendingUntrustedFingerprint = fingerprint
        DebugLogger.w(TAG, "公网服务器证书不被系统信任，等待用户确认后重试")
    }

    /**
     * 统一的客户端安全配置：Cookie 作用域仓库 + 凭据跨主机兜底 + 分场景 TLS 信任 + 明文出口闸门。
     *
     * ⚠️ 新增网络请求必须经本方法构造 client（或自行挂 [CleartextGuardInterceptor]）：
     * 平台 network_security_config 仍允许明文，明文「只发私网」的边界只在这里，
     * 绕过它的链路（WebView、未来新增的网络库）不受保护。见仓库根目录 SECURITY.md 第 1 节。
     */
    private fun secured(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        authInterceptor.cookieJar?.let { builder.cookieJar(it) }
        builder.addNetworkInterceptor(HostScopedAuthGuard(hostname, authInterceptor::getCredentials))
        // P2-13：平台 network_security_config 无法表达私网网段（不支持 CIDR），
        // 明文「只发私网」的边界在网络层执行：公网主机走 http/ws 会在这里被拒。
        //
        // 必须用 **网络拦截器**（addNetworkInterceptor）而不是应用拦截器（addInterceptor）：
        // OkHttp 的 30x 跳转在 RetryAndFollowUpInterceptor 内部重新发起，只重走
        // 「连接 + 网络拦截器」这一段，应用拦截器对初始请求只执行一次。挂在应用层的话，
        // https 主机 302 到公网 http:// 地址就会绕过闸门。
        builder.addNetworkInterceptor(CleartextGuardInterceptor)
        return SelfSignedCertHelper.configureToAcceptSelfSigned(
            builder,
            hostname = hostname,
            pinnedFingerprint = ::readPinnedFingerprint,
            onFirstSeen = ::savePinnedFingerprint,
            onUntrusted = ::onUntrustedCertificate
        )
    }

    // OkHttpClient for HTTP requests - short timeouts are fine
    private val httpClient = secured(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)  // Max time to establish connection
            .readTimeout(10, TimeUnit.SECONDS)     // Max time to read response
            .writeTimeout(10, TimeUnit.SECONDS)    // Max time to write request
    ).build()

    // Separate OkHttpClient for WebSocket connections
    // WebSockets need longer timeouts since data may not arrive for extended periods
    // pingInterval keeps the connection alive during long operations
    //
    // v4.18.x 后台省流量：pingInterval 改为**按需构建**（base 不带 ping + newBuilder 注入），
    // 间隔由 [updateWebSocketPingInterval] 维护 —— 后台降级时与 ConnectionManager 的应用层
    // 心跳同步放宽（前台 30s / 不限量后台 120s / 蜂窝后台 240s），两条心跳合计的联网频率
    // 从每 30 秒一次降到每 2-4 分钟一次。newBuilder 共享连接池与线程池，openWebSocket
    // 频率低（建连时才调用），多建一个配置对象可忽略不计。
    private val webSocketClientBase = secured(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)      // No read timeout for WebSocket
            .writeTimeout(10, TimeUnit.SECONDS)
    ).build()

    /** 当前生效的协议层 ping 间隔（秒；@Volatile：写侧在协调器协程，读侧在建连线程） */
    @Volatile
    private var webSocketPingIntervalSec = 30L

    private fun webSocketClient(): OkHttpClient {
        val intervalSec = webSocketPingIntervalSec
        return webSocketClientBase.newBuilder()
            .pingInterval(intervalSec, TimeUnit.SECONDS)
            .build()
            .also {
                logWsInfo("[WS] ping interval = ${intervalSec}s (effective protocol ping for new socket)")
            }
    }

    /**
     * 更新协议层 ping 间隔（秒）。只影响**之后新建**的 WebSocket（在跑的连接保持旧节奏，
     * 降级断连 / 回前台重连时自然切到新值 —— 降级策略本来就伴随断连/保连切换）。
     */
    fun updateWebSocketPingInterval(intervalSec: Long) {
        val previous = webSocketPingIntervalSec
        webSocketPingIntervalSec = intervalSec
        logWsInfo(
            "[WS] ping interval requested=${intervalSec}s (previous=${previous}s; " +
                "existing socket unchanged, next socket effective)"
        )
    }

    // Client for large transfers (uploads/downloads) - timeouts disabled, stall detection handles them
    // This allows slow but progressing transfers to complete while detecting truly stalled connections
    private val transferClient = secured(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)      // Disabled - stall detection handles this
            .writeTimeout(0, TimeUnit.SECONDS)     // Disabled - stall detection handles this
    ).build()

    /**
     * **文件下载**专用 client（v4.4.2 修的真机前 bug）。
     *
     * [transferClient] 的 `readTimeout(0)` 是为了"慢速但持续"的传输不被误杀，可是
     * **断网时它会让请求无限挂起**：实测飞行模式下点图库 3D 条目，界面卡在
     * `Downloading model…` 超过 1 分钟、永不报错 —— 直接违反验收要求的
     * "加载失败必须弹错误信息，不能静默降级"。
     *
     * 这里给 15 秒**读空闲**上限（不是总时长）：只要数据还在流动（哪怕经隧道 9KB/s），
     * 两次读的间隔远小于 15 秒，不会误杀；真正断流才失败。上传仍走 [transferClient]。
     */
    private val downloadClient = secured(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
    ).build()

    /**
     * 拉取 `/object_info` 专用客户端（长读超时，懒加载后复用）。
     *
     * 该响应可达数 MB（本机实测 8.1MB），transferClient 的"不限时 + 停流检测"对它不适用，
     * 需要固定的长读超时兜底；但**不能每次请求都 newBuilder().build()**——每个 OkHttpClient
     * 都带一套独立的连接池与线程池，反复刷新节点定义会不断累积这些资源。
     */
    private val objectInfoClient: OkHttpClient by lazy {
        transferClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * **缩略图链路专用 client**（v4.6.0，修"图库加载 10 张后出不来"的主根因）。
     *
     * [transferClient] 的 `readTimeout(0)` 有一个盲区：停流看门狗在 [onResponse] 之后才启动，
     * 而**服务端 preview 压缩发生在响应头返回之前**（204MB 原图要压约 9 秒）——
     * 这个阶段 readTimeout=0 意味着请求可以无限挂起，图库格子永远转圈。
     * 列表滚动时十几个请求同时打进去（OkHttp 同主机默认 5 并发，其余排队），
     * 排队 + 压缩叠加后"约 10 张之后"成片挂死。
     *
     * 这里给 45 秒**读超时**：OkHttp 的 readTimeout 同时覆盖"等待响应头"与"body 两次读间隔"——
     * 等头超过 45 秒（压缩慢 + 排队的最坏情况）判失败；body 一旦开始流动，
     * 只要间隔不超过 45 秒就不会被误杀（经隧道的 9KB/s 慢速持续传输是安全的）。
     *
     * 与 [objectInfoClient] 同款懒加载：只 build 一次，复用连接池。
     */
    private val thumbnailClient: OkHttpClient by lazy {
        transferClient.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    // Store which protocol worked (http or https)
    private var workingProtocol: String? = null

    /**
     * Set the working protocol externally.
     * Used by ConnectionManager when creating a shared client instance.
     *
     * @param protocol Either "http" or "https"
     */
    fun setWorkingProtocol(protocol: String) {
        workingProtocol = protocol
    }

    /**
     * Get the current working protocol.
     * @return "http", "https", or null if not yet determined
     */
    fun getWorkingProtocol(): String? = workingProtocol

    /**
     * Update the credentials used for authentication.
     * Thread-safe - can be called from any thread.
     *
     * @param credentials The new credentials to use
     */
    fun setCredentials(credentials: AuthCredentials) {
        authInterceptor.setCredentials(credentials)
    }

    /**
     * Get the current credentials.
     * @return The current authentication credentials
     */
    fun getCredentials(): AuthCredentials = authInterceptor.getCredentials()

    /**
     * Register a callback to be invoked when a Cookie-authenticated session expires.
     * Called at most once per session; re-armed by [resetSessionExpiredFlag].
     */
    fun setOnSessionExpired(callback: () -> Unit) {
        authInterceptor.onSessionExpired = callback
    }

    /**
     * Re-arm session-expiry detection after a successful re-authentication.
     */
    fun resetSessionExpiredFlag() {
        authInterceptor.resetSessionExpiredFlag()
    }

    // Store the active WebSocket connection
    private var webSocket: WebSocket? = null

    // Client ID for tracking WebSocket messages
    // This must match the client_id used when submitting prompts
    // Can be overridden by ConnectionManager to ensure WebSocket and HTTP use the same ID
    private var clientId = "comfychair_android_${UUID.randomUUID()}"

    /**
     * Set the client ID externally.
     * Used by ConnectionManager to ensure WebSocket connection and prompt submission
     * use the same client ID.
     *
     * @param id The client ID to use
     */
    fun setClientId(id: String) {
        clientId = id
    }

    /**
     * Test connection to the ComfyUI server
     *
     * 协议选择：用户地址显式以 `http://` 开头才走明文 HTTP，其余一律先试 HTTPS。
     * **HTTPS 失败不再静默降级到 HTTP** —— 明文必须由用户显式选择。
     *
     * @param callback Called with the result:
     *                 - success: true if connected, false if failed
     *                 - errorMessage: error message if failed, null if success
     *                 - certIssue: the type of certificate issue detected (NONE, SELF_SIGNED, UNKNOWN_CA)
     *                 - failureType: the type of failure (NONE, AUTHENTICATION, NETWORK, INVALID_SERVER)
     */
    fun testConnection(callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue, failureType: ConnectionFailure) -> Unit) {
        DebugLogger.i(TAG, "Testing connection to ${Obfuscator.hostname(hostname)}")
        // Reset the certificate issue detection
        SelfSignedCertHelper.reset()
        // 显式 http:// 地址保持原有明文行为；否则只走 HTTPS，不做任何降级
        tryConnection(explicitProtocol ?: "https", callback)
    }

    /**
     * Try connecting with a specific protocol (http or https)
     *
     * @param protocol Either "http" or "https"
     * @param callback Called with result
     */
    private fun tryConnection(
        protocol: String,
        callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue, failureType: ConnectionFailure) -> Unit
    ) {
        // Build the URL to test
        // ComfyUI provides /system_stats endpoint that returns server info
        val url = "$protocol://$hostname:$port/system_stats"

        // Create an HTTP GET request
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // Execute the request asynchronously (in background thread)
        // We use enqueue instead of execute to avoid blocking the UI thread
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (protocol == "https") {
                    // 不再降级到 http：明文必须由用户显式选择
                    DebugLogger.w(TAG, "HTTPS 连接失败，不再降级到 http：${e.message}")
                    // 带上证书问题类型：指纹不一致时界面据此提供「重新信任」入口
                    callback(
                        false,
                        context?.getString(R.string.error_https_connection_failed)
                            ?: "HTTPS connection failed: if the server only supports HTTP, change the address to start with http:// and retry",
                        SelfSignedCertHelper.certificateIssue,
                        ConnectionFailure.NETWORK
                    )
                } else {
                    callback(false, "Connection failed: ${e.message}", CertificateIssue.NONE, ConnectionFailure.NETWORK)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        // Validate this is actually a ComfyUI server by checking response content
                        try {
                            val body = response.body?.string() ?: "{}"
                            val json = JSONObject(body)
                            // ComfyUI's /system_stats returns a "system" object with "os" field
                            val system = json.optJSONObject("system")
                            if (system != null && system.has("os")) {
                                // Valid ComfyUI server
                                workingProtocol = protocol
                                val certIssue = SelfSignedCertHelper.certificateIssue
                                DebugLogger.i(TAG, "Connection successful (protocol: $protocol)")
                                callback(true, null, certIssue, ConnectionFailure.NONE)
                            } else {
                                // Response doesn't match ComfyUI format
                                DebugLogger.w(TAG, "Invalid response: not a ComfyUI server")
                                callback(false, context?.getString(R.string.error_not_comfyui_server)
                                    ?: "Not a ComfyUI server", CertificateIssue.NONE, ConnectionFailure.INVALID_SERVER)
                            }
                        } catch (e: Exception) {
                            // Response is not valid JSON - not a ComfyUI server
                            DebugLogger.w(TAG, "Invalid response: ${e.message}")
                            callback(false, context?.getString(R.string.error_invalid_server_response)
                                ?: "Invalid server response", CertificateIssue.NONE, ConnectionFailure.INVALID_SERVER)
                        }
                    } else {
                        // Got response but status code indicates error
                        val code = response.code

                        // Check for authentication errors - these should not fallback to HTTP
                        // since authentication is protocol-independent
                        if (code == 401 || code == 403) {
                            workingProtocol = protocol  // Protocol works, auth doesn't
                            DebugLogger.w(TAG, "Authentication failed (protocol: $protocol, code: $code)")
                            callback(
                                false,
                                context?.getString(R.string.error_auth_unauthorized)
                                    ?: "Authentication failed. Please check your credentials.",
                                SelfSignedCertHelper.certificateIssue,
                                ConnectionFailure.AUTHENTICATION
                            )
                        } else if (protocol == "https") {
                            // 服务端明确返回错误：同样不降级到 http
                            DebugLogger.w(TAG, "HTTPS 连接被服务端拒绝（HTTP $code），不再降级到 http")
                            callback(
                                false,
                                (context?.getString(R.string.error_https_connection_failed)
                                    ?: "HTTPS connection failed: if the server only supports HTTP, change the address to start with http:// and retry") + " (HTTP $code)",
                                SelfSignedCertHelper.certificateIssue,
                                ConnectionFailure.NETWORK
                            )
                        } else {
                            // Both protocols failed
                            DebugLogger.w(TAG, "Connection failed: server error $code")
                            callback(false, "Server returned error: $code", CertificateIssue.NONE, ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Open WebSocket connection to ComfyUI server
     * WebSocket allows real-time bi-directional communication
     * ComfyUI uses it to send progress updates and receive commands
     *
     * IMPORTANT: The client_id query parameter is required to receive
     * progress and preview events for this client's jobs
     *
     * @param listener WebSocket event listener
     * @return true if connection attempt started, false if no working protocol found
     */
    fun openWebSocket(listener: WebSocketListener): Boolean {
        return openWebSocket(clientId, listener)
    }

    /**
     * Open WebSocket connection with a specific client ID.
     * Used by ConnectionManager to provide a shared client ID across components.
     *
     * @param clientIdOverride The client ID to use for this WebSocket connection
     * @param listener WebSocket event listener
     * @return true if connection attempt started, false if no working protocol found
     */
    fun openWebSocket(clientIdOverride: String, listener: WebSocketListener): Boolean {
        // Make sure we have a working protocol from testConnection
        val protocol = workingProtocol ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: no working protocol")
            return false
        }

        // Build WebSocket URL with clientId parameter
        // This is crucial - ComfyUI only sends progress/preview events to the matching client_id
        // ws:// for http, wss:// for https
        val wsProtocol = if (protocol == "https") "wss" else "ws"
        val wsUrl = "$wsProtocol://$hostname:$port/ws?clientId=$clientIdOverride"

        DebugLogger.i(TAG, "Opening WebSocket connection")
        logWsInfo("[WS] open protocolPingRequested=${webSocketPingIntervalSec}s")

        // Create WebSocket request
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        // Open WebSocket connection using dedicated WebSocket client with longer timeouts
        webSocket = webSocketClient().newWebSocket(request, listener)

        return true
    }

    /**
     * Check if the WebSocket connection is currently active.
     * @return true if connected, false otherwise
     */
    fun isWebSocketConnected(): Boolean {
        return webSocket != null
    }

    /**
     * Close the WebSocket connection
     * Should be called when done with the connection to free resources
     *
     * @param code Close code (1000 = normal closure)
     * @param reason Optional reason for closing
     */
    fun closeWebSocket(code: Int = 1000, reason: String? = null) {
        DebugLogger.i(TAG, "Closing WebSocket (code: $code)")
        webSocket?.close(code, reason)
        webSocket = null
    }

    /**
     * Send a message through the WebSocket
     *
     * @param message The message to send (usually JSON)
     * @return true if sent successfully, false if no connection
     */
    fun sendWebSocketMessage(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    /**
     * Get the base URL for HTTP requests
     * Uses the protocol that was determined to work during testConnection
     *
     * @return The base URL (e.g., "https://192.168.1.100:8188")
     */
    fun getBaseUrl(): String? {
        return workingProtocol?.let { "$it://$hostname:$port" }
    }

    /**
     * 直接可用的 `/view` 地址（查询参数已编码，P2-4）。
     *
     * 供不经过 OkHttp 请求链的下游使用：MediaMetadataRetriever 需要自己打开
     * 视频流来抽首帧（P2-5）。认证方式较特殊的服务器（Basic/Bearer/Cookie）
     * 上它可能失败，调用方按"失败即回退到整段下载"处理。
     */
    // B4.2-A：三方法实现 MediaGateway。Kotlin 规定 override 不得带参数默认值，
    // 默认值收口到接口侧（MediaGateway.DEFAULT_MAX_DECODE_DIMENSION 等）——
    // 经 MediaGateway 类型调用（MediaCache 等缓存层）走接口默认值，行为与迁移前一致；
    // 直调 ComfyUIClient 的调用点显式补参（补参值与旧默认值同源同值）。
    override fun viewUrl(
        filename: String,
        subfolder: String,
        type: String,
        preview: String?
    ): String? {
        val baseUrl = getBaseUrl() ?: return null
        return ComfyViewUrl.build(baseUrl, filename, subfolder, type, preview)
    }

    /**
     * 轻量连通性探测：单次 GET `/system_stats`，用**独立短超时**的客户端发出去。
     *
     * 供离线态下的周期探测使用（见 `ConnectionManager` 的离线看门狗）：
     * - **不做 https→http 回退**：一次探测只发一个请求，探测本身必须足够轻；
     * - **不改写 [workingProtocol]**：探测只回答"服务器现在通不通"，协议沿用上次成功的那个；
     * - 复用主客户端的连接池与认证拦截器，Cookie/Basic 凭据照常带上。
     *
     * @param timeoutMs 连接 / 读取 / 整体超时（毫秒），离线态传短超时
     * @param callback 结果回调，true 表示服务器可达
     */
    fun probeReachability(timeoutMs: Long, callback: (Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            // 连协议都还没定下来（从未连上过），无从探测
            callback(false)
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/system_stats")
            .get()
            .build()

        // newBuilder() 复用连接池与线程池，只覆盖超时；不新建 OkHttpClient 实例
        val probeCall = httpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)

        probeCall.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { resp -> callback(resp.isSuccessful) }
            }
        })
    }

    /**
     * Fetch all available node types from the ComfyUI server.
     * Uses the /object_info endpoint to get the complete node registry.
     *
     * @param callback Called with the result:
     *                 - nodeTypes: Set of available node class_type names, or null on error
     */
    fun fetchAllNodeTypes(callback: (nodeTypes: Set<String>?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/object_info"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // /object_info 全量可达数 MB（大型插件集）：给 120s 读超时 ——
        // 该超时是「两次读之间」的上限，持续下载不会触发，移动网络下的长停顿需要余量
        val nodeTypesClient = transferClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        nodeTypesClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // The keys of the object are the available node class_types
                            val nodeTypes = mutableSetOf<String>()
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                nodeTypes.add(keys.next())
                            }
                            callback(nodeTypes)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Unload all model weights and free memory on the server (ComfyUI /free endpoint).
     * Equivalent to the "Unload Models" action in the ComfyUI web UI.
     */
    fun freeMemory(callback: (success: Boolean, message: String?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false, "no_base_url")
            return
        }

        val body = "{\"unload_models\": true, \"free_memory\": true}".toRequestBody(
            "application/json".toMediaType()
        )
        val request = Request.Builder()
            .url("$baseUrl/free")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(it.isSuccessful, if (it.isSuccessful) null else "HTTP ${it.code}")
                }
            }
        })
    }

    // ============================================================
    // G4（任务书 11.4）：服务器端文件删除（comfyui-browser 插件能力）
    // ============================================================
    //
    // ComfyUI 原生没有删除接口（server.py 只有 GET /view、POST /upload/image、
    // POST /history、POST /queue、POST /interrupt、POST /free）。删除能力来自
    // comfyui-browser 插件的 `DELETE /browser/files`，请求体三字段：
    //   {"filename": "...", "folder_path": "<subfolder>", "folder_type": "outputs|input|temp"}
    // 服务端按 (folder_type 根, folder_path, filename) 定位文件，**只接受三个根**——
    // 客户端拼不出绝对路径，天然删不到 output 之外的目录（破坏性硬约束 1）。
    // 成功返回 201，文件不存在返回 404。两个方法都只回传原始 HTTP 码 + 网络失败标志，
    // 怎么解释交给纯函数 GalleryTrashLogic（classifyProbe / classifyDeleteAttempt），
    // 这里绝不提前把"失败"翻成"成功"。

    /**
     * 探测当前服务器是否安装了提供删除能力的 comfyui-browser 插件。
     *
     * 探测方式是 `GET /browser/files`（与删除同一路由）：
     * 有插件时该路由存在（列表接口返回 2xx；即便 GET 未注册也会得到与"路由不存在"
     * 不同的响应），无插件时直接 404。判定规则在 GalleryTrashLogic.classifyProbe，
     * 网络失败回 (null, true)，调用方按 UNKNOWN 处理（下次可重试，不判死）。
     *
     * @param callback (httpCode, networkFailed) —— httpCode 仅在拿到响应时非 null
     */
    fun probeBrowserDeleteSupport(callback: (httpCode: Int?, networkFailed: Boolean) -> Unit) {
        // L1：与 fetchAllHistory 同款"只投递一次"守卫 —— 能力探测是删除阶段二的入口，
        // 回调二次投递会让仓储层的延续二次 resume（Already resumed）炸在 OkHttp 线程。
        val delivery = SingleDeliveryGuard { DebugLogger.w(TAG, "probeBrowserDeleteSupport 忽略重复的回调投递") }
        fun deliver(httpCode: Int?, networkFailed: Boolean) =
            delivery.deliver { callback(httpCode, networkFailed) }

        val baseUrl = getBaseUrl() ?: run {
            deliver(null, true)
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/browser/files")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                deliver(null, true)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { deliver(it.code, false) }
            }
        })
    }

    /**
     * 删除服务器输出目录里的一个文件（唯一允许的服务器删除通道）。
     *
     * **参数约束**：filename / folderPath / folderType 必须先经
     * GalleryTrashLogic.isLocatorSafe 与 toBrowserFolderType 白名单过滤，
     * 调用方拿到的是过滤后的值；本方法只负责发请求，不做路径运算。
     *
     * @param callback (httpCode, networkFailed) —— 语义同 [probeBrowserDeleteSupport]
     */
    fun deleteBrowserFile(
        filename: String,
        folderPath: String,
        folderType: String,
        callback: (httpCode: Int?, networkFailed: Boolean) -> Unit
    ) {
        // L1：只投递一次（同 fetchAllHistory）。删除文件是破坏性操作，回调两次会让
        // 上层延续二次 resume 直接崩进程，也会让计数/状态机重复前进。
        val delivery = SingleDeliveryGuard { DebugLogger.w(TAG, "deleteBrowserFile 忽略重复的回调投递") }
        fun deliver(httpCode: Int?, networkFailed: Boolean) =
            delivery.deliver { callback(httpCode, networkFailed) }

        val baseUrl = getBaseUrl() ?: run {
            deliver(null, true)
            return
        }

        // JSONObject 负责转义：filename 里出现引号等字符不会破坏请求体结构
        val bodyJson = JSONObject().apply {
            put("filename", filename)
            put("folder_path", folderPath)
            put("folder_type", folderType)
        }
        val body = bodyJson.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/browser/files")
            .delete(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                deliver(null, true)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { deliver(it.code, false) }
            }
        })
    }

    /**
     * Fetch the full /object_info response from the ComfyUI server.
     * Returns the complete node type information including inputs/outputs.
     * Used for resolving slot types for edge coloring in workflow editor.
     *
     * @param callback Called with the result:
     *                 - objectInfo: The full JSON object, or null on error
     */
    fun fetchFullObjectInfo(callback: (objectInfo: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/object_info"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // /object_info 可达数 MB（本机实测 8.1MB）：httpClient 的 10s 读超时在移动网络 /
        // Cloudflare 隧道下会被掐断（用户 2026-09-18 报障的「所有模型下拉全空」就是这条：
        // 请求超时 → 节点定义拿不到 → 模型列表为空。同类修复当初只落在了 fetchAllNodeTypes 上）。
        // 用共享的 objectInfoClient（长读超时；懒加载复用，不再每次新建连接池与线程池）。
        objectInfoClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                android.util.Log.w("ComfyChairModels", "object_info 拉取失败（模型下拉会为空）: ${e.message}")
                DebugLogger.w(TAG, "Failed to fetch object_info: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            callback(json)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse object_info: ${e.message}")
                            callback(null)
                        }
                    } else {
                        DebugLogger.w(TAG, "object_info request failed: ${response.code}")
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Submit a workflow (prompt) to the ComfyUI server for execution
     * This queues the workflow for generation
     *
     * @param workflowJson The workflow JSON (from WorkflowManager)
     * @param callback Called with the result:
     *                 - success: true if submitted successfully
     *                 - promptId: The prompt ID assigned by the server (for tracking)
     *                 - errorMessage: error message if failed
     *                 - failureType: type of failure (NONE for success, AUTHENTICATION for 401/403, NETWORK for other errors)
     */
    fun submitPrompt(
        workflowJson: String,
        front: Boolean = false,
        callback: (success: Boolean, promptId: String?, errorMessage: String?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.i(TAG, "Submitting prompt${if (front) " (front of queue)" else ""}")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Submit failed: no connection")
            callback(false, null, context?.getString(R.string.error_no_connection) ?: "No connection to server", ConnectionFailure.NETWORK)
            return
        }

        val url = "$baseUrl/prompt"

        // Parse the workflow JSON to extract just the nodes section
        val workflowObject = JSONObject(workflowJson)
        val nodesObject = workflowObject.optJSONObject("nodes")

        // Create the prompt request
        // The client_id here must match the clientId used in the WebSocket connection
        val promptRequest = JSONObject().apply {
            put("prompt", nodesObject)
            put("client_id", clientId)
            if (front) {
                put("front", true)
            }
        }

        val requestBody = promptRequest.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.e(TAG, "Submit failed: ${e.message}")
                callback(false, null, "Failed to submit prompt: ${e.message}", ConnectionFailure.NETWORK)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(responseBody)
                            val promptId = json.optString("prompt_id")
                            DebugLogger.i(TAG, "Prompt submitted (promptId: ${Obfuscator.promptId(promptId)})")
                            callback(true, promptId, null, ConnectionFailure.NONE)
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Failed to parse submit response: ${e.message}")
                            callback(false, null, "Failed to parse response: ${e.message}", ConnectionFailure.NETWORK)
                        }
                    } else {
                        // Handle authentication errors with clean messages (avoid raw HTML)
                        if (response.code == 401 || response.code == 403) {
                            DebugLogger.e(TAG, "Authentication error ${response.code}")
                            callback(false, null, context?.getString(R.string.error_auth_unauthorized)
                                ?: "Authentication failed", ConnectionFailure.AUTHENTICATION)
                        } else {
                            // Try to extract error details from response body
                            val errorDetail = try {
                                val errorJson = JSONObject(responseBody)
                                errorJson.optString("error", "") +
                                    (errorJson.optJSONObject("node_errors")?.toString() ?: "")
                            } catch (e: Exception) {
                                responseBody.take(500) // Truncate long responses
                            }
                            DebugLogger.e(TAG, "Server error ${response.code}")
                            callback(false, null, "Server error ${response.code}: $errorDetail", ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Fetch all execution history
     * Used to get all generation history for the gallery
     *
     * @param maxItems 服务端返回的条数上限（ComfyUI 的 `/history?max_items=N`，实测
     *   server.py:1139 支持，另支持 offset）。v4.18.x 后台省流量：全量拉取每条都带完整
     *   prompt JSON，历史攒多后一次可达数十 MB —— 默认传 100 条（图库旧条目由本地索引
     *   兜底，合并语义只增不减，不会丢图）。传 null = 不带参数（旧行为，全量）。
     * @param offset 服务端跳过的 history 条数。**实测语义（F:\ComfyUI\execution.py
     *   :1423-1439）：从最旧一端开始计数**，且响应不带 total —— 客户端换算不出
     *   "从最新端往更早"的 offset。图库"加载更早"因此走**放宽 max_items 窗口**
     *   （GalleryRepository.olderWindowSize），本参数仅为网络层完整性保留；
     *   传 null = 不带（服务端默认取最新端）。
     * @param callback Called with the result:
     *                 - historyJson: The full history JSON object with all prompts, or null on error
     */
    fun fetchAllHistory(
        maxItems: Int? = null,
        offset: Int? = null,
        callback: (historyJson: JSONObject?) -> Unit
    ) {
        // P0：回调只投递一次。
        //
        // OkHttp 正常只会回调一次，但旧实现的 onResponse 里 `callback(historyJson)`
        // 包在 try 里、catch 里又兜了一次 `callback(null)` —— 一旦首次投递本身抛异常
        // （例如上层的延续二次 resume 抛 Already resumed），catch 会再投递一次，
        // 于是"异常 → 再投递 → 再抛"在 OkHttp 回调线程上炸穿进程。
        // 所以这里的 delivery 就是这道闸：第二次投递直接丢弃（只记日志）。
        val delivery = SingleDeliveryGuard { DebugLogger.w(TAG, "fetchAllHistory 忽略重复的回调投递") }
        fun deliver(historyJson: JSONObject?) = delivery.deliver { callback(historyJson) }

        val baseUrl = getBaseUrl() ?: run {
            deliver(null)
            return
        }

        val url = historyUrl(baseUrl, maxItems, offset)

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                deliver(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        // 解析失败只在本地收敛成 null，不再在 catch 里二次投递
                        val historyJson = try {
                            val body = response.body?.string() ?: "{}"
                            JSONObject(body)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "fetchAllHistory 解析失败: ${e.message}")
                            null
                        }
                        deliver(historyJson)
                    } else {
                        deliver(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch execution history for a prompt ID
     * Used to get information about generated images
     *
     * @param promptId The prompt ID to fetch history for
     * @param callback Called with the result:
     *                 - historyJson: The history JSON object, or null on error
     */
    fun fetchHistory(
        promptId: String,
        callback: (historyJson: JSONObject?) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            logWsWarn("[WS] fetchHistory failed promptId=${Obfuscator.promptId(promptId)} reason=no-protocol")
            callback(null)
            return
        }

        val url = "$baseUrl/history/$promptId"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                logWsWarn(
                    "[WS] fetchHistory failed promptId=${Obfuscator.promptId(promptId)} reason=${e.message}"
                )
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val historyJson = JSONObject(response.body?.string() ?: "{}")
                            callback(historyJson)
                        } catch (e: Exception) {
                            logWsWarn(
                                "[WS] fetchHistory failed promptId=${Obfuscator.promptId(promptId)} " +
                                    "reason=parse-error:${e.message}"
                            )
                            callback(null)
                        }
                    } else {
                        logWsWarn(
                            "[WS] fetchHistory failed promptId=${Obfuscator.promptId(promptId)} " +
                                "http=${response.code}"
                        )
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch the current queue status from the ComfyUI server
     * Returns both pending and running queue entries
     *
     * @param callback Called with the queue JSON containing "queue_running" and "queue_pending" arrays,
     *                 or null on error
     */
    fun fetchQueue(callback: (queueJson: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            logWsWarn("[WS] fetchQueue failed reason=no-protocol")
            callback(null)
            return
        }

        val url = "$baseUrl/queue"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                logWsWarn("[WS] fetchQueue failed reason=${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val queueJson = JSONObject(response.body?.string() ?: "{}")
                            callback(queueJson)
                        } catch (e: Exception) {
                            logWsWarn("[WS] fetchQueue failed reason=parse-error:${e.message}")
                            callback(null)
                        }
                    } else {
                        logWsWarn("[WS] fetchQueue failed http=${response.code}")
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Result of a download operation with stall detection.
     * Contains either the downloaded bytes or information about the failure.
     */
    private data class DownloadResult(
        val bytes: ByteArray?,
        val failureType: ConnectionFailure
    )

    /**
     * Downloads bytes from a response body with stall detection.
     * Creates a watchdog coroutine that monitors download progress and cancels
     * the call if no data is received within STALL_TIMEOUT_MS.
     *
     * @param call The OkHttp call (used for cancellation on stall)
     * @param body The response body to download
     * @param logTag Description for log messages (e.g., "Image", "Video")
     * @return DownloadResult containing bytes and failure type
     */
    private fun downloadWithStallDetection(
        call: okhttp3.Call,
        body: ResponseBody,
        logTag: String,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): DownloadResult {
        val trackedBody = ProgressTrackingResponseBody(body)
        var wasStalled = false
        var tooLarge = false

        // 服务器已经告诉我们有多大、而且超限时，连读都不必读 —— 直接中止
        val declaredLength = trackedBody.contentLength()
        if (maxBytes > 0 && declaredLength > maxBytes) {
            call.cancel()
            DebugLogger.w(
                TAG,
                "$logTag too large: ${declaredLength / (1024 * 1024)}MB > ${maxBytes / (1024 * 1024)}MB"
            )
            return DownloadResult(null, ConnectionFailure.FILE_TOO_LARGE)
        }

        val watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                if (System.currentTimeMillis() - trackedBody.lastProgressTime > STALL_TIMEOUT_MS) {
                    DebugLogger.w(TAG, "$logTag download stalled - cancelling")
                    wasStalled = true
                    call.cancel()
                    break
                }
            }
        }

        val bytes = try {
            // 边读边计数（而不是 body.bytes() 一次性读完）：
            // Content-Length 可能缺失或不准，只有累计到超限的那一刻中止，才能真正少读
            readWithLimit(trackedBody.source(), maxBytes) { tooLarge = true }
        } catch (e: IOException) {
            DebugLogger.d(TAG, "$logTag download interrupted: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            // 大图（如 4K 放大结果）一次性读入可能超出堆上限，降级为失败而非崩溃
            DebugLogger.w(TAG, "$logTag download OOM: ${e.message}")
            null
        } finally {
            watchdogJob.cancel()
        }

        val failureType = when {
            bytes != null -> ConnectionFailure.NONE
            tooLarge -> ConnectionFailure.FILE_TOO_LARGE
            wasStalled -> ConnectionFailure.STALLED
            else -> ConnectionFailure.NETWORK
        }

        return DownloadResult(bytes, failureType)
    }

    /**
     * 流式读取响应体，累计超过 [maxBytes] 时放弃并返回 null（同时回调 [onTooLarge]）。
     *
     * 为什么不用 `ResponseBody.bytes()`：它先把整个响应读进一个不断增长的 Buffer，
     * 最后再拷成一个等长 byte[]，**峰值约为文件大小的两倍**。对 200 MB 的放大图就是
     * 400 MB 瞬时占用，堆撑不住只能整个失败 —— 而且是在把 200 MB 都读完、白等一场之后
     * 才知道。这里改成边读边判，超限立刻停，既省内存也省时间。
     */
    private fun readWithLimit(
        source: okio.BufferedSource,
        maxBytes: Long,
        onTooLarge: () -> Unit
    ): ByteArray? {
        val sink = okio.Buffer()
        val chunk = okio.Buffer()
        var total = 0L
        while (true) {
            val read = source.read(chunk, DOWNLOAD_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (maxBytes > 0 && total > maxBytes) {
                DebugLogger.w(TAG, "Download exceeded limit: >${maxBytes / (1024 * 1024)}MB")
                onTooLarge()
                return null
            }
            sink.write(chunk, read)
        }
        return sink.readByteArray()
    }

    /**
     * 按最大边长采样解码图片。
     *
     * 解码后统一做 EXIF 方向校正（P1-26）：BitmapFactory 不读 Orientation，
     * 手机/相机导出的 JPEG 会躺倒。非 JPEG（PNG/WebP）不额外付 IO。
     *
     * @param bytes 原始图片字节
     * @param maxDimension 允许的最大边长（<= 0 表示不限制）
     */
    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val decoded = decodeSampledRaw(bytes, maxDimension) ?: return null
        return ExifOrientation.applyFromEncodedBytes(bytes, decoded)
    }

    /** 纯解码（尺寸采样），EXIF 校正由 [decodeSampled] 统一负责 */
    private fun decodeSampledRaw(bytes: ByteArray, maxDimension: Int): Bitmap? {
        if (maxDimension <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // 第一步：只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= maxDimension) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // 第二步：按 2 的幂次采样，**保证解码后长边不超过上限**
        //
        // 循环条件以前写的是 `longest / (sampleSize * 2) >= maxDimension`：它退出时
        // `longest / sampleSize < 2 * maxDimension`，也就是解码结果最长可以到 8191 ——
        // 号称 4096 的上限实际是 8191×8191 ≈ 268 MB 的位图。对放大类工作流的输出图来说，
        // 这一张就吃掉了整个内存缓存的预算（maxMemory/3），之后任何图都进不了缓存、
        // 每次滚回来都要重下。条件改成 `longest / sampleSize > maxDimension` 才是
        // "不超过上限"的真实含义（代价是 2 的幂次离散，可能比必要的小一档）。
        val sampleSize = computeSampleSize(longest, maxDimension)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        DebugLogger.d(TAG, "Sampled decode: ${bounds.outWidth}x${bounds.outHeight} -> sample=$sampleSize")
        // 兜底：内存实在不够时按更保守的倍率再试一次，宁可少点细节也不要整张失败
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            val fallbackSample = sampleSize * 2
            DebugLogger.w(TAG, "Decode OOM at sample=$sampleSize, retrying at sample=$fallbackSample")
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = fallbackSample }
            )
        }
    }

    /**
     * Fetch a generated image from the ComfyUI server
     * Downloads and decodes the image into a Bitmap
     *
     * @param filename The filename of the generated image
     * @param subfolder The subfolder where the image is stored (usually empty or "")
     * @param type The image type (usually "output" or "temp")
     * @param maxDimension 解码后的最大边长（按需采样）
     * @param preview 非空时走服务端压缩（如 `"jpeg;75"`）—— 适合列表缩略图与大图降级：
     *   它是**同分辨率的有损压缩**，204 MB 的 PNG 实测压到 7.9 MB，代价是服务端要现场压一次。
     *   null（默认）表示拿原始文件。
     * @param callback Called with the result:
     *                 - bitmap: The decoded image, or null on error
     *                 - failureType: type of failure (NONE for success, STALLED if download stalled,
     *                   FILE_TOO_LARGE 表示原图超过下载上限 —— 调用方应据此降级到 [preview])
     */
    /**
     * 下载任意二进制产物到本地文件。
     *
     * 与 [fetchImage] 的区别：那条链路最后要 decode 成 Bitmap（失败就退化成低分辨率重试），
     * 而 3D 的 .glb 是几十 MB 的二进制，只能整包落盘、交给渲染器自己去读。
     *
     * @param destFile 目标文件（调用方选目录；建议 filesDir 下的固定子目录）
     */
    fun fetchRawFile(
        filename: String,
        subfolder: String,
        type: String,
        destFile: java.io.File,
        callback: (ok: Boolean, failureType: ConnectionFailure) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false, ConnectionFailure.NETWORK)
            return
        }
        val url = ComfyViewUrl.build(baseUrl, filename, subfolder, type) ?: run {
            callback(false, ConnectionFailure.NETWORK)
            return
        }
        val request = Request.Builder().url(url).get().build()
        // 用 [downloadClient] 而不是 [transferClient]：后者 readTimeout=0，
        // 断网时会无限挂起、永不回调失败（实测卡 "Downloading model…" 超过 1 分钟），
        // 用户要的"加载失败必须弹错误"就永远等不到。
        downloadClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false, if (call.isCanceled()) ConnectionFailure.STALLED else ConnectionFailure.NETWORK)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        DebugLogger.w(TAG, "Raw file fetch failed: HTTP ${resp.code}")
                        callback(false, ConnectionFailure.NETWORK)
                        return
                    }
                    var tmpFile: java.io.File? = null
                    try {
                        destFile.parentFile?.mkdirs()
                        // 先写临时文件再改名（v4.1.1 修）。
                        // 直写目标文件的后果：3D 全屏查看器与结果区内嵌预览可能同时要同一个
                        // GLB，第二个实例会把"已存在但只写了一半"的文件当成完整缓存 →
                        // 解析失败，且坏文件永久留在缓存目录（30~61MB 的 GLB 下载要几十秒，
                        // 这个窗口很大）。临时文件 + rename 后，只有完整下载才会出现在目标路径。
                        //
                        // P2-4：临时文件必须**每次下载唯一**（createTempFile）。固定
                        // `<dest>.part` 时，两个并发下载会同时写同一个临时文件，内容交错后
                        // rename 出去照样是坏文件 —— 正是上面要避免的那类问题。
                        val tmp = java.io.File.createTempFile(destFile.name + "-", ".part", destFile.parentFile)
                        tmpFile = tmp
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        val expected = body.contentLength()
                        val actual = tmp.length()
                        if (expected > 0 && actual != expected) {
                            tmp.delete()
                            DebugLogger.w(TAG, "Raw file size mismatch: expected=$expected actual=$actual")
                            callback(false, ConnectionFailure.NETWORK)
                            return
                        }
                        if (actual <= 0) {
                            tmp.delete()
                            DebugLogger.w(TAG, "Raw file empty, discarded")
                            callback(false, ConnectionFailure.NETWORK)
                            return
                        }
                        if (!tmp.renameTo(destFile)) {
                            // rename 失败（跨分区/被占用）时退化为拷贝后删临时文件
                            tmp.copyTo(destFile, overwrite = true)
                            tmp.delete()
                        }
                        DebugLogger.i(TAG, "Raw file saved: ${destFile.length()} bytes")
                        callback(true, ConnectionFailure.NETWORK)
                    } catch (e: Exception) {
                        // F4：唯一命名的 .part 可能已经写出几十 MB；异常/中断路径不删的话，
                        // 每次失败重试都会在 filesDir/model3d 留下一个垃圾文件，而该目录
                        // 属于"清缓存不碰"的保护目录，只能卸载应用才消失。
                        tmpFile?.delete()
                        DebugLogger.e(TAG, "Raw file write failed: ${e.message}")
                        callback(false, ConnectionFailure.NETWORK)
                    }
                }
            }
        })
    }

    override fun fetchImage(
        filename: String,
        subfolder: String,
        type: String,
        maxDimension: Int,
        preview: String?,
        useThumbnailClient: Boolean,
        callback: (bitmap: Bitmap?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.d(TAG, "Fetching image: ${Obfuscator.filename(filename)}")
        val baseUrl = getBaseUrl() ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val url = ComfyViewUrl.build(baseUrl, filename, subfolder, type, preview) ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // 缩略图链路（列表预览 / 大图降级）走带响应头超时的专用 client，见 thumbnailClient 的注释
        val call = (if (useThumbnailClient) thumbnailClient else transferClient).newCall(request)

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                val failureType = if (call.isCanceled()) {
                    DebugLogger.w(TAG, "Image download stalled")
                    ConnectionFailure.STALLED
                } else {
                    ConnectionFailure.NETWORK
                }
                callback(null, failureType)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful && response.body != null) {
                        val result = downloadWithStallDetection(call, response.body!!, "Image")
                        val bitmap = result.bytes?.let { decodeSampled(it, maxDimension) }
                        callback(bitmap, result.failureType)
                    } else {
                        // 404 = 服务器上已没有这个文件（电脑端清理了 output 等），
                        // 单独分类让图库停止无意义的重试；其他 HTTP 错误仍按网络类处理
                        val failureType = connectionFailureForHttpCode(response.code)
                        if (failureType == ConnectionFailure.FILE_MISSING) {
                            DebugLogger.w(TAG, "Image missing on server (404): ${Obfuscator.filename(filename)}")
                        }
                        callback(null, failureType)
                    }
                }
            }
        })
    }

    /**
     * Fetch raw bytes from the ComfyUI server without decoding.
     * Used for metadata extraction where we need the original file bytes.
     *
     * @param filename The filename of the file
     * @param subfolder The subfolder where the file is stored
     * @param type The file type (usually "output" or "temp")
     * @param callback Called with the result:
     *                 - bytes: The raw file bytes, or null on error
     *                 - failureType: type of failure (NONE for success, STALLED if download stalled, NETWORK for other errors)
     */
    /**
     * 只下载文件的前 maxBytes 字节（HTTP Range 请求）。
     *
     * 用途：读取 PNG 内嵌的生成参数。ComfyUI 把参数写在 IDAT 之前的 tEXt chunk 里，
     * 实测一张 1.5MB 的图，参数只占前 2591 字节（IHDR + tEXt），后面的都是像素数据。
     * 而整图可达数 MB 到 200MB，用流量/隧道全量下载要几十秒
     * （用户 2026-09-18 报障：点开生成参数要 44 秒）。
     *
     * @param truncated true 表示服务端文件比 maxBytes 大（响应码 206），返回的是前段
     */
    fun fetchPartialBytes(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        maxBytes: Long,
        callback: (bytes: ByteArray?, truncated: Boolean, failureType: ConnectionFailure) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null, false, ConnectionFailure.NETWORK)
            return
        }

        val url = ComfyViewUrl.build(baseUrl, filename, subfolder, type) ?: run {
            callback(null, false, ConnectionFailure.NETWORK)
            return
        }

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${maxBytes - 1}")
            .get()
            .build()

        transferClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, false, ConnectionFailure.NETWORK)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(null, false, ConnectionFailure.NETWORK)
                        return
                    }
                    // 206 = 服务端按 Range 返回了前段；200 = 不支持 Range，body 为完整文件
                    val truncated = response.code == 206
                    val body = response.body
                    if (body == null) {
                        callback(null, truncated, ConnectionFailure.NONE)
                        return
                    }
                    // 必须限量读取：服务端忽略 Range（或被中间代理吞掉 Range 头）时返回的是**完整文件**，
                    // 直接 body.bytes() 会把一张 200MB 的放大图整份读进内存，峰值还约等于两倍。
                    // 超限时返回 null，由调用方走既有的回退分支。
                    val bytes = body.source().use { source ->
                        readWithLimit(source, maxBytes) { /* 超限：交给调用方回退 */ }
                    }
                    callback(bytes, truncated, ConnectionFailure.NONE)
                }
            }
        })
    }

    fun fetchRawBytes(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        callback: (bytes: ByteArray?, failureType: ConnectionFailure) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val url = ComfyViewUrl.build(baseUrl, filename, subfolder, type) ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = transferClient.newCall(request)

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                val failureType = if (call.isCanceled()) {
                    DebugLogger.w(TAG, "Raw bytes download stalled")
                    ConnectionFailure.STALLED
                } else {
                    ConnectionFailure.NETWORK
                }
                callback(null, failureType)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful && response.body != null) {
                        val result = downloadWithStallDetection(call, response.body!!, "Raw bytes")
                        callback(result.bytes, result.failureType)
                    } else {
                        callback(null, ConnectionFailure.NETWORK)
                    }
                }
            }
        })
    }

    /**
     * 取回服务器上的模型介绍数据（`output/comfychair/model_intros.json`）。
     *
     * 与 [fetchRawBytes] 的区别只有三点，都是为了「介绍只是锦上添花」这条原则：
     * 1. **上限收紧到 4MB**（正常数据 250KB）——`fetchRawBytes` 的 48MB 上限对它是过宽的；
     * 2. **失败一律回调 null**，把 OkHttp 的几种失败（网络、停滞、超限、非 2xx）以及
     *    **发起阶段的同步异常**都在这里归并掉，调用方只看「拿到没有」；
     * 3. **顺带做响应校验**（[ModelIntroFetch.decodePayload]）——HTML 错误页、半截 JSON
     *    在这一层就被判成 null，不会流到写盘/装载环节。
     *
     * 回调运行在 OkHttp 的调度线程上（**不是主线程**），调用方可以放心在其中做解析与落盘。
     *
     * @param callback 拿到可用的 JSON 文本；任何失败都是 null，且**绝不抛出**
     */
    fun fetchModelIntros(callback: (json: String?) -> Unit) {
        try {
            val baseUrl = getBaseUrl()
            if (baseUrl.isNullOrBlank()) {
                callback(null)
                return
            }

            // 地址来自 ModelIntroFetch.buildUrl（内部引用 ModelIntroSync.REMOTE_PATH 常量）
            val url = ModelIntroFetch.buildUrl(baseUrl)

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val call = transferClient.newCall(request)
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    DebugLogger.w(TAG, "Model intros fetch failed: ${e.message}")
                    callback(null)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful || response.body == null) {
                            DebugLogger.w(TAG, "Model intros fetch rejected: HTTP ${response.code}")
                            callback(null)
                            return
                        }
                        val result = downloadWithStallDetection(
                            call, response.body!!, "Model intros", ModelIntroFetch.MAX_INTRO_BYTES
                        )
                        if (result.bytes == null) {
                            DebugLogger.w(TAG, "Model intros fetch aborted: ${result.failureType}")
                            callback(null)
                            return
                        }
                        val json = ModelIntroFetch.decodePayload(result.bytes)
                        if (json == null) {
                            DebugLogger.w(TAG, "Model intros response is not a usable table")
                        }
                        callback(json)
                    }
                }
            })
        } catch (e: Exception) {
            // 发起阶段就抛出（取地址 / 地址拼装 / Request 构造 / enqueue）：按"绝不抛出"的约定
            // 归并成一次空回调。调用方（ConnectionManager.syncModelIntros）的闸门靠这次回调复位，
            // 所以必须**恰好回调一次**：能走到这里说明 enqueue 尚未成功，异步回调不可能已经发生过。
            DebugLogger.w(TAG, "Model intros fetch setup failed: ${e.message}")
            callback(null)
        }
    }

    /**
     * Fetch a generated video from the ComfyUI server
     * Downloads the video as raw bytes
     *
     * @param filename The filename of the generated video
     * @param subfolder The subfolder where the video is stored (usually empty or "video")
     * @param type The video type (usually "output" or "temp")
     * @param callback Called with the result:
     *                 - bytes: The raw video bytes, or null on error
     *                 - failureType: type of failure (NONE for success, STALLED if download stalled, NETWORK for other errors)
     */
    override fun fetchVideo(
        filename: String,
        subfolder: String,
        type: String,
        callback: (bytes: ByteArray?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.d(TAG, "Fetching video: ${Obfuscator.filename(filename)}")
        val baseUrl = getBaseUrl() ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val url = ComfyViewUrl.build(baseUrl, filename, subfolder, type) ?: run {
            callback(null, ConnectionFailure.NETWORK)
            return
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = transferClient.newCall(request)

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                val failureType = if (call.isCanceled()) {
                    DebugLogger.w(TAG, "Video download stalled")
                    ConnectionFailure.STALLED
                } else {
                    ConnectionFailure.NETWORK
                }
                callback(null, failureType)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful && response.body != null) {
                        val result = downloadWithStallDetection(call, response.body!!, "Video", MAX_VIDEO_DOWNLOAD_BYTES)
                        callback(result.bytes, result.failureType)
                    } else {
                        // 与图片链路同一口径：404 单独分型为 FILE_MISSING（源已不存在），
                        // 其余 HTTP 错误才归 NETWORK —— 视频/GIF 也能进入第三态并停止重试。
                        val failureType = connectionFailureForHttpCode(response.code)
                        if (failureType == ConnectionFailure.FILE_MISSING) {
                            DebugLogger.w(TAG, "Video missing on server (404): ${Obfuscator.filename(filename)}")
                        }
                        callback(null, failureType)
                    }
                }
            }
        })
    }

    /**
     * Interrupt/cancel the currently running execution
     * Posts to the /interrupt endpoint to stop the current generation
     *
     * @param callback Called with the result: success true/false
     */
    fun interruptExecution(callback: (success: Boolean) -> Unit) {
        DebugLogger.i(TAG, "Interrupting execution")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Cannot interrupt: no connection")
            callback(false)
            return
        }

        val url = "$baseUrl/interrupt"

        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.e(TAG, "Interrupt failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        DebugLogger.i(TAG, "Interrupt successful")
                    } else {
                        DebugLogger.w(TAG, "Interrupt failed: ${response.code}")
                    }
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Get system stats from the ComfyUI server
     * Returns server information like device name, OS, etc.
     *
     * @param callback Called with the result:
     *                 - statsJson: The system stats JSON object, or null on error
     */
    fun getSystemStats(callback: (statsJson: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/system_stats"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val statsJson = JSONObject(response.body?.string() ?: "{}")
                            callback(statsJson)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Clear all tasks from the queue
     * Removes all pending tasks from the execution queue
     *
     * @param callback Called with the result: success true/false
     */
    fun clearQueue(callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/queue"

        val requestBody = JSONObject().apply {
            put("clear", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Clear the history of generated images
     * Removes all history entries
     *
     * @param callback Called with the result: success true/false
     */
    fun clearHistory(callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/history"

        val requestBody = JSONObject().apply {
            put("clear", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Delete a single history item by prompt ID
     * Removes the specified entry from execution history
     *
     * @param promptId The prompt ID of the history item to delete
     * @param callback Called with the result: success true/false
     */
    fun deleteHistoryItem(promptId: String, callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/history"

        val requestBody = JSONObject().apply {
            put("delete", org.json.JSONArray().put(promptId))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Upload an image to the ComfyUI server input folder
     * Used for Image-to-image to upload the source image with mask
     *
     * @param imageData The PNG image data as byte array
     * @param filename The desired filename for the uploaded image
     * @param subfolder The subfolder to upload to (default empty = root input folder)
     * @param overwrite Whether to overwrite existing file (default true)
     * @param mimeType 上传内容的 MIME 类型。ComfyUI 的 `/upload/image` 端点本身不校验类型
     *                 （只按文件名落盘到 input 目录），所以音频走同一个端点、同一个表单字段，
     *                 把这里换成对应类型（如 "audio/mpeg"）即可。
     * @param callback Called with the result:
     *                 - success: true if uploaded successfully
     *                 - filename: The actual filename saved on the server
     *                 - errorMessage: error message if failed
     *                 - failureType: type of failure (NONE for success, AUTHENTICATION for 401/403, NETWORK for other errors)
     */
    fun uploadImage(
        imageData: ByteArray,
        filename: String,
        subfolder: String = "",
        overwrite: Boolean = true,
        mimeType: String = "image/png",
        /**
         * 上传进度回调（v4.6.1 模块 D）。
         * 由 [ProgressTrackingRequestBody] 每写一块回调一次；UI 侧做节流后展示百分比。
         */
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
        callback: (success: Boolean, filename: String?, errorMessage: String?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.i(TAG, "Uploading image: ${Obfuscator.filename(filename)} (${imageData.size} bytes)")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Upload failed: no connection")
            callback(false, null, context?.getString(R.string.error_no_connection) ?: "No connection to server", ConnectionFailure.NETWORK)
            return
        }

        val url = "$baseUrl/upload/image"

        // Build multipart form data request
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                filename,
                imageData.toRequestBody(mimeType.toMediaType())
            )
            .addFormDataPart("subfolder", subfolder)
            .addFormDataPart("overwrite", overwrite.toString())
            .build()

        // Wrap with progress tracking for stall detection（同时把进度透给 UI）
        val progressBody = ProgressTrackingRequestBody(requestBody, onProgress)

        val request = Request.Builder()
            .url(url)
            .post(progressBody)
            .build()

        val call = transferClient.newCall(request)

        // Watchdog coroutine to detect stalls
        val watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                if (System.currentTimeMillis() - progressBody.lastProgressTime > STALL_TIMEOUT_MS) {
                    DebugLogger.w(TAG, "Upload stalled - cancelling")
                    call.cancel()
                    break
                }
            }
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                watchdogJob.cancel()
                if (call.isCanceled()) {
                    // Stall detected - watchdog cancelled the call
                    DebugLogger.w(TAG, "Upload stalled")
                    callback(false, null, context?.getString(R.string.error_upload_stalled)
                        ?: "Upload stalled", ConnectionFailure.STALLED)
                } else {
                    DebugLogger.e(TAG, "Upload failed: ${e.message}")
                    callback(false, null, "Upload failed: ${e.message}", ConnectionFailure.NETWORK)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                watchdogJob.cancel()
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val savedFilename = json.optString("name", filename)
                            DebugLogger.i(TAG, "Upload successful")
                            callback(true, savedFilename, null, ConnectionFailure.NONE)
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Failed to parse upload response: ${e.message}")
                            callback(false, null, "Failed to parse response: ${e.message}", ConnectionFailure.NETWORK)
                        }
                    } else {
                        // Handle authentication errors with clean messages
                        if (response.code == 401 || response.code == 403) {
                            DebugLogger.e(TAG, "Upload auth error: ${response.code}")
                            callback(false, null, context?.getString(R.string.error_auth_unauthorized)
                                ?: "Authentication failed", ConnectionFailure.AUTHENTICATION)
                        } else {
                            DebugLogger.e(TAG, "Upload error: ${response.code}")
                            callback(false, null, "Server error: ${response.code}", ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Clean up resources
     * Should be called when done with the client
     */
    fun shutdown() {
        closeWebSocket()
        // Note: We don't shutdown the httpClient here as it might be reused
        // In a real app, you might want to manage the client lifecycle differently
    }
}

/**
 * "回调只投递一次"守卫（P0：二次 resume 崩溃的 client 层防线）。
 *
 * 用法：`val guard = SingleDeliveryGuard { 记日志 }; guard.deliver { callback(x) }`。
 * 第二次及以后的 [deliver] 一律丢弃：[onDuplicate] 只用于记日志，**不会**再触发回调。
 *
 * 为什么必须"投递前就置位"：如果先执行 block 再置位，block 里抛异常时状态不会变，
 * 下一次投递会重复执行 —— 而 P0 场景正是"首次投递抛异常 → catch 再投递一次"。
 *
 * 抽成独立类（而不是内联局部变量）是为了 JVM 单测能直接钉住"二次投递不抛异常"。
 */
internal class SingleDeliveryGuard(
    private val onDuplicate: (() -> Unit)? = null
) {
    private var delivered = false

    fun deliver(block: () -> Unit) {
        if (delivered) {
            onDuplicate?.invoke()
            return
        }
        delivered = true
        block()
    }

    /** 是否已经投递过（排障/单测用）。 */
    fun hasDelivered(): Boolean = delivered
}

/**
 * `/history` 请求 URL 的纯构造（v4.18.x 抽出以便 JVM 单测钉住）。
 *
 * @param baseUrl 服务器根地址（如 `http://192.168.1.10:8188`）
 * @param maxItems 条数上限；null = 不带参数（全量，旧行为）。服务端实测支持
 *   `?max_items=N`（server.py:1139），这里保证只拼数字，非法值由服务端解析。
 * @param offset 跳过的 history 条数；null = 不带（服务端默认最新端）。
 *   **实测（execution.py:1423-1439）offset 从最旧一端计数**，与"省略 offset"
 *   （取最新端）语义相反，所以 0 也是有效值、必须原样拼出，不得当"无 offset"吞掉。
 */
internal fun historyUrl(baseUrl: String, maxItems: Int?, offset: Int? = null): String {
    val params = buildList {
        maxItems?.let { add("max_items=$it") }
        offset?.let { add("offset=$it") }
    }
    if (params.isEmpty()) return "$baseUrl/history"
    return "$baseUrl/history?" + params.joinToString("&")
}

