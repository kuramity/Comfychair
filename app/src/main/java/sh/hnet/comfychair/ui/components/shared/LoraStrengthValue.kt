package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraSelection

/**
 * 滑块右侧的权重数字：**点一下就能手动输入精确值**。
 *
 * 0~10 的范围意味着滑块整条行程被拉长，靠拖动凑出 1.35 这种值基本拖不准，
 * 所以数字本身做成入口 —— 外形仍是数字（不占额外空间），点击弹出 [LoraStrengthDialog]。
 *
 * 文本一律走 [LoraSelection.formatStrength]，与滑块显示的精度是同一套规则。
 *
 * @param strength 当前权重
 * @param onStrengthChange 确认后的回调（值已夹紧量化）
 * @param modifier Modifier（宽度由调用方决定，两处工具栏留白不同）
 */
@Composable
fun LoraStrengthValue(
    strength: Float,
    onStrengthChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }

    Text(
        text = LoraSelection.formatStrength(strength),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.End,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { editing = true }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )

    if (editing) {
        LoraStrengthDialog(
            initial = strength,
            onConfirm = {
                onStrengthChange(it)
                editing = false
            },
            onDismiss = { editing = false }
        )
    }
}

/**
 * 手动输入权重值的对话框。
 *
 * 校验分两档（[LoraSelection.parseStrength] 只判"是不是数"、[LoraSelection.isStrengthInRange] 判越界）：
 * 打错字与填超范围要给出不同的提示，否则用户不知道是该改格式还是改数值。
 * 非法时确定按钮禁用 —— 不允许把一个"将被静默夹紧"的值提交上去。
 */
@Composable
fun LoraStrengthDialog(
    initial: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(LoraSelection.formatStrength(initial)) }
    val parsed = LoraSelection.parseStrength(text)
    val rangeHint = stringResource(
        R.string.lora_strength_range_hint,
        LoraSelection.formatStrength(LoraSelection.MIN_STRENGTH),
        LoraSelection.formatStrength(LoraSelection.MAX_STRENGTH)
    )

    val error = when {
        parsed == null -> stringResource(R.string.error_invalid_number)
        !LoraSelection.isStrengthInRange(parsed) -> rangeHint
        else -> null
    }
    val canConfirm = parsed != null && error == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_lora_strength_input)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.label_lora_strength)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Text(
                            text = error ?: rangeHint,
                            color = if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(LoraSelection.sanitizeStrength(it)) } },
                enabled = canConfirm
            ) {
                Text(stringResource(R.string.button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
