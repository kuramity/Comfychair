package sh.hnet.comfychair.ui.theme

import androidx.compose.ui.graphics.Color

// ComfyChair 配色体系：深空蓝灰 + 青蓝强调色
//
// 设计约定：
// 1) 界面上所有颜色都必须走 MaterialTheme.colorScheme.*（不要直接引用本文件里的色值），
//    这样深浅两套主题只需在 Theme.kt 里切换色槽映射即可全局生效。
// 2) 深色与浅色是"同一色相、不同明度"的两套方案（M3 标准做法）：
//    - 深色用高明度色（在深底上可读）
//    - 浅色用低明度色（在白底上可读）
//    因此同一个语义色槽在深浅两套里的色值不同，这是刻意的，不是笔误。
// 3) 色槽语义沿用项目既有约定（见下），不要凭 M3 教科书改回去：
//    - primary          ：主操作（生成按钮、选中边框、链接文字）
//    - secondary        ：进行中 / 警告（连接中按钮、警告文字）
//    - secondaryContainer：选中态底色（底部导航选中项、选中卡片、选中芯片）
//    - tertiary         ：成功（已连接按钮、成功图标）
//    - error            ：取消 / 危险 / 失败

// ============================================================
// 深色：深空蓝灰 + 青蓝
// ============================================================

object DarkPalette {

    // ---------- 中性底色（蓝灰阶梯，自暗到亮，用于构建层次）----------
    /** 最底层（比背景更暗，用于需要"凹陷"的区域） */
    val SurfaceLowest = Color(0xFF0B0F14)
    /** 背景（页面底色） */
    val Background = Color(0xFF0F1419)
    /** 表面（与背景同色，保持整体干净） */
    val Surface = Color(0xFF0F1419)
    /** 低层容器（对话框、侧边面板底色） */
    val SurfaceContainerLow = Color(0xFF151B22)
    /** 普通容器（图片预览区、工作流节点底色） */
    val SurfaceContainer = Color(0xFF1A2028)
    /** 高层容器（顶栏、工具栏、右侧面板、节点标题条） */
    val SurfaceContainerHigh = Color(0xFF222A34)
    /** 卡片底色。
     *  M3 的 Card 默认容器色取的是 surfaceContainerHighest，所以"卡片色"必须落在这一级；
     *  标签库的徽章底色也用它，因此这里刻意与 SurfaceContainerHigh 拉开差值，
     *  保证"白底面板上的徽章"仍然看得见。 */
    val SurfaceContainerHighest = Color(0xFF1A2028)
    /** 输入槽 / 占位区 / 滑轨底色（比卡片更暗，做出"凹陷"感） */
    val SurfaceVariant = Color(0xFF141A21)

    /** 最亮的表面（用于需要最亮底色的场景） */
    val SurfaceBright = Color(0xFF2A323D)
    /** 最暗的表面 */
    val SurfaceDim = Color(0xFF0F1419)

    /** 描边（需要比分割线更明显，用于卡片/节点边框） */
    val Outline = Color(0xFF3A4553)
    /** 分割线 */
    val OutlineVariant = Color(0xFF2A323D)

    /** 正文文字 */
    val OnSurface = Color(0xFFE6EDF3)
    /** 次要文字（说明、副标题、未选中图标） */
    val OnSurfaceVariant = Color(0xFF8B98A5)

    // ---------- 强调色：青蓝 ----------
    /** 强调色：按钮 / 选中 */
    val Primary = Color(0xFF38BDF8)
    /** 强调色之上的文字（青蓝偏亮，用深色字） */
    val OnPrimary = Color(0xFF06171F)
    /** 强调色容器（浅青蓝底，用于选中标签等） */
    val PrimaryContainer = Color(0xFF0B3A4F)
    /** 强调色容器之上的文字 */
    val OnPrimaryContainer = Color(0xFFA5E3FB)
    /** 反向主色（用于 Snackbar 等反色底） */
    val InversePrimary = Color(0xFF0369A1)

    // ---------- 警告色：琥珀 ----------
    /** 警告 / 进行中 */
    val Secondary = Color(0xFFF59E0B)
    /** 警告色之上的文字（琥珀偏亮，用深色字） */
    val OnSecondary = Color(0xFF251A02)
    /** 选中态容器：这里刻意用青蓝浅底，让"选中"跟强调色一致 */
    val SecondaryContainer = Color(0xFF123A50)
    /** 选中态容器之上的文字 */
    val OnSecondaryContainer = Color(0xFFA5E3FB)

    // ---------- 成功色：薄荷绿 ----------
    /** 成功 */
    val Tertiary = Color(0xFF34D399)
    /** 成功色之上的文字 */
    val OnTertiary = Color(0xFF04281B)
    /** 成功容器 */
    val TertiaryContainer = Color(0xFF0D3B2C)
    /** 成功容器之上的文字 */
    val OnTertiaryContainer = Color(0xFF86EFAC)

    // ---------- 错误色：柔红 ----------
    /** 错误 / 危险 */
    val Error = Color(0xFFF87171)
    /** 错误色之上的文字 */
    val OnError = Color(0xFF3B0A0D)
    /** 错误容器 */
    val ErrorContainer = Color(0xFF4E1519)
    /** 错误容器之上的文字 */
    val OnErrorContainer = Color(0xFFFFB4AB)

    // ---------- 反色（Snackbar / 反色提示）----------
    val InverseSurface = Color(0xFFE6EDF3)
    val InverseOnSurface = Color(0xFF1A2028)

    /** 模态遮罩 */
    val Scrim = Color(0xFF000000)

