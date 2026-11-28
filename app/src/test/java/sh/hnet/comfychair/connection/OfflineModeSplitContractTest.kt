package sh.hnet.comfychair.connection

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B4.2-A 件二「OfflineModeController 提取」的源码级钉子。
 *
 * 为什么读源码：离线切换的**时序**（八步进入序 / 恢复序）是用户确认过的行为红线，
 * 迁移到 controller 后 JVM 单测既没有 Android Context 也拿不到真实 ConnectionManager
 * 运行态；而"八步序被重排 / 恢复序被换序 / 离线判定口径被改"在现有行为测试里不会红。
 * 因此按 [sh.hnet.comfychair.cache.MediaCacheConcurrencyWiringTest] 的同一口径：
 * 钉源码文本（折叠空白比对 + 顺序断言），钉住"关键语句存在且次序不变"。
 *
 * 口径：单测工作目录是模块根（`app/`）；controller 内通过 `manager.` 前缀访问
 * ConnectionManager 的 internal 协作面，钉子文本按该形态书写。
 */
class OfflineModeSplitContractTest {

    private val controllerPath =
        "src/main/java/sh/hnet/comfychair/connection/OfflineModeController.kt"
    private val managerPath =
        "src/main/java/sh/hnet/comfychair/connection/ConnectionManager.kt"
    private val uiStatePath =
        "src/main/java/sh/hnet/comfychair/connection/OfflineUiState.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    /** 把连续空白折叠成单个空格：语句比对对换行与缩进不敏感。 */
    private fun squash(text: String): String = text.replace(Regex("""\s+"""), " ")

    /** 取某函数声明起到下一个顶层声明（或文件尾）之间的源码段。 */
    private fun bodyOf(text: String, decl: String): String {
        val start = text.indexOf(decl)
        assertTrue("找不到函数声明：$decl", start >= 0)
        // 下一个 "    private fun" / "    internal fun" / "    fun" / 类结尾 —— 取其后 3000 字符内最近的顶层成员
        val nextDecl = Regex("""\n    (@Synchronized\n    )?(private |internal |public )?(suspend )?fun """)
            .findAll(text, start + decl.length)
            .firstOrNull()?.range?.first ?: text.length
        return squash(text.substring(start, nextDecl))
    }

    // ── ① controller 文件存在且含九函数签名 ──────────────────────────

    @Test
    fun `OfflineModeController 存在且含九函数签名`() {
        val controller = squash(source(controllerPath))

        val signatures = listOf(
            "fun enterOfflineMode( context: Context, server: Server? = null, " +
                "credentials: AuthCredentials = AuthCredentials.None, protocol: String? = null ): Boolean",
            "fun setForcedOffline(context: Context, forced: Boolean): Boolean",
            "fun enterOfflineModeInternal( context: Context?, serverId: String? = null, " +
                "notifyOnFailure: Boolean = true ): Boolean",
            "fun onOfflineSwitchFailed()",
            "fun resumeOnlineInternal(context: Context? = null): Boolean",
            "fun startOfflineProbeIfAllowed()",
            "fun stopOfflineProbe()",
            "fun loadFromOfflineCache(context: Context, serverId: String): Boolean",
            "fun hasOfflineCache(context: Context, serverId: String): Boolean"
        )
        for (sig in signatures) {
            assertTrue("OfflineModeController 缺函数签名：$sig", controller.contains(sig))
        }
    }

    // ── ② enterOfflineModeInternal 八步关键语句在 controller 内 ───────

    @Test
    fun `enterOfflineModeInternal 八步序关键语句齐全且次序不变（时序红线）`() {
        val controller = source(controllerPath)
        val body = bodyOf(controller, "fun enterOfflineModeInternal")

        // 步序按 controller 实现书写：不仅语句要存在，出现次序也不得重排
        // （与下方 resumeOnlineInternal 的恢复序断言同一 cursor 递增范式）。
        // 步 1：定 targetServerId（当前服务器 → 已选服务器，缺一即失败）
        val steps = listOf(
            (
                "val targetServerId = serverId ?: manager.currentServerId ?: " +
                    "ServerStorage(ctx).getSelectedServer()?.id"
                ) to "步1：targetServerId 解析链",
            // 步 2：无离线缓存不进离线（失败返回）
            "if (!hasOfflineCache(ctx, targetServerId))" to "步2：hasOfflineCache 检查",
            // 步 3：加载离线缓存
            "if (!loadFromOfflineCache(ctx, targetServerId))" to "步3：loadFromOfflineCache",
            // 步 4：取消待执行重连
            "manager.pendingReconnectJob?.cancel()" to "步4：pendingReconnectJob 取消",
            // 步 5：阻断重连 + 关 WS + 清横幅
            "manager.reconnectBlocked = true" to "步5：reconnectBlocked 置位",
            "manager.closeWebSocketInternal()" to "步5：closeWebSocketInternal",
            "manager.clearConnectionAlert()" to "步5：clearConnectionAlert",
            // 步 6：自动切换显示"正在重试"横幅，手动入口清提示
            "manager._connectionNotice.value = ConnectionNotice(ConnectionFailure.NETWORK)" to "步6：离线横幅设置",
            // 步 7：判定器同步到离线并记冷却起点
            (
                "manager.stabilityState = StabilityState( mode = StabilityMode.OFFLINE, " +
                    "lastSwitchAtMs = SystemClock.elapsedRealtime() )"
                ) to "步7：stabilityState 离线化",
            // 步 8：备好客户端（不开 WS）+ 按需启动周期探测
            "manager.ensureClientForSession(ctx)" to "步8：ensureClientForSession",
            "startOfflineProbeIfAllowed()" to "步8：startOfflineProbeIfAllowed"
        )

        var cursor = -1
        for ((snippet, desc) in steps) {
            val at = body.indexOf(snippet, cursor + 1)
            assertTrue(desc + "：语句缺失", at >= 0)
            assertTrue(desc + "：次序颠倒（$at <= $cursor）", at > cursor)
            cursor = at
        }
    }

