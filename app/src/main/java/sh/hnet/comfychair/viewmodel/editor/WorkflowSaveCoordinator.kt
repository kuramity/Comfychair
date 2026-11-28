package sh.hnet.comfychair.viewmodel.editor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.WorkflowEditorEvent
import sh.hnet.comfychair.workflow.DiscardAction
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.WorkflowEditorUiState
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowSerializer
import sh.hnet.comfychair.workflow.mapping.FieldMappingAnalyzer
import sh.hnet.comfychair.workflow.validation.WorkflowJsonAnalyzer

/**
 * 图操作器 → 保存协调器的窄接口：探测节点类型注册表（加载链路用）、
 * 清空暂存保存数据（保存成功回视图模式时用）。
 */
interface WorkflowSaveCoordinatorBridge {
    fun fetchObjectInfo(callback: (success: Boolean) -> Unit)
    fun clearPendingSave()
}

/**
 * 保存校验用的节点可用性探针结果：[ConnectionManager.clientOrNull] 为空与
 * fetchAllNodeTypes 返回空在 UI 上走**不同的报错路径**（前者是「未连接服务器」，
 * 后者是「拉取节点类型失败」），必须区分。
 */
sealed class WorkflowNodeFetchResult {
    /** 无可用客户端连接（对应原 ConnectionManager.clientOrNull == null 分支）。 */
    object NoConnection : WorkflowNodeFetchResult()

    /** 拉取成功，携带服务器可用的节点类型集合。 */
    data class Available(val nodes: Set<String>) : WorkflowNodeFetchResult()

    /** 客户端可用但拉取失败（对应原 fetchAllNodeTypes 回调 null 分支）。 */
    object FetchFailed : WorkflowNodeFetchResult()
}

/**
 * 保存校验的节点可用性探针：函数式接口，包住 ConnectionManager.clientOrNull +
 * client.fetchAllNodeTypes，便于将来 JVM 测试注入假探针。
 */
fun interface WorkflowSaveValidator {
    fun fetchAvailableNodes(onResult: (WorkflowNodeFetchResult) -> Unit)
}

/**
 * 保存协调器：B4.2-C 从 WorkflowEditorViewModel（原 3381 行）拆出的保存域逻辑——
 * 未保存检查、丢弃/保存对话框族、proceedWithSave / proceedWithEditExistingSave
 * 两条保存主链、confirmMappingAndSave 落盘与事件发射。
 *
 * 拆分约定（照 ConnectionManager→ModelListCatalog 的转发 API 先例）：
 * - 函数体自原 ViewModel 逐字搬运，除编译必需的引用改道外零改动；
 * - 对当前可保存图的读取经 [WorkflowGraphEditorBridge]（currentGraph/hasEdits）窄接口；
 * - 保存校验的节点可用性探测经 [WorkflowSaveValidator]（可注入，便于将来 JVM 测试）；
 * - 时序红线：proceedWithSave 保持 WorkflowManager 校验 → clientOrNull →
 *   fetchAllNodeTypes → 落盘的原顺序；confirmMappingAndSave 保持 serializer →
 *   WorkflowManager.update/addUserWorkflowWithMapping → 事件发射的原顺序。
 */
