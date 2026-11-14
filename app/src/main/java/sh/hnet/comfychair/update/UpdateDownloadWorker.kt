package sh.hnet.comfychair.update

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Deque
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新 APK 下载引擎（v4.6.0）—— WorkManager CoroutineWorker，任务生命周期独立于 UI。
 *
 * 硬性约束的落地对照（编号见任务书 3.2 节）：
 * - **9 断点续传**：`Range: bytes=<已有>-` 请求；总长度**只从 206 的 Content-Range
 *   解析**（`bytes start-end/total`），不信本次 Content-Length；服务器返回 200
 *   （不支持 Range）时丢弃 .part 全量重下。
 * - **10 生命周期独立**：WorkManager 承载，App 被杀后 WorkManager 恢复执行。
 * - **13 节流**：进度经 [UpdateRepository.publishProgress] 统一节流（≥400ms 才向
 *   StateFlow 发射），读盘循环本身不触发任何 UI 更新。
 * - **14 流式读写**：64KB buffer 逐块搬运，无全量 ByteArray。
 * - **15 .part 约定**：`update.apk.part` 放在 getExternalFilesDir/update/；
 *   SHA-256 通过后先删旧完整 APK 再原子重命名，磁盘上最多一份完整 APK。
 * - **17/18 后台完成**：Worker 不直接 startActivity；完成通知由 [UpdateNotifier] 发出，
 *   点击通知触发安装。
 *
 * v4.12.0 稳定性修订（A-R1 取证，逐项编号见类内各处注释）：
 * - 状态机收敛：每次进入都把仓库状态拉回 DOWNLOADING，修复自动重试后进度永久停摆；
 * - 416 自愈：`.part` 达到总长时直接进 SHA 校验；收到 416 删 .part 只全量重下一次；
 * - 异常分型 + 重试上限（5 次）：可恢复走 retry，HTTP 4xx / 磁盘不足直接最终失败；
 * - 停滞看门狗：连续 120 秒无字节增长主动 cancel 连接续传重试（readTimeout 的补充）；
 * - 失败横幅按阶段区分文案；重试退避期间通知切"等待重试（第 N 次）"。
 * 以上新行为统一受 [AppSettings.isUpdateDownloadLegacyMode] 回退开关约束（安全阀）。
 */
class UpdateDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_EXPECTED_SIZE = "expectedSize"
        const val KEY_VERSION_NAME = "versionName"

        /** 文件名约定（硬性约束 15） */
        const val PART_FILE_NAME = "update.apk.part"
        const val FINAL_FILE_NAME = "update.apk"

        /** 单次读写的 buffer 大小（硬性约束 14：8KB–64KB 区间内取 64KB） */
        private const val BUFFER_SIZE = 64 * 1024

        /** 速度滑动窗口的采样保留时长 */
        private const val SPEED_WINDOW_MS = 3000L

        private const val TAG = "UpdateDownloadWorker"

        /** update/ 子目录（getExternalFilesDir 下，外部私有空间，卸载即清） */
        fun updateDir(context: Context): File =
            File(context.getExternalFilesDir(null), "update").apply { mkdirs() }

        /**
         * 应用级共享 OkHttp 客户端（v4.7.1）。
         *
         * 之前每个 Worker 实例各建一个客户端：WorkManager 重试（`Result.retry()`）
         * 会新建 Worker → 新连接池 → 隧道场景下多一次 TLS + 多路复用握手。
         * 放到 companion 的 lazy 里，全进程只有一个连接池。
         */
        internal val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // readTimeout 管"两次读间隔"：只要数据在流动就不杀（隧道 9KB/s 也安全）；
                // 45 秒彻底断流才交给 WorkManager 的重试。A-R1.7 实测 33KB/s 填满
                // 64KB buffer 约 1.9 秒，慢速但持续的传输不会被误杀
                .readTimeout(45, TimeUnit.SECONDS)
                // 写超时对纯下载无实际作用，显式声明避免依赖默认值（A-R1 Minor-1）
                .writeTimeout(30, TimeUnit.SECONDS)
                // callTimeout 必须为 0（不限）：公网隧道实测 33~90KB/s，152.8MB 需要
                // 28~77 分钟（A-R1.5），任何有限的整体超时都会在下载中段误杀慢速传输
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        }

        fun partFile(context: Context): File = File(updateDir(context), PART_FILE_NAME)
        fun finalFile(context: Context): File = File(updateDir(context), FINAL_FILE_NAME)

        /**
         * 从 Content-Range 头解析总长度：`bytes 123-456789/456790` → 456790。
         * 解析不了（缺头 / `*` / 非数字 / 非正数）返回 null。
         * 纯函数放 companion：单测直接覆盖（硬性约束 9 的验收点）。
         */
        internal fun parseTotalFromContentRange(contentRange: String?): Long? {
            if (contentRange.isNullOrEmpty()) return null
            val slash = contentRange.lastIndexOf('/')
            if (slash < 0 || slash == contentRange.length - 1) return null
            val totalPart = contentRange.substring(slash + 1).trim()
            if (totalPart == "*") return null
            return totalPart.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    private val repository = UpdateRepository

    /** 全进程共享客户端（见 [sharedClient]），Worker 实例只借用不新建 */
    private val client: OkHttpClient get() = sharedClient

    // 速度滑动窗口：(时间戳, 累计字节) 采样
    private val speedSamples: Deque<Pair<Long, Long>> = ArrayDeque()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)
        val expectedSha256 = inputData.getString(KEY_SHA256).orEmpty()
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, -1L)
        val versionName = inputData.getString(KEY_VERSION_NAME).orEmpty()
        // 回退开关（v4.12.0 安全阀）：true = 保持 v4.11.0 前的旧行为
        val legacy = AppSettings.isUpdateDownloadLegacyMode(applicationContext)

        if (url.isNullOrEmpty()) {
            repository.publishFailed("下载地址缺失")
            return@withContext Result.failure()
        }

        // v4.7.1：提升为前台服务 —— 退到后台/锁屏后不再被系统当普通后台任务挂起。
        // Android 12+ 在后台启动前台服务可能被拒（ForegroundServiceStartNotAllowedException），
        // 此时降级为普通后台任务 + 通知栏进度，下载本身不受影响。
        try {
            setForeground(createForegroundInfo(versionName))
        } catch (e: Exception) {
            DebugLogger.w(TAG, "promote to foreground failed: ${e.javaClass.simpleName} ${e.message}")
        }

        val part = partFile(applicationContext)

        // 状态机收敛（A-R1 Critical-1）：自动重试 / 进程重启后 phase 可能停在 FAILED，
        // publishProgress 的 phase 闸门会把后续所有进度丢掉 → 通知与 UI 永久停摆。
        // 每次进入 Worker 都无条件把状态拉回 DOWNLOADING（带 .part 已有字节），
        // 失败→重试→再进来才能重新推进度。
        val initialPart = if (part.exists()) part.length() else 0L
        val initialTotal = if (expectedSize > 0) expectedSize else repository.state.value.totalBytes
        if (!legacy) {
            repository.publishResuming(initialPart, initialTotal)
        }
        // 先发一次进度通知，避免"点了更新却看不到任何反馈"
        UpdateNotifier.updateProgress(applicationContext, repository.state.value, versionName)

        try {
            // .part 已达到已知总长（A-R1 Critical-2）：视为上次已下完但没来得及 rename，
            // 再发 `bytes=N-` 续传必然 416。直接跳过下载进 SHA 校验；
            // 校验不过按"残留 .part 与新版本不符"删掉全量重下一次（仍失败才判最终失败）。
            var skipDownload = !legacy && DownloadPolicy.isPartComplete(initialPart, expectedSize)
            var healedPart = false
            var total = if (skipDownload) initialPart else initialTotal

            while (true) {
                if (skipDownload) {
                    // 把"已完整"补进 UI/通知再进校验，避免进度停在旧值
                    repository.publishProgress(initialPart, total, 0L, 0L, force = true)
                } else {
                    val resumeFrom = if (part.exists()) part.length() else 0L
                    total = downloadWithResume(
                        url, part, resumeFrom, expectedSize, versionName,
                        allowSelfHeal = !legacy && !healedPart
                    )
                    repository.publishProgress(part.length(), total, 0L, -1L, force = true)
                }

                // SHA-256 流式校验（硬性约束 15：通过才允许变完整 APK）
                repository.publishPhase(UpdatePhase.VERIFYING)
                // 进度条切成"校验中"的不确定态，用户不会误以为卡在 99%
                UpdateNotifier.updateProgress(applicationContext, repository.state.value, versionName)
                val actualSha = sha256Of(part)
                if (expectedSha256.isEmpty() || actualSha.equals(expectedSha256, ignoreCase = true)) break

                if (skipDownload && !legacy && !healedPart) {
                    DebugLogger.w(TAG, "SHA-256 mismatch on pre-complete .part → full redownload once")
                    part.delete()
                    healedPart = true
                    skipDownload = false
                    // phase 此刻停在 VERIFYING，不拉回 DOWNLOADING 的话第二轮下载的
                    // 所有进度会被 publishProgress 的 phase 闸门拦掉（Critical-1 变体）
                    repository.publishResuming(0L, initialTotal)
                    continue
                }

                // 真下载后校验仍失败：不可恢复（A-R1 Important-1 的分型口径）
                DebugLogger.w(TAG, "SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha")
                val message = applicationContext.getString(R.string.update_error_verify)
                repository.publishFailed(message)
                UpdateNotifier.notifyFailed(applicationContext, message)
                UpdateNotifier.cancelProgress(applicationContext)
                return@withContext Result.failure()
            }

            // 原子就位：先删旧完整 APK（磁盘上最多一份），再 rename
            val final = finalFile(applicationContext)
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) {
                // rename 失败（跨卷/占用）退化为拷贝
                part.copyTo(final, overwrite = true)
                part.delete()
            }

            repository.publishReady(final.length())
            UpdateNotifier.notifyUpdateReady(applicationContext, versionName)
            UpdateNotifier.cancelProgress(applicationContext)
            Result.success(workDataOf("finalSize" to final.length()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 任务被 WorkManager 取消（用户撤单/系统清理）：不是失败，不要发"下载失败"横幅
            throw e
        } catch (e: IOException) {
            if (legacy) {
                // 回退开关：保持旧行为——任何 IO 异常都 Result.retry()（无次数上限）
                DebugLogger.w(TAG, "Download failed (legacy): ${e.message}")
                repository.publishFailed(
                    applicationContext.getString(R.string.update_error_legacy_interrupted, e.javaClass.simpleName)
                )
                UpdateNotifier.notifyFailed(
                    applicationContext,
                    repository.state.value.error ?: "下载中断，可重试续传"
                )
                Result.retry()
            } else {
                handleDownloadFailure(e, versionName)
            }
        } catch (e: Exception) {
            // 非 IO 异常维持旧行为：直接最终失败
            DebugLogger.w(TAG, "Download error: ${e.message}")
            val message = applicationContext.getString(R.string.update_error_unexpected, e.message ?: "")
            repository.publishFailed(message)
            UpdateNotifier.notifyFailed(
                applicationContext,
                repository.state.value.error ?: message
            )
            UpdateNotifier.cancelProgress(applicationContext)
            Result.failure()
        }
    }

    /**
     * 新逻辑的异常分型决策（A-R1 Important-1 / Minor-3 / 必做第 1、3、8 条）：
     * 可恢复异常且未超重试上限 → 切"等待重试（第 N 次）"通知 + Result.retry()；
     * 其余（HTTP 4xx / 磁盘不足 / 重试超限）→ 带失败阶段的横幅 + Result.failure()。
     *
     * 重试路径刻意不 publishFailed：FAILED 只留给"最终失败"，
     * 否则会出现"横幅说失败、后台还在重试"的矛盾状态（A-R1 第 1 条）。
     */
    private fun handleDownloadFailure(e: IOException, versionName: String): Result {
        val kind = DownloadPolicy.classifyFailure(e)
        val attempt = runAttemptCount
        val canRetry = DownloadPolicy.isRetryable(e) &&
            DownloadPolicy.shouldRetryAutomatically(attempt)
        DebugLogger.w(TAG, "download failed: kind=$kind attempt=$attempt retry=$canRetry msg=${e.message}")
        if (canRetry) {
            UpdateNotifier.notifyWaitingRetry(applicationContext, versionName, attempt + 1)
            return Result.retry()
        }
        val message = failureMessage(e, kind, attempt)
        repository.publishFailed(message)
        UpdateNotifier.notifyFailed(applicationContext, message)
        UpdateNotifier.cancelProgress(applicationContext)
        return Result.failure()
    }

    /** 失败横幅按阶段区分文案（A-R1 Minor-3）：连接 / 传输超时 / 服务端错误 / 磁盘 / 重试超限 */
    private fun failureMessage(
        e: IOException,
        kind: DownloadPolicy.FailureKind,
        attempt: Int
    ): String {
        val ctx = applicationContext
        if (DownloadPolicy.isRetryable(e) && !DownloadPolicy.shouldRetryAutomatically(attempt)) {
            // 异常本身可恢复，只是自动重试次数用完：提示手动重试
            return ctx.getString(R.string.update_error_retry_exhausted, attempt)
        }
        return when (kind) {
            DownloadPolicy.FailureKind.CONNECT_FAILED -> ctx.getString(R.string.update_error_connect)
            DownloadPolicy.FailureKind.TRANSFER_TIMEOUT -> ctx.getString(R.string.update_error_timeout)
            DownloadPolicy.FailureKind.SERVER_ERROR -> ctx.getString(
                R.string.update_error_server,
                (e as? DownloadPolicy.DownloadHttpException)?.code ?: 0
            )
            DownloadPolicy.FailureKind.HTTP_REJECTED -> ctx.getString(
                R.string.update_error_http,
                (e as? DownloadPolicy.DownloadHttpException)?.code ?: 0
            )
            DownloadPolicy.FailureKind.DISK_FULL -> {
                val disk = e as? DownloadPolicy.DiskFullException
                ctx.getString(
                    R.string.update_error_disk,
                    UpdateFormat.formatBytes(disk?.usableBytes ?: 0L),
                    UpdateFormat.formatBytes(disk?.neededBytes ?: 0L)
                )
            }
            DownloadPolicy.FailureKind.UNKNOWN -> ctx.getString(R.string.update_error_unknown)
        }
    }

    /**
     * 前台服务通知（与进度通知**同一个 id、同一条 Notification**）：
     * WorkManager 的 SystemForegroundService 接管这条通知后，后续只需要
     * `NotificationManager.notify` 更新同 id 的内容，不会出现两条进度条。
     *
     * 类型固定 `dataSync`：Android 14+ 要求前台服务声明类型，
     * 且 Manifest 里对应的 `SystemForegroundService` 也必须标 `dataSync`，两处必须一致。
     */
    private fun createForegroundInfo(versionName: String): ForegroundInfo {
        val notification = UpdateNotifier.buildProgressNotification(
            applicationContext,
            repository.state.value,
            versionName
        )
        return ForegroundInfo(
            UpdateNotifier.PROGRESS_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * 带断点续传的下载主循环。
     *
     * @param allowSelfHeal 收到 416 时是否允许"删 .part 全量重下一次"的自愈
     *   （A-R1 Critical-2：只在本 Worker 内直接重下一次，不靠 Result.retry() 无限循环）
     * @return 总字节数（206 时从 Content-Range 解析；200 全量时为本次 Content-Length）
     */
    private fun downloadWithResume(
        url: String,
        part: File,
        resumeFrom: Long,
        expectedSize: Long,
        versionName: String,
        allowSelfHeal: Boolean
    ): Long {
        // .part 已超过预期大小（换版本了/文件坏了）：作废重下
        if (expectedSize > 0 && resumeFrom > expectedSize) {
            part.delete()
        }
        val actualResume = if (part.exists()) part.length() else 0L

        val requestBuilder = Request.Builder().url(url).get()
        if (actualResume > 0) {
            requestBuilder.header("Range", "bytes=$actualResume-")
        }

        // call 引用传给 writeBody：停滞看门狗需要主动 cancel 正在阻塞的读
        val call = client.newCall(requestBuilder.build())
        call.execute().use { response ->
            when {
                // 续传起点达到/越过源站文件长度（.part 残留比远端还大）：源站必回 416。
                // 自愈一次：删 .part 全量重下；仍 416 说明不是本地文件问题 → 判不可恢复
                //（原代码把 416 当普通 IOException 无限重试，.part 永不清——A-R1 Critical-2）
                response.code == 416 -> {
                    if (allowSelfHeal && part.exists()) {
                        DebugLogger.w(TAG, "HTTP 416 on resume from $actualResume → delete .part, full redownload once")
                        part.delete()
                        return downloadWithResume(
                            url, part, 0L, expectedSize, versionName,
                            allowSelfHeal = false
                        )
                    }
                    throw DownloadPolicy.DownloadHttpException(416)
                }
                response.code == 206 -> {
                    // 硬性约束 9：总长度只信 Content-Range（bytes start-end/total）
                    val contentRange = response.header("Content-Range")
                    val total = parseTotalFromContentRange(contentRange)
                        ?: (actualResume + (response.body?.contentLength() ?: 0L))
                    writeBody(response, call, part, actualResume, total, versionName)
                    return total
                }
                response.isSuccessful -> {
                    // 服务器不支持 Range（返回 200 全量）：.part 里旧字节与新响应不一致，
                    // 必须丢弃从头写
                    val total = response.body?.contentLength() ?: -1L
                    val target = if (part.exists() && part.length() > 0) {
                        part.delete(); part
                    } else part
                    val written = writeBody(response, call, target, 0L, total, versionName)
                    return if (total > 0) total else written
                }
                else -> throw DownloadPolicy.DownloadHttpException(response.code)
            }
        }
    }

    private fun writeBody(
        response: okhttp3.Response,
        call: okhttp3.Call,
        part: File,
        resumeFrom: Long,
        total: Long,
        versionName: String
    ): Long {
        val body = response.body ?: throw IOException("响应体为空")

        // 存储空间预检（硬性约束 17 的"存储空间不足检查"）：
        // 剩余空间 < 还需要下载的量 + 32MB 余量 时直接失败，不要下到一半撑爆
        val needed = if (total > 0) total - resumeFrom else 0L
        if (needed > 0) {
            val usable = part.usableSpace
            if (usable in 1 until needed + 32L * 1024 * 1024) {
                // 抛 DiskFullException 让分型器归为不可恢复（重试解决不了磁盘满）
                throw DownloadPolicy.DiskFullException(usable, needed)
            }
        }

        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(resumeFrom)
            // 服务器确认了总长且本地已拿满全部字节：视为"上次已下完但没来得及 rename"，
            // 直接结束下载阶段让上层进 SHA 校验。原代码在这里抛 IOException →
            // 无限重试（A-R1 Critical-2 的 206 变体）
            if (resumeFrom > 0 && total > 0 && resumeFrom >= total) {
                return resumeFrom
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var written = resumeFrom
            // 停滞看门狗（A-R1 第 5 条补充手段）：readTimeout 只管"单次 read 阻塞"，
            // 挡不住"连接活着但几乎不吐字节"的慢速滴流。连续 120 秒没有任何字节
            // 增长就主动 cancel 当前 call，按可恢复异常续传重试。不替换 readTimeout。
            var lastByteAt = System.currentTimeMillis()
            var watchdogFired = false
            try {
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            raf.write(buffer, 0, read)
                            written += read
                            lastByteAt = System.currentTimeMillis()

                            // 每块都上报，节流统一由 [UpdateRepository.publishProgress] 做
                            //（硬性约束 13：读盘循环零 UI 开销，400ms 才真正发射 StateFlow）
                            val now = lastByteAt
                            val speed = computeSpeed(now, written)
                            val eta = if (speed > 0 && total > written) (total - written) / speed else -1L
                            // 与 UI 共用同一个 400ms 闸门：只有真的发射了才刷通知，
                            // 否则会出现"通知刷得比 UI 还勤"的无谓开销
                            if (repository.publishProgress(written, total, speed, eta)) {
                                UpdateNotifier.updateProgress(
                                    applicationContext,
                                    repository.state.value,
                                    versionName
                                )
                            }
                        }
                        if (!watchdogFired &&
                            DownloadPolicy.isStalled(lastByteAt, System.currentTimeMillis())
                        ) {
                            watchdogFired = true
                            DebugLogger.w(TAG, "no bytes for ${DownloadPolicy.STALL_THRESHOLD_MS / 1000}s → cancel call and retry")
                            call.cancel()
                        }
                    }
                }
            } catch (e: IOException) {
                // 看门狗 cancel 会让阻塞中的 read 抛 IOException——转成可恢复的停滞异常
                if (watchdogFired) {
                    throw DownloadPolicy.StalledTransferException(lastByteAt, System.currentTimeMillis())
                }
                throw e
            }
            // 收尾再发一次（把最后一段补上，force 跳过节流）
            val speed = computeSpeed(System.currentTimeMillis(), written)
            if (repository.publishProgress(written, total, speed, 0L, force = true)) {
                UpdateNotifier.updateProgress(applicationContext, repository.state.value, versionName)
            }
            return written
        }
    }

    /** 滑动窗口速度：最近 SPEED_WINDOW_MS 内的字节增量 / 时间差，避免跳变 */
    private fun computeSpeed(now: Long, written: Long): Long {
        speedSamples.addLast(now to written)
        while (speedSamples.size > 2 && now - speedSamples.peekFirst().first > SPEED_WINDOW_MS) {
            speedSamples.removeFirst()
        }
        if (speedSamples.size < 2) return 0L
        val (t0, b0) = speedSamples.peekFirst()
        val dt = now - t0
        val db = written - b0
        return if (dt > 0) db * 1000 / dt else 0L
    }

    /** 流式 SHA-256（64KB buffer 逐块喂给 MessageDigest，无全量内存） */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
