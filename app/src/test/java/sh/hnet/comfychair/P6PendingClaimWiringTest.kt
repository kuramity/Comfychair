package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P6：在途提交认领链路的源码级接线钉子。
 *
 * 为什么需要源码断言：`PendingSubmissionClaimTest` 只证明"挑哪一条"算得对，
 * 证明不了生产代码真的登记了意图、真的在 `execution_start` 时认领、真的在提交
 * 回调里对账。按项目既有范式（TrashDeleteWiringTest / ViewerDismissWiringTest）
 * 直接读源码钉住调用顺序，避免"纯函数测试全绿、调用点却被删掉"。
 */
class P6PendingClaimWiringTest {

    /** 单测工作目录是模块根（app/），与 TrashDeleteWiringTest 同口径。 */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 取一个函数的正文：从签名行起，到与签名同缩进的单独一行 `}` 为止。 */
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

    private val generationPath = "src/main/java/sh/hnet/comfychair/viewmodel/GenerationViewModel.kt"
    private val registryPath = "src/main/java/sh/hnet/comfychair/queue/JobRegistry.kt"
    private val connectionPath = "src/main/java/sh/hnet/comfychair/connection/ConnectionManager.kt"

    @Test
    fun `提交入口必须先登记在途意图再发请求`() {
        val block = blockOf(source(generationPath), "private fun submitWorkflow(")

        val begin = block.indexOf("JobRegistry.beginSubmission(")
        val submit = block.indexOf("client.submitPrompt(")

        assertTrue("找不到 beginSubmission 调用", begin >= 0)
        assertTrue("找不到 client.submitPrompt 调用", submit >= 0)
        assertTrue("在途意图必须在请求发出**之前**登记（否则 execution_start 先到时无从认领）", begin < submit)
    }

    @Test
    fun `成功回调必须对账移除在途意图`() {
        val block = blockOf(source(generationPath), "private fun submitWorkflow(")

        assertTrue("成功分支必须调用 completeSubmission", block.contains("JobRegistry.completeSubmission("))
    }

    @Test
    fun `失败回调必须先判认领再决定是否报失败`() {
        val block = blockOf(source(generationPath), "private fun submitWorkflow(")

        val claimed = block.indexOf("JobRegistry.findClaimedPromptId(")
        val failureReport = block.indexOf("onResult(false, null, errorMessage)")
        val unresolved = block.indexOf("JobRegistry.markSubmissionUnresolved(")
        val abandon = block.indexOf("JobRegistry.abandonSubmission(")

        assertTrue("找不到 findClaimedPromptId", claimed >= 0)
        assertTrue("找不到失败上报", failureReport >= 0)
        assertTrue("找不到 markSubmissionUnresolved", unresolved >= 0)
        assertTrue("找不到 abandonSubmission", abandon >= 0)
        assertTrue("必须先判断是否已被 execution_start 接管，再决定报提交失败", claimed < failureReport)
        assertTrue("上报失败之前必须处理在途意图（保留或丢弃）", unresolved < failureReport && abandon < failureReport)
    }

    @Test
    fun `execution_start 时先尝试认领再落外部任务`() {
        val block = blockOf(source(registryPath), "fun markExecuting(promptId: String) {")

        val claim = block.indexOf("claimOldestPendingLocked(")
        val guard = block.indexOf("ENABLE_P6_PENDING_CLAIM")
        val takeoverLog = block.indexOf("P6 接管")
        val external = block.indexOf("External job executing")

        assertTrue("找不到认领调用", claim >= 0)
        assertTrue("认领必须有开关保护", guard >= 0)
        assertTrue("认领必须打验收日志（P6 接管）", takeoverLog >= 0)
        assertTrue("找不到外部任务日志", external >= 0)
        assertTrue("认领必须发生在判外部任务之前", claim < external)
        assertTrue("验收日志必须发生在判外部任务之前", takeoverLog < external)
    }

    @Test
    fun `解析 execution_start 时先 markExecuting 再广播事件`() {
        val block = blockOf(source(connectionPath), "private fun parseAndEmitMessage(text: String) {")

        val mark = block.indexOf("JobRegistry.markExecuting(promptId)")
        val emit = block.indexOf("WebSocketMessage.ExecutionStart(promptId)")

        assertTrue("找不到 markExecuting 调用", mark >= 0)
        assertTrue("找不到 ExecutionStart 广播", emit >= 0)
        assertTrue("必须先让 JobRegistry 处理归属（含 P6 认领），再广播给 ViewModel", mark < emit)
    }
}