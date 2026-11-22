package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主容器返回键回调的**源码级接线钉**（R1-L3）。
 *
 * 背景：起始 tab 按返回 → 回连接页的判定已抽成纯函数 `backActionAt`（MainContainerActivity.kt
 * 文件尾），并有 MainContainerBackActionTest 钉住纯函数本身；但 `onBackPressedDispatcher
 * .addCallback` 里**实际消费纯函数的那段胶水**没有任何断言 —— 有人把回调改回手写判断
 * （比如 `if (currentRouteName == startRoute)`）或把「非起始 tab」分支改成完整登出，
 * 纯函数测试依然全绿，行为却已经坏了。这里按调用点的**实参本身**钉住接线：
 *
 * 1. 回调必须消费 `backActionAt(tabIndexOfRoute(...), tabIndexOfRoute(...))` 纯函数链；
 * 2. `SwitchToStartTab` 分支必须先试 `popBackStack`，**仅当**弹栈失败（`!popSucceeded`）
 *    才兜底 `returnToLoginKeepingQueue()` —— 兜底无条件执行会把「切 tab」变成「回连接页」；
 * 3. `ReturnToLogin` 分支必须直接 `returnToLoginKeepingQueue()`（不登出、不清队列）；
 * 4. 回调块内不得出现完整登出词汇（`logout` / `clearPersistedState`）—— 返回键不是登出，
 *    生成任务和队列都要保留（MainContainerActivity 回调上方的长注释就是这段语义）。
 *
 * 反证：删除/改坏上述任一接线（把 when 换成手写 if、删掉 `!popSucceeded` 条件、
 * 分支里调 GenerationViewModel.logout 等），对应断言立即变红。
 */
class MainContainerBackActionWiringTest {

    private val activityPath = "src/main/java/sh/hnet/comfychair/MainContainerActivity.kt"

    /** 单测工作目录是模块根（app/），与 ViewerDismissWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    /** 行首空格数（本项目 Kotlin 一律用空格缩进）。 */
    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.count()

    /** 去掉行尾注释与行尾空白（结构判定用；保留行首缩进）。 */
    private fun codeOf(line: String): String = line.trimEnd().substringBefore("//").trimEnd()

