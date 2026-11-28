package sh.hnet.comfychair.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 技能提示词覆盖层（B5：服务器下发的 SKILL_MD 覆盖 assets 内置版）的纯 JVM 测试。
 *
 * 覆盖语义：
 * - list：overlay 清单 ∪ assets 清单，同名 **overlay 优先**，按文件名排序；
 * - read：先 overlay 后 assets——下发的规则文件覆盖同名内置版；
 * - 只认 .md / .txt（与 assets 侧过滤一致），overlay 目录不存在 = 无覆盖层。
 */
class SkillPromptOverlayTest {

    private fun tempDir(): File = Files.createTempDirectory("skill-overlay").toFile()

    // ---------------- ① mergeNames：list 的合并谓词 ----------------

    @Test
    fun `overlay 同名条目优先于 assets`() {
        val merged = SkillPromptRepository.mergeNames(
            overlayNames = listOf("Qwen-Image-2.1-文生图.md"),
            assetNames = listOf("Qwen-Image-2.1-文生图.md", "Qwen-Image-2.1-图像编辑.md"),
        )

        assertEquals(2, merged.size)
        // 两条都保留（按文件名排序），但下发的来源标记为 overlay（读取时优先读 overlay 同名文件）
        assertEquals(
            listOf("Qwen-Image-2.1-图像编辑.md", "Qwen-Image-2.1-文生图.md"),
            merged.map { it.fileName },
        )
        val overridden = merged.first { it.fileName == "Qwen-Image-2.1-文生图.md" }
        assertTrue("同名条目必须来自 overlay", overridden.fromOverlay)
        val builtin = merged.first { it.fileName == "Qwen-Image-2.1-图像编辑.md" }
        assertTrue(!builtin.fromOverlay)
    }

    @Test
    fun `mergeNames 按文件名排序且显示名去扩展名`() {
        val merged = SkillPromptRepository.mergeNames(
            overlayNames = listOf("b.md", "a.txt"),
            assetNames = listOf("c.md"),
        )

        assertEquals(listOf("a", "b", "c"), merged.map { it.displayName })
        assertEquals(listOf("a.txt", "b.md", "c.md"), merged.map { it.fileName })
    }

    @Test
    fun `mergeNames 只认 md 与 txt`() {
        val merged = SkillPromptRepository.mergeNames(
            overlayNames = listOf("keep.md", "skip.pdf", "skip2.JSON"),
            assetNames = emptyList(),
        )
        assertEquals(listOf("keep.md"), merged.map { it.fileName })
    }

    // ---------------- ② readFromOverlay：read 的 overlay 侧 ----------------

    @Test
    fun `overlay 中存在同名文件时读到下发行`() {
        val dir = tempDir()
        try {
            File(dir, "规则.md").writeText("下发的规则正文", Charsets.UTF_8)

            assertEquals("下发的规则正文", SkillPromptRepository.readFromOverlay(dir, "规则.md"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `overlay 目录或文件不存在时返回 null（由调用方回退 assets）`() {
        assertNull(SkillPromptRepository.readFromOverlay(null, "规则.md"))

        val dir = tempDir()
        try {
            assertNull(SkillPromptRepository.readFromOverlay(dir, "不存在.md"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `overlay 读取失败时返回 null 而不抛`() {
        // 传一个「文件路径当目录」用：其下任何 listFiles/读取都会失败
        val notADir = java.nio.file.Files.createTempFile("not-a-dir", ".tmp").toFile()
        try {
            assertNull(SkillPromptRepository.readFromOverlay(notADir, "规则.md"))
        } finally {
            notADir.delete()
        }
    }
}
