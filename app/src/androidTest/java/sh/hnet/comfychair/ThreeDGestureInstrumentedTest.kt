package sh.hnet.comfychair

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.ui.components.shared.Model3DScene
import java.io.File
import java.util.Collections

/**
 * 3D 查看器手势的 **instrumented 测试**（跑在模拟器/真机上）。
 *
 * 为什么要它：adb 的 `input swipe` 只能注入单指，模拟器无法合成多点触控
 * （2026-11-09 试过 sendevent / 双设备 / 控制台 MT 等 5 种手段均失败）。
 * 唯一能真正验证"双指捏合"的路径是**在设备进程内合成 MotionEvent**，
 * 而这只有 instrumented 测试（`uiAutomation.injectInputEvent`）做得到。
 *
 * 判据为什么不是截图：SceneView 渲染在 SurfaceView 里，Compose 的
 * `captureToImage()` 抓不到它的内容（实测 bitmap 尺寸为 0）。
 * 所以改用 [Model3DScene] 的手势探针，直接断言：
 *   1. 双指事件被识别为 scale（`scaleBegin` + `scale:*` 且缩放比例 ≠ 1）
 *   2. 单指事件被识别为 scroll（旋转走 orbit）
 */
@RunWith(AndroidJUnit4::class)
class ThreeDGestureInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "ThreeDGestureTest"

    /** 探针收集：线程安全的同步列表（回调来自主线程，断言在测试线程） */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun prepareModel(): File {
        // 测试资产在 **test APK** 里，必须用 instrumentation.context（test context）读；
        // 用 targetContext 会去读被测 App 的 assets，必然 FileNotFoundException（踩过）。
        val inst = InstrumentationRegistry.getInstrumentation()
        val dst = File(inst.targetContext.cacheDir, "test_triangle.glb")
        inst.context.assets.open("test_triangle.glb").use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        return dst
    }

    private fun twoPointerEvent(
        action: Int,
        actionIndex: Int,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f }
        )
        val packed = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            downTime, eventTime, packed, 2, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    private fun inject(event: MotionEvent) {
        // 系统级注入（injectInputEvent）在 Compose + AndroidView + SurfaceView 这条链上
        // **实测打不通**（探针收不到任何回调）。改为拿到真实 SceneView 实例直接派发，
        // 同样是真实 View 的 onTouchEvent → GestureDetector 全链路，只是绕过了输入分发器。
        val view = sceneView
        if (view != null) {
            composeRule.runOnUiThread { view.onTouchEvent(event) }
        }
        event.recycle()
        SystemClock.sleep(110)
    }

    /** 在 Activity 的 View 树里递归找 SceneView（Scene composable 内部创建的实例） */
    private fun findSceneView(root: android.view.View?): io.github.sceneview.SceneView? {
        if (root == null) return null
        if (root is io.github.sceneview.SceneView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findSceneView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private var sceneView: io.github.sceneview.SceneView? = null

    /** 等到 SceneView 出现在 View 树里（首帧后才会有） */
    private fun awaitSceneView(timeoutMs: Long = 8000): io.github.sceneview.SceneView? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var found: io.github.sceneview.SceneView? = null
            composeRule.runOnUiThread {
                // 从当前 RESUMED Activity 的 decorView 递归查找。
                // Scene composable 内部用 AndroidView 创建 SceneView，不对外暴露引用，
                // 只能从 View 树里找（没有别的入口）。
                val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                val activity = activities.firstOrNull() as? android.app.Activity
                found = findSceneView(activity?.window?.decorView)
            }
            if (found != null) {
                sceneView = found
                return found
            }
            SystemClock.sleep(300)
        }
        return null
    }

    private fun screenCenter(): Pair<Float, Float> {
        val dm = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics
        return Pair(dm.widthPixels / 2f, dm.heightPixels / 2f)
    }

    /** 双指外扩（放大） */
    private fun pinchOut(cx: Float, cy: Float) {
        val t0 = SystemClock.uptimeMillis()
        inject(twoPointerEvent(MotionEvent.ACTION_DOWN, 0, cx - 40f, cy, cx + 40f, cy, t0, t0))
        inject(twoPointerEvent(MotionEvent.ACTION_POINTER_DOWN, 1, cx - 40f, cy, cx + 40f, cy, t0, t0 + 40))
        for (step in 1..6) {
            val d = 40f + step * 22f
            inject(
                twoPointerEvent(
                    MotionEvent.ACTION_MOVE, 0,
                    cx - d, cy, cx + d, cy,
                    t0, t0 + 40L + step * 70L
                )
            )
        }
        inject(twoPointerEvent(MotionEvent.ACTION_POINTER_UP, 1, cx - 172f, cy, cx + 172f, cy, t0, t0 + 520))
        inject(twoPointerEvent(MotionEvent.ACTION_UP, 0, cx - 172f, cy, cx + 172f, cy, t0, t0 + 560))
    }

    @Test
    fun pinchOutIsRecognizedAsScaleGesture() {
        val model = prepareModel()
        events.clear()
        composeRule.setContent {
            Model3DScene(
                file = model,
                modifier = Modifier.fillMaxSize(),
                probe = { msg -> events.add(msg) }
            )
        }
        composeRule.waitForIdle()
        SystemClock.sleep(2600) // 等 Filament 首帧与 framing
        Log.w(tag, "probe before gestures: " + synchronized(events) { events.toList() })
        val view = awaitSceneView()
        assertTrue(
            "View 树里找不到 SceneView。探针：${synchronized(events) { events.toList() }}",
            view != null
        )

        val (cx, cy) = screenCenter()
        Log.w(tag, "pinch at ($cx,$cy)")
        pinchOut(cx, cy)
        composeRule.waitForIdle()
        SystemClock.sleep(900)

        val snapshot = synchronized(events) { events.toList() }
        Log.w(tag, "probe events: $snapshot")

        assertTrue("未收到任何手势事件（探针未接到回调）", snapshot.isNotEmpty())
        assertTrue(
            "双指事件没有被识别为缩放手势，收到：$snapshot",
            snapshot.any { it.startsWith("scaleBegin") }
        )
        // scale:* 里的比例必须偏离 1（说明真的在缩放，而不是原地空转）
        val factors = snapshot.filter { it.startsWith("scale:") }
            .mapNotNull { it.removePrefix("scale:").toFloatOrNull() }
        assertTrue("未收到 scale 比例回调", factors.isNotEmpty())
        assertTrue(
            "缩放比例始终为 1，手势没有产生实际缩放：$factors",
            factors.any { kotlin.math.abs(it - 1f) > 0.01f }
        )
    }

    @Test
    fun singleFingerDragIsRecognizedAsScroll() {
        val model = prepareModel()
        events.clear()
        composeRule.setContent {
            Model3DScene(
                file = model,
                modifier = Modifier.fillMaxSize(),
                probe = { msg -> events.add(msg) }
            )
        }
        composeRule.waitForIdle()
        SystemClock.sleep(2600)
        Log.w(tag, "probe before gestures: " + synchronized(events) { events.toList() })
        val view = awaitSceneView()
        assertTrue(
            "View 树里找不到 SceneView。探针：${synchronized(events) { events.toList() }}",
            view != null
        )

        val (cx, cy) = screenCenter()
        val t0 = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        fun single(x: Float, action: Int, dt: Long): MotionEvent {
            val c = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; y = cy; pressure = 1f; size = 1f
            })
            return MotionEvent.obtain(t0, t0 + dt, action, 1, props, c, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        inject(single(cx + 110f, MotionEvent.ACTION_DOWN, 0))
        for (step in 1..6) {
            inject(single(cx + 110f - step * 35f, MotionEvent.ACTION_MOVE, step * 70L))
        }
        inject(single(cx - 100f, MotionEvent.ACTION_UP, 480))
        composeRule.waitForIdle()
        SystemClock.sleep(900)

        val snapshot = synchronized(events) { events.toList() }
        Log.w(tag, "scroll probe: $snapshot")
        assertTrue(
            "单指拖动没有被识别为 scroll（旋转走的就是这条路径），收到：$snapshot",
            snapshot.any { it.startsWith("scroll:") }
        )
    }
}
