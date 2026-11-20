package sh.hnet.comfychair.storage

/**
 * 凭据落盘策略（纯函数，便于 JVM 单测）。
 *
 * 加密存储不可用时返回 false：宁可丢凭据（用户重新登录），也不落明文。
 */
object CredentialPersistencePolicy {
    fun shouldPersist(encryptionAvailable: Boolean): Boolean = encryptionAvailable
}