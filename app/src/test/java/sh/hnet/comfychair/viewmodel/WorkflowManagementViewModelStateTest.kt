package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType

/**
 * 工作流管理 ViewModel「纯状态机」部分的 JVM 直测（不碰 Android）。
 *
 * applicationContext 在未 initialize 时为 null：`?.getString(...)` 整体短路为 null，
 * 不触碰 Android 资源 API，因此超长名/描述的**截断行为**可以在 JVM 上验证
 * （错误文案无法在此环境断言，需要 Context）。
 *
 * 覆盖：导入对话框类型选择、名字/描述截断、内置工作流的编辑/删除保护、
 * 用户工作流进入编辑对话框的预填、各取消路径的复位、复制建议名。
 * 依赖 storage/协程的函数（confirmEdit/confirmDelete/performExport 等）见未覆盖清单。
 */
class WorkflowManagementViewModelStateTest {

    private val vm = WorkflowManagementViewModel()

    private fun state() = vm.uiState.value

    /** 构造内存态 Workflow（纯数据类，不触发 WorkflowManager 的 Context 逻辑）。 */
    private fun workflow(isBuiltIn: Boolean) = WorkflowManager.Workflow(
        id = "wf-001",
        name = "写实人像",
        description = "高细节肖像工作流",
        jsonContent = "{}",
        type = WorkflowType.TTI,
        isBuiltIn = isBuiltIn
    )

    // ---------- 导入对话框 ----------

    @Test
    fun `onImportTypeSelected记录类型并收起下拉`() {
        vm.onToggleTypeDropdown() // 先展开
        assertTrue(state().importTypeDropdownExpanded)

        vm.onImportTypeSelected(WorkflowType.TTV)
        assertEquals(WorkflowType.TTV, state().importSelectedType)
        assertFalse("选定类型后下拉必须收起", state().importTypeDropdownExpanded)
    }

    @Test
    fun `onToggleTypeDropdown切换两次回到收起态`() {
        assertFalse(state().importTypeDropdownExpanded)
        vm.onToggleTypeDropdown()
        assertTrue(state().importTypeDropdownExpanded)
        vm.onToggleTypeDropdown()
        assertFalse(state().importTypeDropdownExpanded)
    }

    @Test
    fun `超长导入名被截断到40字符`() {
        val longName = "甲".repeat(50)
        vm.onImportNameChange(longName)
        assertEquals(
            "导入名超过 ValidationUtils.MAX_WORKFLOW_NAME_LENGTH(40) 必须被截断",
            40,
            state().importName.length
        )
    }

    @Test
    fun `未超长导入名原样保留`() {
        vm.onImportNameChange("写实人像")
        assertEquals("写实人像", state().importName)
    }

    @Test
    fun `超长导入描述不再被截断（2026-11-27 取消字数限制）`() {
        val longDesc = "乙".repeat(90)
        vm.onImportDescriptionChange(longDesc)
        assertEquals(
            "描述已取消字数限制，必须原样保留 —— 原限制为 80 字，而提示文案写的是 120，" +
                "两者本就不一致；且复制弹窗在确认时会无条件再校验一次，" +
                "导致原描述超 80 字时一个字不改也存不进去",
            longDesc,
            state().importDescription
        )
    }

    @Test
    fun `编辑对话框名字仍截断但描述不再截断`() {
        vm.onEditNameChange("丙".repeat(45))
        assertEquals("名称仍限 40 字", 40, state().editName.length)

        val longDesc = "丁".repeat(500)
        vm.onEditDescriptionChange(longDesc)
        assertEquals("描述已取消字数限制，任意长度都要原样保留", longDesc, state().editDescription)
    }

    // ---------- 编辑/删除的内置工作流保护 ----------

    @Test
    fun `内置工作流不允许进入编辑对话框`() {
        vm.onEditWorkflow(workflow(isBuiltIn = true))
        assertFalse("内置工作流是随包资产，必须拒绝编辑", state().showEditDialog)
        assertNull(state().editingWorkflow)
    }

    @Test
    fun `用户工作流进入编辑对话框并预填原名原描述`() {
        vm.onEditWorkflow(workflow(isBuiltIn = false))
        assertTrue(state().showEditDialog)
        assertEquals("写实人像", state().editName)
        assertEquals("高细节肖像工作流", state().editDescription)
        assertNull(state().editNameError)
        assertNull(state().editDescriptionError)
    }

