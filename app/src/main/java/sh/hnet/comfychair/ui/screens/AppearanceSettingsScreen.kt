package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.ui.components.colorpicker.HsvColorPicker
import sh.hnet.comfychair.ui.theme.ColorMath
import sh.hnet.comfychair.ui.theme.ColorSlotKind
import sh.hnet.comfychair.ui.theme.DarkColorScheme
import sh.hnet.comfychair.ui.theme.LightColorScheme
import sh.hnet.comfychair.ui.theme.ThemeController
import sh.hnet.comfychair.ui.theme.ThemeCustomization
import sh.hnet.comfychair.ui.theme.ThemeMode
import sh.hnet.comfychair.ui.theme.buildCustomColorScheme

/** 调色盘开放的三项 —— 顺序即界面上的顺序，展示文案留在界面层 */
private enum class ColorSlot(val kind: ColorSlotKind, val title: String, val subtitle: String) {
    ACCENT(ColorSlotKind.ACCENT, "强调色", "生成按钮、选中边框、选中态底色、链接文字"),
    BASE(ColorSlotKind.BASE, "底色系", "页面背景、卡片、浮层、分割线"),
    TEXT(ColorSlotKind.TEXT, "文字色", "正文与次要说明文字")
}

/**
 * 外观配色（调色盘）。
 *
 * 交互约定：
 * - 三项**互相独立**，只调一项就只影响一项；没动过的项标注「跟随原厂」并原样使用原厂色。
 * - 页面里改的都是**草稿**，点「预览效果」→「保存并应用」才落到全局并持久化。
 * - 「恢复原始配色」把三项一起拨回未自定义，同样要保存才生效 ——
 *   "改—看—定"只有一条路径，不会出现"我明明点过重置，怎么有的地方变了有的没变"。
 */
@Composable
fun AppearanceSettingsScreen(
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val stored by ThemeController.customization.collectAsState()
    val mode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT

    // 进页面时以已保存的配色为起点
    var draft by remember(stored) { mutableStateOf(stored ?: ThemeCustomization()) }
    var expandedSlot by remember { mutableStateOf<ColorSlot?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val dirty = (stored ?: ThemeCustomization()) != draft
    val isAllDefault = draft.isEmpty

    SettingsScreenScaffold(
        title = "外观配色",
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout,
        onNavigateBack = onNavigateBack
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isAllDefault) "当前：原厂配色" else "当前：自定义配色",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "三项可以分别调整，只调一项就只影响一项。只调一套颜色，" +
                        "深色与浅色模式会自动各取合适的一版，不用为了浅色模式再调一遍；" +
                        "底色与文字的明度由程序兜底，保证改完仍然看得清。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ColorSlot.entries.forEach { slot ->
            ColorSlotCard(
                slot = slot,
                color = draft.colorOf(slot.kind, mode),
                isCustomized = draft.isSet(slot.kind),
                expanded = expandedSlot == slot,
                onToggle = { expandedSlot = if (expandedSlot == slot) null else slot },
                onColorChange = { draft = draft.withColor(slot.kind, it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (dirty) {
            Text(
                text = "有未保存的改动，点下面的按钮应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { showPreview = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("预览效果")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showResetConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isAllDefault
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("恢复原始配色")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showPreview) {
        ThemePreviewDialog(
            customization = draft,
            onDismiss = { showPreview = false },
            onConfirm = {
                // 三项全空时 ThemeController 会存 null（清掉自定义），
                // 让界面零偏差地回到原厂色板，而不是把原厂色值再走一遍 HSL 往返
                ThemeController.apply(context, draft)
                showPreview = false
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复原始配色") },
            text = {
                Text(
                    "三项颜色都会拨回 ComfyChair 出厂配色（深空蓝灰 + 青蓝）。" +
                        "这一步只是改草稿，还需要在预览里点「保存并应用」才会真正生效。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = ThemeCustomization()
                    expandedSlot = null
                    showResetConfirm = false
                }) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 单个颜色项：收起时只显示色块与说明，点开才出现取色器 */
@Composable
private fun ColorSlotCard(
    slot: ColorSlot,
    color: Int,
    isCustomized: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onColorChange: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = slot.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (isCustomized) ColorMath.toHexString(color) else "跟随原厂",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (isCustomized) FontFamily.Monospace else FontFamily.Default
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HsvColorPicker(color = color, onColorChange = onColorChange)
            }
        }
    }
}

/**
 * 小样预览弹窗。
 *
 * 面板用草稿配色单独渲染（套一层 MaterialTheme），不改动全局主题 ——
 * 这样"预览"不会影响用户此刻正看着的界面，确认之后才真正应用。
 */
@Composable
private fun ThemePreviewDialog(
    customization: ThemeCustomization,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val mode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT
    val scheme = remember(customization, mode) {
        val base = if (mode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
        buildCustomColorScheme(base, customization, mode)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预览效果") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "下面是用当前配色渲染的控件样例（${if (mode == ThemeMode.DARK) "深色" else "浅色"}模式）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                MaterialTheme(colorScheme = scheme) {
                    ThemeSamplePanel()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存并应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("继续调整") }
        }
    )
}

/** 控件样例：把用户能感知到的配色场景铺一遍 */
@Composable
private fun ThemeSamplePanel() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 顶栏
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "顶栏 / 面板",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        // 卡片：标题 + 正文 + 次要说明
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "卡片标题",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "这是正文文字，用来确认在卡片底色上是否读得清楚。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "这是次要说明文字，比正文淡一档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("生成") }
            OutlinedButton(onClick = {}) { Text("取消") }
        }

        // 选中态与未选中态
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "已选中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "未选中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Text(
                text = "链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        // 输入框：主要看描边色
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("输入框") },
            placeholder = { Text("提示文字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 状态色（色相不跟调色盘走，但要确认仍然读得出）
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "● 连接中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "● 已连接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "● 失败",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
