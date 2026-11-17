package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ripple.PRESS_BG_DISABLED_FACTOR
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [GenerationTopBarActions] 四按钮（3D / Save / Share / Menu）按压反馈一致性的
 * **instrumented 测试**（B-A1b 问题 3）。
 *
 * 复验者无法构造 hasResult=true（模拟器上生成提交被 preflight 拦截），Save/Share
 * 的 enabled 态手感从未被验证过。本测试直接渲染顶栏动作组（不需要真实生成结果），
 * 用真实手势（down/up）+ 可信虚拟时钟（mainClock.autoAdvance=false）+ 像素采样验证：
 * - canExport=true：四按钮按下后 pressBg 达到**同一目标强度**（深色统一档 White 13%，
 *   背景红通道数学预期 ≈ 0.13×255+0.87×28 ≈ 57），松手后定格 150ms 不衰减、300ms 内
 *   慢出归零 —— 同时成为按压时序的回归保护；
 * - canExport=false：Save/Share **半强度反馈**（v4.16.6 S1：禁用仍画底色但减半
 *   13%×0.5=6.5% ≈ 红通道 43，且无缩放——底色画到触控区边缘）。
 *
 * 读数方式：captureToImage 采样按钮触控区内"图标上方"像素（该点只有页面背景 +
 * pressBg 叠加），从红通道灰阶差算出强度。
 */
