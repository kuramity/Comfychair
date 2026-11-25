package sh.hnet.comfychair.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.CustomField
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.model.ModelIntroTemplates
import sh.hnet.comfychair.model.Template
import sh.hnet.comfychair.model.TemplateIcon
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.viewmodel.ModelIntroViewModel

/**
 * 介绍文档编辑器（v4.21，二级页面）。
 *
 * 一个界面承担两种模式：`file == null` 是**新建**（顶栏「＋」进入，先给 3 张模板卡片），
 * 有值是**编辑**（列表长按「编辑这条」进入，预填该条目）。写盘只有一条路：
 * [ModelIntroViewModel.save] → [ModelIntroTable.upsertEntry]（整条写入覆盖层，
 * 内置库与热更新缓存一个字节都不改）——界面自己绝不碰磁盘。
 *
 * 三个语义点（与数据层 KDoc 对齐，别在界面里另发明一套）：
 * 1. 编辑内置条目允许，保存后是**整条覆盖**；界面给一句提示「将覆盖内置内容，可恢复」。
 * 2. 删除按钮只在「覆盖层自建条目」（内置库不存在同名条目）时出现，走
 *    [ModelIntroViewModel.deleteCustom]；内置条目要消失只能卸载（列表长按），这里不提供。
 * 3. 自定义字段的空白草稿行由本界面自己维护（[upsertEntry] 落盘前会剔除空白项，
 *    见 ModelIntroTable KDoc「界面编辑框里的草稿行由界面自己维护」）。
 *
 * 取消 = 返回箭头 / 系统返回，不写盘直接回列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroEditorScreen(
    file: String? = null,
    onBack: () -> Unit,
    viewModel: ModelIntroViewModel = viewModel(),
) {
    val context = LocalContext.current
    val isNew = file.isNullOrBlank()

    // ---- 草稿状态 ----
    var original by remember { mutableStateOf<ModelIntro?>(null) }   // 被编辑的条目（新建为 null）
    var isCustomEntry by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }                  // 装载完成前不出表单（避免预填闪烁）
    var saving by remember { mutableStateOf(false) }                 // 连点保护

    var fileName by remember { mutableStateOf(file.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    var effect by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf<List<CustomField>>(emptyList()) }
    // 模板区只在「新建且还没选过模板/跳过」时出现；选完即收起，让表单成为主体
    var templateResolved by remember { mutableStateOf(false) }

    var pendingDelete by remember { mutableStateOf(false) }
    val fileRequired = stringResource(R.string.model_intro_editor_file_required)

    // ---- 装载（与详情页同一纪律：读表走 IO，不在主线程解析）----
    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            ModelIntroTable.ensureLoaded(context)
            // 条目已不在视图（例如进页前被卸载）→ 不预填、不给删除入口，文件名仍带过去便于改回
            val entry = file?.let { ModelIntroTable.lookup(it) }
            entry to (entry != null && viewModel.isCustomEntry(entry.file))
        }
        val entry = loaded.first
        original = entry
        isCustomEntry = loaded.second
        if (entry != null) {
            fileName = entry.file
            name = entry.name.orEmpty()
            base = entry.base.orEmpty()
            version = entry.version.orEmpty()
            kind = entryKindOf(entry)
            effect = entry.effect.orEmpty()
            fields = entry.customFields
            templateResolved = true // 编辑模式不展示模板区
        }
        ready = true
    }

    // Toast 收集与列表页同一通道（保存/删除的成功与失败提示由 ViewModel 给出）
    ModelIntroToastHost(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.model_intro_editor_title_new
                            else R.string.model_intro_editor_title_edit
                        )
                    )
                },
                navigationIcon = {
                    // 取消：不写盘直接返回（覆盖层只有点「保存」才会被碰）
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.button_back)
                        )
                    }
                },
                actions = {
                    if (isCustomEntry) {
                        // 删除仅自建条目可见（任务书 2）：判据来自数据层 isCustomEntry，
                        // 界面不自己猜「这条是不是内置的」
                        IconButton(
                            onClick = { pendingDelete = true },
                            enabled = !saving
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.model_intro_editor_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            if (saving) return@TextButton
                            val trimmedFile = fileName.trim()
                            if (trimmedFile.isEmpty()) {
                                Toast.makeText(context, fileRequired, Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            saving = true
                            viewModel.save(
                                context = context,
                                intro = buildDraft(
                                    original = original,
                                    file = trimmedFile,
                                    name = name,
                                    base = base,
                                    version = version,
                                    kind = kind,
                                    effect = effect,
                                    fields = fields,
                                )
                            ) { ok ->
                                saving = false
                                if (ok) onBack() // 失败留在页面（原因已由 Toast 提示），用户可改可重试
                            }
                        },
                        enabled = ready && !saving
                    ) {
                        Text(stringResource(R.string.model_intro_editor_save))
                    }
                }
            )
        }
    ) { padding ->
        if (!ready) return@Scaffold // 装载中：空页面一瞬；表已在列表页装好，这里通常不闪
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.Spacing16),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing12)
        ) {
            // ---- 模板选择（仅新建；点选即预填自定义字段，或跳过从空白开始）----
            if (isNew && !templateResolved) {
                TemplateChooser(
                    onPick = { template ->
                        fields = template.customFields
                        templateResolved = true
                    },
                    onSkip = { templateResolved = true }
                )
            }

            // ---- 基本字段 ----
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.model_intro_editor_field_file)) },
                // 编辑模式文件名是覆盖层的键，改它等于另建一条——只读（新建模式可自由填）
                readOnly = !isNew,
                singleLine = true,
                enabled = isNew,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.model_intro_editor_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = base,
                onValueChange = { base = it },
                label = { Text(stringResource(R.string.model_intro_editor_field_base)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = version,
                onValueChange = { version = it },
                label = { Text(stringResource(R.string.model_intro_editor_field_version)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            KindDropdown(
                selected = kind,
                // 委托 var 不做智能转换 → 先落局部 val 再拼选项表
                options = remember(kind) {
                    val current = kind
                    // 8 类 + 现值兜底：数据侧出现新类型时下拉仍显示得出原值（与列表树
                    // 「未知新类型原样显示」同一原则），不能把它丢成空选项
                    if (current != null && current !in KIND_ORDER) KIND_ORDER + current else KIND_ORDER
                },
                label = stringResource(R.string.model_intro_editor_field_kind),
                onSelected = { kind = it }
            )
            OutlinedTextField(
                value = effect,
                onValueChange = { effect = it },
                label = { Text(stringResource(R.string.model_intro_editor_field_effect)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            // 编辑内置条目的后果提示（任务书 2：整条覆盖、可恢复）——
            // 判据与删除按钮互补：自建条目可删、内置条目只提覆盖
            if (!isNew && original != null && !isCustomEntry) {
                Text(
                    text = stringResource(R.string.model_intro_editor_builtin_override),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---- 自定义字段区（有序键值对，可增删；顺序就是列表顺序，不做重排）----
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.model_intro_custom_fields),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { fields = fields + CustomField(label = "", value = "") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.model_intro_editor_add_field))
                    }
                }
                fields.forEachIndexed { index, field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = field.label,
                            onValueChange = { text ->
                                // 草稿允许空白（没写完的中间态），落盘前由数据层与 buildDraft
                                // 双重剔除——界面别把用户写到一半的行吞掉
                                fields = fields.toMutableList().also {
                                    it[index] = it[index].copy(label = text)
                                }
                            },
                            label = { Text(stringResource(R.string.model_intro_editor_label_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.42f)
                                .padding(end = Dimensions.Spacing8)
                        )
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { text ->
                                fields = fields.toMutableList().also {
                                    it[index] = it[index].copy(value = text)
                                }
                            },
                            label = { Text(stringResource(R.string.model_intro_editor_value_hint)) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { fields = fields.filterIndexed { i, _ -> i != index } }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.model_intro_editor_remove_field),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 删除自建条目确认（与卸载确认同款两段式）----
    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.model_intro_editor_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_intro_editor_delete_message,
                        original?.name?.takeIf { it.isNotBlank() } ?: fileName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    val key = original?.file ?: fileName.trim()
                    if (key.isEmpty()) return@TextButton
                    saving = true // 删除中也锁住保存，避免两个写操作打架
                    viewModel.deleteCustom(context, key) { ok ->
                        saving = false
                        if (ok) onBack()
                    }
                }) {
                    Text(
                        text = stringResource(R.string.model_intro_editor_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * 条目「类型」的当前值：优先 kind（分类树用它的 8 类枚举），
 * 缺失时按前缀匹配 type（「大模型（Checkpoint）」→「大模型」）——
 * 匹配不上就不预选（下拉留空，展示层继续按 type ?: kind 显示原值，界面不瞎猜）。
 */
