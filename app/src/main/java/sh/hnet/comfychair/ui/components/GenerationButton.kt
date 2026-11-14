package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.rememberIsOffline
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback
import sh.hnet.comfychair.ui.theme.ColorMath

/** 辅色派生规则（决策 1）：强调色色相 +35°，S/V 不变。 */
private const val ACCENT_HUE_SHIFT_DEG = 35f

/**
 * 渐变辅色：强调色在 HSV 口径上色相 +35°。
 * 刻意不缓存（不 remember）：每次重组现算，调色盘换 accent 后按钮渐变下一帧立即跟随。
 */
private fun Color.shiftAccentHue(): Color =
    Color(ColorMath.shiftHueHsv(toArgb(), ACCENT_HUE_SHIFT_DEG))

/**
 * Split button for generation screens with queue management dropdown.
 *
 * Leading button: Always submits to queue ("Generate" or "Add to queue (X)")
 * Trailing button: Dropdown menu with queue management actions
 *
 * Button text priority: Connecting > Uploading > Fetching > Queue size > Generate
 *
 * 离线置灰由按钮**自己**从连接状态派生（见 [rememberIsOffline]），不靠调用方传参：
 * 用户「强制离线」与网络断开后的自动离线必须表现一致，参数化迟早会漏传一处。
 *
 * @param queueSize Total number of jobs in the server queue (all clients)
 * @param isExecuting Whether any job is currently executing on the server
 * @param isEnabled Whether the button should be enabled (valid input to submit)
 * @param isUploading Whether image upload is in progress (shows "Uploading...")
 * @param isFetching Whether result fetch is in progress (shows "Fetching...")
 * @param isConnecting Whether connection check is in progress (shows "Connecting...")
 * @param onGenerate Callback when Generate/Add to queue is clicked
 * @param onCancelCurrent Callback to cancel the currently executing job
 * @param onAddToFrontOfQueue Callback to add to front of queue
 * @param onClearQueue Callback to clear the server queue
 * @param modifier Modifier for the button layout
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationButton(
    queueSize: Int,
    isExecuting: Boolean,
    isEnabled: Boolean,
    isUploading: Boolean = false,
    isFetching: Boolean = false,
    isConnecting: Boolean = false,
    /** 上传进度 0–100（-1 = 未知/无上传）；v4.6.1 模块 D */
    uploadProgress: Int = -1,
    /**
     * v4.6.1（模块 A3）：当前页面是否**有权取消**。
     *
     * `isExecuting` 是全局的（服务器在跑任何任务都为 true），用它控制"取消当前"会出现
     * 跨模式误伤：A 模式在跑，B 模式点取消会把 A 的任务中断。这里拆成两个语义：
     * - [isExecuting]：只用于文案/颜色（"当前确实有任务在跑"）
     * - [canCancelCurrent]：只有本页面自己的任务（在跑或排队）才为 true
     * 默认值保持旧行为，避免影响尚未迁移的调用点。
     */
    canCancelCurrent: Boolean = isExecuting,
    onGenerate: () -> Unit,
    onCancelCurrent: () -> Unit,
    onAddToFrontOfQueue: () -> Unit = {},
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var showMenu by remember { mutableStateOf(false) }

    // 离线态（强制离线与自动离线一视同仁）：生成与队列操作全部禁用
    val isOffline = rememberIsOffline()

    // 波纹点阵皮肤总开关（v4.12.x）：组合时读一次，置 false 时两个按钮都恢复无特效形态
    val context = LocalContext.current
    val dotFeedbackEnabled = remember(context) {
        AppSettings.isRippleDotFeedbackEnabled(context)
    }
    // 皮肤 v2 总开关（回退阀）：主按钮白边 22%/1dp + 辉光改向下投影、下拉按钮 54×56/无辉光、
    // 主↔下拉间距 10dp。关闭时全部回 v4.12.x 形态（白边 50%、环形辉光、56×56、2dp 间距）。
    val skinV2 = remember(context) { AppSettings.isUiSkinV2Enabled(context) }

    // Button always uses primary color (no more red cancel state)
    val containerColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onPrimary

    // Button text changes based on connection/upload/fetch state and queue size
    val buttonText = when {
        isConnecting && queueSize > 0 -> stringResource(R.string.button_connecting_queue, queueSize)
        isConnecting -> stringResource(R.string.button_connecting)
        // v4.6.1 模块 D：上传进度可见（ProgressTrackingRequestBody → ViewModel → 这里）
        isUploading && uploadProgress in 0..100 && queueSize > 0 ->
            stringResource(R.string.button_uploading_progress_queue, uploadProgress, queueSize)
        isUploading && uploadProgress in 0..100 ->
            stringResource(R.string.button_uploading_progress, uploadProgress)
        isUploading && queueSize > 0 -> stringResource(R.string.button_uploading_queue, queueSize)
        isUploading -> stringResource(R.string.button_uploading)
        isFetching && queueSize > 0 -> stringResource(R.string.button_fetching_queue, queueSize)
        isFetching -> stringResource(R.string.button_fetching)
        queueSize > 0 -> stringResource(R.string.button_add_to_queue, queueSize)
        else -> stringResource(R.string.button_generate)
    }

    Row(modifier = modifier) {
        // Leading button - fills available width, always submits to queue
        // Disabled when offline mode is active or input is invalid
        // 波纹点阵反馈（v4.9.x）：interactionSource 必须与按钮共享，按压回弹才收得到事件；
        // rippleFeedback 只在 Initial pass 观察指针、绝不 consume，提交行为不受影响。
        // shape 取 RoundedCornerShape(28.dp) —— 等于按钮高度一半，才是真正的胶囊；
        val leadingInteraction = remember { MutableInteractionSource() }
        // v5.x 皮肤（决策 1/2/4）：enabled 且皮肤总开关开 → 容器透明，双色渐变从底层露出；
        // 禁用 → 渐变 null、容器回 M3 默认禁用灰，无渐变/白边/辉光/波纹，零特效。
        // 总开关关闭时保留纯色 primary（等价旧版无特效形态），不能给透明容器（会只剩文字）。
        val leadingEnabled = isEnabled && !isOffline
        val leadingSkinActive = leadingEnabled && dotFeedbackEnabled
        val accent = MaterialTheme.colorScheme.primary
        SplitButtonDefaults.ElevatedLeadingButton(
            onClick = {
                focusManager.clearFocus()
                onGenerate()
            },
            enabled = leadingEnabled,
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = when {
                    leadingSkinActive -> Color.Transparent
                    leadingEnabled -> containerColor
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                contentColor = contentColor
            ),
            interactionSource = leadingInteraction,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .rippleFeedback(
                    interactionSource = leadingInteraction,
                    // 56dp 高的胶囊按钮：圆角半径必须取**高度一半（28dp）**才是真胶囊。
                    // CircleShape 是"四角百分比各取 50%"，在非正方形上会变成内切椭圆 ——
                    // 椭圆弧与真实按钮边缘最大偏离约 9dp，会让辉光看起来是条游离的弧线，
                    // 按钮两端的点阵/波纹也会被椭圆裁掉。48dp 方形图标按钮那种才适合 CircleShape。
                    shape = RoundedCornerShape(28.dp),
                    enabled = leadingEnabled && dotFeedbackEnabled,
                    // 渐变色不缓存（决策 7）：accent 变了重组时这里现取新值，绘制层下一帧跟随
                    gradientColors = if (leadingSkinActive) {
                        listOf(accent, accent.shiftAccentHue())
                    } else {
                        null
                    },
                    // v2 皮肤：环形辉光改为向下 6dp 偏移的投影（白边 alpha/线宽在皮肤层内
                    // 随 skinV2 切到 22%/1dp）；回退态 false = 旧版环形辉光
                    haloDownward = skinV2,
                    // 深色渐变底上点阵用白点（对齐基准实例 rgba(255,255,255,α)），
                    // primaryContainer 浅蓝压深蓝渐变对比不足、几乎不可见
                    dotColor = Color.White,
                )
        ) {
            Text(
                text = buttonText,
                fontSize = 18.sp
            )
        }

        // 主↔下拉间距：v2 皮肤统一 10dp（三层操作行规格：主/下拉/齿轮等距）；
        // 回退态恢复 SplitButtonDefaults.Spacing（2dp）旧观感
        Spacer(modifier = Modifier.width(if (skinV2) 10.dp else SplitButtonDefaults.Spacing))

        // Trailing button with dropdown - square button matching height of leading
        // Animate icon rotation: 0° when closed, 180° when open (arrow points up)
        val iconRotation by animateFloatAsState(
            targetValue = if (showMenu) 180f else 0f,
            label = "dropdown icon rotation"
        )

        // Trailing button - disabled in offline mode (no queue operations available)
        // 波纹点阵反馈（v4.12.x）；interactionSource 必须与按钮共享，按压回弹才收得到事件。
        val trailingInteraction = remember { MutableInteractionSource() }
        // 决策 2 原文把渐变范围定为「leading + trailing 小下拉」整组（基准 HTML 的方形版
        // 56×56/18px 也是同款渐变 + 白边 + 辉光）；禁用态与总开关关闭的兜底逻辑同 leading。
        // v2 皮肤：下拉降级为主按钮的次级元素 —— 54×56、无辉光无投影、白边 18%；
        // 回退态恢复 56×56 + 环形辉光（层级：主 > 下拉，不得同重量）。
        val trailingEnabled = !isOffline
        val trailingSkinActive = trailingEnabled && dotFeedbackEnabled
        Box(modifier = Modifier.wrapContentWidth(unbounded = true)) {
            SplitButtonDefaults.ElevatedTrailingButton(
                checked = showMenu,
                onCheckedChange = { showMenu = it },
                enabled = trailingEnabled,
                interactionSource = trailingInteraction,
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = when {
                        trailingSkinActive -> Color.Transparent
                        trailingEnabled -> containerColor
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    },
                    contentColor = contentColor
                ),
                modifier = Modifier
                    // v2 皮肤 54×56（下拉比主按钮窄一档）；回退态 56×56 方形
                    .size(width = if (skinV2) 54.dp else 56.dp, height = 56.dp)
                    .rippleFeedback(
                        interactionSource = trailingInteraction,
                        // 决策 8：方形按钮圆角 18dp（对齐基准方形版），不再是胶囊的 28dp
                        shape = RoundedCornerShape(18.dp),
                        enabled = trailingEnabled && dotFeedbackEnabled,
                        // v2：白边 18%（层级降档）；null = 回退态走皮肤层默认（深色 50%）
                        borderAlphaOverride = if (skinV2) 0.18f else null,
                        // v2：无辉光无投影；回退态保留旧环形辉光
                        showHalo = !skinV2,
                        gradientColors = if (trailingSkinActive) {
                            listOf(accent, accent.shiftAccentHue())
                        } else {
                            null
                        },
                        // 同 leading：深色渐变底 → 白点
                        dotColor = Color.White,
                    )
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_description_queue_menu),
                    modifier = Modifier.rotate(iconRotation)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // Queue management actions (above gap)
                // Add to front of queue
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_to_front_of_queue)) },
                    onClick = {
                        showMenu = false
                        onAddToFrontOfQueue()
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null)
                    }
                )
                // Clear queue
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.button_clear_queue)) },
                    onClick = {
                        showMenu = false
                        onClearQueue()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                )

                // Gap divider separating queue actions from cancel
                HorizontalDivider()

                // Cancel current (below gap) - destructive action separated
                // Use error/warning color when enabled to indicate destructive action
                // v4.6.1：enabled 由 canCancelCurrent 决定（本页面自己的任务），不是全局 isExecuting
                val cancelColor = if (canCancelCurrent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MenuDefaults.itemColors().disabledTextColor
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_cancel_current), color = cancelColor) },
                    onClick = {
                        showMenu = false
                        onCancelCurrent()
                    },
                    enabled = canCancelCurrent,
                    leadingIcon = {
                        Icon(Icons.Default.Cancel, contentDescription = null, tint = cancelColor)
                    }
                )
            }
        }
    }
}
