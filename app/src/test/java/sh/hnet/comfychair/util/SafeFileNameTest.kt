package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-4：3D 产物落盘前的文件名净化。
 *
 * filename 来自 /history（外部输入），不能直接拼进 `File(filesDir/model3d, name)`。
 */
class SafeFileNameTest {

    @Test
    fun `斜杠路径被截成最后一段`() {
        assertEquals("model.glb", SafeFileName.sanitize("../../shared_prefs/model.glb"))
        assertEquals("model.glb", SafeFileName.sanitize("a/b/c/model.glb"))
    }

    @Test
    fun `反斜杠路径同样被截断`() {
        assertEquals("model.glb", SafeFileName.sanitize("..\\..\\model.glb"))
        assertEquals("model.glb", SafeFileName.sanitize("dir\\model.glb"))
    }

    @Test
    fun `点与点点被拒绝`() {
        assertNull(SafeFileName.sanitize("."))
        assertNull(SafeFileName.sanitize(".."))
        assertNull(SafeFileName.sanitize("dir/.."))
        assertNull(SafeFileName.sanitize(""))
        assertNull(SafeFileName.sanitize("   "))
    }

    @Test
    fun `隐藏文件被拒绝`() {
        assertNull(SafeFileName.sanitize(".hidden.glb"))
    }

    @Test
    fun `非法字符替换为下划线`() {
        assertEquals("a_b_c.glb", SafeFileName.sanitize("a:b?c.glb"))
        assertEquals("a_b.glb", SafeFileName.sanitize("a|b.glb"))
        // 控制字符
        assertEquals("a_b.glb", SafeFileName.sanitize("a\u0000b.glb"))
    }

    @Test
    fun `中文与常规字符保留`() {
        assertEquals("模特 01 (final).glb", SafeFileName.sanitize("模特 01 (final).glb"))
        assertEquals("ComfyUI_00001_.glb", SafeFileName.sanitize("ComfyUI_00001_.glb"))
    }

    @Test
    fun `过长文件名截断但仍保留扩展名`() {
        val longName = "a".repeat(300) + ".glb"
        val sanitized = SafeFileName.sanitize(longName)
        assertTrue(sanitized != null)
        assertTrue(sanitized!!.endsWith(".glb"))
        assertTrue(sanitized.length <= 124)
    }

    @Test
    fun `传入目录时保证规范路径不越界`() {
        val dir = java.nio.file.Files.createTempDirectory("safe-name-test").toFile()
        try {
            val name = SafeFileName.sanitize("../escape.glb", dir)
            assertEquals("escape.glb", name)
            val target = java.io.File(dir, name!!).canonicalFile
            assertTrue(target.path.startsWith(dir.canonicalFile.path + java.io.File.separator))
        } finally {
            dir.deleteRecursively()
        }
    }
}