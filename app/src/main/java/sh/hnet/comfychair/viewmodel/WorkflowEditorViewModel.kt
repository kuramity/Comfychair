package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.workflow.DiscardAction
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.WorkflowEditorUiState
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.layout.GraphViewportCalculator
import sh.hnet.comfychair.viewmodel.editor.WorkflowEditorGraphContext
import sh.hnet.comfychair.viewmodel.editor.WorkflowGraphEditor
import sh.hnet.comfychair.viewmodel.editor.WorkflowSaveCoordinator

/**
 * Events emitted by the Workflow Editor
 */
sealed class WorkflowEditorEvent {
    data class MappingConfirmed(val mappingsJson: String) : WorkflowEditorEvent()
    object MappingCancelled : WorkflowEditorEvent()
    data class WorkflowCreated(val workflowId: String) : WorkflowEditorEvent()
    data class WorkflowUpdated(val workflowId: String) : WorkflowEditorEvent()
    object CreateCancelled : WorkflowEditorEvent()

    /**
     * 保存失败（磁盘写入、重名、未知异常……）。
     *
     * 与 [CreateCancelled] 严格区分：取消是「用户主动放弃」，调用方可以安全地关页面；
     * 保存失败时用户填的名字、映射、画布上的改动**都还在**，必须留在编辑页并弹错，
     * 让人能改完再存一次。曾经这里误发 CreateCancelled，等于保存失败即丢掉全部工作。
     */
    data class SaveFailed(val message: String) : WorkflowEditorEvent()
}

/**
 * ViewModel for the Workflow Editor screen
 *
 * B4.2-C 三类拆分后的转发壳（照 ConnectionManager→ModelListCatalog 的转发 API 先例）：
 * - 图操作逻辑（初始化/加载、映射交互、节点属性编辑、笔记、编辑模式还原、选择组删改、
 *   布局重排、增节点、连线、导出、拖拽）迁入 [WorkflowGraphEditor]；
 * - 保存域逻辑（未保存检查、对话框族、两条保存主链、落盘与事件发射）迁入
 *   [WorkflowSaveCoordinator]；
 * - 本类保留：无参构造（既有单测无参 new，签名不许变）、`_uiState`/`_events` 声明、
 *   视口（缩放/平移/适配）逻辑、共享字段接缝实现，并把全部公共方法保留为转发 API。
 * 无 DI 框架，两个协作者在构造时手动装配、共享同一个流实例。
 */
class WorkflowEditorViewModel : ViewModel(), WorkflowEditorGraphContext {

    companion object {
        private const val TAG = "WorkflowEditor"
    }

    private val _uiState = MutableStateFlow(WorkflowEditorUiState())
    val uiState: StateFlow<WorkflowEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WorkflowEditorEvent>()
    val events: SharedFlow<WorkflowEditorEvent> = _events.asSharedFlow()

    // ===== 共享字段接缝（WorkflowEditorGraphContext）：两个协作者经此访问画布/工作流上下文 =====
    override var canvasWidth: Float = 0f
    override var canvasHeight: Float = 0f
    override var currentWorkflowId: String? = null
    override var workflowValuesStorage: WorkflowValuesStorage? = null

    // Use shared NodeTypeRegistry from ConnectionManager (populated on connection)
    override val nodeTypeRegistry: NodeTypeRegistry
        get() = ConnectionManager.nodeTypeRegistry

    // Fit mode for reset zoom button toggle
    private enum class FitMode { FIT_ALL, FIT_WIDTH, FIT_HEIGHT }
    private var lastFitMode: FitMode = FitMode.FIT_ALL

    // ===== 协作者手动装配（无 DI 框架）：共享同一组流实例与接缝 =====
    private val graphEditor = WorkflowGraphEditor(_uiState, _events, this, viewModelScope)
    private val saveCoordinator =
        WorkflowSaveCoordinator(_uiState, _events, this, graphEditor, viewModelScope)

    init {
        // 装配后回填双向桥：图操作器经保存协调器接口清空暂存保存数据/探测注册表
        graphEditor.attachSaveCoordinator(saveCoordinator)
    }

    // ===========================================
    // 视口（缩放/平移/适配）：拆分后仍留在 ViewModel
    // ===========================================

