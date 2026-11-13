package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片解码采样倍率的计算。
 *
 * 这段逻辑的缺陷是"静默"的：采样偏小一档不会报错，只会解出一张比预期大几倍的位图，
 * 然后把内存缓存挤爆 —— 表现是"生成一张放大图之后整个图库都变慢"。
 * 所以这里把边界逐个钉死。
 */
class DecodeSampleSizeTest {

    private val max = 4096

    @Test
    fun `不超过上限时不采样`() {
        assertEquals(1, ComfyUIClient.computeSampleSize(4096, max))
        assertEquals(1, ComfyUIClient.computeSampleSize(1024, max))
        assertEquals(1, ComfyUIClient.computeSampleSize(1, max))
    }

    @Test
    fun `解码后的长边真的不超过上限`() {
        // 这是核心不变式：任何输入下，longest / sampleSize 都必须 <= maxDimension
        val sizes = listOf(4097, 5000, 8191, 8192, 10000, 16384, 32768, 100000)
        for (longest in sizes) {
            val sample = ComfyUIClient.computeSampleSize(longest, max)
            val decoded = longest / sample
            assertTrue(
                "longest=$longest sample=$sample 解码后=$decoded 超过了上限 $max",
                decoded <= max
            )
        }
    }

    @Test
    fun `8192 的图会降到 4096 而不是停在 8192`() {
        // 旧实现（条件写成 longest / (sampleSize * 2) >= maxDimension）在这里会给出 sample=1，
        // 也就是按 8192 全尺寸解码 —— 8192×8192 的位图约 268 MB
        assertEquals(2, ComfyUIClient.computeSampleSize(8192, max))
        assertEquals(4096, 8192 / ComfyUIClient.computeSampleSize(8192, max))
    }

    @Test
    fun `采样倍率始终是二的幂`() {
        for (longest in listOf(5000, 9000, 20000, 65536)) {
            val sample = ComfyUIClient.computeSampleSize(longest, max)
            assertTrue("sample=$sample 不是 2 的幂", sample > 0 && (sample and (sample - 1)) == 0)
        }
    }

    @Test
    fun `上限为非正数时不采样`() {
        assertEquals(1, ComfyUIClient.computeSampleSize(100000, 0))
        assertEquals(1, ComfyUIClient.computeSampleSize(100000, -1))
    }

    @Test
    fun `缩略图尺寸下同样成立`() {
        // 列表缩略图用 512 上限
        val sample = ComfyUIClient.computeSampleSize(4096, ComfyUIClient.THUMBNAIL_DECODE_DIMENSION)
        assertEquals(8, sample)
        assertTrue(4096 / sample <= ComfyUIClient.THUMBNAIL_DECODE_DIMENSION)
    }
}
