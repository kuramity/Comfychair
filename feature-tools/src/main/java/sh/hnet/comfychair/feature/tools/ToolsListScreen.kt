package sh.hnet.comfychair.feature.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 紧凑屏判定阈值：可用宽度小于 360dp 视为紧凑屏。 */
private val CompactScreenWidth = 360.dp

/**
 * 小工具列表页：标签库 + 提示词增强两张入口卡片。
 *
 * 两张卡共用 [ToolEntryCard]，保证完全同构（圆角/高度/内边距/图标底座/字号一致）。
 * 卡片高度固定，Compact 屏（可用宽度 < [CompactScreenWidth]）整体降一档：
 * 88→80dp、内边距 16→12dp、图标底座 44→40dp、图标 24→22dp、说明 2→1 行。
 *
 * @param onOpenTagLibrary 点「标签库」卡片时向外抛的导航回调
 * @param onOpenPromptEnhance 点「提示词增强」卡片时向外抛的导航回调（由 app 的 MainNavHost 接到
 *        [ToolsRoutes.PROMPT_ENHANCE]，或模块内 [ToolsNavGraph] 自行导航）
 */
@Composable
fun ToolsListScreen(
    onOpenTagLibrary: () -> Unit,
    onOpenPromptEnhance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < CompactScreenWidth
        val horizontalPadding = if (compact) 12.dp else 16.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = ToolsRoutes.TAG_LIBRARY) {
                ToolEntryCard(
                    // 与另一张卡的 Outlined.AutoFixHigh 同属描边风格，视觉统一
                    icon = Icons.Outlined.Sell,
                    title = stringResource(R.string.tools_tag_library_title),
                    description = stringResource(R.string.tools_tag_library_desc),
                    compact = compact,
                    onClick = onOpenTagLibrary,
                )
            }
            item(key = ToolsRoutes.PROMPT_ENHANCE) {
                ToolEntryCard(
                    icon = Icons.Outlined.AutoFixHigh,
                    title = stringResource(R.string.tools_prompt_enhance_title),
                    description = stringResource(R.string.tools_prompt_enhance_desc),
                    compact = compact,
                    onClick = onOpenPromptEnhance,
                )
            }
        }
    }
}

/**
 * 工具入口卡片（两张卡共用的唯一实现，改样式只改这里）。
 *
 * 内边距固定 12dp（不是 16dp）：88dp 固定高减上下 2×12dp 只剩 64dp，
 * 标题行高 24 + 间距 4 + 说明两行 32 = 60dp 才装得下；16dp 会溢出。
 *
 * @param compact 紧凑屏：高度 80dp、图标底座 40dp、图标 22dp、说明最多 1 行
 */
@Composable
private fun ToolEntryCard(
    icon: ImageVector,
    title: String,
    description: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardHeight: Dp = if (compact) 80.dp else 88.dp
    val iconBoxSize: Dp = if (compact) 40.dp else 44.dp
    val iconSize: Dp = if (compact) 22.dp else 24.dp
    val descMaxLines = if (compact) 1 else 2

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = descMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}