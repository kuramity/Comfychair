package sh.hnet.comfychair.workflow

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * Full definition of a node input from /object_info
 */
@Stable
data class InputDefinition(
    val name: String,
    val type: String,               // "INT", "FLOAT", "STRING", "BOOLEAN", or connection type
    val isRequired: Boolean,
    val default: Any? = null,
    val min: Number? = null,        // For INT/FLOAT
    val max: Number? = null,        // For INT/FLOAT
    val step: Number? = null,       // For FLOAT sliders
    val options: List<String>? = null,  // For enum dropdowns
    val multiline: Boolean = false,     // For STRING
    val forceInput: Boolean = false,    // If true, must be connected (not editable)
    val tooltip: String? = null,        // Help text for the input
    val round: Number? = null,          // Rounding precision for FLOAT
    val multiselect: Boolean = false    // For COMBO: allow multiple selections
)

/**
 * Get the effective default value for this input definition.
 * Returns the server-provided default if available, otherwise returns
 * a type-appropriate fallback value.
 */
fun InputDefinition.getEffectiveDefault(): Any {
    return default ?: when (type) {
        "INT" -> 0
        "FLOAT" -> 0.0
        "STRING" -> ""
        "BOOLEAN" -> false
        "ENUM" -> options?.firstOrNull() ?: ""
        else -> ""
    }
}

/**
 * Definition of a node output slot from /object_info.
 * Type is used for wire coloring, name is displayed in the UI.
 */
@Immutable
data class OutputSlot(
    val type: String,  // e.g., "CONDITIONING" - used for slot colors
    val name: String   // e.g., "positive" - displayed in UI
)

/**
 * Full definition of a node type from /object_info
 */
@Stable
data class NodeTypeDefinition(
    val classType: String,
    val category: String,
    val inputs: List<InputDefinition>,
    val outputs: List<OutputSlot>,
    val displayName: String? = null,    // Human-readable name
    val description: String? = null,    // Node documentation
    val deprecated: Boolean = false,    // Whether node is deprecated
    val experimental: Boolean = false   // Whether node is experimental
)

/**
 * Information about a node type from /object_info
 */
data class NodeTypeInfo(
    val classType: String,
    val inputTypes: Map<String, String>,    // inputName -> type (e.g., "model" -> "MODEL")
    val outputTypes: List<String>,          // output types by index (e.g., ["MODEL", "CLIP", "VAE"])
    val outputSlots: List<OutputSlot> = emptyList()  // full output info with names
)

/**
 * Registry of node type information fetched from ComfyUI server.
 * Used to resolve slot types for edge coloring and input definitions for editing.
 */
class NodeTypeRegistry {
    // Q3-1（v4.16.5）并发修复：两张表从可变 Map 改为 @Volatile 不可变 Map。
    //
    // 背景：parseObjectInfo 会被两个线程并发调用（磁盘缓存预填的 IO 协程与
    // /object_info 网络回调）。旧实现开头先 clear 再逐个 put 到**同一份**可变表，
    // 两个 parse 交错时互相清空对方刚写入的节点 → 表残缺 → 「UNET 下拉空、
    // VAE/CLIP 有值」（UNET 来源节点只有两三个，丢一个就全空）。
    //
    // 现在的写法：parse 在**局部** map 上构建完整数据，构建完成后一次性替换引用。
    // 读方（getOptionsForField 等 UI 直接调用的方法）拿到的是某一**完整代次**的
    // 快照——要么旧的、要么新的，绝无"半个表"。@Volatile 保证替换后的引用对
    // 读线程立即可见；两张表内容各自不可变，读方无需加锁。
    @Volatile
    private var nodeTypes: Map<String, NodeTypeInfo> = emptyMap()
    @Volatile
    private var nodeDefinitions: Map<String, NodeTypeDefinition> = emptyMap()

