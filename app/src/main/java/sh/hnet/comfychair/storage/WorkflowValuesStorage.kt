package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.WorkflowValues

/**
 * Storage for per-workflow user values.
 * Each workflow's values are stored as a JSON string keyed by server ID and workflow ID.
 * Key format: {serverId}_{workflowId}
 */
class WorkflowValuesStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save values for a specific workflow on a specific server.
     */
    fun saveValues(serverId: String, workflowId: String, values: WorkflowValues) {
        val json = WorkflowValues.toJson(values)
        prefs.edit().putString(keyFor(serverId, workflowId), json).apply()
    }

    /**
     * Load saved values for a specific workflow on a specific server.
     * Returns null if no values have been saved for this workflow.
     */
    fun loadValues(serverId: String, workflowId: String): WorkflowValues? {
        val json = prefs.getString(keyFor(serverId, workflowId), null) ?: return null
        return try {
            WorkflowValues.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete saved values for a specific workflow on a specific server.
     */
    fun deleteValues(serverId: String, workflowId: String) {
        prefs.edit().remove(keyFor(serverId, workflowId)).apply()
    }

    /**
     * Delete all saved values for a specific server.
     */
    fun deleteValuesForServer(serverId: String) {
        val prefix = "${serverId}_"
        val keysToRemove = prefs.all.keys.filter { it.startsWith(prefix) }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Clear all saved workflow values.
     * Called when user restores defaults.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Clear negative prompts from all saved workflow values for a specific server.
     * Called when user resets prompts to defaults.
     */
    fun clearNegativePromptsForServer(serverId: String) {
        val prefix = "${serverId}_"
        val allKeys = prefs.all.keys.filter { it.startsWith(prefix) }
        for (key in allKeys) {
            val json = prefs.getString(key, null) ?: continue
            val values = try {
                WorkflowValues.fromJson(json)
            } catch (e: Exception) {
                continue
            }
            val updated = values.copy(negativePrompt = "")
            prefs.edit().putString(key, WorkflowValues.toJson(updated)).apply()
        }
    }

    /**
     * Clear positive prompts from all saved workflow values for a specific server.
     * Called when user resets prompts to defaults（设置页「重置提示词」）。
     *
     * 与 [clearNegativePromptsForServer] 有一处**刻意的不同**：这里写 `null`（= 删掉该字段），
     * 而不是空串。理由是本字段的三态语义：`""` 表示"用户明确清空过"，
     * 会让下次载入**不再**回退到工作流的默认提示词；而"重置为默认"要的恰恰是回退，
     * 所以必须把字段清成 `null`（详见 WorkflowValues.positivePrompt 的注释）。
     */
    fun clearPositivePromptsForServer(serverId: String) {
        val prefix = "${serverId}_"
        val allKeys = prefs.all.keys.filter { it.startsWith(prefix) }
        for (key in allKeys) {
            val json = prefs.getString(key, null) ?: continue
            val values = try {
                WorkflowValues.fromJson(json)
            } catch (e: Exception) {
                continue
            }
            if (values.positivePrompt == null) continue
            prefs.edit().putString(key, WorkflowValues.toJson(values.copy(positivePrompt = null))).apply()
        }
    }

    /**
     * Get all workflow values for a specific server.
     * Returns a map of workflowId to WorkflowValues.
     */
    fun getAllValuesForServer(serverId: String): Map<String, WorkflowValues> {
        val prefix = "${serverId}_"
        val result = mutableMapOf<String, WorkflowValues>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                val workflowId = key.removePrefix(prefix)
                try {
                    WorkflowValues.fromJson(value)?.let {
                        result[workflowId] = it
                    }
                } catch (_: Exception) {
                    // Skip invalid entries
                }
            }
        }
        return result
    }

    private fun keyFor(serverId: String, workflowId: String): String = "${serverId}_$workflowId"

    companion object {
        private const val PREFS_NAME = "WorkflowValuesPrefs"
    }
}
