package sh.hnet.comfychair.update

import android.app.NotificationManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新流程的状态仓库 —— 单例 + StateFlow，Worker 与 Compose UI 之间的唯一桥梁。
 *
 * ## v4.18.0 行为改造（用户实测事故驱动）
 *
 * 事故：用户已经装好新版本后反复点「检查更新」，后台 Worker 还在用残留任务反复重试
 * 下载 160MB 包，流量被烧掉；通知栏那条 `setOngoing(true)` 的进度条永远撤不掉，
 * 看起来就是「安装进程一直挂起」。另外只要有新版本，每次进 App 都会弹一次更新弹窗。
 *
 * 现在的四条纪律：
 * 1. **一个版本只主动弹一次**（R1）：判定与持久化见 [UpdatePromptPolicy] /
 *    [UpdatePromptStore]；自动检查受配额约束，手动检查不受限。
 * 2. **不自动续传**（R2）：[restoreFromDisk] 在冷启动时**取消**残留 Worker、清通知，
 *    绝不把「.part 还在」恢复成「下载中」；下载只能由用户点「下载更新」触发。
 * 3. **已是最新就清理**（R3）：清单确认远端不比本地新时，按 [UpdateCleanupPolicy]
 *    取消残留任务 + 清通知；Worker 自己每次启动也再比一次版本号。
 * 4. **已下载包可复用**（R4）：校验通过且版本更高 → 直接 READY_TO_INSTALL，不重新下载。
 *
 * 生命周期关系（硬性约束 10/11/16 的落地）：
 * - **UI 只是观察者**：Dialog 的 onDismiss 不碰 Worker；但「忽略」会放弃本次 AVAILABLE
 *   （见 [reset]），后续只能靠手动检查再唤起（R1）。
 * - **Worker 是唯一写入方**：只有 [UpdateDownloadWorker] 调 publish* 系列；
 *   [startDownload] 只负责入队（先取消旧任务再 REPLACE，保证手动重试必然真正开跑）。
 */
object UpdateRepository {

    private const val TAG = "UpdateRepository"

    /** WorkManager 唯一任务名（入队 / 手动取消 / 冷启动恢复共用，必须一致） */
    const val DOWNLOAD_WORK_NAME = "comfychair_update_download"

    /**
     * 更新下载任务 tag（R3）。
     *
     * 与唯一名并存的原因：`getWorkInfosByTag` 能一次拿到「所有尚未结束的更新任务」的
     * 数量，清理判定需要这个数字；唯一名只能取消、数不出来。
     */
    const val UPDATE_WORK_TAG = "comfychair_update_work"

    /** 进度发射的最小间隔（硬性约束 13：300–500ms，取 400ms） */
    internal const val PROGRESS_THROTTLE_MS = 400L

    /** 自动检查更新的最小间隔（v4.9.2：断线重连很频繁，不能每次都打请求） */
    private const val AUTO_CHECK_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * 重试退避基数（R5 第 4 条）。
     *
     * 从 10 秒提到 30 秒：WorkManager 的最小值是 10 秒，但 10 秒退避意味着
     * 「失败 → 10 秒后再撞一次」—— 弱网下就是连续三次无效请求把流量烧掉。
     * 指数退避下三次尝试约在 0s / 30s / 90s 之后发生，足够跳过瞬时抖动。
     */
    private const val BACKOFF_SECONDS = 30L

    private val _state = MutableStateFlow(UpdateState())

    /** UI 观察的唯一入口 */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastPublishAt = 0L

    /** 当前已知的更新信息（AVAILABLE / READY_TO_INSTALL 后有值；重开弹窗时用） */
    @Volatile
    var pendingUpdate: UpdateInfo? = null
        private set

    /**
     * 本进程内是否有一个「用户手动触发、正在跑」的下载会话（R2）。
     *
     * 用途：冷启动恢复时区分「进程重启后残留的旧任务」与「同一进程里 Activity 重建
     * （例如切服务器、登出再登录）时仍在进行的下载」。前者必须取消，后者不能打断。
     * 进程一死这个标记自然消失 —— 这正是 R2 想要的效果。
     */
    @Volatile
    private var downloadSessionActive = false

