package sh.hnet.comfychair.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.storage.AppSettings

/**
 * 生成过程通知（锁屏 / 后台可见）。
 *
 * 与 [sh.hnet.comfychair.update.UpdateNotifier] 同构但**渠道独立**：更新的进度/结果
 * 渠道语义绑定更新下载（id 也占用 20001/20002），混用会让用户在系统设置里看到
 * 「更新下载」渠道出图，关闭更新通知会连带砍掉生成通知。因此这里新建一对渠道。
 *
 * **两个渠道，用途分离**（与更新通知同一套理由）：
 * - [PROGRESS_CHANNEL_ID] `IMPORTANCE_LOW`：生成进度常驻通知。静默、不响铃、无横幅，
 *   锁屏 / 通知栏可见进度条 —— 进度每步都变，HIGH 会持续骚扰。
 * - [RESULT_CHANNEL_ID] `IMPORTANCE_HIGH`：完成 / 失败。后台时弹横幅，点击回 App。
 *
 * **前台不显示进度通知**：界面本身就有进度条与预览，App 在前台时再挂一条通知纯属
 * 重复。前后台由 [ComfyChairApp 的 ActivityLifecycleCallbacks][setAppInForeground]
 * 维护；进后台那一刻若有活跃生成，用最后一次快照补发一条；回前台即撤。
 *
 * **任务级前台服务（P4 起）**：[GenerationKeepAliveService] 在有生成任务且 WiFi 后台时
 * 把这条进度通知提升为前台服务通知，目的只是让进程不被冻结 —— 服务随任务结束立即退出，
 * **不是常驻通知**。没有任务时不存在该服务（用户明确要求）。
 *
 * **隐私约束**：只显示模式名（文生图等）+ 进度百分比 + 失败原因摘要。
 * 绝不显示提示词内容、绝不带图片缩略图 —— 通知栏在锁屏 / 公共场合可见。
 *
 * Android 13+ 的 POST_NOTIFICATIONS 被拒时全部静默跳过（与更新通知同口径）；
 * 设置开关（[AppSettings.isGenerationNotificationsEnabled]）关闭时同样静默跳过，
 * 并撤掉已挂的通知。
 */
object GenerationNotifier {

    /** 进度通知渠道（LOW：静默常驻） */
    internal const val PROGRESS_CHANNEL_ID = "comfychair_generation_progress"

    /** 完成/失败通知渠道（HIGH：后台弹横幅） */
    internal const val RESULT_CHANNEL_ID = "comfychair_generation_result"

    /**
     * 单条通知 id：进度 → 完成用**同一条**更新（"通知更新为已完成"），
     * 与更新模块的 20001/20002 错开。
     */
    internal const val NOTIFICATION_ID = 21001

    /** 通知「取消」动作的广播（由 [GenerationNotificationActionReceiver] 接收） */
    internal const val ACTION_CANCEL = "sh.hnet.comfychair.notify.ACTION_CANCEL_GENERATION"

    private const val REQ_LAUNCH = 21011
    private const val REQ_CANCEL = 21012

    /** 失败原因摘要的最大长度（execution_error 的 message 可能整段异常栈开头） */
    internal const val FAILURE_SUMMARY_MAX_CHARS = 120

    /** 进度通知的最小更新间隔：ComfyUI 每步发一次 progress，直接透传太耗电 */
    private const val MIN_PROGRESS_INTERVAL_MS = 1000L

    /**
     * 没有百分比数据（max<=0）时的**保活刷新**间隔（P4）。
     *
     * 修复前：percent 恒为 null → 节流游标 lastPercent 恒为 -1 → 「百分比没变」
     * 直接从第二次起永不更新，通知停在第一次的「生成中…」。用户看到的就是进度停滞。
     * 现在无百分比状态按时间窗重发同一条静默通知（LOW 渠道、setOnlyAlertOnce），
     * 让「还在跑」这件事在通知栏保持新鲜，代价是每分钟一次 notify（不联网、不唤醒网络）。
     */
    private const val INDETERMINATE_REFRESH_MS = 60_000L

    // ---------- 状态（全部 @Volatile：写侧在 ViewModel / 主线程，读侧在通知构建） ----------

