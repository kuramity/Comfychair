package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.util.DebugLogger

/** 待清理数据的 ID 集合读取结果：失败必须上抛给判定器，不能静默当成空集。 */
sealed interface IdSetResult {
    data class Success(val ids: Set<String>) : IdSetResult
    data class Failure(val message: String) : IdSetResult
}

/**
 * Performs maintenance on SharedPreferences to remove stale entries.
 * Cleans up orphaned data from deleted servers and workflows.
 *
 * Should be called on app startup.
 *
 * 采用 fail-closed：任何一侧读取失败、或服务器数量异常归零且无显式删除证据时，
 * 整体跳过本次清理，绝不清空凭据与用户参数。
 */
object PreferencesMaintenance {
    private const val TAG = "PrefsMaintenance"

    // SharedPreferences file names
    private const val PREFS_WORKFLOW_VALUES = "WorkflowValuesPrefs"
    private const val PREFS_CREDENTIALS = "CredentialPrefs"
    private const val PREFS_TEXT_TO_IMAGE = "TextToImageFragmentPrefs"
    private const val PREFS_IMAGE_TO_IMAGE = "ImageToImageFragmentPrefs"
    private const val PREFS_TEXT_TO_VIDEO = "TextToVideoFragmentPrefs"
    private const val PREFS_IMAGE_TO_VIDEO = "ImageToVideoFragmentPrefs"
    private const val PREFS_MAINTENANCE_STATE = "MaintenanceStatePrefs"

    private const val KEY_LAST_SERVER_COUNT = "last_server_count"

    // Credential key prefixes
    private const val KEY_USERNAME_PREFIX = "username_"
    private const val KEY_PASSWORD_PREFIX = "password_"
    private const val KEY_TOKEN_PREFIX = "token_"

    /**
     * Perform full maintenance on all SharedPreferences.
     * Removes entries for deleted servers and workflows.
     *
     * @param context Application context
     * @param explicitDeletion 调用方是否掌握用户显式删除服务器的证据；
     *        拿不到证据时保持 false，服务器数归零将中止清理。
     */
    fun performMaintenance(context: Context, explicitDeletion: Boolean = false) {
        DebugLogger.i(TAG, "Starting preferences maintenance")

        val serversResult = loadValidServerIds(context)
        val workflowsResult = loadValidWorkflowIds(context)

        val serverCount = (serversResult as? IdSetResult.Success)?.ids?.size ?: 0
        val workflowCount = (workflowsResult as? IdSetResult.Success)?.ids?.size ?: 0
        val previousServerCount = readLastServerCount(context)

        val decision = MaintenancePlan.decide(
            serversOk = serversResult is IdSetResult.Success,
            workflowsOk = workflowsResult is IdSetResult.Success,
            workflowCount = workflowCount,
            serverCount = serverCount,
            previousServerCount = previousServerCount,
            explicitDeletion = explicitDeletion
        )

        if (decision is MaintenancePlan.Decision.Abort) {
            DebugLogger.e(TAG, "维护中止：${decision.reason}")
            return
        }

        val validServerIds = (serversResult as IdSetResult.Success).ids
        val validWorkflowIds = (workflowsResult as IdSetResult.Success).ids

        DebugLogger.d(TAG, "Valid servers: ${validServerIds.size}, valid workflows: ${validWorkflowIds.size}")

        var totalRemoved = 0
        totalRemoved += cleanWorkflowValuesPrefs(context, validServerIds, validWorkflowIds)
        totalRemoved += cleanCredentialPrefs(context, validServerIds)
        totalRemoved += cleanScreenPrefs(context, validServerIds, validWorkflowIds)
        totalRemoved += cleanFileCaches(context, validServerIds)

        writeLastServerCount(context, serverCount)
        DebugLogger.i(TAG, "Maintenance complete. Removed $totalRemoved stale entries/files")
    }

    /**
     * Get the set of valid server IDs from ServerStorage.
     */
    private fun loadValidServerIds(context: Context): IdSetResult {
        return when (val loaded = ServerStorage(context).loadServers()) {
            is ServerStorage.LoadResult.Success -> IdSetResult.Success(loaded.servers.map { it.id }.toSet())
            is ServerStorage.LoadResult.Failure -> {
                DebugLogger.e(TAG, "Failed to get valid server IDs: ${loaded.error.message}")
                IdSetResult.Failure(loaded.error.message ?: "unknown")
            }
        }
    }

