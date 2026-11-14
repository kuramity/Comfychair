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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.ripple.RippleIconButton

/**
 * 生成页顶部的"释放模型内存"按钮：确认后调用服务器 /free 卸载全部模型权重。
 * 与 ComfyUI 网页端的 Unload Models 行为一致。
 */
@Composable
fun FreeMemoryButton() {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 波纹点阵反馈（v4.9.x）：包装层只观察指针（Initial pass、不 consume），确认弹窗与
    // /free 请求逻辑完全不变；图标 22dp 对齐顶栏裸图标规格（v2 皮肤）
    RippleIconButton(onClick = { showConfirm = true }) {
        Icon(
            Icons.Outlined.Eject,
            contentDescription = context.getString(R.string.button_free_memory),
            modifier = Modifier.size(22.dp)
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
