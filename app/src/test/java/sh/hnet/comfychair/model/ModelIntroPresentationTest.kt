package sh.hnet.comfychair.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 元信息头与摘要清洗（[ModelIntroPresentation]）的纯函数测试。
 *
 * 守的是 2026-11-23 的两条用户决策：
 * 1. 详情的开头是**分行元信息头**（名字/底模/版本/类型/效果），不是一大段散文；
 * 2. 散文摘要里与下方独立区块重复的模板段（推荐参数 / 作者推荐 tag / 搭配 VAE /
 *    在用工作流 / 风格）与作者名一律剥掉。
 *
 * 所有用例显式传 `effectFallback = null`，与全局的 LoRA 效果档案隔离
 * （与 [ModelIntroMarkdownTest] 同一纪律）。
 */
class ModelIntroPresentationTest {

    @Before
    fun setUp() {
        LoraEffectTable.clearForTest()
    }

    @After
    fun tearDown() {
        LoraEffectTable.clearForTest()
    }

    // ---------------- 三种真实样本（数据侧脚本实际拼出来的形态） ----------------

    /** 大模型：模板句 + 作者与版本 + 推荐参数 + 搭配 VAE + 在用工作流，全是重复段。 */
    private val sampleCheckpoint =
        "「AWPainting」是 SD 1.5 底模的大模型（Checkpoint），作者 DynamicWang（版本 v1.2）。 " +
            "推荐参数：步数 20 步、CFG 5.7、采样器 dpmpp_2m、调度器 karras、降噪 0.9 " +
            "搭配 VAE：vae-ft-mse-840000-ema-pruned.safetensors " +
            "在你的工作流里用过：03c_OpenPose姿势锁、03d_Tile细节修复。"

    /** 只有基本信息 + 占位：整条都是模板套话。 */
    private val samplePlaceholder =
        "「lucida (背景去除)」是 通用 底模的背景去除模型，作者 Comfy-Org。（暂无更多介绍信息）"

    /** LoRA：模板句 + 作者与版本 + 作者推荐 tag。 */
    private val sampleLora =
        "「DeepSeek Girl (Chibi) | ...」是 Anima 底模的LoRA，作者 HermitST（版本 v1.0）。 " +
            "作者推荐 tag：deepseek_whale_girl, ..."

    @Test
    fun `样本一_大模型散文清洗后为空`() {
        assertEquals("", ModelIntroPresentation.cleanIntroSummary(sampleCheckpoint))
    }

    @Test
    fun `样本二_占位条目清洗后为空`() {
        assertEquals("", ModelIntroPresentation.cleanIntroSummary(samplePlaceholder))
    }

    @Test
    fun `样本三_LoRA的tag段清洗后为空`() {
        assertEquals("", ModelIntroPresentation.cleanIntroSummary(sampleLora))
    }

    /**
     * 清洗不是「一律清空」：夹在模板段之外的真实描述必须保留
     * （否则摘要的价值就全丢了，只剩元信息头）。
     */
    @Test
    fun `模板段之外的真实描述被保留`() {
        val raw = "「X」是 SD 1.5 底模的大模型，作者 A（版本 v1）。适合赛博朋克夜景。"
        assertEquals("适合赛博朋克夜景", ModelIntroPresentation.cleanIntroSummary(raw))
    }

    /**
     * 真实数据里还有一条模板句变体：没有「底模的」，直接「「X」是大模型。」（17 条）。
     * 剥掉之后剩下的才是真实描述，必须保留。
     */
    @Test
    fun `无底模的模板句变体也剥掉`() {
        assertEquals(
            "",
            ModelIntroPresentation.cleanIntroSummary("「触发词：（nsfw）lora.safetensors」是LoRA。")
        )
        assertEquals(
            "YesMix 是作者 zakp 制作的混合大模型",
            ModelIntroPresentation.cleanIntroSummary(
                "「CheckpointYesmix_v20.safetensors」是大模型。 YesMix 是作者 zakp 制作的混合大模型"
            ),
        )
    }

    /** 变体正则只认已知 kind 且必须紧跟标点：真实描述里的「是 LoRA …」不能被误吞。 */
    @Test
    fun `变体正则不误伤正文里的同形句子`() {
        val raw = "这个模型是 LoRA 效果很好用的那种"
        assertEquals(raw, ModelIntroPresentation.cleanIntroSummary(raw))
    }

