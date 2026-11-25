package sh.hnet.comfychair.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
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
 * update/ 目录解析结果（派工单第 1 层）。
 *
 * 存在的意义：把「路径查询失败」和「磁盘真的满」拆成两种语义。旧实现
 * `File(getExternalFilesDir(null), "update")` 在外部目录返回 null 时会得到
 * **相对路径** `update`，`mkdirs()` 静默失败，`usableSpace` 恒为 0 —— 用户看到
 * 「剩余 0 B」，实际是路径根本不合法。
 *
 * @see UpdateDownloadWorker.resolveUpdateDir
 */
sealed interface UpdateDirResult {
    /** 拿到了可用目录；[usedFallback]=true 表示外部私有目录不可用、回退到了应用内部目录 */
    data class Ok(val dir: File, val usedFallback: Boolean) : UpdateDirResult

    /** 外部与内部目录都不可用：调用方必须按「存储不可用」失败，绝不产出相对路径 */
    data class Unavailable(val reason: String) : UpdateDirResult
}

/**
 * 空间预检结论（派工单第 2 层）：UNKNOWN（查询失败）与 INSUFFICIENT（数值有效但不够）
 * 是两种不同的语义，前者放行记 warning，后者才拦截。
 */
enum class SpaceOutcome { ENOUGH, INSUFFICIENT, UNKNOWN }

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
 * - **15 .part 约定**：`update.apk.part` 放在 getExternalFilesDir/update/（外部私有
 *   目录不可用时回退 filesDir/update/，见 [resolveUpdateDir]）；
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

        /** update/ 子目录名（外部私有空间与内部回退共用同一个名字，卸载即清） */
        private const val UPDATE_DIR_NAME = "update"

        /**
         * update/ 目录（派工单第 1 层）：返回 [UpdateDirResult]，不再可能给出相对路径。
         *
         * 首选 `getExternalFilesDir(null)/update`（外部私有空间，卸载即清）；
         * 该 API 可返回 null（无外部存储 / 存储被卸载），旧代码在此处构造出
         * `File(null, "update")` → 相对路径 → usableSpace 恒 0 的「剩余 0 B」误报。
         * 现在外部不可用就回退 `filesDir/update`，两者都失败由调用方按存储不可用处理。
         */
        fun updateDir(context: Context): UpdateDirResult =
            resolveUpdateDir(context.getExternalFilesDir(null), context.filesDir)

        /**
         * 纯函数版目录解析（JVM 单测直接覆盖，不碰 Android 框架）。
         *
         * 判定口径：
         * - `mkdirs()` 返回 false 时**必须再查 isDirectory** —— 目录已存在时 mkdirs 同样
         *   返回 false，属正常情况；
         * - 目录存在但不可写（`canWrite()` false）同样视为不可用 → 回退；
         * - 结果一律经 `absoluteFile` 归一化 —— **绝不产出相对路径**。
         */
        fun resolveUpdateDir(externalFilesDir: File?, internalFilesDir: File): UpdateDirResult {
            if (externalFilesDir != null) {
                val external = File(externalFilesDir, UPDATE_DIR_NAME).absoluteFile
                if (usableDirectory(external)) {
                    return UpdateDirResult.Ok(external, usedFallback = false)
                }
            }
            val internal = File(internalFilesDir, UPDATE_DIR_NAME).absoluteFile
            if (usableDirectory(internal)) {
                return UpdateDirResult.Ok(internal, usedFallback = true)
            }
            return UpdateDirResult.Unavailable(
                "外部与内部 update 目录均不可用" +
                    "（externalFilesDir=$externalFilesDir, internalCandidate=${internal.path}）"
            )
        }

        /** 目录可用性判定：能建/已建 + 确实是目录 + 可写。任何异常按不可用处理。 */
        private fun usableDirectory(dir: File): Boolean = try {
            (dir.mkdirs() || dir.isDirectory) && dir.isDirectory && dir.canWrite()
        } catch (_: Exception) {
            false
        }

        /** 下载预检的空间余量门槛：可用空间必须 ≥ 还需下载的字节 + 32MB 才放行 */
        private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

        /**
         * 空间预检纯函数（派工单第 2 层）。
         *
         * 语义分层（旧 bug 的教训：`usable < needed + margin` 把「查询失败的 0」
         * 当成了「真的满」）：
         * - [usableBytes] 为 null → **UNKNOWN**：查询失败不等于空间不足，调用方放行记 warning；
         * - 数值有效（**包括 0**，0 是真实测得的剩余量）→ 与 needed+margin 比较；
         * - 边界 `usable == needed + margin` 判 ENOUGH。
         *
         * 注意与 [selectUsable] 的分工：**API 语义上不成立的 0**（getAllocatableBytes 返回 0）
         * 已在 [selectUsable] 里转成 null（视为查询无效），不会走到这里；而 StatFs 实测的 0
         * 是有效数值，会带着 0 走到这里判 INSUFFICIENT。两种 0 的语义必须分开。
         */
        fun decideSpaceOutcome(usableBytes: Long?, neededBytes: Long, marginBytes: Long): SpaceOutcome {
            if (usableBytes == null) return SpaceOutcome.UNKNOWN
            return if (usableBytes < neededBytes + marginBytes) {
                SpaceOutcome.INSUFFICIENT
            } else {
                SpaceOutcome.ENOUGH
            }
        }

        /**
         * 三路查询的原始值（诊断日志用；null = 该路失败或系统版本不支持）。
         * [usableBytes] 是按优先级选出的最终口径，可能为 null（未知）。
         */
        data class SpaceQuery(
            val usableBytes: Long?,
            val statFsBytes: Long?,
            val allocatableBytes: Long?,
        )

        /**
         * 从两路原始值里选出有效口径（纯函数，派工单第 2 层的「0 的语义」在这里落地）：
         * 1. `StorageManager.getAllocatableBytes(UUID_DEFAULT)`（API 26+，考虑分配配额）
         *    —— 返回 **0 视为无效**（该 API 在配额模型下不会以 0 表示「真的满了」），走回退；
         * 2. 回退 `StatFs(dir.absolutePath).availableBytes` —— 非负即为有效（含 0）；
         * 3. 两路都无效 → null（未知）。
         */
        internal fun selectUsable(allocatableBytes: Long?, statFsBytes: Long?): Long? =
            allocatableBytes?.takeIf { it > 0L } ?: statFsBytes?.takeIf { it >= 0L }

        /** [selectUsable] 的两路原始值查询（需要 Context，不在 JVM 单测里直接调） */
        internal fun querySpaceDetail(context: Context, dir: File): SpaceQuery {
            val statFsBytes = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrNull()
            val allocatableBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)
                        ?.getAllocatableBytes(StorageManager.UUID_DEFAULT)
                }.getOrNull()
            } else {
                null
            }
            return SpaceQuery(
                usableBytes = selectUsable(allocatableBytes, statFsBytes),
                statFsBytes = statFsBytes,
                allocatableBytes = allocatableBytes,
            )
        }

        /** 写探针文件名（下载开始前自检用，用完即删） */
        private const val PROBE_FILE_NAME = ".write-probe.tmp"

        /**
         * 写探针（派工单第 4 层）：真实写 1KB → 读回校验 → 删除。
         *
         * 存在的意义：空间 API 报「够」不代表写进去真的成功（配额、fuse 异常、只读重挂载
         * 都会骗过静态查询）。返回 null = 通过；非 null = 失败原因，由调用方转
         * [DownloadPolicy.StorageUnavailableException]（不可重试分型）。
         * 纯 java.io，JVM 单测直接覆盖（temp 目录通过 / 不存在目录失败 / 探针文件必被清）。
         */
        internal fun probeWritable(dir: File): String? {
            val probe = File(dir, PROBE_FILE_NAME)
            return try {
                val payload = ByteArray(1024) { (it % 251).toByte() }
                probe.writeBytes(payload)
                if (!probe.readBytes().contentEquals(payload)) {
                    "写探针读回校验不一致"
                } else {
                    null
                }
            } catch (e: Exception) {
                "写探针 IO 失败: ${e.javaClass.simpleName} ${e.message}"
            } finally {
                runCatching { if (probe.exists()) probe.delete() }
            }
        }

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

        /**
         * update/ 下文件的定位（派工单第 1 层的调用点适配）。
         *
         * 目录解析成功 → 用解析结果；Unavailable → 仍给出**内部目录口径的绝对路径**
         * （该目录此刻多半不存在，`exists()/isFile` 自然为 false、`delete()` 无害失败、
         * 写操作抛 IOException 由外层 catch 分型），任何情况下不再出现相对路径，
         * 也不再出现「路径无效却报 usableSpace=0」的假数值。
         */
        private fun updateFile(context: Context, name: String): File =
            when (val result = updateDir(context)) {
                is UpdateDirResult.Ok -> File(result.dir, name)
                is UpdateDirResult.Unavailable ->
                    File(File(context.filesDir, UPDATE_DIR_NAME), name)
            }

        fun partFile(context: Context): File = updateFile(context, PART_FILE_NAME)
        fun finalFile(context: Context): File = updateFile(context, FINAL_FILE_NAME)
        fun shaFile(context: Context): File = updateFile(context, SHA_FILE_NAME)
        fun versionFile(context: Context): File = updateFile(context, VERSION_FILE_NAME)

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
            // 派工单第 1、4 层：下载开始前（part 已定位）做目录复检 + 1KB 写探针。
            // 两者失败都抛 StorageUnavailableException → classifyFailure 判
            // STORAGE_UNAVAILABLE（不可重试）→ failureMessage 走「存储不可用」横幅，
            // 不会再以「剩余 0 B」的假数值面目出现（那是路径失效，不是磁盘满）。
            val dirResult = updateDir(applicationContext)
            when (dirResult) {
                is UpdateDirResult.Unavailable ->
                    throw DownloadPolicy.StorageUnavailableException(dirResult.reason)
                is UpdateDirResult.Ok -> {
                    if (dirResult.usedFallback) {
                        // 第 4 层诊断：回退必须能区分「externalFilesDir 为 null」与「目录存在但不可写」，
                        // 否则事后只有「回退了」这一条信息，无法定位是存储被卸载还是权限/配额问题。
                        val externalRoot = applicationContext.getExternalFilesDir(null)
                        val externalCandidate = externalRoot?.let {
                            File(it, UPDATE_DIR_NAME).absoluteFile
                        }
                        val fallbackReason = when {
                            externalRoot == null ->
                                "externalFilesDir 为 null（无外部存储或存储被卸载）"
                            externalCandidate == null ->
                                "外部 update 目录路径无法构造"
                            !externalCandidate.isDirectory ->
                                "外部 update 目录不存在或不是目录（创建失败）"
                            !externalCandidate.canWrite() ->
                                "外部 update 目录存在但不可写"
                            else ->
                                "外部 update 目录未通过可用性判定"
                        }
                        DebugLogger.w(
                            TAG,
                            "外部私有目录不可用 → 回退内部目录 ${dirResult.dir.absolutePath}" +
                                "（原因：$fallbackReason）"
                        )
                    }
                    probeWritable(dirResult.dir)?.let { failure ->
                        // 第 4 层诊断：探针失败必须能定位到具体路径与失败原因（配额/fuse/只读重挂载）
                        DebugLogger.w(
                            TAG,
                            "写探针失败 path=${File(dirResult.dir, PROBE_FILE_NAME).absolutePath}: $failure"
                        )
                        throw DownloadPolicy.StorageUnavailableException(
                            "dir=${dirResult.dir.absolutePath}: $failure"
                        )
                    }
                }
            }

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
                // INSUFFICIENT 才走到这里，数值是真实测得的剩余量（不再是伪造的 0）
                ctx.getString(
                    R.string.update_error_disk,
                    UpdateFormat.formatBytes(disk?.usableBytes ?: 0L),
                    UpdateFormat.formatBytes(disk?.neededBytes ?: 0L)
                )
            }
            // 派工单第 3 层：目录不可用/探针失败说「存储不可用」，绝不显示剩余字节数
            DownloadPolicy.FailureKind.STORAGE_UNAVAILABLE -> ctx.getString(
                R.string.update_error_storage_unavailable
            )
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

        // 存储空间预检（派工单第 2、4 层）：三路查询 + 语义分层。
        // 旧实现 `part.usableSpace < needed + 32MB` 的问题：路径无效时 usableSpace
        // 恒为 0，「查询失败」被当成「真的满了」，用户只看到「剩余 0 B」。
        // 现在：INSUFFICIENT（数值有效且不够）才拦截并带真实数值；UNKNOWN（查询失败）
        // 放行并记 warning——写不进去会由 [DownloadPolicy.StorageUnavailableException]
        // 的探针/IO 分型兜底，不会再伪装成「磁盘只剩 0」。
        val needed = if (total > 0) total - resumeFrom else 0L
        if (needed > 0) {
            val detail = querySpaceDetail(applicationContext, part)
            val outcome = decideSpaceOutcome(detail.usableBytes, needed, SPACE_MARGIN_BYTES)
            // 全量诊断（验收标准：出错时能看到 路径/externalDir 是否为 null/三路原始值/needed）
            DebugLogger.i(
                TAG,
                "update storage: dir=${part.parentFile?.absolutePath} " +
                    "externalDir=${applicationContext.getExternalFilesDir(null) == null} " +
                    "usableSpace=${part.usableSpace} statFs=${detail.statFsBytes} " +
                    "allocatable=${detail.allocatableBytes} needed=$needed outcome=$outcome"
            )
            when (outcome) {
                SpaceOutcome.INSUFFICIENT ->
                    // 只在数值有效且确实不够时抛 DiskFullException，必带真实剩余量
                    throw DownloadPolicy.DiskFullException(detail.usableBytes ?: 0L, needed)
                SpaceOutcome.UNKNOWN ->
                    DebugLogger.w(
                        TAG,
                        "存储空间查询失败（allocatable=${detail.allocatableBytes} statFs=${detail.statFsBytes}），按放行策略继续"
                    )
                SpaceOutcome.ENOUGH -> Unit
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
