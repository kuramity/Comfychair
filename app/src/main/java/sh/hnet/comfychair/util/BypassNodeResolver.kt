package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Utilities for handling non-executable nodes in workflow execution.
 *
 * ComfyUI 的节点有两种「不执行」形态：
 * - **bypass（mode=4）**：按类型直通 —— 下游输入要重连到该节点同类型的上游；
 * - **mute（mode=2）**：彻底不执行 —— 不能直通，只能断开下游连线。
 *
 * 而 API 格式的 prompt **不识别 mode 字段**（mode 只存在于 UI 工作流格式），
 * 所以提交/导出前必须在这里显式剔除，否则 muted 节点照样会被执行
 * （P3-9 复核：`isMuted` 曾是全仓零引用的死属性）。
 */
internal object BypassNodeResolver {

    private const val TAG = "BypassNodeResolver"

    /** ComfyUI 节点 mode：2 = muted（不执行） */
    private const val MODE_MUTED = 2

    /** ComfyUI 节点 mode：4 = bypassed（按类型直通） */
    private const val MODE_BYPASSED = 4

    /**
     * Apply bypass + mute logic to a workflow JSON before submission to ComfyUI API.
     * ComfyUI's API doesn't automatically handle the mode property - we need to
     * manually remove non-executable nodes and rewire/disconnect connections around them.
     *
     * - bypassed（mode=4）：类型推断重连，行为与旧实现一致；
     * - muted（mode=2）：直接移除该节点，并断开所有指向它的输入（不重连）；
     * - bypass 直通链撞上 muted 节点时返回 null → 下游输入被断开，绝不指向已删除节点。
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @return Modified workflow JSON with non-executable nodes removed and connections resolved
     */
    fun applyBypassedNodes(workflowJson: String): String {
        return try {
            val json = JSONObject(workflowJson)
            val hasWrapper = json.has("nodes") && json.optJSONObject("nodes") != null
            val nodes = if (hasWrapper) json.getJSONObject("nodes") else json

            // Find all non-executable nodes (mode=4 bypassed / mode=2 muted)
            val bypassedNodeIds = mutableSetOf<String>()
            val mutedNodeIds = mutableSetOf<String>()
            val nodeIds = nodes.keys().asSequence().toList()
            for (nodeId in nodeIds) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                when (node.optInt("mode", 0)) {
                    MODE_BYPASSED -> {
                        bypassedNodeIds.add(nodeId)
                        DebugLogger.d(TAG, "applyBypassedNodes: Found bypassed node $nodeId (${node.optString("class_type")})")
                    }
                    MODE_MUTED -> {
                        mutedNodeIds.add(nodeId)
                        DebugLogger.d(TAG, "applyBypassedNodes: Found muted node $nodeId (${node.optString("class_type")})")
                    }
                }
            }

            if (bypassedNodeIds.isEmpty() && mutedNodeIds.isEmpty()) {
                DebugLogger.d(TAG, "applyBypassedNodes: No bypassed/muted nodes found")
                return workflowJson
            }

            // Rewire connections using type-based matching (bypass) / disconnect (mute)
            for (nodeId in nodeIds) {
                if (nodeId in bypassedNodeIds || nodeId in mutedNodeIds) continue
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                val inputKeys = inputs.keys().asSequence().toList()
                for (inputKey in inputKeys) {
                    val inputValue = inputs.opt(inputKey)
                    if (inputValue is JSONArray && inputValue.length() >= 2) {
                        val sourceNodeId = inputValue.optString(0, "")

                        if (sourceNodeId in mutedNodeIds) {
                            // mute = 该节点不执行：不能像 bypass 那样按类型直通，
                            // 只能断开。API prompt 里引用已删除的节点 id 会直接报错。
                            DebugLogger.w(TAG, "applyBypassedNodes: Input '$inputKey' on node $nodeId is fed by muted node $sourceNodeId - removing connection")
                            inputs.remove(inputKey)
                        } else if (sourceNodeId in bypassedNodeIds) {
                            // This input references a bypassed node - need to rewire using type matching
                            val expectedType = inferTypeFromInputName(inputKey)

                            if (expectedType != null) {
                                DebugLogger.d(TAG, "applyBypassedNodes: Input '$inputKey' on node $nodeId expects type $expectedType")

                                val resolvedSource = resolveBypassChain(
                                    nodes,
                                    sourceNodeId,
                                    inputValue.optInt(1, 0),
                                    expectedType,
                                    bypassedNodeIds,
                                    mutedNodeIds
                                )

                                if (resolvedSource != null) {
                                    DebugLogger.d(TAG, "applyBypassedNodes: Rewiring node $nodeId input '$inputKey' ($expectedType) from bypassed $sourceNodeId to ${resolvedSource.optString(0)}[${resolvedSource.optInt(1)}]")
                                    inputs.put(inputKey, resolvedSource)
                                } else {
                                    DebugLogger.w(TAG, "applyBypassedNodes: Could not find $expectedType source for node $nodeId input '$inputKey' - removing connection")
                                    inputs.remove(inputKey)
                                }
                            } else {
                                DebugLogger.w(TAG, "applyBypassedNodes: Unknown type for input '$inputKey' on node $nodeId - removing connection")
                                inputs.remove(inputKey)
                            }
                        }
                    }
                }
            }

            // Remove non-executable nodes from the JSON
            for (bypassedId in bypassedNodeIds) {
                nodes.remove(bypassedId)
                DebugLogger.d(TAG, "applyBypassedNodes: Removed bypassed node $bypassedId")
            }
            for (mutedId in mutedNodeIds) {
                nodes.remove(mutedId)
                DebugLogger.d(TAG, "applyBypassedNodes: Removed muted node $mutedId")
            }

            DebugLogger.i(
                TAG,
                "applyBypassedNodes: Processed ${bypassedNodeIds.size} bypassed / " +
                    "${mutedNodeIds.size} muted nodes"
            )
            json.toString()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "applyBypassedNodes: Error processing bypass/mute: ${e.message}")
            workflowJson
        }
    }

    /**
     * 从**导出的 API JSON** 中剔除 muted 节点（mode=2）并断开指向它们的输入。
     *
     * 为什么导出路径单独一个函数、而不是复用 [applyBypassedNodes]：
     * - 任务口径要求「不改变非 mute 节点的导出结果」——bypass（mode=4）在导出里维持
     *   原样，不能被顺带剔除；
     * - 导出入口拿到的已经是 API 节点表（`nodeId -> {class_type, inputs, mode...}`），
     *   直接原地修改即可保留调用方 `toString(2)` 的格式。
     *
     * 依据：API 格式的 prompt 不识别 mode 字段（mode 只存在于 UI 工作流格式），
     * 引擎会把 muted 节点当普通节点执行，与用户的「静音」意图相反。ComfyUI 的
     * mute 语义是「该节点不执行」，与 bypass（按类型直通）不同，所以这里选择
     * **剔除节点 + 断开下游连线**，而不是重连。
     *
     * @param nodes API 节点表（`JSONObject`，会被原地修改）
     * @return 被移除的 muted 节点数（0 表示没有 mute 节点，内容未变）
     */
    internal fun stripMutedNodes(nodes: JSONObject): Int {
        return try {
            val nodeIds = nodes.keys().asSequence().toList()
            val mutedNodeIds = nodeIds.filterTo(mutableSetOf()) { nodeId ->
                nodes.optJSONObject(nodeId)?.optInt("mode", 0) == MODE_MUTED
            }
            if (mutedNodeIds.isEmpty()) return 0

            for (nodeId in nodeIds) {
                if (nodeId in mutedNodeIds) continue
                val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                for (inputKey in inputs.keys().asSequence().toList()) {
                    val value = inputs.opt(inputKey)
                    if (value is JSONArray && value.length() >= 2 &&
                        value.optString(0, "") in mutedNodeIds
                    ) {
                        inputs.remove(inputKey)
                        DebugLogger.d(
                            TAG,
                            "stripMutedNodes: 断开 node $nodeId 输入 '$inputKey'（源为 muted 节点）"
                        )
                    }
                }
            }
            for (mutedId in mutedNodeIds) {
                nodes.remove(mutedId)
            }
            DebugLogger.i(TAG, "stripMutedNodes: 移除 ${mutedNodeIds.size} 个 muted 节点")
            mutedNodeIds.size
        } catch (e: Exception) {
            DebugLogger.e(TAG, "stripMutedNodes: Error: ${e.message}")
            0
        }
    }

    /**
     * Infer the expected data type from an input name using ComfyUI naming conventions.
     * This allows type-based matching when rewiring connections around bypassed nodes.
     */
    private fun inferTypeFromInputName(inputName: String): String? {
        val lowerName = inputName.lowercase()
        return when {
            lowerName in listOf("samples", "latent_image", "latent", "latent_images") -> "LATENT"
            lowerName in listOf("model", "unet") || lowerName.startsWith("model") -> "MODEL"
            lowerName in listOf("clip", "clip_l", "clip_g") || lowerName.startsWith("clip") -> "CLIP"
            lowerName == "vae" -> "VAE"
            lowerName in listOf("positive", "negative", "conditioning", "cond") -> "CONDITIONING"
            lowerName in listOf("image", "images", "pixels") -> "IMAGE"
            lowerName == "mask" -> "MASK"
            lowerName == "noise" -> "NOISE"
            else -> null
        }
    }

    /**
     * Find a connection input on a node that matches the expected type.
     * Returns the connection JSONArray [sourceNodeId, outputIndex] or null.
     */
    private fun findConnectionByType(nodeInputs: JSONObject, expectedType: String): JSONArray? {
        val inputKeys = nodeInputs.keys()
        while (inputKeys.hasNext()) {
            val inputKey = inputKeys.next()
            val inputValue = nodeInputs.opt(inputKey)

            // Only consider connections (array format)
            if (inputValue is JSONArray && inputValue.length() >= 2) {
                val inputType = inferTypeFromInputName(inputKey)
                if (inputType == expectedType) {
                    DebugLogger.d(TAG, "findConnectionByType: Found $expectedType input '$inputKey' -> [${inputValue.optString(0)}, ${inputValue.optInt(1)}]")
                    return inputValue
                }
            }
        }
        return null
    }

    /**
     * Follow a connection through any bypassed nodes to find the final non-bypassed source.
     * Uses type inference to match the correct input when traversing bypassed nodes.
     *
     * 撞上 muted 节点时返回 null（由调用方断开连线）：mute 是「不执行」而不是
     * 「按类型直通」，继续沿链走会把下游接到一个即将被删除的节点上。
     */
    private fun resolveBypassChain(
        nodes: JSONObject,
        sourceNodeId: String,
        sourceOutputIndex: Int,
        expectedType: String,
        bypassedNodeIds: Set<String>,
        mutedNodeIds: Set<String>,
        depth: Int = 0
    ): JSONArray? {
        if (depth > 10) {
            DebugLogger.w(TAG, "resolveBypassChain: Max depth reached, possible cycle")
            return null
        }

        if (sourceNodeId in mutedNodeIds) {
            // 直通链撞上 muted 节点：mute 是「不执行」，不能继续直通；返回 null
            // 让调用方断开该输入，避免下游指向一个将被删除的节点 id。
            DebugLogger.w(TAG, "resolveBypassChain: Chain hits muted node $sourceNodeId - connection will be removed")
            return null
        }

        if (sourceNodeId !in bypassedNodeIds) {
            // Found non-bypassed source
            return JSONArray().apply {
                put(sourceNodeId)
                put(sourceOutputIndex)
            }
        }

        // Source is bypassed - find matching input and follow it
        val bypassedNode = nodes.optJSONObject(sourceNodeId) ?: return null
        val bypassedInputs = bypassedNode.optJSONObject("inputs") ?: return null

        val matchingConnection = findConnectionByType(bypassedInputs, expectedType)
        if (matchingConnection != null) {
            val nextSourceId = matchingConnection.optString(0, "")
            val nextOutputIndex = matchingConnection.optInt(1, 0)
            DebugLogger.d(TAG, "resolveBypassChain: Following $expectedType through bypassed $sourceNodeId to $nextSourceId[$nextOutputIndex]")
            return resolveBypassChain(
                nodes, nextSourceId, nextOutputIndex, expectedType,
                bypassedNodeIds, mutedNodeIds, depth + 1
            )
        }

        DebugLogger.w(TAG, "resolveBypassChain: No $expectedType input found on bypassed node $sourceNodeId")
        return null
    }
}