    // ── ③ resumeOnlineInternal 恢复序关键语句（次序断言） ─────────────

    @Test
    fun `resumeOnlineInternal 恢复序关键语句齐全且次序不变`() {
        val controller = source(controllerPath)
        val body = bodyOf(controller, "fun resumeOnlineInternal")

        val steps = listOf(
            "resolveSession(ctx) ?: run" to "恢复序1：resolveSession 解析会话",
            "stopOfflineProbe()" to "恢复序2：停离线探测",
            "manager.forcedOffline = false" to "恢复序3：清强制离线标志",
            "manager.connect(" to "恢复序4：重连",
            "MediaCache.ensureInitialized(ctx)" to "恢复序5：MediaCache 上下文补挂",
            "JobRegistry.restoreState(ctx)" to "恢复序6：JobRegistry 状态恢复"
        )

        var cursor = -1
        for ((snippet, desc) in steps) {
            val at = body.indexOf(snippet, cursor + 1)
            assertTrue(desc + "：语句缺失", at >= 0)
            assertTrue(desc + "：次序颠倒（$at <= $cursor）", at > cursor)
            cursor = at
        }
    }

    // ── ③-补：ConnectionManager 侧转发面完整（外部调用方零改动） ─────

    @Test
    fun `ConnectionManager 保留四个对外转发方法且签名不变`() {
        val manager = squash(source(managerPath))
        val forwards = listOf(
            "fun enterOfflineMode( context: Context, server: Server? = null, " +
                "credentials: AuthCredentials = AuthCredentials.None, protocol: String? = null ): Boolean",
            "fun setForcedOffline(context: Context, forced: Boolean): Boolean",
            "fun loadFromOfflineCache(context: Context, serverId: String): Boolean",
            "fun hasOfflineCache(context: Context, serverId: String): Boolean"
        )
        for (sig in forwards) {
            assertTrue("ConnectionManager 对外转发方法签名丢失：$sig", manager.contains(sig))
        }
    }

    // ── ④ ConnectionManager 的 isOffline 转发 controller ──────────────

    @Test
    fun `ConnectionManager 保留 isOffline 且指向 controller 并构造 OfflineModeController`() {
        val manager = squash(source(managerPath))
        assertTrue(
            "ConnectionManager.isOffline 应转发 offlineController",
            manager.contains("val isOffline: Boolean get() = offlineController.isOffline")
        )
        assertTrue(
            "ConnectionManager 应构造 OfflineModeController（注入自身引用）",
            manager.contains("OfflineModeController(this)")
        )
        // 判定口径仍以 connectionState 为唯一状态源（controller 只做转发判定）
        val controller = squash(source(controllerPath))
        assertTrue(
            "controller.isOffline 判定口径应仍读 connectionState（OfflineConnected）",
            controller.contains(
                "val isOffline: Boolean get() = manager.connectionState.value is ConnectionState.OfflineConnected"
            )
        )
    }

    // ── ④-补：锁语义不变（锁仍落在 ConnectionManager 单例 monitor 上） ─

    @Test
    fun `controller 的异步切换路径锁在 ConnectionManager 单例上（并发语义不变）`() {
        val controller = source(controllerPath)
        val squashed = squash(controller)
        // enterOfflineModeInternal / resumeOnlineInternal 会被 reportProbeOutcome 的
        // scope.launch 在无外层锁的情况下调用 —— 方法体必须 synchronized(manager)
        // 与原 @Synchronized（锁 ConnectionManager 单例）同一 monitor。
        for (decl in listOf("fun enterOfflineModeInternal", "fun resumeOnlineInternal")) {
            val body = bodyOf(controller, decl)
            assertTrue("$decl 方法体缺 synchronized(manager)，锁语义会漂移", body.contains("synchronized(manager)"))
        }
        // 两个统一入口仍是 ConnectionManager 上的 @Synchronized（可重入，不与 controller 死锁）
        val manager = squash(source(managerPath))
        assertTrue(
            "ConnectionManager.enterOfflineMode 应保持 @Synchronized",
            manager.contains("@Synchronized fun enterOfflineMode(")
        )
        assertTrue(
            "ConnectionManager.setForcedOffline 应保持 @Synchronized",
            manager.contains("@Synchronized fun setForcedOffline(")
        )
        assertTrue(
            "controller 入口不得自带 @Synchronized（锁对象会变成 controller 实例）",
            !squashed.contains("@Synchronized fun enterOfflineMode(") &&
                !squashed.contains("@Synchronized fun setForcedOffline(")
        )
    }

    // ── ⑤ OfflineUiState 未被修改（纯判定层不并入） ───────────────────

    @Test
    fun `OfflineUiState 纯判定层未被并入且判定函数仍在`() {
        val uiState = squash(source(uiStatePath))
        assertTrue(
            "OfflineUiState.isOffline 纯判定函数丢失",
            uiState.contains("fun isOffline(state: ConnectionState): Boolean = state is ConnectionState.OfflineConnected")
        )
        // Compose 入口仍从 ConnectionManager.connectionState 派生
        assertTrue(
            "rememberIsOffline 的状态源被改",
            uiState.contains("ConnectionManager.connectionState.collectAsStateWithLifecycle()")
        )
    }
}
