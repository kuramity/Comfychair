package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.ui.components.ImageViewer
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.ViewerExitCleanup
import sh.hnet.comfychair.ui.components.ViewerExitReason
import sh.hnet.comfychair.ui.components.ViewerExitRegistry
import sh.hnet.comfychair.ui.components.ViewerOverscroll
import sh.hnet.comfychair.ui.components.ViewerPageMotion
import sh.hnet.comfychair.ui.components.ViewerRect
import sh.hnet.comfychair.ui.components.ViewerSideBySide
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.util.viewerInitialPage
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerFailureCategory
import sh.hnet.comfychair.viewmodel.ViewerLoadPolicy
import sh.hnet.comfychair.viewmodel.ViewerMode
import sh.hnet.comfychair.viewmodel.ViewerShowState

/**
 * 并排进度吸附时长（ms，v4.16.2）。
 *
 * 松手后的档位吸附要"干脆"：太快（<180ms）显得生硬、看不清，太慢（>400ms）会拖
 * 出"松手还黏着手指"的滞后感。280ms + FastOutSlowIn 与顶栏按压反馈的直给手感和
 * 查看器翻页动画（Pager 默认 ~400ms 的短路版本）都合拍。
 */
private const val PROGRESS_SETTLE_MS = 280

/**
 * 把下载链接写进系统剪贴板，返回是否成功。
 *
 * 失败时调用方要给出可读提示 —— 不能让用户以为复制成功了、到浏览器里粘出来是空的。
 */
private fun copyUrlToClipboard(context: android.content.Context, url: String): Boolean = try {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comfychair_download", url))
    true
} catch (_: Exception) {
    false
}

/**
 * 查看器内部条目稳定键：与图库网格 / 去重口径**完全一致**（NUL 分隔，见
 * [GalleryMetadataCache.itemIdentityKey]）。P0-R3：之前查看器内部用下划线拼接
 * `"${promptId}_${filename}"`，图库网格改成 NUL 口径后，撞键只是从网格挪到了 Pager
 * （Compose Pager/LazyGrid 遇到重复 key 都抛 IllegalArgumentException）。
 * 这里统一到同一个函数，避免两套口径再次分叉。
 */
private fun viewerItemKey(promptId: String, filename: String): String =
    GalleryMetadataCache.itemIdentityKey(promptId, filename)

/**
 * 由缓存侧标记推断失败档位（H6，与 MediaViewerViewModel.inferLoadFailure 同口径）。
 *
 * 为什么 UI 层要自己再查一遍：GALLERY 模式的图片加载走 rememberLazyBitmap（UI 侧链路），
 * 它失败时 ViewModel 不一定记过 loadFailure（预取分支、缩略图链路都是独立失败点），
 * 而 404 标记一定在 MediaCache 上 —— 图库格子的"文件不存在"图标也是这么查的
 * （见 GalleryItemCard）。ViewModel 记录的档位作为非 404 场景的兜底。
 */
private fun inferFailureCategory(
    cacheKey: MediaCacheKey,
    fromViewModel: ViewerFailureCategory?,
    sourceMissing: Boolean = false
): ViewerFailureCategory = when {
    // 持久化的第三态优先：源已不存在时，即使本次没走到网络也必须是"文件不存在"
    sourceMissing -> ViewerFailureCategory.FILE_MISSING
    MediaCache.isFileMissing(cacheKey) -> ViewerFailureCategory.FILE_MISSING
    ConnectionManager.isOffline || ConnectionManager.clientOrNull == null ->
        ViewerFailureCategory.NETWORK
    else -> fromViewModel ?: ViewerFailureCategory.GENERIC
}

