package sh.hnet.comfychair.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-24：备份恢复时的服务器条目校验。
 *
 * 修复前 `BackupValidator.validateHostname/validatePort` 是死代码 —— 恢复只依赖
 * `Server.fromJson` 的「非空 + 端口 > 0」，畸形地址（含空格、带路径、端口 70000）
 * 会被原样写进服务器列表。这里钉住判据本身，并用源码断言钉住接线：
 * 恢复循环必须逐台调用 `validateServer` 并累计跳过数。
 */
class BackupServerValidationTest {

    private val validator = BackupValidator()

    @Test
    fun `合法地址与端口`() {
        val cases = listOf(
            "192.168.1.20" to 8188,
            "10.0.0.5" to 80,
            "127.0.0.1" to 65535,
            "comfyui.local" to 8188,
            "my-server.lan" to 1,
            "http://192.168.1.20" to 8188,
            "https://comfy.example.com" to 443
        )
        for ((host, port) in cases) {
            assertTrue("$host:$port 应判为合法", validator.validateServer(host, port))
        }
    }

    @Test
    fun `地址格式不合法一律拒绝`() {
        val badHosts = listOf(
            "",
            "   ",
            "192.168.1.20 with space",
            "192.168.1.20/24",
            "192.168.1",
            "999.1.1.1",
            "http://",
            "host:8188",
            "-bad-.local",
            "host_with_underscore"
        )
        for (host in badHosts) {
            assertFalse("[$host] 应判为非法地址", validator.validateServer(host, 8188))
        }
    }

    @Test
    fun `端口越界一律拒绝`() {
        for (port in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            assertFalse(
                "端口 $port 应判为非法",
                validator.validateServer("192.168.1.20", port)
            )
        }
    }

    @Test
    fun `端口边界值合法`() {
        assertTrue(validator.validateServer("192.168.1.20", BackupValidator.MIN_PORT))
        assertTrue(validator.validateServer("192.168.1.20", BackupValidator.MAX_PORT))
    }

    @Test
    fun `恢复循环逐台校验并计数跳过`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/storage/BackupManager.kt")

        val validate = body.indexOf("validator.validateServer(")
        val addServer = body.indexOf("serverStorage.addServer(")
        assertTrue("恢复循环必须调用 validator.validateServer", validate >= 0)
        assertTrue(
            "校验（$validate）必须排在写服务器列表（$addServer）之前",
            addServer > validate
        )
        assertTrue(
            "非法条目必须累加 skippedServers 计数",
            body.contains("skippedServers++")
        )
        assertTrue(
            "RestoreResult.Success 必须携带 skippedServers 供界面提示",
            body.contains("skippedServers = skippedServers")
        )
    }

    @Test
    fun `界面按跳过个数提示`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/viewmodel/SettingsViewModel.kt")
        assertTrue(
            "恢复完成后必须按 skippedServers 发带计数的提示",
            body.contains("ShowToastWithCount(") &&
                body.contains("msg_backup_restore_skipped_servers")
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}