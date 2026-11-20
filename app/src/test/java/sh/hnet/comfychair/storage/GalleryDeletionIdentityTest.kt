package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * P1-19 的纯逻辑测试：删除身份键必须是 `(promptId, filename)`，不是 promptId。
 *
 * 背景：批量出图（一次生成 4 张）共用同一个 promptId。修复前删除按 promptId 去重，
 * 用户在图库里删掉其中一张，同批的另外三张会一起消失 —— 这组测试把它钉死。
 */
class GalleryDeletionIdentityTest {

    private fun item(filename: String, promptId: String = "prompt-a") = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = "",
        type = "output",
        isVideo = false
    )

    @Test
    fun `同批四张图共用一个 promptId`() {
        val batch = listOf(
            item("ComfyUI_00001_.png"),
            item("ComfyUI_00002_.png"),
            item("ComfyUI_00003_.png"),
            item("ComfyUI_00004_.png")
        )
        assertEquals(1, batch.map { it.promptId }.distinct().size)
        assertEquals(4, batch.map { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) }
            .distinct().size)
    }

    @Test
    fun `条目键删除只影响被删的那一张`() {
        val deleted = GalleryMetadataCache.itemIdentityKey("prompt-a", "ComfyUI_00001_.png")
        val tombstones = setOf(deleted)

        assertTrue(
            GalleryMetadataCache.isDeleted(
                item("ComfyUI_00001_.png"), tombstones
            )
        )
        // 同批的另外三张必须保留
        assertFalse(
            GalleryMetadataCache.isDeleted(
                item("ComfyUI_00002_.png"), tombstones
            )
        )
        assertFalse(
            GalleryMetadataCache.isDeleted(
                item("ComfyUI_00003_.png"), tombstones
            )
        )
        assertFalse(
            GalleryMetadataCache.isDeleted(
                item("ComfyUI_00004_.png"), tombstones
            )
        )
    }

    @Test
    fun `旧格式的裸 promptId 墓碑仍按整批删除处理`() {
        // 旧版本写下的墓碑文件里只有 promptId（不含 NUL）——向后兼容不能把旧删除意图解释丢
        val legacyTombstones = setOf("prompt-a")

        assertTrue(GalleryMetadataCache.isDeleted(item("ComfyUI_00001_.png"), legacyTombstones))
        assertTrue(GalleryMetadataCache.isDeleted(item("ComfyUI_00009_.png"), legacyTombstones))
    }

    @Test
    fun `不同 promptId 的同名文件互不牵连`() {
        val tombstones = setOf(
            GalleryMetadataCache.itemIdentityKey("prompt-a", "ComfyUI_00001_.png")
        )

        assertFalse(
            GalleryMetadataCache.isDeleted(item("ComfyUI_00001_.png", promptId = "prompt-b"), tombstones)
        )
    }

    @Test
    fun `身份键对下划线文件名无歧义`() {
        // (a, b_c) 与 (a_b, c) 用 "_" 拼会撞键；NUL 分隔后必须不同
        val first = GalleryMetadataCache.itemIdentityKey("a", "b_c")
        val second = GalleryMetadataCache.itemIdentityKey("a_b", "c")
        assertNotEquals(first, second)

        val tombstones = setOf(first)
        assertFalse(GalleryMetadataCache.isDeleted("a_b", "c", tombstones))
    }

    @Test
    fun `空墓碑集合不删任何条目`() {
        assertFalse(
            GalleryMetadataCache.isDeleted(item("ComfyUI_00001_.png"), emptySet())
        )
    }
}