package sh.hnet.comfychair.workflow.convert

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition

/**
 * Converts LiteGraph workflow format (ComfyUI's default "Save" format) to
 * ComfyChair/API format that can be used by the app.
 *
 * LiteGraph format has:
 * - nodes: Array of node objects with id, type, widgets_values, inputs, outputs
 * - links: Array of [linkId, srcNodeId, srcSlot, dstNodeId, dstSlot, type]
 * - groups: Array with bounding boxes
 *
 * API/ComfyChair format has:
 * - nodes: Object keyed by node ID with class_type, inputs (named map)
 * - groups: Array with member_nodes
 * - notes: Array with id, title, content
 */
class LiteGraphConverter(
    private val getNodeDefinition: (classType: String) -> NodeTypeDefinition?
) {

    /**
     * Result of LiteGraph to API format conversion
     */
    data class ConversionResult(
        val jsonContent: String,
        val warnings: List<String>
    )

    /**
     * Internal representation of a LiteGraph link
     */
    private data class Link(
        val linkId: Int,
        val srcNodeId: Int,
        val srcSlotIdx: Int,
        val dstNodeId: Int,
        val dstSlotIdx: Int,
        val type: String
    )

    /**
     * Internal representation of a LiteGraph node (for group membership calculation)
     */
    private data class NodePosition(
        val id: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    /**
     * Subgraph (ComfyUI component) definition from definitions.subgraphs
     */
    private data class SubgraphDefinition(
        val id: String,
        val name: String,
        val inputs: List<SubgraphPort>,
        val outputs: List<SubgraphPort>,
        val nodes: JSONArray,
        val links: List<SubgraphLink>,
        val inputNodeId: Int,
        val outputNodeId: Int
    )

    /**
     * Input or output port of a subgraph
     */
    private data class SubgraphPort(
        val name: String,
        val type: String,
        val linkIds: List<Int>,
        val slotIndex: Int
    )

    /**
     * Internal link within a subgraph (uses object format, not array)
     */
    private data class SubgraphLink(
        val id: Int,
        val originId: Int,
        val originSlot: Int,
        val targetId: Int,
        val targetSlot: Int,
        val type: String
    )

    /**
     * Result of flattening a subgraph node
     */
    private data class FlattenResult(
        val nodes: Map<String, JSONObject>,
        val nodePositions: Map<Int, NodePosition>,
        val groupTitle: String,
        val memberNodeIds: List<String>,
        val subgraphMetadata: JSONObject,
        val newMaxNodeId: Int,
        val newMaxLinkId: Int
    )

    companion object {
        private const val TAG = "LiteGraphConverter"

        // Note node types that should be extracted to notes array
        private val NOTE_NODE_TYPES = setOf("Note", "MarkdownNote", "workflow/note")

        // Reroute node types that should be skipped
        private val REROUTE_NODE_TYPES = setOf("Reroute", "ReroutePrimitive")

        /** 沿 reroute 链上溯的最大层数（病态数据/环路的硬闸门） */
        private const val MAX_REROUTE_DEPTH = 32

        /** 嵌套 subgraph 的最大展开层数 */
        private const val MAX_SUBGRAPH_DEPTH = 16

        // Control widget values (frontend-only, appear after seed inputs in widgets_values)
        // These are not in server's /object_info and must be detected by value
        private val CONTROL_WIDGET_VALUES = setOf("fixed", "increment", "decrement", "randomize")

        // Input names that may have a control widget following them
        private val SEED_INPUT_NAMES = setOf("seed", "noise_seed")
    }

    /**
     * Convert LiteGraph format workflow to ComfyChair format
     */
    fun convert(liteGraphJson: JSONObject): ConversionResult {
        val warnings = mutableListOf<String>()
        val inputNodeCount = liteGraphJson.optJSONArray("nodes")?.length() ?: 0
        val inputGroupCount = liteGraphJson.optJSONArray("groups")?.length() ?: 0
        DebugLogger.i(TAG, "Converting LiteGraph workflow: $inputNodeCount nodes, $inputGroupCount groups")

        // Parse subgraph definitions first
        val subgraphs = parseSubgraphDefinitions(liteGraphJson)
        if (subgraphs.isNotEmpty()) {
            DebugLogger.i(TAG, "Found ${subgraphs.size} subgraph definitions")
        }

        // Parse links into lookup map
        val linksArray = liteGraphJson.optJSONArray("links")
        val linkMap = parseLinkMap(linksArray)

        // 主图节点索引：reroute 上溯要按 id 找到节点本体，看它是不是 Reroute
        val nodesArray = liteGraphJson.optJSONArray("nodes") ?: JSONArray()
        val mainNodeById = mutableMapOf<Int, JSONObject>()
        for (i in 0 until nodesArray.length()) {
            val node = nodesArray.optJSONObject(i) ?: continue
            val id = node.optInt("id", -1)
            if (id != -1) mainNodeById[id] = node
        }

        // 主图的「link id -> 源(节点,槽位)」解析器（reroute 链上溯的终点查询）
        val mainLinkResolver: (Int) -> Pair<Int, Int>? = { linkId ->
            linkMap[linkId]?.let { it.srcNodeId to it.srcSlotIdx }
        }

        // Calculate max node and link IDs for subgraph flattening offsets
        var maxNodeId = 0
        var maxLinkId = 0
        for (nodeId in mainNodeById.keys) {
            if (nodeId > maxNodeId) maxNodeId = nodeId
        }
        for (link in linkMap.values) {
            if (link.linkId > maxLinkId) maxLinkId = link.linkId
        }

        // Track subgraph output mappings for remapping connections
        // Maps (subgraphNodeId, outputSlot) -> (internalNodeId, internalSlot)
        val subgraphOutputMappings = mutableMapOf<Pair<Int, Int>, Pair<String, Int>>()

        // Parse node positions for group membership
        val nodePositions = mutableMapOf<Int, NodePosition>()

        // Convert nodes
        val apiNodes = JSONObject()
        val notes = JSONArray()
        val subgraphGroups = mutableListOf<JSONObject>()

        // First pass: process subgraph nodes to build output mappings
        var currentNodeOffset = maxNodeId
        var currentLinkOffset = maxLinkId

        for (i in 0 until nodesArray.length()) {
            val node = nodesArray.optJSONObject(i) ?: continue
            val nodeId = node.optInt("id", -1)
            if (nodeId == -1) continue

            val nodeType = node.optString("type", "")

            // Check if this is a subgraph node
            val subgraphDef = subgraphs[nodeType]
            if (subgraphDef != null) {
                // Flatten the subgraph（含嵌套子图的递归展开，visitedSubgraphs 负责环检测）
                val flattenResult = flattenSubgraph(
                    subgraphNode = node,
                    subgraphDef = subgraphDef,
                    subgraphs = subgraphs,
                    nodeIdOffset = currentNodeOffset,
                    linkIdOffset = currentLinkOffset,
                    depth = 1,
                    visitedSubgraphs = setOf(subgraphDef.id),
                    resolveExternalLink = mainLinkResolver,
                    warnings = warnings
                )

                // Add all flattened nodes
                for ((nodeIdStr, apiNode) in flattenResult.nodes) {
                    apiNodes.put(nodeIdStr, apiNode)
                }

                // Merge positions
                nodePositions.putAll(flattenResult.nodePositions)

                // Create group for flattened nodes
                if (flattenResult.memberNodeIds.isNotEmpty()) {
                    val groupId = currentNodeOffset + 10000 + subgraphGroups.size  // Unique group ID
                    subgraphGroups.add(JSONObject().apply {
                        put("id", groupId)
                        put("title", flattenResult.groupTitle)
                        put("member_nodes", JSONArray(flattenResult.memberNodeIds))
                        put("subgraph_metadata", flattenResult.subgraphMetadata)
                    })
                }

                // Store output mappings for this subgraph node
                // Parse from subgraphMetadata.outputs
                val outputsArray = flattenResult.subgraphMetadata.optJSONArray("outputs")
                if (outputsArray != null) {
                    for (j in 0 until outputsArray.length()) {
                        val out = outputsArray.optJSONObject(j) ?: continue
                        val slot = out.optInt("slot", -1)
                        val internalNodeId = out.optString("internal_node_id", "")
                        val internalSlot = out.optInt("internal_slot", 0)
                        if (slot >= 0 && internalNodeId.isNotEmpty()) {
                            subgraphOutputMappings[Pair(nodeId, slot)] = Pair(internalNodeId, internalSlot)
                        }
                    }
                }

                currentNodeOffset = flattenResult.newMaxNodeId
                currentLinkOffset = flattenResult.newMaxLinkId
                continue
            }

            // Skip reroute nodes
            if (nodeType in REROUTE_NODE_TYPES) continue

            // Store position for group membership calculation
            val pos = node.optJSONArray("pos")
            val size = node.optJSONArray("size")
            if (pos != null && pos.length() >= 2) {
                nodePositions[nodeId] = NodePosition(
                    id = nodeId,
                    x = pos.optDouble(0, 0.0).toFloat(),
                    y = pos.optDouble(1, 0.0).toFloat(),
                    width = size?.optDouble(0, 200.0)?.toFloat() ?: 200f,
                    height = size?.optDouble(1, 100.0)?.toFloat() ?: 100f
                )
            }

            // Extract note nodes separately
            if (nodeType in NOTE_NODE_TYPES) {
                val noteObj = extractNote(node)
                if (noteObj != null) {
                    notes.put(noteObj)
                }
                continue
            }

            // Convert regular node
            val (apiNode, nodeWarnings) = convertNode(
                node,
                linkMap,
                { nodeId -> mainNodeById[nodeId] },
                mainLinkResolver
            )
            if (apiNode != null) {
                apiNodes.put(nodeId.toString(), apiNode)
            }
            warnings.addAll(nodeWarnings)
        }

        // Second pass: remap connections that reference subgraph outputs
        if (subgraphOutputMappings.isNotEmpty()) {
            remapSubgraphConnections(apiNodes, linkMap, subgraphOutputMappings)
        }

        // Convert groups
        val groupsArray = liteGraphJson.optJSONArray("groups")
        val groups = convertGroups(groupsArray, nodePositions)

        // Add subgraph groups
        for (sg in subgraphGroups) {
            groups.put(sg)
        }

        // Build final ComfyChair-format JSON
        val result = JSONObject().apply {
            put("nodes", apiNodes)
            if (groups.length() > 0) {
                put("groups", groups)
            }
            if (notes.length() > 0) {
                put("notes", notes)
            }
        }

        DebugLogger.i(TAG, "Conversion complete: ${apiNodes.length()} nodes, ${groups.length()} groups, ${notes.length()} notes")
        if (warnings.isNotEmpty()) {
            DebugLogger.w(TAG, "Conversion warnings: ${warnings.joinToString("; ")}")
        }

        return ConversionResult(result.toString(2), warnings)
    }

    /**
     * Remap connections in converted nodes that reference subgraph outputs
     */
    private fun remapSubgraphConnections(
        apiNodes: JSONObject,
        linkMap: Map<Int, Link>,
        subgraphOutputMappings: Map<Pair<Int, Int>, Pair<String, Int>>
    ) {
        // For each API node, check all its connection inputs
        val nodeIds = apiNodes.keys().asSequence().toList()
        for (nodeIdStr in nodeIds) {
            val apiNode = apiNodes.optJSONObject(nodeIdStr) ?: continue
            val inputs = apiNode.optJSONObject("inputs") ?: continue

            val inputNames = inputs.keys().asSequence().toList()
            for (inputName in inputNames) {
                val value = inputs.opt(inputName)
                // 与嵌套 subgraph 的换算同一判据：连线首元素一定是 String（本类所有连线都
                // 用 put(...toString()) 写入），第二元素是数字。数值型 2 元字面量（如 [11, 0]）
                // 不能被当成连线改写。
                if (value is JSONArray && value.length() == 2 &&
                    value.opt(0) is String && value.opt(1) is Number
                ) {
                    // This is a connection: [srcNodeId, srcSlot]
                    val srcNodeIdStr = value.optString(0, "")
                    val srcSlot = value.optInt(1, 0)
                    val srcNodeId = srcNodeIdStr.toIntOrNull() ?: continue

                    // Check if this references a subgraph output
                    val mapping = subgraphOutputMappings[Pair(srcNodeId, srcSlot)]
                    if (mapping != null) {
                        // Replace with internal node reference
                        val newConnection = JSONArray().apply {
                            put(mapping.first)  // internal node ID
                            put(mapping.second) // internal slot
                        }
                        inputs.put(inputName, newConnection)
                        DebugLogger.d(TAG, "Remapped connection: $srcNodeIdStr:$srcSlot -> ${mapping.first}:${mapping.second}")
                    }
                }
            }
        }
    }

    /**
     * Parse links array into a map for quick lookup
     */
    private fun parseLinkMap(linksArray: JSONArray?): Map<Int, Link> {
        val linkMap = mutableMapOf<Int, Link>()
        if (linksArray == null) return linkMap

        for (i in 0 until linksArray.length()) {
            val linkArr = linksArray.optJSONArray(i) ?: continue
            if (linkArr.length() < 6) continue

            val linkId = linkArr.optInt(0, -1)
            if (linkId == -1) continue

            linkMap[linkId] = Link(
                linkId = linkId,
                srcNodeId = linkArr.optInt(1, 0),
                srcSlotIdx = linkArr.optInt(2, 0),
                dstNodeId = linkArr.optInt(3, 0),
                dstSlotIdx = linkArr.optInt(4, 0),
                type = linkArr.optString(5, "")
            )
        }

        return linkMap
    }

    /**
     * Parse definitions.subgraphs section into a map
     */
    private fun parseSubgraphDefinitions(json: JSONObject): Map<String, SubgraphDefinition> {
        val subgraphs = mutableMapOf<String, SubgraphDefinition>()
        val definitions = json.optJSONObject("definitions") ?: return subgraphs
        val subgraphsArray = definitions.optJSONArray("subgraphs") ?: return subgraphs

        for (i in 0 until subgraphsArray.length()) {
            val sg = subgraphsArray.optJSONObject(i) ?: continue
            val id = sg.optString("id", "")
            if (id.isEmpty()) continue

            val name = sg.optString("name", "Subgraph")
            val inputNodeId = sg.optJSONObject("inputNode")?.optInt("id", -10) ?: -10
            val outputNodeId = sg.optJSONObject("outputNode")?.optInt("id", -20) ?: -20

            // Parse inputs
            val inputsArray = sg.optJSONArray("inputs") ?: JSONArray()
            val inputs = mutableListOf<SubgraphPort>()
            for (j in 0 until inputsArray.length()) {
                val inp = inputsArray.optJSONObject(j) ?: continue
                val linkIdsArray = inp.optJSONArray("linkIds") ?: JSONArray()
                val linkIds = (0 until linkIdsArray.length()).map { linkIdsArray.optInt(it) }
                inputs.add(SubgraphPort(
                    name = inp.optString("name", ""),
                    type = inp.optString("type", "*"),
                    linkIds = linkIds,
                    slotIndex = j
                ))
            }

            // Parse outputs
            val outputsArray = sg.optJSONArray("outputs") ?: JSONArray()
            val outputs = mutableListOf<SubgraphPort>()
            for (j in 0 until outputsArray.length()) {
                val out = outputsArray.optJSONObject(j) ?: continue
                val linkIdsArray = out.optJSONArray("linkIds") ?: JSONArray()
                val linkIds = (0 until linkIdsArray.length()).map { linkIdsArray.optInt(it) }
                outputs.add(SubgraphPort(
                    name = out.optString("name", ""),
                    type = out.optString("type", "*"),
                    linkIds = linkIds,
                    slotIndex = j
                ))
            }

            // Parse internal links (object format, not array)
            val linksArray = sg.optJSONArray("links") ?: JSONArray()
            val links = mutableListOf<SubgraphLink>()
            for (j in 0 until linksArray.length()) {
                val link = linksArray.optJSONObject(j) ?: continue
                links.add(SubgraphLink(
                    id = link.optInt("id", -1),
                    originId = link.optInt("origin_id", 0),
                    originSlot = link.optInt("origin_slot", 0),
                    targetId = link.optInt("target_id", 0),
                    targetSlot = link.optInt("target_slot", 0),
                    type = link.optString("type", "*")
                ))
            }

            val nodes = sg.optJSONArray("nodes") ?: JSONArray()

            subgraphs[id] = SubgraphDefinition(
                id = id,
                name = name,
                inputs = inputs,
                outputs = outputs,
                nodes = nodes,
                links = links,
                inputNodeId = inputNodeId,
                outputNodeId = outputNodeId
            )

            DebugLogger.d(TAG, "Parsed subgraph '$name': ${nodes.length()} nodes, ${links.size} links, ${inputs.size} inputs, ${outputs.size} outputs")
        }

        return subgraphs
    }

    /**
     * Flatten a subgraph node into its internal nodes.
     *
     * 支持**嵌套 subgraph**（P1-14）：内部节点的 type 如果又是一个 subgraph 的 id，
     * 就递归展开；`visitedSubgraphs` + `depth` 负责环检测与层数上限，遇到环直接跳过并留 warning，
     * 绝不把 subgraph 的 UUID 当成 class_type 写进输出（那会让整条流程在服务器上无法执行）。
     *
     * @param subgraphNode 引用该 subgraph 的节点（坐标系在父级）
     * @param subgraphDef 该 subgraph 的定义
     * @param subgraphs 全部 subgraph 定义（按 id/type 索引，供嵌套展开）
     * @param nodeIdOffset 已占用的最大节点 id（新 id 从 +1 开始，保证不冲突）
     * @param linkIdOffset 已占用的最大 link id
     * @param depth 当前展开层数（顶层为 1）
     * @param visitedSubgraphs 当前展开路径上的 subgraph id 集合（环检测）
     * @param resolveExternalLink 把「父级坐标系里的 link id」解析成全局 (源节点, 源槽位)
     * @return FlattenResult with converted nodes, positions, and metadata
     */
    private fun flattenSubgraph(
        subgraphNode: JSONObject,
        subgraphDef: SubgraphDefinition,
        subgraphs: Map<String, SubgraphDefinition>,
        nodeIdOffset: Int,
        linkIdOffset: Int,
        depth: Int,
        visitedSubgraphs: Set<String>,
        resolveExternalLink: (Int) -> Pair<Int, Int>?,
        warnings: MutableList<String>
    ): FlattenResult {
        val subgraphNodeId = subgraphNode.optInt("id", -1)
        DebugLogger.d(TAG, "Flattening subgraph '${subgraphDef.name}' (node #$subgraphNodeId) depth=$depth")

        val apiNodes = mutableMapOf<String, JSONObject>()
        val nodePositions = mutableMapOf<Int, NodePosition>()
        val memberNodeIds = mutableListOf<String>()
        var maxNodeId = nodeIdOffset
        var maxLinkId = linkIdOffset

        // 本子图内的节点/连线索引（reroute 上溯与嵌套展开都要用）
        val localNodeById = mutableMapOf<Int, JSONObject>()
        for (i in 0 until subgraphDef.nodes.length()) {
            val node = subgraphDef.nodes.optJSONObject(i) ?: continue
            val oldId = node.optInt("id", -1)
            if (oldId != -1) localNodeById[oldId] = node
        }
        val localLinkById = subgraphDef.links.associateBy { it.id }

        // Build ID remapping tables（按自增计数器分配，避免「旧 id + 偏移」在嵌套时撞号）
        val nodeIdRemap = mutableMapOf<Int, Int>()  // old internal ID -> new global ID
        var nextNodeId = nodeIdOffset
        for (i in 0 until subgraphDef.nodes.length()) {
            val node = subgraphDef.nodes.optJSONObject(i) ?: continue
            val oldId = node.optInt("id", -1)
            if (oldId == -1) continue
            nextNodeId++
            nodeIdRemap[oldId] = nextNodeId
        }
        if (nextNodeId > maxNodeId) maxNodeId = nextNodeId

        // Remap link IDs
        val linkIdRemap = mutableMapOf<Int, Int>()  // old link ID -> new link ID
        var nextLinkId = linkIdOffset
        for (link in subgraphDef.links) {
            nextLinkId++
            linkIdRemap[link.id] = nextLinkId
        }
        if (nextLinkId > maxLinkId) maxLinkId = nextLinkId

        // Parse subgraph node's external inputs (connections coming from main workflow)
        // 记下「子图输入槽位 -> 父级坐标系里的 link id」，真正的解析交给 resolveExternalLink。
        val subgraphNodeInputLinkIds = mutableMapOf<Int, Int>()
        val subgraphNodeInputs = subgraphNode.optJSONArray("inputs")
        if (subgraphNodeInputs != null) {
            for (i in 0 until subgraphNodeInputs.length()) {
                val input = subgraphNodeInputs.optJSONObject(i) ?: continue
                val linkId = input.optInt("link", -1)
                if (linkId != -1) {
                    subgraphNodeInputLinkIds[i] = linkId
                }
            }
        }

        // These will replace connections from virtual input node (-10)
        val externalInputConnections = mutableMapOf<Int, Pair<Int, Int>>()  // slotIndex -> (全局 srcNodeId, srcSlot)
        for ((slotIndex, linkId) in subgraphNodeInputLinkIds) {
            resolveExternalLink(linkId)?.let { externalInputConnections[slotIndex] = it }
        }

        /**
         * 在本子图坐标系里沿 reroute 链上溯，返回**全局** (节点 id, 槽位)。
         * 上游缺失/成环/超层返回 null。
         */
        fun resolveLocalReroute(startNodeId: Int, startSlot: Int): Pair<Int, Int>? {
            fun toGlobal(localId: Int): Int = nodeIdRemap[localId] ?: localId

            var currentId = startNodeId
            var currentSlot = startSlot
            val visited = mutableSetOf<Int>()
            var hops = 0
            while (hops++ < MAX_REROUTE_DEPTH) {
                val node = localNodeById[currentId]
                if (node == null) {
                    // 不是本子图的节点（可能是虚拟输入节点或外部节点），按原值返回
                    return toGlobal(currentId) to currentSlot
                }
                if (node.optString("type", "") !in REROUTE_NODE_TYPES) {
                    return toGlobal(currentId) to currentSlot
                }
                if (!visited.add(currentId)) {
                    DebugLogger.w(TAG, "子图内 Reroute 链成环（节点 #$currentId），断开这条连线")
                    return null
                }
                val upstreamLinkId = node.optJSONArray("inputs")
                    ?.optJSONObject(0)
                    ?.optInt("link", -1) ?: -1
                if (upstreamLinkId == -1) return null
                val upstreamLink = localLinkById[upstreamLinkId] ?: return null
                if (upstreamLink.originId == subgraphDef.inputNodeId) {
                    // reroute 的上游是子图输入：转成对父级输入的解析（结果已是全局 id）
                    val parentLinkId = subgraphNodeInputLinkIds[upstreamLink.originSlot] ?: return null
                    return resolveExternalLink(parentLinkId)
                }
                currentId = upstreamLink.originId
                currentSlot = upstreamLink.originSlot
            }
            DebugLogger.w(TAG, "子图内 Reroute 链超过 $MAX_REROUTE_DEPTH 层，断开这条连线")
            return null
        }

        /** 把本子图坐标系里的 link id 解析成全局 (源节点 id, 源槽位)。 */
        fun resolveLinkId(linkId: Int): Pair<Int, Int>? {
            val link = localLinkById[linkId] ?: return null
            return if (link.originId == subgraphDef.inputNodeId) {
                val parentLinkId = subgraphNodeInputLinkIds[link.originSlot] ?: return null
                resolveExternalLink(parentLinkId)
            } else {
                resolveLocalReroute(link.originId, link.originSlot)
            }
        }

        // Parse subgraph node's widget values for unconnected inputs
        val widgetValues = subgraphNode.optJSONArray("widgets_values")
        val proxyWidgets = subgraphNode.optJSONObject("properties")?.optJSONArray("proxyWidgets")

        // Build widget value map: inputName -> value
        val widgetValueMap = mutableMapOf<String, Any>()
        if (widgetValues != null && proxyWidgets != null) {
            for (i in 0 until proxyWidgets.length()) {
                val proxy = proxyWidgets.optJSONArray(i) ?: continue
                val inputName = proxy.optString(1, "")
                if (inputName.isNotEmpty() && i < widgetValues.length()) {
                    val value = widgetValues.opt(i)
                    if (value != null && value != JSONObject.NULL) {
                        widgetValueMap[inputName] = value
                    }
                }
            }
        }

        // Build link lookup for internal links: (targetNodeId, targetSlot) -> link
        val internalLinksByTarget = subgraphDef.links
            .filter { it.targetId != subgraphDef.outputNodeId }  // Exclude links to output node
            .groupBy { Pair(it.targetId, it.targetSlot) }

        // Build lookup for links FROM input node: slotIndex -> list of internal links
        val inputNodeLinks = mutableMapOf<Int, MutableList<SubgraphLink>>()
        for (link in subgraphDef.links) {
            if (link.originId == subgraphDef.inputNodeId) {
                inputNodeLinks.getOrPut(link.originSlot) { mutableListOf() }.add(link)
            }
        }

        // Find output mappings: which internal nodes connect to output virtual node
        // subgraph output slot -> (internal node ID, slot)
        // 同样要沿 reroute 上溯：直接连到 Reroute 的输出，其真实来源在 reroute 的上游。
        val outputMappings = mutableMapOf<Int, Pair<Int, Int>>()
        for (link in subgraphDef.links) {
            if (link.targetId == subgraphDef.outputNodeId) {
                val source = resolveLocalReroute(link.originId, link.originSlot)
                if (source != null) {
                    outputMappings[link.targetSlot] = source
                } else {
                    DebugLogger.w(
                        TAG,
                        "子图 '${subgraphDef.name}' 输出槽 ${link.targetSlot} 的源节点无法解析（link #${link.id}），已跳过"
                    )
                    warnings.add(
                        "Subgraph \"${subgraphDef.name}\": output slot ${link.targetSlot} was dropped (unresolvable source)"
                    )
                }
            }
        }

        // Process each internal node
        //
        // 嵌套 subgraph 节点本身**不产出** API 节点（只产出它内部的节点），但同级别的
        // 连线/输出槽完全可能以它为源。为此记两份表，循环结束后统一换算：
        // - nestedNodePlaceholderIds：占位 id -> 该嵌套节点的旧 id
        // - nestedOutputMappings：(嵌套节点旧 id, 输出槽) -> 内部真实节点 id/槽位
        val nestedNodePlaceholderIds = mutableMapOf<Int, Int>()
        val nestedOutputMappings = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()

        for (i in 0 until subgraphDef.nodes.length()) {
            val node = subgraphDef.nodes.optJSONObject(i) ?: continue
            val oldNodeId = node.optInt("id", -1)
            if (oldNodeId == -1) continue

            val nodeType = node.optString("type", "")

            // 嵌套 subgraph：内部节点本身又是一个 subgraph → 递归展开（P1-14）。
            // 旧实现只展开第一层，嵌套层的 UUID 会被当成 class_type 原样写进输出，
            // 服务器 /prompt 校验时直接报「找不到该节点类型」，整条流程不可用。
            val nestedDef = subgraphs[nodeType]
            if (nestedDef != null) {
                if (nestedDef.id in visitedSubgraphs || depth >= MAX_SUBGRAPH_DEPTH) {
                    val reason = if (nestedDef.id in visitedSubgraphs) {
                        "循环引用（${nestedDef.id} 已在当前展开路径上）"
                    } else {
                        "嵌套层数超过上限 $MAX_SUBGRAPH_DEPTH"
                    }
                    DebugLogger.w(TAG, "跳过嵌套子图 '${nestedDef.name}'(#${node.optInt("id", -1)})：$reason")
                    warnings.add("Subgraph \"${nestedDef.name}\" (#${node.optInt("id", -1)}) skipped: $reason")
                    // 仍然登记它的占位 id（但不登记任何输出映射）：下面统一换算时，
                    // 指向它的连线会被断开，而不是留下指向不存在节点的悬空引用。
                    nodeIdRemap[oldNodeId]?.let { nestedNodePlaceholderIds[it] = oldNodeId }
                    continue
                }
                val nestedResult = flattenSubgraph(
                    subgraphNode = node,
                    subgraphDef = nestedDef,
                    subgraphs = subgraphs,
                    nodeIdOffset = maxNodeId,
                    linkIdOffset = maxLinkId,
                    depth = depth + 1,
                    visitedSubgraphs = visitedSubgraphs + nestedDef.id,
                    resolveExternalLink = { linkId -> resolveLinkId(linkId) },
                    warnings = warnings
                )
                apiNodes.putAll(nestedResult.nodes)
                nodePositions.putAll(nestedResult.nodePositions)
                memberNodeIds.addAll(nestedResult.memberNodeIds)
                if (nestedResult.newMaxNodeId > maxNodeId) maxNodeId = nestedResult.newMaxNodeId
                if (nestedResult.newMaxLinkId > maxLinkId) maxLinkId = nestedResult.newMaxLinkId

                // 记下这个嵌套节点的占位 id，以及它的输出槽在内部对应的真实节点，
                // 供循环结束后的连线换算使用（否则父级连线会指向一个永不存在的 id）。
                nodeIdRemap[oldNodeId]?.let { nestedNodePlaceholderIds[it] = oldNodeId }
                val nestedOutputs = nestedResult.subgraphMetadata.optJSONArray("outputs")
                if (nestedOutputs != null) {
                    for (k in 0 until nestedOutputs.length()) {
                        val out = nestedOutputs.optJSONObject(k) ?: continue
                        val outSlot = out.optInt("slot", -1)
                        val internalNodeId = out.optString("internal_node_id", "").toIntOrNull() ?: continue
                        val internalSlot = out.optInt("internal_slot", 0)
                        if (outSlot >= 0) {
                            nestedOutputMappings[oldNodeId to outSlot] = internalNodeId to internalSlot
                        }
                    }
                }
                continue
            }

            // Skip reroute nodes
            if (nodeType in REROUTE_NODE_TYPES) continue

            // Skip note nodes (handled separately in main conversion)
            if (nodeType in NOTE_NODE_TYPES) continue

            val newNodeId = nodeIdRemap[oldNodeId] ?: (oldNodeId + nodeIdOffset)
            val newNodeIdStr = newNodeId.toString()
            memberNodeIds.add(newNodeIdStr)

            // Store position for group membership
            val pos = node.optJSONArray("pos")
            val size = node.optJSONArray("size")
            if (pos != null && pos.length() >= 2) {
                nodePositions[newNodeId] = NodePosition(
                    id = newNodeId,
                    x = pos.optDouble(0, 0.0).toFloat(),
                    y = pos.optDouble(1, 0.0).toFloat(),
                    width = size?.optDouble(0, 200.0)?.toFloat() ?: 200f,
                    height = size?.optDouble(1, 100.0)?.toFloat() ?: 100f
                )
            }

            // Get node definition for widget mapping
            val nodeDef = getNodeDefinition(nodeType)

            // Build inputs map
            val inputs = JSONObject()

            // 1. Map widget values from internal node
            val internalWidgetValues = node.optJSONArray("widgets_values")
            if (internalWidgetValues != null && nodeDef != null) {
                mapWidgetValues(internalWidgetValues, nodeDef.inputs, inputs, nodeType, newNodeId, warnings)
            }

            // 2. Add connections from internal node's inputs array
            val nodeInputs = node.optJSONArray("inputs")
            if (nodeInputs != null) {
                for (j in 0 until nodeInputs.length()) {
                    val input = nodeInputs.optJSONObject(j) ?: continue
                    val inputName = input.optString("name", "")
                    val linkId = input.optInt("link", -1)

                    if (inputName.isEmpty() || linkId == -1) continue

                    // Find the internal link
                    val internalLink = subgraphDef.links.find { it.id == linkId }
                    if (internalLink != null) {
                        if (internalLink.originId == subgraphDef.inputNodeId) {
                            // Connection from virtual input node - resolve to external connection or widget value
                            val slotIndex = internalLink.originSlot
                            val subgraphInput = subgraphDef.inputs.getOrNull(slotIndex)

                            // Check if there's an external connection (takes priority over widget value)
                            val externalConn = externalInputConnections[slotIndex]
                            if (externalConn != null) {
                                // Use external connection
                                val connection = JSONArray().apply {
                                    put(externalConn.first.toString())
                                    put(externalConn.second)
                                }
                                inputs.put(inputName, connection)
                            } else if (subgraphInput != null && widgetValueMap.containsKey(subgraphInput.name)) {
                                // Use widget value from subgraph node
                                inputs.put(inputName, widgetValueMap[subgraphInput.name])
                            }
                        } else {
                            // Internal connection：沿 reroute 链上溯到真实源节点（P1-13）。
                            // 直连 reroute 时旧实现写的是 reroute 自己的 id，而 reroute 已被跳过，
                            // 输出里就成了一条指向不存在节点的连线。
                            val source = resolveLocalReroute(internalLink.originId, internalLink.originSlot)
                            if (source != null) {
                                val connection = JSONArray().apply {
                                    put(source.first.toString())
                                    put(source.second)
                                }
                                inputs.put(inputName, connection)
                            } else {
                                DebugLogger.w(
                                    TAG,
                                    "子图 '${subgraphDef.name}' 节点 #$oldNodeId 的输入 '$inputName' 源节点无法解析（link #$linkId），已断开"
                                )
                                warnings.add(
                                    "Subgraph \"${subgraphDef.name}\": input \"$inputName\" on node #$oldNodeId was disconnected (unresolvable source)"
                                )
                            }
                        }
                    }
                }
            }

            // Build API node
            val apiNode = JSONObject().apply {
                put("class_type", nodeType)
                put("inputs", inputs)

                // Preserve mode
                val mode = node.optInt("mode", 0)
                if (mode != 0) {
                    put("mode", mode)
                }

                // Add metadata
                val meta = JSONObject()
                val title = node.optString("title", "")
                if (title.isNotEmpty() && title != nodeType) {
                    meta.put("title", title)
                }
                if (meta.length() > 0) {
                    put("_meta", meta)
                }
            }

            apiNodes[newNodeIdStr] = apiNode
        }

        // ---- 嵌套 subgraph 的连线换算（F2）----
        //
        // 嵌套 subgraph 节点只"透传"内部节点，自身不产出 API 节点。父级里以它为源的连线
        // （以及本层的输出槽）此前会保留它的占位 id —— 那个 id 永远不在输出节点表里，
        // 与 P1-13 的 reroute 悬空引用是同一类故障。这里统一换算成内部真实节点；
        // 换算不到（该输出槽在内部没接，或嵌套因环/超层被跳过）就断开这条连线并留 warning。
        if (nestedNodePlaceholderIds.isNotEmpty()) {
            /**
             * 把「以嵌套 subgraph 为源」的引用换算成内部真实节点。
             * 不是占位 id 的原样返回；占位 id 但找不到对应输出槽时返回 null（调用方断开）。
             */
            fun resolveNestedSource(sourceId: Int, sourceSlot: Int): Pair<Int, Int>? {
                val nestedOldId = nestedNodePlaceholderIds[sourceId] ?: return sourceId to sourceSlot
                return nestedOutputMappings[nestedOldId to sourceSlot] ?: run {
                    DebugLogger.w(
                        TAG,
                        "子图 '${subgraphDef.name}'：嵌套子图 #$nestedOldId 的输出槽 $sourceSlot 无法解析，断开该连线"
                    )
                    warnings.add(
                        "Subgraph \"${subgraphDef.name}\": reference to nested subgraph #$nestedOldId output slot $sourceSlot was dropped"
                    )
                    null
                }
            }

            for (nodeKey in apiNodes.keys.toList()) {
                val nodeInputsJson = apiNodes[nodeKey]?.optJSONObject("inputs") ?: continue
                for (inputName in nodeInputsJson.keys().asSequence().toList()) {
                    val value = nodeInputsJson.opt(inputName)
                    // 连线一律写成 [节点id(字符串), 槽位(数字)]（见本类所有 put(...toString()) 处）。
                    // 因此首元素必须是 **String** 才算连线：数值型 2 元字面量（如 [11, 0]）
                    // 首元素是数字，即使数值恰等于某个嵌套占位 id，也绝不能被改写或删除。
                    if (value !is JSONArray || value.length() != 2) continue
                    if (value.opt(0) !is String || value.opt(1) !is Number) continue
                    val sourceId = value.optString(0, "").toIntOrNull() ?: continue
                    val sourceSlot = value.optInt(1, 0)
                    val nestedOldId = nestedNodePlaceholderIds[sourceId] ?: continue

                    val resolved = resolveNestedSource(sourceId, sourceSlot)
                    if (resolved == null) {
                        nodeInputsJson.remove(inputName)
                    } else {
                        nodeInputsJson.put(inputName, JSONArray().apply {
                            put(resolved.first.toString())
                            put(resolved.second)
                        })
                        DebugLogger.d(TAG, "嵌套子图连线换算：[$sourceId,$sourceSlot] -> [${resolved.first},${resolved.second}]")
                    }
                }
            }

            // 子图自身的输出槽同样可能以嵌套 subgraph 为源
            for ((outSlot, mapping) in outputMappings.entries.toList()) {
                val resolved = resolveNestedSource(mapping.first, mapping.second)
                if (resolved == null) {
                    outputMappings.remove(outSlot)
                } else {
                    outputMappings[outSlot] = resolved
                }
            }
        }

        // Build subgraph metadata for future compact view support
        val subgraphMetadata = JSONObject().apply {
            put("original_subgraph_id", subgraphDef.id)
            put("display_mode", "expanded")  // Future: "collapsed" for compact view

            // Store input/output definitions for future compact view
            val inputsArray = JSONArray()
            for (input in subgraphDef.inputs) {
                inputsArray.put(JSONObject().apply {
                    put("name", input.name)
                    put("type", input.type)
                    put("slot", input.slotIndex)
                })
            }
            put("inputs", inputsArray)

            val outputsArray = JSONArray()
            for ((slot, mapping) in outputMappings) {
                val output = subgraphDef.outputs.getOrNull(slot)
                outputsArray.put(JSONObject().apply {
                    put("name", output?.name ?: "output_$slot")
                    put("type", output?.type ?: "*")
                    put("slot", slot)
                    put("internal_node_id", mapping.first.toString())
                    put("internal_slot", mapping.second)
                })
            }
            put("outputs", outputsArray)
        }

        DebugLogger.d(TAG, "Flattened subgraph '${subgraphDef.name}': ${apiNodes.size} nodes, ${outputMappings.size} outputs")

        return FlattenResult(
            nodes = apiNodes,
            nodePositions = nodePositions,
            groupTitle = subgraphDef.name,
            memberNodeIds = memberNodeIds,
            subgraphMetadata = subgraphMetadata,
            newMaxNodeId = maxNodeId,
            newMaxLinkId = maxLinkId
        )
    }

    /**
     * 沿 reroute 链上溯到真实源节点。
     *
     * 背景（P1-13）：`Reroute` 节点在转换时被跳过（它不产生 API 节点），但别的节点指向
     * reroute 的连线曾经被原样写成 `[rerouteId, slot]`，那个 id 在输出 JSON 里根本不存在，
     * ComfyUI 会报 `Prompt outputs failed validation: node not found`。
     * 这里顺着 `inputs[0].link` 一路往上游走，直到命中一个**不是** Reroute 的节点。
     *
     * @param nodeLookup 当前坐标系下 id -> LiteGraph 节点
     * @param linkResolver 当前坐标系下 linkId -> (源节点 id, 源槽位)
     * @return 真实源（节点, 槽位）；上游缺失或成环时返回 null（调用方应断开这条连线）
     */
    private fun resolveThroughReroutes(
        sourceNodeId: Int,
        sourceSlot: Int,
        nodeLookup: (Int) -> JSONObject?,
        linkResolver: (Int) -> Pair<Int, Int>?
    ): Pair<Int, Int>? {
        var currentId = sourceNodeId
        var currentSlot = sourceSlot
        val visited = mutableSetOf<Int>()
        var depth = 0

        while (depth++ < MAX_REROUTE_DEPTH) {
            val node = nodeLookup(currentId) ?: return currentId to currentSlot
            if (node.optString("type", "") !in REROUTE_NODE_TYPES) {
                return currentId to currentSlot
            }
            if (!visited.add(currentId)) {
                DebugLogger.w(TAG, "Reroute 链成环（节点 #$currentId），断开这条连线")
                return null
            }
            val upstreamLinkId = node.optJSONArray("inputs")
                ?.optJSONObject(0)
                ?.optInt("link", -1) ?: -1
            if (upstreamLinkId == -1) {
                DebugLogger.w(TAG, "Reroute 节点 #$currentId 没有上游输入，断开这条连线")
                return null
            }
            val upstream = linkResolver(upstreamLinkId)
            if (upstream == null) {
                DebugLogger.w(TAG, "Reroute 节点 #$currentId 的上游 link #$upstreamLinkId 解析失败，断开这条连线")
                return null
            }
            currentId = upstream.first
            currentSlot = upstream.second
        }

        DebugLogger.w(TAG, "Reroute 链超过 $MAX_REROUTE_DEPTH 层（节点 #$sourceNodeId），断开这条连线")
        return null
    }

    /**
     * Convert a single LiteGraph node to API format
     */
    private fun convertNode(
        node: JSONObject,
        linkMap: Map<Int, Link>,
        nodeLookup: (Int) -> JSONObject?,
        linkResolver: (Int) -> Pair<Int, Int>?
    ): Pair<JSONObject?, List<String>> {
        val warnings = mutableListOf<String>()
        val nodeType = node.optString("type", "")
        val nodeId = node.optInt("id", -1)

        if (nodeType.isEmpty()) {
            return null to warnings
        }

        // Get node definition for widget mapping
        val nodeDef = getNodeDefinition(nodeType)

        // Build inputs map
        val inputs = JSONObject()

        // 1. Map widget values to named inputs
        val widgetValues = node.optJSONArray("widgets_values")
        if (widgetValues != null && nodeDef != null) {
            mapWidgetValues(widgetValues, nodeDef.inputs, inputs, nodeType, nodeId, warnings)
        } else if (widgetValues != null && nodeDef == null) {
            // Unknown node - can't map widget values
            DebugLogger.w(TAG, "Node #$nodeId ($nodeType): no definition found, cannot map widget values")
            warnings.add("Node \"$nodeType\" (#$nodeId): inputs could not be fully mapped")
        }

        // 2. Add connections from inputs array
        val nodeInputs = node.optJSONArray("inputs")
        if (nodeInputs != null) {
            for (i in 0 until nodeInputs.length()) {
                val input = nodeInputs.optJSONObject(i) ?: continue
                val inputName = input.optString("name", "")
                val linkId = input.optInt("link", -1)

                if (inputName.isNotEmpty() && linkId != -1) {
                    val link = linkMap[linkId]
                    if (link != null) {
                        // 指向 Reroute 的连线必须上溯到真实源节点，否则输出里会引用不存在的节点
                        val source = resolveThroughReroutes(
                            link.srcNodeId, link.srcSlotIdx, nodeLookup, linkResolver
                        )
                        if (source != null) {
                            // Connection format: [srcNodeId, srcSlotIdx]
                            val connection = JSONArray().apply {
                                put(source.first.toString())
                                put(source.second)
                            }
                            inputs.put(inputName, connection)
                        } else {
                            DebugLogger.w(
                                TAG,
                                "Node #$nodeId ($nodeType): 输入 '$inputName' 的源节点无法解析（link #$linkId），已断开"
                            )
                            warnings.add("Node \"$nodeType\" (#$nodeId): input \"$inputName\" was disconnected (unresolvable source)")
                        }
                    }
                }
            }
        }

        // Build API node
        val apiNode = JSONObject().apply {
            put("class_type", nodeType)
            put("inputs", inputs)

            // Preserve mode (0=active, 2=muted, 4=bypassed)
            val mode = node.optInt("mode", 0)
            if (mode != 0) {
                put("mode", mode)
            }

            // Add metadata (title only)
            val meta = JSONObject()
            val title = node.optString("title", "")
            if (title.isNotEmpty() && title != nodeType) {
                meta.put("title", title)
            }
            if (meta.length() > 0) {
                put("_meta", meta)
            }
        }

        return apiNode to warnings
    }

    /**
     * Map LiteGraph widgets_values array to named inputs using node definition
     */
    private fun mapWidgetValues(
        widgetValues: JSONArray,
        inputDefs: List<InputDefinition>,
        inputs: JSONObject,
        nodeType: String,
        nodeId: Int,
        warnings: MutableList<String>
    ) {
        // Filter to only editable inputs (not connection-only inputs)
        val editableInputs = inputDefs.filter { !it.forceInput && !isConnectionOnlyType(it.type) }

        var widgetIndex = 0
        for (inputDef in editableInputs) {
            if (widgetIndex >= widgetValues.length()) break

            val value = widgetValues.opt(widgetIndex)

            // Skip control widgets defined in server (rare, but handle it)
            if (inputDef.name == "control_after_generate" ||
                inputDef.name.startsWith("control_")) {
                widgetIndex++
                continue
            }

            // Map the value
            if (value != null && value != JSONObject.NULL) {
                inputs.put(inputDef.name, value)
            }

            widgetIndex++

            // After mapping a seed input, check if next widget value is a control value
            // (control_after_generate is frontend-only and not in server's /object_info)
            if (inputDef.name in SEED_INPUT_NAMES && widgetIndex < widgetValues.length()) {
                val nextValue = widgetValues.opt(widgetIndex)
                if (nextValue is String && nextValue in CONTROL_WIDGET_VALUES) {
                    widgetIndex++
                }
            }
        }
    }

    /**
     * Check if an input type is connection-only (not a widget)
     */
    private fun isConnectionOnlyType(type: String): Boolean {
        // Common connection-only types (not widgets)
        return type in setOf(
            "MODEL", "CLIP", "VAE", "CONDITIONING", "LATENT", "IMAGE",
            "MASK", "CONTROL_NET", "STYLE_MODEL", "CLIP_VISION", "CLIP_VISION_OUTPUT",
            "GLIGEN", "UPSCALE_MODEL", "SAMPLER", "SIGMAS", "NOISE", "GUIDER",
            "*"  // Wildcard type
        )
    }

    /**
     * Extract a Note node to notes array format
     */
    private fun extractNote(node: JSONObject): JSONObject? {
        val nodeId = node.optInt("id", -1)
        if (nodeId == -1) return null

        val title = node.optString("title", "Note")
        val widgetValues = node.optJSONArray("widgets_values")
        val content = widgetValues?.optString(0, "") ?: ""

        return JSONObject().apply {
            put("id", nodeId)
            put("title", title)
            put("content", content)
        }
    }

    /**
     * Convert LiteGraph groups (with bounding boxes) to ComfyChair format (with member_nodes)
     */
    private fun convertGroups(
        groupsArray: JSONArray?,
        nodePositions: Map<Int, NodePosition>
    ): JSONArray {
        val result = JSONArray()
        if (groupsArray == null) return result

        for (i in 0 until groupsArray.length()) {
            val group = groupsArray.optJSONObject(i) ?: continue

            val title = group.optString("title", "Group")
            val bounding = group.optJSONArray("bounding")

            if (bounding == null || bounding.length() < 4) continue

            val groupX = bounding.optDouble(0, 0.0).toFloat()
            val groupY = bounding.optDouble(1, 0.0).toFloat()
            val groupW = bounding.optDouble(2, 0.0).toFloat()
            val groupH = bounding.optDouble(3, 0.0).toFloat()

            // Find all nodes within this group's bounds
            val memberNodes = JSONArray()
            for ((nodeId, pos) in nodePositions) {
                if (isNodeInGroup(pos, groupX, groupY, groupW, groupH)) {
                    memberNodes.put(nodeId.toString())
                }
            }

            // Only add group if it has members
            if (memberNodes.length() > 0) {
                val groupId = group.optInt("id", i + 1)  // Use original id or fallback to index+1
                val groupObj = JSONObject().apply {
                    put("id", groupId)
                    put("title", title)
                    put("member_nodes", memberNodes)
                }
                result.put(groupObj)
            }
        }

        return result
    }

    /**
     * Check if a node is within a group's bounding box
     */
    private fun isNodeInGroup(
        nodePos: NodePosition,
        groupX: Float,
        groupY: Float,
        groupW: Float,
        groupH: Float
    ): Boolean {
        // Check if node's top-left corner is within the group bounds
        // (This is how LiteGraph typically determines group membership)
        return nodePos.x >= groupX &&
               nodePos.y >= groupY &&
               nodePos.x <= groupX + groupW &&
               nodePos.y <= groupY + groupH
    }
}
