package sh.hnet.comfychair.viewmodel

/**
 * 断线重连 / 回前台后对账（[GenerationViewModel.checkServerForCompletion]）的处置判定
 * （纯函数，供 JVM 单测钉住完整输入矩阵）。
 *
 * 背景：断线期间任务在服务器上继续跑。重连成功后用「/history 条目 + /queue 是否还在」
 * 三个信号就能把任务判进四种终态之一。原实现只认「有产物 = 完成」一种，
 * 其余情况一律静默 [GenerationViewModel.resetGenerationState] —— 断线期间失败的任务
 * 表现为「进度条无声消失」，既没有失败提示，JobRegistry 里的条目也没人清。
 *
 * @param outputsPresent /history 里这个 promptId 的 `outputs` 非空（有产物）
 * @param historyStatusStr /history 条目里 `status.status_str`（"error" = 服务器判定失败）
 * @param inQueue /queue 的 running/pending 里还有这个 promptId（队列查询失败时
 *   [GenerationViewModel.checkIfPromptInQueue] 保守地返回 true，不会误判丢失）
 */
internal enum class ReconcileOutcome {
    /** 有产物 → 派发完成事件（ImageGenerated / VideoGenerated）并收尾 */
    COMPLETED,

    /** 服务器在 history 里明确写了失败 → 按失败收尾（提示 + markFailed） */
    FAILED_REPORTED,

    /** 没产物也没报错、但还在服务器队列里 → 继续轮询等它跑完 */
    STILL_RUNNING,

    /** 没产物、没报错、也不在队列 → 任务丢失（服务器重启 / 队列被清），按失败收尾 */
    LOST,
}

/**
 * 对账判定本体：三个信号按优先级折叠成一个处置。
 *
 * 判定顺序有意为之：
 * 1. 产物优先 —— outputs 非空就是完成，status_str 在成功任务上无关紧要；
 * 2. 显式失败次之 —— status_str == "error" 时 outputs 必为空，直接判失败，
 *    不必再查一次队列；
 * 3. 在队列 = 还在跑（可能是排队等 GPU 的慢任务）；
 * 4. 都不是 = 丢了。
 */
internal fun resolveReconcileOutcome(
    outputsPresent: Boolean,
    historyStatusStr: String,
    inQueue: Boolean,
): ReconcileOutcome = when {
    outputsPresent -> ReconcileOutcome.COMPLETED
    historyStatusStr == "error" -> ReconcileOutcome.FAILED_REPORTED
    inQueue -> ReconcileOutcome.STILL_RUNNING
    else -> ReconcileOutcome.LOST
}
