package sh.hnet.comfychair.ui.components.shared

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager

/**
 * 生成页顶部的"释放模型内存"按钮：确认后调用服务器 /free 卸载全部模型权重。
 * 与 ComfyUI 网页端的 Unload Models 行为一致。
 */
@Composable
fun FreeMemoryButton() {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    IconButton(onClick = { showConfirm = true }) {
        Icon(
            Icons.Outlined.Eject,
            contentDescription = context.getString(R.string.button_free_memory)
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(context.getString(R.string.free_memory_confirm_title)) },
            text = { Text(context.getString(R.string.free_memory_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    if (busy) return@TextButton
                    busy = true
                    val client = ConnectionManager.clientOrNull
                    if (client == null) {
                        busy = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.error_no_connection),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        client.freeMemory { success, msg ->
                            // OkHttp 回调在工作线程：UI 状态与 Toast 必须切回主线程
                            Handler(Looper.getMainLooper()).post {
                                busy = false
                                val text = if (success) {
                                    context.getString(R.string.free_memory_success)
                                } else {
                                    context.getString(R.string.free_memory_failed, msg ?: "?")
                                }
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text(context.getString(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(context.getString(R.string.action_cancel))
                }
            }
        )
    }
}
