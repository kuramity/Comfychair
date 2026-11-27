package sh.hnet.comfychair.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.notify.GenerationNotifier
import sh.hnet.comfychair.platform.AppLifecycleMonitor
import sh.hnet.comfychair.platform.BackgroundDegradationPolicy
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.repository.awaitCallbackOnce
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import java.util.concurrent.TimeUnit

/**
 * 蜂窝降级下的队列轮询 Worker（v4.18.x 后台省流量，任务例外路径；P4 可靠性修复）。
 *
 * 触发场景（决策表）：降级态 × METERED × 「后台保持连接」关 × **有本机任务在跑** →
 * WS 断开（进度实时链路牺牲），改由本 Worker 每 15 分钟拉一次 `/queue` 对账，
 * 任务结束时补发系统通知 —— 进度收不到，**完成必须知道**（体验底线）。
 *
 * P4 修的四件事（对应执行书 §六 B）：
 * 1. **无 UI 建连**：进程被 WorkManager 拉起时 [ConnectionManager.clientOrNull] 为空，
 *    原来直接 `Result.success()` 空转。现在按已保存的服务器/凭据临时建一个只用于
 *    拉 /queue、/history 的客户端（不碰 ConnectionManager 的状态，也不打开 WS），
 *    用完 shutdown；拿不到就 `Result.retry()`。
 * 2. **恢复注册表**：Worker 开始先 [JobRegistry.restoreState]（Application.onCreate
 *    也会恢复一次，这里是幂等保险）。
 * 3. **先查后删**：不再让 [JobRegistry.updateFromServerQueue] 先删任务、后查 /history。
 *    现在只查「不在 /queue 里」的活跃任务，查不到（历史还没落盘 / 网络失败）就
 *    **保留任务**，下一轮重试 —— 原来一次查询失败就永久丢通知。
 * 4. **不空转/不误报**：/queue 失败返回 `Result.retry()`；状态最多重试
 *    [MAX_COMPLETION_CONFIRM_ATTEMPTS] 轮，仍无法确认时发「结果待确认」而不是
 *    谎报成功；没有活跃任务才自愈取消周期任务。
 *
 * 生命周期约定：
 * - 由 [sh.hnet.comfychair.lifecycle.BackgroundDegradationCoordinator] 在策略进入
 *   [BackgroundDegradationPolicy.WsPolicy.DROP_AND_POLL] 时 [schedule]，离开时 [cancel]；
 * - **自愈**：轮询发现本机活跃任务归零（全部结束）后自行取消；
 * - 网络约束 CONNECTED：没网时不白跑（系统在恢复联网后才调度）。
 */
class BackgroundQueuePollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BgQueuePoll"

        /** 唯一周期任务名（挂起/恢复都按它定位） */
        const val UNIQUE_WORK_NAME = "background_queue_poll"

        /** 重试计数持久化（跨进程存活，进程被杀后下一轮仍知道已经试了几次） */
        private const val PREFS_NAME = "BgQueuePollPrefs"
        private const val KEY_ATTEMPT_PREFIX = "confirm_attempts_"

        /**
         * 完成状态最多确认多少轮。15 分钟 × 8 ≈ 2 小时：
         * 足够覆盖「服务端写完 history 稍晚」与短时网络抖动；
         * 超过仍查不到时发「结果待确认」，避免无限轮询与谎报结果。
         */
        private const val MAX_COMPLETION_CONFIRM_ATTEMPTS = 8

        /** /queue 或 /history 网络失败后的重试退避（周期任务本身仍是 15 分钟一轮） */
        private const val RETRY_BACKOFF_MINUTES = 5L

        /** 排上周期轮询（幂等；KEEP：已排上时不重置周期） */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundQueuePollWorker>(
                BackgroundDegradationPolicy.QUEUE_POLL_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                // 失败重试走 WorkManager 退避（默认 30 秒太密，改成 5 分钟）。
                // 周期任务仍是 15 分钟一轮；退避只影响「本轮失败」的补跑。
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            DebugLogger.i(TAG, "已排周期轮询（${BackgroundDegradationPolicy.QUEUE_POLL_INTERVAL_MINUTES} 分钟）")
        }

        /** 取消周期轮询（回前台 / 无任务静默降级 / 自愈时调用；未排上时是 no-op） */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private val prefs by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result {
        // 防御：回前台瞬间与取消指令的竞态 —— 前台有自己的 WS 实时链路，不重复拉
        if (AppLifecycleMonitor.isForeground()) {
            return Result.success()
        }

        // P4-2：进程可能是被 WorkManager 拉起的，注册表先恢复（幂等）。
        // 只在内存表为空时恢复：活进程里 Application.onCreate 已恢复过，重复 restore
        // 会拿磁盘旧快照覆盖内存（刚 registerJob、apply 尚未落盘的那几毫秒）。
        if (JobRegistry.queueState.value.ownJobs.isEmpty()) {
            JobRegistry.restoreState(applicationContext)
            DebugLogger.i(TAG, "轮询恢复注册表：${JobRegistry.queueState.value.ownJobs.size} 条")
        }

        // 拉取前的活跃任务快照：完成检测的对照面
        val beforeActive = JobRegistry.queueState.value.ownJobs.values.filter {
            it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
        }
        if (beforeActive.isEmpty()) {
            // 没有自己活跃的任务（状态已被别处收尾）：自愈退出
            clearAllAttempts()
            cancel(applicationContext)
            return Result.success()
        }

        // 用户显式「强制离线」时不联网：不发请求、也不取消任务（关掉离线后会继续）
        if (AppSettings.isOfflineMode(applicationContext)) {
            DebugLogger.i(TAG, "强制离线中，跳过本轮轮询")
            return Result.success()
        }

        // P4-1：无 UI 建连。优先复用已存在的 ConnectionManager 客户端；
        // 没有（进程刚被拉起）就按持久化会话临时建一个，避免空转。
        val sharedClient = ConnectionManager.clientOrNull
        val ephemeralClient = if (sharedClient == null) createBackgroundClient() else null
        val client = sharedClient ?: ephemeralClient ?: run {
            DebugLogger.w(TAG, "无 UI 建连失败（没有可用的服务器/凭据），本轮重试")
            return Result.retry()
        }
        try {
            return pollWithClient(client)
        } finally {
            // 临时客户端用完即关（不打开 WS；httpClient 线程按 OkHttp 空闲回收）
            ephemeralClient?.shutdown()
        }
    }

    /**
     * 真正的一轮对账（拆出来只为 try/finally 管临时客户端生命周期）。
     *
     * 顺序是 P4 的关键：**先判完成、再清理队列**。判定不了的任务原样保留，
     * 下一轮（或退避重试）继续查 /history。
     */
    private suspend fun pollWithClient(client: ComfyUIClient): Result {
        // P1-5：先取登记水位再发请求（与 pollQueueStatus 同口径），防止在飞期间
        // 刚登记的任务被对账误删
        val snapshotSeq = JobRegistry.beginQueueSnapshot()
        val queueJson = awaitCallbackOnce<JSONObject?> { deliver ->
            client.fetchQueue { queue -> deliver(queue) }
        } ?: run {
            // 网络失败：交给 WorkManager 退避重试，任务一个都不动
            DebugLogger.w(TAG, "/queue 拉取失败，等待重试")
            return Result.retry()
        }

        val running = queueJson.optJSONArray("queue_running")
        val pending = queueJson.optJSONArray("queue_pending")
        val serverPromptIds = promptIdsInQueue(running, pending)

        val beforeActive = JobRegistry.queueState.value.ownJobs.values.filter {
            it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
        }
        val finishedCandidates = beforeActive.filter { it.promptId !in serverPromptIds }
        if (finishedCandidates.isEmpty()) {
            // 所有本机活跃任务都还在服务器队列里：仅同步队列视图
            JobRegistry.updateFromServerQueue(running, pending, snapshotSeq)
            if (JobRegistry.queueState.value.ownActiveJobCount == 0) cancel(applicationContext)
            return Result.success()
        }

        var needsRetry = false
        var resolvedAny = false
        var unresolved = 0
        for (job in finishedCandidates) {
            val history = fetchHistoryStatus(client, job.promptId)
            when (history.status) {
                PromptStatus.SUCCESS -> {
                    DebugLogger.i(TAG, "确认完成: ${Obfuscator.promptId(job.promptId)}")
                    GenerationNotifier.onGenerationCompleted(applicationContext, job.ownerId)
                    JobRegistry.markCompleted(job.promptId)
                    clearAttempts(job.promptId)
                    resolvedAny = true
                }
                PromptStatus.ERROR -> {
                    DebugLogger.i(TAG, "确认失败: ${Obfuscator.promptId(job.promptId)}")
                    GenerationNotifier.onGenerationFailed(applicationContext, job.ownerId, null)
                    JobRegistry.markFailed(job.promptId)
                    clearAttempts(job.promptId)
                    resolvedAny = true
                }
                PromptStatus.UNKNOWN -> {
                    if (!history.networkOk) {
                        // 网络失败不算「确认次数」，任务原样保留，交给退避重试
                        DebugLogger.w(
                            TAG,
                            "任务 ${Obfuscator.promptId(job.promptId)} 的 /history 查询失败，保留待重试"
                        )
                        needsRetry = true
                        unresolved++
                        continue
                    }
                    val attempts = incrementAttempts(job.promptId)
                    if (attempts >= MAX_COMPLETION_CONFIRM_ATTEMPTS) {
                        // 不谎报结果：只提示「待确认」，然后停止跟踪该任务，避免无限轮询
                        DebugLogger.w(
                            TAG,
                            "任务 ${Obfuscator.promptId(job.promptId)} 连续 $attempts 轮状态未知，" +
                                "发「结果待确认」并停止跟踪"
                        )
                        GenerationNotifier.onPendingConfirmation(applicationContext, job.ownerId)
                        JobRegistry.removeJob(job.promptId)
                        clearAttempts(job.promptId)
                        resolvedAny = true
                    } else {
                        DebugLogger.w(
                            TAG,
                            "完成待确认（第 $attempts 轮），保留任务下一轮重试: " +
                                Obfuscator.promptId(job.promptId)
                        )
                        unresolved++
                    }
                }
            }
        }

        // P4-3：有未判定的任务时**不**做队列对账删除，否则 updateFromServerQueue 会把它
        // 从 ownJobs 里删掉，下一轮再也没有它的对照面（这就是原来「一次失败永久丢」的根因）。
        if (unresolved == 0) {
            JobRegistry.updateFromServerQueue(running, pending, snapshotSeq)
        }

        if (resolvedAny) {
            DebugLogger.i(TAG, "轮询确认了 ${finishedCandidates.size - unresolved} 个任务结束，补发通知并刷新图库")
            // 相当于"收到任务完成事件"：图库补拉一次（受限 max_items，决策 2）
            GalleryRepository.getInstance().refresh()
        }

        // 全部结束：自愈退出，不留空转的周期任务
        if (JobRegistry.queueState.value.ownActiveJobCount == 0 && unresolved == 0) {
            clearAllAttempts()
            cancel(applicationContext)
        }
        return if (needsRetry) Result.retry() else Result.success()
    }

    // ---------- 无 UI 建连 ----------

    /**
     * 按持久化的「已选服务器 + 凭据」建一个临时客户端（P4-1）。
     *
     * **刻意不调用 [ConnectionManager.connect]**：那会把全局连接状态改成 Connected、
     * 拉 /object_info 与模型列表，并可能与连接层的离线兜底/弹窗逻辑互相干扰。
     * 这里只需要一个能发 /queue、/history GET 的客户端，用完即弃。
     *
     * 协议口径与正常连接一致：用户显式选择优先，否则 HTTPS（不做明文降级）。
     * 凭据来自加密存储；BROWSER 认证的 Cookie 过期时请求会失败，走 Result.retry。
     */
    private fun createBackgroundClient(): ComfyUIClient? {
        val server = ServerStorage(applicationContext).getSelectedServer() ?: run {
            DebugLogger.w(TAG, "无 UI 建连：没有已选服务器")
            return null
        }
        val credentials = CredentialStorage(applicationContext)
            .getCredentials(server.id, server.authType)
        val protocol = backgroundProtocolOf(server)
        return ComfyUIClient(
            context = applicationContext,
            rawHostname = server.hostname,
            port = server.port,
            credentials = credentials,
            serverId = server.id,
            preferredProtocol = protocol
        ).apply {
            // getBaseUrl 依赖 workingProtocol（构造函数只记录显式协议，不自动设置）
            setWorkingProtocol(protocol)
        }
    }

    /**
     * 后台建连的协议口径（与 LoginScreen / ComfyUIClient 的探测规则保持一致）：
     * 用户显式选择 > 地址里的协议前缀 > HTTPS（不做明文降级）。
     */
    private fun backgroundProtocolOf(server: sh.hnet.comfychair.model.Server): String {
        server.protocol?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?.let { return it }
        val host = server.hostname.trim()
        return when {
            host.startsWith("http://", ignoreCase = true) -> "http"
            host.startsWith("https://", ignoreCase = true) -> "https"
            else -> "https"
        }
    }

    // ---------- /queue、/history 解析 ----------

    private fun promptIdsInQueue(
        running: org.json.JSONArray?,
        pending: org.json.JSONArray?,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        for (arr in listOfNotNull(running, pending)) {
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.optString(1)?.takeIf { it.isNotEmpty() }?.let(ids::add)
            }
        }
        return ids
    }

    /** /history/{promptId} 的 status_str 分类（纯对账：查不到返回 [PromptStatus.UNKNOWN]） */
    private enum class PromptStatus { SUCCESS, ERROR, UNKNOWN }

    /**
     * @param networkOk false = 请求本身失败（网络/服务端 5xx/解析失败），
     *   true = 拿到了历史响应但没有该任务或状态不可识别
     */
    private data class HistoryStatus(val status: PromptStatus, val networkOk: Boolean)

    private suspend fun fetchHistoryStatus(client: ComfyUIClient, promptId: String): HistoryStatus {
        val history = awaitCallbackOnce<JSONObject?> { deliver ->
            client.fetchHistory(promptId) { deliver(it) }
        } ?: return HistoryStatus(PromptStatus.UNKNOWN, networkOk = false)
        val entry = history.optJSONObject(promptId)
            ?: return HistoryStatus(PromptStatus.UNKNOWN, networkOk = true)
        val statusStr = entry.optJSONObject("status")?.optString("status_str")
            ?: return HistoryStatus(PromptStatus.UNKNOWN, networkOk = true)
        return when (statusStr) {
            "success" -> HistoryStatus(PromptStatus.SUCCESS, networkOk = true)
            "error" -> HistoryStatus(PromptStatus.ERROR, networkOk = true)
            else -> HistoryStatus(PromptStatus.UNKNOWN, networkOk = true)
        }
    }

    // ---------- 待确认计数（持久化） ----------

    private fun incrementAttempts(promptId: String): Int {
        val next = prefs.getInt(KEY_ATTEMPT_PREFIX + promptId, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPT_PREFIX + promptId, next).apply()
        return next
    }

    private fun clearAttempts(promptId: String) {
        prefs.edit().remove(KEY_ATTEMPT_PREFIX + promptId).apply()
    }

    private fun clearAllAttempts() {
        prefs.edit().clear().apply()
    }
}