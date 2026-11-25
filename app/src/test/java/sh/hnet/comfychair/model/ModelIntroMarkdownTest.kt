package sh.hnet.comfychair.model

import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Markdown 模板（[ModelIntroMarkdown]）的纯函数测试。
 *
 * 守的是模板自身的契约：
 * 1. **一处定义**：界面渲染用的头部块与导出的完整文档同源（同一批构件、同一顺序）；
 * 2. **决策 1**：元信息头不出现作者（author 字段留在数据里）；
 * 3. **宁可少一行**：空字段整行/整节不渲染；
 * 4. 「效果」优先 effect、缺失退回 summary，并剥掉数据自带的「效果：」前缀；
 * 5. 触发词在有 tags 时输出反引号列表，滑块型 LoRA（有权重无 tags）输出提示，其余不渲染；
 * 6. 参数两种来源并列不合并；社区样本数不到门槛不写数字；
 * 7. 导出文件名必须能安全落到 `cacheDir/share/`（单段、无路径片段）。
 *
 * 所有用例都显式传 `effectFallback = null`，与全局的 LoRA 效果档案隔离。
 */
class ModelIntroMarkdownTest {

    @Before
    fun setUp() {
        LoraEffectTable.clearForTest()
    }

    @After
    fun tearDown() {
        LoraEffectTable.clearForTest()
    }

    private val fullIntro = ModelIntro(
        file = "AWPainting_v1.2.safetensors",
        rel = "checkpoints/SD1.5/anime/AWPainting_v1.2.safetensors",
        kind = "大模型",
        name = "AWPainting",
        type = "大模型（Checkpoint）",
        base = "SD 1.5",
        author = "DynamicWang",
        version = "v1.2",
        summary = "「AWPainting」是 SD 1.5 底模的大模型…",
        effect = "效果：动画质感更强",
        params = linkedMapOf("步数" to "28 步", "CFG" to "7"),
        authorParams = linkedMapOf("步数" to "30", "CFG" to "4.5", "采样器" to "Euler a"),
        tags = listOf("anime", "ink"),
        communityPrompt = CommunityPrompt(positive = "masterpiece, best quality"),
        communityParams = linkedMapOf(
            "sampler" to CommunityParam("Euler a", 17),
            "cfg" to CommunityParam("4", 2),
        ),
        introOriginal = "作者原文……",
    )

    // ---------------- 完整文档：逐字钉住模板 ----------------

    @Test
    fun `完整条目按模板逐字输出`() {
        val expected = """
        |# AWPainting
        |
        |名字：AWPainting
        |底模：SD 1.5
        |版本：v1.2
        |类型：大模型（Checkpoint）
        |模型效果：动画质感更强
        |
        |## 推荐参数
        |
        || 来源 | 步数 | CFG | 采样器 |
        || --- | --- | --- | --- |
        || 作者推荐 | 30 | 4.5 | Euler a |
        || 你的工作流 | 28 步 | 7 |  |
        |
        |## 触发词
        |
        |`anime`、`ink`
        |
        |## 推荐提示词
        |
        |masterpiece, best quality
        |
        |## 社区常用
        |
        |- 采样器：Euler a（17 张）
        |- CFG：4
        |
        |## 作者原文
        |
        |作者原文……
        |""".trimMargin()
        assertEquals(expected, ModelIntroMarkdown.buildMarkdown(fullIntro, effectFallback = null))
    }