/** 失败档位 → 文案资源（H6 三档映射的 UI 层唯一入口） */
private fun failureMessageRes(category: ViewerFailureCategory): Int = when (category) {
    ViewerFailureCategory.FILE_MISSING -> R.string.viewer_load_failed_file_missing
    ViewerFailureCategory.NETWORK -> R.string.viewer_load_failed_network
    ViewerFailureCategory.GENERIC -> R.string.viewer_load_failed_generic
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onClose: () -> Unit,
    // B-23b 第三批（E1~E2）：图库整屏截图与目标格子矩形（window px）。
    // 由 GalleryScreen 在 launch 之前截好、经 BitmapCache 并行字段传到这里；
    // null = 单图模式 / 截图失败 / 开关关闭，E1~E5 全部禁用（纯黑 + 现有下滑关闭）
    gallerySnapshot: Bitmap? = null,
    galleryCellRect: ViewerRect? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val currentMetadata by viewModel.currentMetadata.collectAsStateWithLifecycle()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Layout constants - 8dp because navigationBarsPadding() adds ~24dp, totaling ~32dp like Workflow Editor
    val toolbarBottomPadding = 8.dp

    // 并排预览三级手势开关（v4.16.2，总体计划 D1~D5）：组合期读一次，置 false 完全回到
    // v4.15.x 的旧行为（未放大态捏合 = 缩小淡出关闭查看器）
    val threeLevelEnabled = remember { AppSettings.isGalleryThreeLevelEnabled(context) }

    // 下拉透出 + 飞回开关（v4.16.3，总体计划 E1~E5）：组合期读一次，置 false 完全回到
    // 纯黑背景 + 现有下滑关闭（无截图透出、无飞回）
    val dismissRevealEnabled = remember { AppSettings.isGalleryDismissRevealEnabled(context) }

    // N2 挖空开关（v4.16.8）：开启 = 图库截图已在像素空间挖好空置格（默认）；
    // 关闭 = 回退旧的覆盖层方案（在截图之上画黑矩形，即 E3 Canvas）
    val revealHolePunchEnabled = remember { AppSettings.isGalleryRevealHolePunch(context) }

    // ============================================================
    // G6（任务书 12.2）：退出相关状态与**唯一清理入口**
    // ============================================================
    // 为什么这些状态要从 pager 代码块里提到组合最外层：
    // 它们原先只在 pager 作用域可见，于是"退出清理"只能各写一份 —— 下滑与双指收缩
    // 在 ImageViewer 里各清各的，系统返回（BackHandler）则**完全够不到**档位进度与
    // 越界位移，退出时 pager 的 graphicsLayer 还带着那 0~18% 视口宽的平移量。
    // finish() 取的窗口转场快照因此是一幅"画歪了的图"，淡出时就像一层贴歪的残影。
    // 提到最外层后，三条路径统一调 exitViewer(reason)（任务书实施指令 1）。
    //
    // remember 仍以条目列表指纹为 key：删图会让 pager 整段重建，档位与越界状态
    // 照旧回到中性值（与提升前的行为一致）。
    // 指纹字符串 remember 住：items 引用不变的重组不再每次 map + joinToString
    val itemsKey = remember(uiState.items) {
        uiState.items.map { viewerItemKey(it.promptId, it.filename) }
            .joinToString("\u0001")
    }

    // 并排预览三级手势的进度状态（语义见 ViewerSideBySide：0 = 全屏；1 = 并排；1.5 = 临时缩小档）
    var sideProgress by remember(itemsKey) { mutableFloatStateOf(0f) }
    // 本次捏合的起始进度：ImageViewer 只上报"自手势起点累计的缩放倍率"，
    // 换算成绝对进度必须知道起点（全屏 0 / 并排 1 / 吸附动画被打断的中间值）
    var gestureBaseProgress by remember(itemsKey) { mutableFloatStateOf(0f) }
    var progressJob by remember(itemsKey) { mutableStateOf<Job?>(null) }
    var pagerSize by remember(itemsKey) { mutableStateOf(IntSize.Zero) }

    // G1（v4.16.11）：横向越界（rubber-band）位移，px、带符号。
    // 全屏与并排（队列）两态共用同一套状态与同一段回弹动画——
    // 手感一致由"同一实现"保证，而不是两套参数各调一遍。
    // 判定与位移映射是纯函数（ViewerOverscroll，JVM 单测钉住）。
    var overscrollPx by remember(itemsKey) { mutableFloatStateOf(0f) }       // 当前显示的越界位移
    var overscrollRawDrag by remember(itemsKey) { mutableFloatStateOf(0f) }  // 原始手指累计（未衰减）
    var overscrollSettleJob by remember(itemsKey) { mutableStateOf<Job?>(null) }

    // G2/G3（v4.16.12）：手指是否按在 pager 上（第一根按下 → 全部抬起）。
    // 翻页位移状态机里唯一的"手势相位"信号：手势进行中禁止任何程序化
    // 滚动/归零写回翻页位移（G3 规则 1/2），手势结束后才允许吸附。
    // 由 pager 那一层被动观察指针得到（不消费任何事件）。
    var pagerGestureActive by remember(itemsKey) { mutableStateOf(false) }

    // G4（任务书 11.4）：竖滑删除手势相位（页内 ImageViewer 上报）。
    // **轴间仲裁的屏幕侧一半**：删除手势激活期间冻结横向越界的增量消费与
    // fling 速度接管，也冻结"静止残留归零"的吸附前置归零 —— 组件侧另一半是
    // ImageViewer 纵向锁定后的事件全量 consume（pager 拿不到增量，吸附没有输入）。
    // 用 State 而不是普通 Boolean：越界 NestedScrollConnection 是 remember(pagerState)
    // 缓存的连接对象，闭包只能持有状态引用读最新值（每次组合重建的局部变量会被冻结）。
    val swipeDeleteActiveState = remember(itemsKey) { mutableStateOf(false) }

    // G6：页内组件（ImageViewer）把"图层复位"注册到这里，统一入口按同一顺序调用。
    // 系统返回这条路径不经过 ImageViewer 的手势代码，没有注册表就只能在 BackHandler
    // 里再抄一份清理 —— 正是任务书禁止的"各留一份"。
    val exitRegistry = remember { ViewerExitRegistry() }
    // 防重入：转场中的动画完成回调、pager 重建都可能再次请求退出。
    // 重复退出不得累积动画、不得重复 finish（G6 验收项"重复进出不得累积"）
    val exitStarted = remember { mutableStateOf(false) }

    /**
     * 查看器退出的唯一清理入口（G6，任务书 12.2 实施指令 1）。
     *
     * 三条退出路径 —— 下滑脱离（DISMISS_DRAG）、双指收缩（PINCH_IN）、
     * 系统返回（SYSTEM_BACK）—— 全部只调用本函数；reason **只进日志**：
     * 清理范围与 reason 无关（字段清单见 [ViewerExitCleanup.plan]，单测钉住同一性），
     * 退出转场也与 reason 无关（[ViewerExitCleanup.playsContentSideTransition] 恒 false，
     * 三条路径共用同一段窗口淡出，不允许谁再自播一段）。
     */
    fun exitViewer(reason: ViewerExitReason) {
        if (exitStarted.value) {
            if (BuildConfig.DEBUG) {
                Log.d("ViewerExit", "忽略重复退出请求 reason=$reason")
            }
            return
        }
        exitStarted.value = true
        if (BuildConfig.DEBUG) {
            Log.d(
                "ViewerExit",
                "退出清理 reason=$reason 字段=${ViewerExitCleanup.plan(reason).size} 项"
            )
        }
        // ① 取消屏幕级在途动画：档位吸附、越界回弹
        progressJob?.cancel()
        progressJob = null
        overscrollSettleJob?.cancel()
        overscrollSettleJob = null
        // ② 复位屏幕级位移与手势相位（ViewerExitCleanup.SCREEN_FIELDS）
        overscrollPx = 0f
        overscrollRawDrag = 0f
        pagerGestureActive = false
        // ③ 页内图层复位（ViewerExitCleanup.PAGE_FIELDS）：取消缩放/回位/收尾动画、
        //    置位退出防重入标志。三条路径共用这一份实现（ImageViewer 注册进来的同一个回调）
        exitRegistry.fire(reason)
        // ④ 退出转场**只有窗口层那一段**（res/anim/viewer_fade_out_shrink），三条路径共用。
        //    这里不再按 reason 决定"要不要再多播一段"：G6 的残影正是双指收缩自播了一段
        //    内容层淡出、播完又把标志位翻回不透明、随后窗口快照把这层图片淡到宿主页上。
        //    飞回落格那条（E4）仍由 ImageViewer 的 onFlyBackCompleted 抑制窗口转场，
        //    因为它的画面已经无缝落到格子上，与"淡出"无关。
        // ⑤ 关闭查看器。宿主页的交互与焦点由 Activity 栈恢复（finish 后宿主页重新获得
        //    窗口焦点与输入），不再额外做"恢复可点击"的补丁
        onClose()
    }

    // 手势/事件回调里要用最新一次的 exitViewer（局部函数每次组合重建）
    val currentExitViewer by rememberUpdatedState<(ViewerExitReason) -> Unit> { reason ->
        exitViewer(reason)
    }

    // Metadata bottom sheet state
    var showMetadataSheet by remember { mutableStateOf(false) }

    // Load metadata when sheet is shown
    LaunchedEffect(showMetadataSheet) {
        if (showMetadataSheet) {
            viewModel.loadMetadata()
        }
    }

    // Handle system back button - ensures onClose() is called with proper result
    // G6：系统返回走与手势完全相同的清理入口（旧实现直接 onClose()，
    // 页内缩放/收尾状态与 pager 平移量都没人复位）
    BackHandler {
        currentExitViewer(ViewerExitReason.SYSTEM_BACK)
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaViewerEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is MediaViewerEvent.ItemDeleted -> {
                    // Items list changed, pager will recompose with new key
                }
                is MediaViewerEvent.Close -> {
                    // G6：删空等自动关闭同样只走统一入口
                    currentExitViewer(ViewerExitReason.AUTO_CLOSE)
                }
            }
        }
    }

    // 容器背景 = 查看器唯一的黑化来源（G1，v4.16.11）。
    // 并排（队列）态页内不再画"档位宽度黑"补丁：两侧 15% 露边区、无邻页的一端、
    // 横向越界跟手露出的新带，全部靠这一层纯黑承接。任何"页内黑色遮挡层"都不再
    // 承担背景职责——只保留透出遮罩（下拉时按 alpha 淡出）与全屏态的满页黑
    // （盖住背后的图库截图）。根 Box 必须先于所有内容绘制，pager 平移之后
    // 露出的边带才看到的是黑。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Media content
        when (uiState.mode) {
            ViewerMode.GALLERY -> {
                if (uiState.items.isNotEmpty()) {
                    // Key the pager by items list identity to force recreation on deletion
                    // （G6：itemsKey 的计算提到组合最外层 —— 退出清理要以同一个 key
                    //  复位档位/越界状态，不能让它们留在只有手势够得到的作用域里）

                    // B-23b 第三批：下拉透出/飞回只对"进入查看器时的那一张"启用。
                    // 这里按**稳定键**记（不是索引）：删除条目后索引会移位，用索引判等会
                    // 把旧的透出截图挂到另一张图上。
                    val entryItemKey = remember {
                        uiState.items.getOrNull(uiState.currentIndex)?.let {
                            viewerItemKey(it.promptId, it.filename)
                        }
                    }

                    key(itemsKey) {
                        // P1-24：initialPage 每次重建都重新读 ViewModel 的 currentIndex。
                        // 修复前它被 remember 在 key() **之外**——删除条目会让 itemsKey 变化、
                        // 整段 pager 重建，而 initialPage 仍是"进入那一刻的页"，
                        // 于是删完一张就跳回进入页，而不是停在删除位置旁边。
                        // 现在 currentIndex 由 ViewModel 在删除时已调整到合理位置，
                        // 重建后直接用它；普通翻页不改变 itemsKey，pager 不会被重建。
                        //
                        // P0（v4.16.13 之后）：initialPage 的空列表边界统一走纯函数。
                        // `coerceIn(0, size - 1)` 在 size = 0 时会变成 `coerceIn(0, -1)`，
                        // 直接抛 IllegalArgumentException；外层虽然已用 isNotEmpty 拦住
                        // 组合，但这里再保证一次"任何输入都产出合法页号"。
                        val initialPage = viewerInitialPage(
                            currentIndex = uiState.currentIndex,
                            itemCount = uiState.items.size
                        )
                        val pagerState = rememberPagerState(
                            initialPage = initialPage,
                            pageCount = { uiState.items.size }
                        )

                        // ============================================================
                        // 下拉透出图库 + 位置飞回（B-23b 第三批，E1~E5）
                        // ============================================================
                        // 截图是"进入那一刻"的静态画面（客户已知情接受该局限），所以
                        // 透出只在"停留在进入时的那一页"时启用——翻页后截图与真实图库
                        // 脱节，回退纯黑 + 现有下滑关闭。视频页同样不启用（VideoPlayer
                        // 无分段遮罩链路，且初始页若是视频，透出会从播放器背后漏截图）
                        val revealItem = uiState.items.getOrNull(pagerState.currentPage)
                        // 用稳定键判断"是否还是进入时那一张"（P1-24：索引会因删除移位）
                        val revealItemKey = revealItem?.let { viewerItemKey(it.promptId, it.filename) }
                        val revealActive = dismissRevealEnabled &&
                            gallerySnapshot != null && galleryCellRect != null &&
                            revealItemKey != null && revealItemKey == entryItemKey &&
                            revealItem.isVideo == false

                        // ============================================================
                        // 并排进度 + 横向越界位移
                        // ============================================================
                        // 这六个状态（sideProgress / gestureBaseProgress / progressJob /
                        // pagerSize / overscrollPx / overscrollRawDrag / overscrollSettleJob /
                        // pagerGestureActive）已提到组合最外层，见本文件顶部
                        // "G6：退出相关状态与唯一清理入口"一节 —— 退出清理必须能在
                        // BackHandler（系统返回）这条路径里够到它们，不能留在本作用域里。
                        // 进度语义见 ViewerSideBySide：0 = 全屏；1 = 并排（当前页占视口 70%、
                        // 两侧各露 15%）；1.5 = 临时缩小档（55%）。捏合期间直接写值跟手，
                        // 松手由 animateProgressTo 做档位吸附。

                        // 新手指落下时接管正在回弹的位移：由当前位移反解 raw 作为起点，
                        // 否则位移会先跳回 0 再跟手（可见跳变）
                        fun takeOverOverscrollSettle() {
                            val job = overscrollSettleJob ?: return
                            if (!job.isActive) {
                                overscrollSettleJob = null
                                return
                            }
                            job.cancel()
                            overscrollSettleJob = null
                            overscrollRawDrag = ViewerOverscroll.rawDistanceForDisplacement(
                                displacement = overscrollPx,
                                maxDistance = ViewerOverscroll.maxDisplacementPx(
                                    pagerSize.width.toFloat()
                                ),
                                damping = ViewerOverscroll.DAMPING
                            )
                        }

                        // 松手回弹：位移回到 0，原始累计同步清零。tween（非弹簧）——
                        // 弹簧过冲就是验收里"二次回跳"的那种毛病。
                        // 已在回弹中直接返回：抬手事件与 fling 分发在同一帧里各调一次，
                        // 重启动画会让回弹从半途重新计时（看起来像卡了一下）
                        fun settleOverscroll() {
                            if (overscrollSettleJob?.isActive == true) return
                            overscrollSettleJob?.cancel()
                            val start = overscrollPx
                            overscrollRawDrag = 0f
                            if (start == 0f) {
                                overscrollPx = 0f
                                overscrollSettleJob = null
                                return
                            }
                            overscrollSettleJob = scope.launch {
                                animate(
                                    initialValue = start,
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        ViewerOverscroll.SETTLE_DURATION_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { value, _ -> overscrollPx = value }
                                overscrollPx = 0f
                            }
                        }

                        // E1/E2 背景截图层：铺满屏幕、1:1 对齐进查看器那一刻的屏幕像素
                        //（截图本身就是全屏，不缩放不裁剪）。初始态页内黑底遮罩
                        //（ImageViewer drawBehind）alpha=1 把它盖成纯黑（E1）；
                        // 下拉偏离原位约 10% 后遮罩快速淡到 0.2，图库从背后透出（E2）。
                        //
                        // G1：截图只在**全屏态**参与画面。并排（队列）态一旦露边，
                        // 截图就会从没有邻页的那一端 / 越界新带里漏出真实图库网格
                        // （E1/E2 的穿帮）。所以并排进度一离开 0，截图整层透明——
                        // 露出区域交给屏幕根容器的纯黑背景，与页内补丁宽度彻底无关。
                        // 同时截图跟随越界位移一起平移：越界露出的新带必须是容器黑，
                        // 不能是"截图被推走后仍留在原地"的图库画面。
                        if (revealActive && gallerySnapshot != null && galleryCellRect != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // sideProgress 读在 layer lambda 内：捏合每帧只更新
                                        // 图层属性，不触发整页重组
                                        alpha = if (sideProgress == 0f) 1f else 0f
                                        translationX = overscrollPx
                                    }
                            ) {
                                // asImageBitmap 每次重组都会新建包装对象，remember 住同一份 ImageBitmap
                                val snapshotBitmap = remember(gallerySnapshot) { gallerySnapshot.asImageBitmap() }
                                Image(
                                    bitmap = snapshotBitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // E3：目标格子挖黑占位——N2 挖空开关关闭时的旧覆盖层路径。
                                // 挖空开启（默认）时截图里那格已经是"空置格子"，这里不再
                                // 画黑矩形，避免"空置格 + 黑块"叠两层
                                if (!revealHolePunchEnabled) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(galleryCellRect.left, galleryCellRect.top),
                                            size = Size(galleryCellRect.width, galleryCellRect.height)
                                        )
                                    }
                                }
                            }
                        }

                        // Sync pager state with ViewModel (user swipes)
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page ->
                                    if (page != uiState.currentIndex) {
                                        viewModel.setCurrentIndex(page)

                                        // Update cache priorities based on new position
                                        val allKeys = uiState.items.map {
                                            MediaCacheKey.of(it.promptId, it.filename, it.type)
                                        }
                                        MediaCache.updateNavigationPriorities(page, allKeys)
                                    }
                                }
                        }

                        // ============================================================
                        // 翻页位移状态机 · 静止残留归零（N5 治本；G3 规则 2 的唯一例外）
                        // ============================================================
                        // pager 静止却带着非零翻页进度 = 双指接管取消了拖动、又没有 settle
                        // （N5Probe 实测：残留值会一直留在那里）。归零只在**完全静止**时执行：
                        // 手势按着、或拖动/惯性/程序化滚动在途，一律不碰 —— 那才是"手势中途
                        // 重置翻页位移"，正是 G3 要根治的事（旧实现在捏合起点就调 scrollToPage，
                        // 拖动未释放时它会挂在 pager 的 mutator 队列上，恢复时就是一次位移跳变）。
                        // 渲染侧不依赖这次归零（displayPageFraction 在静止时直接按 0 显示），
                        // 它是治本兜底：把状态修好，下一次拖动才始终从整页起步。
                        LaunchedEffect(pagerState) {
                            snapshotFlow {
                                Triple(
                                    pagerState.isScrollInProgress,
                                    // G4：竖滑删除激活同样算"手势占用"——静止归零这条
                                    // 吸附前置动作在删除手势期间必须放弃（轴间仲裁）
                                    pagerGestureActive || swipeDeleteActiveState.value,
                                    pagerState.currentPageOffsetFraction
                                )
                            }.collect { (scrolling, gestureActive, fraction) ->
                                if (ViewerPageMotion.mayNormalizeStalePager(
                                        gestureActive = gestureActive,
                                        scrollInProgress = scrolling,
                                        pagerFraction = fraction
                                    )
                                ) {
                                    pagerState.scrollToPage(pagerState.currentPage)
                                }
                            }
                        }

                        // ============================================================
                        // 并排预览三级手势（D1~D5，v4.16.2）
                        // ============================================================
                        // 进度状态（sideProgress / gestureBaseProgress / progressJob /
                        // pagerSize）与越界位移状态已在背景截图之前声明——截图层的
                        // 可见性与平移由它们驱动，不能前向引用。

                        // 布尔派生：只有"越过 0.5"时才让分页内容重组，捏合中的每帧 Float 变化不穿透
                        val sideBySideActive by remember {
                            derivedStateOf { sideProgress > ViewerSideBySide.SETTLE_THRESHOLD }
                        }

                        fun cancelProgressJob() {
                            progressJob?.cancel()
                            progressJob = null
                        }

                        // 档位吸附 / 回全屏的统一动画出口
                        fun animateProgressTo(target: Float) {
                            cancelProgressJob()
                            if (sideProgress == target) return
                            progressJob = scope.launch {
                                animate(
                                    initialValue = sideProgress,
                                    targetValue = target,
                                    animationSpec = tween(
                                        PROGRESS_SETTLE_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { value, _ -> sideProgress = value }
                            }
                        }

                        // D4："点哪张图哪张全屏"。露边页要"先翻到它 + 同时放大回全屏"——
                        // 两件事并行，只做其中一件都会先跳正再缩放，观感断裂
                        fun expandPage(targetPage: Int) {
                            // P0：列表可能在本页手势回调的生命周期内被删空（队列删空 →
                            // 自动关闭前的一帧）。空列表直接不再翻页，绝不让
                            // `coerceIn(0, size - 1)` 在 size = 0 时抛异常。
                            if (uiState.items.isEmpty()) return
                            val safe = targetPage.coerceIn(0, uiState.items.size - 1)
                            if (safe == pagerState.currentPage) {
                                animateProgressTo(0f)
                                return
                            }
                            cancelProgressJob()
                            progressJob = scope.launch {
                                launch { pagerState.animateScrollToPage(safe) }
                                animate(
                                    initialValue = sideProgress,
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        PROGRESS_SETTLE_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { value, _ -> sideProgress = value }
                            }
                        }

                        // 供 ImageViewer 读取的"当前页占视口比例"：lambda 形式让捏合动画每帧只更新
                        // graphicsLayer、不触发整页重组（三页同时 60fps 重组会掉帧）
                        val sideScaleProvider: (() -> Float)? = if (threeLevelEnabled) {
                            remember { { ViewerSideBySide.pageFraction(sideProgress) } }
                        } else {
                            null
                        }

                        // 捏合三段回调（与页无关，整段只建一次）
                        val sideStartHandler: (() -> Unit)? = if (threeLevelEnabled) {
                            {
                                cancelProgressJob()
                                gestureBaseProgress = sideProgress
                                // G1：越界位移还挂着时落第二指捏合——先把 pager 平移
                                // 回吸附位（260ms 回弹），否则整段捏合期间页面都带着
                                // 那笔横向偏移，并排几何会算错位置
                                settleOverscroll()
                                // N5Probe（仅 debug 包输出）：手势起点快照
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "sideStart base=$gestureBaseProgress " +
                                            "offset=${pagerState.currentPageOffsetFraction} " +
                                            "page=${pagerState.currentPage} " +
                                            "scrolling=${pagerState.isScrollInProgress}"
                                    )
                                }
                                // G2/G3（v4.16.12）：这里**不再**在手势中途归零翻页位移。
                                // 历史实现（v4.16.8 N5 返工）在捏合起点调 scrollToPage：
                                // 拖动未释放时该调用会挂在 pager 的 mutator 队列上十几秒
                                // （N5Probe 实测），一旦恢复执行就是一次"手势中途的位移
                                // 重置"——正是 G3 禁止的第二种回抖来源。现在：
                                // ① 手势期间的位移口径由 ViewerPageMotion.displayPageFraction
                                //    统一处理（pager 静止时残留值不参与显示，不会把整页推出中心）；
                                // ② 真正的归零放到「手指抬起且完全静止之后」的
                                //    LaunchedEffect（见上方"静止残留归零"），只执行一次。
                                // 本回调每次手势只触发一次（ImageViewer 的
                                // wasSideBySidePinch 门控）。
                            }
                        } else {
                            null
                        }
                        val sideHandler: ((Float) -> Unit)? = if (threeLevelEnabled) {
                            { gestureScale ->
                                cancelProgressJob()
                                sideProgress = ViewerSideBySide.progressFromGesture(
                                    gestureBaseProgress, gestureScale
                                )
                                // N5Probe（仅 debug 包输出）：并排进度轨迹，配合
                                // start/settle 三点定位复合手势下进度链路的行为
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "sideUpdate base=$gestureBaseProgress " +
                                            "scale=$gestureScale " +
                                            "progress=$sideProgress " +
                                            "offset=${pagerState.currentPageOffsetFraction}"
                                    )
                                }
                            }
                        } else {
                            null
                        }
                        val sideSettleHandler: ((Float) -> Unit)? = if (threeLevelEnabled) {
                            {
                                // 松手吸附：≥0.5 → 并排 1.0；<0.5 → 全屏 0。并排态继续合拢
                                // （进度 1.0~1.5 的临时缩小档）同样吸附回 1.0 —— 绝不退出图库
                                // N5Probe（仅 debug 包输出）：松手瞬间进度与吸附目标
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "settle progress=$sideProgress " +
                                            "target=${ViewerSideBySide.settleProgress(sideProgress)} " +
                                            "offset=${pagerState.currentPageOffsetFraction}"
                                    )
                                }
                                animateProgressTo(ViewerSideBySide.settleProgress(sideProgress))
                            }
                        } else {
                            null
                        }

                        // 并排是图片专属体验：翻到视频页时把进度收回全屏。视频层没有档位缩放，
                        // 停在并排会变成"全尺寸视频 + 露边"，且点击语义也会错乱
                        LaunchedEffect(pagerState.currentPage, sideBySideActive) {
                            if (sideBySideActive &&
                                uiState.items.getOrNull(pagerState.currentPage)?.isVideo == true
                            ) {
                                animateProgressTo(0f)
                            }
                        }

                        // ============================================================
                        // 横向越界（rubber-band）机制（G1，v4.16.11）
                        // ============================================================
                        // 全屏与并排（队列）两态共用这一套：pager 在两端继续外拉时，
                        // 增量进 overscrollRawDrag，经纯函数非线性衰减成位移
                        // overscrollPx，写在 pager 的 graphicsLayer 平移上；露出区域
                        // 由屏幕根容器的纯黑背景承接（见本文件根 Box 的 background）。
                        // 松手回弹到吸附位（位移回 0），不交给弹簧（避免二次回跳）。
                        //
                        // 页数取最新值：连接对象按 remember(pagerState) 缓存，直接读
                        // uiState.items.size 会冻结在首次组合时的页数（删除条目后会错判边界）
                        val currentPageCount by rememberUpdatedState(uiState.items.size)

                        // 只吃手指拖动（UserInput）：pager 自身吸附/fling 产生的增量走
                        // SideEffect，不能被当成越界（否则翻页途中会出现假位移）
                        val overscrollConnection = remember(pagerState) {
                            object : NestedScrollConnection {
                                override fun onPreScroll(
                                    available: Offset,
                                    source: NestedScrollSource
                                ): Offset {
                                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                                    if (!available.x.isFinite() || available.x == 0f) return Offset.Zero
                                    // G4 轴间仲裁：竖滑删除手势在途 → 越界机制完全让位
                                    // （回弹接管、增量消费、fling 吞速一概不参与）。
                                    // 正常情况下页内 consume 已让 pager 收不到增量，
                                    // 这里是显式第二道闸：多指抖动等异常输入不得让两轴同动
                                    if (swipeDeleteActiveState.value) return Offset.Zero
                                    takeOverOverscrollSettle()
                                    if (overscrollRawDrag == 0f) return Offset.Zero
                                    // 回拖：先把已产生的越界位移吃回来，吃到零点后
                                    // 剩余量由 reverseStep 的 consumed 留给 pager 翻页
                                    val step = ViewerOverscroll.reverseStep(
                                        rawDrag = overscrollRawDrag,
                                        delta = available.x,
                                        maxDistance = ViewerOverscroll.maxDisplacementPx(
                                            pagerSize.width.toFloat()
                                        ),
                                        damping = ViewerOverscroll.DAMPING
                                    )
                                    overscrollRawDrag = step.rawDrag
                                    overscrollPx = step.displacement
                                    return Offset(step.consumed, 0f)
                                }

                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource
                                ): Offset {
                                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                                    if (!available.x.isFinite() || available.x == 0f) return Offset.Zero
                                    // G4 轴间仲裁：同 onPreScroll（第二道闸）
                                    if (swipeDeleteActiveState.value) return Offset.Zero
                                    // 只有"已在两端仍未消费掉"的增量才算越界：页间正常翻页的
                                    // 增量 pager 自己吃掉了，available 为 0，走不到这里
                                    if (!ViewerOverscroll.isOutOfBoundsDirection(
                                            pagerState.currentPage,
                                            currentPageCount,
                                            available.x
                                        )
                                    ) {
                                        return Offset.Zero
                                    }
                                    takeOverOverscrollSettle()
                                    val step = ViewerOverscroll.accumulateOutward(
                                        rawDrag = overscrollRawDrag,
                                        delta = available.x,
                                        maxDistance = ViewerOverscroll.maxDisplacementPx(
                                            pagerSize.width.toFloat()
                                        ),
                                        damping = ViewerOverscroll.DAMPING
                                    )
                                    overscrollRawDrag = step.rawDrag
                                    overscrollPx = step.displacement
                                    // 全部消费：平台 overscroll effect 已关闭
                                    //（overscrollEffect = null），这里消费是为了让回拖时
                                    // 增量不再被重复计入 pager
                                    return Offset(step.consumed, 0f)
                                }

                                override suspend fun onPreFling(available: Velocity): Velocity {
                                    // G4 轴间仲裁：删除手势在途不接管 fling（详见 onPreScroll 注释）
                                    if (swipeDeleteActiveState.value) return Velocity.Zero
                                    if (overscrollRawDrag == 0f && overscrollPx == 0f) {
                                        return Velocity.Zero
                                    }
                                    // G2 补漏（v4.16.13）：吞不吞甩速要看**方向**，不能一刀切。
                                    // 旧实现只要处在越界状态就 return available，把速度全吞了，
                                    // 于是"越界后快速回甩"（甩向页内、pager 本来能翻页）也没有
                                    // 惯性，只能靠拖动过半吸附 —— 与拖拽路径 reverseStep 的口径
                                    // （同向不消费、越过零点把余量交 pager）自相矛盾。
                                    // 纯函数判定见 ViewerOverscroll.shouldSwallowFlingVelocity。
                                    // 位移回弹与方向无关：两种情况都要 settle 回吸附位。
                                    val swallow = ViewerOverscroll.shouldSwallowFlingVelocity(
                                        rawDrag = overscrollRawDrag,
                                        displacement = overscrollPx,
                                        velocityX = available.x
                                    )
                                    settleOverscroll()
                                    return if (swallow) {
                                        // 继续往外甩：两端没有可翻的页，速度不能交给 pager
                                        available
                                    } else {
                                        // 甩向页内：速度整份交回 pager，走正常惯性跨页
                                        Velocity.Zero
                                    }
                                }
                            }
                        }

                        // ============================================================
                        // 翻页位移状态机 · 松手后的惯性滑行（G2，v4.16.12）
                        // ============================================================
                        // 现状根因（框架字节码取证，Compose Foundation 1.10）：
                        // PagerDefaults.flingBehavior 的默认参数是
                        // `PagerSnapDistance.atMost(1)` —— 松手后滑行最多只吸附相邻一张，
                        // 所以"快速甩动不产生跨页滑行"。速度本身没被丢弃（框架的
                        // spline 摩擦衰减一直在算），被丢弃的是**跨页数**。
                        //
                        // 改法：保留框架的摩擦衰减轨迹（rememberSplineBasedDecay，与系统
                        // 控件同一套物理），只把"跨页上限 + 吸附目标"换成纯函数：
                        //   · 跨页上限 = ViewerPageMotion.flingPageCount(松手速度, 页距)
                        //   · 吸附目标 = ViewerPageMotion.snapTargetPage(衰减终点, ...)
                        // 慢速时跨页上限仍是 1 → 逐张手感与改动前完全一致。
                        // 惯性档位（v4.16.14 / 复核 GATE-1）：跨页惯性只保留在**当前真的处于
                        // 队列（并排三张）态**时 —— 判据是 sideBySideActive（sideProgress
                        // 越过 0.5 的派生状态），不是"队列功能开关" threeLevelEnabled。
                        //
                        // 为什么不能用 threeLevelEnabled：它只是设置项（默认 true），
                        // 而查看器初始态永远是全屏（sideProgress = 0）。绑设置项等于
                        // "默认用户的全屏单图也保留多张惯性"，用户可见现象一条都没消除。
                        // 这与既有队列态口径一致（见下方竖滑删除的
                        // `threeLevelEnabled && sideBySideActive`）。
                        //
                        // allowInertia 用 lambda 注入且读的是 State（derivedStateOf）：
                        // 每次 calculateTargetPage 调用都取当时的值，所以 remember 的
                        // key 不需要带模式（带了反而会重建吸附对象，把在途 fling 的
                        // 回调换成另一个实例）。
                        val pagerSnapDistance = remember(pagerState) {
                            ViewerPagerSnapDistance(
                                pageCountProvider = { pagerState.pageCount },
                                allowInertia = {
                                    pagerInertiaAllowed(threeLevelEnabled, sideBySideActive)
                                }
                            )
                        }
                        val pagerFlingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            pagerSnapDistance = pagerSnapDistance,
                            decayAnimationSpec = rememberSplineBasedDecay()
                        )

                        HorizontalPager(
                            state = pagerState,
                            flingBehavior = pagerFlingBehavior,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { pagerSize = it }
                                // 越界位移：读在 layer lambda 内，拖动每帧只更新图层属性。
                                // 放在最外层，让绘制与命中检测（嵌套滚动、抬手检测）一起平移
                                .graphicsLayer { translationX = overscrollPx }
                                .nestedScroll(overscrollConnection)
                                // 抬手即回弹（被动观察，不消费任何事件）：越界回弹不能只
                                // 依赖 fling 分发——零速抬手时 fling 是否下发由框架内部
                                // 决定，位移会冻在半路。这一层只盯"最后一根手指抬起"，
                                // 与 onPreFling（消费甩速、避免两端还带着速度翻页）互补。
                                // G2/G3（v4.16.12）：同一观察点顺带维护手势相位——按下即
                                // 关闭所有程序化写回翻页位移的入口，全部抬起才允许吸附/归零
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        pagerGestureActive = true
                                        try {
                                            do {
                                                val event = awaitPointerEvent()
                                            } while (event.changes.any { it.pressed })
                                        } finally {
                                            // 松手与手势取消（被双指接管）都走这里：
                                            // 之后才允许吸附（G3 规则 1）
                                            pagerGestureActive = false
                                        }
                                        settleOverscroll()
                                    }
                                },
                            // 平台 overscroll（Android 12+ 拉伸）关闭：越界跟手完全由上面
                            // 的 rubber-band 接管，两端手感与全屏/并排两态严格一致
                            overscrollEffect = null,
                            // 并排露边必须让 pager 提前组合相邻页（API 名 beyondViewportPageCount，
                            // 即派工单里的 beyondBoundsPageCount），否则两侧露出来的是黑底
                            beyondViewportPageCount = if (threeLevelEnabled) 1 else 0,
                            // P0：key 与页内容一律用 getOrNull —— 删除/清空与 pager 测量之间
                            // 可能差一帧，此时旧页索引会大于新列表长度。直接 `items[index]`
                            // 就是一次 IndexOutOfBoundsException（线上表现为"进图库瞬间闪退"）。
                            key = { index ->
                                uiState.items.getOrNull(index)?.let {
                                    viewerItemKey(it.promptId, it.filename)
                                } ?: "missing-$index"
                            }
                        ) { page ->
                            val item = uiState.items.getOrNull(page)
                                ?: uiState.items.firstOrNull()
                                ?: return@HorizontalPager
                            val isCurrentPage = page == pagerState.currentPage
                            val cacheKey = MediaCacheKey.of(item.promptId, item.filename, item.type)
                            // 本页的稳定键：与 entryItemKey 比对，判断"是不是进入时那一张"
                            //（P1-24：不能再用页索引，删除会让索引移位）
                            val pageItemKey = viewerItemKey(item.promptId, item.filename)
                            val isEntryPage = pageItemKey == entryItemKey

                            // Lazy load bitmap from cache
                            //
                            // 第三态（源已不存在）：不再请求原图，直接拿本机 720 档缓存 ——
                            // 画面是"清晰的低清副本"，不是加载中的模糊占位；右上角另有「已删除」角标。
                            // 这里用**条目持久化字段**而不是上面含本次 404 的 sourceMissing：
                            // 首次打开时还不知道已删除（也要允许那一次请求去确认 404），
                            // 确认后 fetchViewerImage 会回落到本机缓存；若此时把 thumbnail 翻成 true，
                            // remember 会重建状态、可能把刚显示出来的缓存图丢掉。
                            val sourceMissing = item.sourceMissing || MediaCache.isFileMissing(cacheKey)
                            val (bitmap, isBitmapLoading) = rememberLazyBitmap(
                                cacheKey = cacheKey,
                                isVideo = item.isVideo,
                                subfolder = item.subfolder,
                                type = item.type,
                                thumbnail = item.sourceMissing,
                                localOnly = item.sourceMissing
                            )

                            // Video URI only needed for current page (VideoPlayer)
                            val videoUri = if (isCurrentPage && item.isVideo) uiState.currentVideoUri else null

                            // 原图还没到之前，先用列表里那张缩略图占位：
                            // 超大图要等服务端压缩（那张 204 MB 的约 9 秒），这段时间屏幕上
                            // 不该只有一个转圈 —— 先看到（模糊的）画面，原图到了再替换
                            val placeholder = if (bitmap == null) MediaCache.getThumbnail(cacheKey) else null
                            val displayBitmap = bitmap ?: placeholder

                            // Show loading if content not available yet
                            val showLoading = if (isCurrentPage) {
                                uiState.isLoading && (if (item.isVideo) videoUri == null else displayBitmap == null)
                            } else {
                                isBitmapLoading && displayBitmap == null
                            }

                            // 失败态判定（H1-a/H1-b）：内容缺失且**确定不在加载**才算失败。
                            // 只对当前页生效 —— 非当前页还没轮到加载，显示"失败"会误导；
                            // 视频页尤其关键：fetchVideoUri 失败后 currentVideoUri 永远是 null，
                            // 修复前这一页无条件转圈（死转圈）。
                            val isBusy = uiState.isLoading || isBitmapLoading
                            val showState = if (isCurrentPage) {
                                val hasContent = if (item.isVideo) {
                                    videoUri != null || displayBitmap != null
                                } else {
                                    displayBitmap != null
                                }
                                ViewerLoadPolicy.resolveShowState(item.isVideo, hasContent, isBusy)
                            } else {
                                ViewerShowState.LOADING
                            }
                            val failureCategory = if (showState == ViewerShowState.FAILED) {
                                inferFailureCategory(cacheKey, uiState.loadFailure, sourceMissing)
                            } else {
                                null
                            }
                            // 视频页"缩略图已解出、播放地址没拿到"的失败：缩略图保留，
                            // 叠加一条可重试的失败提示。整屏换成失败占位会把已解出的首帧也藏掉。
                            val videoOverlayFailure = if (
                                isCurrentPage && item.isVideo && !isBusy &&
                                videoUri == null && displayBitmap != null
                            ) {
                                inferFailureCategory(cacheKey, uiState.loadFailure)
                            } else {
                                null
                            }

                            // 并排几何（D2/D3）：页宽保持全屏（布局与命中区域都不变），只做
                            // "整页按页距向内平移"这一步 —— 当前页 k=0 不动，左右邻页各向内收
                            // (1 - 档位比例) × 屏宽，配合图片自身的档位缩放，最终呈现
                            // "中间 70% + 两侧各露 15%"。全部在 graphicsLayer lambda 内读
                            // sideProgress：捏合每帧只更新 layer 属性，不重组分页内容
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        if (threeLevelEnabled) {
                                            val scale = ViewerSideBySide.pageFraction(sideProgress)
                                            // k = 本页相对"滚动中的当前位置"的页距（-1 左邻 / 0 当前 / +1 右邻）
                                            // G3（v4.16.12）根因与修法：
                                            // 旧实现只要处于并排态就无条件把翻页进度按 0 计算
                                            // （`if (sideProgress > 0f) 0f`）。但 pager 自己仍在按
                                            // 实时进度布局：currentPage 在进度 50% 处翻转的那一帧，
                                            // 整数页距跳 1、实时进度补 -1，两者本该抵消；被强制成
                                            // 0 之后只剩整数页距那一跳，整段页平移瞬移 0.3 个视口宽
                                            // （档位系数 0.7）——这就是"自第 i 张往第 i+1 张拖、
                                            // 越过约一半时前一图突然弹回并占据大半视口、把后图挤回"。
                                            // 现在一律用实时进度（手势进度与显示单调一致）；
                                            // 唯一返回 0 的情况是 pager **静止**却带着非零进度
                                            // （N5 的取消残留），那时残留值本来就不该参与显示。
                                            val offsetFraction = ViewerPageMotion.displayPageFraction(
                                                pagerFraction = pagerState.currentPageOffsetFraction,
                                                scrollInProgress = pagerState.isScrollInProgress
                                            )
                                            val pageDistance = (page - pagerState.currentPage) -
                                                offsetFraction
                                            translationX = pageDistance * size.width * (scale - 1f)
                                        }
                                    }
                                    .clipToBounds()
                            ) {
                                // D4 并排态点击：点在居中页 → 放大回全屏；点在露边页 → 先翻到
                                // 该页再同步放大。全屏态保持 null，单击继续走既有 onSingleTap
                                // （保留双击缩放：并排回调非 null 时 ImageViewer 会跳过双击判定）
                                val sideTap: ((Float, Float) -> Unit)? =
                                    if (threeLevelEnabled && sideBySideActive) {
                                        { imageLocalX, _ ->
                                            // 物理落点可能是任意一页（露边的邻页与当前页的布局
                                            // 区域互相重叠，右侧那页还画在上层）。ImageViewer 上报的
                                            // 是"图片被档位缩放之后"的层内坐标，必须连同缩放与页位移
                                            // 一起反算回视口坐标 —— 少算缩放这一步会让落点整体偏向中间，
                                            // 表现就是"点露边页只放大不翻页"（纯函数里带单测钉住）
                                            val fraction =
                                                ViewerSideBySide.pageFraction(sideProgress)
                                            // 与页平移用同一个进度口径（displayPageFraction），
                                            // 否则残留值会让命中分区与实际画面对不上
                                            val pageDistance = (page - pagerState.currentPage) -
                                                ViewerPageMotion.displayPageFraction(
                                                    pagerFraction = pagerState.currentPageOffsetFraction,
                                                    scrollInProgress = pagerState.isScrollInProgress
                                                )
                                            val screenX = ViewerSideBySide.viewportXFromImageLocal(
                                                imageLocalX = imageLocalX,
                                                viewportWidth = pagerSize.width.toFloat(),
                                                fraction = fraction,
                                                pageDistance = pageDistance
                                            )
                                            val zone = ViewerSideBySide.tapZone(
                                                screenX,
                                                pagerSize.width.toFloat(),
                                                sideProgress
                                            )
                                            // zone：-1 = 左邻、0 = 居中（当前页）、+1 = 右邻
                                            expandPage(pagerState.currentPage + zone)
                                        }
                                    } else {
                                        null
                                    }

                                MediaContent(
                                    isVideo = item.isVideo,
                                    bitmap = displayBitmap,
                                    videoUri = videoUri,
                                    isLoading = showLoading,
                                    onSingleTap = { viewModel.toggleUiVisibility() },
                                    cacheKey = cacheKey,
                                    failureCategory = failureCategory,
                                    // 第三态且本机无缓存：失败占位必须说清「原图已被删除，且本机无缓存」
                                    sourceMissingNoCache = sourceMissing,
                                    onRetry = { viewModel.retryCurrentItem() },
                                    // G6：下滑/双指收缩的退出都交给屏幕级唯一清理入口
                                    onViewerClose = currentExitViewer,
                                    exitRegistry = exitRegistry,
                                    // E1~E5 只挂在进入时的那一页图片上（revealActive 已含 currentPage == entryPage；
                                    // 翻页动画中的邻页 page 不同）
                                    flyBackCellRect = if (revealActive && isEntryPage) {
                                        galleryCellRect
                                    } else {
                                        null
                                    },
                                    // G1：并排（队列）态页内不画整页黑（背景由容器负责）
                                    sideBySideActive = sideBySideActive,
                                    // E4：飞回已无缝落格，关闭时抑制 Activity 层缩小淡出
                                    onFlyBackCompleted = if (revealActive && isEntryPage) {
                                        { MediaViewerActivity.suppressNextExitTransition = true }
                                    } else {
                                        null
                                    },
                                    // N6（v4.16.9，方案 A2）：放大态贴边继续同向拖 → 主动翻页，
                                    // direction 是 pager 页方向（+1 下一张 / -1 上一张）。
                                    // coerceIn 兜住边界页（第 0 页上一张 / 末页下一张的
                                    // animateScrollToPage 目标不变、等于空操作）；视频页不传
                                    // （视频层无放大平移链路）。翻页后新页由新组合的
                                    // ImageViewer 实例天然回到 scale=1、居中
                                    onPanEdgeFling = if (item.isVideo) {
                                        null
                                    } else {
                                        { direction ->
                                            scope.launch {
                                                // P0：回调触发时列表可能已被删空。空列表直接
                                                // 放弃翻页（`coerceIn(0, -1)` 会抛异常）
                                                val lastIndex = uiState.items.size - 1
                                                if (lastIndex < 0) return@launch
                                                val target = (pagerState.currentPage + direction)
                                                    .coerceIn(0, lastIndex)
                                                pagerState.animateScrollToPage(target)
                                            }
                                        }
                                    },
                                    // 并排预览三级手势（仅 GALLERY 图片页接线；视频页不传，
                                    // 由上面的 LaunchedEffect 把进度收回全屏）。
                                    // 这里按 item.isVideo 再门控一次：视频页在播放地址还没拿到的
                                    // 瞬间显示的是缩略图（走 ImageViewer 分支），若把并排回调传进去，
                                    // 捏合会先撑开并排、紧接着被上面那个 effect 拽回全屏，两下打架
                                    onSideBySideStart = if (item.isVideo) null else sideStartHandler,
                                    onSideBySide = if (item.isVideo) null else sideHandler,
                                    onSideBySideSettle = if (item.isVideo) null else sideSettleHandler,
                                    onSideBySideTap = if (item.isVideo) null else sideTap,
                                    sideBySideScale = if (item.isVideo) null else sideScaleProvider,
                                    // ============================================================
                                    // G4（任务书 11.4）：队列模式竖滑快捷删除接线
                                    // ============================================================
                                    // 只挂给「当前在架的图片页」：
                                    // - 邻页不挂 —— 露边上的纵滑删除目标说不清（误删邻居），
                                    //   维持既有语义（邻页纵向手势不消费、翻页优先）；
                                    // - 视频页不挂 —— 并排本来就是图片专属体验（进度会被
                                    //   强制收回全屏），竖滑走播放器既有链路；
                                    // - threeLevelEnabled=false（三级手势安全阀关闭）时
                                    //   enabled 恒 false —— 队列态不存在，删除手势整体停用，
                                    //   下滑关闭行为与 v4.16.13 完全一致。
                                    // 语义判定按手势起点锁定在 ImageViewer 内做
                                    // （enabled 只在纵向锁定那一帧读一次）。
                                    swipeDeleteEnabled = if (isCurrentPage && !item.isVideo) {
                                        { threeLevelEnabled && sideBySideActive }
                                    } else {
                                        null
                                    },
                                    onSwipeDeleteCommit = if (isCurrentPage && !item.isVideo) {
                                        { viewModel.deleteCurrentItem() }
                                    } else {
                                        null
                                    },
                                    onSwipeDeletePhaseChange = if (isCurrentPage && !item.isVideo) {
                                        { active -> swipeDeleteActiveState.value = active }
                                    } else {
                                        null
                                    }
                                )

                                // 「已删除」角标（第三态）：源已不存在，画面是本机缓存的低清副本。
                                // 与网格同一套视觉（error 底 + onError 文字 + CircleShape），
                                // 不做模糊 —— 模糊在本项目里是「还在加载」的语义，必须与第三态区分。
                                if (sourceMissing) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .statusBarsPadding()
                                            .padding(top = 16.dp, end = 16.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.label_source_missing),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onError
                                        )
                                    }
                                }

                                // 原图超过下载上限时的说明 + 取原图的途径。
                                // 只出现在"当前页且确实是降级来的图"上：App 读不动那张图，
                                // 但浏览器可以，所以给一条一键复制的链接而不是让用户自己拼。
                                if (isCurrentPage && uiState.compressedFallback) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(16.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.viewer_large_image_notice),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            TextButton(onClick = {
                                                val url = viewModel.downloadUrlForCurrent()
                                                if (url != null && copyUrlToClipboard(context, url)) {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.msg_download_link_copied,
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.msg_copy_prompt_failed,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }) {
                                                Text(stringResource(R.string.viewer_copy_download_link))
                                            }
                                        }
                                    }
                                }

                                // 视频播放地址没拿到但缩略图还在：不藏画面，底部叠加可重试的失败说明（H1-a）
                                if (videoOverlayFailure != null) {
                                    MediaLoadFailureBar(
                                        category = videoOverlayFailure,
                                        onRetry = { viewModel.retryCurrentItem() },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // P0：图库模式但条目已被删空（全删 / 会话失效 / 删除写入异常后的空列表）。
                    // 空列表进入查看器本身就是非法状态 —— 这里不创建 pager，也不再让任何
                    // `items[...]` / `coerceIn(0, size - 1)` 面对 size = 0，直接走统一退出入口关闭。
                    LaunchedEffect(Unit) {
                        currentExitViewer(ViewerExitReason.AUTO_CLOSE)
                    }
                }
            }
            ViewerMode.SINGLE -> {
                val item = uiState.currentItem
                // SINGLE 失败态（H1-a/H1-b）：内容缺失且不在加载 —— 修复前视频死转圈、图片静默黑屏。
                // 生成页跳转时视频 URI 没拿到（fetchVideoFromHistory 失败）正是这个分支的典型场景
                val hasContent = if (item?.isVideo == true) {
                    uiState.currentVideoUri != null || uiState.currentBitmap != null
                } else {
                    uiState.currentBitmap != null
                }
                val showState = ViewerLoadPolicy.resolveShowState(
                    item?.isVideo ?: false, hasContent, uiState.isLoading
                )
                val singleFailure = if (showState == ViewerShowState.FAILED && item != null) {
                    inferFailureCategory(
                        MediaCacheKey.of(item.promptId, item.filename, item.type),
                        uiState.loadFailure
                    )
                } else {
                    null
                }
                MediaContent(
                    isVideo = item?.isVideo ?: false,
                    bitmap = uiState.currentBitmap,
                    videoUri = uiState.currentVideoUri,
                    isLoading = uiState.isLoading,
                    onSingleTap = { viewModel.toggleUiVisibility() },
                    failureCategory = singleFailure,
                    onRetry = { viewModel.retryCurrentItem() },
                    // G6：单图模式同样只走屏幕级唯一清理入口
                    onViewerClose = currentExitViewer,
                    exitRegistry = exitRegistry,
                )
            }
        }

        // Counter chip (gallery mode only, when multiple items)
        if (uiState.mode == ViewerMode.GALLERY && uiState.totalCount > 1) {
            AnimatedVisibility(
                visible = uiState.isUiVisible,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.totalCount}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // 第三态且本机连缓存都没有：保存按钮必须禁用（点了也只会失败）。
        // 此时画面区已显示「原图已被删除，且本机无缓存」，用户拿得到原因。
        val currentItem = uiState.currentItem
        val saveEnabled = remember(
            currentItem?.promptId,
            currentItem?.filename,
            currentItem?.type,
            currentItem?.sourceMissing,
            uiState.isLoading
        ) {
            if (currentItem == null) {
                true
            } else {
                val key = MediaCacheKey.of(currentItem.promptId, currentItem.filename, currentItem.type)
                !((currentItem.sourceMissing || MediaCache.isFileMissing(key)) &&
                    !MediaCache.hasLocalImage(key, currentItem.isVideo))
            }
        }

        // Floating Toolbar
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = toolbarBottomPadding)
        ) {
            MediaViewerFloatingToolbar(
                isGalleryMode = uiState.mode == ViewerMode.GALLERY,
                saveEnabled = saveEnabled,
                onDelete = { viewModel.deleteCurrentItem() },
                onSave = { viewModel.saveCurrentItem() },
                onShare = { viewModel.shareCurrentItem() },
                onInfo = { showMetadataSheet = true },
                // 任何图都能复制下载链接 —— 不是只在"原图太大被降级"时才有。
                // 大图在 App 内受内存限制只能看降采样版，这条链接是拿到原始分辨率的通用出路
                onCopyLink = {
                    val url = viewModel.downloadUrlForCurrent()
                    if (url != null && copyUrlToClipboard(context, url)) {
                        Toast.makeText(context, R.string.msg_download_link_copied, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.msg_copy_prompt_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // 返回按钮（F2，v4.16.2 已删除）：关闭查看器只剩三条入口 ——
        // ① 下滑手势（MediaContent → ImageViewer/VideoPlayer 的 onViewerClose）
        // ② 并排态张开手指回全屏、并排态下拉关闭
        // ③ 系统返回键（本页顶部 BackHandler）
        // G6（任务书 12.2）：三条入口全部只调用本文件顶部的 exitViewer(reason)，
        // 取消在途动画 + 复位越界位移 + 经注册表复位页内图层，然后才 onClose()。
        // 历史上这里是"三份清理"（收缩路径还自播一段内容层淡出），残影就出在那。

        // G4（任务书 11.4）：竖滑删除后的**一次性撤销条**（限时 2 秒，用户已确认的
        // 交互决策）。挂在工具栏同侧、浮于其上：删除落点就在拇指够得到的下半屏，
        // 撤销按钮必须出现在图片消失的地方附近。超时 / 再次删除 / 队列删空都会让它消失
        // （槽位逻辑在 MediaViewerViewModel.trashUndo，条目本体始终在回收站界面可恢复）。
        val trashUndo by viewModel.trashUndo.collectAsStateWithLifecycle()
        if (uiState.mode == ViewerMode.GALLERY && trashUndo != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 76.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.msg_trash_moved),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { scope.launch { viewModel.undoTrashMove() } }) {
                        Text(stringResource(R.string.button_undo))
                    }
                }
            }
        }

        // Metadata bottom sheet
        if (showMetadataSheet) {
            MetadataBottomSheet(
                metadata = currentMetadata,
                isLoading = isLoadingMetadata,
                onDismiss = { showMetadataSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaViewerFloatingToolbar(
    isGalleryMode: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onCopyLink: () -> Unit,
    /** false = 源已不存在且本机无缓存：保存必然失败，按钮直接禁用 */
    saveEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val toolbarColors = FloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        fabContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        fabContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = toolbarColors,
        content = {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Delete button (gallery mode only)
                if (isGalleryMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.media_viewer_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Save button
                IconButton(onClick = onSave, enabled = saveEnabled) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(R.string.media_viewer_save)
                    )
                }

                // 复制下载链接：大图（App 内只能看降采样版）拿原始分辨率的通用出路
                IconButton(onClick = onCopyLink) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = stringResource(R.string.media_viewer_copy_link)
                    )
                }

                // Share button
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.media_viewer_share)
                    )
                }

                // Divider before Info
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Info button
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.button_show_metadata)
                    )
                }
            }
        }
    )
}

