package sh.hnet.comfychair.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一圆角体系。
 *
 * 接入方式：Theme.kt 里 `MaterialTheme(shapes = Shapes)`，
 * 之后 [MaterialTheme.shapes] 的所有取值都来自这里。
 *
 * 各级别对应的实际控件（M3 组件的默认形状就是按这些色槽取的）：
 * - extraSmall：输入框（OutlinedTextField / TextField）、下拉菜单
 * - small     ：中小型容器、部分自定义面板
 * - medium    ：卡片（Card）、图片槽位、图库网格格子
 * - large     ：自定义对话框、侧边面板
 * - extraLarge：AlertDialog、底部弹出面板（ModalBottomSheet）
 *
 * 设计原则：五级半径保持等差递增（8 / 12 / 16 / 20 / 28），
 * 让"越大的容器圆角越大"这件看起来自然是同一套语言，避免各页面各圆各的。
 * 相比 M3 默认的 4 / 8 / 12 / 16 / 28，只把前三级略微上调，
 * 属于细节统一，不改变任何控件的尺寸与位置。
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
