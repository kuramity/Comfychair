package sh.hnet.comfychair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.NodeAttributeEdits
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.BypassNodeResolver
import sh.hnet.comfychair.util.LoraInjectionUtils
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.PlaceholderLiterals
import sh.hnet.comfychair.workflow.SaveFormatMaterializer
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.TemplatePatterns
import sh.hnet.comfychair.workflow.TimelineSlotDetector
import sh.hnet.comfychair.workflow.dynamic.ClipGateMaterializer
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.workflow.dynamic.DynamicParamRange
import sh.hnet.comfychair.workflow.dynamic.DynamicParamScanner
import sh.hnet.comfychair.workflow.dynamic.DynamicParamTypeResolver
import sh.hnet.comfychair.workflow.dynamic.DynamicParamValues
import sh.hnet.comfychair.workflow.dynamic.NodeTypeRegistryDefinitionSource
import sh.hnet.comfychair.workflow.dynamic.PeGateMaterializer
import sh.hnet.comfychair.workflow.dynamic.escapeJsonString
import sh.hnet.comfychair.prompt.SkillPromptRepository
import sh.hnet.comfychair.prompt.SkillPromptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream

/**
 * Workflow types supported by ComfyChair
 */
enum class WorkflowType {
    TTI,              // Text-to-Image
    ITI_INPAINTING,   // Image-to-Image Inpainting
    ITI_EDITING,      // Image-to-Image Editing
    TTV,              // Text-to-Video
    ITV,              // Image-to-Video
    TTI_3D,           // Text-to-3D（文生3D：内部先文生图，再转 3D）
    ITI_3D            // Image-to-3D（图生3D：给一张图出 .glb 模型）
}

/**
 * Display utilities for WorkflowType in Compose UI.
 * Provides stable list of types with their string resource IDs.
 */
object WorkflowTypeDisplay {
    /**
     * All workflow types with their display name string resource IDs.
     * Use this instead of creating lists inline to avoid recomposition.
     */
    val allTypes: List<Pair<WorkflowType, Int>> = listOf(
        WorkflowType.TTI to R.string.workflow_section_tti,
        WorkflowType.ITI_INPAINTING to R.string.workflow_section_iti_inpainting,
        WorkflowType.ITI_EDITING to R.string.workflow_section_iti_editing,
        WorkflowType.TTV to R.string.workflow_section_ttv,
        WorkflowType.ITV to R.string.workflow_section_itv,
        // 3D 生成：产物是 .glb 模型文件而不是图片/视频，App 端有专属结果页
        WorkflowType.TTI_3D to R.string.workflow_section_tti_3d,
        WorkflowType.ITI_3D to R.string.workflow_section_iti_3d
    )

    /**
     * Get the display name string resource ID for a workflow type.
     */
    fun getDisplayNameResId(type: WorkflowType): Int {
        return allTypes.first { it.first == type }.second
    }
}

/**
 * Result of workflow validation
 */
sealed class WorkflowValidationResult {
    object Success : WorkflowValidationResult()
    data class InvalidFilename(val message: String) : WorkflowValidationResult()
    data class InvalidJson(val message: String) : WorkflowValidationResult()
    data class MissingPlaceholders(val missing: List<String>) : WorkflowValidationResult()
    data class MissingKeys(val missing: List<String>) : WorkflowValidationResult()
}

/**
 * WorkflowManager - Manages ComfyUI workflow JSON files
 *
 * This singleton handles:
 * - Loading workflow JSON files from res/raw (built-in) and internal storage (user-uploaded)
 * - Extracting workflow names and descriptions
 * - Validating workflows against required placeholders
 * - Replacing template variables with actual values
 * - Providing workflow data for API calls
 */
object WorkflowManager {

    private const val TAG = "Workflow"
    private const val USER_WORKFLOWS_PREFS = "UserWorkflowsPrefs"
    private const val USER_WORKFLOWS_KEY = "user_workflows_json"
    private const val USER_WORKFLOWS_DIR = "user_workflows"

    // Workflow data class
    data class Workflow(
        val id: String,
        val name: String,
        val description: String,
        val jsonContent: String,
        val type: WorkflowType,
        val isBuiltIn: Boolean,
        val defaults: WorkflowDefaults = WorkflowDefaults()
    )

    // Context initialization
    private lateinit var applicationContext: Context
    private var isInitialized = false

    /**
     * Initialize the WorkflowManager with application context.
     * Must be called before first use.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (!isInitialized) {
            applicationContext = context.applicationContext
            seedPrebuiltWorkflows()
            loadAllWorkflows()
            isInitialized = true
            DebugLogger.i(TAG, "WorkflowManager initialized")
        }
    }

    /**
     * Ensure initialized, or initialize lazily with provided context.
     */
    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    // Constants - delegate to WorkflowJsonAnalyzer
    val REQUIRED_PLACEHOLDERS get() = WorkflowJsonAnalyzer.REQUIRED_PLACEHOLDERS
    val REQUIRED_PATTERNS get() = WorkflowJsonAnalyzer.REQUIRED_PATTERNS
    val PREFIX_TO_TYPE get() = WorkflowJsonAnalyzer.PREFIX_TO_TYPE

    // Available workflows loaded from res/raw and user storage
    private val workflows = mutableListOf<Workflow>()
    private val workflowValuesStorage by lazy { WorkflowValuesStorage(applicationContext) }

    // Workflow change notification - increments when workflows are added/updated/deleted
    // ViewModels can observe this to refresh their workflow lists
    private val _workflowsVersion = MutableStateFlow(0L)
    val workflowsVersion: StateFlow<Long> = _workflowsVersion.asStateFlow()

    /**
     * Load all workflows (built-in and user-uploaded)
     */
    private fun loadAllWorkflows() {
        workflows.clear()
        loadBuiltInWorkflows()
        loadUserWorkflows()
    }

    /**
     * Reload all workflows (call after adding/deleting user workflows)
     */
    fun reloadWorkflows() {
        loadAllWorkflows()
    }

