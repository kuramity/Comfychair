package sh.hnet.comfychair.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.bottomSheetSafeDrawingInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.storage.PromptPresetStorage
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.ui.theme.Dimensions
import java.util.Locale

/**
 * Bottom sheet displaying generation metadata from ComfyUI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataBottomSheet(
    metadata: GenerationMetadata?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Storage for saving prompts to library
    val storage = remember { PromptPresetStorage(context) }

    // Dialog state for saving prompt to library
    var showSavePromptDialog by remember { mutableStateOf(false) }
    // 保存到提示词库：正向与负面一起带进对话框（面板里两条提示词都在，一次存全）
    var positiveToSave by remember { mutableStateOf("") }
    var negativeToSave by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 统一安全区：底部让开手势条 / 三键导航栏与输入法，左右让开横屏刘海。
        // 本弹窗宿主在 edge-to-edge 的 MediaViewerActivity 里，原先写死
        // WindowInsets(0,0,0,0) 会让三键导航（48dp）挡住滚动到底的最后一行。
        contentWindowInsets = { bottomSheetSafeDrawingInsets() }
    ) {
        NoOverscrollContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.Spacing16)
                    .padding(bottom = Dimensions.Spacing32)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题 + 「加入提示词库」：正向与负面一并存入，按钮放在标题右侧
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.title_generation_parameters),
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (metadata != null) {
                        IconButton(
                            onClick = {
                                positiveToSave = metadata.positivePrompt.orEmpty()
                                negativeToSave = metadata.negativePrompt.orEmpty()
                                showSavePromptDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BookmarkAdd,
                                contentDescription = stringResource(R.string.metadata_save_prompt),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                when {
                    isLoading -> {
                        // Loading state
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.metadata_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    metadata == null -> {
                        // No metadata available
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.metadata_not_available),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        // Display metadata
                        val items = remember(metadata) { buildMetadataItems(metadata, context) }
                        items.forEach { item ->
                            // 「加入提示词库」已移到标题右侧（正负一并保存），行内不再放书签按钮
                            MetadataRow(
                                label = item.label,
                                value = item.value,
                                onCopy = { copyToClipboard(context, item.value) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Save prompt to library dialog
    if (showSavePromptDialog) {
        PromptPresetDialog(
            editingPreset = null,
            currentPrompt = positiveToSave,
            // 面板里正负提示词都有，一并预填（用户可再改）
            currentNegativePrompt = negativeToSave,
            existingTags = emptySet(),
            isNameTaken = { _, _ -> false },  // Not used in screen type selector mode
            onDismiss = { showSavePromptDialog = false },
            onSave = { _, _, _, _ -> },  // Not used in screen type selector mode
            showScreenTypeSelector = true,
            storage = storage,
            onSaveWithScreenType = { name, prompt, negativePrompt, tags, screenType ->
                val preset = PromptPreset.create(screenType, name, prompt, tags, negativePrompt)
                // 存储损坏时 addPreset 会拒绝写入并返回 false：不能弹「已保存」，也不关对话框
                if (storage.addPreset(preset)) {
                    showSavePromptDialog = false
                    Toast.makeText(context, R.string.msg_prompt_preset_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        R.string.error_local_store_corrupted,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

/**
 * A single row in the metadata sheet.
 *
 * @param label The label to display
 * @param value The value to display
 * @param onCopy Called when the copy button is tapped
 * @param onSave Optional callback for save action; when provided, a save button is shown
 */
@Composable
private fun MetadataRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row {
            if (onSave != null) {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = stringResource(R.string.metadata_save_prompt),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.button_copy_to_clipboard),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Data class for metadata item display.
 */
private data class MetadataItem(
    val label: String,
    val value: String,
    val isPositivePrompt: Boolean = false
)

/**
 * Build list of metadata items to display.
 */
private fun buildMetadataItems(metadata: GenerationMetadata, context: Context): List<MetadataItem> {
    val items = mutableListOf<MetadataItem>()

    metadata.positivePrompt?.let {
        items.add(MetadataItem(
            label = context.getString(R.string.metadata_positive_prompt),
            value = it,
            isPositivePrompt = true
        ))
    }

    metadata.negativePrompt?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_negative_prompt), it))
    }

    // 种子紧随提示词之后显示（自定义采样器工作流的种子常来自独立 SeedNode，
    // 见 MetadataParser 的解析逻辑），便于查看与一键复制
    metadata.seed?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_seed), it.toString()))
    }

    metadata.models.forEach { model ->
        items.add(MetadataItem(context.getString(R.string.metadata_model), model))
    }

    metadata.unets.forEach { unet ->
        items.add(MetadataItem(context.getString(R.string.metadata_unet), unet))
    }

    metadata.loras.forEach { lora ->
        // 固定 Locale.US：ComfyUI 参数里的数值始终用半角数字与 "." 小数点。
        // 跟随系统区域时，阿拉伯/波斯等区域会输出本地数字形态（如 ٠٫٨٠），
        // 既和服务器/提示词里的写法不一致，也会让用户复制后无法回填。
        val loraValue = "${lora.name} (${String.format(Locale.US, "%.2f", lora.strength)})"
        items.add(MetadataItem(context.getString(R.string.metadata_lora), loraValue))
    }

    metadata.steps?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_steps), it.toString()))
    }

    metadata.cfg?.let {
        // 同上：CFG 等采样参数固定 Locale.US，避免本地化数字形态
        items.add(MetadataItem(context.getString(R.string.metadata_cfg), String.format(Locale.US, "%.1f", it)))
    }

    metadata.sampler?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_sampler), it))
    }

    metadata.scheduler?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_scheduler), it))
    }

    if (metadata.width != null && metadata.height != null) {
        items.add(MetadataItem(
            context.getString(R.string.metadata_size),
            "${metadata.width} x ${metadata.height}"
        ))
    }

    metadata.vaes.forEach { vae ->
        items.add(MetadataItem(context.getString(R.string.metadata_vae), vae))
    }

    return items
}

/**
 * Copy text to clipboard and show toast.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("metadata", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
}
