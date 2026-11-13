package sh.hnet.comfychair.ui.components.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R

/**
 * 输出端保存格式开关（v4.6.1 模块 C）。
 *
 * 打开 = 服务端把 `SaveImage` 换成插件节点 `BatchImageSaverCN`，直接落 `.webp`；
 * 关闭 = 保持原生 `SaveImage`，落 `.png`。两条路互斥，不会同时产出两份文件。
 *
 * 只由图片类屏幕（文生图 / 图生图修复 / 图生图编辑）装配；视频与 3D 页面不构造
 * [OutputFormatConfig]，因此该开关不会出现在那些界面。
 */
@Composable
fun OutputFormatSection(config: OutputFormatConfig) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.output_format_webp_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.output_format_webp_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = config.saveAsWebp,
            onCheckedChange = config.onSaveAsWebpChange
        )
    }
}
