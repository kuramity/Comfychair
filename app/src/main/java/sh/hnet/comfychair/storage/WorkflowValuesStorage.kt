package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.util.DebugLogger

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
     * 读取该工作流的保存值，并**在读取时清洗 sampler / scheduler**（非法值自愈）。
     *
     * 为什么要在读的时候做：下拉框的 `selectedValue` 是原样回显的（见
     * BottomSheetConfigHelpers），持久化里存了 `dpm++` 这种非法值时 UI 没有任何提示，
     * 用户一按生成就被 ComfyUI 的 `validate_inputs()` 判成 `Value not in list` 返回 400
     * （真机日志：`scheduler: 'dpm++' not in ['simple', 'sgm_uniform', ...]`）。
     *
     * 处理方式：**在服务器清单已知（非空）时**把非法值就地改写为默认值，并**立即写回**磁盘 ——
     * 脏数据只生效一次，之后读到的就是干净值（写回才是"自愈"）。
     * 只改写确实非法的字段：字段本来为 null（= 跟随工作流默认）时保持 null，不无谓地固化。
     *
     * **清单未知（`available*` 为空）时不做判断**：冷启动时 `/object_info` 常还没回来，
     * 内置清单可能落后于服务端（日志里的 `shifted_tangent` 就不在内置清单里），
     * 此时判废 + 写回会把用户合法的选择永久改掉。这个窗口交给提交前的严格清洗兜底
     * （见各 ViewModel 的 prepare*：清单为空才退回内置清单，且不会写坏磁盘）。
     *
     * @param availableSamplers 服务器 `/object_info` 报的可用 sampler；空表 = 还没同步，本次不判废
     * @param availableSchedulers 同上，scheduler 版
     * @return 清洗后的保存值；该工作流从未保存过时返回 null
     */
    fun loadValuesSanitized(
        serverId: String,
        workflowId: String,
        availableSamplers: List<String>,
        availableSchedulers: List<String>
    ): WorkflowValues? {
        val saved = loadValues(serverId, workflowId) ?: return null
        // 读阶段只在服务器清单已知时判废（见 SamplerOptions.sanitizeSamplerOnLoad 的时序说明）
        val sampler = saved.samplerName?.let { SamplerOptions.sanitizeSamplerOnLoad(it, availableSamplers) }
        val scheduler = saved.scheduler?.let { SamplerOptions.sanitizeSchedulerOnLoad(it, availableSchedulers) }
        if (sampler == saved.samplerName && scheduler == saved.scheduler) return saved
        val healed = saved.copy(samplerName = sampler, scheduler = scheduler)
        saveValues(serverId, workflowId, healed)
        DebugLogger.w(
            TAG,
            "已修正持久化里的非法采样参数：sampler ${saved.samplerName} -> $sampler，" +
                "scheduler ${saved.scheduler} -> $scheduler"
        )
        return healed
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
        private const val TAG = "WorkflowValuesStorage"
    }
}