    /**
     * Set the canvas size for proper centering calculations
     */
    fun setCanvasSize(width: Float, height: Float) {
        if (canvasWidth != width || canvasHeight != height) {
            canvasWidth = width
            canvasHeight = height
            // Auto-fit on first load
            if (_uiState.value.scale == 1f && _uiState.value.offset == Offset.Zero) {
                fitToScreen()
            }
        }
    }

    /**
     * Update scale and offset from pan/zoom gestures
     */
    fun onTransform(scale: Float, offset: Offset) {
        _uiState.value = _uiState.value.copy(
            scale = scale.coerceIn(0.2f, 3f),
            offset = offset
        )
    }

    /**
     * Set the zoom scale
     */
    fun setScale(scale: Float) {
        _uiState.value = _uiState.value.copy(
            scale = scale.coerceIn(0.2f, 3f)
        )
    }

    /**
     * Set the offset (pan position)
     */
    fun setOffset(offset: Offset) {
        _uiState.value = _uiState.value.copy(offset = offset)
    }

    /**
     * Zoom in by 20%, centered on the canvas center
     */
    fun zoomIn() {
        zoomTowardCenter(1.2f)
    }

    /**
     * Zoom out by 20%, centered on the canvas center
     */
    fun zoomOut() {
        zoomTowardCenter(1f / 1.2f)
    }

    /**
     * Set zoom to exactly 100%, centered on the canvas center
     */
    fun setZoom100() {
        val currentScale = _uiState.value.scale
        if (currentScale != 1f) {
            zoomTowardCenter(1f / currentScale)
        }
    }

    /**
     * Zoom by a factor while keeping the canvas center stationary
     */
    private fun zoomTowardCenter(zoomFactor: Float) {
        val center = Offset(canvasWidth / 2, canvasHeight / 2)
        val transform = GraphViewportCalculator.zoomTowardPoint(
            focusPoint = center,
            currentScale = _uiState.value.scale,
            currentOffset = _uiState.value.offset,
            zoomFactor = zoomFactor
        )
        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
    }

