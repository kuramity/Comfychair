package sh.hnet.comfychair.viewmodel.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode

/**
 * [GenerationParams] 纯校验函数的 JVM 单测（任务书 6.1 第一步）。
 *
 * 不依赖 Android 设备：所有 `ctx` 参数传 null，错误消息落回
 * [sh.hnet.comfychair.util.ValidationUtils] 内置的英文兜底文案 ——
 * 与现有 `FloatFiniteValidationTest` 等 util 测试对 `validateXxx(value, null)`
 * 的调用惯例一致。断言英文兜底文案的内容同时钉住了「错误确实是这条校验产生」，
 * 避免 null 消息与合法值混在一起分不清。
 *
 * 范围阈值断言只用默认值与显式传入值两种，保证迁移前后校验规则零漂移。
 */
class GenerationParamsTest {

    // ── width / height：整数 1..4096 ─────────────────────────────

    @Test
    fun `宽度合法值不报错`() {
        val p = GenerationParams().withWidth("1024")
        assertEquals("1024", p.width)
        assertNull(p.widthError)
    }

    @Test
    fun `宽度上下边界合法`() {
        assertNull(GenerationParams().withWidth("1").widthError)
        assertNull(GenerationParams().withWidth("4096").widthError)
    }

    @Test
    fun `宽度下界外报错`() {
        val p = GenerationParams().withWidth("0")
        assertEquals("Must be 1-4096", p.widthError)
    }

    @Test
    fun `宽度上界外报错`() {
        assertEquals("Must be 1-4096", GenerationParams().withWidth("4097").widthError)
    }

    @Test
    fun `宽度空串视为未输入不报错`() {
        assertNull(GenerationParams().withWidth("").widthError)
    }

    @Test
    fun `宽度非数字报错`() {
        assertEquals("Must be 1-4096", GenerationParams().withWidth("abc").widthError)
    }

    @Test
    fun `高度合法值不报错且字段更新`() {
        val p = GenerationParams().withHeight("768")
        assertEquals("768", p.height)
        assertNull(p.heightError)
    }

    @Test
    fun `高度上下界外出错`() {
        assertEquals("Must be 1-4096", GenerationParams().withHeight("0").heightError)
        assertEquals("Must be 1-4096", GenerationParams().withHeight("5000").heightError)
    }

    @Test
    fun `高度空串合法非数字报错`() {
        assertNull(GenerationParams().withHeight("").heightError)
        assertEquals("Must be 1-4096", GenerationParams().withHeight("12x").heightError)
    }

    // ── steps：整数 1..255 ───────────────────────────────────────

    @Test
    fun `步数合法值不报错`() {
        val p = GenerationParams().withSteps("20")
        assertEquals("20", p.steps)
        assertNull(p.stepsError)
    }

    @Test
    fun `步数上下边界合法`() {
        assertNull(GenerationParams().withSteps("1").stepsError)
        assertNull(GenerationParams().withSteps("255").stepsError)
    }

    @Test
    fun `步数下界外报错`() {
        assertEquals("Must be 1-255", GenerationParams().withSteps("0").stepsError)
    }

    @Test
    fun `步数上界外报错`() {
        assertEquals("Must be 1-255", GenerationParams().withSteps("256").stepsError)
    }

    @Test
    fun `步数空串合法非数字报错`() {
        assertNull(GenerationParams().withSteps("").stepsError)
        assertEquals("Must be 1-255", GenerationParams().withSteps("ten").stepsError)
    }

    @Test
    fun `步数自定义阈值生效不漂移`() {
        // 任务书要求：范围不一致的字段纯函数要能接受 min/max 参数。
        // 显式传 (1, 50) 时 100 越界；默认阈值时 100 合法 —— 阈值由调用方决定。
        assertEquals("Must be 1-50", GenerationParams().withSteps("100", null, 1, 50).stepsError)
        assertNull(GenerationParams().withSteps("100").stepsError)
    }

    // ── cfg：浮点 0.0..100.0 ─────────────────────────────────────

    @Test
    fun `CFG合法值不报错`() {
        val p = GenerationParams().withCfg("7.0")
        assertEquals("7.0", p.cfg)
        assertNull(p.cfgError)
    }

    @Test
    fun `CFG上下边界合法`() {
        assertNull(GenerationParams().withCfg("0.0").cfgError)
        assertNull(GenerationParams().withCfg("100.0").cfgError)
    }

    @Test
    fun `CFG下界外报错`() {
        assertEquals("Must be 0.0-100.0", GenerationParams().withCfg("-0.1").cfgError)
    }

    @Test
    fun `CFG上界外报错`() {
        assertEquals("Must be 0.0-100.0", GenerationParams().withCfg("100.5").cfgError)
    }

    @Test
    fun `CFG空串合法非法格式报错`() {
        assertNull(GenerationParams().withCfg("").cfgError)
        assertEquals("Must be 0.0-100.0", GenerationParams().withCfg("seven").cfgError)
    }

