package sh.hnet.comfychair.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器配置写操作前的备份判定。
 *
 * A4-4 回归点：解析失败后界面显示空列表，新增服务器会以空列表覆盖原文件；
 * 写之前必须先备份，且同一秒内不重复备份。
 */
class ServerPrefsWriteGuardTest {

    @Test
    fun `上次解析失败且本秒未备份时需要先备份`() {
        assertTrue(
            ServerPrefsWriteGuard.shouldBackupBeforeWrite(
                lastParseFailed = true,
                lastBackupStamp = null,
                currentStamp = "20261118-210000"
            )
        )
    }

    @Test
    fun `上次解析失败但同秒已备份时跳过`() {
        assertFalse(
            ServerPrefsWriteGuard.shouldBackupBeforeWrite(
                lastParseFailed = true,
                lastBackupStamp = "20261118-210000",
                currentStamp = "20261118-210000"
            )
        )
    }

    @Test
    fun `上次解析失败且跨秒后再次备份`() {
        assertTrue(
            ServerPrefsWriteGuard.shouldBackupBeforeWrite(
                lastParseFailed = true,
                lastBackupStamp = "20261118-205959",
                currentStamp = "20261118-210000"
            )
        )
    }

    @Test
    fun `上次解析成功时不备份`() {
        assertFalse(
            ServerPrefsWriteGuard.shouldBackupBeforeWrite(
                lastParseFailed = false,
                lastBackupStamp = null,
                currentStamp = "20261118-210000"
            )
        )
    }
}
