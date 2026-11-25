package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * N4-R 长按滑选的 **设备端 instrumented 测试**（线性连续区间语义）。
 *
 * 为什么要跑设备端：这套交互的关键不是「算得对」而是「手势抢得过滚动」——
 * Compose 的 Main 通道是子节点先消费，网格的 scrollable 会吃掉长按后的位移。
 * 只有在真实触摸分发链路里注入事件，才能证明「长按后拖，网格不跟着滚，而是整段选中」。
 *
 * 被测接线与 GalleryScreen 完全同一份代码（[rememberGalleryDragSelectModifier]）；
 * 这里把「整体设置选中集合」换成一个记账用的假实现（key = "k{index}"），格子换成带 testTag 的方块。
 *
 * **注**：所有坐标必须在 `performTouchInput { }` 之前算好。手势块里的每次
 * `fetchSemanticsNode()` 都是真实耗时操作，慢设备上足以让「按下 → 第一次移动」的
 * 真实时间超过 500ms 的长按阈值，把「快滑」误判成长按。
 */
@RunWith(AndroidJUnit4::class)
class GalleryDragSelectInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selected = Collections.synchronizedSet(mutableSetOf<String>())
    private val appliedLog = Collections.synchronizedList(mutableListOf<Set<String>>())
    private val taps = Collections.synchronizedList(mutableListOf<Int>())

    private var gridState: LazyGridState = LazyGridState()

    private fun setGrid(count: Int = 80, columns: Int = 2, clearSelection: Boolean = true) {
        if (clearSelection) selected.clear()
        appliedLog.clear()
        taps.clear()
        gridState = LazyGridState()
        val state = gridState
        composeRule.setContent {
            val dragSelectModifier = rememberGalleryDragSelectModifier(
                gridState = state,
                itemCount = { count },
                itemKeyAt = { index -> if (index in 0 until count) "k$index" else null },
                selectedKeys = { selected.toSet() },
                applySelection = { keys ->
                    selected.clear()
                    selected.addAll(keys)
                    appliedLog.add(keys)
                }
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = state,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("grid")
                    .then(dragSelectModifier)
            ) {
                items(count) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .aspectRatio(1f)
                            .background(if (index % 2 == 0) Color(0xFFDDDDDD) else Color(0xFFBBBBBB))
                            .testTag("cell_$index")
                            // 与生产代码同构：格子上只有 tap（长按已统一由容器处理）
                            .pointerInput(index) {
                                detectTapGestures { taps.add(index) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$index")
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun boundsOf(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** 格子中心：转成网格节点的本地坐标（performTouchInput 用的是节点本地坐标）。 */
    private fun cellCenter(index: Int): Offset {
        val center = boundsOf("cell_$index").center
        val gridTopLeft = boundsOf("grid").topLeft
        return Offset(center.x - gridTopLeft.x, center.y - gridTopLeft.y)
    }

    private fun grid() = composeRule.onNodeWithTag("grid")

    private fun selectedInts(): List<Int> =
        synchronized(selected) { selected.mapNotNull { it.removePrefix("k").toIntOrNull() } }.sorted()

    /**
     * 让真实时间流过去：长按阈值与边缘滚动循环（delay 16ms）都是真实时钟，
     * 合成事件的时间戳只是「事件时间」，不会推动协程里的 delay。
     */
    private fun pump(millis: Long) {
        Thread.sleep(millis)
    }

    @Test
    fun linearRangeSelectsEveryCellInBetweenIncludingSkippedOnes() {
        setGrid()
        val start = cellCenter(0)
        val end = cellCenter(4) // 一次跳到第 5 格：中间 1~4 没有任何中间事件
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            moveTo(end)
            advanceEventTime(40)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0, 1, 2, 3, 4), selectedInts())
        assertTrue("长按滑选不该掉回单击（否则会顺带打开查看器），tap 记录：$taps", taps.isEmpty())
    }

    @Test
    fun draggingBackShrinksRangeAndDeselectsLeavingCells() {
        setGrid()
        val start = cellCenter(0)
        val far = cellCenter(4)
        val back = cellCenter(2)
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            moveTo(far)
            advanceEventTime(40)
            // 往回缩到第 3 格：第 4 格应被取消
            moveTo(back)
            advanceEventTime(40)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0, 1, 2), selectedInts())
    }

    @Test
    fun reversedDragProducesSameRange() {
        setGrid()
        val start = cellCenter(4)
        val end = cellCenter(0)
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            moveTo(end)
            advanceEventTime(40)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0, 1, 2, 3, 4), selectedInts())
    }

    @Test
    fun baselineSelectionIsNeverClearedByDrag() {
        // baseline：两张不相邻的图先被选中（index 1 与 3，都在首屏内）
        selected.add("k1")
        selected.add("k3")
        setGrid(clearSelection = false)
        val start = cellCenter(4)
        val end = cellCenter(6)
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            moveTo(end)
            advanceEventTime(40)
            up()
        }
        composeRule.waitForIdle()

        val result = selectedInts()
        assertTrue("基线 k1/k3 被框选取消了：$result", 1 in result && 3 in result)
        assertEquals("区间 4..6 必须整段选中且与基线求并集", listOf(1, 3, 4, 5, 6), result)
    }

    @Test
    fun draggingToBottomEdgeAutoScrollsAndExtendsRange() {
        setGrid()
        val beforeIndex = gridState.firstVisibleItemIndex
        val beforeOffset = gridState.firstVisibleItemScrollOffset
        val start = cellCenter(0)
        val gridHeight = boundsOf("grid").height
        val gridWidth = boundsOf("grid").width
        // 压到底边感应区深处（离底 20px）：速度更快，滚动量足以跨过整行
        val edge = Offset(gridWidth / 2f, gridHeight - 20f)
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            moveTo(edge)
            advanceEventTime(40)
            pump(800)
            up()
        }
        composeRule.waitForIdle()

        println(
            "DIAG edgeScroll index=$beforeIndex→${gridState.firstVisibleItemIndex}" +
                " offset=$beforeOffset→${gridState.firstVisibleItemScrollOffset}" +
                " selected=${selectedInts()}"
        )
        assertTrue(
            "拖到底边后网格没有自动滚动：index=$beforeIndex→${gridState.firstVisibleItemIndex}" +
                " offset=$beforeOffset→${gridState.firstVisibleItemScrollOffset}",
            gridState.firstVisibleItemIndex > beforeIndex ||
                gridState.firstVisibleItemScrollOffset > beforeOffset
        )
        val result = selectedInts()
        assertTrue("边缘滚动后区间为空：$result", result.isNotEmpty())
        // 区间必须是从 0 开始的一段连续索引（起点固定、终点随滚动增长）
        assertEquals("区间不连续：$result", (0..result.last()).toList(), result)
        assertTrue("滚入的新格子没有进区间：$result", result.size >= 8)
        // 松手即停：再等一段时间，位置不能继续变
        val indexAfterUp = gridState.firstVisibleItemIndex
        val offsetAfterUp = gridState.firstVisibleItemScrollOffset
        pump(400)
        composeRule.waitForIdle()
        assertEquals("松手后仍在滚动（index）", indexAfterUp, gridState.firstVisibleItemIndex)
        assertEquals("松手后仍在滚动（offset）", offsetAfterUp, gridState.firstVisibleItemScrollOffset)
    }

    @Test
    fun plainLongPressSelectsExactlyOneCellAndDoesNotTap() {
        setGrid()
        val start = cellCenter(1)
        grid().performTouchInput {
            down(start)
            advanceEventTime(600)
            pump(650)
            up()
        }
        composeRule.waitForIdle()

        println("DIAG longPress selected=${selectedInts()} taps=$taps applied=$appliedLog")
        assertEquals(listOf(1), selectedInts())
        assertTrue("长按松手不该同时触发单击，tap 记录：$taps（按下点 $start）", taps.isEmpty())
    }

    @Test
    fun shortTapStillTapsAndSelectsNothing() {
        setGrid()
        val target = cellCenter(1)
        grid().performTouchInput {
            down(target)
            advanceEventTime(90)
            up()
        }
        composeRule.waitForIdle()

        println("DIAG shortTap selected=${selectedInts()} taps=$taps")
        assertTrue("单击没有被触发：taps=$taps", taps.contains(1))
        assertTrue("短按不该产生任何选中：${selectedInts()}", selected.isEmpty())
    }

    @Test
    fun standardSwipeScrollsWithoutSelectingAnything() {
        // 验收 11（正常滚动不误触）：用最规范的连续滑动，必须真的滚动，且不产生选中
        setGrid()
        val beforeIndex = gridState.firstVisibleItemIndex
        val beforeOffset = gridState.firstVisibleItemScrollOffset
        grid().performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        println(
            "DIAG swipeUp index=$beforeIndex→${gridState.firstVisibleItemIndex}" +
                " offset=$beforeOffset→${gridState.firstVisibleItemScrollOffset}" +
                " selected=${selectedInts()} taps=$taps"
        )
        assertTrue(
            "正常滑动没有滚动网格：index=$beforeIndex→${gridState.firstVisibleItemIndex}" +
                " offset=$beforeOffset→${gridState.firstVisibleItemScrollOffset}",
            gridState.firstVisibleItemIndex > beforeIndex ||
                gridState.firstVisibleItemScrollOffset > beforeOffset
        )
        assertTrue("正常滑动被误判成滑选：${selectedInts()}", selected.isEmpty())
        assertTrue("正常滑动不该产生单击：$taps", taps.isEmpty())
    }

    @Test
    fun quickDiscreteDragNeverSelects() {
        // 只有按下 + 两次跳跃式位移（间隔 60ms，远小于长按阈值）：不能进入滑选
        setGrid()
        val p0 = cellCenter(0)
        val p1 = cellCenter(2)
        val p2 = cellCenter(4)
        grid().performTouchInput {
            down(p0)
            advanceEventTime(60)
            moveTo(p1)
            advanceEventTime(60)
            moveTo(p2)
            advanceEventTime(60)
            up()
        }
        composeRule.waitForIdle()

        println(
            "DIAG quickDrag index=${gridState.firstVisibleItemIndex}" +
                " offset=${gridState.firstVisibleItemScrollOffset}" +
                " selected=${selectedInts()} taps=$taps"
        )
        assertTrue("跳跃式快滑被误判成滑选：${selectedInts()}", selected.isEmpty())
        assertTrue("跳跃式快滑不该产生单击：$taps", taps.isEmpty())
    }

    @Test
    fun longPressInTopContentPaddingSelectsNearestFirstCell() {
        // 顶部内边距（8dp）里长按、x 明确落在第 1 格上方：按「最近格子兜底」应落到第一格
        setGrid()
        val paddingPoint = Offset(20f, 4f)
        grid().performTouchInput {
            down(paddingPoint)
            advanceEventTime(600)
            pump(650)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(0), selectedInts())
    }

    @Test
    fun twoFingerPressNeverStartsDragSelect() {
        setGrid()
        val p0 = cellCenter(0)
        val p1 = cellCenter(1)
        grid().performTouchInput {
            down(0, p0)
            down(1, p1)
            advanceEventTime(600)
            pump(200)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("双指按下触发了滑选：${selectedInts()}", selected.isEmpty())
    }
}
