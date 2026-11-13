package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.ImageSlotDetector

/**
 * 编号图位增删的纯逻辑。
 *
 * 重点钉住一个真实缺陷：编号图位被用户删光后容器信息必须还在——
 * 否则「+ 添加」入口消失、图位再也加不回来（设计 §3：可删到 0 张，可通过「+ 添加」增加到上限）。
 */
class ImageSlotNumberingTest {

    private val firstFrame = ImageSlot.Named(
        placeholder = "image_filename",
        order = 0,
        semantics = ImageSlot.Semantics.FIRST_FRAME,
        targetInput = "first_frame"
    )

    private val container = NumberedSlotContainer(
        container = "ref_images",
        prefix = "ref_image_",
        maxCount = 9
    )

    private fun reference(index: Int) = ImageSlot.Numbered(
        placeholder = "reference_image_$index",
        order = 100 + index,
        container = "ref_images",
        prefix = "ref_image_",
        index = index,
        maxCount = 9
    )

    @Test
    fun `把编号图位全删光后仍然可以继续添加`() {
        // 复现路径：ref2v 两张参考图 → 长按 → 全选 → 删除 → imageSlots 里只剩甲类
        val slots = listOf(firstFrame)

        assertTrue("删光后「+ 添加」的判据必须仍为真", hasRoomForNumberedSlot(slots, container))

        val added = nextNumberedSlot(slots, container)
        assertNotNull("删光后必须还能造出新图位", added)
        assertEquals("reference_image_0", added!!.placeholder)
        assertEquals(0, added.index)
        assertEquals("ref_images", added.container)
        assertEquals("ref_image_", added.prefix)
        assertEquals(9, added.maxCount)
    }

    @Test
    fun `纯甲类工作流没有容器信息也不提供添加`() {
        val slots = listOf(firstFrame)

        assertNull("纯甲类工作流提取不出容器信息", numberedContainerOf(slots))
        assertFalse("没有容器就不能加", hasRoomForNumberedSlot(slots, null))
    }

    @Test
    fun `达到容器上限后不再提供添加`() {
        val slots = listOf(firstFrame) + (0 until 9).map { reference(it) }

        assertFalse(hasRoomForNumberedSlot(slots, container))
        assertNull(nextNumberedSlot(slots, container))
    }

    @Test
    fun `未到上限时可以添加且上限取自容器而非当前列表`() {
        val slots = listOf(firstFrame, reference(0))

        assertTrue(hasRoomForNumberedSlot(slots, container))
        assertEquals(1, nextNumberedSlot(slots, container)!!.index)
    }

    @Test
    fun `新图位占用最小空编号`() {
        val slots = listOf(reference(0), reference(2))

        val added = nextNumberedSlot(slots, container)!!

        assertEquals("编号 1 空着就该用 1", 1, added.index)
        assertEquals("reference_image_1", added.placeholder)
    }

    @Test
    fun `编号空着但占位符名被别的图位占用时跳到下一个编号`() {
        // 编号与实际占位符不一致的工作流：reference_image_1 这个名字被甲类图位占着
        val busyName = ImageSlot.Named(
            placeholder = "reference_image_1",
            order = 2,
            semantics = ImageSlot.Semantics.GENERIC,
            targetInput = "image"
        )
        val slots = listOf(reference(0), reference(2), busyName)

        assertEquals(3, nextNumberedSlot(slots, container)!!.index)
    }

    @Test
    fun `容器信息取自第一个编号图位`() {
        val detected = numberedContainerOf(listOf(firstFrame, reference(0), reference(1)))

        assertEquals("ref_images", detected!!.container)
        assertEquals("ref_image_", detected.prefix)
        assertEquals(9, detected.maxCount)
    }

    /** ref2v 的真实结构（同 `ImageSlotDetectorTest`）：编号 0 接主图占位符，编号 1 接参考图占位符 */
    private val ref2vJson = """
    {"nodes":{
      "295":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
          "ref_images.ref_image_0":["308",0],"ref_images.ref_image_1":["295",0]}}
    }}
    """.trimIndent()

    private fun numberedIndices(slots: List<ImageSlot>): List<Int> =
        slots.filterIsInstance<ImageSlot.Numbered>().map { it.index }

    /**
     * 钉住 order 的口径：新图位的 order 必须与**同编号**的探测结果一致，不能是"当前最大 order + 1"。
     *
     * 编号决定模型看到的 `<Picture N>`（ComfyUI 按 `names = [f"{prefix}{i}" for i in range(max)]`
     * 展开自动增长输入），而界面标签序号是按列表顺序数出来的——两者只有"列表按 order 排序后编号也升序"
     * 时才一致。旧写法（max(order) + 1）会让补回编号 0 的新格排到编号 1 之后，标签与 `<Picture N>` 互换。
     */
    @Test
    fun `新建图位的 order 与编号一致 而不是追加到末尾`() {
        // ref2v 实测路径：检测出编号 0 / 1 两格 → 删掉第一格（编号 0）→ 「+ 添加」
        val detected = ImageSlotDetector.detect(ref2vJson, null)
        assertEquals(listOf(0, 1), numberedIndices(detected))
        val removed = detected.filterIsInstance<ImageSlot.Numbered>().first { it.index == 0 }
        val remaining = detected.filterNot { it.placeholder == removed.placeholder }
        assertEquals(listOf(1), numberedIndices(remaining))

        val added = nextNumberedSlot(remaining, container)!!

        assertEquals("编号 0 空着就该补回编号 0", 0, added.index)
        assertEquals(
            "新图位的 order 必须与同编号的探测结果同口径，不能是 max(order) + 1（那样会被排到末尾）",
            removed.order,
            added.order
        )
        assertEquals(
            "列表按 order 排序后编号必须升序——新格（<Picture 1>）要排在幸存那格（<Picture 2>）前面",
            listOf(0, 1),
            numberedIndices(withSlotAdded(remaining, added))
        )
    }

    /**
     * 三步复现的纯逻辑版本：删第一格 → 「+ 添加」→ 列表顺序回到编号升序。
     *
     * 界面标签按列表顺序数「参考图 N」，所以列表顺序错位时会出现"标着参考图 2 的格子其实是 `<Picture 1>`"。
     */
    @Test
    fun `删掉第一格再添加后列表回到编号升序`() {
        val detected = ImageSlotDetector.detect(ref2vJson, null)
        val removed = detected.filterIsInstance<ImageSlot.Numbered>().first { it.index == 0 }
        val remaining = detected.filterNot { it.placeholder == removed.placeholder }

        val added = nextNumberedSlot(remaining, container)!!
        val merged = withSlotAdded(remaining, added)

        assertEquals(listOf("reference_image_0", "reference_image_1"), merged.map { it.placeholder })
        assertEquals(listOf(0, 1), numberedIndices(merged))
    }
}
