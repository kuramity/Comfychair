package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

// ============================================================
// N4-R：图库长按滑选（**线性连续区间**）
// ============================================================
//
// 语义（执行书 N4-R §三，用户确认的终版）：
// 把网格按阅读顺序（从左到右、从上到下）看成一条线 —— 长按起点格与当前手指所在格之间的
// **整段**全部选中，跨行时中间整行全选，手指没经过也选；往回拖则区间缩短、移出区间的取消；
// 手势开始前已选中的（baseline）永不被框选取消。
//
// 本文件三块：
//   1. 纯逻辑（精确/最近命中、区间、基线并集、边缘速度）—— JVM 单测管得着；
//   2. 手势检测器 detectDragSelectAfterLongPress —— Initial 通道，长按达成前不消费；
//   3. rememberGalleryDragSelectModifier —— 接到 LazyGridState 与「整体设置选中集合」回调上
//      （GalleryScreen 与 instrumented 测试共用同一段接线）。

/** 网格里一个可命中格子的矩形（**网格本地坐标系，px**，与 pointerInput 的指针坐标同一基准）。 */
internal data class DragSelectCell(
    val index: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(pointer: Offset): Boolean =
        pointer.x >= left && pointer.x <= right &&
            pointer.y >= top && pointer.y <= bottom
}

/** 点到格子矩形的最短距离（点在矩形内为 0）。 */
internal fun dragSelectEdgeDistance(pointer: Offset, cell: DragSelectCell): Float {
    val dx = max(max(cell.left - pointer.x, 0f), pointer.x - cell.right)
    val dy = max(max(cell.top - pointer.y, 0f), pointer.y - cell.bottom)
    return sqrt(dx * dx + dy * dy)
}

/**
 * 指针所在格子的索引。
 *
 * 优先精确命中；未命中时取「格子中心离指针最近」的一格 —— 这样拖到行末空白、格子间隙
 * （8dp）、两行之间时不会卡住不动：拖到行末空白直觉是该行最后一格，拖到两行间隙直觉是
 * 更靠近的那一行，取最近两者都对。
 *
 * [maxEdgeDistancePx] 限制「最近」兜底的作用范围（长按起点用一个小值：起点必须落在格子上
 * 或紧邻格子的缝隙里，否则会连页脚按钮的长按也抢走）。求终点时传默认的无限大。
 *
 * 没有任何可用格子时返回 null（调用方跳过该帧）。
 */
internal fun dragSelectIndexAt(
    pointer: Offset,
    cells: List<DragSelectCell>,
    maxEdgeDistancePx: Float = Float.POSITIVE_INFINITY
): Int? {
    cells.firstOrNull { it.contains(pointer) }?.let { return it.index }
    var bestIndex: Int? = null
    var bestSquared = Float.POSITIVE_INFINITY
    for (cell in cells) {
        if (dragSelectEdgeDistance(pointer, cell) > maxEdgeDistancePx) continue
        val cx = cell.left + cell.width / 2f
        val cy = cell.top + cell.height / 2f
        val dx = pointer.x - cx
        val dy = pointer.y - cy
        val squared = dx * dx + dy * dy
        if (squared < bestSquared) {
            bestSquared = squared
            bestIndex = cell.index
        }
    }
    return bestIndex
}

/** 线性区间内的所有索引（含两端，自动处理拖动方向）。startIndex == endIndex 时返回单元素。 */
internal fun dragSelectIndexRange(startIndex: Int, endIndex: Int): IntRange =
    if (startIndex <= endIndex) startIndex..endIndex else endIndex..startIndex

/** 框选结果：基线（长按前已选）∪ 区间内。基线里的条目永远不会被框选取消。 */
internal fun dragSelectResolveSelection(
    baseline: Set<String>,
    inside: Set<String>
): Set<String> = baseline + inside

// ---------------- 边缘自动滚动 ----------------

/** 上下边缘感应区各占视口高度的比例（执行书 §5.4）。 */
internal const val DRAG_SELECT_EDGE_BAND_FRACTION = 0.15f

/**
 * 刚进入感应区时的速度占最大速度的比例。
 *
 * 纯线性（比例 0 起步）会让「刚压到边缘」的头几十像素完全不动，手感像失灵；
 * 给一个不大的起步值，既有「越靠边越快」的加速感，又不会在边缘附近突然窜出去。
 */
internal const val DRAG_SELECT_EDGE_MIN_SPEED_RATIO = 0.2f

/** 指针贴在屏幕最边缘时的最大速度：每秒滚过的视口高度数。 */
internal const val DRAG_SELECT_EDGE_MAX_VIEWPORTS_PER_SECOND = 1.0f

/** 边缘滚动循环的节拍（ms）。用 delay 而不是 withFrameNanos：这个协程不在 Recomposer 下。 */
internal const val DRAG_SELECT_SCROLL_FRAME_MS = 16L