    /**
     * Fit the entire graph to the screen (both width and height)
     */
    private fun fitToScreen() {
        val transform = GraphViewportCalculator.fitToScreen(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_ALL
    }

    /**
     * Fit the graph width to the screen, showing the top of the graph
     */
    private fun fitToWidth() {
        val transform = GraphViewportCalculator.fitToWidth(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_WIDTH
    }

    /**
     * Fit the graph height to the screen, showing the left side of the graph
     */
    private fun fitToHeight() {
        val transform = GraphViewportCalculator.fitToHeight(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_HEIGHT
    }

    /**
     * Toggle fit mode - switches between fit to height and fit to width.
     * Initial view is "fit all", then toggles between height and width.
     */
    fun resetView() {
        when (lastFitMode) {
            FitMode.FIT_ALL -> fitToHeight()
            FitMode.FIT_HEIGHT -> fitToWidth()
            FitMode.FIT_WIDTH -> fitToHeight()
        }
    }

    // ===========================================
    // 初始化/加载（转发 → 图操作器）
    // ===========================================

    /**
     * Initialize the editor with a workflow ID (view mode)
     */
    fun initialize(context: Context, workflowId: String?, workflowJson: String?) {
        graphEditor.initialize(context, workflowId, workflowJson)
    }

    /**
     * Initialize the editor for field mapping mode (new workflow upload)
     */
    fun initializeForMapping(
        jsonContent: String,
        name: String,
        description: String,
        mappingState: WorkflowMappingState
    ) {
        graphEditor.initializeForMapping(jsonContent, name, description, mappingState)
    }

    /**
     * Initialize the editor for creating a new workflow from scratch.
     * Creates an empty mutable graph and enters edit mode automatically.
     */
    fun initializeForCreation(context: Context) {
        graphEditor.initializeForCreation(context)
    }

    /**
     * Initialize the editor for editing an existing user workflow's structure.
     * Loads the workflow, enters edit mode, but preserves existing metadata.
     * Save flow skips the save dialog and updates the existing file.
     */
    fun initializeForEditingExisting(context: Context, workflowId: String) {
        graphEditor.initializeForEditingExisting(context, workflowId)
    }

    // ===========================================
    // 映射交互（转发 → 图操作器）
    // ===========================================

    /**
     * Handle node tap in field mapping mode
     */
    fun onNodeTapped(nodeId: String) {
        graphEditor.onNodeTapped(nodeId)
    }

    /**
     * Update the selected candidate for a field.
     * If the selected node was previously mapped to another field, clears that field's mapping.
     */
    fun updateFieldMapping(fieldKey: String, candidateIndex: Int) {
        graphEditor.updateFieldMapping(fieldKey, candidateIndex)
    }

    /**
     * Clear the mapping for a single field (sets selectedCandidateIndex to -1).
     */
    fun clearFieldMapping(fieldKey: String) {
        graphEditor.clearFieldMapping(fieldKey)
    }

    /**
     * Clear all field mappings.
     */
    fun clearAllMappings() {
        graphEditor.clearAllMappings()
    }

    /**
     * Select a field to highlight its candidates
     */
    fun selectFieldForMapping(fieldKey: String?) {
        graphEditor.selectFieldForMapping(fieldKey)
    }

    /**
     * Get the final field mappings as JSON for returning via Intent
     * Only includes fields that are actually mapped (required + optionally mapped fields)
     */
    fun getFinalMappingsJson(): String? {
        return graphEditor.getFinalMappingsJson()
    }

    /**
     * Confirm the mapping and emit event (for upload flow - returns mappings to calling activity)
     */
    fun confirmMapping() {
        graphEditor.confirmMapping()
    }

    /**
     * Cancel mapping and emit event
     */
    fun cancelMapping() {
        graphEditor.cancelMapping()
    }

    // ===========================================
    // 保存域（转发 → 保存协调器）
    // ===========================================

    /**
     * Check if there are unsaved changes that would be lost
     */
    fun hasUnsavedWork(): Boolean {
        return saveCoordinator.hasUnsavedWork()
    }

    /**
     * Show discard confirmation dialog with specified action
     */
    fun showDiscardConfirmation(action: DiscardAction = DiscardAction.EXIT_EDIT_MODE) {
        saveCoordinator.showDiscardConfirmation(action)
    }

    /**
     * Dismiss discard confirmation dialog
     */
    fun dismissDiscardConfirmation() {
        saveCoordinator.dismissDiscardConfirmation()
    }

    /**
     * Confirm discard - action depends on discardAction state
     */
    fun confirmDiscard() {
        saveCoordinator.confirmDiscard()
    }

    /**
     * Handle exit edit mode button - show confirmation if unsaved changes exist,
     * otherwise just exit edit mode. This returns to view mode but stays in editor.
     */
    fun handleExitEditModeWithConfirmation() {
        saveCoordinator.handleExitEditModeWithConfirmation()
    }

    /**
     * Handle close button in create mode - show confirmation if work exists,
     * then close the editor.
     */
    fun handleCreateModeClose() {
        saveCoordinator.handleCreateModeClose()
    }

    /**
     * Handle close button in edit-existing mode.
     * Shows discard confirmation if there are unsaved changes, then closes editor.
     */
    fun handleEditExistingModeClose() {
        saveCoordinator.handleEditExistingModeClose()
    }

    /**
     * Show save dialog when Done is pressed in create mode.
     * For edit-existing mode, skips the dialog and proceeds directly to validation.
     * Auto-detects workflow type from graph nodes.
     */
    fun showSaveDialog(context: Context) {
        saveCoordinator.showSaveDialog(context)
    }

    /**
     * Cancel save dialog
     */
    fun cancelSaveDialog() {
        saveCoordinator.cancelSaveDialog()
    }

    /**
     * Update save dialog type selection
     */
    fun onSaveDialogTypeSelected(type: WorkflowType) {
        saveCoordinator.onSaveDialogTypeSelected(type)
    }

    /**
     * Toggle save dialog type dropdown
     */
    fun onSaveDialogToggleTypeDropdown() {
        saveCoordinator.onSaveDialogToggleTypeDropdown()
    }

    /**
     * Update save dialog name
     */
    fun onSaveDialogNameChange(name: String) {
        saveCoordinator.onSaveDialogNameChange(name)
    }

    /**
     * Update save dialog description
     */
    fun onSaveDialogDescriptionChange(description: String) {
        saveCoordinator.onSaveDialogDescriptionChange(description)
    }

    /**
     * Dismiss duplicate name dialog
     */
    fun dismissDuplicateNameDialog() {
        saveCoordinator.dismissDuplicateNameDialog()
    }

    /**
     * Dismiss missing nodes dialog
     */
    fun dismissMissingNodesDialog() {
        saveCoordinator.dismissMissingNodesDialog()
    }

    /**
     * Dismiss missing fields dialog
     */
    fun dismissMissingFieldsDialog() {
        saveCoordinator.dismissMissingFieldsDialog()
    }

    /**
     * Proceed with save - validates and enters mapping mode.
     */
    fun proceedWithSave(context: Context) {
        saveCoordinator.proceedWithSave(context)
    }

    /**
     * Confirm mapping and save the workflow (for create mode and edit-existing mode)
     */
    fun confirmMappingAndSave(context: Context) {
        saveCoordinator.confirmMappingAndSave(context)
    }

    // ===========================================
    // 节点属性编辑（转发 → 图操作器）
    // ===========================================

    /**
     * Handle node tap for editing (view mode only, not mapping mode).
     */
    fun onNodeTappedForEditing(node: WorkflowNode) {
        graphEditor.onNodeTappedForEditing(node)
    }

    /**
     * Dismiss the node editor and restore previous view.
     */
    fun dismissNodeEditor() {
        graphEditor.dismissNodeEditor()
    }

    /**
     * Update a node attribute value.
     */
    fun updateNodeAttribute(nodeId: String, inputName: String, value: Any) {
        graphEditor.updateNodeAttribute(nodeId, inputName, value)
    }

    /**
     * Reset a node attribute to its default value.
     */
    fun resetNodeAttribute(nodeId: String, inputName: String) {
        graphEditor.resetNodeAttribute(nodeId, inputName)
    }

    /**
     * Rename a node (change its title/display name).
     */
    fun renameNode(nodeId: String, newTitle: String) {
        graphEditor.renameNode(nodeId, newTitle)
    }

    /**
     * Rename a group (change its title).
     */
    fun renameGroup(groupId: Int, newTitle: String) {
        graphEditor.renameGroup(groupId, newTitle)
    }

    // ========== Note Operations ==========

    /**
     * Add a new note to the workflow.
     */
    fun addNote() {
        graphEditor.addNote()
    }

    /**
     * Add a new note in manual placement mode, positioned at the view center.
     * Calculates view center from stored canvas dimensions and current transform.
     */
    fun addNoteManual() {
        graphEditor.addNoteManual()
    }

    /**
     * Rename a note (change its title).
     */
    fun renameNote(noteId: Int, newTitle: String) {
        graphEditor.renameNote(noteId, newTitle)
    }

    /**
     * Update a note's content.
     */
    fun updateNoteContent(noteId: Int, newContent: String) {
        graphEditor.updateNoteContent(noteId, newContent)
    }

    /**
     * Delete a note by ID.
     */
    fun deleteNote(noteId: Int) {
        graphEditor.deleteNote(noteId)
    }

    /**
     * Get a note by ID.
     */
    fun getNote(noteId: Int): sh.hnet.comfychair.workflow.WorkflowNote? {
        return graphEditor.getNote(noteId)
    }

    /**
     * Toggle the bypass state of a node.
     * Bypassed nodes (mode=4) are skipped during execution but connections pass through.
     */
    fun toggleNodeBypass(nodeId: String, bypassed: Boolean) {
        graphEditor.toggleNodeBypass(nodeId, bypassed)
    }

    /**
     * Get edits for a specific node.
     */
    fun getEditsForNode(nodeId: String): Map<String, Any> {
        return graphEditor.getEditsForNode(nodeId)
    }

    // ===========================================
    // 图编辑模式（转发 → 图操作器）
    // ===========================================

    /**
     * Enter edit mode - creates a mutable copy of the graph for editing.
     * Saves the original graph and bounds for restoration on discard.
     */
    fun enterEditMode() {
        graphEditor.enterEditMode()
    }

    /**
     * Exit edit mode - discards unsaved changes and restores original graph.
     */
    fun exitEditMode() {
        graphEditor.exitEditMode()
    }

    /**
     * Toggle node selection.
     */
    fun toggleNodeSelection(nodeId: String) {
        graphEditor.toggleNodeSelection(nodeId)
    }

    /**
     * Select a single node (replaces current selection).
     */
    fun selectNode(nodeId: String) {
        graphEditor.selectNode(nodeId)
    }

    /**
     * Clear node and note selection.
     */
    fun clearSelection() {
        graphEditor.clearSelection()
    }

    /**
     * Toggle note selection.
     */
    fun toggleNoteSelection(noteId: Int) {
        graphEditor.toggleNoteSelection(noteId)
    }

    /**
     * Delete selected nodes and notes with their connected edges.
     */
    fun deleteSelectedNodes() {
        graphEditor.deleteSelectedNodes()
    }

    /**
     * Duplicate selected nodes with offset position.
     * Internal edges between selected nodes are also duplicated.
     * Triggers auto-layout to prevent node overlaps.
     */
    fun duplicateSelectedNodes() {
        graphEditor.duplicateSelectedNodes()
    }

    /**
     * Create a group from the currently selected nodes and/or notes.
     * Requires at least 1 item (node or note) to be selected.
     */
    fun createGroupFromSelection() {
        graphEditor.createGroupFromSelection()
    }

    /**
     * Check if a node is inside a group (for enabling/disabling ungroup action).
     * Uses explicit memberNodeIds if available, otherwise falls back to position detection.
     */
    fun isSelectedNodeInGroup(): Boolean {
        return graphEditor.isSelectedNodeInGroup()
    }

    /**
     * Remove selected nodes from the group they belong to.
     * If the group has fewer than 2 members remaining, the group is dissolved entirely.
     */
    fun ungroupSelectedNode() {
        graphEditor.ungroupSelectedNode()
    }

    /**
     * Re-layout the entire graph. Call this after structural changes (add/delete nodes)
     * or when note heights change to recalculate positions.
     */
    fun relayoutGraph() {
        graphEditor.relayoutGraph()
    }

    // ========== Manual Placement Mode ==========

    /**
     * Toggle manual placement mode for this workflow.
     * If currently enabled, shows confirmation dialog before disabling.
     * If currently disabled, enables immediately.
     */
    fun toggleManualPlacementMode() {
        graphEditor.toggleManualPlacementMode()
    }

    /**
     * Confirm disabling manual placement mode after user confirms in dialog.
     * Clears all manual positions and runs auto-layout.
     */
    fun confirmDisableManualPlacement() {
        graphEditor.confirmDisableManualPlacement()
    }

    /**
     * Cancel disabling manual placement mode (dismiss dialog).
     */
    fun cancelDisableManualPlacement() {
        graphEditor.cancelDisableManualPlacement()
    }

    /**
     * Show the node browser for adding new nodes.
     */
    fun showNodeBrowser(insertPosition: Offset) {
        graphEditor.showNodeBrowser(insertPosition)
    }

    /**
     * Hide the node browser.
     */
    fun hideNodeBrowser() {
        graphEditor.hideNodeBrowser()
    }

    /**
     * Check if the node type registry has node definitions available.
     */
    fun hasNodeDefinitions(): Boolean {
        return graphEditor.hasNodeDefinitions()
    }

    /**
     * Get all available node types from the registry.
     */
    fun getAvailableNodeTypes(): List<NodeTypeDefinition> {
        return graphEditor.getAvailableNodeTypes()
    }

    /**
     * Get node types grouped by category.
     */
    fun getNodeTypesByCategory(): Map<String, List<NodeTypeDefinition>> {
        return graphEditor.getNodeTypesByCategory()
    }

    /**
     * Add a new node from a NodeTypeDefinition at the current insert position.
     */
    fun addNode(nodeType: NodeTypeDefinition) {
        graphEditor.addNode(nodeType)
    }

    // ===========================================
    // 连线（转发 → 图操作器）
    // ===========================================

    /**
     * Enter connection mode when an output slot is tapped.
     * Calculates valid input slots and highlights them.
     */
    fun enterConnectionMode(outputSlot: SlotPosition) {
        graphEditor.enterConnectionMode(outputSlot)
    }

    /**
     * Enter reverse connection mode when an input slot is tapped.
     * Calculates valid output slots and highlights them.
     */
    fun enterReverseConnectionMode(inputSlot: SlotPosition) {
        graphEditor.enterReverseConnectionMode(inputSlot)
    }

    /**
     * Exit connection mode without making a connection.
     */
    fun exitConnectionMode() {
        graphEditor.exitConnectionMode()
    }

    /**
     * Connect to a target slot when tapped in connection mode.
     * Handles both directions:
     * - OUTPUT_TO_INPUT: targetSlot is an input, sourceSlot is an output
     * - INPUT_TO_OUTPUT: targetSlot is an output, sourceSlot is an input
     */
    fun connectToTarget(targetSlot: SlotPosition) {
        graphEditor.connectToTarget(targetSlot)
    }

    /**
     * Start long-press connection mode when an output slot is long-pressed.
     * Opens the filtered node browser showing only compatible nodes.
     */
    fun startLongPressConnection(sourceSlot: SlotPosition) {
        graphEditor.startLongPressConnection(sourceSlot)
    }

    /**
     * Cancel long-press connection mode.
     */
    fun cancelLongPressConnection() {
        graphEditor.cancelLongPressConnection()
    }

    /**
     * Handle node selection from the filtered browser.
     * If multiple compatible inputs exist, shows input selection dialog.
     * If single compatible input, completes connection immediately.
     */
    fun selectNodeForConnection(nodeType: NodeTypeDefinition) {
        graphEditor.selectNodeForConnection(nodeType)
    }

    /**
     * Handle input selection from the dialog.
     */
    fun selectInputForConnection(input: InputDefinition) {
        graphEditor.selectInputForConnection(input)
    }

    /**
     * Handle node selection from the filtered browser for reverse connection.
     * When user long-pressed an INPUT slot and selected a node to add.
     * If multiple compatible outputs exist, shows output selection dialog.
     * If single compatible output, completes connection immediately.
     */
    fun selectNodeForReverseConnection(nodeType: NodeTypeDefinition) {
        graphEditor.selectNodeForReverseConnection(nodeType)
    }

    /**
     * Handle output selection from the dialog.
     */
    fun selectOutputForConnection(output: OutputSlot) {
        graphEditor.selectOutputForConnection(output)
    }

    /**
     * Delete a connection by removing the edge to a specific input.
     */
    fun deleteConnection(nodeId: String, inputName: String) {
        graphEditor.deleteConnection(nodeId, inputName)
    }

    // ===========================================
    // 导出（转发 → 图操作器）
    // ===========================================

    /**
     * Export the current workflow to JSON string.
     * Applies any pending edits before serialization.
     */
    fun exportToJson(): String? {
        return graphEditor.exportToJson()
    }

    /**
     * Export the workflow with wrapper metadata (name, description).
     */
    fun exportToJsonWithWrapper(): String? {
        return graphEditor.exportToJsonWithWrapper()
    }

    /**
     * Copy the workflow JSON to clipboard.
     */
    fun copyToClipboard(context: Context): Boolean {
        return graphEditor.copyToClipboard(context)
    }

    // ===========================================
    // 拖拽（转发 → 图操作器）
    // ===========================================

    /**
     * Start dragging a node for manual repositioning.
     */
    fun startNodeDrag(nodeId: String) {
        graphEditor.startNodeDrag(nodeId)
    }

    /**
     * Update the drag offset while dragging a node or note.
     * Works for both node and note drags since they share the same dragOffset.
     */
    fun updateNodeDrag(delta: Offset) {
        graphEditor.updateNodeDrag(delta)
    }

    /**
     * End dragging and snap the node to the grid.
     */
    fun endNodeDrag() {
        graphEditor.endNodeDrag()
    }

    /**
     * Cancel dragging without saving changes.
     */
    fun cancelNodeDrag() {
        graphEditor.cancelNodeDrag()
    }

    /**
     * Clear the just-dropped node ID after the canvas has rendered it.
     */
    fun clearJustDroppedNode() {
        graphEditor.clearJustDroppedNode()
    }

    /**
     * Start dragging a note for manual repositioning.
     */
    fun startNoteDrag(noteId: Int) {
        graphEditor.startNoteDrag(noteId)
    }

    /**
     * Update the drag offset while dragging a note.
     * Uses the same dragOffset as node drag since only one element can be dragged at a time.
     */
    fun updateNoteDrag(delta: Offset) {
        graphEditor.updateNoteDrag(delta)
    }

    /**
     * End dragging a note and snap to the grid.
     */
    fun endNoteDrag() {
        graphEditor.endNoteDrag()
    }

    /**
     * Cancel dragging a note without saving changes.
     */
    fun cancelNoteDrag() {
        graphEditor.cancelNoteDrag()
    }

    /**
     * Clear the just-dropped note ID after the canvas has rendered it.
     */
    fun clearJustDroppedNote() {
        graphEditor.clearJustDroppedNote()
    }

    /**
     * Add a new node in manual placement mode, positioned at the view center.
     * Calculates view center from stored canvas dimensions and current transform.
     */
    fun addNodeManual(nodeType: NodeTypeDefinition) {
        graphEditor.addNodeManual(nodeType)
    }
}