    @Test
    fun `元信息头不显示作者`() {
        val header = ModelIntroMarkdown.buildHeaderMarkdown(fullIntro, effectFallback = null)
        assertFalse("元信息头不得出现作者（用户决策 1）", header.contains("作者"))
        assertFalse("元信息头不得出现作者名", header.contains("DynamicWang"))

        val export = ModelIntroMarkdown.buildMarkdown(fullIntro, effectFallback = null)
        assertFalse("导出文档不得出现「作者：」行", export.contains("作者："))
        assertFalse(export.contains("DynamicWang"))
    }

@Test
    fun `头部块与导出文档同源_没有重复的节标题`() {
        val header = ModelIntroMarkdown.buildHeaderMarkdown(fullIntro, effectFallback = null)
        val export = ModelIntroMarkdown.buildMarkdown(fullIntro, effectFallback = null)

        val headerLines = header.split("\n").filter { it.isNotBlank() }
        val exportLines = export.split("\n")
        for (line in headerLines) {
            assertTrue("导出文档必须包含同一行：$line", line in exportLines)
        }
        assertFalse("头部块只含标题与元信息，不含分节", header.contains("## "))
    }

    /**
     * 界面渲染路径的硬约束：commonmark 把**单个换行**当软换行（MarkdownParser 会渲染成空格），
     * 只有空行分段的每一条才是独立 Paragraph、渲染出来才各占一行。
     * 这里直接用 commonmark 解析界面变体，钉住「每条元信息一个段落」。
     *
     * 同时钉住「界面头部块不带 `# 名字` 大标题」：用户要求开头就是分行元信息头
     * （示例第一行就是「名字：…」），再叠标题会让模型名在首屏出现两次。
     */
    @Test
    fun `头部块每条元信息都是独立段落`() {
        val document = Parser.builder().build()
            .parse(ModelIntroMarkdown.buildHeaderMarkdown(fullIntro, effectFallback = null))

        val headings = mutableListOf<String>()
        val paragraphs = mutableListOf<String>()
        var node: Node? = document.firstChild
        while (node != null) {
            when (node) {
                is Heading -> headings += (node.firstChild as? Text)?.literal.orEmpty()
                is Paragraph -> paragraphs += (node.firstChild as? Text)?.literal.orEmpty()
                else -> Unit
            }
            node = node.next
        }
        assertTrue("界面头部块不得带大标题（模型名会重复两次）：$headings", headings.isEmpty())
        assertEquals(
            listOf(
                "名字：AWPainting",
                "底模：SD 1.5",
                "版本：v1.2",
                "类型：大模型（Checkpoint）",
                "模型效果：动画质感更强",
            ),
            paragraphs,
        )
    }

    /**
     * 真实数据巡检：assets 里全部条目都能按模板生成、且不出现「效果：效果：」与作者行。
     * 数据换版时这条会先响，而不是用户导出时看到坏文档。
     */
    @Test
    fun `真实数据全部条目都能生成合法模板`() {
        val text = listOf(File("src/main/assets/model_intros.json"), File("app/src/main/assets/model_intros.json"))
            .firstOrNull { it.isFile }?.readText()
        assumeTrue("model_intros.json 不可用时跳过", text != null)
        ModelIntroTable.setTableJsonForTest(text!!)
        val all = ModelIntroTable.all()
        assumeTrue("model_intros.json 不可用时跳过", all.isNotEmpty())
        try {
            for (intro in all) {
                val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                assertTrue("${intro.file} 必须以标题开头", markdown.startsWith("# "))
                // 「不显示作者」管的是元信息头：原文/摘要里出现「作者 tsolful」是数据事实，不算违规
                val header = ModelIntroMarkdown.headerLines(intro, effectFallback = null)
                assertTrue(
                    "${intro.file} 元信息头出现作者行：$header",
                    header.none { it.startsWith("作者") },
                )
                assertTrue(
                    "${intro.file} 出现重复效果前缀：$header",
                    header.none {
                        it.startsWith("模型效果：效果") || it.startsWith("LoRA 效果：效果")
                    },
                )
                // 表格行必须首尾都是竖线（否则 Markdown 表格会整块失效）
                for (line in markdown.lines()) {
                    if (!line.startsWith("|")) continue
                    assertTrue("${intro.file} 表格行不闭合：$line", line.endsWith("|"))
                }
            }
        } finally {
            ModelIntroTable.clearForTest()
        }
    }

    // ---------------- 空字段：整行/整节不渲染 ----------------

