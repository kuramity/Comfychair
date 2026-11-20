package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 钉住 4.2「落盘脏标记丢失更新」的修复语义：用版本号 + 原子条件删除。
 *
 * 旧实现是布尔脏标记（`Set<MediaKey>`）：写盘前取快照，写完后**无条件** remove(key)。
 * 写盘期间同一 key 又被写入时，那次 remove 会把新脏标记一起清掉，新内容永远不落盘。
 *
 * 这里用可控时序把「取快照 → 写盘 → 期间来新版本 → 清脏标记」拆开逐帧断言，
 * 不依赖协程调度器（本项目没有 kotlinx-coroutines-test 依赖），
 * 也不碰 Android（Bitmap / Context），纯 JVM 可跑。
 *
 * 5.2 追加：发号器改成**全局单调、永不回退**（见 [MediaPersistVersionOps] 的类注释）。
 * 旧实现按 key 各自从 1 计数，且在 evict / 清内存时把计数删掉，于是旧一轮写盘握着的
 * 「版本 1」会与新一轮重新从 1 开始的版本撞车，条件删除把未落盘的新内容清掉。
 */
class MediaPersistVersionsTest {

    private fun newIssued() = AtomicLong(0)
    private fun newDirty() = ConcurrentHashMap<String, Long>()

    @Test
    fun `版本号全局单调递增，不同 key 也不回退`() {
        val issued = newIssued()

        val a1 = MediaPersistVersionOps.nextVersion(issued)
        val b1 = MediaPersistVersionOps.nextVersion(issued)
        val a2 = MediaPersistVersionOps.nextVersion(issued)

        assertEquals(1L, a1)
        assertTrue("不同 key 也共用同一个发号器（全局单调），不是各自从 1 开始", b1 > a1)
        assertTrue(a2 > b1)
    }

    @Test
    fun `快照是副本，写盘期间的新登记不会改掉已取到的版本`() {
        val issued = newIssued()
        val dirty = newDirty()
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v1)

        val snapshot = MediaPersistVersionOps.snapshot(dirty)

        // 快照之后又来了一次写入（写盘期间的新内容）
        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v2)

        assertEquals("已取到的快照必须是写盘开始那一刻的版本", listOf("tti_preview" to v1), snapshot)
        assertEquals("脏账本本身要推进到新版本", mapOf("tti_preview" to v2), dirty)
    }

    @Test
    fun `写盘期间来了新版本时脏标记必须保留`() {
        val issued = newIssued()
        val dirty = newDirty()

        // t0：第一次写入，登记脏版本 v1
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v1)

        // t1：persistToDisk 取快照，随后开始写盘（PNG 压缩较慢）
        val snapshotVersion = MediaPersistVersionOps.snapshot(dirty).single().second

        // t2：写盘进行中，同一 key 又写入一次（连续出图 / 连续换图）
        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v2)
        assertTrue("前提：新版本必须比快照版本新", v2 > v1)

        // t3：这一轮写盘结束，按快照版本做条件清除 —— 必须失败
        val cleared = MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", snapshotVersion)

        assertFalse("写盘期间的更新不能被这一轮清掉（旧布尔脏标记正是在这里丢更新的）", cleared)
        assertEquals("脏标记应停在新版本上，等下一轮落盘", v2, dirty["tti_preview"])
    }

    @Test
    fun `没有并发写入时写完清掉脏标记`() {
        val issued = newIssued()
        val dirty = newDirty()
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v1)

        val snapshotVersion = MediaPersistVersionOps.snapshot(dirty).single().second
        val cleared = MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", snapshotVersion)

        assertTrue("内容没再变过，这一轮已经写全，脏标记该清掉", cleared)
        assertNull(dirty["tti_preview"])
    }

    @Test
    fun `内容已不在内存的分支同样只清自己那一版`() {
        // persistToDisk 里 bitmap == null / bytes == null（内容已被 evict）的分支也走条件删除
        val issued = newIssued()
        val dirty = newDirty()
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "itv_video", v1)
        val snapshotVersion = MediaPersistVersionOps.snapshot(dirty).single().second

        // 写盘期间：evict 之后又写入了新内容，登记新版本
        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "itv_video", v2)

        assertFalse(
            "evict 分支不能把写盘期间新登记的版本清掉",
            MediaPersistVersionOps.clearIfStillDirty(dirty, "itv_video", snapshotVersion)
        )
        assertEquals(v2, dirty["itv_video"])
    }

    @Test
    fun `evict 之后重新写入的版本号必须大于旧快照版本（否则旧一轮会误清新内容）`() {
        val issued = newIssued()
        val dirty = newDirty()

        // 第一轮：写入 v1 并取快照（写盘开始）
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v1)
        val staleSnapshotVersion = MediaPersistVersionOps.snapshot(dirty).single().second

        // 写盘期间内容被 evict：内容与脏标记都没了，但发号器**不回退**
        dirty.remove("tti_preview")

        // 同一 key 重新写入（用户又出了一张图）
        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v2)

        assertTrue("发号器必须全局单调：旧实现这里会重新从 1 开始", v2 > staleSnapshotVersion)
        assertFalse(
            "旧一轮的快照版本不能命中新一轮的脏标记",
            MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", staleSnapshotVersion)
        )
        assertEquals("新一轮的内容必须留到下一轮落盘", v2, dirty["tti_preview"])
    }

    @Test
    fun `清内存之后重新写入的版本号同样不回退`() {
        // clearMemoryState（模式切换 / clearAll）只清内容与脏标记，不动发号器
        val issued = newIssued()
        val dirty = newDirty()

        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "itv_video", v1)
        val staleSnapshotVersion = MediaPersistVersionOps.snapshot(dirty).single().second

        // clearMemoryState：dirtyVersions.clear()，发号器保留
        dirty.clear()

        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "itv_video", v2)

        assertTrue(v2 > staleSnapshotVersion)
        assertFalse(
            "清内存之后旧快照同样不能清新内容",
            MediaPersistVersionOps.clearIfStillDirty(dirty, "itv_video", staleSnapshotVersion)
        )
        assertEquals(v2, dirty["itv_video"])
    }

    @Test
    fun `多轮落盘：每轮只清自己那一版，最终账本为空`() {
        val issued = newIssued()
        val dirty = newDirty()

        // 第一轮：v1 登记后立刻取快照，期间没有新写入 → 清掉
        val v1 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v1)
        assertTrue(MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", v1))

        // 第二轮：v1 的快照在 v2 登记后才被清除 → 不清；v2 自己那一轮才清
        val v2 = MediaPersistVersionOps.nextVersion(issued)
        MediaPersistVersionOps.markDirty(dirty, "tti_preview", v2)
        assertFalse("v2 已登记，v1 不能清", MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", v1))
        assertTrue("v2 这一轮清掉", MediaPersistVersionOps.clearIfStillDirty(dirty, "tti_preview", v2))
        assertTrue(dirty.isEmpty())
    }
}