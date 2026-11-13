package sh.hnet.comfychair.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AssetReleaseGate] 的测试，重点是**中途取消**这条路径。
 *
 * 为什么单独把这条路径拎出来测：3D 查看器的"加载完再退出"是正常路径，谁来跑都没问题；
 * 真正会漏的是"加载到一半就退出"——界面已经销毁、模型却在几十毫秒后才交回来。
 * 那时候如果没人接住它，模型就变成不可达且未释放的孤儿（用户看不见，显存降不下来）。
 */
class AssetReleaseGateTest {

    // 1. 正常路径：没取消之前，加载回来的模型应该可以被接管
    @Test
    fun adoptSucceedsBeforeCancel() {
        val gate = AssetReleaseGate()
        assertTrue("未取消时应可接管", gate.tryAdopt())
        assertFalse(gate.isCancelled)
    }

    // 2. 中途取消：闸门关闭后到达的模型不能被接管（必须走销毁分支）
    @Test
    fun adoptFailsAfterCancel() {
        val gate = AssetReleaseGate()
        gate.cancel()
        assertTrue(gate.isCancelled)
        assertFalse("已取消时回调必须判为无主，由调用方销毁", gate.tryAdopt())
    }

    // 3. 幂等：销毁回调可能因重组触发多次，重复 cancel 不能把状态弄反
    @Test
    fun cancelIsIdempotent() {
        val gate = AssetReleaseGate()
        gate.cancel()
        gate.cancel()
        gate.cancel()
        assertTrue(gate.isCancelled)
        assertFalse(gate.tryAdopt())
    }

    // 4. 取消之后无论问多少次都必须是 false（防止某次误判为 true 而漏掉释放）
    @Test
    fun neverAdoptsAfterCancelEvenUnderRepeatedAsks() {
        val gate = AssetReleaseGate()
        gate.cancel()
        repeat(100) {
            assertFalse("第 $it 次询问仍应为 false", gate.tryAdopt())
        }
    }

    // 5. 并发：加载线程在问、主线程在取消，绝不能出现"取消之后还判为可接管"
    @Test
    fun concurrentCancelAndAdoptNeverAdoptsAfterCancel() {
        val gate = AssetReleaseGate()
        val pool = Executors.newFixedThreadPool(2)
        val cancelled = CountDownLatch(1)
        val violations = AtomicInteger(0)

        try {
            // 取消线程：立刻拉起闸门
            pool.execute {
                gate.cancel()
                cancelled.countDown()
            }
            // 加载线程：持续尝试接管；一旦观察到"已取消"之后又拿到 true，就是违规
            pool.execute {
                repeat(5_000) {
                    val adopted = gate.tryAdopt()
                    val wasCancelled = gate.isCancelled
                    if (wasCancelled && adopted) violations.incrementAndGet()
                }
            }
            cancelled.await(2, TimeUnit.SECONDS)
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        org.junit.Assert.assertEquals(
            "取消生效后仍返回可接管，说明可见性/原子性有问题",
            0,
            violations.get()
        )
    }
}
