package sh.hnet.comfychair.ui.components.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.prompt.SkillTextResult

/**
 * 技能流的结果弹窗：把模型改写出的成品提示词展示出来。
 *
 * 技能流（prebuilt_24/25）不出图，结果是一段文字 —— 图片预览那套界面用不上，
 * 所以单独走这个弹窗。关闭只是**收起**（[SkillTextResult.dismiss]），结果留在通道里：
 * 页面上会显示一个常驻按钮，随时点开再看 —— 提示词动辄上千字，一次没复制完就得重跑
 * 一次本地模型太亏。
 */
@Composable
fun SkillTextResultDialog(modifier: Modifier = Modifier) {
    val visible by SkillTextResult.dialogVisible.collectAsState()
    val text by SkillTextResult.text.collectAsState()
    val result = text ?: return
    if (!visible) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { SkillTextResult.dismiss() },
        title = { Text("生成的提示词") },
        text = {
            Column(modifier = modifier) {
                Text(
                    text = "${result.length} 字符 · 可复制后直接用于出图",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copyPrompt(context, result) }) { Text("复制") }
                TextButton(onClick = { SkillTextResult.dismiss() }) { Text("关闭") }
            }
        }
    )
}

/**
 * 「查看生成的提示词」常驻按钮：弹窗被关掉之后出现在操作区，点一下重新打开。
 *
 * **跟着工作流走**：只在该工作流自己产出的结果上出现 —— 换到别的流按钮就消失
 * （原先只看"有没有结果"，切走后按钮还赖在界面上，用户反馈"画面很拥挤"），
 * 再切回来才恢复上一次的结果。归属由这里首次绑定的（[SkillTextResult.bindOwner]）。
 *
 * 没有隐藏结果时它自己不画任何东西，普通出图流那边完全无感。
 */
@Composable
fun SkillTextResultButton(workflowName: String, modifier: Modifier = Modifier) {
    val hasHidden by SkillTextResult.hasHiddenResult.collectAsState()
    val owner by SkillTextResult.ownerWorkflow.collectAsState()

    // 结果刚出现时把它绑定到当时的工作流；已绑定过的不再改动，
    // 否则用户切一次工作流就会把归属改走
    LaunchedEffect(hasHidden, workflowName) {
        if (hasHidden) SkillTextResult.bindOwner(workflowName)
    }

    if (!hasHidden || owner != workflowName) return

    OutlinedIconButton(
        onClick = { SkillTextResult.reopen() },
        modifier = modifier.size(56.dp)
    ) {
        Icon(
            Icons.Default.Article,
            contentDescription = "查看生成的提示词"
        )
    }
}

private fun copyPrompt(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("prompt", text))
}
