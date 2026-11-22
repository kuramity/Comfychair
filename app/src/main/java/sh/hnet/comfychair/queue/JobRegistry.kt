package sh.hnet.comfychair.queue

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.viewmodel.ContentType
import java.util.concurrent.atomic.AtomicLong

/**
 * Status of a tracked job in the queue system.
 */
enum class JobStatus {
    PENDING,    // Job submitted, waiting in queue
    EXECUTING,  // Job currently being processed
    COMPLETED,  // Job finished successfully
    FAILED      // Job failed
}

/**
 * Represents a job tracked by the JobRegistry.
 */
data class TrackedJob(
    val promptId: String,
    val ownerId: String,
    val contentType: ContentType,
    val status: JobStatus
)

/**
 * Represents the current state of the queue system.
 * This state is observable and updates in real-time based on WebSocket events.
 */
data class QueueState(
    /** The prompt ID of the currently executing job (if any) */
    val executingPromptId: String? = null,
    /** The owner ID of the currently executing job (for preview routing) */
    val executingOwnerId: String? = null,
    /** The content type of the currently executing job */
    val executingContentType: ContentType? = null,
    /** Total number of jobs in the server queue (all clients) */
    val totalQueueSize: Int = 0,
    /** Our tracked jobs by promptId */
    val ownJobs: Map<String, TrackedJob> = emptyMap()
) {
    /** Whether any job is currently executing */
    val isExecuting: Boolean get() = executingPromptId != null

    /** Number of our own jobs that are still pending or executing */
    val ownActiveJobCount: Int get() = ownJobs.values.count {
        it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
    }

    /**
     * 指定模式（ownerId）自己的活跃任务数（排队 + 执行中）。
     *
     * **为什么需要它**：按钮文案原先用 [totalQueueSize]（服务器上所有客户端的队列总数），
     * 于是只要服务器有任务在跑，文生图/图生图/文生视频/图生视频四个模式的按钮会**同时**
     * 变成「添加到队列 (N)」—— 即"跨模式状态污染"。文案必须按当前模式过滤。
     *
     * @param ownerId 由各 ViewModel 的 `OWNER_ID` 常量传入
     */
    fun activeCountFor(ownerId: String): Int = ownJobs.values.count {
        it.ownerId == ownerId && (it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING)
    }
}

/**
 * 本机提交过的任务记录（P1-1 归属兜底用）。
 *
 * 为什么不能只看 `ownJobs`：那份表会被"完成 / 失败 / 取消 / 队列对账"删条目，
 * 删掉之后再收到同一个 promptId 的 `execution_start`（WS 重连补发、事件乱序）
 * 就查不到归属了。而 `execution_start` 是服务器广播给**所有客户端**的，
 * 一旦"查不到就当成自己的"，外部任务就会被记到本机名下。
 */
data class SubmittedPrompt(
    val promptId: String,
    val ownerId: String,
    val contentType: ContentType
)

/**
 * P1-1：`execution_start` 的归属判定（纯函数，供 JVM 单测钉住）。
 *
 * 判定只有两条成立路径：
 * 1. 注册表里已有这个 promptId（提交成功时 [JobRegistry.registerJob] 登记的）；
 * 2. 这个 promptId 是本机提交返回过的（[SubmittedPrompt] 记录在案）。
 *
 * 两条都不满足 = 外部客户端的任务。绝不能因为"本机恰好有在途提交"就认领：
 * 那正是"外部客户端出的图跑到本机页面、本机进度条被外部任务推动"的根因。
 *
 * **覆盖边界（R2-L5）**：本机提交记录同样只在 [JobRegistry.registerJob] 里写入，
 * 所以"`execution_start` 早于 `registerJob` 到达"这个时序**不在**第 2 条的覆盖范围内
 *（那时注册表与本机提交记录都还没有这个 promptId，事件会被当成外部任务忽略）。
 * 该时序不会丢状态：[JobRegistry.markExecuting] 会先把 `executingPromptId` 记下来，
 * 随后 `registerJob` 的"已在执行则回填 owner/contentType"分支把归属补齐。
 * 第 2 条真正覆盖的是：条目已被完成/取消/对账清掉之后，同一个 promptId 的事件被重放。
 *
 * @param eventPromptId 事件里带的 promptId
 * @param registeredOwnerId 注册表查到的归属；null = 查不到
 * @param submittedOwnerId 本机提交记录里的归属；null = 本机没提交过这个 promptId
 * @return 应采用的 ownerId；null = 不属于本机（调用方必须原样忽略）
 */
