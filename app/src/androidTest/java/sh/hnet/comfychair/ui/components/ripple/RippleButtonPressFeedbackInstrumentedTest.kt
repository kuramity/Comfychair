package sh.hnet.comfychair.ui.components.ripple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.storage.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 真实 [RippleIconButton] 按压底色（pressBg）的时序 instrumented 测试（B-A1b）。
 *
 * 隔离层（rememberPressTimelineProgress）已由 PressTimelineInstrumentedTest 钉住；
 * 本测试走完整集成链：真实手势（performTouchInput down/up）→ interactionSource →
 * LaunchedEffect 时序 → drawBehind 画 pressBg → 窗口像素，验证"松手后定格 150ms
 * 再慢出 150ms"在真实组件上生效（复验者录屏测出松手后 16~33ms 硬切回静置）。
 *
 * 读数方式：captureToImage 采样按钮触控区内"图标上方"的像素 —— 该点只有
 * 页面背景 + pressBg（White 13% 深色统一档）叠加，可从灰阶差算出强度。
 */
@RunWith(AndroidJUnit4::class)
class RippleButtonPressFeedbackInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // 三个皮肤/反馈开关显式置默认开，防设备旧 SharedPreferences 干扰
        AppSettings.setUiSkinV2Enabled(context, true)
        AppSettings.setRippleDotFeedbackEnabled(context, true)
        AppSettings.setPressFeedbackUnified(context, true)
    }

    /** 按钮触控区内"图标上方"采样点的像素（该点只有背景 + pressBg 叠加）。 */
    private fun samplePressBgPixel(node: androidx.compose.ui.test.SemanticsNodeInteraction): Int {
        // captureToImage 返回节点自身区域的位图（非整窗），坐标用节点内相对位置：
        // 横向中心、垂直中心上方 14dp —— 避开 22dp 图标（半高 11dp）与 12dp 圆角
        val bitmap = node.captureToImage()
        val density = composeRule.density.density
        val px = bitmap.width / 2
        val py = (bitmap.height / 2 - 14.dp.value * density).roundToInt()
        val clampedX = px.coerceIn(0, bitmap.width - 1)
        val clampedY = py.coerceIn(0, bitmap.height - 1)
        return bitmap.asAndroidBitmap().getPixel(clampedX, clampedY)
    }

    private fun pixelRed(value: Int): Int = android.graphics.Color.red(value)

    private fun setContent() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1C1B1F)),
                    contentAlignment = Alignment.Center,
                ) {
                    RippleIconButton(onClick = {}, enabled = true) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "测试按钮",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { }
    }

    private fun node() = composeRule.onNodeWithContentDescription("测试按钮")

    private fun advance(ms: Long) {
        composeRule.mainClock.advanceTimeBy(ms)
        composeRule.runOnIdle { }
    }

    @Test
    fun `真实按钮_按下满强度_松手33ms不硬切_定格150ms_慢出归零`() {
        setContent()
        val node = node()

        // ---- 静置像素 ----
        val idle = pixelRed(samplePressBgPixel(node))

        // ---- 按下 → 快进 60ms 到满强度 ----
        node.performTouchInput { down(center) }
        advance(60)
        advance(60)
        val pressed = pixelRed(samplePressBgPixel(node))
        assertTrue(
            "按下后应显著亮于静置（idle=$idle pressed=$pressed）",
            pressed - idle >= 15,
        )

        // ---- 松手 → 硬切检测点（复验录屏测出松手后 16~33ms 回静置）----
        node.performTouchInput { up() }
        advance(33)
        val hold33 = pixelRed(samplePressBgPixel(node))
        assertEquals(
            "松手后 33ms 应仍满强度（定格段内，不得硬切回落；idle=$idle pressed=$pressed hold33=$hold33）",
            pressed, hold33,
        )

        advance(67)
        val hold100 = pixelRed(samplePressBgPixel(node))
        assertEquals(
            "松手后 100ms 应仍满强度（定格段内，实测 $hold100）",
            pressed, hold100,
        )

        // ---- 慢出完成 ----
        advance(200)
        val out = pixelRed(samplePressBgPixel(node))
        assertTrue(
            "松手后 300ms 应回到静置（idle=$idle out=$out）",
            abs(out - idle) <= 3,
        )
    }
}
