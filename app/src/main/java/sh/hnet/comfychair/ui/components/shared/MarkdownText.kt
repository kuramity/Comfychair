package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.workflow.MarkdownParser

/**
 * 用项目既有的 [MarkdownParser]（commonmark）渲染一段 Markdown 的普通文本 Composable。
 *
 * 背景：MarkdownParser 原先只服务工作流便签的 Canvas 绘制（返回 [androidx.compose.ui.text.AnnotatedString]，
 * 由 TextMeasurer 测量后手工绘制）。模型介绍的元信息头/效果要「界面渲染与导出共用同一份
 * 模板」（见 [sh.hnet.comfychair.model.ModelIntroMarkdown]），这里把「解析 + Text」收成一处，
 * 避免每个调用点各自 measure。
 *
 * @param markdown 要渲染的 Markdown 原文
 * @param style 基础文本样式（字号/行高/字重）。标题会在此基础上按 level 放大
 *        （MarkdownParser 的既有规则：H1 150% / H2 130% / H3 115%）
 * @param color 文本颜色；默认 `Color.Unspecified` 表示跟随 `LocalContentColor`
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    // MarkdownParser 用 baseFontSize 做比例缩放；fontSize 为 Unspecified 时乘法会抛异常，
    // 所以这里必须落到一个具体的 sp（Material3 typography 都有字号，兜底 14sp 防自定义样式）
    val baseFontSize = style.fontSize.takeIf { it.isSp } ?: 14.sp
    val annotated = remember(markdown, codeBackgroundColor, baseFontSize) {
        MarkdownParser.parse(
            markdown = markdown,
            codeBackgroundColor = codeBackgroundColor,
            baseFontSize = baseFontSize,
        )
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
    )
}