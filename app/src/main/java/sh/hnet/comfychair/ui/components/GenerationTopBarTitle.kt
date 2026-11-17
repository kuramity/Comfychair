package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * 顶栏标题字号档位（v4.16.6 Q6）：默认 titleLarge 22sp；可用宽度不足时先降到 20sp、
 * 再降到 titleMedium 16sp —— 优先保证完整显示，ellipsis 只作极端兜底。
 * internal 供单测钉住规格。
 */
internal const val TITLE_FONT_SP_LARGE = 22
internal const val TITLE_FONT_SP_MIDDLE = 20
internal const val TITLE_FONT_SP_SMALL = 16

/** 字号预估之外的安全边距（dp）：方块字宽 ≈ 字号，再留余量防字体渲染边缘溢出。 */
internal const val TITLE_WIDTH_SAFETY_DP = 8f

/**
 * 顶栏标题字号选择（纯函数，供 JVM 单测）。
 *
 * 汉字是方块字，宽度 ≈ 字号（sp 与 dp 在默认字体缩放下近似 1:1），因此按
 * `字符数 × 字号 + 安全边距 ≤ 可用宽度` 判定该档放不放得下：
 * - 放得下 22sp → 22sp（默认，与原 titleLarge 一致）；
 * - 否则放得下 20sp → 20sp；
 * - 都放不下 → 16sp（360dp 屏图生视频：标题槽只剩 84dp，22sp 需 96 / 20sp 需 88
 *   都不够，降到 16sp 需 72 —— 四个汉字完整显示、不出省略号）。
 *
 * @param availableWidthDp 标题槽可用宽度（[GenerationTopBarTitle] 的 BoxWithConstraints maxWidth）
 * @param textCharCount 标题字符数（按 char 计；英文单词混排时偏保守——提前降档也比截断好）
 */
internal fun resolveTopBarTitleFontSize(availableWidthDp: Float, textCharCount: Int): Int =
    when {
        availableWidthDp >= textCharCount * TITLE_FONT_SP_LARGE + TITLE_WIDTH_SAFETY_DP -> TITLE_FONT_SP_LARGE
        availableWidthDp >= textCharCount * TITLE_FONT_SP_MIDDLE + TITLE_WIDTH_SAFETY_DP -> TITLE_FONT_SP_MIDDLE
        else -> TITLE_FONT_SP_SMALL
    }

/**
 * 四个生成页顶栏共用的标题（v4.16.6 Q6）。
 *
 * 修复「图生视频」标题在 360dp 屏被挤成两行：文生视频 / 图生视频两页的标题 Text
 * 缺 `maxLines=1`（文生图 / 图生图已修），而 5 个顶栏控件（5×44 + 4×14 = 276dp）后
 * 360dp 屏标题槽只剩 84dp，titleLarge(22sp) 的四个汉字需 ≈88dp → 必换行。光加
 * maxLines 会变成省略号，不满足"完整显示"，所以在窄屏同时降字号：
 *
 * - 单行硬约束（maxLines=1）+ 尾部 ellipsis 兜底（分屏等 <300dp 极端场景）；
 * - 字号按标题槽实际可用宽度三档自适应（22 / 20 / 16sp，见 [resolveTopBarTitleFontSize]）。
 *
 * 可用宽度从 title 槽内的 [BoxWithConstraints] 拿：M3 TopAppBar 先测 actions、剩余宽给
 * title，约束穿透到 BoxWithConstraints —— 顶栏控件数变化（如 3D 流多一颗按钮）时
 * 下一帧自动重判，无需调用方传屏宽。
 *
 * @param text 标题文本（调用方各自传原有字符串键，四屏写法统一到本组件）
 */
@Composable
fun GenerationTopBarTitle(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val fontSize = resolveTopBarTitleFontSize(maxWidth.value, text.length)
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontSize = fontSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
