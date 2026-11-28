package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「B4.2-C 三类拆分还在不在」的源码级钉子（照 `FeatureWiringContractTest` 的先例约定：
 * 读源文件、折叠空白、断言语句存在性）。
 *
 * 为什么需要：`WorkflowEditorViewModel`（3381 行）拆成「图操作器 WorkflowGraphEditor +
 * 保存协调器 WorkflowSaveCoordinator + ViewModel 转发壳」后，纯 JVM 单测够不着
 * Context/OkHttp 依赖的生产代码，拆分本身（函数搬没搬对家、事件发射有没有归位、
 * 保存协调器的 pendingSaveData 有没有被偷偷内联回 ViewModel）没有行为级覆盖。
 * 这里用**读源码文本**的方式钉住：
 *
 * - 事件发射归属：MappingConfirmed/MappingCancelled 只能由图操作器发射（上传流），
 *   WorkflowCreated/WorkflowUpdated/SaveFailed/CreateCancelled 只能由保存协调器发射（保存流）；
 * - pendingSaveData 状态只能活在保存协调器里，ViewModel 与图操作器都不得持有；
 * - ViewModel 必须保留无参构造（既有 DialogStateTest / ViewportTest 无参 new）与
 *   `_uiState`/`_events` 声明，公共方法全部保留为转发 API。
 *
 * 断言前把空白折叠成单空格，所以换行与缩进调整不会误报。
 */
class WorkflowEditorSplitContractTest {

    private val viewModelPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/WorkflowEditorViewModel.kt"
    private val graphEditorPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/editor/WorkflowGraphEditor.kt"
    private val saveCoordinatorPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/editor/WorkflowSaveCoordinator.kt"

    /** 读源码；单测工作目录是模块根（`app/`），与 `FeatureWiringContractTest` 同一口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根；" +
                "若文件被移动，请同步更新本测试里的路径",
            file.exists()
        )
        return file.readText()
    }

    /** 折叠空白：断言跨行表达式，换行/缩进调整不会误报。 */
    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    // ------------------------------------------------------------ ① 拆分文件存在且含关键签名

    @Test
    fun `拆分出的图操作器与保存协调器文件存在且含关键函数`() {
        val graphEditor = source(graphEditorPath)
        val coordinator = source(saveCoordinatorPath)

        // 图操作器：初始化/加载、映射、增删节点、连线、编辑模式还原都要落在这里
        assertTrue("图操作器必须持有工作流加载入口 initialize", graphEditor.contains("fun initialize(context: Context"))
        assertTrue("图操作器必须持有映射模式初始化 initializeForMapping", graphEditor.contains("fun initializeForMapping("))
        assertTrue("图操作器必须持有创建模式初始化 initializeForCreation", graphEditor.contains("fun initializeForCreation(context: Context"))
        assertTrue("图操作器必须持有编辑既有流程初始化 initializeForEditingExisting", graphEditor.contains("fun initializeForEditingExisting(context: Context"))
        assertTrue("图操作器必须持有节点属性编辑入口 onNodeTappedForEditing", graphEditor.contains("fun onNodeTappedForEditing("))
        assertTrue("图操作器必须持有增节点 addNode", graphEditor.contains("fun addNode(nodeType: NodeTypeDefinition"))
        assertTrue("图操作器必须持有连线 connectToTarget", graphEditor.contains("fun connectToTarget(targetSlot: SlotPosition"))
        assertTrue("图操作器必须持有编辑模式进入 enterEditMode", graphEditor.contains("fun enterEditMode()"))
        assertTrue("图操作器必须持有编辑模式还原 exitEditMode", graphEditor.contains("fun exitEditMode()"))
        assertTrue("图操作器必须持有保存后回视图模式 returnToViewMode", graphEditor.contains("fun returnToViewMode("))
        assertTrue("图操作器必须持有拖拽 endNodeDrag", graphEditor.contains("fun endNodeDrag()"))

        // 保存协调器：未保存检查、对话框族、两条保存主链、pendingSaveData
        assertTrue("保存协调器必须持有未保存检查 hasUnsavedWork", coordinator.contains("fun hasUnsavedWork(): Boolean"))
        assertTrue("保存协调器必须持有保存对话框 showSaveDialog", coordinator.contains("fun showSaveDialog(context: Context"))
        assertTrue("保存协调器必须持有新建保存主链 proceedWithSave", coordinator.contains("fun proceedWithSave(context: Context"))
        assertTrue("保存协调器必须持有确认映射落盘 confirmMappingAndSave", coordinator.contains("fun confirmMappingAndSave(context: Context"))
        assertTrue("保存协调器必须持有暂存保存数据 pendingSaveData", coordinator.contains("pendingSaveData: PendingSaveData?"))
        assertTrue("保存协调器必须提供 PendingSaveData 数据类", coordinator.contains("data class PendingSaveData("))
    }