/**
 * 边缘自动滚动的「真的在拖了」判定位移（dp）。
 *
 * 长按落点本身就可能在底边感应区内（比如最后一行）。如果长按一达成就开始滚，
 * 用户只是按住不动、随后松手，列表也会自己往上滑一段 —— 这不是要的行为。
 * 所以指针相对长按落点移动超过这个距离（约系统 touchSlop 量级）之后，才允许边缘滚动。
 */
internal const val DRAG_SELECT_EDGE_START_SLOP_DP = 8f

/**
 * 长按起点允许「最近格子」兜底的半径（dp）。
 *
 * 格子间隙只有 8dp，按在缝隙里应当算最近那格；但页脚「加载更早」按钮在最后一行下方几十 dp，
 * 按住它不该进入滑选（否则会把最后一行莫名其妙选中）。12dp 刚好覆盖间隙、够不到页脚。
 */
internal const val DRAG_SELECT_START_HIT_TOLERANCE_DP = 12f

/** 感应区高度（px）。视口高度非法（未排版）时返回 0 = 不启用边缘滚动。 */
internal fun dragSelectEdgeBandPx(
    viewportHeightPx: Float,
    fraction: Float = DRAG_SELECT_EDGE_BAND_FRACTION
): Float {
    if (viewportHeightPx <= 0f || fraction <= 0f) return 0f
    return viewportHeightPx * fraction
}

/**
 * 边缘滚动速度（px/秒）。负 = 向上滚（内容往下走），正 = 向下滚，0 = 不在感应区。
 *
 * 感应区内的速度按进入深度线性加速：贴边最慢 = [DRAG_SELECT_EDGE_MIN_SPEED_RATIO] × max，
 * 越过整条感应区 = max。指针拖出网格外（坐标 < 0 或 > 视口高）按最深处理（钳到 max）。
 */
internal fun dragSelectScrollVelocityPxPerSecond(
    pointerY: Float,
    viewportHeightPx: Float,
    bandPx: Float,
    maxPxPerSecond: Float
): Float {
    if (viewportHeightPx <= 0f || bandPx <= 0f || maxPxPerSecond <= 0f) return 0f
    // 到顶边的深度 / 到底边的深度，谁更深往谁那边滚
    val topDepth = bandPx - pointerY
    val bottomDepth = pointerY - (viewportHeightPx - bandPx)
    val upward = topDepth > 0f && topDepth >= bottomDepth
    val depth = if (upward) topDepth else bottomDepth
    if (depth <= 0f) return 0f
    val ratio = (depth / bandPx).coerceAtMost(1f)
    val magnitude = maxPxPerSecond * (DRAG_SELECT_EDGE_MIN_SPEED_RATIO +
        (1f - DRAG_SELECT_EDGE_MIN_SPEED_RATIO) * ratio)
    return if (upward) -magnitude else magnitude
}

// ---------------- 手势检测器 ----------------

/**
 * 长按后滑选的手势检测（挂在网格容器的 Modifier.pointerInput 上）。
 *
 * **为什么不用现成的 `detectDragGesturesAfterLongPress`**：它在 Main 通道上工作，
 * 而 Main 通道是「子节点先消费、父节点后看」——网格自己的 `scrollable` 和格子的
 * `detectTapGestures` 都在 Grid 之下，长按之后手指一动，`scrollable` 先把位移消费掉，
 * 父节点看到的已是 `isConsumed = true`，现成的拖拽器会直接 `onDragCancel`。
 * 所以在 [PointerEventPass.Initial]（父节点先看）上自己做：长按达成前一根毫毛都不吃
 * （滚动/点击/下拉刷新全照旧），长按达成且落点在格子上才接管并消费后续事件。
 *
 * 与既有长按的关系：格子上原来的 `onLongPress` 已移除，长按入口统一走这里
 * （[onLongPressStart] 负责「进入选择模式 + 选中起点」），否则两处都会在 500ms 阈值
 * 同时触发，同一个格子被处理两次。
 *
 * @param onLongPressStart 长按达成时的落点（网格本地坐标）。返回 true 表示接管手势
 *   （落点在格子上）；返回 false 表示这里没有可勾的东西（无格子/页脚/空态），放手交给原逻辑。
 * @param onPointerMove 接管后手指移动到的位置。
 * @param onGestureFinished 松手 / 取消 / 被第二根手指打断：停边缘滚动、清滑选态。
 */