    @Test
    fun `内置工作流不允许进入删除确认`() {
        vm.onDeleteWorkflow(workflow(isBuiltIn = true))
        assertFalse("内置工作流必须拒绝删除", state().showDeleteDialog)
        assertNull(state().workflowToDelete)
    }

    @Test
    fun `用户工作流进入删除确认并记住目标`() {
        vm.onDeleteWorkflow(workflow(isBuiltIn = false))
        assertTrue(state().showDeleteDialog)
        assertEquals("wf-001", state().workflowToDelete?.id)
    }

    // ---------- 复制对话框 ----------

    @Test
    fun `onDuplicateWorkflow为用户工作流打开复制对话框`() {
        vm.onDuplicateWorkflow(workflow(isBuiltIn = false))
        assertTrue(state().showDuplicateDialog)
        assertEquals("wf-001", state().duplicatingWorkflow?.id)
        assertEquals(
            "建议名必须保留原名并追加 (n) 后缀（WorkflowManager 单例无同名冲突时 n 从 1 起）",
            "写实人像 (1)",
            state().duplicateName
        )
        assertEquals("描述预填自原工作流", "高细节肖像工作流", state().duplicateDescription)
    }

    // ---------- 各取消路径的复位 ----------

    @Test
    fun `cancelImport清空导入暂存与筛选`() {
        vm.onImportTypeSelected(WorkflowType.TTV)
        vm.onImportNameChange("名字")
        vm.onImportDescriptionChange("描述")

        vm.cancelImport()

        // 注意：当前实现的 cancelImport 不重置 importTypeDropdownExpanded（疑似遗漏，
        // 靠 showImportDialog=false 关掉整个对话框兜底）。本测试钉住的是"清空暂存数据"
        // 这条契约，不把下拉标志的现行为钉死成规范。
        assertNull(state().importSelectedType)
        assertEquals("", state().importName)
        assertEquals("", state().importDescription)
        assertNull(state().pendingWorkflowForMapping)
    }

    @Test
    fun `dismissMissingNodesDialog同时收起导入对话框并清空暂存`() {
        vm.onImportTypeSelected(WorkflowType.TTI)
        vm.dismissMissingNodesDialog()
        assertFalse(state().showMissingNodesDialog)
        assertTrue(state().missingNodes.isEmpty())
        assertFalse("缺节点警告关闭后导入对话框不得残留", state().showImportDialog)
        assertNull("缺节点路径放弃后不得留下待映射工作流", state().pendingWorkflowForMapping)
    }

    @Test
    fun `dismissMissingFieldsDialog同时收起导入对话框并清空暂存`() {
        vm.onImportTypeSelected(WorkflowType.TTI)
        vm.dismissMissingFieldsDialog()
        assertFalse(state().showMissingFieldsDialog)
        assertTrue(state().missingFields.isEmpty())
        assertFalse(state().showImportDialog)
        assertNull(state().pendingWorkflowForMapping)
    }

    @Test
    fun `cancelEdit复位编辑对话框`() {
        vm.onEditWorkflow(workflow(isBuiltIn = false))
        vm.cancelEdit()
        assertFalse(state().showEditDialog)
        assertNull(state().editingWorkflow)
        assertEquals("", state().editName)
        assertEquals("", state().editDescription)
    }

    @Test
    fun `cancelDelete复位删除确认`() {
        vm.onDeleteWorkflow(workflow(isBuiltIn = false))
        vm.cancelDelete()
        assertFalse(state().showDeleteDialog)
        assertNull(state().workflowToDelete)
    }

    @Test
    fun `cancelDuplicate复位复制对话框`() {
        vm.onDuplicateWorkflow(workflow(isBuiltIn = false))
        vm.cancelDuplicate()
        assertFalse(state().showDuplicateDialog)
        assertNull(state().duplicatingWorkflow)
        assertEquals("", state().duplicateName)
        assertEquals("", state().duplicateDescription)
    }

    @Test
    fun `cancelMapping清空待映射工作流`() {
        vm.cancelMapping()
        assertNull(state().pendingWorkflowForMapping)
    }
}
