package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「移入回收站」返回值处理的两条源码级接线钉子（第 1 轮复核 R1-M1 / R1-L2）。
 *
 * 为什么需要源码断言：`TrashDeleteOutcomeTest` 只证明纯对象本身算得对，证明不了
 * 生产代码真的**调用**了它。复核时把 `deleteCurrentItem` 里的守卫整段删掉，
 * 全量单测仍然全绿 —— 说明没有测试触及调用点。纯函数测不了"这行 if 还在不在"，
 * 因此按项目既有范式（ViewerDismissWiringTest / UpdateInstallGateWiringTest）
 * 直接读源码钉住结构。
 *
 * 覆盖：
 * 1. 查看器删除：必须先判 `moveToTrash` 返回值，再 `removeAt` / 填撤销槽 / 报成功；
 * 2. 查看器删除失败分支：必须提前 `return@launch` 并提示删除失败，不得提示"已移入回收站"；
 * 3. 图库批量删除：全失败（FAILED）时保留选择，不得无条件 `clearSelection()`。
 */
class TrashDeleteWiringTest {

    /** 单测工作目录是模块根（app/），与 ViewerDismissWiringTest 同口径。 */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * 取一个函数的正文：从签名行起，到**与签名同缩进**的单独一行 `}` 为止。
     * 按缩进配对，避免把后面的代码吞进来（与 ViewerDismissWiringTest.blockOf 同一实现）。
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

    private val viewerPath = "src/main/java/sh/hnet/comfychair/viewmodel/MediaViewerViewModel.kt"
    private val galleryViewModelPath = "src/main/java/sh/hnet/comfychair/viewmodel/GalleryViewModel.kt"

    // ============================================================
    // 1. 查看器删除：守卫顺序
    // ============================================================

    @Test
    fun `查看器删除必须先判返回值再移除条目`() {
        val block = blockOf(source(viewerPath), "fun deleteCurrentItem() {")

        val guard = block.indexOf("TrashDeleteOutcome.single(identities)")
        val remove = block.indexOf("currentItems.removeAt(")
        val undo = block.indexOf("identities.firstOrNull()?.let")
        val flag = block.indexOf("_hasDeletedItems.value = true")
        val successToast = block.indexOf("R.string.msg_trash_moved")

        assertTrue("必须消费 moveToTrash 的返回值（找不到 TrashDeleteOutcome.single(identities)）", guard >= 0)
        assertTrue("找不到 removeAt", remove >= 0)
        assertTrue("找不到撤销槽", undo >= 0)
        assertTrue("找不到 hasDeletedItems 标记", flag >= 0)
        assertTrue("找不到成功提示", successToast >= 0)

        // 回滚复核 R1-M1 的核心：删掉守卫后这四条会同时变红
        assertTrue("返回值判定必须早于 removeAt", guard < remove)
        assertTrue("返回值判定必须早于撤销槽", guard < undo)
        assertTrue("返回值判定必须早于 hasDeletedItems", guard < flag)
        assertTrue("返回值判定必须早于『已移入回收站』提示", guard < successToast)
    }

    // ============================================================
    // 2. 查看器删除失败分支：提前返回 + 报失败
    // ============================================================

    @Test
    fun `查看器删除失败分支必须提前返回并提示失败`() {
        val block = blockOf(source(viewerPath), "fun deleteCurrentItem() {")
        val lines = block.lines()

        val start = lines.indexOfFirst {
            it.contains("if (TrashDeleteOutcome.single(identities) == TrashDeleteResult.FAILED)")
        }
        assertTrue("找不到失败守卫", start >= 0)

        // 守卫体到第一个单独一行 `}` 为止（体内无嵌套花括号）
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "}" }
        assertTrue("找不到失败守卫的结束花括号", end != null)

        val guardBody = lines.subList(start, end!! + 1).joinToString("\n")
        assertTrue("失败分支必须提前结束，不得继续走到 removeAt", guardBody.contains("return@launch"))
        assertTrue("失败分支必须提示删除失败", guardBody.contains("R.string.error_history_item_delete"))
        assertFalse(
            "失败分支不得提示『已移入回收站』",
            guardBody.contains("R.string.msg_trash_moved")
        )
        assertFalse("失败分支不得移除条目", guardBody.contains("removeAt("))
    }

    // ============================================================
    // 3. 图库批量删除：全失败保留选择（R1-L2）
    // ============================================================

    @Test
    fun `图库批量删除全失败时不得清空选择`() {
        val block = blockOf(source(galleryViewModelPath), "fun deleteSelected() {")

        val guard = block.indexOf("if (outcome != TrashDeleteResult.FAILED)")
        assertTrue("必须用 FAILED 判定保护 clearSelection（找不到 if (outcome != TrashDeleteResult.FAILED)）", guard >= 0)

        val clear = block.indexOf("clearSelection()", guard)
        assertTrue("清空选择必须发生在失败保护之后", clear > guard)

        // 保证不是"守卫之后又无条件清空"：从守卫处到函数末尾只允许一次 clearSelection()
        val firstClear = block.indexOf("clearSelection()")
        val secondClear = block.indexOf("clearSelection()", firstClear + 1)
        assertTrue("clearSelection 不得出现在失败保护之前", firstClear >= guard)
        assertFalse("失败保护之外还有额外的无条件 clearSelection", secondClear >= 0)
    }
}