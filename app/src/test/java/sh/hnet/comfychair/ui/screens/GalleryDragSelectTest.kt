package sh.hnet.comfychair.ui.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N4-R 长按滑选的纯逻辑单测：**线性连续区间**语义。
 *
 * 手势本体（真实触摸分发）在 GalleryDragSelectInstrumentedTest 里验证；这里保证「算的部分」
 * 与执行书第五节 1-12 的判定口径一致（尤其是用户举的原例：起点在第 2 行第 2 格，
 * 往上拖到第 1 行第 1 格 → 第 1 行 4 格 + 第 2 行前 2 格）。
 */
class GalleryDragSelectTest {

    // ---------------- 测试用网格（4 列，按行优先编号：index = row * 4 + col） ----------------

    private val cellSize = 100f
    private val gap = 8f
    private val cols = 4

    /** 4 列网格：格子 100×100、间距 8，左上角从 (0,0) 开始（不模拟 contentPadding） */
    private fun gridCells(rows: Int): List<DragSelectCell> = buildList {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                add(
                    DragSelectCell(
                        index = row * cols + col,
                        left = col * (cellSize + gap),
                        top = row * (cellSize + gap),
                        width = cellSize,
                        height = cellSize
                    )
                )
            }
        }
    }

    private fun centerOf(row: Int, col: Int): Offset = Offset(
        x = col * (cellSize + gap) + cellSize / 2f,
        y = row * (cellSize + gap) + cellSize / 2f
    )

    private fun cellCenter(cell: DragSelectCell) =
        Offset(cell.left + cell.width / 2f, cell.top + cell.height / 2f)

    private fun keys(range: IntRange) = range.map { "k$it" }.toSet()

    /**
     * 模拟一次长按滑选：起点 + 若干终点帧 → 每帧「区间 ∪ 基线」整体覆盖。
     * 与 [rememberGalleryDragSelectModifier] 里的 applyAt 同一套判定。
     */
    private fun simulateDrag(
        cells: List<DragSelectCell>,
        start: Offset,
        waypoints: List<Offset>,
        baseline: Set<String> = emptySet()
    ): Set<String> {
        val startIndex = dragSelectIndexAt(start, cells) ?: return baseline
        var applied = baseline
        for (pointer in waypoints) {
            val endIndex = dragSelectIndexAt(pointer, cells) ?: continue
            val inside = keys(dragSelectIndexRange(startIndex, endIndex))
            applied = dragSelectResolveSelection(baseline, inside)
        }
        return applied
    }

    // ---------------- 命中：精确优先，最近兜底 ----------------

    @Test
    fun `精确命中落在格子内取该格`() {
        val cells = gridCells(rows = 3)
        assertEquals(0, dragSelectIndexAt(centerOf(0, 0), cells))
        assertEquals(5, dragSelectIndexAt(centerOf(1, 1), cells))
        assertEquals(11, dragSelectIndexAt(centerOf(2, 3), cells))
    }

    @Test
    fun `落在格子间隙时取最近的一格`() {
        val cells = gridCells(rows = 3)
        // 横向 8px 缝隙正中（第 0 行第 0/1 格之间）：到两格中心距离相同，取先命中扫描的最近者之一
        val gapX = cellSize + gap / 2f
        val hit = dragSelectIndexAt(Offset(gapX, centerOf(0, 0).y), cells)
        assertTrue("间隙必须落到相邻两格之一，实际 $hit", hit == 0 || hit == 1)
        // 偏向下半行：必须落到第 1 行
        val belowRow0 = Offset(centerOf(0, 0).x, cellSize + gap / 2f + 6f)
        assertEquals(4, dragSelectIndexAt(belowRow0, cells))
    }

    @Test
    fun `拖到行末空白取该行最后一格`() {
        // 执行书验收 8：第 1 行第 4 格右边一大段空白
        val cells = gridCells(rows = 3)
        val rowEnd = Offset(cellSize * 4 + gap * 4, centerOf(0, 0).y)
        assertEquals(3, dragSelectIndexAt(rowEnd, cells))
    }

    @Test
    fun `没有任何可见格子返回 null`() {
        assertNull(dragSelectIndexAt(Offset(10f, 10f), emptyList()))
    }

    @Test
    fun `长按起点用容差兜底：缝隙算、页脚不算`() {
        val cells = gridCells(rows = 2)
        // 8px 缝隙在 12dp 容差内 → 算最近格
        val gapX = cellSize + gap / 2f
        val inGap = dragSelectIndexAt(Offset(gapX, centerOf(0, 0).y), cells, maxEdgeDistancePx = 40f)
        assertTrue("缝隙里长按应当能起手，实际 $inGap", inGap == 0 || inGap == 1)
        // 最后一行下方 60px（页脚「加载更早」所在区域）→ 超出容差 → 不接管
        val footerArea = Offset(centerOf(0, 0).x, cellSize * 2 + gap + 60f)
        assertNull(dragSelectIndexAt(footerArea, cells, maxEdgeDistancePx = 40f))
        // 同一个点在不带容差（求终点）时会落到最近格
        assertEquals(4, dragSelectIndexAt(footerArea, cells))
    }

    @Test
    fun `点到格子的距离在格子内为零`() {
        val cell = DragSelectCell(index = 0, left = 0f, top = 0f, width = 100f, height = 100f)
        assertEquals(0f, dragSelectEdgeDistance(Offset(50f, 50f), cell), 0.001f)
        assertEquals(10f, dragSelectEdgeDistance(Offset(110f, 50f), cell), 0.001f)
        assertEquals(0f, dragSelectEdgeDistance(Offset(100f, 100f), cell), 0.001f)
    }

    // ---------------- 区间与并集 ----------------

    @Test
    fun `区间正反向同结果且含两端`() {
        assertEquals(0..5, dragSelectIndexRange(0, 5))
        assertEquals(0..5, dragSelectIndexRange(5, 0))
        assertEquals(7..7, dragSelectIndexRange(7, 7))
    }

    @Test
    fun `并集保留基线`() {
        assertEquals(setOf("a", "b", "k1"), dragSelectResolveSelection(setOf("a", "b"), setOf("k1")))
        assertEquals(setOf("a"), dragSelectResolveSelection(setOf("a"), emptySet()))
    }

    // ---------------- 执行书验收 1-6、8 的判定口径 ----------------

    @Test
    fun `验收1 用户原例：起点第2行第2格往上拖到第1行第1格`() {
        val cells = gridCells(rows = 4)
        // 起点 (row1, col1) = index 5；终点 (row0, col0) = index 0
        val selected = simulateDrag(cells, centerOf(1, 1), listOf(centerOf(0, 0)))
        // 第一行 4 格 + 第二行前 2 格 = 0..5
        assertEquals(keys(0..5), selected)
        assertEquals(6, selected.size)
    }

    @Test
    fun `验收2 跨行时中间整行全选`() {
        val cells = gridCells(rows = 4)
        // 第 1 行第 3 格 (row0, col2) = 2 → 第 3 行第 2 格 (row2, col1) = 9
        val selected = simulateDrag(cells, centerOf(0, 2), listOf(centerOf(2, 1)))
        // 第1行第3-4格(2,3) + 第2行整行(4,5,6,7) + 第3行第1-2格(8,9)
        assertEquals(keys(2..9), selected)
        assertEquals(8, selected.size)
    }

    @Test
    fun `验收3 手指没经过的格子也在选中集合里`() {
        val cells = gridCells(rows = 4)
        // 一次性从左上跳到右下（中间没有任何中间帧）：整段 0..15 全选
        val selected = simulateDrag(cells, centerOf(0, 0), listOf(centerOf(3, 3)))
        assertEquals(keys(0..15), selected)
    }

    @Test
    fun `验收4 反向拖动与正向结果一致`() {
        val cells = gridCells(rows = 4)
        val forward = simulateDrag(cells, centerOf(0, 1), listOf(centerOf(2, 2)))
        val backward = simulateDrag(cells, centerOf(2, 2), listOf(centerOf(0, 1)))
        assertEquals(forward, backward)
        assertEquals(keys(1..10), forward)
    }

    @Test
    fun `验收5 区间缩短后移出的格子被取消`() {
        val cells = gridCells(rows = 4)
        val selected = simulateDrag(
            cells,
            start = centerOf(0, 0),
            // 先拖到底（0..15），再拖回 index 4
            waypoints = listOf(centerOf(3, 3), centerOf(1, 0))
        )
        assertEquals("缩回后只剩 0..4", keys(0..4), selected)
    }

    @Test
    fun `验收6 长按前已选中的两项永不被框选取消`() {
        val cells = gridCells(rows = 4)
        // baseline 里是不相邻的两张（index 2 与 index 9）
        val baseline = setOf("k2", "k9")
        val selected = simulateDrag(
            cells,
            start = centerOf(3, 0),
            waypoints = listOf(centerOf(3, 2), centerOf(3, 1)),
            baseline = baseline
        )
        assertTrue(selected.containsAll(baseline))
        // 区间最后停在 12..13，加上基线
        assertEquals(setOf("k2", "k9", "k12", "k13"), selected)
    }

    @Test
    fun `验收8 拖到行末空白等价于该行最后一格`() {
        val cells = gridCells(rows = 3)
        val rowEndBlank = Offset(cellSize * 4 + gap * 4 + 120f, centerOf(0, 0).y)
        val selected = simulateDrag(cells, centerOf(0, 0), listOf(rowEndBlank))
        assertEquals("第 1 行 4 格全选", keys(0..3), selected)
    }

    // ---------------- 边缘自动滚动速度 ----------------

    private val viewport = 2000f
    private val band = dragSelectEdgeBandPx(viewport)
    private val maxSpeed = viewport * DRAG_SELECT_EDGE_MAX_VIEWPORTS_PER_SECOND

    private fun velocityAt(y: Float) =
        dragSelectScrollVelocityPxPerSecond(y, viewport, band, maxSpeed)

    @Test
    fun `感应区为视口高度的百分之十五`() {
        assertEquals(300f, band, 0.01f)
        assertEquals(0f, dragSelectEdgeBandPx(0f), 0.01f)
    }

    @Test
    fun `视口中部与感应区外沿不滚动`() {
        assertEquals(0f, velocityAt(1000f), 0.01f)
        assertEquals(0f, velocityAt(300f), 0.01f)
        assertEquals(0f, velocityAt(1700f), 0.01f)
    }

    @Test
    fun `顶部与底部方向相反且贴边最快`() {
        assertEquals(-maxSpeed, velocityAt(0f), 0.01f)
        assertEquals(maxSpeed, velocityAt(viewport), 0.01f)
        assertEquals(-maxSpeed, velocityAt(-200f), 0.01f)
        assertEquals(maxSpeed, velocityAt(2600f), 0.01f)
    }

    @Test
    fun `越靠边速度越快`() {
        assertTrue(kotlin.math.abs(velocityAt(20f)) > kotlin.math.abs(velocityAt(150f)))
        assertTrue(kotlin.math.abs(velocityAt(150f)) > 0f)
        assertTrue(kotlin.math.abs(velocityAt(1990f)) > kotlin.math.abs(velocityAt(1850f)))
    }

    @Test
    fun `视口高度非法时不滚动`() {
        assertEquals(0f, dragSelectScrollVelocityPxPerSecond(0f, 0f, 0f, 100f), 0.01f)
        assertEquals(0f, dragSelectScrollVelocityPxPerSecond(0f, -10f, 0f, 100f), 0.01f)
    }

    @Test
    fun `格子中心计算与命中一致`() {
        val cells = gridCells(rows = 2)
        cells.forEach { cell ->
            assertEquals(cell.index, dragSelectIndexAt(cellCenter(cell), cells))
        }
    }
}
