package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 查看器脱离拖动的两条源码级钉子（v4.16.14 第 2 轮复核后重写）。
 *
 * ## 一、回弹起点的装配顺序（防"首帧跳变"）
 *
 * `settleDismissBack` / `playSwipeDeleteEnterBin` 把位移从手势 state 交接给
 * Animatable 时，顺序必须是：
 *   ① `dismissDragAnimatable(.X).snapTo(当前位移)` —— 先把动画值同步到松手位移；
 *   ② `isDismissSettling = true` —— 再切数据源（图形层/遮罩从此读 Animatable）；
 *   ③ `dismissDragY/X = 0f` —— 最后才清零手势 state。
 * 任何一步调换，切换的那一帧就会读到"零位移 + 缩小/半透明"，即缩小版图片瞬跳。
 * 纯函数测不了这个顺序（它描述的是 Compose 组件里的三次赋值），所以读源码钉。
 *
 * 本测试是**顺序敏感**的：删掉 ①、把 ① 挪到 ② 之后、或把 ③ 提前，都会变红
 * （已在会话内实测：删掉两条 snapTo 后本测试失败，恢复后通过）。
 *
 * ## 二、透出收回的时机（第 2 项用户现象的根因）
 *
 * 透出（maskRevealed）旧实现只在位移跌回 10% 以下时翻 false，于是"松手那一刻
 * 还没淡完的透出"会在手指抬起后继续朝 0.2 补完 —— 图库截图里的缩略图在回弹
 * 途中反而更清楚，随后才淡回纯黑，就是"松手回弹瞬间闪一下图片"。
 * 现在两处判定都走 `ViewerDismissGesture.shouldRevealMask`：回弹侧传
 * `gestureActive = false`（松手即作废）、拖动侧传 `gestureActive = true`。这里**按调用点的
 * 实参本身**钉住（R2-L3b：切片不依赖本项目函数名，改局部函数名不会让测试失效）：
 * 先把每个调用的实参括号配平地解析出来，按"位移读 Animatable 还是读手势 state"给两侧定性，
 * 再要求各自实参里的 `gestureActive` 字面量正确；结构归属只用 `LaunchedEffect` / `fun` /
 * `pointerInput(Unit)` 这些不会改名的构造。全文级断言在两处布尔互换时仍会绿，钉不住行为。
 */
class ViewerDismissWiringTest {

    private val imageViewerPath = "src/main/java/sh/hnet/comfychair/ui/components/ImageViewer.kt"

    /** 单测工作目录是模块根（app/），与 ViewerExitWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 去掉行尾注释与行尾空白（结构判定用；保留行首缩进）。空行/纯注释行返回空串。 */
    private fun codeOf(line: String): String = line.trimEnd().substringBefore("//").trimEnd()

    /** 行首空格数（本项目 Kotlin 一律用空格缩进）。 */
    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.count()

    /**
     * 取一个块的正文。R2-L2：**统一走 [blockRangeAt]** 的配对口径（容忍 `},` 与行尾注释、
     * 且配对不可信时抛错），不再保留"找不到收尾就返回到文件末尾"的第二套实现——
     * 那会让块无声吞掉后面的代码。
     */
    private fun blockOf(source: String, signature: String): String {
        val lines = source.lines()
        return slice(lines, blockRange(lines, signature))
    }

    /** 断言 [before] 在 [after] 之前出现（两者都必须存在）。 */
    private fun assertOrder(block: String, before: String, after: String, why: String) {
        val iBefore = block.indexOf(before)
        val iAfter = block.indexOf(after)
        assertTrue("$why —— 块里找不到：$before", iBefore >= 0)
        assertTrue("$why —— 块里找不到：$after", iAfter >= 0)
        assertTrue("$why（顺序反了：$before 必须在 $after 之前）", iBefore < iAfter)
    }

