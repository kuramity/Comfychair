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
import sh.hnet.comfychair.connection.ModelListUiState
import sh.hnet.comfychair.connection.resolveModelListUiState
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

    // 模型列表的四态（Q3-3，v4.16.5）：加载中 / 可用 / 空（服务器真没模型）/
    // 失败（可重试）。旧实现只区分"加载中"与"空"，/object_info 失败后下拉是
    // 一个沉默的空框，与"服务器没模型"无法区分。判定抽成纯函数
    // resolveModelListUiState（connection 包），JVM 单测逐条钉住
    val cache by ConnectionManager.modelCache.collectAsState()
    val listState = resolveModelListUiState(
        isLoading = cache.isLoading,
        isLoaded = cache.isLoaded,
        lastError = cache.lastError,
        optionCount = options.size
    )

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
            value = if (selectedValue.isBlank() && cache.isLoading) {
                stringResource(R.string.model_list_loading)
            } else if (selectedValue.isBlank() && listState == ModelListUiState.FAILED) {
                // 没有选中值时把失败写进字段本身：不打开下拉也能看到"出问题了"，
                // 而不是一个看似正常的空框。已选中旧数据的字段不受影响（选项照常）
                stringResource(R.string.model_list_load_failed)
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
            if (options.isEmpty() && cache.isLoading) {
                // 展开时数据还没到 —— 明确告诉用户在加载，而不是给一个空菜单
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_list_loading)) },
                    onClick = {},
                    enabled = false,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            } else if (options.isEmpty() && listState == ModelListUiState.EMPTY) {
                // 服务器真的没有模型：明确告知，不显示重试（重试也拉不出东西，
                // 与"失败"严格区分是本态存在的意义）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_list_empty)) },
                    onClick = {},
                    enabled = false,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            } else if (listState == ModelListUiState.FAILED && options.isEmpty()) {
                // 失败且没有任何可显示数据（连磁盘预填都没有）：失败说明 + 重试。
                // 点击复用 fetchServerData 的同一入口（其 SingleFlight 闸门保证
                // 与 onResume 补拉 / 连接链路不会并发堆请求）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_list_load_failed)) },
                    onClick = {
                        expanded = false
                        ConnectionManager.refreshServerData()
                    },
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

            if (listState == ModelListUiState.FAILED && options.isNotEmpty()) {
                // 刷新失败但磁盘预填的旧数据仍可显示（失败不清列表）：选项照常用，
                // 菜单尾附一行失败 + 重试——数据可见性与"出了问题"的告知两不误
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.model_list_load_failed)) },
                    onClick = {
                        expanded = false
                        ConnectionManager.refreshServerData()
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
