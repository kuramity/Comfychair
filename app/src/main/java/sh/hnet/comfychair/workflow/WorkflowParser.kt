package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * Parses ComfyUI workflow JSON into a WorkflowGraph
 */
class WorkflowParser {

    companion object {
        private const val TAG = "WorkflowParser"

        /** 画布/列表里字面量预览的最大字符数（仅用于显示，绝不写回磁盘） */
        const val DISPLAY_MAX_LENGTH = 30

        /**
         * 把字面量格式化成**仅供显示**的短文本。
         *
         * 与 [parseInputs] 里的字面量归一化严格区分：本函数的结果只允许出现在 UI 上，
         * 不得回填到 `InputValue.Literal` 或序列化路径（否则又变成把截断值写回磁盘）。
         */
        fun formatLiteralValueForDisplay(value: Any): String {
            val text = value.toString()
            return if (text.length > DISPLAY_MAX_LENGTH) {
                text.take(DISPLAY_MAX_LENGTH - 3) + "..."
            } else {
                text
            }
        }
    }

    /**
     * Parse workflow JSON content into a WorkflowGraph
     */
    fun parse(jsonContent: String, workflowName: String = "", workflowDescription: String = ""): WorkflowGraph {
        DebugLogger.d(TAG, "Parsing workflow: name=$workflowName")
        val json = JSONObject(jsonContent)

        val nodes = mutableListOf<WorkflowNode>()
        val edges = mutableListOf<WorkflowEdge>()
        val groups = mutableListOf<WorkflowGroup>()
        val allTemplateVars = mutableSetOf<String>()
        val mappedFields = mutableMapOf<Pair<String, String>, String>()

        // Parse groups if present (at root level)
        if (json.has("groups")) {
            DebugLogger.d(TAG, "Found 'groups' key in JSON at root level")
            val groupsArray = json.optJSONArray("groups")
            if (groupsArray != null) {
                DebugLogger.d(TAG, "Groups array has ${groupsArray.length()} entries")
                groups.addAll(parseGroups(groupsArray))
            } else {
                DebugLogger.w(TAG, "'groups' key exists but is not a JSON array")
            }
        } else {
            DebugLogger.d(TAG, "No 'groups' key found in JSON root")
        }

        // Determine the structure: our custom format has "nodes" object, ComfyUI API format has nodes at top level
        val nodesJson = if (json.has("nodes") && json.get("nodes") is JSONObject) {
            // Our custom workflow format: { "name": "...", "description": "...", "nodes": { ... } }
            json.getJSONObject("nodes")
        } else {
            // Standard ComfyUI API format: nodes at top level
            json
        }

        // Parse each node - workflow JSON has node IDs as keys
        val nodeIds = nodesJson.keys()
        for (nodeId in nodeIds) {
            val nodeValue = nodesJson.get(nodeId)
            if (nodeValue !is JSONObject) continue

            val nodeJson = nodeValue
            val classType = nodeJson.optString("class_type", "Unknown")
            val meta = nodeJson.optJSONObject("_meta")
            val title = meta?.optString("title")?.takeIf { it.isNotEmpty() } ?: classType

            val inputsJson = nodeJson.optJSONObject("inputs") ?: JSONObject()
            val inputs = parseInputs(inputsJson)

            // Find inputs that contain template placeholders like {{positive_prompt}}
            val templateKeys = mutableSetOf<String>()
            inputs.forEach { (key, value) ->
                if (value is InputValue.Literal) {
                    val stringValue = value.value.toString()
                    // Check for {{placeholder}} patterns（正则提升为常量，原先是循环内每次编译）
                    val matches = TemplatePatterns.PLACEHOLDER.findAll(stringValue)
                    for (match in matches) {
                        val placeholderName = match.groupValues[1]
                        // Store the input key that contains a template placeholder
                        templateKeys.add(key)
                        // Also track the placeholder name for the graph
                        allTemplateVars.add(placeholderName)
                        // Track mapped field: (nodeId, inputName) -> placeholderName
                        mappedFields[Pair(nodeId, key)] = placeholderName
                    }
                }
            }

            // Also check for keys that are known template targets (for workflows using direct key names)
            inputs.keys.filter { key ->
                TemplateKeyRegistry.isTemplateKey(key)
            }.forEach { key ->
                templateKeys.add(key)
                allTemplateVars.add(key)
            }

            // Parse node mode (0=active, 2=muted, 4=bypassed)
            val mode = nodeJson.optInt("mode", 0)
            if (mode != 0) {
                DebugLogger.d(TAG, "Parsed node $nodeId with mode=$mode (${if (mode == 4) "bypassed" else if (mode == 2) "muted" else "unknown"})")
            }

            // Parse manual position from _meta.position array [x, y]
            val positionArray = meta?.optJSONArray("position")
            val hasManualPosition = positionArray != null && positionArray.length() >= 2
            val manualX = if (hasManualPosition) positionArray!!.optDouble(0, 0.0).toFloat() else 0f
            val manualY = if (hasManualPosition) positionArray!!.optDouble(1, 0.0).toFloat() else 0f
            if (hasManualPosition) {
                DebugLogger.d(TAG, "Parsed node $nodeId with manual position: ($manualX, $manualY)")
            }

            nodes.add(
                WorkflowNode(
                    id = nodeId,
                    classType = classType,
                    title = title,
                    category = categorizeNode(classType),
                    inputs = sortInputsForLayout(inputs),
                    templateInputKeys = templateKeys,
                    mode = mode,
                    x = manualX,
                    y = manualY,
                    hasManualPosition = hasManualPosition
                )
            )

            // Extract edges from connection inputs
            inputs.forEach { (inputName, value) ->
                if (value is InputValue.Connection) {
                    edges.add(
                        WorkflowEdge(
                            sourceNodeId = value.sourceNodeId,
                            sourceOutputIndex = value.outputIndex,
                            targetNodeId = nodeId,
                            targetInputName = inputName
                        )
                    )
                }
            }
        }

        // Parse notes if present (at root level)
        val notes = if (json.has("notes")) {
            DebugLogger.d(TAG, "Found 'notes' key in JSON at root level")
            val notesArray = json.optJSONArray("notes")
            if (notesArray != null) {
                DebugLogger.d(TAG, "Notes array has ${notesArray.length()} entries")
                parseNotes(notesArray)
            } else {
                DebugLogger.w(TAG, "'notes' key exists but is not a JSON array")
                emptyList()
            }
        } else {
            DebugLogger.d(TAG, "No 'notes' key found in JSON root")
            emptyList()
        }

        // Parse manual placement mode flag from root level
        val isManualPlacementMode = json.optBoolean("manual_placement", false)
        if (isManualPlacementMode) {
            DebugLogger.d(TAG, "Workflow has manual_placement mode enabled")
        }

        DebugLogger.d(TAG, "Parsed workflow: ${nodes.size} nodes, ${edges.size} edges, ${groups.size} groups, ${notes.size} notes, ${allTemplateVars.size} template vars, ${mappedFields.size} mapped fields")

        return WorkflowGraph(
            name = workflowName,
            description = workflowDescription,
            nodes = nodes,
            edges = edges,
            groups = groups,
            notes = notes,
            templateVariables = allTemplateVars,
            mappedFields = mappedFields,
            isManualPlacementMode = isManualPlacementMode
        )
    }

