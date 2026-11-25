package sh.hnet.comfychair.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * Alert dialog shown for connection failures that need an explicit user decision
 * (currently only authentication failures).
 *
 * P3（弱网不再把人困在弹窗里）：
 * - 对话框**任何时刻都可以关闭**（返回键 / 点外部 / 「稍后再试」）；
 * - 点「重新连接」后即使一直转圈，`返回登录` 与 `稍后再试` 仍然可点，
 *   正在进行的重连不会被取消，只是不再挡着界面；
 * - 网络中断 / 传输停滞已改走顶部非阻塞横幅（见 `ConnectionStatusBanner`），
 *   不会再走到这里。
 *
 * The Reconnect button shows a loading state with "Reconnecting..." while attempting.
 * Buttons are stacked vertically with thumb-friendly ordering (primary action at bottom).
 */
@Composable
fun ConnectionAlertDialog(
    failureType: ConnectionFailure,
    isReconnecting: Boolean = false,
    onReconnect: () -> Unit,
    onReturnToLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val isAuthFailure = failureType == ConnectionFailure.AUTHENTICATION
    val isStallFailure = failureType == ConnectionFailure.STALLED

    AlertDialog(
        // 永远允许关闭：被"稍后再试"关掉后，后台重连/退避不受影响。
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    when {
                        isAuthFailure -> R.string.title_session_expired
                        isStallFailure -> R.string.title_transfer_stalled
                        else -> R.string.title_connection_lost
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        when {
                            isAuthFailure -> R.string.msg_session_expired
                            isStallFailure -> R.string.msg_transfer_stalled
                            else -> R.string.msg_connection_lost
                        }
                    )
                )

                if (isStallFailure) {
                    // Stall failure - informative only with single dismiss button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.button_dismiss))
                        }
                    }
                } else if (!isAuthFailure) {
                    // Network failure - show vertically stacked buttons
                    // Order: secondary (top) → primary (bottom, easiest thumb reach)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 明确出口①：先关掉弹窗，继续用当前界面（后台重连不受影响）
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_try_later))
                        }

                        // 明确出口②：返回登录页
                        OutlinedButton(
                            onClick = onReturnToLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_return_to_login))
                        }

                        // Primary: Reconnect (filled button, bottom - easiest thumb reach)
                        // 仅本按钮在重连期间禁用；其余出口始终可点，杜绝"转圈后被卡死"。
                        Button(
                            onClick = onReconnect,
                            enabled = !isReconnecting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isReconnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_reconnecting))
                            } else {
                                Text(stringResource(R.string.action_reconnect))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // For auth failure, show single button + explicit "稍后再试" exit
            if (isAuthFailure) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_try_later))
                    }
                    Button(onClick = onReturnToLogin) {
                        Text(stringResource(R.string.action_return_to_login))
                    }
                }
            }
            // For network/stall failure, buttons are in the text content above
        }
    )
}

/**
 * 顶部非阻塞连接横幅（P3）。
 *
 * 弱网下"连接中断"不该用模态框拦人：这里只占一条细边，界面与数据完全保留，
 * 重连成功后随 [visible] 变 false 自动消失（调用方用 WebSocket 状态驱动）。
 */
@Composable
fun ConnectionStatusBanner(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
