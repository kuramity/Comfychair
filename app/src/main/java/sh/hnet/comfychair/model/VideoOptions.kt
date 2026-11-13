package sh.hnet.comfychair.model

/**
 * ResolutionSelector / TESpeedMiniMaxH3 的下拉选项回退值。
 *
 * 优先使用服务器 /object_info 的真实选项（ModelCache.aspectRatios / speedModes，
 * 按节点类型取，见 ConnectionManager.extractModelLists）；未连接服务器（列表为空）时
 * 用这里的回退值，保证下拉框始终可选。与 SamplerOptions 的回退思路一致。
 */
object VideoOptions {
    /** ResolutionSelector.aspect_ratio 的服务器选项 */
    val ASPECT_RATIOS = listOf(
        "1:1 (Square)",
        "2:3 (Portrait Photo)",
        "3:2 (Photo)",
        "3:4 (Portrait Standard)",
        "4:3 (Standard)",
        "9:16 (Portrait Widescreen)",
        "16:9 (Widescreen)",
        "21:9 (Ultrawide)"
    )

    /** TESpeedMiniMaxH3.mode 的服务器选项 */
    val SPEED_MODES = listOf("standard", "4-step LoRA", "8-step LoRA")
}
