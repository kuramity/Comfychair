package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraEffectTable
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.shared.modelSubtitle
import sh.hnet.comfychair.viewmodel.ModelIntroViewModel

/** 列表里的一行：目录节点 / 模型条目 / 档案孤儿条目（新旧行模型并存，见各 KDoc）。 */
internal sealed interface IntroRow {
    val key: String

    /**
     * 旧目录树（v4.16.5 形态，[AppSettings.isLoraArchiveMerged] 关闭时的回退用）的文件夹行。
     */
    data class Folder(
        val path: String,
        val name: String,
        val depth: Int,
        val modelCount: Int,
    ) : IntroRow {
        override val key: String get() = "d:$path"
    }

    /** 旧目录树的模型行（名称 maxLines=2、行距 8dp——回退态保持 v4.16.5 原观感）。 */
    data class Model(val intro: ModelIntro, val depth: Int) : IntroRow {
        override val key: String get() = "m:${intro.file.lowercase()}"
    }

    /**
     * 三层树（v4.16.6 Q4-2/Q4-3）的目录节点：类别（一级）/ 分组（二级）/ 档案虚拟节点
     * 共用同一套渲染（同一文件夹图标体系、同一缩进与行尾计数位置）。
     *
     * @param expandKey 展开/收起状态的键（类别 `k:名`、分组 `g:名|目录段`、档案 `k:__archive`）
     * @param isGroupTitle 二级分组标题样式（labelMedium + 轻微底色），一级节点为 false
     */
    data class Node(
        val expandKey: String,
        val name: String,
        val depth: Int,
        val modelCount: Int,
        val isGroupTitle: Boolean,
    ) : IntroRow {
        override val key: String get() = expandKey
    }

    /** 三层树的模型条目行（名称 maxLines=1 + ellipsis、行距 8dp——全树统一）。 */
    data class Entry(val intro: ModelIntro, val depth: Int) : IntroRow {
        override val key: String get() = "e:${(intro.rel ?: intro.file).lowercase()}"
    }

    /**
     * 档案孤儿条目（效果档案收录、介绍表里没有的）：点不开详情页，就地展开/收起
     * 显示效果全文，并标「仅效果档案收录」（复用现有孤儿标记键）。
     */
    data class Orphan(val fileName: String, val effect: String, val depth: Int) : IntroRow {
        override val key: String get() = "o:$fileName"
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
 *
 * v4.21 起接上**用户覆盖层**（见 [ModelIntroViewModel]）：顶栏菜单里能批量管理、
 * 查看并恢复已卸载条目；长按条目可卸载（只在本机隐藏）。点条目**直接进详情页**
 * （[ModelIntroDetailScreen]，20 区块完整版）。
 *
 * @param onOpenDetail 点条目时进入详情页（v4.21 前是先在底部抽屉里预览，抽屉已下线）
 * @param onOpenHidden 「已卸载（N）」的列表页入口（新路由，见 MainRoute.ModelIntroHidden）
 * @param onOpenEditor 顶栏「＋」进入编辑器**新建模式**（无 file 参数，先选模板；
 *        见 MainRoute.ModelIntroEditor）
 * @param onEditEntry 长按菜单「编辑这条」进入编辑器**编辑模式**（带该条目的 file）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroScreen(
    onBack: () -> Unit,
    onOpenDetail: (ModelIntro) -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onEditEntry: (ModelIntro) -> Unit = {},
    viewModel: ModelIntroViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entries = state.entries
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }

    // Q4（v4.16.6）合并形态开关（安全阀，组合时读一次）：开 = 三层树 + 档案只留孤儿；
    // 关 = 完整回到 v4.16.5 旧形态（独立档案分区 47 条 + 按路径展开的旧树）
    val archiveMerged = remember(context) { AppSettings.isLoraArchiveMerged(context) }

    // 「LoRA 效果档案」分区：孤儿条目（介绍表里没有的）在前，其余条目在后。
    // Q4-1（v4.16.6）：合并去重只在**内存展示层**完成——splitArchiveKeys 划分
    // 「已合并 / 孤儿」，数据文件（assets / filesDir 副本）一个字节都不改。
    var archiveExpanded by rememberSaveable { mutableStateOf(false) }
    var orphanOpenKeys by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    var effects by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // ---- 覆盖层交互状态 ----
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<ModelIntro?>(null) }
    var showBatchConfirm by remember { mutableStateOf(false) }

