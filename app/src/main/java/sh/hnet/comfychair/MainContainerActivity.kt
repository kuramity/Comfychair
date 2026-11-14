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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import sh.hnet.comfychair.update.UpdateChecker
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
            val info = UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE, manualCheck = false)
            if (info != null) {
                UpdateRepository.publishAvailable(info)
            }
        }

        // Load saved media state before screens initialize
        if (isMemoryFirst) {
            // Memory-first: load everything from disk into memory
            DebugLogger.d("MainContainer", "loading media from disk (memory-first mode)")
            runBlocking {
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
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsState()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsState()
                val sessionExpired by ConnectionManager.sessionExpired.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    // v4.6.1 模块 B：外面包一层 Box，粒子覆盖层曾叠在导航内容之上。
                    // v4.12.x 起默认不挂载（新验收标准禁止全屏描边圆环 + 飞散粒子，
                    // 效果改由按钮内的 ripple 点阵皮肤承载）；开关打开时才组合旧特效层，
                    // 作为一键回退通道。ParticleOverlay 本身零触摸修饰符，不参与命中测试。
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
                                startDestination = startDestination
                            )
                        }
                        if (particleOverlayEnabled) {
                            ParticleOverlay()
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

        // Persist all dirty media to disk synchronously to ensure completion before process death
        // Only persist in memory-first mode (disk-first writes immediately, no persistence needed)
        // Also skip if media cache is disabled
        val isMemoryFirst = AppSettings.isMemoryFirstCache(this)
        val isCacheDisabled = AppSettings.isMediaCacheDisabled(this)
        DebugLogger.d("MainContainer", "onStop - memoryFirst=$isMemoryFirst, cacheDisabled=$isCacheDisabled")
        if (isMemoryFirst && !isCacheDisabled) {
            DebugLogger.d("MainContainer", "onStop - persisting media to disk")
            runBlocking {
                MediaStateHolder.persistToDisk(applicationContext)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val isGenerating = generationViewModel.generationState.value.isGenerating
        val cache = ConnectionManager.modelCache.value

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

            // v4.6.1（模块 A4）：后台期间 object_info 可能从未拉成功（或首次连接就被挂起），
            // 回前台时模型列表为空 → 生成时 VAE/CLIP/UNET 被写成空串 → 服务端 400。
            // 这里补一次主动拉取；内部有 isLoading 保护，不会并发堆请求。
            if (ConnectionManager.isConnected && !cache.isLoaded && !cache.isLoading) {
                ConnectionManager.refreshServerData()
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