internal suspend fun PointerInputScope.detectDragSelectAfterLongPress(
    onLongPressStart: (Offset) -> Boolean,
    onPointerMove: (Offset) -> Unit,
    onGestureFinished: () -> Unit
) {
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop

    awaitEachGesture {
        // Initial 通道：比网格的 scrollable / 格子手势都先拿到同一批事件
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        // 阶段一：等长按。期间抬手 / 净位移超过 touchSlop / 被子节点消费 / 双指按着 → 都不算
        // 长按，一个事件都不消费直接退出，滚动、单击、双击全部按原样走。
        var longPressReached = false
        try {
            withTimeout(longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // 两根手指同时按着（捏合 / 误触）：不可能是长按滑选
                    if (event.changes.count { it.pressed } >= 2) return@withTimeout
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeout
                    if (change.changedToUp()) return@withTimeout
                    if (change.isConsumed) return@withTimeout
                    if ((change.position - down.position).getDistance() > touchSlop) {
                        return@withTimeout
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            // 超时没挪窝 = 长按达成（系统阈值约 500ms；用户按住一秒再拖天然满足）
            longPressReached = true
        }
        if (!longPressReached) return@awaitEachGesture

        // 阶段二：接管。落点没有格子就别抢手势（页脚按钮 / 空态）
        val latest = currentEvent.changes.firstOrNull { it.id == down.id }
        val startPosition = latest?.position ?: down.position
        if (!onLongPressStart(startPosition)) return@awaitEachGesture

        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                val up = change == null || change.changedToUp()
                // 滑选中冒出第二根手指（捏合/误触）：结束本次滑选，已选的部分保留
                val secondFinger = event.changes.any { it.id != down.id && it.changedToDown() }
                // 全消费：scrollable 起不了拖、格子的 detectTapGestures 拿不到 tap
                event.changes.forEach { it.consume() }
                if (up || secondFinger) break
                onPointerMove(change!!.position)
            }
        } finally {
            onGestureFinished()
        }
    }
}

/**
 * 滑选运行期的可变持有器。
 *
 * 指针位置每帧都变，**不能**放进 `mutableStateOf`：读取方在组合里就会每帧重组
 * （P2 明确要求网格性能不回退）。这里用普通字段，只有一次性的 active 标志。
 */
internal class DragSelectRuntime {
    var pointer: Offset = Offset.Zero

    /** 长按落点：用来判断「用户真的开始拖了」，避免长按落在底边区域就自己滚起来。 */
    var claimPointer: Offset = Offset.Zero
    var active: Boolean = false

    /** 指针已经离开长按落点足够远：这时才允许边缘自动滚动。 */
    var dragging: Boolean = false

    /** 长按起点格的 **layoutInfo index**（手势期间固定不变，边缘滚动也不改）。 */
    var startIndex: Int? = null

    /** 长按前的选中集合快照：并集进每一帧结果，保证基线永不被框选取消。 */
    var baselineSelected: Set<String> = emptySet()

    /** 上一次真正提交给 ViewModel 的集合（相同就不重复提交，省 StateFlow 抖动）。 */
    var appliedSelection: Set<String> = emptySet()

    /** 上一次算出的终点索引：手指没动、内容没滚时直接跳过整段重算。 */
    var lastEndIndex: Int? = null
    var scrollJob: Job? = null

    fun reset() {
        active = false
        dragging = false
        startIndex = null
        baselineSelected = emptySet()
        appliedSelection = emptySet()
        lastEndIndex = null
        scrollJob?.cancel()
        scrollJob = null
    }
}

/**
 * 把「长按滑选」装成可挂在网格 Modifier 上的手势（GalleryScreen 与 instrumented 测试共用）。
 *
 * 接线：
 * ① 把 LazyGrid 的可见项摊成命中矩形（页脚/空态占位项的 index 在 items 之外，一律排除）；
 * ② 长按起点记 `startIndex` + 当前选中集合快照；
 * ③ 每帧（手指移动帧 / 边缘滚动帧）算终点索引 → 区间 → 区间内 key ∪ 基线 → 整体提交。
 *
 * 回调与 itemCount 用 rememberUpdatedState 取最新值：`pointerInput(Unit)` 的手势块只在
 * 首次组合时创建一次，闭包若直接捕获参数会永远读到首帧的旧数据。
 *
 * @param itemCount 列表条目数（区间只在这个范围内取值）
 * @param itemKeyAt index → ViewModel 选择键（与 `_selectedItems` 同一口径），越界返回 null
 * @param selectedKeys 当前选中集合（手势开始时快照成 baseline）
 * @param applySelection 整体设置选中集合（对应 `GalleryViewModel.applyDragSelection`）
 */
