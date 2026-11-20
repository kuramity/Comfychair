package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P1-20 的单元测试：图库索引 / 墓碑文件必须**原子写**。
 *
 * 修复前是 `File.writeText`（先截断再写）：写到一半被杀进程就留下半截 JSON，
 * 下次启动解析失败 → 图库索引整份作废（用户看到"图全没了"）。
 *
 * 现在的写入路径是：写 `<file>.tmp` → flush + fsync → 原子改名覆盖目标。
 * 目标文件在任何时刻都只能是"旧的完整内容"或"新的完整内容"。
 */
class GalleryMetadataAtomicWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `首次写入生成完整文件且不残留临时文件`() {
        val target = File(tempFolder.root, "gallery_metadata_server1.json")
        GalleryMetadataCache.writeTextAtomically(target, """[{"promptId":"p1"}]""")

        assertTrue(target.isFile)
        assertEquals("""[{"promptId":"p1"}]""", target.readText())
        assertFalse(File(tempFolder.root, target.name + ".tmp").exists())
    }

    @Test
    fun `覆盖写以新内容整体替换旧内容`() {
        // Windows 上直接 renameTo 覆盖已存在文件会失败——这条测试也钉住回退路径
        val target = File(tempFolder.root, "gallery_deleted_server1.json")
        GalleryMetadataCache.writeTextAtomically(target, """["old-prompt"]""")
        GalleryMetadataCache.writeTextAtomically(target, """["new-prompt-1","new-prompt-2"]""")

        assertEquals("""["new-prompt-1","new-prompt-2"]""", target.readText())
        assertFalse(File(tempFolder.root, target.name + ".tmp").exists())
    }

    @Test
    fun `上次崩溃残留的半截临时文件不会污染本次写入`() {
        val target = File(tempFolder.root, "gallery_metadata_server1.json")
        // 模拟上一次写到一半留下的垃圾
        File(tempFolder.root, target.name + ".tmp").writeText("""[{"promptId":""")

        GalleryMetadataCache.writeTextAtomically(target, """[{"promptId":"p1"}]""")

        assertEquals("""[{"promptId":"p1"}]""", target.readText())
        assertFalse(File(tempFolder.root, target.name + ".tmp").exists())
    }

    @Test
    fun `中文与转义字符按 UTF-8 原样往返`() {
        val target = File(tempFolder.root, "gallery_metadata_server1.json")
        val content = """[{"filename":"精修_00002_.png","note":"换行\\n与制表\\t"}]"""
        GalleryMetadataCache.writeTextAtomically(target, content)

        assertEquals(content, target.readText(Charsets.UTF_8))
    }
}