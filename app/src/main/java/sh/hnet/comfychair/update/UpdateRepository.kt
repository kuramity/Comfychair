package sh.hnet.comfychair.update

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
import java.util.concurrent.TimeUnit

/**
 * 更新流程的状态仓库（v4.6.0）—— 单例 + StateFlow，Worker 与 Compose UI 之间的唯一桥梁。
 *
 * 生命周期关系（硬性约束 10/11/16 的落地）：
 * - **UI 只是观察者**：Dialog 的 onDismiss 只把 `showDialog=false`，不碰这里的任何方法 ——
 *   Worker 继续在 WorkManager 里跑，重新打开弹窗进度还在。
 * - **Worker 是唯一写入方**：只有 [UpdateDownloadWorker] 调 publish* 系列；
 *   [startDownload] 只负责入队（先取消旧任务再 REPLACE，保证手动重试必然真正开跑）。
 * - **重启恢复**：[restoreFromDisk] 在 App 冷启动时调用 —— 检查 .part 与 WorkManager
 *   队列，把状态恢复成"下载中（已续到 N 字节）"，绝不清文件、绝不从头下。
 *
 * 节流（硬性约束 13）：[publishProgress] 距上次发射 ≥[PROGRESS_THROTTLE_MS] 才真正
 * 更新 StateFlow；[force=true] 的收尾/阶段切换不受节流限制。
 */
object UpdateRepository {

    /** WorkManager 唯一任务名（入队 / 手动取消 / 冷启动恢复共用，必须一致） */
    const val DOWNLOAD_WORK_NAME = "comfychair_update_download"

    /** 进度发射的最小间隔（硬性约束 13：300–500ms，取 400ms） */
    internal const val PROGRESS_THROTTLE_MS = 400L

    /** 自动检查更新的最小间隔（v4.9.2：断线重连很频繁，不能每次都打请求） */
    private const val AUTO_CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private val _state = MutableStateFlow(UpdateState())

    /** UI 观察的唯一入口 */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastPublishAt = 0L

    /** 当前已知的更新信息（AVAILABLE 后有值；重开弹窗时用） */
    @Volatile
    var pendingUpdate: UpdateInfo? = null
        private set

    // ---------- UI / ViewModel 侧 ----------

    /** 记录检查出的更新（等待用户确认） */
    fun publishAvailable(info: UpdateInfo) {
        pendingUpdate = info
        _state.value = UpdateState(
            phase = UpdatePhase.AVAILABLE,
            totalBytes = if (info.apkSize > 0) info.apkSize else 0L
        )
    }