    @Test
    fun `只有文件名时只渲染标题与名字行`() {
        val minimal = ModelIntro(file = "a.safetensors")
        assertEquals(
            "# a.safetensors\n\n名字：a.safetensors\n",
            ModelIntroMarkdown.buildMarkdown(minimal, effectFallback = null),
        )
    }

    @Test
    fun `空字段整行不渲染_空节整节不渲染`() {
        val intro = ModelIntro(
            file = "b.safetensors",
            name = "B",
            base = "  ",
            version = "",
            kind = null,
            params = emptyMap(),
            authorParams = emptyMap(),
            tags = emptyList(),
            communityPrompt = null,
            communityParams = emptyMap(),
            introOriginal = null,
        )
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertEquals("# B\n\n名字：B\n", markdown)
        assertFalse(markdown.contains("底模"))
        assertFalse(markdown.contains("版本"))
        assertFalse(markdown.contains("类型"))
        assertFalse(markdown.contains("效果"))
        assertTrue(
            "空条目不产生任何分节",
            ModelIntroMarkdown.buildSections(intro).isEmpty(),
        )
    }

    // ---------------- 效果行 ----------------

    @Test
    fun `效果优先effect_剥掉自身的效果前缀`() {
        val intro = ModelIntro(file = "a", effect = "效果：皮肤更细腻", summary = "摘要不该出现")
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("效果：皮肤更细腻"))
        assertFalse(markdown.contains("效果：效果："))
        assertFalse("effect 存在时摘要不重复出现", markdown.contains("摘要不该出现"))
    }

    @Test
    fun `半角冒号的效果前缀也剥掉`() {
        val intro = ModelIntro(file = "a", effect = "效果:皮肤更细腻")
        assertTrue(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                .contains("效果：皮肤更细腻")
        )
    }

    @Test
    fun `没有effect时退回摘要`() {
        val intro = ModelIntro(file = "a", summary = "一句话说明")
        assertTrue(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                .contains("效果：一句话说明")
        )
    }

    @Test
    fun `效果档案兜底排在摘要前面`() {
        val intro = ModelIntro(file = "a", summary = "摘要")
        assertTrue(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = "档案里的效果")
                .contains("效果：档案里的效果")
        )
    }

    // ---------------- 自定义字段（v4.21 编辑器写入） ----------------

    /**
     * 自定义字段行紧跟元信息头之后（与详情页头部块同位置同措辞，导出与界面一致）；
     * 空白项（label 或 value 空白）整行剔除——与落盘侧 introToJson 同一判定。
     */
    @Test
    fun `自定义字段紧跟元信息头且空白项剔除`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            base = "SD 1.5",
            customFields = listOf(
                CustomField(label = "基座", value = "Qwen-Image-2.1"),
                CustomField(label = "空白值", value = "  "),
                CustomField(label = " ", value = "没名字"),
            ),
        )
        assertEquals(
            "# A\n\n名字：A\n底模：SD 1.5\n\n基座：Qwen-Image-2.1\n",
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null),
        )
    }

    @Test
    fun `没有自定义字段时导出不出现该块_与旧期望逐字一致`() {
        val intro = ModelIntro(file = "a.safetensors", name = "A", customFields = emptyList())
        assertEquals("# A\n\n名字：A\n", ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null))
    }

    // ---------------- 触发词 ----------------

    @Test
    fun `有tags时输出反引号词表`() {
        val intro = ModelIntro(file = "a", tags = listOf("soft lighting", "ink"))
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("## 触发词\n\n`soft lighting`、`ink`"))
    }

    @Test
    fun `无tags但有权重建议时输出滑块提示`() {
        val intro = ModelIntro(
            file = "slider.safetensors",
            loraWeight = LoraWeightSuggestion(min = 0.5, max = 1.0),
        )
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("## 触发词\n\n滑块调节，无需触发词"))
    }

    @Test
    fun `既无tags也无权重建议时触发词整节不渲染`() {
        val intro = ModelIntro(file = "a")
        assertFalse(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                .contains("## 触发词")
        )
    }

    // ---------------- 推荐参数 ----------------

    @Test
    fun `只有工作流参数时不带来源列`() {
        val intro = ModelIntro(file = "a", params = linkedMapOf("步数" to "28 步", "CFG" to "7"))
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(
            markdown.contains("## 推荐参数\n\n| 步数 | CFG |\n| --- | --- |\n| 28 步 | 7 |")
        )
        assertFalse("单一来源不写来源列", markdown.contains("你的工作流"))
    }

    @Test
    fun `只有作者参数时同样不带来源列`() {
        val intro = ModelIntro(file = "a", authorParams = linkedMapOf("采样器" to "Euler a"))
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("| 采样器 |\n| --- |\n| Euler a |"))
        assertFalse(markdown.contains("作者推荐"))
    }

    @Test
    fun `未列进预设顺序的新键排在后面`() {
        val intro = ModelIntro(
            file = "a",
            params = linkedMapOf("CFG" to "7", "自定义项" to "x"),
        )
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("| CFG | 自定义项 |"))
    }

    @Test
    fun `表格单元格里的竖线被转义`() {
        val intro = ModelIntro(file = "a", params = linkedMapOf("说明" to "a|b"))
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("a\\|b"))
    }

    // ---------------- 社区常用 ----------------

    @Test
    fun `社区样本数达到门槛才写数字`() {
        val intro = ModelIntro(
            file = "a",
            communityParams = linkedMapOf(
                "sampler" to CommunityParam("Euler a", ModelIntroMarkdown.COMMUNITY_COUNT_MIN_DISPLAY),
                "steps" to CommunityParam("30", ModelIntroMarkdown.COMMUNITY_COUNT_MIN_DISPLAY - 1),
            ),
        )
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("- 采样器：Euler a（3 张）"))
        assertTrue("不到门槛只写值不写数字", markdown.contains("- 步数：30\n"))
        assertFalse(markdown.contains("- 步数：30（"))
    }

    @Test
    fun `没有社区参数时整节不渲染`() {
        val intro = ModelIntro(file = "a", communityParams = emptyMap())
        assertFalse(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                .contains("## 社区常用")
        )
    }

    // ---------------- 作者原文 ----------------

    @Test
    fun `作者原文有值才渲染且给纯文本`() {
        val intro = ModelIntro(file = "a", introOriginal = "  line1\nline2  ")
        val markdown = ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
        assertTrue(markdown.contains("## 作者原文\n\nline1\nline2"))
        assertFalse("不用 HTML details（commonmark 会把内容一起丢掉）", markdown.contains("<details>"))
    }

    @Test
    fun `作者原文为空时整节不渲染`() {
        val intro = ModelIntro(file = "a", introOriginal = "   ")
        assertFalse(
            ModelIntroMarkdown.buildMarkdown(intro, effectFallback = null)
                .contains("## 作者原文")
        )
    }

    // ---------------- 导出文件名 ----------------

    @Test
    fun `文件名去掉路径片段与非法字符`() {
        val name = ModelIntroMarkdown.fileNameFor(
            ModelIntro(file = "x.safetensors", name = "../万象熔炉 / Anything:XL")
        )
        // SafeFileName 取最后一段（「 Anything:XL」）并把冒号换成 _
        assertEquals("Anything_XL.md", name)
        assertFalse("不得含路径分隔符：$name", name.contains("/") || name.contains("\\"))
        assertFalse("不得含上跳片段：$name", name.contains(".."))
        assertFalse("不得含冒号：$name", name.contains(":"))
    }

    @Test
    fun `没有名字时用文件名去扩展名`() {
        assertEquals("AWPainting_v1.2.md", ModelIntroMarkdown.fileNameFor(ModelIntro(file = "AWPainting_v1.2.safetensors")))
    }

    @Test
    fun `名字与文件名都净化不出来时退回固定名`() {
        assertEquals("model_intro.md", ModelIntroMarkdown.fileNameFor(ModelIntro(file = "...", name = "///...")))
    }
}