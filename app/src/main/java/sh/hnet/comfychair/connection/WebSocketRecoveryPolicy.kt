package sh.hnet.comfychair.connection

/**
 * WebSocket 状态机的**收尾规则**（条目 4.1：状态机可能永久卡在 Connecting）。
 *
 * 为什么单独抽成纯函数：真正的状态写入发生在 [ConnectionManager] 的 OkHttp 回调里，
 * 没有 Robolectric 就测不到；而"某个入口提前 return 导致过渡态没人收尾"恰恰是
 * 纯逻辑问题——把它抽出来后可以用假时钟在 JVM 单测里逐条钉死。
 *
 * 三条不变量（由 `WebSocketRecoveryPolicyTest` 钉住）：
 * 1. 连接失败必须落成 [WebSocketState.Failed]，不能停留在 [WebSocketState.Connecting]；
 * 2. 连接关闭必须落成 [WebSocketState.Disconnected]，与关闭码无关；
 * 3. 排重连之前若还停在过渡态（Connecting / Reconnecting），必须先收敛为 Disconnected。
 */
internal object WebSocketRecoveryPolicy {

    /** 陈旧 Connecting 的容忍上限：超过即视为僵尸连接，[ConnectionManager.openWebSocket] 会强制重建。 */
    const val CONNECTING_TIMEOUT_MS = 20_000L

    /** WebSocket 正常关闭码：不触发自动重连。 */
    const val CLOSE_NORMAL = 1000

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    /**
     * onFailure 之后的状态：**无论当前是什么，都要落成明确的失败终态**。
     *
     * 旧实现只 `scheduleReconnect()`、不写状态；而排重连在"离线 / 手动重连中 / 被阻断"
     * 时会提前 return，状态就永久停在 Connecting —— 这正是条目 4.1 的成因。
     *
     * @param current 当前状态（已处于完全相同的失败终态时保持原对象，避免多余写入）
     * @param responseCode HTTP 响应码；握手阶段被拒绝时 OkHttp 会带上它
     * @param reason 失败原因（异常 message，可能为 null）
     */
    fun afterFailure(current: WebSocketState, responseCode: Int?, reason: String?): WebSocketState {
        val failureType = failureTypeFor(responseCode)
        if (current is WebSocketState.Failed &&
            current.failureType == failureType &&
            current.reason == reason
        ) {
            return current
        }
        return WebSocketState.Failed(reason = reason, failureType = failureType)
    }

    /**
     * 握手阶段的失败归类：401/403 属于认证失败（重试无意义，需要用户重新登录），
     * 其余（含没有响应码的连接异常）一律归网络失败。
     */
    fun failureTypeFor(responseCode: Int?): ConnectionFailure {
        return if (responseCode == HTTP_UNAUTHORIZED || responseCode == HTTP_FORBIDDEN) {
            ConnectionFailure.AUTHENTICATION
        } else {
            ConnectionFailure.NETWORK
        }
    }

    /**
     * onClosed 的完整收尾结果：**无论 code 是多少都先把状态置 Disconnected**，
     * 再按关闭码决定是否自动重连。
     *
     * 旧实现只在 code == 1000 时写 Disconnected，其它码把收尾完全交给
     * [ConnectionManager.scheduleReconnect]，后者一旦提前 return 就永久卡在 Connecting。
     *
     * @param current 当前状态（已是 Disconnected 时保持原对象）
     * @param code WebSocket 关闭码
     */
    fun afterClosed(current: WebSocketState, code: Int): ClosedOutcome {
        val state = if (current is WebSocketState.Disconnected) {
            current
        } else {
            WebSocketState.Disconnected
        }
        return ClosedOutcome(state = state, reconnect = shouldReconnectAfterClose(code))
    }

    /** 关闭码不是正常关闭（1000）时才需要自动重连。 */
    fun shouldReconnectAfterClose(code: Int): Boolean {
        return code != CLOSE_NORMAL
    }

