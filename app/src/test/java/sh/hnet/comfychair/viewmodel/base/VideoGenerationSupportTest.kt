package sh.hnet.comfychair.viewmodel.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.viewmodel.TextToVideoUiState
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.workflow.dynamic.DynamicParamControl

/**
 * 视频页共享计算（[VideoGenerationSupport]）的行为钉子。
 *
 * 这些函数是从 `TextToVideoViewModel` / `ImageToVideoViewModel` 里逐字节相同的私有实现
 * 抽出来的，抽取必须保证：
 * 1. `buildWorkflowValues` 的每个字段映射与 `takeIf { isNotEmpty() }` 语义不变
 *    （空串 → null，positivePrompt 等不属于按工作流持久化的字段保持不写）；
 * 2. `refreshDynamicParams` 的三条比较 + 两处 null 短路不变；
 * 3. `advanceSeed` 只在递增/递减下动、步长方向正确。
 *
 * 测试用 `TextToVideoUiState` 真实类型构造输入（它就是生产实现之一），避免造一个
 * 与生产形态漂移的假对象。
 */
class VideoGenerationSupportTest {

    private fun lora(name: String, strength: Float = 0.5f) = LoraSelection(name = name, strength = strength)

    /**
     * 全字段有值的状态。注意 `positivePrompt` 刻意不设——它不是按工作流持久化的字段。
     */
    private fun fullState() = TextToVideoUiState(
        width = "832",
        height = "480",
        length = "33",
        fps = "16",
        steps = "20",
        loraStrength = 0.75f,
        megapixels = "1.0",
        aspectRatio = "16:9",
        speedMode = "fast",
        seed = "42",
        seedMode = SeedMode.INCREMENT,
        negativePrompt = "bad things",
        selectedCheckpoint = "ckpt",
        selectedUnet = "unet",
        selectedLoraName = "lora",
        selectedHighnoiseUnet = "hUnet",
        selectedLownoiseUnet = "lUnet",
        selectedHighnoiseLora = "hLora",
        selectedLownoiseLora = "lLora",
        selectedVae = "vae",
        selectedClip = "clip0",
        selectedClip1 = "clip1",
        selectedClip2 = "clip2",
        selectedClip3 = "clip3",
        selectedClip4 = "clip4",
        selectedTextEncoder = "textEncoder",
        selectedLatentUpscaleModel = "latentModel",
        highnoiseLoraChain = listOf(lora("high")),
        lownoiseLoraChain = emptyList(),
        dynamicParamValues = mapOf("placeholder" to "userValue")
    )

    // ============================================================
    // buildWorkflowValues
    // ============================================================

    @Test
    fun `buildWorkflowValues 按原顺序映射全部视频字段`() {
        val values = VideoGenerationSupport.buildWorkflowValues(
            fullState(),
            WorkflowValues(nodeAttributeEdits = "{\"12\":{}}")
        )

        assertEquals(832, values.width)
        assertEquals(480, values.height)
        assertEquals(33, values.length)
        assertEquals(16, values.frameRate)
        assertEquals(20, values.steps)
        assertEquals(0.75f, values.loraStrength)
        assertEquals(1.0f, values.megapixels)
        assertEquals("16:9", values.aspectRatio)
        assertEquals("fast", values.speedMode)
        assertEquals(42L, values.seed)
        // seedMode=INCREMENT → 提交时不随机（randomSeed 是派生值）
        assertEquals(false, values.randomSeed)
        assertEquals("INCREMENT", values.seedMode)
        assertEquals("bad things", values.negativePrompt)
        assertEquals("ckpt", values.model)
        assertEquals("unet", values.unetModel)
        assertEquals("lora", values.loraModel)
        assertEquals("hUnet", values.highnoiseUnetModel)
        assertEquals("lUnet", values.lownoiseUnetModel)
        assertEquals("hLora", values.highnoiseLoraModel)
        assertEquals("lLora", values.lownoiseLoraModel)
        assertEquals("vae", values.vaeModel)
        assertEquals("clip0", values.clipModel)
        assertEquals("clip1", values.clip1Model)
        assertEquals("clip2", values.clip2Model)
        assertEquals("clip3", values.clip3Model)
        assertEquals("clip4", values.clip4Model)
        assertEquals("textEncoder", values.textEncoderModel)
        assertEquals("latentModel", values.latentUpscaleModel)
        // 非空链才序列化；空链保持 null
        assertNotNull("非空高噪链必须序列化", values.highnoiseLoraChain)
        assertNull("空低噪链必须是 null", values.lownoiseLoraChain)
        // Workflow Editor 写入的节点属性编辑必须原样保留
        assertEquals("{\"12\":{}}", values.nodeAttributeEdits)
        assertEquals(mapOf("placeholder" to "userValue"), values.dynamicParams)
        // 正面词是全局偏好，不随工作流值持久化
        assertNull("positivePrompt 不写进按工作流持久化的值", values.positivePrompt)
    }

