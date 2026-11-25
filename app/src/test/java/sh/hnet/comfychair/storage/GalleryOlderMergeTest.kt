package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * 「加载更早」分页的索引**追加合并**单测（config-audit-report 事项2 的"合并去重"项）。
 *
 * 与首屏 mergeMetadata（服务器条目在前）方向相反：更早页必须接在既有列表尾部，
 * 否则"新的在前"的展示顺序当场错乱。钉住四条规则：
 * 1. 去重口径 = (promptId, filename) 条目键（与 mergeItems/LazyGrid key 同一口径），
 *    incoming 内部重复也要收敛；
 * 2. 净新增追加到尾部并按位置重排 index；
 * 3. 整页全认识 → 不写盘、返回原表（原子写 + fsync + 读回校验的代价不白付）；
 * 4. 索引读不出来 → 返回 null 且**不拿更早页重建**（更早页不含最新条目，
 *    重建会把首屏数据挤掉；恢复归首屏合并路径管）。
 */
class GalleryOlderMergeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun item(promptId: String, filename: String, index: Int = 0) = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = "",
        type = "output",
        isVideo = false,
        index = index
    )

    private fun indexFile(name: String = "srv") = File(tempFolder.root, "gallery_metadata_$name.json")

    // ============================================================
    // 1. 去重（纯函数）
    // ============================================================

    @Test
    fun `已知条目被滤掉只留净新增`() {
        val existing = listOf(item("p1", "a.png"), item("p1", "b.png"))
        val incoming = listOf(item("p1", "b.png"), item("p1", "c.png"), item("p1", "d.png"))
        val appended = GalleryMetadataCache.olderItemsNotKnown(existing, incoming)
        assertEquals(
            listOf("p1-c.png", "p1-d.png"),
            appended.map { "${it.promptId}-${it.filename}" }
        )
    }

    @Test
    fun `incoming 内部重复只保留首次出现`() {
        val existing = emptyList<GalleryItem>()
        val incoming = listOf(item("p1", "a.png"), item("p1", "a.png"), item("p1", "z.png"))
        val appended = GalleryMetadataCache.olderItemsNotKnown(existing, incoming)
        assertEquals(2, appended.size)
        assertEquals(listOf("a.png", "z.png"), appended.map { it.filename })
    }

    @Test
    fun `同 promptId 不同文件名是不同条目不得整批吞掉`() {
        // P1-19 口径：身份是 (promptId, filename) 而不是 promptId ——
        // 批量出图 4 张共用一个 promptId，按 promptId 去重会把同批更早的图整批漏掉。
        val existing = listOf(item("p1", "a.png"))
        val incoming = listOf(item("p1", "a.png"), item("p1", "a2.png"))
        assertEquals(listOf("a2.png"), GalleryMetadataCache.olderItemsNotKnown(existing, incoming).map { it.filename })
    }

    // ============================================================
    // 2. 追加合并（临时目录钉住落盘行为）
    // ============================================================

    @Test
    fun `净新增追加到尾部并按位置重排`() {
        val existing = listOf(item("new1", "0.png"), item("new2", "1.png"))
        assertTrue(GalleryMetadataCache.writeMetadataToFile(indexFile(), existing))

        val older = listOf(item("old1", "2.png"), item("old2", "3.png"))
        val merged = GalleryMetadataCache.mergeOlderInto(tempFolder.root, "srv", older)

        assertEquals(
            listOf("new1", "new2", "old1", "old2"),
            merged?.map { it.promptId }
        )
        // index 按位置重排（0..n-1），不能带着解析期的临时序号
        assertEquals(listOf(0, 1, 2, 3), merged?.map { it.index })
        // 落盘内容与内存结果一致（重读一遍核对）
        val reread = GalleryMetadataCache.loadMetadataFromDir(tempFolder.root, "srv")
        assertEquals(listOf("new1", "new2", "old1", "old2"), reread?.map { it.promptId })
    }

    @Test
    fun `重叠窗口只追加更早段`() {
        // 分页窗口=最新 200 条 history 时，头 100 条与索引重叠（首屏已收），
        // 模拟 incoming：前 2 条已知、后 2 条更早。
        val existing = listOf(item("p1", "a.png"), item("p2", "b.png"))
        assertTrue(GalleryMetadataCache.writeMetadataToFile(indexFile(), existing))
        val incoming = listOf(
            item("p1", "a.png"), item("p2", "b.png"),   // 重叠段
            item("p3", "c.png"), item("p4", "d.png")    // 更早段
        )
        val merged = GalleryMetadataCache.mergeOlderInto(tempFolder.root, "srv", incoming)
        assertEquals(4, merged?.size)
        assertEquals(listOf("p1", "p2", "p3", "p4"), merged?.map { it.promptId })
    }

    @Test
    fun `整页全认识时不写盘不改动`() {
        val existing = listOf(item("p1", "a.png"), item("p2", "b.png"))
        assertTrue(GalleryMetadataCache.writeMetadataToFile(indexFile(), existing))
        val bytesBefore = indexFile().readBytes()

        val merged = GalleryMetadataCache.mergeOlderInto(
            tempFolder.root, "srv",
            listOf(item("p1", "a.png"), item("p2", "b.png"))
        )

        assertEquals(existing.map { it.promptId }, merged?.map { it.promptId })
        assertTrue(bytesBefore.contentEquals(indexFile().readBytes()))
    }

    @Test
    fun `索引读不出来时拒绝用更早页重建`() {
        // 无索引文件 = 读不出来（null）：mergeMetadata 会用首屏数据新建，
        // mergeOlderInto **必须**拒绝 —— 更早页不含最新条目，拿它当整份索引会把
        // 首屏挤掉。返回 null，调用方（GalleryRepository.loadOlderInternal）不动列表。
        val merged = GalleryMetadataCache.mergeOlderInto(
            tempFolder.root, "srv",
            listOf(item("old1", "9.png"))
        )
        assertNull(merged)
        assertFalse(indexFile().exists())
    }

    @Test
    fun `更早页追加不影响墓碑与既有标记的读取`() {
        // sourceMissing 是本地观测：整条重叠条目被滤掉后保留的是**索引里那一份**
        //（带标记），incoming 的"无标记副本"不会覆盖它。
        val flagged = item("p1", "a.png").copy(sourceMissing = true)
        assertTrue(GalleryMetadataCache.writeMetadataToFile(indexFile(), listOf(flagged)))
        val incoming = listOf(item("p1", "a.png"), item("p2", "older.png"))
        val merged = GalleryMetadataCache.mergeOlderInto(tempFolder.root, "srv", incoming)
        assertEquals(2, merged?.size)
        assertTrue(merged!!.first { it.filename == "a.png" }.sourceMissing)
    }
}
