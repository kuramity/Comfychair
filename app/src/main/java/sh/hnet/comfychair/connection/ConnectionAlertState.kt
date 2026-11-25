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

/**
 * 非阻塞的连接提示（顶部细横幅）。
 *
 * 与 [ConnectionAlertState]（模态对话框）的分工：
 * - **模态**只留给必须由用户处理的事（目前只有认证失效），且必须可关闭；
 * - 网络中断 / 传输停滞 / 自动切离线失败这些「等待就会自己恢复」的情况一律走这里，
 *   界面数据保持原样，后台继续退避重连 —— 用户不会因为弹窗挡屏而只能按返回逃离。
 *
 * 刻意保持为极小的值对象：UI 只需知道「现在有没有提示」，文案由界面按资源决定。
 */
data class ConnectionNotice(
    val failureType: ConnectionFailure
)
