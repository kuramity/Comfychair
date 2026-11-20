package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F1：新键名（v2_…）与旧文件名（{promptId}_{filename}.{ext}）的读路径兼容。
 *
 * 只验证纯文件系统规则：新名优先、旧名回退、命中旧名后懒迁移。
 */
class MediaCacheFileNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `旧命名规则保持原样`() {
        assertEquals(
            "cd420ba4-1234_ComfyUI_00001_.png.png",
            MediaCacheFileNames.legacyName(
                promptId = "cd420ba4-1234",
                filename = "ComfyUI_00001_.png",
                ext = "png"
            )
        )
    }

    @Test
    fun `新名字存在时直接返回且不动旧文件`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg").apply { writeText("new") }
        val legacy = File(dir, MediaCacheFileNames.legacyName("p", "a.png", "jpg"))
            .apply { writeText("old") }

        val resolved = MediaCacheFileNames.resolveReadable(target, legacy.name)

        assertEquals(target, resolved)
        assertEquals("new", resolved!!.readText())
        assertTrue("新名字命中时不需要迁移旧文件", legacy.exists())
    }

    @Test
    fun `新名字缺失时回退旧文件并懒迁移`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg")
        val legacy = File(dir, MediaCacheFileNames.legacyName("p", "a.png", "jpg"))
            .apply { writeText("cached-bytes") }

        val resolved = MediaCacheFileNames.resolveReadable(target, legacy.name)

        assertEquals(target, resolved)
        assertEquals("cached-bytes", resolved!!.readText())
        assertFalse("迁移后旧文件不应继续占空间", legacy.exists())
        assertTrue(target.exists())
    }

    @Test
    fun `新旧名字都不存在时返回 null`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg")

        assertNull(MediaCacheFileNames.resolveReadable(target, "p_a.png.jpg"))
    }

    @Test
    fun `旧文件名里的下划线与多点不会影响迁移`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_temp_p_ComfyUI_00001_.mp4.jpg")
        val legacyName = MediaCacheFileNames.legacyName("p", "ComfyUI_00001_.mp4", "jpg")
        val legacy = File(dir, legacyName).apply { writeText("frame") }

        val resolved = MediaCacheFileNames.resolveReadable(target, legacyName)

        assertEquals(target, resolved)
        assertEquals("frame", resolved!!.readText())
    }

    // ------------------------------------------------------------
    // R2-N2：并发懒迁移时败者的选择
    // ------------------------------------------------------------
    @Test
    fun `rename 失败后新名字已由胜者就位时返回新文件`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg").apply { writeText("winner") }
        val legacy = File(dir, MediaCacheFileNames.legacyName("p", "a.png", "jpg"))
            .apply { writeText("old") }

        val picked = MediaCacheFileNames.pickAfterFailedRename(target, legacy)

        assertEquals("必须返回已就位的新文件，否则本次读会被误判 MISS", target, picked)
        assertEquals("winner", picked!!.readText())
    }

    @Test
    fun `rename 失败且新名字仍不存在时回退旧文件`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg")
        val legacy = File(dir, MediaCacheFileNames.legacyName("p", "a.png", "jpg"))
            .apply { writeText("old") }

        val picked = MediaCacheFileNames.pickAfterFailedRename(target, legacy)

        assertEquals(legacy, picked)
    }

    @Test
    fun `rename 失败后两者都不存在时返回 null`() {
        val dir = tmp.newFolder("cache")
        val target = File(dir, "v2_server_output_p_a.png.jpg")
        val legacy = File(dir, MediaCacheFileNames.legacyName("p", "a.png", "jpg"))

        assertNull(MediaCacheFileNames.pickAfterFailedRename(target, legacy))
    }

    // ------------------------------------------------------------
    // R2-N1：同一媒体身份的旧名/新名 key 去重
    // ------------------------------------------------------------
    @Test
    fun `同一媒体条目旧名与新名 key 只保留带 serverId 的新格式`() {
        val legacy = MediaCacheKey("p1", "a.png", "output", "")
        val fresh = MediaCacheKey("p1", "a.png", "temp", "server-a")
        val other = MediaCacheKey("p1", "b.png", "output", "server-a")

        val deduped = MediaCacheFileNames.dedupeByIdentity(listOf(legacy, fresh, other))

        assertEquals(2, deduped.size)
        assertEquals("同一条目必须只留一条，并优先带类型信息的 v2 键", fresh, deduped[0])
        assertEquals(other, deduped[1])
    }

    @Test
    fun `output 与 temp 同名同 prompt 也只保留一条`() {
        val output = MediaCacheKey("p1", "a.png", "output", "server-a")
        val temp = MediaCacheKey("p1", "a.png", "temp", "server-a")

        val deduped = MediaCacheFileNames.dedupeByIdentity(listOf(output, temp))

        assertEquals(listOf(output), deduped)
    }

    @Test
    fun `不同文件名不受去重影响`() {
        val first = MediaCacheKey("p1", "a.png", "output", "server-a")
        val second = MediaCacheKey("p1", "b.png", "output", "server-a")

        assertEquals(listOf(first, second), MediaCacheFileNames.dedupeByIdentity(listOf(first, second)))
    }

    @Test
    fun `条目身份使用 NUL 分隔避免下划线歧义`() {
        assertTrue(MediaCacheFileNames.identityKey("a_b", "c.png").contains('\u0000'))
    }
}