    // ------------------------------------------------------------ ② 事件发射归位

    @Test
    fun `上传流事件只能由图操作器发射`() {
        val graphEditor = folded(source(graphEditorPath))
        val coordinator = folded(source(saveCoordinatorPath))

        // MappingConfirmed（确认映射回传上传流）与 MappingCancelled：图操作器发射
        assertTrue(
            "MappingConfirmed 必须由图操作器经 _events.emit 发射",
            graphEditor.contains("_events.emit(WorkflowEditorEvent.MappingConfirmed(")
        )
        assertTrue(
            "MappingCancelled 必须由图操作器经 _events.emit 发射",
            graphEditor.contains("_events.emit(WorkflowEditorEvent.MappingCancelled)")
        )
        assertFalse(
            "保存协调器不得发射上传流事件 MappingConfirmed",
            coordinator.contains("WorkflowEditorEvent.MappingConfirmed(")
        )
        assertFalse(
            "保存协调器不得发射上传流事件 MappingCancelled",
            coordinator.contains("WorkflowEditorEvent.MappingCancelled")
        )
    }

    @Test
    fun `保存流事件只能由保存协调器发射`() {
        val graphEditor = folded(source(graphEditorPath))
        val coordinator = folded(source(saveCoordinatorPath))

        // WorkflowCreated / WorkflowUpdated / SaveFailed / CreateCancelled：保存协调器发射
        assertTrue(
            "WorkflowCreated 必须由保存协调器经 _events.emit 发射",
            coordinator.contains("_events.emit(WorkflowEditorEvent.WorkflowCreated(")
        )
        assertTrue(
            "WorkflowUpdated 必须由保存协调器经 _events.emit 发射",
            coordinator.contains("_events.emit(WorkflowEditorEvent.WorkflowUpdated(")
        )
        assertTrue(
            "SaveFailed 必须由保存协调器经 _events.emit 发射（保存失败 ≠ 用户取消）",
            coordinator.contains("_events.emit(WorkflowEditorEvent.SaveFailed(")
        )
        assertTrue(
            "CreateCancelled 必须由保存协调器经 _events.emit 发射",
            coordinator.contains("_events.emit(WorkflowEditorEvent.CreateCancelled)")
        )
        assertFalse(
            "图操作器不得发射保存流事件 WorkflowCreated/WorkflowUpdated/SaveFailed/CreateCancelled",
            graphEditor.contains("WorkflowEditorEvent.WorkflowCreated(") ||
                graphEditor.contains("WorkflowEditorEvent.WorkflowUpdated(") ||
                graphEditor.contains("WorkflowEditorEvent.SaveFailed(") ||
                graphEditor.contains("WorkflowEditorEvent.CreateCancelled")
        )
    }

    // ------------------------------------------------------------ ③ ViewModel 转发壳本体

