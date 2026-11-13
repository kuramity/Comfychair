package sh.hnet.comfychair.model

/**
 * 工作流里的一个图片输入位。
 *
 * 分两类（详见设计文档第 0 节）：
 * - [Named]    甲类，工作流里写死的具名输入，数量固定，节点按名字读取，天然不补位
 * - [Numbered] 乙类，ComfyUI 的 COMFY_AUTOGROW_V3 自动增长输入，数量由用户决定
 */
sealed interface ImageSlot {
    /** 工作流中的占位符名，如 "image_filename"、"reference_image_1" */
    val placeholder: String
    /** 显示顺序，小的排前面 */
    val order: Int

    /** 甲类：具名图位（首帧/尾帧/普通主图） */
    data class Named(
        override val placeholder: String,
        override val order: Int,
        val semantics: Semantics,
        /** 下游消费节点上的输入口名，如 "first_frame" */
        val targetInput: String
    ) : ImageSlot

    /** 乙类：编号图位（参考图） */
    data class Numbered(
        override val placeholder: String,
        override val order: Int,
        /** 容器输入名，如 "ref_images" */
        val container: String,
        /** 编号前缀，如 "ref_image_" */
        val prefix: String,
        /** 该槽在容器内的编号，0 起 */
        val index: Int,
        /** 容器总上限，从 /object_info 读，读不到时由调用方给默认值 */
        val maxCount: Int
    ) : ImageSlot

    enum class Semantics { FIRST_FRAME, LAST_FRAME, GENERIC }
}
