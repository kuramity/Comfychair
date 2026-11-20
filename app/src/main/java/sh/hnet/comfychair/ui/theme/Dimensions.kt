package sh.hnet.comfychair.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全应用集中式尺寸令牌。
 *
 * 为什么要有这一层：Compose 代码里散落的 `16.dp` / `RoundedCornerShape(12.dp)` 无法表达
 * "这里为什么是 16"，改版时也无从判断哪些值属于同一套规格。令牌把常用间距、圆角、描边
 * 收敛成唯一来源，新代码优先从这里取值；历史硬编码由后续批次逐步替换（本轮只覆盖
 * ConnectionSplitButton / GenerationButton / ServerConnectButton 三个文件，这三个文件里
 * 的尺寸字面量已全部换成本文件的令牌）。
 *
 * 命名约定：数字后缀就是实际 dp 值（[Spacing16] = 16.dp），避免 Small/Medium 这类
 * 相对命名在不同语境里含义漂移；语义化别名（如 [ButtonHeight]）只给跨页面复用、
 * 含义明确的组件规格使用。
 *
 * 关于"暂未被引用"的令牌：[Spacing4]…[Spacing24]、[Radius8]…[Radius16]、[Stroke1]、
 * [Stroke1_5]、[Radius18] 目前调用点为 0 —— 它们是本轮按任务书建立的**目标刻度**，
 * 供后续批次替换全项目其余硬编码时使用。数值由 `DimensionsTokenTest` 钉住，不是
 * 随手写的死值；任何一次误改都会让该测试失败。
 */
object Dimensions {
    /** Size for placeholder logo shown in empty preview areas */
    val PlaceholderLogoSize = 300.dp

    // ---------------- 间距（4dp 网格） ----------------

    /** 4dp：图标与文字、相邻小元素之间的最小间隙 */
    val Spacing4 = 4.dp

    /** 8dp：紧凑列表/按钮内部的默认间隙 */
    val Spacing8 = 8.dp

    /** 12dp：控件之间的常规间隙 */
    val Spacing12 = 12.dp

    /** 16dp：区块内边距、表单行间距 */
    val Spacing16 = 16.dp

    /** 24dp：页面级外边距、区块之间的分隔 */
    val Spacing24 = 24.dp

    /**
     * 32dp：底部弹窗内容的底部留白。
     *
     * 与 [Spacing24] 不构成 4dp 网格上的相邻档，而是弹窗场景专用值 —— 四个生成页的
     * 选项弹窗（ConfigBottomSheetContent）与元数据弹窗（MetadataBottomSheet）都用它，
     * 让"最后一行与安全区之间"留出一指宽的呼吸空间。
     */
    val Spacing32 = 32.dp

    // ---------------- 圆角 ----------------

    /** 8dp：输入框、小徽标（对齐 M3 shapes.extraSmall） */
    val Radius8 = 8.dp

    /** 12dp：中小型容器（对齐 M3 shapes.small） */
    val Radius12 = 12.dp

    /** 16dp：卡片、图片槽位（对齐 M3 shapes.medium） */
    val Radius16 = 16.dp

    /** 18dp：下拉（trailing）方形按钮的四角圆角，对齐拆分按钮的方形基准版 */
    val Radius18 = 18.dp

    /** 28dp：胶囊按钮/底部弹出面板（56dp 高按钮的一半，对齐 M3 shapes.extraLarge） */
    val Radius28 = 28.dp

    // ---------------- 描边 ----------------

    /** 1dp：常规分割线、卡片描边 */
    val Stroke1 = 1.dp

    /** 1.5dp：需要更清晰边界的高亮描边 */
    val Stroke1_5 = 1.5.dp

    // ---------------- 跨页面复用的组件规格 ----------------

    /** 主操作按钮（连接/生成）的统一高度：56dp，恰好是 [Radius28] 胶囊圆角的两倍 */
    val ButtonHeight = 56.dp

    /**
     * 下拉（trailing）按钮在 v2 皮肤下的宽度：54dp。
     *
     * 比主按钮 [ButtonHeight] 窄一档，是有意的视觉层级：主按钮占满剩余宽度、下拉退一档。
     * 回退态（非 v2 皮肤）用 56dp 方形，直接取 [ButtonHeight]。
     */
    val TrailingButtonWidth = 54.dp

    /**
     * 主↔下拉之间的间距：10dp。
     *
     * 偏离 4dp 网格，是 v2 皮肤"主/下拉/齿轮三层操作行等距"的规格值（基准实例量得），
     * 回退态仍走 M3 的 SplitButtonDefaults.Spacing（2dp）。
     */
    val SplitButtonGap = 10.dp

    /**
     * 标准图标尺寸：24dp。
     *
     * Material Icons 的默认绘制尺寸，也是本项目同排图标（箭头/齿轮/菜单）的统一规格。
     * 注意 M3 的 SplitButtonMediumTokens.TrailingIconSize 是 26dp，此处刻意不跟随，
     * 以保持与既有图标体系一致（见 GenerationButton 的注释）。
     */
    val IconSize24 = 24.dp

    /**
     * 主操作按钮文字尺寸：18sp。
     *
     * 18sp 不在 M3 默认字号阶梯上（titleMedium 16sp / titleLarge 22sp），但它是连接页
     * 与生成页按钮长期使用的规格。这里收成 token，调用方用
     * `MaterialTheme.typography.bodyLarge.copy(fontSize = Dimensions.ButtonTextSize)`
     * 派生——字体族/字重/行高仍由 Typography 统一管理，只有字号走这条显式例外。
     */
    val ButtonTextSize = 18.sp
}
