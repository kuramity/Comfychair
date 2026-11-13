package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R

/**
 * 图位的全屏查看器：点已填格子后放大看图，并就地给一个「更换」入口（设计 §3「点 = 查看 / 换单张」）。
 *
 * - 用全屏 `Dialog` 承载，不为一次查看动作引入新 Activity；`onDismiss` 由宿主决定是否关掉
 *   （返回键、点按钮、点遮罩都会回调它）。
 * - 手势直接复用图库那套 [ImageViewer]（同一份捏合缩放/平移/双击放大），手感与图库一致。
 * - 入场是 0.9 → 1.0 的缩放 + 淡入；**不用模糊、不用阴影、不用渐变**（目标机型性能偏弱，全项目零模糊零阴影）。
 * - [slotLabel] 浮在左上角（与网格格子里那颗标签同款），避免用户忘了正在看的是哪个图位。
 *
 * 不含任何 ViewModel / 选图器调用：这些属于宿主（页面）的职责，本组件只发回调。
 *
 * @param bitmap 该图位已解码的图片
 * @param slotLabel 图位名（"首帧" / "参考图 1" …），由宿主按与网格同一口径生成
 * @param onReplace 点「更换」时回调，宿主去打开系统选图器
 * @param onDismiss 请求关闭（返回键 / 关闭按钮 / 点遮罩）
 */
@Composable
fun ImageSlotViewer(
    bitmap: Bitmap,
    slotLabel: String,
    onReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 应用是 edge-to-edge（各 ContainerActivity 都调了 enableEdgeToEdge），
            // 不关掉这一项，遮罩会在状态栏/导航栏处断掉，不成其为「全屏」
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // 先画一帧"只有遮罩"，下一帧再置真，入场动画才会播
        // （AnimatedVisibility 首次组合就是 visible = true 时会直接显示，不播动画）
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
        ) {
            AnimatedVisibility(
                visible = appeared,
                // 默认 initialScale = 0：查看器自带不透明黑底，从 0 放大会看到黑块在遮罩上扫开；
                // 取 0.9 + 淡入，缩放幅度与图库双击放大的观感接近，也够轻量
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                modifier = Modifier.fillMaxSize()
            ) {
                ImageViewer(bitmap = bitmap, modifier = Modifier.fillMaxSize())
            }

            // 图位名：与网格格子的标签同款（surface 底色压 0.75 alpha 的纯色块，非渐变）
            AnimatedVisibility(
                visible = appeared,
                enter = fadeIn(tween(200)),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    text = slotLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            AnimatedVisibility(
                visible = appeared,
                enter = fadeIn(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(onClick = onReplace) {
                        Text(stringResource(R.string.image_slot_replace))
                    }
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}
