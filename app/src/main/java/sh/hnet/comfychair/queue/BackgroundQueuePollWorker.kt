package sh.hnet.comfychair.queue

import android.content.Context
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
import sh.hnet.comfychair.lifecycle.AppLifecycleMonitor
import sh.hnet.comfychair.lifecycle.BackgroundDegradationPolicy
import sh.hnet.comfychair.notify.GenerationNotifier
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.repository.awaitCallbackOnce
import sh.hnet.comfychair.util.DebugLogger
import java.util.concurrent.TimeUnit

/**
 * 蜂窝降级下的队列轮询 Worker（v4.18.x 后台省流量，任务例外路径）。
 *
 * 触发场景（决策表）：降级态 × METERED × 「后台保持连接」关 × **有本机任务在跑** →
 * WS 断开（进度实时链路牺牲），改由本 Worker 每 15 分钟拉一次 `/queue` 对账，
 * 任务结束时补发系统通知 —— 进度收不到，**完成必须知道**（体验底线）。
 *
 * 生命周期约定：
 * - 由 [BackgroundDegradationCoordinator] 在策略进入 [BackgroundDegradationPolicy.WsPolicy.DROP_AND_POLL]
 *   时 [schedule]，离开时 [cancel]；
 * - **自愈**：轮询发现本机活跃任务归零（全部结束）后自行取消 —— 否则会在没有任务
 *   的日子里每 15 分钟空拉一次 /queue；
 * - 网络约束 CONNECTED：没网时不白跑（系统在恢复联网后才调度）。
 *
 * 复用约束：走 [ConnectionManager.pollQueueStatus] 同一套对账口径（先取登记水位
 * [JobRegistry.beginQueueSnapshot] 再发请求，P1-5），通知复用 [GenerationNotifier]。
 */
class BackgroundQueuePollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BgQueuePoll"

        /** 唯一周期任务名（挂起/恢复都按它定位） */
        const val UNIQUE_WORK_NAME = "background_queue_poll"

        /** 排上周期轮询（幂等；KEEP：已排上时不重置周期） */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundQueuePollWorker>(
                BackgroundDegradationPolicy.QUEUE_POLL_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
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

    override suspend fun doWork(): Result {
        // 防御：回前台瞬间与取消指令的竞态 —— 前台有自己的 WS 实时链路，不重复拉
        if (AppLifecycleMonitor.isForeground()) {
            return Result.success()
        }
        val client = ConnectionManager.clientOrNull ?: return Result.success()

        // 拉取前的活跃任务快照：完成检测的对照面
        val beforeActive = JobRegistry.queueState.value.ownJobs.values.filter {
            it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
        }
        if (beforeActive.isEmpty()) {
            // 没有自己活跃的任务（状态已被别处收尾）：自愈退出
            cancel(applicationContext)
            return Result.success()
        }

        // P1-5：先取登记水位再发请求（与 pollQueueStatus 同口径），防止在飞期间
        // 刚登记的任务被对账误删
        val snapshotSeq = JobRegistry.beginQueueSnapshot()
        val queueJson = awaitCallbackOnce<JSONObject?> { deliver ->
            client.fetchQueue { queue -> deliver(queue) }
        } ?: run {
            // 网络失败：下一轮再试（WorkManager 周期任务天然重试）
            DebugLogger.w(TAG, "/queue 拉取失败，等待下一轮")
            return Result.success()
        }
        JobRegistry.updateFromServerQueue(
            queueJson.optJSONArray("queue_running"),
            queueJson.optJSONArray("queue_pending"),
            snapshotSeq
        )

        val after = JobRegistry.queueState.value
        val finished = beforeActive.filter { job ->
            val now = after.ownJobs[job.promptId]
            now == null || (now.status != JobStatus.PENDING && now.status != JobStatus.EXECUTING)
        }
        for (job in finished) {
            // 轮询只知道"不在队列里了"，成败要用 /history/{id} 的 status 补判，
            // 避免对失败任务谎报"已完成"（查不到时宁可不发，不误报）
            when (promptStatusOf(client, job.promptId)) {
                PromptStatus.ERROR ->
                    GenerationNotifier.onGenerationFailed(applicationContext, job.ownerId, null)
                PromptStatus.SUCCESS ->
                    GenerationNotifier.onGenerationCompleted(applicationContext, job.ownerId)
                PromptStatus.UNKNOWN -> DebugLogger.w(
                    TAG, "任务 ${job.promptId} 已结束但状态查不到，不补发通知"
                )
            }
        }
        if (finished.isNotEmpty()) {
            DebugLogger.i(TAG, "轮询发现 ${finished.size} 个任务结束，补发通知并刷新图库")
            // 相当于"收到任务完成事件"：图库补拉一次（受限 max_items，决策 2）
            GalleryRepository.getInstance().refresh()
        }

        // 全部结束：自愈退出，不留空转的周期任务
        if (after.ownActiveJobCount == 0) {
            cancel(applicationContext)
        }
        return Result.success()
    }

    /** /history/{promptId} 的 status_str 分类（纯对账：查不到返回 [PromptStatus.UNKNOWN]） */
    private enum class PromptStatus { SUCCESS, ERROR, UNKNOWN }

    private suspend fun promptStatusOf(client: ComfyUIClient, promptId: String): PromptStatus {
        val history = awaitCallbackOnce<JSONObject?> { deliver ->
            client.fetchHistory(promptId) { deliver(it) }
        } ?: return PromptStatus.UNKNOWN
        val entry = history.optJSONObject(promptId) ?: return PromptStatus.UNKNOWN
        val statusStr = entry.optJSONObject("status")?.optString("status_str") ?: return PromptStatus.UNKNOWN
        return when (statusStr) {
            "success" -> PromptStatus.SUCCESS
            "error" -> PromptStatus.ERROR
            else -> PromptStatus.UNKNOWN
        }
    }
}
