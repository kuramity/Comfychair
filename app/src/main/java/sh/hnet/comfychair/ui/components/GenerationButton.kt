package sh.hnet.comfychair.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SplitButtonShapes
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.rememberIsOffline
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.RippleSkinState
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback
import sh.hnet.comfychair.ui.theme.ColorMath
import sh.hnet.comfychair.ui.theme.Dimensions

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

/**
 * 忙碌态按钮文字 alpha（v4.16.14，internal 供单测钉规格）。
 *
 * 忙碌态沿用 onPrimary（渐变上的主色文字），只降到 0.92 —— 肉眼几乎无差，但足以和
 * 静置态区分"我在忙"。刻意**不用** M3 默认禁用文字色 onSurface@0.38：那在青蓝渐变底上
 * 会糊成一片、看不清字。真禁用仍是 onSurface@0.38（灰底灰字，不许被忙碌态顺手带亮）。
 */
internal const val BUSY_CONTENT_ALPHA = 0.92f

/**
 * 生成主按钮的「忙碌 / 皮肤」判定结果（纯数据，v4.16.14 复核 BUSY-03/04）。
 *
 * @property enabled 按钮最终是否可点（[isEnabled] 已剔除离线）
 * @property busy 是否本页忙碌（离线时恒 false）
 * @property skinActive 是否启用皮肤（渐变 / 点阵 / 白边）
 * @property busyContainerTransparent 忙碌态禁用容器是否透明（露出渐变）
 */
internal data class GenerationButtonSkin(
    val enabled: Boolean,
    val busy: Boolean,
    val skinActive: Boolean,
    val busyContainerTransparent: Boolean,
)

/**
 * 生成主按钮的忙碌态判定（纯函数，供 JVM 单测钉住完整输入矩阵）。
 *
 * 为什么单独抽出来：组件内部无法从 `isEnabled=false` 反推"真禁用"还是"忙碌"，
 * 而忙碌态又必须是**本页自己的**忙碌 ——
 * 1. [isSubmitting] 来自 Activity 级共享的 GenerationViewModel（四页同一个实例），
 *    A 页提交期间切到 B 页，B 页哪怕提示词为空也会看到 isSubmitting=true。调用方必须
 *    先在页面侧按 ownerId 过滤（`isSubmitting && submitOwner == OWNER_ID`）再传进来；
 * 2. 离线（强制离线 / 断网）一律按真禁用处理 —— 提交途中掉线不能让按钮继续亮着
 *    忙碌皮肤，否则"离线灰底"的视觉承诺被打破（复核 BUSY-04）。
 *
 * @param isEnabled 页面传入的 enabled（本页输入合法 且 本页无提交）
 * @param isOffline 强制离线或自动离线（rememberIsOffline 同值）
 * @param isConnecting 连接检查在途（ConnectionManager 全局信号）
 * @param isUploading 本页图片上传在途
 * @param isFetching 本页取回结果在途
 * @param isSubmitting 本页自己的提交闸门在途（页面侧已按 ownerId 过滤）
 * @param dotFeedbackEnabled 波纹点阵皮肤总开关
 */
internal fun resolveGenerationButtonSkin(
    isEnabled: Boolean,
    isOffline: Boolean,
    isConnecting: Boolean,
    isUploading: Boolean,
    isFetching: Boolean,
    isSubmitting: Boolean,
    dotFeedbackEnabled: Boolean,
): GenerationButtonSkin {
    val enabled = isEnabled && !isOffline
    val busy = !isOffline && (isConnecting || isUploading || isFetching || isSubmitting)
    return GenerationButtonSkin(
        enabled = enabled,
        busy = busy,
        skinActive = RippleSkinState.skinActive(dotFeedbackEnabled, enabled, busy),
        busyContainerTransparent =
            RippleSkinState.busyContainerTransparent(busy, dotFeedbackEnabled),
    )
}

/**
 * 下拉白边 override 的解析（v4.16.10 N1-2.1，纯函数供单测钉规格）。
 *
 * 修复的缺陷：`!pressUnified -> 0.18f` 原先没有 enabled 门控 —— 断连禁用态的白边会跳过
 * 禁用降档（深 0.10 / 浅 0.08）直接走启用档 0.18（亮约 80%），与
 * [sh.hnet.comfychair.ui.components.ripple.RippleFeedbackModifier] 的注释承诺
 *（"禁用还要 override 的调用点不存在"）矛盾。现在 [trailingEnabled] 为 false 时返回 null：
 * override 层不干预，让皮肤层的禁用降档分支生效。
 *
 * @param pressUnified 模块 2 安全阀（false = 回退态：静置 18% 恒定、打开也不提亮）
 * @param trailingEnabled 按钮是否可用（false = 离线禁用，返回 null 走禁用档白边）
 * @param showMenu 队列菜单是否展开（true = 打开态提亮 32%，仅在模块 2 开关开启时生效）
 * @return 白边 alpha 覆盖值；null = 不覆盖（皮肤层按主题/状态默认值取档）
 */
