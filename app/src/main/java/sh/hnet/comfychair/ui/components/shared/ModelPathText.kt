package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Displays a model path with the directory portion dimmed.
 * Handles both forward slashes (Unix) and backslashes (Windows).
 *
 * @param maxLines 允许的最大行数。默认 1 行（配合省略号）；模型下拉里传 2，
 *   让长文件名（LoRA 的「触发词：（xxx）中文名」这类）能完整显示而不是被切掉。
 */
@Composable
fun ModelPathText(path: String, maxLines: Int = 1) {
    // Find the last separator (either / or \)
    val lastSlashIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if (lastSlashIndex > 0) {
        val directoryPart = path.substring(0, lastSlashIndex + 1)
        val filenamePart = path.substring(lastSlashIndex + 1)

        val dimmedColor = LocalContentColor.current.copy(alpha = 0.5f)

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = dimmedColor)) {
                    append(directoryPart)
                }
                append(filenamePart)
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(path, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