@RunWith(AndroidJUnit4::class)
class GenerationTopBarActionsPressConsistencyInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 深色统一档 pressBg 满强度的红通道数学预期：0.13×255 + 0.87×28 ≈ 57.5。 */
    private val expectedPressedRed = (0.13f * 255f + 0.87f * 28f).roundToInt()

    /** 禁用态半强度（v4.16.6 S1：13%×0.5=6.5%）的红通道数学预期：0.065×255 + 0.935×28 ≈ 42.8。 */
    private val expectedDisabledPressedRed =
        (PRESS_BG_DISABLED_FACTOR * 0.13f * 255f + (1f - PRESS_BG_DISABLED_FACTOR * 0.13f) * 28f)
            .roundToInt()

    @Before
    fun setUp() {
        // 三个皮肤/反馈开关显式置默认开，防设备旧 SharedPreferences 干扰
        AppSettings.setUiSkinV2Enabled(context, true)
        AppSettings.setRippleDotFeedbackEnabled(context, true)
        AppSettings.setPressFeedbackUnified(context, true)
    }

    private fun setContent(hasResult: Boolean) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1C1B1F)),
                    contentAlignment = Alignment.Center,
                ) {
                    GenerationTopBarActions(
                        is3DWorkflow = true,
                        threeDResult = null,
                        onSettings = {},
                        onLogout = {},
                        hasResult = hasResult,
                        onSave = {},
                        onShare = {},
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { }
    }

    private fun nodeOf(desc: String): SemanticsNodeInteraction =
        composeRule.onNodeWithContentDescription(desc)

    private fun pixelRed(node: SemanticsNodeInteraction): Int {
        val bitmap = node.captureToImage()
        // 节点内相对坐标：横向中心、垂直中心上方 14dp（避开 22dp 图标与 12dp 圆角）
        val density = composeRule.density.density
        val px = bitmap.width / 2
        val py = (bitmap.height / 2 - 14 * density).roundToInt()
        return bitmap.asAndroidBitmap()
            .getPixel(px.coerceIn(0, bitmap.width - 1), py.coerceIn(0, bitmap.height - 1))
            .let(android.graphics.Color::red)
    }

    /** 采样触控区**下缘内 1dp** 的红通道（水平中心处是直边段，不受 12dp 圆角影响）。 */
    private fun edgePixelRed(node: SemanticsNodeInteraction): Int {
        val bitmap = node.captureToImage()
        val density = composeRule.density.density
        val px = bitmap.width / 2
        val py = (bitmap.height / 2 + 21 * density).roundToInt()
        return bitmap.asAndroidBitmap()
            .getPixel(px.coerceIn(0, bitmap.width - 1), py.coerceIn(0, bitmap.height - 1))
            .let(android.graphics.Color::red)
    }

    private fun advance(ms: Long) {
        composeRule.mainClock.advanceTimeBy(ms)
        composeRule.runOnIdle { }
    }

    /** 完整按压时序采样：返回 (idle, pressed, hold33, hold100, out300) 的红通道值。 */
    private fun pressTimeline(node: SemanticsNodeInteraction): IntArray {
        val idle = pixelRed(node)
        node.performTouchInput { down(center) }
        advance(60)
        advance(60)
        val pressed = pixelRed(node)
        node.performTouchInput { up() }
        advance(33)
        val hold33 = pixelRed(node)
        advance(67)
        val hold100 = pixelRed(node)
        advance(200)
        val out300 = pixelRed(node)
        return intArrayOf(idle, pressed, hold33, hold100, out300)
    }

    @Test
    fun `canExport为真_四按钮按压同一目标强度_松手定格与慢出一致`() {
        setContent(hasResult = true)
        val descs = listOf(
            context.getString(R.string.three_d_viewer_title),
            context.getString(R.string.button_save_to_gallery),
            context.getString(R.string.button_share),
            context.getString(R.string.content_description_menu),
        )
        val timelines = descs.map { desc -> desc to pressTimeline(nodeOf(desc)) }

        // ---- 每个按钮的三段时序 ----
        timelines.forEach { (desc, t) ->
            val (idle, pressed, hold33, hold100, out300) = listOf(t[0], t[1], t[2], t[3], t[4])
            assertTrue(
                "$desc 按下后应显著亮于静置（idle=$idle pressed=$pressed）",
                pressed - idle >= 15,
            )
            assertEquals("$desc 松手后 33ms 应仍满强度（定格段内，不得硬切）", pressed, hold33)
            assertEquals("$desc 松手后 100ms 应仍满强度（定格段内）", pressed, hold100)
            assertTrue(
                "$desc 松手后 300ms 应回到静置（idle=$idle out=$out300）",
                abs(out300 - idle) <= 3,
            )
            // 满强度红通道与数学预期一致（深色统一档 White 13%）
            assertTrue(
                "$desc 满强度应接近 White 13% 数学预期（实测 $pressed，预期 $expectedPressedRed）",
                abs(pressed - expectedPressedRed) <= 4,
            )
        }

        // ---- 四按钮一致性：满强度互差 ≤ 4 ----
        val pressedValues = timelines.map { it.second[1] }
        assertTrue(
            "四按钮满强度应一致（实测 ${pressedValues.joinToString()}）",
            (pressedValues.max() - pressedValues.min()) <= 4,
        )
    }

    @Test
    fun `canExport为假_Save与Share半强度反馈且无缩放`() {
        setContent(hasResult = false)

        listOf(
            context.getString(R.string.button_save_to_gallery),
            context.getString(R.string.button_share),
        ).forEach { desc ->
            val node = nodeOf(desc)
            val idle = pixelRed(node)
            node.performTouchInput { down(center) }
            advance(60)
            advance(60)
            // 中上 14dp 采样点：禁用态按压应达到半强度（满强度的一半 ≈ 43）
            val pressed = pixelRed(node)
            // 下缘内 1dp 采样点：底色应画到触控区边缘 —— 若禁用态误加了缩放，
            // 底色会向中心收缩（44dp×0.94 → 边缘内缩 ≈1.3dp），该点露出背景（28）
            val edge = edgePixelRed(node)
            node.performTouchInput { up() }
            advance(33)
            val hold33 = pixelRed(node)
            advance(67)
            val hold100 = pixelRed(node)
            advance(200)
            val out300 = pixelRed(node)

            assertTrue(
                "$desc 禁用态按压应有半强度反馈（idle=$idle pressed=$pressed " +
                    "预期 $expectedDisabledPressedRed）",
                abs(pressed - expectedDisabledPressedRed) <= 4,
            )
            assertTrue(
                "$desc 禁用态底色应画到边缘（无缩放，edge=$edge 预期 $expectedDisabledPressedRed）",
                abs(edge - expectedDisabledPressedRed) <= 6,
            )
            // 时序与满强度一致（60/150/150）：定格段不衰减、慢出归零
            assertEquals("$desc 松手后 33ms 应仍在定格段", pressed, hold33)
            assertEquals("$desc 松手后 100ms 应仍在定格段", pressed, hold100)
            assertTrue(
                "$desc 松手后 300ms 应回到静置（idle=$idle out=$out300）",
                abs(out300 - idle) <= 3,
            )
        }
    }
}
