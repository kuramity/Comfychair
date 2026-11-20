package sh.hnet.comfychair.connection

/**
 * WebSocket 监听代次计数器（NEW-2）。
 *
 * 递增是"读-改-写"：两个并发调用若各自读到同一个旧值，就会拿到**相同**的新代次，
 * 于是两个监听器都被认成"当前连接"——D1 的"旧回调必被判陈旧"随之失效，迟到回调
 * 又能改状态、又能排重连。
 *
 * 这不是理论上的竞态：`openWebSocket` 会从两个线程侧进入——
 * - 主线程：冷启动、切服务器、用户点重试（`ensureConnection` / `retrySingleAttempt` 的
 *   协程回调都在 Dispatchers.Main）；
 * - OkHttp dispatcher 线程：`attemptSilentReconnect` / `reconnectWebSocket` 的
 *   `testConnection` 回调直接调用 `openWebSocket`（`ComfyUIClient.tryConnection`
 *   用的是 `newCall(...).enqueue(...)`）。
 *
 * 因此递增必须在锁内完成；读侧（OkHttp 回调里的 `isStaleCallback`）只需一次
 * volatile 读，不必拿锁。抽成独立类是为了能在 JVM 单测里用多线程验证
 * "并发递增互不重复"。
 */
internal class WebSocketGenerationCounter {

    /** 递增与"取号"必须互斥，否则并发调用可能拿到同一个代次。 */
    private val lock = Any()

    /**
     * 当前代次。
     *
     * @Volatile：写侧在主线程 / OkHttp 回调线程，读侧在 OkHttp 回调线程，
     * 没有 happens-before 时可能读到旧值，把已作废的回调误判为当前连接。
     */
    @Volatile
    private var generation: Long = 0L

    /** 递增并返回新代次；并发调用返回的值两两不同。 */
    fun next(): Long = synchronized(lock) {
        generation = WebSocketRecoveryPolicy.nextGeneration(generation)
        generation
    }

    /**
     * 只在 [callbackGeneration] 仍是当前代次时才执行 [block]，**且"判代次 + 执行"在同一把锁内**
     * （NEW-2）。
     *
     * 只做"先检查、再执行"是不够的：监听器通过陈旧检查之后，另一线程（例如
     * `retrySingleAttempt` 先作废代次、再关闭 socket）可以在这两步之间把代次推进，
     * 于是旧 `onOpen` 会在 socket 已被关闭之后继续写 Connected、启动 keepalive ——
     * UI 显示已连接，而底层 socket 已经没了（`openWebSocket` 又会因 ALREADY_CONNECTED
     * 拒绝重建），直到进程重启才自愈。
     *
     * 锁与 [next] 共用，所以"作废代次"要么发生在 [block] 之前（本次直接跳过），
     * 要么发生在 [block] 之后（调用方会再写自己的终态），不存在"通过检查后又被作废"的窗口。
     *
     * @return true = 已执行；false = 该监听器已被作废，调用方必须直接返回
     */
    fun runIfCurrent(callbackGeneration: Long, block: () -> Unit): Boolean {
        synchronized(lock) {
            if (generation != callbackGeneration) return false
            block()
            return true
        }
    }

    /** 供回调判断自己是否已陈旧（volatile 读）。 */
    val current: Long
        get() = generation
}