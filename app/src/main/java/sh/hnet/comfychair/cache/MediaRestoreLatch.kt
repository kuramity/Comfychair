package sh.hnet.comfychair.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 启动期媒体恢复的「就绪闩锁」——按**每一次恢复**计状态，而不是整个进程只认一次。
 *
 * 旧实现是一只进程内一次性的布尔闩锁：恢复完成置 true 之后永不复位。于是同一进程里
 * 第二次创建 MainContainerActivity（登出→重新登录、切换服务器）时，onCreate 里的
 * loadFromDisk 是异步的，而界面在 setContent 时读到的仍是上一轮留下的 true ——
 * 占位分支被跳过、直接组合导航图，各屏幕 onInitialize 一次性读媒体
 * （TextToImageViewModel 只读一次、不重试），读到的就是还没恢复完的空媒体。
 *
 * 语义：
 * - [beginRestore]：开始一轮恢复，先**复位为未就绪**并返回本轮令牌；
 * - [markRestored]：只认**本轮**令牌 —— 上一轮迟到的完成回调不会把新一轮误置为就绪；
 * - [resetForNewSession]：新一轮恢复即将开始（新建 Activity / 切换服务器），
 *   同步复位为未就绪并作废所有在途令牌。必须在组合界面**之前**调用，
 *   否则首帧仍会按上一轮的「已就绪」组合；
 * - [settleReady]：清空媒体后调用（clearAll）。**没有在途恢复**时才直接收敛到「就绪」，
 *   置未就绪会让界面停在占位分支上永远等（清缓存 / 断线重连都不会再启动一轮恢复）；
 *   若有恢复在途，则保持未就绪、把置就绪留给该轮的 [markRestored] ——
 *   清空只是把内存清了，那一轮马上还会把数据写回来，提前置就绪会让界面先按空媒体组合
 *   （屏幕只读一次媒体、不会补刷），D3 的症状会从这条窄路径重现。
 * - [isCurrent]：写回数据前校验「本轮是否仍然有效」，供恢复线程在换服务器 / 新一轮开始后
 *   立刻停手，不把旧服务器的媒体写进当前档位。
 *
 * 跨线程可见性：
 * - 状态只有 [MutableStateFlow] 的 value 一个字段（内部 volatile），任意线程写入后立即可见，
 *   读侧（Compose 的 collectAsState、单测直接读）不会读到过期值；
 * - 「判令牌 + 置状态」这一对操作由 [gate] 锁保护，**必须成对原子执行**：
 *   [beginRestore] 可能在 IO 线程（`persistScope.launch { loadFromDisk(...) }`），
 *   [markRestored] 可能在主线程（`lifecycleScope.launch`），两者交错时若把比较与写入分开，
 *   上一轮迟到的完成会插在「新一轮已复位」之后把闩锁又置成就绪 —— 「只认本轮令牌」当场失效。
 *   锁内没有挂起点、也不等待任何回调，不存在死锁面；调用频率是「每轮恢复一次」，可忽略不计。
 */
internal class MediaRestoreLatch {

    private val _restored = MutableStateFlow(false)

    /** 媒体是否已就绪（本轮的恢复已完成）。 */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    /** 当前恢复轮次；只增不减，用来判废上一轮的完成回调。 */
    private val generation = AtomicLong(0)

    /** 轮次变更与就绪位写入的互斥锁：保证「判令牌 + 置状态」是一个原子步骤（见类注释）。 */
    private val gate = Any()

    /**
     * 在途恢复的令牌；null = 当前没有正在跑的恢复。
     *
     * 只在 [gate] 锁内读写（不是 volatile）：它唯一的读者是 [settleReady]，
     * 而 [settleReady] 与 [beginRestore] / [markRestored] 都在同一把锁内。
     */
    private var inFlightToken: Long? = null

    /** 开始一轮恢复：复位为未就绪，返回本轮令牌（交给 [markRestored]）。 */
    fun beginRestore(): Long = synchronized(gate) {
        val token = generation.incrementAndGet()
        inFlightToken = token
        _restored.value = false
        token
    }

    /**
     * 本轮恢复结束（成功 / 失败 / 无服务器都算）。非本轮令牌直接忽略。
     *
     * 比较与写入在同一把锁内完成：不会出现「读到本轮令牌 → 新一轮复位 → 再把状态写回就绪」。
     */
    fun markRestored(token: Long) {
        synchronized(gate) {
            if (generation.get() == token) {
                inFlightToken = null
                _restored.value = true
            }
        }
    }

    /** 本轮恢复是否仍然有效（写回数据前的校验，见类注释）。只读一次代次，不必拿锁。 */
    fun isCurrent(token: Long): Boolean = generation.get() == token

    /**
     * 新一轮恢复即将开始（新建 Activity / 切换服务器）：同步复位为未就绪，并作废在途令牌。
     *
     * 调用方必须紧接着启动一轮恢复（[MediaStateHolder.loadFromDisk] /
     * [MediaStateHolder.discoverVideoPromptIds]），否则界面会一直停在占位分支上。
     */
    fun resetForNewSession() {
        synchronized(gate) {
            generation.incrementAndGet()
            inFlightToken = null
            _restored.value = false
        }
    }

    /**
     * 清空媒体后收敛就绪（MediaStateHolder.clearAll）。
     *
     * - 没有在途恢复：直接置「就绪」—— 清空之后没有可等待的内容，
     *   置未就绪会让界面无限占位（清缓存 / 断线重连都不会再启动一轮恢复）；
     * - 有在途恢复（startup 恢复跑到一半时恰好走到 connect()→invalidateAll→clearAll）：
     *   **保持未就绪**，等该轮 [markRestored] 再置。清空只清了内存，那一轮马上还会把
     *   数据写回来；此刻置就绪，界面会先按空媒体组合导航图，而屏幕只读一次媒体、不会补刷。
     *   注意这里**不能**作废令牌：作废之后那一轮的完成会被忽略，闩锁就永远停在未就绪。
     */
    fun settleReady() {
        synchronized(gate) {
            if (inFlightToken != null) return
            _restored.value = true
        }
    }

    /** 当前是否已就绪（同步读：日志与单测用）。 */
    val isRestored: Boolean get() = _restored.value
}