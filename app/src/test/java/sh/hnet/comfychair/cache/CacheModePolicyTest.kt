package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「内存优先 ↔ 磁盘优先」切换的清理范围。
 *
 * 重点是一条容易写坏、坏了又很难从界面看出来的规则：
 * **切到内存优先不得删磁盘缓存** —— `cacheDir/gallery_cache` 是离线读取唯一的数据源，
 * 删掉它，离线态（用户强制离线或网络断开自动切换）就什么都读不到了。
 */
class CacheModePolicyTest {

    @Test
    fun `切到内存优先不清任何一层`() {
        val layers = CacheModePolicy.layersToClear(enteringMemoryFirst = true)

        assertFalse("磁盘层是离线读取的数据源，切内存优先绝不能删", layers.disk)
        assertFalse("内存层里还留着已加载的画廊数据，切过去正好直接命中，没必要清", layers.memory)
    }

    @Test
    fun `切到磁盘优先清内存层但不碰磁盘层`() {
        val layers = CacheModePolicy.layersToClear(enteringMemoryFirst = false)

        assertTrue("内容此后由磁盘承载，内存里那份是冗余副本", layers.memory)
        assertFalse("磁盘层归离线读取用，任何模式切换都不动", layers.disk)
    }

    @Test
    fun `磁盘层在两种切换方向上都不被清理`() {
        // 回归：旧实现在切到内存优先时 deleteRecursively()，
        // 于是"开内存优先 → 离线无数据可读"。这里把两个方向都钉住。
        assertFalse(CacheModePolicy.layersToClear(enteringMemoryFirst = true).disk)
        assertFalse(CacheModePolicy.layersToClear(enteringMemoryFirst = false).disk)
    }
}
