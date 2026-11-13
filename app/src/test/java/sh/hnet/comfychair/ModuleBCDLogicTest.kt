package sh.hnet.comfychair

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.components.ParticleSystem
import sh.hnet.comfychair.workflow.SaveFormatMaterializer
import sh.hnet.comfychair.workflow.WorkflowPreflight

/**
 * v4.6.1 模块 B/C/D 的纯逻辑单测。
 *
 * 覆盖点：
 * 1. [SaveFormatMaterializer]：WebP 开启时把 SaveImage 换成 BatchImageSaverCN（参数正确）、
 *    关闭时原样返回、前缀清洗；
 * 2. [WorkflowPreflight]：占位符残留 / 模型字段为空 / 正常通过 / 无 nodes 的格式；
 * 3. [ParticleSystem]：对象池上限、单次数量受控、生命周期归零。
 */
class ModuleBCDLogicTest {

    // ---------- 模块 C：SaveFormatMaterializer ----------

    private val saveImageJson = """
        {"nodes":{
          "1":{"class_type":"SaveImage","inputs":{"images":["9",0],"filename_prefix":"%date:yyyy-MM-dd%"}},
          "9":{"class_type":"VAEDecode","inputs":{}}
        }}
    """.trimIndent()

    @Test
    fun `webp on replaces SaveImage with BatchImageSaverCN`() {
        val out = SaveFormatMaterializer.materialize(saveImageJson, saveAsWebp = true)
        val nodes = JSONObject(out).getJSONObject("nodes")
        val node = nodes.getJSONObject("1")

        assertEquals("BatchImageSaverCN", node.getString("class_type"))
        val inputs = node.getJSONObject("inputs")
        assertEquals("WEBP", inputs.getString("输出格式"))
        assertEquals(90, inputs.getInt("质量"))
        // 原标题里的 %date% token 被剥掉后为空 → 回落默认前缀
        assertEquals("ComfyChair", inputs.getString("自定义前缀"))
        // 连线必须保留（images → image），否则保存节点没有输入
        assertEquals("9", inputs.getJSONArray("image").getString(0))
        assertEquals(0, inputs.getJSONArray("image").getInt(1))
        // 旧的 filename_prefix 不应再留着
        assertTrue(!inputs.has("filename_prefix"))
    }

    @Test
    fun `webp off keeps the original json untouched`() {
        assertEquals(saveImageJson, SaveFormatMaterializer.materialize(saveImageJson, saveAsWebp = false))
    }

    @Test
    fun `no SaveImage means no change even when enabled`() {
        val json = """{"nodes":{"1":{"class_type":"SaveVideo","inputs":{}}}}"""
        assertEquals(json, SaveFormatMaterializer.materialize(json, saveAsWebp = true))
    }

    @Test
    fun `prefix sanitizer strips formatting tokens and illegal chars`() {
        assertEquals("ComfyChair", SaveFormatMaterializer.sanitizePrefix("%date:yyyy-MM-dd%"))
        assertEquals("my_flow", SaveFormatMaterializer.sanitizePrefix("my/flow"))
        assertEquals("abc", SaveFormatMaterializer.sanitizePrefix("  abc  "))
    }

    // ---------- 模块 A4：WorkflowPreflight ----------

    @Test
    fun `leftover placeholder rejects`() {
        val json = """{"nodes":{"1":{"class_type":"VAELoader","inputs":{"vae_name":"{{vae_name}}"}}}}"""
        val problem = WorkflowPreflight.check(json)
        assertTrue(problem != null && problem!!.contains("{{vae_name}}"))
    }

    @Test
    fun `empty model field rejects with field name`() {
        val json = """{"nodes":{"1":{"class_type":"VAELoader","inputs":{"vae_name":""}}}}"""
        val problem = WorkflowPreflight.check(json)
        assertTrue(problem != null && problem!!.contains("vae_name"))
    }

    @Test
    fun `complete model fields pass`() {
        val json = """{"nodes":{"1":{"class_type":"VAELoader","inputs":{"vae_name":"ae.safetensors"}}}}"""
        assertNull(WorkflowPreflight.check(json))
    }

    @Test
    fun `json without nodes is passed to server validation`() {
        val json = """{"1":{"class_type":"VAELoader","inputs":{"vae_name":""}}}"""
        assertNull(WorkflowPreflight.check(json))
    }

    @Test
    fun `malformed json does not crash`() {
        assertNull(WorkflowPreflight.check("not json"))
    }

    // ---------- 模块 B：ParticleSystem ----------

    @Test
    fun `emit fills the pool up to max per tap`() {
        val system = ParticleSystem(capacity = 96, maxPerTap = 12)
        system.emit(100f, 100f)
        assertEquals(12, system.activeCount)
    }

    @Test
    fun `low ram configuration emits fewer particles`() {
        val system = ParticleSystem(capacity = 96, maxPerTap = 8)
        system.emit(0f, 0f)
        assertEquals(8, system.activeCount)
    }

    @Test
    fun `pool never exceeds capacity`() {
        val system = ParticleSystem(capacity = 24, maxPerTap = 12)
        repeat(10) { system.emit(50f, 50f) }
        assertTrue(system.activeCount <= 24)
    }

    @Test
    fun `particles die out and stop requesting frames`() {
        val system = ParticleSystem(capacity = 96, maxPerTap = 12)
        system.emit(10f, 10f)
        var frames = 0
        while (system.update(1f / 60f) && frames < 600) frames++
        assertEquals(0, system.activeCount)
        // 寿命上限 0.8s → 60fps 下最多 48 帧左右；给足余量
        assertTrue(frames in 1..120)
    }

    @Test
    fun `update on empty system reports no redraw`() {
        val system = ParticleSystem()
        assertEquals(false, system.update(1f / 60f))
    }
}