internal fun resolveExecutionStartOwner(
    eventPromptId: String,
    registeredOwnerId: String?,
    submittedOwnerId: String?,
): String? {
    if (eventPromptId.isEmpty()) return null
    if (registeredOwnerId != null) return registeredOwnerId
    return submittedOwnerId
}

/**
 * P1-5：`/queue` 对账时，一个本机任务是否该被判"服务器上已不存在"并删除。
 *
 * `/queue` 的响应是**请求发出那一刻**服务器状态的快照。请求在飞期间新登记的任务
 * 必然不在那份快照里，原实现把它们一并删掉，于是刚提交的任务从本地列表消失
 *（按钮计数回退、进度条丢失，而服务器其实正在跑）。所以只删
 *「快照发出前就已登记」且不在响应里的活跃任务；快照之后登记的给一个宽限期。
 *
 * @param isActive 本地状态是否 PENDING/EXECUTING（完成/失败的条目另有清理路径）
 * @param presentInResponse 该 promptId 是否出现在 /queue 响应里
 * @param registeredSeq 该任务的登记序号
 * @param snapshotSeq 请求发出前的登记序号水位
 */
internal fun shouldDropFromQueueSnapshot(
    isActive: Boolean,
    presentInResponse: Boolean,
    registeredSeq: Long,
    snapshotSeq: Long,
): Boolean = isActive && !presentInResponse && registeredSeq <= snapshotSeq

/**
 * Central singleton for tracking jobs and server queue state.
 *
 * JobRegistry provides:
 * - Unified job tracking across all generation screens
 * - Real-time server queue size updates
 * - Preview routing based on which screen submitted the executing job
 * - Persistence across app restart with server validation
 *
 * Architecture:
 * - ConnectionManager notifies JobRegistry of WebSocket events
 * - GenerationViewModel registers jobs when submitting workflows
 * - Generation screens observe queueState for UI updates
 */
object JobRegistry {
    private const val TAG = "JobRegistry"
    private const val PREFS_NAME = "JobRegistryPrefs"
    private const val PREF_JOBS_JSON = "jobs"

    /**
     * 本机提交记录的上限。
     *
     * 这份记录只用于"归属兜底"，不需要完整历史；无上限的话长时间运行会一直涨。
     * 超出后按插入顺序丢最旧的（[LinkedHashMap] 默认访问顺序 = 插入顺序）。
     */
    private const val MAX_REMEMBERED_SUBMISSIONS = 64

    /**
     * 本机提交返回过的 promptId → 归属（P1-1）。
     *
     * 所有读写都在 [Synchronized] 方法里（与 [_queueState] 的更新同一把锁），
     * 保证「登记一条任务」与「判定一条 execution_start 归属」看到同一份记录。
     */
    private val submittedPrompts = LinkedHashMap<String, SubmittedPrompt>()

    /**
     * 任务登记序号水位（P1-5）。
     *
     * [registerJob] 每登记一条 +1；`/queue` 请求发出前取一次水位 [beginQueueSnapshot]，
     * 响应回来只删"水位之前登记、且不在响应里"的任务。用 [AtomicLong] 而不是普通 var：
     * 提交回调在 OkHttp 线程、对账回调也在 OkHttp 线程，两者可能并发。
     */
    private val registrationSeq = AtomicLong(0L)

    /** promptId → 登记时的序号；仅 [shouldDropFromQueueSnapshot] 用得上 */
    private val jobRegistrationSeq = mutableMapOf<String, Long>()

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    /**
     * 应用 Context（[restoreState] 时记下）。
     *
     * 为什么要有它：任务状态变化发生在**很多**入口（提交、WS 完成、WS 失败、轮询完成、
     * 轮询判定丢失、队列对账），过去只有"提交"那一路会 `saveState`，其余全是内存改了、
     * 磁盘没改。后果很具体（2026-11-10 实测）：任务失败后重启 App，[restoreState] 会把
     * 那个任务当"排队中"恢复回来，界面凭空多出一个幽灵任务，直到 `/queue` 对账才消。
     * 记下 Context 后，状态一变就顺手落盘，内存与磁盘始终一致。
     */
    @Volatile
    private var appContextRef: Context? = null

