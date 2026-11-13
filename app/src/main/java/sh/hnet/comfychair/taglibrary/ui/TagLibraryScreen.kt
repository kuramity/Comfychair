package sh.hnet.comfychair.taglibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.comfychair.R
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.taglibrary.state.TagLibraryViewModel
import java.util.Locale

/**
 * Danbooru 标签库主页面（需求 3.2）。
 *
 * 布局自上而下：常驻搜索框 → 搜索结果列表（虚拟滚动）→ 底部构建器。
 * 未绑定生成页面时，构建器区域给出提示且添加按钮不写入提示词。
 */
@Composable
fun TagLibraryScreen(
    viewModel: TagLibraryViewModel,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // 懒初始化：只有真正进入标签库页面才解压数据库、构建内存索引，
    // 避免 App 启动时占用 CPU/IO 拖慢图片库加载。
    LaunchedEffect(Unit) {
        viewModel.ensureInitialized()
    }

    // 事件提示（Toast）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is TagLibraryViewModel.Event.NoTarget ->
                    context.getString(R.string.tag_toast_no_target)
                is TagLibraryViewModel.Event.Added ->
                    context.getString(R.string.tag_toast_added, event.tagName)
                is TagLibraryViewModel.Event.Inserted ->
                    context.getString(R.string.tag_inserted, event.segmentIndex + 1)
                is TagLibraryViewModel.Event.Copied ->
                    context.getString(R.string.tag_copied)
                is TagLibraryViewModel.Event.CopyFailed ->
                    context.getString(R.string.tag_copy_failed)
                is TagLibraryViewModel.Event.InitFailed ->
                    context.getString(R.string.tag_error_init, event.message)
            }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // "插入到指定位置"：记录待插入的标签与目标通道，非空时弹出位置选择对话框
    var pendingInsert by remember { mutableStateOf<Pair<String, PromptBridge.Channel>?>(null) }

    pendingInsert?.let { (tagName, channel) ->
        val segments = remember(pendingInsert) { viewModel.segmentsOf(channel) }
        val channelLabel = stringResource(
            if (channel == PromptBridge.Channel.POSITIVE) {
                R.string.tag_insert_channel_positive
            } else {
                R.string.tag_insert_channel_negative
            }
        )

        AlertDialog(
            onDismissRequest = { pendingInsert = null },
            title = { Text(stringResource(R.string.tag_insert_title, tagName, channelLabel)) },
            text = {
                if (segments.isEmpty()) {
                    Text(stringResource(R.string.tag_insert_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 每一行都是"插到它之后"；第 0 行是"插到最前面"
                        Text(
                            text = stringResource(R.string.tag_insert_at_start),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.insertTagAt(channel, tagName, 0)
                                    pendingInsert = null
                                }
                                .padding(vertical = 10.dp)
                        )
                        segments.forEachIndexed { index, segment ->
                            Text(
                                text = stringResource(R.string.tag_insert_after, segment),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.insertTagAt(channel, tagName, index + 1)
                                        pendingInsert = null
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingInsert = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // ---------- 搜索框 ----------
        OutlinedTextField(
            value = uiState.keyword,
            onValueChange = viewModel::onKeywordChange,
            placeholder = { Text(stringResource(R.string.tag_search_hint)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (uiState.keyword.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearKeyword() }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.tag_search_clear)
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    viewModel.searchNow()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ---------- 分类过滤 ----------
        CategoryFilterRow(
            selected = uiState.selectedCategory,
            onSelect = viewModel::onCategoryChange
        )

        // ---------- 最近使用（仅空关键词时给快捷入口，点一下即用它当关键词） ----------
        if (uiState.keyword.isBlank() && uiState.recents.isNotEmpty()) {
            RecentsRow(recents = uiState.recents.take(12), onPick = viewModel::onKeywordChange)
        }

        // ---------- 只看收藏 ----------
        FavoritesFilterRow(
            favoritesOnly = uiState.favoritesOnly,
            favoriteCount = uiState.favorites.size,
            onToggle = { viewModel.setFavoritesOnly(!uiState.favoritesOnly) }
        )

        // ---------- 内容区 ----------
        Box(modifier = Modifier.weight(1f)) {
            when {
                // 初始化失败：错误提示 + 重试（需求 8.4）
                uiState.errorMessage != null -> InitErrorState(
                    message = uiState.errorMessage!!,
                    onRetry = viewModel::retryInitialize
                )

                // 首次加载中
                !uiState.isDbReady -> LoadingState(
                    text = stringResource(R.string.tag_loading_db)
                )

                // 搜索中（且无既有结果）
                uiState.isSearching && uiState.results.isEmpty() -> LoadingState(
                    text = stringResource(R.string.tag_searching)
                )

                // 空态
                uiState.results.isEmpty() -> EmptyState(
                    text = stringResource(
                        if (uiState.showEmptyHint) R.string.tag_empty_hint
                        else R.string.tag_empty_no_result
                    )
                )

                // 结果列表
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.results, key = { it.name }) { tag ->
                        TagResultRow(
                            tag = tag,
                            enabled = true,
                            isFavorite = tag.name in uiState.favorites,
                            onToggleFavorite = { viewModel.toggleFavorite(tag.name) },
                            onTagClick = { onTagClick(tag.name) },
                            onAddPositive = {
                                viewModel.addTag(PromptBridge.Channel.POSITIVE, tag.name)
                            },
                            onAddNegative = {
                                viewModel.addTag(PromptBridge.Channel.NEGATIVE, tag.name)
                            },
                            onPickPosition = { channel ->
                                pendingInsert = tag.name to channel
                            },
                            onCopy = { viewModel.copyTagToClipboard(tag.name) }
                        )
                    }
                }
            }
        }

        // ---------- 底部构建器 ----------
        TagBuilderPanel(
            expanded = uiState.builderExpanded,
            positiveTags = uiState.positiveTags,
            negativeTags = uiState.negativeTags,
            hasTarget = uiState.hasTarget,
            onToggle = viewModel::toggleBuilder,
            onWeightChange = viewModel::updateTagWeight,
            onRemove = viewModel::removeTag
        )
    }
}

/** 「最近使用」快捷行：点一下即用它当关键词（仅空关键词时显示）。 */
@Composable
private fun RecentsRow(
    recents: List<String>,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.tag_recents_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (name in recents) {
                AssistChip(
                    onClick = { onPick(name) },
                    label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

/** 「只看收藏」开关（与关键词、分类筛选叠加）。 */
@Composable
private fun FavoritesFilterRow(
    favoritesOnly: Boolean,
    favoriteCount: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        FilterChip(
            selected = favoritesOnly,
            onClick = onToggle,
            label = {
                Text(
                    text = stringResource(R.string.tag_favorites_only, favoriteCount),
                    style = MaterialTheme.typography.labelSmall
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}

/** 分类过滤行。 */
@Composable
private fun CategoryFilterRow(
    selected: TagCategory?,
    onSelect: (TagCategory?) -> Unit
) {
    val categories = remember { listOf(null) + TagCategory.entries }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (category in categories) {
            val isSelected = category == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(category) },
                label = {
                    Text(
                        text = if (category == null) stringResource(R.string.tag_category_all)
                        else stringResource(category.labelRes),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

/**
 * 单条搜索结果（需求 4.2.1）。
 */
@Composable
private fun TagResultRow(
    tag: Tag,
    enabled: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTagClick: () -> Unit,
    onAddPositive: () -> Unit,
    onAddNegative: () -> Unit,
    onPickPosition: (PromptBridge.Channel) -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 标签名 + 释义 + 别名
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onTagClick() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(tag.category)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatCount(tag.count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (tag.zhName.isNotEmpty() || tag.aliases.isNotEmpty()) {
                    Text(
                        text = tag.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // 收藏星标（点击切换，与"只看收藏"筛选联动）
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.tag_unfavorite else R.string.tag_favorite
                    ),
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            // 添加按钮：点击弹出"放到哪儿"的菜单（追加 / 指定位置插入 / 只复制到剪贴板）
            var openMenu by remember { mutableStateOf<PromptBridge.Channel?>(null) }

            Box {
                Button(
                    onClick = { openMenu = PromptBridge.Channel.POSITIVE },
                    enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 0.dp
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.tag_add_positive), style = MaterialTheme.typography.labelSmall)
                }
                TagPlacementMenu(
                    expanded = openMenu == PromptBridge.Channel.POSITIVE,
                    onDismiss = { openMenu = null },
                    onAppend = {
                        openMenu = null
                        onAddPositive()
                    },
                    onInsert = {
                        openMenu = null
                        onPickPosition(PromptBridge.Channel.POSITIVE)
                    },
                    onCopy = {
                        openMenu = null
                        onCopy()
                    }
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box {
                Button(
                    onClick = { openMenu = PromptBridge.Channel.NEGATIVE },
                    enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 0.dp
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.tag_add_negative), style = MaterialTheme.typography.labelSmall)
                }
                TagPlacementMenu(
                    expanded = openMenu == PromptBridge.Channel.NEGATIVE,
                    onDismiss = { openMenu = null },
                    onAppend = {
                        openMenu = null
                        onAddNegative()
                    },
                    onInsert = {
                        openMenu = null
                        onPickPosition(PromptBridge.Channel.NEGATIVE)
                    },
                    onCopy = {
                        openMenu = null
                        onCopy()
                    }
                )
            }
        }
    }
}

/**
 * 「这个标签怎么放到提示词里」的菜单。
 *
 * 三种取用方式：追加到末尾（沿用原有习惯）、插入到指定位置（由位置选择对话框完成）、
 * 以及只复制到剪贴板——最后一种**不碰任何提示词区**，粘贴到哪由用户自己定。
 */
@Composable
private fun TagPlacementMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAppend: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tag_menu_append)) },
            onClick = onAppend
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tag_menu_insert)) },
            onClick = onInsert
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tag_menu_copy)) },
            onClick = onCopy
        )
    }
}

/** 分类徽章（不同分类用不同色调区分）。 */
@Composable
private fun CategoryBadge(category: TagCategory) {
    val (container, content) = when (category) {
        TagCategory.GENERAL ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        TagCategory.ARTIST ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        TagCategory.COPYRIGHT ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TagCategory.CHARACTER ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TagCategory.META ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            text = stringResource(category.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/** 热度格式化：1 万以上用「万」，否则千分位。 */
internal fun formatCount(count: Long): String =
    if (count >= 10_000) String.format(Locale.US, "%.1f万", count / 10_000.0)
    else String.format(Locale.US, "%,d", count)

/** 加载态。 */
@Composable
private fun LoadingState(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 空态。 */
@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 初始化失败态（带重试）。 */
@Composable
private fun InitErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.tag_retry))
        }
    }
}