    @Test
    fun `buildWorkflowValues 把空串映射为 null`() {
        val empty = TextToVideoUiState(
            width = "",
            height = "",
            length = "",
            fps = "",
            steps = "",
            megapixels = "",
            aspectRatio = "",
            speedMode = "",
            seed = "",
            negativePrompt = "",
            selectedCheckpoint = "",
            selectedUnet = "",
            selectedLoraName = "",
            selectedHighnoiseUnet = "",
            selectedLownoiseUnet = "",
            selectedHighnoiseLora = "",
            selectedLownoiseLora = "",
            selectedVae = "",
            selectedClip = "",
            selectedClip1 = "",
            selectedClip2 = "",
            selectedClip3 = "",
            selectedClip4 = "",
            selectedTextEncoder = "",
            selectedLatentUpscaleModel = ""
        )

        val values = VideoGenerationSupport.buildWorkflowValues(empty, null)

        assertNull(values.width)
        assertNull(values.height)
        assertNull(values.length)
        assertNull(values.frameRate)
        assertNull(values.steps)
        assertNull(values.megapixels)
        assertNull(values.aspectRatio)
        assertNull(values.speedMode)
        assertNull(values.seed)
        assertNull(values.negativePrompt)
        assertNull(values.model)
        assertNull(values.unetModel)
        assertNull(values.loraModel)
        assertNull(values.highnoiseUnetModel)
        assertNull(values.lownoiseUnetModel)
        assertNull(values.highnoiseLoraModel)
        assertNull(values.lownoiseLoraModel)
        assertNull(values.vaeModel)
        assertNull(values.clipModel)
        assertNull(values.clip1Model)
        assertNull(values.clip2Model)
        assertNull(values.clip3Model)
        assertNull(values.clip4Model)
        assertNull(values.textEncoderModel)
        assertNull(values.latentUpscaleModel)
        assertNull(values.highnoiseLoraChain)
        assertNull(values.lownoiseLoraChain)
        assertNull("没有旧值时必须写 null（而不是空串）", values.nodeAttributeEdits)
        assertTrue(values.dynamicParams.isEmpty())
    }

    // ============================================================
    // refreshDynamicParams
    // ============================================================

    @Test
    fun `refreshDynamicParams 未选工作流时必须提前短路`() {
        // 值表与快照刻意不同：若没有 `selectedWorkflowId.isEmpty()` 的提前返回，
        // 这次调用会产出一个清空用户值的更新（非 null），该断言即变红。
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        val state = TextToVideoUiState(
            selectedWorkflowId = "",
            dynamicParams = emptyList(),
            dynamicParamValues = mapOf("placeholder" to "用户填的值"),
            dynamicParamsRegistryReady = ready
        )
        assertNull(VideoGenerationSupport.refreshDynamicParams(state))
    }

    @Test
    fun `refreshDynamicParams 三项比较全部相同时返回 null`() {
        // 未登记的工作流：快照必定为空清单 + 空值表，因此让状态与它一致即可命中「无变化」分支
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        val state = TextToVideoUiState(
            selectedWorkflowId = "不存在的视频工作流",
            dynamicParams = emptyList(),
            dynamicParamValues = emptyMap(),
            dynamicParamsRegistryReady = ready
        )
        assertNull(VideoGenerationSupport.refreshDynamicParams(state))
    }

