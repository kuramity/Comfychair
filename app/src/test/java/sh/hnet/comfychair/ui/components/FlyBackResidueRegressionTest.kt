package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E4 残影修复链的接线钉（含 v4.19.2 之后为修黑格做的回退修复）。
 *
 * ## 契约的演变
 *
 * - v4.16.15（第一次修复）：飞回播完时"清零松手位移 + 关闭飞回分支"，
 *   由纯函数 flyBackEndStateAfterCompletion 换算终态。结果残影换了形态：
 *   关分支的那一帧 graphicsLayer 落进 else 分支，画出 scale=1 / 位移 0 /
 *   alpha=1 的**全屏极清晰图**（用户真机截图确证）——关闭动作本身就是残影来源。
 * - v4.19.2（第二次修复）：**两层**——
 *   ① 修改 1（保留至今，刻意设计）：飞回正常播完**不关** isFlyBackAnimating。动画末帧
 *     flyBackAnimatable 已到 1f，飞回分支经 flyBackTargetFactors 插值出的正是「图片停在
 *     目标格子里」，那是正确的最后一帧；组件马上被 requestViewerExit 销毁，标志位无需
 *     手工复位。配套删除了 flyBackEndStateAfterCompletion / FlyBackEndState（死代码）。
 *   ② 修改 2（本轮已回退）：graphicsLayer 回落分支（else）最前面加硬兜底——exitCommitted
 *     已置位时 alpha = 0f 一律不画，防"别处把 isFlyBackAnimating 翻回 false 后画出全屏帧"。
 *     但代价是退出一开始图片整个消失、而 Viewer 暗背景/遮罩还没撤 → 用户看到「格子黑 +
 *     屏幕暗」（真机截图确证）。
 *
 * - 本轮（第三次修复）：删掉修改 2 的 alpha=0 兜底，并让 resetViewerLayers **不再复位**
 *   isFlyBackAnimating（旧实现置 false 正是渲染层回落 else 分支、再叠加兜底变黑的源头）。
 *   飞回一旦起步标志位就恒为 true 直到组件销毁，else 分支在飞回退出路径上不会再被走到，
 *   兜底失去存在意义。
 *
 * ## 为什么是源码级接线钉、且必须按块截取
 *
 * 手势链路 JVM 单测跑不了，钉只能钉源码结构。飞回收尾块与 resetViewerLayers 是两处不同的
 * 代码块（分别由第一条与 ViewerExitWiringTest 钉住），alpha=0f 兜底是全文结构（第三条钉）。
 * 涉及块内判断的断言一律先按大括号配对截出目标块，再在块内判断，避免误伤同名其它代码。
 *
 * ## 反证（会话内实测）
 *
 * - 飞回收尾块内加回 `isFlyBackAnimating = false`（第一次修复写法）→ 第一条变红；
 * - resetViewerLayers 块内加回 `isFlyBackAnimating = false`（B-2 旧写法）→
 *   ViewerExitWiringTest「复位函数…」一条变红；
 * - 把 alpha=0f 兜底加回 ImageViewer → 第三条「渲染层不得再有 alpha 全透明兜底」变红。
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
     * 接线钉（B-1）：上一轮的 exitCommitted→alpha=0f 硬兜底必须整体删除。
     *
     * 它本意防"标志被别处翻回 false 后画出全屏回落帧"，但代价是退出一开始图片整个
     * 消失、而 Viewer 暗背景/遮罩还没撤 → 用户看到「格子黑 + 屏幕暗」（真机截图确证）。
     * 配合 B-2（resetViewerLayers 不再复位 isFlyBackAnimating），飞回一旦起步标志位就
     * 恒为 true 直到组件销毁，else 分支在飞回退出路径上不会再被走到，兜底失去存在意义，
     * 且它本身就是黑格元凶，必须删干净。
     * 反证：把 `alpha = 0f` 兜底加回 ImageViewer，本测试立即变红。
     */
    @Test
    fun `渲染层不得再有 alpha 全透明兜底`() {
        val viewer = source(imageViewerPath)
        assertFalse(
            "ImageViewer 全文不得出现 alpha = 0f：该兜底会让退出一开始图片整个消失、" +
                "Viewer 暗背景未撤 → 格子变黑（真机复现），已随 B-2 一并删除",
            viewer.contains("alpha = 0f")
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
