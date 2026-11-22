package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 连接页自动连抑制的**源码级接线钉**（R1-L1）。
 *
 * 背景：抑制自动重连的标志（ConnectionManager.isUserInitiatedLogout）曾被连接页
 * 「读完就清」—— 配置变更（旋转/深色模式）导致 Compose 重建后 LaunchedEffect 再跑
 * 一遍，标志已被清掉，抑制失效、自动连了回去。修复把判定结果记在 rememberSaveable
 * （跨重建保留），消费时机改为「抑制代次变化时重判一次」（幂等）：
 *
 * 1. 两个判定状态必须是 `rememberSaveable` —— 反证：改回 `remember`，配置变更重建后
 *    状态归零、抑制判定重丢，本测试红；
 * 2. `shouldAutoConnect` 必须用 `!autoConnectSuppressed`，**不得**直接读
 *    `ConnectionManager.isUserInitiatedLogout` —— 反证：改回旧写法（读标志本身做判定，
 *    标志已在 ON_RESUME 被消费为 false）→ 正则不匹配 → 红；
 * 3. `refreshAutoConnectSuppression` 内必须有「代次不一致才重判 + 读标志 + 清标志」的
 *    完整闭环 —— 反证：删掉清标志（标志永不清，下次冷启动误抑制）或删掉代次比较
 *    （重建后重复判定/永不判定）→ 红；
 * 4. LaunchedEffect(Unit) 与 ON_RESUME 两处都必须调用 `refreshAutoConnectSuppression()`
 *    —— 少任何一处，对应进入路径（新组合 / 复用返回）的抑制消费就断了。
 */
class LoginAutoConnectSuppressionWiringTest {

    private val loginPath = "src/main/java/sh/hnet/comfychair/ui/screens/LoginScreen.kt"

    /** 单测工作目录是模块根（app/），与 ViewerDismissWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    /** 行首空格数。 */
    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.count()

    /** 逐行大括号净增量（跳过注释/字符串/字符字面量）。 */
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

    /** 按前缀找块签名并返回块正文。 */
    private fun blockOf(source: String, signature: String): String {
        val lines = source.lines()
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        assertTrue("源码里找不到块签名（前缀匹配）：$signature", startIdx >= 0)
        val deltas = braceDeltas(lines)
        val want = leadingSpaces(lines[startIdx])
        var depth = 0
        var started = false
        for (i in startIdx until lines.size) {
            depth += deltas[i]
            if (depth > 0) started = true
            if (started && depth == 0) {
                assertTrue(
                    "块（$signature）收尾行缩进与签名行不一致 —— 块边界不可信",
                    leadingSpaces(lines[i]) == want
                )
                return lines.subList(startIdx, i + 1).joinToString("\n")
            }
        }
        throw AssertionError("块（$signature）大括号配不平")
    }

    @Test
    fun `抑制判定状态必须跨配置变更保留`() {
        val body = source(loginPath)
        // 反证：rememberSaveable 改回 remember → 配置变更重建后状态归零 → 正则不匹配 → 红
        assertTrue(
            "autoConnectSuppressed 必须用 rememberSaveable（配置变更重建后抑制状态要保留）",
            Regex(
                """autoConnectSuppressed\s+by\s+rememberSaveable\s*\{\s*mutableStateOf\(false\)"""
            ).containsMatchIn(body)
        )
        assertTrue(
            "handledSuppressionEpoch 必须用 rememberSaveable（重建后凭它跳过重复判定）",
            Regex(
                """handledSuppressionEpoch\s+by\s+rememberSaveable\s*\{\s*mutableStateOf\(-1L\)"""
            ).containsMatchIn(body)
        )
    }