    @Test
    fun `ViewModel 保留无参构造与状态流声明并转发到协作者`() {
        val vm = source(viewModelPath)

        // 无参构造：两个既有测试（DialogStateTest / ViewportTest）无参 new，签名不许变。
        // 拆分后类声明追加了 WorkflowEditorGraphContext 接口实现，钉子文本随之切换，意图不变：
        // 主构造仍无参数（类名后直接是接口列表 + 大括号）。
        assertTrue(
            "WorkflowEditorViewModel 必须保留无参构造（既有测试无参 new）",
            vm.contains("class WorkflowEditorViewModel : ViewModel(), WorkflowEditorGraphContext {")
        )
        assertTrue(
            "ViewModel 必须保留 _uiState 声明",
            vm.contains("private val _uiState = MutableStateFlow(WorkflowEditorUiState())")
        )
        assertTrue(
            "ViewModel 必须保留 _events 声明",
            vm.contains("private val _events = MutableSharedFlow<WorkflowEditorEvent>()")
        )

        // 转发 API：公共方法必须保留在 ViewModel 上并委托到协作者（每个采样一组）
        val forwardingSamples = listOf(
            "fun initialize(context: Context, workflowId: String?, workflowJson: String?)",
            "fun hasUnsavedWork(): Boolean",
            "fun proceedWithSave(context: Context)",
            "fun confirmMappingAndSave(context: Context)",
            "fun addNode(nodeType: NodeTypeDefinition)",
            "fun addNodeManual(nodeType: NodeTypeDefinition)",
            "fun connectToTarget(targetSlot: SlotPosition)",
            "fun enterEditMode()",
            "fun exitEditMode()",
            "fun exportToJson(): String?",
        )
        forwardingSamples.forEach { signature ->
            assertTrue("ViewModel 必须保留转发 API：$signature", vm.contains(signature))
        }
        // 委托证据：转发壳里必须出现对两个协作者的调用（而不是把实现留在壳里）
        assertTrue("ViewModel 必须装配图操作器 WorkflowGraphEditor", vm.contains("WorkflowGraphEditor("))
        assertTrue("ViewModel 必须装配保存协调器 WorkflowSaveCoordinator", vm.contains("WorkflowSaveCoordinator("))
        assertTrue(
            "ViewModel 必须把图操作类调用委托给 graphEditor",
            vm.contains("graphEditor.")
        )
        assertTrue(
            "ViewModel 必须把保存类调用委托给 saveCoordinator",
            vm.contains("saveCoordinator.")
        )
    }

    // ------------------------------------------------------------ ④ pendingSaveData 归位

    @Test
    fun `pendingSaveData 只能活在保存协调器里`() {
        val vm = source(viewModelPath)
        val graphEditor = source(graphEditorPath)
        val coordinator = source(saveCoordinatorPath)

        // 拆分后 returnToViewMode 整体迁入图操作器（原 ViewModel 内的函数体不复存在），
        // 钉子目标随拆解同步切换、意图不变：任何一方都不得再直接引用 pendingSaveData 字段。
        assertFalse(
            "ViewModel 不得持有 pendingSaveData（暂存保存数据归保存协调器）",
            vm.contains("pendingSaveData")
        )
        assertFalse(
            "图操作器不得直接引用 pendingSaveData 字段（returnToViewMode 清空须改经保存协调器接口）",
            graphEditor.contains("pendingSaveData")
        )
        assertTrue(
            "保存协调器必须持有 pendingSaveData 字段",
            coordinator.contains("private var pendingSaveData: PendingSaveData? = null")
        )

        // returnToViewMode（已在图操作器里）对 pendingSaveData 的访问必须走协调器接口：
        // 原语义是「保存成功回视图模式时清空暂存数据」，等价替换为 clearPendingSave() 调用。
        val body = folded(bodyOf(graphEditor, "override fun returnToViewMode("))
        assertTrue(
            "returnToViewMode 必须经保存协调器接口 clearPendingSave() 清空暂存数据（原 pendingSaveData = null）",
            body.contains("clearPendingSave()")
        )
        assertFalse(
            "returnToViewMode 函数体内不得直接引用 pendingSaveData 字段",
            body.contains("pendingSaveData")
        )

        // 清空动作本身必须真的作用于协调器内的字段
        val clearBody = folded(bodyOf(coordinator, "override fun clearPendingSave()"))
        assertTrue(
            "clearPendingSave 必须清空保存协调器内的 pendingSaveData",
            clearBody.contains("pendingSaveData = null")
        )
    }

    /**
     * 取出一个函数的正文：从签名起，到下一个 4 空格缩进的成员声明之前（**原样保留空白**，
     * 由调用方决定是按"整行语句"还是按"折叠后的表达式"来断言）。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到函数：$signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val nextMember = Regex("""\n {4}(private |internal |public |protected )?(fun|val|var)""").find(rest)
        return if (nextMember != null) rest.substring(0, nextMember.range.first) else rest
    }
}
