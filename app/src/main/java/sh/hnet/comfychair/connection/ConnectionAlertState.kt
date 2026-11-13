package sh.hnet.comfychair.connection

/**
 * State holder for connection alert dialog.
 * Single source of truth owned by ConnectionManager.
 *
 * 只描述"连接出了什么问题"：是否进离线不再由用户在这个弹窗里选
 * （有缓存时 `ConnectionManager` 会自动切过去），所以这里也没有相关标志位。
 */
data class ConnectionAlertState(
    val failureType: ConnectionFailure
)