    /**
     * Get the set of valid workflow IDs from WorkflowManager.
     */
    private fun loadValidWorkflowIds(context: Context): IdSetResult {
        return try {
            // Ensure WorkflowManager is initialized
            WorkflowManager.initialize(context)
            // 加载过程中出现过异常（如用户工作流元数据损坏）：列表只是部分结果，
            // 不能当成「全部有效 ID」去清理，否则用户工作流的参数会被误删
            if (!MaintenancePlan.workflowsLoadOk(WorkflowManager.didLastLoadHaveErrors())) {
                DebugLogger.e(TAG, "工作流加载过程中出现异常，视为读取失败")
                return IdSetResult.Failure("工作流加载过程中出现异常")
            }
            IdSetResult.Success(WorkflowManager.getAllWorkflows().map { it.id }.toSet())
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get valid workflow IDs: ${e.message}")
            IdSetResult.Failure(e.message ?: "unknown")
        }
    }

    /** 上次成功清理时记录的服务器数量；读取失败按 0 处理。 */
    private fun readLastServerCount(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS_MAINTENANCE_STATE, Context.MODE_PRIVATE)
                .getInt(KEY_LAST_SERVER_COUNT, 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun writeLastServerCount(context: Context, count: Int) {
        try {
            context.getSharedPreferences(PREFS_MAINTENANCE_STATE, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LAST_SERVER_COUNT, count).apply()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "记录服务器数量失败：${e.message}")
        }
    }

    /**
     * Clean WorkflowValuesPrefs: remove entries for deleted servers or workflows.
     * Key format: {serverId}_{workflowId}
     *
     * @return Number of entries removed
     */
    private fun cleanWorkflowValuesPrefs(
        context: Context,
        validServerIds: Set<String>,
        validWorkflowIds: Set<String>
    ): Int {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val allKeys = prefs.all.keys.toList()
        val keysToRemove = mutableListOf<String>()

        for (key in allKeys) {
            // Key format: {serverId}_{workflowId}
            val underscoreIndex = key.indexOf('_')
            if (underscoreIndex <= 0) continue

            val serverId = key.substring(0, underscoreIndex)
            val workflowId = key.substring(underscoreIndex + 1)

            val serverInvalid = serverId !in validServerIds
            val workflowInvalid = workflowId !in validWorkflowIds

            if (serverInvalid || workflowInvalid) {
                keysToRemove.add(key)
                if (serverInvalid) {
                    DebugLogger.d(TAG, "WorkflowValues: removing key for deleted server: $key")
                } else {
                    DebugLogger.d(TAG, "WorkflowValues: removing key for deleted workflow: $key")
                }
            }
        }

        if (keysToRemove.isNotEmpty()) {
            val editor = prefs.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }

        return keysToRemove.size
    }

