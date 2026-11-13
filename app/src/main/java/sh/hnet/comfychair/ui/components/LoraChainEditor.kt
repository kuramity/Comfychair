package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.ui.components.config.LORA_NONE_LABEL
import sh.hnet.comfychair.ui.components.shared.HierarchicalTreeItems
import sh.hnet.comfychair.ui.components.shared.LoraStrengthValue
import sh.hnet.comfychair.ui.components.shared.ModelPathText
import sh.hnet.comfychair.ui.components.shared.buildModelTree
import sh.hnet.comfychair.ui.components.shared.folderPathOf
import sh.hnet.comfychair.ui.components.shared.modelTreeHasFolders

/**
 * Reusable component for editing a LoRA chain (0-5 LoRAs with per-LoRA strength)
 */
@Composable
fun LoraChainEditor(
    title: String,
    loraChain: List<LoraSelection>,
    availableLoras: List<String>,
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header with title and add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )

            TextButton(
                onClick = onAddLora,
                enabled = loraChain.size < LoraSelection.MAX_CHAIN_LENGTH && availableLoras.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.button_add_lora))
            }
        }

        if (loraChain.isEmpty()) {
            // Empty state
            Text(
                text = stringResource(R.string.msg_lora_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // LoRA entries
            loraChain.forEachIndexed { index, lora ->
                key(lora.name, index) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LoraEntryItem(
                        index = index,
                        lora = lora,
                        availableLoras = availableLoras,
                        onNameChange = { name -> onLoraNameChange(index, name) },
                        onStrengthChange = { strength -> onLoraStrengthChange(index, strength) },
                        onRemove = { onRemoveLora(index) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraEntryItem(
    index: Int,
    lora: LoraSelection,
    availableLoras: List<String>,
    onNameChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val tree = remember(availableLoras) { buildModelTree(availableLoras) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(lora.name) { mutableStateOf(folderPathOf(lora.name)) }
    // 空名 = 该项不使用 LoRA（新增项默认如此），显示成与主 LoRA 下拉一致的文案；
    // 提交阶段由 LoraInjectionUtils 跳过空名项，不会把空值发给服务器
    val displayName = if (lora.name.isEmpty()) LORA_NONE_LABEL else lora.name

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // LoRA selection row with dropdown and remove button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index indicator
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(24.dp)
            )

            // LoRA dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = {
                    expanded = it
                    if (it) expandedPath = folderPathOf(lora.name)
                },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_lora)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    // 「不使用 LoRA」：把该项置空，提交阶段跳过注入（不会建 LoraLoader 节点）
                    DropdownMenuItem(
                        text = { Text(LORA_NONE_LABEL) },
                        onClick = {
                            onNameChange("")
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                    if (!hasFolders) {
                        availableLoras.forEach { option ->
                            DropdownMenuItem(
                                // 折行：LoRA 名普遍长（「触发词：（xxx）中文名」），单行必被截断
                                text = { ModelPathText(option, maxLines = 2) },
                                onClick = {
                                    onNameChange(option)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    } else {
                        HierarchicalTreeItems(
                            nodes = tree,
                            expandedPath = expandedPath,
                            selectedValue = lora.name,
                            depth = 0,
                            onExpandFolder = { expandedPath = it },
                            onSelectModel = {
                                onNameChange(it)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.button_remove_lora)
                )
            }
        }

        // Strength slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.label_lora_strength),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(64.dp)
            )

            Slider(
                value = lora.strength,
                // 量化在回调里做，显示值 == 提交值（拖 0~10 的行程也凑不出 1.35 这种值）
                onValueChange = { onStrengthChange(LoraSelection.sanitizeStrength(it)) },
                valueRange = LoraSelection.MIN_STRENGTH..LoraSelection.MAX_STRENGTH,
                modifier = Modifier.weight(1f)
            )

            // 点数字手动输入精确权重
            LoraStrengthValue(
                strength = lora.strength,
                onStrengthChange = onStrengthChange,
                modifier = Modifier.width(44.dp)
            )
        }
    }
}
