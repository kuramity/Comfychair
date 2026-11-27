package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.platform.AppLifecycleMonitor
import sh.hnet.comfychair.ui.components.ConnectionAlertDialog
import sh.hnet.comfychair.ui.components.ConnectionStatusBanner
import sh.hnet.comfychair.ui.components.shared.ProvideFilamentEngine
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/**
 * Container activity that hosts the Gallery screen.
 * Uses a simple layout with FAB to navigate back to generation.
 */
class GalleryContainerActivity : ComponentActivity() {

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()

    // Lifecycle methods
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Guard check - redirect to login if not connected (unless in offline mode)
        // 离线态统一看连接状态：自动切换（网络断开）与强制离线都会置 OfflineConnected
        if (!ConnectionManager.isConnected && !ConnectionManager.isOffline) {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        // Initialize the GenerationViewModel (uses ConnectionManager internally)
        generationViewModel.initialize(this)

        // Initialize MediaCache with context for image/video fetching
        MediaCache.ensureInitialized(applicationContext)

        setContent {
            ComfyChairTheme {
                // Observe connection alert state from ConnectionManager (single source of truth)
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsStateWithLifecycle()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsStateWithLifecycle()
                val connectionNotice by ConnectionManager.connectionNotice.collectAsStateWithLifecycle()
                val webSocketState by ConnectionManager.webSocketState.collectAsStateWithLifecycle()
                // P3：图库断线不弹模态、不切页；顶部细横幅提示，重连成功后自动消失
                val failedState = webSocketState as? WebSocketState.Failed
                val connectionBannerVisible = webSocketState is WebSocketState.Reconnecting ||
                    (failedState != null && failedState.failureType != ConnectionFailure.AUTHENTICATION) ||
                    connectionNotice != null

                Surface(modifier = Modifier.fillMaxSize()) {
                    // 共享 Filament Engine（v4.5.0）：图库的 3D 条目查看器与主页面
                    // 各自是独立 Activity，所以这里也要单独提供一份 —— 同一 Activity 内
                    // 的"3D 角标点开 + 全屏 Dialog"才能共用一个引擎。
                    ProvideFilamentEngine {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Scaffold(
                                floatingActionButton = {
                                    FloatingActionButton(
                                        onClick = { finish() },
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = stringResource(R.string.menu_generation),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            ) { paddingValues ->
                                GalleryScreen(
                                    generationViewModel = generationViewModel,
                                    galleryViewModel = galleryViewModel,
                                    onNavigateToSettings = { openSettings() },
                                    onLogout = { logout() },
                                    modifier = Modifier.padding(paddingValues)
                                )
                            }

                            ConnectionStatusBanner(
                                visible = connectionBannerVisible,
                                text = stringResource(R.string.msg_connection_interrupted),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                            )
                        }
                    }
                }

                // Show connection alert dialog when connection fails
                connectionAlertState?.let { state ->
                    ConnectionAlertDialog(
                        failureType = state.failureType,
                        isReconnecting = isReconnecting,
                        onReconnect = {
                            ConnectionManager.retrySingleAttempt(this@GalleryContainerActivity)
                        },
                        onReturnToLogin = {
                            ConnectionManager.clearConnectionAlert()
                            logout()
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
        startActivity(intent)
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        generationViewModel.logout()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 小窗/分屏失焦上报（v4.18.x 后台省流量）：与主容器同口径，小窗收球时进入降级态。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLifecycleMonitor.reportWindowFocus(hasFocus, isInMultiWindowMode)
    }
}