    // 介绍表（含覆盖层合并结果 + 已卸载名单）由 ViewModel 在 IO 线程装载；
    // 卸载/恢复之后 ViewModel 自己会再刷一次，界面只读状态。
    LaunchedEffect(Unit) { viewModel.load(context) }

    // Toast 收集（文案 id + 格式参数由 ViewModel 给出，见 ModelIntroToastHost）
    ModelIntroToastHost(viewModel)

    LaunchedEffect(Unit) {
        // P2-17：效果档案同样是读盘 + 解析，放 IO 线程
        effects = withContext(Dispatchers.IO) {
            LoraEffectTable.ensureLoaded(context)
            LoraEffectTable.all()
        }
    }

    // 档案去重划分：介绍表条目集变化（卸载/恢复）时要跟着重算
    val archiveSplit = remember(entries, effects) {
        if (effects.isEmpty() || entries.isEmpty()) {
            emptyList<Pair<String, String>>() to emptyList<Pair<String, String>>()
        } else {
            val known = entries.map { it.file.lowercase() }.toSet()
            val (joined, orphans) = splitArchiveKeys(effects.keys.toList().sorted(), known)
            joined.map { it to effects.getValue(it) } to orphans.map { it to effects.getValue(it) }
        }
    }
    val archiveJoined = archiveSplit.first
    val archiveOrphans = archiveSplit.second

    // 三层树中间结构（Q4-3：类别 → 分组 → 条目），条目集不变时只算一次
    val categories = remember(entries) { buildCategories(entries) }

