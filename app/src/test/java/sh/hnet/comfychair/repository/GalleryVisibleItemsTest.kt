package sh.hnet.comfychair.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * P0-R1 回归：索引读不出来时，图库仍然必须走**磁盘缓存恢复**。
 *
 * 背景（复核发现）：上一版把 `recoverFromDiskCache` 挪进了"索引非 null"的分支，
 * 于是"索引损坏 + ComfyUI 重启（/history 内存态清空，但电脑端文件全在）"时
 * 图库会渲染成空表 —— 而磁盘缓存里明明还有可恢复的图片。这正是用户描述的
 * "图库加载不出任何图片"。
 *
 * 反证：把 [buildVisibleItems] 改回 `indexItems.orEmpty()`（不恢复磁盘条目），
 * 本类前两条用例立即变红。
 */
class GalleryVisibleItemsTest {

    private fun item(
        promptId: String = "p1",
        filename: String = "a.png",
        type: String = "output",
        index: Int = 0
    ) = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = "",
        type = type,
        isVideo = false,
        index = index
    )

    private fun cacheKey(
        promptId: String,
        filename: String,
        type: String = "output"
    ) = MediaCacheKey(promptId = promptId, filename = filename, type = type, serverId = "srv-1")

    @Test
    fun `索引读不出来时仍然恢复磁盘缓存条目`() {
        val cached = listOf(
            cacheKey("p1", "a.png"),
            cacheKey("p2", "b.mp4", type = "temp")
        )

        val visible = buildVisibleItems(
            indexItems = null, // 索引损坏/不存在
            cachedKeys = cached,
            tombstones = emptySet()
        )

        assertEquals("索引读不出来绝不能等于图库为空", 2, visible.size)
        assertEquals(listOf("a.png", "b.mp4"), visible.map { it.filename })
        val video = visible.first { it.filename == "b.mp4" }
        assertTrue("按扩展名恢复 isVideo", video.isVideo)
        assertEquals("按缓存键恢复 type", "temp", video.type)
    }

    @Test
    fun `索引读不出来且磁盘没有缓存时返回空表（由仓储层保留旧列表）`() {
        val visible = buildVisibleItems(
            indexItems = null,
            cachedKeys = emptyList(),
            tombstones = emptySet()
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `索引正常时磁盘条目只补充不覆盖已有信息`() {
        val known = listOf(item("p1", "a.png", type = "temp", index = 5))
        val cached = listOf(
            cacheKey("p1", "a.png"),          // 已存在：必须保留索引里的 type=temp / index=5
            cacheKey("p2", "new.png")         // 缺失：补回
        )

        val visible = buildVisibleItems(known, cached, emptySet())

        assertEquals(2, visible.size)
        val existing = visible.first { it.filename == "a.png" }
        assertEquals("已有条目的信息不能被反推结果覆盖", "temp", existing.type)
        assertEquals(5, existing.index)
        assertEquals("new.png", visible.last().filename)
    }

    @Test
    fun `墓碑对索引条目与恢复条目同时生效`() {
        val tombstoned = GalleryMetadataCache.itemIdentityKey("p1", "a.png")
        val visible = buildVisibleItems(
            indexItems = listOf(item("p1", "a.png"), item("p2", "keep.png")),
            cachedKeys = listOf(cacheKey("p1", "a.png"), cacheKey("p3", "recovered.png")),
            tombstones = setOf(tombstoned)
        )

        assertFalse(
            "已删除的条目不能从磁盘恢复路径复活",
            visible.any { it.filename == "a.png" }
        )
        assertEquals(listOf("keep.png", "recovered.png"), visible.map { it.filename })
    }

    @Test
    fun `磁盘键内部按条目身份去重`() {
        val visible = buildVisibleItems(
            indexItems = null,
            cachedKeys = listOf(
                cacheKey("p1", "a.png"),
                cacheKey("p1", "a.png", type = "temp"),
                cacheKey("p2", "b.png")
            ),
            tombstones = emptySet()
        )

        assertEquals(
            "同一 (promptId, filename) 只能出现一次，否则 LazyGrid 撞 key",
            2,
            visible.size
        )
    }
}