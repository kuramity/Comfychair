package sh.hnet.comfychair.taglibrary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.taglibrary.model.AppliedTag
import sh.hnet.comfychair.taglibrary.state.PromptBridge

/**
 * 底部构建器（需求 4.4）。
 *
 * 左右分栏展示正向 / 负向提示词的已添加标签，支持：
 * - 点击权重值弹出编辑框（±0.1 步进或直接输入）
 * - 点击 × 从列表移除（单向删除，不影响提示词内容）
 * - 一键收起为窄标题栏（收起后仍可继续通过「+正 / -负」添加）
 */
@Composable
fun TagBuilderPanel(
    expanded: Boolean,
    positiveTags: List<AppliedTag>,
    negativeTags: List<AppliedTag>,
    hasTarget: Boolean,
    onToggle: () -> Unit,
    onWeightChange: (PromptBridge.Channel, Int, Float) -> Unit,
    onRemove: (PromptBridge.Channel, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 当前正在编辑权重的标签（null 表示无弹窗）
    var editing by remember { mutableStateOf<EditingState?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ---------- 标题栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)  // 触控优化：最小 48dp
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tag_builder_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        R.string.tag_builder_counts,
                        positiveTags.size,
                        negativeTags.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = stringResource(
                        if (expanded) R.string.tag_builder_collapse else R.string.tag_builder_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ---------- 展开区 ----------
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (!hasTarget) {
                        // 未绑定工作流时的提示
                        Text(
                            text = stringResource(R.string.tag_builder_no_target),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 168.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            BuilderColumn(
                                title = stringResource(R.string.tag_builder_positive),
                                titleColor = MaterialTheme.colorScheme.primary,
                                tags = positiveTags,
                                channel = PromptBridge.Channel.POSITIVE,
                                onWeightClick = { index, tag ->
                                    editing = EditingState(PromptBridge.Channel.POSITIVE, index, tag)
                                },
                                onRemove = onRemove,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                            BuilderColumn(
                                title = stringResource(R.string.tag_builder_negative),
                                titleColor = MaterialTheme.colorScheme.error,
                                tags = negativeTags,
                                channel = PromptBridge.Channel.NEGATIVE,
                                onWeightClick = { index, tag ->
                                    editing = EditingState(PromptBridge.Channel.NEGATIVE, index, tag)
                                },
                                onRemove = onRemove,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 权重编辑弹窗 ----------
    editing?.let { state ->
        TagWeightDialog(
            tag = state.tag,
            onDismiss = { editing = null },
            onConfirm = { newWeight ->
                onWeightChange(state.channel, state.index, newWeight)
                editing = null
            }
        )
    }
}

/** 权重编辑状态。 */
private data class EditingState(
    val channel: PromptBridge.Channel,
    val index: Int,
    val tag: AppliedTag
)

/** 单侧标签列。 */
@Composable
private fun BuilderColumn(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    tags: List<AppliedTag>,
    channel: PromptBridge.Channel,
    onWeightClick: (Int, AppliedTag) -> Unit,
    onRemove: (PromptBridge.Channel, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        if (tags.isEmpty()) {
            Text(
                text = stringResource(R.string.tag_builder_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(tags, key = { index, tag -> "$channel-$index-${tag.name}" }) { index, tag ->
                    AppliedTagRow(
                        tag = tag,
                        onWeightClick = { onWeightClick(index, tag) },
                        onRemove = { onRemove(channel, index) }
                    )
                }
            }
        }
    }
}

/** 单个已添加标签条。 */
@Composable
private fun AppliedTagRow(
    tag: AppliedTag,
    onWeightClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 权重按钮：点击弹出编辑框
            Box(
                modifier = Modifier
                    .clickable { onWeightClick() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = formatWeightLabel(tag.weight),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (AppliedTag.isNeutral(tag.weight)) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            // 移除按钮（单向删除，不改提示词）
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.tag_builder_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 权重显示文本（1.0 显示为「1.0」，保留一位小数便于识别）。 */
private fun formatWeightLabel(weight: Float): String =
    String.format(java.util.Locale.US, "%.1f", weight)

/**
 * 权重编辑弹窗（±0.1 步进 + 直接输入）。
 */
@Composable
private fun TagWeightDialog(
    tag: AppliedTag,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var input by remember { mutableStateOf(formatWeightLabel(tag.weight)) }
    val parsed = input.toFloatOrNull()
    val valid = parsed != null && parsed in 0.1f..2.0f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.tag_weight_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val current = parsed ?: 1.0f
                        input = formatWeightLabel((current - 0.1f).coerceAtLeast(0.1f))
                    }) { Text("−0.1") }

                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        isError = !valid,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    TextButton(onClick = {
                        val current = parsed ?: 1.0f
                        input = formatWeightLabel((current + 0.1f).coerceAtMost(2.0f))
                    }) { Text("+0.1") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(parsed!!) },
                enabled = valid
            ) { Text(stringResource(R.string.tag_weight_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tag_weight_cancel)) }
        }
    )
}