    /** 用户放弃本次更新（AVAILABLE → IDLE；不清 .part，下次检查还能续） */
    fun reset() {
        _state.value = UpdateState()
        pendingUpdate = null
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
     * 自动重试 / 进程重启后 phase 可能停在 FAILED，[publishProgress] 的 phase 闸门
     * 会把后续所有进度丢掉 → 通知与 UI 永久停摆。带 .part 已有字节恢复，
     * 用户看到的是"从上次的地方继续"而不是"从 0 开始"。
     */
    internal fun publishResuming(partBytes: Long, totalBytes: Long) {
        _state.value = UpdateState.resuming(partBytes, totalBytes)
    }

    internal fun publishReady(finalSize: Long) {
        _state.value = UpdateState(
            phase = UpdatePhase.READY_TO_INSTALL,
            downloadedBytes = finalSize,
            totalBytes = finalSize
        )
    }

    internal fun publishFailed(message: String) {
        val prev = _state.value
        _state.value = UpdateState(
            phase = UpdatePhase.FAILED,
            downloadedBytes = prev.downloadedBytes,
            totalBytes = prev.totalBytes,
            error = message
        )
    }

    // ---------- 入队与恢复 ----------

    /**
     * 入队下载（用户点"立即更新"或失败后点"重试"）。
     *
     * 手动触发必须**必然真正开始一次下载**（A-R1 Important-2）：原来的
     * [ExistingWorkPolicy.KEEP] 在旧任务未 finished（比如上一轮正无限重试）时会
     * **静默丢弃**新请求，但下面已经把 UI 置 DOWNLOADING(0) → 假的"下载中 0MB"。
     * 改为先显式取消旧任务（.part 保留，可续传），再 REPLACE 入队；
     * 双击按钮、弹窗重开产生的重复入队也只是重置同一个任务，不会出现两个下载并行。
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        pendingUpdate = info
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_URL to info.apkUrl,
                    UpdateDownloadWorker.KEY_SHA256 to info.sha256,
                    UpdateDownloadWorker.KEY_EXPECTED_SIZE to info.apkSize,
                    UpdateDownloadWorker.KEY_VERSION_NAME to info.versionName
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
     * App 冷启动 / 强杀后恢复（硬性约束 16）：
     * 1. WorkManager 里还有这个任务（在跑或排队）→ 若 UI 状态丢了，从 .part 恢复"下载中"；
     * 2. 完整 update.apk 已存在（上次校验过了但用户没装）→ 直接 READY_TO_INSTALL；
     * 3. .part 存在但任务没了（下载完成前被杀且 WorkManager 没恢复）→ 保留 .part，
     *    状态回 AVAILABLE（用户再点"更新"时 Worker 会从 .part 续传，不会从头下）。
     *
     * 任何分支都不删除 .part / update.apk —— 文件只由 Worker 生命周期管理。
     */
    fun restoreFromDisk(context: Context) {
        val part = UpdateDownloadWorker.partFile(context)
        val final = UpdateDownloadWorker.finalFile(context)
        val workInfo = try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME).get()
        } catch (_: Exception) {
            null
        }
        val workerActive = workInfo?.any { !it.state.isFinished } == true

        when {
            final.exists() && final.length() > 0 -> {
                _state.value = UpdateState(
                    phase = UpdatePhase.READY_TO_INSTALL,
                    downloadedBytes = final.length(),
                    totalBytes = final.length()
                )
            }
            workerActive -> {
                val total = pendingUpdate?.apkSize ?: _state.value.totalBytes
                if (part.exists()) {
                    _state.value = UpdateState.resuming(part.length(), total)
                } else {
                    _state.value = UpdateState(phase = UpdatePhase.DOWNLOADING, totalBytes = total)
                }
            }
            // 任务不在但 .part 还在：不删（续传价值），等用户下次点更新时接着下
        }
    }

    /** 安装完成后清理（磁盘上不留 153MB 的旧安装包） */
    fun clearDownloadedFiles(context: Context) {
        UpdateDownloadWorker.finalFile(context).delete()
        UpdateDownloadWorker.partFile(context).delete()
        _state.value = UpdateState()
        pendingUpdate = null
    }

    // ---------- 自动检查（v4.9.2）----------

    /** 上次自动检查的时间戳（毫秒）；0 = 本次进程还没查过 */
    private var lastAutoCheckAt = 0L

    /**
     * 回前台 / 重连后自动检查更新（节流 30 分钟）。
     *
     * **为什么需要它**：原先只有 `MainContainerActivity.onCreate` 查一次 —— 用户重连、
     * 长时间挂后台再回来，都不会重新检查，必须手动去设置页点"检查更新"才有反应。
     *
     * 三条约束：
     * 1. **节流**：断线重连可能很频繁，不能每次都打请求（30 分钟内只查一次）；
     * 2. **不打断进行中的流程**：正在下载/校验/已下好待安装时直接返回 ——
     *    尤其 `READY_TO_INSTALL` 时再 publishAvailable 会把界面打回 AVAILABLE，
     *    让用户以为要重下 150MB；
     * 3. **阻塞调用**：内部走 `checkForUpdate` 同步网络请求，**必须在 IO 线程调用**。
     *
     * 发现新版本时只 `publishAvailable`（弹窗等用户确认），不自动下载。
     */
    fun autoCheckIfDue(currentVersionCode: Int) {
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
        val info = UpdateChecker.checkForUpdate(currentVersionCode, manualCheck = false)
        if (info != null) {
            publishAvailable(info)
        }
    }
}
