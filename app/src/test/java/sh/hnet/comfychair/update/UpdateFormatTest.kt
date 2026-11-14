package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UpdateFormat] 的单元测试（v4.7.1）。
 *
 * 这些函数直接决定通知栏与弹窗显示的数字文案，算错会让用户看到
 * "剩余 0s 但进度不动"或"速度 15 B/s"这类误导信息，所以逐档断言边界。
 */
class UpdateFormatTest {

    @Test
    fun `formatBytes 四档单位`() {
        assertEquals("512 B", UpdateFormat.formatBytes(512))
        assertEquals("1 KB", UpdateFormat.formatBytes(1024))
        assertEquals("1.0 MB", UpdateFormat.formatBytes(1024L * 1024))
        assertEquals("1.50 GB", UpdateFormat.formatBytes(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("0 B", UpdateFormat.formatBytes(0))
    }

    @Test
    fun `formatSpeed 未测出速度时显示破折号`() {
        assertEquals("—", UpdateFormat.formatSpeed(0))
        assertEquals("—", UpdateFormat.formatSpeed(-1))
        assertEquals("2.0 MB/s", UpdateFormat.formatSpeed(2L * 1024 * 1024))
        assertEquals("870 KB/s", UpdateFormat.formatSpeed(870L * 1024))
    }

    @Test
    fun `formatEta 各时间档`() {
        assertEquals("—", UpdateFormat.formatEta(-1))
        assertEquals("0s", UpdateFormat.formatEta(0))
        assertEquals("45s", UpdateFormat.formatEta(45))
        assertEquals("1m0s", UpdateFormat.formatEta(60))
        assertEquals("3m20s", UpdateFormat.formatEta(200))
        assertEquals("1h05m", UpdateFormat.formatEta(3900))
    }

    @Test
    fun `percent 正常与未知总长`() {
        assertEquals(0, UpdateFormat.percent(0, 1000))
        assertEquals(50, UpdateFormat.percent(500, 1000))
        assertEquals(100, UpdateFormat.percent(1000, 1000))
        // 总长未知（服务器没给 Content-Range）→ 用不确定态，而不是显示 0%
        assertEquals(UpdateFormat.unknownPercent, UpdateFormat.percent(500, 0))
        assertEquals(UpdateFormat.unknownPercent, UpdateFormat.percent(500, -1))
    }

    @Test
    fun `percent 溢出被夹到 100`() {
        // 续传场景：本地 .part 比服务器报的总长还大（换包/坏文件），不能显示 137%
        assertEquals(100, UpdateFormat.percent(1370, 1000))
    }
}