    /**
     * 排重连入口的过渡态收敛：若当前是 Connecting / Reconnecting，先落成 Disconnected。
     *
     * [ConnectionManager.scheduleReconnect] 的三个提前 return 分支（离线、手动重连中、
     * 被阻断）都不会再写状态，所以收敛必须发生在这些分支**之前**。
     */
    fun collapseTransitional(current: WebSocketState): WebSocketState {
        return when (current) {
            is WebSocketState.Connecting, is WebSocketState.Reconnecting -> WebSocketState.Disconnected
            else -> current
        }
    }

    /**
     * [ConnectionManager.openWebSocket] 的入口决策。
     *
     * 旧实现只判断"是不是 Connected / Connecting"就跳过，于是卡死的 Connecting 永远
     * 无法重建。这里引入 [connectingSinceMs]（elapsedRealtime 时间戳）：
     * - 未超过 [CONNECTING_TIMEOUT_MS]：仍在正常握手，跳过；
     * - 超过（或根本没有记录）：视为僵尸状态，强制重建。
     *
     * @param state 当前 WebSocket 状态
     * @param connectingSinceMs 本轮 Connecting 的起始时刻；[NO_CONNECTING_SINCE] 表示没有记录
     * @param nowMs 当前时刻（与 [connectingSinceMs] 同一时钟源）
     */
    fun openDecision(
        state: WebSocketState,
        connectingSinceMs: Long,
        nowMs: Long
    ): OpenWebSocketDecision {
        return when (state) {
            is WebSocketState.Connected -> OpenWebSocketDecision.ALREADY_CONNECTED
            is WebSocketState.Connecting -> {
                if (connectingSinceMs == NO_CONNECTING_SINCE) {
                    // 没有起始时刻的 Connecting 一定是旧路径遗留的僵尸态
                    OpenWebSocketDecision.REBUILD_STALE
                } else if (nowMs - connectingSinceMs < CONNECTING_TIMEOUT_MS) {
                    OpenWebSocketDecision.STILL_CONNECTING
                } else {
                    OpenWebSocketDecision.REBUILD_STALE
                }
            }
            else -> OpenWebSocketDecision.OPEN
        }
    }

    /**
     * 等待 WebSocket 握手完成的超时（[ConnectionManager.ensureConnection] /
     * [ConnectionManager.retrySingleAttempt] 里 `withTimeoutOrNull` 的参数）。
     */
    const val CONNECT_WAIT_TIMEOUT_MS = 10_000L

    /**
     * 超时分支的状态收敛（D4）。
     *
     * 旧实现只弹对话框/Toast、不写状态，`_webSocketState` 就永久停在 Connecting
     * （没有任何自治定时器会再来收尾）。这里把"等待超时仍没连上"收敛成明确的
     * 失败终态，并且：
     * - 已经落成 [WebSocketState.Failed]：保持原对象（可能带着更具体的握手失败原因，
     *   如 401 归类为认证失败），不覆盖；
     * - 世界在超时边缘变好了（已经 [WebSocketState.Connected] 或别的终态）：
     *   不把正常连接打回失败；
     * - 仍是 [WebSocketState.Connecting]：落 [ConnectionFailure.NETWORK] 失败。
     *
     * @param current 超时时刻读到的状态
     */
    fun afterConnectTimeout(current: WebSocketState): WebSocketState {
        return if (current is WebSocketState.Connecting) {
            WebSocketState.Failed(
                reason = "WebSocket 握手超时（${CONNECT_WAIT_TIMEOUT_MS}ms）",
                failureType = ConnectionFailure.NETWORK
            )
        } else {
            current
        }
    }

