package sh.hnet.comfychair.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 更新下载的失败分型与重试决策（v4.12.0，A-B1 执行项）。
 *
 * 全部为纯函数、不依赖 Android 框架 —— [UpdateDownloadWorker] 的 catch 块据此
 * 决定 Result.retry() 还是 Result.failure()，横幅文案按类别区分失败阶段；
 * 单元测试直接覆盖每个分支，不在测试里放宽断言。
 */
object DownloadPolicy {

    /** 自动重试上限（WorkManager runAttemptCount 达到该值后判最终失败，不再 Result.retry()） */
    const val MAX_AUTO_RETRIES = 5

    /** 进度级停滞阈值：连续这么久没有任何字节增长，看门狗就主动断开连接续传重试 */
    const val STALL_THRESHOLD_MS = 120_000L

    /** 失败类别（决定横幅文案与是否可自动重试） */
    enum class FailureKind {
        /** 连接建立失败（ConnectException / UnknownHostException / SSLException） */
        CONNECT_FAILED,

        /** 传输超时（SocketTimeoutException / 停滞看门狗） */
        TRANSFER_TIMEOUT,

        /** 服务端错误（HTTP 5xx，通常临时性） */
        SERVER_ERROR,

        /** 服务端拒绝（HTTP 4xx；416 由 Worker 先行自愈，走到分型器即自愈已失败） */
        HTTP_REJECTED,

        /** 磁盘空间不足（不可靠重试解决） */
        DISK_FULL,

        /** 重定向目标不在更新域白名单内（P0-7，不可恢复：重试解决不了策略问题） */
        INSECURE_REDIRECT,

        /** 其他（保持旧版宽容口径：IO 类异常仍可重试） */
        UNKNOWN
    }

    /** HTTP 状态码异常（响应非 2xx 时抛出，替代原来只有字符串的裸 IOException） */
    class DownloadHttpException(val code: Int) : IOException("服务器返回 HTTP $code")

    /** 磁盘空间不足（带剩余/需要字节数，横幅文案用） */
    class DiskFullException(val usableBytes: Long, val neededBytes: Long) :
        IOException(
            "存储空间不足（剩余 ${usableBytes / (1024 * 1024)}MB，" +
                "需要约 ${neededBytes / (1024 * 1024)}MB）"
        )

    /** 进度级停滞（看门狗主动 cancel 连接后抛出，按可恢复异常处理续传重试） */
    class StalledTransferException(val lastByteAtMs: Long, val detectedAtMs: Long) :
        IOException("传输停滞超过 ${STALL_THRESHOLD_MS / 1000} 秒无字节增长")

    /**
     * 重定向目标越界（P0-7）：
     * 清单里的下载地址通过了白名单校验，但 302 指向了别的主机。
     * 不加这道闸，攻击者只要控制一个 302 就能把任意载荷投进安装链路。
     */
    class InsecureRedirectException(val targetUrl: String) :
        IOException("重定向目标不在更新域白名单内：$targetUrl")

    /** 异常 → 失败类别（A-R1 Important-1 的分型口径） */
    fun classifyFailure(e: Throwable): FailureKind = when (e) {
        is DownloadHttpException ->
            if (e.code in 500..599) FailureKind.SERVER_ERROR else FailureKind.HTTP_REJECTED
        is InsecureRedirectException -> FailureKind.INSECURE_REDIRECT
        is StalledTransferException -> FailureKind.TRANSFER_TIMEOUT
        is SocketTimeoutException -> FailureKind.TRANSFER_TIMEOUT
        is ConnectException -> FailureKind.CONNECT_FAILED
        is UnknownHostException -> FailureKind.CONNECT_FAILED
        is SSLException -> FailureKind.CONNECT_FAILED
        is DiskFullException -> FailureKind.DISK_FULL
        else -> FailureKind.UNKNOWN
    }

