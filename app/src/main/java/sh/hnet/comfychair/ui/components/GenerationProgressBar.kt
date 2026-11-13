package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Progress bar for generation screens. Shows progress when generation is active.
 * Uses Material 3 wavy progress indicator for a modern look.
 *
 * 参数刻意收 **lambda**（不是值）：生成期间每个采样步都会更新进度，若在调用方页面顶层
 * 直接读 `state.progress` 传进来，每次进度变化都会让整个页面 body 重组一遍。传 lambda
 * 则把读取推迟到进度指示器自己的作用域（它本来就在绘制阶段读），其它内容不再跟着重组。
 *
 * @param progress 取当前进度（0 至 maxProgress）
 * @param maxProgress 取进度上限
 * @param modifier Modifier for positioning
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationProgressBar(
    progress: () -> Int,
    maxProgress: () -> Int,
    modifier: Modifier = Modifier
) {
    LinearWavyProgressIndicator(
        progress = {
            val max = maxProgress()
            if (max > 0) {
                progress().toFloat() / max
            } else {
                0f
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    )
}
