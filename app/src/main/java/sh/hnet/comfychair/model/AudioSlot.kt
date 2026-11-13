package sh.hnet.comfychair.model

/**
 * 工作流里的一个音频输入位（参考音频）。
 *
 * 与图片位不同，音频**只有编号一种形态**：参考音频在节点侧是 `COMFY_AUTOGROW_V3`
 * 的自动增长输入（`ref_audios.ref_audio_N`，上限 3），没有首帧/尾帧那样的具名语义口，
 * 所以这里不需要 `sealed interface` 那一层。
 *
 * 编号与占位符名的对应关系与图片侧同构：0 号是 `{{audio_filename}}`，
 * 之后是 `{{reference_audio_1}}`、`{{reference_audio_2}}`。
 */
data class AudioSlot(
    /** 工作流中的占位符名："audio_filename"（0 号）或 "reference_audio_N"（N≥1） */
    val placeholder: String,
    /** 显示顺序：等于容器内编号，界面按它排 */
    val order: Int,
    /** 容器输入名，如 "ref_audios" */
    val container: String,
    /** 容器内编号前缀，如 "ref_audio_" */
    val prefix: String,
    /** 该槽在容器内的编号，0 起 */
    val index: Int,
    /** 容器总上限，从 /object_info 读 */
    val maxCount: Int
)

/** 音频槽占位符的前缀（工作流里写的名字，如 `{{reference_audio_1}}`） */
internal const val AUDIO_SLOT_PLACEHOLDER_PREFIX = "reference_audio_"

/** 第一个音频槽的占位符名（与图片的 `image_filename` 同构） */
internal const val AUDIO_MAIN_PLACEHOLDER = "audio_filename"

/** 编号 N 对应的占位符名 */
internal fun audioPlaceholderFor(index: Int): String =
    if (index == 0) AUDIO_MAIN_PLACEHOLDER else "$AUDIO_SLOT_PLACEHOLDER_PREFIX$index"

/**
 * 从检测出的音频槽里提取容器信息（供「+ 添加」与新增逻辑使用）。
 *
 * 与图片侧同构：音频槽可以被用户删到一个不剩，那时从槽位列表里再也推不出容器，
 * 但「+ 添加」入口与新增逻辑都还要靠它工作。
 */
internal fun audioContainerOf(slots: List<AudioSlot>): NumberedSlotContainer? =
    slots.firstOrNull()?.let {
        NumberedSlotContainer(container = it.container, prefix = it.prefix, maxCount = it.maxCount)
    }

/** 还能不能再加一个音频槽：容器存在，且数量没到上限 */
internal fun hasRoomForAudioSlot(
    slots: List<AudioSlot>,
    container: NumberedSlotContainer?
): Boolean = container != null && slots.size < container.maxCount

/**
 * 造一个新音频槽：占用编号里最小的空位；编号用完时返回 null。
 *
 * 与图片侧同理，必须占"最小空编号"而不是追加到末尾：
 * 界面标签的序号按列表顺序数，节点侧看到的 `<Audio N>` 按编号升序定，
 * 两者只有在"列表按 order 排序后编号也升序"时才一致。
 */
internal fun nextAudioSlot(
    slots: List<AudioSlot>,
    container: NumberedSlotContainer
): AudioSlot? {
    val used = slots.map { it.index }.toSet()
    val takenPlaceholders = slots.map { it.placeholder }.toSet()
    val next = (0 until container.maxCount).firstOrNull { index ->
        index !in used && audioPlaceholderFor(index) !in takenPlaceholders
    } ?: return null

    return AudioSlot(
        placeholder = audioPlaceholderFor(next),
        order = next,
        container = container.container,
        prefix = container.prefix,
        index = next,
        maxCount = container.maxCount
    )
}

/** 并入列表并按编号排序（排序是稳定的，同类 order 相同时保持原有先后） */
internal fun withAudioSlotAdded(slots: List<AudioSlot>, added: AudioSlot): List<AudioSlot> =
    (slots + added).sortedBy { it.order }
