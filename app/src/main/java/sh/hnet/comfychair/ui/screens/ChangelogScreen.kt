package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.update.ChangelogEntry
import sh.hnet.comfychair.update.ChangelogParser
import sh.hnet.comfychair.ui.util.withStableKeys

/**
 * 更新日志页（设置页入口，离线只读）。
 *
 * 数据来自随包内置的 assets/CHANGELOG.md，不联网：更新弹窗只能显示「这一版」的说明，
 * 而给更新流程加的新 UI 在旧客户端上永远看不到（自举悖论），这里补一个能看全部
 * 历史版本的入口。读取放在 IO 协程，读不到 / 解析为空时显示友好空态，不崩溃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // null = 还没读完或读取失败；读到文本后用解析器切条目
    var markdown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        markdown = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
    val entries = remember(markdown) {
        markdown?.let { ChangelogParser.parse(it) } ?: emptyList()
    }
    // 每张卡片以版本号为业务标识（版本号天然唯一，重复版本按出现顺序消歧）；
    // 不用下标当 key：更新日志是倒序追加的，新增版本会让所有下标整体下移，
    // 行状态会从一个版本串到另一个版本上。
    val keyedEntries = remember(entries) { entries.withStableKeys { it.version } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.button_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.changelog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(keyedEntries, key = { it.key }) { item ->
                ChangelogCard(
                    entry = item.value,
                    isCurrent = item.value.version == BuildConfig.VERSION_NAME
                )
            }
        }
    }
}

/** 一个版本一张卡片；当前安装版本加「当前版本」徽标。 */
@Composable
private fun ChangelogCard(entry: ChangelogEntry, isCurrent: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = listOf(entry.date, entry.versionCode?.let { "code$it" })
                        .filterNotNull()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 当前安装版本的徽标（primaryContainer 底色）
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.changelog_badge_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (entry.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                entry.items.forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 内置日志文件名（assets 根目录）。 */
private const val CHANGELOG_ASSET = "CHANGELOG.md"
