package sh.hnet.comfychair.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.MAX_TIMELINE_CLIPS
import sh.hnet.comfychair.model.TimelineClipDraft
import sh.hnet.comfychair.model.TimelineReferenceMode
import sh.hnet.comfychair.model.UncoveredInterval

/**
 * 时间线导演台的片段编辑器。
 *
 * 手机上刻意不做拖拽时间线（屏幕宽度放不下可用的时间刻度，拖到十几秒以外就失去精度），
 * 改成"每段一张卡片、三个数字框"的表单式编辑 —— 导演台真正需要的操作
 * （摆位置、定长度、选参考方式）都能精确表达，而且每个数字都是可复现的。
 *
 * 空隙不靠用户自己算：下方直接把选区内的空隙列出来，点一下就对准它。
 * 这正是这台导演台最常用的用法（两段之间留空、让模型补中间）。
 */
@Composable
fun TimelineClipArea(
    clips: List<TimelineClipDraft>,
    selectionStart: Double,
    selectionDuration: Double,
    gaps: List<UncoveredInterval>,
    coveredSeconds: Double,
    canAdd: Boolean,
    loadedFromWorkflow: Boolean,
    onAddClip: (uri: Uri) -> Unit,
    onClipStartChange: (id: String, seconds: Double) -> Unit,
    onClipDurationChange: (id: String, seconds: Double) -> Unit,
    onClipTrimStartChange: (id: String, seconds: Double) -> Unit,
    onClipReferenceModeChange: (id: String, mode: TimelineReferenceMode) -> Unit,
    onRemoveClip: (id: String) -> Unit,
    onSelectionChange: (start: Double, duration: Double) -> Unit,
    onUseGap: (UncoveredInterval) -> Unit,
    modifier: Modifier = Modifier
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAddClip(uri)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.timeline_section_title),
            trailing = stringResource(R.string.timeline_clip_count, clips.size, MAX_TIMELINE_CLIPS)
        )

        if (loadedFromWorkflow) {
            Text(
                text = stringResource(R.string.timeline_loaded_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        clips.forEachIndexed { index, clip ->
            // 按片段 id 定 key：不给 key 时 Compose 按位置复用，删掉中间一段会让后面那张
            // 卡片继承前一张的输入框文本状态 —— 两个值恰好接近时就看不出错，看起来像"数字自己变了"
            key(clip.id) {
                TimelineClipCard(
                    index = index,
                    clip = clip,
                    onStartChange = { onClipStartChange(clip.id, it) },
                    onDurationChange = { onClipDurationChange(clip.id, it) },
                    onTrimStartChange = { onClipTrimStartChange(clip.id, it) },
                    onReferenceModeChange = { onClipReferenceModeChange(clip.id, it) },
                    onRemove = { onRemoveClip(clip.id) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (canAdd) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { picker.launch("video/*") }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.timeline_add_clip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SelectionRangeEditor(
            selectionStart = selectionStart,
            selectionDuration = selectionDuration,
            coveredSeconds = coveredSeconds,
            onSelectionChange = onSelectionChange
        )

        Spacer(modifier = Modifier.height(10.dp))

        GapList(gaps = gaps, onUseGap = onUseGap)
    }
}

/** 生成范围：起点 + 时长，下面一行是"被原画面固定多少、模型要生成多少" */
@Composable
private fun SelectionRangeEditor(
    selectionStart: Double,
    selectionDuration: Double,
    coveredSeconds: Double,
    onSelectionChange: (start: Double, duration: Double) -> Unit
) {
    SectionHeader(title = stringResource(R.string.timeline_selection_title))

    Row(modifier = Modifier.fillMaxWidth()) {
        TimelineNumberField(
            label = stringResource(R.string.timeline_field_selection_start),
            value = selectionStart,
            onValueChange = { onSelectionChange(it, selectionDuration) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        TimelineNumberField(
            label = stringResource(R.string.timeline_field_selection_length),
            value = selectionDuration,
            onValueChange = { onSelectionChange(selectionStart, it) },
            modifier = Modifier.weight(1f)
        )
    }

    val freeSeconds = (selectionDuration - coveredSeconds).coerceAtLeast(0.0)
    Text(
        text = stringResource(
            R.string.timeline_summary,
            formatSeconds(coveredSeconds),
            formatSeconds(freeSeconds)
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** 选区内可生成的空隙；一条都没有时说明选区完全压在片段上 */
@Composable
private fun GapList(gaps: List<UncoveredInterval>, onUseGap: (UncoveredInterval) -> Unit) {
    if (gaps.isEmpty()) {
        Text(
            text = stringResource(R.string.timeline_gap_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    SectionHeader(title = stringResource(R.string.timeline_gaps_title))
    gaps.forEach { gap ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.timeline_gap_item,
                    formatSeconds(gap.start),
                    formatSeconds(gap.end),
                    formatSeconds(gap.duration)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.timeline_gap_use),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onUseGap(gap) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** 单个片段：文件名 + 三个数字 + 参考方式下拉 + 删除 */
@Composable
private fun TimelineClipCard(
    index: Int,
    clip: TimelineClipDraft,
    onStartChange: (Double) -> Unit,
    onDurationChange: (Double) -> Unit,
    onTrimStartChange: (Double) -> Unit,
    onReferenceModeChange: (TimelineReferenceMode) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.timeline_clip_label, index + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.timeline_clip_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TimelineNumberField(
                label = stringResource(R.string.timeline_field_start),
                value = clip.start,
                onValueChange = onStartChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            TimelineNumberField(
                label = stringResource(R.string.timeline_field_duration),
                value = clip.duration,
                onValueChange = onDurationChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            TimelineNumberField(
                label = stringResource(R.string.timeline_field_trim),
                value = clip.trimStart,
                onValueChange = onTrimStartChange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        ReferenceModeDropdown(
            current = clip.referenceMode,
            onSelect = onReferenceModeChange
        )
    }
}

/**
 * 参考方式的三档下拉。
 *
 * 三个选项的说明写在选项文字里（"固定原画面"这类），不另做帮助页：
 * 这一项直接决定生成结果长什么样，用户必须在选的时候就看见差别。
 */
@Composable
private fun ReferenceModeDropdown(
    current: TimelineReferenceMode,
    onSelect: (TimelineReferenceMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.timeline_reference_mode_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(referenceModeLabelRes(current)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TimelineReferenceMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(referenceModeLabelRes(mode))) },
                        onClick = {
                            expanded = false
                            onSelect(mode)
                        }
                    )
                }
            }
        }
    }
}

private fun referenceModeLabelRes(mode: TimelineReferenceMode): Int = when (mode) {
    TimelineReferenceMode.GUIDE -> R.string.timeline_reference_mode_guide
    TimelineReferenceMode.EDIT -> R.string.timeline_reference_mode_edit
    TimelineReferenceMode.BOUNDARY -> R.string.timeline_reference_mode_boundary
}

/**
 * 秒数输入框。
 *
 * 本地留一份文本是有必要的：把每次按键都当数字解析，"1." 这种中间状态会解析失败、
 * 外部值一回填就把光标里的内容改掉，用户根本打不出小数。
 *
 * 关键在于**有焦点时绝不回填**。只在"值变了就同步文本"是不够的：状态里的值会被夹到
 * 合法区间（起点非负、长度不小于一帧、生成时长不小于节点下限），于是用户打 `0`（想打
 * `0.5`）时状态被夹成 `0.21`，回填把输入框改成 `"0.21"`，接着的按键就接成了
 * `"0.21.5"` —— 解析不出、显示错乱，而状态里还是 0.21，用户看不到自己真正提交的是什么。
 * 失焦后再对齐一次，那时用户已经输完了。
 */
@Composable
private fun TimelineNumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(formatSeconds(value)) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(value, editing) {
        // 编辑中不回填（见上）；失焦时对齐成状态里的真值 —— 用户输入被夹过的值也能看见
        if (editing) return@LaunchedEffect
        val parsed = text.toDoubleOrNull()
        if (parsed == null || kotlin.math.abs(parsed - value) > 0.001) {
            text = formatSeconds(value)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.onFocusChanged { editing = it.isFocused }
    )
}

/** 秒数统一两位小数。用 [Locale.US] 定点格式化：某些语言环境的小数点是逗号，会让数字框内容不可解析 */
private fun formatSeconds(seconds: Double): String =
    String.format(Locale.US, "%.2f", seconds)

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
