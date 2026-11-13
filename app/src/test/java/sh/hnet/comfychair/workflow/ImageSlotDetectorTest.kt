package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.ImageSlot

class ImageSlotDetectorTest {

    /** flf2v 的真实结构：image_filename 连到尾帧，reference_image_1 连到首帧 */
    private val flf2vJson = """
    {"nodes":{
      "275":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "282":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "337":{"class_type":"ImageResizeKJv2","inputs":{"image":["275",0]}},
      "334":{"class_type":"ImageResizeKJv2","inputs":{"image":["282",0]}},
      "259":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
          "first_frame":["337",0],"last_frame":["334",0]}}
    }}
    """.trimIndent()

    @Test
    fun `首尾帧被识别为具名图位且顺序为首帧在前`() {
        val slots = ImageSlotDetector.detect(flf2vJson, null)

        assertEquals(2, slots.size)
        val first = slots[0] as ImageSlot.Named
        val last = slots[1] as ImageSlot.Named
        assertEquals(ImageSlot.Semantics.FIRST_FRAME, first.semantics)
        assertEquals("reference_image_1", first.placeholder)   // 首帧用的是 ref1 占位符
        assertEquals(ImageSlot.Semantics.LAST_FRAME, last.semantics)
        assertEquals("image_filename", last.placeholder)       // 尾帧用的是主图占位符
    }

    /** ref2v 的真实结构：两个占位符都连到 ref_images 容器 */
    private val ref2vJson = """
    {"nodes":{
      "295":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
          "ref_images.ref_image_0":["308",0],"ref_images.ref_image_1":["295",0]}}
    }}
    """.trimIndent()

    private val objectInfo = JSONObject("""
    {"MiniMaxH3ReferenceToVideo":{"input":{"optional":{
      "ref_images":["COMFY_AUTOGROW_V3",{"template":{
        "input":{"required":{"ref_image":["IMAGE",{}]}},
        "prefix":"ref_image_","min":0,"max":9}}]}}}}
    """.trimIndent())

    @Test
    fun `参考图被识别为编号图位并读出上限`() {
        val slots = ImageSlotDetector.detect(ref2vJson, objectInfo)

        assertEquals(2, slots.size)
        val zero = slots[0] as ImageSlot.Numbered
        val one = slots[1] as ImageSlot.Numbered
        assertEquals("ref_images", zero.container)
        assertEquals(0, zero.index)
        assertEquals("image_filename", zero.placeholder)
        assertEquals(1, one.index)
        assertEquals("reference_image_1", one.placeholder)
        assertEquals(9, zero.maxCount)
    }

    /**
     * 追不到消费口时也必须产出图位。
     * 若像初稿那样搜不到就直接丢弃，占位符会以字面量 `{{image_filename}}` 发给 ComfyUI。
     */
    @Test
    fun `追不到消费口的图片位仍然产出图位且没有目标输入口`() {
        // 情形一：LoadImage 的下游完全悬空
        assertDanglingImageSlot(
            """{"nodes":{"7":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}}}}"""
        )

        // 情形二：下游只有非图片口（image_a 既不是语义口、也不是通用口）
        assertDanglingImageSlot("""
        {"nodes":{
          "7":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "8":{"class_type":"SomeImageNode","inputs":{"image_a":["7",0]}}
        }}
        """.trimIndent())
    }

    private fun assertDanglingImageSlot(json: String) {
        val slots = ImageSlotDetector.detect(json, null)

        assertEquals(1, slots.size)
        val slot = slots[0] as ImageSlot.Named
        assertEquals("image_filename", slot.placeholder)
        assertEquals(ImageSlot.Semantics.GENERIC, slot.semantics)
        assertEquals("", slot.targetInput)
    }

    /**
     * prebuilt_08_krea2_edit_dual 式双兜底：两个图位都是 GENERIC。
     * 并列同一个 order 的话，`sortedBy` 保留的是 JSONObject 的遍历顺序（实测不是文档顺序），
     * 显示顺序与「哪一格贴哪个序号」都会变得不可预期。
     */
    @Test
    fun `多个兜底图位按占位符名排序且不并列同一 order`() {
        val dualGenericJson = """
        {"nodes":{
          "21":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
          "142":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["9",0]}},
          "132":{"class_type":"ImageResizeKJv2","inputs":{"image":["21",0]}}
        }}
        """.trimIndent()

        val slots = ImageSlotDetector.detect(dualGenericJson, null)

        assertEquals(2, slots.size)
        val first = slots[0] as ImageSlot.Named
        val second = slots[1] as ImageSlot.Named
        assertEquals(2, first.order)
        assertEquals(3, second.order)                          // 关键：不能与前者并列 2
        assertEquals("image_filename", first.placeholder)      // 占位符名升序：image_filename < reference_image_1
        assertEquals("reference_image_1", second.placeholder)
    }

    @Test
    fun `没有图位的工作流返回空列表`() {
        val tti = """{"nodes":{"1":{"class_type":"KSampler","inputs":{}}}}"""
        assertTrue(ImageSlotDetector.detect(tti, null).isEmpty())
    }
}
