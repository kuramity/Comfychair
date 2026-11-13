package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable

/**
 * A dropdown component for selecting models (checkpoints, UNETs, VAEs, CLIPs, etc.)
 *
 * Automatically switches between flat and hierarchical (folder tree) display based on
 * whether any option contains path separators.
 *
 * Hierarchical behavior:
 * - Folders shown first, then root-level models, both sorted alphabetically
 * - Clicking a folder expands it (accordion: collapses siblings at the same level)
 * - Re-opening auto-expands to the currently selected model's folder
 * - Selected model highlighted in primary color
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // 模型列表是否还在从服务器加载：值为空时用它显示「正在加载…」——
    // 那个 /object_info 有 8.1MB，慢网下要等十几秒，留一个沉默的空框用户会以为坏了
    val loading by ConnectionManager.modelCache.collectAsState()

    val tree = remember(options) { buildModelTree(options) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(selectedValue) { mutableStateOf(folderPathOf(selectedValue)) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) expandedPath = folderPathOf(selectedValue)
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (selectedValue.isBlank() && loading.isLoading) {
                stringResource(R.string.model_list_loading)
            } else {
                selectedValue
            },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
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
            if (options.isEmpty() && loading.isLoading) {
                // 展开时数据还没到 —— 明确告诉用户在加载，而不是给一个空菜单
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_list_loading)) },
                    onClick = {},
                    enabled = false,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            } else if (!hasFolders) {
                options.forEach { option ->
                    DropdownMenuItem(
                        // 只显示名字：附加的副标题会把长文件名挤掉，而"看介绍"已经交给
                        // 设置区的「模型介绍」页了
                        text = { ModelPathText(option, maxLines = 2) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            } else {
                HierarchicalTreeItems(
                    nodes = tree,
                    expandedPath = expandedPath,
                    selectedValue = selectedValue,
                    depth = 0,
                    onExpandFolder = { expandedPath = it },
                    onSelectModel = {
                        onValueChange(it)
                        expanded = false
                    }
                )
            }
        }
    }
}
