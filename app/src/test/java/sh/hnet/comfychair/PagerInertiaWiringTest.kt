package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 横向惯性档位的「接线还在不在」源码级钉子（v4.16.14 复核 GATE-1）。
 *
 * ## 为什么需要这一层测试
 *
 * [sh.hnet.comfychair.ui.screens.ViewerPagerSnapDistanceTest] 只能证明
 * `ViewerPagerSnapDistance` 对 `allowInertia` 这个开关**敏感**，证明不了屏幕真的
 * 按规格传了值。GATE-1 就是这么漏的：接线传的是裸 `threeLevelEnabled`
 * （设置项、默认 true），而查看器初始态永远是全屏 —— 于是"惯性只在队列模式"
 * 这条规格形同虚设，用户可见现象一条都没消除，而类级单测全绿。
 *
 * 这里按 [ViewerExitWiringTest] 同款写法直接读 MediaViewerScreen.kt 的源码，
 * 钉住两件事：
 * 1. 判据函数 [sh.hnet.comfychair.ui.screens.pagerInertiaAllowed] 的实现确实是
 *    "队列功能开启 **且** 当前处于队列态"，不是单独任一条件；
 * 2. 屏幕的接线传的是 `pagerInertiaAllowed(threeLevelEnabled, sideBySideActive)`，
 *    **不是**旧的裸 `threeLevelEnabled`。
 *
 * MediaViewerScreen 需要 Compose 运行时（pager、pointerInput、graphicsLayer），
 * JVM 单测起不来，所以"生产代码有没有真的接上去"只能在源码层钉。
 * 断言按**空白归一化后的子串**匹配：调整缩进/换行不会误报，改判定口径必红。
 */
class PagerInertiaWiringTest {

    private val screenPath = "src/main/java/sh/hnet/comfychair/ui/screens/MediaViewerScreen.kt"

    /** 单测工作目录是模块根（app/），与 ViewerExitWiringTest / FeatureWiringContractTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 把连续空白折叠成单个空格：只忽略排版，不忽略语义。 */
    private fun normalized(body: String): String =
        body.replace(Regex("\\s+"), " ").trim()

    private fun containsNormalized(body: String, fragment: String): Boolean =
        normalized(body).contains(normalized(fragment))

    @Test
    fun `惯性判据是队列功能开启且当前处于队列态`() {
        val body = source(screenPath)
        // 判据函数体必须同时要求两个条件（顺序固定为功能开关在前，便于阅读）
        assertTrue(
            "pagerInertiaAllowed 的实现必须同时要求 threeLevelEnabled 与 sideBySideActive，" +
                "只留其中一个就会重现 GATE-1（默认功能开着 → 全屏仍然跨多页）",
            containsNormalized(
                body,
                "internal fun pagerInertiaAllowed(threeLevelEnabled: Boolean, sideBySideActive: Boolean): Boolean = threeLevelEnabled && sideBySideActive"
            )
        )
    }

    @Test
    fun `屏幕接线传的是当前队列态而不是功能开关`() {
        val body = source(screenPath)
        // 正例：接线必须经由判据函数传入"当前队列态"
        assertTrue(
            "屏幕的 allowInertia 必须绑到 pagerInertiaAllowed(threeLevelEnabled, sideBySideActive)；" +
                "绑成裸 threeLevelEnabled 等于默认用户全屏也能跨多页（GATE-1）",
            containsNormalized(
                body,
                "allowInertia = { pagerInertiaAllowed(threeLevelEnabled, sideBySideActive) }"
            )
        )
        // 反例：旧的错误绑法不许回潮
        assertFalse(
            "allowInertia 不得再绑裸 threeLevelEnabled —— 设置项默认 true，" +
                "而查看器初始态是全屏，跨页惯性会原样出现在全屏单图上",
            containsNormalized(body, "allowInertia = { threeLevelEnabled }")
        )
    }

    @Test
    fun `吸附对象仍然按页数与惯性开关构造`() {
        val body = source(screenPath)
        // 防止"判据函数写了但没接上"：构造点两个具名参数都要在
        assertTrue(
            "ViewerPagerSnapDistance 的构造必须显式传 pageCountProvider 与 allowInertia 两个具名参数",
            containsNormalized(body, "ViewerPagerSnapDistance(") &&
                containsNormalized(body, "pageCountProvider = { pagerState.pageCount }") &&
                containsNormalized(body, "allowInertia = {")
        )
    }
}