    /**
     * App 是否在前台（[setAppInForeground] 由 Application 的生命周期回调维护）。
     *
     * **默认 false（P4 修复）**：进程被 WorkManager 拉起时不会安装 Activity 生命周期，
     * 原来的默认 true 会让 Worker 补发的完成通知走「前台」分支被直接撤销 ——
     * 「完成提醒必达」在进程重启场景下永远不成立。正常前台路径里，监视器会在
     * Activity ON_START 时把值改为 true，不依赖这个默认值。
     */
    @Volatile
    private var appInForeground: Boolean = false

    /** 最后一次进度快照：进后台补发通知时用（null = 当前没有可展示的生成） */
    @Volatile
    private var lastSnapshot: ProgressSnapshot? = null

    /** 节流游标：上次真正发出通知的时刻与百分比 */
    @Volatile
    private var lastEmitAtMs: Long = 0L
    @Volatile
    private var lastEmitPercent: Int = -1

    /** 通知「取消」按钮的处理器（由 GenerationViewModel 在生成期间注册） */
    @Volatile
    internal var cancelHandler: (() -> Unit)? = null

    /** 进度快照（模式名资源 id + 百分比；percent null = 尚无进度数据） */
    private data class ProgressSnapshot(
        val titleResId: Int,
        val percent: Int?,
    )

    // ---------- 渠道 ----------

    /**
     * 创建两个通知渠道（App 启动时调用一次，重复调用幂等）。
     * minSdk 33 ≥ O，渠道 API 无需版本判断。
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progress = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            context.getString(R.string.notify_generation_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notify_generation_channel_progress_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(progress)

        val result = NotificationChannel(
            RESULT_CHANNEL_ID,
            context.getString(R.string.notify_generation_channel_result_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notify_generation_channel_result_desc)
        }
        manager.createNotificationChannel(result)
    }

    // ---------- 前后台 ----------

    /**
     * App 前后台切换（Application 的 ActivityLifecycleCallbacks 维护）。
     *
     * - 回前台：撤掉进度通知（界面自己会展示进度与结果）。
     * - 进后台：若仍有活跃生成，用最后一次快照立刻挂一条进度通知 —— 否则从进后台
     *   到下一次 progress 事件之间锁屏上看不到任何东西。
     */
    fun setAppInForeground(context: Context, inForeground: Boolean) {
        appInForeground = inForeground
        if (inForeground) {
            cancel(context)
            return
        }
        val snapshot = lastSnapshot ?: return
        if (!canNotify(context)) return
        postProgressNotification(context, snapshot)
    }

    // ---------- 生成生命周期挂钩（GenerationViewModel 调用） ----------

    /**
     * 任务开始执行（execution_start 认领成功或恢复生成态）。
     * 只缓存快照；通知本体等「进后台」或第一次进度事件时才挂。
     */
    fun onGenerationStarted(context: Context, ownerId: String?) {
        val snapshot = ProgressSnapshot(titleResId = titleResFor(ownerId), percent = null)
        lastSnapshot = snapshot
        lastEmitAtMs = 0L
        lastEmitPercent = -1
        if (!appInForeground && canNotify(context)) {
            postProgressNotification(context, snapshot)
        }
    }

    /** 进度更新（WS progress 事件；max<=0 时没有可算的百分比，只表达"在跑"）。 */
    fun onProgress(context: Context, ownerId: String?, value: Int, max: Int) {
        val percent = percentOf(value, max)
        val snapshot = ProgressSnapshot(titleResId = titleResFor(ownerId), percent = percent)
        lastSnapshot = snapshot
        if (appInForeground || !canNotify(context)) return

        val nowMs = System.currentTimeMillis()
        if (!shouldEmitProgress(lastEmitAtMs, nowMs, MIN_PROGRESS_INTERVAL_MS, lastEmitPercent, percent ?: -1)) {
            return
        }
        lastEmitAtMs = nowMs
        lastEmitPercent = percent ?: -1
        postProgressNotification(context, snapshot)
    }

