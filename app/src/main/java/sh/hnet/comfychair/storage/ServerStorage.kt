package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent storage for server configurations.
 */
class ServerStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近一次读取是否解析失败；解析失败时写操作一律拒绝，绝不覆盖原文件。 */
    @Volatile
    private var lastParseFailed = false

    /** 最近一次读取是否解析失败。 */
    fun didLastParseFail(): Boolean = lastParseFailed

    /** 服务器列表的读取结果：读取失败必须能被调用方感知，不能静默当成「空列表」。 */
    sealed interface LoadResult {
        data class Success(val servers: List<Server>) : LoadResult
        data class Failure(val error: Exception) : LoadResult
    }

    companion object {
        private const val TAG = "ServerStorage"
        private const val PREFS_NAME = "ServerPrefs"
        private const val KEY_SERVERS_JSON = "servers_json"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_CERT_PIN_PREFIX = "cert_pin_"
    }

    /**
     * 读取某台服务器已信任证书的 SHA-256 指纹（小写十六进制）。
     * 返回 null 表示尚未信任过（首次连接走 TOFU）。
     */
    fun getCertificatePin(serverId: String): String? {
        return prefs.getString(KEY_CERT_PIN_PREFIX + serverId, null)
    }

    /**
     * 保存证书指纹（TOFU 首次信任时由连接层回调写入）。
     */
    fun setCertificatePin(serverId: String, fingerprintHex: String) {
        prefs.edit().putString(KEY_CERT_PIN_PREFIX + serverId, fingerprintHex).apply()
    }

    /**
     * 清除证书指纹。私网服务器换证书后由界面触发「重新信任」，清除后重连一次即可。
     */
    fun clearCertificatePin(serverId: String) {
        prefs.edit().remove(KEY_CERT_PIN_PREFIX + serverId).apply()
    }

    /**
     * Get all saved servers.
     */
    fun getServers(): List<Server> = when (val result = loadServers()) {
        is LoadResult.Success -> result.servers
        is LoadResult.Failure -> emptyList()
    }

    /**
     * 读取服务器列表并保留失败信息，供启动清理等需要 fail-closed 的调用方使用。
     *
     * 解析失败时把原文件复制为 `ServerPrefs.xml.corrupt-<时间戳>`，
     * 绝不把现有配置覆盖成空。
     */
    fun loadServers(): LoadResult {
        val json = prefs.getString(KEY_SERVERS_JSON, null)
        if (json == null) {
            lastParseFailed = false
            return LoadResult.Success(emptyList())
        }
        return try {
            val array = JSONArray(json)
            val servers = (0 until array.length()).mapNotNull { i ->
                Server.fromJson(array.getJSONObject(i))
            }
            lastParseFailed = false
            LoadResult.Success(servers)
        } catch (e: Exception) {
            lastParseFailed = true
            DebugLogger.e(TAG, "服务器列表解析失败，保留原文件：${e.message}")
            backupCorruptPrefsFile()
            LoadResult.Failure(e)
        }
    }

    private fun currentBackupStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * 把损坏的 ServerPrefs.xml 另存一份，便于事后排查与人工恢复。
     *
     * @return true 表示已安全备份（或原文件本就不存在），false 表示备份失败
     */
    private fun backupCorruptPrefsFile(stamp: String = currentBackupStamp()): Boolean {
        try {
            val prefsFile = File(appContext.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (!prefsFile.exists()) return true
            val backup = File(prefsFile.parentFile, "$PREFS_NAME.xml.corrupt-$stamp")
            prefsFile.copyTo(backup, overwrite = true)
            DebugLogger.w(TAG, "已备份损坏的服务器配置：${backup.name}")
            return true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "备份损坏的服务器配置失败：${e.message}")
            return false
        }
    }

    /**
     * Save all servers (replaces existing list).
     */
    fun saveServers(servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS_JSON, array.toString()).apply()
        lastParseFailed = false
    }

    /**
     * Add a new server.
     *
     * @return false 表示列表解析失败（已备份原文件），本次写入被拒绝 —— 调用方必须给出可见错误
     */
    fun addServer(server: Server): Boolean {
        val loaded = loadServers()
        if (loaded is LoadResult.Failure) return false
        val servers = (loaded as LoadResult.Success).servers.toMutableList()
        servers.add(server)
        saveServers(servers)
        return true
    }

    /**
     * Update an existing server.
     *
     * @return false 表示列表解析失败（已备份原文件），本次写入被拒绝 —— 调用方必须给出可见错误
     */
    fun updateServer(server: Server): Boolean {
        val loaded = loadServers()
        if (loaded is LoadResult.Failure) return false
        val servers = (loaded as LoadResult.Success).servers.map {
            if (it.id == server.id) server else it
        }
        saveServers(servers)
        return true
    }

    /**
     * Delete a server by ID.
     *
     * @return false 表示列表解析失败（已备份原文件），本次写入被拒绝 —— 调用方必须给出可见错误
     */
    fun deleteServer(serverId: String): Boolean {
        val loaded = loadServers()
        if (loaded is LoadResult.Failure) return false
        val servers = (loaded as LoadResult.Success).servers.filter { it.id != serverId }
        saveServers(servers)

        // Clear selection if deleted server was selected
        if (getSelectedServerId() == serverId) {
            setSelectedServerId(null)
        }
        return true
    }

    /**
     * Get a server by ID.
     */
    fun getServer(serverId: String): Server? {
        return getServers().find { it.id == serverId }
    }

    /**
     * Get the currently selected server ID.
     */
    fun getSelectedServerId(): String? {
        return prefs.getString(KEY_SELECTED_SERVER_ID, null)
    }

    /**
     * Set the currently selected server ID.
     */
    fun setSelectedServerId(serverId: String?) {
        if (serverId != null) {
            prefs.edit().putString(KEY_SELECTED_SERVER_ID, serverId).apply()
        } else {
            prefs.edit().remove(KEY_SELECTED_SERVER_ID).apply()
        }
    }

    /**
     * Get the currently selected server.
     */
    fun getSelectedServer(): Server? {
        val id = getSelectedServerId() ?: return null
        return getServer(id)
    }

    /**
     * Check if a server name is already taken (case-insensitive).
     * @param excludeServerId Server ID to exclude from check (for editing)
     */
    fun isServerNameTaken(name: String, excludeServerId: String? = null): Boolean {
        return getServers().any {
            it.name.equals(name, ignoreCase = true) &&
            (excludeServerId == null || it.id != excludeServerId)
        }
    }
}
