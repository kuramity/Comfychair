package sh.hnet.comfychair.util

import org.json.JSONObject

/**
 * Parsed generation metadata from ComfyUI workflow.
 */
data class GenerationMetadata(
    val positivePrompt: String? = null,
    val negativePrompt: String? = null,
    val seed: Long? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val models: List<String> = emptyList(),
    val loras: List<LoraInfo> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val vaes: List<String> = emptyList(),
    val unets: List<String> = emptyList()
)

/**
 * Information about a LoRA model used in generation.
 */
data class LoraInfo(
    val name: String,
    val strength: Double
)

/**
 * Parses ComfyUI workflow JSON into structured metadata.
 */
object MetadataParser {

    /**
     * Parse a ComfyUI workflow JSON string into GenerationMetadata.
     *
     * @param json The workflow JSON string
     * @return Parsed metadata, or null if parsing fails
     */
    fun parseWorkflowJson(json: String): GenerationMetadata? {
        return try {
            val root = JSONObject(json)
            parseWorkflow(root)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a ComfyUI workflow JSONObject into GenerationMetadata.
     */
    private fun parseWorkflow(root: JSONObject): GenerationMetadata {
        var positivePrompt: String? = null
        var negativePrompt: String? = null
        var seed: Long? = null
        var steps: Int? = null
        var cfg: Double? = null
        var sampler: String? = null
        var scheduler: String? = null
        val models = mutableListOf<String>()
        val loras = mutableListOf<LoraInfo>()
        var width: Int? = null
        var height: Int? = null
        val vaes = mutableListOf<String>()
        val unets = mutableListOf<String>()

        // Build node type map and connection graph
        val nodeTypes = mutableMapOf<String, String>() // nodeId -> class_type
        val nodeInputs = mutableMapOf<String, JSONObject>() // nodeId -> inputs
        val clipTexts = mutableMapOf<String, String>() // nodeId -> text (for CLIPTextEncode nodes)

        // Track KSampler positive/negative source connections (nodeId to outputSlot)
        val positiveSourceConnections = mutableListOf<Pair<String, Int>>()
        val negativeSourceConnections = mutableListOf<Pair<String, Int>>()

        // First pass: collect all node data
        val nodeIterator = root.keys()
        while (nodeIterator.hasNext()) {
            val nodeId = nodeIterator.next()
            val node = root.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: continue

            nodeTypes[nodeId] = classType
            nodeInputs[nodeId] = inputs

            // 正/负向连接收集：独立于节点类型。
            // 只认 KSampler* 会漏掉自定义采样器（如 IllustriousTriplePassSampler），
            // 一旦漏掉就只能走"按节点顺序取前两个"的兜底，导致正负提示词互换。
            val positiveInput = inputs.opt("positive")
            val negativeInput = inputs.opt("negative")
            if (positiveInput is org.json.JSONArray && positiveInput.length() >= 2) {
                positiveSourceConnections.add(
                    positiveInput.optString(0) to positiveInput.optInt(1, 0)
                )
            }
            if (negativeInput is org.json.JSONArray && negativeInput.length() >= 2) {
                negativeSourceConnections.add(
                    negativeInput.optString(0) to negativeInput.optInt(1, 0)
                )
            }

            when {
                // 采样器参数提取：KSampler 变体 + 其他自定义采样器
                classType.startsWith("KSampler") || classType.contains("Sampler") -> {
                    // 只在真正取到种子时才覆盖：部分自定义采样器（如 IllustriousTriplePassSampler）
                    // 只有 *_seed_offset 而没有 seed 参数，若直接赋值会把先前取到的种子清空。
                    extractSeed(inputs)?.let { seed = it }
                    steps = inputs.optInt("steps", 0).takeIf { it > 0 }
                    cfg = inputs.optDouble("cfg", 0.0).takeIf { it > 0 }
                    sampler = inputs.optString("sampler_name", "").takeIf { it.isNotEmpty() }
                    scheduler = inputs.optString("scheduler", "").takeIf { it.isNotEmpty() }
                }

                // 独立种子节点（SeedNode / Seed (rgthree) / RandomNoise 等）。
                // 自定义采样器工作流常把种子放在这类节点上，必须单独识别，否则种子缺失。
                seed == null && (inputs.has("seed") || inputs.has("noise_seed")) -> {
                    extractSeed(inputs)?.let { seed = it }
                }

                // CLIP Text Encode
                classType == "CLIPTextEncode" -> {
                    val text = inputs.optString("text", "").takeIf { it.isNotEmpty() }
                    if (text != null) {
                        clipTexts[nodeId] = text
                    }
                }

                // Checkpoint loaders
                classType in listOf("CheckpointLoaderSimple", "CheckpointLoader") -> {
                    val modelName = inputs.optString("ckpt_name", "").takeIf { it.isNotEmpty() }
                    if (modelName != null && modelName !in models) {
                        models.add(modelName)
                    }
                }

                // UNET loaders
                classType == "UNETLoader" -> {
                    val unetName = inputs.optString("unet_name", "").takeIf { it.isNotEmpty() }
                    if (unetName != null && unetName !in unets) {
                        unets.add(unetName)
                    }
                }

                // VAE loaders
                classType == "VAELoader" -> {
                    val vaeName = inputs.optString("vae_name", "").takeIf { it.isNotEmpty() }
                    if (vaeName != null && vaeName !in vaes) {
                        vaes.add(vaeName)
                    }
                }

                // LoRA loaders
                classType.contains("LoraLoader") -> {
                    val loraName = inputs.optString("lora_name", "").takeIf { it.isNotEmpty() }
                    val strength = inputs.optDouble("strength_model", 1.0)
                    if (loraName != null) {
                        loras.add(LoraInfo(loraName, strength))
                    }
                }

                // Latent image size
                classType in listOf("EmptyLatentImage", "EmptySD3LatentImage", "EmptyMochiLatentVideo", "EmptyHunyuanLatentVideo") -> {
                    width = inputs.optInt("width", 0).takeIf { it > 0 }
                    height = inputs.optInt("height", 0).takeIf { it > 0 }
                }
            }
        }

        // Trace back from positive/negative sources to find CLIPTextEncode nodes
        for ((sourceNodeId, outputSlot) in positiveSourceConnections) {
            val clipNodeId = traceToClipTextEncode(
                sourceNodeId, outputSlot, nodeTypes, nodeInputs, preferPositive = true
            )
            if (clipNodeId != null && clipTexts.containsKey(clipNodeId)) {
                positivePrompt = clipTexts[clipNodeId]
            }
        }

        for ((sourceNodeId, outputSlot) in negativeSourceConnections) {
            val clipNodeId = traceToClipTextEncode(
                sourceNodeId, outputSlot, nodeTypes, nodeInputs, preferPositive = false
            )
            if (clipNodeId != null && clipTexts.containsKey(clipNodeId)) {
                negativePrompt = clipTexts[clipNodeId]
            }
        }

        // Fallback: if we couldn't determine prompts from connections,
        // use first two CLIP text nodes
        if (positivePrompt == null && clipTexts.isNotEmpty()) {
            val entries = clipTexts.entries.toList()
            positivePrompt = entries[0].value
            if (negativePrompt == null && entries.size > 1) {
                negativePrompt = entries[1].value
            }
        }

        return GenerationMetadata(
            positivePrompt = positivePrompt,
            negativePrompt = negativePrompt,
            seed = seed,
            steps = steps,
            cfg = cfg,
            sampler = sampler,
            scheduler = scheduler,
            models = models,
            loras = loras,
            width = width,
            height = height,
            vaes = vaes,
            unets = unets
        )
    }

    /**
     * Trace backwards from a node to find the source CLIPTextEncode node.
     * Handles intermediate conditioning nodes by following the connection chain.
     *
     * @param startNodeId The node to start tracing from
     * @param outputSlot The output slot being traced (0 typically means positive, 1 means negative for multi-output nodes)
     * @param nodeTypes Map of node IDs to their class types
     * @param nodeInputs Map of node IDs to their inputs
     * @param visited Set of already visited nodes to prevent cycles
     * @param preferPositive 正在追踪的是正向（true）还是负向（false）；
     *        为 null 时退化为按 outputSlot 判断。**必须显式传入**：
     *        KSampler 的 positive/negative 输入槽号通常都是 0，仅凭 slot 无法区分方向，
     *        若兜底逻辑同时遍历 positive 与 negative 输入，会导致正负提示词互换。
     */
    private fun traceToClipTextEncode(
        startNodeId: String,
        outputSlot: Int,
        nodeTypes: Map<String, String>,
        nodeInputs: Map<String, JSONObject>,
        visited: MutableSet<String> = mutableSetOf(),
        preferPositive: Boolean? = null
    ): String? {
        val visitKey = "$startNodeId:$outputSlot:$preferPositive"
        if (visitKey in visited) return null
        visited.add(visitKey)

        val nodeType = nodeTypes[startNodeId] ?: return null

        // If this is a CLIPTextEncode, we found it
        if (nodeType == "CLIPTextEncode") {
            return startNodeId
        }

        val inputs = nodeInputs[startNodeId] ?: return null

        // 方向判定：优先用调用方传入的追踪方向；
        // 未指定时退化为多输出节点的 slot 约定（slot 0 = positive，slot 1 = negative）
        val tracePositive = preferPositive ?: (outputSlot == 0)

        if (tracePositive) {
            val positiveInput = inputs.opt("positive")
            if (positiveInput is org.json.JSONArray && positiveInput.length() >= 2) {
                val sourceNodeId = positiveInput.optString(0)
                val sourceSlot = positiveInput.optInt(1, 0)
                val result = traceToClipTextEncode(
                    sourceNodeId, sourceSlot, nodeTypes, nodeInputs, visited, preferPositive
                )
                if (result != null) return result
            }
        } else {
            val negativeInput = inputs.opt("negative")
            if (negativeInput is org.json.JSONArray && negativeInput.length() >= 2) {
                val sourceNodeId = negativeInput.optString(0)
                val sourceSlot = negativeInput.optInt(1, 0)
                val result = traceToClipTextEncode(
                    sourceNodeId, sourceSlot, nodeTypes, nodeInputs, visited, preferPositive
                )
                if (result != null) return result
            }
        }

        // For single-output conditioning nodes, check common input names.
        // 注意：positive / negative 只在对应方向下尝试，绝不能交叉，
        // 否则正向追踪会误入负向分支造成正负提示词互换。
        val conditioningInputs = mutableListOf("conditioning", "conditioning_1", "conditioning_2", "cond")
        conditioningInputs.add(if (tracePositive) "positive" else "negative")
        for (inputName in conditioningInputs) {
            val input = inputs.opt(inputName)
            if (input is org.json.JSONArray && input.length() >= 2) {
                val sourceNodeId = input.optString(0)
                val sourceSlot = input.optInt(1, 0)
                val result = traceToClipTextEncode(
                    sourceNodeId, sourceSlot, nodeTypes, nodeInputs, visited, preferPositive
                )
                if (result != null) return result
            }
        }

        return null
    }

    /**
     * Extract seed from KSampler inputs.
     * Handles both direct seed values and control_after_generate pattern.
     */
    private fun extractSeed(inputs: JSONObject): Long? {
        // Try direct seed value
        val seed = inputs.optLong("seed", Long.MIN_VALUE)
        if (seed != Long.MIN_VALUE && seed >= 0) {
            return seed
        }

        // Try noise_seed (used in some samplers)
        val noiseSeed = inputs.optLong("noise_seed", Long.MIN_VALUE)
        if (noiseSeed != Long.MIN_VALUE && noiseSeed >= 0) {
            return noiseSeed
        }

        return null
    }
}