    // 行列表：合并态走三层树（档案节点 + 类别 + 分组 + 条目），回退态走旧路径树
    val archiveNodeName = stringResource(R.string.model_intro_effect_archive, archiveOrphans.size)
    val rows = if (archiveMerged) {
        remember(categories, archiveOrphans, archiveNodeName, expanded) {
            buildCategorizedRows(categories, archiveNodeName, archiveOrphans, expanded)
        }
    } else {
        remember(entries, expanded) { buildVisibleRows(entries, expanded) }
    }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.selectMode) {
                                stringResource(R.string.model_intro_batch_manage)
                            } else {
                                stringResource(R.string.model_intro_screen_title)
                            }
                        )
                        Text(
                            text = if (state.selectMode) {
                                stringResource(R.string.model_intro_select_count, state.selected.size)
                            } else {
                                stringResource(R.string.model_intro_screen_count, entries.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (state.selectMode) {
                        IconButton(onClick = { viewModel.exitSelectMode() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.button_back)
                            )
                        }
                    }
                },
                actions = {
                    if (state.selectMode) {
                        TextButton(
                            onClick = {
                                if (state.selected.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.model_intro_select_none),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    showBatchConfirm = true
                                }
                            }
                        ) {
                            Text(stringResource(R.string.model_intro_batch_title))
                        }
                    } else {
                        // 新建介绍入口：放在「⋮」左边（MoreVert 是溢出菜单，语义上永远最右）。
                        // 顶栏按钮固定进「新建」模式，编辑既有条目走长按菜单（任务书拍板）。
                        IconButton(onClick = onOpenEditor) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.model_intro_editor_entry)
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.model_intro_menu_more)
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.model_intro_batch_manage)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.enterSelectMode()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.model_intro_hidden_menu,
                                                state.hidden.size
                                            )
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenHidden()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        // loading 期间不显示「暂无介绍」（否则冷启动会闪一下空态）
        if (entries.isEmpty() && !state.loading) {
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
        if (entries.isEmpty()) return@Scaffold

        // 旧档案分区只在回退态渲染（合并态的孤儿已在三层树顶的档案节点下）
        val archiveItems = if (archiveExpanded) archiveOrphans + archiveJoined else emptyList()
        val hasArchive = !archiveMerged && (archiveOrphans.isNotEmpty() || archiveJoined.isNotEmpty())

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 回退态：「LoRA 效果档案」分区固定在目录树之前；key 用 "a:" 前缀，
            // 与目录树行的 "d:"/"m:" 前缀互不冲突，不影响既有行的 key 稳定性
            if (hasArchive) {
                item(key = "a:header") {
                    ArchiveHeaderRow(
                        expanded = archiveExpanded,
                        count = archiveOrphans.size + archiveJoined.size,
                        onToggle = { archiveExpanded = !archiveExpanded }
                    )
                }
                items(archiveItems, key = { "a:${it.first}" }) { (fileName, effect) ->
                    ArchiveEntryRow(
                        fileName = fileName,
                        effect = effect,
                        isOpen = fileName in orphanOpenKeys,
                        onToggleOrphan = {
                            orphanOpenKeys = if (fileName in orphanOpenKeys) {
                                orphanOpenKeys - fileName
                            } else {
                                orphanOpenKeys + fileName
                            }
                        },
                        onOpenDetail = onOpenDetail
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    // ---- 回退态（v4.16.5 旧树）----
                    is IntroRow.Folder -> FolderRow(
                        row = row,
                        isExpanded = row.path in expanded,
                        onToggle = {
                            expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
                        }
                    )

                    is IntroRow.Model -> ModelRow(
                        intro = row.intro,
                        depth = row.depth,
                        selectionMode = state.selectMode,
                        isSelected = row.intro.file in state.selected,
                        onClick = {
                            if (state.selectMode) {
                                viewModel.toggleSelected(row.intro.file)
                            } else {
                                onOpenDetail(row.intro)
                            }
                        },
                        onLongClick = {
                            if (state.selectMode) {
                                viewModel.toggleSelected(row.intro.file)
                            } else {
                                pendingUninstall = row.intro
                            }
                        },
                    )

                    // ---- 合并态（v4.16.6 三层树）----
                    is IntroRow.Node -> NodeRow(
                        node = row,
                        isExpanded = row.expandKey in expanded,
                        onToggle = {
                            expanded = if (row.expandKey in expanded) {
                                expanded - row.expandKey
                            } else {
                                expanded + row.expandKey
                            }
                        }
                    )

                    is IntroRow.Entry -> EntryRow(
                        intro = row.intro,
                        depth = row.depth,
                        selectionMode = state.selectMode,
                        isSelected = row.intro.file in state.selected,
                        onClick = {
                            if (state.selectMode) {
                                viewModel.toggleSelected(row.intro.file)
                            } else {
                                onOpenDetail(row.intro)
                            }
                        },
                        onLongClick = {
                            if (state.selectMode) {
                                viewModel.toggleSelected(row.intro.file)
                            } else {
                                pendingUninstall = row.intro
                            }
                        },
                    )

                    is IntroRow.Orphan -> OrphanRow(
                        fileName = row.fileName,
                        effect = row.effect,
                        depth = row.depth,
                        isOpen = row.fileName in orphanOpenKeys,
                        onToggle = {
                            orphanOpenKeys = if (row.fileName in orphanOpenKeys) {
                                orphanOpenKeys - row.fileName
                            } else {
                                orphanOpenKeys + row.fileName
                            }
                        }
                    )
                }
            }
        }
    }

    // ---- 长按操作（卸载 / 编辑，均为二次确认后执行）----
    pendingUninstall?.let { intro ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.model_intro_uninstall_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_intro_uninstall_message,
                        intro.name?.takeIf { it.isNotBlank() } ?: intro.file
                    )
                )
            },
            confirmButton = {
                // 「编辑这条」与「卸载」并列（任务书 3）：编辑进编辑器（覆盖层整条覆盖，
                // 内置库一个字节不改），卸载只是本地隐藏——两条路径互不掺和。
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        pendingUninstall = null
                        onEditEntry(intro)
                    }) {
                        Text(stringResource(R.string.model_intro_edit_entry))
                    }
                    TextButton(onClick = {
                        pendingUninstall = null
                        viewModel.hide(context, intro.file)
                    }) {
                        Text(
                            text = stringResource(R.string.model_intro_uninstall_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ---- 批量卸载确认 ----
    if (showBatchConfirm) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text(stringResource(R.string.model_intro_batch_title)) },
            text = { Text(stringResource(R.string.model_intro_batch_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatchConfirm = false
                    viewModel.hideMany(context, state.selected)
                }) {
                    Text(
                        text = stringResource(R.string.model_intro_uninstall_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
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

// ============================================================
// 三层树（v4.16.6 Q4）：类别（kind）→ 分组（rel 目录段）→ 条目
// ============================================================

/** 档案虚拟节点的展开键（"archive" 是英文小写，与数据侧中文 kind 枚举不可能撞）。 */
internal const val ARCHIVE_NODE_KEY = "k:__archive"

/** kind 为空时的兜底桶名（数据侧 kind 是代码内硬编码中文枚举，兜底名随此惯例）。 */
internal const val FALLBACK_KIND = "未分类"

/**
 * 类别展示顺序（数据侧 8 类）；不在表内的未知 kind 排在最后、「未分类」兜底桶殿后。
 *
 * internal：编辑器「类型」下拉用同一份枚举（任务书：下拉选 8 类）——两处各写一遍，
 * 数据侧扩类时必漂移。
 */
internal val KIND_ORDER = listOf(
    "背景去除", "大模型", "图像编码器", "扩散模型", "LoRA", "放大模型", "VAE", "几何估计"
)

/**
 * Q4-1（v4.16.6）档案条目去重划分（纯函数，供 JVM 单测）。
 *
 * 档案条目键（小写 basename）与介绍表收录文件名集合天然同口径，无需归一化：
 * - 键在集合里 → **已合并**（介绍表条目自带同样的 effect，档案区不再重复列出，
 *   用户在树里点对应模型看详情页）；
 * - 键不在集合里 → **孤儿**（介绍表还没收录这条模型），档案节点下保留展示。
 *
 * 输入顺序即输出顺序（调用方传排序后的键，输出保持字典序稳定）。
 */
internal fun splitArchiveKeys(
    archiveKeys: List<String>,
    introFiles: Set<String>,
): Pair<List<String>, List<String>> {
    val known = introFiles.map { it.lowercase() }.toSet()
    val joined = ArrayList<String>()
    val orphans = ArrayList<String>()
    for (key in archiveKeys) {
        if (key.lowercase() in known) joined.add(key) else orphans.add(key)
    }
    return joined to orphans
}

/** 三层树的中间结构（纯数据，便于 JVM 单测断言类别数与条目数）。 */
internal data class IntroGroup(
    /** rel 目录段（如 `image/sdxl/style`） */
    val folder: String,
    /** 组内条目，已按文件名排序 */
    val models: List<ModelIntro>,
)

internal data class IntroCategory(
    val kind: String,
    /** 无目录段（根直属）的条目，展开类别后直接列出 */
    val direct: List<ModelIntro>,
    /** 有目录段的分组，按目录段排序 */
    val groups: List<IntroGroup>,
) {
    /** 该类别下条目总数（直属 + 各分组），类别节点行尾计数用。 */
    val total: Int get() = direct.size + groups.sumOf { it.models.size }
}

/**
 * Q4-3（v4.16.6）三层树构建（纯函数，供 JVM 单测）：类别（kind）→ 分组（rel 目录段）→ 条目。
 *
 * 只动展示层：`lora_effects.json` 没有 category 字段，类别一律取介绍表的 kind，
 * 不从档案表推（孤儿没有路径信息，也不瞎猜归类——它们单独挂档案节点）。
 */
internal fun buildCategories(entries: List<ModelIntro>): List<IntroCategory> {
    val byKind = entries.groupBy {
        it.kind?.trim()?.takeIf { k -> k.isNotEmpty() } ?: FALLBACK_KIND
    }
    val ordered = KIND_ORDER.filter { byKind.containsKey(it) } +
        byKind.keys.filter { it !in KIND_ORDER && it != FALLBACK_KIND }.sorted() +
        listOfNotNull(FALLBACK_KIND.takeIf { byKind.containsKey(it) })
    return ordered.map { kind ->
        val list = byKind.getValue(kind)
            .sortedWith(compareBy({ it.folder }, { it.file.lowercase() }))
        IntroCategory(
            kind = kind,
            direct = list.filter { it.folder.isEmpty() },
            groups = list.filter { it.folder.isNotEmpty() }
                .groupBy { it.folder }
                .map { (folder, models) -> IntroGroup(folder, models) }
                .sortedBy { it.folder },
        )
    }
}

/**
 * Q4-2/Q4-3（v4.16.6）三层树摊平成可见行（纯函数，供 JVM 单测）。
 *
 * - 档案节点（有孤儿才出现）排最前：普通一级文件夹节点样式，与类别节点完全对等，
 *   展开后列出孤儿条目（Q4-1 合并后档案区只剩这 5 条）；
 * - 类别节点展开 → 直属条目 + 分组节点；分组节点展开 → 组内条目；
 * - 只渲染「所有祖先都已展开」的行（与旧 [buildVisibleRows] 同一机制）。
 *
 * @param archiveNodeName 档案节点显示名（调用方用现有 `model_intro_effect_archive` 键
 *   带孤儿数生成——纯函数不进 Composable 上下文）
 */
internal fun buildCategorizedRows(
    categories: List<IntroCategory>,
    archiveNodeName: String,
    archiveOrphans: List<Pair<String, String>>,
    expanded: Set<String>,
): List<IntroRow> {
    val rows = ArrayList<IntroRow>()

    if (archiveOrphans.isNotEmpty()) {
        rows.add(
            IntroRow.Node(
                expandKey = ARCHIVE_NODE_KEY,
                name = archiveNodeName,
                depth = 0,
                modelCount = archiveOrphans.size,
                isGroupTitle = false,
            )
        )
        if (ARCHIVE_NODE_KEY in expanded) {
            archiveOrphans.forEach { (fileName, effect) ->
                rows.add(IntroRow.Orphan(fileName, effect, depth = 1))
            }
        }
    }

    for (cat in categories) {
        val key = "k:${cat.kind}"
        rows.add(IntroRow.Node(key, cat.kind, depth = 0, modelCount = cat.total, isGroupTitle = false))
        if (key !in expanded) continue
        cat.direct.forEach { rows.add(IntroRow.Entry(it, depth = 1)) }
        for (group in cat.groups) {
            val groupKey = "g:${cat.kind}|${group.folder}"
            rows.add(
                IntroRow.Node(groupKey, group.folder, depth = 1, modelCount = group.models.size, isGroupTitle = true)
            )
            if (groupKey in expanded) {
                group.models.forEach { rows.add(IntroRow.Entry(it, depth = 2)) }
            }
        }
    }
    return rows
}

/**
 * 「LoRA 效果档案」分区的折叠头：默认收起，标题带条目数（有值条目数）。
 */
@Composable
private fun ArchiveHeaderRow(expanded: Boolean, count: Int, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.model_intro_effect_archive, count),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 效果档案的一条：能按文件名 join 到介绍表的条目点击进详情页；
 * join 不到的（档案里先记了效果、模型还没进全量溯源的孤儿条目）就地展开/收起
 * 显示效果全文，不新开路由。
 */
@Composable
private fun ArchiveEntryRow(
    fileName: String,
    effect: String,
    isOpen: Boolean,
    onToggleOrphan: () -> Unit,
    onOpenDetail: (ModelIntro) -> Unit,
) {
    val intro = ModelIntroTable.lookup(fileName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (intro != null) onOpenDetail(intro) else onToggleOrphan()
            }
            .padding(start = 44.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(
            text = intro?.name ?: fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (intro == null) {
            // 孤儿条目：介绍表里没有对应模型，标出来让用户知道它点不开详情
            Text(
                text = stringResource(R.string.model_intro_effect_archive_orphan_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isOpen && intro == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = effect,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(
    intro: ModelIntro,
    depth: Int,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 统一走 shared 的 modelSubtitle：它额外带两段兜底——没有结构化字段时用作者推荐 tag，
    // 以及「文件已移除」标记。原先本页自己拼 base·type·作者，模型文件被清理后这行就是空白，
    // 用户完全看不出这个条目已经失效。
    val subtitle = modelSubtitle(intro, stringResource(R.string.model_intro_missing_badge))
    // U1-A：自绘「按下即完整高亮」的底色，替掉 M3 ripple。
    // ripple 有扩散 + 淡出的前摇（约 300–500ms），而 onClick 抬手就导航、列表随即被销毁，
    // 用户看到的是「白色高亮刚晕开一半画面就跳走了」。这里按下第一帧整行就是完整底色，
    // 松开立即恢复，不存在播到一半被打断的观感。长按（卸载确认框）同样会亮，这是对的。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 与分组标题（GROUP_TITLE_SHAPE 6dp）同一套视觉语言；行本身无圆角，圆角只作用于底色
    val rowShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 底色画在整行（含缩进区），「完整高亮」才是 U1-A 要的观感
            .background(
                color = if (pressed) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
                shape = rowShape
            )
            // 长按 = 卸载此条介绍（批量管理模式下退化为勾选，不弹卸载框）
            .combinedClickable(
                interactionSource = interactionSource,
                // 关掉 ripple：不关的话它会在自绘底色上再晕一层，看起来更脏
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 12.dp + (depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = null)
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
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
            if (!subtitle.isNullOrEmpty()) {
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
}

// ============================================================
// 三层树行渲染（v4.16.6 Q4-2/Q4-4）：ArchiveHeaderRow 与 FolderRow 合并成
// 同一套节点渲染（同一文件夹图标体系、同一缩进规则、同一行尾计数位置），
// 行距统一 8dp、条目名称统一 maxLines=1 + ellipsis
// ============================================================

/** 三层树统一行距（Q4-4：旧三套 6/10/8dp 收敛为一套，四个层级共用同一常量）。 */
private val INTRO_ROW_SPACING = 8.dp

/** 分组标题（labelMedium）的轻微底色圆角（视觉上把"分组层"与条目层分开）。 */
private val GROUP_TITLE_SHAPE = RoundedCornerShape(6.dp)

/**
 * 三层树的目录节点行：类别（一级）/ 分组（二级，带轻微底色的分组标题样式）/
 * 档案虚拟节点共用 —— 同一文件夹图标（展开态 FolderOpen）、同一缩进（12 + depth×16）、
 * 同一行尾独立计数 Text（不拼进标题，Q4-2）。
 */
@Composable
private fun NodeRow(node: IntroRow.Node, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            // 分组标题的轻微底色画在整行（含缩进区），条目层不带底色
            .then(
                if (node.isGroupTitle) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, GROUP_TITLE_SHAPE)
                } else {
                    Modifier
                }
            )
            .padding(
                start = 12.dp + (node.depth * 16).dp,
                end = 16.dp,
                top = INTRO_ROW_SPACING,
                bottom = INTRO_ROW_SPACING
            )
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.name,
            style = if (node.isGroupTitle) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (node.isGroupTitle) null else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${node.modelCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 三层树的模型条目行：名称（bodyMedium，Q4-4 统一 maxLines=1 + ellipsis，尾部截断）+
 * 文件名（labelSmall）+ 副标题/状态标记（[modelSubtitle] 带「文件已移除」等标记）。
 * 点击进详情页——42 条已合并的档案条目就在详情页里看到效果（详情页优先读条目 effect）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    intro: ModelIntro,
    depth: Int,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 统一走 shared 的 modelSubtitle（与旧 ModelRow 同一兜底链：结构化字段 → 推荐 tag → 移除标记）
    val subtitle = modelSubtitle(intro, stringResource(R.string.model_intro_missing_badge))
    // U1-A：与 ModelRow 同一套「按下即完整高亮」的自绘底色（三棵树的条目行手感必须一致，
    // 否则合并态与回退态点起来是两种感觉）。按下第一帧即完整高亮，松开立即恢复，随后照常导航
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 底色画在整行（含缩进区），条目层与分组标题的底色语言统一
            .background(
                color = if (pressed) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
                shape = rowShape
            )
            // 长按 = 卸载此条介绍（批量管理模式下退化为勾选，不弹卸载框）
            .combinedClickable(
                interactionSource = interactionSource,
                // 关掉 ripple，否则它会在自绘底色上再晕一层
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(
                start = 12.dp + (depth * 16).dp,
                end = 16.dp,
                top = INTRO_ROW_SPACING,
                bottom = INTRO_ROW_SPACING
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = null)
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = intro.name ?: intro.file,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = intro.file,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
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
}

/**
 * 档案孤儿条目行（合并态档案节点下，共 5 条）：介绍表里没有这条模型，点不开详情页，
 * 就地展开/收起显示效果全文；名称行下标「仅效果档案收录」（复用现有孤儿标记键），
 * 即"未收录到目录树"的明确标注。
 */
@Composable
private fun OrphanRow(
    fileName: String,
    effect: String,
    depth: Int,
    isOpen: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start = 12.dp + (depth * 16).dp,
                end = 16.dp,
                top = INTRO_ROW_SPACING,
                bottom = INTRO_ROW_SPACING
            )
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.model_intro_effect_archive_orphan_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isOpen) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = effect,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
