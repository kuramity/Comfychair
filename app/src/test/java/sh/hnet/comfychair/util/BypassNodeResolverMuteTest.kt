package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-9：mute（mode=2）在 API 导出与提交路径上必须生效。
 *
 * 背景：ComfyUI 的 API 格式 prompt **不识别 mode 字段**（mode 只存在于 UI 工作流
 * 格式），而 `LiteGraphConverter` 会把 mode 原样写进 API 节点（mode=2 也照写）。
 * 如果导出/提交前不处理，接收端会把 muted 节点当普通节点执行，与用户的「静音」
 * 意图相反。本文件把「导出剔除 + 下游断开」和「提交路径同样剔除」钉死在 JSON 层，
 * 并用源码断言确认导出入口真的接上了。
 *
 * 反证：去掉 `stripMutedNodes` 的调用，最后一组接线测试立刻变红；把 mode==2 的
 * 分支从 `applyBypassedNodes` 删除，第一/三/四组测试变红。
 */
class BypassNodeResolverMuteTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ---------- 1. 导出：剔除 muted 节点并断开下游 ----------

    @Test
    fun `API 导出剔除 muted 节点并断开下游连线`() {
        val nodes = JSONObject(
            """
            {
              "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a.safetensors"}},
              "2": {"class_type": "LoraLoader", "mode": 2, "inputs": {"model": ["1", 0], "lora_name": "b"}},
              "3": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "seed": 1}}
            }
            """.trimIndent()
        )

        val removed = BypassNodeResolver.stripMutedNodes(nodes)

        assertEquals("应只移除 1 个 muted 节点", 1, removed)
        assertNull("muted 节点必须从导出结果里消失", nodes.optJSONObject("2"))
        assertNotNull("非 mute 节点必须保留", nodes.optJSONObject("1"))
        assertNotNull("非 mute 节点必须保留", nodes.optJSONObject("3"))

        val sampler = nodes.getJSONObject("3").getJSONObject("inputs")
        assertFalse("指向 muted 节点的输入必须断开", sampler.has("model"))
        assertEquals("其他输入不受影响", 1, sampler.getInt("seed"))
        assertEquals(
            "非 mute 节点的内容不得被改动",
            "a.safetensors",
            nodes.getJSONObject("1").getJSONObject("inputs").getString("ckpt_name")
        )
    }

    @Test
    fun `没有 muted 节点时导出内容完全不变且 bypass 不被剔除`() {
        val nodes = JSONObject(
            """
            {
              "1": {"class_type": "A", "inputs": {}},
              "2": {"class_type": "B", "mode": 4, "inputs": {"model": ["1", 0]}},
              "3": {"class_type": "C", "inputs": {"model": ["2", 0]}}
            }
            """.trimIndent()
        )
        val before = nodes.toString()

        val removed = BypassNodeResolver.stripMutedNodes(nodes)

        assertEquals("没有 mute 节点时应返回 0", 0, removed)
        assertEquals("导出路径只处理 mute，bypass 必须原样保留", before, nodes.toString())
    }

    // ---------- 2. 提交路径：applyBypassedNodes 同时处理 mute ----------

    @Test
    fun `提交路径剔除 muted 节点并断开其下游`() {
        val input = """
            {
              "nodes": {
                "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a.safetensors"}},
                "2": {"class_type": "LoraLoader", "mode": 2, "inputs": {"model": ["1", 0], "lora_name": "b"}},
                "3": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "seed": 1}}
              }
            }
        """.trimIndent()

        val nodes = JSONObject(BypassNodeResolver.applyBypassedNodes(input)).getJSONObject("nodes")

        assertNull("muted 节点不得提交执行", nodes.optJSONObject("2"))
        assertNotNull(nodes.optJSONObject("1"))
        val sampler = nodes.getJSONObject("3").getJSONObject("inputs")
        assertFalse("下游不得再引用已删除的 muted 节点", sampler.has("model"))
        assertEquals(1, sampler.getInt("seed"))
    }

    @Test
    fun `bypass 直通链撞上 muted 节点时断开而不是指向已删除节点`() {
        // 1(active) -> 2(muted) -> 3(bypassed) -> 4(active)
        val input = """
            {
              "nodes": {
                "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a.safetensors"}},
                "2": {"class_type": "LoraLoader", "mode": 2, "inputs": {"model": ["1", 0], "lora_name": "b"}},
                "3": {"class_type": "LoraLoader", "mode": 4, "inputs": {"model": ["2", 0], "lora_name": "c"}},
                "4": {"class_type": "KSampler", "inputs": {"model": ["3", 0], "seed": 1}}
              }
            }
        """.trimIndent()

        val nodes = JSONObject(BypassNodeResolver.applyBypassedNodes(input)).getJSONObject("nodes")

        assertNull(nodes.optJSONObject("2"))
        assertNull(nodes.optJSONObject("3"))
        val sampler = nodes.getJSONObject("4").getJSONObject("inputs")
        assertFalse("直通链终点是 muted 时必须断开，不能留下悬空引用", sampler.has("model"))
    }

    @Test
    fun `原有 bypass 直通行为不变`() {
        // 1(active) -> 2(bypassed) -> 3(active)：仍应重连到 1，而不是断开
        val input = """
            {
              "nodes": {
                "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a.safetensors"}},
                "2": {"class_type": "LoraLoader", "mode": 4, "inputs": {"model": ["1", 0], "lora_name": "b"}},
                "3": {"class_type": "KSampler", "inputs": {"model": ["2", 0], "seed": 1}}
              }
            }
        """.trimIndent()

        val nodes = JSONObject(BypassNodeResolver.applyBypassedNodes(input)).getJSONObject("nodes")

        assertNull("bypassed 节点照旧移除", nodes.optJSONObject("2"))
        val model = nodes.getJSONObject("3").getJSONObject("inputs").getJSONArray("model")
        assertEquals("bypass 应直通到上游节点", "1", model.getString(0))
        assertEquals(0, model.getInt(1))
    }

    // ---------- 3. 接线：导出入口真的调用剔除 ----------

    @Test
    fun `API 导出入口在序列化前剔除 muted 节点`() {
        val source = readSource("src/main/java/sh/hnet/comfychair/WorkflowManager.kt")
        val start = source.indexOf("fun exportWorkflowToComfyUIFormat(")
        assertTrue("找不到 exportWorkflowToComfyUIFormat", start >= 0)
        val end = source.indexOf("fun exportWorkflowInternal(", start)
        assertTrue("找不到 exportWorkflowToComfyUIFormat 的结束边界", end > start)
        val body = source.substring(start, end)

        val strip = body.indexOf("BypassNodeResolver.stripMutedNodes(nodesJson)")
        val serialize = body.indexOf("return Result.success(nodesJson.toString(2))")
        assertTrue("导出路径必须调用 stripMutedNodes(nodesJson)", strip >= 0)
        assertTrue("剔除必须发生在序列化返回（$serialize）之前", serialize > strip)
    }
}