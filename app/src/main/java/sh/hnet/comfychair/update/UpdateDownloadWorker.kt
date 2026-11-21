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
import okhttp3.Interceptor
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
 * - 异常分型 + 重试上限（v4.18.0 由 5 降到 2）：可恢复走 retry，HTTP 4xx / 磁盘不足直接最终失败；
 * - 停滞看门狗：连续 120 秒无字节增长主动 cancel 连接续传重试（readTimeout 的补充）；
 * - 失败横幅按阶段区分文案；重试退避期间通知切"等待重试（第 N 次）"。
 * 以上新行为统一受 [AppSettings.isUpdateDownloadLegacyMode] 回退开关约束（安全阀）。
 *
 * v4.18.0（R3 / R5）：
 * - **版本闸门**：每次启动先比「目标 versionCode」与「当前已安装 versionCode」，
 *   已装好就直接结束并清通知，不再白下 160MB；
 * - **失败即停**：达到上限返回 failure（不再 retry），并发一条「点检查更新可重试」的通知；
 * - **回退开关也有上限**（3 次），不存在无上限的重试分支。
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

        /**
         * 本次任务要下载的 versionCode（R3）。
         *
         * Worker 每次启动（含 WorkManager 的自动重试、跨进程恢复）都拿它和
         * **当前已安装版本**再比一次：装好了新版本还在下旧包，正是用户流量被烧掉的根源。
         */
        const val KEY_VERSION_CODE = "versionCode"

        /** 下载会话代次（R2：迟到的旧会话回调不污染新会话状态） */
        const val KEY_SESSION_GENERATION = "sessionGeneration"

        /** 文件名约定（硬性约束 15） */
        const val PART_FILE_NAME = "update.apk.part"
        const val FINAL_FILE_NAME = "update.apk"

        /**
         * 清单 sha256 旁车文件（P2-11）。
         *
         * 与 update.apk 同目录：冷启动恢复时内存里没有清单，只能靠它复验磁盘文件的完整性。
         * 写完安装包就写它，删包时一起删。
         */
        const val SHA_FILE_NAME = "update.apk.sha256"

        /**
         * 安装包 versionCode 旁车（R4）。
         *
         * 只有 sha256 说明「文件没被换过」，说明不了「它比已安装的版本新」——
         * 没有这份元数据，冷启动就会把一个已经装好的版本重新提示为「待安装」。
         */
        const val VERSION_FILE_NAME = "update.apk.version"

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
                // P0-7：禁止自动跟随重定向。OkHttp 默认会自动跟随并**重新校验不了**目标主机，
                // 清单里的 https 地址只要 302 到任意主机，载荷就能绕过白名单进安装链路。
                // 改为逐跳校验（见 [redirectGuardInterceptor]）。
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(redirectGuardInterceptor())
                .build()
        }

        /** 手动跟随重定向的最大跳数（防止 A→B→A 之类的跳转死循环） */
        private const val MAX_REDIRECT_HOPS = 5

        /**
         * 重定向白名单拦截器（P0-7）。
         *
         * 客户端已关闭自动跟随，由这里逐跳处理 3xx：每一跳的新地址都必须仍命中
         * [UpdateChecker.isApkUrlAllowed]（https + 清单同域白名单），任一跳越界立即抛
         * [DownloadPolicy.InsecureRedirectException]，请求不会发到越界主机上。
         * 请求头（含 Range）随 builder 一路保留，续传语义不受影响。
         */
        internal fun redirectGuardInterceptor(): Interceptor = Interceptor { chain ->
            var request = chain.request()
            var response = chain.proceed(request)
            var hops = 0
            while (response.isRedirect) {
                val location = response.header("Location")
                val nextUrl = DownloadPolicy.resolveRedirect(request.url.toString(), location)
                // 先关掉上一跳响应体，避免连接泄漏
                response.close()
                if (nextUrl == null) {
                    throw IOException("重定向地址无法解析（Location=$location）")
                }
                if (!UpdateChecker.isApkUrlAllowed(nextUrl)) {
                    DebugLogger.w(TAG, "重定向目标越界，已拒绝：$nextUrl")
                    throw DownloadPolicy.InsecureRedirectException(nextUrl)
                }
                if (hops >= MAX_REDIRECT_HOPS) {
                    throw IOException("重定向次数过多（超过 $MAX_REDIRECT_HOPS 跳）")
                }
                hops++
                request = request.newBuilder().url(nextUrl).build()
                response = chain.proceed(request)
            }
            response
        }

        fun partFile(context: Context): File = File(updateDir(context), PART_FILE_NAME)
        fun finalFile(context: Context): File = File(updateDir(context), FINAL_FILE_NAME)
        fun shaFile(context: Context): File = File(updateDir(context), SHA_FILE_NAME)
        fun versionFile(context: Context): File = File(updateDir(context), VERSION_FILE_NAME)

        /**
         * 读取当前**已安装**的 versionCode（Int 口径的便捷入口）。
         *
         * 实现统一委托给 [UpdateCleanupPolicy.installedVersionCode]：PackageManager 为主，
         * 异常时回退 BuildConfig 并记 warning。F2 复核指出旧实现有两份口径不同的回退
         * （Worker 读失败返回 0 = 闸门 fail-open；共享版回退 BuildConfig），后续必然漂移，
         * 因此这里只保留一层委托，不再自己 catch。
         */
        internal fun installedVersionCode(context: Context): Int =
            UpdateCleanupPolicy.installedVersionCode(context).toInt()

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
        val targetVersionCode = inputData.getInt(KEY_VERSION_CODE, 0)
        val generation = inputData.getLong(KEY_SESSION_GENERATION, -1L)
        // 回退开关（v4.12.0 安全阀）：true = 保持 v4.11.0 前的旧行为
        val legacy = AppSettings.isUpdateDownloadLegacyMode(applicationContext)

        if (url.isNullOrEmpty()) {
            repository.publishFailed("下载地址缺失", generation)
            return@withContext Result.failure()
        }

        // P2 fail-closed（复核 low）：升级前入队的旧任务没有 KEY_VERSION_CODE 入参，
        // getInt(默认 0) 读成 0；而 isObsolete 要求 target > 0 才可能返回 true，
        // 于是闸门对这类任务完全失效 —— WorkManager 在应用更新后重排它们，旧包会被
        // 继续下完（继续烧流量）。拿不到目标版本就无法证明该任务还需要跑，直接中止。
        // 正常路径（入队时必带 versionCode）行为不变。
        if (UpdateCleanupPolicy.shouldAbortMissingTarget(targetVersionCode)) {
            DebugLogger.w(
                TAG,
                "更新任务缺少目标 versionCode（读到 $targetVersionCode），无法判断是否过时 → 中止下载"
            )
            // 用户可见文案走资源（6 个语言目录同步补齐），不再硬编码中文（F1 复核）。
            val message = applicationContext.getString(R.string.update_missing_target_cancelled)
            repository.publishFailed(message, generation)
            UpdateNotifier.notifyFailed(applicationContext, message)
            UpdateNotifier.cancelProgress(applicationContext)
            return@withContext Result.failure()
        }

        // R3：每次启动都再比一次版本号。
        // WorkManager 会把未完成的 OneTimeWork 跨进程、跨重启恢复，于是「已经装好新版本
        // 之后旧任务还在下」——这是流量被烧掉、通知栏进度条永远撤不掉的直接原因。
        // 这里在**发任何网络请求之前**拦下，不再白下一字节。
        val installedCode = installedVersionCode(applicationContext)
        if (UpdateCleanupPolicy.isObsolete(targetVersionCode, installedCode)) {
            DebugLogger.i(
                TAG,
                "任务已过时（目标 $targetVersionCode <= 已安装 $installedCode），跳过下载并清理"
            )
            repository.publishObsolete()
            UpdateNotifier.cancel(applicationContext)
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
                // P0-7：清单缺校验值必须判失败。原判据是
                // `if (expectedSha256.isEmpty() || actualSha.equals(expectedSha256)) break`，
                // 只要清单不写 sha256 就能让完整性校验直接通过 —— 等于把开关交给攻击者。
                when (DownloadPolicy.verifySha256(expectedSha256, actualSha)) {
                    DownloadPolicy.Sha256Verdict.MATCH -> break
                    DownloadPolicy.Sha256Verdict.MISSING -> {
                        DebugLogger.w(TAG, "清单缺少有效 SHA-256（raw=$expectedSha256）→ 拒绝安装")
                        val message = applicationContext.getString(R.string.update_error_verify_missing)
                        repository.publishFailed(message, generation)
                        UpdateNotifier.notifyFailed(applicationContext, message)
                        UpdateNotifier.cancelProgress(applicationContext)
                        return@withContext Result.failure()
                    }
                    // 不匹配：落到下面既有的自愈/失败处理
                    DownloadPolicy.Sha256Verdict.MISMATCH -> Unit
                }

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
                repository.publishFailed(message, generation)
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

            // P2-11：把清单 sha256 与安装包一起落盘。冷启动恢复时清单已不在内存，
            // 没有这份旁车就无法复验，而「无法复验」必须按不可信处理（见 UpdateRestorePolicy）。
            try {
                val normalizedSha = DownloadPolicy.normalizeSha256(expectedSha256)
                if (normalizedSha != null) {
                    shaFile(applicationContext).writeText(normalizedSha)
                } else {
                    // 走到这里说明上面的校验分支已判过 MISSING，正常不会发生；防御性记一条
                    DebugLogger.w(TAG, "写入 sha256 旁车失败：清单校验值为空，恢复时将按不可信处理")
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "写入 sha256 旁车失败：${e.javaClass.simpleName} ${e.message}")
            }

            // R4：把本次包的 versionCode 一起落盘。冷启动只有 sha256 还不够 ——
            // 「文件没被换」不等于「它比已安装的版本新」，缺了版本号就会把
            // 已经装好的版本重新提示成「待安装」。
            if (targetVersionCode > 0) {
                try {
                    versionFile(applicationContext).writeText(targetVersionCode.toString())
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "写入 versionCode 旁车失败：${e.javaClass.simpleName} ${e.message}")
                }
            }

            repository.publishReady(final.length(), generation)
            UpdateNotifier.notifyUpdateReady(applicationContext, versionName)
            UpdateNotifier.cancelProgress(applicationContext)
            Result.success(workDataOf("finalSize" to final.length()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 任务被 WorkManager 取消（手动重试 REPLACE / R3 清理 / 系统清理）：
            // 不是失败，不要发"下载失败"横幅；只把会话标记收掉（代次不符的迟到回调会被忽略）
            repository.publishSessionEnded(generation)
            throw e
        } catch (e: IOException) {
            if (legacy) {
                // 回退开关（v4.12.0 安全阀）：R5 第 3 条要求它**也有上限** ——
                // 旧行为「任何 IO 异常都 Result.retry()、无次数上限」是流量黑洞，
                // 已删除。这里最多 LEGACY_MAX_AUTO_RETRIES 次，之后失败即停。
                DebugLogger.w(
                    TAG,
                    "Download failed (legacy): attempt=$runAttemptCount ${e.message}"
                )
                if (DownloadPolicy.shouldRetryLegacy(runAttemptCount)) {
                    UpdateNotifier.notifyWaitingRetry(applicationContext, versionName, runAttemptCount + 1)
                    Result.retry()
                } else {
                    val message = applicationContext.getString(
                        R.string.update_error_retry_exhausted,
                        runAttemptCount
                    )
                    repository.publishFailed(message, generation)
                    UpdateNotifier.notifyFailed(
                        applicationContext,
                        applicationContext.getString(R.string.update_failed_retry_hint)
                    )
                    UpdateNotifier.cancelProgress(applicationContext)
                    Result.failure()
                }
            } else {
                handleDownloadFailure(e, versionName, generation)
            }
        } catch (e: Exception) {
            // 非 IO 异常维持旧行为：直接最终失败
            DebugLogger.w(TAG, "Download error: ${e.message}")
            val message = applicationContext.getString(R.string.update_error_unexpected, e.message ?: "")
            repository.publishFailed(message, generation)
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
    private fun handleDownloadFailure(e: IOException, versionName: String, generation: Long): Result {
        val kind = DownloadPolicy.classifyFailure(e)
        val attempt = runAttemptCount
        // R5：可重试异常也只在 MAX_AUTO_RETRIES（2）以内重试，之后失败即停
        val canRetry = DownloadPolicy.isRetryable(e) &&
            DownloadPolicy.shouldRetryAutomatically(attempt)
        DebugLogger.w(TAG, "download failed: kind=$kind attempt=$attempt retry=$canRetry msg=${e.message}")
        if (canRetry) {
            UpdateNotifier.notifyWaitingRetry(applicationContext, versionName, attempt + 1)
            return Result.retry()
        }
        val message = failureMessage(e, kind, attempt)
        repository.publishFailed(message, generation)
        // 已到上限：通知里给出明确的下一步（用户手动点「检查更新」即可续传重试），
        // 并撤掉那条 setOngoing 的进度条 —— 否则它会一直挂在通知栏，看起来像「安装进程挂起」。
        val notificationText = if (DownloadPolicy.isRetryable(e)) {
            applicationContext.getString(R.string.update_failed_retry_hint)
        } else {
            message
        }
        UpdateNotifier.notifyFailed(applicationContext, notificationText)
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
            // P0-7：重定向越界属于安全拒绝，必须给出明确原因而不是笼统的"未知错误"
            DownloadPolicy.FailureKind.INSECURE_REDIRECT -> ctx.getString(
                R.string.update_error_redirect,
                (e as? DownloadPolicy.InsecureRedirectException)?.targetUrl ?: ""
            )
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