    /**
     * 代次递增（D1/D2 的可测部分）。
     *
     * 每一次"要换一条 socket"的动作（新建、主动关闭、强制重建）都必须让代次前进，
     * 使旧监听器手里那份 `callbackGeneration` 立刻作废。
     *
     * 单抽成函数是为了在 JVM 单测里钉住"递增后旧代次必被判陈旧"这条契约。
     * 真正的字段在 [ConnectionManager] 的 [WebSocketGenerationCounter] 里，**写侧并不只在主线程**：
     * 除了连接/断开流程（主线程），`attemptSilentReconnect` / `reconnectWebSocket` 的
     * `testConnection` 回调会在 OkHttp dispatcher 线程里直接调用 `openWebSocket` 并递增代次
     * （[ComfyUIClient.testConnection] 用的是 `newCall(...).enqueue(...)`）。
     * 读侧（`isStaleCallback`）同样在 OkHttp 回调线程 —— 写侧多线程 + 读侧无锁，
     * 正是计数器要加锁、字段要 @Volatile 的原因（见 [WebSocketGenerationCounter] 的类注释）。
     */
    fun nextGeneration(current: Long): Long = current + 1

    /**
     * 回调代次是否已作废（D1）。
     *
     * 只认"完全相等"：递增过的代次一律视为陈旧，迟到回调不得再改状态、不得再排重连。
     */
    fun isStaleCallback(callbackGeneration: Long, currentGeneration: Long): Boolean {
        return callbackGeneration != currentGeneration
    }

    /**
     * 该失败类型是否"可恢复"（D10）。
     *
     * 可恢复 = 自动重连/重试有机会成功（网络抖动、传输停滞），此时不应弹模态告警；
     * 认证失败（401/403）与服务器无效需要用户介入，才值得打断界面。
     */
    fun isRecoverableFailure(failureType: ConnectionFailure): Boolean {
        return when (failureType) {
            ConnectionFailure.NETWORK, ConnectionFailure.STALLED, ConnectionFailure.NONE -> true
            // 认证失败、服务器无效要用户介入；文件类失败（太大 / 已不存在）重试无意义
            ConnectionFailure.AUTHENTICATION,
            ConnectionFailure.INVALID_SERVER,
            ConnectionFailure.FILE_TOO_LARGE,
            ConnectionFailure.FILE_MISSING -> false
        }
    }

    /**
     * 退避重连到点时，本轮重连是否已经没有必要（NEW-1）。
     *
     * 退避期间可能已有别的入口把连接建好了：图库初始化会直接调
     * [ConnectionManager.openWebSocket]（它不取消退避中的 `pendingReconnectJob`），
     * 于是退避到点时 socket 已经 [WebSocketState.Connected]。
     *
     * 此时若继续"作废代次 + 关闭旧 socket"，本次关闭触发的 onClosed 会因代次已作废
     * 被丢弃，状态就永久停在 Connected —— 而 [ConnectionManager.openWebSocket] 会因
     * ALREADY_CONNECTED 拒绝重建，keepalive 又只往已被置空的 socket 发 ping，
     * 不重启 App 无法自愈。所以 Connected 时必须直接跳过本轮重连。
     */
    fun shouldSkipReconnect(current: WebSocketState): Boolean {
        return current is WebSocketState.Connected
    }

    /** "从未记录过 Connecting 起始时刻"的哨兵值。 */
    const val NO_CONNECTING_SINCE = 0L
}

/**
 * [WebSocketRecoveryPolicy.afterClosed] 的结果。
 *
 * @param state 收尾后要写入的状态（恒为 Disconnected）
 * @param reconnect 是否还需要排一次自动重连
 */
internal data class ClosedOutcome(
    val state: WebSocketState,
    val reconnect: Boolean
)

/** [WebSocketRecoveryPolicy.openDecision] 的决策结果。 */
internal enum class OpenWebSocketDecision {
    /** 已连接：直接返回 true，不重复建连。 */
    ALREADY_CONNECTED,

    /** 仍在 20 秒握手窗口内：直接返回 true，继续等回调。 */
    STILL_CONNECTING,

    /** 陈旧 Connecting：视为僵尸连接，关闭旧 socket 并强制重建。 */
    REBUILD_STALE,

    /** 其余状态（Disconnected / Failed / Reconnecting）：正常发起新连接。 */
    OPEN
}
