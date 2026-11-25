package sh.hnet.comfychair.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.LoraEffectTable
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable

/**
 * LoRA 档案并入目录树的展示层纯函数测试。
 *
 * 全部使用**构造数据**——未知 kind 排序、「未分类」兜底、档案键划分、展开/折叠摊平
 * 这些边界行为，不依赖任何外部数据文件。
 *
 * 合并只在内存展示层完成（[splitArchiveKeys] / [buildCategories] / [buildCategorizedRows]），
 * 数据文件零修改。
 */
class ModelIntroTreeTest {

    @After
    fun tearDown() {
        ModelIntroTable.clearForTest()
        LoraEffectTable.clearForTest()
    }

    // ---------------- 档案键划分（构造数据） ----------------

    @Test
    fun `档案键按介绍表是否收录划分已合并与孤儿`() {
        val known = setOf("a.safetensors", "b.safetensors")
        val archiveKeys = listOf("a.safetensors", "b.safetensors", "c.safetensors", "d.safetensors")

        val (joined, orphans) = splitArchiveKeys(archiveKeys, known)

        assertEquals("被介绍表收录的进已合并", listOf("a.safetensors", "b.safetensors"), joined)
        assertEquals("未收录的进孤儿", listOf("c.safetensors", "d.safetensors"), orphans)
        assertEquals("档案总条目数不丢", archiveKeys.size, joined.size + orphans.size)
    }

    @Test
    fun `反证_介绍表删掉一个已合并键_它必须落入孤儿`() {
        val known = setOf("a.safetensors", "b.safetensors")
        val archiveKeys = listOf("a.safetensors", "b.safetensors", "c.safetensors")

        val (joined, orphans) = splitArchiveKeys(archiveKeys, known)
        assumeTrue("前提：确实存在已合并条目", joined.isNotEmpty())

        // 从介绍表侧删掉第一个已合并键（模拟"这条模型从溯源表里下架"）
        val removed = joined.first()
        val shrunk = known - removed
        val (joined2, orphans2) = splitArchiveKeys(archiveKeys, shrunk)

        assertEquals("已合并少一条", joined.size - 1, joined2.size)
        assertEquals("孤儿多一条", orphans.size + 1, orphans2.size)
        assertTrue("被删键必须出现在孤儿里", removed in orphans2)
        assertFalse("被删键不应再出现在已合并里", removed in joined2)
    }

    // ---------------- 构造数据：边界行为 ----------------

    @Test
    fun `未知kind排最后_空kind归入未分类兜底桶`() {
        val intros = listOf(
            ModelIntro(file = "a.safetensors", kind = "LoRA", rel = "loras/x/a.safetensors"),
            ModelIntro(file = "b.safetensors", kind = "新奇分类", rel = "others/b.safetensors"),
            ModelIntro(file = "c.safetensors", kind = null, rel = "c.safetensors"),
            ModelIntro(file = "d.safetensors", kind = "  ", rel = "d.safetensors"),
        )
        val categories = buildCategories(intros)
        assertEquals(listOf("LoRA", "新奇分类", FALLBACK_KIND), categories.map { it.kind })
        assertEquals(2, categories[2].total)
    }

    @Test
    fun `摊平_档案节点带孤儿_收起态只显示节点行`() {
        val categories = buildCategories(
            listOf(
                ModelIntro(file = "a.safetensors", kind = "LoRA", rel = "loras/sub/a.safetensors"),
                ModelIntro(file = "b.safetensors", kind = "LoRA", rel = "b.safetensors"),
            )
        )
        val orphans = listOf("o1.safetensors" to "效果 1", "o2.safetensors" to "效果 2")

        // 全收起：档案节点 1 行 + 类别节点 1 行 = 2 行
        val collapsed = buildCategorizedRows(categories, "档案（2 条）", orphans, emptySet())
        assertEquals(2, collapsed.size)
        assertEquals(ARCHIVE_NODE_KEY, collapsed[0].key)
        assertEquals("档案（2 条）", (collapsed[0] as IntroRow.Node).name)
        assertEquals(2, (collapsed[0] as IntroRow.Node).modelCount)

        // 展开档案节点：+2 条孤儿；展开 LoRA：+1 直属 + 1 分组节点
        val expandedArchive = buildCategorizedRows(categories, "档案（2 条）", orphans, setOf(ARCHIVE_NODE_KEY))
        assertEquals(4, expandedArchive.size)
        val orphansShown = expandedArchive.filterIsInstance<IntroRow.Orphan>()
        assertEquals(listOf("o1.safetensors", "o2.safetensors"), orphansShown.map { it.fileName })
        assertEquals("效果 1", orphansShown[0].effect)

        val expandedLora = buildCategorizedRows(categories, "档案（2 条）", orphans, setOf("k:LoRA"))
        // 档案节点 + LoRA 节点 + 直属 b + 分组节点 loras/sub = 4 行
        assertEquals(4, expandedLora.size)
        val groupNode = expandedLora.filterIsInstance<IntroRow.Node>().last()
        assertTrue(groupNode.isGroupTitle)
        assertEquals("loras/sub", groupNode.name)

        // 全部展开：档案节点 + 2 孤儿 + LoRA 节点 + 直属 b + 分组节点 + 组内 a = 7 行
        val allOpen = buildCategorizedRows(
            categories, "档案（2 条）", orphans, setOf(ARCHIVE_NODE_KEY, "k:LoRA", "g:LoRA|loras/sub")
        )
        assertEquals(7, allOpen.size)
        assertEquals(2, allOpen.filterIsInstance<IntroRow.Entry>().size)
    }

    @Test
    fun `无孤儿时不出现档案节点`() {
        val categories = buildCategories(
            listOf(ModelIntro(file = "a.safetensors", kind = "LoRA", rel = "a.safetensors"))
        )
        val rows = buildCategorizedRows(categories, "档案（0 条）", emptyList(), emptySet())
        assertEquals(1, rows.size)
        assertEquals("k:LoRA", rows[0].key)
    }

    @Test
    fun `splitArchiveKeys输入顺序即输出顺序且大小写不敏感`() {
        val (joined, orphans) = splitArchiveKeys(
            listOf("B.safetensors", "a.safetensors", "orphan.safetensors"),
            setOf("A.SAFETENSORS"),  // 介绍表侧任意大小写
        )
        assertEquals(listOf("a.safetensors"), joined)
        assertEquals(listOf("B.safetensors", "orphan.safetensors"), orphans)
    }
}
