package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.model.NumberedSlotContainer
import sh.hnet.comfychair.model.WorkflowCapabilities

/**
 * 「删光图位后仍走图位机制」这条不变量的回归锁（Task 8 审查发现 1/2）。
 *
 * 判据必须是**检测阶段留下的持久事实**（能力快照 / 乙类容器），不能用当前图位列表的瞬时状态。
 * 两个用户可见缺陷都出自同一个错误模式：
 * - 界面用 `imageSlots.isNotEmpty()` → 乙类删光后图位区与「+ 添加」一起消失，图位加不回来
 * - 提交用 `imageSlots.isEmpty()` → 乙类删光后工作流里残留的 `{{reference_image_N}}` 占位符
 *   既不被替换也不被剪掉，当字面量文件名发给 ComfyUI
 */
class ImageToVideoUiStateSlotTest {

    private val ref2vContainer = NumberedSlotContainer("ref_images", "ref_image_", 9)

    private fun numberedSlot(placeholder: String, index: Int) = ImageSlot.Numbered(
        placeholder = placeholder,
        order = 100 + index,
        container = "ref_images",
        prefix = "ref_image_",
        index = index,
        maxCount = 9
    )

    /** ref2v 检测结果：主图占位符与参考图占位符都接在 ref_images 容器上 */
    private val ref2vSlots = listOf(
        numberedSlot("image_filename", 0),
        numberedSlot("reference_image_1", 1)
    )

    private fun stateFor(slots: List<ImageSlot>, container: NumberedSlotContainer?) = ImageToVideoUiState(
        imageSlots = slots,
        numberedSlotContainer = container,
        capabilities = WorkflowCapabilities.fromPlaceholders(setOf("image_filename"), slots)
    )

    private val ref2vLoaded = stateFor(ref2vSlots, ref2vContainer)

    /** flf2v：两个具名图位，工作流里根本没有乙类容器 */
    private val flf2vSlots = listOf(
        ImageSlot.Named("reference_image_1", 0, ImageSlot.Semantics.FIRST_FRAME, "first_frame"),
        ImageSlot.Named("image_filename", 1, ImageSlot.Semantics.LAST_FRAME, "last_frame")
    )
    private val flf2vLoaded = stateFor(flf2vSlots, null)

    /**
     * 乙类图位被逐个删掉后的状态。
     *
     * 与 `ImageToVideoViewModel.removeImageSlots` 的效果一致（图位定义与图片一起摘掉），
     * 这里手写 `copy` 是因为 ViewModel 一构造就会注册提示词桥接、起协程，JVM 单测里跑不动。
     */
    private fun ImageToVideoUiState.afterDeleting(vararg placeholders: String): ImageToVideoUiState {
        val removed = placeholders.toSet()
        return copy(
            imageSlots = imageSlots.filterNot { it.placeholder in removed },
            slotImages = slotImages.filterKeys { it !in removed }
        )
    }

    @Test
    fun `乙类图位删光后仍判定为图位型工作流`() {
        val deleted = ref2vLoaded.afterDeleting("image_filename", "reference_image_1")

        assertTrue("列表空了也仍然是图位型工作流", deleted.usesImageSlots)
    }

    @Test
    fun `乙类图位删光后「+ 添加」入口仍在`() {
        val deleted = ref2vLoaded.afterDeleting("image_filename", "reference_image_1")

        assertTrue("删到 0 张后必须还能加回来（设计 §3）", deleted.canAddNumberedSlot)
    }

    @Test
    fun `判据不随图位列表的瞬时变化而变`() {
        val afterOne = ref2vLoaded.afterDeleting("image_filename")
        val afterAll = afterOne.afterDeleting("reference_image_1")

        assertTrue(ref2vLoaded.usesImageSlots)
        assertEquals(
            "同一个工作流删前删后必须是同一个判断",
            ref2vLoaded.usesImageSlots,
            afterAll.usesImageSlots
        )
        assertTrue(afterOne.usesImageSlots)
    }

    @Test
    fun `只有具名图位的工作流同样判定为图位型`() {
        assertTrue(flf2vLoaded.usesImageSlots)
    }

    /**
     * 具名图位也一个不剩时，判据只能来自**检测阶段的快照**：
     * 这个状态下 `imageSlots` 与 `capabilities.imageSlots` 必须产生不同结论，
     * 才能把"用瞬时列表判"的实现区分出来（ref2v 那种带乙类容器的状态区分不出来——
     * 容器本身就把判据兜住了）。
     *
     * 可达性：当前 UI 删不掉具名图位的定义（`deleteSlots` 对甲类只清图片，设计 §3「甲类不允许增减」），
     * 所以这是"状态形状"的回归锁，防的是判据被改回瞬时形式。
     */
    @Test
    fun `具名图位也被删光时仍判定为图位型（判据不依赖列表快照）`() {
        val deleted = flf2vLoaded.afterDeleting("image_filename", "reference_image_1")

        assertTrue("检测阶段识别过图位，就不该因为列表清空而退回旧路径", deleted.usesImageSlots)
        assertEquals(
            "能力快照才是持久事实",
            flf2vLoaded.capabilities.imageSlots.isNotEmpty(),
            true
        )
    }

    @Test
    fun `识别不出图位的工作流走旧的单图路径`() {
        val noSlots = ImageToVideoUiState()

        assertFalse(noSlots.usesImageSlots)
        assertFalse(noSlots.canAddNumberedSlot)
    }

    @Test
    fun `有图位时判定为图位型（正向对照）`() {
        assertTrue(ref2vLoaded.usesImageSlots)
        assertTrue(ref2vLoaded.canAddNumberedSlot)
    }

    @Test
    fun `乙类图位删光后提交仍走图位注入（不退回字符串替换）`() {
        val deleted = ref2vLoaded.afterDeleting("image_filename", "reference_image_1")
        val uploaded = mapOf("image_filename" to "uploaded.png")

        assertEquals(
            "传空表会让工作流里剩下的 {{reference_image_N}} 变成字面量文件名",
            uploaded,
            imageSlotFilesForInjection(deleted, uploaded)
        )
    }

    @Test
    fun `识别不出图位的工作流提交空表（维持旧的字符串替换）`() {
        val uploaded = mapOf("image_filename" to "uploaded.png")

        assertTrue(imageSlotFilesForInjection(ImageToVideoUiState(), uploaded).isEmpty())
    }

    // ---- Task 8 复审新问题 2：顶部上传必须落在"看得见的图位"上 ----

    @Test
    fun `顶栏上传填第一个空图位（flf2v 上先填首帧）`() {
        val target = uploadTargetSlot(flf2vSlots, setOf("image_filename"), null)

        assertEquals("已填的尾帧要让位给空着的首帧", "reference_image_1", target?.placeholder)
    }

    @Test
    fun `图位全部填满时顶栏上传替换第一个图位`() {
        val target = uploadTargetSlot(
            flf2vSlots,
            setOf("reference_image_1", "image_filename"),
            null
        )

        assertEquals("reference_image_1", target?.placeholder)
    }

    @Test
    fun `图位被删光时顶栏上传新建图位而不是写主图键`() {
        val target = uploadTargetSlot(emptyList(), emptySet(), ref2vContainer)

        assertEquals(
            "乙类最小空位编号（删空后回到 0）；绝不能退回主图键——那个键在界面上没有格子承载",
            "reference_image_0",
            target?.placeholder
        )
    }

    @Test
    fun `识别不出图位的工作流顶栏上传回退主图键`() {
        assertNull(uploadTargetSlot(emptyList(), emptySet(), null))
    }
}
