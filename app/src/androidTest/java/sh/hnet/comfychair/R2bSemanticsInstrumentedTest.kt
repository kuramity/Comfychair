package sh.hnet.comfychair

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import kotlin.math.abs

/**
 * R2b 定向复验临时取证测试（跑完即删，不入库）。
 * 取证目标：两态（居中/回退）下 merged/unmerged 语义树中 "Queue menu" 的挂载位置，
 * 以及按钮几何（图标中心 vs 按钮中心偏差、按钮 bounds）。
 */
@RunWith(AndroidJUnit4::class)
class R2bSemanticsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "R2bVerify"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        AppSettings.setTrailingIconCentered(context, true)
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                GenerationButton(
                    queueSize = 0,
                    isExecuting = false,
                    isEnabled = true,
                    onGenerate = {},
                    onCancelCurrent = {},
                    onClearQueue = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hasClickAction(n: androidx.compose.ui.semantics.SemanticsNode): Boolean =
        n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsActions.OnClickLabel) ||
            n.config.contains(SemanticsActions.Toggle)

    private fun dumpQueueMenuNodes(state: String) {
        val merged = composeRule.onAllNodesWithContentDescription("Queue menu", useUnmergedTree = false)
            .fetchSemanticsNodes()
        val unmerged = composeRule.onAllNodesWithContentDescription("Queue menu", useUnmergedTree = true)
            .fetchSemanticsNodes()
        Log.d(tag, "$state mergedCount=${merged.size} unmergedCount=${unmerged.size}")
        merged.forEachIndexed { i, n ->
            Log.d(tag, "$state merged[$i] cd=${n.config.getOrNull(SemanticsProperties.ContentDescription)} " +
                "clickable=${hasClickAction(n)} bounds=${n.boundsInRoot}")
        }
        unmerged.forEachIndexed { i, n ->
            Log.d(tag, "$state unmerged[$i] cd=${n.config.getOrNull(SemanticsProperties.ContentDescription)} " +
                "clickable=${hasClickAction(n)} bounds=${n.boundsInRoot}")
        }
        return
    }

    private fun logGeometry(state: String, density: Float, assertCentered: Boolean) {
        val icon = composeRule.onNodeWithTag("trailing-arrow", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val button = composeRule.onNodeWithTag("trailing-button", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val dx = abs(icon.center.x - button.center.x) / density
        val dy = abs(icon.center.y - button.center.y) / density
        Log.d(tag, "$state buttonBounds=${button.width}x${button.height}px(=${button.width / density}x${button.height / density}dp) " +
            "iconCenter=(${icon.center.x}, ${icon.center.y}) buttonCenter=(${button.center.x}, ${button.center.y}) " +
            "dx=${dx}dp dy=${dy}dp")
        if (assertCentered) {
            assertTrue("$state 中心偏差超限 dx=$dx dy=$dy", dx <= 1f && dy <= 1f)
        }
    }

    @Test
    fun `居中态语义与几何取证`() {
        setContent()
        val density = composeRule.density.density
        dumpQueueMenuNodes("居中态-收起")
        logGeometry("居中态-收起", density, assertCentered = true)
        val merged = composeRule.onAllNodesWithContentDescription("Queue menu", useUnmergedTree = false)
            .fetchSemanticsNodes()
        assertTrue("居中态 merged 树 Queue menu 节点数应为 1，实际 ${merged.size}", merged.size == 1)
        assertTrue("居中态 merged 节点应带点击动作", hasClickAction(merged[0]))

        // 展开菜单后再取证
        composeRule.onNodeWithTag("trailing-button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        dumpQueueMenuNodes("居中态-展开")
        logGeometry("居中态-展开", density, assertCentered = true)
    }

    @Test
    fun `回退态语义与几何取证`() {
        AppSettings.setTrailingIconCentered(context, false)
        composeRule.waitForIdle()
        setContent()
        val density = composeRule.density.density
        dumpQueueMenuNodes("回退态-收起")
        logGeometry("回退态-收起", density, assertCentered = false)
        val merged = composeRule.onAllNodesWithContentDescription("Queue menu", useUnmergedTree = false)
            .fetchSemanticsNodes()
        Log.d(tag, "回退态 merged 数量=${merged.size}")
        assertTrue("回退态 merged 树 Queue menu 应存在", merged.isNotEmpty())
        assertTrue("回退态 merged 命中节点应带点击动作（否则 TalkBack 读到名称却不可操作）",
            merged.all { hasClickAction(it) })

        composeRule.onNodeWithTag("trailing-button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        dumpQueueMenuNodes("回退态-展开")
    }
}
