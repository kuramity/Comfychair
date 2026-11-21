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
 * 更新弹窗的宿主（v4.6.0，v4.18.0 按 R1 收紧）。
 *
 * 弹一次的语义（现在由两层共同保证）：
 * 1. **跨进程**：`UpdateRepository.autoCheckIfDue` 只在「该 versionCode 没主动弹过」时
 *    才把状态置 AVAILABLE，并把配额写进 SharedPreferences
 *    （[UpdatePromptPolicy] / [UpdatePromptStore]）。换进程、杀后台、重连都不会重置。
 * 2. **本进程**：用户点「稍后」后立刻 [UpdateRepository.reset] 把状态收回 IDLE，
 *    界面不会因为重组/旋转再次冒出来；要再看只能自己去点「检查更新」（手动不受配额限制）。
 *
 * 状态翻转（AVAILABLE → 其他 → 又 AVAILABLE，例如用户手动检查出更高版本）会重新武装弹窗。
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
        onDismiss = {
            dismissedForThisAvailability = true
            // R1：忽略 = 放弃本次提示。状态收回 IDLE 后，同一版本不会再主动弹；
            // 用户想更新时自己点「检查更新」即可（手动检查不受配额限制）。
            if (UpdateRepository.state.value.phase == UpdatePhase.AVAILABLE) {
                UpdateRepository.reset()
            }
        }
    )
}