    /**
     * 逐行**大括号净增量**：跳过块注释、行注释、字符串与字符字面量，注释与字符串里的
     * 花括号不参与配对（与 ViewerDismissWiringTest 同一套口径）。
     */
    private fun braceDeltas(lines: List<String>): IntArray {
        val deltas = IntArray(lines.size)
        var inBlockComment = false
        var inString = false
        var inChar = false
        for ((row, raw) in lines.withIndex()) {
            var i = 0
            var delta = 0
            while (i < raw.length) {
                val c = raw[i]
                val n = if (i + 1 < raw.length) raw[i + 1] else ' '
                when {
                    inBlockComment -> if (c == '*' && n == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> when {
                        c == '\\' -> i++
                        c == '"' -> inString = false
                    }
                    inChar -> when {
                        c == '\\' -> i++
                        c == '\'' -> inChar = false
                    }
                    c == '/' && n == '/' -> i = raw.length
                    c == '/' && n == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '{' -> delta++
                    c == '}' -> delta--
                }
                i++
            }
            deltas[row] = delta
        }
        return deltas
    }

    /** 从 [startIdx] 起按大括号配平取块区间，收尾行缩进必须与签名行一致。 */
    private fun blockRangeAt(lines: List<String>, startIdx: Int, what: String): IntRange {
        val deltas = braceDeltas(lines)
        val want = leadingSpaces(lines[startIdx])
        var depth = 0
        var started = false
        for (i in startIdx until lines.size) {
            depth += deltas[i]
            if (depth > 0) started = true
            if (started && depth == 0) {
                if (leadingSpaces(lines[i]) != want) {
                    throw AssertionError(
                        "$what：按大括号配平得到的收尾在第 ${i + 1} 行，与签名行第 ${startIdx + 1} 行" +
                            "的缩进不一致 —— 收尾被重排或漏配，本块边界不可信"
                    )
                }
                return startIdx..i
            }
        }
        throw AssertionError("$what：从第 ${startIdx + 1} 行起大括号配不平（深度没有归零）")
    }

    /** 按前缀找块签名并返回块正文（0 起算闭区间的行切片）。 */
    private fun blockOf(source: String, signature: String): String {
        val lines = source.lines()
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        assertTrue("源码里找不到块签名（前缀匹配）：$signature", startIdx >= 0)
        val range = blockRangeAt(lines, startIdx, "块（$signature）")
        return lines.subList(range.first, range.last + 1).joinToString("\n")
    }

    /** 断言 [before] 在 [after] 之前出现（两者都必须存在于 [block] 内）。 */
    private fun assertOrder(block: String, before: String, after: String, why: String) {
        val iBefore = block.indexOf(before)
        val iAfter = block.indexOf(after)
        assertTrue("$why —— 块里找不到：$before", iBefore >= 0)
        assertTrue("$why —— 块里找不到：$after", iAfter >= 0)
        assertTrue("$why（顺序反了：$before 必须在 $after 之前）", iBefore < iAfter)
    }

    @Test
    fun `返回键回调必须消费 backActionAt 纯函数链`() {
        val callback = blockOf(source(activityPath), "onBackPressedDispatcher.addCallback(this) {")

        // 回调判定必须是纯函数消费：backActionAt(tabIndexOfRoute(当前), tabIndexOfRoute(起始))。
        // 反证：把 when 换成手写比较（如 currentRouteName == startRoute）→ backActionAt 缺失 → 红
        assertTrue(
            "返回键回调必须消费 backActionAt 纯函数，不得自己写一份判断",
            callback.contains("backActionAt(")
        )
        assertTrue(
            "backActionAt 的两个实参必须都走 tabIndexOfRoute 纯函数（当前路由 + 起始路由）",
            callback.contains("tabIndexOfRoute(currentRouteName)") &&
                callback.contains("tabIndexOfRoute(startRoute)")
        )

        // 两个 BackAction 分支都必须在回调里有落点（枚举新增分支时这里会提示补接线）
        assertTrue("必须有 SwitchToStartTab 分支", callback.contains("BackAction.SwitchToStartTab ->"))
        assertTrue("必须有 ReturnToLogin 分支", callback.contains("BackAction.ReturnToLogin ->"))

        // 返回键不是登出：块内不得出现完整登出词汇（GenerationViewModel.logout 会清队列与状态）
        assertFalse(
            "返回键回调不得触发完整登出（logout / clearPersistedState）—— 生成任务与队列要保留",
            Regex("""\.logout\(|clearPersistedState""").containsMatchIn(callback)
        )
    }

    @Test
    fun `SwitchToStartTab 必须先弹栈失败才兜底回连接页`() {
        val callback = blockOf(source(activityPath), "onBackPressedDispatcher.addCallback(this) {")

        // 反证 1：删掉 if (!popSucceeded) 条件、无条件回连接页 → 该行缺失 → 红
        // （弹栈成功本该留在起始 tab，无条件兜底会把「切 tab」变成「回连接页」）
        assertTrue(
            "弹栈兜底必须带条件：仅当 popBackStack 失败（!popSucceeded）才回连接页",
            callback.contains("if (!popSucceeded)")
        )
        // 反证 2：先回连接页再弹栈（或删掉弹栈只回连接页）→ 顺序断言红
        assertOrder(
            callback,
            "navControllerRef?.popBackStack(",
            "if (!popSucceeded)",
            "必须先尝试弹回起始 tab，弹不动才走兜底"
        )
    }

    @Test
    fun `ReturnToLogin 分支必须直接回连接页保持队列`() {
        val callback = blockOf(source(activityPath), "onBackPressedDispatcher.addCallback(this) {")

        // ReturnToLogin 的落点就是 returnToLoginKeepingQueue（只导航，不清连接与队列）。
        // 反证：改回完整登出（GenerationViewModel.logout / ConnectionManager.logout）→ 红
        assertTrue(
            "ReturnToLogin 分支必须调 returnToLoginKeepingQueue()（连接不断、队列不清）",
            Regex("""BackAction\.ReturnToLogin\s*->\s*returnToLoginKeepingQueue\(\)""")
                .containsMatchIn(callback)
        )
        assertTrue(
            "SwitchToStartTab 弹栈失败时的兜底也必须是 returnToLoginKeepingQueue()",
            callback.contains("returnToLoginKeepingQueue()")
        )
    }
}
