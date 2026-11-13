package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * 图库本地索引的合并规则。
 *
 * 背景（这是"图库突然一张都没有"的根因所在）：图库原先只以服务器的 `/history` 为数据源，
 * 而 ComfyUI 的历史是**内存态**的，重启一次就清空 —— 图片文件明明还在 output 目录里，
 * 图库却空了；更糟的是那次刷新把本地索引也一起覆盖成了空数组。
 *
 * 修法是把写入语义从"覆盖"改成"并集"，这几条用例钉的就是并集规则。
 */
class GalleryMergeTest {

    private fun item(promptId: String, filename: String, index: Int = 0) = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = "",
        type = "output",
        isVideo = false,
        index = index
    )

    @Test
    fun `服务器返回空列表时本地索引原样保留`() {
        // 最关键的一条：ComfyUI 重启后 /history 是空的，
        // 但绝不能因此把本地攒下的索引丢掉（旧实现正是这么干的）
        val existing = listOf(item("p1", "a.png"), item("p2", "b.png"))

        val merged = GalleryMetadataCache.mergeItems(existing, emptyList())

        assertEquals("服务器说「没有」不等于「删掉」", 2, merged.size)
        assertEquals(listOf("a.png", "b.png"), merged.map { it.filename })
    }

    @Test
    fun `服务器条目排在前面_本地独有的排在后面`() {
        // 顺序不是随手定的：服务器返回的是"现在还在的"（较新），
        // 本地独有的那些是"服务器已经不认识"的（较旧），图库按新的在前展示
        val existing = listOf(item("old", "old.png"))
        val incoming = listOf(item("new", "new.png"))

        val merged = GalleryMetadataCache.mergeItems(existing, incoming)

        assertEquals(listOf("new.png", "old.png"), merged.map { it.filename })
    }

    @Test
    fun `两边都有的条目只保留一份`() {
        val existing = listOf(item("p1", "a.png"), item("p2", "b.png"))
        val incoming = listOf(item("p2", "b.png"))

        val merged = GalleryMetadataCache.mergeItems(existing, incoming)

        assertEquals(2, merged.size)
        assertEquals("服务器那份应排在前", listOf("b.png", "a.png"), merged.map { it.filename })
    }

    @Test
    fun `同一个 prompt 产出的多张图各自成条`() {
        // 去重键必须含 filename：一次生成可能出多张图，
        // 只按 promptId 去重会把它们互相吞掉
        val existing = listOf(item("p1", "a.png"), item("p1", "b.png"))

        val merged = GalleryMetadataCache.mergeItems(existing, emptyList())

        assertEquals(2, merged.size)
    }

    @Test
    fun `合并后重新编号_序号不重复`() {
        // index 是解析阶段的临时序号，两条来源合并后必须重排，
        // 否则会出现两条记录带同一个序号（界面排序会变得不确定）
        val existing = listOf(item("p1", "a.png", index = 0))
        val incoming = listOf(item("p2", "b.png", index = 0))

        val merged = GalleryMetadataCache.mergeItems(existing, incoming)

        assertEquals(listOf(0, 1), merged.map { it.index })
    }

    @Test
    fun `两边都为空时结果为空`() {
        assertEquals(0, GalleryMetadataCache.mergeItems(emptyList(), emptyList()).size)
    }
}
