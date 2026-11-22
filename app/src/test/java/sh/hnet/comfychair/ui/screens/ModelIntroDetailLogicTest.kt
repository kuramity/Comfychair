package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.LoraWeightSuggestion
import sh.hnet.comfychair.model.ModelIntro

/**
 * 模型介绍详情页（ModelIntroDetailScreen）的纯逻辑测试。
 *
 * 这些函数是排版前的最后一道"数据整形"关卡：剥前缀、拆 chips、权重条的域与文案数字、
 * 复制文本的分节。在这里钉住行为，改坏格式时单测先红，而不是用户先看到乱排。
 */
class ModelIntroDetailLogicTest {

    // ---------------- sourceLabel ----------------

    @Test
    fun `sourceLabel品牌名映射`() {
        assertEquals("Civitai", sourceLabel("civitai"))
        assertEquals("HuggingFace", sourceLabel("huggingface"))
    }

    @Test
    fun `sourceLabel大小写与空白宽容`() {
        assertEquals("Civitai", sourceLabel(" CIVITAI "))
    }

    @Test
    fun `sourceLabel未知与空返回null`() {
        assertNull(sourceLabel("unknown"))
        assertNull(sourceLabel(null))
        assertNull(sourceLabel(""))
        assertNull(sourceLabel("  "))
    }

    // ---------------- stripEffectPrefix ----------------

    @Test
    fun `剥掉全角冒号的效果前缀`() {
        assertEquals("皮肤变好", stripEffectPrefix("效果：皮肤变好"))
    }

    @Test
    fun `剥掉半角冒号的效果前缀`() {
        assertEquals("皮肤变好", stripEffectPrefix("效果:皮肤变好"))
    }

    @Test
    fun `没有前缀的effect原样返回`() {
        assertEquals("皮肤变好", stripEffectPrefix("皮肤变好"))
    }

    @Test
    fun `前缀前后空白也剥掉`() {
        assertEquals("皮肤变好", stripEffectPrefix(" 效果： 皮肤变好 "))
    }

    // ---------------- splitStyleTags ----------------

    @Test
    fun `风格定位按加号拆分`() {
        assertEquals(listOf("动漫插画", "真实皮肤质感"), splitStyleTags("动漫插画 + 真实皮肤质感"))
    }

    @Test
    fun `风格定位按顿号拆分`() {
        assertEquals(listOf("动漫插画", "真实皮肤质感"), splitStyleTags("动漫插画、真实皮肤质感"))
    }

    @Test
    fun `风格定位单值不拆`() {
        assertEquals(listOf("赛博朋克"), splitStyleTags("赛博朋克"))
    }

    @Test
    fun `风格定位空值返回空表`() {
        assertTrue(splitStyleTags(null).isEmpty())
        assertTrue(splitStyleTags("").isEmpty())
        assertTrue(splitStyleTags("   ").isEmpty())
    }

    @Test
    fun `风格定位拆分后的空段被跳过`() {
        assertEquals(listOf("动漫插画", "真实皮肤质感"), splitStyleTags("动漫插画 + + 真实皮肤质感"))
    }

    // ---------------- formatWeight ----------------

    @Test
    fun `权重整数省小数`() {
        assertEquals("1", formatWeight(1.0))
        assertEquals("-3", formatWeight(-3.0))
    }

    @Test
    fun `权重小数保留一位`() {
        assertEquals("0.7", formatWeight(0.7))
        assertEquals("0.8", formatWeight(0.8))
    }

    @Test
    fun `权重超一位小数原样显示`() {
        // 数据里的 1.3 / 0.85 这类值不该被悄悄四舍五入（宁可照实显示，不编造精度）
        assertEquals("1.3", formatWeight(1.3))
    }

    // ---------------- loraWeightDomain ----------------

    @Test
    fun `常规权重域固定为0到1`() {
        val domain = loraWeightDomain(LoraWeightSuggestion(min = 0.7, max = 1.0))
        assertEquals(0.0, domain.start, 1e-9)
        assertEquals(1.0, domain.endInclusive, 1e-9)
    }

