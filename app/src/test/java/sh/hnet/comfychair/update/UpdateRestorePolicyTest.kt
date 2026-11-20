package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P2-11：冷启动恢复 update.apk 的复验判据。
 *
 * 修复前的行为是「文件存在且非空 → READY_TO_INSTALL」，等价于承认磁盘上任何 APK
 * 都是官方包。这个测试钉住三点：
 * 1. 只有和清单 sha256 完全一致才算可信；
 * 2. 缺期望值（老版本下载、旁车丢失）与不一致都不可信（fail-closed）；
 * 3. sha256Of 对真实文件算出的值与标准值一致，且文件缺失时返回 null。
 */
class UpdateRestorePolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val shaOfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `sha256 与清单一致才可信`() {
        assertEquals(
            UpdateRestorePolicy.Verdict.VERIFIED,
            UpdateRestorePolicy.verify(shaOfAbc, shaOfAbc)
        )
    }

    @Test
    fun `期望值大小写与空白不敏感，实际值大小写不敏感`() {
        assertEquals(
            UpdateRestorePolicy.Verdict.VERIFIED,
            UpdateRestorePolicy.verify("  ${shaOfAbc.uppercase()}\n", shaOfAbc)
        )
        assertEquals(
            UpdateRestorePolicy.Verdict.VERIFIED,
            UpdateRestorePolicy.verify(shaOfAbc, shaOfAbc.uppercase())
        )
    }

    @Test
    fun `磁盘文件与清单不一致判 MISMATCH`() {
        val other = "0".repeat(64)
        assertEquals(
            UpdateRestorePolicy.Verdict.MISMATCH,
            UpdateRestorePolicy.verify(shaOfAbc, other)
        )
    }

    @Test
    fun `缺期望值一律不可信（fail-closed）`() {
        val missingValues = listOf(null, "", "   ", "not-a-sha", shaOfAbc.dropLast(1))
        for (expected in missingValues) {
            assertEquals(
                "期望值 [$expected] 不得让校验放行",
                UpdateRestorePolicy.Verdict.NO_EXPECTED_SHA,
                UpdateRestorePolicy.verify(expected, shaOfAbc)
            )
        }
    }

    @Test
    fun `实算值缺失或为空判 MISMATCH`() {
        assertEquals(
            UpdateRestorePolicy.Verdict.MISMATCH,
            UpdateRestorePolicy.verify(shaOfAbc, null)
        )
        assertEquals(
            UpdateRestorePolicy.Verdict.MISMATCH,
            UpdateRestorePolicy.verify(shaOfAbc, "  ")
        )
    }

    @Test
    fun `对真实文件流式计算 sha256`() {
        val file = tmp.newFile("abc.bin").apply { writeText("abc") }
        assertEquals(shaOfAbc, UpdateRestorePolicy.sha256Of(file))
    }

    @Test
    fun `文件不存在时返回 null`() {
        assertNull(UpdateRestorePolicy.sha256Of(File(tmp.root, "missing.apk")))
    }

    @Test
    fun `被替换的文件复验不通过`() {
        val file = tmp.newFile("update.apk")
        file.writeText("official-apk-bytes")
        val officialSha = UpdateRestorePolicy.sha256Of(file)!!

        // 同一路径换成另一份内容：期望值仍是官方包，实算值必然不同
        file.writeText("tampered-apk-bytes")
        val tamperedSha = UpdateRestorePolicy.sha256Of(file)!!

        assertEquals(
            UpdateRestorePolicy.Verdict.VERIFIED,
            UpdateRestorePolicy.verify(officialSha, officialSha)
        )
        assertEquals(
            UpdateRestorePolicy.Verdict.MISMATCH,
            UpdateRestorePolicy.verify(officialSha, tamperedSha)
        )
    }

    @Test
    fun `恢复链路接线：先复验 sha256 再走安装闸门才允许置就绪`() {
        val full = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = full.indexOf("fun restoreFromDisk(")
        assertTrue("找不到 restoreFromDisk", start >= 0)
        // 只看恢复方法本身：publishReady 里也有一处 READY_TO_INSTALL，全文搜索会误判
        val body = full.substring(start)

        val verify = body.indexOf("UpdateRestorePolicy.verify(")
        val trust = body.indexOf("AppInstaller.isApkTrusted(")
        val ready = body.indexOf("phase = UpdatePhase.READY_TO_INSTALL")
        assertTrue("restoreFromDisk 必须调用 UpdateRestorePolicy.verify", verify >= 0)
        assertTrue("restoreFromDisk 必须复用 AppInstaller.isApkTrusted 的包名/签名校验", trust >= 0)
        assertTrue(
            "复验（$verify）与签名闸门（$trust）都必须排在置 READY_TO_INSTALL（$ready）之前",
            ready > verify && ready > trust
        )
    }

    @Test
    fun `下载完成后把清单 sha256 落盘供冷启动复验`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt")
        assertTrue(
            "Worker 必须在 SHA-256 校验通过后写 sha256 旁车（否则冷启动无法复验）",
            body.contains("shaFile(applicationContext).writeText(")
        )
        assertTrue(
            "旁车文件名必须与恢复侧共用同一个常量",
            body.contains("const val SHA_FILE_NAME")
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}