    // ---------- 固定色（深浅模式下取值相同，供需要恒定色相的组件使用）----------
    val PrimaryFixed = Color(0xFFBAE6FD)
    val PrimaryFixedDim = Color(0xFF7DD3FC)
    val OnPrimaryFixed = Color(0xFF0B3A52)
    val OnPrimaryFixedVariant = Color(0xFF0369A1)
    val SecondaryFixed = Color(0xFFFDE68A)
    val SecondaryFixedDim = Color(0xFFFCD34D)
    val OnSecondaryFixed = Color(0xFF3A2A05)
    val OnSecondaryFixedVariant = Color(0xFF92400E)
    val TertiaryFixed = Color(0xFFA7F3D0)
    val TertiaryFixedDim = Color(0xFF6EE7B7)
    val OnTertiaryFixed = Color(0xFF064E3B)
    val OnTertiaryFixedVariant = Color(0xFF047857)
}

// ============================================================
// 浅色：冷调灰白 + 深青蓝（与深色同色相，明度反转）
// ============================================================

object LightPalette {

    // ---------- 中性底色（冷调灰白阶梯，自亮到暗）----------
    val SurfaceLowest = Color(0xFFFFFFFF)
    /** 背景（页面底色，浅冷灰）。
     *  比卡片明显灰一档：因为本项目零阴影，层次只能靠底色差表达，
     *  底色差太小的话白卡片会"糊"在背景里。 */
    val Background = Color(0xFFEDF1F7)
    val Surface = Color(0xFFEDF1F7)
    /** 低层容器（对话框、侧边面板、缩略图底） */
    val SurfaceContainerLow = Color(0xFFF7FAFC)
    /** 普通容器（图片预览区、工作流节点底色） */
    val SurfaceContainer = Color(0xFFFFFFFF)
    /** 高层容器（顶栏、工具栏、右侧面板、节点标题条：比卡片略深，读起来像"条"） */
    val SurfaceContainerHigh = Color(0xFFE6EBF2)
    /** 卡片底色。
     *  与深色模式同理，M3 的 Card 取的是 surfaceContainerHighest，
     *  所以卡片色必须落在这一级；浅色下用纯白，靠"白卡片 + 浅灰底"分层。 */
    val SurfaceContainerHighest = Color(0xFFFFFFFF)
    /** 输入槽 / 占位区 / 滑轨底色（比白卡片深，做出"凹陷"感） */
    val SurfaceVariant = Color(0xFFDFE6EE)

    /** 最亮的表面 */
    val SurfaceBright = Color(0xFFFFFFFF)
    /** 最暗的表面 */
    val SurfaceDim = Color(0xFFDCE3EA)

    /** 描边 */
    val Outline = Color(0xFFA9B6C4)
    /** 分割线 */
    val OutlineVariant = Color(0xFFCDD6E0)

    /** 正文文字 */
    val OnSurface = Color(0xFF16202B)
    /** 次要文字 */
    val OnSurfaceVariant = Color(0xFF52606E)

    // ---------- 强调色：深青蓝（比深色模式压暗，保证白底可读）----------
    /** 强调色：按钮 / 选中（#38BDF8 在白底对比度仅约 2:1，作为文字不可读，
     *  因此浅色模式压暗到同色相的 #0369A1，白底对比度约 5.9:1） */
    val Primary = Color(0xFF0369A1)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFCDE9FB)
    val OnPrimaryContainer = Color(0xFF0B3A52)
    /** 反向主色：这里放回亮青蓝，让反色底上保留强调色的原貌 */
    val InversePrimary = Color(0xFF38BDF8)

    // ---------- 警告色：深琥珀 ----------
    val Secondary = Color(0xFFB45309)
    val OnSecondary = Color(0xFFFFFFFF)
    /** 选中态容器：与深色模式一致，选中态统一走青蓝 */
    val SecondaryContainer = Color(0xFFCFE6F5)
    val OnSecondaryContainer = Color(0xFF0B3A52)

    // ---------- 成功色：深薄荷绿 ----------
    val Tertiary = Color(0xFF047857)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFD1FAE5)
    val OnTertiaryContainer = Color(0xFF064E3B)

    // ---------- 错误色 ----------
    val Error = Color(0xFFBA1A1A)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF93000A)

    // ---------- 反色 ----------
    val InverseSurface = Color(0xFF2A323D)
    val InverseOnSurface = Color(0xFFE6EDF3)

    val Scrim = Color(0xFF000000)

    // ---------- 固定色（与深色模式取相同值，符合 M3 语义）----------
    val PrimaryFixed = DarkPalette.PrimaryFixed
    val PrimaryFixedDim = DarkPalette.PrimaryFixedDim
    val OnPrimaryFixed = DarkPalette.OnPrimaryFixed
    val OnPrimaryFixedVariant = DarkPalette.OnPrimaryFixedVariant
    val SecondaryFixed = DarkPalette.SecondaryFixed
    val SecondaryFixedDim = DarkPalette.SecondaryFixedDim
    val OnSecondaryFixed = DarkPalette.OnSecondaryFixed
    val OnSecondaryFixedVariant = DarkPalette.OnSecondaryFixedVariant
    val TertiaryFixed = DarkPalette.TertiaryFixed
    val TertiaryFixedDim = DarkPalette.TertiaryFixedDim
    val OnTertiaryFixed = DarkPalette.OnTertiaryFixed
    val OnTertiaryFixedVariant = DarkPalette.OnTertiaryFixedVariant
}

// 纯黑 / 纯白：保留给图片查看器等需要"绝对黑底"的覆盖层使用（不随主题变化）
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
