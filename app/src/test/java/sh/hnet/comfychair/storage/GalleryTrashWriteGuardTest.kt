package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * P0「删除动作写坏持久化状态」的专项测试（任务书 5.1 / 5.4 / 5.6）。
 *
 * 回收站是 v4.16.13 新增的删除路径：每删一张就整份重写 `gallery_trash.json`。
 * 这组用例钉住三件事：
 * 1. 极端字段（中文/空格/圆括号 filename、空 subfolder、null threeD、index 0/负数、
 *    空 promptId）save → load 必须**逐字段全等**；
 * 2. 截断的 JSON 必须被识别为损坏（空表 + 备份），而不是抛异常；
 * 3. 写出去但读不回来（条数/身份不一致）的写入必须**回滚**，不留坏文件。
 *
 * 反证：去掉 [GalleryTrashStorage.saveAllToFile] 里的写后读回校验，第 3 类用例变红。
 */
class GalleryTrashWriteGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val trashFile: File
        get() = File(tempFolder.root, "gallery_trash.json")

    private fun entry(
        id: String,
        promptId: String = "prompt-1",
        filename: String = "ComfyUI_00021_.png",
        subfolder: String = "",
        type: String = "output",
        isVideo: Boolean = false,
        index: Int = 0,
        threeD: ThreeDResultHolder.Result? = null,
        serverId: String = "srv-1",
        deletedAt: Long = 1_732_000_000_000L,
        pendingRetry: Boolean = false,
        lastError: String? = null
    ) = GalleryTrashEntry(
        id = id,
        serverId = serverId,
        item = GalleryItem(
            promptId = promptId,
            filename = filename,
            subfolder = subfolder,
            type = type,
            isVideo = isVideo,
            index = index,
            threeD = threeD
        ),
        deletedAt = deletedAt,
        pendingRetry = pendingRetry,
        lastError = lastError
    )

    // ============================================================
    // 5.1 / 5.6 极端值往返
    // ============================================================

    @Test
    fun `极端字段完整往返 - 中文空格圆括号 空 subfolder null threeD index 0 与负数 空 promptId`() {
        val source = listOf(
            entry(
                id = "e-0",
                promptId = "",
                filename = "精修 图 (1) [final].png",
                subfolder = "",
                type = "output",
                isVideo = false,
                index = 0,
                threeD = null
            ),
            entry(
                id = "e-neg",
                promptId = "prompt-2",
                filename = "视频 (2).mp4",
                subfolder = "",
                type = "temp",
                isVideo = true,
                index = -3,
                threeD = ThreeDResultHolder.Result("模型 (1).glb", "", "output"),
                deletedAt = 42L,
                pendingRetry = true,
                lastError = "网络失败"
            )
        )

        assertTrue("正常条目写盘应当成功", GalleryTrashStorage.saveAllToFile(trashFile, source))
        val loaded = GalleryTrashStorage.loadAllFromFile(trashFile)

        assertEquals("每一条都必须读回来（空 promptId 也不例外）", source.size, loaded.size)
        assertEquals("所有字段必须逐项全等", source, loaded)
    }

    @Test
    fun `toJson 与 parse 字段一一对应`() {
        val source = entry(
            id = "e1",
            promptId = "p1",
            filename = "名字 (1).png",
            subfolder = "",
            type = "output",
            isVideo = false,
            index = 0,
            threeD = null
        )
        val json = GalleryTrashStorage.toJson(listOf(source))

        for (field in listOf(
            "id", "serverId", "deletedAt", "pendingRetry",
            "promptId", "filename", "subfolder", "type", "isVideo", "index"
        )) {
            assertTrue("toJson 缺少字段 $field", json.contains("\"$field\""))
        }
        assertFalse("threeD 为 null 时不应写出 threeDFilename", json.contains("threeDFilename"))

        val loaded = GalleryTrashStorage.parse(json).single()
        assertEquals("p1", loaded.item.promptId)
        assertEquals("名字 (1).png", loaded.item.filename)
        assertEquals("", loaded.item.subfolder)
        assertEquals("output", loaded.item.type)
        assertEquals(0, loaded.item.index)
        assertNull(loaded.item.threeD)
        assertNull(loaded.lastError)
    }

    @Test
    fun `filename 为空的 3D 三元组往返后按无 3D 处理`() {
        val source = entry(
            id = "e-blank3d",
            threeD = ThreeDResultHolder.Result("", "", "output")
        )

        assertTrue(GalleryTrashStorage.saveAllToFile(trashFile, listOf(source)))
        val loaded = GalleryTrashStorage.loadAllFromFile(trashFile).single()

        assertNull("空 filename 的 3D 等同没有 3D（序列化/解析两侧口径一致）", loaded.item.threeD)
        assertEquals("条目身份不受影响", source.identityKey, loaded.identityKey)
    }

    // ============================================================
    // 5.6 截断 JSON：识别为损坏，不崩
    // ============================================================

    @Test
    fun `截断的回收站 JSON 读取为空表且不崩`() {
        trashFile.writeText("""[{"id":"e1","promptId":"p1","filename":"a.png"}""")
        val truncatedContent = trashFile.readText()

        val loaded = GalleryTrashStorage.loadAllFromFile(trashFile)

        assertTrue("整份损坏 → 空表（不是抛异常）", loaded.isEmpty())
        assertTrue("原文件必须保留，供人工排查", trashFile.exists())
        assertEquals("原文件内容不许被改", truncatedContent, trashFile.readText())
        assertNotNull(
            "必须留下 .corrupt-* 备份",
            tempFolder.root.listFiles()!!.firstOrNull { it.name.startsWith(trashFile.name + ".corrupt-") }
        )
    }

    @Test
    fun `单条缺 filename 只跳该条其余保留`() {
        val good = GalleryTrashStorage.toJson(
            listOf(entry(id = "keep", filename = "keep.png"))
        )
        val mixed = good.removeSuffix("]") + """,{"id":"rotten","serverId":"srv-1","promptId":"p"}]"""
        trashFile.writeText(mixed)

        val loaded = GalleryTrashStorage.loadAllFromFile(trashFile)

        assertEquals(1, loaded.size)
        assertEquals("keep", loaded.first().id)
    }

    // ============================================================
    // 5.4 写后读回校验：写不回来的内容必须回滚
    // ============================================================

    @Test
    fun `写入后读不回来的条目会让整次保存失败并回滚旧内容`() {
        val good = listOf(entry(id = "good", filename = "good.png"))
        assertTrue(GalleryTrashStorage.saveAllToFile(trashFile, good))
        val before = trashFile.readText()

        // filename 为空的条目在 parse 阶段会被跳过 → 读回条数不一致 → 必须回滚
        val unreadable = good + entry(id = "bad", filename = "")
        val ok = GalleryTrashStorage.saveAllToFile(trashFile, unreadable)

        assertFalse("读回条数不一致 = 写成功但读不出来，必须判失败", ok)
        assertEquals("失败后必须回滚到写前内容", before, trashFile.readText())
        assertEquals(
            "回滚后回收站仍是写前那一份",
            listOf("good"),
            GalleryTrashStorage.loadAllFromFile(trashFile).map { it.id }
        )
    }

    @Test
    fun `正常保存返回 true 且文件立即可解析`() {
        val ok = GalleryTrashStorage.saveAllToFile(
            trashFile,
            listOf(entry(id = "a"), entry(id = "b", filename = "b (2).png", index = -1))
        )

        assertTrue(ok)
        assertEquals(2, GalleryTrashStorage.loadAllFromFile(trashFile).size)
    }

    @Test
    fun `空表往返是空表`() {
        assertTrue(GalleryTrashStorage.saveAllToFile(trashFile, emptyList()))
        assertEquals("[]", trashFile.readText())
        assertTrue(GalleryTrashStorage.loadAllFromFile(trashFile).isEmpty())
    }
}