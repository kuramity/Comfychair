package sh.hnet.comfychair.update

import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.util.DebugLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 版本检查器（v4.6.0 内置更新模块）。
 *
 * 职责：拉取服务器上的 update.json，解析出 [UpdateInfo]，并用
 * **versionCode 整型比较**判断是否需要更新（硬性约束：禁止 versionName 字符串比较）。
 *
 * **静默降级**（硬性约束 20）：任何失败（服务未开启 / 笔记本休眠 / 隧道断开 / 网络异常 /
 * JSON 损坏）都返回 null，不抛异常、不弹错误 —— 调用方按"无更新"处理。
 * 只有用户手动点"检查更新"时，才通过 [lastCheckError] 展示失败原因。
 *
 * **URL 白名单**（安全规范）：更新服务器域名固定，只有 [MANIFEST_URL] 一个入口，
 * 下载地址（apkUrl）在解析后做 scheme+host 校验，防清单被篡改后指向任意地址。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /**
     * 更新清单地址（开源版由 BuildConfig 注入，默认空字符串）。
     *
     * 开源仓库不内置任何个人服务器的地址：构建时若 [BuildConfig.UPDATE_MANIFEST_URL] 为空，
     * 本模块整体降级为「未配置」——发版者要自建更新源，在 local.properties 里配一行即可。
     */
    val MANIFEST_URL: String = BuildConfig.UPDATE_MANIFEST_URL.trim()

    /** 更新源是否已配置（空地址视为未配置，所有网络请求直接跳过） */
    val isConfigured: Boolean = MANIFEST_URL.isNotEmpty()

    /** 允许的 APK 下载域白名单：由清单地址的 host 推导，不额外硬编码任何域名 */
    private val ALLOWED_HOSTS: Set<String> =
        MANIFEST_URL.toHttpUrlOrNull()?.host?.let { setOf(it) } ?: emptySet()

    /** 最近一次手动检查的失败原因（自动检查不写这个字段） */
    @Volatile
    var lastCheckError: String? = null
        private set

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * versionCode 整型比较（硬性约束 7）。
     * 纯函数，单测直接覆盖：远端大于本地才更新；相等或小于都不更新。
     */
    fun shouldUpdate(currentVersionCode: Int, remoteVersionCode: Int): Boolean =
        remoteVersionCode > currentVersionCode

    /**
     * 校验清单里的下载地址：必须 https 且 host 在白名单内。
     * 防止 update.json 被篡改后把 153MB 的任意载荷装进 App 的安装链路。
     */
    fun isApkUrlAllowed(url: String): Boolean = isApkUrlAllowed(url, ALLOWED_HOSTS)

    /**
     * 白名单校验的可测试形式：host 集合由调用方传入，
     * 单元测试不必依赖构建期注入的真实域名。
     */
    internal fun isApkUrlAllowed(url: String, allowedHosts: Set<String>): Boolean {
        if (allowedHosts.isEmpty()) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in allowedHosts
    }

    /**
     * 拉取并解析更新清单。
     *
     * @param manualCheck true 表示用户手动触发（失败原因会写入 [lastCheckError] 供 UI 展示）；
     *                    false 表示启动时的静默检查（失败不留痕、不弹窗）
     * @return 有更新时返回 [UpdateInfo]；无更新（已是最新版）返回 null 且不写错误；
     *         检查失败返回 null（手动检查时 [lastCheckError] 有原因）
     */
    fun checkForUpdate(currentVersionCode: Int, manualCheck: Boolean): UpdateInfo? {
        lastCheckError = null
        // 未配置更新源（开源默认）：不发任何网络请求，静默按「无更新」处理
        if (!isConfigured) {
            if (manualCheck) {
                lastCheckError = "未配置更新源（构建时未注入 UPDATE_MANIFEST_URL）"
            }
            return null
        }
        val info = try {
            val request = Request.Builder().url(MANIFEST_URL).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onError("服务器返回 HTTP ${response.code}", manualCheck)
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    onError("清单为空", manualCheck)
                    return null
                }
                UpdateInfo.fromJson(body)
            }
        } catch (e: IOException) {
            onError("无法连接更新服务器（${e.javaClass.simpleName}）", manualCheck)
            return null
        } catch (e: Exception) {
            onError("清单解析失败（${e.message}）", manualCheck)
            return null
        }

        if (!isApkUrlAllowed(info.apkUrl)) {
            onError("清单里的下载地址不在允许的域名内", manualCheck)
            return null
        }

        // P0-7：清单必须带有效 SHA-256 校验值，否则下载完成也无法确认载荷完整性。
        // 在这里就拒绝，避免白下 150MB 再失败；Worker 侧另有硬闸门兜底。
        if (DownloadPolicy.normalizeSha256(info.sha256) == null) {
            onError("清单缺少有效的 sha256 校验值，已拒绝该更新", manualCheck)
            return null
        }

        return if (shouldUpdate(currentVersionCode, info.versionCode)) {
            DebugLogger.i(TAG, "Update available: code ${info.versionCode} > $currentVersionCode")
            info
        } else {
            DebugLogger.i(TAG, "Already up to date (local=$currentVersionCode remote=${info.versionCode})")
            null
        }
    }

    private fun onError(message: String, manualCheck: Boolean) {
        DebugLogger.w(TAG, "Check failed: $message")
        if (manualCheck) {
            lastCheckError = message
        }
    }
}
