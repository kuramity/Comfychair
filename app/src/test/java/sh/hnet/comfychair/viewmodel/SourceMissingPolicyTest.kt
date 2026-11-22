package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「源已不存在」请求门的纯函数钉子（JVM 直测，不碰 Android）。
 *
 * 语义契约（见 SourceMissingPolicy.kt 文档）：
 * - 条目被确认服务器上已不存在（/view 返回 404 且标记已持久化，sourceMissing=true）时，
 *   **不再发起任何原图请求** —— 请求的结果只可能是又一次 404 或一次无谓的超时等待。
 * - 本函数只回答「要不要请求原图」，不负责判定请求结果；超时/断网/5xx 永远不会置位标记。
 *
 * 反证：把实现改成恒返回 true（永远请求）时，「已标记条目不再请求」两条测试变红；
 * 改成恒返回 false（永远不请求）时，「正常条目允许请求」两条变红。
 */
class SourceMissingPolicyTest {

    /** 构造最小图库条目（GalleryItem 的非默认参数只有 5 个）。 */
    private fun galleryItem(sourceMissing: Boolean): GalleryItem = GalleryItem(
        promptId = "p-001",
        filename = "ComfyUI_00001_.png",
        subfolder = "",
        type = "output",
        isVideo = false,
        sourceMissing = sourceMissing
    )

    /** 构造最小查看器条目（MediaViewerItem 与 GalleryItem 同构）。 */
    private fun viewerItem(sourceMissing: Boolean): MediaViewerItem = MediaViewerItem(
        promptId = "p-001",
        filename = "ComfyUI_00001_.png",
        subfolder = "",
        type = "output",
        isVideo = false,
        sourceMissing = sourceMissing
    )

    @Test
    fun `源正常的图库条目允许请求原图`() {
        assertTrue(
            "未标记 404 的条目必须照常请求原图，否则图库永远停在模糊占位",
            shouldRequestOriginal(galleryItem(sourceMissing = false))
        )
    }

    @Test
    fun `已确认源不存在的图库条目不再请求原图`() {
        assertFalse(
            "已确认 404 的条目再请求只会得到又一次 404 或白等一次超时",
            shouldRequestOriginal(galleryItem(sourceMissing = true))
        )
    }

    @Test
    fun `源正常的查看器条目允许请求原图`() {
        assertTrue(
            "查看器队列快照里的正常条目必须照常请求原图",
            shouldRequestOriginal(viewerItem(sourceMissing = false))
        )
    }

    @Test
    fun `已确认源不存在的查看器条目不再请求原图`() {
        assertFalse(
            "从图库点开一张已删除的图，查看器不得再去请求原图（白等一次超时）",
            shouldRequestOriginal(viewerItem(sourceMissing = true))
        )
    }

    @Test
    fun `两个数据类的默认构造都未标记源不存在`() {
        assertFalse(
            "GalleryItem 默认必须是正常条目：默认值错了会让所有新图都被当「已删除」",
            GalleryItem(
                promptId = "p-001", filename = "a.png", subfolder = "", type = "output", isVideo = false
            ).sourceMissing
        )
        assertFalse(
            "MediaViewerItem 默认必须是正常条目",
            MediaViewerItem(
                promptId = "p-001", filename = "a.png", subfolder = "", type = "output", isVideo = false
            ).sourceMissing
        )
    }
}
