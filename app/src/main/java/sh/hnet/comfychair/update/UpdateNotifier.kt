package sh.hnet.comfychair.update

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

/**
 * 更新相关通知（v4.6.0）。
 *
 * 硬性约束 17/18 的落地：
 * - 下载完成后若 App 在后台，**不**直接 startActivity（后台启动 Activity 受限且被禁止
 *   作为唯一方案）—— 发高优先级通知"更新已就绪，点击安装"，用户点击后触发安装。
 * - Android 13+ 需要 POST_NOTIFICATIONS 运行时权限；权限被拒时静默跳过（不崩溃、
 *   不阻塞——用户回到 App 时弹窗本身也会显示 READY_TO_INSTALL）。
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "comfychair_update"
    private const val NOTIFICATION_ID = 20001

    /** 创建通知渠道（App 启动时调用一次，重复调用幂等）。
     *  minSdk 33 ≥ O：渠道 API 无需版本判断（lint ObsoleteSdkInt 已确认）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.update_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** "更新已就绪，点击安装"高优先级通知（App 在后台时作为安装入口的保底） */
    fun notifyUpdateReady(context: Context, versionName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 通知权限被拒：静默跳过（弹窗仍是主入口）
            return
        }
        ensureChannel(context)

        // 点击通知 → 打开 App（由 App 的 READY_TO_INSTALL 状态弹窗承接安装动作，
        // 避免通知直接拉安装器时 Activity 缺失/权限缺失的复杂分支）
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_comfychair_foreground)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text, versionName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 下载完成但用户已把通知清掉时，取消残留（可选调用） */
    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
