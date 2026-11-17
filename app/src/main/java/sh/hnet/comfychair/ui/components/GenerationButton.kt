package sh.hnet.comfychair.ui.components

import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.rememberIsOffline
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback
import sh.hnet.comfychair.ui.theme.ColorMath

/** 辅色派生规则（决策 1）：强调色色相 +35°，S/V 不变。 */
private const val ACCENT_HUE_SHIFT_DEG = 35f

/**
 * 下拉按钮渐变向背景沉的权重（U3：下拉必须是次层级）：
 * 主色 70% + 背景色 30% 混出的底再走色相偏移，亮度/饱和度都必须低于主按钮渐变。
 * （模块 2 安全阀关闭时的回退规格；开启时下拉基色走主按钮渐变中点色，见下。）
 */
private const val TRAILING_SURFACE_MIX_FRACTION = 0.30f

/** 下拉基色 = 主按钮渐变（accent → +35°）的中点（模块 2/C1，internal 供单测钉规格）。 */
internal const val TRAILING_GRADIENT_MIDPOINT_FRACTION = 0.5f

/**
 * 下拉打开态白边 alpha（模块 2/S2，internal 供单测钉规格）：
 * 静置走主按钮同源默认（深色 22% / 浅色 12%），打开提亮到 32%。
 */
internal const val TRAILING_BORDER_OPEN_ALPHA = 0.32f

/** 测试定位 tag（Compose 惯例：仅测试可见的 tag 允许留在生产代码）。 */
private const val TRAILING_BUTTON_TAG = "trailing-button"
private const val TRAILING_ARROW_TAG = "trailing-arrow"

/**
 * 下拉按钮箭头图标的显式尺寸（B1：图标显式几何居中）。
 * 24dp = Material Icons 默认尺寸，与同排齿轮/菜单图标同为 24dp 体系。
 * 注意：M3 对 55dp+ trailing 按钮的图标 token 实为
 * SplitButtonMediumTokens.TrailingIconSize = 26dp（material3 1.5.0-alpha11 反编译确认），
 * 此处刻意不用 26dp —— 数值与改动前视觉一致，且保持与既有图标体系统一。
 */
