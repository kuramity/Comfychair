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
import java.io.File

/**
 * Q4（v4.16.6）LoRA 档案并入目录树的展示层纯函数测试。
 *
 * 两类用例：
 * 1. **真实数据**（assets 内置那份）——钉住合并去重的具体数字（已合并 36 / 孤儿 11）
 *    与三层树规模（8 类 / LoRA 94 条全在），数据换版时在这里响铃提醒同步改数字；
 * 2. **构造数据**——未知 kind 排序、「未分类」兜底、展开/折叠摊平这些边界行为。
 *
 * 合并只在内存展示层完成（[splitArchiveKeys] / [buildCategories] / [buildCategorizedRows]），
 * 数据文件零修改——真实数据用例读到什么就断言什么，不做任何回写。
 */
class ModelIntroTreeTest {

    @After
    fun tearDown() {
        ModelIntroTable.clearForTest()
        LoraEffectTable.clearForTest()
    }

    // ---------------- 真实数据装载（assets 不可用时跳过，不误报） ----------------

    /** JVM 单测的工作目录是模块目录（app/），assets 在 src/main/assets 下。 */
    private fun loadAssetText(name: String): String? {
        for (dir in listOf("src/main/assets", "app/src/main/assets")) {
            val f = File(dir, name)
            if (f.exists()) return f.readText()
        }
        return null
    }

    /** 载入真实介绍表；失败返回 null 让用例跳过。 */
    private fun loadRealIntros(): List<ModelIntro>? {
        val text = loadAssetText("model_intros.json") ?: return null
        ModelIntroTable.setTableJsonForTest(text)
        val all = ModelIntroTable.all()
        if (all.isEmpty()) return null
        return all
    }

    /** 载入真实效果档案；失败返回 null。 */
    private fun loadRealEffects(): Map<String, String>? {
        val text = loadAssetText("lora_effects.json") ?: return null
        LoraEffectTable.setTableJsonForTest(text)
        val all = LoraEffectTable.all()
        if (all.isEmpty()) return null
        return all
    }

    // ---------------- Q4-1 去重划分（真实数据） ----------------

    @Test
    fun `真实数据档案去重_已合并36条_孤儿11条`() {
        val intros = assumeTrue("model_intros.json 不可用时跳过", true).let { loadRealIntros() }
        assumeTrue("model_intros.json 不可用时跳过", intros != null)
        val effects = loadRealEffects()
        assumeTrue("lora_effects.json 不可用时跳过", effects != null)

        val known = intros!!.map { it.file.lowercase() }.toSet()
        val (joined, orphans) = splitArchiveKeys(effects!!.keys.toList().sorted(), known)

        // 以下三个数字：2026-11-21 模型库巡检同步后更新（已合并 42 → 36 / 孤儿 5 → 11，总数 47 不变）
        assertEquals("已合并条目数（档案与介绍表交集）", 36, joined.size)
        assertEquals("孤儿条目数（介绍表没收录的）", 11, orphans.size)
        assertEquals("档案总条目数不丢", 47, joined.size + orphans.size)
    }

    @Test
    fun `反证_介绍表删掉一个已合并键_它必须落入孤儿`() {
        val intros = loadRealIntros()
        assumeTrue("model_intros.json 不可用时跳过", intros != null)
        val effects = loadRealEffects()
        assumeTrue("lora_effects.json 不可用时跳过", effects != null)

        val known = intros!!.map { it.file.lowercase() }.toSet()
        val (joined, orphans) = splitArchiveKeys(effects!!.keys.toList().sorted(), known)
        assumeTrue("前提：确实存在已合并条目", joined.isNotEmpty())

        // 从介绍表侧删掉第一个已合并键（模拟"这条模型从溯源表里下架"）
        val removed = joined.first()
        val shrunk = known - removed
        val (joined2, orphans2) = splitArchiveKeys(effects.keys.toList().sorted(), shrunk)

        assertEquals("已合并少一条", joined.size - 1, joined2.size)
        assertEquals("孤儿多一条", orphans.size + 1, orphans2.size)
        assertTrue("被删键必须出现在孤儿里", removed in orphans2)
        assertFalse("被删键不应再出现在已合并里", removed in joined2)
    }

    // ---------------- Q4-3 三层树构建（真实数据） ----------------

    @Test
    fun `真实数据三层树_8类173条_LoRA94条全在`() {
        val intros = loadRealIntros()
        assumeTrue("model_intros.json 不可用时跳过", intros != null)

        val categories = buildCategories(intros!!)
        assertEquals("类别数", 8, categories.size)
        assertEquals("条目总数不丢", intros.size, categories.sumOf { it.total })

        val byKind = categories.associateBy { it.kind }
        // 派工单口径：背景去除 2 / 大模型 15 / 图像编码器 3 / 扩散模型 34 / LoRA 94 /
        // 放大模型 5 / VAE 18 / 几何估计 2
        // （扩散模型 33 → 34、LoRA 92 → 94 为 2026-11-22 模型库同步后更新：新增5删2 → 173条）
        assertEquals(2, byKind.getValue("背景去除").total)
        assertEquals(15, byKind.getValue("大模型").total)
        assertEquals(3, byKind.getValue("图像编码器").total)
        assertEquals(34, byKind.getValue("扩散模型").total) // 2026-11-22 模型库同步后更新（新增5删2 → 173条）
        assertEquals(94, byKind.getValue("LoRA").total)     // 2026-11-22 模型库同步后更新（新增5删2 → 173条）
        assertEquals(5, byKind.getValue("放大模型").total)
        assertEquals(18, byKind.getValue("VAE").total)
        assertEquals(2, byKind.getValue("几何估计").total)

        // 类别展示顺序与派工单一致
        assertEquals(
            listOf("背景去除", "大模型", "图像编码器", "扩散模型", "LoRA", "放大模型", "VAE", "几何估计"),
            categories.map { it.kind },
        )
    }

    @Test
    fun `真实数据LoRA按目录段分组_展开后94条条目行都在`() {
        val intros = loadRealIntros()
        assumeTrue("model_intros.json 不可用时跳过", intros != null)
        val effects = loadRealEffects()
        assumeTrue("lora_effects.json 不可用时跳过", effects != null)

        val categories = buildCategories(intros!!)
        val lora = categories.first { it.kind == "LoRA" }
        // LoRA 有分组（rel 目录段）且分组条目数与总数自洽
        assertTrue("LoRA 应按目录段分组", lora.groups.isNotEmpty())
        assertEquals(94, lora.total) // 2026-11-22 模型库同步后更新（新增5删2 → 173条）

        // 摊平 + 全部展开：LoRA 类别下 94 条条目行一条不少
        val fullExpanded = buildCategorizedRows(
            categories,
            archiveNodeName = "LoRA 效果档案（11 条）",
            archiveOrphans = emptyList(),
            expanded = categories.flatMap { cat ->
                listOf("k:${cat.kind}") + cat.groups.map { "g:${cat.kind}|${it.folder}" }
            }.toSet(),
        )
        val loraEntries = fullExpanded
            .filterIsInstance<IntroRow.Entry>()
            .filter { (it.intro.kind ?: "") == "LoRA" }
        assertEquals("LoRA 条目行数", 94, loraEntries.size) // 2026-11-22 模型库同步后更新（新增5删2 → 173条）
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
