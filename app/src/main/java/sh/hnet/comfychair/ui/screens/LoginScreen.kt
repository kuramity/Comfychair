package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BrowserAuthActivity
import sh.hnet.comfychair.CertificateIssue
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.MainContainerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.ConnectionSplitButton
import sh.hnet.comfychair.ui.components.ServerDialog
import sh.hnet.comfychair.ui.components.ServerDropdown
import sh.hnet.comfychair.util.LoginBackgroundStore
import sh.hnet.comfychair.util.ServerUrlUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Connection states for the login screen
 */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    FAILED,
    CONNECTED
}

/**
 * Login screen composable - handles connection to ComfyUI server
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Server and credential storage
    val serverStorage = remember { ServerStorage(context.applicationContext) }
    val credentialStorage = remember { CredentialStorage(context.applicationContext) }

    // Check offline mode
    var isOfflineMode by remember { mutableStateOf(AppSettings.isOfflineMode(context)) }

    // State
    var servers by remember { mutableStateOf(serverStorage.getServers()) }
    var selectedServer by remember {
        mutableStateOf(
            serverStorage.getSelectedServerId()?.let { id -> servers.find { it.id == id } }
                ?: servers.firstOrNull()
        )
    }
    var connectionState by remember { mutableStateOf(ConnectionState.IDLE) }
    var warningMessage by remember { mutableStateOf<String?>(null) }
    var comfyUIClient by remember { mutableStateOf<ComfyUIClient?>(null) }
    var hasAutoConnected by remember { mutableStateOf(false) }

    // 连接页背景照片（用户自选，重编码后存在私有目录）
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showBackgroundMenu by remember { mutableStateOf(false) }
    val pickBackground = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (LoginBackgroundStore.save(context, uri)) {
                    backgroundBitmap = LoginBackgroundStore.load(context)
                } else {
                    Toast.makeText(context, R.string.login_background_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        backgroundBitmap = LoginBackgroundStore.load(context)
    }

    // 恢复默认外观：删掉私有目录里的背景图并同步界面
    fun resetBackground() {
        LoginBackgroundStore.clear(context)
        backgroundBitmap = null
        Toast.makeText(context, R.string.login_background_reset_done, Toast.LENGTH_SHORT).show()
    }

    // Dialog state
    var showServerDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<Server?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Browser auth state:
    // - pendingBrowserAuthServer: the server whose cookies are being captured
    // - pendingBrowserAuthUrl: non-null triggers BrowserAuthActivity launch (cleared after launch)
    // - retryAfterAuthServer: non-null triggers a retry connect after successful auth
    var pendingBrowserAuthServer by remember { mutableStateOf<Server?>(null) }
    var pendingBrowserAuthUrl by remember { mutableStateOf<String?>(null) }
    var retryAfterAuthServer by remember { mutableStateOf<Server?>(null) }

    // Launcher for BrowserAuthActivity.
    // Declared before attemptConnection so that the lambda captures only state vars
    // (all declared above), not the local function. attemptConnection sets state to
    // trigger the launch; LaunchedEffect below calls the launcher.
    val browserAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val server = pendingBrowserAuthServer ?: return@rememberLauncherForActivityResult
        pendingBrowserAuthServer = null

        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data
                ?.getStringExtra(BrowserAuthActivity.RESULT_COOKIES)
                .orEmpty()
            if (cookies.isNotEmpty()) {
                credentialStorage.saveCredentials(server.id, AuthCredentials.Cookie(cookies))
                retryAfterAuthServer = server  // Triggers retry via LaunchedEffect below
            } else {
                // User tapped Done before completing sign-in
                Toast.makeText(
                    context,
                    R.string.error_browser_auth_cancelled,
                    Toast.LENGTH_SHORT
                ).show()
                connectionState = ConnectionState.IDLE
            }
        } else {
            // User closed the WebView without authenticating
            connectionState = ConnectionState.IDLE
        }
    }

    // String resources
    val warningSelfSigned = stringResource(R.string.warning_self_signed_cert)
    val warningUnknownCa = stringResource(R.string.warning_unknown_ca)

    // Connection function.
    // isRetryAfterAuth: true when called after a successful WebView auth — prevents a
    // second WebView launch if the connection still fails (e.g. wrong cookies).
    // When browser auth is needed, sets pendingBrowserAuth* state instead of calling
    // the launcher directly (avoids a forward-reference to browserAuthLauncher).
    fun attemptConnection(server: Server, isRetryAfterAuth: Boolean = false) {
        connectionState = ConnectionState.CONNECTING
        warningMessage = null

        scope.launch {
            // Load credentials for the server
            val credentials = credentialStorage.getCredentials(server.id, server.authType)

            // For BROWSER auth with no saved cookies, skip the connection test and go
            // straight to the WebView so the user can sign in before we try the server.
            if (server.authType == AuthType.BROWSER
                && credentials is AuthCredentials.None
                && !isRetryAfterAuth
            ) {
                pendingBrowserAuthServer = server
                pendingBrowserAuthUrl =
                    ServerUrlUtils.buildServerUrl("https", server.hostname, server.port)
                connectionState = ConnectionState.IDLE
                return@launch
            }

            val client = ComfyUIClient(
                context.applicationContext,
                server.hostname,
                server.port,
                credentials
            )
            comfyUIClient = client

            // Test connection using suspendCoroutine
            val result = suspendCoroutine { continuation ->
                client.testConnection { success, errorMessage, certIssue, _ ->
                    continuation.resume(Triple(success, errorMessage, certIssue))
                }
            }

            val (success, errorMessage, certIssue) = result

            if (success) {
                connectionState = ConnectionState.CONNECTED

                // Handle certificate warnings
                val navigateDelay = when (certIssue) {
                    CertificateIssue.SELF_SIGNED -> {
                        warningMessage = warningSelfSigned
                        1000L
                    }
                    CertificateIssue.UNKNOWN_CA -> {
                        warningMessage = warningUnknownCa
                        1000L
                    }
                    CertificateIssue.NONE -> 500L
                }

                delay(navigateDelay)

                // Establish connection via ConnectionManager
                val detectedProtocol = client.getWorkingProtocol() ?: "http"

                // Save selected server
                serverStorage.setSelectedServerId(server.id)

                ConnectionManager.connect(
                    context = context.applicationContext,
                    serverId = server.id,
                    hostname = server.hostname,
                    port = server.port,
                    protocol = detectedProtocol,
                    credentials = credentials
                )

                // Navigate to main activity
                val intent = Intent(context, MainContainerActivity::class.java)
                context.startActivity(intent)
            } else if (server.authType == AuthType.BROWSER && !isRetryAfterAuth) {
                // Connection failed with browser auth and WebView hasn't been tried yet.
                // The server may be reachable but behind an SSO that blocked the request.
                val protocol = client.getWorkingProtocol() ?: "https"
                pendingBrowserAuthServer = server
                pendingBrowserAuthUrl =
                    ServerUrlUtils.buildServerUrl(protocol, server.hostname, server.port)
                connectionState = ConnectionState.IDLE
            } else {
                connectionState = ConnectionState.FAILED

                // 自动切换：有可用缓存就直接进离线，不再弹窗询问用户
                // （无缓存时不进离线——进去也是空白，明确报连接失败即可）
                val enteredOffline = if (ConnectionManager.hasOfflineCache(context, server.id)) {
                    ConnectionManager.enterOfflineMode(
                        context = context.applicationContext,
                        server = server,
                        credentials = credentials,
                        protocol = client.getWorkingProtocol()
                    )
                } else {
                    false
                }

                if (enteredOffline) {
                    connectionState = ConnectionState.CONNECTED
                    delay(500)

                    // Save selected server
                    serverStorage.setSelectedServerId(server.id)

                    // Navigate to main activity (offline: reads from cache only)
                    val intent = Intent(context, MainContainerActivity::class.java)
                    context.startActivity(intent)
                    return@launch
                }

                // No cache available (or offline switch failed) - show error Toast
                errorMessage?.let { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }

                delay(2000)
                connectionState = ConnectionState.IDLE
            }
        }
    }

    // Launch BrowserAuthActivity when attemptConnection signals it via state
    LaunchedEffect(pendingBrowserAuthUrl) {
        val url = pendingBrowserAuthUrl ?: return@LaunchedEffect
        pendingBrowserAuthUrl = null  // Clear to prevent re-trigger on recomposition
        browserAuthLauncher.launch(BrowserAuthActivity.createIntent(context, url))
    }

    // Retry connection after successful browser auth (triggered by launcher result handler)
    LaunchedEffect(retryAfterAuthServer) {
        val server = retryAfterAuthServer ?: return@LaunchedEffect
        retryAfterAuthServer = null
        attemptConnection(server, isRetryAfterAuth = true)
    }

    // Offline connection function - loads from cache instead of connecting to server
    fun attemptOfflineConnection(server: Server) {
        connectionState = ConnectionState.CONNECTING

        scope.launch {
            val credentials = credentialStorage.getCredentials(server.id, server.authType)

            // 走统一入口：加载缓存 + 置 OfflineConnected + 关后台 WebSocket
            val success = ConnectionManager.enterOfflineMode(
                context = context.applicationContext,
                server = server,
                credentials = credentials
            )

            if (success) {
                connectionState = ConnectionState.CONNECTED

                delay(500)

                // Save selected server
                serverStorage.setSelectedServerId(server.id)

                // Navigate to main activity (no actual connection established)
                val intent = Intent(context, MainContainerActivity::class.java)
                context.startActivity(intent)
            } else {
                connectionState = ConnectionState.FAILED
                Toast.makeText(context, R.string.error_no_offline_cache, Toast.LENGTH_LONG).show()
                delay(2000)
                connectionState = ConnectionState.IDLE
            }
        }
    }

    // Reset state when activity resumes (e.g., after logout or back navigation)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reload servers in case they were modified
                servers = serverStorage.getServers()
                selectedServer = serverStorage.getSelectedServerId()?.let { id -> servers.find { it.id == id } }
                    ?: servers.firstOrNull()

                // Reload offline mode setting in case it was changed in settings
                isOfflineMode = AppSettings.isOfflineMode(context)

                // Reset connection state when screen becomes visible again
                if (connectionState == ConnectionState.CONNECTED) {
                    connectionState = ConnectionState.IDLE
                    warningMessage = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-connect on fresh app launch (if enabled in settings, we have a selected server, user didn't explicitly logout, and not in offline mode)
    LaunchedEffect(Unit) {
        val isAutoConnectEnabled = AppSettings.isAutoConnectEnabled(context)
        val shouldAutoConnect = isAutoConnectEnabled && !ConnectionManager.isUserInitiatedLogout && !isOfflineMode
        ConnectionManager.clearLogoutFlag()

        if (!hasAutoConnected && shouldAutoConnect && selectedServer != null) {
            hasAutoConnected = true
            delay(500)
            if (connectionState == ConnectionState.IDLE) {
                attemptConnection(selectedServer!!)
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            comfyUIClient?.shutdown()
        }
    }

    // Server dialog
    if (showServerDialog) {
        // Load existing credentials for editing
        val existingCredentials = remember(serverToEdit) {
            serverToEdit?.let { server ->
                credentialStorage.getCredentials(server.id, server.authType)
            } ?: AuthCredentials.None
        }

        ServerDialog(
            server = serverToEdit,
            existingCredentials = existingCredentials,
            isNameTaken = { name, excludeServerId ->
                serverStorage.isServerNameTaken(name, excludeServerId)
            },
            onDismiss = {
                showServerDialog = false
                serverToEdit = null
            },
            onSave = { name, hostname, port, authType, credentials ->
                if (serverToEdit != null) {
                    // Update existing server
                    val updatedServer = serverToEdit!!.copy(
                        name = name,
                        hostname = hostname,
                        port = port,
                        authType = authType
                    )
                    serverStorage.updateServer(updatedServer)

                    // Save credentials
                    credentialStorage.saveCredentials(updatedServer.id, credentials)

                    servers = serverStorage.getServers()

                    // If editing the selected server, update selection
                    if (selectedServer?.id == updatedServer.id) {
                        selectedServer = updatedServer
                    }
                } else {
                    // Add new server
                    val newServer = Server.create(name, hostname, port, authType)
                    serverStorage.addServer(newServer)

                    // Save credentials
                    credentialStorage.saveCredentials(newServer.id, credentials)

                    servers = serverStorage.getServers()

                    // Select the new server
                    selectedServer = newServer
                    serverStorage.setSelectedServerId(newServer.id)
                }
                showServerDialog = false
                serverToEdit = null
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmation && selectedServer != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.title_server_delete)) },
            text = { Text(stringResource(R.string.msg_server_delete, selectedServer!!.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        val serverToDelete = selectedServer!!
                        serverStorage.deleteServer(serverToDelete.id)

                        // Also delete credentials for the server
                        credentialStorage.deleteCredentials(serverToDelete.id)

                        servers = serverStorage.getServers()

                        // Select another server or null
                        selectedServer = servers.firstOrNull()
                        selectedServer?.let { serverStorage.setSelectedServerId(it.id) }

                        showDeleteConfirmation = false
                        Toast.makeText(context, R.string.msg_server_deleted_success, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_server_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 背景照片操作菜单（仅在已有背景时弹出）
    if (showBackgroundMenu) {
        AlertDialog(
            onDismissRequest = { showBackgroundMenu = false },
            title = { Text(stringResource(R.string.login_background_pick)) },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showBackgroundMenu = false
                        pickBackground.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Text(stringResource(R.string.login_background_change))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showBackgroundMenu = false
                        resetBackground()
                    }
                ) {
                    Text(stringResource(R.string.login_background_reset))
                }
            }
        )
    }

    // 注：连接失败时不再弹"是否离线继续"的询问框——有缓存就直接自动切离线
    // （见 attemptConnection 的失败分支），没缓存则只报连接失败。

    // UI
    Box(modifier = Modifier.fillMaxSize()) {
        // 用户自选的背景照片：铺满 + 轻微模糊 + 一层浅暗色。
        // 模糊与暗化都刻意压得比较轻——太重（试过 55% 暗化）会让照片几乎看不出来，
        // 用户选背景就失去意义了；这个组合保证既能看清照片、前景文字也仍然可读。
        backgroundBitmap?.let { bg ->
            Image(
                bitmap = bg.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(16.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
            )
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App logo and name
        // The text has a -16dp offset to tuck it closer to the icon (which has built-in padding).
        // To keep the visual center aligned, we offset the entire Row by half that amount (-8dp).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = (-8).dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_comfychair_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp)
            )
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 36.sp,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = (-16).dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Server dropdown
        ServerDropdown(
            servers = servers,
            selectedServer = selectedServer,
            onServerSelected = { server ->
                selectedServer = server
                serverStorage.setSelectedServerId(server.id)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Connect split button with server management
        ConnectionSplitButton(
            connectionState = connectionState,
            hasSelectedServer = selectedServer != null,
            isOfflineMode = isOfflineMode,
            onConnect = {
                if (selectedServer != null) {
                    if (isOfflineMode) {
                        attemptOfflineConnection(selectedServer!!)
                    } else {
                        attemptConnection(selectedServer!!)
                    }
                }
            },
            onGoOnline = {
                // 取消「强制离线」：恢复在线并重连（不只是改标志位）
                ConnectionManager.setForcedOffline(context, false)
                isOfflineMode = false
            },
            onAddServer = {
                serverToEdit = null
                showServerDialog = true
            },
            onEditServer = {
                serverToEdit = selectedServer
                showServerDialog = true
            },
            onRemoveServer = {
                showDeleteConfirmation = true
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Warning message
        warningMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )
        }
    }

        // 右上角：选/换背景照片；长按清除，恢复默认外观
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        // 已有背景时先让用户选"换图"还是"恢复默认"——重置此前只藏在长按里，用户找不到
                        if (backgroundBitmap == null) {
                            pickBackground.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        } else {
                            showBackgroundMenu = true
                        }
                    },
                    onLongClick = { resetBackground() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (backgroundBitmap == null) {
                    Icons.Outlined.Wallpaper
                } else {
                    Icons.Outlined.PhotoLibrary
                },
                contentDescription = stringResource(R.string.login_background_pick),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