@Composable
private fun MediaContent(
    isVideo: Boolean,
    bitmap: android.graphics.Bitmap?,
    videoUri: android.net.Uri?,
    isLoading: Boolean,
    onSingleTap: () -> Unit,
    cacheKey: MediaCacheKey? = null,
    // 失败占位（H1-a/H1-b）：调用方判定"内容缺失且不在加载"后传入；null = 不显示失败态
    failureCategory: ViewerFailureCategory? = null,
    /**
     * 第三态且本机无缓存：失败文案改用「原图已被删除，且本机无缓存」。
     * 与普通 FILE_MISSING 分开，是因为用户还需要知道：连低清副本都没得存。
     */
    sourceMissingNoCache: Boolean = false,
    onRetry: (() -> Unit)? = null,
    // 关闭查看器的回调；null = 组件不启用关闭语义（缩略图槽位等场景）
    //
    // G6（任务书 12.2）：签名带**退出原因**。图片页的下滑脱离与双指收缩都经
    // MediaViewerScreen.exitViewer(reason) 这**一个**清理入口；视频页播放器只有
    // "关闭"一种语义，由下面包成 DISMISS_DRAG 后转调。
    onViewerClose: ((ViewerExitReason) -> Unit)? = null,
    // G6：ImageViewer 注册"页内图层复位"用的注册表（同一实例由屏幕级入口统一触发）
    exitRegistry: ViewerExitRegistry? = null,
    // B-23b 第三批（E1~E5）：本页的飞回目标格子矩形（window px）。非 null = 该页启用
    // 下拉透出与飞回（分段遮罩在 ImageViewer 内）；null = 行为与旧版完全一致
    flyBackCellRect: ViewerRect? = null,
    // G1（v4.16.11）：本页当前是否处于并排（队列）态。并排态下页内不再画任何整页黑
    // （背景由容器负责）；加载中/失败态的"盖截图黑"只在全屏态需要
    sideBySideActive: Boolean = false,
    // E4：飞回动画完成后的通知（先于 onViewerClose），上层据此抑制 Activity 层缩小淡出
    onFlyBackCompleted: (() -> Unit)? = null,
    // N6（v4.16.9，方案 A2）：放大态贴边继续同向拖的翻页回调，+1 右翻 / -1 左翻。
    // 上层用 pagerState.animateScrollToPage 主动翻页（A1 让位实测无效，见 ImageViewer）
    onPanEdgeFling: ((direction: Int) -> Unit)? = null,
    // 并排预览三级手势（D1~D5，v4.16.2）：仅 GALLERY 模式的图片页由上层接线。
    // 四个回调全 null 时 ImageViewer 的行为与改动前完全一致（不含任何并排分支）
    onSideBySideStart: (() -> Unit)? = null,
    onSideBySide: ((Float) -> Unit)? = null,
    onSideBySideSettle: ((Float) -> Unit)? = null,
    onSideBySideTap: ((Float, Float) -> Unit)? = null,
    sideBySideScale: (() -> Float)? = null,
    // G4（任务书 11.4）：队列竖滑快捷删除三件套（语义与接线约束见调用点注释）。
    // 全 null = ImageViewer 行为与改动前一致
    swipeDeleteEnabled: (() -> Boolean)? = null,
    onSwipeDeleteCommit: (() -> Unit)? = null,
    onSwipeDeletePhaseChange: ((Boolean) -> Unit)? = null
) {
    Box(
        // 这里**不能**铺不透明背景：并排预览（D2/D3）下每一页都会被整体缩放并
        // 向中间平移，页与页的布局区域互相重叠。若每页自带不透明黑底，当前页
        // 的黑底会盖掉左邻露边、右邻的黑底又会盖掉中间图右侧（实测各吃 48px）。
        // 屏幕根容器（MediaViewerScreen 的 Box）已经是纯黑，少这一层视觉零差别。
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isVideo && videoUri != null -> {
                // Current video page: show VideoPlayer
                VideoPlayer(
                    videoUri = videoUri,
                    modifier = Modifier.fillMaxSize(),
                    showController = false,
                    scaleMode = VideoScaleMode.FIT,
                    enableZoom = true,
                    onSingleTap = onSingleTap,
                    cacheKey = cacheKey,
                    // G6：视频播放器只有"关闭"一种语义，包成 DISMISS_DRAG 转调同一个
                    // 屏幕级清理入口 —— 不允许出现"视频退出另一套清理"的第二份实现
                    onClose = remember(onViewerClose) {
                        onViewerClose?.let { close -> { close(ViewerExitReason.DISMISS_DRAG) } }
                    }
                )
            }
            bitmap != null -> {
                // Image OR video thumbnail (for non-current video pages)
                ImageViewer(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = onSingleTap,
                    onViewerClose = onViewerClose,
                    // G6：页内复位注册给屏幕级统一清理入口（三条退出路径共用同一份清理）
                    exitRegistry = exitRegistry,
                    onFlyBackCompleted = onFlyBackCompleted,
                    // E1~E5：格子矩形非 null 时本页启用下拉透出与飞回（分段遮罩、
                    // 飞回落格都在 ImageViewer 内）；null = 与旧版完全一致
                    flyBackCellRect = flyBackCellRect,
                    onPanEdgeFling = onPanEdgeFling,
                    onSideBySideStart = onSideBySideStart,
                    onSideBySide = onSideBySide,
                    onSideBySideSettle = onSideBySideSettle,
                    onSideBySideTap = onSideBySideTap,
                    sideBySideScale = sideBySideScale,
                    onSwipeDeleteCommit = onSwipeDeleteCommit,
                    swipeDeleteEnabled = swipeDeleteEnabled,
                    onSwipeDeletePhaseChange = onSwipeDeletePhaseChange
                    // v4.16.8 N3/N4：pageBackground 参数已随本版删除——背景分支收敛为
                    // ImageViewer.drawBehind 三分支（判定见 resolveBackgroundBranch），
                    // 全屏无格子态由"档位宽度黑"（sideScale=1 → 满宽满高）兜底，
                    // 背景从此只有一处来源
                )
            }
            else -> {
                // E1：透出页的加载/失败态先盖一层纯黑——背景截图不能从 spinner 与
                // 失败占位后面漏出来（图片分支的黑幕在 ImageViewer 内分段驱动）。
                // G1：只在全屏态需要——并排（队列）态的截图层已整层透明，背景由
                // 容器纯黑负责，页内再铺满页黑只会盖住两侧露出的邻图（"各吃 48px"）
                if (flyBackCellRect != null && !sideBySideActive) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
                if (failureCategory != null) {
                    // 加载失败（H1-a/H1-b）：图标 + 分档文案 + 重试。
                    // 修复前视频页到这里永远转圈、图片页静默黑屏，用户无从分辨"在加载"还是"坏了"
                    MediaLoadFailurePlaceholder(
                        category = failureCategory,
                        sourceMissingNoCache = sourceMissingNoCache,
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isVideo || isLoading) {
                    // Video pages without content always show spinner (never black)
                    // This handles the case where thumbnail is not yet in cache
                    // （视频页失败态已被上面的 failureCategory 分支拦下，走到这里的只剩
                    //   非当前页视频与真正的加载中 —— 保持"never black"的原语义）
                    CircularProgressIndicator(color = Color.White)
                }
                // Fallback: only images without bitmap and not loading show black
                // （非当前页图片加载失败保持黑屏：它们是候补页，不该替用户下"失败"的结论）
            }
        }
    }
}

