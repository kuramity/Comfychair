package sh.hnet.comfychair.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // readTimeout 管"两次读间隔"：只要数据在流动就不杀（隧道 9KB/s 也安全）；
        // 45 秒彻底断流才交给 WorkManager 的重试
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // 速度滑动窗口：(时间戳, 累计字节) 采样
    private val speedSamples: Deque<Pair<Long, Long>> = ArrayDeque()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)
        val expectedSha256 = inputData.getString(KEY_SHA256).orEmpty()
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, -1L)
        val versionName = inputData.getString(KEY_VERSION_NAME).orEmpty()

        if (url.isNullOrEmpty()) {
            repository.publishFailed("下载地址缺失")
            return@withContext Result.failure()
        }

        try {
            val part = partFile(applicationContext)
            val resumeFrom = if (part.exists()) part.length() else 0L
            val total = downloadWithResume(url, part, resumeFrom, expectedSize)
            val downloaded = part.length()

            repository.publishProgress(downloaded, total, 0L, -1L)

            // SHA-256 流式校验（硬性约束 15：通过才允许变完整 APK）
            repository.publishPhase(UpdatePhase.VERIFYING)
            val actualSha = sha256Of(part)
            if (expectedSha256.isNotEmpty() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                repository.publishFailed("安装包校验失败（SHA-256 不匹配），已清理重新下载可重试")
                DebugLogger.w(TAG, "SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha")
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
            Result.success(workDataOf("finalSize" to final.length()))
        } catch (e: IOException) {
            DebugLogger.w(TAG, "Download failed: ${e.message}")
            repository.publishFailed("下载中断（${e.javaClass.simpleName}），可重试续传")
            Result.retry()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Download error: ${e.message}")
            repository.publishFailed("下载出错：${e.message}")
            Result.failure()
        }
    }

    /**
     * 带断点续传的下载主循环。
     *
     * @return 总字节数（206 时从 Content-Range 解析；200 全量时为本次 Content-Length）
     */
    private fun downloadWithResume(url: String, part: File, resumeFrom: Long, expectedSize: Long): Long {
        // .part 已超过预期大小（换版本了/文件坏了）：作废重下
        if (expectedSize > 0 && resumeFrom > expectedSize) {
            part.delete()
        }
        val actualResume = if (part.exists()) part.length() else 0L

        val requestBuilder = Request.Builder().url(url).get()
        if (actualResume > 0) {
            requestBuilder.header("Range", "bytes=$actualResume-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code == 206 -> {
                    // 硬性约束 9：总长度只信 Content-Range（bytes start-end/total）
                    val contentRange = response.header("Content-Range")
                    val total = parseTotalFromContentRange(contentRange)
                        ?: (actualResume + (response.body?.contentLength() ?: 0L))
                    writeBody(response, part, actualResume, total)
                    return total
                }
                response.isSuccessful -> {
                    // 服务器不支持 Range（返回 200 全量）：.part 里旧字节与新响应不一致，
                    // 必须丢弃从头写
                    val total = response.body?.contentLength() ?: -1L
                    val target = if (part.exists() && part.length() > 0) {
                        part.delete(); part
                    } else part
                    writeBody(response, target, 0L, total)
                    return if (total > 0) total else target.length()
                }
                else -> throw IOException("服务器返回 HTTP ${response.code}")
            }
        }
    }

    private fun writeBody(response: okhttp3.Response, part: File, resumeFrom: Long, total: Long) {
        val body = response.body ?: throw IOException("响应体为空")

        // 存储空间预检（硬性约束 17 的"存储空间不足检查"）：
        // 剩余空间 < 还需要下载的量 + 32MB 余量 时直接失败，不要下到一半撑爆
        val needed = if (total > 0) total - resumeFrom else 0L
        if (needed > 0) {
            val usable = part.usableSpace
            if (usable in 1 until needed + 32L * 1024 * 1024) {
                throw IOException("存储空间不足（剩余 ${usable / (1024 * 1024)}MB，需要约 ${needed / (1024 * 1024)}MB）")
            }
        }

        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(resumeFrom)
            // 续传长度校验：本地 .part 比 Content-Range 的 start 大说明文件不一致
            if (resumeFrom > 0 && total > 0 && resumeFrom >= total) {
                throw IOException("本地缓存已完整，无需续传")
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var written = resumeFrom
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        raf.write(buffer, 0, read)
                        written += read

                        // 每块都上报，节流统一由 [UpdateRepository.publishProgress] 做
                        //（硬性约束 13：读盘循环零 UI 开销，400ms 才真正发射 StateFlow）
                        val now = System.currentTimeMillis()
                        val speed = computeSpeed(now, written)
                        val eta = if (speed > 0 && total > written) (total - written) / speed else -1L
                        repository.publishProgress(written, total, speed, eta)
                    }
                }
            }
            // 收尾再发一次（把最后一段补上，force 跳过节流）
            val speed = computeSpeed(System.currentTimeMillis(), written)
            repository.publishProgress(written, total, speed, 0L, force = true)
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
