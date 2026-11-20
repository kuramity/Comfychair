package sh.hnet.comfychair.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-27 的纯判定测试：WebSocket 二进制预览必须有字节上限。
 *
 * 修复前是无条件 `decodeByteArray`：服务端推来的预览帧想多大就多大，
 * 全尺寸解码一次就是上百 MB 位图，低内存设备直接被系统杀掉。
 */
class PreviewFramePolicyTest {

    @Test
    fun `正常大小的预览帧解码`() {
        assertTrue(PreviewFramePolicy.shouldDecode(1024))
        assertTrue(PreviewFramePolicy.shouldDecode(PreviewFramePolicy.MAX_PREVIEW_BYTES))
    }

    @Test
    fun `超过上限的预览帧被丢弃`() {
        assertFalse(PreviewFramePolicy.shouldDecode(PreviewFramePolicy.MAX_PREVIEW_BYTES + 1))
        assertFalse(PreviewFramePolicy.shouldDecode(64 * 1024 * 1024))
    }

    @Test
    fun `空帧与非法长度不解码`() {
        assertFalse(PreviewFramePolicy.shouldDecode(0))
        assertFalse(PreviewFramePolicy.shouldDecode(-1))
    }

    @Test
    fun `上限可按调用方覆盖`() {
        assertFalse(PreviewFramePolicy.shouldDecode(payloadBytes = 2048, maxBytes = 1024))
        assertTrue(PreviewFramePolicy.shouldDecode(payloadBytes = 1024, maxBytes = 1024))
    }

    @Test
    fun `预览解码长边上限为 1024`() {
        // 与 8MB 字节上限配套：单帧最大内存约 1024×1024×4 ≈ 4MB
        assertTrue(PreviewFramePolicy.DECODE_DIMENSION == 1024)
    }
}