package sh.hnet.comfychair.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.ui.components.shared.FreeMemoryButton
import sh.hnet.comfychair.ui.components.shared.ThreeDResultButton

/**
 * 四个生成页共用的顶栏图标组（v4.9.0）。
 *
 * ## 为什么要抽出来
 *
 * 原先四个页面各写一串裸的 `IconButton` + `if`，于是排列顺序和出现时机全不一致：
 * 文生图是「保存→分享→显存→菜单」、图生图是「3D→显存→加图→蒙版→菜单」、
 * 文生视频是「显存→保存→分享→菜单」、图生视频又多一个加图 —— 同一个功能在不同
 * 模式里位置不同，用户根本形不成肌肉记忆。图生图还漏了保存/分享（出了图导不出去）。
 *
 * ## 四段式（顺序固定，不适用的传 null 即整段跳过）
 *
 * ```
 * [3D 查看器] → [加图] [蒙版] [清除] → [保存] [分享] → [释放显存] [菜单]
 *   模式入口          输入类                输出类           全局（恒在，永远最后两位）
 * ```
 *
 * 三条规则：
 * 1. **模式级入口在最左**：3D 查看器属于"这个流能不能看 3D"，不是对当前结果的操作；
 * 2. **输入在前、输出在后**：贴合同一条工作流的自然顺序（先给素材，再导出结果）；
 * 3. **全局动作永远最后两位**：释放显存和菜单在任何模式里都出现，位置必须稳定 ——
 *    它们是最容易被肌肉记忆点到的两个。
 *
 * ## 每个参数何时传 null（页面侧决定）
 *
 * | 参数 | 传 null 的条件 |
 * |---|---|
 * | [onUploadImage] | 文生文/文生视频（没有输入图概念）；图生视频的时间线流 |
 * | [onEditMask] / [onClearMask] | 非图生图修复模式，或还没有源图 |
 * | [onSave] / [onShare] | 还没有可导出的结果（图片无预览 / 视频无产物） |
 *
 * @param is3DWorkflow 当前工作流是否 3D 流；组件内部还有 `ThreeDVisibility` 的门槛
 * @param threeDResult 当前工作流已产出的 3D 结果（按归属过滤后的，不是全局值）
 */
@Composable
fun GenerationTopBarActions(
    is3DWorkflow: Boolean,
    threeDResult: ThreeDResultHolder.Result?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onUploadImage: (() -> Unit)? = null,
    onEditMask: (() -> Unit)? = null,
    onClearMask: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ---- 第一段：模式级入口 ----
    ThreeDResultButton(
        is3DWorkflow = is3DWorkflow,
        result = threeDResult,
        modifier = modifier
    )

    // ---- 第二段：输入类（加图 → 蒙版编辑 → 清除蒙版）----
    onUploadImage?.let { action ->
        IconButton(onClick = action) {
            Icon(
                Icons.Default.AddPhotoAlternate,
                contentDescription = stringResource(R.string.button_upload_source_image)
            )
        }
    }
    onEditMask?.let { action ->
        IconButton(onClick = action) {
            Icon(
                Icons.Default.Brush,
                contentDescription = stringResource(R.string.button_edit_mask)
            )
        }
    }
    onClearMask?.let { action ->
        IconButton(onClick = action) {
            Icon(
                Icons.Default.Clear,
                contentDescription = stringResource(R.string.button_clear_mask)
            )
        }
    }

    // ---- 第三段：输出类（保存 → 分享）----
    onSave?.let { action ->
        IconButton(onClick = action) {
            Icon(
                Icons.Default.Save,
                contentDescription = stringResource(R.string.button_save_to_gallery)
            )
        }
    }
    onShare?.let { action ->
        IconButton(onClick = action) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.button_share)
            )
        }
    }

    // ---- 第四段：全局（恒在）----
    FreeMemoryButton()
    AppMenuDropdown(onSettings = onSettings, onLogout = onLogout)
}
