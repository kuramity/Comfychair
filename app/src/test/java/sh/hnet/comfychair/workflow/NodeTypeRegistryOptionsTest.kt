package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点选项提取的回归测试（2026-09-18 用户报「所有工作流的模型下拉全空」）。
 *
 * 样本 `object_info_sample.json` 是从真机 App 实际拿到的 /object_info 缓存里抽出来的
 * 8 个节点（保留原始结构：旧式 `[[...], {...}]` 与新式 `["COMBO", {...}]` 混杂），
 * 用于钉住「解析 → 取选项」这条链路。
 */
class NodeTypeRegistryOptionsTest {

    @Test
    fun `真实样本能解析出各模型的选项`() {
        val registry = NodeTypeRegistry()
        registry.parseObjectInfo(loadSample())

        assertEquals(
            "checkpoint 选项数应与样本一致",
            18,
            registry.getOptionsForField("ckpt_name").size
        )
        assertTrue("unet 选项不应为空", registry.getOptionsForField("unet_name").isNotEmpty())
        assertTrue("vae 选项不应为空", registry.getOptionsForField("vae_name").isNotEmpty())
        assertTrue("clip 选项不应为空", registry.getOptionsForFieldPrefix("clip_name").isNotEmpty())
    }

    @Test
    fun `新旧两种选项格式都能取到`() {
        val registry = NodeTypeRegistry()
        registry.parseObjectInfo(loadSample())

        // 旧式：CheckpointLoaderSimple.ckpt_name -> [[...], {...}]
        assertEquals(
            18,
            registry.getOptionsForNodeInput("CheckpointLoaderSimple", "ckpt_name").size
        )
        // 新式：UpscaleModelLoader.model_name -> ["COMBO", {"options": [...]}]
        assertEquals(
            5,
            registry.getOptionsForNodeInput("UpscaleModelLoader", "model_name").size
        )
        // 放大模型下拉走的是按字段名取（跨节点并集）
        assertTrue("model_name 选项不应为空", registry.getOptionsForField("model_name").isNotEmpty())
    }

    @Test
    fun `解析后注册表处于就绪态`() {
        val registry = NodeTypeRegistry()
        registry.parseObjectInfo(loadSample())
        assertTrue("解析后 registry 应处于就绪态", registry.isPopulated())
    }

    private fun loadSample(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("object_info_sample.json")
            ?: error("测试资源缺失: object_info_sample.json")
        return stream.use { JSONObject(it.readBytes().decodeToString()) }
    }
}
