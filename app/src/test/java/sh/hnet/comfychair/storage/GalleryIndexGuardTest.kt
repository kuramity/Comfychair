package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * P0「索引写坏 / 写空」护栏的单元测试（JVM，用临时目录代替 filesDir）。
 *
 * 对应任务书 1.1-1.4、3.4、5.3、5.4（索引与墓碑部分）：
 * - 索引绝不能因为"读失败 / 服务器为空"被冲成空表（删到空是唯一合法空表场景）；
 * - 解析失败必须留证据（.corrupt-* 备份）而不是删文件；
 * - 兜底索引只借"能读出来"的那份，并在当前 serverId 下落一份（迁移）；
 * - 写后必须读回校验，失败回滚 —— 不留"写成功但读不出来"的文件；
 * - LazyGrid key 与去重口径必须同为 NUL 分隔（下划线会撞键）。
 *
 * 反证：把任意一条护栏改回旧行为（允许写空 / mergeOrNull 返回空表 / key 用下划线 /
 * 去掉读回校验），对应用例都会变红。
 */
class GalleryIndexGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun item(
        promptId: String = "p1",
        filename: String = "a.png",
        subfolder: String = "",
        type: String = "output",
        isVideo: Boolean = false,
        index: Int = 0,
        threeD: ThreeDResultHolder.Result? = null
    ) = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = subfolder,
        type = type,
        isVideo = isVideo,
        index = index,
        threeD = threeD
    )

    private fun indexFile(name: String = "srv") =
        File(tempFolder.root, "gallery_metadata_$name.json")

    // ============================================================
    // 1.1 索引绝不写空
    // ============================================================

    @Test
    fun `非空索引拒绝被空表覆盖`() {
        val file = indexFile()
        assertTrue(
            "前置条件：先写一份非空索引",
            GalleryMetadataCache.writeMetadataToFile(file, listOf(item()))
        )
        val before = file.readText()

        val wrote = GalleryMetadataCache.writeMetadataToFile(file, emptyList())

        assertFalse("非空索引必须拒绝写空（默认 allowEmpty=false）", wrote)
        assertEquals("被拒绝的写入不能改动文件内容", before, file.readText())
    }

    @Test
    fun `原本就是空表的索引允许再次写空`() {
        val file = indexFile()
        file.writeText("[]")

        assertTrue(
            "文件本来就是 '[]'（2 字节）时写空不构成丢列表",
            GalleryMetadataCache.writeMetadataToFile(file, emptyList())
        )
        assertEquals("[]", file.readText())
    }

    @Test
    fun `删到空是合法终态 - 显式 allowEmpty 可以落盘空表`() {
        val file = indexFile()
        assertTrue(GalleryMetadataCache.writeMetadataToFile(file, listOf(item())))

        assertTrue(
            "removeFromIndex 的删到空场景传 allowEmpty=true，必须允许",
            GalleryMetadataCache.writeMetadataToFile(file, emptyList(), allowEmpty = true)
        )
        assertEquals("[]", file.readText())
    }

    // ============================================================
    // 1.2 mergeMetadata 读失败 + 服务器为空 → null，不写盘
    // ============================================================

    @Test
    fun `索引不存在且服务器为空时返回 null 且不创建文件`() {
        val result = GalleryMetadataCache.mergeMetadataInto(tempFolder.root, "srv", emptyList())

        assertNull("读不出来 + 服务器没有条目 = 什么都不该写", result)
        assertFalse("绝不能顺手建出一份空索引", indexFile().exists())
    }

    @Test
    fun `索引损坏且服务器为空时返回 null 且原文件保持不变`() {
        val file = indexFile()
        file.writeText("""[{"promptId":"p1","filename":"a.png"}""") // 截断的 JSON
        val before = file.readText()

        val result = GalleryMetadataCache.mergeMetadataInto(tempFolder.root, "srv", emptyList())

        assertNull(result)
        assertEquals("解析失败时不能把坏索引覆盖成空表", before, file.readText())
        assertTrue(
            "必须留下 .corrupt-* 备份作为证据",
            tempFolder.root.listFiles()!!.any { it.name.contains(".corrupt-") }
        )
    }

    @Test
    fun `服务器返回空但本地索引可读时索引原样保留`() {
        val file = indexFile()
        assertTrue(
            GalleryMetadataCache.writeMetadataToFile(
                file,
                listOf(item("p1", "a.png"), item("p2", "b.png"))
            )
        )

        val result = GalleryMetadataCache.mergeMetadataInto(tempFolder.root, "srv", emptyList())

        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals("服务器说「没有」不等于「删掉」", 2, GalleryMetadataCache.loadMetadataFromDir(tempFolder.root, "srv")!!.size)
    }

    @Test
    fun `索引读不出来但服务器有数据时用服务器数据恢复并落盘`() {
        val result = GalleryMetadataCache.mergeMetadataInto(
            tempFolder.root,
            "srv",
            listOf(item("p9", "fresh.png", index = 3))
        )

        assertNotNull(result)
        assertEquals(listOf("fresh.png"), result!!.map { it.filename })
        assertTrue("服务器给了数据就应该落一份新索引", indexFile().exists())
        assertEquals(1, GalleryMetadataCache.loadMetadataFromDir(tempFolder.root, "srv")!!.size)
    }

    @Test
    fun `mergeOrNull - 只有读失败加空服务器才返回 null`() {
        assertNull("读失败 + 空 = null", GalleryMetadataCache.mergeOrNull(null, emptyList()))
        assertNotNull("读失败 + 有数据 = 用数据重建", GalleryMetadataCache.mergeOrNull(null, listOf(item())))
        assertEquals(
            "空表 + 空 = 合法空表（不是读失败）",
            0,
            GalleryMetadataCache.mergeOrNull(emptyList(), emptyList())!!.size
        )
    }

    // ============================================================
    // 1.3 解析失败：备份 + 不删原文件
    // ============================================================

    @Test
    fun `截断的索引被识别为损坏而不是崩`() {
        val file = indexFile()
        val truncated = """[{"promptId":"p1","filename":"精修 (1).png"},{"promptId":"p2","""
        file.writeText(truncated)

        val parsed = GalleryMetadataCache.parseItems(file)

        assertNull("截断的 JSON 必须返回 null（调用方保留旧列表），不能抛异常", parsed)
        assertTrue("原文件必须保留", file.exists())
        assertEquals("原文件内容不许被改", truncated, file.readText())
        assertEquals(
            "必须复制出一份 .corrupt-* 备份",
            1,
            tempFolder.root.listFiles()!!.count { it.name.startsWith(file.name + ".corrupt-") }
        )
    }

    // ============================================================
    // 1.4 兜底索引 + 迁移
    // ============================================================

    @Test
    fun `当前 serverId 无索引时借用最近索引并迁移一份到本 serverId`() {
        val source = indexFile("serverA")
        assertTrue(GalleryMetadataCache.writeMetadataToFile(source, listOf(item("pa", "a.png"))))
        source.setLastModified(1_700_000_000_000L)

        val items = GalleryMetadataCache.loadMetadataWithFallbackFromDir(tempFolder.root, "serverB")

        assertNotNull(items)
        assertEquals(listOf("a.png"), items!!.map { it.filename })
        val migrated = indexFile("serverB")
        assertTrue("迁移：必须给当前 serverId 落一份", migrated.exists())
        assertEquals(
            "迁移内容必须与原索引等价",
            items.map { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) },
            GalleryMetadataCache.parseItems(migrated)!!
                .map { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) }
        )
    }

    @Test
    fun `当前 serverId 索引已存在时只读不写`() {
        val source = indexFile("serverA")
        assertTrue(GalleryMetadataCache.writeMetadataToFile(source, listOf(item("pa", "a.png"))))
        source.setLastModified(1_700_000_000_000L)

        val own = indexFile("serverC")
        assertTrue(GalleryMetadataCache.writeMetadataToFile(own, listOf(item("pc", "c.png"))))
        own.setLastModified(1_600_000_000_000L)
        val before = own.readText()

        val items = GalleryMetadataCache.loadMetadataWithFallbackFromDir(tempFolder.root, "serverC")

        assertEquals(listOf("c.png"), items!!.map { it.filename })
        assertEquals("已存在的索引不允许被兜底内容覆盖", before, own.readText())
    }

    @Test
    fun `兜底会跳过损坏的最新索引去借更旧的可用索引`() {
        val good = indexFile("serverOld")
        assertTrue(GalleryMetadataCache.writeMetadataToFile(good, listOf(item("pold", "old.png"))))
        good.setLastModified(1_600_000_000_000L)

        val broken = indexFile("serverBroken")
        broken.writeText("""[{"promptId":"p""")
        broken.setLastModified(1_700_000_000_000L)

        val items = GalleryMetadataCache.loadMetadataWithFallbackFromDir(tempFolder.root, "serverNew")

        assertNotNull("最新那份坏了必须继续往后找，而不是直接放弃", items)
        assertEquals(listOf("old.png"), items!!.map { it.filename })
        assertTrue("借用成功后同样要迁移", indexFile("serverNew").exists())
    }

    // ============================================================
    // 3.4 key 口径（NUL vs 下划线）
    // ============================================================

    @Test
    fun `条目 key 用 NUL 分隔 - 下划线拼法会撞键而生产 key 不会`() {
        val first = GalleryMetadataCache.itemIdentityKey("a_b", "c.png")
        val second = GalleryMetadataCache.itemIdentityKey("a", "b_c.png")

        // 这是本测试唯一有意义的断言：把 IDENTITY_SEPARATOR 改回 '_' 时两者相等 → 变红。
        // 旧 UI 用下划线拼 key 会撞车这件事由 GalleryWriteResultWiringTest 钉住
        // "图库网格确实调用 itemIdentityKey"，不再用字符串字面量自证（恒真断言已删除）。
        assertNotEquals("(a_b, c.png) 与 (a, b_c.png) 必须是两个 key", first, second)
    }

    // ============================================================
    // 5.3 删除后 index 重排
    // ============================================================

    @Test
    fun `删除后 index 重排为 0 到 n-1 既无重复也无负数`() {
        val kept = listOf(
            item("p1", "a.png", index = -5),
            item("p2", "b.png", index = 99),
            item("p3", "c.png", index = 99)
        )

        val renumbered = GalleryMetadataCache.renumberByPosition(kept)

        assertEquals(listOf(0, 1, 2), renumbered.map { it.index })
        assertEquals("条目顺序不能变", listOf("a.png", "b.png", "c.png"), renumbered.map { it.filename })
    }

    // ============================================================
    // 5.4 写后读回校验 + 回滚
    // ============================================================

    @Test
    fun `写后读回校验失败时回滚到写前内容`() {
        val file = indexFile()
        file.writeText("[]")
        val before = file.readText()

        val ok = GalleryMetadataCache.writeTextAtomicallyVerified(
            file = file,
            content = "这不是 JSON",
            label = "单测",
            verify = { text -> GalleryMetadataCache.parseItemsText(text) != null }
        )

        assertFalse("校验失败必须返回 false", ok)
        assertEquals("必须回滚到写前内容，不留坏文件", before, file.readText())
    }

    @Test
    fun `写后读回校验失败且原本无文件时删除半成品`() {
        val file = File(tempFolder.root, "gallery_trash.json")

        val ok = GalleryMetadataCache.writeTextAtomicallyVerified(
            file = file,
            content = "坏内容",
            label = "单测",
            verify = { false }
        )

        assertFalse(ok)
        assertFalse("原本不存在就不该留下任何东西", file.exists())
    }

    @Test
    fun `索引往返字段全等 - 中文空格圆括号 空 subfolder null threeD 负 index`() {
        val file = indexFile()
        val source = listOf(
            item(
                promptId = "p-1",
                filename = "精修 图 (1).png",
                subfolder = "",
                type = "output",
                isVideo = false,
                index = 0,
                threeD = null
            ),
            item(
                promptId = "",
                filename = "视频 (2).mp4",
                subfolder = "",
                type = "temp",
                isVideo = true,
                index = -3,
                threeD = ThreeDResultHolder.Result("模型 (1).glb", "", "output")
            )
        )

        assertTrue(GalleryMetadataCache.writeMetadataToFile(file, source))
        val loaded = GalleryMetadataCache.parseItems(file)

        assertEquals("所有字段必须一字不差地往返", source, loaded)
    }

    // ============================================================
    // 5.2 墓碑：NUL 必须转义成六字符，不能写出真正的 0x00
    // ============================================================

    @Test
    fun `墓碑 JSON 不含裸 NUL 字节且能读回同一个键`() {
        val keys = linkedSetOf(
            GalleryMetadataCache.itemIdentityKey("a_b", "c.png"),
            "legacy-prompt-id"
        )

        val json = GalleryMetadataCache.tombstoneJson(keys)
        val file = File(tempFolder.root, "gallery_deleted_srv.json")
        GalleryMetadataCache.writeTextAtomically(file, json)

        val bytes = file.readBytes()
        assertTrue(
            "写出去的字节里不能有真正的 0x00（会让部分解析器截断整份数组）",
            bytes.none { it == 0.toByte() }
        )
        assertTrue(
            "NUL 必须以 \\u0000 六字符转义存在",
            file.readText().contains("\\u0000")
        )
        assertTrue(
            "读回校验：条数与内容集合一致",
            GalleryMetadataCache.isTombstoneDocumentConsistent(file.readText(), keys)
        )
    }

    @Test
    fun `墓碑里有裸 NUL 时读回校验判为不一致`() {
        val key = GalleryMetadataCache.itemIdentityKey("p", "a.png")
        assertFalse(
            "裸 NUL 是坏文件信号，校验必须拒绝",
            GalleryMetadataCache.isTombstoneDocumentConsistent("[\"$key\"]", setOf(key))
        )
    }

    @Test
    fun `墓碑文件损坏时读取跳过该文件并留下 corrupt 备份`() {
        val good = File(tempFolder.root, "gallery_deleted_a.json")
        good.writeText("""["p1","p2"]""")
        val bad = File(tempFolder.root, "gallery_deleted_b.json")
        val badContent = """["p3""""
        bad.writeText(badContent)

        val keys = GalleryMetadataCache.loadTombstonesFromFiles(listOf(good, bad))

        assertEquals("可读文件里的删除意图必须保留", setOf("p1", "p2"), keys)
        assertEquals("坏文件不许被改动", badContent, bad.readText())
        assertTrue(
            "坏墓碑必须留下 .corrupt-* 备份（否则下次整份重写会永久丢掉里面的删除记录）",
            tempFolder.root.listFiles()!!.any { it.name.startsWith(bad.name + ".corrupt-") }
        )
    }

    @Test
    fun `addTombstones 重写前会备份读不出来的目标文件`() {
        val corrupt = File(tempFolder.root, "gallery_deleted_srv.json")
        val corruptContent = """["旧删除记录","""
        corrupt.writeText(corruptContent)

        assertTrue(
            "读不出来的墓碑文件必须先备份再覆盖",
            GalleryMetadataCache.backupTombstoneIfUnreadable(corrupt)
        )
        assertEquals("备份不删除原文件的原始字节", corruptContent, corrupt.readText())
        assertTrue(
            tempFolder.root.listFiles()!!.any { it.name.startsWith(corrupt.name + ".corrupt-") }
        )

        val valid = File(tempFolder.root, "gallery_deleted_other.json")
        valid.writeText("""["p1"]""")
        assertFalse(
            "可读文件不需要备份",
            GalleryMetadataCache.backupTombstoneIfUnreadable(valid)
        )
    }

    @Test
    fun `坏墓碑文件不阻塞恢复 - 可读文件仍被清理`() {
        val good = File(tempFolder.root, "gallery_deleted_srv.json")
        good.writeText(GalleryMetadataCache.tombstoneJson(linkedSetOf("key-a", "key-b")))
        val bad = File(tempFolder.root, "gallery_deleted_other.json")
        val badContent = """["key-a""""
        bad.writeText(badContent)

        val allOk = GalleryMetadataCache.removeTombstonesFromFiles(
            files = listOf(good, bad),
            keys = setOf("key-a"),
            serverId = "srv"
        )

        // 读路径对坏文件整份跳过 → 它的键不参与 isDeleted → 「改不动它」不能算恢复失败。
        // 旧实现把这里判为 false，只要目录里有一个坏墓碑文件，之后每次恢复都会被永久取消。
        assertTrue("坏墓碑文件不能把恢复卡成死胡同", allOk)
        assertEquals(
            "可读文件里的目标键必须被清掉",
            setOf("key-b"),
            GalleryMetadataCache.loadTombstonesFromFiles(listOf(good))
        )
        assertEquals("坏文件不许被改写", badContent, bad.readText())
        assertTrue(
            "坏文件仍要留 .corrupt-* 备份",
            tempFolder.root.listFiles()!!.any { it.name.startsWith(bad.name + ".corrupt-") }
        )
    }
}