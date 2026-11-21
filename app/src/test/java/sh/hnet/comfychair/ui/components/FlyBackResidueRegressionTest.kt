package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E4 残影**二次**修复（v4.19.2）的接线钉。
 *
 * ## 契约的演变
 *
 * - v4.16.15（第一次修复）：飞回播完时"清零松手位移 + 关闭飞回分支"，
 *   由纯函数 flyBackEndStateAfterCompletion 换算终态。结果残影换了形态：
 *   关分支的那一帧 graphicsLayer 落进 else 分支，画出 scale=1 / 位移 0 /
 *   alpha=1 的**全屏极清晰图**（用户真机截图确证）——关闭动作本身就是残影来源。
 * - v4.19.2（本次）：**两层修复**——
 *   ① 修改 1：飞回正常播完**不关** isFlyBackAnimating。动画末帧 flyBackAnimatable
 *     已到 1f，飞回分支经 flyBackTargetFactors 插值出的正是「图片停在目标格子里」，
 *     那是正确的最后一帧；组件马上被 requestViewerExit 销毁，标志位无需手工复位。
 *     配套删除了 flyBackEndStateAfterCompletion / FlyBackEndState（死代码）。
 *   ② 修改 2：graphicsLayer 回落分支（else）最前面加硬兜底——exitCommitted 已置位
 *     时 alpha = 0f 一律不画。防的是别处把 isFlyBackAnimating 翻回 false
 *     （resetViewerLayers :286 会，它由宿主页退出清理调用，合法保留）。
 *
 * ## 为什么是源码级接线钉、且必须按块截取
 *
 * 手势链路 JVM 单测跑不了，钉只能钉源码结构。全文件搜 `isFlyBackAnimating = false`
 * 会撞上 resetViewerLayers 里**合法保留**的那一行（机制状态复位，兜底已由修改 2
 * 覆盖），所以断言一律先按大括号配对截出目标块，再在块内判断。
 *
 * ## 反证（会话内实测）
 *
 * - 修改 1 回退成 `isFlyBackAnimating = false` → 第一条变红（实测贴入会话记录）；
 * - 修改 2 的守卫条件失效（`if (exitCommitted)` 改 `if (false)`）→
 *   第三条变红（同现实测）。
 */
class FlyBackResidueRegressionTest {

    private val imageViewerPath =
        "src/main/java/sh/hnet/comfychair/ui/components/ImageViewer.kt"
    private val logicPath =
        "src/main/java/sh/hnet/comfychair/ui/components/ViewerDismissGestureLogic.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /**
     * 按块截取：从 anchor（必须以 `{` 结尾）起做花括号配对，返回含首尾大括号的整块。
     * 目标块内没有含大括号的字符串字面量，注释也不含半角大括号，朴素配对即可。
     */
    private fun extractBlock(source: String, anchor: String): String {
        val start = source.indexOf(anchor)
        assertTrue("找不到锚点：$anchor", start >= 0)
        assertTrue("锚点必须以 { 结尾才能配对截取", anchor.endsWith("{"))
        var depth = 0
        var i = start + anchor.length - 1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        throw IllegalStateException("锚点块大括号不配对：$anchor")
    }

    /**
     * 新契约核心（修改 1）：飞回收尾回调**不得**再动 isFlyBackAnimating。
     * 反证：在收尾块内加回 `isFlyBackAnimating = false`（第一次修复的写法），
     * 本测试立即变红。
     */
    @Test
    fun `飞回收尾块不得关闭飞回分支`() {
        val block = extractBlock(source(imageViewerPath), "if (cause == null) {")
        // 截取正确性自检：块里必须确有这两行收尾动作，防止锚点错位导致空断言
        assertTrue(
            "截取到的块不含 currentOnFlyBackCompleted —— 锚点错位",
            block.contains("currentOnFlyBackCompleted")
        )
        // 收尾块内不得出现对 isFlyBackAnimating 的任何赋值
        // （覆盖 `= false` 与旧终态写法 `= endState.animating` 两种回退形态）
        assertFalse(
            "收尾块不得给 isFlyBackAnimating 赋值：关掉它 graphicsLayer 立刻落回 " +
                "else 分支，在组件销毁前闪一帧 scale=1 的全屏图（E4 二次修复的根因）",
            block.contains("isFlyBackAnimating =")
        )
        // 旧的"终态换算 + 三项写回"写法整体不得复活
        assertFalse("收尾块不得残留旧终态写法 endState.animating", block.contains("endState.animating"))
        assertFalse("收尾块不得残留旧终态写法 endState.dismissY", block.contains("endState.dismissY"))
        assertFalse("收尾块不得残留旧终态写法 endState.dismissX", block.contains("endState.dismissX"))
        // 收尾动作本身仍在：通知宿主落格 + 统一出口发起退出
        assertTrue(
            "收尾块必须仍通知宿主飞回落格",
            block.contains("currentOnFlyBackCompleted?.invoke()")
        )
        assertTrue(
            "收尾块必须仍走 DISMISS_DRAG 统一出口",
            block.contains("requestViewerExit(ViewerExitReason.DISMISS_DRAG)")
        )
    }

