package sh.hnet.comfychair.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.viewmodel.AudioFileChoice

/**
 * 参考音频区（接在图位区下方，与图位共用同一片区域）。
 *
 * 与图位区的差异是刻意的：
 * - 音频没法显示缩略图，所以一行一个槽、只显示文件名；
 * - 数量少（上限 3），所以不做长按多选删除，每行自带一个删除按钮；
 * - 点空槽或点已填的行都是"换一个文件"。
 *
 * 槽位上限由工作流的节点定义决定（`ref_audios` 的 max），[canAdd] 为 false 时
 * 连「+ 添加」都不显示 —— 与图位区同一套判据口径。
 */
@Composable
fun AudioSlotArea(
    slots: List<AudioSlot>,
    files: Map<String, AudioFileChoice>,
    canAdd: Boolean,
    onAudioSelected: (placeholder: String, uri: Uri) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveSlot: (placeholder: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 选文件的目标槽位：选完才知道该写给哪个槽
    var pickTarget by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pickTarget
        pickTarget = null
        if (uri != null && target != null) onAudioSelected(target, uri)
    }
    val launchPick: (String) -> Unit = { placeholder ->
        pickTarget = placeholder
        picker.launch("audio/*")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.audio_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        slots.forEachIndexed { index, slot ->
            val choice = files[slot.placeholder]
            AudioSlotRow(
                label = stringResource(R.string.audio_slot_label, index + 1),
                fileName = choice?.displayName,
                onClick = { launchPick(slot.placeholder) },
                onRemove = { onRemoveSlot(slot.placeholder) }
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (canAdd) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAddSlot)
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
                    text = stringResource(R.string.audio_slot_add),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 单个音频槽：空槽显示提示，已填槽显示文件名，右侧是删除按钮 */
@Composable
private fun AudioSlotRow(
    label: String,
    fileName: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = fileName ?: stringResource(R.string.audio_slot_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (fileName != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.audio_slot_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