    /**
     * [getNodeTypesByCategory] 的结果缓存。
     *
     * 节点浏览器每次重组都会取一次分类表，而重新 `groupBy` 上千个节点定义（每个还带着
     * 完整输入列表）代价不小——节点定义只在 `parseObjectInfo` / `clear` 时变化，缓存到那时失效即可。
     */
    @Volatile
    private var categoryCache: Map<String, List<NodeTypeDefinition>>? = null

    companion object {
        private const val TAG = "NodeTypeRegistry"
    }

    /**
     * Parse /object_info response and populate the registry.
     *
     * Q3-1（v4.16.5）：解析全程在局部 map 上进行，结束时一次性替换两张表引用。
     * 多线程并发调用时各自构建各自的局部表、谁后完成谁生效，表本身永远完整
     * （旧实现先 clear 后逐 put 的共享可变表，并发交错会互相清空节点）。
     */
    fun parseObjectInfo(objectInfoJson: JSONObject) {
        val newTypes = mutableMapOf<String, NodeTypeInfo>()
        val newDefinitions = mutableMapOf<String, NodeTypeDefinition>()

        val keys = objectInfoJson.keys()
        while (keys.hasNext()) {
            val classType = keys.next()
            val nodeInfo = objectInfoJson.optJSONObject(classType) ?: continue

            // Parse input types (for edge coloring)
            val inputTypes = mutableMapOf<String, String>()
            val inputJson = nodeInfo.optJSONObject("input")

            // Parse required inputs
            val requiredJson = inputJson?.optJSONObject("required")
            parseInputTypes(requiredJson, inputTypes)

            // Parse optional inputs
            val optionalJson = inputJson?.optJSONObject("optional")
            parseInputTypes(optionalJson, inputTypes)

            // Parse output types and names
            val outputArray = nodeInfo.optJSONArray("output")
            val outputNameArray = nodeInfo.optJSONArray("output_name")
            val outputTypes = mutableListOf<String>()
            val outputSlots = mutableListOf<OutputSlot>()
            if (outputArray != null) {
                for (i in 0 until outputArray.length()) {
                    val type = outputArray.optString(i, "UNKNOWN")
                    val name = outputNameArray?.optString(i)?.takeIf { it.isNotEmpty() } ?: type
                    outputTypes.add(type)
                    outputSlots.add(OutputSlot(type = type, name = name))
                }
            }

            newTypes[classType] = NodeTypeInfo(
                classType = classType,
                inputTypes = inputTypes,
                outputTypes = outputTypes,
                outputSlots = outputSlots
            )

            // Parse full input definitions (for attribute editing)
            val inputDefinitions = mutableListOf<InputDefinition>()
            parseFullInputDefinitions(requiredJson, isRequired = true, inputDefinitions)
            parseFullInputDefinitions(optionalJson, isRequired = false, inputDefinitions)

            // Parse node-level metadata
            val category = nodeInfo.optString("category", "")
            val displayName = (nodeInfo.opt("display_name") as? String)?.takeIf { it.isNotEmpty() }
            val description = (nodeInfo.opt("description") as? String)?.takeIf { it.isNotEmpty() }
            val deprecated = nodeInfo.optBoolean("deprecated", false)
            val experimental = nodeInfo.optBoolean("experimental", false)

            newDefinitions[classType] = NodeTypeDefinition(
                classType = classType,
                category = category,
                inputs = inputDefinitions,
                outputs = outputSlots,
                displayName = displayName,
                description = description,
                deprecated = deprecated,
                experimental = experimental
            )
        }

        // 原子替换（引用赋值）：读方从此刻起看到的是本次解析的完整快照。
        // categoryCache 随之失效
        nodeTypes = newTypes
        nodeDefinitions = newDefinitions
        categoryCache = null

        DebugLogger.i(TAG, "NodeTypeRegistry populated: ${newDefinitions.size} node types parsed")
    }

