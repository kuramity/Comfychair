package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G6（任务书 12.2）与 G2 补漏的「接线还在不在」源码级钉子。
 *
 * 为什么沿用 [FeatureWiringContractTest] / [P2LongTailWiringTest] 的读源码写法：
 * 本轮核心断言是"**三条退出路径调用的是同一个清理入口**"，而 ImageViewer /
 * MediaViewerScreen 需要 Compose 运行时（pointerInput、graphicsLayer、
 * DisposableEffect），JVM 单测起不来。纯函数部分已由 ViewerExitCleanupTest 与
 * ViewerOverscrollTest 钉住；这里补上"生产代码有没有真的接上去"。
 *
 * 断言按**整行语句**比较（不折叠空白；注释里出现同名字样不算数）：
 * 把 `exitRegistry.fire(reason)` 改成注释、或把 BackHandler 换回裸 `onClose()`、
 * 或把 onPreFling 改回无条件 `return available`，本测试立刻变红。
 */
class ViewerExitWiringTest {

    private val screenPath = "src/main/java/sh/hnet/comfychair/ui/screens/MediaViewerScreen.kt"
    private val imageViewerPath = "src/main/java/sh/hnet/comfychair/ui/components/ImageViewer.kt"

    /** 单测工作目录是模块根（app/），与其他接线测试同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 是否存在**恰好这一条语句**（忽略行首缩进）。 */
    private fun hasStatement(body: String, statement: String): Boolean =
        body.lines().any { it.trim() == statement }

    /** 某条语句在文件里出现几次（按整行、忽略缩进）。 */
    private fun countStatement(source: String, statement: String): Int =
        source.lines().count { it.trim() == statement }

    /**
     * 取一个块的正文：从签名行起，到**与签名同缩进**的单独一行 `}` 为止。
     *
     * 用缩进配对而不是"下一个 4 空格右括号"：被取块的代码本身嵌在 pager /
     * NestedScrollConnection 里（缩进 20~32 空格），按固定缩进找结尾会把整段
     * 后面的代码都吞进来，断言就可能"因为错误的理由通过"。
     */
    private fun blockOf(source: String, signature: String): String {
        val lines = source.lines()
        val startIdx = lines.indexOfFirst { it.trim() == signature }
        assertTrue("源码里找不到签名行：$signature", startIdx >= 0)
        val indent = lines[startIdx].takeWhile { it == ' ' }.count()
        val closer = " ".repeat(indent) + "}"
        val builder = StringBuilder()
        for (i in startIdx until lines.size) {
            builder.appendLine(lines[i])
            if (i > startIdx && lines[i] == closer) return builder.toString()
        }
        return builder.toString()
    }

    // ============================================================
    // 一、唯一清理入口 exitViewer(reason)
    // ============================================================

    @Test
    fun `整个屏幕只有一处调用 onClose`() {
        val source = source(screenPath)
        // 三条退出路径（下滑 / 双指收缩 / 系统返回）加上 ViewModel 的 Close 事件，
        // 全部收敛到 exitViewer；exitViewer 是唯一允许调 onClose() 的地方。
        // 出现第二处 = 又冒出一份"各留一份"的退出路径（G6 的原始缺陷形态）
        assertEquals(
            "MediaViewerScreen 里 onClose() 只应出现在 exitViewer 内部",
            1,
            countStatement(source, "onClose()")
        )
    }

    @Test
    fun `exitViewer 执行屏幕级复位并触发页内复位`() {
        val body = blockOf(source(screenPath), "fun exitViewer(reason: ViewerExitReason) {")
        // 防重入（G6 验收项"重复进出不得累积"）
        assertTrue("缺退出防重入置位", hasStatement(body, "exitStarted.value = true"))
        // 屏幕级字段：与 ViewerExitCleanup.SCREEN_FIELDS 一一对应
        assertTrue(hasStatement(body, "progressJob?.cancel()"))
        assertTrue(hasStatement(body, "progressJob = null"))
        assertTrue(hasStatement(body, "overscrollSettleJob?.cancel()"))
        assertTrue(hasStatement(body, "overscrollSettleJob = null"))
        assertTrue("缺 overscrollPx 归零（退出快照会带一层贴歪的图）", hasStatement(body, "overscrollPx = 0f"))
        assertTrue(hasStatement(body, "overscrollRawDrag = 0f"))
        assertTrue(hasStatement(body, "pagerGestureActive = false"))
        // 页内复位：必须经注册表，而不是在 BackHandler 里另抄一份
        assertTrue("缺 exitRegistry.fire(reason)", hasStatement(body, "exitRegistry.fire(reason)"))
        // 转场归属：三条路径共用窗口层那一段，清理入口里不得再按 reason 抑制/加戏
        assertFalse(
            "exitViewer 里不该再按 reason 决定转场（收缩路径曾自播一段淡出 = 残影）",
            body.lines().any { it.trim().contains("suppressNextExitTransition") }
        )
        assertTrue(hasStatement(body, "onClose()"))
    }