    /**
     * 按**前缀**找块签名并取行号区间（0 起算，闭区间）。
     * 锚点用前缀而不是精确全文：形参类型/名字调整不会让切片失效；
     * 本项目函数整体改名仍会在这里响铃失败（不静默假绿）。
     */
    private fun blockRange(lines: List<String>, anchorPrefix: String): IntRange {
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith(anchorPrefix) }
        assertTrue("源码里找不到块签名（前缀匹配）：$anchorPrefix", startIdx >= 0)
        return blockRangeAt(lines, startIdx, "按签名找块（$anchorPrefix）")
    }

    /**
     * 逐行**大括号净增量**：跨行状态机跳过块注释、行注释、字符串与字符字面量，
     * 注释与字符串里的花括号不参与配对。
     *
     * R2-L2：块边界必须由**括号平衡**决定，不能靠"缩进恰好等于签名 + `}`"——
     * 真收尾被改缩进（或被删）时，缩进匹配会无声配到后面别的同缩进 `}`，块被悄悄扩大。
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
                    // 行注释：本行剩余全部忽略
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

    /**
     * 从 [startIdx] 起按**大括号配平**取块区间，并要求收尾行缩进与签名行一致。
     * 配不平或收尾缩进异常 → 直接抛 AssertionError（而不是无声把邻近代码吞进块里）。
     */
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
                        "$what：按大括号配平得到的收尾在第 ${i + 1} 行（缩进 ${leadingSpaces(lines[i])}），" +
                            "与签名行第 ${startIdx + 1} 行的缩进 $want 不一致 —— 收尾被重排或漏配，" +
                            "本块边界不可信（继续用会吞掉/漏掉邻近代码）"
                    )
                }
                return startIdx..i
            }
        }
        throw AssertionError("$what：从第 ${startIdx + 1} 行起大括号配不平（深度没有归零）")
    }

    /** 取行号区间对应的源码文本。 */
    private fun slice(lines: List<String>, range: IntRange): String =
        lines.subList(range.first, range.last + 1).joinToString("\n")

    // ============================================================
    // 一、回弹起点装配顺序
    // ============================================================

    @Test
    fun `回弹必须先同步 Animatable 再切数据源再清零`() {
        val body = source(imageViewerPath)
        val settle = blockOf(body, "fun settleDismissBack() {")
        assertOrder(
            settle,
            "dismissDragAnimatable.snapTo(dismissDragY)",
            "isDismissSettling = true",
            "y 轴：必须先 snapTo 松手位移，再切 isDismissSettling"
        )
        assertOrder(
            settle,
            "dismissDragAnimatableX.snapTo(dismissDragX)",
            "isDismissSettling = true",
            "x 轴：必须先 snapTo 松手位移，再切 isDismissSettling"
        )
        assertOrder(
            settle,
            "isDismissSettling = true",
            "dismissDragY = 0f",
            "切源之后才允许清零手势 state（提前清零 → 首帧读到零位移）"
        )
        assertOrder(
            settle,
            "isDismissSettling = true",
            "dismissDragX = 0f",
            "切源之后才允许清零手势 state"
        )
    }

    @Test
    fun `进站动画同样先同步再切源`() {
        val body = source(imageViewerPath)
        val fly = blockOf(body, "fun playSwipeDeleteEnterBin() {")
        assertOrder(
            fly,
            "dismissDragAnimatable.snapTo(startDy)",
            "isDismissSettling = true",
            "进站动画：必须先 snapTo 起点位移，再切 isDismissSettling"
        )
        assertOrder(
            fly,
            "isDismissSettling = true",
            "dismissDragY = 0f",
            "进站动画：切源之后才允许清零手势 state"
        )
    }

    // ============================================================
    // 二、透出收回时机（第 2 项根因）
    // ============================================================

    /** 一个 `shouldRevealMask(` 调用点：所在行号（0 起算）、实参文本、解析出的 gestureActive 字面量。 */
    private data class MaskCall(val lineIdx: Int, val args: String, val gestureActive: String)

    /**
     * 找出所有 `ViewerDismissGesture.shouldRevealMask(` 调用点，并**解析该次调用的实参**：
     * 从 `(` 起按括号配平累计到匹配的 `)`（逐行剥掉行尾注释）。
     * 实参文本才是断言对象 —— 这样切片不再依赖任何本项目函数名（R2-L3b）。
     */
    private fun maskCalls(lines: List<String>): List<MaskCall> {
        val out = mutableListOf<MaskCall>()
        for (i in lines.indices) {
            val code = codeOf(lines[i])
            val at = code.indexOf("ViewerDismissGesture.shouldRevealMask(")
            if (at < 0) continue
            val sb = StringBuilder()
            var depth = 0
            var j = i
            while (j < lines.size) {
                val piece = if (j == i) code.substring(at) else codeOf(lines[j])
                for (ch in piece) {
                    if (ch == '(') depth++
                    if (ch == ')') depth--
                    if (depth > 0) sb.append(ch)
                }
                if (depth <= 0) break
                sb.append('\n')
                j++
            }
            assertTrue(
                "第 ${i + 1} 行的 shouldRevealMask( 调用括号不配平，实参无法解析",
                depth <= 0
            )
            val args = sb.toString()
            val literal = Regex("""gestureActive\s*=\s*(true|false)""").find(args)?.groupValues?.get(1) ?: ""
            out += MaskCall(i, args, literal)
        }
        return out
    }

    /**
     * 从 [fromIdx] 向上找最近的**归属块**起始行：行尾是 `{`、缩进严格递减，且行首是
     * `fun ` 或 `LaunchedEffect(`。两者都是语言/框架构造，不随本项目函数改名而变；
     * 用来区分"由 effect 驱动的松手回弹侧"与"由函数承载的跟手拖动侧"。
     */
    private fun ownerKind(lines: List<String>, fromIdx: Int): Pair<Int, String>? {
        var lastIndent = Int.MAX_VALUE
        var i = fromIdx
        while (i >= 0) {
            val code = codeOf(lines[i])
            if (code.endsWith("{")) {
                val indent = leadingSpaces(lines[i])
                if (indent < lastIndent) {
                    val t = code.trimStart()
                    if (t.startsWith("fun ")) return i to "FUN"
                    if (t.startsWith("LaunchedEffect(")) return i to "EFFECT"
                    lastIndent = indent
                }
            }
            i--
        }
        return null
    }

    /** 实参文本压成一行，供失败消息直接展示。 */
    private fun oneLine(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    @Test
    fun `回弹侧必须传 gestureActive 为 false 且拖动侧为 true（按调用点实参与归属块）`() {
        val body = source(imageViewerPath)
        val lines = body.lines()
        val calls = maskCalls(lines)
        assertTrue(
            "透出判定必须走纯函数 shouldRevealMask（拖动侧 + 回弹侧各一次），" +
                "实际调用点 ${calls.size} 个：${calls.map { it.lineIdx + 1 }}",
            calls.isNotEmpty()
        )

        // R2-L3b：切片**不再耦合本项目函数名**。按实参读的数据源给调用点分类：
        // - 回弹侧 = 位移读 Animatable（松手后位移由动画驱动，见 settleDismissBack 的切源）
        // - 拖动侧 = 位移读手势 state（dismissDragY），不读 Animatable
        val settleCalls = calls.filter { it.args.contains("dismissDragAnimatable") }
        val dragCalls = calls.filter {
            !it.args.contains("dismissDragAnimatable") && it.args.contains("dismissDragY")
        }
        assertTrue(
            "必须恰好有一个「读 Animatable 的回弹侧调用」，实际 ${settleCalls.size} 个：" +
                calls.joinToString { "第 ${it.lineIdx + 1} 行[${oneLine(it.args)}]" },
            settleCalls.size == 1
        )
        assertTrue(
            "必须恰好有一个「读手势 state 的拖动侧调用」，实际 ${dragCalls.size} 个：" +
                calls.joinToString { "第 ${it.lineIdx + 1} 行[${oneLine(it.args)}]" },
            dragCalls.size == 1
        )
        assertTrue(
            "存在既不属于回弹侧也不属于拖动侧的 shouldRevealMask 调用点，需人工确认语义后补断言：" +
                calls.filter { it !in settleCalls && it !in dragCalls }
                    .joinToString { "第 ${it.lineIdx + 1} 行[${oneLine(it.args)}]" },
            settleCalls.size + dragCalls.size == calls.size
        )

        val settle = settleCalls.single()
        val drag = dragCalls.single()

        // 核心布尔：直接读**该次调用的实参**里的 gestureActive 字面量。
        // 两处互换（拖动侧 false、回弹侧 true，行为完全反转）时这里必红；
        // 少写这个实参（gestureActive 走默认值）同样必红。
        assertTrue(
            "回弹侧调用（第 ${settle.lineIdx + 1} 行）必须显式传 gestureActive = false（松手即作废）；" +
                "实际实参=(${oneLine(settle.args)})。缺它则淡出会在手指抬起后继续补完，" +
                "图库缩略图在回弹途中反而更清楚 —— 就是闪一下的根因",
            settle.gestureActive == "false"
        )
        assertTrue(
            "拖动侧调用（第 ${drag.lineIdx + 1} 行）必须显式传 gestureActive = true（手指还在屏上才允许透出）；" +
                "实际实参=(${oneLine(drag.args)})",
            drag.gestureActive == "true"
        )

        // 结构性归属（用 LaunchedEffect / fun 这两个不会改名的构造，替代函数名锚点）：
        // 回弹侧必须挂在某个 LaunchedEffect 块里，拖动侧必须落在某个 fun 里，
        // 且该 fun 完整位于 pointerInput(Unit) 手势块内部。
        val settleOwner = ownerKind(lines, settle.lineIdx)
            ?: throw AssertionError("回弹侧调用（第 ${settle.lineIdx + 1} 行）找不到 LaunchedEffect/fun 归属块")
        assertTrue(
            "回弹侧调用（第 ${settle.lineIdx + 1} 行）的归属块必须是 LaunchedEffect（当前是第 " +
                "${settleOwner.first + 1} 行 `${codeOf(lines[settleOwner.first]).trim()}`）——" +
                "透出的收回由 isDismissSettling 驱动的 effect 负责",
            settleOwner.second == "EFFECT"
        )
        val dragOwner = ownerKind(lines, drag.lineIdx)
            ?: throw AssertionError("拖动侧调用（第 ${drag.lineIdx + 1} 行）找不到 LaunchedEffect/fun 归属块")
        assertTrue(
            "拖动侧调用（第 ${drag.lineIdx + 1} 行）的归属块必须是某个 fun（当前是第 " +
                "${dragOwner.first + 1} 行 `${codeOf(lines[dragOwner.first]).trim()}`）——" +
                "跟手位移由拖动侧的局部函数写入",
            dragOwner.second == "FUN"
        )

        val settleOwnerRange = blockRangeAt(lines, settleOwner.first, "回弹侧归属块")
        val dragOwnerRange = blockRangeAt(lines, dragOwner.first, "拖动侧归属块")
        assertTrue(
            "回弹侧归属块（第 ${settleOwnerRange.first + 1}-${settleOwnerRange.last + 1} 行）" +
                "必须真的包住该调用（第 ${settle.lineIdx + 1} 行）",
            settle.lineIdx in settleOwnerRange
        )
        assertTrue(
            "拖动侧归属块（第 ${dragOwnerRange.first + 1}-${dragOwnerRange.last + 1} 行）" +
                "必须真的包住该调用（第 ${drag.lineIdx + 1} 行）",
            drag.lineIdx in dragOwnerRange
        )
        val pointerRange = blockRange(lines, ".pointerInput(Unit) {")
        assertTrue(
            "拖动侧归属块（第 ${dragOwnerRange.first + 1}-${dragOwnerRange.last + 1} 行）必须**完整位于** " +
                "pointerInput(Unit) 块（第 ${pointerRange.first + 1}-${pointerRange.last + 1} 行）内部",
            dragOwnerRange.first > pointerRange.first && dragOwnerRange.last < pointerRange.last
        )

        // R1-L4：旧监听的特征是"订阅 dismissDragAnimatable.value 的 snapshotFlow"。
        // 原先用精确子串比较，换空格/换行即可绕过；这里改成**空白不敏感**的正则。
        // 边界：把属性整体改名仍无法自动识别（源码级契约测试的固有限度，改名时需人工同步）。
        val legacySettleListener =
            Regex("""snapshotFlow\s*\{\s*dismissDragAnimatable\s*\.\s*value""")
        assertFalse(
            "旧的『回弹期间还按位移阈值继续透出』的监听必须删除（空白不敏感匹配）：" +
                "它会让遮罩目标在松手后保持 0.2，把淡出补完",
            legacySettleListener.containsMatchIn(body)
        )
    }
}