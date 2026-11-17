package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.screens.galleryDisplayScaleForColumns

/**
 * 图库持久化缓存（v4.16.1）的纯逻辑层测试。
 *
 * 钉住三件事：统一档长边 720、显示降采样倍率（2/3/4 列）、
 * 512MB 上限的淘汰顺序（最旧先删、回到上限即停）。淘汰的文件系统部分
 * （listFiles / delete / lastModified）依赖真机，在自测报告里给 run-as 证据。
 */
class GalleryPersistCacheLogicTest {

    // ---------- 统一档长边 ----------

    @Test
    fun `统一档长边为720`() {
        // PM 裁定：只存一份 720（不按列数存两档），2 列直接用、3/4 列显示时降采样
        assertEquals(720, MediaCache.GALLERY_DECODE_DIMENSION)
    }

    // ---------- 显示降采样倍率（galleryDisplayScaleForColumns） ----------

    @Test
    fun `两列直接用统一档`() {
        assertEquals(1f, galleryDisplayScaleForColumns(2), 1e-6f)
    }

    @Test
    fun `三列缩到约480长边`() {
        // 720 × 2/3 ≈ 480
        assertEquals(480f, 720f * galleryDisplayScaleForColumns(3), 1e-3f)
    }

    @Test
    fun `四列缩到360长边`() {
        assertEquals(360f, 720f * galleryDisplayScaleForColumns(4), 1e-3f)
    }

    @Test
    fun `列数越界按边界处理`() {
        // 异常小值当作 2 列（直接用），异常大值当作最密档（1/2）
        assertEquals(1f, galleryDisplayScaleForColumns(1), 1e-6f)
        assertEquals(0.5f, galleryDisplayScaleForColumns(8), 1e-6f)
    }

    // ---------- 512MB 上限的淘汰顺序（pickEvictionCandidates） ----------

    /** MB → 字节 */
    private fun mb(v: Long) = v * 1024 * 1024

    @Test
    fun `未超上限_不淘汰`() {
        val files = listOf("a" to mb(100), "b" to mb(100))
        assertTrue(MediaCache.pickEvictionCandidates(files, totalBytes = mb(200), limitBytes = mb(512)).isEmpty())
    }

    @Test
    fun `恰好等于上限_不淘汰`() {
        val files = listOf("a" to mb(512))
        assertTrue(MediaCache.pickEvictionCandidates(files, totalBytes = mb(512), limitBytes = mb(512)).isEmpty())
    }

    @Test
    fun `超限_从最旧开始删`() {
        // 调用方先按 lastModified 升序传入（最旧在前）：700MB 超限，
        // 删 old1（剩 600 仍超）→ 删 old2（剩 500 回到上限内）→ 停，new 保留
        val files = listOf("old1" to mb(100), "old2" to mb(100), "new" to mb(500))
        val picked = MediaCache.pickEvictionCandidates(files, totalBytes = mb(700), limitBytes = mb(512))
        assertEquals(listOf("old1", "old2"), picked)
    }

    @Test
    fun `回到上限即停_不多删`() {
        // 删掉 old1（100MB）后 600-100=500 ≤ 512，old2 保留
        val files = listOf("old1" to mb(100), "old2" to mb(200), "new" to mb(300))
        val picked = MediaCache.pickEvictionCandidates(files, totalBytes = mb(600), limitBytes = mb(512))
        assertEquals(listOf("old1"), picked)
    }

    @Test
    fun `单文件超限_也必须删到自己以内`() {
        // 一张异常大的图（600MB 落盘失败前不可能，但防御目录被外部塞入大文件）
        val files = listOf("huge" to mb(600))
        val picked = MediaCache.pickEvictionCandidates(files, totalBytes = mb(600), limitBytes = mb(512))
        assertEquals(listOf("huge"), picked)
    }
}
