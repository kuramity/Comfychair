package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import sh.hnet.comfychair.util.UuidUtils

/**
 * Represents a ComfyUI server configuration.
 */
@Immutable
data class Server(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int,
    val authType: AuthType = AuthType.NONE,
    /** 用户显式选择的协议："http" / "https"；null 表示自动（默认先试 HTTPS，不做明文降级）。 */
    val protocol: String? = null
) {
    companion object {
        /**
         * 合法的服务器 ID 形态：标准 UUID（8-4-4-4-12 十六进制）。
         *
         * **为什么必须校验（P1-18）**：这个 id 会被拼进文件名
         * （`MediaStateHolder` 的媒体缓存前缀、`ObjectInfoCache`、`GalleryMetadataCache`），
         * 值来自 ServerPrefs 与备份 JSON 这类外部输入。`id` 里带上 `../` 就能把
         * 缓存文件写到 `filesDir` 之外；本应用自身生成 id 只走 [UuidUtils]，
         * 因此格式不符的条目一律视为损坏数据丢弃。
         */
        private val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        /** id 是否是合法 UUID（供存储层/界面复用同一口径） */
        fun isValidId(id: String): Boolean = UUID_PATTERN.matches(id)

        /**
         * Create a new server with auto-generated UUID.
         */
        fun create(
            name: String,
            hostname: String,
            port: Int,
            authType: AuthType = AuthType.NONE,
            protocol: String? = null
        ): Server {
            return Server(
                id = UuidUtils.generateRandomId(),
                name = name,
                hostname = hostname,
                port = port,
                authType = authType,
                protocol = protocol
            )
        }

        /**
         * Parse server from JSON object.
         * Returns null if required fields are missing.
         */
        fun fromJson(json: JSONObject): Server? {
            val id = json.optString("id").trim()
                .takeIf { it.isNotEmpty() && isValidId(it) } ?: return null
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val hostname = json.optString("hostname").takeIf { it.isNotEmpty() } ?: return null
            val port = json.optInt("port", -1).takeIf { it > 0 } ?: return null
            val authType = try {
                AuthType.valueOf(json.optString("authType", "NONE"))
            } catch (e: IllegalArgumentException) {
                AuthType.NONE
            }
            // 旧数据没有 protocol 字段：缺失或非法值一律按「自动」，保持旧行为
            val protocol = json.optString("protocol", "").trim().lowercase()
                .takeIf { it == "http" || it == "https" }

            return Server(id, name, hostname, port, authType, protocol)
        }
    }

    /**
     * Convert server to JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("hostname", hostname)
            put("port", port)
            put("authType", authType.name)
            // 仅显式协议才写字段，避免把旧数据无谓地改写成「自动」
            protocol?.let { put("protocol", it) }
        }
    }

    /**
     * Get display string for connection (hostname:port).
     */
    fun getConnectionString(): String = "$hostname:$port"
}