    /**
     * 下载会话代次。
     *
     * 手动重试会先 `cancelUniqueWork` 再 REPLACE 入队，被取消的旧 Worker 会在
     * `CancellationException` / 失败分支里回调仓库。带代次就能识别「迟到的旧会话」，
     * 不让它把新会话的 DOWNLOADING 状态或 session 标记改掉。
     */
    @Volatile
    private var sessionGeneration = 0L

    // ---------- UI / ViewModel 侧 ----------

    /** 记录检查出的更新（等待用户确认） */
    fun publishAvailable(info: UpdateInfo) {
        pendingUpdate = info
        _state.value = UpdateState(
            phase = UpdatePhase.AVAILABLE,
            totalBytes = if (info.apkSize > 0) info.apkSize else 0L
        )
    }

    /**
     * 用户放弃本次更新（AVAILABLE → IDLE；不清 .part，下次手动下载还能续）。
     *
     * R1：忽略也算「本次主动提示已消费」。配额在弹窗展示时就已经落盘，
     * 所以这里只负责把界面收回 IDLE，不需要再写一次。
     */
    fun reset() {
        _state.value = UpdateState()
        pendingUpdate = null
        downloadSessionActive = false
    }

    // ---------- Worker 侧 ----------

    /**
     * 发布下载进度。
     *
     * @return true 表示这次真的发射了（未被节流、阶段仍是 DOWNLOADING）——
     *         Worker 据此决定要不要同步刷新通知栏，**通知与 UI 共用同一个 400ms 闸门**，
     *         避免出现"UI 已更新、通知还是旧值"或"通知刷得比 UI 还勤"两种偏差。
     */
    internal fun publishProgress(
        downloaded: Long,
        total: Long,
        speed: Long,
        eta: Long,
        force: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishAt < PROGRESS_THROTTLE_MS) return false
        lastPublishAt = now
        val prev = _state.value
        if (prev.phase != UpdatePhase.DOWNLOADING) return false // 阶段已被切换，丢弃迟到进度
        _state.value = UpdateState(
            phase = UpdatePhase.DOWNLOADING,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSec = speed,
            etaSeconds = eta
        )
        return true
    }

    internal fun publishPhase(phase: UpdatePhase) {
        val prev = _state.value
        _state.value = prev.copy(phase = phase)
    }

    /**
     * Worker 每次进入时把状态拉回 DOWNLOADING（A-R1 Critical-1 的状态机收敛）：
     * 自动重试后 phase 可能停在 FAILED，[publishProgress] 的 phase 闸门
     * 会把后续所有进度丢掉 → 通知与 UI 永久停摆。带 .part 已有字节恢复，
     * 用户看到的是"从上次的地方继续"而不是"从 0 开始"。
     *
     * 注意：这**只发生在 Worker 真正开始跑的时候**（用户已手动触发下载），
     * 冷启动不会调用它 —— R2 禁止把残留 .part 恢复成「下载中」。
     */
    internal fun publishResuming(partBytes: Long, totalBytes: Long) {
        _state.value = UpdateState.resuming(partBytes, totalBytes)
    }

    /** 下载完成、校验通过（R4：只差一步安装） */
    internal fun publishReady(finalSize: Long, generation: Long = -1L) {
        if (isStale(generation)) return
        downloadSessionActive = false
        _state.value = UpdateState(
            phase = UpdatePhase.READY_TO_INSTALL,
            downloadedBytes = finalSize,
            totalBytes = finalSize
        )
    }

    /** 最终失败（FAILED 只留给「不会再自动重试」的情况） */
    internal fun publishFailed(message: String, generation: Long = -1L) {
        if (isStale(generation)) return
        downloadSessionActive = false
        val prev = _state.value
        _state.value = UpdateState(
            phase = UpdatePhase.FAILED,
            downloadedBytes = prev.downloadedBytes,
            totalBytes = prev.totalBytes,
            error = message
        )
    }

    /** Worker 被取消时收尾会话标记（代次不符的迟到回调直接忽略） */
    internal fun publishSessionEnded(generation: Long) {
        if (isStale(generation)) return
        downloadSessionActive = false
    }

    /**
     * 本次任务已经过时（目标版本 <= 已安装版本），Worker 提前结束时调用（R3）：
     * 不显示失败横幅，只把「下载中」的假象收掉。
     */
    internal fun publishObsolete() {
        downloadSessionActive = false
        if (_state.value.phase == UpdatePhase.DOWNLOADING ||
            _state.value.phase == UpdatePhase.VERIFYING
        ) {
            _state.value = UpdateState()
            pendingUpdate = null
        }
    }

    /** 迟到的旧会话回调（代次不符）直接丢弃；-1 表示不校验（非 Worker 调用） */
    private fun isStale(generation: Long): Boolean =
        generation >= 0 && generation != sessionGeneration

    // ---------- 入队与恢复 ----------

    /**
     * 入队下载（**唯一**的下载入口：弹窗里的「下载更新」/「重试」，或设置页的下载动作）。
     *
     * R2：自动路径（进 App / 回前台 / 重连）绝不调用本方法。
     *
     * 手动触发必须**必然真正开始一次下载**（A-R1 Important-2）：原来的
     * [ExistingWorkPolicy.KEEP] 在旧任务未 finished（比如上一轮正无限重试）时会
     * **静默丢弃**新请求，但下面已经把 UI 置 DOWNLOADING(0) → 假的"下载中 0MB"。
     * 改为先显式取消旧任务（.part 保留，可续传），再 REPLACE 入队；
     * 双击按钮、弹窗重开产生的重复入队也只是重置同一个任务，不会出现两个下载并行。
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        pendingUpdate = info
        sessionGeneration += 1
        val generation = sessionGeneration
        downloadSessionActive = true

        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .addTag(UPDATE_WORK_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // R5：指数退避 + 30 秒基数，不允许短时间内连续重试
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_URL to info.apkUrl,
                    UpdateDownloadWorker.KEY_SHA256 to info.sha256,
                    UpdateDownloadWorker.KEY_EXPECTED_SIZE to info.apkSize,
                    UpdateDownloadWorker.KEY_VERSION_NAME to info.versionName,
                    // R3：Worker 每次启动都要自查「这个版本是不是已经装上了」
                    UpdateDownloadWorker.KEY_VERSION_CODE to info.versionCode,
                    // 迟到回调的识别依据
                    UpdateDownloadWorker.KEY_SESSION_GENERATION to generation
                )
            )
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        workManager.enqueueUniqueWork(
            DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        val total = if (info.apkSize > 0) info.apkSize else 0L
        _state.value = UpdateState(phase = UpdatePhase.DOWNLOADING, totalBytes = total)
    }

    /**
     * 冷启动 / 强杀后恢复（**R2 + R4**）。
     *
     * 现在只可能恢复出两种状态：
     * - 磁盘上有「完整、校验通过、版本比已安装新」的包 → READY_TO_INSTALL（不重新下载）；
     * - 其余 → IDLE（**绝不**恢复成「下载中」）。
     *
     * 与此同时把残留的下载任务取消、把更新通知撤掉（R3 的同一套收尾）——
     * 用户退出 App 之后 WorkManager 仍可能把旧任务重新拉起来接着下，那就是
     * 「已装好新版本还在烧流量」的直接来源。
     *
     * .part 文件保留不删：它是用户下次手动点「下载更新」时断点续传的素材。
     *
     * @param installedVersionCode 当前已安装的 versionCode（用 PackageManager 读，
     *   不要用 BuildConfig —— 装完新版本后旧进程可能还在跑）
     */
    fun restoreFromDisk(context: Context, installedVersionCode: Int) {
        // 1) 已下载的包能不能直接复用（R4）
        if (restoreLocalPackage(context, installedVersionCode)) {
            // 包已就绪，残留 Worker 只会「把已经装好的版本再下一遍」→ 取消
            cancelUpdateWork(context)
            return
        }

        // 2) 本进程内确实在下载（Activity 重建，不是进程重启）：不打断
        if (downloadSessionActive) return

        // 3) 冷启动：R2 不允许自动续传 → 取消残留任务、清通知、状态回 IDLE
        val cancelled = cancelUpdateWork(context)
        cancelUpdateNotificationsIfShown(context)

        val partExists = UpdateDownloadWorker.partFile(context).exists()
        val restoredPhase = UpdateRestorePolicy.coldStartPhase(
            localPackageReusable = false,
            downloadSessionActive = false,
            partExists = partExists
        )
        if (restoredPhase == UpdatePhase.IDLE) {
            val current = _state.value.phase
            if (current == UpdatePhase.DOWNLOADING || current == UpdatePhase.VERIFYING) {
                _state.value = UpdateState()
                pendingUpdate = null
            }
        }
        if (cancelled > 0) {
            DebugLogger.i(TAG, "冷启动取消残留下载任务 $cancelled 个（R2：不自动续传）")
        }
        if (partExists) {
            DebugLogger.i(TAG, "检测到 update.apk.part：保留作为续传素材，但不自动开始下载")
        }
    }

    /**
     * 本地已下载包的复用检查（R4）。
     *
     * @return true 表示状态已置 READY_TO_INSTALL，调用方**不应**再发起任何下载
     */
    fun restoreLocalPackage(context: Context, installedVersionCode: Int): Boolean {
        val final = UpdateDownloadWorker.finalFile(context)
        if (!final.isFile || final.length() == 0L) {
            // 内存里还停在「待安装」但文件已经不在了（用户清理了外部私有目录 / 已装完
            // 系统删了包）：必须把这条过期的「安装」入口收掉，否则点下去只会静默失败。
            clearReadyStateIfStale()
            return false
        }

        val expectedSha = readTextSidecar(UpdateDownloadWorker.shaFile(context))
        val actualSha = UpdateRestorePolicy.sha256Of(final)
        val verdict = UpdateRestorePolicy.verify(expectedSha, actualSha)
        val packageVersionCode = readVersionCodeSidecar(context)
        // 只有 sha 已通过才值得付出 PackageManager 解析的代价
        val trusted = verdict == UpdateRestorePolicy.Verdict.VERIFIED &&
            AppInstaller.isApkTrusted(context, final)
        val decision = UpdateRestorePolicy.decideLocalPackage(
            shaVerdict = verdict,
            packageVersionCode = packageVersionCode,
            installedVersionCode = installedVersionCode,
            packageTrusted = trusted
        )

        return when (decision) {
            UpdateRestorePolicy.LocalPackageVerdict.REUSE -> {
                DebugLogger.i(
                    TAG,
                    "本地已有可复用安装包（code=$packageVersionCode > $installedVersionCode），直接置待安装"
                )
                _state.value = UpdateState(
                    phase = UpdatePhase.READY_TO_INSTALL,
                    downloadedBytes = final.length(),
                    totalBytes = final.length()
                )
                true
            }
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD -> {
                DebugLogger.w(
                    TAG,
                    "restoreLocalPackage: 丢弃不可复用的安装包" +
                        "（sha=$verdict / code=$packageVersionCode / 已安装=$installedVersionCode / 包名可信=$trusted）"
                )
                discardRestoredApk(context, final)
                // 包已经不可用，"更新已就绪"的通知与界面入口都不能再留着
                UpdateNotifier.cancel(context)
                clearReadyStateIfStale()
                false
            }
        }
    }

    /** 把内存里已失效的 READY_TO_INSTALL 收回 IDLE（文件没了 / 包已不可用） */
    private fun clearReadyStateIfStale() {
        if (_state.value.phase == UpdatePhase.READY_TO_INSTALL) {
            _state.value = UpdateState()
            pendingUpdate = null
        }
    }

    /**
     * R3 的清理入口：清单确认「远端不比本地新」时，取消残留任务 + 清通知。
     *
     * 动作集合由 [UpdateCleanupPolicy] 纯函数决定（单测覆盖）；
     * 这里只负责把动作落到 WorkManager / NotificationManager 上。
     */
    internal fun clearResidualUpdate(
        context: Context,
        latestVersionCode: Int,
        installedVersionCode: Int
    ) {
        val actions = UpdateCleanupPolicy.actionsFor(
            latestVersionCode = latestVersionCode,
            installedVersionCode = installedVersionCode,
            pendingWorkCount = pendingUpdateWorkCount(context),
            updateNotificationsShown = updateNotificationsShown(context)
        )
        if (actions.isEmpty()) return
        DebugLogger.i(
            TAG,
            "已是新版本（远端=$latestVersionCode 本地=$installedVersionCode），清理残留：$actions"
        )
        if (UpdateCleanupPolicy.Action.CANCEL_PENDING_WORK in actions) {
            cancelUpdateWork(context)
            downloadSessionActive = false
            if (_state.value.phase == UpdatePhase.DOWNLOADING ||
                _state.value.phase == UpdatePhase.VERIFYING
            ) {
                _state.value = UpdateState()
                pendingUpdate = null
            }
        }
        if (UpdateCleanupPolicy.Action.CLEAR_UPDATE_NOTIFICATIONS in actions) {
            UpdateNotifier.cancel(context)
        }
    }

    /** 取消所有更新下载任务（按 tag + 唯一名双保险，兼容没打 tag 的旧任务） */
    private fun cancelUpdateWork(context: Context): Int {
        return try {
            val manager = WorkManager.getInstance(context)
            val pending = manager.getWorkInfosByTag(UPDATE_WORK_TAG).get()
                .count { !it.state.isFinished }
            manager.cancelAllWorkByTag(UPDATE_WORK_TAG)
            manager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
            pending
        } catch (e: Exception) {
            DebugLogger.w(TAG, "取消更新任务失败：${e.javaClass.simpleName} ${e.message}")
            0
        }
    }

    /** 尚未结束的更新任务数（清理判定的输入） */
    private fun pendingUpdateWorkCount(context: Context): Int = try {
        WorkManager.getInstance(context)
            .getWorkInfosByTag(UPDATE_WORK_TAG).get()
            .count { !it.state.isFinished }
    } catch (_: Exception) {
        0
    }

    /**
     * 当前是否有更新相关的通知在显示。
     *
     * 查不到就按「有」处理：清通知是无害动作，漏清反而留下「安装进程挂起」的观感。
     */
    private fun updateNotificationsShown(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.any {
            it.id == UpdateNotifier.PROGRESS_NOTIFICATION_ID ||
                it.id == UpdateNotifier.RESULT_NOTIFICATION_ID
        }
    } catch (_: Exception) {
        true
    }

    /** 清更新通知（仅当确实有通知挂着时） */
    private fun cancelUpdateNotificationsIfShown(context: Context) {
        if (updateNotificationsShown(context)) UpdateNotifier.cancel(context)
    }

    /** 复验/复用失败的 update.apk：连同 sha256 与 versionCode 旁车一起删掉 */
    private fun discardRestoredApk(context: Context, apk: File) {
        apk.delete()
        UpdateDownloadWorker.shaFile(context).delete()
        UpdateDownloadWorker.versionFile(context).delete()
    }

    /** 读取文本旁车（缺失/读取失败返回 null → 按不可复验处理） */
    private fun readTextSidecar(file: File): String? = try {
        if (file.isFile) file.readText().trim() else null
    } catch (_: Exception) {
        null
    }

    /** 读取与安装包一起落盘的 versionCode 旁车 */
    private fun readVersionCodeSidecar(context: Context): Int? =
        readTextSidecar(UpdateDownloadWorker.versionFile(context))?.toIntOrNull()?.takeIf { it > 0 }

    /** 安装完成后清理（磁盘上不留 160MB 的旧安装包） */
    fun clearDownloadedFiles(context: Context) {
        UpdateDownloadWorker.finalFile(context).delete()
        UpdateDownloadWorker.partFile(context).delete()
        UpdateDownloadWorker.shaFile(context).delete()
        UpdateDownloadWorker.versionFile(context).delete()
        _state.value = UpdateState()
        pendingUpdate = null
        downloadSessionActive = false
        UpdateNotifier.cancel(context)
    }

    // ---------- 自动检查（v4.9.2 / v4.18.0 R1+R3）----------

    /** 上次自动检查的时间戳（毫秒）；0 = 本次进程还没查过 */
    private var lastAutoCheckAt = 0L

    /**
     * 自动检查更新是否被离线态拦下（P2-10）。
     *
     * 用 [ConnectionManager.isOffline]（**实际状态**：用户强制离线或网络断开自动切换都算）
     * 而不是设置项：只要当前处于离线态，就不该再向更新服务器发请求。
     */
    internal fun isOfflineBlocked(): Boolean = ConnectionManager.isOffline

    /**
     * 回前台 / 重连后自动检查更新（节流 30 分钟）。
     *
     * 与 v4.9.2 的区别（v4.18.0）：
     * - **只检查、不下载**（R2）：本方法里没有任何 enqueue；下载只能由用户点按钮触发；
     * - **一个版本只主动弹一次**（R1）：弹之前先比 [UpdatePromptStore] 里的配额；
     * - **已是最新就清理**（R3）：清单确认远端不比本地新 → 取消残留下载任务 + 清通知。
     *
     * 三条旧约束保留：
     * 1. **节流**：断线重连可能很频繁，不能每次都打请求（30 分钟内只查一次）；
     * 2. **不打断进行中的流程**：正在下载/校验/已下好待安装时直接返回；
     * 3. **阻塞调用**：内部走同步网络请求，**必须在 IO 线程调用**；
     * 4. **离线不联网**（P2-10）：离线态直接返回，不发任何请求。
     */
    fun autoCheckIfDue(context: Context, currentVersionCode: Int) {
        // P2-10：用户强制离线（或网络已断进入离线态）时不得访问更新服务器。
        if (isOfflineBlocked()) return

        val now = System.currentTimeMillis()
        if (now - lastAutoCheckAt < AUTO_CHECK_INTERVAL_MS) return

        val phase = _state.value.phase
        if (phase == UpdatePhase.DOWNLOADING ||
            phase == UpdatePhase.VERIFYING ||
            phase == UpdatePhase.READY_TO_INSTALL
        ) {
            return
        }

        lastAutoCheckAt = now
        when (val result = UpdateChecker.checkDetailed(currentVersionCode, manualCheck = false)) {
            is UpdateChecker.CheckResult.Available -> {
                val lastPrompted = UpdatePromptStore.lastPromptedVersionCode(context)
                val decision = UpdatePromptPolicy.decide(
                    manual = false,
                    latestCode = result.info.versionCode,
                    lastPromptedCode = lastPrompted
                )
                if (decision == UpdatePromptPolicy.PromptDecision.PROMPT) {
                    // 先落盘再弹：哪怕用户立刻杀掉进程，这个版本也算「已主动提示过」
                    UpdatePromptStore.markPrompted(context, result.info.versionCode)
                    publishAvailable(result.info)
                } else {
                    DebugLogger.i(
                        TAG,
                        "自动检查：版本 ${result.info.versionCode} 已在 $lastPrompted 主动提示过，静默跳过"
                    )
                }
            }
            // R3：确认「不需要更新」才清理（检查失败不清理，避免误杀进行中的下载）
            is UpdateChecker.CheckResult.UpToDate ->
                clearResidualUpdate(context, result.remoteVersionCode, currentVersionCode)
            is UpdateChecker.CheckResult.Failed -> Unit
        }
    }

    /**
     * 手动检查更新（用户点「检查更新」）—— R1（不受配额限制）+ R3 + R4 的统一入口。
     *
     * 顺序：
     * 1. **先看磁盘**：本地已有校验通过且版本更高的包 → 直接 ReadyToInstall，不联网；
     * 2. 再拉清单比对；
     * 3. 需要更新 → 记录提示配额并发布 AVAILABLE（手动永远弹）；
     * 4. 已是最新 → 清理残留任务 / 通知，返回 [ManualCheckResult.AlreadyLatest]。
     *
     * **阻塞调用：必须在 IO 线程执行。**
     */
    fun manualCheck(context: Context, currentVersionCode: Int): ManualCheckResult {
        // R4：已下载好的包优先复用，哪怕断网也能把「安装」给出来
        if (restoreLocalPackage(context, currentVersionCode)) {
            return ManualCheckResult.ReadyToInstall
        }

        return when (val result = UpdateChecker.checkDetailed(currentVersionCode, manualCheck = true)) {
            is UpdateChecker.CheckResult.Available -> {
                // 手动检查也记录配额：用户已经看过这个版本，之后进 App 不必再主动弹一次
                UpdatePromptStore.markPrompted(context, result.info.versionCode)
                publishAvailable(result.info)
                ManualCheckResult.UpdateAvailable(result.info)
            }
            is UpdateChecker.CheckResult.UpToDate -> {
                clearResidualUpdate(context, result.remoteVersionCode, currentVersionCode)
                ManualCheckResult.AlreadyLatest
            }
            is UpdateChecker.CheckResult.Failed -> ManualCheckResult.Failed(result.message)
        }
    }

    /** 手动检查的结论（设置页据此给提示 / 打开弹窗） */
    sealed class ManualCheckResult {
        /** 有更新，弹窗已发布 */
        data class UpdateAvailable(val info: UpdateInfo) : ManualCheckResult()

        /** 本地已有可复用的安装包，弹窗应直接展示「安装」 */
        object ReadyToInstall : ManualCheckResult()

        /** 确认已是最新 */
        object AlreadyLatest : ManualCheckResult()

        /** 检查失败（未配置 / 网络 / 清单非法），[message] 为原因 */
        data class Failed(val message: String) : ManualCheckResult()
    }
}