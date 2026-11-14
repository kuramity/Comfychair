package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.ui.components.ripple.RippleIconButton
import sh.hnet.comfychair.ui.components.shared.FreeMemoryButton
import sh.hnet.comfychair.ui.components.shared.ThreeDResultButton

/** 顶栏图标统一尺寸（v2 裸图标规格，44dp 触控区里的图标约 22dp）。 */
private val TOP_BAR_ICON_SIZE = 22.dp

/** 保存/分享禁用态的图标不透明度（规格建议值 0.38，与 M3 IconButton 禁用内容 alpha 同值）。 */
private const val DISABLED_ICON_ALPHA = 0.38f

/**
 * 保存/分享的可用性判定（纯函数，便于单测钉住 4 种组合）。
 *
 * v5 起保存/分享按钮**常驻**（修复"只在出图后出现、再生成一次还会闪没"的 bug），
 * 只切 enabled 不改显隐：
 * - [hasResult]：当前模式有没有可导出的结果（图=预览位非空 / 视频=产物 URI 非空）；
 * - [isEditing]：视频模式处于编辑态（图生视频非预览视图）时暂不可导出——
 *   原逻辑是"隐藏按钮"，现在降级为 enabled=false，按钮位置稳定。
 */
internal fun saveShareEnabled(hasResult: Boolean, isEditing: Boolean): Boolean =
    hasResult && !isEditing

/**
 * 四个生成页共用的顶栏图标组（v4.9.0 四段式，v5 起保存/分享常驻）。
 *
 * ## 为什么要抽出来
 *
 * 原先四个页面各写一串裸的 `IconButton` + `if`，于是排列顺序和出现时机全不一致：
 * 文生图是「保存→分享→显存→菜单」、图生图是「3D→显存→加图→蒙版→菜单」、
 * 文生视频是「显存→保存→分享→菜单」、图生视频又多一个加图 —— 同一个功能在不同
 * 模式里位置不同，用户根本形不成肌肉记忆。图生图还漏了保存/分享（出了图导不出去）。
 *
 * ## 四段式（顺序固定，不适用的输入类传 null 即整段跳过）
 *
 * ```
 * [3D 查看器] → [加图] [蒙版] [清除] → [保存] [分享] → [释放显存] [菜单]
 *   模式入口          输入类                输出类（常驻）      全局（恒在，永远最后两位）
 * ```
 *
 * 三条规则：
 * 1. **模式级入口在最左**：3D 查看器属于"这个流能不能看 3D"，不是对当前结果的操作；
 * 2. **输入在前、输出在后**：贴合同一条工作流的自然顺序（先给素材，再导出结果）；
 * 3. **全局动作永远最后两位**：释放显存和菜单在任何模式里都出现，位置必须稳定 ——
 *    它们是最容易被肌肉记忆点到的两个。
 *
 * ## v5 变化：保存/分享常驻（修复 bug）
 *
 * 原先 [onSave]/[onShare] 是可空参数、页面只在"有可导出结果"时才传 → 按钮随结果
 * 出现/消失（再点一次生成还会闪没）。现在改为**恒定渲染**：页面恒定传回调 +
 * [hasResult]/[isEditing] 两个状态，按钮可用性由 [saveShareEnabled] 判定——
 * 无结果时 enabled=false、图标降透明度（0.38），有结果时恢复；回调逻辑本身不变。
 *
 * ## 输入类参数何时传 null（页面侧决定）
 *
 * | 参数 | 传 null 的条件 |
 * |---|---|
 * | [onUploadImage] | 文生视频（没有输入图概念）；图生视频的时间线流 |
 * | [onEditMask] / [onClearMask] | 非图生图修复模式，或还没有源图 |
 *
 * @param hasResult 当前模式是否有可导出结果（图片=预览位非空 / 视频=产物 URI 非空）
 * @param isEditing 视频模式编辑态（图生视频非预览视图）——保存/分享临时禁用而非隐藏
 * @param is3DWorkflow 当前工作流是否 3D 流；组件内部还有 `ThreeDVisibility` 的门槛
 * @param threeDResult 当前工作流已产出的 3D 结果（按归属过滤后的，不是全局值）
 */
@Composable
fun GenerationTopBarActions(
    is3DWorkflow: Boolean,
    threeDResult: ThreeDResultHolder.Result?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    hasResult: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    isEditing: Boolean = false,
    onUploadImage: (() -> Unit)? = null,
    onEditMask: (() -> Unit)? = null,
    onClearMask: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // C8 修正：modifier 挂到容器 Row 上（原先挂给了第一个子组件 ThreeDResultButton）；
    // 图标间距 14dp（原先贴排 0 间距，模板规格）。
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 第一段：模式级入口（接入与顶栏一致的波纹皮肤，C7）----
        ThreeDResultButton(
            is3DWorkflow = is3DWorkflow,
            result = threeDResult,
        )

        // ---- 第二段：输入类（加图 → 蒙版编辑 → 清除蒙版）----
        // onClick / enabled / contentDescription 与原裸 IconButton 一一对应，
        // 总开关（AppSettings.isRippleDotFeedbackEnabled）在 RippleIconButton 内部读取。
        onUploadImage?.let { action ->
            RippleIconButton(onClick = action) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.button_upload_source_image),
                    modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                )
            }
        }
        onEditMask?.let { action ->
            RippleIconButton(onClick = action) {
                Icon(
                    Icons.Default.Brush,
                    contentDescription = stringResource(R.string.button_edit_mask),
                    modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                )
            }
        }
        onClearMask?.let { action ->
            RippleIconButton(onClick = action) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = stringResource(R.string.button_clear_mask),
                    modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                )
            }
        }

        // ---- 第三段：输出类（保存 → 分享，常驻）----
        // v5：按钮恒定渲染，无结果/编辑态时只降 enabled + 图标 38% 不透明度；
        // enabled 恒为 true 时 Icon 的 tint 走 Color.Unspecified（沿用顶栏 LocalContentColor，
        // 与其他图标同色），禁用时显式给 onSurfaceVariant@0.38（规格建议值）。
        val canExport = saveShareEnabled(hasResult, isEditing)
        val saveShareIconTint = if (canExport) {
            Color.Unspecified
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ICON_ALPHA)
        }
        RippleIconButton(onClick = onSave, enabled = canExport) {
            Icon(
                Icons.Default.Save,
                contentDescription = stringResource(R.string.button_save_to_gallery),
                modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                tint = saveShareIconTint,
            )
        }
        RippleIconButton(onClick = onShare, enabled = canExport) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.button_share),
                modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                tint = saveShareIconTint,
            )
        }

        // ---- 第四段：全局（恒在）----
        // 波纹点阵反馈（v4.9.x）：「释放显存」与「菜单」经 shared/FreeMemoryButton.kt 与
        // AppMenuDropdown 的 useRippleFeedback 参数接入；全部受总开关控制。
        FreeMemoryButton()
        // 波纹反馈显式传 true：只有生成页顶栏开，相册页等其他调用点走默认 false 保持原样
        AppMenuDropdown(
            onSettings = onSettings,
            onLogout = onLogout,
            useRippleFeedback = true,
            iconSize = TOP_BAR_ICON_SIZE,
        )
    }
}
