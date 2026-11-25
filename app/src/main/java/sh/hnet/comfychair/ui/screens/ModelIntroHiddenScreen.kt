package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.viewmodel.ModelIntroViewModel

/**
 * 「已卸载的介绍」列表页（二级页面）。
 *
 * 卸载只是**本地隐藏**（覆盖层里一条 `hidden: true`，见 [sh.hnet.comfychair.model.ModelIntroOverrides]），
 * 内置介绍一个字节都没删——所以这里逐条「恢复」就是把隐藏标记去掉，条目立刻回到浏览页。
 *
 * 每条显示展示名（条目名，取不到时退回归一化文件名）与文件名本身；
 * 列表为空时给出明确的空态，而不是一片空白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroHiddenScreen(
    onBack: () -> Unit,
    viewModel: ModelIntroViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 与浏览页同一装载纪律：读盘在 IO 线程（ViewModel 内部调度）
    LaunchedEffect(Unit) { viewModel.load(context) }
    ModelIntroToastHost(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_intro_hidden_title)) },
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
        if (state.hidden.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.model_intro_hidden_empty),
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
            items(state.hidden, key = { it.file }) { item ->
                HiddenIntroRow(
                    item = item,
                    onRestore = { viewModel.restore(context, item.file) }
                )
            }
        }
    }
}

/** 一条已卸载记录：展示名 + 文件名 + 「恢复」。 */
@Composable
private fun HiddenIntroRow(item: ModelIntroViewModel.HiddenItem, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimensions.Spacing16, end = Dimensions.Spacing16, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 展示名与键不同（条目名 vs 文件名）时才补一行文件名，重复信息不占行
            if (item.displayName != item.file) {
                Text(
                    text = item.file,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(Dimensions.Spacing8))
        TextButton(onClick = onRestore) {
            Text(stringResource(R.string.model_intro_restore))
        }
    }
}