package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LogEntry
import sh.hnet.comfychair.util.LogLevel
import sh.hnet.comfychair.ui.util.KeyedItem
import sh.hnet.comfychair.ui.util.withStableKeys

/**
 * A fullscreen dialog for viewing debug logs.
 * Shows log entries with color-coding by level.
 * Auto-refreshes and auto-scrolls to follow new entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    // 列表行 = 日志条目 + 由条目内容派生的稳定 key（见 logEntryIdentity）。
    // 不能拿下标当 key：日志到 1000 条上限后是"头删尾插"的滚动窗口，每来一条新日志
    // 所有下标都会平移一格，行槽位会整体串行。
    val rows = remember { mutableStateListOf<KeyedItem<LogEntry>>() }
    val listState = rememberLazyListState()
    var isFollowing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Check if we're at the bottom of the list
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisibleItem >= totalItems - 2
        }
    }

    // Update isFollowing when user scrolls to bottom manually
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            isFollowing = true
        }
    }

    // Auto-refresh every 500ms and scroll if following.
    // 先读版本号（轻量、无拷贝），只有内容真的变了才重新取列表——原先每 500ms 无条件
    // 全量拷贝并 clear+addAll 整个列表，等于让可见项反复重建（日志上千条时尤其浪费）。
    LaunchedEffect(Unit) {
        var seenVersion = -1
        while (true) {
            val currentVersion = DebugLogger.currentVersion()
            if (currentVersion != seenVersion) {
                seenVersion = currentVersion
                val newEntries = DebugLogger.getEntries()
                rows.clear()
                rows.addAll(newEntries.withStableKeys(::logEntryIdentity))

                if (isFollowing && rows.isNotEmpty()) {
                    listState.scrollToItem(rows.size - 1)
                }
            }
            delay(500)
        }
    }

    // Detect user scrolling up (away from bottom)
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (!isAtBottom && rows.isNotEmpty()) {
            isFollowing = false
        }
    }

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
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                TopAppBar(
                    title = { Text(stringResource(R.string.title_debug_log)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.content_description_close)
                            )
                        }
                    },
                    actions = {
                        // Follow/scroll-to-bottom button
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isFollowing = true
                                    if (rows.isNotEmpty()) {
                                        listState.scrollToItem(rows.size - 1)
                                    }
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (isFollowing)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                Icons.Default.VerticalAlignBottom,
                                contentDescription = stringResource(R.string.debug_log_follow)
                            )
                        }
                        IconButton(onClick = onSave) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(R.string.debug_log_save)
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )

                // Log entries
                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.msg_debug_log_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                        ) {
                            items(
                                items = rows,
                                key = { it.key }
                            ) { row ->
                                LogEntryItem(row.value)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFFF9800)  // Orange
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = entry.format(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/**
 * 日志行的业务标识：时间戳 + 级别 + tag + 正文。
 *
 * [LogEntry] 没有自增 id（DebugLogger 只存内容，且达到 1000 条上限后是头删尾插的滚动窗口），
 * 所以用「整条日志内容」当标识；同一毫秒内出现完全相同的两行时，由
 * [withStableKeys] 追加 `#dupN` 后缀区分，保证 key 不重复。
 */
private fun logEntryIdentity(entry: LogEntry): String =
    "${entry.timestamp}|${entry.level.name}|${entry.tag}|${entry.message}"
