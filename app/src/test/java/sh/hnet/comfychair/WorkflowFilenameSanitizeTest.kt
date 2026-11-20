package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-15：工作流文件名清洗必须保留非 ASCII 字符，否则不同中文名会折叠成同一个文件。
 *
 * 旧实现 `generateFilename` 只保留 `[a-z0-9]`，其余一律换下划线：
 * 「写实流」→ `tti_.json`、「二次元」→ `tti_.json` —— 两个完全不同的工作流抢同一个文件名，
 * 第二次保存要么被拒、要么覆盖第一个的文件。
 *
 * 这里钉住清洗规则（纯函数，不碰磁盘）。写盘前的**唯一性检查**在
 * `generateUniqueFilename` 里，需要 Android 文件目录，由 `isFilenameExists` 承担，
 * 单测只覆盖可离线验证的清洗部分。
 */
class WorkflowFilenameSanitizeTest {

    @Test
    fun `中文名完整保留`() {
        assertEquals("tti_写实流.json", WorkflowManager.generateFilename(WorkflowType.TTI, "写实流"))
        assertEquals("ttv_二次元动画.json", WorkflowManager.generateFilename(WorkflowType.TTV, "二次元动画"))
    }

    @Test
    fun `不同中文名生成不同文件名`() {
        val a = WorkflowManager.generateFilename(WorkflowType.TTI, "写实流")
        val b = WorkflowManager.generateFilename(WorkflowType.TTI, "二次元")

        assertNotEquals("两个不同中文名绝不能折叠成同一个文件名", a, b)
        assertFalse("不允许出现空名片段", a.contains("tti_.json"))
        assertFalse("不允许出现空名片段", b.contains("tti_.json"))
    }

    @Test
    fun `纯符号名回落到默认片段而不是空串`() {
        assertEquals("tti_workflow.json", WorkflowManager.generateFilename(WorkflowType.TTI, "!!!???"))
        assertEquals("tti_workflow.json", WorkflowManager.generateFilename(WorkflowType.TTI, "   "))
    }

    @Test
    fun `路径分隔符与非法字符被替换`() {
        val sanitized = WorkflowManager.sanitizeFilenameSegment("a/b\\c:d*e?f\"g<h>i|j")

        assertFalse("不能留下路径分隔符", sanitized.contains("/"))
        assertFalse("不能留下路径分隔符", sanitized.contains("\\"))
        assertFalse("不能留下非法字符", sanitized.contains(":"))
        assertFalse("不能留下非法字符", sanitized.contains("*"))
        assertFalse("不能留下非法字符", sanitized.contains("?"))
        assertFalse("不能留下非法字符", sanitized.contains("\""))
        assertFalse("不能留下非法字符", sanitized.contains("<"))
        assertFalse("不能留下非法字符", sanitized.contains(">"))
        assertFalse("不能留下非法字符", sanitized.contains("|"))
    }

    @Test
    fun `连字符与下划线保留且连续下划线折叠`() {
        assertEquals("my-workflow", WorkflowManager.sanitizeFilenameSegment("My-Workflow"))
        // 连字符是合法文件名字符，所以 "a - b" 会保留成 "a_-_b"（只折叠连续下划线）
        assertEquals("a_-_b", WorkflowManager.sanitizeFilenameSegment("a - b"))
        assertEquals("a_b_c", WorkflowManager.sanitizeFilenameSegment("a___b___c"))
    }

    @Test
    fun `首尾分隔符被裁掉`() {
        assertEquals("test", WorkflowManager.sanitizeFilenameSegment("__test__"))
        assertEquals("测试", WorkflowManager.sanitizeFilenameSegment("  测试  "))
    }

    @Test
    fun `英文名行为与旧实现一致`() {
        assertEquals("tti_my_workflow.json", WorkflowManager.generateFilename(WorkflowType.TTI, "My Workflow"))
    }

    @Test
    fun `前缀按类型区分`() {
        assertTrue(WorkflowManager.generateFilename(WorkflowType.TTI, "x").startsWith("tti_"))
        assertTrue(WorkflowManager.generateFilename(WorkflowType.TTV, "x").startsWith("ttv_"))
        assertTrue(WorkflowManager.generateFilename(WorkflowType.ITV, "x").startsWith("itv_"))
    }
}