    /**
     * Clean CredentialPrefs: remove credentials for deleted servers.
     * Key format: {prefix}_{serverId} where prefix is username_, password_, or token_
     *
     * 加密存储不可用时直接跳过：绝不回落明文（明文写入比残留数据更危险）。
     *
     * @return Number of entries removed
     */
    private fun cleanCredentialPrefs(context: Context, validServerIds: Set<String>): Int {
        val prefs = try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_CREDENTIALS,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "加密存储不可用，跳过凭据清理：${e.message}")
            return 0
        }

        val allKeys = prefs.all.keys.toList()
        val keysToRemove = mutableListOf<String>()

        for (key in allKeys) {
            val serverId = when {
                key.startsWith(KEY_USERNAME_PREFIX) -> key.removePrefix(KEY_USERNAME_PREFIX)
                key.startsWith(KEY_PASSWORD_PREFIX) -> key.removePrefix(KEY_PASSWORD_PREFIX)
                key.startsWith(KEY_TOKEN_PREFIX) -> key.removePrefix(KEY_TOKEN_PREFIX)
                else -> continue
            }

            if (serverId !in validServerIds) {
                keysToRemove.add(key)
                DebugLogger.d(TAG, "Credentials: removing key for deleted server: $key")
            }
        }

        if (keysToRemove.isNotEmpty()) {
            val editor = prefs.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }

        return keysToRemove.size
    }

    /**
     * Clean screen preferences (TTI, ITI, TTV, ITV): remove entries for deleted servers.
     * Key format: {serverId}_{field}
     *
     * Also validates selectedWorkflowId values to ensure they reference existing workflows.
     *
     * @return Number of entries removed
     */
    private fun cleanScreenPrefs(
        context: Context,
        validServerIds: Set<String>,
        validWorkflowIds: Set<String>
    ): Int {
        val screenPrefsNames = listOf(
            PREFS_TEXT_TO_IMAGE,
            PREFS_IMAGE_TO_IMAGE,
            PREFS_TEXT_TO_VIDEO,
            PREFS_IMAGE_TO_VIDEO
        )

        var totalRemoved = 0

        for (prefsName in screenPrefsNames) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val allEntries = prefs.all.toMap()
            val keysToRemove = mutableListOf<String>()

            for ((key, value) in allEntries) {
                // Key format: {serverId}_{field}
                val underscoreIndex = key.indexOf('_')
                if (underscoreIndex <= 0) continue

                val serverId = key.substring(0, underscoreIndex)
                val field = key.substring(underscoreIndex + 1)

                // Remove if server is deleted
                if (serverId !in validServerIds) {
                    keysToRemove.add(key)
                    DebugLogger.d(TAG, "$prefsName: removing key for deleted server: $key")
                    continue
                }

                // For selectedWorkflowId fields, validate the workflow exists
                if (field == "selectedWorkflowId" || field == "selectedEditingWorkflowId") {
                    val workflowId = value as? String
                    if (workflowId != null && workflowId.isNotEmpty() && workflowId !in validWorkflowIds) {
                        keysToRemove.add(key)
                        DebugLogger.d(TAG, "$prefsName: removing invalid workflow reference: $key -> $workflowId")
                    }
                }
            }

            if (keysToRemove.isNotEmpty()) {
                val editor = prefs.edit()
                keysToRemove.forEach { editor.remove(it) }
                editor.apply()
                totalRemoved += keysToRemove.size
            }
        }

        return totalRemoved
    }

    /**
     * Clean file caches: remove ObjectInfoCache and GalleryMetadataCache files for deleted servers.
     *
     * @return Number of files removed
     */
    private fun cleanFileCaches(context: Context, validServerIds: Set<String>): Int {
        var removed = 0

        // Clean ObjectInfoCache
        val cachedObjectInfoServerIds = ObjectInfoCache.getCachedServerIds(context)
        for (serverId in cachedObjectInfoServerIds) {
            if (serverId !in validServerIds) {
                ObjectInfoCache.clearCache(context, serverId)
                DebugLogger.d(TAG, "ObjectInfoCache: removed cache for deleted server: $serverId")
                removed++
            }
        }

        // Clean GalleryMetadataCache
        // GalleryMetadataCache doesn't have getCachedServerIds, so we scan filesDir manually
        val galleryPrefix = "gallery_metadata_"
        val gallerySuffix = ".json"
        try {
            context.filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(galleryPrefix) && file.name.endsWith(gallerySuffix)) {
                    val serverId = file.name.removePrefix(galleryPrefix).removeSuffix(gallerySuffix)
                    if (serverId !in validServerIds) {
                        GalleryMetadataCache.clearCache(context, serverId)
                        DebugLogger.d(TAG, "GalleryMetadataCache: removed cache for deleted server: $serverId")
                        removed++
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to scan gallery metadata caches: ${e.message}")
        }

        // 已删服务器的媒体文件（预览 / 参考图 / 视频）：判据与 fail-closed 规则见
        // MediaStateHolder.cleanupOrphanedMediaFiles。放在这里而不是新增触发路径 ——
        // 能走到本函数就说明 MaintenancePlan.decide 已返回 Proceed，
        // validServerIds 也通过了「读取失败 / 异常归零」的拦截。
        removed += MediaStateHolder.cleanupOrphanedMediaFiles(context, validServerIds)

        return removed
    }
}