package sh.hnet.comfychair.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sh.hnet.comfychair.ui.components.shared.UpdateDialog

/**
 * 更新弹窗的宿主（v4.6.0）：挂在主界面顶层，自动静默检查的结果在这里承接。
 *
 * 弹一次的语义：AVAILABLE 状态**首次**出现时弹出；用户点"稍后"关掉后，
 * 只要还停留在这次 AVAILABLE 就不再弹（避免打扰）；状态翻转（AVAILABLE →
 * 其他 → 又 AVAILABLE，例如再次检查出新版本）会重新武装弹窗。
 */
@Composable
fun UpdateDialogHost() {
    val state by UpdateRepository.state.collectAsStateWithLifecycle()
    var dismissedForThisAvailability by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        if (state.phase == UpdatePhase.AVAILABLE) {
            dismissedForThisAvailability = false
        }
    }

    UpdateDialog(
        showDialog = state.phase == UpdatePhase.AVAILABLE && !dismissedForThisAvailability,
        onDismiss = { dismissedForThisAvailability = true }
    )
}
