package sh.hnet.comfychair.ui.components.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.prompt.SkillPromptRepository
import sh.hnet.comfychair.prompt.SkillPromptStore

/**
 * 「技能提示词」选择区：给工作流挑一份 md 规则文件。
 *
 * 只在工作流本身带 `{{skill_file_name}}` 占位符（技能流 技能流模板）时才出现 ——
 * 普通出图流没有这个字段，露出来只会让人困惑。默认值按工作流类型给（文生图 / 图像编辑各一份），
 * 用户改过的选择按工作流名持久化（[SkillPromptStore]）。
 *
 * 选项只有 assets 里真实存在的规则文件，**没有「不注入」入口**：空规则会让 ComfyUI 走
 * 「自动选择 Skill」分支并报错（提示词增强页的 usableRuleFiles 同样过滤了这一点）；
 * 历史上存过空串的用户加载时会自动落到默认规则上。
 *
 * 「查看」按钮是任务书要的**独立入口**：弹窗里能看到全部技能文件、随时切换、并预览正文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPromptSection(
    workflowName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val workflow = remember(workflowName) { WorkflowManager.getWorkflowByName(workflowName) }
    val json = workflow?.jsonContent.orEmpty()
    // 技能流才显示（占位符是转换脚本注入的，普通出图流里没有）
    if (!json.contains("{{skill_file")) return

    val options = remember { SkillPromptRepository.list(context.assets) }
    if (options.isEmpty()) return

    var selected by remember(workflowName) {
        // 历史上存过空串（旧版「（不注入）」选项写入）的用户：空串不在 options 里，
        // 落到按类型给的默认规则上 —— 「不注入」会让 ComfyUI 走自动选择分支并报错，
        // 不许再把用户带进这个状态
        val stored = SkillPromptStore.get(context, workflowName)
        val chosen = options.firstOrNull { it.fileName == stored }?.fileName
            ?: workflow?.let { SkillPromptRepository.defaultFor(it.type, options) }.orEmpty()
        mutableStateOf(chosen)
    }
    var expanded by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "技能提示词",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = options.firstOrNull { it.fileName == selected }?.displayName ?: "（不注入）",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("规则文件") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    singleLine = true
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                selected = option.fileName
                                SkillPromptStore.set(context, workflowName, selected)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { showManager = true }) { Text("查看") }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "这份规则会交给工作流里的本地模型，把你的需求改写成成品提示词。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showManager) {
        SkillPromptManagerDialog(
            options = options,
            selected = selected,
            onSelect = { fileName ->
                selected = fileName
                SkillPromptStore.set(context, workflowName, fileName)
            },
            onDismiss = { showManager = false }
        )
    }
}

/**
 * 技能管理弹窗（独立入口）：列出全部技能文件、随时切换、预览正文并可复制。
 */
@Composable
private fun SkillPromptManagerDialog(
    options: List<SkillPromptRepository.SkillPrompt>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val preview = remember(selected) {
        if (selected.isEmpty()) "" else SkillPromptRepository.read(context.assets, selected).orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能提示词") },
        text = {
            Column {
                Text(
                    text = "选择要注入的规则文件",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                options.forEach { option ->
                    SkillRow(
                        label = option.displayName,
                        chosen = option.fileName == selected,
                        onClick = { onSelect(option.fileName) }
                    )
                }
                if (preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${preview.length} 字符 · 开头预览",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = preview.take(600),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        if (preview.isNotEmpty()) copyToClipboard(context, "skill", preview)
                    },
                    enabled = preview.isNotEmpty()
                ) { Text("复制正文") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun SkillRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (chosen) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "✓ 当前", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 复制到剪贴板。不打 `EXTRA_IS_SENSITIVE` —— 这份内容用户就是要拿去用的。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