private val TRAILING_ICON_SIZE = 24.dp

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

    // S2Probe（v4.16.7 取证）：主按钮窗口坐标时间线。onGloballyPositioned 每次布局
    // 摆放都会回写最新 bounds，onClick 里取到的就是"点击瞬间"按钮在窗口里的位置，
    // 用来对照"DOWN 时按钮在哪 / UP 时按钮在哪"（imePadding 位移验证）。
    // 仅 debug 包输出（BuildConfig.DEBUG 门控），release 无任何日志开销。
    var latestBounds by remember { mutableStateOf<Rect?>(null) }

    // B2b（问题 3 兜底）：showMenu 在菜单因任何原因关闭后必须复位 false —— 状态一旦
    // 卡在 true，下一次点击只会把它翻成 false，表现为"下拉永久打不开"。正常路径
    // DropdownMenu 的 onDismissRequest 已覆盖 BACK 关闭与点击外部关闭（M3
    // DefaultMenuProperties：focusable=true / dismissOnBackPress=true /
    // dismissOnClickOutside=true），菜单项点击各自显式置 false；本回调只兜异常路径 ——
    // popup 因焦点异常没消费 BACK 时，BACK 会直接把页面退回连接界面且状态卡 true
    //（只能 force-stop 恢复），由这里先复位状态并消费 BACK，行为与 popup 正常关闭一致。
    // 正常路径 BACK 在 popup 窗口层就被消费，本回调不触发，零行为变化。
    BackHandler(enabled = showMenu) { showMenu = false }

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
    // 下拉图标显式居中开关（B1 回退阀）：开 = 图标外置到按钮容器上显式居中（轴心=按钮中心）；
    // 关 = 图标留 M3 content 槽内、居中交给 ElevatedTrailingButton 内部排布（改动前原路径）。
    val trailingIconCentered = remember(context) { AppSettings.isTrailingIconCentered(context) }
    // 模块 2 安全阀（组合时读一次）：开 = 下拉中点色纯色 + 白边与主按钮同源 + 打开态提亮；
    // 关 = 改动前形态（0.30 混合渐变 + 0.18 白边恒定）
    val pressUnified = remember(context) { AppSettings.isPressFeedbackUnified(context) }
    // S2 修复开关（v4.16.7 安全阀，组合时读一次）：开 = onGenerate 先行、clearFocus 推后
    // 一帧；关 = 改动前顺序（clearFocus 立即执行）。用途见 onClick 内注释。
    val imeClickFix = remember(context) { AppSettings.isGenerateClickImeFixEnabled(context) }
    val view = LocalView.current

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
                // S2Probe（v4.16.7 取证）：onClick 是否到达。R4 判据是"焦点没被清除 =
                // onClick 没执行"，这条日志一锤定音（必须在 clearFocus 之前打，
                // clearFocus 是同步的，执行了必清焦点）。
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "S2Probe",
                        "onClick enabled=$leadingEnabled offline=$isOffline " +
                            "ts=${SystemClock.uptimeMillis()} bounds=$latestBounds"
                    )
                }
                if (imeClickFix) {
                    // S2 修复（v4.16.7 方向 A1）：先同步提交生成，clearFocus 推后一帧。
                    // 键盘收起会触发 imePadding 逐帧重排、按钮在窗口里位移 246px（R4
                    // 取证）；把"收键盘"从点击处理路径里挪出去，点击后按钮不再立即跳动，
                    // 连点时第二击仍落在按钮停留过的位置上。
                    onGenerate()
                    view.post { focusManager.clearFocus() }
                } else {
                    // 回退开关关闭：改动前顺序（clearFocus 立即执行）
                    focusManager.clearFocus()
                    onGenerate()
                }
            },
            enabled = leadingEnabled,
            // U3：M3 自带 elevation 阴影去掉 —— SplitButton 默认 LeadingButtonShape 是
            // "左圆右方"半胶囊，M3 阴影会在按钮尾部露出直角灰块（实测浅色主题可见），
            // 且与皮肤层可控投影叠加后发脏。主按钮投影统一由 rippleFeedback 的 2 层投影承担。
            elevation = null,
            colors = ButtonDefaults.elevatedButtonColors(
                // v4.16.6（Q2）：原 `else -> onSurface@12%` 是死分支 —— material3 1.5.0-alpha11 的
                // ButtonColors.containerColor(enabled) 在 disabled 时读 disabledContainerColor 槽位、
                // 不读 containerColor，数值恰与 M3 token 默认一致所以视觉没露馅。现在把禁用色
                // 显式写进 disabled 槽位（意图与行为对齐）；禁用文字色不传 disabledContentColor，
                // 保持 M3 默认 onSurface@0.38（写成 onPrimary 会在灰底上看不清）。
                containerColor = if (leadingSkinActive) Color.Transparent else containerColor,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor = contentColor
            ),
            interactionSource = leadingInteraction,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                // S2Probe（v4.16.7 取证）：布局摆放回写按钮窗口坐标（见 latestBounds 注释）
                .onGloballyPositioned { latestBounds = it.boundsInWindow() }
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

        // 队列菜单标签（B1b）：两条路径共用同一字符串 —— 回退路径挂在槽内 Icon 的
        // contentDescription，外置路径挂到按钮 merge 边界（见下方 trailing modifier）
        val queueMenuLabel = stringResource(R.string.content_description_queue_menu)

        // Trailing button - disabled in offline mode (no queue operations available)
        // interactionSource 必须与按钮共享，按压回弹才收得到事件。
        val trailingInteraction = remember { MutableInteractionSource() }
        // 决策 2 原文把渐变范围定为「leading + trailing 小下拉」整组；禁用态与总开关关闭的
        // 兜底逻辑同 leading。v2 皮肤：下拉降级为主按钮的次级元素 —— 54×56、无辉光无投影、
        // 白边与主按钮同源 + 打开态提亮 32%（模块 2）、基色走主按钮渐变中点色（U1：且不带
        // 点阵）；回退态恢复 56×56 + 环形辉光（层级：主 > 下拉，不得同重量）。
        val trailingEnabled = !isOffline
        val trailingSkinActive = trailingEnabled && dotFeedbackEnabled
        // C1（模块 2）：下拉基色改为主按钮渐变（accent → +35°）的**中点色**，且下拉内部
        // 不再做渐变（纯色填充）—— 54dp 窄条上铺 accent→+35° 渐变时左端（亮）占比大，
        // 整体偏亮；中点色纯色的平均亮度与主按钮（158dp 上铺开同一渐变）最接近，
        // 两主题都成立（基色不含 surface 混合，不存在"往亮里混"的问题）。
        // 开关关闭回 U3 旧规格：主色先向 surface 沉 30%（70% 主色 + 30% 背景）再走 +35° 色相偏移。
        val trailingGradientBase = if (pressUnified) {
            lerp(accent, accent.shiftAccentHue(), TRAILING_GRADIENT_MIDPOINT_FRACTION)
        } else {
            lerp(accent, MaterialTheme.colorScheme.surface, TRAILING_SURFACE_MIX_FRACTION)
        }
        Box(modifier = Modifier.wrapContentWidth(unbounded = true)) {
            SplitButtonDefaults.ElevatedTrailingButton(
                checked = showMenu,
                onCheckedChange = { showMenu = it },
                enabled = trailingEnabled,
                // U3：次级元素不带任何投影 —— M3 elevation 阴影会在半胶囊形状外露出
                // 错形残边（同 leading），皮肤层白边 18% 已足够勾勒轮廓
                elevation = null,
                interactionSource = trailingInteraction,
                colors = ButtonDefaults.elevatedButtonColors(
                    // 同 leading（Q2）：禁用色显式写进 disabled 槽位，删掉不可达的 else 分支
                    containerColor = if (trailingSkinActive) Color.Transparent else containerColor,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    contentColor = contentColor
                ),
                modifier = Modifier
                    .testTag(TRAILING_BUTTON_TAG)
                    // B1b a11y：外置路径下 content 槽为空，M3 内部合并边界收不到任何标签，
                    // TalkBack 焦点到按钮只读「未加标签」，而外置 Icon 自带 cd 会变成第二个
                    // 冗余焦点节点。这里显式补一个 merge 边界 + contentDescription：按钮节点
                    // 自带标签并吞并内部 toggleable 的点击动作，TalkBack 一次聚焦即可操作
                    //（语义属性不参与测量/布局，两态几何不受影响）。回退路径不加 —— 槽内
                    // Icon 的 cd 已随内部边界合并进按钮节点，再叠一层会把 cd 拼接读两遍。
                    .then(
                        if (trailingIconCentered) {
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = queueMenuLabel
                            }
                        } else {
                            Modifier
                        }
                    )
                    // v2 皮肤 54×56（下拉比主按钮窄一档）；回退态 56×56 方形
                    .size(width = if (skinV2) 54.dp else 56.dp, height = 56.dp)
                    .rippleFeedback(
                        interactionSource = trailingInteraction,
                        // 决策 8：方形按钮圆角 18dp（对齐基准方形版），不再是胶囊的 28dp
                        shape = RoundedCornerShape(18.dp),
                        enabled = trailingEnabled && dotFeedbackEnabled,
                        // C2（模块 2）：静置白边与主按钮同源（不传 override 走皮肤层默认
                        // 深色 22% / 浅色 12%）；S2：打开态提亮到 32%。开关关闭回 v2 旧规格
                        // 18%（打开不提亮）；回退态（!skinV2）走皮肤层默认（深色 50%）
                        borderAlphaOverride = when {
                            !skinV2 -> null
                            !pressUnified -> 0.18f
                            showMenu -> TRAILING_BORDER_OPEN_ALPHA
                            else -> null
                        },
                        // v2：无辉光无投影；回退态保留旧环形辉光
                        showHalo = !skinV2,
                        // U1：下拉不带点阵（点阵只留在生成主按钮），皮肤其余结构保留
                        showDots = false,
                        gradientColors = if (trailingSkinActive) {
                            if (pressUnified) {
                                // C1：中点色纯色填充（同色"渐变" = 无渐变观感）
                                listOf(trailingGradientBase, trailingGradientBase)
                            } else {
                                listOf(trailingGradientBase, trailingGradientBase.shiftAccentHue())
                            }
                        } else {
                            null
                        },
                        // 同 leading：深色渐变底 → 白点
                        dotColor = Color.White,
                    )
            ) {
                if (!trailingIconCentered) {
                    // 回退路径（开关关闭）：图标留在 M3 content 槽内，居中交给
                    // ElevatedTrailingButton 内部排布 —— 与改动前逐像素一致的原路径。
                    // cd 保留在 Icon 上，经内部合并边界汇入按钮节点（改动前 TalkBack 形态）
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = queueMenuLabel,
                        modifier = Modifier
                            .rotate(iconRotation)
                            .testTag(TRAILING_ARROW_TAG)
                    )
                }
            }

            if (trailingIconCentered) {
                // B1 显式居中：图标外置到按钮容器上，align(Center) 让图标中心恒等于按钮
                // 几何中心 —— 不依赖 M3 content 槽内部的 Row 排布与光学水平偏移
                //（horizontalCenterOptically 按形状做光学补偿，收起/展开形状不同偏移也不同，
                // 跨版本还可能变）。中心重合后，Modifier.rotate 的默认轴心（图标自身中心）
                // 即按钮中心，两态共用同一个图标节点与同一条 modifier 链。
                // tint 显式给 contentColor：外置后图标不再经过按钮 content 槽的
                // ProvideContentColorTextStyle 作用域，默认取不到 onPrimary。
                // contentDescription 必须为 null（B1b）：按钮标签已由上方的 merge 边界承担，
                // 图标若再带 cd 会成为 TalkBack 树里第二个不可点的冗余焦点节点；置 null 后
                // 图标纯装饰、不单独聚焦（也不能用 clearAndSetSemantics —— 会连 testTag
                // 一起清掉，破坏 instrumented 测试的 trailing-arrow 定位）
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(TRAILING_ICON_SIZE)
                        .rotate(iconRotation)
                        .testTag(TRAILING_ARROW_TAG)
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
