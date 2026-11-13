package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.model.NumberedSlotContainer
import sh.hnet.comfychair.model.audioContainerOf
import sh.hnet.comfychair.model.hasRoomForAudioSlot
import sh.hnet.comfychair.model.nextAudioSlot
import sh.hnet.comfychair.model.withAudioSlotAdded

/**
 * 音频槽识别。
 *
 * 与图位识别同构，但有两点必须钉住：
 * 1. **只认占位符**：audio 字段是真实文件名的 LoadAudio 节点（用户手工接的音频）不能被当成槽位，
 *    否则注入器会把用户自己的接线当成"空槽"删掉；
 * 2. **容器锚点**：注入器要靠 `ref_audios` / `ref_audio_` 这两个名字来补新节点，
 *    识别错了会写到别的容器上（同节点上还有 ref_images / ref_videos 几个兄弟容器）。
 */
class AudioSlotDetectorTest {

    private fun singleSlotWorkflow() = """
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

    private fun threeSlotWorkflow() = """
    {
      "nodes": {
        "316": {
          "class_type": "MiniMaxH3ReferenceToVideo",
          "inputs": {
            "ref_audios.ref_audio_0": ["317", 0],
            "ref_audios.ref_audio_1": ["318", 0],
            "ref_audios.ref_audio_2": ["319", 0]
          }
        },
        "317": { "class_type": "LoadAudio", "inputs": { "audio": "{{audio_filename}}" } },
        "318": { "class_type": "LoadAudio", "inputs": { "audio": "{{reference_audio_1}}" } },
        "319": { "class_type": "LoadAudio", "inputs": { "audio": "{{reference_audio_2}}" } }
      }
    }
    """.trimIndent()

    private fun audioSlot(index: Int) = AudioSlot(
        placeholder = if (index == 0) "audio_filename" else "reference_audio_$index",
        order = index,
        container = "ref_audios",
        prefix = "ref_audio_",
        index = index,
        maxCount = 3
    )

    @Test
    fun `识别出音频槽并读到容器锚点`() {
        val slots = AudioSlotDetector.detect(singleSlotWorkflow(), null)

        assertEquals("应识别出 1 个音频槽", 1, slots.size)
        val slot = slots.first()
        assertEquals("audio_filename", slot.placeholder)
        assertEquals(0, slot.index)
        assertEquals("容器名错了，注入器会写到别的容器上", "ref_audios", slot.container)
        assertEquals("ref_audio_", slot.prefix)
        assertEquals("读不到 object_info 时应退回节点定义的默认上限", 3, slot.maxCount)
    }

    @Test
    fun `多个音频槽按编号排序`() {
        val slots = AudioSlotDetector.detect(threeSlotWorkflow(), null)

        assertEquals(3, slots.size)
        assertEquals(
            listOf("audio_filename", "reference_audio_1", "reference_audio_2"),
            slots.map { it.placeholder }
        )
        assertEquals(listOf(0, 1, 2), slots.map { it.order })
    }

    @Test
    fun `上限以服务器返回的节点定义为准`() {
        // 服务器把 ref_audios 的 max 改成 5 时，界面上的「+ 添加」也该跟着放宽
        val objectInfo = JSONObject(
            """{"MiniMaxH3ReferenceToVideo":{"input":{"optional":{"ref_audios":
               ["COMFY_AUTOGROW_V3",{"template":{"prefix":"ref_audio_","min":0,"max":5}}]}}}}"""
        )
        val slots = AudioSlotDetector.detect(singleSlotWorkflow(), objectInfo)
        assertEquals(5, slots.first().maxCount)
    }

    @Test
    fun `真实文件名的音频节点不算槽位`() {
        // 用户手工接的音频（audio 字段是具体文件名）不是占位符槽位，
        // 把它当成槽位的话，注入器会认为"这个槽用户没填"而连节点一起删掉
        val manual = """
        {
          "nodes": {
            "316": { "class_type": "MiniMaxH3ReferenceToVideo",
                     "inputs": { "ref_audios.ref_audio_0": ["317", 0] } },
            "317": { "class_type": "LoadAudio", "inputs": { "audio": "my_voice.mp3" } }
          }
        }
        """.trimIndent()
        assertTrue(AudioSlotDetector.detect(manual, null).isEmpty())
    }

    @Test
    fun `没有音频节点的工作流识别为空`() {
        assertTrue(AudioSlotDetector.detect("""{"nodes":{}}""", null).isEmpty())
        assertTrue(AudioSlotDetector.detect("不是 JSON", null).isEmpty())
    }

    @Test
    fun `追不到容器口时退回节点定义里的容器名`() {
        // 占位符还在、但连线被手工断开：槽位仍要建出来（交给提交方按空位清理），
        // 容器锚点用节点定义里的名字兜底，否则「+ 添加」会无处可写
        val detached = """
        {
          "nodes": {
            "317": { "class_type": "LoadAudio", "inputs": { "audio": "{{audio_filename}}" } }
          }
        }
        """.trimIndent()
        val slots = AudioSlotDetector.detect(detached, null)
        assertEquals(1, slots.size)
        assertEquals("ref_audios", slots.first().container)
        assertEquals("ref_audio_", slots.first().prefix)
    }

    // ---------- 槽位增删（与图位侧同构的编号规则） ----------

    private val container = NumberedSlotContainer("ref_audios", "ref_audio_", 3)

    @Test
    fun `新音频槽补最小空编号而不是追加到末尾`() {
        // 界面标签的序号按列表顺序数，节点侧看到的 <Audio N> 按编号升序定，
        // 两者只有"列表顺序 == 编号升序"时才一致。删掉 0 号后新加的必须补 0 号。
        val existing = listOf(audioSlot(1))
        val added = nextAudioSlot(existing, container)

        assertEquals("应补最小的空编号 0", 0, added?.index)
        assertEquals("audio_filename", added?.placeholder)
    }

    @Test
    fun `编号用完时不再新增`() {
        val full = listOf(audioSlot(0), audioSlot(1), audioSlot(2))
        assertNull(nextAudioSlot(full, container))
    }

    @Test
    fun `并入新槽位后按编号排好序`() {
        val existing = listOf(audioSlot(1))
        val added = nextAudioSlot(existing, container)!!
        val merged = withAudioSlotAdded(existing, added)

        assertEquals(listOf(0, 1), merged.map { it.index })
        assertEquals("audio_filename", merged.first().placeholder)
    }

    @Test
    fun `数量到上限后不再显示添加入口`() {
        assertTrue("还有空位时要能继续加", hasRoomForAudioSlot(listOf(audioSlot(0)), container))
        assertTrue(
            "到上限后不能再加",
            !hasRoomForAudioSlot(listOf(audioSlot(0), audioSlot(1), audioSlot(2)), container)
        )
        assertTrue(
            "工作流不支持音频（没有容器）时永远不显示添加入口",
            !hasRoomForAudioSlot(emptyList(), null)
        )
    }

    @Test
    fun `容器信息在被删光后仍可推出`() {
        // 音频槽可以被用户删到一个不剩，此时「+ 添加」入口还要能用
        assertEquals(container, audioContainerOf(listOf(audioSlot(2))))
        assertNull("没有音频槽时推不出容器", audioContainerOf(emptyList()))
    }
}