    /**
     * Parse groups array from workflow JSON.
     *
     * New format (ComfyChair): { id, title, member_nodes }
     * Legacy format (ComfyUI): { id, title, bounding } - groups without member_nodes are skipped
     *
     * Groups with no members are skipped.
     */
    private fun parseGroups(groupsArray: JSONArray): List<WorkflowGroup> {
        DebugLogger.d(TAG, "parseGroups: parsing ${groupsArray.length()} group entries")
        val result = mutableListOf<WorkflowGroup>()

        for (i in 0 until groupsArray.length()) {
            val groupJson = groupsArray.optJSONObject(i)
            if (groupJson == null) {
                DebugLogger.w(TAG, "parseGroups: entry $i is not a JSON object, skipping")
                continue
            }

            val id = groupJson.optInt("id", -1)
            if (id < 0) {
                DebugLogger.w(TAG, "parseGroups: entry $i has invalid id ($id), skipping")
                continue
            }

            val title = groupJson.optString("title", "Group")
            val hasMemberNodes = groupJson.has("member_nodes")
            val hasBounding = groupJson.has("bounding")

            DebugLogger.d(TAG, "parseGroups: entry $i - id=$id, title='$title', has member_nodes=$hasMemberNodes, has bounding=$hasBounding")

            // Parse member node IDs (required for new format)
            val memberNodeIds = groupJson.optJSONArray("member_nodes")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
                DebugLogger.d(TAG, "parseGroups: group $id member_nodes array has ${arr.length()} entries, parsed ${ids.size} valid IDs: $ids")
                ids
            } ?: run {
                DebugLogger.d(TAG, "parseGroups: group $id has no member_nodes array (legacy format?)")
                emptySet()
            }

            // Skip groups with no members (including legacy groups without member_nodes)
            if (memberNodeIds.isEmpty()) {
                DebugLogger.w(TAG, "parseGroups: skipping group $id '$title': has no members")
                continue
            }

            result.add(
                WorkflowGroup(
                    id = id,
                    title = title,
                    memberNodeIds = memberNodeIds
                )
            )
            DebugLogger.i(TAG, "parseGroups: successfully parsed group $id '$title' with ${memberNodeIds.size} members")
        }

