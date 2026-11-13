package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager

/**
 * 音频槽注入。
 *
 * 注入器本身与图片共用（[WorkflowManager.injectMediaSlots]），这里钉的是**音频这一侧**的契约：
 * - 有值写文件名、空位删节点并清连线；
 * - 新加的编号槽要补出 LoadAudio 节点并接到 ref_audios 上；
 * - **两种媒体互不干扰** —— 音频注入不能动图片节点，反之亦然。
 *   最后这条最容易出事：两者共用同一个注入器，一个"顺手"的通用化改动就可能让
 *   音频的剪枝把图片链路一起剪掉。
 */
class AudioSlotInjectorTest {

    private fun workflow() = """
    {
      "nodes": {
        "316": {
          "class_type": "MiniMaxH3ReferenceToVideo",
          "inputs": {
            "ref_images.ref_image_0": ["308", 0],
            "ref_audios.ref_audio_0": ["317", 0]
          }
        },
        "317": { "class_type": "LoadAudio", "inputs": { "audio": "{{audio_filename}}" } },
        "308": { "class_type": "LoadImage", "inputs": { "image": "{{image_filename}}" } }
      }
    }
    """.trimIndent()

    private fun nodesOf(json: String) = JSONObject(json).getJSONObject("nodes")

    private fun consumerInputsOf(json: String) =
        nodesOf(json).getJSONObject("316").getJSONObject("inputs")

    @Test
    fun `有值的音频槽写入文件名`() {
        val out = WorkflowManager.injectAudioSlots(workflow(), mapOf("audio_filename" to "voice.mp3"))
        val audio = nodesOf(out).getJSONObject("317").getJSONObject("inputs").getString("audio")
        assertEquals("voice.mp3", audio)
    }

    @Test
    fun `没填的音频槽删掉节点并清掉连线`() {
        val out = WorkflowManager.injectAudioSlots(workflow(), emptyMap())

        assertNull("空槽的 LoadAudio 节点必须删掉，否则会带着占位符提交、解析失败", nodesOf(out).optJSONObject("317"))
        assertNull(
            "指向它的连线也要清掉",
            consumerInputsOf(out).opt("ref_audios.ref_audio_0")
        )
    }

    @Test
    fun `空串按留空处理`() {
        // ComfyUI 不接受空文件名，调用方给空串的意图只可能是"这个槽没填"
        val out = WorkflowManager.injectAudioSlots(workflow(), mapOf("audio_filename" to "   "))
        assertNull(nodesOf(out).optJSONObject("317"))
    }

    @Test
    fun `新加的编号槽会补出节点并接到容器上`() {
        val out = WorkflowManager.injectAudioSlots(
            workflow(),
            mapOf("audio_filename" to "a.mp3", "reference_audio_1" to "b.mp3")
        )

        val wired = consumerInputsOf(out).getJSONArray("ref_audios.ref_audio_1")
        val newId = wired.getString(0)
        val newNode = nodesOf(out).getJSONObject(newId)

        assertEquals("LoadAudio", newNode.getString("class_type"))
        assertEquals("b.mp3", newNode.getJSONObject("inputs").getString("audio"))
        assertEquals("接线端口号应为 0", 0, wired.getInt(1))
    }

    @Test
    fun `音频注入不碰图片节点`() {
        val out = WorkflowManager.injectAudioSlots(workflow(), mapOf("audio_filename" to "a.mp3"))

        val imageNode = nodesOf(out).getJSONObject("308")
        assertEquals("{{image_filename}}", imageNode.getJSONObject("inputs").getString("image"))
        assertTrue(
            "图片的连线也不该被动",
            consumerInputsOf(out).has("ref_images.ref_image_0")
        )
    }

    @Test
    fun `图片注入不碰音频节点`() {
        // 反向隔离：共用注入器之后，这条最容易被"顺手通用化"改坏
        val out = WorkflowManager.injectImageSlots(workflow(), mapOf("image_filename" to "pic.png"))

        val audioNode = nodesOf(out).getJSONObject("317")
        assertEquals("{{audio_filename}}", audioNode.getJSONObject("inputs").getString("audio"))
        assertTrue(
            "音频的连线也不该被动",
            consumerInputsOf(out).has("ref_audios.ref_audio_0")
        )
    }

    @Test
    fun `没有音频占位符的工作流上音频注入是空操作`() {
        // 16 个预置工作流里只有 ref2v 接了音频槽，其余流提交时音频注入必须什么都不做
        val noAudio = """
        {
          "nodes": {
            "316": { "class_type": "MiniMaxH3ImageToVideo",
                     "inputs": { "first_frame": ["308", 0] } },
            "308": { "class_type": "LoadImage", "inputs": { "image": "{{image_filename}}" } }
          }
        }
        """.trimIndent()

        val out = WorkflowManager.injectAudioSlots(noAudio, mapOf("audio_filename" to "a.mp3"))
        assertEquals("不该凭空造出 LoadAudio 节点", 2, nodesOf(out).length())
        assertEquals(
            "图片节点应保持原样",
            "{{image_filename}}",
            nodesOf(out).getJSONObject("308").getJSONObject("inputs").getString("image")
        )
    }
}
