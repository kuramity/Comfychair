package sh.hnet.comfychair

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketState
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
            UpdateRepository.restoreFromDisk(this@MainContainerActivity)
            // P2-10：改走 autoCheckIfDue —— 它自带离线短路（强制离线/断网时直接返回，
            // 不向更新服务器发任何请求）与 30 分钟节流；原先这里直连 checkForUpdate
            // 绕过了离线判断，onCreate/onResume 两条路径行为也不一致。
            UpdateRepository.autoCheckIfDue(BuildConfig.VERSION_CODE)
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

        val activity = this
        setContent {
            ComfyChairTheme {
                // Observe connection alert state from ConnectionManager (single source of truth)
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsStateWithLifecycle()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsStateWithLifecycle()
                val sessionExpired by ConnectionManager.sessionExpired.collectAsStateWithLifecycle()
                // 4.3：启动期媒体恢复完成信号。loadFromDisk 改为异步后，未就绪时只组合占位内容，
                // 否则各屏幕的 onInitialize 会在媒体还没进内存时读到 null，且之后不会再刷新
                val mediaRestored by MediaStateHolder.restored.collectAsStateWithLifecycle()

                Surface(modifier = Modifier.fillMaxSize()) {
                    // v4.6.1 模块 B：外面包一层 Box，粒子覆盖层曾叠在导航内容之上。
                    // v4.12.x 起默认不挂载（新验收标准禁止全屏描边圆环 + 飞散粒子，
                    // 效果改由按钮内的 ripple 点阵皮肤承载）；开关打开时才组合旧特效层，
                    // 作为一键回退通道。ParticleOverlay 本身零触摸修饰符，不参与命中测试。
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!mediaRestored) {
                            // 媒体尚未就绪（仅内存优先模式会出现，通常只有几十到几百毫秒）：
                            // 只铺主题背景 + 进度指示，不组合导航图 —— 屏幕初始化会一次性读媒体，
                            // 在读之前组合出来就再也补不上了。这里既不会崩，也不会露出半截空界面。
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
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
                                    startDestination = startDestination
                                )
                            }
                            if (particleOverlayEnabled) {
                                ParticleOverlay()
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
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateRepository.autoCheckIfDue(BuildConfig.VERSION_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles cleanup automatically via onCleared()
    }
}