        DebugLogger.i(TAG, "parseGroups: parsed ${result.size} valid groups out of ${groupsArray.length()} entries")
        return result
    }

    /**
     * Parse notes array from workflow JSON.
     *
     * Format: { id, title, content }
     */
    private fun parseNotes(notesArray: JSONArray): List<WorkflowNote> {
        DebugLogger.d(TAG, "parseNotes: parsing ${notesArray.length()} note entries")
        val result = mutableListOf<WorkflowNote>()

        for (i in 0 until notesArray.length()) {
            val noteJson = notesArray.optJSONObject(i)
            if (noteJson == null) {
                DebugLogger.w(TAG, "parseNotes: entry $i is not a JSON object, skipping")
                continue
            }

            val id = noteJson.optInt("id", -1)
            if (id < 0) {
                DebugLogger.w(TAG, "parseNotes: entry $i has invalid id ($id), skipping")
                continue
            }

            val title = noteJson.optString("title", "Note")
            val content = noteJson.optString("content", "")

            // Parse manual position from position array [x, y]
            val positionArray = noteJson.optJSONArray("position")
            val hasManualPosition = positionArray != null && positionArray.length() >= 2
            val manualX = if (hasManualPosition) positionArray!!.optDouble(0, 0.0).toFloat() else 0f
            val manualY = if (hasManualPosition) positionArray!!.optDouble(1, 0.0).toFloat() else 0f

            result.add(
                WorkflowNote(
                    id = id,
                    title = title,
                    content = content,
                    x = manualX,
                    y = manualY,
                    hasManualPosition = hasManualPosition
                )
            )
            if (hasManualPosition) {
                DebugLogger.d(TAG, "parseNotes: parsed note id=$id title='$title' with manual position: ($manualX, $manualY)")
            } else {
                DebugLogger.d(TAG, "parseNotes: parsed note id=$id title='$title' (${content.length} chars)")
            }
        }

        DebugLogger.i(TAG, "parseNotes: parsed ${result.size} notes out of ${notesArray.length()} entries")
        return result
    }

    /**
     * Parse inputs from a node's inputs JSON object
     */
    private fun parseInputs(inputsJson: JSONObject): Map<String, InputValue> {
        val result = mutableMapOf<String, InputValue>()

        for (key in inputsJson.keys()) {
            val value = inputsJson.get(key)
            result[key] = when {
                // Connection format: ["nodeId", outputIndex]
                value is JSONArray && value.length() == 2 -> {
                    try {
                        InputValue.Connection(
                            sourceNodeId = value.getString(0),
                            outputIndex = value.getInt(1)
                        )
                    } catch (e: Exception) {
                        // If parsing fails, treat as literal
                        DebugLogger.w(TAG, "Failed to parse connection for input '$key': ${e.message}")
                        InputValue.Literal(value.toString())
                    }
                }
                else -> InputValue.Literal(formatLiteralValue(value))
            }
        }

        return result
    }

    /**
     * 归一化字面量值，**保留完整原文**。
     *
     * 这里曾经把超过 30 字符的字符串截成 `前 27 字符 + "..."`，那是给画布显示做的缩进处理，
     * 却落在了图模型上：`WorkflowSerializer` 再把 `WorkflowNode.inputs` 写回文件时，磁盘上
     * 的长提示词就永久变成了 `...`（用户没编辑过的内容也会丢）。显示侧的截断本来就该由
     * 渲染层按可用宽度做（见 `WorkflowGraphCanvas` 的 `TextMeasureCache.truncate`），
     * 所以模型层只做类型归一化，不做任何长度裁剪。需要纯文本预览请用
     * [formatLiteralValueForDisplay]。
     */
    private fun formatLiteralValue(value: Any): Any {
        return when (value) {
            is String -> value
            is Number -> value
            is Boolean -> value
            else -> value.toString()
        }
    }

    /**
     * Categorize a node by its class type
     */
    private fun categorizeNode(classType: String): NodeCategory {
        return when {
            // Loaders
            classType.contains("Loader", ignoreCase = true) -> NodeCategory.LOADER

            // Text encoders
            classType.contains("CLIPTextEncode", ignoreCase = true) ||
            classType.contains("TextEncode", ignoreCase = true) -> NodeCategory.ENCODER

            // Samplers
            classType.contains("Sampler", ignoreCase = true) -> NodeCategory.SAMPLER

            // Latent image creation
            classType.contains("EmptyLatent", ignoreCase = true) ||
            classType.contains("Empty") && classType.contains("Latent", ignoreCase = true) -> NodeCategory.LATENT

            // Input nodes
            classType.contains("LoadImage", ignoreCase = true) -> NodeCategory.INPUT

            // Output nodes
            classType.contains("Save", ignoreCase = true) ||
            classType.contains("Preview", ignoreCase = true) -> NodeCategory.OUTPUT

            // Processing nodes
            classType.contains("Decode", ignoreCase = true) ||
            classType.contains("Encode", ignoreCase = true) ||
            classType.contains("Scale", ignoreCase = true) ||
            classType.contains("Create", ignoreCase = true) ||
            classType.contains("Sampling", ignoreCase = true) ||
            classType.contains("ModelMerge", ignoreCase = true) -> NodeCategory.PROCESS

            else -> NodeCategory.OTHER
        }
    }
}
