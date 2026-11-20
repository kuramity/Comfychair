package sh.hnet.comfychair.workflow.mapping

import android.content.Context
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode

/**
 * Analyzes workflow graphs to detect field mapping candidates.
 *
 * Provides pure functions for creating field mappings, finding candidates,
 * and tracing connections in workflow graphs.
 */
object FieldMappingAnalyzer {

    /**
     * Create field mapping state by analyzing graph nodes.
     *
     * @param context Context for string resources
     * @param graph The workflow graph to analyze
     * @param type The workflow type
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return Complete mapping state for the workflow
     */
    fun createFieldMappingState(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType,
        nodeTypeRegistry: NodeTypeRegistry
    ): WorkflowMappingState {
        // Get strictly required keys for this workflow type
        val strictlyRequiredKeys = TemplateKeyRegistry.getRequiredKeysForType(type)

        // Get optional keys, adjusted for workflow structure (DualCLIPLoader, BasicGuider)
        val optionalKeys = getOptionalKeysFromGraph(type, graph)

        // 字段顺序：必填在前、可选在后（与改动前一致，UI 上的排列不变）
        val orderedKeys = strictlyRequiredKeys.toList() + optionalKeys.filterNot { it in strictlyRequiredKeys }

        // Pre-compute prompt field mappings using graph tracing
        val promptMappings = createPromptFieldMappings(graph, nodeTypeRegistry)

        // 先给所有字段备好候选，再统一选：选的时候要跨字段去重（同一个输入键只能归一个字段），
        // 所以不能一个字段一个字段地独立决定。规则见 [autoSelectIndices]。
        val candidatesByField = orderedKeys.associateWith { fieldKey ->
            findCandidatesForField(fieldKey, graph, promptMappings, nodeTypeRegistry)
        }
        val selection = autoSelectIndices(orderedKeys, candidatesByField)

        val fieldMappings = orderedKeys.map { fieldKey ->
            FieldMappingState(
                field = FieldDisplayRegistry.createRequiredField(
                    context, fieldKey, isRequired = fieldKey in strictlyRequiredKeys
                ),
                candidates = candidatesByField[fieldKey].orEmpty(),
                selectedCandidateIndex = selection[fieldKey] ?: -1
            )
        }

        return WorkflowMappingState(
            workflowType = type,
            fieldMappings = fieldMappings
        )
    }

    /**
     * Get optional keys for a workflow type, adjusted for graph structure.
     * Handles BasicGuider (no CFG/negative).
     *
     * @param type The workflow type
     * @param graph The workflow graph
     * @return Set of optional field keys
     */
    fun getOptionalKeysFromGraph(type: WorkflowType, graph: WorkflowGraph): Set<String> {
        val baseKeys = TemplateKeyRegistry.getOptionalKeysForType(type).toMutableSet()

        if (type == WorkflowType.TTI) {
            // Check for BasicGuider (no CFG, no negative prompt)
            val hasBasicGuider = graph.nodes.any { it.classType == "BasicGuider" }
            if (hasBasicGuider) {
                baseKeys.remove("cfg")
                baseKeys.remove("negative_text")
            }
        }

        return baseKeys
    }

    /**
     * Find candidates for a field in the graph.
     * Uses two detection strategies:
     * 1. Input-key-based: Look for nodes with matching input key names (fast path)
     * 2. Output-type-based: For "image" field, look for nodes with IMAGE output + ENUM inputs (fallback)
     *
     * @param fieldKey The field key to find candidates for
     * @param graph The workflow graph
     * @param promptMappings Pre-computed prompt field mappings
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return List of candidates matching the field
     */
    fun findCandidatesForField(
        fieldKey: String,
        graph: WorkflowGraph,
        promptMappings: Map<String, List<FieldCandidate>>,
        nodeTypeRegistry: NodeTypeRegistry
    ): List<FieldCandidate> {
        // Handle positive_text and negative_text with graph tracing
        if (fieldKey == "positive_text" || fieldKey == "negative_text") {
            return promptMappings[fieldKey] ?: emptyList()
        }

        // Strategy 1: Find nodes that have matching input key names
        val directCandidates = mutableListOf<FieldCandidate>()

        for (node in graph.nodes) {
            node.inputs.forEach { (inputName, inputValue) ->
                if (TemplateKeyRegistry.doesInputKeyMatchField(fieldKey, inputName)) {
                    val currentValue = when (inputValue) {
                        is InputValue.Literal -> inputValue.value
                        else -> null
                    }

                    if (TemplateKeyRegistry.doesValueMatchPlaceholder(fieldKey, currentValue)) {
                        directCandidates.add(
                            FieldCandidate(
                                nodeId = node.id,
                                nodeName = node.title,
                                classType = node.classType,
                                inputKey = inputName,
                                currentValue = currentValue
                            )
                        )
                    }
                }
            }
        }

        // Strategy 2 (fallback): For "image" field, find nodes with IMAGE output + ENUM inputs
        // This works regardless of input key language (Chinese, etc.)
        if (directCandidates.isEmpty() && fieldKey == "image") {
            for (node in graph.nodes) {
                if (node.outputs.any { it.type == "IMAGE" }) {
                    val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
                    definition?.inputs
                        ?.filter { it.type == "ENUM" && node.inputs.containsKey(it.name) }
                        ?.forEach { inputDef ->
                            val inputValue = node.inputs[inputDef.name]
                            val currentValue = when (inputValue) {
                                is InputValue.Literal -> inputValue.value
                                else -> null
                            }
                            directCandidates.add(
                                FieldCandidate(
                                    nodeId = node.id,
                                    nodeName = node.title,
                                    classType = node.classType,
                                    inputKey = inputDef.name,
                                    currentValue = currentValue
                                )
                            )
                        }
                }
            }
        }

        return directCandidates
    }

