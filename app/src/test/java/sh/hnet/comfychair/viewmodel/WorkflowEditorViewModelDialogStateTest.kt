package sh.hnet.comfychair.viewmodel

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.workflow.DiscardAction

/**
 * 工作流编辑器「对话框与空态防御」状态机的 JVM 直测（不碰 Android、不碰 ConnectionManager）。
 *
 * 覆盖 WorkflowEditorViewModel 中无 Context 依赖的部分：
 * - 保存对话框（类型选择/下拉/名字/描述）的纯状态写入；
 * - 放弃确认对话框（discard）的打开/关闭与 EXIT_EDIT_MODE 分支（该分支走
 *   exitEditMode()，不发协程事件，JVM 安全；CLOSE_EDITOR 分支碰 viewModelScope，不测）；
 * - 节点浏览器的开合；
 * - 未初始化（无图）时的空态防御：hasUnsavedWork=false、export=null、edits=空。
 */
class WorkflowEditorViewModelDialogStateTest {

    private val vm = WorkflowEditorViewModel()

    private fun state() = vm.uiState.value

    // ---------- 保存对话框 ----------

    @Test
    fun `onSaveDialogTypeSelected记录类型并收起下拉`() {
        vm.onSaveDialogToggleTypeDropdown() // 先展开
        assertTrue(state().saveDialogTypeDropdownExpanded)

        vm.onSaveDialogTypeSelected(WorkflowType.TTV)
        assertEquals(WorkflowType.TTV, state().saveDialogSelectedType)
        assertFalse("选定类型后下拉必须收起", state().saveDialogTypeDropdownExpanded)
    }

    @Test
    fun `onSaveDialogToggleTypeDropdown切换两次回到收起态`() {
        assertFalse(state().saveDialogTypeDropdownExpanded)
        vm.onSaveDialogToggleTypeDropdown()
        assertTrue(state().saveDialogTypeDropdownExpanded)
        vm.onSaveDialogToggleTypeDropdown()
        assertFalse(state().saveDialogTypeDropdownExpanded)
    }

    @Test
    fun `onSaveDialogNameChange记录名字`() {
        vm.onSaveDialogNameChange("写实人像")
        assertEquals("写实人像", state().saveDialogName)
    }

    @Test
    fun `onSaveDialogDescriptionChange记录描述`() {
        vm.onSaveDialogDescriptionChange("高细节肖像工作流")
        assertEquals("高细节肖像工作流", state().saveDialogDescription)
    }

    // ---------- 放弃确认（discard） ----------

    @Test
    fun `showDiscardConfirmation记录要执行的动作`() {
        vm.showDiscardConfirmation(DiscardAction.CLOSE_EDITOR)
        assertTrue(state().showDiscardConfirmation)
        assertEquals(
            "动作必须被记录：确认时按它分流到退出编辑或关闭编辑器",
            DiscardAction.CLOSE_EDITOR,
            state().discardAction
        )
    }

    @Test
    fun `dismissDiscardConfirmation只关对话框不改记录的动作`() {
        vm.showDiscardConfirmation(DiscardAction.EXIT_EDIT_MODE)
        vm.dismissDiscardConfirmation()
        assertFalse(state().showDiscardConfirmation)
        assertEquals(
            "关闭对话框不得顺手清掉动作记录",
            DiscardAction.EXIT_EDIT_MODE,
            state().discardAction
        )
    }

    @Test
    fun `confirmDiscard按退出编辑模式分支复位编辑态`() {
        vm.showDiscardConfirmation(DiscardAction.EXIT_EDIT_MODE)
        vm.confirmDiscard()
        assertFalse(state().showDiscardConfirmation)
        assertFalse("确认放弃后必须退出编辑模式", state().isEditMode)
        assertTrue(state().selectedNodeIds.isEmpty())
        assertTrue(state().selectedNoteIds.isEmpty())
        assertFalse(state().hasUnsavedChanges)
        assertFalse(state().showNodeBrowser)
        assertNull(state().nodeInsertPosition)
    }

    // ---------- 节点浏览器 ----------

    @Test
    fun `showNodeBrowser记录插入位置`() {
        vm.showNodeBrowser(Offset(5f, 9f))
        assertTrue(state().showNodeBrowser)
        assertEquals("插入位置必须被记住，新增节点落在这里", 5f, state().nodeInsertPosition?.x ?: -1f, 0f)
        assertEquals(9f, state().nodeInsertPosition?.y ?: -1f, 0f)
    }

    @Test
    fun `hideNodeBrowser收起并清空插入位置`() {
        vm.showNodeBrowser(Offset(5f, 9f))
        vm.hideNodeBrowser()
        assertFalse(state().showNodeBrowser)
        assertNull("残留插入位置会让下一次新增节点落到旧坐标", state().nodeInsertPosition)
    }

    // ---------- 空态防御（未 initialize、无图） ----------

    @Test
    fun `未初始化时exportToJson返回null`() {
        assertNull("无图可导出必须返回 null（调用方按 null 提示），不得崩溃", vm.exportToJson())
    }

    @Test
    fun `未初始化时exportToJsonWithWrapper返回null`() {
        assertNull(vm.exportToJsonWithWrapper())
    }

    @Test
    fun `未初始化时getEditsForNode返回空映射`() {
        assertTrue(vm.getEditsForNode("node-1").isEmpty())
    }

    @Test
    fun `未初始化时hasUnsavedWork为false`() {
        assertFalse("无图的冷 ViewModel 不能声称有未保存工作，否则一进页面就弹放弃确认", vm.hasUnsavedWork())
    }
}
