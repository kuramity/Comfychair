package sh.hnet.comfychair.connection

/**
 * 离线自动切换的**判定逻辑**，刻意与 Android 无关，可在 JVM 单测里逐条钉死。
 *
 * 为什么单独抽出来：校园网抽风时"界面反复横跳"是这类状态机最容易写坏的地方，
 * 而它又完全由「连续 N 次探测结果的累计 + 冷却时间」决定——正好是可以纯函数化、
 * 用假时钟反复验证的部分。`ConnectionManager` 只负责把真实探测结果喂进来并执行
 * [StabilitySwitch] 指令，判定本身不碰任何 I/O。
 */

/** 一次连通性探测的结果。 */
internal enum class ProbeOutcome { SUCCESS, FAILURE }

/** 判定器所在的连接模式（与 [ConnectionState] 的在线/离线一一对应）。 */
internal enum class StabilityMode { ONLINE, OFFLINE }

/**
 * 判定器的状态快照（值语义，不可变）。
 *
 * @param mode 当前判定器认为所处的模式
 * @param consecutiveFailures ONLINE 模式下**当前连续**的失败次数（一旦出现成功即清零）
 * @param consecutiveSuccesses OFFLINE 模式下**当前连续**的成功次数（一旦出现失败即清零）
 * @param lastSwitchAtMs 最近一次模式切换发生的时刻（单调毫秒）；从未切换过时为 [NEVER_SWITCHED]
 */
internal data class StabilityState(
    val mode: StabilityMode = StabilityMode.ONLINE,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val lastSwitchAtMs: Long = NEVER_SWITCHED
) {
    companion object {
        /** "从未切换过"：冷却视为早已耗尽，首次切换不受冷却限制。 */
        const val NEVER_SWITCHED = Long.MIN_VALUE
    }
}

/** 判定器下达的切换指令。 */
internal enum class StabilitySwitch { TO_OFFLINE, TO_ONLINE }

/**
 * 一次判定的结果。
 *
 * @param nextState 喂入本次结果后应保存的新状态
 * @param switch 需要执行的切换；null 表示保持现状
 */
internal data class StabilityDecision(
    val nextState: StabilityState,
    val switch: StabilitySwitch?
)

/**
 * 自动切换的判定参数与纯逻辑。
 *
 * 三条不变量（由 [ConnectionStabilityTest] 钉住）：
 * 1. **连续 [REQUIRED_FAILURES] 次失败**才允许 ONLINE → OFFLINE；
 * 2. **连续 [REQUIRED_SUCCESSES] 次成功**才允许 OFFLINE → ONLINE；
 * 3. 每次切换后 [SWITCH_COOLDOWN_MS] 内不再接受任何切换，即使计数已经达标。
 *
 * 中间只要出现一次相反结果，对应计数就归零——这正是"抽风不横跳"的来源：
 * 时通时断的链路永远攒不齐连续两次同向结果。
 */
internal object ConnectionStability {

    /**
     * 连续失败达到该次数才切离线。
     *
     * 取 2 而非 1：单次失败常常是切 Wi-Fi、电梯/地铁里的一瞬丢包、服务端一次 GC 停顿，
     * 立刻切离线会让界面无故跳到"离线"，而用户随后任何一次操作都还能成功。
     * 取 3 则断网后要等 3 轮探测（约 30~60 秒）才进离线，体感太迟钝。
     */
    const val REQUIRED_FAILURES = 2

    /** 连续成功达到该次数才切回在线。理由同 [REQUIRED_FAILURES]：避免半死链路上假恢复。 */
    const val REQUIRED_SUCCESSES = 2

    /**
     * 切换后的冷却窗口。
     *
     * 这是"连续 N 次"之外的兜底：挡住刚切完模式就立刻被反向结果掀回去的抖动
     * （典型是刚回到在线、WebSocket 还在建连时出现的一次瞬时失败）。
     * 取值刻意小于常规探测间隔（[PROBE_INTERVAL_STEADY_MS]），
     * 这样链路真正恢复时，冷却不会给正常切换拖后腿。
     */
    const val SWITCH_COOLDOWN_MS = 15_000L

    /**
     * 刚进入离线后的快速探测间隔。
     *
     * 多数断线是短暂的（切网络、路由器重启），这段时间密一点探测能让用户几乎无感地回到在线。
     */
    const val PROBE_INTERVAL_FAST_MS = 10_000L