/**
 * 整屏加载失败占位（H1-a/H1-b）：图标 + 分档文案 + 重试按钮。
 *
 * 三档图标对齐图库格子的既有语义（见 GalleryItemCard）：
 * 文件不存在是"找不回"，网络不可达是"暂时取不到"，用户要能一眼区分。
 * 黑底上用白色系（与 spinner 同一套），不借主题色 —— 查看器背景恒为黑。
 */
@Composable
private fun MediaLoadFailurePlaceholder(
    category: ViewerFailureCategory,
    onRetry: (() -> Unit)?,
    sourceMissingNoCache: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = when (category) {
                ViewerFailureCategory.FILE_MISSING -> Icons.Default.ImageSearch
                ViewerFailureCategory.NETWORK -> Icons.Default.CloudOff
                ViewerFailureCategory.GENERIC -> Icons.Default.Image
            },
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Color.White.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (sourceMissingNoCache) {
                stringResource(R.string.msg_image_source_missing_no_cache)
            } else {
                stringResource(failureMessageRes(category))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.viewer_retry))
            }
        }
    }
}

/**
 * 视频页的失败提示条（H1-a）：缩略图保留在画面上，底部叠加一条"没拿到播放地址"
 * 的说明与重试入口。样式对齐"原图过大"的降级提示层（compressedFallback），
 * 同一套 Surface + Row + TextButton，用户看到的是同一种"附加说明"而不是弹窗。
 */
