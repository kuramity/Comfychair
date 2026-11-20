package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.lora.LoraInjectionUtils
import sh.hnet.comfychair.model.LoraSelection

/**
 * 提交前的 LoRA 注入必须跳过"未选择"的项。
 *
 * 这是 400 的**最后一道防线**（commit 428a392）：
 * ```
 * Value not in list: lora_name: 'None' not in (list of length 66)
 * ```
 * 只要空名 / 历史遗留的 `"None"` 还能走到 [LoraInjectionUtils.createLoraNode]，
 * 服务器就必然报这个错。这里钉住：这类项一律不建节点、不改接线。
 */
class LoraInjectionUtilsTest {

    private val workflow = """
    {
      "nodes": {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "{{ckpt_name}}"}},
        "2": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "seed": 1}}
      }
    }
    """.trimIndent()

    @Test
    fun `整条链都是未选择时工作流原样返回`() {
        val chain = listOf(LoraSelection("None"), LoraSelection(""), LoraSelection("  none  ", 0.5f))

        val out = LoraInjectionUtils.injectLoraChain(workflow, chain, WorkflowType.TTI)

        assertEquals("没有任何真实 LoRA → 一个节点都不该建", workflow, out)
        assertFalse("不能出现 lora_name 为 None/空 的节点", out.contains("LoraLoaderModelOnly"))
    }

    @Test
    fun `混合链只注入真实项 未选择项被跳过`() {
        val chain = listOf(
            LoraSelection("None"),                       // 旧存档里的"不使用"
            LoraSelection(""),                           // 下拉里的「（不使用 LoRA）」
            LoraSelection("lora_a.safetensors", 0.7f)    // 用户真正挑的
        )

        val out = LoraInjectionUtils.injectLoraChain(workflow, chain, WorkflowType.TTI)
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertEquals("只该多出一个 LoRA 节点", 3, nodes.length())
        val injected = nodes.getJSONObject("100")
        assertEquals("LoraLoaderModelOnly", injected.getString("class_type"))
        val inputs = injected.getJSONObject("inputs")
        assertEquals("lora_a.safetensors", inputs.getString("lora_name"))
        assertEquals("权重按用户设定透传", 0.7, inputs.getDouble("strength_model"), 1e-6)

        // 原消费者（KSampler）改接到注入节点上
        val samplerModel = nodes.getJSONObject("2").getJSONObject("inputs").getJSONArray("model")
        assertEquals("100", samplerModel.getString(0))
        assertEquals(0, samplerModel.getInt(1))

        assertFalse("提交体里不能再出现字面量 None", out.contains("\"None\""))
    }

    @Test
    fun `超出旧上限的权重照样提交`() {
        // 范围放宽到 0~10 后，"大权重"是用户明确要的合法值：
        // 任何残留的 0~2 夹紧都会把它悄悄改回 2，而且改了之后界面还显示 10（静默失真）
        val chain = listOf(LoraSelection("strong.safetensors", 10.0f))

        val out = LoraInjectionUtils.injectLoraChain(workflow, chain, WorkflowType.TTI)
        val inputs = JSONObject(out).getJSONObject("nodes")
            .getJSONObject("100").getJSONObject("inputs")

        assertEquals("10 必须原样提交给服务器", 10.0, inputs.getDouble("strength_model"), 1e-6)
    }

    @Test
    fun `越界与 NaN 权重在提交前被夹紧`() {
        // 存档 / 手工写入的脏数据不能让整单 400：NaN 序列化出来是非法 JSON 字面量
        val cases = listOf(
            15.0f to 10.0f,
            -3.0f to 0.0f,
            Float.NaN to LoraSelection.DEFAULT_STRENGTH
        )
        cases.forEach { (input, expected) ->
            val chain = listOf(LoraSelection("x.safetensors", input))
            val out = LoraInjectionUtils.injectLoraChain(workflow, chain, WorkflowType.TTI)
            val inputs = JSONObject(out).getJSONObject("nodes")
                .getJSONObject("100").getJSONObject("inputs")

            assertEquals(
                "权重 $input 提交前应被夹到 $expected",
                expected.toDouble(),
                inputs.getDouble("strength_model"),
                1e-6
            )
        }
    }

    @Test
    fun `视频链的附加 LoRA 同样跳过未选择项`() {
        val chain = listOf(LoraSelection("None"), LoraSelection("   "))

        val highNoise = LoraInjectionUtils.injectAdditionalVideoLoras(workflow, chain, true)
        val lowNoise = LoraInjectionUtils.injectAdditionalVideoLoras(workflow, chain, false)

        assertEquals("高噪链：没有真实 LoRA 时原样返回", workflow, highNoise)
        assertEquals("低噪链：没有真实 LoRA 时原样返回", workflow, lowNoise)
        assertFalse(highNoise.contains("LoraLoaderModelOnly"))
    }

    /** 视频流的双链结构：UNETLoader → 强制 LoRA → ModelSamplingSD3 → KSamplerAdvanced */
    private val videoWorkflow = """
    {
      "nodes": {
        "10": {"class_type": "UNETLoader", "inputs": {"unet_name": "{{highnoise_unet_name}}"}},
        "11": {"class_type": "LoraLoaderModelOnly", "inputs": {
            "lora_name": "{{highnoise_lora_name}}", "strength_model": 1.0, "model": ["10", 0]}},
        "12": {"class_type": "ModelSamplingSD3", "inputs": {"model": ["11", 0], "shift": 3.0}},
        "13": {"class_type": "KSamplerAdvanced", "inputs": {"model": ["12", 0], "start_at_step": 0}}
      }
    }
    """.trimIndent()

    @Test
    fun `视频附加链只注入真实项 未选择项被跳过`() {
        val chain = listOf(LoraSelection("None"), LoraSelection("extra.safetensors", 0.8f))

        val out = LoraInjectionUtils.injectAdditionalVideoLoras(videoWorkflow, chain, isHighNoise = true)
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertEquals("只该多出一个附加 LoRA 节点（None 不能建节点）", 5, nodes.length())
        val injected = nodes.getJSONObject("100")
        assertEquals("extra.safetensors", injected.getJSONObject("inputs").getString("lora_name"))
        assertEquals("100", nodes.getJSONObject("12").getJSONObject("inputs").getJSONArray("model").getString(0))
        assertFalse("提交体里不能再出现字面量 None", out.contains("\"None\""))
    }
}
