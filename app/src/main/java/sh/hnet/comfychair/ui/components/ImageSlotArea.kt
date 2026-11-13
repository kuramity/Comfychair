package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.ui.theme.Dimensions

/**
 * 页面的图位区（图生图与图生视频共用）：分格展示图位，按设计 §3 的交互表操作——
 * 点空位选图、点已填位放大查看并在放大界面里更换、长按进入多选删除。
 *
 * **状态归本组件**（选择模式、正在查看的图位、选图目标），图片与图位定义由宿主传入、
 * 变更经回调上报，宿主再落到自己的 ViewModel 上。两个页面因此共用同一套操作方式。
 *
 * 布局：
 * - 只有 1 个图位且没有「+ 添加」时按**整块**渲染（设计 §3「1 张：整块」），尺寸与接入本组件
 *   之前的单图区域一致（`fillMaxWidth().weight(1f).heightIn(min = 150.dp)` + `fillMaxSize/Crop`）
 * - 其余情况交给 [ImageSlotGrid]；网格自带的格子是 1:1，所以宿主这里必须给**纵向滚动**，
 *   否则图位多于 4 个时末尾的「+ 添加」会被挤出可视区点不到（设计 §3）
 *
 * @param slots 工作流的图位定义（甲类具名 + 乙类编号）
 * @param images 已选图片，键为图位占位符名
 * @param canAddNumbered 是否显示「+ 添加」。判据是"工作流有没有乙类容器"这个持久事实，
 *   由宿主传入（见 `ImageToVideoUiState.canAddNumberedSlot`），不能从 [slots] 里还剩几个编号图位推
 * @param onImageSelected 选好图片（`GetContent` 返回）或更换图片时回调，宿主负责写入对应图位
 * @param onAddSlot 点「+ 添加」新增一个乙类图位
 * @param onDeleteSelected 选择模式下点「删除所选」时回调已勾选的图位占位符名。
 *   删除语义（乙类摘掉图位定义 / 甲类只清空图片）由宿主决定，见设计 §3「甲类数量由工作流固定」
 */
@Composable
fun ImageSlotArea(
    slots: List<ImageSlot>,
    images: Map<String, Bitmap>,
    canAddNumbered: Boolean,
    onImageSelected: (placeholder: String, uri: Uri) -> Unit,
    onAddSlot: () -> Unit,
    onDeleteSelected: (placeholders: Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // 图位定义变化（换工作流 / 增删图位）就收敛勾选，避免勾选项指向已不存在的图位；
    // 批量删除后勾选会清空，选择模式随之自动退出（见 SlotSelection.retainAvailable）
    val placeholders = remember(slots) { slots.map { it.placeholder } }
    var selection by remember { mutableStateOf(SlotSelection()) }
    LaunchedEffect(placeholders) { selection = selection.retainAvailable(placeholders.toSet()) }

    // 正在放大查看的图位
    var viewing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(placeholders) { if (viewing !in placeholders) viewing = null }

    // 选图目标：点空位、或放大界面里点「更换」时记下来，选图器返回后才知道该写给哪个图位
    var pickTarget by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pickTarget
        pickTarget = null
        if (uri != null && target != null) onImageSelected(target, uri)
    }
    val launchPick: (String) -> Unit = { placeholder ->
        pickTarget = placeholder
        picker.launch("image/*")
    }

    // 放大界面与格子里显示同一个图位名
    val labels = imageSlotLabels(slots)

    Column(modifier = modifier.fillMaxSize()) {
        if (selection.active) {
            SlotSelectionBar(
                canDelete = selection.canDelete,
                onDelete = {
                    onDeleteSelected(selection.selected)
                    // 删除后必须显式退出：乙类图位被摘掉时靠 retainAvailable 也能收敛，但甲类图位
                    // 只是清空图片、图位还在，勾选会留在集合里而格子上已没有勾选框
                    // （界面上看不见的项 + 「删除所选」仍可用，正是设计要避免的形态）
                    selection = selection.exit()
                },
                onExit = { selection = selection.exit() }
            )
        }

        if (slots.size == 1 && !canAddNumbered) {
            // 单图位：整块（设计 §3）。尺寸与接入前的单图区域一致
            val slot = slots.first()
            val bitmap = images[slot.placeholder]
            SingleSlotBlock(
                bitmap = bitmap,
                label = labels[slot.placeholder],
                selectionMode = selection.active,
                isSelected = slot.placeholder in selection.selected,
                onClick = {
                    when {
                        selection.active -> selection = selection.toggle(slot.placeholder)
                        bitmap != null -> viewing = slot.placeholder
                        else -> launchPick(slot.placeholder)
                    }
                },
                // 长按只对已填格子生效：空格子不显示勾选框，选中了也看不出来（见 SlotSelection 注释）
                onLongClick = {
                    if (bitmap != null) selection = selection.enter(slot.placeholder)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 150.dp)
            )
        } else {
            // 网格：自带纵向滚动，图位多于 4 个时末尾的「+ 添加」仍可滚动到
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                ImageSlotGrid(
                    slots = slots,
                    images = images,
                    selectionMode = selection.active,
                    selected = selection.selected,
                    canAddNumbered = canAddNumbered,
                    onSlotClick = { slot ->
                        when {
                            selection.active -> selection = selection.toggle(slot.placeholder)
                            images[slot.placeholder] != null -> viewing = slot.placeholder
                            else -> launchPick(slot.placeholder)
                        }
                    },
                    onSlotLongClick = { slot ->
                        if (images[slot.placeholder] != null) selection = selection.enter(slot.placeholder)
                    },
                    onAddClick = onAddSlot
                )
            }
        }
    }

    viewing?.let { placeholder ->
        val bitmap = images[placeholder]
        if (bitmap != null) {
            ImageSlotViewer(
                bitmap = bitmap,
                slotLabel = labels[placeholder].orEmpty(),
                onReplace = {
                    viewing = null
                    launchPick(placeholder)
                },
                onDismiss = { viewing = null }
            )
        }
    }
}