@Composable
private fun MediaLoadFailureBar(
    category: ViewerFailureCategory,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        ) {
            Text(
                text = stringResource(failureMessageRes(category)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.viewer_retry))
            }
        }
    }
}

/**
 * G2（v4.16.12）：把纯函数 [ViewerPageMotion.snapTargetPage] 接进 Compose Pager
 * 的吸附决策。
 *
 * 框架调用链（Compose Foundation 1.10 字节码取证）：`PagerDefaults.flingBehavior`
 * 构造的 SnapFlingBehavior 先用 spline 摩擦衰减算出"滑行终点"，再把终点换算成
 * `currentPage + round(滑行距离 / 页距)`（= 终点附近可见面积最大的那一页），
 * 最后经本接口收窄成最终目标页。默认实现 `PagerSnapDistance.atMost(1)` 把跨页数
 * 死钉在 1 —— 任务书里"快速甩动不产生跨页滑行"就是它。
 *
 * 这里只改一件事：跨页上限改由松手速度算（[ViewerPageMotion.flingPageCount]），
 * 吸附目标仍取框架给的衰减终点（它已经按"round 到最近整页"算过），
 * 方向也沿用框架给出的终点——不依赖 velocity 的符号约定（不同版本可能相反）。
 *
 * 页数用 `() -> Int` 而不是直接持有 PagerState：这条接线本身要能在 JVM 单测里
 * 覆盖（ViewerPagerSnapDistanceTest），不把 Compose 状态机拖进测试。
 *
 * v4.16.14：新增 [allowInertia]（同样用 lambda 注入，保持可在 JVM 单测里开关）。
 * 用户规格："横向甩动的跨多张惯性只保留在队列模式（并排三张）；单图全屏模式下
 * 不管甩多大力都只滑一张。" 实现上**不改纯函数**：`allowInertia` 为 false 时把
 * 速度按 0 传进 [ViewerPageMotion.snapTargetPage] —— 零速下 `flingPageCount`
 * 返回 0、跨页上限退化为 `maxOf(1, 0) = 1`，正好就是"最多吸附相邻一张"，
 * 而"拖过半仍能翻一张"的既有手感原样保留。（若改成 `atMost(0)` 会把慢速拖动
 * 也钉死在原地：松手后永远弹回当前页。）
 *
 * 本类只负责"给什么速度就按什么口径吸附"，**判断当前该不该有惯性不在本类里**：
 * 判据是 [pagerInertiaAllowed]（当前是否真的处于队列态），由屏幕的接线传进来。
 */