private fun entryKindOf(entry: ModelIntro): String? {
    entry.kind?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val type = entry.type?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return KIND_ORDER.firstOrNull { type.startsWith(it, ignoreCase = true) }
}

/**
 * 编辑器提交 → 完整 [ModelIntro]（纯函数，供 JVM 单测）。
 *
 * 三条规则都有数据层依据（[ModelIntroTable.upsertEntry] 的 KDoc：编辑器提交整条，
 * 不做字段级合并）：
 * - 没在表单里出现的字段（summary / params / tags / 原文……）从被编辑条目**原样带走**，
 *   整条覆盖才不会把没编辑的部分洗掉；
 * - 类型下拉只写 kind：type 的带后缀说法（「大模型（Checkpoint）」）与 kind 语义同源，
 *   kind 变了而 type 不变会让元信息头继续显示旧类型（type 优先）——kind 改动时把过期的
 *   type 剥掉，头部退回显示新 kind；kind 没动则 type 原样保留；
 * - 空白自定义字段行落盘前剔除（与 upsertEntry 同一判定，界面先筛一遍，行为与内存一致）。
 */
internal fun buildDraft(
    original: ModelIntro?,
    file: String,
    name: String,
    base: String,
    version: String,
    kind: String?,
    effect: String,
    fields: List<CustomField>,
): ModelIntro {
    val kindOut = kind?.trim()?.takeIf { it.isNotEmpty() }
    val kindChanged = kindOut != (original?.kind?.trim()?.takeIf { it.isNotEmpty() })
    return (original ?: ModelIntro(file = file)).copy(
        file = file,
        kind = kindOut,
        type = when {
            original == null -> null
            !kindChanged -> original.type
            else -> null
        },
        name = name.trim().takeIf { it.isNotEmpty() },
        base = base.trim().takeIf { it.isNotEmpty() },
        version = version.trim().takeIf { it.isNotEmpty() },
        effect = effect.trim().takeIf { it.isNotEmpty() },
        customFields = fields.filter { it.label.isNotBlank() && it.value.isNotBlank() },
    )
}

