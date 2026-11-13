package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ImageSlot

/**
 * 图位网格。图生图与图生视频共用。
 *
 * 布局：1 个图位占满整行；2 个各占一半；3 个以上每行 2 个。
 * 甲类（具名）图位数量固定，不显示「+ 添加」；乙类（编号）图位可由用户增加。
 *
 * @param slots 工作流的图位列表
 * @param images 已选图片，键为图位占位符名
 * @param selectionMode 多选模式：显示勾选框，隐藏「+ 添加」
 * @param selected 已选中的图位占位符名
 * @param canAddNumbered 是否显示「+ 添加」。由调用方按"工作流有没有乙类容器"这个持久事实派生
 *   （见 `ImageToVideoUiState.canAddNumberedSlot`），**不能**用 slots 里还剩几个编号图位来推：
 *   用户把编号图位删光后 slots 里一个都没有，但入口必须还在（设计 §3）
 * @param onSlotClick 单击图位（空位应打开选图，已有图则进入预览/更换）
 * @param onSlotLongClick 长按图位（多选模式外用于删除等操作）
 * @param onAddClick 点击「+ 添加」新增一个乙类图位
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageSlotGrid(
    slots: List<ImageSlot>,
    images: Map<String, Bitmap>,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Set<String> = emptySet(),
    canAddNumbered: Boolean = false,
    onSlotClick: (ImageSlot) -> Unit = {},
    onSlotLongClick: (ImageSlot) -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    // 「+ 添加」的判据来自调用方，不从 slots 反推：编号图位被删光后 slots 里一个 Numbered
    // 都不剩，但入口必须还在，否则用户再也加不回来（设计 §3）；判据见 ImageToVideoUiState
    val canAdd = canAddNumbered

    // 同类图位内部的序号（1 起），用于生成「参考图 2」「图片 3」这样的名字。
    // 注意不能用 slot.order —— 它是排序键（编号图位的基准是 100），不是同类内的序号，
    // 直接拿它加 1 会显示出「参考图 101」这种名字。
    // 列表本身由 ViewModel 按 order 维护好顺序（`withSlotAdded`），所以这里的列表顺序与编号升序一致，
    // 数出来的「参考图 N」就是模型看到的 `<Picture N>`。
    val ordinals = mutableMapOf<String, Int>()
    var genericSeen = 0
    var referenceSeen = 0
    for (slot in slots) {
        when (slot) {
            is ImageSlot.Named ->
                if (slot.semantics == ImageSlot.Semantics.GENERIC) {
                    ordinals[slot.placeholder] = ++genericSeen
                }
            is ImageSlot.Numbered -> ordinals[slot.placeholder] = ++referenceSeen
        }
    }

    // 只有一个图位时独占整行，否则每行 2 个
    val columns = if (slots.size == 1) 1 else 2

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { slot ->
                    ImageSlotCell(
                        slot = slot,
                        ordinal = ordinals[slot.placeholder] ?: 1,
                        bitmap = images[slot.placeholder],
                        selectionMode = selectionMode,
                        isSelected = slot.placeholder in selected,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = { onSlotClick(slot) },
                        onLongClick = { onSlotLongClick(slot) }
                    )
                }
                // 该行不足 2 个时用占位撑住，避免单个格子被拉成整行宽（仅当该行有同伴时才补）
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (canAdd && !selectionMode) {
            OutlinedButton(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.image_slot_add))
            }
        }
    }
}

@Composable
private fun ImageSlotCell(
    slot: ImageSlot,
    /** 同类图位内部的序号（1 起），由 ImageSlotGrid 算好传入 */
    ordinal: Int,
    bitmap: Bitmap?,
    selectionMode: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 标签：甲类按语义，乙类带序号
    val label = when (slot) {
        is ImageSlot.Named -> when (slot.semantics) {
            ImageSlot.Semantics.FIRST_FRAME -> stringResource(R.string.image_slot_first_frame)
            ImageSlot.Semantics.LAST_FRAME -> stringResource(R.string.image_slot_last_frame)
            ImageSlot.Semantics.GENERIC -> stringResource(R.string.image_slot_generic, ordinal)
        }
        is ImageSlot.Numbered -> stringResource(R.string.image_slot_reference, ordinal)
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = stringResource(R.string.image_slot_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 图位名浮在左上角
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (selectionMode && bitmap != null) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}