internal class ViewerPagerSnapDistance(
    private val pageCountProvider: () -> Int,
    private val allowInertia: () -> Boolean = { true }
) : PagerSnapDistance {

    override fun calculateTargetPage(
        startPage: Int,
        suggestedTargetPage: Int,
        velocity: Float,
        pageSize: Int,
        pageSpacing: Int
    ): Int = ViewerPageMotion.snapTargetPage(
        // 起点一律用框架传进来的 startPage：衰减终点是相对它算的，
        // 换成 currentPage 会在"速度方向令 firstVisiblePage 偏移"时错一页
        startPage = startPage,
        decayEndPage = suggestedTargetPage.toFloat(),
        // 全屏单图：速度视为 0，只允许相邻一张（见类注释）
        velocityPxPerSec = if (allowInertia()) velocity else 0f,
        pagePitchPx = (pageSize + pageSpacing).toFloat(),
        pageCount = pageCountProvider()
    )
}

/**
 * 横向惯性档位（v4.16.14 复核 GATE-1 的判据，纯函数便于单测）。
 *
 * 用户规格：**跨多张的横向惯性只保留在「队列模式（并排三张）」**；单图全屏模式下
 * 不管甩多大力都只滑一张。所以判据必须是"**当前是否真的在队列态**"
 * （[sideBySideActive]，即 sideProgress 越过 0.5 的派生状态），
 * **不是"队列功能是否开启"**（threeLevelEnabled 只是设置项、默认 true，
 * 而查看器初始态永远是全屏 —— 拿它当判据等于默认用户全屏仍然跨多页，
 * 用户可见现象一条都不消除）。
 *
 * 两个条件同时成立才放开惯性，与屏幕里既有队列态口径
 * （竖滑删除的 `threeLevelEnabled && sideBySideActive`）完全一致：
 * - threeLevelEnabled = false：并排/队列链路整体停用，就没有"队列模式"可言；
 * - sideBySideActive = false：此刻是全屏单图，只能逐张翻。
 *
 * 这个函数存在的唯一意义就是让"接线到底传了什么"可以被 JVM 单测钉住
 * （见 ViewerPagerSnapDistanceTest 与 PagerInertiaWiringTest）：
 * 只测 ViewerPagerSnapDistance 本身证明不了屏幕传的是哪个值。
 */
internal fun pagerInertiaAllowed(threeLevelEnabled: Boolean, sideBySideActive: Boolean): Boolean =
    threeLevelEnabled && sideBySideActive