    /**
     * Sync prebuilt workflows from assets/prebuilt_workflows into user storage.
     * Runs on every launch: content-compares assets with stored prebuilt_* files,
     * overwrites only when different (idempotent). Fixes, type corrections and
     * model defaults added in new APK versions always reach installed apps.
     */
    private fun seedPrebuiltWorkflows() {
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        try {
            val assetDir = "prebuilt_workflows"
            val assetNames = applicationContext.assets.list(assetDir) ?: return
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
            if (!dir.exists()) dir.mkdirs()
            val existingMetadata = prefs.getString(USER_WORKFLOWS_KEY, null)
            val metadataArray = if (existingMetadata != null) JSONArray(existingMetadata) else JSONArray()
            val indexByFilename = mutableMapOf<String, Int>()
            for (i in 0 until metadataArray.length()) {
                val item = metadataArray.optJSONObject(i) ?: continue
                val fn = item.optString("filename", "")
                if (fn.startsWith("prebuilt_")) indexByFilename[fn] = i
            }
            var synced = 0
            // 清理：资产中已移除的预置条目（如被删除的预置流），从 metadata 与磁盘移除
            val assetSet = assetNames.filter { it.endsWith(".json") && it.startsWith("prebuilt_") }.toSet()
            var removed = 0
            var cleanupIdx = 0
            while (cleanupIdx < metadataArray.length()) {
                val item = metadataArray.optJSONObject(cleanupIdx)
                if (item == null) {
                    cleanupIdx++
                    continue
                }
                val fn = item.optString("filename", "")
                if (fn.startsWith("prebuilt_") && fn !in assetSet) {
                    metadataArray.remove(cleanupIdx)
                    File(dir, fn).delete()
                    removed++
                    continue
                }
                cleanupIdx++
            }
            for (assetName in assetNames.sorted()) {
                if (!assetName.endsWith(".json")) continue
                val jsonContent = applicationContext.assets.open("$assetDir/$assetName")
                    .bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)
                // 预置文件自带显式分类（workflow_type），比自动检测更可靠
                val explicitType = jsonObject.optString("workflow_type", "")
                val type = (
                    if (explicitType.isNotEmpty()) {
                        try {
                            WorkflowType.valueOf(explicitType)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                ) ?: detectWorkflowType(jsonContent) ?: continue
                val name = jsonObject.optString("name", assetName.removeSuffix(".json"))
                val description = jsonObject.optString("description", "")
                val file = File(dir, assetName)
                if (file.exists() && file.readText() == jsonContent) continue
                file.writeText(jsonContent)
                val existingIndex = indexByFilename[assetName]
                val newMetadata = JSONObject().apply {
                    if (existingIndex != null) {
                        put("id", metadataArray.getJSONObject(existingIndex).optString("id"))
                    } else {
                        put("id", UuidUtils.generateRandomId())
                    }
                    put("name", name)
                    put("description", description)
                    put("type", type.name)
                    put("filename", assetName)
                }
                if (existingIndex != null) {
                    metadataArray.put(existingIndex, newMetadata)
                } else {
                    metadataArray.put(newMetadata)
                }
                synced++
            }
            if (synced > 0 || removed > 0) {
                prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()
            }
            DebugLogger.i(TAG, "Prebuilt sync: $synced updated/added")
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Prebuilt sync failed: ${e.message}")
        }
    }

    /**
     * Load built-in workflow JSON files from res/raw
     * Auto-discovers all workflow resources by scanning R.raw fields that match workflow prefixes
     */
    private fun loadBuiltInWorkflows() {
        // Get all workflow resource IDs by scanning R.raw fields
        val workflowResources = discoverWorkflowResources()

        for (resId in workflowResources) {
            try {
                val inputStream: InputStream = applicationContext.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val resourceName = applicationContext.resources.getResourceEntryName(resId)
                val type = parseWorkflowType(resourceName)
                if (type == null) {
                    // Old-format resource names (tti_checkpoint_*, tti_unet_*, etc.) are no longer supported
                    DebugLogger.w(TAG, "Skipping built-in workflow '$resourceName': unrecognized prefix")
                    continue
                }
                val defaults = WorkflowDefaults.fromJson(jsonObject.optJSONObject("defaults"))

                // Generate deterministic UUID from resource name
                val id = UuidUtils.generateDeterministicId(resourceName)

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = true, defaults))
            } catch (e: Exception) {
                // Failed to load workflow
            }
        }
    }

    /**
     * Discover all workflow resources in R.raw using reflection
     * Looks for fields matching workflow prefixes (tti_, iti_inpainting_, iti_editing_, ttv_, itv_)
     */
    private fun discoverWorkflowResources(): List<Int> {
        val resources = mutableListOf<Int>()
        val prefixes = PREFIX_TO_TYPE.keys

        try {
            // Use reflection to scan R.raw class fields
            val rawClass = R.raw::class.java
            for (field in rawClass.fields) {
                val fieldName = field.name
                // Check if field name matches any workflow prefix
                if (prefixes.any { fieldName.startsWith(it) }) {
                    try {
                        val resId = field.getInt(null)
                        resources.add(resId)
                    } catch (e: Exception) {
                        // Skip fields that can't be read
                    }
                }
            }
        } catch (e: Exception) {
            // Reflection failed, return empty list
        }

        return resources
    }

    /**
     * Load user-uploaded workflows from internal storage
     */
    private fun loadUserWorkflows() {
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return

        try {
            val metadataArray = JSONArray(metadataJson)
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                val id = metadata.getString("id")
                val name = metadata.getString("name")
                val description = metadata.optString("description", "")
                val typeStr = metadata.getString("type")
                val filename = metadata.getString("filename")

                val type = try {
                    WorkflowType.valueOf(typeStr)
                } catch (e: Exception) {
                    // Old-format workflow types (TTI_CHECKPOINT, TTI_UNET, ITI_CHECKPOINT, ITI_UNET, ITE_UNET, TTV_UNET, ITV_UNET)
                    // are no longer supported. These workflows will be silently skipped.
                    DebugLogger.w(TAG, "Skipping workflow '$name': unsupported type '$typeStr' (old format)")
                    continue
                }

                val file = File(dir, filename)
                if (!file.exists()) continue

                val jsonContent = file.readText()
                val jsonObject = try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    null
                }
                val defaults = WorkflowDefaults.fromJson(jsonObject?.optJSONObject("defaults"))

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = false, defaults))
            }
        } catch (e: Exception) {
            // Failed to load user workflows
        }
    }

    // Workflow JSON analysis - delegates to WorkflowJsonAnalyzer

    /** Parse workflow type from filename prefix */
    fun parseWorkflowType(filename: String): WorkflowType? =
        WorkflowJsonAnalyzer.parseWorkflowType(filename)

    /** Detect workflow type by analyzing JSON content */
    fun detectWorkflowType(jsonContent: String): WorkflowType? =
        WorkflowJsonAnalyzer.detectWorkflowType(jsonContent)

    /** Validate workflow JSON against type requirements */
    fun validateWorkflow(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflow(jsonContent, type)

    /** Validate workflow JSON by checking for required input keys */
    fun validateWorkflowKeys(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflowKeys(jsonContent, type)

    /** Extract all class_type values from workflow JSON */
    fun extractClassTypes(jsonContent: String): Result<Set<String>> =
        WorkflowJsonAnalyzer.extractClassTypes(jsonContent)

    /** Validate workflow nodes against available server nodes */
    fun validateNodesAgainstServer(
        workflowClassTypes: Set<String>,
        availableNodes: Set<String>
    ): List<String> =
        WorkflowJsonAnalyzer.validateNodesAgainstServer(workflowClassTypes, availableNodes)

    /**
     * Check if workflow name is already taken
     * @param name The name to check
     * @param excludeWorkflowId Optional workflow ID to exclude from the check (used when editing)
     */
    fun isWorkflowNameTaken(name: String, excludeWorkflowId: String? = null): Boolean {
        return workflows.any { workflow ->
            workflow.name.equals(name, ignoreCase = true) &&
            (excludeWorkflowId == null || workflow.id != excludeWorkflowId)
        }
    }

    /**
     * Generate a unique name for duplicating a workflow.
     * Returns "Name (1)", "Name (2)", etc. until a unique name is found.
     * The original name is kept intact, e.g. "My Workflow (2)" becomes "My Workflow (2) (1)".
     */
    fun generateUniqueDuplicateName(originalName: String): String {
        val existingNames = workflows.map { it.name }.toSet()

        // Find all numbers already used with this exact base name
        val numberPattern = Regex("""^\Q$originalName\E \((\d+)\)$""", RegexOption.IGNORE_CASE)
        val usedNumbers = existingNames.mapNotNull { name ->
            numberPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()

        // Find the next available number starting from 1
        var nextNumber = 1
        while (nextNumber in usedNumbers) {
            nextNumber++
        }

        return "$originalName ($nextNumber)"
    }

    /**
     * Duplicate a workflow (built-in or user) as a new user workflow.
     * The duplicate is always a user workflow that can be edited.
     */
    fun duplicateWorkflow(
        sourceWorkflowId: String,
        newName: String,
        newDescription: String
    ): Result<Workflow> {
        val sourceWorkflow = workflows.find { it.id == sourceWorkflowId }
            ?: return Result.failure(Exception("Source workflow not found"))

        // Validate new name
        val nameError = validateWorkflowName(newName)
        if (nameError != null) {
            return Result.failure(Exception(nameError))
        }

        // Check for duplicate name
        if (isWorkflowNameTaken(newName)) {
            return Result.failure(Exception("A workflow with this name already exists"))
        }

        // Generate filename for the new workflow
        val filename = generateFilename(sourceWorkflow.type, newName)

        // Parse and update the JSON with new name and description
        val json = try {
            JSONObject(sourceWorkflow.jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse source workflow"))
        }

        // Update metadata in the JSON
        if (json.has("name")) {
            json.put("name", newName)
        }
        if (json.has("description")) {
            json.put("description", newDescription)
        }

        val updatedJsonContent = json.toString(2)

        // Save the new workflow file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(updatedJsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Create the new workflow entry with a random UUID
        val newId = UuidUtils.generateRandomId()

        // Clean placeholders from copied defaults - they should be null so ViewModels use proper fallbacks
        val cleanedDefaults = sourceWorkflow.defaults.copy(
            samplerName = sourceWorkflow.defaults.samplerName?.takeIf { !isPlaceholder(it) },
            scheduler = sourceWorkflow.defaults.scheduler?.takeIf { !isPlaceholder(it) },
            negativePrompt = sourceWorkflow.defaults.negativePrompt?.takeIf { !isPlaceholder(it) }
        )

        val newWorkflow = Workflow(
            id = newId,
            name = newName,
            description = newDescription,
            jsonContent = updatedJsonContent,
            type = sourceWorkflow.type,
            isBuiltIn = false,
            defaults = cleanedDefaults
        )

        // Save metadata
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", newId)
            put("filename", filename)
            put("name", newName)
            put("description", newDescription)
            put("type", sourceWorkflow.type.name)
            put("defaults", WorkflowDefaults.toJson(newWorkflow.defaults))
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        // Add to in-memory list
        workflows.add(newWorkflow)

        DebugLogger.i(TAG, "Duplicated workflow: ${sourceWorkflow.name} -> $newName (id: $newId)")

        _workflowsVersion.value++
        return Result.success(newWorkflow)
    }

    /**
     * Export a workflow to ComfyUI format (just the nodes, without our wrapper metadata).
     * This is the inverse of importing - strips name, description, defaults, fieldMappings
     * and returns only the raw ComfyUI workflow JSON.
     *
     * @return Result containing the JSON string in ComfyUI format, or error
     */
    fun exportWorkflowToComfyUIFormat(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        val json = try {
            JSONObject(workflow.jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse workflow JSON"))
        }

        // Extract the nodes object (ComfyUI format is just the nodes)
        val nodesJson = if (json.has("nodes") && json.optJSONObject("nodes") != null) {
            json.getJSONObject("nodes")
        } else {
            // Already in raw format
            json
        }

        // Replace any placeholders ({{placeholder}}) with default values if available
        val defaults = workflow.defaults
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys()) {
                val value = inputs.opt(inputKey)
                if (value is String && value.startsWith("{{") && value.endsWith("}}")) {
                    // This is a placeholder, try to replace with default
                    val placeholderName = value.substring(2, value.length - 2)
                    val defaultValue = getDefaultValueForPlaceholder(placeholderName, defaults)
                    if (defaultValue != null) {
                        inputs.put(inputKey, defaultValue)
                    }
                }
            }
        }

        DebugLogger.i(TAG, "Exported workflow to ComfyUI format: ${workflow.name}")

        return Result.success(nodesJson.toString(2))
    }

    /**
     * Export a workflow in internal format (preserves placeholders, groups, notes, etc.).
     * This exports the complete workflow JSON as stored internally.
     *
     * @return Result containing the JSON string in internal format, or error
     */
    fun exportWorkflowInternal(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        return try {
            // Parse and re-format with indentation for readability
            val json = JSONObject(workflow.jsonContent)
            DebugLogger.i(TAG, "Exported workflow in internal format: ${workflow.name}")
            Result.success(json.toString(2))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse workflow JSON"))
        }
    }

    /**
     * Get default value for a placeholder name.
     */
    private fun getDefaultValueForPlaceholder(placeholder: String, defaults: WorkflowDefaults): Any? {
        return when (placeholder) {
            "width" -> defaults.width
            "height" -> defaults.height
            "steps" -> defaults.steps
            "cfg" -> defaults.cfg
            "sampler_name", "sampler" -> defaults.samplerName
            "scheduler" -> defaults.scheduler
            "denoise" -> 1.0  // Common default
            // 正面提示词的默认值取自 defaults（prebuilt 资产里本来就带），与下面一行负面完全对称。
            // 以前这里硬编码空串，等于把资产里的默认正面词丢掉，切到该工作流只能看到空白输入框。
            "positive_prompt", "positive_text" -> defaults.positivePrompt ?: ""
            "negative_prompt", "negative_text" -> defaults.negativePrompt ?: ""
            "seed" -> -1  // Random seed
            "batch_size" -> 1  // Default batch size
            "megapixels" -> defaults.megapixels
            "aspect_ratio" -> defaults.aspectRatio
            "speed_mode" -> defaults.speedMode
            "lora_strength" -> defaults.loraStrength
            "length" -> defaults.length
            "frame_rate", "fps" -> defaults.frameRate
            else -> null
        }
    }

    /**
     * Get model options for a specific field based on the workflow's actual node type.
     * Returns node-specific options (e.g., only GGUF files for UnetLoaderGGUF)
     * or null if node type can't be determined (caller should fall back to global options).
     */
    fun getNodeSpecificOptionsForField(workflowId: String, fieldKey: String): List<String>? {
        val workflow = getWorkflowById(workflowId) ?: return null
        val json = try {
            JSONObject(workflow.jsonContent)
        } catch (e: Exception) {
            return null
        }
        val nodesJson = if (json.has("nodes")) json.optJSONObject("nodes") ?: json else json

        // Find the placeholder pattern for this field
        val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
        val placeholder = "{{$placeholderName}}"

        // Search nodes for the one containing this placeholder
        val nodeIds = nodesJson.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val classType = node.optString("class_type")

            // Check if any input has our placeholder
            val inputKeys = inputs.keys()
            while (inputKeys.hasNext()) {
                val inputKey = inputKeys.next()
                val value = inputs.optString(inputKey, "")
                if (value == placeholder) {
                    // Found the node! Get its options from NodeTypeRegistry
                    val nodeDefinition = ConnectionManager.nodeTypeRegistry.getNodeDefinition(classType)
                    return nodeDefinition?.inputs
                        ?.find { it.name == inputKey }
                        ?.options
                }
            }
        }
        return null
    }

    /**
     * Generate a sanitized filename for export (without type prefix).
     */
    fun generateExportFilename(workflowName: String): String {
        val sanitized = workflowName
            .replace(Regex("[^a-zA-Z0-9 _\\-]"), "")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return "$sanitized.json"
    }

    /**
     * Generate filename from workflow type and name
     */
    fun generateFilename(type: WorkflowType, name: String): String {
        val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_"
        val sanitizedName = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "${prefix}${sanitizedName}.json"
    }

    /**
     * Check if a filename already exists in user workflows
     */
    fun isFilenameExists(filename: String): Boolean {
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        return File(dir, filename).exists()
    }

    /**
     * Validate workflow name format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String): String? {
        return ValidationUtils.validateWorkflowName(name, applicationContext)
    }

    /**
     * Validate workflow description format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowDescription(description: String): String? {
        return ValidationUtils.validateWorkflowDescription(description, applicationContext)
    }

    /**
     * 把字段映射落成工作流里的 `{{占位符}}`。
     *
     * 注意这里**只对字面量输入生效**是调用方的责任：本函数按 [fieldMappings] 里的 (节点, 输入键)
     * 无条件写入。谁被选进 [fieldMappings] 由 `FieldMappingAnalyzer.autoSelectIndex` 决定——
     * 那里保证「已经映射好的工作流重新保存时，每个字段只会选中持有自己占位符的那个节点」，
     * 所以不会出现把占位符写进不相干节点、顶掉它原有连线的情况（Task 9 验收 8(b) 的回归点）。
     */
    internal fun applyFieldMappings(
        nodesJson: JSONObject,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ) {
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    // Convert input key to proper placeholder name
                    // e.g., "text" -> "positive_prompt", "ckpt_name" -> "checkpoint"
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }
    }

    /**
     * 清掉「陈旧」与「未映射」的占位符。两个保存入口（新增 / 更新）共用。
     *
     * - 陈旧（Case 1）：某节点还持有**已映射**字段的 `{{占位符}}`，但它已经不是该字段的目标节点
     *   （用户重选过节点，或目标节点当初就没选对）。若留着，`replaceCommonPlaceholders()` 的全局
     *   字符串替换会把值塞进不相干的节点（例如 `{{latent_upscale_model}}` 塞进
     *   `UltralyticsDetectorProvider`）导致服务器 400。
     * - 未映射（Case 2）：节点持有某个**没有任何映射**的可选字段的占位符。
     *
     * **处理方式一律是删掉该输入键，不是写成空串。** 空串能通过 ComfyUI 的 `/prompt` 校验
     * （`LoadImage.image` 只要是个字符串就算数），却会在**执行期**炸掉——实测 `image=""` 时
     * ComfyUI 把空串当目录去打开，抛 `[Errno 13] Permission denied: '<ComfyUI>/input'`，
     * App 只弹一句 "Generation failed"，用户看不出是哪个节点、什么原因。
     * 删掉输入键则立刻变成一次**缺必填输入的校验报错**，直接指向具体节点，才是「响亮的失败」。
     *
     * 只处理**字面量**占位符：连线（`["282",0]`）经 `optString` 取出后不匹配 `{{...}}` 正则，天然不受影响。
     */
    internal fun clearStaleAndUnmappedPlaceholders(
        nodesJson: JSONObject,
        placeholderToTargetNodeId: Map<String, String>,
        unmappedOptionalKeys: Set<String>
    ) {
        val placeholderRegex = TemplatePatterns.PLACEHOLDER

        for (nodeId in nodesJson.keys().asSequence().toList()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys().asSequence().toList()) {
                val value = inputs.optString(inputKey, "")
                val placeholderMatch = placeholderRegex.find(value) ?: continue
                val placeholderName = placeholderMatch.groupValues[1]

                // Case 1: placeholder belongs to a mapped field but this is not the target node
                val targetNodeId = placeholderToTargetNodeId[placeholderName]
                if (targetNodeId != null && nodeId != targetNodeId) {
                    inputs.remove(inputKey)
                    continue
                }

                // Case 2: placeholder belongs to an unmapped optional field
                val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                if (placeholderName in unmappedOptionalKeys || fieldKey in unmappedOptionalKeys) {
                    inputs.remove(inputKey)
                }
            }
        }
    }

    /**
     * Add a user workflow with explicit type and field mappings
     * This is used for the new upload flow where the user selects the type
     */
    fun addUserWorkflowWithMapping(
        name: String,
        description: String,
        jsonContent: String,
        type: WorkflowType,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ): Result<Workflow> {
        // Parse JSON
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    // Only capture numeric and string values as defaults (not model names, but include prompts and params)
                    // Skip placeholder strings - they should be replaced with actual defaults by ViewModels
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate",
                            // 提示词的白名单：正负两条都要在，否则「上传新工作流」时
                            // 节点里原本写好的默认提示词会被丢掉（正面正是这么丢了很久的）
                            "positive_text", "negative_text"
                        ))) {
                        if (!isPlaceholder(originalValue)) {
                            extractedDefaults[fieldKey] = originalValue
                        }
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        applyFieldMappings(nodesJson, fieldMappings)

        // Build reverse map: placeholder name -> target node ID for all mapped fields
        val placeholderToTargetNodeId = fieldMappings.entries.associate { (fieldKey, mapping) ->
            TemplateKeyRegistry.getPlaceholderForKey(fieldKey) to mapping.first
        }

        // Remove stale and unmapped placeholders in a single pass (见 clearStaleAndUnmappedPlaceholders)
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys

        clearStaleAndUnmappedPlaceholders(nodesJson, placeholderToTargetNodeId, unmappedOptionalKeys)

        // Create defaults object from extracted values
        // Field visibility is determined by placeholder detection, not by has* flags
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            positivePrompt = extractedDefaults["positive_text"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt()
        )

        // Create wrapped JSON with metadata and defaults
        val finalJson = JSONObject().apply {
            put("name", name)
            put("description", description)
            // Add defaults if any were extracted
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            // Include groups if present in original JSON
            if (isWrapped && json.has("groups")) {
                put("groups", json.getJSONArray("groups"))
            }
            // Include notes if present in original JSON
            if (isWrapped && json.has("notes")) {
                put("notes", json.getJSONArray("notes"))
            }
            // Include manual_placement if present in original JSON
            if (isWrapped && json.has("manual_placement")) {
                put("manual_placement", json.getBoolean("manual_placement"))
            }
            // Store field mappings for reference
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Generate filename
        val filename = generateFilename(type, name)

        // Check for duplicate filename
        if (isFilenameExists(filename)) {
            return Result.failure(Exception("Workflow with this name already exists"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata with UUID
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", filename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson.toString(2), type, isBuiltIn = false, defaults)
        workflows.add(workflow)

        _workflowsVersion.value++
        return Result.success(workflow)
    }

    /**
     * Update an existing user workflow's JSON structure with new field mappings.
     * Preserves the workflow's name, description, and type.
     * @return Result with the updated Workflow or error message
     */
    fun updateUserWorkflowWithMapping(
        workflowId: String,
        jsonContent: String,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ): Result<Workflow> {
        // Find existing workflow
        val existingWorkflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        if (existingWorkflow.isBuiltIn) {
            return Result.failure(Exception("Cannot edit built-in workflows"))
        }

        // Parse JSON
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null
        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    // Skip placeholder strings - they should be replaced with actual defaults by ViewModels
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate",
                            // 提示词的白名单：正负两条都要在，否则「上传新工作流」时
                            // 节点里原本写好的默认提示词会被丢掉（正面正是这么丢了很久的）
                            "positive_text", "negative_text"
                        ))) {
                        if (!isPlaceholder(originalValue)) {
                            extractedDefaults[fieldKey] = originalValue
                        }
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        applyFieldMappings(nodesJson, fieldMappings)

        // Build reverse map: placeholder name -> target node ID for all mapped fields
        val placeholderToTargetNodeId = fieldMappings.entries.associate { (fieldKey, mapping) ->
            TemplateKeyRegistry.getPlaceholderForKey(fieldKey) to mapping.first
        }

        // Remove stale and unmapped placeholders in a single pass (见 clearStaleAndUnmappedPlaceholders)
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(existingWorkflow.type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys

        clearStaleAndUnmappedPlaceholders(nodesJson, placeholderToTargetNodeId, unmappedOptionalKeys)

        // Create defaults object from extracted values
        // Field visibility is determined by placeholder detection, not by has* flags
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            positivePrompt = extractedDefaults["positive_text"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt()
        )

        // Create wrapped JSON with original metadata preserved
        val finalJson = JSONObject().apply {
            put("name", existingWorkflow.name)
            put("description", existingWorkflow.description)
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            // Include groups if present in original JSON
            if (isWrapped && json.has("groups")) {
                put("groups", json.getJSONArray("groups"))
            }
            // Include notes if present in original JSON
            if (isWrapped && json.has("notes")) {
                put("notes", json.getJSONArray("notes"))
            }
            // Include manual_placement if present in original JSON
            if (isWrapped && json.has("manual_placement")) {
                put("manual_placement", json.getBoolean("manual_placement"))
            }
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Get filename from preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null)
            ?: return Result.failure(Exception("Workflow metadata not found"))

        var filename: String? = null
        val metadataArray = JSONArray(metadataJson)
        for (i in 0 until metadataArray.length()) {
            val metadata = metadataArray.getJSONObject(i)
            if (metadata.getString("id") == workflowId) {
                filename = metadata.getString("filename")
                break
            }
        }

        if (filename == null) {
            return Result.failure(Exception("Workflow file not found"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save: ${e.message}"))
        }

        // Update in-memory list
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex >= 0) {
            val updatedWorkflow = existingWorkflow.copy(
                jsonContent = finalJson.toString(2),
                defaults = defaults
            )
            workflows[workflowIndex] = updatedWorkflow
            _workflowsVersion.value++
            return Result.success(updatedWorkflow)
        }

        return Result.failure(Exception("Failed to update workflow list"))
    }

    /**
     * Add a user workflow
     * Auto-detects workflow type from content and wraps raw ComfyUI workflows if needed
     * @return Result with the created Workflow or error message
     */
    fun addUserWorkflow(
        filename: String,
        name: String,
        description: String,
        jsonContent: String
    ): Result<Workflow> {
        // Parse JSON to check format
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message ?: ""}"))
        }

        // Check if already wrapped (has "nodes" object at top level)
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        // Auto-detect type from content
        val type = detectWorkflowType(jsonContent)
            ?: return Result.failure(Exception("Cannot determine workflow type"))

        // Wrap if needed
        val finalJson = if (isWrapped) {
            jsonContent
        } else {
            // Wrap raw workflow: {"name": "...", "description": "...", "nodes": {...}}
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("nodes", json)
            }.toString(2)
        }

        // Validate workflow - first try placeholder-based validation (for our wrapped format),
        // then try key-based validation (for raw ComfyUI workflows)
        val placeholderValidation = validateWorkflow(finalJson, type)
        val keyValidation = if (placeholderValidation !is WorkflowValidationResult.Success) {
            validateWorkflowKeys(finalJson, type)
        } else {
            placeholderValidation
        }

        // Use key validation result if placeholder validation failed
        val validationResult = if (placeholderValidation is WorkflowValidationResult.Success) {
            placeholderValidation
        } else {
            keyValidation
        }

        if (validationResult !is WorkflowValidationResult.Success) {
            val errorMessage = when (validationResult) {
                is WorkflowValidationResult.InvalidJson -> validationResult.message
                is WorkflowValidationResult.MissingPlaceholders ->
                    "Missing placeholders: ${validationResult.missing.joinToString(", ")}"
                is WorkflowValidationResult.MissingKeys ->
                    "Missing inputs: ${validationResult.missing.joinToString(", ")}"
                else -> "Unknown error"
            }
            return Result.failure(Exception(errorMessage))
        }

        // Generate filename with correct prefix if not already prefixed
        val prefixedFilename = if (parseWorkflowType(filename) != null) {
            filename
        } else {
            // Add prefix based on detected type
            val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_"
            prefix + filename.substringBeforeLast(".") + ".json"
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, prefixedFilename)
        try {
            file.writeText(finalJson)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", prefixedFilename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson, type, isBuiltIn = false)
        workflows.add(workflow)

        _workflowsVersion.value++
        return Result.success(workflow)
    }

    /**
     * Delete a user workflow
     */
    fun deleteUserWorkflow(workflowId: String): Boolean {
        val workflow = workflows.find { it.id == workflowId }
        if (workflow == null || workflow.isBuiltIn) return false

        // Remove from list
        workflows.removeAll { it.id == workflowId }

        // Remove file
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)
            var filename: String? = null
            val newArray = JSONArray()

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    filename = metadata.getString("filename")
                } else {
                    newArray.put(metadata)
                }
            }

            // Delete file
            filename?.let {
                val file = File(applicationContext.filesDir, "$USER_WORKFLOWS_DIR/$it")
                file.delete()
            }

            // Update preferences
            prefs.edit().putString(USER_WORKFLOWS_KEY, newArray.toString()).apply()

            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Clear all user-uploaded workflows
     */
    fun clearAllUserWorkflows(): Boolean {
        try {
            // Remove all user workflows from the list
            workflows.removeAll { !it.isBuiltIn }

            // Delete all files in user workflows directory
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            // Clear preferences
            val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(USER_WORKFLOWS_KEY).apply()

            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Update user workflow metadata (name and description)
     */
    fun updateUserWorkflowMetadata(workflowId: String, name: String, description: String): Boolean {
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex == -1) return false

        val workflow = workflows[workflowIndex]
        if (workflow.isBuiltIn) return false

        // Update in list
        workflows[workflowIndex] = workflow.copy(name = name, description = description)

        // Update in preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    metadata.put("name", name)
                    metadata.put("description", description)
                    break
                }
            }

            prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()
            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get all workflows
     */
    fun getAllWorkflows(): List<Workflow> = workflows.toList()

    /**
     * Get workflows by type
     */
    fun getWorkflowsByType(type: WorkflowType): List<Workflow> {
        return workflows.filter { it.type == type }.sortedBy { it.name.lowercase() }
    }

    /**
     * Check if a workflow can be deleted (not built-in)
     */
    fun canDeleteWorkflow(workflowId: String): Boolean {
        return workflows.find { it.id == workflowId }?.isBuiltIn == false
    }

    /**
     * Get list of text-to-image workflow names for dropdown
     */
    fun getTtiWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTI }.map { it.name }
    }

    /**
     * Get list of Image-to-image inpainting workflow names for dropdown
     */
    fun getItiInpaintingWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_INPAINTING }.map { it.name }
    }

    /**
     * Get list of Image-to-image editing workflow names for dropdown
     */
    fun getItiEditingWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_EDITING }.map { it.name }
    }

    /**
     * Get list of text-to-video workflow names for dropdown
     */
    fun getTtvWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTV }.map { it.name }
    }

    /**
     * Get list of image-to-video workflow names for dropdown
     */
    fun getItvWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITV }.map { it.name }
    }

    /**
     * Get workflow by name
     */
    fun getWorkflowByName(name: String): Workflow? {
        return workflows.find { it.name == name }
    }

    /**
     * Get workflow by ID
     */
    fun getWorkflowById(id: String): Workflow? {
        return workflows.find { it.id == id }
    }

    /**
     * Get workflow defaults by workflow name
     */
    fun getWorkflowDefaults(workflowName: String): WorkflowDefaults? {
        return getWorkflowByName(workflowName)?.defaults
    }

    /**
     * Get workflow defaults by workflow ID
     */
    fun getWorkflowDefaultsById(workflowId: String): WorkflowDefaults? {
        return getWorkflowById(workflowId)?.defaults
    }

    /**
     * Get all placeholders (template variables) used in a workflow.
     * This scans the workflow JSON for {{placeholder}} patterns and returns
     * the set of placeholder names (without the braces).
     *
     * @param workflowId The workflow ID to analyze
     * @return Set of placeholder names found in the workflow, or empty set if workflow not found
     */
    fun getWorkflowPlaceholders(workflowId: String): Set<String> {
        val workflow = getWorkflowById(workflowId) ?: return emptySet()
        return extractPlaceholdersFromJson(workflow.jsonContent)
    }

    /**
     * Get all placeholders (template variables) used in a workflow by name.
     *
     * @param workflowName The workflow name to analyze
     * @return Set of placeholder names found in the workflow, or empty set if workflow not found
     */
    fun getWorkflowPlaceholdersByName(workflowName: String): Set<String> {
        val workflow = getWorkflowByName(workflowName) ?: return emptySet()
        return extractPlaceholdersFromJson(workflow.jsonContent)
    }

    /**
     * Extract all placeholders from a workflow JSON string.
     */
    private fun extractPlaceholdersFromJson(jsonContent: String): Set<String> {
        return TemplatePatterns.PLACEHOLDER.findAll(jsonContent)
            .map { it.groupValues[1] }
            .toSet()
    }

    // ==================================================================
    // 通用节点参数引擎（动态参数）
    //
    // 机制：工作流里把某个节点输入的值写成 {{占位符名}}，App 扫描后按该输入的
    // /object_info 真实类型渲染控件 —— 暴露插件节点参数无需改 App 代码。
    // 只接管「未识别」的占位符（清单见 TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS）。
    // 设计文档：docs/superpowers/specs/2026-09-13-dynamic-node-params-design.md
    // ==================================================================

    /**
     * 扫描 + 类型推导：工作流里所有「未识别」占位符及其控件描述。
     * UI 用 [DynamicParam.control] 渲染控件；替换用同一份清单。
     *
     * @return 无动态参数时返回空列表
     */
    fun getDynamicParams(workflowId: String): List<DynamicParam> {
        val workflow = getWorkflowById(workflowId) ?: return emptyList()
        val refs = DynamicParamScanner.scan(workflow.jsonContent)
        if (refs.isEmpty()) return emptyList()
        val source = NodeTypeRegistryDefinitionSource(ConnectionManager.nodeTypeRegistry)
        return refs.map { DynamicParamTypeResolver.resolve(it, source) }
    }

    /**
     * 工作流 JSON 里 `defaults` 那个对象（以**占位符名**为键的原始值）。
     *
     * 动态参数的默认值就落在这里，例如 `"defaults": {"my_steps": 30}`。
     * 不能用 [getWorkflowDefaultsById]：那是固定 schema，未知键会被丢掉。
     */
    fun getRawDefaultsObject(workflowId: String): JSONObject? {
        val workflow = getWorkflowById(workflowId) ?: return null
        return try {
            JSONObject(workflow.jsonContent).optJSONObject("defaults")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按取值顺序（已有保存值 → defaults.<占位符名> → 节点定义的 default → 空）
     * 得到动态参数的当前值表。
     *
     * UI 加载工作流时调用；返回值即配置面板里各控件的初值。
     */
    fun resolveDynamicParamValues(workflowId: String, savedValues: Map<String, String>): Map<String, String> {
        return loadDynamicParams(workflowId, savedValues).values
    }

    /** 动态参数的一次性快照：[params] 是控件清单，[values] 是按取值顺序算好的当前值 */
    data class DynamicParamsSnapshot(
        val params: List<DynamicParam> = emptyList(),
        val values: Map<String, String> = emptyMap()
    )

    /**
     * 控件清单 + 当前值**一次算好**（加载工作流时、以及注册表就绪后重算时都走这里）。
     *
     * 分为两步是因为 [getDynamicParams] 要扫一遍工作流并逐个输入查注册表：
     * 分别调用会白算一遍（[resolveDynamicParamValues] 内部又要清单）。
     *
     * @param savedValues 用户已保存的值；**重算时传当前 UiState 里的值**即可保留用户已输入的内容
     *                    （空串在 [DynamicParamValues.resolveValue] 里视为「没有」，会回退到默认值链）
     */
    fun loadDynamicParams(workflowId: String, savedValues: Map<String, String>): DynamicParamsSnapshot {
        val params = getDynamicParams(workflowId)
        if (params.isEmpty()) return DynamicParamsSnapshot()
        val values = DynamicParamValues.resolveAll(params, savedValues, getRawDefaultsObject(workflowId))
            .associate { it.param.placeholder to it.value }
        return DynamicParamsSnapshot(params = params, values = values)
    }

    /**
     * 统一替换工作流里所有「未识别」的 `{{占位符}}`。
     *
     * **必须在 prepare 流程的最后一步调用**（放在既有 `replace*` / 节点属性编辑 / 节点旁路之后），
     * 否则会和逐个 `replace("{{xxx}}", ...)` 的既有逻辑打架。
     */
    /**
     * 技能提示词注入：把用户选的 md（规则文件）写进工作流里的
     * `{{skill_file_name}}` / `{{skill_file_content}}`。技能流（prebuilt_24/25）靠这两个字段
     * 把「规则」交给本地模型，模型再按规则把用户需求改写成成品提示词。
     *
     * 不含这两个占位符的工作流原样返回，所以对普通出图流是零成本的空操作。
     * **未选技能时也要替换**（写成空串）：留着占位符会被服务器当字面文本塞进文本框；
     * 写成空串则节点回到自身默认值（`skill = 自动选择`），行为更合理。
     */
    private fun applySkillPrompt(json: String, workflow: Workflow): String {
        if (!json.contains("{{skill_file")) return json
        val assets = applicationContext.assets
        val selected = SkillPromptStore.get(applicationContext, workflow.name)
            ?: SkillPromptRepository.defaultFor(workflow.type, SkillPromptRepository.list(assets))
        val content = SkillPromptRepository.read(assets, selected).orEmpty()
        DebugLogger.d(TAG, "Skill prompt: ${selected.ifEmpty { "(不注入)" }} (${content.length} 字符)")
        return json
            .replace("{{skill_file_name}}", escapeForJson(selected))
            .replace("{{skill_file_content}}", escapeForJson(content))
    }

    internal fun applyDynamicParamsToJson(
        json: String,
        workflowId: String,
        values: Map<String, String>
    ): String {
        val params = getDynamicParams(workflowId)
        if (params.isEmpty()) return json
        val resolved = DynamicParamValues.resolveAll(params, values, getRawDefaultsObject(workflowId))
        // 越界值会在替换时被夹到最近的合法值（见 DynamicParamRange）：留一条日志，
        // 免得"界面里填的是 0、发出去的是 1"这种不一致事后查不出来
        DynamicParamRange.outOfRangeIn(resolved).takeIf { it.isNotEmpty() }?.let { clamped ->
            DebugLogger.w(
                TAG,
                "动态参数越界已夹紧：" + clamped.joinToString(", ") { (name, out) ->
                    "$name=${out.violation.name.lowercase()}→${out.clamped}"
                }
            )
        }
        val out = DynamicParamValues.applyDynamicParams(json, resolved)
        DebugLogger.d(TAG, "Dynamic params: ${resolved.size} 个占位符，值 ${resolved.map { it.param.placeholder }}")
        return out
    }

    /**
     * Escape special characters in a string for safe JSON insertion
     * 实现委托给 [sh.hnet.comfychair.workflow.dynamic.escapeJsonString]，保证只有一份转义规则
     * （动态节点参数引擎的替换也用同一份）。
     */
    private fun escapeForJson(input: String): String = escapeJsonString(input)

    /**
     * 时间线导演台的两个占位符（[TimelineSlotDetector.TIMELINE_PLACEHOLDER] /
     * [TimelineSlotDetector.GENERATION_SECONDS_PLACEHOLDER]）的替换。
     *
     * 抽成独立函数是为了能在 JVM 单测里钉住转义：整份时间线是一段 JSON 文本，要塞进
     * JSON 字符串里当值，转义错一个字符（时间线里有引号、中文、换行）整个提交就会变成
     * 非法 JSON —— 而那种错误在手机上只表现为"生成失败"，看不出是转义问题。
     *
     * @param timelineData 时间线 JSON 文本；空串按"一个片段都没有"处理，注入 `{}`
     * @param generationSeconds 生成时长；null 表示不替换（保留占位符，由提交校验暴露出来）
     */
    internal fun applyTimelinePlaceholders(
        json: String,
        timelineData: String,
        generationSeconds: Double?
    ): String {
        var out = json
        // 整份时间线是**字符串**输入：替换结果要带上引号（模板里那两个引号可能被
        // 带引号形态一起吃掉，也可能原样留着，见 replacePlaceholderWithJsonLiteral）
        if (out.contains(TimelineSlotDetector.TIMELINE_PLACEHOLDER)) {
            val timelineJson = timelineData.takeIf { it.isNotBlank() } ?: "{}"
            out = replacePlaceholderWithJsonLiteral(
                json = out,
                placeholder = TimelineSlotDetector.TIMELINE_PLACEHOLDER,
                literal = "\"${escapeForJson(timelineJson)}\""
            )
        }
        // 生成时长是 **JSON 数字**（不带引号）。这里必须把模板里那对引号一起吃掉：
        // 留下引号会得到一个字符串 "5.000"，而节点定义里它是 Float 输入 ——
        // 那是个只在提交时才暴露、且看不出根因的类型错误（单元测试抓到过一次）。
        //
        // 用 Double.toString()（Kotlin 给最短往返表示）而不是 "%.3f"：后者会引入
        // 0.0005 秒以内的舍入，让这个值与 timeline_data 里的 selection.duration
        // 不再完全同源。亚帧级的差异（1 帧 = 0.0417 秒）不至于让 guide 落错帧，
        // 但"两个必须同源的值其实不同"是个没必要留的隐患。
        // 它也顺带避开了 Locale 问题：某些语言环境的小数点是逗号，那会写出非法 JSON
        if (generationSeconds != null) {
            out = replacePlaceholderWithJsonLiteral(
                json = out,
                placeholder = TimelineSlotDetector.GENERATION_SECONDS_PLACEHOLDER,
                literal = generationSeconds.toString()
            )
        }
        return out
    }

    /**
     * 用一个 JSON 字面量替换占位符。
     *
     * 两种写法都要认：`"{{name}}"`（值整体是占位符，模板自带引号）与裸 `{{name}}`
     * （嵌在更长的字符串里）。带引号形态会**连引号一起**被替换掉，这样调用方就能
     * 通过 [literal] 决定结果是字符串还是数字 —— 这是它与普通字符串替换的唯一区别，
     * 也是 `{{generation_seconds}}` 不会变成字符串的原因。
     */
    private fun replacePlaceholderWithJsonLiteral(
        json: String,
        placeholder: String,
        literal: String
    ): String {
        val quoted = "\"$placeholder\""
        return if (json.contains(quoted)) {
            json.replace(quoted, literal)
        } else {
            json.replace(placeholder, literal)
        }
    }

    /**
     * Replace all common parameter placeholders in a workflow JSON.
     *
     * This is the SINGLE SOURCE OF TRUTH for common placeholder handling.
     * ALL prepare*WorkflowById functions MUST call this to ensure consistent
     * placeholder replacement across all workflow types.
     *
     * IMPORTANT: Any workflow can use any placeholder. Field visibility is
     * controlled by WorkflowCapabilities, not by screen type. This function
     * must handle ALL possible parameter placeholders uniformly.
     *
     * @param json The workflow JSON string
     * @param seed Random seed for generation
     * @param steps Number of sampling steps
     * @param cfg CFG scale value
     * @param samplerName Sampler algorithm name
     * @param scheduler Scheduler algorithm name
     * @param denoise Denoising strength (0.0-1.0)
     * @param batchSize Number of images to generate
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param megapixels Target megapixels for resolution
     * @param aspectRatio ResolutionSelector 宽高比（如 "9:16 (Portrait Widescreen)"）
     * @param speedMode TESpeedMiniMaxH3 加速模式（standard / 4-step LoRA / 8-step LoRA）
     * @param loraStrength 主 LoRA 权重（LoraLoaderModelOnly.strength_model）
     * @param length Video frame count
     * @param frameRate Video FPS
     * @param upscaleMethod Upscaling algorithm name
     * @param scaleBy Upscale factor
     * @param stopAtClipLayer CLIP skip value
     * @return JSON with all matching placeholders replaced
     */
    private fun replaceCommonPlaceholders(
        json: String,
        seed: Long,
        steps: Int? = null,
        cfg: Float? = null,
        samplerName: String? = null,
        scheduler: String? = null,
        denoise: Float? = null,
        batchSize: Int? = null,
        width: Int? = null,
        height: Int? = null,
        megapixels: Float? = null,
        aspectRatio: String? = null,
        speedMode: String? = null,
        loraStrength: Float? = null,
        length: Int? = null,
        frameRate: Int? = null,
        upscaleMethod: String? = null,
        scaleBy: Float? = null,
        stopAtClipLayer: Int? = null
    ): String {
        var result = json
        // 数值参数走 [PlaceholderLiterals.number]：模板里写成 `"{{steps}}"`（带引号）时会把引号
        // 一起吃掉、写出 JSON 数字。原先的 `replace("{{steps}}", "8")` 留下引号得到 `"8"` ——
        // 一个字符串，KSampler 的 INT 输入在服务端校验时被拒（prompt_outputs_failed_validation）。
        // 字符串参数走 [PlaceholderLiterals.string]（自带引号），两种写法同样都对。
        //
        // Seed - always required, support both {{seed}} and {{noise_seed}} placeholders
        result = PlaceholderLiterals.number(result, "{{seed}}", seed.toString())
        result = PlaceholderLiterals.number(result, "{{noise_seed}}", seed.toString())
        // Also handle legacy "seed": 0 and "noise_seed": 0 patterns
        result = result.replace("\"seed\": 0", "\"seed\": $seed")
        result = result.replace("\"noise_seed\": 0", "\"noise_seed\": $seed")
        // Optional parameters - only replace if provided
        steps?.let { result = PlaceholderLiterals.number(result, "{{steps}}", it.toString()) }
        cfg?.let { result = PlaceholderLiterals.number(result, "{{cfg}}", it.toString()) }
        samplerName?.let { result = PlaceholderLiterals.string(result, "{{sampler_name}}", it) }
        scheduler?.let { result = PlaceholderLiterals.string(result, "{{scheduler}}", it) }
        denoise?.let { result = PlaceholderLiterals.number(result, "{{denoise}}", it.toString()) }
        batchSize?.let { result = PlaceholderLiterals.number(result, "{{batch_size}}", it.toString()) }
        width?.let { result = PlaceholderLiterals.number(result, "{{width}}", it.toString()) }
        height?.let { result = PlaceholderLiterals.number(result, "{{height}}", it.toString()) }
        megapixels?.let { result = PlaceholderLiterals.number(result, "{{megapixels}}", it.toString()) }
        // 宽高比 / 加速模式：值是带空格与括号的字符串（如 "9:16 (Portrait Widescreen)"），
        // 本身不含引号或反斜杠，转义后原样进入 JSON 字符串字面量
        aspectRatio?.let { result = PlaceholderLiterals.string(result, "{{aspect_ratio}}", it) }
        speedMode?.let { result = PlaceholderLiterals.string(result, "{{speed_mode}}", it) }
        // 主 LoRA 权重是数值字面量（节点原值同为数值），与 cfg / megapixels 一样
        // 直接 toString 写进 JSON 数值位置，不走 escapeForJson（那会把数字变成字符串）
        loraStrength?.let { result = PlaceholderLiterals.number(result, "{{lora_strength}}", it.toString()) }
        length?.let { result = PlaceholderLiterals.number(result, "{{length}}", it.toString()) }
        frameRate?.let { result = PlaceholderLiterals.number(result, "{{frame_rate}}", it.toString()) }
        upscaleMethod?.let { result = PlaceholderLiterals.string(result, "{{upscale_method}}", it) }
        scaleBy?.let { result = PlaceholderLiterals.number(result, "{{scale_by}}", it.toString()) }
        stopAtClipLayer?.let { result = PlaceholderLiterals.number(result, "{{stop_at_clip_layer}}", it.toString()) }
        return result
    }

    /**
     * Apply node attribute edits to a workflow JSON.
     * This modifies the input values in specific nodes based on user edits.
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @param edits Map of nodeId -> (inputName -> value)
     * @return Modified workflow JSON with edits applied
     */
    fun applyNodeAttributeEdits(
        workflowJson: String,
        edits: Map<String, Map<String, Any>>
    ): String {
        if (edits.isEmpty()) return workflowJson

        return try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: json

            for ((nodeId, nodeEdits) in edits) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                for ((inputName, value) in nodeEdits) {
                    // Only update if the input exists and is not a connection
                    if (inputs.has(inputName)) {
                        val currentValue = inputs.opt(inputName)
                        // Don't overwrite connections (JSONArray format)
                        if (currentValue !is JSONArray) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value.toInt())
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                        }
                    }
                }
            }

            json.toString()
        } catch (e: Exception) {
            workflowJson
        }
    }

    // Bypass handling - delegates to BypassNodeResolver

    /**
     * Apply bypass logic to a workflow JSON before submission to ComfyUI API.
     * Delegates to BypassNodeResolver.
     */
    fun applyBypassedNodes(workflowJson: String): String =
        BypassNodeResolver.applyBypassedNodes(workflowJson)

    /**
     * 替换 LoRA 占位符；当用户未选择 LoRA 时，移除 LoraLoader 节点并重连。
     *
     * 背景：部分工作流模板强制带 LoraLoader 节点，但用户可能不想用任何 LoRA。
     * 若此时把 lora_name 写成空值/占位符，ComfyUI 会返回
     * 400 "prompt_outputs_failed_validation"（Value not in list: lora_name）。
     *
     * 注意：**不能只把节点标成 mode=4** —— API 格式的 prompt 不识别 mode 字段
     * （mode 只存在于 UI 工作流格式），节点依然会被验证。必须真正删除节点，
     * 并把引用它的连接重连到上游（复用 [BypassNodeResolver] 的类型推断重连逻辑）。
     *
     * 名为 "None" / "null" 的取值同样按"不使用"处理：服务器上 CR Load LoRA /
     * Lora Loader / Efficient Loader 等节点的 lora_name 选项里带一个字面量 "None"，
     * App 的 LoRA 列表是各节点 lora_name 选项的并集，用户从下拉里选中它之后会被当成
     * 真实 LoRA 名提交，而 LoraLoaderModelOnly 的 66 项里并没有它 —— 这正是
     *   Value not in list: lora_name: 'None' not in (list of length 66)
     * 的来历。
     *
     * @param json 已完成占位符替换的 JSON
     * @param lora 用户选择的 LoRA 名（null / 空串 / "None" 一律表示不使用）
     */
    fun replaceLoraPlaceholder(json: String, lora: String?): String {
        val normalizedLora = LoraSelection.normalizeName(lora)
        if (normalizedLora.isNotEmpty()) {
            return json.replace("{{lora_name}}", escapeForJson(normalizedLora))
        }
        return try {
            val root = JSONObject(json)
            val nodes = root.optJSONObject("nodes") ?: root
            var marked = 0
            for (key in nodes.keys().asSequence().toList()) {
                val node = nodes.optJSONObject(key) ?: continue
                val classType = node.optString("class_type", "")
                if (classType.contains("LoraLoader") || classType.contains("Lora Loader")) {
                    node.put("mode", 4)
                    marked++
                }
            }
            if (marked == 0) {
                json
            } else {
                DebugLogger.i(TAG, "未选择 LoRA：移除 $marked 个 LoraLoader 节点并重连")
                BypassNodeResolver.applyBypassedNodes(root.toString())
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "replaceLoraPlaceholder: ${e.message}")
            json
        }
    }

    // ---- 动态图位注入（多图工作流）----

    /**
     * 图片类占位符。占位符判定与 ImageSlotDetector 保持一致：整值占位符，编号从 1 起。
     *
     * 额外兼容历史遗留的"魔法文件名"写法（`reference_image_1.png [input]`）——它和图位占位符
     * 是同一批节点的两代写法，图生图编辑模式第一遍替换会一并处理，模板导入的身份判定也认它
     * （见 WorkflowJsonAnalyzer），所以第二遍清理必须同样认得，否则用户不传参考图时这些节点
     * 会被留在工作流里（旧实现会删掉它们）。只认参考图：主图在编辑模式里必填，旧实现从不删它。
     */
    private val IMAGE_SLOT_PLACEHOLDER = Regex(
        """^\{\{(image_filename|reference_image_\d+)\}\}$|^(reference_image_\d+)\.png \[input\]$"""
    )

    /** 编号图位的占位符名，如 reference_image_1 */
    private val NUMBERED_SLOT_PLACEHOLDER = Regex("""^reference_image_(\d+)$""")

    /** 音频占位符：第一个是 `audio_filename`，之后是 `reference_audio_N`（与图片同构） */
    private val AUDIO_SLOT_PLACEHOLDER = Regex("""^\{\{(audio_filename|reference_audio_\d+)\}\}$""")

    /** 编号音频位的占位符名，如 reference_audio_1 */
    private val NUMBERED_AUDIO_SLOT_PLACEHOLDER = Regex("""^reference_audio_(\d+)$""")

    /**
     * 媒体槽的类别描述。
     *
     * 图片与音频的注入逻辑完全同构（找占位符节点 → 有值写文件名 / 空位删节点并剪枝），
     * 差异只有"节点类名、承载文件名的字段名、占位符怎么写"这三样，全部收在这里。
     * 各写一份 200 行的注入器是最糟的选择：将来改一处必然漏掉另一处。
     */
    private data class MediaSlotSpec(
        val nodeClassType: String,
        val valueField: String,
        val placeholderRegex: Regex,
        val numberedPlaceholderRegex: Regex,
        /**
         * 该媒体"只是从这儿流过去"的口名，用于剪枝判定（见 [pruneDeadChain]）。
         *
         * 刻意按媒体分开而不是并成一个集合：图片链路的剪枝行为已经被 16 个预置工作流
         * 验证过，把音频的口名混进去会改变它的判定结果（多口节点可能被误判成中转节点），
         * 那是拿稳定的东西去换不必要的一致性。
         */
        val genericDataInputs: Set<String>
    )

    /** 容器编号输入口，形如 ref_images.ref_image_0 */
    private val SLOT_CONTAINER_INPUT = Regex("""^(\w+)\.(\w+?)(\d+)$""")

    /**
     * 通用数据口：图片/潜变量只是"从这儿流过去"，不代表图位语义
     * （与 ImageSlotDetector 判定图位语义时用的"通用输入口"是同一个意思）。
     * 从空图位出发沿这些口能走到的节点，就是那条链路上的纯中转节点。
     *
     * 已知限制（见 [pruneDeadChain]）：判定只按**口名**，不区分该口在节点定义里是必填还是可选；
     * 这个名单是按 16 个预置工作流归纳的，自定义节点若有别的数据口名字则认不出来（表现为漏剪、
     * 同样由提交时的校验失败暴露，不会静默出错）。
     */
    private val GENERIC_DATA_INPUTS = setOf("image", "pixels", "samples")

    private val IMAGE_MEDIA_SPEC = MediaSlotSpec(
        nodeClassType = "LoadImage",
        valueField = "image",
        placeholderRegex = IMAGE_SLOT_PLACEHOLDER,
        numberedPlaceholderRegex = NUMBERED_SLOT_PLACEHOLDER,
        genericDataInputs = GENERIC_DATA_INPUTS
    )

    private val AUDIO_MEDIA_SPEC = MediaSlotSpec(
        nodeClassType = "LoadAudio",
        valueField = "audio",
        placeholderRegex = AUDIO_SLOT_PLACEHOLDER,
        numberedPlaceholderRegex = NUMBERED_AUDIO_SLOT_PLACEHOLDER,
        // 音频链路里没有图片那种 resize 中转（LoadAudio 直连节点输入口），
        // 但仍按同一套规则剪枝：万一用户自建工作流插了中转节点，行为是可预期的
        genericDataInputs = setOf("audio")
    )

    /** 乙类图位的容器信息：承载它的下游节点 + 容器名 + 编号前缀 */
    private data class SlotContainer(
        val consumer: JSONObject,
        val container: String,
        val prefix: String
    )

    /** 按用户实际提供的图片增删工作流里的图片节点并接线。 */
    fun injectImageSlots(json: String, assignments: Map<String, String>): String =
        injectMediaSlots(json, assignments, IMAGE_MEDIA_SPEC)

    /**
     * 按用户实际提供的音频增删工作流里的音频节点并接线。
     *
     * 语义与 [injectImageSlots] 完全一致（有值的写文件名、空位删节点并沿链路剪枝），
     * 差别只在节点类名、承载文件名的字段名、以及占位符的命名。
     */
    fun injectAudioSlots(json: String, assignments: Map<String, String>): String =
        injectMediaSlots(json, assignments, AUDIO_MEDIA_SPEC)

    /**
     * 图片与音频共用的槽位注入器（[spec] 描述两者的差异）。
     *
     * 规则：
     * - [assignments] 的键是占位符名（图片：`image_filename` / `reference_image_N`；
     *   音频：`audio_filename` / `reference_audio_N`），
     *   值是**调用方已格式化好的最终文件名**——是否带 ` [input]` 后缀由调用方决定，
     *   注入器原样写进 `image`（或音频的 `audio`）字段，不加也不减任何后缀
     * - 有值的图位：把占位符换成文件名；工作流确实支持这个编号图位时，补一个 LoadImage 节点
     * - 空图位（不在 [assignments] 里）：删掉对应的 LoadImage 节点、清掉指向它的连线，
     *   并沿链路把因此报废的中转节点一并剪掉
     * - 甲类图位（首帧/尾帧等具名口）按输入口名独立处理，删掉首帧不会影响尾帧（设计文档 4.2）
     *
     * 为什么必须剪枝：`LoadImage → ImageResizeKJv2 → MiniMaxH3ImageToVideo.last_frame` 这种链路里，
     * 只删 LoadImage、只清连线会留下一个丢了自己的 `image` 的 ImageResizeKJv2。它的必填输入
     * **不会**因为没人引用它就消失（`/object_info` 里 `ImageResizeKJv2.image` 是 required），
     * 提交 ComfyUI 时会报 `required_input_missing` 导致整张工作流失败——等于没修。
     * 所以删完图位后还要把"因缺图而必然跑不动"的中转节点继续剪掉，直到链路落到真正消费图片的
     * 节点（具名口/容器编号口）为止。
     */
    private fun injectMediaSlots(
        json: String,
        assignments: Map<String, String>,
        spec: MediaSlotSpec
    ): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return json
        val nodes = root.optJSONObject("nodes") ?: return json

        // 空串一律按"留空"处理：ComfyUI 不接受空文件名，调用方给空串的意图只可能是这个槽没填
        val filled = assignments.filterValues { it.isNotBlank() }

        // 1. 找出所有承载占位符的媒体节点（占位符 -> 节点 id）
        val placeholderNodes = linkedMapOf<String, String>()
        for (nodeId in nodes.keys().asSequence().toList()) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            if (node.optString("class_type") != spec.nodeClassType) continue
            val value = node.optJSONObject("inputs")?.optString(spec.valueField).orEmpty()
            val match = spec.placeholderRegex.matchEntire(value) ?: continue
            placeholderNodes[match.groupValues[1].ifEmpty { match.groupValues[2] }] = nodeId
        }

        // 2. 有值的图位：写文件名；工作流确实有编号容器的，补一个编号节点
        //
        // 待删集合先算出来：写值阶段要用它判断"某个编号口上挂的节点是不是本次就要删掉的"
        // （见下面的编号口占用判定）。它只依赖占位符节点表与 [filled]，不受本阶段改动影响。
        val removed = placeholderNodes.filterKeys { it !in filled }.values.toSet()

        var nextId = (nodes.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var container: SlotContainer? = null
        var containerResolved = false
        for ((placeholder, filename) in filled) {
            val escaped = escapeForJson(filename)
            val existing = placeholderNodes[placeholder]
            if (existing != null) {
                nodes.getJSONObject(existing).getJSONObject("inputs").put(spec.valueField, escaped)
                continue
            }

            // 占位符节点已经不在 JSON 里了，有两种可能：
            //   ① 调用方在前一遍已经把用户提供的图位替换成文件名（图生图编辑模式就是这样）
            //   ② 这是用户新加的编号图位，工作流里本来就没有这个口
            // 只有能确认是 ② 时才允许新建节点：见下面的锚点判定。
            val slotIndex = spec.numberedPlaceholderRegex.matchEntire(placeholder)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue

            if (!containerResolved) {
                container = findSlotContainer(nodes, placeholderNodes.values.toSet())
                containerResolved = true
            }
            val anchor = container ?: continue

            // 自动增长输入的编号口（ref_images.ref_image_N）是节点运行时才出现的，
            // 所以不是"找空位"，而是从已有编号口推出容器名与前缀，再按需要的编号拼出新 key。
            val wiredKey = "${anchor.container}.${anchor.prefix}$slotIndex"
            val consumerInputs = anchor.consumer.optJSONObject("inputs") ?: continue

            // 编号口已经占着东西时要分辨占用者是谁：
            // - 挂的是**本次就要删掉的占位符节点**（用户把那个图位删了、又新建了同编号的图位）：
            //   不能跳过。跳过等于新图无处可写，而那个旧节点随后会被删——用户的图被静默丢弃
            //   （Task 8 复审新问题 1：ref2v 删光图位后「+ 添加」新建 reference_image_0，
            //   而 ref_images.ref_image_0 上还挂着 {{image_filename}} 的节点）。
            // - 挂的是别的节点（图生图前一遍已把占位符换成文件名的那张、或用户手工接的图）：
            //   那才是"已经接好了、不许覆盖"的真实接线。
            val occupant = consumerInputs.optJSONArray(wiredKey)?.optString(0).orEmpty()
            if (occupant.isNotEmpty() && occupant !in removed) continue

            val newId = (nextId++).toString()
            nodes.put(newId, JSONObject().apply {
                put("class_type", spec.nodeClassType)
                put("inputs", JSONObject().put(spec.valueField, escaped))
            })
            consumerInputs.put(wiredKey, JSONArray().put(newId).put(0))
        }

        // 3. 空图位：删节点、清连线，并沿通用数据口把报废的中转节点剪掉
        if (removed.isEmpty()) return root.toString()

        val sinks = collectSinks(nodes)
        for (id in removed) nodes.remove(id)
        val dead = pruneDeadChain(nodes, removed, sinks, spec)
        for (id in dead) nodes.remove(id)
        clearConnectionsTo(nodes, dead + removed)

        return root.toString()
    }

    /**
     * 从"已经接在容器编号口上的兄弟图位"反推容器名与前缀。
     *
     * 只认**占位符节点**所在的编号口：这样能保证推出来的容器确实是本工作流用来放图片的那个，
     * 而不是 ref_videos / ref_audios 这类同节点上的别的容器。反过来，如果一个占位符节点都不剩
     * （图生图编辑模式第一遍替换之后就是这样），说明没有任何证据表明工作流支持编号图位，
     * 此时不新建任何节点——甲类具名口的工作流永远走不到这里。
     */
    private fun findSlotContainer(nodes: JSONObject, placeholderNodeIds: Set<String>): SlotContainer? {
        if (placeholderNodeIds.isEmpty()) return null
        for (nodeId in nodes.keys().asSequence().toList()) {
            val consumer = nodes.optJSONObject(nodeId) ?: continue
            val inputs = consumer.optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                if (value !is JSONArray || value.length() < 2) continue
                if (value.optString(0) !in placeholderNodeIds) continue
                val match = SLOT_CONTAINER_INPUT.matchEntire(key) ?: continue
                return SlotContainer(consumer, match.groupValues[1], match.groupValues[2])
            }
        }
        return null
    }

    /**
     * 沿通用数据口把因空图位而报废的中转节点找出来（不含 [seed] 本身）。
     *
     * 判据：从被删图位出发正向走，入口是通用数据口（image/pixels/samples）的节点是纯中转——
     * 它吃的图已经没了，自己又没有别的图片来源，留在工作流里只会缺必填输入。
     * 一旦某节点的入口是具名口（first_frame / image_b / ref_images.ref_image_N …），
     * 说明到了真正消费图片的节点，只清那条连线、不删节点。
     *
     * [protected] 是改动前就没人引用的末端节点（SaveVideo/SaveImage 之类）：它们本来就没有消费者，
     * 不能按"链路上的中转"处理。
     *
     * **已知限制（本次有意保留）**：判据只看输入口名，**不区分该口是必填还是可选**，
     * 因此对"空图位的下游第一个消费者吃的是可选通用口"的工作流会过度删除 ——
     * 实测形态如 `prebuilt_07`（四视图）在图位留空时会把 `23.source_latent` 这类**必填**口一并清掉。
     * 触发后的表现是**响亮的校验失败**（清掉必填连线 → 提交 ComfyUI 报 `required_input_missing`、
     * 工作流整体失败），**不是静默错误**，所以不会悄悄产出错误结果。
     * 16 个预置工作流的**可达路径**不受影响（首个消费者均为必填 image 的 resize/scale 节点）；
     * 用户导入/自建工作流若踩到，需要靠校验失败暴露出来。
     * 要更准必须先拿到 `/object_info` 的必填信息，而本任务不允许新增运行时查询 ——
     * 留给后续任务（届时把 object_info 传进注入器即可）。
     */
    private fun pruneDeadChain(
        nodes: JSONObject,
        seed: Set<String>,
        protected: Set<String>,
        spec: MediaSlotSpec
    ): Set<String> {
        val dead = seed.toMutableSet()
        var frontier = seed
        while (frontier.isNotEmpty()) {
            val next = mutableSetOf<String>()
            for (nodeId in nodes.keys().asSequence().toList()) {
                if (nodeId in dead) continue
                val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                var fedByFrontier = false
                var viaGenericInput = false
                for (key in inputs.keys().asSequence().toList()) {
                    val value = inputs.opt(key)
                    if (value !is JSONArray || value.length() < 2) continue
                    if (value.optString(0) !in frontier) continue
                    fedByFrontier = true
                    if (key in spec.genericDataInputs) viaGenericInput = true
                }
                if (fedByFrontier && viaGenericInput && nodeId !in protected) {
                    dead += nodeId
                    next += nodeId
                }
            }
            frontier = next
        }
        return dead
    }

    /** 改动前就没有任何节点引用它的节点（末端节点）。 */
    private fun collectSinks(nodes: JSONObject): Set<String> {
        val referenced = mutableSetOf<String>()
        for (nodeId in nodes.keys().asSequence().toList()) {
            val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                if (value is JSONArray && value.length() >= 2) referenced += value.optString(0)
            }
        }
        return nodes.keys().asSequence().filter { it !in referenced }.toSet()
    }

    /** 清掉所有指向 [targets] 里节点的连线。 */
    private fun clearConnectionsTo(nodes: JSONObject, targets: Set<String>) {
        if (targets.isEmpty()) return
        for (nodeId in nodes.keys().asSequence().toList()) {
            val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
            val drop = mutableListOf<String>()
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                if (value is JSONArray && value.length() >= 2 && value.optString(0) in targets) {
                    drop.add(key)
                }
            }
            drop.forEach { inputs.remove(it) }
        }
    }

    /**
     * 图生视频的图位表兜底与分支判定（抽成独立函数是为了能在 JVM 单元测试里钉住这两条契约）。
     *
     * **主图兜底**：`imageFilename` 是必填参数，主图不能指望调用方自觉放进图位表。
     * 调用方漏传 `image_filename` 时，主图链路会被当成"留空"删掉，而且**不会报错**——
     * 静默退化成一张没有主图的生成。所以这里在键不存在时补上，值按本函数一贯的约定给
     * （不带 ` [input]` 后缀，注入器照原样写入）。
     *
     * **分支判据**：只看"过滤空串后是否还有值"，不能用 `isNotEmpty()`。
     * **全空串的 map 与空 map 不是等价输入**：两者都走原来的字符串替换分支（空 map 与改动前完全一致），
     * 但全空串意味着"调用方传了表却一张图都没有"，若按 `isNotEmpty()` 走注入器会把整条图片链路删掉，
     * 与空 map 的行为完全不同。
     *
     * @return 交给 [injectImageSlots] 的图位表；null 表示走原来的字符串替换分支
     */
    internal fun resolveImageSlotFiles(
        imageSlotFiles: Map<String, String>,
        imageFilename: String
    ): Map<String, String>? {
        if (imageSlotFiles.values.none { it.isNotBlank() }) return null
        if (imageSlotFiles.containsKey("image_filename")) return imageSlotFiles
        return imageSlotFiles + ("image_filename" to imageFilename)
    }

    // LoRA chain injection - delegates to LoraInjectionUtils

    /**
     * Inject a chain of LoRA loaders into a workflow JSON.
     * Delegates to LoraInjectionUtils.
     */
    fun injectLoraChain(
        workflowJson: String,
        loraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        workflowType: WorkflowType
    ): String = LoraInjectionUtils.injectLoraChain(workflowJson, loraChain, workflowType)

    /**
     * Inject additional LoRAs into a video workflow.
     * Delegates to LoraInjectionUtils.
     */
    fun injectAdditionalVideoLoras(
        workflowJson: String,
        additionalLoraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        isHighNoise: Boolean
    ): String = LoraInjectionUtils.injectAdditionalVideoLoras(workflowJson, additionalLoraChain, isHighNoise)

    /**
     * Apply node attribute edits from storage to the processed workflow JSON.
     * This allows users to customize node attributes via the Workflow Editor
     * that will be applied when generating.
     *
     * @param processedJson The workflow JSON after placeholder replacement
     * @param workflowId The workflow ID to load edits for
     * @return The JSON with node attribute edits applied
     */
    private fun applyNodeAttributeEdits(processedJson: String, workflowId: String): String {
        // Load edits from storage
        val serverId = sh.hnet.comfychair.connection.ConnectionManager.currentServerId ?: run {
            DebugLogger.w(TAG, "applyNodeAttributeEdits: No serverId (ConnectionManager.currentServerId is null)")
            return processedJson
        }
        val values = workflowValuesStorage.loadValues(serverId, workflowId) ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val editsJson = values.nodeAttributeEdits ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No node edits in saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val edits = NodeAttributeEdits.fromJson(editsJson)

        if (edits.isEmpty()) return processedJson

        // Parse the workflow JSON
        val json = try {
            JSONObject(processedJson)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse workflow JSON for edits: ${e.message}")
            return processedJson
        }

        // Get the nodes object (could be at root level or under "nodes" key)
        val nodesJson = if (json.has("nodes")) {
            json.optJSONObject("nodes") ?: json
        } else {
            json
        }

        // Apply edits to each node
        var modified = false
        edits.edits.forEach { (nodeId, nodeEdits) ->
            val node = nodesJson.optJSONObject(nodeId)
            if (node != null) {
                val inputs = node.optJSONObject("inputs")
                if (inputs != null) {
                    nodeEdits.forEach { (inputName, value) ->
                        // Only apply if input exists (don't add new inputs)
                        if (inputs.has(inputName)) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value.toInt())
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                            modified = true
                            DebugLogger.d(TAG, "Applied edit: node $nodeId, $inputName = $value")
                        }
                    }
                }
            }
        }

        return if (modified) json.toString() else processedJson
    }

    /**
     * Prepare workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        // Single-model patterns
        checkpoint: String = "",
        unet: String = "",
        lora: String? = null,  // Mandatory LoRA (single selection dropdown)
        // Dual-model patterns (for unified support)
        highnoiseUnet: String = "",
        lownoiseUnet: String = "",
        highnoiseLora: String = "",
        lownoiseLora: String = "",
        // Common models
        vae: String = "",
        clip: String? = null,
        clip1: String? = null,
        clip2: String? = null,
        clip3: String? = null,
        clip4: String? = null,
        textEncoder: String? = null,
        latentUpscaleModel: String? = null,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float = 8.0f,
        samplerName: String = "euler",
        scheduler: String = "normal",
        seed: Long? = null,
        randomSeed: Boolean = true,
        denoise: Float? = null,
        batchSize: Int? = null,
        // Qwen2.1 的尺寸走 ResolutionSelector：宽高比字符串 + 百万像素（见 replaceCommonPlaceholders）
        megapixels: Float? = null,
        aspectRatio: String? = null,
        upscaleMethod: String? = null,
        scaleBy: Float? = null,
        stopAtClipLayer: Int? = null,
        // 动态节点参数：占位符名 -> 值（见 getDynamicParams）
        dynamicParams: Map<String, String> = emptyMap(),
        /**
         * 输出端是否落 WebP（v4.6.1 模块 C）。
         *
         * 只有图片类路径（TTI / ITI 修复 / ITI 编辑）会把它交给
         * [SaveFormatMaterializer]；视频与 3D 路径收到该参数也不使用，
         * 保存节点保持原样（SaveVideo / Save3DAdvanced / SaveGLB）。
         */
        saveAsWebp: Boolean = false
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        lora?.let { DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(it)}") }
        if (highnoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        if (lownoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        if (highnoiseLora.isNotEmpty()) DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        if (lownoiseLora.isNotEmpty()) DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        clip?.let { DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(it)}") }
        clip1?.let { DebugLogger.d(TAG, "CLIP1: ${Obfuscator.modelName(it)}") }
        clip2?.let { DebugLogger.d(TAG, "CLIP2: ${Obfuscator.modelName(it)}") }
        clip3?.let { DebugLogger.d(TAG, "CLIP3: ${Obfuscator.modelName(it)}") }
        clip4?.let { DebugLogger.d(TAG, "CLIP4: ${Obfuscator.modelName(it)}") }

        // Determine actual seed value - use provided seed if randomSeed is false, otherwise generate random
        // 随机种子限制在 UINT32 范围（0..2^32-1，与 ComfyUI 官方前端一致）：
        // 部分节点（如 SeedVR2VideoUpscaler）的 seed 上限是 4294967295，超限会被服务器 400 拒绝
        val actualSeed = if (randomSeed) (0..4294967295L).random() else (seed ?: 0)
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent

        // Type-specific placeholders: prompts and models
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        // Single-model placeholders
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = replaceLoraPlaceholder(processedJson, lora)
        // Dual-model placeholders
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        // Common model placeholders
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        clip?.let { processedJson = processedJson.replace("{{clip_name}}", escapeForJson(it)) }
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        clip3?.let { processedJson = processedJson.replace("{{clip_name3}}", escapeForJson(it)) }
        clip4?.let { processedJson = processedJson.replace("{{clip_name4}}", escapeForJson(it)) }
        textEncoder?.let { processedJson = processedJson.replace("{{text_encoder_name}}", escapeForJson(it)) }
        latentUpscaleModel?.let { processedJson = processedJson.replace("{{latent_upscale_model}}", escapeForJson(it)) }

        // Common parameter placeholders (unified handling)
        processedJson = replaceCommonPlaceholders(
            json = processedJson,
            seed = actualSeed,
            steps = steps,
            cfg = cfg,
            samplerName = samplerName,
            scheduler = scheduler,
            denoise = denoise,
            batchSize = batchSize,
            width = width,
            height = height,
            // Qwen2.1 这类流的尺寸由 ResolutionSelector 决定（宽高比 + 百万像素），
            // 不看 width/height —— 不传这两个，前端改了尺寸也到不了工作流里
            megapixels = megapixels,
            aspectRatio = aspectRatio,
            upscaleMethod = upscaleMethod,
            scaleBy = scaleBy,
            stopAtClipLayer = stopAtClipLayer
        )

        // Apply node attribute edits from the Workflow Editor
        val preparedJson = applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
        // 动态节点参数：统一替换「未识别」占位符。必须放在最后一步，避免与既有 replace* 逻辑打架
        val dynamicApplied = applyDynamicParamsToJson(preparedJson, workflow.id, dynamicParams)
        // CLIP 加载器格式门控（prebuilt_01/21）：`{{clip_gguf}}` 此时已是 JSON 布尔，
        // 把未选中的那个加载器从图上断开 —— 服务器的 validate_inputs() 会校验未选中分支的
        // COMBO 值（实测），断成孤岛后它才不参与校验（见 ClipGateMaterializer 的类注释）。
        // 两个门控物化，顺序无关（各管各的开关，判据互不重叠）：
        // CLIP 格式门控断未选中的加载器；PE 提示词强化断未选中的强化节点
        // —— 后者必须断结构，因为 CRZSwitch 的惰性在 STRING 链路上实测不生效
        val gated = PeGateMaterializer.materialize(
            ClipGateMaterializer.materialize(applySkillPrompt(dynamicApplied, workflow))
        )
        // 输出端格式物化（v4.6.1 模块 C）：开启时把 SaveImage 换成插件 WebP 保存节点，
        // 关闭时原样返回（仍是 PNG）。两者互斥，同一张图只会落一份文件。
        return SaveFormatMaterializer.materialize(gated, saveAsWebp)
    }

    /**
     * Prepare Image-to-image workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareImageToImageWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        // Single-model patterns
        checkpoint: String = "",
        unet: String = "",
        lora: String? = null,  // Mandatory LoRA (single selection dropdown)
        // Dual-model patterns (for unified support)
        highnoiseUnet: String = "",
        lownoiseUnet: String = "",
        highnoiseLora: String = "",
        lownoiseLora: String = "",
        // Common models
        vae: String = "",
        clip: String = "",
        clip1: String? = null,
        clip2: String? = null,
        clip3: String? = null,
        clip4: String? = null,
        textEncoder: String? = null,
        latentUpscaleModel: String? = null,
        megapixels: Float = 1.0f,
        steps: Int,
        cfg: Float = 8.0f,
        samplerName: String = "euler",
        scheduler: String = "normal",
        denoise: Float = 1.0f,
        imageFilename: String,
        seed: Long? = null,
        randomSeed: Boolean = true,
        /**
         * ResolutionSelector 的宽高比 / 加速模式。
         *
         * 图生图页**没有**这两个控件（那是视频页的），所以调用方通常不传；
         * 为 null 时由 [getWorkflowDefaultsById] 的 defaults 兜底。
         * 两个都不传又没有 defaults，工作流里的 `{{aspect_ratio}}` 就会以字面量
         * 提交给 ResolutionSelector → 服务端 400。
         */
        aspectRatio: String? = null,
        speedMode: String? = null,
        // 动态节点参数：占位符名 -> 值（见 getDynamicParams）
        dynamicParams: Map<String, String> = emptyMap(),
        /**
         * 输出端是否落 WebP（v4.6.1 模块 C）。
         *
         * 只有图片类路径（TTI / ITI 修复 / ITI 编辑）会把它交给
         * [SaveFormatMaterializer]；视频与 3D 路径收到该参数也不使用，
         * 保存节点保持原样（SaveVideo / Save3DAdvanced / SaveGLB）。
         */
        saveAsWebp: Boolean = false
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITI workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Megapixels: $megapixels, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        lora?.let { DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(it)}") }
        if (highnoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        if (lownoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        if (highnoiseLora.isNotEmpty()) DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        if (lownoiseLora.isNotEmpty()) DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        if (clip.isNotEmpty()) DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source image: ${Obfuscator.filename(imageFilename)}")

        // 实际种子：随机开关打开则随机生成，否则用用户指定的值（缺省 0）
        // 随机种子限制在 UINT32 范围（0..2^32-1，与 ComfyUI 官方前端一致），超限会被节点上限校验 400 拒绝
        val actualSeed = if (randomSeed) (0..4294967295L).random() else (seed ?: 0)
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent

        // Type-specific placeholders: prompts and models
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        // Single-model placeholders
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = replaceLoraPlaceholder(processedJson, lora)
        // Dual-model placeholders
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        // Common model placeholders
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        clip3?.let { processedJson = processedJson.replace("{{clip_name3}}", escapeForJson(it)) }
        clip4?.let { processedJson = processedJson.replace("{{clip_name4}}", escapeForJson(it)) }
        textEncoder?.let { processedJson = processedJson.replace("{{text_encoder_name}}", escapeForJson(it)) }
        latentUpscaleModel?.let { processedJson = processedJson.replace("{{latent_upscale_model}}", escapeForJson(it)) }

        // Type-specific: source image placeholder (both template and literal formats)
        val escapedImageFilename = escapeForJson(imageFilename)
        processedJson = processedJson.replace("{{image_filename}}", "$escapedImageFilename [input]")
        processedJson = processedJson.replace("uploaded_image.png [input]", "$escapedImageFilename [input]")

        // Common parameter placeholders (unified handling)
        processedJson = replaceCommonPlaceholders(
            json = processedJson,
            seed = actualSeed,
            steps = steps,
            cfg = cfg,
            samplerName = samplerName,
            scheduler = scheduler,
            denoise = denoise,
            megapixels = megapixels,
            // 宽高比 / 加速模式：图生图页没有这两个控件，值一律来自工作流 defaults。
            // 不传的话 `{{aspect_ratio}}` 会以字面量提交给 ResolutionSelector，
            // 服务端校验直接 400（Value not in list）——2026-11-11 真机复现。
            aspectRatio = aspectRatio ?: getWorkflowDefaultsById(workflowId)?.aspectRatio,
            speedMode = speedMode ?: getWorkflowDefaultsById(workflowId)?.speedMode
        )

        // Apply node attribute edits from the Workflow Editor
        val preparedJson = applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
        // 动态节点参数：统一替换「未识别」占位符。必须放在最后一步，避免与既有 replace* 逻辑打架
        // PE 门控物化：关掉提示词强化时把强化节点从图上摘掉（CRZSwitch 的惰性在
        // STRING 链路上实测不生效，不断结构就会白跑一次本地大模型）
        val gated = PeGateMaterializer.materialize(
            applySkillPrompt(
                applyDynamicParamsToJson(preparedJson, workflow.id, dynamicParams), workflow
            )
        )
        // 输出端格式物化（v4.6.1 模块 C）：WebP 开关只在图片路径生效
        return SaveFormatMaterializer.materialize(gated, saveAsWebp)
    }

    /**
     * Prepare Image-to-Image Editing workflow JSON with actual parameter values (by workflow ID).
     * This is for the QwenImage Edit-style workflows that use reference images instead of masks.
     */
    fun prepareImageEditingWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        checkpoint: String = "",
        unet: String,
        lora: String,
        vae: String,
        clip: String,
        clip1: String? = null,
        clip2: String? = null,
        clip3: String? = null,
        clip4: String? = null,
        textEncoder: String? = null,
        latentUpscaleModel: String? = null,
        upscaleModel: String? = null,
        // Dual-model patterns (for video-style workflows)
        highnoiseUnet: String = "",
        lownoiseUnet: String = "",
        highnoiseLora: String = "",
        lownoiseLora: String = "",
        megapixels: Float = 2.0f,
        steps: Int,
        cfg: Float = 1.0f,
        samplerName: String = "euler",
        scheduler: String = "simple",
        denoise: Float = 1.0f,
        sourceImageFilename: String,
        referenceImage1Filename: String? = null,
        referenceImage2Filename: String? = null,
        seed: Long? = null,
        randomSeed: Boolean = true,
        /** 同 [prepareImageToImageWorkflowById]：缺了它 `{{aspect_ratio}}` 会原样提交并 400 */
        aspectRatio: String? = null,
        speedMode: String? = null,
        // 动态节点参数：占位符名 -> 值（见 getDynamicParams）
        dynamicParams: Map<String, String> = emptyMap(),
        /**
         * 输出端是否落 WebP（v4.6.1 模块 C）。
         *
         * 只有图片类路径（TTI / ITI 修复 / ITI 编辑）会把它交给
         * [SaveFormatMaterializer]；视频与 3D 路径收到该参数也不使用，
         * 保存节点保持原样（SaveVideo / Save3DAdvanced / SaveGLB）。
         */
        saveAsWebp: Boolean = false
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITE workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Megapixels: $megapixels, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(lora)}")
        DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source: ${Obfuscator.filename(sourceImageFilename)}")
        referenceImage1Filename?.let { DebugLogger.d(TAG, "Reference1: ${Obfuscator.filename(it)}") }
        referenceImage2Filename?.let { DebugLogger.d(TAG, "Reference2: ${Obfuscator.filename(it)}") }

        // 实际种子：随机开关打开则随机生成，否则用用户指定的值（缺省 0）
        // 随机种子限制在 UINT32 范围（0..2^32-1，与 ComfyUI 官方前端一致），超限会被节点上限校验 400 拒绝
        val actualSeed = if (randomSeed) (0..4294967295L).random() else (seed ?: 0)
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent

        // Type-specific placeholders: prompts and models
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        // Checkpoint (for checkpoint-based workflows like CheckpointLoaderSimple)
        if (checkpoint.isNotEmpty()) {
            processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        }
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = replaceLoraPlaceholder(processedJson, lora)
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        clip3?.let { processedJson = processedJson.replace("{{clip_name3}}", escapeForJson(it)) }
        clip4?.let { processedJson = processedJson.replace("{{clip_name4}}", escapeForJson(it)) }
        textEncoder?.let { processedJson = processedJson.replace("{{text_encoder_name}}", escapeForJson(it)) }
        latentUpscaleModel?.let { processedJson = processedJson.replace("{{latent_upscale_model}}", escapeForJson(it)) }
        upscaleModel?.let { processedJson = processedJson.replace("{{upscale_model}}", escapeForJson(it)) }
        // Dual-model patterns (for video-style workflows)
        if (highnoiseUnet.isNotEmpty()) {
            processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        }
        if (lownoiseUnet.isNotEmpty()) {
            processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        }
        if (highnoiseLora.isNotEmpty()) {
            processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        }
        if (lownoiseLora.isNotEmpty()) {
            processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        }

        // Type-specific: source image placeholder (both template and literal formats)
        val escapedSourceFilename = escapeForJson(sourceImageFilename)
        processedJson = processedJson.replace("{{image_filename}}", "$escapedSourceFilename [input]")
        processedJson = processedJson.replace("uploaded_image.png [input]", "$escapedSourceFilename [input]")

        // Type-specific: reference images (both placeholder and magic filename formats)
        if (referenceImage1Filename != null) {
            val escaped1 = "${escapeForJson(referenceImage1Filename)} [input]"
            processedJson = processedJson.replace("{{reference_image_1}}", escaped1)
            processedJson = processedJson.replace("reference_image_1.png [input]", escaped1)
        }
        if (referenceImage2Filename != null) {
            val escaped2 = "${escapeForJson(referenceImage2Filename)} [input]"
            processedJson = processedJson.replace("{{reference_image_2}}", escaped2)
            processedJson = processedJson.replace("reference_image_2.png [input]", escaped2)
        }

        // Common parameter placeholders (unified handling)
        processedJson = replaceCommonPlaceholders(
            json = processedJson,
            seed = actualSeed,
            steps = steps,
            cfg = cfg,
            samplerName = samplerName,
            scheduler = scheduler,
            denoise = denoise,
            megapixels = megapixels,
            // 同 prepareImageToImageWorkflowById：图生图页无宽高比控件，
            // 缺了这条 `{{aspect_ratio}}` 会原样提交并触发 400
            aspectRatio = aspectRatio ?: getWorkflowDefaultsById(workflowId)?.aspectRatio,
            speedMode = speedMode ?: getWorkflowDefaultsById(workflowId)?.speedMode
        )

        // 第二遍：按用户实际提供的图片增删节点并接线
        // （原本这里写死 ref1/ref2 的删除逻辑，现泛化为任意图位）。
        // 注意第一遍的占位符替换必须保留在上面：已提供的图位在第一遍就已经写成最终文件名，
        // 注入器扫不到占位符节点，自然不会再去动它；只有留空的图位还会留着占位符，由注入器删掉。
        val assignments = buildMap {
            put("image_filename", sourceImageFilename)
            referenceImage1Filename?.let { put("reference_image_1", it) }
            referenceImage2Filename?.let { put("reference_image_2", it) }
        }
        processedJson = injectImageSlots(processedJson, assignments)

        // Apply node attribute edits from the Workflow Editor
        val preparedJson = applyNodeAttributeEdits(processedJson, workflow.id)
        // 动态节点参数：统一替换「未识别」占位符。必须放在最后一步，避免与既有 replace* 逻辑打架
        // PE 门控物化：关掉提示词强化时把强化节点从图上摘掉（CRZSwitch 的惰性在
        // STRING 链路上实测不生效，不断结构就会白跑一次本地大模型）
        val gated = PeGateMaterializer.materialize(
            applySkillPrompt(
                applyDynamicParamsToJson(preparedJson, workflow.id, dynamicParams), workflow
            )
        )
        // 输出端格式物化（v4.6.1 模块 C）：WebP 开关只在图片路径生效
        return SaveFormatMaterializer.materialize(gated, saveAsWebp)
    }

    /**
     * Prepare video workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareVideoWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        // Single-model patterns (e.g., LTX 2.0)
        checkpoint: String = "",
        unet: String = "",
        lora: String? = null,
        // Dual-model patterns (e.g., Wan 2.2)
        highnoiseUnet: String = "",
        lownoiseUnet: String = "",
        highnoiseLora: String = "",
        lownoiseLora: String = "",
        // Common models
        vae: String = "",
        clip: String = "",
        clip1: String? = null,
        clip2: String? = null,
        clip3: String? = null,
        clip4: String? = null,
        textEncoder: String? = null,
        latentUpscaleModel: String? = null,
        width: Int,
        height: Int,
        length: Int,
        fps: Int = 16,
        // Generation parameters (optional - workflow may have defaults)
        steps: Int? = null,
        cfg: Float? = null,
        samplerName: String? = null,
        scheduler: String? = null,
        denoise: Float? = null,
        seed: Long? = null,
        randomSeed: Boolean = true,
        // 视频分辨率选择器 / 加速模式
        megapixels: Float? = null,
        aspectRatio: String? = null,
        speedMode: String? = null,
        // 主 LoRA 权重（LoraLoaderModelOnly.strength_model）
        loraStrength: Float? = null,
        // 动态节点参数：占位符名 -> 值（见 getDynamicParams）
        dynamicParams: Map<String, String> = emptyMap(),
        /**
         * 输出端是否落 WebP（v4.6.1 模块 C）。
         *
         * 只有图片类路径（TTI / ITI 修复 / ITI 编辑）会把它交给
         * [SaveFormatMaterializer]；视频与 3D 路径收到该参数也不使用，
         * 保存节点保持原样（SaveVideo / Save3DAdvanced / SaveGLB）。
         */
        saveAsWebp: Boolean = false
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing TTV workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Length: $length frames, FPS: $fps")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        lora?.let { DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(it)}") }
        if (highnoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        if (lownoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        if (highnoiseLora.isNotEmpty()) DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        if (lownoiseLora.isNotEmpty()) DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        if (clip.isNotEmpty()) DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")

        // 实际种子：随机开关打开则随机生成，否则用用户指定的值（缺省 0）
        // 随机种子限制在 UINT32 范围（0..2^32-1，与 ComfyUI 官方前端一致），超限会被节点上限校验 400 拒绝
        val actualSeed = if (randomSeed) (0..4294967295L).random() else (seed ?: 0)
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent

        // Type-specific placeholders: prompts and models
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        // Single-model placeholders
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = replaceLoraPlaceholder(processedJson, lora)
        // Dual-model placeholders
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        // Common model placeholders
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        clip3?.let { processedJson = processedJson.replace("{{clip_name3}}", escapeForJson(it)) }
        clip4?.let { processedJson = processedJson.replace("{{clip_name4}}", escapeForJson(it)) }
        textEncoder?.let { processedJson = processedJson.replace("{{text_encoder_name}}", escapeForJson(it)) }
        latentUpscaleModel?.let { processedJson = processedJson.replace("{{latent_upscale_model}}", escapeForJson(it)) }

        // Common parameter placeholders (unified handling)
        processedJson = replaceCommonPlaceholders(
            json = processedJson,
            seed = actualSeed,
            steps = steps,
            cfg = cfg,
            samplerName = samplerName,
            scheduler = scheduler,
            denoise = denoise,
            width = width,
            height = height,
            length = length,
            frameRate = fps,
            batchSize = 1,
            megapixels = megapixels,
            aspectRatio = aspectRatio,
            speedMode = speedMode,
            loraStrength = loraStrength
        )

        // Apply node attribute edits from the Workflow Editor
        val preparedJson = applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
        // 动态节点参数：统一替换「未识别」占位符。必须放在最后一步，避免与既有 replace* 逻辑打架
        // PE 门控物化：关掉提示词强化时把强化节点从图上摘掉（CRZSwitch 的惰性在
        // STRING 链路上实测不生效，不断结构就会白跑一次本地大模型）
        return PeGateMaterializer.materialize(
            applySkillPrompt(
                applyDynamicParamsToJson(preparedJson, workflow.id, dynamicParams), workflow
            )
        )
    }

    /**
     * Prepare image-to-video workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareImageToVideoWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        // Single-model patterns (e.g., LTX 2.0)
        checkpoint: String = "",
        unet: String = "",
        lora: String? = null,
        // Dual-model patterns (e.g., Wan 2.2)
        highnoiseUnet: String = "",
        lownoiseUnet: String = "",
        highnoiseLora: String = "",
        lownoiseLora: String = "",
        // Common models
        vae: String = "",
        clip: String = "",
        clip1: String? = null,
        clip2: String? = null,
        clip3: String? = null,
        clip4: String? = null,
        textEncoder: String? = null,
        latentUpscaleModel: String? = null,
        width: Int,
        height: Int,
        length: Int,
        fps: Int = 16,
        imageFilename: String,
        /**
         * 图位占位符 -> 已上传文件名（调用方已格式化好的最终字符串，注入器原样写入）。
         * 键为 "image_filename" / "reference_image_N"；留空的图位不在 map 中，其节点会被删除。
         * 为空 map 时行为与改动前完全一致。
         */
        imageSlotFiles: Map<String, String> = emptyMap(),
        /**
         * 音频槽占位符 -> 已上传文件名（键为 "audio_filename" / "reference_audio_N"）。
         * 与图片侧不同，这里**无条件注入**：工作流里没有音频占位符时注入器是空操作，
         * 有占位符而表为空时正好把那些 LoadAudio 节点删干净（否则占位符会被当字面量文件名提交）。
         */
        audioSlotFiles: Map<String, String> = emptyMap(),
        // Generation parameters (optional - workflow may have defaults)
        steps: Int? = null,
        cfg: Float? = null,
        samplerName: String? = null,
        scheduler: String? = null,
        denoise: Float? = null,
        seed: Long? = null,
        randomSeed: Boolean = true,
        // 视频分辨率选择器 / 加速模式
        megapixels: Float? = null,
        aspectRatio: String? = null,
        speedMode: String? = null,
        // 主 LoRA 权重（LoraLoaderModelOnly.strength_model）
        loraStrength: Float? = null,
        /**
         * 时间线导演台的整份时间线（`timeline_data` 的 JSON 文本，见 [sh.hnet.comfychair.model.TimelineDocument]）。
         *
         * 空串按「一个片段都没有」处理并注入 `{}`：插件对空串也会自己兜成 `{}`，
         * 但显式写出来能让服务器侧日志一眼看出这是 App 给的，而不是用户漏填。
         */
        timelineData: String = "",
        /**
         * 生成时长（秒）。与 `timeline_data.selection.duration` **必须同源**：
         * 节点用 `generation_seconds` 算生成帧数、用 `selection` 算原画面固定到第几帧，
         * 两者不一致会让 guide 落到错误的帧上（画面错位，且不报错）。
         */
        generationSeconds: Double? = null,
        // 动态节点参数：占位符名 -> 值（见 getDynamicParams）
        dynamicParams: Map<String, String> = emptyMap(),
        /**
         * 输出端是否落 WebP（v4.6.1 模块 C）。
         *
         * 只有图片类路径（TTI / ITI 修复 / ITI 编辑）会把它交给
         * [SaveFormatMaterializer]；视频与 3D 路径收到该参数也不使用，
         * 保存节点保持原样（SaveVideo / Save3DAdvanced / SaveGLB）。
         */
        saveAsWebp: Boolean = false
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITV workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Length: $length frames, FPS: $fps")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        lora?.let { DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(it)}") }
        if (highnoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        if (lownoiseUnet.isNotEmpty()) DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        if (highnoiseLora.isNotEmpty()) DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        if (lownoiseLora.isNotEmpty()) DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        if (clip.isNotEmpty()) DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source image: ${Obfuscator.filename(imageFilename)}")

        // 实际种子：随机开关打开则随机生成，否则用用户指定的值（缺省 0）
        // 随机种子限制在 UINT32 范围（0..2^32-1，与 ComfyUI 官方前端一致），超限会被节点上限校验 400 拒绝
        val actualSeed = if (randomSeed) (0..4294967295L).random() else (seed ?: 0)
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent

        // Type-specific placeholders: prompts and models
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        // Single-model placeholders
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = replaceLoraPlaceholder(processedJson, lora)
        // Dual-model placeholders
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        // Common model placeholders
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        clip3?.let { processedJson = processedJson.replace("{{clip_name3}}", escapeForJson(it)) }
        clip4?.let { processedJson = processedJson.replace("{{clip_name4}}", escapeForJson(it)) }
        textEncoder?.let { processedJson = processedJson.replace("{{text_encoder_name}}", escapeForJson(it)) }
        latentUpscaleModel?.let { processedJson = processedJson.replace("{{latent_upscale_model}}", escapeForJson(it)) }

        // Type-specific: source image placeholder
        // 多图位：按用户实际提供的图片增删节点并接线（首尾帧、参考图都走这里）。
        // 主图由 resolveImageSlotFiles 兜底补进图位表；返回 null 表示没传表（或表里一张图都没有），
        // 走原来的字符串替换，行为与改动前完全一致。
        val slotFiles = resolveImageSlotFiles(imageSlotFiles, imageFilename)
        if (slotFiles != null) {
            processedJson = injectImageSlots(processedJson, slotFiles)
        } else {
            processedJson = processedJson.replace("{{image_filename}}", escapeForJson(imageFilename))
        }

        // 参考音频：用户没填的音频槽要连同 LoadAudio 节点一起删掉，
        // 否则节点会带着 {{...}} 占位符提交，ComfyUI 去找一个名为 "{{audio_filename}}" 的文件而失败
        processedJson = injectAudioSlots(processedJson, audioSlotFiles)

        // 时间线导演台：整份时间线是**一个字符串输入**，与 positive_prompt 同构
        // （模板里 `"timeline_data": "{{timeline_data}}"` 已带外层引号，这里只给转义后的内容）。
        // 不替换的后果不只是参数没生效：占位符会被当字面量发给插件，它 json.loads 会直接抛错。
        processedJson = applyTimelinePlaceholders(processedJson, timelineData, generationSeconds)

        // Common parameter placeholders (unified handling)
        processedJson = replaceCommonPlaceholders(
            json = processedJson,
            seed = actualSeed,
            steps = steps,
            cfg = cfg,
            samplerName = samplerName,
            scheduler = scheduler,
            denoise = denoise,
            width = width,
            height = height,
            length = length,
            frameRate = fps,
            batchSize = 1,
            megapixels = megapixels,
            aspectRatio = aspectRatio,
            speedMode = speedMode,
            loraStrength = loraStrength
        )

        // Apply node attribute edits from the Workflow Editor
        val preparedJson = applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
        // 动态节点参数：统一替换「未识别」占位符。必须放在最后一步，避免与既有 replace* 逻辑打架
        // PE 门控物化：关掉提示词强化时把强化节点从图上摘掉（CRZSwitch 的惰性在
        // STRING 链路上实测不生效，不断结构就会白跑一次本地大模型）
        return PeGateMaterializer.materialize(
            applySkillPrompt(
                applyDynamicParamsToJson(preparedJson, workflow.id, dynamicParams), workflow
            )
        )
    }

    /**
     * Check if a value is a template placeholder (e.g., "{{sampler_name}}")
     * These should not be extracted as default values.
     */
    private fun isPlaceholder(value: Any?): Boolean {
        return value is String && value.startsWith("{{") && value.endsWith("}}")
    }

}
