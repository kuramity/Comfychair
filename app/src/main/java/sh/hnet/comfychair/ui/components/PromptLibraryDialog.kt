package sh.hnet.comfychair.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer

/**
 * Full-screen dialog for managing the prompt library.
 * Shows search, tag filters, and a list of all presets.
 * User selects a preset then confirms with OK button.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PromptLibraryDialog(
    presets: List<PromptPreset>,
    availableTags: Set<String>,
    searchQuery: String,
    selectedTags: Set<String>,
    filterFavoritesOnly: Boolean,
    activePresetId: String?,
    onSearchQueryChange: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onPresetSelected: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onDuplicatePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Local selection state - initialize with currently active preset
    var selectedPresetId by remember { mutableStateOf(activePresetId) }

    // Tag filter expansion state
    var tagsExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // Title
                Text(
                    text = stringResource(R.string.title_prompt_library),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Search bar with favorites filter toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                onSearch = { },
                                expanded = false,
                                onExpandedChange = { },
                                placeholder = { Text(stringResource(R.string.placeholder_prompt_library_search)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { onSearchQueryChange("") }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.button_clear_search)
                                            )
                                        }
                                    }
                                } else null
                            )
                        },
                        expanded = false,
                        onExpandedChange = { },
                        modifier = Modifier.weight(1f)
                    ) { }

                    // Favorites filter toggle
                    FilledTonalIconToggleButton(
                        checked = filterFavoritesOnly,
                        onCheckedChange = { onToggleFavoritesFilter() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (filterFavoritesOnly) {
                                Icons.Filled.Star
                            } else {
                                Icons.Outlined.StarOutline
                            },
                            contentDescription = stringResource(R.string.prompt_library_filter_favorites)
                        )
                    }
                }

                // Tag filter chips (expandable)
                if (availableTags.isNotEmpty()) {
                    val sortedTags = availableTags.sorted()
                    val canExpand = sortedTags.size > 3

                    if (tagsExpanded) {
                        // Expanded mode: wrapping flow layout with collapse button first
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Collapse button first
                            if (canExpand) {
                                FilledTonalIconButton(
                                    onClick = { tagsExpanded = false }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = null
                                    )
                                }
                            }
                            sortedTags.forEach { tag ->
                                key(tag) {
                                    FilterChip(
                                        selected = tag in selectedTags,
                                        onClick = { onTagToggle(tag) },
                                        label = { Text(tag) },
                                        leadingIcon = if (tag in selectedTags) {
                                            {
                                                Icon(
                                                    Icons.Default.Done,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    } else {
                        // Collapsed mode: horizontal scrolling with expand button first
                        NoOverscrollContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Expand button first
                                if (canExpand) {
                                    item(key = "expand_chip") {
                                        FilledTonalIconButton(
                                            onClick = { tagsExpanded = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null
                                            )
                                        }
                                    }
                                }
                                items(
                                    items = sortedTags,
                                    key = { "chip_$it" }
                                ) { tag ->
                                    FilterChip(
                                        selected = tag in selectedTags,
                                        onClick = { onTagToggle(tag) },
                                        label = { Text(tag) },
                                        leadingIcon = if (tag in selectedTags) {
                                            {
                                                Icon(
                                                    Icons.Default.Done,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                // Preset list
                if (presets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.msg_prompt_library_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    NoOverscrollContainer(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            items(presets, key = { it.id }) { preset ->
                                PresetListItem(
                                    preset = preset,
                                    isSelected = preset.id == selectedPresetId,
                                    onSelect = { selectedPresetId = preset.id },
                                    onToggleFavorite = { onToggleFavorite(preset.id) },
                                    onEdit = { onEditPreset(preset.id) },
                                    onDuplicate = { onDuplicatePreset(preset.id) },
                                    onDelete = { onDeletePreset(preset.id) }
                                )
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.button_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectedPresetId?.let { onPresetSelected(it) }
                            onDismiss()
                        }
                    ) {
                        Text(
                            stringResource(
                                if (selectedPresetId != null) {
                                    R.string.button_apply
                                } else {
                                    R.string.button_done
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

/** 剪贴板标签（系统会在剪贴板预览里显示它，标明这段内容的来源用途） */
private const val PROMPT_CLIP_LABEL = "prompt"