    /**
     * Register a new job when a workflow is submitted.
     * Call this immediately after receiving a successful prompt submission response.
     *
     * @param promptId The prompt ID returned by the server
     * @param ownerId The owner ID (e.g., "TEXT_TO_IMAGE", "IMAGE_TO_VIDEO")
     * @param contentType The type of content being generated
     */
    @Synchronized
    fun registerJob(promptId: String, ownerId: String, contentType: ContentType) {
        DebugLogger.i(TAG, "Registering job: ${Obfuscator.promptId(promptId)} (owner: $ownerId, type: $contentType)")

        // P1-1：promptId 只有在提交成功（服务器把 prompt id 返回给我们）时才会到这里，
        // 所以这条记录就是"本机确实提交过它"的证据，供归属兜底使用。
        // P1-5：同时记下登记序号，供 /queue 快照对账判断"这条是不是请求发出后才出现的"。
        val seq = registrationSeq.incrementAndGet()
        submittedPrompts[promptId] = SubmittedPrompt(promptId, ownerId, contentType)
        while (submittedPrompts.size > MAX_REMEMBERED_SUBMISSIONS) {
            val oldest = submittedPrompts.keys.firstOrNull() ?: break
            submittedPrompts.remove(oldest)
        }
        jobRegistrationSeq[promptId] = seq

        val currentState = _queueState.value

        // Determine initial status - if this job is already executing, mark it as such
        val isAlreadyExecuting = currentState.executingPromptId == promptId
        val initialStatus = if (isAlreadyExecuting) JobStatus.EXECUTING else JobStatus.PENDING

        val job = TrackedJob(
            promptId = promptId,
            ownerId = ownerId,
            contentType = contentType,
            status = initialStatus
        )

        val newJobs = currentState.ownJobs + (promptId to job)

        // If this job is already executing (execution_start came before registration),
        // update the executing owner info now that we know it
        if (isAlreadyExecuting) {
            DebugLogger.d(TAG, "Job was already executing, updating owner to: $ownerId")
            _queueState.value = currentState.copy(
                ownJobs = newJobs,
                executingOwnerId = ownerId,
                executingContentType = contentType
            )
        } else {
            _queueState.value = currentState.copy(ownJobs = newJobs)
        }
        persist()
    }

    /**
     * Find the owner of a job by its prompt ID.
     * Used for routing previews and completion events to the correct screen.
     *
     * @return The owner ID, or null if not found
     */
    fun findOwner(promptId: String): String? {
        return _queueState.value.ownJobs[promptId]?.ownerId
    }

    /**
     * Find the content type of a job by its prompt ID.
     *
     * @return The content type, or null if not found
     */
    fun findContentType(promptId: String): ContentType? {
        return _queueState.value.ownJobs[promptId]?.contentType
    }

    /**
     * 查"本机提交返回过这个 promptId 吗"（P1-1 归属兜底的唯一依据）。
     *
     * 与 [findOwner] 的区别：条目被完成/对账清掉后这里仍然查得到，
     * 因此调用方**必须**用 [resolveExecutionStartOwner] 组合两者，
     * 不能把这里的命中直接当成"任务还在队列里"。
     */
    @Synchronized
    fun findSubmittedPrompt(promptId: String): SubmittedPrompt? = submittedPrompts[promptId]

    /**
     * 取当前登记水位（P1-5）。`/queue` 请求**发出前**调用，响应回来时原样传给
     * [updateFromServerQueue]，用于区分"请求发出前就存在"与"请求在飞期间新登记"。
     */
    @Synchronized
    fun beginQueueSnapshot(): Long = registrationSeq.get()