internal fun resolveTrailingBorderAlpha(
    pressUnified: Boolean,
    trailingEnabled: Boolean,
    showMenu: Boolean,
): Float? = when {
    // 禁用一律 null：override 层不干预，皮肤层 `!enabled` 分支给禁用降档（深 0.10 / 浅 0.08）。
    // showMenu 分支也要先过这关 —— 禁用态打不开菜单（保险丝），万一状态残留也不许拿启用档。
    !trailingEnabled -> null
    !pressUnified -> 0.18f
    showMenu -> TRAILING_BORDER_OPEN_ALPHA
    else -> null
}

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
private val TRAILING_ICON_SIZE = Dimensions.IconSize24

/**
 * 生成主按钮的本体形状（v4.16.10 N1）：**四角全 28dp 胶囊**。
 *
 * 背景：M3 SplitButton 默认 leading 形状是"左全圆 + 右仅 4dp 小圆角"的**不对称半胶囊**，
 * 而皮肤层（白边/渐变/点阵裁剪）一直按对称的 RoundedCornerShape(28.dp) 画 —— 启用态容器
 * 透明时本体不可见、两条曲线各自安好；禁用后 M3 Surface 按不对称本体画出灰块，右端与
 * 白边错开约 24.75dp（R7 取证）。现在把本体显式传成与皮肤同一条曲线：容器透明时零变化，
 * 禁用灰块与白边逐角重合。本体与白边**共用同一个常量**（皮肤层 shape 参数不再写字面量），
 * 两条曲线的同源关系由类型保证、由单测钉住（GenerationButtonShapeAlignmentTest）。
 */
internal val GENERATION_LEADING_BODY_SHAPE = RoundedCornerShape(Dimensions.Radius28)

/**
 * 下拉按钮的本体形状（v4.16.10 N1）：**四角全 18dp**（含按压态与 checked 展开态）。
 *
 * 背景：M3 默认 trailing 形状是"左 4dp + 右全圆"、checked 时整体 CircleShape，与皮肤层
 * RoundedCornerShape(18.dp) 白边在禁用态左端错开 14.75dp、右端白边悬在灰块外 9.25dp
 *（R7 取证）；展开态若落回 CircleShape 还会再错一次。统一传 18dp 后三条状态（静置/按压/
 * 展开）与白边都是同一条曲线。与皮肤层 shape 参数共用本常量（同源由类型保证）。
 */
