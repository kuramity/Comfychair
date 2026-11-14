package sh.hnet.comfychair.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    /** 异常 → 失败类别（A-R1 Important-1 的分型口径） */
    fun classifyFailure(e: Throwable): FailureKind = when (e) {
        is DownloadHttpException ->
            if (e.code in 500..599) FailureKind.SERVER_ERROR else FailureKind.HTTP_REJECTED
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
     * 网络类/服务端临时故障可重试；HTTP 4xx / 磁盘不足不可恢复；
     * UNKNOWN 保持旧行为——只有 IO 类异常才重试（对应原 catch(IOException) → retry 的口径）。
     */
    fun isRetryable(e: Throwable): Boolean = when (classifyFailure(e)) {
        FailureKind.TRANSFER_TIMEOUT,
        FailureKind.CONNECT_FAILED,
        FailureKind.SERVER_ERROR -> true
        FailureKind.HTTP_REJECTED,
        FailureKind.DISK_FULL -> false
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
}