    /**
     * 生成完成。后台时把进度通知更新为「已完成」（HIGH，点击回 App）；
     * 前台时直接撤掉（用户就在界面看着出图）。
     */
    fun onGenerationCompleted(context: Context, ownerId: String?) {
        lastSnapshot = null
        if (appInForeground || !canNotify(context)) {
            cancel(context)
            return
        }
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.notify_generation_completed_title))
            .setContentText(context.getString(titleResFor(ownerId)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(launchAppPendingIntent(context))
            .build()
        notify(context, notification)
    }

    /**
     * 生成失败。后台时弹失败横幅（HIGH，带原因摘要，点击回 App）；
     * 前台时直接撤掉进度通知（失败提示由界面 Toast / 对话框承担）。
     */
    fun onGenerationFailed(context: Context, ownerId: String?, message: String?) {
        lastSnapshot = null
        if (appInForeground || !canNotify(context)) {
            cancel(context)
            return
        }
        ensureChannels(context)
        val summary = summarizeFailure(message)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.notify_generation_failed_title))
            .setContentText(
                context.getString(titleResFor(ownerId)) +
                    if (summary != null) " · $summary" else ""
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(launchAppPendingIntent(context))
            .build()
        notify(context, notification)
    }

    /**
     * 完成状态在轮询重试上限内仍无法确认（P4）。
     *
     * **不谎报成功/失败**：只提示「结果待确认，打开 App 查看最新状态」。
     * 走结果渠道（HIGH），因为这是用户需要采取动作的提醒，而不是过程进度。
     */
    fun onPendingConfirmation(context: Context, ownerId: String?) {
        lastSnapshot = null
        if (appInForeground || !canNotify(context)) {
            // 前台时界面自己能展示状态；通知关闭时静默
            return
        }
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.notify_generation_pending_title))
            .setContentText(
                context.getString(titleResFor(ownerId)) + " · " +
                    context.getString(R.string.notify_generation_pending_text)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(launchAppPendingIntent(context))
            .build()
        notify(context, notification)
    }

    /** 用户主动取消 / 登出 / 状态复位：撤掉通知，清快照。 */
    fun cancel(context: Context) {
        lastSnapshot = null
        lastEmitAtMs = 0L
        lastEmitPercent = -1
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    // ---------- 通知「取消」动作 ----------

    /**
     * 注册取消处理器（GenerationViewModel 在任务进入生成态时注册，复位时置 null）。
     * 通知按钮的动作转发给它，走 ViewModel 的完整取消链（/interrupt + 状态复位）。
     */
    fun registerCancelHandler(handler: (() -> Unit)?) {
        cancelHandler = handler
    }

    /**
     * 通知按钮触发取消（[GenerationNotificationActionReceiver] 调用）。
     *
     * 优先走注册的处理器（ViewModel 存活时的完整收尾）；没有处理器时降级为
     * 直接中断当前执行的任务并同步注册表 —— 保证"点了通知上的取消，服务器必停"。
     */
    fun requestCancel(context: Context) {
        val handler = cancelHandler
        if (handler != null) {
            handler()
            return
        }
        val executing = JobRegistry.queueState.value
        val executingId = executing.executingPromptId
        if (executingId == null || !executing.ownJobs.containsKey(executingId)) {
            // 不是本机任务（或没有执行中的任务）：撤掉通知即可，绝不能误发 /interrupt
            cancel(context)
            return
        }
        ConnectionManager.clientOrNull?.interruptExecution { success ->
            if (success) {
                JobRegistry.markCancelled(executingId)
            }
            cancel(context)
        }
    }

    // ---------- 纯判定（internal 供 JVM 单测钉住） ----------

    /**
     * ownerId → 通知标题资源。复用导航栏的模式名文案（文生图 / 图生图 / 文生视频 / 图生视频），
     * 未知 ownerId（含 null）回落到泛化的「生成」标题。
     */
    internal fun titleResFor(ownerId: String?): Int = when (ownerId) {
        "TEXT_TO_IMAGE" -> R.string.nav_text_to_image
        "IMAGE_TO_IMAGE" -> R.string.nav_image_to_image
        "TEXT_TO_VIDEO" -> R.string.nav_text_to_video
        "IMAGE_TO_VIDEO" -> R.string.nav_image_to_video
        else -> R.string.notify_generation_generic_title
    }

    /** value/max → 百分比（0–100）；max<=0 或除零时返回 null（通知显示"在跑"而非假数字）。 */
    internal fun percentOf(value: Int, max: Int): Int? {
        if (max <= 0) return null
        return ((value * 100L) / max).toInt().coerceIn(0, 100)
    }

    /**
     * 进度通知节流：第一次必发；之后要求「百分比变化」或「无百分比状态的时间窗刷新」。
     * max<=0 时 percent 为 -1（占位）。
     *
     * P4 修复：-1 → -1 原来一律返回 false，于是无百分比的任务从第二次起永远不更新，
     * 通知停在第一次的文案。现在 -1 状态每 [INDETERMINATE_REFRESH_MS] 重发一次。
     */
    internal fun shouldEmitProgress(
        lastEmitAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long,
        lastPercent: Int,
        newPercent: Int,
    ): Boolean {
        if (lastEmitAtMs == 0L) return true
        if (newPercent == lastPercent) {
            return newPercent < 0 &&
                nowMs - lastEmitAtMs >= maxOf(minIntervalMs, INDETERMINATE_REFRESH_MS)
        }
        return nowMs - lastEmitAtMs >= minIntervalMs
    }

    /** 失败原因摘要：截到 [FAILURE_SUMMARY_MAX_CHARS]；空消息返回 null（通知不显示摘要段）。 */
    internal fun summarizeFailure(message: String?): String? {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= FAILURE_SUMMARY_MAX_CHARS) {
            trimmed
        } else {
            trimmed.take(FAILURE_SUMMARY_MAX_CHARS) + "…"
        }
    }

    // ---------- 内部 ----------

    private fun canNotify(context: Context): Boolean {
        // 设置开关（阶段 C）：用户关掉后不再发任何生成相关通知，并撤掉已挂的
        if (!isNotificationSettingEnabled(context)) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 设置里的生成通知开关（P4：前台服务启动门槛用）。
     *
     * 与 [canNotify] 的区别：这里**不**检查运行时权限 —— 权限被拒时系统本就不展示
     * 前台服务通知，但服务仍可保护进程；而设置里明确关闭时不应再挂任何生成通知，
     * 也就不启动前台服务（回落到心跳 + 轮询）。
     */
    internal fun isNotificationSettingEnabled(context: Context): Boolean =
        AppSettings.isGenerationNotificationsEnabled(context)

    private fun notify(context: Context, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun postProgressNotification(context: Context, snapshot: ProgressSnapshot) {
        notify(context, buildProgressNotification(context, snapshot))
    }

    /**
     * 构建进度通知本体（P4：前台服务复用同一通知 id / 渠道 / 文案）。
     *
     * @param percent null = 无百分比数据（indeterminate），显示「生成中…」
     */
    internal fun buildProgressNotification(
        context: Context,
        ownerId: String?,
        percent: Int?,
    ): Notification = buildProgressNotification(
        context,
        ProgressSnapshot(titleResId = titleResFor(ownerId), percent = percent)
    )

    private fun buildProgressNotification(
        context: Context,
        snapshot: ProgressSnapshot,
    ): Notification {
        ensureChannels(context)
        val textRes = if (snapshot.percent != null) {
            R.string.notify_generation_progress_text
        } else {
            R.string.notify_generation_progress_running
        }
        val text = if (snapshot.percent != null) {
            context.getString(textRes, snapshot.percent)
        } else {
            context.getString(textRes)
        }
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(snapshot.titleResId))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(launchAppPendingIntent(context))
            .addAction(
                0,
                context.getString(R.string.notify_generation_action_cancel),
                cancelPendingIntent(context)
            )
        if (snapshot.percent != null) {
            builder.setProgress(100, snapshot.percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun launchAppPendingIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            REQ_LAUNCH,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GenerationNotificationActionReceiver::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_CANCEL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 当前是否有活跃生成的快照（进度通知挂着的依据）。 */
    internal fun hasActiveSnapshot(): Boolean = lastSnapshot != null
}
