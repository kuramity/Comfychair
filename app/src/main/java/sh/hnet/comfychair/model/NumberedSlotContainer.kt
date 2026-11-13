package sh.hnet.comfychair.model

/**
 * 编号图位的**占位符**名前缀（工作流里写的名字，如 `{{reference_image_0}}`）。
 *
 * 注意与 [NumberedSlotContainer.prefix] 区分：那个是容器输入口里的编号前缀（如 `ref_image_`），
 * 两者在真实工作流里通常同源，但口径不同——占位符名给注入器用，容器前缀属于节点输入口。
 */
internal const val NUMBERED_SLOT_PLACEHOLDER_PREFIX = "reference_image_"

/**
 * 乙类（编号）图位所属容器的信息：容器输入名、容器内编号前缀、张数上限。
 *
 * 从检测阶段的图位定义里提取一次（见 [numberedContainerOf]），之后与 `List<ImageSlot>` **分开保存**。
 * 这是刻意的：编号图位可以被用户一路删到 0 张（设计 §3「可删到 0 张，可通过「+ 添加」增加到上限」），
 * 那时从图位列表里再也推不出容器信息，但「+ 添加」入口与新增逻辑都还要靠它工作。
 */
data class NumberedSlotContainer(
    /** 容器输入名，如 "ref_images" */
    val container: String,
    /** 容器内编号前缀，如 "ref_image_" */
    val prefix: String,
    /** 容器总上限（`/object_info` 读不到时取识别器的默认值） */
    val maxCount: Int
)

/**
 * 乙类（编号）图位的 order 基准：编号 N 的图位，order = 本基准 + N。
 *
 * **必须与 `ImageSlotDetector` 建槽时同口径**（那边也用它给检测出的编号图位定序），
 * 否则"检测出的图位"与"用户新加的图位"会排到不同位置。
 *
 * 为什么不能取"当前最大 order + 1"（追加到末尾）：界面标签的序号是按**列表顺序**数出来的
 * （`ImageSlotGrid` / `ImageSlotArea` 逐个 `++referenceSeen` 数「参考图 N」），而模型侧看到的
 * `<Picture N>` 由**编号升序**决定（ComfyUI 按 `names = [f"{prefix}{i}" for i in range(max)]`
 * 展开自动增长输入，见 `comfy_api/latest/_io.py`）。两者只有在"列表按 order 排序后编号也升序"
 * 时才一致——ref2v 删掉编号 0 那格再点「+ 添加」时，新图位补的是编号 0，若按"最大 order + 1"
 * 排到末尾，它明明是 `<Picture 1>` 却会被标成「参考图 2」，与幸存那格的标签正好互换。
 */
internal const val NUMBERED_SLOT_ORDER_BASE = 100

/**
 * 从检测出的图位里提取乙类容器信息：取第一个编号图位作代表。
 *
 * 编号图位同属一个容器（`ImageSlotDetector` 按容器口建槽），所以取第一个即可；
 * 工作流没有编号图位（纯甲类，如首尾帧）时返回 null——此时界面不给「+ 添加」入口。
 */
internal fun numberedContainerOf(slots: List<ImageSlot>): NumberedSlotContainer? =
    slots.filterIsInstance<ImageSlot.Numbered>().firstOrNull()?.let {
        NumberedSlotContainer(container = it.container, prefix = it.prefix, maxCount = it.maxCount)
    }

/**
 * 还能不能再加一个编号图位：容器存在（纯甲类工作流为 null），且当前编号图位数没到上限。
 *
 * 判据里的"当前数量"来自图位列表，"上限"来自容器——后者不随删图位消失，所以删光后本函数仍为 true。
 */
internal fun hasRoomForNumberedSlot(
    slots: List<ImageSlot>,
    container: NumberedSlotContainer?
): Boolean = container != null && slots.count { it is ImageSlot.Numbered } < container.maxCount

/**
 * 造一个新的编号图位：占用编号里最小的空位；编号用完时返回 null。
 *
 * - 占位符名按 [NUMBERED_SLOT_PLACEHOLDER_PREFIX] 生成，容器/前缀/上限则取自 [container]，
 *   与 `ImageToVideoViewModel.addImageSlot` 原来的写法等价（原来是从 `numbered.first()` 复制）
 * - 编号空着还不够：占位符名也不能被别的图位占用（编号与实际占位符不一致的工作流里可能撞名）
 */
internal fun nextNumberedSlot(
    slots: List<ImageSlot>,
    container: NumberedSlotContainer
): ImageSlot.Numbered? {
    val used = slots.filterIsInstance<ImageSlot.Numbered>().map { it.index }.toSet()
    val takenPlaceholders = slots.map { it.placeholder }.toSet()
    val next = (0 until container.maxCount).firstOrNull { index ->
        index !in used && "$NUMBERED_SLOT_PLACEHOLDER_PREFIX$index" !in takenPlaceholders
    } ?: return null

    return ImageSlot.Numbered(
        placeholder = "$NUMBERED_SLOT_PLACEHOLDER_PREFIX$next",
        // 与探测器同口径：order 由编号算出，不是"当前最大 order + 1"。见 NUMBERED_SLOT_ORDER_BASE
        order = NUMBERED_SLOT_ORDER_BASE + next,
        container = container.container,
        prefix = container.prefix,
        index = next,
        maxCount = container.maxCount
    )
}

/**
 * 把新图位并入列表，并按 [ImageSlot.order] 排好序。
 *
 * 每次加图位都要重排：新图位占的是"最小空编号"，它的 order 可能**小于**列表里已有的图位
 * （删掉编号 0 之后新加的图位 order=100，幸存的编号 1 是 101），此时只有排好序它才会落到
 * 该出现的位置——列表顺序就是界面标签序号的来源（`ImageSlotGrid` 数「参考图 N」），
 * 编号顺序才是模型看到的 `<Picture N>`，两者必须一致，否则标签会说谎。
 *
 * 排序是稳定的（`sortedBy` 用 TimSort），同类图位 order 相同时保持原有先后。
 */
internal fun withSlotAdded(slots: List<ImageSlot>, added: ImageSlot): List<ImageSlot> =
    (slots + added).sortedBy { it.order }