    /**
     * Create field mappings for positive_text and negative_text using graph tracing.
     * Uses two detection strategies:
     * 1. Input-key-based: Look for nodes with "text" or "prompt" inputs (fast path)
     * 2. Output-type-based: Look for nodes with CONDITIONING output + STRING inputs (fallback for custom nodes)
     *
     * @param graph The workflow graph
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return Map of field keys to their candidates
     */
    fun createPromptFieldMappings(
        graph: WorkflowGraph,
        nodeTypeRegistry: NodeTypeRegistry
    ): Map<String, List<FieldCandidate>> {
        val positiveTextCandidates = mutableListOf<FieldCandidate>()
        val negativeTextCandidates = mutableListOf<FieldCandidate>()

        // Text input keys to look for (common English names)
        val textInputKeys = listOf("text", "prompt")

        // Strategy 1: Find nodes with known text/prompt input keys
        data class TextEncoderNode(val node: WorkflowNode, val inputKey: String)
        val textEncoderNodes = graph.nodes.mapNotNull { node ->
            val matchingInputKey = textInputKeys.firstOrNull { node.inputs.containsKey(it) }
            if (matchingInputKey != null) TextEncoderNode(node, matchingInputKey) else null
        }

        // Strategy 2 (fallback): Find nodes with CONDITIONING output + STRING inputs
        // This works regardless of input key language (Chinese, etc.)
        val outputBasedNodes = if (textEncoderNodes.isEmpty()) {
            graph.nodes.flatMap { node ->
                if (node.outputs.any { it.type == "CONDITIONING" }) {
                    val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
                    definition?.inputs
                        ?.filter { it.type == "STRING" && node.inputs.containsKey(it.name) }
                        ?.map { inputDef -> TextEncoderNode(node, inputDef.name) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }

        // Combine both strategies
        val allTextEncoderNodes = textEncoderNodes + outputBasedNodes

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((node, inputKey) in allTextEncoderNodes) {
            val textInput = node.inputs[inputKey]
            val currentValue = when (textInput) {
                is InputValue.Literal -> textInput.value
                else -> null
            }

            val candidate = FieldCandidate(
                nodeId = node.id,
                nodeName = node.title,
                classType = node.classType,
                inputKey = inputKey,
                currentValue = currentValue
            )

            // Determine if this node connects to positive or negative input
            val connectionType = traceConditioningConnection(graph, node.id)

            when (connectionType) {
                "positive" -> positiveTextCandidates.add(0, candidate) // Add traced match first
                "negative" -> negativeTextCandidates.add(0, candidate) // Add traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = node.title.lowercase()
                    when {
                        titleLower.contains("positive") -> positiveTextCandidates.add(candidate)
                        titleLower.contains("negative") -> negativeTextCandidates.add(candidate)
                        else -> {
                            // Add to both as fallback candidates
                            positiveTextCandidates.add(candidate)
                            negativeTextCandidates.add(candidate)
                        }
                    }
                }
            }
        }

        return mapOf(
            "positive_text" to positiveTextCandidates,
            "negative_text" to negativeTextCandidates
        )
    }

    /** 该字段对应的占位符字面量，如 `{{image_filename}}` */
    private fun placeholderPattern(fieldKey: String): String =
        "{{${TemplateKeyRegistry.getPlaceholderForKey(fieldKey)}}}"

    /**
     * 统一决定每个字段自动选中的候选下标（-1 = 不映射）。**分两遍**：
     *
     * **第一遍 · 占位符精确匹配（独占）**：值恰好等于该字段占位符 `{{...}}` 的候选。
     * 已经映射好的工作流走这条 —— 每个字段都选中自己占位符原来所在的那个节点，
     * 原样保存回去就什么都不会挪动。这是「打开编辑器 → 直接保存」不改动任何已有连线的保证。
     *
     * **第二遍 · 其余字段退化成「第一个未被占用的字面量候选」**：工作流里还没有这个字段的占位符时
     * （新建、首次导入、或提示词还硬编码在节点里）给它一个默认选中项。两类候选必须跳过：
     *
     * - **连线**（`currentValue == null`）：往连线里写占位符就是拿它顶掉一条既有连线。
     *   实测把 `{{reference_image_2}}` 写进了 `ImageResizeKJv2.image`，覆盖了 `["282",0]`。
     * - **已被别的字段占用的 (节点, 输入键)**：同一个输入只可能承载一个占位符。
     *   `reference_image_1` / `reference_image_2` 共用 jsonKey `image`、候选集完全相同，
     *   没有这条去重，`reference_image_2` 就会把 `reference_image_1` 从 LoadImage 上挤掉
     *   （或反过来）。两遍的顺序也保证了精确匹配不会被子段抢走。
     *
     * 第一遍先跑完才跑第二遍：否则一个没有占位符的字段可能把另一个字段"已精确匹配"的输入抢走。
     */
    internal fun autoSelectIndices(
        fieldKeysInOrder: List<String>,
        candidatesByField: Map<String, List<FieldCandidate>>
    ): Map<String, Int> {
        val selection = mutableMapOf<String, Int>()
        val claimed = mutableSetOf<Pair<String, String>>()

        // 第一遍：占位符精确匹配
        for (fieldKey in fieldKeysInOrder) {
            val candidates = candidatesByField[fieldKey] ?: continue
            val pattern = placeholderPattern(fieldKey)
            val index = candidates.indexOfFirst { it.currentValue?.toString() == pattern }
            if (index >= 0) {
                selection[fieldKey] = index
                claimed += candidates[index].nodeId to candidates[index].inputKey
            }
        }

        // 第二遍：其余字段取第一个未被占用的字面量候选
        for (fieldKey in fieldKeysInOrder) {
            if (selection.containsKey(fieldKey)) continue
            val candidates = candidatesByField[fieldKey] ?: continue
            val index = candidates.indexOfFirst {
                it.currentValue != null && (it.nodeId to it.inputKey) !in claimed
            }
            selection[fieldKey] = index
            if (index >= 0) {
                claimed += candidates[index].nodeId to candidates[index].inputKey
            }
        }

        return selection
    }

    /**
     * 单字段版本，语义与 [autoSelectIndices] 一致（精确匹配优先，否则第一个未占用的字面量候选）。
     * 供只需要一个字段的场景使用；批量为准的调用请用 [autoSelectIndices]，因为它还负责跨字段去重。
     *
     * @param claimed 已被别的字段占用的 (节点, 输入键)
     */
    internal fun autoSelectIndex(
        fieldKey: String,
        candidates: List<FieldCandidate>,
        claimed: Set<Pair<String, String>> = emptySet()
    ): Int {
        if (candidates.isEmpty()) return -1
        val pattern = placeholderPattern(fieldKey)
        val exact = candidates.indexOfFirst { it.currentValue?.toString() == pattern }
        if (exact >= 0) return exact
        return candidates.indexOfFirst {
            it.currentValue != null && (it.nodeId to it.inputKey) !in claimed
        }
    }

    /**
     * Trace a conditioning node's output to find what type of connection it has.
     * Uses WorkflowGraph.edges to trace connections.
     *
     * This is classType-agnostic: it simply checks if the target input name
     * indicates positive or negative conditioning, regardless of the target node type.
     *
     * @param graph The workflow graph
     * @param conditioningNodeId ID of the conditioning node to trace from
     * @return "positive", "negative", or null if no connection found
     */
    fun traceConditioningConnection(graph: WorkflowGraph, conditioningNodeId: String): String? {
        // Find edges originating from this conditioning node
        val outgoingEdges = graph.edges.filter { it.sourceNodeId == conditioningNodeId }

        for (edge in outgoingEdges) {
            // Check target input name - works for any node type (KSampler, custom samplers, etc.)
            when (edge.targetInputName.lowercase()) {
                "positive" -> return "positive"
                "negative" -> return "negative"
                "conditioning" -> return "positive"  // Single conditioning input (like BasicGuider)
            }
        }

        return null
    }
}
