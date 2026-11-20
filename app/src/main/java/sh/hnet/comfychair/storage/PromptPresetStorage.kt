package sh.hnet.comfychair.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.model.ScreenType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PromptPresetStorage"

/** 预设键值存储的最小接口（便于 JVM 单测替换 SharedPreferences）。 */
internal interface PromptPresetKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** 损坏数据的备份落盘点。 */
internal interface PromptPresetCorruptBackup {
    /** @return 备份文件名；原文件不存在或备份失败返回 null */
    fun backup(stamp: String): String?
}

/**
 * Storage for prompt presets using SharedPreferences.
 * Each screen type has its own separate preset library.
 *
 * ## 损坏保护（与 [ServerStorage] 同一策略）
 *
 * 预设是「整份 JSON 一次读写」：解析失败时如果按空列表处理，随后任意一次新增/修改/删除
 * 都会把「空 + 1 条」整份写回，用户原有预设（可能只是某一个字段坏掉、人工还能救）
 * 就被彻底覆盖了。因此：
 * - 解析失败先把 `PromptPresetPrefs.xml` 复制成 `.corrupt-<时间戳>` 备份；
 * - 本次运行内的所有写操作一律拒绝（返回 false），原数据原样保留；
 * - 只有用户主动「清空全部预设」才允许清掉，并同时解除拒绝状态。
 */
class PromptPresetStorage internal constructor(
    private val store: PromptPresetKeyValueStore,
    private val backupCorrupt: PromptPresetCorruptBackup,
    private val stampProvider: () -> String = { currentBackupStamp() },
    private val logWarn: (String, Throwable?) -> Unit = { message, error ->
        Log.w(TAG, message, error)
    }
) {
    constructor(context: Context) : this(
        SharedPrefsPromptPresetStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ),
        FilePromptPresetCorruptBackup(context.applicationContext)
    )

    /** 最近一次读取是否解析失败；失败后所有写操作一律拒绝，绝不覆盖原数据。 */
    @Volatile
    private var lastParseFailed = false

    /** 最近一次损坏备份的时间戳（同一秒内不重复备份，避免刷屏）。 */
    private var lastBackupStamp: String? = null

    companion object {
        const val PREFS_NAME = "PromptPresetPrefs"
        internal const val KEY_PRESETS_JSON = "presets_json"
        const val MAX_FAVORITES_PER_SCREEN = 8
    }

    /** 最近一次读取是否解析失败（调用方可据此给出可见提示）。 */
    fun didLastParseFail(): Boolean = lastParseFailed

    /**
     * Get all presets across all screen types.
     *
     * 解析失败返回空列表（界面仍可用），同时把原文件备份一份并进入「拒绝写入」状态。
     */
    fun getPresets(): List<PromptPreset> {
        val json = store.getString(KEY_PRESETS_JSON)
        if (json == null) {
            lastParseFailed = false
            return emptyList()
        }
        return try {
            val array = JSONArray(json)
            val presets = (0 until array.length()).mapNotNull { i ->
                PromptPreset.fromJson(array.getJSONObject(i))
            }
            lastParseFailed = false
            presets
        } catch (e: Exception) {
            lastParseFailed = true
            logWarn("预设数据解析失败，本次运行拒绝写入以免覆盖原数据：${e.message}", e)
            backupCorruptIfNeeded()
            emptyList()
        }
    }

    /** 首次解析失败时备份原文件（同一时间戳只备份一次）。 */
    private fun backupCorruptIfNeeded() {
        val stamp = stampProvider()
        if (lastBackupStamp == stamp) return
        val backupName = backupCorrupt.backup(stamp)
        if (backupName != null) {
            lastBackupStamp = stamp
            logWarn("已备份损坏的预设配置：$backupName", null)
        }
    }

    /**
     * Get presets for a specific screen type.
     */
    fun getPresetsForScreen(screenType: ScreenType): List<PromptPreset> {
        return getPresets()
            .filter { it.screenType == screenType }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Get favorite presets for a specific screen type.
     * Returns at most MAX_FAVORITES_PER_SCREEN presets, sorted by creation date (newest first).
     */
    fun getFavoritesForScreen(screenType: ScreenType): List<PromptPreset> {
        return getPresetsForScreen(screenType)
            .filter { it.isFavorite }
            .take(MAX_FAVORITES_PER_SCREEN)
    }

    /**
     * Get all unique tags used by presets for a specific screen type.
     * Tags are derived dynamically from existing presets.
     */
    fun getTagsForScreen(screenType: ScreenType): Set<String> {
        return getPresetsForScreen(screenType)
            .flatMap { it.tags }
            .toSet()
    }

    /**
     * Save all presets to storage.
     *
     * @return false 表示此前读取已解析失败，本次写入被拒绝（原数据保留）
     */
    private fun savePresets(presets: List<PromptPreset>): Boolean {
        if (lastParseFailed) {
            logWarn("预设数据此前解析失败，本次写入已拒绝（原数据保留）", null)
            return false
        }
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        store.putString(KEY_PRESETS_JSON, array.toString())
        return true
    }

    /**
     * Add a new preset.
     *
     * @return false 表示存储此前解析失败（已备份原数据），本次写入被拒绝
     */
    fun addPreset(preset: PromptPreset): Boolean {
        val presets = getPresets().toMutableList()
        presets.add(preset)
        return savePresets(presets)
    }

    /**
     * Update an existing preset.
     *
     * @return false 表示存储此前解析失败（已备份原数据），本次写入被拒绝
     */
    fun updatePreset(preset: PromptPreset): Boolean {
        val presets = getPresets().map {
            if (it.id == preset.id) preset else it
        }
        return savePresets(presets)
    }

    /**
     * Delete a preset by ID.
     *
     * @return false 表示存储此前解析失败（已备份原数据），本次写入被拒绝
     */
    fun deletePreset(presetId: String): Boolean {
        val presets = getPresets().filter { it.id != presetId }
        return savePresets(presets)
    }

    /**
     * Clear all presets from storage.
     * Called when user resets all prompts and library from App Settings.
     *
     * 这是用户明确触发的「重置」，即便此前解析失败也允许执行（损坏数据已另有备份），
     * 执行后解除拒绝写入状态。
     */
    fun clearAll() {
        store.remove(KEY_PRESETS_JSON)
        lastParseFailed = false
        lastBackupStamp = null
    }

    /**
     * Get a preset by ID.
     */
    fun getPreset(presetId: String): PromptPreset? {
        return getPresets().find { it.id == presetId }
    }

    /**
     * Toggle the favorite status of a preset.
     * Returns true if successful, false if max favorites reached when trying to favorite
     * （或存储已损坏、本次写入被拒绝）.
     */
    fun toggleFavorite(presetId: String): Boolean {
        val preset = getPreset(presetId) ?: return false

        // If unfavoriting, always allow
        if (preset.isFavorite) {
            return updatePreset(preset.copy(isFavorite = false))
        }

        // If favoriting, check max limit
        val currentFavorites = getFavoritesForScreen(preset.screenType)
        if (currentFavorites.size >= MAX_FAVORITES_PER_SCREEN) {
            return false // Max favorites reached
        }

        return updatePreset(preset.copy(isFavorite = true))
    }

    /**
     * Check if a preset name is already taken for a screen type.
     * Optionally exclude a specific preset ID (for editing).
     */
    fun isNameTaken(name: String, screenType: ScreenType, excludeId: String? = null): Boolean {
        return getPresetsForScreen(screenType).any {
            it.name.equals(name, ignoreCase = true) &&
                (excludeId == null || it.id != excludeId)
        }
    }

    /**
     * Count favorites for a screen type.
     */
    fun getFavoriteCount(screenType: ScreenType): Int {
        return getPresetsForScreen(screenType).count { it.isFavorite }
    }
}

