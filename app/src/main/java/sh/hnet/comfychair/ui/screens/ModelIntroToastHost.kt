package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import sh.hnet.comfychair.viewmodel.ModelIntroViewModel

/**
 * 介绍库相关的 Toast 收集器（浏览页与「已卸载」页共用）。
 *
 * 文案由 [ModelIntroViewModel] 以资源 id + 格式参数给出（`viewmodel` 包不得 import `ui`，
 * 见 `ViewModelUiDependencyTest`），这里统一拼串并弹出——两处各写一遍迟早漂移。
 */
@Composable
internal fun ModelIntroToastHost(viewModel: ModelIntroViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ModelIntroViewModel.Event.Toast -> {
                    val text = if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageResId)
                    } else {
                        context.getString(event.messageResId, *event.formatArgs.toTypedArray())
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}