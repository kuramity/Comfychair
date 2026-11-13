package sh.hnet.comfychair.taglibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.taglibrary.data.CooccurTable
import sh.hnet.comfychair.taglibrary.data.TagRepository
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory
import sh.hnet.comfychair.taglibrary.search.AliasParser

/**
 * 标签详情页（需求 4.5，二级页面）。
 *
 * 展示：标签名 / 分类 / 热度 / 中文释义 / 完整三语别名（全部列出，不省略）。
 * 底部提供「+正向」「+负向」按钮，行为与列表一致（写入提示词并记录到构建器）。
 *
 * 不包含（需求 4.5.4 明确排除）：共现推荐、收藏、别名编辑。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagDetailScreen(
    tagName: String,
    onBack: () -> Unit,
    onAddPositive: (String) -> Unit,
    onAddNegative: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 按标签名加载完整详情（IO 线程，失败时为 null）
    val tag by produceState<Tag?>(initialValue = null, tagName) {
        value = withContext(Dispatchers.IO) {
            TagRepository.getInstance()
                .ensureReady(context)
                .getOrNull()
                ?.loadByNames(listOf(tagName))
                ?.firstOrNull()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tagName,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tag_detail_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onAddPositive(tagName) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.tag_add_positive))
                    }
                    Button(
                        onClick = { onAddNegative(tagName) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.tag_add_negative))
                    }
                }
            }
        }
    ) { padding ->
        val current = tag
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            return@Scaffold
        }

        val (zhAliases, jaAliases, enAliases) = AliasParser.splitByLanguage(current.aliases)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 标题 + 分类 + 热度
            Text(
                text = current.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryChip(current.category)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.tag_detail_count, formatCount(current.count)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 中文释义
            if (current.zhName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.tag_detail_meaning, current.zhName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 三语别名分组（全部列出）
            AliasGroup(stringResource(R.string.tag_alias_zh), zhAliases)
            AliasGroup(stringResource(R.string.tag_alias_ja), jaAliases)
            AliasGroup(stringResource(R.string.tag_alias_en), enAliases)

            // 常见搭配（共现库提取）：点一下直接加进正向提示词
            val partners = remember(tagName) { CooccurTable.partners(tagName) }
            if (partners.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.tag_partners_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for ((name, count) in partners) {
                        AssistChip(
                            onClick = { onAddPositive(name) },
                            label = {
                                Text(
                                    text = "$name · ${formatCount(count)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 分类徽章（详情页版本，字号略大）。 */
@Composable
private fun CategoryChip(category: TagCategory) {
    val container = when (category) {
        TagCategory.GENERAL -> MaterialTheme.colorScheme.surfaceContainerHighest
        TagCategory.ARTIST -> MaterialTheme.colorScheme.tertiaryContainer
        TagCategory.COPYRIGHT -> MaterialTheme.colorScheme.secondaryContainer
        TagCategory.CHARACTER -> MaterialTheme.colorScheme.primaryContainer
        TagCategory.META -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (category) {
        TagCategory.GENERAL -> MaterialTheme.colorScheme.onSurfaceVariant
        TagCategory.ARTIST -> MaterialTheme.colorScheme.onTertiaryContainer
        TagCategory.COPYRIGHT -> MaterialTheme.colorScheme.onSecondaryContainer
        TagCategory.CHARACTER -> MaterialTheme.colorScheme.onPrimaryContainer
        TagCategory.META -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text = stringResource(category.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 单组别名（标题 + 流式 chip 列表）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AliasGroup(title: String, aliases: List<String>) {
    if (aliases.isEmpty()) return
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.tag_alias_group_title, title, aliases.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (alias in aliases) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = alias,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
