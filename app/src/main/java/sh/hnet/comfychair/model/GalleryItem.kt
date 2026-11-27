package sh.hnet.comfychair.model

/**
 * Represents a gallery item (image or video).
 * Bitmaps are stored in MediaCache, not in this data class.
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0, // For sorting
    /**
     * 同一产出（promptId）里的 3D 产物位置（glb 三元组，来自 /history 的 `3d` 字段）。
     *
     * 非空时该条目渲染「3D」角标，点击打开 3D 查看器而不是图片查看器 ——
     * IDOL 图生3D 的输出就是一个 prompt 下两个 node：SaveGLB 写 `3d`、
     * SaveImage 写预览图 `images`，不配对的话用户只能在图库看到 png、
     * 永远找不到 glb 的入口（v4.2.2 真机反馈的实际缺陷）。
     */
    val threeD: ThreeDResultHolder.Result? = null,
    /**
     * 「源已不存在」：服务器上的原文件确认已被删除（/view 返回 **HTTP 404**）。
     *
     * 与「加载中」「已加载」构成图库条目的第三态：
     * - 加载中：模糊/转圈占位，保存不可用；
     * - 已加载：清晰原图，保存可用；
     * - 源已不存在：显示本机缓存的低清副本（不盖模糊），右上角「已删除」角标，
     *   保存可用但明确提示保存的是低清版。
     *
     * **只有 404 才会置 true**（见 `ComfyUIClient.isSourceMissingStatus`）：
     * 超时、断网、5xx 一律不置位 —— 地铁里打开图库不能满屏「已删除」。
     * 该标记随图库索引持久化，刷新合并时与本地旧值做逻辑或，
     * 防止 ComfyUI「只删文件不删 /history」把标记冲掉。
     */
    val sourceMissing: Boolean = false
)