@Composable
internal fun rememberGalleryDragSelectModifier(
    gridState: LazyGridState,
    itemCount: () -> Int,
    itemKeyAt: (Int) -> String?,
    selectedKeys: () -> Set<String>,
    applySelection: (Set<String>) -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val runtime = remember { DragSelectRuntime() }
    val edgeStartSlopPx = with(LocalDensity.current) { DRAG_SELECT_EDGE_START_SLOP_DP.dp.toPx() }
    val startHitTolerancePx =
        with(LocalDensity.current) { DRAG_SELECT_START_HIT_TOLERANCE_DP.dp.toPx() }
    val latestItemCount = rememberUpdatedState(itemCount)
    val latestItemKey = rememberUpdatedState(itemKeyAt)
    val latestSelectedKeys = rememberUpdatedState(selectedKeys)
    val latestApply = rememberUpdatedState(applySelection)

    fun cells(): List<DragSelectCell> {
        val count = latestItemCount.value()
        return gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            if (info.index < 0 || info.index >= count) return@mapNotNull null
            DragSelectCell(
                index = info.index,
                left = info.offset.x.toFloat(),
                top = info.offset.y.toFloat(),
                width = info.size.width.toFloat(),
                height = info.size.height.toFloat()
            )
        }
    }

    /**
     * 一次重算并提交：终点索引 → 区间 → 区间内 key → 与基线求并集 → 整体设置。
     *
     * [pointerMoved] = false 用于「手指没动、内容被边缘滚动推走了」的那一帧：
     * 终点索引照样要重算（格子已经换了）。
     */
    fun applyAt(pointer: Offset, pointerMoved: Boolean) {
        runtime.pointer = pointer
        if (pointerMoved && !runtime.dragging &&
            (pointer - runtime.claimPointer).getDistance() >= edgeStartSlopPx
        ) {
            runtime.dragging = true
        }
        val startIndex = runtime.startIndex ?: return
        val endIndex = dragSelectIndexAt(pointer, cells()) ?: return
        if (endIndex == runtime.lastEndIndex) return
        runtime.lastEndIndex = endIndex
        val range = dragSelectIndexRange(startIndex, endIndex)
        val inside = range.mapNotNull { latestItemKey.value(it) }.toSet()
        val target = dragSelectResolveSelection(runtime.baselineSelected, inside)
        if (target != runtime.appliedSelection) {
            runtime.appliedSelection = target
            latestApply.value(target)
        }
    }

    fun start(pointer: Offset): Boolean {
        val startIndex = dragSelectIndexAt(pointer, cells(), startHitTolerancePx) ?: return false
        runtime.reset()
        runtime.pointer = pointer
        runtime.claimPointer = pointer
        runtime.active = true
        runtime.startIndex = startIndex
        // 必须是快照：ViewModel 里的集合是不可变的，但调用方若传可变集合，并集会被后续写入污染
        runtime.baselineSelected = latestSelectedKeys.value().toSet()
        runtime.appliedSelection = runtime.baselineSelected
        // 起点那一下立刻生效（区间 [start, start]）：长按不拖 = 只选中该图并进入选择模式
        applyAt(pointer, pointerMoved = false)

        runtime.scrollJob = scope.launch {
            var lastTickNanos = System.nanoTime()
            while (isActive && runtime.active) {
                delay(DRAG_SELECT_SCROLL_FRAME_MS)
                // 还没真正拖动（只是按住）：不滚，避免长按落在最后一行时列表自己往上走
                if (!runtime.dragging) continue
                val now = System.nanoTime()
                // 帧间隔按真实经过时间算：delay 会被主线程排队拖慢，固定步长会滚得偏慢
                val dtSeconds = ((now - lastTickNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastTickNanos = now
                val viewportHeightPx = gridState.layoutInfo.viewportSize.height.toFloat()
                val velocity = dragSelectScrollVelocityPxPerSecond(
                    pointerY = runtime.pointer.y,
                    viewportHeightPx = viewportHeightPx,
                    bandPx = dragSelectEdgeBandPx(viewportHeightPx),
                    maxPxPerSecond = viewportHeightPx * DRAG_SELECT_EDGE_MAX_VIEWPORTS_PER_SECOND
                )
                if (velocity == 0f) continue
                gridState.scrollBy(velocity * dtSeconds)
                // 内容滚了：手指没动但终点格换了 → 重算区间（startIndex 不随滚动改变）
                applyAt(runtime.pointer, pointerMoved = false)
            }
        }
        return true
    }

    fun stop() {
        runtime.reset()
    }

    // Modifier 用 remember 固定成同一个实例：既避免每次重组新建 pointerInput 元素，
    // 也彻底排除「重组时 handler 被替换、手势中途重启」这类隐患。
    // 块里引用的都是稳定对象（remember 的 runtime/scope + rememberUpdatedState），
    // 所以只构建一次也能读到最新数据。
    return remember(gridState) {
        Modifier.pointerInput(Unit) {
            detectDragSelectAfterLongPress(
                onLongPressStart = { start(it) },
                onPointerMove = { applyAt(it, pointerMoved = true) },
                onGestureFinished = { stop() }
            )
        }
    }
}