/** 类型下拉（ExposedDropdownMenu，与 [sh.hnet.comfychair.ui.components.LanguageDropdown] 同款）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(
    selected: String?,
    options: List<String>,
    label: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

/**
 * 模板选择区（新建模式首屏）：三张卡片按 [ModelIntroTemplates.builtInTemplates] 的
 * 返回顺序排（数据层钉住 LoRA → 大模型 → 通用），点选即用模板的 [Template.customFields]
 * 预填自定义字段（值全为空串——预填示例值是假介绍，数据层 KDoc 明令禁止）。
 */
@Composable
private fun TemplateChooser(onPick: (Template) -> Unit, onSkip: () -> Unit) {
    val templates = remember { ModelIntroTemplates.builtInTemplates() }
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)) {
        Text(
            text = stringResource(R.string.model_intro_editor_template_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)) {
            templates.forEach { template ->
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPick(template) }
                ) {
                    Column(
                        modifier = Modifier.padding(Dimensions.Spacing12),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing4)
                    ) {
                        Icon(
                            imageVector = templateIconFor(template.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = template.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        template.hint?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.model_intro_editor_template_skip))
        }
    }
}

/** 模板图标语义 → Material 图标（数据层只给枚举不给 Android 资源，映射归界面）。 */
private fun templateIconFor(icon: TemplateIcon) = when (icon) {
    TemplateIcon.LORA -> Icons.Default.Tune
    TemplateIcon.CHECKPOINT -> Icons.Default.Extension
    TemplateIcon.GENERIC -> Icons.Default.Description
}