    @Test
    fun `refreshDynamicParams 注册表就绪状态变化时产出新快照`() {
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        val state = TextToVideoUiState(
            selectedWorkflowId = "不存在的视频工作流",
            dynamicParams = emptyList(),
            dynamicParamValues = emptyMap(),
            dynamicParamsRegistryReady = !ready
        )

        val update = VideoGenerationSupport.refreshDynamicParams(state)

        assertNotNull("就绪状态不同必须产出更新", update)
        assertEquals(ready, update!!.registryReady)
        assertTrue(update.params.isEmpty())
        assertTrue(update.values.isEmpty())
    }

    @Test
    fun `refreshDynamicParams 已存值有差异时产出新快照`() {
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        val state = TextToVideoUiState(
            selectedWorkflowId = "不存在的视频工作流",
            dynamicParams = emptyList(),
            dynamicParamValues = mapOf("placeholder" to "用户填的值"),
            dynamicParamsRegistryReady = ready
        )

        val update = VideoGenerationSupport.refreshDynamicParams(state)

        assertNotNull("值表不同必须产出更新", update)
        // 未登记的工作流没有动态参数 → 快照把用户值清空（与抽取前的实现一致）
        assertTrue(update!!.values.isEmpty())
    }

    @Test
    fun `refreshDynamicParams 控件清单有差异时产出新快照`() {
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        val staleParam = DynamicParam(
            placeholder = "placeholder",
            nodeId = "12",
            classType = "KSampler",
            inputName = "steps",
            control = DynamicParamControl.TextField(multiline = false, default = null)
        )
        val state = TextToVideoUiState(
            selectedWorkflowId = "不存在的视频工作流",
            dynamicParams = listOf(staleParam),
            dynamicParamValues = emptyMap(),
            dynamicParamsRegistryReady = ready
        )

        val update = VideoGenerationSupport.refreshDynamicParams(state)

        assertNotNull("控件清单不同必须产出更新", update)
        // 未登记的工作流没有动态参数 → 快照清空旧清单
        assertTrue(update!!.params.isEmpty())
    }

    // ============================================================
    // advanceSeed
    // ============================================================

    @Test
    fun `advanceSeed 固定与随机模式都不动种子`() {
        assertNull(VideoGenerationSupport.advanceSeed(TextToVideoUiState(seedMode = SeedMode.FIXED, seed = "5")))
        assertNull(VideoGenerationSupport.advanceSeed(TextToVideoUiState(seedMode = SeedMode.RANDOM, seed = "5")))
    }

    @Test
    fun `advanceSeed 递增模式加一`() {
        val state = TextToVideoUiState(
            seedMode = SeedMode.INCREMENT,
            selectedWorkflow = "不存在的视频工作流",
            seed = "5"
        )
        assertEquals("6", VideoGenerationSupport.advanceSeed(state))
    }

    @Test
    fun `advanceSeed 递减模式减一`() {
        val state = TextToVideoUiState(
            seedMode = SeedMode.DECREMENT,
            selectedWorkflow = "不存在的视频工作流",
            seed = "5"
        )
        assertEquals("4", VideoGenerationSupport.advanceSeed(state))
    }

    @Test
    fun `advanceSeed 递减到 0 时停在下限`() {
        val state = TextToVideoUiState(
            seedMode = SeedMode.DECREMENT,
            selectedWorkflow = "不存在的视频工作流",
            seed = "0"
        )
        assertEquals("0", VideoGenerationSupport.advanceSeed(state))
    }

    @Test
    fun `advanceSeed 非法种子按 0 处理`() {
        val state = TextToVideoUiState(
            seedMode = SeedMode.INCREMENT,
            selectedWorkflow = "不存在的视频工作流",
            seed = "abc"
        )
        assertEquals("1", VideoGenerationSupport.advanceSeed(state))
    }

    @Test
    fun `advanceSeed 是纯函数 重复调用结果一致`() {
        val state = TextToVideoUiState(
            seedMode = SeedMode.INCREMENT,
            selectedWorkflow = "不存在的视频工作流",
            seed = "9"
        )
        assertEquals(VideoGenerationSupport.advanceSeed(state), VideoGenerationSupport.advanceSeed(state))
        // 输入没有被改动（纯计算，不写状态）
        assertEquals("9", state.seed)
    }
}