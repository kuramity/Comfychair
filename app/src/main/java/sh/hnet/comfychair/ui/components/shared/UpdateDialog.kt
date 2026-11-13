package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val state by UpdateRepository.state.collectAsState()
    val pending = UpdateRepository.pendingUpdate

    AlertDialog(
        onDismissRequest = onDismiss, // 硬性约束 11：只隐藏，不取消任何东西
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = { UpdateDialogContent(state, pending) },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(
                    onClick = {
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            UpdatePhase.IDLE -> Text(stringResource(R.string.update_checking))

            UpdatePhase.AVAILABLE -> {
                if (pending != null) {
                    Text(stringResource(R.string.update_available_line, pending.versionName))
                    if (pending.apkSize > 0) {
                        Text(
                            stringResource(R.string.update_size_line, formatBytes(pending.apkSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            UpdatePhase.DOWNLOADING -> {
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