/**
 * 把提示词写入系统剪贴板。
 *
 * 刻意**不**给内容打 `ClipDescription.EXTRA_IS_SENSITIVE`：提示词不是密码类内容，
 * 一旦标记，系统会隐藏剪贴板预览，用户会以为"没复制成功"—— 那正是本功能要解决的困扰。
 *
 * 返回是否成功。失败时调用方退回 [ManualCopyDialog]，让用户至少还能长按手动拷贝，
 * 而不是只丢一句无能为力的报错。
 */
private fun copyPromptToClipboard(context: Context, prompt: String): Boolean = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        false
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText(PROMPT_CLIP_LABEL, prompt))
        true
    }
} catch (e: Exception) {
    false
}

/**
 * 兜底对话框：自动写剪贴板失败时，把提示词摊在一个可长按选中的区域里让用户手动复制。
 */
@Composable
private fun ManualCopyDialog(prompt: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_copy_prompt_failed)) },
        text = {
            SelectionContainer {
                Text(text = prompt, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

/**
 * Individual preset item in the library list.
 */
@Composable
private fun PresetListItem(
    preset: PromptPreset,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    // 直接写剪贴板失败时（极罕见）退回"长按手动复制"的兜底，而不是只丢一句报错。
    // 非 null 表示当前要手动复制的那段文本（正面或负面，取决于点的是哪一项）
    var manualCopyText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    manualCopyText?.let { text ->
        ManualCopyDialog(prompt = text, onDismiss = { manualCopyText = null })
    }

    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favorite star toggle
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (preset.isFavorite) {
                        Icons.Filled.Star
                    } else {
                        Icons.Outlined.StarOutline
                    },
                    contentDescription = stringResource(R.string.prompt_preset_toggle_favorite),
                    tint = if (preset.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (isSelected) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preset.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isSelected) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 负面只在有内容时显示（它可以是空的，见 PromptPreset.negativePrompt）。
                // 用另一档颜色与前缀标出来，让用户一眼看出这条预设带了负面
                if (preset.negativePrompt.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.prompt_preset_negative_line, preset.negativePrompt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = if (isSelected) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (preset.tags.isNotEmpty()) {
                    Text(
                        text = preset.tags.sorted().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = if (isSelected) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Overflow menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.button_more_options)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    // 复制到剪贴板：只写系统剪贴板，不碰任何存储。
                    // 与下面那项「另存为副本」是两件事 —— 后者会在提示词库里新增一条同名条目。
                    //
                    // 正面与负面**分成两项**：剪贴板一次只能放一段内容，拆成两项才能让用户
                    // 各点一次、各拿到一段（点一次就往剪贴板塞两段的话，后者会覆盖前者，
                    // 用户永远只能粘出最后那一段）。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_preset_copy_positive)) },
                        onClick = {
                            showMenu = false
                            if (copyPromptToClipboard(context, preset.prompt)) {
                                Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            } else {
                                manualCopyText = preset.prompt
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_preset_copy_negative)) },
                        onClick = {
                            showMenu = false
                            // 负面为空时明确说一句，而不是往剪贴板写一段空内容 ——
                            // 那样用户以为复制成功了，粘出来却发现什么都没有
                            if (preset.negativePrompt.isBlank()) {
                                Toast.makeText(context, R.string.msg_no_negative_prompt, Toast.LENGTH_SHORT).show()
                            } else if (copyPromptToClipboard(context, preset.negativePrompt)) {
                                Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            } else {
                                manualCopyText = preset.negativePrompt
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FileCopy, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_preset_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_preset_duplicate)) },
                        onClick = {
                            showMenu = false
                            onDuplicate()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FileCopy, contentDescription = null)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.prompt_preset_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}
