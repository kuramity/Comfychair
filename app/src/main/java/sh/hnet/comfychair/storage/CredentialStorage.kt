package sh.hnet.comfychair.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.util.DebugLogger

/**
 * Secure storage for authentication credentials using EncryptedSharedPreferences.
 * Credentials are stored separately from server configuration for security.
 *
 * 加密存储创建失败时 [prefs] 为 null：此时不落盘、不回落明文，
 * 读取一律按「无凭据」处理，等价于要求用户重新登录。
 */
class CredentialStorage(context: Context) {

    companion object {
        private const val TAG = "CredentialStorage"
        private const val PREFS_NAME = "CredentialPrefs"
        private const val KEY_USERNAME_PREFIX = "username_"
        private const val KEY_PASSWORD_PREFIX = "password_"
        private const val KEY_TOKEN_PREFIX = "token_"
        private const val KEY_COOKIES_PREFIX = "cookies_"
    }

    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "加密存储不可用，凭据将不落盘：${e.message}")
            null
        }
    }

    /** 加密存储是否可用；不可用时所有写操作被跳过。 */
    val isPersistenceAvailable: Boolean get() = prefs != null

    /**
     * Save credentials for a server.
     * @param serverId The server UUID
     * @param credentials The credentials to save
     */
    fun saveCredentials(serverId: String, credentials: AuthCredentials) {
        if (!CredentialPersistencePolicy.shouldPersist(prefs != null)) {
            DebugLogger.w(TAG, "加密存储不可用，跳过凭据写入（不回落明文）")
            return
        }
        val store = prefs ?: return
        store.edit().apply {
            // Clear all existing credentials first
            remove("$KEY_USERNAME_PREFIX$serverId")
            remove("$KEY_PASSWORD_PREFIX$serverId")
            remove("$KEY_TOKEN_PREFIX$serverId")
            remove("$KEY_COOKIES_PREFIX$serverId")

            when (credentials) {
                is AuthCredentials.None -> { /* Nothing to store */ }
                is AuthCredentials.Basic -> {
                    putString("$KEY_USERNAME_PREFIX$serverId", credentials.username)
                    putString("$KEY_PASSWORD_PREFIX$serverId", credentials.password)
                }
                is AuthCredentials.Bearer -> {
                    putString("$KEY_TOKEN_PREFIX$serverId", credentials.token)
                }
                is AuthCredentials.Cookie -> {
                    putString("$KEY_COOKIES_PREFIX$serverId", credentials.cookies)
                }
            }
            apply()
        }
        DebugLogger.d(TAG, "Saved credentials for server $serverId")
    }

    /**
     * Get credentials for a server.
     * @param serverId The server UUID
     * @param authType The expected auth type (from Server model)
     * @return The credentials, or AuthCredentials.None if not found
     */
    fun getCredentials(serverId: String, authType: AuthType): AuthCredentials {
        if (authType == AuthType.NONE) return AuthCredentials.None

        val store = prefs ?: run {
            DebugLogger.w(TAG, "加密存储不可用，按无凭据处理（需要重新登录）")
            return AuthCredentials.None
        }

        return try {
            when (authType) {
                AuthType.NONE -> AuthCredentials.None
                AuthType.BASIC -> {
                    val username = store.getString("$KEY_USERNAME_PREFIX$serverId", null)
                    val password = store.getString("$KEY_PASSWORD_PREFIX$serverId", null)
                    if (username != null && password != null) {
                        AuthCredentials.Basic(username, password)
                    } else {
                        AuthCredentials.None
                    }
                }
                AuthType.BEARER -> {
                    val token = store.getString("$KEY_TOKEN_PREFIX$serverId", null)
                    if (token != null) {
                        AuthCredentials.Bearer(token)
                    } else {
                        AuthCredentials.None
                    }
                }
                AuthType.BROWSER -> {
                    val cookies = store.getString("$KEY_COOKIES_PREFIX$serverId", null)
                    if (cookies != null) {
                        AuthCredentials.Cookie(cookies)
                    } else {
                        AuthCredentials.None
                    }
                }
            }
        } catch (e: Exception) {
            // 解密失败（例如从旧设备迁移过来的密文）：只清当前服务器这一份脏数据，
            // 避免每次启动反复失败；绝不能清空整个文件，否则会连带删掉其它服务器的凭据
            DebugLogger.e(TAG, "读取凭据失败，仅清除该服务器的损坏数据：${e.message}")
            try {
                store.edit()
                    .remove("$KEY_USERNAME_PREFIX$serverId")
                    .remove("$KEY_PASSWORD_PREFIX$serverId")
                    .remove("$KEY_TOKEN_PREFIX$serverId")
                    .remove("$KEY_COOKIES_PREFIX$serverId")
                    .apply()
            } catch (clearError: Exception) {
                DebugLogger.e(TAG, "清除损坏凭据失败：${clearError.message}")
            }
            AuthCredentials.None
        }
    }

    /**
     * Delete credentials for a server.
     * Should be called when a server is deleted.
     */
    fun deleteCredentials(serverId: String) {
        val store = prefs ?: return
        store.edit().apply {
            remove("$KEY_USERNAME_PREFIX$serverId")
            remove("$KEY_PASSWORD_PREFIX$serverId")
            remove("$KEY_TOKEN_PREFIX$serverId")
            remove("$KEY_COOKIES_PREFIX$serverId")
            apply()
        }
        DebugLogger.d(TAG, "Deleted credentials for server $serverId")
    }

    /**
     * Check if credentials exist for a server.
     */
    fun hasCredentials(serverId: String, authType: AuthType): Boolean {
        if (authType == AuthType.NONE) return true
        val store = prefs ?: return false
        return try {
            when (authType) {
                AuthType.NONE -> true
                AuthType.BASIC -> {
                    store.getString("$KEY_USERNAME_PREFIX$serverId", null) != null &&
                        store.getString("$KEY_PASSWORD_PREFIX$serverId", null) != null
                }
                AuthType.BEARER -> {
                    store.getString("$KEY_TOKEN_PREFIX$serverId", null) != null
                }
                AuthType.BROWSER -> {
                    store.getString("$KEY_COOKIES_PREFIX$serverId", null) != null
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "检查凭据失败：${e.message}")
            false
        }
    }
}