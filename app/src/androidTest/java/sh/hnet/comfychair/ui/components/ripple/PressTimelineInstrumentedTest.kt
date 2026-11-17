package sh.hnet.comfychair.ui.components.ripple

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * 顶栏按压时序"快进 → 定格 → 慢出"的 **instrumented 可信时钟测试**（B-A1b）。
 *
 * 复验者用录屏测出"松手后 16~33ms 直接回静置"——录屏对静止帧不编码、测不准，
 * 本测试用 composeTestRule.mainClock（虚拟帧时钟）逐步推进，直接读取
 * [rememberPressTimelineProgress] 输出的 Animatable 值，对三段时序做精确断言：
 * - 松手后 150ms 内强度不下降（定格段契约）；
 * - 松手后 300ms 已回到 0（慢出完成契约）。
 *
 * internal 的 rememberPressTimelineProgress 对 androidTest 可见（AGP friend module），
 * 直接渲染最小宿主即可，不需要真实按钮/SharedPreferences。
 */
@RunWith(AndroidJUnit4::class)
class PressTimelineInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 宿主把 Animatable 引用挂出来，测试线程直接读 .value（动画每帧更新）。 */
    private val progressHolder = AtomicReference<Animatable<Float, AnimationVector1D>?>(null)

    /** 按压态由测试线程写入（写全局快照，advanceTimeBy 推进帧时主线程应用重组）。 */
    private lateinit var pressedState: MutableState<Boolean>

    private fun setContent() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            pressedState = remember { mutableStateOf(false) }
            val progress = rememberPressTimelineProgress(pressedState.value)
            SideEffect { progressHolder.set(progress) }
        }
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.mainClock.advanceTimeBy(16)
        // 宿主 Animatable 引用就位
        composeRule.runOnIdle { }
        check(progressHolder.get() != null) { "宿主未把 progress 挂出" }
    }

    private fun press(v: Boolean) {
        composeRule.runOnIdle { pressedState.value = v }
    }

    private fun value(): Float = progressHolder.get()!!.value

    @Test
    fun `慢按松手_定格150ms不衰减_300ms内慢出归零`() {
        setContent()

        // ---- 按下 → 快进 60ms 到满强度 ----
        press(true)
        composeRule.mainClock.advanceTimeBy(60)
        composeRule.mainClock.advanceTimeBy(60)  // 再留一帧余量，确保快进动画收尾帧已跑
        assertEquals("按下 60ms 后应到满强度", 1f, value(), 0.001f)

        // ---- 松手 → 定格段 ----
        press(false)
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals("松手后 100ms 应仍满强度（定格段内）", 1f, value(), 0.001f)

        composeRule.mainClock.advanceTimeBy(49)
        assertEquals("松手后 149ms 应仍满强度（定格段边界前）", 1f, value(), 0.001f)

        // ---- 慢出完成 ----
        composeRule.mainClock.advanceTimeBy(200)
        assertEquals("松手后 349ms 应已回到 0（慢出 150ms 完成）", 0f, value(), 0.001f)
    }

    @Test
    fun `快击松手_白框补满强度后定格_慢出按时序归零`() {
        setContent()

        // ---- 按下 20ms 就松手（快进只走了约 1/3）----
        // 注意：虚拟帧时钟帧间隔 ≈16.67ms，advanceTimeBy(16) 不足一帧；两帧 ≈48ms
        press(true)
        composeRule.mainClock.advanceTimeBy(24)  // 帧1：重组应用，LaunchedEffect(true) 协程启动
        composeRule.mainClock.advanceTimeBy(24)  // 帧2+：快进动画前几帧
        val midValue = value()
        assertTrue(
            "快进 20ms 应处于中间强度（实测 $midValue）",
            midValue in 0.05f..0.98f,
        )

        // ---- 松手：补完快进段 → 定格 ----
        press(false)
        composeRule.mainClock.advanceTimeBy(150)
        assertEquals("快击松手后 150ms 应已补完到满强度并定格", 1f, value(), 0.001f)

        // ---- 慢出完成（补完 ≤60ms + 定格 150ms + 慢出 150ms = ≤360ms）----
        composeRule.mainClock.advanceTimeBy(250)
        assertEquals("快击松手后 400ms 应已回到 0", 0f, value(), 0.001f)
    }
}
