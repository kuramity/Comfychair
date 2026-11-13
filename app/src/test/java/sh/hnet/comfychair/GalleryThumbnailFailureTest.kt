package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * v4.6.0 图库加载优化的纯逻辑单测：
 *
 * 1. [ComfyUIClient.connectionFailureForHttpCode] —— 404 必须单独分类成 FILE_MISSING
 *    （"服务器上文件没了"），401/403 归 AUTHENTICATION，其余归 NETWORK。
 *    这是图库"失败占位可重试 + 文件不存在不再打网络"链路的判定核心。
 *
 * 2. 缩略图采样函数（computeSampleSize）的 512 上限回归：磁盘缩略图解码
 *    （readThumbFromDisk，v4.6.0 加了降采样）与网络链路共用这个函数。
 */
class GalleryThumbnailFailureTest {

    // ---------- 404 → FILE_MISSING ----------

    @Test
    fun `404 maps to FILE_MISSING`() {
        assertEquals(ConnectionFailure.FILE_MISSING, ComfyUIClient.connectionFailureForHttpCode(404))
    }

    @Test
    fun `auth codes map to AUTHENTICATION`() {
        assertEquals(ConnectionFailure.AUTHENTICATION, ComfyUIClient.connectionFailureForHttpCode(401))
        assertEquals(ConnectionFailure.AUTHENTICATION, ComfyUIClient.connectionFailureForHttpCode(403))
    }

    @Test
    fun `other codes map to NETWORK`() {
        assertEquals(ConnectionFailure.NETWORK, ComfyUIClient.connectionFailureForHttpCode(500))
        assertEquals(ConnectionFailure.NETWORK, ComfyUIClient.connectionFailureForHttpCode(403 + 404 - 403 + 1))
        assertEquals(ConnectionFailure.NETWORK, ComfyUIClient.connectionFailureForHttpCode(200))
    }

    // ---------- 缩略图 512 采样上限 ----------

    @Test
    fun `thumbnail decode caps long side at 512`() {
        // 服务端压缩预览仍是同分辨率（204MB PNG 的 preview 仍是几千像素边长），
        // 解码侧必须采样到 512，否则一格 180dp 的格子要展开几十 MB 位图
        val sampled = ComfyUIClient.computeSampleSize(longest = 4096, maxDimension = 512)
        // 4096/8 = 512，不超过上限
        assertEquals(8, sampled)
        assertEquals(true, 4096 / sampled <= 512)
    }

    @Test
    fun `thumbnail decode does not upscale small images`() {
        assertEquals(1, ComfyUIClient.computeSampleSize(longest = 512, maxDimension = 512))
        assertEquals(1, ComfyUIClient.computeSampleSize(longest = 300, maxDimension = 512))
    }

    @Test
    fun `thumbnail decode guarantees decoded side within limit`() {
        // 各尺寸下解码结果都不得超过上限（2048 边是 Z-Image/放大流的常见预览尺寸）
        for (longest in intArrayOf(513, 768, 1024, 1536, 2048, 3072, 6144)) {
            val sample = ComfyUIClient.computeSampleSize(longest, 512)
            val decoded = longest / sample
            assert(decoded <= 512) { "longest=$longest sample=$sample decoded=$decoded" }
        }
    }
}