    private fun parseInputTypes(inputsJson: JSONObject?, result: MutableMap<String, String>) {
        if (inputsJson == null) return

        val keys = inputsJson.keys()
        while (keys.hasNext()) {
            val inputName = keys.next()
            val inputSpec = inputsJson.opt(inputName)

            // Input format can be:
            // - ["TYPE"] for simple types
            // - [["option1", "option2"], {}] for enums/dropdowns (no type info)
            // - ["TYPE", {"default": ...}] for types with options
            when (inputSpec) {
                is JSONArray -> {
                    if (inputSpec.length() > 0) {
                        val firstElement = inputSpec.opt(0)
                        if (firstElement is String) {
                            // Simple type: ["MODEL"] or ["MODEL", {...}]
                            result[inputName] = firstElement
                        }
                        // If firstElement is JSONArray, it's an enum/dropdown - no type info
                    }
                }
            }
        }
    }

    /**
     * Parse full input definitions with constraints for attribute editing.
     */
    private fun parseFullInputDefinitions(
        inputsJson: JSONObject?,
        isRequired: Boolean,
        result: MutableList<InputDefinition>
    ) {
        if (inputsJson == null) return

        val keys = inputsJson.keys()
        while (keys.hasNext()) {
            val inputName = keys.next()
            val inputSpec = inputsJson.opt(inputName)

            if (inputSpec !is JSONArray || inputSpec.length() == 0) continue

            val firstElement = inputSpec.opt(0)
            val optionsObj = if (inputSpec.length() > 1) inputSpec.optJSONObject(1) else null

            when (firstElement) {
                is String -> {
                    // Check for new COMBO format: ["COMBO", {"options": [...], "multiselect": bool}]
                    if (firstElement == "COMBO" && optionsObj != null) {
                        val optionsArray = optionsObj.optJSONArray("options")
                        if (optionsArray != null) {
                            val options = mutableListOf<String>()
                            for (i in 0 until optionsArray.length()) {
                                val opt = optionsArray.opt(i)
                                if (opt is String) {
                                    options.add(opt)
                                }
                            }
                            val default = optionsObj.opt("default")
                            val forceInput = optionsObj.optBoolean("forceInput", false)
                            val tooltip = (optionsObj.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }
                            val multiselect = optionsObj.optBoolean("multiselect", false)

                            result.add(
                                InputDefinition(
                                    name = inputName,
                                    type = "ENUM",
                                    isRequired = isRequired,
                                    default = default,
                                    options = options,
                                    forceInput = forceInput,
                                    tooltip = tooltip,
                                    multiselect = multiselect
                                )
                            )
                            continue
                        }
                    }

                    // Type-based input: ["INT", {...}] or ["FLOAT", {...}] etc.
                    val inputDef = parseTypedInput(inputName, firstElement, optionsObj, isRequired)
                    result.add(inputDef)
                }
                is JSONArray -> {
                    // Enum/dropdown (old format): [["option1", "option2"], {...}]
                    val options = mutableListOf<String>()
                    for (i in 0 until firstElement.length()) {
                        val opt = firstElement.opt(i)
                        if (opt is String) {
                            options.add(opt)
                        }
                    }
                    val default = optionsObj?.opt("default")
                    val forceInput = optionsObj?.optBoolean("forceInput", false) ?: false
                    val tooltip = (optionsObj?.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }

                    result.add(
                        InputDefinition(
                            name = inputName,
                            type = "ENUM",
                            isRequired = isRequired,
                            default = default,
                            options = options,
                            forceInput = forceInput,
                            tooltip = tooltip
                        )
                    )
                }
            }
        }
    }

    /**
     * Parse a typed input (INT, FLOAT, STRING, BOOLEAN, or connection type).
     */
    private fun parseTypedInput(
        name: String,
        type: String,
        options: JSONObject?,
        isRequired: Boolean
    ): InputDefinition {
        val default = options?.opt("default")
        val min = options?.opt("min") as? Number
        val max = options?.opt("max") as? Number
        val step = options?.opt("step") as? Number
        val multiline = options?.optBoolean("multiline", false) ?: false
        val forceInput = options?.optBoolean("forceInput", false) ?: false
        val tooltip = (options?.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }
        val round = options?.opt("round") as? Number

        return InputDefinition(
            name = name,
            type = type,
            isRequired = isRequired,
            default = default,
            min = min,
            max = max,
            step = step,
            multiline = multiline,
            forceInput = forceInput,
            tooltip = tooltip,
            round = round
        )
    }

