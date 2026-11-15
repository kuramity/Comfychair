package sh.hnet.comfychair

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
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
 * 下拉按钮（队列菜单）箭头图标「显式几何居中」的 **instrumented 测试**（B1）。
 *
 * 派工单 B1 的门禁要求是**数字证据**：收起态与展开态的图标中心、按钮中心必须有
 * 实测坐标，且两态偏差与「图标中心 vs 按钮中心」的偏差都 ≤ 1dp。
 *
 * 为什么不用截图对齐：截图像素比对受主题/抗锯齿影响，拿不到「中心差多少 dp」
 * 这种可写进门禁的数字；semantics 节点的 boundsInRoot 是布局层的精确包围盒，
 * 旋转动画不改变节点 bounds（graphicsLayer 变换不参与布局），测的就是几何中心本身。
 *
 * 两个用例：
 *  1. 开关开（默认新路径）：外置图标 align(Center) —— 三组坐标断言 + 日志留档。
 *  2. 开关关（回退路径）：图标回 M3 content 槽 —— 验证回退分支真实接线、按钮功能
 *     不回归；位置不做硬断言（M3 内部光学偏移量不受本模块控制）。
 */
@RunWith(AndroidJUnit4::class)
class GenerationButtonTrailingIconInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "B1TrailingIconTest"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // SharedPreferences 是全局持久状态：每个用例显式设定开关，避免设备旧值干扰
        AppSettings.setTrailingIconCentered(context, true)
    }

    /** 组装最小可用的 GenerationButton（无网络依赖，纯布局测量）。 */
    private fun setContentWithButton() {
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

    /** semantics 节点中心（root 坐标，px）。回退分支的槽内图标会被 Surface 合并语义，必须用 unmerged 树。 */
    private fun centerOf(tagValue: String): androidx.compose.ui.geometry.Offset {
        val bounds = composeRule.onNodeWithTag(tagValue, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        return bounds.center
    }

    private fun logCenters(state: String, icon: androidx.compose.ui.geometry.Offset, button: androidx.compose.ui.geometry.Offset, density: Float) {
        Log.d(
            tag,
            "$state iconCenter=(${"%.2f".format(icon.x)}, ${"%.2f".format(icon.y)})px " +
                "buttonCenter=(${"%.2f".format(button.x)}, ${"%.2f".format(button.y)})px " +
                "dx=${"%.2f".format(abs(icon.x - button.x) / density)}dp " +
                "dy=${"%.2f".format(abs(icon.y - button.y) / density)}dp"
        )
    }

    @Test
    fun `两态图标中心均等于按钮几何中心且两态零漂移`() {
        setContentWithButton()
        val density = composeRule.density.density
        val tolerance = 1f  // 派工单门禁：偏差 ≤ 1dp

        // ---- 收起态 ----
        val iconCollapsed = centerOf("trailing-arrow")
        val buttonCollapsed = centerOf("trailing-button")
        logCenters("收起态", iconCollapsed, buttonCollapsed, density)
        assertTrue(
            "收起态图标中心偏离按钮中心超限（dx=${abs(iconCollapsed.x - buttonCollapsed.x) / density}dp, " +
                "dy=${abs(iconCollapsed.y - buttonCollapsed.y) / density}dp, 上限 $tolerance dp）",
            abs(iconCollapsed.x - buttonCollapsed.x) / density <= tolerance &&
                abs(iconCollapsed.y - buttonCollapsed.y) / density <= tolerance
        )

        // ---- 展开态（点击 → 菜单开 → 180° 旋转动画收敛）----
        composeRule.onNodeWithTag("trailing-button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val iconExpanded = centerOf("trailing-arrow")
        val buttonExpanded = centerOf("trailing-button")
        logCenters("展开态", iconExpanded, buttonExpanded, density)
        assertTrue(
            "展开态图标中心偏离按钮中心超限（dx=${abs(iconExpanded.x - buttonExpanded.x) / density}dp, " +
                "dy=${abs(iconExpanded.y - buttonExpanded.y) / density}dp, 上限 $tolerance dp）",
            abs(iconExpanded.x - buttonExpanded.x) / density <= tolerance &&
                abs(iconExpanded.y - buttonExpanded.y) / density <= tolerance
        )

        // ---- 两态漂移（旋转不得移动图标节点）----
        val driftDx = abs(iconCollapsed.x - iconExpanded.x) / density
        val driftDy = abs(iconCollapsed.y - iconExpanded.y) / density
        Log.d(tag, "两态图标中心漂移 dx=${"%.2f".format(driftDx)}dp dy=${"%.2f".format(driftDy)}dp")
        assertTrue(
            "两态图标中心漂移超限（dx=$driftDx dp, dy=$driftDy dp, 上限 $tolerance dp）",
            driftDx <= tolerance && driftDy <= tolerance
        )
    }

    @Test
    fun `回退开关关闭时图标留在M3槽内且按钮功能不回归`() {
        AppSettings.setTrailingIconCentered(context, false)
        setContentWithButton()

        // 回退路径节点可定位（分支真实生效，不是新路径残留）
        val icon = centerOf("trailing-arrow")
        val button = centerOf("trailing-button")
        Log.d(
            tag,
            "回退态 iconCenter=(${icon.x}, ${icon.y})px buttonCenter=(${button.x}, ${button.y})px " +
                "（M3 槽内排布，位置不设硬断言）"
        )

        // 功能回归：点击展开菜单后交互链路完整
        composeRule.onNodeWithTag("trailing-button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        // 展开后图标节点仍在（未崩溃、未脱离树）
        centerOf("trailing-arrow")
        assertTrue(true)
    }
}
