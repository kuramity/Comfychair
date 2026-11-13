package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraSelection

/**
 * 单条 LoRA 权重滑块（0.0~10.0），用于主 LoRA 下拉下方。
 *
 * 取值区间沿用 LoRA 链的 [LoraSelection.MIN_STRENGTH]..[LoraSelection.MAX_STRENGTH]，
 * 与 LoRA 链编辑器里的强度滑块保持同一个量纲（用户不必记两套范围）。
 *
 * 右侧数字是可点击的：[LoraStrengthValue] —— 手动输入精确值（拖滑块在 0~10 的行程上凑不准小数）。
 *
 * 只用于「一个下拉 + 一个权重」的主 LoRA：链条里的每项权重由 LoraChainEditor 自己渲染。
 *
 * @param value 当前权重
 * @param onValueChange 拖动回调
 * @param modifier Modifier
 */
@Composable
fun LoraStrengthRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.label_lora_strength),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp)
        )

        Slider(
            value = value,
            // 量化在回调里做：滑块位置完全由 value 驱动，量化后显示值 == 提交值
            onValueChange = { onValueChange(LoraSelection.sanitizeStrength(it)) },
            valueRange = LoraSelection.MIN_STRENGTH..LoraSelection.MAX_STRENGTH,
            modifier = Modifier.weight(1f)
        )

        LoraStrengthValue(
            strength = value,
            onStrengthChange = onValueChange,
            modifier = Modifier.width(44.dp)
        )
    }
}
