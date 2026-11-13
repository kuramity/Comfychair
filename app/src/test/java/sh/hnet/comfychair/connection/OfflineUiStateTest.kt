package sh.hnet.comfychair.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住界面「离线」的判定口径：**只认连接状态**。
 *
 * 背景：界面以前读 `AppSettings.isOfflineMode(context)`（用户「强制离线」的持久化意图），
 * 而网络断开后自动切过来的离线并不会把它置为 true ——
 * 结果是「已经在离线，生成按钮却还是亮的」，点下去必然失败。
 *
 * 判定的关键在于：两种离线来源在连接状态上**不可区分**（都是 OfflineConnected），
 * 所以只看连接状态，界面表现自然一致。
 */
class OfflineUiStateTest {

    private fun connected() = ConnectionState.Connected(
        serverId = "server-1",
        hostname = "10.0.2.2",
        port = 8188,
        protocol = "http",
        clientId = "client-1"
    )

    @Test
    fun `未连接不算离线`() {
        assertFalse("从未连上（登录页/断开）不是离线态，别把生成按钮误置灰", OfflineUiState.isOffline(ConnectionState.Disconnected))
    }

    @Test
    fun `在线不算离线`() {
        assertFalse(OfflineUiState.isOffline(connected()))
    }

    @Test
    fun `离线态一律算离线`() {
        assertTrue(OfflineUiState.isOffline(ConnectionState.OfflineConnected("server-1")))
    }

    @Test
    fun `强制离线与自动离线在判定上完全一致`() {
        // 两种来源进入的都是同一个状态值：判定函数拿不到、也不需要"是谁切的"这个信息，
        // 这正是"两种情形界面表现一致"的结构性保证。
        val forced = ConnectionState.OfflineConnected("server-1")
        val auto = ConnectionState.OfflineConnected("server-1")

        assertTrue(OfflineUiState.isOffline(forced))
        assertTrue(OfflineUiState.isOffline(auto))
    }

    @Test
    fun `状态一变判定结果立刻跟着变`() {
        // 界面用 rememberIsOffline() 从 connectionState 派生，
        // 这里用同一函数覆盖"切过去 / 切回来"两个方向，保证不是单向的。
        assertFalse(OfflineUiState.isOffline(connected()))
        assertTrue(OfflineUiState.isOffline(ConnectionState.OfflineConnected("server-1")))
        assertFalse(OfflineUiState.isOffline(connected()))
    }
}