internal val GENERATION_TRAILING_BODY_SHAPE = RoundedCornerShape(Dimensions.Radius18)

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
 * @param isSubmitting 提交闸门是否在途（忙碌态信号，视觉保留皮肤但整体降档；见参数注释）
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
    /**
     * v4.16.14：**本页自己的**提交闸门是否在途。
     *
     * 来源是 Activity 级共享的 `GenerationViewModel.isSubmitting`（四个生成页同一个实例），
     * 页面必须按 ownerId 过滤后再传（`isSubmitting && submitOwner == 本页 OWNER_ID`）——
     * 否则 A 页提交期间切到 B 页，B 页的空输入按钮会显示忙碌皮肤而不是真禁用灰底。
     *
     * 点击「生成」后 [isEnabled] 会因为 `!isSubmitting` 变 false —— 但这**不是**"提示词为空"
     * 那种真禁用，只是提交往返期间的忙碌态。组件内部无法从 isEnabled 反推，必须单独接进来；
     * 否则忙碌瞬间皮肤整层被拆掉，表现就是"点下生成、正在扩散的水波突然看不见"。
     */
    isSubmitting: Boolean = false,
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

    // 生成通知的运行时权限（Android 13+ POST_NOTIFICATIONS）。
    // 请求时机 = 用户点「生成」那一刻：即将开始一个可能较长的任务，"同意后可以在通知栏
    // 看进度"语义最自然（与更新弹窗点"立即更新"时请求同一套理由）；拒绝不阻塞生成。
    // 已授权 / Android 12- 时 launch 也不会弹（系统对拒绝后的重复请求同样静默），零成本。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权与否都不影响生成本身；被拒时通知静默跳过 */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
        // v5.x 皮肤（决策 1/2/4）：皮肤总开关开 + 非"真禁用" → 容器透明，双色渐变从底层露出。
        // v4.16.14：忙碌（提交/上传/取回/连接）与真禁用（提示词为空/离线）必须分开 ——
        // 忙碌态保留渐变 + 点阵 + 白边 + 投影（整体乘 busyDim 压暗），只有真禁用才回灰底零特效。
        // 判定抽成纯函数 resolveGenerationButtonSkin：离线一律真禁用；isSubmitting 必须是
        // 本页自己的提交（页面侧已按 ownerId 过滤，避免 A 页提交把 B 页的空输入按钮带亮）。
        val skin = resolveGenerationButtonSkin(
            isEnabled = isEnabled,
            isOffline = isOffline,
            isConnecting = isConnecting,
            isUploading = isUploading,
            isFetching = isFetching,
            isSubmitting = isSubmitting,
            dotFeedbackEnabled = dotFeedbackEnabled,
        )
        val leadingEnabled = skin.enabled
        val busy = skin.busy
        val skinActive = skin.skinActive
        val busyContainerTransparent = skin.busyContainerTransparent
        // 真禁用文字色沿用 M3 默认 token（onSurface@0.38）——不手写常量，避免主题版本漂移
        val defaultDisabledContentColor =
            ButtonDefaults.elevatedButtonColors().disabledContentColor
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
                // 首次点生成时请求通知权限（见上方 launcher 注释）；同步调用、不阻塞提交
                requestNotificationPermissionIfNeeded()
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
            // v4.16.10（N1）：本体形状显式对齐皮肤层 —— M3 默认 leading 是"左全圆 + 右 4dp"
            // 不对称半胶囊，禁用灰块会与 28dp 白边在右端错开约 24.75dp。三个状态（静置/按压）
            // 全传 28dp（leading 无 checked 态，checkedShape 传 null 走 shape），四角与白边
            // 逐角相等；同值形状下 M3 的圆角动画（rememberAnimatedShape）无跳变。
            shapes = SplitButtonShapes(
                shape = GENERATION_LEADING_BODY_SHAPE,
                pressedShape = GENERATION_LEADING_BODY_SHAPE,
                checkedShape = null,
            ),
            // U3：M3 自带 elevation 阴影去掉 —— SplitButton 默认 LeadingButtonShape 是
            // "左圆右方"半胶囊，M3 阴影会在按钮尾部露出直角灰块（实测浅色主题可见），
            // 且与皮肤层可控投影叠加后发脏。主按钮投影统一由 rippleFeedback 的 2 层投影承担。
            elevation = null,
            colors = ButtonDefaults.elevatedButtonColors(
                // v4.16.6（Q2）：原 `else -> onSurface@12%` 是死分支 —— material3 1.5.0-alpha11 的
                // ButtonColors.containerColor(enabled) 在 disabled 时读 disabledContainerColor 槽位、
                // 不读 containerColor，数值恰与 M3 token 默认一致所以视觉没露馅。现在把禁用色
                // 显式写进 disabled 槽位（意图与行为对齐）。
                // v4.16.14：忙碌态禁用容器改透明（让皮肤渐变露出）；真禁用仍是 M3 禁用灰。
                containerColor = if (skinActive) Color.Transparent else containerColor,
                disabledContainerColor = if (busyContainerTransparent) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                contentColor = contentColor,
                // v4.16.14：忙碌态文字用 onPrimary@0.92（渐变底上可读，静置态基本无差）；
                // 真禁用保持 M3 默认 onSurface@0.38（灰底灰字）——真禁用不许被忙碌态带亮。
                disabledContentColor = if (busyContainerTransparent) {
                    contentColor.copy(alpha = BUSY_CONTENT_ALPHA)
                } else {
                    defaultDisabledContentColor
                }
            ),
            interactionSource = leadingInteraction,
            modifier = Modifier
                .weight(1f)
                .height(Dimensions.ButtonHeight)
                // S2Probe（v4.16.7 取证）：布局摆放回写按钮窗口坐标（见 latestBounds 注释）
                .onGloballyPositioned { latestBounds = it.boundsInWindow() }
                .rippleFeedback(
                    interactionSource = leadingInteraction,
                    // 56dp 高的胶囊按钮：圆角半径必须取**高度一半（28dp）**才是真胶囊。
                    // CircleShape 是"四角百分比各取 50%"，在非正方形上会变成内切椭圆 ——
                    // 椭圆弧与真实按钮边缘最大偏离约 9dp，会让辉光看起来是条游离的弧线，
                    // 按钮两端的点阵/波纹也会被椭圆裁掉。48dp 方形图标按钮那种才适合 CircleShape。
                    // v4.16.10（N1）：本体与皮肤共用 GENERATION_LEADING_BODY_SHAPE 常量，
                    // 禁用灰块（本体）与白边（皮肤）是同一条曲线（同源由类型保证）。
                    shape = GENERATION_LEADING_BODY_SHAPE,
                    // v4.16.10（N1-2.2）：拆分"按钮禁用"与"皮肤开关"两个信号 —— enabled 只表达
                    // 按钮本身可不可点（false = 画禁用降档白边），皮肤总开关移到 skinEnabled
                    //（false = 完全不画，回 v4.16.6 之前的零绘制行为）。原先两个信号混用：
                    // 用户关掉波纹点阵后，可用按钮会画出禁用档白边（0.10 而非 0.22）。
                    enabled = leadingEnabled,
                    // v4.16.14：忙碌态（提交/上传/取回/连接）保留皮肤 —— 点下生成的瞬间，
                    // 正在扩散的水波不能因为 isEnabled 变 false 就整层消失。
                    busy = busy,
                    skinEnabled = dotFeedbackEnabled,
                    // 渐变色不缓存（决策 7）：accent 变了重组时这里现取新值，绘制层下一帧跟随
                    gradientColors = if (skinActive) {
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
                // 基线用 M3 按钮自身的内容样式 labelLarge（SemiBold / 行高 20sp），只换字号；
                // bodyLarge 是正文样式（Regular / 行高 24sp），会让主 CTA 文字变细（R2-F1）
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = Dimensions.ButtonTextSize
                )
            )
        }

        // 主↔下拉间距：v2 皮肤统一 10dp（三层操作行规格：主/下拉/齿轮等距）；
        // 回退态恢复 SplitButtonDefaults.Spacing（2dp）旧观感
        Spacer(modifier = Modifier.width(if (skinV2) Dimensions.SplitButtonGap else SplitButtonDefaults.Spacing))

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
                // v4.16.10（N1）：本体形状显式对齐皮肤层 —— M3 默认 trailing 是"左 4dp + 右全圆"、
                // checked 展开时整体 CircleShape，禁用灰块会与 18dp 白边左端错开 14.75dp、
                // 右端白边悬在灰块外 9.25dp；展开态若落回圆形还会再错一次。静置/按压/展开
                // 三个状态全传 18dp（首选方案：展开态保持圆角矩形，与白边同一条曲线），
                // 四角与白边逐角相等；同值形状下 M3 的圆角动画无跳变。
                shapes = SplitButtonShapes(
                    shape = GENERATION_TRAILING_BODY_SHAPE,
                    pressedShape = GENERATION_TRAILING_BODY_SHAPE,
                    checkedShape = GENERATION_TRAILING_BODY_SHAPE,
                ),
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
                    .size(
                        width = if (skinV2) Dimensions.TrailingButtonWidth else Dimensions.ButtonHeight,
                        height = Dimensions.ButtonHeight
                    )
                    .rippleFeedback(
                        interactionSource = trailingInteraction,
                        // 决策 8：方形按钮圆角 18dp（对齐基准方形版），不再是胶囊的 28dp
                        // v4.16.10（N1）：本体与皮肤共用 GENERATION_TRAILING_BODY_SHAPE 常量
                        //（含 checked 展开态，见上方 shapes 注释），两条曲线同源。
                        shape = GENERATION_TRAILING_BODY_SHAPE,
                        // v4.16.10（N1-2.2）：拆分"按钮禁用"与"皮肤开关"（同 leading 注释）
                        enabled = trailingEnabled,
                        skinEnabled = dotFeedbackEnabled,
                        // C2（模块 2）：静置白边与主按钮同源（不传 override 走皮肤层默认
                        // 深色 22% / 浅色 12%）；S2：打开态提亮到 32%。开关关闭回 v2 旧规格
                        // 18%（打开不提亮）；回退态（!skinV2）走皮肤层默认（深色 50%）。
                        // v4.16.10（N1-2.1）：override 解析抽成纯函数并补 enabled 门控 ——
                        // 断连禁用态返回 null，让皮肤层禁用降档（0.10/0.08）生效，
                        // 不再亮着启用档 0.18（修复前亮约 80%）。
                        borderAlphaOverride = if (skinV2) {
                            resolveTrailingBorderAlpha(pressUnified, trailingEnabled, showMenu)
                        } else {
                            null
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