/**
 * 选择模式的操作栏（设计 §3「顶部出现操作栏（删除所选 / 退出）」）。
 *
 * 关闭按钮沿用 `action_cancel`：项目里没有独立的 `action_close`（Task 7 已确认），
 * 新增字符串要同步 7 个语言目录，为一次交互不值当。
 */
@Composable
private fun SlotSelectionBar(
    canDelete: Boolean,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onDelete, enabled = canDelete) {
            Text(stringResource(R.string.action_delete_selected))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * 单图位时的整块格子：尺寸与图位网格里的格子不同（这里要占满整个弹性容器，不是 1:1），
 * 但标签、勾选框、点按行为保持一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SingleSlotBlock(
    bitmap: Bitmap?,
    label: String?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier
) {
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
            // 没选图时沿用接入前的占位：App 图标 + 提示文字
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_comfychair_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = stringResource(R.string.msg_no_source_image),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 图位名浮在左上角（与网格格子里那颗标签同款）
        Text(
            text = label.orEmpty(),
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

/**
 * 图位名（"首帧" / "尾帧" / "参考图 2" / "图片 1"），与 `ImageSlotGrid` 单元格上的标签同一口径：
 * 序号取"同类图位内部的显示顺序"，不用 `ImageSlot.Numbered.index`——参考图在节点侧是按顺序取值、
 * 忽略编号的，编号有空洞时用编号推会跳号、与模型实际看到的 `<Picture N>` 不一致。
 */
@Composable
private fun imageSlotLabels(slots: List<ImageSlot>): Map<String, String> {
    var genericSeen = 0
    var referenceSeen = 0
    val labels = mutableMapOf<String, String>()
    for (slot in slots) {
        labels[slot.placeholder] = when (slot) {
            is ImageSlot.Named -> when (slot.semantics) {
                ImageSlot.Semantics.FIRST_FRAME -> stringResource(R.string.image_slot_first_frame)
                ImageSlot.Semantics.LAST_FRAME -> stringResource(R.string.image_slot_last_frame)
                ImageSlot.Semantics.GENERIC -> stringResource(R.string.image_slot_generic, ++genericSeen)
            }

            is ImageSlot.Numbered -> stringResource(R.string.image_slot_reference, ++referenceSeen)
        }
    }
    return labels
}
