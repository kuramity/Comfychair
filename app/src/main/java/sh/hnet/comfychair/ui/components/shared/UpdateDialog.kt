package sh.hnet.comfychair.ui.components.shared

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.update.AppInstaller
import sh.hnet.comfychair.update.UpdatePhase
import sh.hnet.comfychair.update.UpdateRepository
import sh.hnet.comfychair.update.UpdateState
import java.util.Locale

/**
 * 内置更新的进度弹窗（v4.6.0）。
 *
 * **生命周期纪律（硬性约束 11）**：[onDismissRequest]（点空白/返回键）只把
 * `showDialog` 置 false —— 不取消 WorkManager 任务、不清进度、不重新入队。
 * 下载在后台继续，重新打开弹窗时进度还在（状态在 [UpdateRepository] 单例里）。
 *
 * 常驻字段（硬性约束 12）：阶段 / 已下载/总大小 / 百分比 / 速度 / 剩余时间。
 */
@Composable
fun UpdateDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit
) {
    if (!showDialog) return
    val context = LocalContext.current
    val state by UpdateRepository.state.collectAsStateWithLifecycle()
    val pending = UpdateRepository.pendingUpdate

    // v4.9.2：Android 13+ 的 POST_NOTIFICATIONS 全项目从来没请求过 —— 不请求等于默认拒绝，
    // 通知栏的下载进度条与"下载完成，点击安装"提示全都不会出现（用户看到的就是"通知栏没内容"）。
    // 放在"点立即更新"那一刻请求：用户刚决定要下 150MB，此时同意概率最高；
    // 拒绝也不阻塞下载（弹窗内仍有完整进度）。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权与否都不影响下载，无需处理结果 */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss, // 硬性约束 11：只隐藏，不取消任何东西
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = { UpdateDialogContent(state, pending) },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(
                    onClick = {
                        requestNotificationPermissionIfNeeded()
                        pending?.let { UpdateRepository.startDownload(context, it) }
                    }
                ) { Text(stringResource(R.string.update_action_now)) }

                UpdatePhase.READY_TO_INSTALL -> TextButton(
                    onClick = {
                        when (AppInstaller.install(context)) {
                            AppInstaller.InstallResult.NEED_UNKNOWN_SOURCE_PERMISSION ->
                                AppInstaller.openUnknownSourceSettings(context)
                            else -> Unit
                        }
                    }
                ) { Text(stringResource(R.string.update_action_install)) }

                UpdatePhase.FAILED -> TextButton(
                    onClick = {
                        requestNotificationPermissionIfNeeded()
                        pending?.let { UpdateRepository.startDownload(context, it) }
                    }
                ) { Text(stringResource(R.string.update_action_retry)) }

                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) }
        }
    )
}

@Composable
private fun UpdateDialogContent(
    state: UpdateState,
    pending: sh.hnet.comfychair.update.UpdateInfo?
) {
    // v4.10.1：说明可能很长（notes 段落 + changelog 多条），加高度上限与滚动，
    // 否则说明一多就会把"立即更新 / 稍后"两个按钮顶出屏幕，用户只能按返回键。
    Column(
        modifier = Modifier
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state.phase) {
            UpdatePhase.IDLE -> Text(stringResource(R.string.update_checking))

            UpdatePhase.AVAILABLE -> {
                if (pending != null) {
                    Text(stringResource(R.string.update_available_line, pending.versionName))
                    // 显示当前版本：用户能一眼看出"我在 v4.9.1，要升到 v4.10.0"。
                    // 这一行同时是排障入口 —— 看不到更新说明时，先看这里是不是旧版客户端。
                    Text(
                        stringResource(
                            R.string.update_current_version_line,
                            BuildConfig.VERSION_NAME
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pending.apkSize > 0) {
                        Text(
                            stringResource(R.string.update_size_line, formatBytes(pending.apkSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // v4.10.1：完整更新说明（update.json 的 notes 字段，按行分段）
                    if (pending.notes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_notes_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        pending.notes
                            .split('\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    }
                    // v4.9.2：本次更新内容（来自 update.json 的 changelog 字段），逐条列出
                    if (pending.changelog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_changelog_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        pending.changelog.forEach { line ->
                            Text(
                                text = "· $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 两个字段都空时给一句明确的话，而不是"什么都没有"让用户以为又出 bug 了
                    if (pending.notes.isEmpty() && pending.changelog.isEmpty()) {
                        Text(
                            text = stringResource(R.string.update_changelog_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            UpdatePhase.DOWNLOADING -> {
                if (state.totalBytes > 0) {
                    Text(
                        stringResource(
                            R.string.update_progress_line,
                            formatBytes(state.downloadedBytes),
                            formatBytes(state.totalBytes),
                            state.percent
                        )
                    )
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                } else {
                    // 总长未知（Content-Range/Content-Length 都缺）时不显示误导性的 "0%"，
                    // 只报已下载字节 + 不确定进度条（与通知栏口径一致，A-B1 第 10 条）
                    Text(
                        stringResource(
                            R.string.update_progress_unknown_total,
                            formatBytes(state.downloadedBytes)
                        )
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
                Text(
                    speedEtaLine(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            UpdatePhase.VERIFYING -> {
                Text(stringResource(R.string.update_verifying))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
            }

            UpdatePhase.READY_TO_INSTALL ->
                Text(stringResource(R.string.update_ready_dialog_line))

            UpdatePhase.FAILED -> Text(
                state.error ?: stringResource(R.string.update_failed_generic),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

internal fun speedEtaLine(state: UpdateState): String {
    val speed = if (state.speedBytesPerSec > 0) "${formatBytes(state.speedBytesPerSec)}/s" else "—"
    val eta = if (state.etaSeconds >= 0) {
        val m = state.etaSeconds / 60
        val s = state.etaSeconds % 60
        if (m > 0) "${m}m${s}s" else "${s}s"
    } else "—"
    return "↓$speed · 剩余 $eta"
}
