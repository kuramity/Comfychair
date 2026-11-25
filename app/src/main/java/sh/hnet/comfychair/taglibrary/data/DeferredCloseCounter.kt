package sh.hnet.comfychair.taglibrary.data

/**
 * 「引用计数 + 延迟物理关闭」的纯逻辑门（修复方案 A）。
 *
 * **它解决的问题**：`TagDatabase.close()` 过去与在途查询之间零协调 ——
 * close 落在查询中间，下一次 `rawQuery` 就抛
 * `IllegalStateException: attempt to re-open an already-closed object`
 * （`performSearch` 没有 try/catch，直接进程级崩溃）；close 落在
 * 「最后一条查询之后、`searcher` 赋值之前」，则把已关闭的句柄固化进单例（毒化，
 * 进程存活期内每次搜索必崩且 retry 救不回来）。详见
 * `E:\Zcode\ZcodeProject\output\taglibrary-race-report.md` 第一/三节。
 *
 * **语义**：
 * - [acquire]：查询开始前加计数。关闭已被请求（正在排空）时**拒绝**放行并返回 false，
 *   调用方必须放弃本次查询 —— 这一道门是报告第 4 节指出的「不加就等于窗口只是缩小」。
 * - [release]：查询结束减计数，**必须写在 finally 里**。把计数减到 0 且关闭已请求时，
 *   由最后一个离开的查询执行 [physicalClose]。
 * - [requestClose]：请求关闭。无在途引用时立即执行 [physicalClose]（与修复前行为一致）；
 *   有在途引用时只置 `closeRequested` 标志，不阻塞 —— 所以 [close] 仍可在主线程调用。
 *
 * **线程安全**：所有状态转移在同一把监视器锁内完成，一次关闭只会交给一个线程执行
 * （「标志消费」与「计数归零」在同一临界区里判定）。[physicalClose] 一律在**锁外**调用：
 * 回调方 `TagDatabase.closeHandleNow` 要取 TagDatabase 自身的 monitor，而
 * `TagDatabase.open` 是「monitor → 本类锁」的顺序，若在锁内回调就是反向加锁、会死锁。
 *
 * 本类刻意不含任何 Android 依赖，好让计数与延迟关闭时机能在 JVM 单元测试里直接跑
 * （见 `DeferredCloseCounterTest`）。
 *
 * @param physicalClose 真正的物理关闭动作，保证「同一轮关闭请求内只被调用一次」
 */
class DeferredCloseCounter(private val physicalClose: () -> Unit) {

    private val lock = Any()

    /** 在途引用数：[acquire] 加、[release] 减。 */
    private var refs = 0

    /** 关闭已请求、正在等待在途查询排空（期间禁止新的 [acquire]）。 */
    private var closeRequested = false

    /** 是否有关闭请求正在排空中（上层 open/赋值要看的「禁入门」）。 */
    val isClosePending: Boolean
        get() = synchronized(lock) { closeRequested }

    /** 当前在途引用数。仅用于诊断与单元测试。 */
    val refCount: Int
        get() = synchronized(lock) { refs }

    /**
     * 进入一次查询。
     *
     * @return true = 放行，调用方**必须**在 finally 里 [release]；
     *         false = 关闭已在请求/排空中，调用方不得触碰句柄，且**不得**调用 [release]
     */
    fun acquire(): Boolean = synchronized(lock) {
        if (closeRequested) {
            false
        } else {
            refs++
            true
        }
    }

    /**
     * 退出一次查询。
     *
     * 若这是最后一个离开的引用且关闭已被请求，就在本方法里执行物理关闭。
     * 没有配对 [acquire] 的多余调用会被忽略（不吃成负数、也不误触发关闭）——
     * 这是报告第 4 节点名的「finally 写漏/写重」风险的兜底。
     */
    fun release() {
        val closeIsMine = synchronized(lock) {
            if (refs == 0) {
                false
            } else {
                refs--
                closeRequested && refs == 0
            }
        }
        if (closeIsMine) closeNow()
    }

    /**
     * 请求关闭。非阻塞。
     *
     * 无在途引用 → 立即物理关闭；有在途引用 → 只置标志，由最后一个 [release] 收尾。
     */
    fun requestClose() {
        val closeIsMine = synchronized(lock) {
            // 先置标志：之后所有 acquire 都被挡住，物理关闭期间不会有新查询挤进来
            closeRequested = true
            refs == 0
        }
        if (closeIsMine) closeNow()
    }

    /**
     * 执行物理关闭并放行「关闭已完成」。
     *
     * 两点都必须守住：
     * 1. **标志一定要清**。若 `closeRequested` 永挂，标签库从此不可用
     *    （既不能 acquire 也不能 open），比修复前的竞态更糟。
     * 2. **异常不外抛**。[requestClose] 的调用链是主线程
     *    （`retryInitialize` → `TagRepository.release` → `TagDatabase.close`），
     *    把一个关闭失败抛回主线程等于用修复引入新崩溃。
     *    句柄自身必须无条件作废，具体原因由 physicalClose 自己记录
     *    （见 `TagDatabase.closeHandleNow` 的 try/catch + Log.w）。
     */
    private fun closeNow() {
        try {
            physicalClose()
        } catch (e: Throwable) {
            // 刻意吞掉：见上面第 2 点。计数与标志仍然要在 finally 里归位。
        } finally {
            synchronized(lock) { closeRequested = false }
        }
    }
}
