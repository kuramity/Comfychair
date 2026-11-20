package sh.hnet.comfychair.connection

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

/**
 * 界面「现在是不是离线」的唯一判定口径。
 *
 * 「离线」有两种来源，界面上必须表现一致：
 * 1. 用户在设置页打开「强制离线」——走 [ConnectionManager.setForcedOffline]；
 * 2. 网络断开后由 [ConnectionManager] 自动切换过来。
 *
 * 两者都会把连接状态置为 [ConnectionState.OfflineConnected]，所以界面只认连接状态。
 *
 * **不要**用 `AppSettings.isOfflineMode(context)` 做界面判断：那只是用户的持久化意图，
 * 自动离线时它仍是 false，会出现「已经在离线却还能点生成」（点下去必然失败）。
 */
object OfflineUiState {

    /**
     * 连接状态是否为离线态。
     *
     * 纯函数、零 Android 依赖，判定规则可以在 JVM 上直接钉住。
     */
    fun isOffline(state: ConnectionState): Boolean = state is ConnectionState.OfflineConnected
}

/**
 * Compose 侧读取离线态：从 [ConnectionManager.connectionState] 派生，连接状态一变界面立刻刷新。
 *
 * 旧写法 `remember { AppSettings.isOfflineMode(context) }` 只在首帧读一次：
 * 切「强制离线」开关、或自动进/出离线时，界面都不刷新。
 */
@Composable
fun rememberIsOffline(): Boolean {
    val state by ConnectionManager.connectionState.collectAsStateWithLifecycle()
    return OfflineUiState.isOffline(state)
}