    /** 效果行取用了清洗后的摘要时，下方不得再渲染同一段文字（否则同一句话出现两次）。 */
    @Test
    fun `效果行已取用摘要时不再单独渲染剩余简介`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            summary = "「X」是 SD 1.5 底模的大模型，作者 A（版本 v1）。适合赛博朋克夜景。",
        )
        assertNull(
            "效果行已显示这段文字，摘要块必须跳过",
            ModelIntroPresentation.summaryRestForDisplay(intro, effectFallback = null),
        )
    }

    /** effect 自身有值（与摘要不同）时，摘要剩余部分照常单独显示。 */
    @Test
    fun `effect有值时摘要剩余照常单独渲染`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            effect = "效果：皮肤更细腻",
            summary = "「X」是 SD 1.5 底模的大模型，作者 A（版本 v1）。适合赛博朋克夜景。",
        )
        assertEquals(
            "适合赛博朋克夜景",
            ModelIntroPresentation.summaryRestForDisplay(intro, effectFallback = null),
        )
    }

    @Test
    fun `摘要清洗为空时没有剩余简介`() {
        val intro = ModelIntro(file = "a", name = "A", summary = sampleCheckpoint)
        assertNull(ModelIntroPresentation.summaryRestForDisplay(intro, effectFallback = null))
    }

    // ---------------- 元信息头 ----------------

    @Test
    fun `effect优先于summary`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            effect = "效果：皮肤更细腻",
            summary = sampleCheckpoint,
        )
        assertEquals(
            listOf(
                ModelIntroPresentation.LABEL_NAME to "A",
                ModelIntroPresentation.LABEL_MODEL_EFFECT to "皮肤更细腻",
            ),
            ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null),
        )
    }

    @Test
    fun `摘要清洗后为空时效果行跳过`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            base = "SD 1.5",
            summary = sampleCheckpoint,
        )
        val header = ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
        assertEquals(
            listOf(
                ModelIntroPresentation.LABEL_NAME to "A",
                ModelIntroPresentation.LABEL_BASE to "SD 1.5",
            ),
            header,
        )
        assertFalse("清洗后为空的效果行不得出现", header.any { it.first.contains("效果") })
    }

    @Test
    fun `含占位的效果字段视同没有效果`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            name = "A",
            effect = "（暂无更多介绍信息）",
        )
        assertTrue(
            ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
                .none { it.first.contains("效果") }
        )
    }

    @Test
    fun `LoRA条目效果行叫LoRA效果`() {
        val intro = ModelIntro(
            file = "lora.safetensors",
            name = "L",
            kind = "LoRA",
            type = "LoRA（风格）",
            effect = "效果：眼睛更亮",
        )
        val header = ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
        assertTrue(header.contains(ModelIntroPresentation.LABEL_LORA_EFFECT to "眼睛更亮"))
        assertFalse(header.any { it.first == ModelIntroPresentation.LABEL_MODEL_EFFECT })
    }

    @Test
    fun `非LoRA条目效果行叫模型效果`() {
        val intro = ModelIntro(
            file = "ckpt.safetensors",
            name = "C",
            kind = "大模型",
            type = "大模型（Checkpoint）",
            effect = "效果：画面更干净",
        )
        val header = ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
        assertTrue(header.contains(ModelIntroPresentation.LABEL_MODEL_EFFECT to "画面更干净"))
        assertFalse(header.any { it.first == ModelIntroPresentation.LABEL_LORA_EFFECT })
    }

    /** 用户 SPEC 的示例顺序：名字 / 底模 / 版本 / 类型 / 效果，类型取 `type` 的完整说法。 */
    @Test
    fun `元信息头顺序固定且空值整项跳过`() {
        val intro = ModelIntro(
            file = "AWPainting_v1.2.safetensors",
            name = "AWPainting",
            base = "SD 1.5",
            version = "v1.2",
            kind = "大模型",
            type = "大模型（Checkpoint）",
            effect = "效果：动画质感更强",
            author = "DynamicWang",
        )
        assertEquals(
            listOf(
                "名字" to "AWPainting",
                "底模" to "SD 1.5",
                "版本" to "v1.2",
                "类型" to "大模型（Checkpoint）",
                "模型效果" to "动画质感更强",
            ),
            ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null),
        )
    }

    @Test
    fun `type缺失时类型退回kind`() {
        val intro = ModelIntro(file = "a", name = "A", kind = "扩散模型")
        assertTrue(
            ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
                .contains(ModelIntroPresentation.LABEL_KIND to "扩散模型")
        )
    }

    @Test
    fun `版本为null时不输出版本行`() {
        val intro = ModelIntro(file = "a", name = "A", version = null)
        val header = ModelIntroPresentation.buildMetaHeader(intro, effectFallback = null)
        assertEquals(listOf(ModelIntroPresentation.LABEL_NAME to "A"), header)
        assertFalse("不得出现版本：null", header.any { it.second == "null" })
    }

    // ---------------- 空值 / 括号混用 ----------------

    @Test
    fun `空值与null输入`() {
        assertEquals("", ModelIntroPresentation.cleanIntroSummary(""))
        assertEquals("", ModelIntroPresentation.cleanIntroSummary("   "))
        assertEquals(
            listOf(ModelIntroPresentation.LABEL_NAME to "a.safetensors"),
            ModelIntroPresentation.buildMetaHeader(ModelIntro(file = "a.safetensors"), effectFallback = null),
        )
    }

    /** 全角括号（版本）、半角括号（占位）混用：两种都必须剥掉。 */
    @Test
    fun `全角半角括号混用都能剥掉`() {
        val raw = "「Y」是 SD 1.5 底模的大模型(Checkpoint)，作者 B(版本 v2). (暂无更多介绍信息)"
        assertEquals("", ModelIntroPresentation.cleanIntroSummary(raw))
        assertEquals(
            "",
            ModelIntroPresentation.cleanIntroSummary("「Z」是 Anima 底模的LoRA，作者 C（版本 v1）。")
        )
    }
}