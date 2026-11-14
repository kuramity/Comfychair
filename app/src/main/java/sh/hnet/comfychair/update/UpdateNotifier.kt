package sh.hnet.comfychair.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.DebugLogger

/**
 * 更新相关通知（v4.6.0 建立，v4.7.1 加进度横幅与点击即安装）。
 *
 * **两个渠道，用途分离**（避免进度更新把用户吵死）：
 * - [PROGRESS_CHANNEL_ID] `IMPORTANCE_LOW`：下载进度常驻通知。静默、不弹横幅、不响铃，
 *   下拉通知栏可见实时进度条 —— 因为进度每 400ms 变一次，用 HIGH 会持续骚扰。
 * - [RESULT_CHANNEL_ID] `IMPORTANCE_HIGH`：下载完成 / 失败。弹横幅，点击分别直接安装 / 打开 App。
 *
 * **点击即安装**：完成通知的 PendingIntent 在**发通知时**就按当前状态二选一构造：
 * - 已有「安装未知应用」权限 → 直接 `ACTION_VIEW` + FileProvider 拉起系统安装器；
 * - 没有权限 → 跳到该权限的系统设置页（引导，不静默绕过）。
 * 这样用户从通知栏点一次就能落到正确界面，不必先进 App 再点按钮。
 *
 * Android 13+ 的 POST_NOTIFICATIONS 被拒时全部静默跳过（不崩溃、不阻塞），
 * 弹窗仍是主入口。
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"

    /** 进度通知渠道（LOW：静默常驻） */
    internal const val PROGRESS_CHANNEL_ID = "comfychair_update_progress"

    /** 完成/失败通知渠道（HIGH：弹横幅） */
    internal const val RESULT_CHANNEL_ID = "comfychair_update"

    /** 进度通知 id —— 与 WorkManager 前台服务的通知 id 一致（同一 id 才能被前台服务接管） */
    internal const val PROGRESS_NOTIFICATION_ID = 20001

    /** 结果通知 id（与进度分开，否则完成横幅会把进度条顶掉） */
    internal const val RESULT_NOTIFICATION_ID = 20002

    private const val REQ_INSTALL = 20011
    private const val REQ_LAUNCH = 20012

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val AUTHORITIES_SUFFIX = ".fileprovider"

    /**
     * 创建两个通知渠道（App 启动时调用一次，重复调用幂等）。
     * minSdk 33 ≥ O，渠道 API 无需版本判断。
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progress = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            context.getString(R.string.update_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.update_channel_progress_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(progress)

        val result = NotificationChannel(
            RESULT_CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.update_channel_desc)
        }
        manager.createNotificationChannel(result)
    }

    /** 兼容旧调用点（MainContainerActivity 启动时只调一次） */
    fun ensureChannel(context: Context) = ensureChannels(context)

    // ---------- 进度通知 ----------

    /**
     * 构造进度通知（同时供 [UpdateDownloadWorker] 的 ForegroundInfo 复用，保证
     * 前台服务的通知与 UI 进度是同一条，不会出现两条进度条）。
     */
    fun buildProgressNotification(
        context: Context,
        state: UpdateState,
        versionName: String
    ): Notification {
        val percent = UpdateFormat.percent(state.downloadedBytes, state.totalBytes)
        val title = if (state.phase == UpdatePhase.VERIFYING) {
            context.getString(R.string.update_progress_verifying_title)
        } else {
            context.getString(R.string.update_progress_title, versionName)
        }
        val text = if (percent >= 0) {
            context.getString(
                R.string.update_progress_text,
                UpdateFormat.formatBytes(state.downloadedBytes),
                UpdateFormat.formatBytes(state.totalBytes),
                percent
            )
        } else {
            UpdateFormat.formatBytes(state.downloadedBytes)
        }

        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            // 只响一次：首次弹出后，后续进度更新不再响铃/振动词
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchAppPendingIntent(context))

        if (state.phase == UpdatePhase.VERIFYING) {
            builder.setProgress(0, 0, true) // 不确定进度
        } else if (percent >= 0) {
            builder.setProgress(100, percent, false)
            builder.setSubText(
                context.getString(
                    R.string.update_progress_meta,
                    UpdateFormat.formatSpeed(state.speedBytesPerSec),
                    UpdateFormat.formatEta(state.etaSeconds)
                )
            )
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 更新进度通知（Worker 节流后调用，频率由 UpdateRepository 的 400ms 闸门控制） */
    fun updateProgress(context: Context, state: UpdateState, versionName: String) {
        if (!canPost(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            PROGRESS_NOTIFICATION_ID,
            buildProgressNotification(context, state, versionName)
        )
    }

    /**
     * 下载暂时失败、WorkManager 退避等待中（v4.12.0，A-R1 第 8 条）：
     * 把进度通知切成"等待重试（第 N 次）"的明确文案，不留旧的百分比 ——
     * 否则退避期间用户看到的是一条永远不动的旧进度条，误以为还在下载。
     * 仓库状态此时保持 DOWNLOADING（FAILED 只留给最终失败）。
     */
    fun notifyWaitingRetry(context: Context, versionName: String, attempt: Int) {
        if (!canPost(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.update_progress_title, versionName))
            .setContentText(context.getString(R.string.update_progress_retrying, attempt))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(launchAppPendingIntent(context))
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    /** 撤掉进度通知（下载结束/失败/完成时调用；前台服务会把同 id 的通知一并撤掉） */
    fun cancelProgress(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(PROGRESS_NOTIFICATION_ID)
    }

    // ---------- 结果通知 ----------

    /** "更新已就绪，点击安装"高优先级横幅（点击直接进安装器 / 未知来源设置页） */
    fun notifyUpdateReady(context: Context, versionName: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        cancelProgress(context)

        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_install_text, versionName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setAutoCancel(true)
            .setContentIntent(installPendingIntent(context))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    /** 下载失败横幅（点击打开 App，由弹窗的「重试」按钮承接续传） */
    fun notifyFailed(context: Context, message: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        cancelProgress(context)

        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.update_failed_title))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(launchAppPendingIntent(context))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    /** 全部清理（安装完成/用户清空下载时调用） */
    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(PROGRESS_NOTIFICATION_ID)
        manager.cancel(RESULT_NOTIFICATION_ID)
    }

    // ---------- 内部 ----------

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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

    /**
     * 安装用的 PendingIntent：能装就直接拉起安装器，没权限就跳设置页。
     * 任何一步失败（APK 不见了 / FileProvider 路径不匹配）都退化为「打开 App」。
     */
    private fun installPendingIntent(context: Context): PendingIntent {
        val intent = runCatching { buildInstallIntent(context) }
            .onFailure { DebugLogger.w(TAG, "install intent failed: ${it.message}") }
            .getOrNull()
            ?: return launchAppPendingIntent(context)

        return PendingIntent.getActivity(
            context,
            REQ_INSTALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildInstallIntent(context: Context): Intent? {
        val apk = UpdateDownloadWorker.finalFile(context)
        if (!apk.isFile || apk.length() == 0L) return null

        // 没有「安装未知应用」权限：先把用户送到授权页
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITIES_SUFFIX,
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