    /**
     * Mark a job as executing when execution_start is received.
     * This updates the executingPromptId/ownerId for preview routing.
     *
     * @param promptId The prompt ID from the execution_start event
     */
    @Synchronized
    fun markExecuting(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.i(TAG, "Job executing: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")

            val updatedJob = job.copy(status = JobStatus.EXECUTING)
            val newJobs = currentState.ownJobs + (promptId to updatedJob)

            _queueState.value = currentState.copy(
                executingPromptId = promptId,
                executingOwnerId = job.ownerId,
                executingContentType = job.contentType,
                ownJobs = newJobs
            )
        } else {
            // Job from another client - just track execution state
            DebugLogger.d(TAG, "External job executing: ${Obfuscator.promptId(promptId)}")
            _queueState.value = currentState.copy(
                executingPromptId = promptId,
                executingOwnerId = null,
                executingContentType = null
            )
        }
    }

    /**
     * Mark a job as completed when execution finishes (executing with node=null).
     * Clears the executing state and removes the job from tracking.
     *
     * @param promptId The prompt ID from the executing event
     */
    @Synchronized
    fun markCompleted(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.i(TAG, "Job completed: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")
        } else {
            DebugLogger.d(TAG, "External job completed: ${Obfuscator.promptId(promptId)}")
        }

        // Clear executing state if this was the executing job
        val isCurrentlyExecuting = currentState.executingPromptId == promptId

        // Remove the job from tracking
        val newJobs = currentState.ownJobs - promptId
        jobRegistrationSeq.remove(promptId)

        _queueState.value = currentState.copy(
            executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
            executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
            executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
            ownJobs = newJobs
        )

        // Trigger gallery refresh - single source of truth for completion-triggered refreshes
        // Handles both in-app jobs and external jobs (from other clients)
        DebugLogger.d(TAG, "Triggering gallery refresh for job completion")
        GalleryRepository.getInstance().refresh()

        // 完成也要落盘：否则这个任务会以 PENDING 状态留在 prefs 里，
        // 下次启动被 restoreState 当成"排队中"恢复（幽灵任务）。
        persist()
    }

    /**
     * Mark a job as failed.
     *
     * 与 [markCompleted] 同构：executing 指针清理与落盘是无条件的 ——
     * execution_error 是广播事件，外部任务（ownJobs 无条目）失败时同样要清掉
     * executingPromptId，否则进度条会停在已死任务上，直到下次 /queue 对账才自愈。
     *
     * @param promptId The prompt ID of the failed job
     */
    @Synchronized
    fun markFailed(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.w(TAG, "Job failed: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")
        } else {
            DebugLogger.d(TAG, "External job failed: ${Obfuscator.promptId(promptId)}")
        }

        // Remove the failed job
        val newJobs = currentState.ownJobs - promptId
        jobRegistrationSeq.remove(promptId)

        // Clear executing state if this was the executing job
        val isCurrentlyExecuting = currentState.executingPromptId == promptId

        _queueState.value = currentState.copy(
            executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
            executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
            executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
            ownJobs = newJobs
        )

        // 失败也要落盘，理由同 markCompleted
        persist()
    }

    /**
     * 用户主动取消后同步状态（v4.6.1，模块 A2/A3）。
     *
     * 与 [markCompleted] 的区别：取消不触发图库刷新（没有产物），但同样必须清掉
     * `executingPromptId` 指针并落盘 —— 否则取消后进度条/队列角标会停在原地，
     * 重启还会把该任务从 Prefs 恢复成"排队中"的幽灵任务。
     */
    @Synchronized
    fun markCancelled(promptId: String) {
        val currentState = _queueState.value
        val isCurrentlyExecuting = currentState.executingPromptId == promptId
        if (currentState.ownJobs.containsKey(promptId)) {
            DebugLogger.i(TAG, "Job cancelled by user: ${Obfuscator.promptId(promptId)}")
        }
        jobRegistrationSeq.remove(promptId)
        _queueState.value = currentState.copy(
            executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
            executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
            executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
            ownJobs = currentState.ownJobs - promptId
        )
        persist()
    }

    /**
     * Remove a job from tracking.
     *
     * @param promptId The prompt ID to remove
     */
    @Synchronized
    fun removeJob(promptId: String) {
        val currentState = _queueState.value
        if (currentState.ownJobs.containsKey(promptId)) {
            DebugLogger.d(TAG, "Removing job: ${Obfuscator.promptId(promptId)}")
            val newJobs = currentState.ownJobs - promptId
            jobRegistrationSeq.remove(promptId)
            _queueState.value = currentState.copy(ownJobs = newJobs)
            persist()
        }
    }

    /**
     * Update queue size from WebSocket status message.
     * The status message contains exec_info with the queue remaining count.
     *
     * When the queue size decreases, it indicates a job completed.
     * For external jobs (from other clients), we don't receive `executing` or
     * `execution_success` messages, so we detect completion via queue size decrease.
     *
     * @param queueRemaining Number of items in the server queue
     */
    @Synchronized
    fun updateFromStatus(queueRemaining: Int) {
        val currentState = _queueState.value
        val previousSize = currentState.totalQueueSize

        if (previousSize != queueRemaining) {
            DebugLogger.d(TAG, "Queue size updated: $queueRemaining")
            _queueState.value = currentState.copy(totalQueueSize = queueRemaining)

            // Detect job completion via queue size decrease
            // This handles external jobs that don't send execution messages to our client
            if (queueRemaining < previousSize) {
                val completedCount = previousSize - queueRemaining
                DebugLogger.d(TAG, "Detected $completedCount job(s) completed via queue decrease")
                // Trigger gallery refresh for external job completions
                GalleryRepository.getInstance().refresh()
            }
        }
    }

    /**
     * Update state from server queue response.
     * This provides detailed information about running and pending jobs.
     *
     * @param running The queue_running array from /queue endpoint
     * @param pending The queue_pending array from /queue endpoint
     * @param snapshotSeq 请求发出前的登记水位（[beginQueueSnapshot]）。缺省 [Long.MAX_VALUE] =
     *   没有快照信息，按旧语义删除所有"不在响应里的活跃任务"（保守：宁可少保留，不做宽限）。
     */
    @Synchronized
    fun updateFromServerQueue(
        running: JSONArray?,
        pending: JSONArray?,
        snapshotSeq: Long = Long.MAX_VALUE,
    ) {
        val runningCount = running?.length() ?: 0
        val pendingCount = pending?.length() ?: 0
        val totalCount = runningCount + pendingCount

        val currentState = _queueState.value
        var newState = currentState

        // Update total queue size
        if (newState.totalQueueSize != totalCount) {
            newState = newState.copy(totalQueueSize = totalCount)
        }

        // Find currently executing job from server queue
        if (running != null && running.length() > 0) {
            val firstRunning = running.optJSONArray(0)
            if (firstRunning != null && firstRunning.length() > 1) {
                val runningPromptId = firstRunning.optString(1)
                if (runningPromptId.isNotEmpty() && newState.executingPromptId != runningPromptId) {
                    // Update executing job based on server state
                    val job = newState.ownJobs[runningPromptId]
                    newState = newState.copy(
                        executingPromptId = runningPromptId,
                        executingOwnerId = job?.ownerId,
                        executingContentType = job?.contentType
                    )
                }
            }
        } else if (newState.executingPromptId != null && runningCount == 0) {
            // No running jobs on server but we thought something was executing
            newState = newState.copy(
                executingPromptId = null,
                executingOwnerId = null,
                executingContentType = null
            )
        }

        // Validate our tracked jobs against server queue
        val serverPromptIds = mutableSetOf<String>()
        running?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.optString(1)?.takeIf { it.isNotEmpty() }?.let {
                    serverPromptIds.add(it)
                }
            }
        }
        pending?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.optString(1)?.takeIf { it.isNotEmpty() }?.let {
                    serverPromptIds.add(it)
                }
            }
        }

        // Remove jobs that are no longer in the server queue (completed or removed).
        //
        // P1-5：只删「快照发出前就已登记」的条目。请求在飞期间刚 registerJob 的任务
        // 必然不在那份快照里，若照删就会出现"刚提交的任务从本地列表消失、按钮计数回退，
        // 而服务器其实正在跑"。宽限只针对新登记的条目，旧条目该删照删。
        val validJobs = newState.ownJobs.filter { (promptId, job) ->
            val isInQueue = serverPromptIds.contains(promptId)
            val isPendingOrExecuting = job.status == JobStatus.PENDING || job.status == JobStatus.EXECUTING
            val registeredSeq = jobRegistrationSeq[promptId] ?: Long.MIN_VALUE
            !shouldDropFromQueueSnapshot(
                isActive = isPendingOrExecuting,
                presentInResponse = isInQueue,
                registeredSeq = registeredSeq,
                snapshotSeq = snapshotSeq,
            )
        }

        if (validJobs.size != newState.ownJobs.size) {
            val removedIds = newState.ownJobs.keys - validJobs.keys
            removedIds.forEach { jobRegistrationSeq.remove(it) }
            val removedCount = removedIds.size
            DebugLogger.d(TAG, "Removed $removedCount stale jobs not found in server queue")
            newState = newState.copy(ownJobs = validJobs)
        }

        if (newState != currentState) {
            _queueState.value = newState
            // 对账也会删任务（服务端重启/队列被清）：落盘，避免重启后又被恢复
            persist()
        }
    }

    /**
     * Save job state to SharedPreferences for persistence across app restart.
     *
     * @param context Application context
     */
    fun saveState(context: Context) {
        appContextRef = context.applicationContext
        val currentState = _queueState.value
        val jobsArray = JSONArray()

        currentState.ownJobs.values.forEach { job ->
            val jobJson = JSONObject().apply {
                put("promptId", job.promptId)
                put("ownerId", job.ownerId)
                put("contentType", job.contentType.name)
                put("status", job.status.name)
            }
            jobsArray.put(jobJson)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(PREF_JOBS_JSON, jobsArray.toString())
            apply()
        }

        DebugLogger.d(TAG, "Saved ${currentState.ownJobs.size} jobs to preferences")
    }

    /**
     * 状态变化后把内存快照落盘（只在已经见过 Context 时做）。
     *
     * 所有改 `ownJobs` 的入口末尾都要调它，否则重启后 `restoreState` 会把已经结束
     * 的任务当"排队中"恢复回来（幽灵任务）。
     */
    private fun persist() {
        appContextRef?.let { ctx -> saveState(ctx) }
    }

    /**
     * Restore job state from SharedPreferences.
     * Should be called early in app startup.
     *
     * @param context Application context
     */
    fun restoreState(context: Context) {
        appContextRef = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jobsJson = prefs.getString(PREF_JOBS_JSON, null) ?: return

        try {
            val jobsArray = JSONArray(jobsJson)
            val jobs = mutableMapOf<String, TrackedJob>()

            for (i in 0 until jobsArray.length()) {
                // 单条损坏只跳过该条并继续：整块 catch 会因一条缺字段把其余完好条目一起丢掉
                try {
                    val jobObj = jobsArray.getJSONObject(i)
                    val promptId = jobObj.getString("promptId")
                    val ownerId = jobObj.getString("ownerId")
                    val contentType = try {
                        ContentType.valueOf(jobObj.getString("contentType"))
                    } catch (e: IllegalArgumentException) {
                        ContentType.IMAGE
                    }
                    val status = try {
                        JobStatus.valueOf(jobObj.getString("status"))
                    } catch (e: IllegalArgumentException) {
                        JobStatus.PENDING
                    }

                    // Only restore pending/executing jobs (completed/failed don't need restoration)
                    if (status == JobStatus.PENDING || status == JobStatus.EXECUTING) {
                        jobs[promptId] = TrackedJob(
                            promptId = promptId,
                            ownerId = ownerId,
                            contentType = contentType,
                            status = JobStatus.PENDING  // Reset to pending, will validate against server
                        )
                    }
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Skipping malformed job entry #$i: ${e.message}")
                }
            }

            if (jobs.isNotEmpty()) {
                _queueState.value = _queueState.value.copy(ownJobs = jobs)
                DebugLogger.i(TAG, "Restored ${jobs.size} jobs from preferences")
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to restore jobs: ${e.message}")
        }
    }

    /**
     * Clear all state. Called on logout.
     */
    @Synchronized
    fun clear() {
        DebugLogger.i(TAG, "Clearing all job state")
        submittedPrompts.clear()
        jobRegistrationSeq.clear()
        _queueState.value = QueueState()
        // clear 也要落盘：否则 prefs 里留着旧任务，本会话若再无其它 persist 触发点，
        // 下次启动 restoreState 会把已清的任务恢复成幽灵任务。
        persist()
    }

    /**
     * Clear persisted state. Called on logout.
     *
     * @param context Application context
     */
    fun clearPersistedState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        DebugLogger.d(TAG, "Cleared persisted job state")
    }
}
