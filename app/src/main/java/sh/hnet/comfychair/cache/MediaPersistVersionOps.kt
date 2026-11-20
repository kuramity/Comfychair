package sh.hnet.comfychair.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 媒体落盘的「版本号 + 脏标记」账本操作。
 *
 * 纯逻辑、零 Android 依赖，可以在 JVM 单测里用可控时序钉住语义。
 *
 * 为什么要有它（4.2 丢失更新）：
 * 旧实现用 `Set<MediaKey>` 当脏标记 —— 写盘前取快照，写完后**无条件** `remove(key)`。
 * 如果写盘期间同一个 key 又被写入一次（连续出图/连续换图），那次 `remove` 会把
 * **新**的脏标记一起清掉，新内容就再也不会落盘（下次启动读到的是旧图）。
 *
 * 版本号方案：
 * - [nextVersion] 每次写入向**全局单调发号器**取一个新号，这一份内容的版本是 v；
 * - [markDirty] 记下「尚未落盘的最新版本」；
 * - 写盘前用 [snapshot] 取 `(key, version)` 快照；
 * - 写盘结束用 [clearIfStillDirty] 做**原子条件删除**：只有当前脏版本仍等于快照版本才删。
 *   写盘期间来过新版本时 `remove(key, version)` 返回 false，脏标记保留，下一轮继续落盘。
 *
 * 版本号必须**全局单调、永不回退**（5.2 修正）：
 * 旧实现按 key 各自从 1 计数，并在 `evict` / `clearMemoryState` 时把该 key 的计数一起删掉。
 * 一旦旧一轮写盘还握着「版本 1」的快照，而 evict 之后重新写入又从 1 开始，
 * 那条 `remove(key, 1)` 就会命中**新一轮**的脏标记，把还没落盘的新内容清掉 ——
 * 这正是条件删除要防的丢失更新，只是被版本号回退从后门放了进来。
 * 现在发号器只有一个 [AtomicLong]，`evict` / 清内存都**不碰**它，
 * 于是「条件删除只能少清、不能多清」这条不变量在任何时序下都成立。
 */
internal object MediaPersistVersionOps {

    /**
     * 登记一次新写入，返回**全局唯一且单调递增**的版本号。
     *
     * 调用方必须**先**把新内容放进内存 Map、**再**调用本方法：反过来的话，
     * 落盘线程可能拿着新版本号读到上一份内容，把旧内容写进磁盘后又把脏标记清掉。
     *
     * @param issued 进程内共享的发号器；任何清理路径都不得重置它（见类注释）
     */
    fun nextVersion(issued: AtomicLong): Long = issued.incrementAndGet()

    /** 标记 key 的最新未落盘版本（见 [clearIfStillDirty] 的配对语义）。 */
    fun <K : Any> markDirty(dirtyVersions: ConcurrentHashMap<K, Long>, key: K, version: Long) {
        dirtyVersions[key] = version
    }

    /** 取写盘快照：拿到的是「此刻每个 key 待落盘的版本」。 */
    fun <K : Any> snapshot(dirtyVersions: ConcurrentHashMap<K, Long>): List<Pair<K, Long>> =
        dirtyVersions.entries.map { it.key to it.value }

    /**
     * 写盘完成后的原子条件删除。
     *
     * @return true = 脏标记已清除（这一轮之后没有新写入）；
     *         false = 写盘期间来过新版本（或版本号来自更早的一轮），脏标记保留，需要在下一轮继续落盘。
     */
    fun <K : Any> clearIfStillDirty(
        dirtyVersions: ConcurrentHashMap<K, Long>,
        key: K,
        version: Long
    ): Boolean = dirtyVersions.remove(key, version)
}