    @Test
    fun `系统返回走统一清理入口而不是直接关闭`() {
        val body = blockOf(source(screenPath), "BackHandler {")
        assertTrue(
            "BackHandler 必须调 currentExitViewer(SYSTEM_BACK)（旧写法裸 onClose()，" +
                "pager 平移量与页内收尾动画都没人复位）",
            hasStatement(body, "currentExitViewer(ViewerExitReason.SYSTEM_BACK)")
        )
        assertFalse("BackHandler 里不得再出现裸 onClose()", hasStatement(body, "onClose()"))
    }

    @Test
    fun `ViewModel 的 Close 事件也走统一清理入口`() {
        val source = source(screenPath)
        assertTrue(
            hasStatement(source, "currentExitViewer(ViewerExitReason.AUTO_CLOSE)")
        )
        val body = blockOf(source, "is MediaViewerEvent.Close -> {")
        assertFalse("Close 事件分支里不得残留裸 onClose()", hasStatement(body, "onClose()"))
    }

    @Test
    fun `两种模式的内容页都把统一入口与注册表传给 ImageViewer`() {
        val source = source(screenPath)
        // GALLERY 分页 + SINGLE 各一处；两处都接同一个 currentExitViewer
        assertEquals(
            "GALLERY 与 SINGLE 都必须接到统一清理入口",
            2,
            countStatement(source, "onViewerClose = currentExitViewer,")
        )
        // 三处：GALLERY 分页 → MediaContent、SINGLE → MediaContent、MediaContent → ImageViewer。
        // 少一处（尤其最后一处）就等于注册链断了，系统返回清不到页内图层
        assertEquals(
            "注册表必须一路传到 ImageViewer",
            3,
            countStatement(source, "exitRegistry = exitRegistry,")
        )
    }

    @Test
    fun `视频播放器复用同一入口不另立清理`() {
        // 播放器只有"关闭"一种语义：包成 DISMISS_DRAG 转调同一入口
        val source = source(screenPath)
        assertTrue(
            "视频退出必须转调统一清理入口（不得再有第四份清理实现）",
            source.lines().any { it.trim().contains("close(ViewerExitReason.DISMISS_DRAG)") }
        )
    }

    // ============================================================
    // 二、页内（ImageViewer）：复位实现 + 唯一退出出口
    // ============================================================

    @Test
    fun `页内只有一处发起关闭`() {
        val source = source(imageViewerPath)
        // 三条手势路径都只通过 requestViewerExit 发起退出；组件内部不得直接 invoke 回调
        assertEquals(
            "ImageViewer 里直接调用退出回调的地方只应是 requestViewerExit",
            1,
            countStatement(source, "currentOnViewerClose?.invoke(reason)")
        )
        assertEquals(
            "旧的 onClose?.invoke() 必须清零（残影正是这几条分支各清各的）",
            0,
            countStatement(source, "onClose?.invoke()")
        )
    }

    @Test
    fun `双指收缩与下滑分别带上自己的退出原因`() {
        val source = source(imageViewerPath)
        assertEquals(
            "双指收缩意图成立必须直接走 PINCH_IN 统一出口",
            1,
            countStatement(source, "requestViewerExit(ViewerExitReason.PINCH_IN)")
        )
        assertEquals(
            "飞回收尾与旧链路兜底都必须走 DISMISS_DRAG 统一出口",
            2,
            countStatement(source, "requestViewerExit(ViewerExitReason.DISMISS_DRAG)")
        )
        // 出口自带防重入：转场中再有手势也不会发起第二次退出
        assertTrue(
            hasStatement(
                blockOf(source, "fun requestViewerExit(reason: ViewerExitReason) {"),
                "if (exitCommitted) return"
            )
        )
    }

