package sh.hnet.comfychair

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.lifecycle.AppLifecycleMonitor
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.ui.components.ConnectionAlertDialog
import sh.hnet.comfychair.ui.components.ParticleOverlay
import sh.hnet.comfychair.ui.components.ParticleTouchRouter
import sh.hnet.comfychair.ui.components.shared.ProvideFilamentEngine
import sh.hnet.comfychair.ui.navigation.MainNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.update.UpdateCleanupPolicy
import sh.hnet.comfychair.update.UpdateDialogHost
import sh.hnet.comfychair.update.UpdateNotifier
import sh.hnet.comfychair.update.UpdateRepository
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ServerUrlUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

/**
 * Container activity that hosts the main navigation graph.
 * Uses Jetpack Compose for UI with bottom navigation.
 */
class MainContainerActivity : ComponentActivity() {

    // Constants
    companion object {
        const val RESULT_REFRESH_NEEDED = 100
    }

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()
    private val imageToImageViewModel: ImageToImageViewModel by viewModels()
    private val imageToVideoViewModel: ImageToVideoViewModel by viewModels()

    // Credential storage — used to persist refreshed browser-session cookies
    private val credentialStorage by lazy { CredentialStorage(applicationContext) }

    // Launcher for BrowserAuthActivity — handles mid-session cookie refresh
    private val browserAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data
                ?.getStringExtra(BrowserAuthActivity.RESULT_COOKIES)
                .orEmpty()
            if (cookies.isNotEmpty()) {
                val serverId = ConnectionManager.currentServerId ?: return@registerForActivityResult
                val newCredentials = AuthCredentials.Cookie(cookies)
                credentialStorage.saveCredentials(serverId, newCredentials)
                ConnectionManager.updateCredentials(newCredentials)
                ConnectionManager.retrySingleAttempt(this@MainContainerActivity)
            }
        }
        // If cancelled or no cookies: leave the user in the current state;
        // the session-expired dialog remains dismissible via Return to Login.
    }

    // Activity result launchers
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_REFRESH_NEEDED -> {
                // Settings cleared/restored or workflows changed - restart activity fresh
                val intent = Intent(this, MainContainerActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            SettingsContainerActivity.RESULT_CONNECTION_CHANGED -> {
                // Connection settings changed - return to login screen
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
        }
    }

    // Lifecycle methods

    /**
     * 全屏粒子特效开关（v4.12.x）：默认 false 时既不组合 [ParticleOverlay]、
     * 也不转发触点坐标 —— 全屏描边圆环 + 飞散粒子的特效形态已被 2026-11-14 的
     * 新验收标准禁用，按钮内的 ripple 点阵皮肤（rippleFeedback）取而代之。
     * onCreate 读一次存成员变量，避免每次触摸都查 SharedPreferences。
     */
    private var particleOverlayEnabled = false

    /**
     * 主界面的导航控制器引用（返回键回调要用）。
     *
     * 返回键回调必须在 `onCreate` 里注册（此时还没 composition），而 NavHostController
     * 由 `setContent` 里的 `rememberNavController()` 创建 —— 所以先存字段、组合完成后回填。
     * 不自己 `NavHostController(this)` 新建：NavHost 会给控制器挂 Compose 导航器，
     * 直接复用 NavHost 正在用的那一个实例，才不会出现「两个控制器各有一套回退栈」。
     */
    private var navControllerRef: NavHostController? = null

    /**
     * 当前所在的导航路由（由 NavController 的 destination 变化回调维护）。
     * 只在返回键判定里用：判断「是不是还在起始 tab 上」。null = 导航图还没就绪。
     */
    private var currentRouteName: String? = null

    /**
     * 点击特效的坐标来源（v4.8.0；v4.12.x 起仅当粒子层开关打开时才转发）。
     *
     * **这个方法必须 `return super`**：它只读一眼 ACTION_DOWN 的坐标交给特效池，
     * 不消费、不拦截、不改写事件 —— Compose 的分发链与没有特效层时完全一致。
     *
     * v4.7.0 的写法是在全屏 Canvas 上挂 `pointerInput`：Compose 命中测试默认
     * `sharePointerInputWithSiblings = false`，最上层指针节点命中后**下层兄弟节点不再被命中**，
     * 于是按钮、下拉、输入框全部失灵。改到这一层读坐标就不会参与命中测试。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (particleOverlayEnabled && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("ParticleFx", "down ${ev.x},${ev.y}")
            }
            ParticleTouchRouter.dispatch(ev.x, ev.y)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 全屏粒子特效回退开关（v4.12.x）：默认 false，读一次供 dispatchTouchEvent 与挂载点共用
        particleOverlayEnabled = AppSettings.isParticleOverlayEnabled(this)

        // Guard check - redirect to login if not connected (unless in offline mode)
        // 离线态统一看连接状态：自动切换（网络断开）与强制离线都会置 OfflineConnected
        if (!ConnectionManager.isConnected && !ConnectionManager.isOffline) {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        // Initialize GalleryRepository for offline cache access
        GalleryRepository.getInstance().initialize(this)

        // Enable debug logging based on saved preference (must be early to capture init logs)
        DebugLogger.setEnabled(AppSettings.isDebugLoggingEnabled(this))
        DebugLogger.i("MainContainer", "onCreate - debug logging enabled")

        // Initialize the ViewModel (uses ConnectionManager internally)
        generationViewModel.initialize(this)

        // Set current server ID for per-server media scoping
        val serverId = ConnectionManager.currentServerId
        DebugLogger.d("MainContainer", "setCurrentServerId: ${serverId?.take(8) ?: "NULL"}...")
        MediaStateHolder.setCurrentServerId(serverId)

        // Set caching mode based on user preference
        val isMemoryFirst = AppSettings.isMemoryFirstCache(this)
        DebugLogger.d("MainContainer", "caching mode: ${if (isMemoryFirst) "memory-first" else "disk-first"}")
        MediaStateHolder.setMemoryFirstMode(isMemoryFirst, applicationContext)
        MediaCache.setMemoryFirstMode(isMemoryFirst)

        // Initialize MediaCache with context for image/video fetching
        MediaCache.ensureInitialized(applicationContext)

        // 内置更新模块（v4.6.0）：通知渠道 + 断点恢复 + 静默检查。
        // 三步全在 IO 协程：检查失败静默降级（硬性约束 20：不弹窗、不阻塞启动）；
        // 发现新版本时 publishAvailable，由 [UpdateDialogHost] 在主界面承接弹窗。
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateNotifier.ensureChannel(this@MainContainerActivity)
            // P2（复核 low）：已装版本必须用 PackageManager 读，不能用 BuildConfig。
            // BuildConfig 是编译期常量：装完新版本后旧进程可能还在跑，会把「已装好的
            // 版本」当成待下载/待更新的旧版，和 Worker 的口径也不一致。
            // 仓库接口沿用 Int 口径（longVersionCode 就是 versionCode 的 Long 扩展）。
            val installedCode = UpdateCleanupPolicy.installedVersionCode(this@MainContainerActivity)
            // R4 + R2：先看磁盘上有没有「校验通过且版本更高」的已下载包（有就直接待安装，
            // 不重新下载）；其余情况在冷启动时取消残留 Worker、清通知，绝不自动续传。
            UpdateRepository.restoreFromDisk(
                this@MainContainerActivity,
                installedCode.toInt()
            )
            // P2-10：改走 autoCheckIfDue —— 它自带离线短路（强制离线/断网时直接返回，
            // 不向更新服务器发任何请求）与 30 分钟节流；v4.18.0 起它只检查不下载（R2），
            // 且一个版本只主动弹一次（R1）。
            UpdateRepository.autoCheckIfDue(
                this@MainContainerActivity,
                installedCode.toInt()
            )
        }

        // Load saved media state before screens initialize
        // 4.3：这里原来是 runBlocking { loadFromDisk() } —— 主线程同步解码所有预览图、读回视频，
        // 冷启动会卡几百毫秒到数秒。现在投递到后台协程，setContent 立刻执行；
        // 屏幕等 MediaStateHolder.restored 变 true 后才组合（见 setContent 里的占位分支）。
        //
        // 5.2：闩锁按「本次恢复」判断，不再是一次性进程闩锁。必须在 setContent **之前**同步复位：
        // 同一进程里第二次创建本 Activity（登出→重新登录、切换服务器）时，上一轮留下的 true
        // 会让占位分支被跳过，而下面的 loadFromDisk 还在异步路上 —— 屏幕初始化一次性读媒体，
        // 读到的就是空（TextToImageViewModel.restoreLastGeneratedImage 不会重试）。
        MediaStateHolder.beginRestoreSession()
        if (isMemoryFirst) {
            // Memory-first: load everything from disk into memory
            DebugLogger.d("MainContainer", "loading media from disk (memory-first mode, async)")
            lifecycleScope.launch {
                MediaStateHolder.loadFromDisk(applicationContext)
            }
        } else {
            // Disk-first: just discover video promptIds (bytes read on-demand)
            DebugLogger.d("MainContainer", "discovering video promptIds (disk-first mode)")
            MediaStateHolder.discoverVideoPromptIds(applicationContext)
        }

        // Determine start destination based on active generation owner
        val startDestination = when (generationViewModel.generationState.value.ownerId) {
            TextToImageViewModel.OWNER_ID -> MainRoute.TextToImage.route
            ImageToImageViewModel.OWNER_ID -> MainRoute.ImageToImage.route
            TextToVideoViewModel.OWNER_ID -> MainRoute.TextToVideo.route
            ImageToVideoViewModel.OWNER_ID -> MainRoute.ImageToVideo.route
            else -> MainRoute.TextToImage.route
        }

        // ── 返回键：非起始 tab → 回起始 tab；起始 tab → 回连接页（不登出） ─────────
        //
        // 背景：登录页进主容器时用了 NEW_TASK + CLEAR_TASK（LoginScreen.launchMainContainer，
        // LoginScreen.kt:111-115），主容器成了任务栈里唯一的 Activity —— 系统默认返回是
        // finish()，直接掉回桌面。这里接管返回键，把它变成「回上一级界面」。
        //
        // 为什么不能复用 GenerationViewModel.logout()（GenerationViewModel.kt:1765-1774）：
        // 那是完整登出 —— JobRegistry.clearPersistedState + ConnectionManager.logout（清缓存、
        // 断连、置 reconnectBlocked）+ resetGenerationState。生成中按返回会把队列和状态一起清掉。
        // 本方法只做导航：连接不断、缓存不清、队列不动、生成状态不重置。
        val startRoute = startDestination
        onBackPressedDispatcher.addCallback(this) {
            when (backActionAt(tabIndexOfRoute(currentRouteName), tabIndexOfRoute(startRoute))) {
                // 不在起始 tab：切回起始 tab，不动连接、不动生成状态。
                BackAction.SwitchToStartTab -> {
                    // 复用导航图自己的回退栈语义（不另写一套 tab 跳转）：底栏的
                    // MainNavigationBar.navigateTo 一直是「popUpTo(起始页) + saveState」，
                    // 栈里恒为「起始页 + 当前 tab」两层，且起始页是**动态**的（按上次生成
                    // 任务的 ownerId 决定）。所以回到起始页 = 把栈弹到动态算出的 startRoute：
                    //   - 传 route 字符串而不是写死某个 tab，跟随真实起始页；
                    //   - saveState = true 与底栏 navigateTo 的 saveState 同口径 ——
                    //     被弹掉的 tab 下次点回去能恢复现场，已输入的参数不会丢。
                    val popSucceeded = navControllerRef?.popBackStack(
                        route = startRoute,
                        inclusive = false,
                        saveState = true
                    ) ?: false
                    if (!popSucceeded) {
                        // 导航图还没就绪（媒体恢复转圈那一瞬，currentRouteName 为 null），
                        // 或者起始页已不在栈里：不能把这次返回吞掉（用户会以为卡死），
                        // 按「回连接页」兜底，至少不会退到桌面。
                        returnToLoginKeepingQueue()
                    }
                }
                // 已经在起始 tab：回连接页。连接与队列都保留，只抑制一次自动重连。
                BackAction.ReturnToLogin -> returnToLoginKeepingQueue()
            }
        }

        val activity = this
        setContent {
            // 与 NavHost 共用同一个控制器（NavHost 的 navController 参数默认值就是它内部的
            // rememberNavController，这里显式提升出来，供 onCreate 注册的返回键回调使用）。
            // 必须是 setContent 顶层的 remember：若写在下面 Crossfade 的分支里，
            // 分支被回收时控制器会重建，回退栈就丢了。
            val navController = rememberNavController()
            // 组合完成后回填字段 + 挂 destination 监听（返回键判定要知道「当前在哪个 tab」）。
            // 用 DisposableEffect 而不是组合期直接赋值：副作用不落在组合阶段，
            // 且 Activity 销毁时摘掉监听，不让列表持有已销毁的 Activity。
            DisposableEffect(navController) {
                navControllerRef = navController
                currentRouteName = navController.currentDestination?.route
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    currentRouteName = destination.route
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose {
                    navController.removeOnDestinationChangedListener(listener)
                    if (navControllerRef === navController) {
                        navControllerRef = null
                        currentRouteName = null
                    }
                }
            }
            ComfyChairTheme {
                // Observe connection alert state from ConnectionManager (single source of truth)
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsStateWithLifecycle()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsStateWithLifecycle()
                val sessionExpired by ConnectionManager.sessionExpired.collectAsStateWithLifecycle()
                // 4.3：启动期媒体恢复完成信号。loadFromDisk 改为异步后，未就绪时只组合占位内容，
                // 否则各屏幕的 onInitialize 会在媒体还没进内存时读到 null，且之后不会再刷新
                val mediaRestored by MediaStateHolder.restored.collectAsStateWithLifecycle()

                // 等待上限（用户反馈「点连接后短暂转圈」的兜底）：到点仍未恢复就放行 UI，
                // 不让转圈无限挂着。**只放行、不取消**：loadFromDisk 仍在 lifecycleScope 里跑，
                // 恢复完成照样写进 MediaStateHolder，只是这一次不再等它。
                //
                // 取舍（已核对代码）：各页面在 onInitialize 里一次性读媒体
                // （TextToImageViewModel.restoreLastGeneratedImage 读一次、不重试），
                // 所以「超时之后才恢复」的那部分预览图本次进不了界面；能接受的理由是
                // 无限转圈更糟，且重新生成 / 重进页面 / 切工作流都会重新读到媒体。
                var mediaRestoreTimedOut by remember { mutableStateOf(false) }
                // 只在「尚未恢复」期间跑；mediaRestored 变 true 会重启本 effect，循环自然结束
                LaunchedEffect(mediaRestored) {
                    if (mediaRestored) return@LaunchedEffect
                    val startedAt = SystemClock.elapsedRealtime()
                    while (true) {
                        // 放行前再确认一次：恰好在这一拍恢复完成就不该记「超时」
                        if (MediaStateHolder.restored.value) return@LaunchedEffect
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        if (shouldGiveUp(elapsed, MEDIA_RESTORE_TIMEOUT_MS)) {
                            mediaRestoreTimedOut = true
                            DebugLogger.w(
                                "MainContainer",
                                "媒体恢复超过 ${MEDIA_RESTORE_TIMEOUT_MS}ms 未完成，先放行 UI（后台恢复继续）"
                            )
                            return@LaunchedEffect
                        }
                        delay(MEDIA_RESTORE_POLL_INTERVAL_MS)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    // v4.6.1 模块 B：外面包一层 Box，粒子覆盖层曾叠在导航内容之上。
                    // v4.12.x 起默认不挂载（新验收标准禁止全屏描边圆环 + 飞散粒子，
                    // 效果改由按钮内的 ripple 点阵皮肤承载）；开关打开时才组合旧特效层，
                    // 作为一键回退通道。ParticleOverlay 本身零触摸修饰符，不参与命中测试。
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 转圈 ↔ 正常 UI 之间交叉淡入淡出（180ms），不再硬切：
                        // 用户实测「极其短暂的转圈后直接跳到正常 UI」观感突兀
                        Crossfade(
                            targetState = !mediaRestored && !mediaRestoreTimedOut,
                            animationSpec = tween(durationMillis = MEDIA_RESTORE_FADE_MS),
                            label = "mediaRestoreFade"
                        ) { waitingForMedia ->
                            if (waitingForMedia) {
                                // 媒体尚未就绪（仅内存优先模式会出现，通常只有几十到几百毫秒）：
                                // 只铺主题背景 + 进度指示，不组合导航图 —— 屏幕初始化会一次性读媒体，
                                // 在读之前组合出来就再也补不上了。这里既不会崩，也不会露出半截空界面。
                                // 转圈上不加文字/百分比（用户没要求）。
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                // 内层再包一个 Box，保持粒子层压在导航内容之上的原有层叠关系
                                // （不依赖 Crossfade 内部容器的布局行为）
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 共享 Filament Engine（v4.5.0）：整个页面的 3D 内嵌预览与全屏查看器
                                    // 共用一个引擎，避免"点全屏时两个引擎并存"和"反复进出反复重建"。
                                    ProvideFilamentEngine {
                                        MainNavHost(
                                            generationViewModel = generationViewModel,
                                            imageToImageViewModel = imageToImageViewModel,
                                            imageToVideoViewModel = imageToVideoViewModel,
                                            onNavigateToSettings = { openSettings() },
                                            onNavigateToGallery = { openGallery() },
                                            onLogout = { logout() },
                                            startDestination = startDestination,
                                            navController = navController
                                        )
                                    }
                                    if (particleOverlayEnabled) {
                                        ParticleOverlay()
                                    }
                                }
                            }
                        }
                    }
                }

                // 内置更新弹窗（v4.6.0）：静默检查发现新版本时在这里承接。
                // onDismiss 只隐藏 UI，后台下载不受影响（见 UpdateDialog 的注释）。
                UpdateDialogHost()

                // Session-expired dialog for browser-based (Cookie) authentication.
                // Shown when the AuthInterceptor detects a 401/403 or SSO redirect.
                if (sessionExpired) {
                    AlertDialog(
                        onDismissRequest = { ConnectionManager.resetSessionExpired() },
                        title = { Text(stringResource(R.string.title_session_expired)) },
                        text = { Text(stringResource(R.string.msg_session_expired)) },
                        confirmButton = {
                            Button(onClick = {
                                ConnectionManager.resetSessionExpired()
                                val serverUrl = ServerUrlUtils.buildServerUrl(
                                    ConnectionManager.protocol,
                                    ConnectionManager.hostname,
                                    ConnectionManager.port
                                )
                                browserAuthLauncher.launch(
                                    BrowserAuthActivity.createIntent(activity, serverUrl)
                                )
                            }) {
                                Text(stringResource(R.string.button_reauthenticate))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                ConnectionManager.resetSessionExpired()
                                generationViewModel.logout()
                                finish()
                            }) {
                                Text(stringResource(R.string.action_return_to_login))
                            }
                        }
                    )
                }

                // Show connection alert dialog when connection fails
                connectionAlertState?.let { state ->
                    ConnectionAlertDialog(
                        failureType = state.failureType,
                        isReconnecting = isReconnecting,
                        onReconnect = {
                            ConnectionManager.retrySingleAttempt(this@MainContainerActivity)
                        },
                        onReturnToLogin = {
                            ConnectionManager.clearConnectionAlert()
                            generationViewModel.logout()
                            finish()
                        },
                        onDismiss = { ConnectionManager.clearConnectionAlert() }
                    )
                }
            }
        }
    }

    // Navigation helpers
    /**
     * Open the Settings activity
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsContainerActivity::class.java)
        settingsLauncher.launch(intent)
    }

    /**
     * Open the Gallery activity
     */
    private fun openGallery() {
        val intent = Intent(this, GalleryContainerActivity::class.java)
        startActivity(intent)
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        generationViewModel.logout()
        finish()
    }

    /**
     * 回连接页：**不登出、不清队列、不重置生成状态**（返回键专用路径）。
     *
     * 与 [logout] 的差别：不调 `generationViewModel.logout()`（不 clearPersistedState、
     * 不 ConnectionManager.logout、不 resetGenerationState），只做界面导航。
     * 队列与生成进度靠既有持久化保住：本 Activity 结束时 `onStop` 会
     * `saveGenerationState`，JobRegistry 每次状态变化都会落盘；重新进主容器时
     * `GenerationViewModel.initialize` 的 `JobRegistry.restoreState` + `restoreGenerationState`
     * 会把它们读回来。连接也一直留着（不断连、不清缓存）。
     *
     * 为什么必须先置自动重连抑制标志：连接页的 `LaunchedEffect(Unit)`
     * （LoginScreen.kt:446-458）会自动重连，一旦连上就 `launchMainContainer()` ——
     * 用户按返回反而被弹回主界面，等于返回键失灵。
     *
     * 一次性的时序保证（已逐行核对 LoginScreen.kt:446-458）：
     *   第 448 行先算 `shouldAutoConnect = ... && !ConnectionManager.isUserInitiatedLogout`；
     *   第 449 行**之后**才 `clearLogoutFlag()`。
     * 读在清之前，所以本次进连接页一定停在连接页、不会自动连回；标志随即被清掉，
     * 既不会残留成「以后永不自动连」，也不影响用户手动点「连接」。
     */
    private fun returnToLoginKeepingQueue() {
        // ConnectionManager.kt:410 的 isUserInitiatedLogout 是 private set，外部不能直赋；
        // 这里走专门的「只置标志、不做别的」入口（它与 logout() 共用同一个标志位）。
        ConnectionManager.suppressAutoConnectOnce()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    override fun onStop() {
        super.onStop()
        DebugLogger.d("MainContainer", "onStop - saving generation state")
        // Save generation state when going to background
        generationViewModel.saveGenerationState(this)

        // 把残留的脏媒体投递到应用级 IO 作用域落盘，本方法立即返回（**不是**同步写盘）：
        // 只在内存优先模式需要（磁盘优先模式写入即落盘），且「禁用媒体缓存」时跳过。
        // 预览本身有 800ms 防抖落盘兜底，这里通常已经没有脏数据；
        // 代价是进程若在写完前被杀，最多丢这一轮尚未落盘的预览（见 persistInBackground 的注释）。
        val isMemoryFirst = AppSettings.isMemoryFirstCache(this)
        val isCacheDisabled = AppSettings.isMediaCacheDisabled(this)
        DebugLogger.d("MainContainer", "onStop - memoryFirst=$isMemoryFirst, cacheDisabled=$isCacheDisabled")
        if (isMemoryFirst && !isCacheDisabled) {
            // 4.3：投递到应用级 IO 作用域后立即返回。原先是主线程 runBlocking { persistToDisk() }，
            // 退到后台要同步等全量 PNG 压缩 + 视频写盘。
            DebugLogger.d("MainContainer", "onStop - persisting media to disk (async)")
            MediaStateHolder.persistInBackground(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        val isGenerating = generationViewModel.generationState.value.isGenerating

        if (!ConnectionManager.isOffline) {
            // 有任务在跑：必须保证实时进度/预览链路可用
            if (isGenerating) {
                ConnectionManager.attemptSilentReconnect()
            } else if (ConnectionManager.isConnected &&
                ConnectionManager.webSocketState.value !is WebSocketState.Connected
            ) {
                // v4.6.1（模块 A4）：长时间后台后 WebSocket 往往已被系统/网络掐断，
                // 而 HTTP 层仍认为"已连接"。以前只在 isGenerating 时重连，空闲回前台
                // 什么都不做 —— 下次点生成虽然会 testConnection，但预览/进度链路是断的。
                ConnectionManager.attemptSilentReconnect()
            }

            // v4.6.1（模块 A4）→ G7（v4.16.10 重做）：后台期间 object_info 可能从未拉成功、
            // 拉失败被吞、或加载态卡在「正在加载」不收敛（E7）。旧实现这里用一份 ad-hoc 的
            // cacheEmpty 判断，有两个洞：① 只看四类核心列表是否**全空**，「只剩 UNET 空」
            // 这种部分空不触发补拉（G7「部分空」、G8 E6 复发的直接原因）；② 要求 !isLoading，
            // 于是「加载态卡住」反而永远不被救回。
            //
            // 现在统一交给 ConnectionManager.ensureModelListLoadedAfterResume()：
            // 判据抽成纯函数 decideModelListReload（有单测覆盖「全空需重拉 / 部分有值且加载中
            // 不重拉 / 失败态可重拉 / 有数据健康不打扰」），且它会**主动避开过渡态**——
            // 此刻若正在静默重连/在飞，这里判 SKIP，真正的重拉由 WebSocket onOpen（连接收敛后）
            // 兜底触发（G7 指令 3「不得在过渡态下发起而再次被吞」）。
            // 任何一条重拉路径都不会清空已有列表，也不会用空快照去校验、从而清掉用户已保存的
            // 模型/LoRA 选择（G7 指令 1/4；那层保护在 ValidationUtils.isUsableModelCache）。
            if (ConnectionManager.isConnected) {
                ConnectionManager.ensureModelListLoadedAfterResume()
            }
        }

        // Check if there's a pending generation that may have completed while in background
        if (isGenerating) {
            generationViewModel.checkServerForCompletion()
        }

        // v4.9.2：回前台/重连后自动检查更新（内部 30 分钟节流、不打断下载中的流程）。
        // 原先只有 onCreate 查一次，用户重连或长期挂后台回来都得手动去设置页点"检查更新"。
        // v4.18.0：自动路径只检查、不下载（R2），且受「一版只主动弹一次」配额约束（R1）。
        lifecycleScope.launch(Dispatchers.IO) {
            // 与 onCreate 同口径：已装版本用 PackageManager 读。BuildConfig 是编译期
            // 常量，装完新版本后旧进程里读到的是旧值（F2 复核：这条路径曾漏改）。
            val installedCode = UpdateCleanupPolicy.installedVersionCode(this@MainContainerActivity)
            UpdateRepository.autoCheckIfDue(
                this@MainContainerActivity,
                installedCode.toInt()
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles cleanup automatically via onCleared()
    }

    /**
     * 小窗/分屏失焦上报（v4.18.x 后台省流量）：小窗收成悬浮球时 Activity 仍 Resumed、
     * 不触发 onStop，只有这里能感知 —— 失焦 + 多窗口态 = 进入「可见但失焦」降级态
     * （AppLifecycleMonitor 合成 MULTI_WINDOW_UNFOCUSED），拿回焦点即恢复。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLifecycleMonitor.reportWindowFocus(hasFocus, isInMultiWindowMode)
    }

    /**
     * TopResumed 辅助信号上报（API 29+，minSdk 33 直接可用）：与窗口焦点同口径，
     * 只在多窗口态下并入失焦标记；非多窗口的叠页由 ProcessLifecycleOwner 的 ON_STOP 兜底。
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AppLifecycleMonitor.reportTopResumedChanged(isTopResumedActivity, isInMultiWindowMode)
    }
}

/**
 * 启动期「等媒体恢复」的上限（毫秒）。
 *
 * 到点仍未恢复就放行 UI，避免转圈无限挂着（用户实测：点连接后有一段非常短暂的转圈）。
 * 取 3 秒：内存优先模式下的恢复通常只有几十到几百毫秒，3 秒已经远超正常区间。
 */
internal const val MEDIA_RESTORE_TIMEOUT_MS = 3_000L

/** 等待期间「是否到点该放行」的轮询间隔（毫秒）。只影响判定精度，不影响上限本身。 */
internal const val MEDIA_RESTORE_POLL_INTERVAL_MS = 100L

/** 转圈 ↔ 正常 UI 的交叉淡入淡出时长（毫秒）。150-200ms 区间内取 180，避免硬切。 */
private const val MEDIA_RESTORE_FADE_MS = 180

/**
 * 「等待是否已到上限、该放行 UI」的判据（纯函数，可 JVM 单测）。
 *
 * 抽成纯函数的理由：真实的等待逻辑跑在 Compose 的 LaunchedEffect + 系统时钟上，单测碰不到；
 * 把「到点没到点」这一条判断独立出来后，边界行为（恰好等于上限算放行）可以被钉住。
 *
 * @param elapsedMs 已等待时长（毫秒）
 * @param timeoutMs 上限（毫秒）
 * @return 已等待时长达到/超过上限时返回 true（**包含等于**：到点即放行）
 */
internal fun shouldGiveUp(elapsedMs: Long, timeoutMs: Long): Boolean = elapsedMs >= timeoutMs

/**
 * 主界面按返回键时的两种落点。
 *
 * 抽成枚举（而不是回调里就地 if/else）是为了让「按返回该干什么」可以脱离 Android
 * 运行时单测 —— 判定本身是纯数据问题，跟 Activity / NavController 无关。
 */
internal enum class BackAction {
    /** 当前不在起始 tab：把回退栈弹回起始 tab。 */
    SwitchToStartTab,

    /** 当前就在起始 tab：回连接页（不登出、不清队列）。 */
    ReturnToLogin
}

/**
 * 底部导航的 tab 顺序，必须与 [MainNavigationBar] 里按钮的排列一致。
 *
 * 只用于把「当前路由 / 起始路由」折算成下标做同异比较，顺序本身不影响判定结果 ——
 * 但保持一致可以让日志和后续扩展（例如按方向做切换动画）不出错。
 * 注意：`Gallery` 是独立 Activity（FAB 打开），`TagDetail` 是二级页，都不在这里。
 */
internal val MAIN_TAB_ROUTES: List<String> = listOf(
    MainRoute.TextToImage.route,
    MainRoute.ImageToImage.route,
    MainRoute.TextToVideo.route,
    MainRoute.ImageToVideo.route,
    MainRoute.TagLibrary.route
)

/**
 * 把导航路由折算成 tab 下标（纯函数，可 JVM 单测）。
 *
 * @param route NavController 报的当前路由；null = 导航图还没就绪
 * @return tab 下标；null / 非 tab 路由（如 `tag_detail/{tagName}` 这种二级页）返回 -1。
 *         返回 -1 的语义是「不在起始 tab 上」—— 二级页按返回同样先回起始 tab，
 *         与需求「非起始 tab → 切回起始 tab」一致（且真实弹栈由 popBackStack 完成，
 *         二级页会被一并弹掉，不会卡在中间层）。
 */
internal fun tabIndexOfRoute(route: String?): Int {
    if (route == null) return NOT_A_TAB_INDEX
    val index = MAIN_TAB_ROUTES.indexOf(route)
    return if (index >= 0) index else NOT_A_TAB_INDEX
}

/** 「不在任何 tab 上」（含导航图未就绪、二级页）的下标哨兵值。 */
internal const val NOT_A_TAB_INDEX = -1

/**
 * 「当前该切 tab 还是回连接页」的判定（纯函数，可 JVM 单测）。
 *
 * - 当前 tab == 起始 tab（含同为「非 tab」的退化情形）→ [BackAction.ReturnToLogin]
 * - 其余（在别的 tab 上、在二级页上）→ [BackAction.SwitchToStartTab]
 *
 * 抽成纯函数的理由：真实回调跑在 `onBackPressedDispatcher` + NavController 上，JVM 单测
 * 碰不到；把这一条判定独立出来后，「在起始 tab 按返回不能只切 tab（那样会卡死）」
 * 和「在别的 tab 按返回不能直接回连接页」两侧都能被钉住。
 *
 * @param tabIndex 当前所在 tab 的下标（见 [tabIndexOfRoute]）
 * @param startIndex 起始 tab 的下标（见 [tabIndexOfRoute]）
 */
internal fun backActionAt(tabIndex: Int, startIndex: Int): BackAction =
    if (tabIndex == startIndex) BackAction.ReturnToLogin else BackAction.SwitchToStartTab
