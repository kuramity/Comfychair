package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.CoreCategory
import sh.hnet.comfychair.connection.ModelListUiState
import sh.hnet.comfychair.connection.resolveModelListUiState
import sh.hnet.comfychair.connection.stateOf
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
    modifier: Modifier = Modifier,
    /**
     * G8 指令 1 的接线：UNET / VAE / CLIP 三类下拉传各自的类别，四态就按**这一类**的
     * 终态判定；其余下拉传 null，沿用整体四态（行为与历史一致）。
     */
    category: CoreCategory? = null
) {
    var expanded by remember { mutableStateOf(false) }

    // 模型列表的四态（Q3-3，v4.16.5）：加载中 / 可用 / 空（服务器真没模型）/
    // 失败（可重试）。旧实现只区分"加载中"与"空"，/object_info 失败后下拉是
    // 一个沉默的空框，与"服务器没模型"无法区分。判定抽成纯函数
    // resolveModelListUiState（connection 包），JVM 单测逐条钉住
    val cache by ConnectionManager.modelCache.collectAsStateWithLifecycle()
    // G8：分类态优先。UNET 单独失败时不该把 VAE / CLIP 一起标成失败，
    // UNET 也不该被整体的「还在加载」拖成永久「正在加载」（E7 的观感正是这么来的）
    val listState = if (category != null) {
        resolveModelListUiState(
            state = cache.stateOf(category),
            optionCount = options.size
        )
    } else {
        resolveModelListUiState(
            isLoading = cache.isLoading,
            isLoaded = cache.isLoaded,
            lastError = cache.lastError,
            optionCount = options.size
        )
    }

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
            // 三个分支一律按 listState 判，不再直接读整体的 cache.isLoading（G8 指令 2）：
            // 分类下拉只看自己那一类是否还在加载，别的类卡住不会把它拖成「正在加载…」
            value = if (selectedValue.isBlank() && listState == ModelListUiState.LOADING) {
                stringResource(R.string.model_list_loading)
            } else if (selectedValue.isBlank() && listState == ModelListUiState.FAILED) {
                // 没有选中值时把失败写进字段本身：不打开下拉也能看到"出问题了"，
                // 而不是一个看似正常的空框。已选中旧数据的字段不受影响（选项照常）
                stringResource(R.string.model_list_load_failed)
            } else if (selectedValue.isBlank() && listState == ModelListUiState.EMPTY && category != null) {
                // G7 指令 2「禁止静默留空」：这一类真没模型时，收起状态下也要写明，
                // 而不是留一个空白框让人以为加载坏了（E6「UNET 行空值」就是这么显示的）。
                // 只对**分类下拉**生效：采样器 / 分辨率等非模型列表用「没有可用模型」
                // 描述会是错的措辞，它们维持原样（展开时才提示）。
                stringResource(R.string.model_list_empty)
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

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (options.isEmpty() && listState == ModelListUiState.LOADING) {
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
                        // G8 指令 1：分类下拉的重试入口就是「单独重试这一类」
                        ConnectionManager.retryCoreCategory(category)
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
                        ConnectionManager.retryCoreCategory(category)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
