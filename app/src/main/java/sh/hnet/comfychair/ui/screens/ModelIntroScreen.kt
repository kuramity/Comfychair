package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.ui.components.shared.ModelIntroSheet

/** 列表里的一行：要么是文件夹，要么是模型。 */
private sealed interface IntroRow {
    val key: String

    data class Folder(
        val path: String,
        val name: String,
        val depth: Int,
        val modelCount: Int,
    ) : IntroRow {
        override val key: String get() = "d:$path"
    }

    data class Model(val intro: ModelIntro, val depth: Int) : IntroRow {
        override val key: String get() = "m:${intro.file.lowercase()}"
    }
}

/**
 * 模型介绍浏览页。
 *
 * 用**多级可折叠目录树**呈现：目录来自模型在服务器上的实际存放路径（`rel`），
 * 所以层级和你打开文件管理器看到的一致。默认全部收起（一眼看到顶层结构），
 * 点目录展开、再点收起。
 *
 * 数据来自随包内置 / 服务器更新的介绍表（[ModelIntroTable]），离线也能看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ModelIntro>>(emptyList()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<ModelIntro?>(null) }

    LaunchedEffect(Unit) {
        ModelIntroTable.ensureLoaded(context)
        entries = ModelIntroTable.all()
    }

    // 行列表：目录（含中间层）+ 它下面的模型；只渲染"所有祖先都已展开"的行
    val rows = remember(entries, expanded) { buildVisibleRows(entries, expanded) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.model_intro_screen_title))
                        Text(
                            text = stringResource(R.string.model_intro_screen_count, entries.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.button_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.model_intro_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is IntroRow.Folder -> FolderRow(
                        row = row,
                        isExpanded = row.path in expanded,
                        onToggle = {
                            expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
                        }
                    )

                    is IntroRow.Model -> ModelRow(intro = row.intro, depth = row.depth) {
                        detail = row.intro
                    }
                }
            }
        }
    }

    detail?.let { intro ->
        ModelIntroSheet(intro = intro, onDismiss = { detail = null })
    }
}

/**
 * 把条目摊成"可见行"。
 *
 * 目录集合含所有中间层（`a/b/c` 会带来 `a`、`a/b`、`a/b/c` 三个节点），按路径排序后
 * 天然就是深度优先顺序；某个目录只在**它的所有祖先都展开**时才出现。
 */
private fun buildVisibleRows(entries: List<ModelIntro>, expanded: Set<String>): List<IntroRow> {
    if (entries.isEmpty()) return emptyList()

    // 所有目录路径（含中间层）
    val allFolders = sortedSetOf<String>()
    for (e in entries) {
        if (e.folder.isEmpty()) continue
        var acc = ""
        for (seg in e.folder.split('/')) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            allFolders.add(acc)
        }
    }
    val modelsByFolder = entries.groupBy { it.folder }

    fun ancestorsExpanded(path: String): Boolean {
        val parts = path.split('/')
        var acc = ""
        for (i in 0 until parts.size - 1) {
            acc = if (acc.isEmpty()) parts[i] else "$acc/${parts[i]}"
            if (acc !in expanded) return false
        }
        return true
    }

    val rows = ArrayList<IntroRow>(entries.size + allFolders.size)
    // 根目录下的模型排在最前
    modelsByFolder[""]?.forEach { rows.add(IntroRow.Model(it, 0)) }

    for (folder in allFolders) {
        if (!ancestorsExpanded(folder)) continue
        val depth = folder.count { it == '/' }
        val models = modelsByFolder[folder].orEmpty()
        rows.add(
            IntroRow.Folder(
                path = folder,
                name = folder.substringAfterLast('/'),
                depth = depth,
                // 数量要算**含子目录**的总数：模型大多躺在深层目录里，
                // 只看直属文件的话顶层目录会显示 0，让人以为没内容
                modelCount = entries.count { it.folder == folder || it.folder.startsWith("$folder/") }
            )
        )
        if (folder in expanded) {
            models.forEach { rows.add(IntroRow.Model(it, depth + 1)) }
        }
    }
    return rows
}

@Composable
private fun FolderRow(row: IntroRow.Folder, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 12.dp + (row.depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${row.modelCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModelRow(intro: ModelIntro, depth: Int, onClick: () -> Unit) {
    val subtitle = listOfNotNull(intro.base, intro.type, intro.author?.let { "作者 $it" })
        .joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp + (depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Text(
            text = intro.name ?: intro.file,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = intro.file,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
