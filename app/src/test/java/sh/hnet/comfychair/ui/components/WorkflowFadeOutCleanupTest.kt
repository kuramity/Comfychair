package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-15：淡出协程被取消时，元素不能停在中间 alpha。
 *
 * 事故形态：淡入/淡出都在 `LaunchedEffect(...)` 里 `launch {}`，key 变化即整体取消；
 * 旧代码把 `xxxStates.remove(id)` 写在 `alpha.animateTo(0f)` 之后，取消时这行永远
 * 不执行。元素于是以 `isFadingOut = true` 的中间 alpha 永久留在动画状态表里 ——
 * 绘制循环仍把它当「离场中的元素」画出来，就是残影。
 *
 * 复核（P2-15-SIBLING-FADEOUTS）指出同一缺陷存在于节点 / 便签 / 分组 / 连线四处，
 * 因此这里同时钉住：
 * 1. 纯状态迁移 [resolveFadeOutOutcome] 的契约；
 * 2. 四类淡出循环都必须把清理放进 `try/finally`。
 *
 * 反证：删掉任一处 finally 收尾（恢复旧写法），第二组接线测试立刻变红。
 */
class WorkflowFadeOutCleanupTest {

    // ---------- 1. 纯状态迁移 ----------

    @Test
    fun `取消且元素已移除时清掉动画状态`() {
        val outcome = resolveFadeOutOutcome(
            current = FadeOutState(alpha = 0.37f, isFadingOut = true),
            end = FadeOutEnd.CANCELLED,
            stillInGraph = false,
            stateStillOwnedByThisAnimation = true
        )
        // 留一个中间态就是残影；正确去向是整体移除（消失即中性态）
        assertEquals(FadeOutOutcome.RemoveState, outcome)
    }

    @Test
    fun `正常播完与取消走同一收尾`() {
        val cancelled = resolveFadeOutOutcome(
            FadeOutState(0.12f, true), FadeOutEnd.CANCELLED, false, true
        )
        val completed = resolveFadeOutOutcome(
            FadeOutState(0.12f, true), FadeOutEnd.COMPLETED, false, true
        )
        assertEquals(FadeOutOutcome.RemoveState, cancelled)
        assertEquals(FadeOutOutcome.RemoveState, completed)
    }

    @Test
    fun `取消时元素仍在图中则立即回到中性态`() {
        val outcome = resolveFadeOutOutcome(
            current = FadeOutState(alpha = 0.37f, isFadingOut = true),
            end = FadeOutEnd.CANCELLED,
            stillInGraph = true,
            stateStillOwnedByThisAnimation = true
        )
        assertTrue("元素回到图中时应保留状态而不是删除", outcome is FadeOutOutcome.KeepNeutral)
        val state = (outcome as FadeOutOutcome.KeepNeutral).state
        assertEquals("alpha 必须回到 1（不能留 0.37 的中间值）", 1f, state.alpha, 0f)
        assertFalse("必须清掉淡出标记", state.isFadingOut)
    }

    @Test
    fun `状态已被新动画接管时不得误删`() {
        val outcome = resolveFadeOutOutcome(
            current = FadeOutState(alpha = 0.5f, isFadingOut = false),
            end = FadeOutEnd.CANCELLED,
            stillInGraph = true,
            stateStillOwnedByThisAnimation = false
        )
        assertEquals(FadeOutOutcome.KeepUntouched, outcome)
    }

    // ---------- 2. 接线：四类淡出全都要在 finally 里收尾 ----------

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 取一段淡出循环：从注释标记到「当前元素状态更新」注释之间。 */
    private fun fadeOutBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到淡出块：$marker", start >= 0)
        val end = source.indexOf("// Update or create animated state for current", start)
        assertTrue("找不到 '$marker' 的结束边界", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `四类淡出都必须把清理写进 finally`() {
        val source = readSource(
            "src/main/java/sh/hnet/comfychair/ui/components/WorkflowGraphCanvas.kt"
        )
        val blocks = mapOf(
            "节点" to "// Handle removed nodes - fade out then remove",
            "便签" to "// Handle removed notes - fade out then remove",
            "分组" to "// Handle removed groups - fade out then remove",
            "连线" to "// Handle removed edges - fade out then remove",
        )
        for ((label, marker) in blocks) {
            val block = fadeOutBlock(source, marker)
            assertTrue("$label：淡出必须包在 try 里", block.contains("try {"))
            assertTrue("$label：淡出必须有 finally 收尾", block.contains("finally {"))
            assertTrue(
                "$label：finally 里必须调用纯函数决策",
                block.contains("resolveFadeOutOutcome(")
            )
            assertTrue("$label：finally 里必须处理 KeepNeutral", block.contains("KeepNeutral"))
            assertTrue("$label：finally 里必须真的移除状态", block.contains(".remove("))

            val finallyAt = block.indexOf("finally {")
            val removeAt = block.indexOf(".remove(")
            assertTrue(
                "$label：移除必须发生在 finally 里（不能回到 animateTo 之后）",
                removeAt > finallyAt
            )
        }
    }

    @Test
    fun `旧写法 animateTo 后直接 remove 已全部消失`() {
        val source = readSource(
            "src/main/java/sh/hnet/comfychair/ui/components/WorkflowGraphCanvas.kt"
        )
        assertFalse(
            "不得再出现『animateTo(0f) 之后直接 remove』的旧写法",
            Regex("animateTo\\(0f, animationSpec\\)\\s*\\n\\s*[A-Za-z]+States\\.remove\\(")
                .containsMatchIn(source)
        )
    }
}