    @Test
    fun `CFG自定义阈值生效`() {
        assertEquals("Must be 0.0-2.0", GenerationParams().withCfg("3.0", null, 0.0f, 2.0f).cfgError)
    }

    // ── seed：整数 ≥ 0（只查下限）───────────────────────────────

    @Test
    fun `种子零与正数合法`() {
        assertNull(GenerationParams().withSeed("0").seedError)
        assertNull(GenerationParams().withSeed("4294967295").seedError)
    }

    @Test
    fun `种子负数报错`() {
        assertEquals("Must be 0 or greater", GenerationParams().withSeed("-1").seedError)
    }

    @Test
    fun `种子空串合法非数字报错`() {
        assertNull(GenerationParams().withSeed("").seedError)
        assertEquals("Must be 0 or greater", GenerationParams().withSeed("0x10").seedError)
    }

    @Test
    fun `种子字段与错误同时更新`() {
        val p = GenerationParams(seed = "5", seedError = "old").withSeed("-3")
        assertEquals("-3", p.seed)
        assertEquals("Must be 0 or greater", p.seedError)
    }

    // ── denoise：浮点 0.0..1.0 ───────────────────────────────────

    @Test
    fun `去噪合法值不报错`() {
        val p = GenerationParams().withDenoise("0.75")
        assertEquals("0.75", p.denoise)
        assertNull(p.denoiseError)
    }

    @Test
    fun `去噪上下边界合法`() {
        assertNull(GenerationParams().withDenoise("0.0").denoiseError)
        assertNull(GenerationParams().withDenoise("1.0").denoiseError)
    }

    @Test
    fun `去噪下界外报错`() {
        assertEquals("Must be 0.0-1.0", GenerationParams().withDenoise("-0.01").denoiseError)
    }

    @Test
    fun `去噪上界外报错`() {
        assertEquals("Must be 0.0-1.0", GenerationParams().withDenoise("1.01").denoiseError)
    }

    @Test
    fun `去噪空串合法非法格式报错`() {
        assertNull(GenerationParams().withDenoise("").denoiseError)
        assertEquals("Must be 0.0-1.0", GenerationParams().withDenoise("half").denoiseError)
    }

    // ── batchSize：整数 1..64 ────────────────────────────────────

    @Test
    fun `批量合法值不报错`() {
        val p = GenerationParams().withBatchSize("4")
        assertEquals("4", p.batchSize)
        assertNull(p.batchSizeError)
    }

    @Test
    fun `批量上下边界合法`() {
        assertNull(GenerationParams().withBatchSize("1").batchSizeError)
        assertNull(GenerationParams().withBatchSize("64").batchSizeError)
    }

    @Test
    fun `批量下界外报错`() {
        assertEquals("Must be 1-64", GenerationParams().withBatchSize("0").batchSizeError)
    }

    @Test
    fun `批量上界外报错`() {
        assertEquals("Must be 1-64", GenerationParams().withBatchSize("65").batchSizeError)
    }

    @Test
    fun `批量空串合法非法格式报错`() {
        assertNull(GenerationParams().withBatchSize("").batchSizeError)
        assertEquals("Must be 1-64", GenerationParams().withBatchSize("2.5").batchSizeError)
    }

    // ── 无校验字段：直接赋值 ─────────────────────────────────────

    @Test
    fun `提示词直接赋值`() {
        val p = GenerationParams()
            .withPositivePrompt("masterpiece")
            .withNegativePrompt("lowres")
        assertEquals("masterpiece", p.positivePrompt)
        assertEquals("lowres", p.negativePrompt)
    }

    @Test
    fun `模型选择直接赋值`() {
        val p = GenerationParams()
            .withCheckpoint("sdxl.safetensors")
            .withUnet("flux.safetensors")
            .withVae("vae.safetensors")
            .withSampler("euler")
            .withScheduler("normal")
        assertEquals("sdxl.safetensors", p.selectedCheckpoint)
        assertEquals("flux.safetensors", p.selectedUnet)
        assertEquals("vae.safetensors", p.selectedVae)
        assertEquals("euler", p.sampler)
        assertEquals("normal", p.scheduler)
    }

    @Test
    fun `五个CLIP槽独立赋值`() {
        val p = GenerationParams()
            .withClip("clip_a.gguf")
            .withClip1("clip_b.safetensors")
            .withClip2("clip_c.safetensors")
            .withClip3("clip_d.safetensors")
            .withClip4("clip_e.safetensors")
        assertEquals("clip_a.gguf", p.selectedClip)
        assertEquals("clip_b.safetensors", p.selectedClip1)
        assertEquals("clip_c.safetensors", p.selectedClip2)
        assertEquals("clip_d.safetensors", p.selectedClip3)
        assertEquals("clip_e.safetensors", p.selectedClip4)
    }

    @Test
    fun `种子模式赋值`() {
        assertEquals(SeedMode.FIXED, GenerationParams().withSeedMode(SeedMode.FIXED).seedMode)
        assertEquals(SeedMode.INCREMENT, GenerationParams(seedMode = SeedMode.FIXED).withSeedMode(SeedMode.INCREMENT).seedMode)
    }

