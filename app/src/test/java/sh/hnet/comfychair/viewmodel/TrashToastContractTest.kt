package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「已移入回收站」提示归属契约（真机录屏缺陷：删除时屏幕上两条相同提示重叠）。
 *
 * 背景：查看器删除成功后有两处提示——撤销条（UI 侧 trashUndo 槽，带「撤销」按钮）
 * 与 ViewModel 的 Toast 用了同一个 `msg_trash_moved`。队列还有剩余项时撤销条正在
 * 显示，再发 Toast 就是两条相同文字交叉淡入重叠成团（真机录屏确认）。
 * 修复：提示由撤销条独占，Toast 只在队列删空（撤销条已被清掉、无"就地撤销"位置）时发。
 *
 * 双轨钉法（与 TrashDeleteOutcomeTest / TrashDeleteWiringTest 同一范式）：
 * 1. 纯判定 [TrashDeleteOutcome.shouldEmitTrashToast] 的档位契约；
 * 2. 源码级接线钉——纯函数测不了"接线还在不在"，按块截取 deleteCurrentItem 的
 *    两个分支，钉住 Toast 的分布（删空分支必须有、剩余项分支必须无）。
 */
class TrashToastContractTest {

    // ============================================================
    // 1. 纯判定：shouldEmitTrashToast
    // ============================================================

    @Test
    fun `队列删空才由ViewModel发Toast-还有剩余项不发`() {
        // 删空（撤销条已被清掉）：Toast 是唯一反馈，必须发
        assertTrue("队列删空时必须发 Toast（否则用户收不到任何成功反馈）", TrashDeleteOutcome.shouldEmitTrashToast(0))
        // 还有剩余项（撤销条正在显示，文字相同）：Toast 必须让位，否则两条重叠
        assertFalse("剩余 1 条时不得发 Toast", TrashDeleteOutcome.shouldEmitTrashToast(1))
        assertFalse("剩余 2 条时不得发 Toast", TrashDeleteOutcome.shouldEmitTrashToast(2))
        assertFalse("剩余 7 条时不得发 Toast", TrashDeleteOutcome.shouldEmitTrashToast(7))
    }

    @Test
    fun `不变量-任意剩余条数下判定只取决于是否删空`() {
        // 多组剩余条数扫描：非空一律不发，与条数大小无关
        for (remaining in 1..64) {
            assertFalse("remaining=$remaining 时不得发 Toast", TrashDeleteOutcome.shouldEmitTrashToast(remaining))
        }
        assertTrue("队列删空时必须发 Toast", TrashDeleteOutcome.shouldEmitTrashToast(0))
    }

    // ============================================================
    // 2. 源码级接线钉：deleteCurrentItem 两个分支的 Toast 分布
    // ============================================================

    /** 单测工作目录是模块根（app/），与 TrashDeleteWiringTest 同口径。 */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * 取一个函数的正文：从签名行起，到**与签名同缩进**的单独一行 `}` 为止
     *（与 TrashDeleteWiringTest.blockOf 同一实现）。
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

    /**
     * 把 `if (currentItems.isEmpty()) { ... } else { ... }` 拆成两个分支体。
     * 按缩进配对：if 体到同缩进 `} else {` 为止，else 体到同缩进单独 `}` 为止
     *（分支体内嵌套块缩进更深，不会被误吞）。
     */
    private fun splitIfElse(functionBlock: String): Pair<String, String> {
        val lines = functionBlock.lines()
        val ifIdx = lines.indexOfFirst { it.contains("if (currentItems.isEmpty())") }
        assertTrue("deleteCurrentItem 里找不到 if (currentItems.isEmpty()) 分支", ifIdx >= 0)
        val count = lines.count { it.contains("if (currentItems.isEmpty())") }
        assertEquals("deleteCurrentItem 里 if (currentItems.isEmpty()) 必须恰好出现一次", 1, count)

        val indent = lines[ifIdx].takeWhile { it == ' ' }.count()
        val elseLine = " ".repeat(indent) + "} else {"
        val closerLine = " ".repeat(indent) + "}"

        val elseIdx = (ifIdx + 1 until lines.size).firstOrNull { lines[it] == elseLine }
            ?: error("找不到同缩进的 } else {")
        val endIdx = (elseIdx + 1 until lines.size).firstOrNull { lines[it] == closerLine }
            ?: error("找不到 else 块的同缩进结束花括号")

        val ifBody = lines.subList(ifIdx + 1, elseIdx).joinToString("\n")
        val elseBody = lines.subList(elseIdx + 1, endIdx).joinToString("\n")
        return ifBody to elseBody
    }

    private val viewerPath = "src/main/java/sh/hnet/comfychair/viewmodel/MediaViewerViewModel.kt"

    @Test
    fun `查看器删除-队列删空分支必须发已移入回收站Toast且走判定函数`() {
        val functionBlock = blockOf(source(viewerPath), "fun deleteCurrentItem() {")
        val (ifBody, _) = splitIfElse(functionBlock)

        // 判定函数必须真实接线（不是死代码），且只在删空分支出现一次
        val firstCall = functionBlock.indexOf("shouldEmitTrashToast(")
        val secondCall = functionBlock.indexOf("shouldEmitTrashToast(", firstCall + 1)
        assertTrue("deleteCurrentItem 必须调用 shouldEmitTrashToast 判定", firstCall >= 0)
        assertFalse("shouldEmitTrashToast 不得出现第二次（判定唯一来源）", secondCall >= 0)
        assertTrue("删空分支必须调用 shouldEmitTrashToast 判定", ifBody.contains("shouldEmitTrashToast("))
        assertTrue("删空分支必须发「已移入回收站」Toast（唯一反馈）", ifBody.contains("R.string.msg_trash_moved"))

        // 分支内其余事件不得顺带丢失（ItemDeleted / Close 有其它逻辑依赖）
        assertTrue("删空分支必须保留 ItemDeleted 事件", ifBody.contains("MediaViewerEvent.ItemDeleted"))
        assertTrue("删空分支必须保留 Close 事件", ifBody.contains("MediaViewerEvent.Close"))
        // 判定必须先于 Toast（包住它），不得只在旁边摆样子
        val judge = ifBody.indexOf("shouldEmitTrashToast(")
        val toast = ifBody.indexOf("R.string.msg_trash_moved")
        assertTrue("判定必须早于 Toast emit", judge in 0 until toast)
    }

    @Test
    fun `查看器删除-还有剩余项分支不得发已移入回收站Toast`() {
        val functionBlock = blockOf(source(viewerPath), "fun deleteCurrentItem() {")
        val (_, elseBody) = splitIfElse(functionBlock)

        // 证明截对了块：else 体应有的关键语句在
        assertTrue("else 体截取异常（找不到 loadCurrentItem）", elseBody.contains("loadCurrentItem()"))
        assertTrue("else 体截取异常（找不到 ItemDeleted）", elseBody.contains("MediaViewerEvent.ItemDeleted"))

        // 核心断言：撤销条正在显示，此处不得再发相同文案的 Toast（真机录屏重叠缺陷）
        assertFalse(
            "还有剩余项的分支不得发「已移入回收站」Toast（提示由撤销条独占）",
            elseBody.contains("R.string.msg_trash_moved")
        )
    }
}

/** Kotlin 标准库没有 String.count(pattern)，补一个小工具保持断言可读。 */
private fun String.count(pattern: String): Int {
    var idx = 0
    var n = 0
    while (true) {
        idx = indexOf(pattern, idx)
        if (idx < 0) return n
        n++
        idx += pattern.length
    }
}