    /**
     * 该异常是否值得自动重试（与重试上限是两个独立维度）。
     * 网络类/服务端临时故障可重试；HTTP 4xx / 磁盘不足 / 重定向越界不可恢复；
     * UNKNOWN 保持旧行为——只有 IO 类异常才重试（对应原 catch(IOException) → retry 的口径）。
     */
    fun isRetryable(e: Throwable): Boolean = when (classifyFailure(e)) {
        FailureKind.TRANSFER_TIMEOUT,
        FailureKind.CONNECT_FAILED,
        FailureKind.SERVER_ERROR -> true
        FailureKind.HTTP_REJECTED,
        FailureKind.DISK_FULL,
        FailureKind.INSECURE_REDIRECT -> false
        FailureKind.UNKNOWN -> e is IOException
    }

    /** 自动重试次数是否还在上限内（runAttemptCount：0 = 首次运行，4 可重试、5 不可） */
    fun shouldRetryAutomatically(runAttemptCount: Int): Boolean =
        runAttemptCount in 0 until MAX_AUTO_RETRIES

    /**
     * ".part 已等于总长" = 上次已下完但没来得及 rename。此时再发 `bytes=N-`
     * 续传请求源站必然回 416（A-R1 Critical-2 的根源），Worker 应直接进 SHA 校验。
     */
    fun isPartComplete(partLength: Long, total: Long): Boolean =
        partLength > 0 && total > 0 && partLength == total

    /**
     * 停滞判定：自最后一次收到字节起已过阈值。
     * [lastByteAtMs] <= 0 表示一次字节都没收到（连接刚建立），交给 readTimeout 管，不算停滞。
     */
    fun isStalled(lastByteAtMs: Long, nowMs: Long, thresholdMs: Long = STALL_THRESHOLD_MS): Boolean =
        lastByteAtMs > 0 && nowMs - lastByteAtMs >= thresholdMs

    // ---------------- SHA-256 完整性校验（P0-7） ----------------

    /** SHA-256 的十六进制表示恰好 64 个字符 */
    private const val SHA256_HEX_LENGTH = 64

    private val SHA256_HEX_PATTERN = Regex("^[0-9a-fA-F]{$SHA256_HEX_LENGTH}$")

    /** SHA-256 校验结论 */
    enum class Sha256Verdict {
        /** 实际值与清单给出的校验值一致 → 放行 */
        MATCH,

        /** 清单没给校验值（空串/缺失/不是 64 位十六进制）→ 必须判失败，不能短路放行 */
        MISSING,

        /** 校验值对不上 → 载荷不可信 */
        MISMATCH
    }

    /**
     * 规范化清单里的 SHA-256：合法（64 位十六进制）返回小写值，否则返回 null。
     *
     * 单独抽出来的原因：Worker 与清单解析器都要判断「清单到底有没有给校验值」，
     * 两处口径必须完全一致。
     */
    fun normalizeSha256(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (!SHA256_HEX_PATTERN.matches(trimmed)) return null
        return trimmed.lowercase()
    }

    /**
     * 完整性校验结论（P0-7 的核心闸门）。
     *
     * 修复前 Worker 的判据是
     * `if (expectedSha256.isEmpty() || actualSha.equals(expected)) break`——
     * 清单里**不写** sha256 反而能让校验直接通过，等于给攻击者留了「把校验关掉」的开关。
     * 现在缺失一律判 [Sha256Verdict.MISSING]，由调用方按失败处理。
     */
    fun verifySha256(expected: String?, actual: String): Sha256Verdict {
        val normalized = normalizeSha256(expected) ?: return Sha256Verdict.MISSING
        return if (normalized.equals(actual.trim().lowercase())) {
            Sha256Verdict.MATCH
        } else {
            Sha256Verdict.MISMATCH
        }
    }

    // ---------------- 重定向白名单（P0-7） ----------------

    /**
     * 解析重定向目标（支持 `Location` 写相对路径）。
     * 基地址或 Location 不合法时返回 null。
     */
    fun resolveRedirect(baseUrl: String, location: String?): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        return base.resolve(loc)?.toString()
    }

    /**
     * 重定向目标是否允许：必须仍是 https，且主机在更新域白名单内。
     *
     * 白名单为空（未配置更新源）时一律拒绝 —— fail-closed。
     */
    fun isRedirectTargetAllowed(targetUrl: String?, allowedHosts: Set<String>): Boolean {
        if (allowedHosts.isEmpty()) return false
        val parsed = targetUrl?.trim()?.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in allowedHosts
    }
}