    /**
     * 修改 1 的连带清理：旧终态纯函数与数据类已无调用方，必须整体删除
     * （留着装死代码只会诱使后人把它接回去，复活"关分支"这个残影来源）。
     */
    @Test
    fun `旧终态纯函数已作为死代码删除`() {
        val logic = source(logicPath)
        // 按声明形态断言（注释里提及这些名字是允许的，代码声明不允许复活）
        assertFalse(
            "flyBackEndStateAfterCompletion 已无调用方，必须从 ViewerDismissGesture 删除",
            logic.contains("fun flyBackEndStateAfterCompletion")
        )
        assertFalse(
            "FlyBackEndState 数据类必须随之删除",
            logic.contains("data class FlyBackEndState")
        )
        val viewer = source(imageViewerPath)
        assertFalse(
            "ImageViewer 不得再引用 flyBackEndStateAfterCompletion",
            viewer.contains("flyBackEndStateAfterCompletion")
        )
        assertFalse("ImageViewer 不得再引用 FlyBackEndState", viewer.contains("FlyBackEndState"))
    }

    /**
     * 新契约（修改 2）：graphicsLayer 回落分支必须先判 exitCommitted 兜底、
     * 再算正常视觉。钉的是位置关系：`if (exitCommitted) {` 必须出现在
     * 飞回分支起点之后、`val effScaleFactor` 之前，且兜底分支内写全
     * alpha = 0f 与 rotationZ = 0f。
     * 反证：把守卫条件改成 `if (false) {`（等价于删掉兜底），本测试立即变红。
     */
    @Test
    fun `回落分支必须先判 exitCommitted 兜底再算全屏视觉`() {
        val source = source(imageViewerPath)
        val effIdx = source.indexOf("val effScaleFactor")
        assertTrue("找不到回落分支视觉计算起点 val effScaleFactor", effIdx >= 0)
        // 回落分支属于 graphicsLayer：其前面的飞回分支起点（637 处）
        val flyIdx = source.lastIndexOf("if (isFlyBackAnimating) {", effIdx)
        assertTrue("val effScaleFactor 必须位于 graphicsLayer 飞回分支之后", flyIdx >= 0)
        val guardIdx = source.lastIndexOf("if (exitCommitted) {", effIdx)
        assertTrue(
            "回落分支内必须先有 if (exitCommitted) 硬兜底（置于 val effScaleFactor 之前）：" +
                "宿主页退出清理 resetViewerLayers 会把 isFlyBackAnimating 翻回 false，" +
                "没有兜底就再闪一帧 scale=1 的全屏图",
            guardIdx in (flyIdx + 1) until effIdx
        )
        val guardRegion = source.substring(guardIdx, effIdx)
        assertTrue("兜底分支必须 alpha = 0f（内容层不绘制）", guardRegion.contains("alpha = 0f"))
        assertTrue(
            "兜底分支必须显式 rotationZ = 0f（属性块跨帧复用，复位纪律）",
            guardRegion.contains("rotationZ = 0f")
        )
    }

    /**
     * 修改 2 兜底的可信前提：exitCommitted 只置位、从不复位。
     * 一旦出现 `exitCommitted = false`，兜底就能被别处解除，必须变红。
     */
    @Test
    fun `exitCommitted 置位后全文件不得复位`() {
        val source = source(imageViewerPath)
        assertFalse(
            "不得存在把 exitCommitted 置回 false 的代码（兜底依赖它单调置位）",
            source.contains("exitCommitted = false")
        )
    }
}
