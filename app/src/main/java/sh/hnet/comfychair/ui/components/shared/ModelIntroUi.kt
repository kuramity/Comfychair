package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.model.ModelIntro

/**
 * 副标题内容：底模 · 类型（只拼有值的部分）。
 *
 * @param missingBadge 本地文件已移除时追加的标记文案（传空串表示不标）；
 *        标记走参数传而不是写死在这里，是因为本函数要保持纯函数、可被非 Composable 复用
 */
internal fun modelSubtitle(intro: ModelIntro?, missingBadge: String = ""): String? {
    if (intro == null) return null
    val parts = ArrayList<String>(3)
    intro.base?.let { parts.add(it) }
    intro.type?.let { parts.add(it) }
    if (parts.isEmpty()) {
        // 没有结构化字段时，退一步用作者推荐 tag 让用户知道这模型是干嘛的
        if (intro.tags.isNotEmpty()) return "推荐 tag：" + intro.tags.take(3).joinToString("、")
        // tag 也没有时，至少把「已移除」这个事实透出去（否则列表项看起来就是空白的）
        return if (intro.missing && missingBadge.isNotEmpty()) missingBadge else null
    }
    val text = parts.joinToString(" · ")
    return if (intro.missing && missingBadge.isNotEmpty()) "$text · $missingBadge" else text
}

/**
 * 元信息头**逐行**渲染（名字 / 底模 / 版本 / 类型 / 模型效果或 LoRA 效果）。
 *
 * 详情页（[sh.hnet.comfychair.ui.screens.ModelIntroDetailScreen]）用这一支。键值来源是
 * [sh.hnet.comfychair.model.ModelIntroPresentation.buildMetaHeader]——与导出的 `.md` 同源。
 *
 * 样式照抄详情页参数卡的两列：键 labelMedium + onSurfaceVariant（min 72dp 让四五种键名
 * 都对齐到同一条基线）、值 bodyMedium + onSurface（关键信息不用 3:1 的 onSurfaceVariant）。
 * 行距 8dp。
 *
 * @param header 该条目的元信息键值对，空列表时整块不渲染（父级的 spacedBy 不会留白）
 */
@Composable
internal fun MetaHeaderRows(header: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 72.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // U1-B：fill = false —— 元信息短值（「底模：Illustrious」这类）的布局框
                    // 只到文字末尾为止，点同一行右侧空白不再被 SelectionContainer 命中；
                    // 长值仍可占满剩余宽度换行
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}