class WorkflowSaveCoordinator(
    private val _uiState: MutableStateFlow<WorkflowEditorUiState>,
    private val _events: MutableSharedFlow<WorkflowEditorEvent>,
    private val graphContext: WorkflowEditorGraphContext,
    private val graphEditor: WorkflowGraphEditorBridge,
    // 宿主 ViewModel.viewModelScope 经构造注入，保持原函数体 `viewModelScope.launch` 字面零改动
    private val viewModelScope: CoroutineScope
) : WorkflowSaveCoordinatorBridge {

    private companion object {
        const val TAG = "WorkflowEditor"
    }

    // Use shared NodeTypeRegistry from ConnectionManager (populated on connection)
    private val nodeTypeRegistry: NodeTypeRegistry
        get() = graphContext.nodeTypeRegistry

    private var currentWorkflowId: String?
        get() = graphContext.currentWorkflowId
        set(value) { graphContext.currentWorkflowId = value }

    private var workflowValuesStorage: WorkflowValuesStorage?
        get() = graphContext.workflowValuesStorage
        set(value) { graphContext.workflowValuesStorage = value }

    // 序列化器无状态，拆分后图操作器与保存协调器各自 new
    private val serializer = WorkflowSerializer()

    // 保存校验探针（默认实现包住 ConnectionManager，行为与拆分前逐分支一致）
    private val saveValidator = WorkflowSaveValidator { onResult ->
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            // 同步回调：与拆分前 clientOrNull == null 时同步 return 的时序一致
            onResult(WorkflowNodeFetchResult.NoConnection)
        } else {
            client.fetchAllNodeTypes { availableNodes ->
                onResult(
                    if (availableNodes == null) WorkflowNodeFetchResult.FetchFailed
                    else WorkflowNodeFetchResult.Available(availableNodes)
                )
            }
        }
    }

    // Data to hold pending save information
    private data class PendingSaveData(
        val name: String,
        val description: String,
        val type: WorkflowType,
        val graph: WorkflowGraph
    )
    private var pendingSaveData: PendingSaveData? = null

    // ===========================================
    // WorkflowSaveCoordinatorBridge（图操作器经此调用）
    // ===========================================

    /**
     * Check if node type registry is populated (data loaded by ConnectionManager on connection).
     * Calls the callback with true if registry is available, false otherwise.
     */
    override fun fetchObjectInfo(callback: (success: Boolean) -> Unit) {
        // Node type registry is now populated by ConnectionManager on connection
        // Just check if it's available
        callback(nodeTypeRegistry.isPopulated())
    }

    override fun clearPendingSave() {
        pendingSaveData = null
    }

    // ========== Create Mode Methods ==========

    /**
     * Check if there are unsaved changes that would be lost
     */
    fun hasUnsavedWork(): Boolean {
        val state = _uiState.value

        // Check the hasUnsavedChanges flag (set when nodes are added/deleted/rewired)
        if (state.hasUnsavedChanges) return true

        // For create mode, also check if any nodes exist
        if (state.isCreateMode) {
            return graphEditor.hasEdits()
        }

        return false
    }

    /**
     * Show discard confirmation dialog with specified action
     */
    fun showDiscardConfirmation(action: DiscardAction = DiscardAction.EXIT_EDIT_MODE) {
        _uiState.value = _uiState.value.copy(
            showDiscardConfirmation = true,
            discardAction = action
        )
    }

    /**
     * Dismiss discard confirmation dialog
     */
    fun dismissDiscardConfirmation() {
        _uiState.value = _uiState.value.copy(showDiscardConfirmation = false)
    }

    /**
     * Confirm discard - action depends on discardAction state
     */
    fun confirmDiscard() {
        val action = _uiState.value.discardAction
        _uiState.value = _uiState.value.copy(showDiscardConfirmation = false)

        when (action) {
            DiscardAction.EXIT_EDIT_MODE -> graphEditor.exitEditMode()
            DiscardAction.CLOSE_EDITOR -> {
                viewModelScope.launch {
                    _events.emit(WorkflowEditorEvent.CreateCancelled)
                }
            }
        }
    }

    /**
     * Handle exit edit mode button - show confirmation if unsaved changes exist,
     * otherwise just exit edit mode. This returns to view mode but stays in editor.
     */
    fun handleExitEditModeWithConfirmation() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.EXIT_EDIT_MODE)
        } else {
            graphEditor.exitEditMode()
        }
    }

    /**
     * Handle close button in create mode - show confirmation if work exists,
     * then close the editor.
     */
    fun handleCreateModeClose() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.CLOSE_EDITOR)
        } else {
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.CreateCancelled)
            }
        }
    }

    /**
     * Handle close button in edit-existing mode.
     * Shows discard confirmation if there are unsaved changes, then closes editor.
     */
    fun handleEditExistingModeClose() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.CLOSE_EDITOR)
        } else {
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.CreateCancelled)
            }
        }
    }

    /**
     * Show save dialog when Done is pressed in create mode.
     * For edit-existing mode, skips the dialog and proceeds directly to validation.
     * Auto-detects workflow type from graph nodes.
     */
    fun showSaveDialog(context: Context) {
        val state = _uiState.value
        val hasExistingWorkflow = state.editingWorkflowId != null
        DebugLogger.i(TAG, "showSaveDialog: hasExistingWorkflow=$hasExistingWorkflow")
        val graph = graphEditor.currentGraph()
        if (graph == null) {
            DebugLogger.w(TAG, "showSaveDialog: No mutable graph available")
            return
        }
        if (graph.nodes.isEmpty()) {
            DebugLogger.w(TAG, "showSaveDialog: Cannot save empty workflow")
            return
        }

        DebugLogger.d(TAG, "showSaveDialog: Graph has ${graph.nodes.size} nodes, ${graph.edges.size} edges")

        // For existing workflow, skip the dialog and proceed directly to validation
        if (hasExistingWorkflow) {
            DebugLogger.i(TAG, "showSaveDialog: Existing workflow, skipping dialog")
            proceedWithEditExistingSave(context)
            return
        }

        // Auto-detect type from nodes
        val detectedType = detectWorkflowTypeFromGraph(graph)
        DebugLogger.d(TAG, "showSaveDialog: Detected type=$detectedType")

        _uiState.value = _uiState.value.copy(
            showSaveDialog = true,
            saveDialogSelectedType = detectedType,
            saveDialogName = "",
            saveDialogDescription = "",
            saveDialogNameError = null,
            saveDialogDescriptionError = null,
            isSaveValidating = false
        )
    }

    /**
     * Cancel save dialog
     */
    fun cancelSaveDialog() {
        _uiState.value = _uiState.value.copy(
            showSaveDialog = false,
            saveDialogSelectedType = null,
            saveDialogName = "",
            saveDialogDescription = "",
            saveDialogNameError = null,
            saveDialogDescriptionError = null
        )
    }

    /**
     * Update save dialog type selection
     */
    fun onSaveDialogTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            saveDialogSelectedType = type,
            saveDialogTypeDropdownExpanded = false
        )
    }

    /**
     * Toggle save dialog type dropdown
     */
    fun onSaveDialogToggleTypeDropdown() {
        _uiState.value = _uiState.value.copy(
            saveDialogTypeDropdownExpanded = !_uiState.value.saveDialogTypeDropdownExpanded
        )
    }

    /**
     * Update save dialog name
     */
    fun onSaveDialogNameChange(name: String) {
        _uiState.value = _uiState.value.copy(
            saveDialogName = name,
            saveDialogNameError = null
        )
    }

    /**
     * Update save dialog description
     */
    fun onSaveDialogDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(
            saveDialogDescription = description,
            saveDialogDescriptionError = null
        )
    }

    /**
     * Dismiss duplicate name dialog
     */
    fun dismissDuplicateNameDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateNameDialog = false)
    }

    /**
     * Dismiss missing nodes dialog
     */
    fun dismissMissingNodesDialog() {
        _uiState.value = _uiState.value.copy(showMissingNodesDialog = false)
    }

    /**
     * Dismiss missing fields dialog
     */
    fun dismissMissingFieldsDialog() {
        _uiState.value = _uiState.value.copy(showMissingFieldsDialog = false)
    }

    /**
     * Detect workflow type by analyzing nodes in the graph.
     */
    private fun detectWorkflowTypeFromGraph(graph: WorkflowGraph): WorkflowType? {
        return WorkflowJsonAnalyzer.detectWorkflowTypeFromGraph(graph)
    }

    /**
     * Proceed with save - validates and enters mapping mode.
     */
    fun proceedWithSave(context: Context) {
        DebugLogger.i(TAG, "proceedWithSave: Starting validation")
        val state = _uiState.value
        val graph = graphEditor.currentGraph()
        if (graph == null) {
            DebugLogger.e(TAG, "proceedWithSave: No mutable graph available")
            return
        }
        val selectedType = state.saveDialogSelectedType
        if (selectedType == null) {
            DebugLogger.e(TAG, "proceedWithSave: No workflow type selected")
            return
        }
        val name = state.saveDialogName.trim()
        val description = state.saveDialogDescription.trim()

        DebugLogger.d(TAG, "proceedWithSave: name='$name', type=$selectedType, nodes=${graph.nodes.size}")

        // Validate name format
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            DebugLogger.w(TAG, "proceedWithSave: Name validation failed: $nameError")
            _uiState.value = _uiState.value.copy(
                saveDialogNameError = nameError
            )
            return
        }

        // Validate description
        val descError = WorkflowManager.validateWorkflowDescription(description)
        if (descError != null) {
            DebugLogger.w(TAG, "proceedWithSave: Description validation failed: $descError")
            _uiState.value = _uiState.value.copy(
                saveDialogDescriptionError = descError
            )
            return
        }

        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            DebugLogger.w(TAG, "proceedWithSave: Duplicate name detected")
            _uiState.value = _uiState.value.copy(showDuplicateNameDialog = true)
            return
        }

        // Start validation
        _uiState.value = _uiState.value.copy(isSaveValidating = true)
        DebugLogger.d(TAG, "proceedWithSave: Validating nodes on server")

        // Validate nodes exist on server
        saveValidator.fetchAvailableNodes { fetchResult ->
            // NoConnection 在默认实现里同步回调：与拆分前 clientOrNull == null 时同步 return 的时序一致
            if (fetchResult is WorkflowNodeFetchResult.NoConnection) {
                DebugLogger.e(TAG, "proceedWithSave: No connection to server")
                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    saveDialogNameError = context.getString(R.string.error_no_connection)
                )
                return@fetchAvailableNodes
            }

            viewModelScope.launch {
                val availableNodes: Set<String> = when (fetchResult) {
                    is WorkflowNodeFetchResult.Available -> fetchResult.nodes
                    else -> {
                        DebugLogger.e(TAG, "proceedWithSave: Failed to fetch available nodes")
                        _uiState.value = _uiState.value.copy(
                            isSaveValidating = false,
                            saveDialogNameError = context.getString(R.string.error_workflow_fetch_nodes_failed)
                        )
                        return@launch
                    }
                }

                DebugLogger.d(TAG, "proceedWithSave: Server has ${availableNodes.size} node types")

                val workflowClassTypes = graph.nodes
                    .map { it.classType }
                    .toSet()
                val missingNodes = workflowClassTypes.filter { it !in availableNodes }

                if (missingNodes.isNotEmpty()) {
                    DebugLogger.w(TAG, "proceedWithSave: Missing nodes: $missingNodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithSave: All nodes validated, creating field mappings")

                // Create field mapping state from graph
                val mappingState = createFieldMappingStateFromGraph(context, graph, selectedType)

                // Check if all REQUIRED fields can be mapped (optional fields can be unmapped)
                if (!mappingState.allRequiredFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedRequiredFields.map { it.displayName }
                    DebugLogger.w(TAG, "proceedWithSave: Unmapped required fields: $unmappedFieldNames")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                DebugLogger.i(TAG, "proceedWithSave: Validation passed, entering mapping mode")

                // Store pending save data and enter mapping mode
                pendingSaveData = PendingSaveData(
                    name = name,
                    description = description,
                    type = selectedType,
                    graph = graph
                )

                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    showSaveDialog = false,
                    isFieldMappingMode = true,
                    mappingState = mappingState,
                    isEditMode = false,  // Exit edit mode
                    selectedNodeIds = emptySet(),  // Clear selection when entering mapping mode
                    selectedNoteIds = emptySet(),
                    canConfirmMapping = mappingState.allRequiredFieldsMapped,
                    highlightedNodeIds = calculateHighlightedNodes(mappingState)
                )
            }
        }
    }

    /**
     * Proceed with saving an edited existing workflow.
     * Skips save dialog since we already have metadata, uses stored workflow type.
     */
    private fun proceedWithEditExistingSave(context: Context) {
        DebugLogger.i(TAG, "proceedWithEditExistingSave: Starting validation for edit-existing mode")
        val state = _uiState.value
        val graph = graphEditor.currentGraph()
        if (graph == null) {
            DebugLogger.e(TAG, "proceedWithEditExistingSave: No mutable graph available")
            return
        }
        val workflowType = state.editingWorkflowType
        if (workflowType == null) {
            DebugLogger.e(TAG, "proceedWithEditExistingSave: No workflow type available")
            return
        }

        DebugLogger.d(TAG, "proceedWithEditExistingSave: type=$workflowType, nodes=${graph.nodes.size}")

        // Start validation
        _uiState.value = _uiState.value.copy(isSaveValidating = true)

        // Validate nodes exist on server
        saveValidator.fetchAvailableNodes { fetchResult ->
            // NoConnection 在默认实现里同步回调：与拆分前 clientOrNull == null 时同步 return 的时序一致
            if (fetchResult is WorkflowNodeFetchResult.NoConnection) {
                DebugLogger.e(TAG, "proceedWithEditExistingSave: No connection to server")
                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    showMissingNodesDialog = true,
                    missingNodes = listOf("No connection to server")
                )
                return@fetchAvailableNodes
            }

            viewModelScope.launch {
                val availableNodes: Set<String> = when (fetchResult) {
                    is WorkflowNodeFetchResult.Available -> fetchResult.nodes
                    else -> {
                        DebugLogger.e(TAG, "proceedWithEditExistingSave: Failed to fetch available nodes")
                        _uiState.value = _uiState.value.copy(
                            isSaveValidating = false,
                            showMissingNodesDialog = true,
                            missingNodes = listOf("Failed to fetch available nodes")
                        )
                        return@launch
                    }
                }

                DebugLogger.d(TAG, "proceedWithEditExistingSave: Server has ${availableNodes.size} node types")

                val workflowClassTypes = graph.nodes
                    .map { it.classType }
                    .toSet()
                val missingNodes = workflowClassTypes.filter { it !in availableNodes }

                if (missingNodes.isNotEmpty()) {
                    DebugLogger.w(TAG, "proceedWithEditExistingSave: Missing nodes: $missingNodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithEditExistingSave: All nodes validated, creating field mappings")

                // Create field mapping state from graph
                val mappingState = createFieldMappingStateFromGraph(context, graph, workflowType)

                // Check if all REQUIRED fields can be mapped (optional fields can be unmapped)
                if (!mappingState.allRequiredFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedRequiredFields.map { it.displayName }
                    DebugLogger.w(TAG, "proceedWithEditExistingSave: Unmapped required fields: $unmappedFieldNames")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                DebugLogger.i(TAG, "proceedWithEditExistingSave: Validation passed, entering mapping mode")

                // Store pending save data and enter mapping mode
                pendingSaveData = PendingSaveData(
                    name = state.originalWorkflowName,
                    description = state.originalWorkflowDescription,
                    type = workflowType,
                    graph = graph
                )

                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    isFieldMappingMode = true,
                    mappingState = mappingState,
                    isEditMode = false,  // Exit edit mode
                    selectedNodeIds = emptySet(),  // Clear selection when entering mapping mode
                    selectedNoteIds = emptySet(),
                    canConfirmMapping = mappingState.allRequiredFieldsMapped,
                    highlightedNodeIds = calculateHighlightedNodes(mappingState)
                )
            }
        }
    }

    /**
     * Create field mapping state by analyzing graph nodes.
     * Delegates to FieldMappingAnalyzer utility.
     */
    private fun createFieldMappingStateFromGraph(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType
    ): WorkflowMappingState {
        return FieldMappingAnalyzer.createFieldMappingState(context, graph, type, nodeTypeRegistry)
    }

    /**
     * Confirm mapping and save the workflow (for create mode and edit-existing mode)
     */
    fun confirmMappingAndSave(context: Context) {
        DebugLogger.i(TAG, "confirmMappingAndSave: Starting save process")
        val state = _uiState.value
        val pending = pendingSaveData
        if (pending == null) {
            DebugLogger.e(TAG, "confirmMappingAndSave: No pending save data")
            return
        }
        val mappingState = state.mappingState
        if (mappingState == null) {
            DebugLogger.e(TAG, "confirmMappingAndSave: No mapping state")
            return
        }

        DebugLogger.d(TAG, "confirmMappingAndSave: Saving '${pending.name}' as ${pending.type}, editingWorkflowId=${state.editingWorkflowId}")

        // Convert mappings to the format expected by WorkflowManager
        val fieldMappings = mutableMapOf<String, Pair<String, String>>()
        mappingState.fieldMappings.forEach { fieldMapping ->
            val selectedCandidate = fieldMapping.selectedCandidate ?: return@forEach
            fieldMappings[fieldMapping.field.fieldKey] = Pair(
                selectedCandidate.nodeId,
                selectedCandidate.inputKey
            )
            DebugLogger.d(TAG, "confirmMappingAndSave: Field '${fieldMapping.field.fieldKey}' -> node ${selectedCandidate.nodeId}, input '${selectedCandidate.inputKey}'")
        }

        // Serialize graph to JSON (with wrapper to include groups)
        DebugLogger.d(TAG, "confirmMappingAndSave: Serializing graph with ${pending.graph.nodes.size} nodes, ${pending.graph.groups.size} groups")
        val jsonContent = serializer.serializeWithWrapper(pending.graph)
        DebugLogger.d(TAG, "confirmMappingAndSave: JSON length=${jsonContent.length}")

        // Save using WorkflowManager - update if editing existing workflow, create if new
        val result = if (state.editingWorkflowId != null) {
            DebugLogger.i(TAG, "confirmMappingAndSave: Calling WorkflowManager.updateUserWorkflowWithMapping for id=${state.editingWorkflowId}")
            WorkflowManager.updateUserWorkflowWithMapping(
                workflowId = state.editingWorkflowId,
                jsonContent = jsonContent,
                fieldMappings = fieldMappings
            )
        } else {
            DebugLogger.i(TAG, "confirmMappingAndSave: Calling WorkflowManager.addUserWorkflowWithMapping")
            WorkflowManager.addUserWorkflowWithMapping(
                name = pending.name,
                description = pending.description,
                jsonContent = jsonContent,
                type = pending.type,
                fieldMappings = fieldMappings
            )
        }

        if (result.isSuccess) {
            val savedWorkflow = result.getOrThrow()
            DebugLogger.i(TAG, "confirmMappingAndSave: Workflow saved successfully with id=${savedWorkflow.id}")
            val wasCreateMode = state.editingWorkflowId == null
            // 回填当前工作流 ID。创建模式下 currentWorkflowId 此前一直是 null，
            // 导致之后每次关掉节点属性侧栏时 saveNodeAttributeEdits() 都在第一行早退，
            // 用户在新建流程里调过的节点参数从来不会落盘。
            currentWorkflowId = savedWorkflow.id
            if (workflowValuesStorage == null) {
                workflowValuesStorage = WorkflowValuesStorage(context)
            }
            // Return to view mode instead of exiting
            graphEditor.returnToViewMode(savedWorkflow.id, context)
            if (wasCreateMode) {
                // 新建流程在保存前没有 ID，那时的节点属性编辑无法落盘：这里补存一次。
                // （空集合时会走「清空」分支，不会写垃圾数据。）
                graphEditor.saveNodeAttributeEdits()
            }
            // Emit event to notify Activity (triggers list refresh in caller)
            viewModelScope.launch {
                if (state.editingWorkflowId != null) {
                    _events.emit(WorkflowEditorEvent.WorkflowUpdated(savedWorkflow.id))
                } else {
                    _events.emit(WorkflowEditorEvent.WorkflowCreated(savedWorkflow.id))
                }
            }
        } else {
            val error = result.exceptionOrNull()
            val message = error?.message ?: "Save failed"
            DebugLogger.e(TAG, "confirmMappingAndSave: Save failed: $message")
            // 保存失败不能当「取消」：编辑页、画布改动和已确认的映射都要原样留着，
            // 只把失败原因交给界面弹出来（由 Activity 弹错并停留在此页）。
            _uiState.value = _uiState.value.copy(isSaveValidating = false)
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.SaveFailed(message))
            }
        }
    }

    /**
     * 计算映射模式下的高亮节点：经图操作器窄接口调用（拆分前是 ViewModel 内私有函数）。
     */
    private fun calculateHighlightedNodes(mappingState: WorkflowMappingState): Set<String> {
        return graphEditor.calculateHighlightedNodes(mappingState)
    }
}
