package sh.hnet.comfychair

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * 3D 查看器的**负向测试**（无效模型 / 异常路径）。
 *
 * 用户要求的"无效模型、缺失资源、异常路径"三条里，前两条与这里对应：
 *  - 无效模型：0 字节、随机字节（gltfio 解析失败）
 *  - 异常路径：不存在的文件
 * 验收判据：**不能崩**，且必须给用户可读的错误提示（`three_d_render_failed`）。
 */
@RunWith(AndroidJUnit4::class)
class ThreeDNegativeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "ThreeDNegativeTest"
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun cacheDir(): File =
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun assertFailureShown(hint: String) {
        composeRule.waitForIdle()
        SystemClock.sleep(1800)
        val snapshot = synchronized(events) { events.toList() }
        Log.w(tag, "$hint probe: $snapshot")
        // 必须走到失败分支（不能停在 loading 圈上）
        assertTrue(
            "$hint：没有进入失败分支，探针=$snapshot",
            snapshot.any { it.startsWith("assetFailed") || it == "instanceNull" }
        )
        // 且界面上出现可读的错误文案。
        // 注意：不能写死中文——模拟器 locale 是 en-US 时会读 values/strings.xml（英文），
        // 必须从资源里取当前语言的实际文案再断言（第一版写死中文导致三条测试全红）。
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = ctx.getString(sh.hnet.comfychair.R.string.three_d_render_failed)
        composeRule.onNodeWithText(expected, substring = true).assertIsDisplayed()
    }

    @Test
    fun zeroByteModelShowsFailure() {
        val bad = File(cacheDir(), "bad_zero.glb")
        bad.writeBytes(ByteArray(0))
        events.clear()
        composeRule.setContent {
            Model3DScene(
                file = bad,
                modifier = Modifier.fillMaxSize(),
                probe = { msg -> events.add(msg) }
            )
        }
        assertFailureShown("0 字节模型")
    }

    @Test
    fun garbageBytesModelShowsFailure() {
        val bad = File(cacheDir(), "bad_garbage.glb")
        // 不是 GLB 的随机内容（magic 不对）
        bad.writeBytes(ByteArray(4096) { (it * 37 % 251).toByte() })
        events.clear()
        composeRule.setContent {
            Model3DScene(
                file = bad,
                modifier = Modifier.fillMaxSize(),
                probe = { msg -> events.add(msg) }
            )
        }
        assertFailureShown("垃圾字节文件")
    }

    @Test
    fun missingFileShowsFailure() {
        val gone = File(cacheDir(), "not_exists_${SystemClock.uptimeMillis()}.glb")
        events.clear()
        composeRule.setContent {
            Model3DScene(
                file = gone,
                modifier = Modifier.fillMaxSize(),
                probe = { msg -> events.add(msg) }
            )
        }
        assertFailureShown("文件不存在")
    }
}
