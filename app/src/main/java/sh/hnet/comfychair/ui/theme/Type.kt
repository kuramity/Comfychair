package sh.hnet.comfychair.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 字体：统一使用系统默认字体（FontFamily.Default），不引入任何字体资源文件。
//
// 这里只覆盖需要微调的几个层级，未列出的层级自动沿用 M3 默认值
// —— M3 的默认值与项目此前手抄的那份数值完全一致（字号/行高/字距都相同），
// 所以视觉上除了下面三处字重之外，其余层级没有任何变化。
//
// 调整原则：只动字重，不动字号、行高与 letterSpacing。
// 中文对字距很敏感，改 letterSpacing 会让汉字排版变松散，因此一律保持原值。
val Typography = Typography(
    // 对话框标题：Normal → SemiBold，标题更立得住
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    // 顶栏标题：Normal → SemiBold，标题层级更清晰
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // 按钮文字：Medium → SemiBold，文字更实、更容易读
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)