    @Test
    fun `判定函数必须是代次比较加读标志加清标志的闭环`() {
        val refresh = blockOf(
            source(loginPath),
            "fun refreshAutoConnectSuppression() {"
        )
        // 反证 1：删掉代次比较（每次都重判）→ 重建后 ON_RESUME 会拿消费过的标志重判 → 抑制丢失 → 红
        assertTrue(
            "判定必须以代次不一致为前提（epoch != handledSuppressionEpoch 才重判）",
            Regex("""epoch\s*!=\s*handledSuppressionEpoch""").containsMatchIn(refresh)
        )
        // 反证 2：判定里不读标志（永远不抑制）→ 红
        assertTrue(
            "重判时必须读 ConnectionManager.isUserInitiatedLogout 作为抑制结果",
            refresh.contains("autoConnectSuppressed = ConnectionManager.isUserInitiatedLogout")
        )
        // 反证 3：读完不清（标志永驻，下次冷启动/进入误抑制）→ 红
        assertTrue(
            "重判后必须消费标志（ConnectionManager.clearLogoutFlag()），否则抑制永不解除",
            refresh.contains("ConnectionManager.clearLogoutFlag()")
        )
    }

    @Test
    fun `自动连判定不得直接读抑制标志`() {
        val body = source(loginPath)
        // 反证：改回旧写法（shouldAutoConnect 直接判 !ConnectionManager.isUserInitiatedLogout，
        // 重建后标志已被消费为 false）→ 正则不匹配 → 红
        assertTrue(
            "shouldAutoConnect 必须判 rememberSaveable 的抑制状态（autoConnectSuppressed），" +
                "不得直接读标志本身（标志是消费后即 false 的一次性值，重建后会误判为可自动连）",
            Regex(
                """shouldAutoConnect\s*=\s*isAutoConnectEnabled\s*&&\s*""" +
                    """!autoConnectSuppressed\s*&&\s*!isOfflineMode"""
            ).containsMatchIn(body)
        )
        // 兜底钉：LaunchedEffect(Unit) 的自动连块里不得再出现 isUserInitiatedLogout
        val launched = blockOf(body, "LaunchedEffect(Unit) {")
        assertFalse(
            "自动连块内不得直接读 isUserInitiatedLogout（判定走 autoConnectSuppressed）",
            launched.contains("isUserInitiatedLogout")
        )
    }

    /** 找出**所有**匹配前缀签名的块正文（LoginScreen 有多个 LaunchedEffect(Unit)，不能只取第一个）。 */
    private fun allBlocksOf(source: String, signature: String): List<String> {
        val lines = source.lines()
        val deltas = braceDeltas(lines)
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith(signature)) {
                i++
                continue
            }
            val want = leadingSpaces(lines[i])
            var depth = 0
            var started = false
            var end = -1
            for (j in i until lines.size) {
                depth += deltas[j]
                if (depth > 0) started = true
                if (started && depth == 0) {
                    assertTrue(
                        "块（$signature）第 ${i + 1} 行的收尾缩进异常 —— 块边界不可信",
                        leadingSpaces(lines[j]) == want
                    )
                    end = j
                    break
                }
            }
            assertTrue("块（$signature）第 ${i + 1} 行大括号配不平", end >= 0)
            out += lines.subList(i, end + 1).joinToString("\n")
            i = end + 1
        }
        return out
    }

    @Test
    fun `两处进入路径都必须消费抑制请求`() {
        val body = source(loginPath)
        // 新组合路径：LoginScreen 有多个 LaunchedEffect(Unit)，按块内容锁定自动连块
        //（含 shouldAutoConnect 判定的那个）—— 不依赖块顺序，注释改名也不影响定位
        val autoConnectBlock = allBlocksOf(body, "LaunchedEffect(Unit) {")
            .filter { it.contains("shouldAutoConnect") }
        assertTrue(
            "必须恰好有一个带 shouldAutoConnect 判定的 LaunchedEffect(Unit)（自动连块）",
            autoConnectBlock.size == 1
        )
        assertTrue(
            "LaunchedEffect(Unit) 自动连块里必须调 refreshAutoConnectSuppression()（消费新抑制请求）",
            autoConnectBlock.single().contains("refreshAutoConnectSuppression()")
        )
        // 复用返回路径：ON_RESUME observer（组合仍在、LaunchedEffect 不会重跑，
        // 只有这里能消费返回键/登出置位的抑制请求）
        val resume = blockOf(body, "if (event == Lifecycle.Event.ON_RESUME) {")
        assertTrue(
            "ON_RESUME 里必须调 refreshAutoConnectSuppression()（复用返回路径只有它能消费）",
            resume.contains("refreshAutoConnectSuppression()")
        )
    }
}