    // ── LoRA 链：委托 LoraChainManager ───────────────────────────

    @Test
    fun `添加LoRA到空链`() {
        val p = GenerationParams().addLora(listOf("a.safetensors"))
        assertEquals(1, p.loraChain.size)
        // 新项 name 是空串（「不使用 LoRA」），强度为默认值
        assertEquals("", p.loraChain[0].name)
        assertEquals(LoraSelection.DEFAULT_STRENGTH, p.loraChain[0].strength)
    }

    @Test
    fun `无候选时添加LoRA返回原对象`() {
        val original = GenerationParams()
        assertSame(original, original.addLora(emptyList()))
    }

    @Test
    fun `链满时添加LoRA返回原对象`() {
        val full = listOf(
            LoraSelection("a", 1f), LoraSelection("b", 1f), LoraSelection("c", 1f),
            LoraSelection("d", 1f), LoraSelection("e", 1f)
        )
        val p = GenerationParams(loraChain = full)
        assertSame(p, p.addLora(listOf("f.safetensors")))
    }

    @Test
    fun `移除LoRA并更新名字与强度`() {
        val withLora = GenerationParams().addLora(listOf("a.safetensors"))
        val named = withLora.withLoraName(0, "real_lora")
        assertEquals("real_lora", named.loraChain[0].name)

        val strengthened = named.withLoraStrength(0, 0.5f)
        assertEquals(0.5f, strengthened.loraChain[0].strength)

        val removed = strengthened.removeLora(0)
        assertTrue(removed.loraChain.isEmpty())
    }

    @Test
    fun `LoRA名字归一化None落成空串`() {
        val withLora = GenerationParams().addLora(listOf("a.safetensors"))
        val named = withLora.withLoraName(0, "None")
        assertEquals("", named.loraChain[0].name)
    }

    @Test
    fun `LoRA索引无效时返回原对象`() {
        val original = GenerationParams()
        assertSame(original, original.removeLora(0))
        assertSame(original, original.withLoraName(0, "x"))
        assertSame(original, original.withLoraStrength(0, 2f))
    }

    @Test
    fun `LoRA强度clamp到合法区间`() {
        val withLora = GenerationParams().addLora(listOf("a.safetensors"))
        val clamped = withLora.withLoraStrength(0, 99f)
        assertEquals(LoraSelection.MAX_STRENGTH, clamped.loraChain[0].strength)
    }

    @Test
    fun `整条替换LoRA链`() {
        val chain = listOf(LoraSelection("x", 0.8f))
        val p = GenerationParams(loraChain = listOf(LoraSelection("old", 1f)))
            .withLoraChain(chain)
        assertEquals(chain, p.loraChain)
    }

    @Test
    fun `按可用候选过滤LoRA链`() {
        val p = GenerationParams(loraChain = listOf(LoraSelection("keep", 1f), LoraSelection("gone", 1f)))
        val filtered = p.filterLoraChain(listOf("keep"))
        assertEquals(listOf(LoraSelection("keep", 1f)), filtered.loraChain)
    }

    // ── 动态节点参数 ─────────────────────────────────────────────

    @Test
    fun `动态参数值按占位符更新`() {
        val p = GenerationParams()
            .withDynamicParamValue("clip_gguf", "true")
            .withDynamicParamValue("steps_max", "40")
        assertEquals(mapOf("clip_gguf" to "true", "steps_max" to "40"), p.dynamicParamValues)
    }

    @Test
    fun `同名占位符覆盖旧值`() {
        val p = GenerationParams()
            .withDynamicParamValue("k", "v1")
            .withDynamicParamValue("k", "v2")
        assertEquals("v2", p.dynamicParamValues["k"])
    }

    @Test
    fun `控件清单整份替换`() {
        // DynamicParam 是纯数据类，这里用 emptyList 之外的语义验证：替换后引用内容一致
        val params = emptyList<sh.hnet.comfychair.workflow.dynamic.DynamicParam>()
        val p = GenerationParams().withDynamicParams(params)
        assertEquals(params, p.dynamicParams)
    }

    // ── 纯函数性 / copy 语义 ─────────────────────────────────────

    @Test
    fun `校验函数不修改原对象`() {
        val original = GenerationParams()
        original.withWidth("0")
        assertEquals("1024", original.width)
        assertNull(original.widthError)
    }

    @Test
    fun `带错误的对象校验通过后错误被清掉`() {
        val p = GenerationParams(widthError = "Must be 1-4096").withWidth("512")
        assertNull(p.widthError)
    }

    @Test
    fun `不同字段校验互不覆盖`() {
        val p = GenerationParams()
            .withWidth("0")     // widthError 报错
            .withSteps("300")   // stepsError 报错
        assertEquals("Must be 1-4096", p.widthError)
        assertEquals("Must be 1-255", p.stepsError)
    }

    @Test
    fun `数据类相等性按字段比较`() {
        val a = GenerationParams().withSteps("20")
        val b = GenerationParams().withSteps("20")
        assertEquals(a, b)
        assertNotEquals(a, a.withSteps("21"))
    }
}