    /**
     * 稳定离线后的常规探测间隔。
     *
     * 30 秒的理由：长离线（用户在飞机/地铁上翻缓存）时，探测是纯功耗开销，
     * 30 秒一次空载 `/system_stats`（几百字节）的代价可以忽略；反过来，
     * 间隔拉到 60 秒以上会让"网络其实已经好了"要到几分钟后才发现，体验差。
     * 注意恢复还需要连续 [REQUIRED_SUCCESSES] 次成功，所以最坏恢复延迟约为
     * `PROBE_INTERVAL_STEADY_MS × REQUIRED_SUCCESSES`（≈60 秒）。
     */
    const val PROBE_INTERVAL_STEADY_MS = 30_000L

    /** 进入离线后按 [PROBE_INTERVAL_FAST_MS] 探测的次数，之后的探测转入常规间隔。 */
    const val FAST_PROBE_COUNT = 2

    /**
     * 单次探测的超时（毫秒）。
     *
     * 探测必须"轻"：短超时既省电，也避免一个半死的 TCP 连接把整轮探测卡住；
     * 离线态下我们宁可快速判失败、下一轮再来。
     */
    const val PROBE_TIMEOUT_MS = 4_000L

    /**
     * 喂入一次探测结果，得到新状态与（可能的）切换指令。
     *
     * @param state 当前状态
     * @param outcome 本次探测结果
     * @param nowMs 当前时刻（单调毫秒），仅用于冷却判定；测试可传任意假时钟
     */
    fun evaluate(state: StabilityState, outcome: ProbeOutcome, nowMs: Long): StabilityDecision {
        return when (state.mode) {
            StabilityMode.ONLINE -> evaluateOnline(state, outcome, nowMs)
            StabilityMode.OFFLINE -> evaluateOffline(state, outcome, nowMs)
        }
    }

    private fun evaluateOnline(state: StabilityState, outcome: ProbeOutcome, nowMs: Long): StabilityDecision {
        if (outcome == ProbeOutcome.SUCCESS) {
            // 成功即清零失败计数：抖动会重新从头累计，永远攒不齐"连续两次"
            return StabilityDecision(state.copy(consecutiveFailures = 0), null)
        }

        val failures = state.consecutiveFailures + 1
        // 未切换时计数照常累积：冷却一过，下一次失败就能立刻生效，不必重新数两遍
        val nextState = state.copy(consecutiveFailures = failures)

        return if (failures >= REQUIRED_FAILURES && isCooldownElapsed(state.lastSwitchAtMs, nowMs)) {
            StabilityDecision(
                nextState = StabilityState(mode = StabilityMode.OFFLINE, lastSwitchAtMs = nowMs),
                switch = StabilitySwitch.TO_OFFLINE
            )
        } else {
            StabilityDecision(nextState, null)
        }
    }

    private fun evaluateOffline(state: StabilityState, outcome: ProbeOutcome, nowMs: Long): StabilityDecision {
        if (outcome == ProbeOutcome.FAILURE) {
            // 失败即清零成功计数：半死链路（时通时断）不得被判定为"已恢复"
            return StabilityDecision(state.copy(consecutiveSuccesses = 0), null)
        }

        val successes = state.consecutiveSuccesses + 1
        val nextState = state.copy(consecutiveSuccesses = successes)

        return if (successes >= REQUIRED_SUCCESSES && isCooldownElapsed(state.lastSwitchAtMs, nowMs)) {
            StabilityDecision(
                nextState = StabilityState(mode = StabilityMode.ONLINE, lastSwitchAtMs = nowMs),
                switch = StabilitySwitch.TO_ONLINE
            )
        } else {
            StabilityDecision(nextState, null)
        }
    }

    /** 冷却是否已过（从未切换过视为已过）。 */
    fun isCooldownElapsed(lastSwitchAtMs: Long, nowMs: Long): Boolean {
        if (lastSwitchAtMs == StabilityState.NEVER_SWITCHED) return true
        return nowMs - lastSwitchAtMs >= SWITCH_COOLDOWN_MS
    }

    /**
     * 离线态下第 `probesSinceOffline` 次探测（从 0 开始）之前应等待的间隔。
     *
     * 先快后稳：前 [FAST_PROBE_COUNT] 次用 [PROBE_INTERVAL_FAST_MS]（短暂断线快速恢复），
     * 之后转入 [PROBE_INTERVAL_STEADY_MS]（长离线省电）。
     */
    fun nextProbeDelayMs(probesSinceOffline: Int): Long {
        return if (probesSinceOffline < FAST_PROBE_COUNT) {
            PROBE_INTERVAL_FAST_MS
        } else {
            PROBE_INTERVAL_STEADY_MS
        }
    }
}