    /**
     * Get the output type for a node at a specific output index.
     */
    fun getOutputType(classType: String, outputIndex: Int): String? {
        val info = nodeTypes[classType] ?: return null
        return info.outputTypes.getOrNull(outputIndex)
    }

    /**
     * Get the input type for a specific input on a node.
     */
    fun getInputType(classType: String, inputName: String): String? {
        val info = nodeTypes[classType] ?: return null
        return info.inputTypes[inputName]
    }

    /**
     * Get the full node definition for a class type.
     */
    fun getNodeDefinition(classType: String): NodeTypeDefinition? {
        return nodeDefinitions[classType]
    }

    /**
     * Get the input definition for a specific input on a node type.
     * Convenience method combining getNodeDefinition and finding the input.
     */
    fun getInputDefinition(classType: String, inputName: String): InputDefinition? {
        val definition = nodeDefinitions[classType]
        if (definition == null) {
            DebugLogger.d(TAG, "getInputDefinition: Node type '$classType' not found in registry")
            return null
        }
        val inputDef = definition.inputs.find { it.name == inputName }
        if (inputDef == null) {
            DebugLogger.d(TAG, "getInputDefinition: Input '$inputName' not found on node '$classType'")
        } else {
            DebugLogger.d(TAG, "getInputDefinition: $classType.$inputName -> " +
                    "min=${inputDef.min}, max=${inputDef.max}, step=${inputDef.step}")
        }
        return inputDef
    }

    /**
     * Check if the registry has been populated.
     */
    fun isPopulated(): Boolean = nodeTypes.isNotEmpty()

    /**
     * Get all available node type definitions.
     */
    fun getAllNodeTypes(): List<NodeTypeDefinition> {
        return nodeDefinitions.values.toList()
    }

    /**
     * Get all unique options for a given input field name across all node types.
     * This enables dynamic discovery of model options from any loader node
     * (standard, GGUF, future plugins).
     */
    fun getOptionsForField(fieldName: String): List<String> {
        val allOptions = mutableSetOf<String>()
        nodeDefinitions.values.forEach { nodeDef ->
            nodeDef.inputs
                .filter { it.name == fieldName && it.options != null }
                .forEach { input -> allOptions.addAll(input.options!!) }
        }
        return allOptions.sorted()
    }

    /**
     * Get all unique options for fields matching a prefix (e.g., "clip_name" matches
     * clip_name, clip_name1, clip_name2, etc.). Useful for nodes with multiple
     * similar inputs like DualCLIPLoaderGGUF.
     */
    fun getOptionsForFieldPrefix(prefix: String): List<String> {
        val allOptions = mutableSetOf<String>()
        nodeDefinitions.values.forEach { nodeDef ->
            nodeDef.inputs
                .filter { it.name.startsWith(prefix) && it.options != null }
                .forEach { input -> allOptions.addAll(input.options!!) }
        }
        return allOptions.sorted()
    }

    /**
     * Get options for a specific input on a specific node type.
     * Use this when a generic field name like "model_name" is used by multiple
     * unrelated node types and you need options from a specific one.
     */
    fun getOptionsForNodeInput(classType: String, inputName: String): List<String> {
        return getInputDefinition(classType, inputName)?.options ?: emptyList()
    }

    /**
     * Get node types grouped by category.
     */
    fun getNodeTypesByCategory(): Map<String, List<NodeTypeDefinition>> {
        categoryCache?.let { return it }
        val grouped = nodeDefinitions.values.groupBy { it.category.ifEmpty { "Other" } }
        categoryCache = grouped
        return grouped
    }

    /**
     * Clear the registry.
     */
    fun clear() {
        nodeTypes = emptyMap()
        nodeDefinitions = emptyMap()
        categoryCache = null
    }
}
