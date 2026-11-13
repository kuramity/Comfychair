package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider

/**
 * 种子行：取值方式（固定/随机/每次+1/每次−1）+ 种子输入框 + 随机化按钮。
 *
 * 约束从工作流里实际映射的节点动态读取。
 *
 * 布局刻意分两行：四项模式选择独占一行（文字标签比图标好认 —— "随机"与"每次+1"
 * 用图标区分度太低），下面一行才是输入框与骰子按钮。挤在一行的话，
 * 四个分段加输入框在手机上会把种子数字压到看不清。
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param seedMode 当前取值方式
 * @param onSeedModeChange 切换取值方式
 * @param seed Current seed value
 * @param onSeedChange Callback when seed changes
 * @param onRandomizeSeed Callback when randomize button is clicked
 * @param seedError Error message for seed field
 * @param modifier Modifier for the row
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SeedRow(
    workflowName: String,
    seedMode: SeedMode,
    onSeedModeChange: (SeedMode) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    onRandomizeSeed: () -> Unit,
    seedError: String?,
    modifier: Modifier = Modifier
) {
    val constraints = WorkflowConstraintsProvider.rememberConstraints("seed", workflowName)

    Column(modifier = modifier.fillMaxWidth()) {
        // 取值方式：四选一。随机时输入框禁用（那个值不参与），其余三种都要能填
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SeedMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = seedMode == mode,
                    onClick = { onSeedModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SeedMode.entries.size
                    )
                ) {
                    Text(
                        text = stringResource(seedModeLabelRes(mode)),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumericStepperField(
                value = seed,
                onValueChange = onSeedChange,
                label = stringResource(R.string.label_seed),
                min = constraints.min,
                max = constraints.max,
                step = constraints.step,
                decimalPlaces = constraints.decimalPlaces,
                error = seedError,
                enabled = seedMode.isSeedEditable,
                modifier = Modifier.weight(1f)
            )
            // Randomize button
            OutlinedIconButton(
                onClick = onRandomizeSeed,
                enabled = seedMode.isSeedEditable,
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (seedMode.isSeedEditable) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Casino,
                    contentDescription = stringResource(R.string.label_randomize_seed)
                )
            }
        }
    }
}

private fun seedModeLabelRes(mode: SeedMode): Int = when (mode) {
    SeedMode.FIXED -> R.string.seed_mode_fixed
    SeedMode.RANDOM -> R.string.seed_mode_random
    SeedMode.INCREMENT -> R.string.seed_mode_increment
    SeedMode.DECREMENT -> R.string.seed_mode_decrement
}
