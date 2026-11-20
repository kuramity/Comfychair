package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P1-18：备份恢复的工作流文件名必须挡得住路径穿越。
 *
 * 攻击路径（修复前成立）：构造一份备份，把某条 userWorkflows 的
 * `"filename": "../../evil.json"`，用户导入后 `File(dir, filename).writeText(...)`
 * 就写到 `user_workflows` 目录之外（`filesDir` 下一层，两个 `../` 即回到 `filesDir`，
 * 三个 `../` 就出了应用私有目录）。
 *
 * 这里直接断言策略层：穿越形态一律拒绝，合法文件名（含中文）照常放行。
 */
class WorkflowFilePolicyTest {

    private fun tempDir(): File = Files.createTempDirectory("wf-policy").toFile()

    /** 修复前的裸 `File(dir, filename)` 路径确实会跑到目录外——证明攻击路径真实存在 */
    @Test
    fun `裸拼接会逃出目标目录（攻击前提成立）`() {
        val dir = tempDir()
        try {
            val naive = File(dir, "../../evil.json").canonicalFile
            assertNotEquals(
                "修复前 File(dir, filename) 会落到目标目录之外",
                dir.canonicalPath,
                naive.parentFile?.canonicalPath
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `穿越与非法文件名一律拒绝`() {
        val dir = tempDir()
        try {
            val evilNames = listOf(
                "../../evil.json",
                "../../../shared_prefs/AppSettings.xml",
                "..\\..\\evil.json",
                "..",
                ".",
                "sub/evil.json",
                "sub\\evil.json",
                "/data/data/sh.hnet.comfychair.plus/files/evil.json",
                "C:\\Windows\\evil.json",
                "a\u0000b.json",
                "line\nbreak.json",
                ".hidden.json",
                "",
                "   ",
                null,
            )
            for (name in evilNames) {
                assertFalse("isValidFilename('$name') 应为 false", WorkflowFilePolicy.isValidFilename(name))
                assertNull("resolveInDir('$name') 应为 null", WorkflowFilePolicy.resolveInDir(dir, name))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `合法文件名解析到目录内的直接子项`() {
        val dir = tempDir()
        try {
            val okNames = listOf(
                "tti_写实流.json",
                "iti_my-workflow.v2.json",
                "tvi_batch_2.json",
            )
            for (name in okNames) {
                assertTrue("isValidFilename('$name') 应为 true", WorkflowFilePolicy.isValidFilename(name))
                val resolved = WorkflowFilePolicy.resolveInDir(dir, name)
                assertNotNull("resolveInDir('$name') 不应为 null", resolved)
                assertEquals(dir.canonicalPath, resolved!!.parentFile?.canonicalPath)
                assertEquals(name, resolved.name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `写出后文件确实落在目标目录内`() {
        val dir = tempDir()
        try {
            val target = WorkflowFilePolicy.resolveInDir(dir, "tti_ok.json")
            assertNotNull(target)
            target!!.writeText("{}")
            assertTrue(target.isFile)
            // 目标目录的上一级（相当于 filesDir）不应出现被写到外面的文件
            val parent = dir.parentFile
            assertNull(parent?.listFiles()?.firstOrNull { it.name == "tti_ok.json" && it.parentFile == parent })
        } finally {
            dir.deleteRecursively()
        }
    }
}