    @Test
    fun `复位函数置防重入标志并取消三条在途动画`() {
        val body = blockOf(
            source(imageViewerPath),
            "val resetViewerLayers: (ViewerExitReason) -> Unit = { reason ->"
        )
        assertTrue("缺 exitCommitted = true", hasStatement(body, "exitCommitted = true"))
        assertTrue(hasStatement(body, "isFlyBackAnimating = false"))
        assertTrue(hasStatement(body, "pendingSingleTap = false"))
        for (job in listOf("zoomJob", "settleJob", "flyBackJob")) {
            assertTrue("$job 必须在统一复位里被取消", hasStatement(body, "$job?.cancel()"))
            assertTrue("$job 取消后要置空", hasStatement(body, "$job = null"))
        }
        // 收缩路径的内容层淡出状态机必须整体拿掉（否则又是"两段动画 + 标志位翻回"）
        assertFalse(body.lines().any { it.trim().contains("isPinchClosing") })
    }

    @Test
    fun `收缩退出的内容层淡出实现已从源码移除`() {
        val source = source(imageViewerPath)
        // 这三样是残影的成因组合：自播一段淡出 → 播完翻回标志位 → 窗口快照再淡一次
        assertEquals("playPinchCloseDismiss 不得再存在", 0, countStatement(source, "playPinchCloseDismiss()"))
        assertFalse(
            "isPinchClosing 标志位必须整体删除",
            source.lines().any { it.trimStart().startsWith("var isPinchClosing") }
        )
        assertFalse(
            "pinchCloseAlphaAnimatable 必须整体删除",
            source.contains("pinchCloseAlphaAnimatable")
        )
        assertFalse(
            "PINCH_CLOSE_TARGET_SCALE 必须整体删除",
            source.contains("PINCH_CLOSE_TARGET_SCALE")
        )
    }

    @Test
    fun `窗口转场抑制只留给飞回落格一处`() {
        // E4 的飞回落格已经无缝落在格子上，只有那条需要跳过窗口淡出；
        // 收缩路径若再挤进来，就是"两份动画时序"复发的信号
        val source = source(screenPath)
        assertEquals(
            "suppressNextExitTransition 只应出现在飞回落格的接线上",
            1,
            countStatement(source, "{ MediaViewerActivity.suppressNextExitTransition = true }")
        )
    }

    @Test
    fun `复位函数注册到统一入口并在离开组合时注销`() {
        val body = blockOf(source(imageViewerPath), "DisposableEffect(exitRegistry) {")
        assertTrue(
            "复位函数必须注册给屏幕级统一清理入口",
            hasStatement(
                body,
                "val unregister = exitRegistry?.register { reason -> latestResetViewerLayers(reason) }"
            )
        )
        assertTrue("必须注销，否则重复进出会累积注册", hasStatement(body, "onDispose { unregister?.invoke() }"))
    }

    // ============================================================
    // 三、G2 补漏：onPreFling 必须按甩速方向决定吞不吞
    // ============================================================

    @Test
    fun `onPreFling 按方向判定吞速而不是无条件吞掉`() {
        val body = blockOf(
            source(screenPath),
            "override suspend fun onPreFling(available: Velocity): Velocity {"
        )
        assertTrue(
            "onPreFling 必须调用 ViewerOverscroll.shouldSwallowFlingVelocity",
            hasStatement(body, "val swallow = ViewerOverscroll.shouldSwallowFlingVelocity(")
        )
        // 回弹与方向无关：两种情况都要 settle 回吸附位
        assertTrue(hasStatement(body, "settleOverscroll()"))
        assertTrue("甩向页内时必须把速度整份交回 pager", hasStatement(body, "return if (swallow) {"))
        assertTrue(body.lines().any { it.trim() == "Velocity.Zero" })
        // 反证：改回无条件吞速（旧写法 `return available`）就变红
        assertFalse(
            "onPreFling 不得无条件 return available（越界后回甩无法惯性翻页的根因）",
            hasStatement(body, "return available")
        )
    }
}
