package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * G4 回收站 JSON 编解码的往返测试（JVM，不碰文件）。
 *
 * 回收站记录是"恢复的唯一依据"：
 * - 完整定位必须一字不差地往返（本地缓存路径 = promptId/filename/subfolder/type +
 *   serverId；丢了任何一项 = 恢复时找不到文件、联动删除时定位不到服务器文件）；
 * - 单条损坏只跳该条（坏一条清空一站的记录 = 把所有"还能恢复"的图判死）；
 * - 两阶段状态（pendingRetry / lastError）必须随记录持久化（跨进程重启仍可重试）。
 */
class GalleryTrashJsonRoundTripTest {

    private fun entry(
        id: String = "e1",
        serverId: String = "srv-1",
        threeD: ThreeDResultHolder.Result? = null,
        pendingRetry: Boolean = false,
        lastError: String? = null,
        deletedAt: Long = 1_732_000_000_000L
    ) = GalleryTrashEntry(
        id = id,
        serverId = serverId,
        item = GalleryItem(
            promptId = "cd420ba4-0000-0000-0000-000000000001",
            filename = "ComfyUI_00021_.png",
            subfolder = "batch/run1",
            type = "output",
            isVideo = false,
            index = 7,
            threeD = threeD
        ),
        deletedAt = deletedAt,
        pendingRetry = pendingRetry,
        lastError = lastError
    )

    @Test
    fun `完整定位字段逐项往返一致`() {
        val source = listOf(
            entry(id = "a"),
            entry(
                id = "b",
                threeD = ThreeDResultHolder.Result("model.glb", "3d/out", "output"),
                pendingRetry = true,
                lastError = "网络失败",
                deletedAt = 42L
            )
        )
        val json = GalleryTrashStorage.toJson(source)
        val restored = GalleryTrashStorage.parse(json)

        assertEquals(2, restored.size)
        val first = restored.first { it.id == "a" }
        assertEquals("srv-1", first.serverId)
        assertEquals("ComfyUI_00021_.png", first.item.filename)
        assertEquals("batch/run1", first.item.subfolder)
        assertEquals("output", first.item.type)
        assertEquals(7, first.item.index)
        assertEquals(1_732_000_000_000L, first.deletedAt)
        assertTrue(!first.pendingRetry)
        assertNull(first.lastError)

        val second = restored.first { it.id == "b" }
        assertNotNull(second.item.threeD)
        assertEquals("model.glb", second.item.threeD!!.filename)
        assertEquals("3d/out", second.item.threeD!!.subfolder)
        assertTrue(second.pendingRetry)
        assertEquals("网络失败", second.lastError)
        assertEquals(42L, second.deletedAt)
    }

    @Test
    fun `身份键编解码后不变（恢复与清空都按它定位）`() {
        val source = entry()
        val restored = GalleryTrashStorage.parse(GalleryTrashStorage.toJson(listOf(source))).single()
        assertEquals(source.identityKey, restored.identityKey)
    }

    @Test
    fun `单条损坏只跳该条，其余保留`() {
        val good = GalleryTrashStorage.toJson(listOf(entry(id = "keep")))
        // 手工拼一条缺 filename 的坏记录混进去
        val broken = good.removeSuffix("]") +
            ",{\"id\":\"rotten\",\"serverId\":\"srv-1\",\"promptId\":\"p\"}]"
        val restored = GalleryTrashStorage.parse(broken)
        assertEquals(1, restored.size)
        assertEquals("keep", restored.first().id)
    }

    @Test
    fun `空表往返是空表（不是 null、不是崩溃）`() {
        assertEquals("[]", GalleryTrashStorage.toJson(emptyList()))
        assertEquals(0, GalleryTrashStorage.parse("[]").size)
    }

    @Test
    fun `缺 id 的记录解析时补发新 id（恢复入口按 id 定位，不能为空串互撞）`() {
        val noId = """[{"serverId":"s","promptId":"p","filename":"f.png"}]"""
        val restored = GalleryTrashStorage.parse(noId)
        assertEquals(1, restored.size)
        assertTrue(restored.first().id.isNotBlank())
    }

    @Test
    fun `阶段状态 - trashStageOf 区分两阶段`() {
        assertEquals(TrashStage.IN_TRASH, trashStageOf(entry()))
        assertEquals(TrashStage.PENDING_RETRY, trashStageOf(entry(pendingRetry = true)))
    }
}
