package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraEffectTable
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.shared.ModelIntroSheet
import sh.hnet.comfychair.ui.components.shared.modelSubtitle

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
     * 档案孤儿条目（效果档案收录、介绍表里没有的）：点不开详情抽屉，就地展开/收起
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroScreen(onBack: () -> Unit, onOpenDetail: (ModelIntro) -> Unit = {}) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ModelIntro>>(emptyList()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<ModelIntro?>(null) }

    // Q4（v4.16.6）合并形态开关（安全阀，组合时读一次）：开 = 三层树 + 档案只留孤儿；
    // 关 = 完整回到 v4.16.5 旧形态（独立档案分区 47 条 + 按路径展开的旧树）
    val archiveMerged = remember(context) { AppSettings.isLoraArchiveMerged(context) }

    // 「LoRA 效果档案」分区：孤儿条目（介绍表里没有的）在前，其余条目在后。
    // Q4-1（v4.16.6）：合并去重只在**内存展示层**完成——splitArchiveKeys 划分
    // 「已合并 42 / 孤儿 5」，数据文件（assets / filesDir 副本）一个字节都不改。
    var archiveExpanded by remember { mutableStateOf(false) }
    var orphanOpenKeys by remember { mutableStateOf(emptySet<String>()) }
    var archiveOrphans by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var archiveJoined by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(Unit) {
        // P2-17：模型介绍表是 513KB 的 JSON，读取 + 解析原先在主线程同步执行，
        // 进页面时会卡住首帧。整段装载（含效果档案）放到 IO 线程，只回主线程写状态。
        val loaded = withContext(Dispatchers.IO) {
            ModelIntroTable.ensureLoaded(context)
            val introList = ModelIntroTable.all()
            // 效果档案在介绍表之后装载：orphans 的判定要先拿到介绍表收录的文件名集合
            LoraEffectTable.ensureLoaded(context)
            introList to LoraEffectTable.all()
        }
        entries = loaded.first
        val effects = loaded.second
        if (effects.isNotEmpty()) {
            val known = entries.map { it.file.lowercase() }.toSet()
            val (joined, orphans) = splitArchiveKeys(effects.keys.toList().sorted(), known)
            archiveOrphans = orphans.map { it to effects.getValue(it) }
            archiveJoined = joined.map { it to effects.getValue(it) }
        }
    }

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
                        onOpenDetail = { intro -> detail = intro }
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

                    is IntroRow.Model -> ModelRow(intro = row.intro, depth = row.depth) {
                        detail = row.intro
                    }

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

                    is IntroRow.Entry -> EntryRow(intro = row.intro, depth = row.depth) {
                        detail = row.intro
                    }

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

    detail?.let { intro ->
        ModelIntroSheet(
            intro = intro,
            onDismiss = { detail = null },
            // v4.17：抽屉是快速预览，「查看完整详情」进二级页面（20 区块完整版）
            onOpenDetail = {
                detail = null
                onOpenDetail(intro)
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

/** 类别展示顺序（数据侧 8 类）；不在表内的未知 kind 排在最后、「未分类」兜底桶殿后。 */
private val KIND_ORDER = listOf(
    "背景去除", "大模型", "图像编码器", "扩散模型", "LoRA", "放大模型", "VAE", "几何估计"
)

/**
 * Q4-1（v4.16.6）档案条目去重划分（纯函数，供 JVM 单测）。
 *
 * 档案条目键（小写 basename）与介绍表收录文件名集合天然同口径，无需归一化：
 * - 键在集合里 → **已合并**（介绍表条目自带同样的 effect，档案区不再重复列出，
 *   用户在树里点对应模型看详情抽屉）；
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
 * 效果档案的一条：能按文件名 join 到介绍表的条目点击打开详情抽屉；
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

@Composable
private fun ModelRow(intro: ModelIntro, depth: Int, onClick: () -> Unit) {
    // 统一走 shared 的 modelSubtitle：它额外带两段兜底——没有结构化字段时用作者推荐 tag，
    // 以及「文件已移除」标记。原先本页自己拼 base·type·作者，模型文件被清理后这行就是空白，
    // 用户完全看不出这个条目已经失效。
    val subtitle = modelSubtitle(intro, stringResource(R.string.model_intro_missing_badge))
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
 * 点击打开详情抽屉——42 条已合并的档案条目就在这里看到效果（抽屉优先读条目 effect）。
 */
@Composable
private fun EntryRow(intro: ModelIntro, depth: Int, onClick: () -> Unit) {
    // 统一走 shared 的 modelSubtitle（与旧 ModelRow 同一兜底链：结构化字段 → 推荐 tag → 移除标记）
    val subtitle = modelSubtitle(intro, stringResource(R.string.model_intro_missing_badge))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = 12.dp + (depth * 16).dp,
                end = 16.dp,
                top = INTRO_ROW_SPACING,
                bottom = INTRO_ROW_SPACING
            )
    ) {
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

/**
 * 档案孤儿条目行（合并态档案节点下，共 5 条）：介绍表里没有这条模型，点不开详情抽屉，
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
