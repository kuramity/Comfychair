package sh.hnet.comfychair.storage

/**
 * 服务器配置写操作前的备份判定（纯函数，便于 JVM 单测）。
 *
 * 背景：`ServerStorage.getServers()` 解析失败时会返回空列表，界面因此显示「没有服务器」。
 * 用户此时新增/编辑/删除服务器，写操作会把损坏但可能仍可人工恢复的原文件直接覆盖。
 * 因此在写之前先把现有 `ServerPrefs.xml` 备份一份；同一秒内已经备份过就跳过，避免刷屏。
 */
internal object ServerPrefsWriteGuard {

    /**
     * @param lastParseFailed 上一次读取是否解析失败
     * @param lastBackupStamp 上一次损坏备份的时间戳（`yyyyMMdd-HHmmss`）；null 表示尚未备份
     * @param currentStamp 本次写操作的时间戳
     * @return true 表示写之前需要先备份
     */
    fun shouldBackupBeforeWrite(
        lastParseFailed: Boolean,
        lastBackupStamp: String?,
        currentStamp: String
    ): Boolean = lastParseFailed && lastBackupStamp != currentStamp
}