/** 备份时间戳格式：与 [ServerStorage] 保持一致（`yyyyMMdd-HHmmss`）。 */
private fun currentBackupStamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

/** SharedPreferences 版键值存储。 */
private class SharedPrefsPromptPresetStore(
    private val prefs: SharedPreferences
) : PromptPresetKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * 把损坏的 `PromptPresetPrefs.xml` 复制一份（`<名>.corrupt-<时间戳>`）。
 *
 * 直接拷 SharedPreferences 的 XML 原文件，而不是把解析失败的 JSON 片段写进新文件：
 * XML 里保留了用户所有键值，事后可以人工挑出还能救的预设。
 */
private class FilePromptPresetCorruptBackup(
    private val context: Context
) : PromptPresetCorruptBackup {
    override fun backup(stamp: String): String? {
        return try {
            val prefsFile = File(
                context.dataDir,
                "shared_prefs/${PromptPresetStorage.PREFS_NAME}.xml"
            )
            if (!prefsFile.exists()) return null
            val backup = File(
                prefsFile.parentFile,
                "${PromptPresetStorage.PREFS_NAME}.xml.corrupt-$stamp"
            )
            prefsFile.copyTo(backup, overwrite = true)
            backup.name
        } catch (e: Exception) {
            Log.e(TAG, "备份损坏的预设配置失败", e)
            null
        }
    }
}