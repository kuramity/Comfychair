package sh.hnet.comfychair.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络计量分类的单测（v4.18.x 阶段 C 的纯函数面）。
 *
 * [NetworkClassMonitor.classify] 是 `NET_CAPABILITY_NOT_METERED` 能力位 →
 * 网络类别的唯一翻译点：判反了会导致 WiFi 被断连 / 蜂窝被常连，两个方向都是事故。
 */
class NetworkClassMonitorTest {

    @Test
    fun `有NOT_METERED能力判不限量（WiFi）`() {
        assertEquals(
            BackgroundDegradationPolicy.NetworkClass.UNMETERED,
            NetworkClassMonitor.classify(hasNotMeteredCapability = true)
        )
    }

    @Test
    fun `无NOT_METERED能力判计量（蜂窝热点）`() {
        assertEquals(
            BackgroundDegradationPolicy.NetworkClass.METERED,
            NetworkClassMonitor.classify(hasNotMeteredCapability = false)
        )
    }
}