    @Test
    fun `负权重扩展域下界`() {
        val domain = loraWeightDomain(LoraWeightSuggestion(min = -8.0))
        assertEquals(-8.0, domain.start, 1e-9)
        assertEquals(1.0, domain.endInclusive, 1e-9)
    }

    @Test
    fun `超范围权重扩展域上界`() {
        val domain = loraWeightDomain(LoraWeightSuggestion(min = 0.3, max = 5.0))
        // 0.3 在常规域 [0,1] 内，域下界仍是 0；上界被数据扩展到 5
        assertEquals(0.0, domain.start, 1e-9)
        assertEquals(5.0, domain.endInclusive, 1e-9)
    }

    @Test
    fun `所有非空锚点都落在域内`() {
        // 关键性质：无论哪种形态，画条时锚点绝不能被画到轨道外面
        val cases = listOf(
            LoraWeightSuggestion(min = 0.5, max = 1.0, default = null),
            LoraWeightSuggestion(min = 0.6, max = 1.0, default = 0.8),
            LoraWeightSuggestion(min = 0.7, max = null, default = null),
            LoraWeightSuggestion(min = null, max = 1.2, default = null),
            LoraWeightSuggestion(min = null, max = null, default = 0.8),
            LoraWeightSuggestion(min = -3.0, max = 5.0, default = null),
            LoraWeightSuggestion(min = 2.0, max = null, default = null),
        )
        for (lw in cases) {
            val domain = loraWeightDomain(lw)
            lw.min?.let { assertTrue("$lw 的 min 应在域内", it in domain) }
            lw.max?.let { assertTrue("$lw 的 max 应在域内", it in domain) }
            lw.default?.let { assertTrue("$lw 的 default 应在域内", it in domain) }
        }
    }

    // ---------------- communityKeyLabel ----------------

    @Test
    fun `社区参数键的中文标签`() {
        assertEquals("采样器", communityKeyLabel("sampler"))
        assertEquals("CFG", communityKeyLabel("cfg"))
        assertEquals("步数", communityKeyLabel("steps"))
        assertEquals("调度器", communityKeyLabel("scheduler"))
    }

    @Test
    fun `社区参数未知键原样显示`() {
        assertEquals("denoise", communityKeyLabel("denoise"))
    }

    // ---------------- buildParamsCopyText ----------------

    @Test
    fun `复制文本只含作者参数时无分节标题`() {
        val intro = ModelIntro(file = "a.safetensors", authorParams = linkedMapOf("步数" to "30", "CFG" to "6"))
        val text = buildParamsCopyText(intro, "作者推荐", "你的工作流")!!
        assertEquals("作者推荐\n步数：30\nCFG：6", text)
    }

    @Test
    fun `复制文本只含工作流参数时无分节标题`() {
        val intro = ModelIntro(file = "a.safetensors", params = linkedMapOf("步数" to "28"))
        val text = buildParamsCopyText(intro, "作者推荐", "你的工作流")!!
        assertEquals("你的工作流\n步数：28", text)
    }

    @Test
    fun `复制文本两块并列分节`() {
        val intro = ModelIntro(
            file = "a.safetensors",
            authorParams = linkedMapOf("CFG" to "6"),
            params = linkedMapOf("步数" to "28"),
        )
        val text = buildParamsCopyText(intro, "作者推荐", "你的工作流")!!
        assertEquals("作者推荐\nCFG：6\n\n你的工作流\n步数：28", text)
    }

    @Test
    fun `复制文本缺介绍条目不复制工作流参数`() {
        // missing 条目的 params 是幽灵推荐（数据侧本应剥掉，防御一层：界面显示层也拦）
        val intro = ModelIntro(
            file = "a.safetensors",
            missing = true,
            params = linkedMapOf("步数" to "28"),
        )
        assertNull(buildParamsCopyText(intro, "作者推荐", "你的工作流"))
    }

    @Test
    fun `复制文本全空返回null`() {
        val intro = ModelIntro(file = "a.safetensors")
        assertNull(buildParamsCopyText(intro, "作者推荐", "你的工作流"))
    }
}
