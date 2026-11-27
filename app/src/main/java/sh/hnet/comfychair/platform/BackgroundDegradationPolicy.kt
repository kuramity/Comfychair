package sh.hnet.comfychair.platform

/**
 * 后台降级的**纯决策函数**（v4.18.x 后台省流量）。
 *
 * 「可见性 × 网络类型 × 是否有任务 × 用户开关」→ WS 策略与心跳间隔的**唯一权威表**。
 * [BackgroundDegradationCoordinator] 只执行这里的路由结果，分支判定不留第二份 ——
 * 与 GalleryTrashLogicTest 钉住 planPurgeRoute 是同一套纪律。
 *
 * 背景与量化目标（调研结论）：
 * - 修复前后台三条吃流量路径：/history 全量 5 分钟一次（首要元凶）、WS 双心跳 30 秒
 *   （应用层 ping + OkHttp 协议层 ping，合计约 120 次联网/小时）、断网重连无限重试；
 * - 小米标准红线是后台 ≤20 次联网/小时。降级后的心跳间隔按远低于红线的频率取值。
 */
object BackgroundDegradationPolicy {

    // ---------- 常量（决策表的数值面，Coordinator/ConnectionManager 都从这里取） ----------

    /** 进入降级态后的容忍期：给「切出去马上回来」的用户留时间，到点仍降级才执行动作 */
    const val DEGRADATION_DELAY_MS = 60_000L

    /** 前台心跳间隔（现状口径，不改变前台行为） */
    const val KEEPALIVE_FOREGROUND_MS = 30_000L

    /** 降级态 + 不限量网络（WiFi 等）：应用层心跳放宽到 2 分钟（60–300 秒区间的中点） */
    const val KEEPALIVE_DEGRADED_UNMETERED_MS = 120_000L

    /** 降级态 + 计量网络但用户开了「后台保持连接」：心跳按 4 分钟（任务书口径） */
    const val KEEPALIVE_DEGRADED_METERED_MS = 240_000L

    /** 蜂窝降级且有任务在跑时，WorkManager 轮询 /queue 的周期（任务书口径 15 分钟） */
    const val QUEUE_POLL_INTERVAL_MINUTES = 15L

    /** 图库 /history 拉取的条数上限（服务端支持 ?max_items=N，实测 server.py:1139） */
    const val HISTORY_MAX_ITEMS = 100

    // ---------- 类型 ----------

    /**
     * 网络计量类别。[UNKNOWN]（无网络 / 回调未就绪）在决策里**视同 METERED**：
     * 拿不准网络性质时按最省流量的方向走，宁可少连不多连。
     */
    enum class NetworkClass { UNMETERED, METERED, UNKNOWN }

    /**
     * WS 连接策略（决策表的输出面）：
     * - [KEEP_FULL_HEARTBEAT]：前台原样，双心跳 30 秒；
     * - [KEEP_RELAXED_HEARTBEAT]：降级但保持连接，双心跳放宽（间隔见 [keepaliveIntervalMs]）；
     * - [DROP_AND_POLL]：断开 WS，改用 WorkManager 每 15 分钟拉 /queue（有任务在跑）；
     * - [DROP_AND_IDLE]：断开 WS，不排轮询（没有自己的任务在跑，断开即静默）。
     */
    enum class WsPolicy { KEEP_FULL_HEARTBEAT, KEEP_RELAXED_HEARTBEAT, DROP_AND_POLL, DROP_AND_IDLE }

    // ---------- 决策表 ----------

    /**
     * 决策表核心：给定四因子返回 WS 策略。
     *
     * | 可见性      | 网络      | 任务在跑 | 后台保连开关 | → 策略                   |
     * |-------------|-----------|----------|--------------|--------------------------|
     * | 前台        | 任意      | 任意     | 任意         | KEEP_FULL_HEARTBEAT      |
     * | 降级        | UNMETERED | 有       | 任意         | KEEP_RELAXED_HEARTBEAT   |
     * | 降级        | UNMETERED | 无       | 任意         | DROP_AND_IDLE            |
     * | 降级        | METERED   | 任意     | 开           | KEEP_RELAXED_HEARTBEAT   |
     * | 降级        | METERED   | 有       | 关           | DROP_AND_POLL            |
     * | 降级        | METERED   | 无       | 关           | DROP_AND_IDLE            |
     * | 降级        | UNKNOWN   | 任意     | 任意         | 按 METERED 处理          |
     *
     * 设计依据（用户已定的三个决策）：
     * - 分网络：UNMETERED 后台**有任务时**保持 WS（结果推送零延迟），空闲时断开省电；
     * - 「后台保持连接」开关打开 = 蜂窝下也保持（心跳 4 分钟），用户知情选择；
     * - 任务例外：METERED 且开关关但有任务在跑 → 断 WS 但保留 15 分钟轮询，
     *   完成时发系统通知（体验底线：进度收不到、完成必须知道）。
     *
     * P4（后台长任务通知可靠性）：
     * - UNMETERED 行按 [hasActiveTask] 拆分 —— 修复前「WiFi 空闲也恒保持连接」，
     *   既违背省电目标，又让「回前台才有降级」的回归项不成立；
     * - 有任务的 KEEP_RELAXED 由 Coordinator 额外拉起**任务级**前台服务
     *   （[sh.hnet.comfychair.notify.GenerationKeepAliveService]）保护进程不被冻结；
     *   该服务不在决策表里，避免把「网络策略」与「进程保活」混成一张表。
     */
    fun decideWsPolicy(
        visibility: AppVisibility,
        network: NetworkClass,
        hasActiveTask: Boolean,
        keepConnectedWhenBackground: Boolean,
    ): WsPolicy {
        if (!visibility.isDegraded) return WsPolicy.KEEP_FULL_HEARTBEAT
        // UNKNOWN 视同 METERED（拿不准就按省流量的方向走）
        val metered = network != NetworkClass.UNMETERED
        return if (!metered) {
            // WiFi：只有还有本机任务时才保持连接（进度依赖 WS 推送）；
            // 空闲时断开，避免「连上 WiFi 就一直双心跳」的空转耗电。
            if (hasActiveTask) WsPolicy.KEEP_RELAXED_HEARTBEAT else WsPolicy.DROP_AND_IDLE
        } else if (keepConnectedWhenBackground) {
            WsPolicy.KEEP_RELAXED_HEARTBEAT
        } else if (hasActiveTask) {
            WsPolicy.DROP_AND_POLL
        } else {
            WsPolicy.DROP_AND_IDLE
        }
    }

    /**
     * 心跳间隔（应用层 ping；OkHttp 协议层 ping 取同值的秒数，见 Coordinator）。
     * 前台 30 秒（现状）；降级不限量 2 分钟；降级蜂窝（开关开）4 分钟。
     */
    fun keepaliveIntervalMs(network: NetworkClass): Long = when (network) {
        NetworkClass.UNMETERED -> KEEPALIVE_DEGRADED_UNMETERED_MS
        else -> KEEPALIVE_DEGRADED_METERED_MS
    }

    /**
     * 图库 /history 的周期刷新是否允许：**只有前台**。
     * 后台完全停掉 5 分钟循环（零流量），回前台 / 收到完成事件由调用方主动拉一次。
     */
    fun shouldPeriodicHistoryRefresh(visibility: AppVisibility): Boolean =
        visibility == AppVisibility.FOREGROUND
}
