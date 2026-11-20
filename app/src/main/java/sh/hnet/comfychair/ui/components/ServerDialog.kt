package sh.hnet.comfychair.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.util.ServerUrlUtils

/**
 * Dialog for adding or editing a server configuration.
 *
 * @param server The server to edit, or null for adding a new server
 * @param existingCredentials The existing credentials for the server (when editing)
 * @param isNameTaken Function to check if a name is already taken (excluding current server)
 * @param onDismiss Called when the dialog should be dismissed
 * @param onSave Called with the server data and credentials when save is confirmed
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerDialog(
    server: Server?,
    existingCredentials: AuthCredentials = AuthCredentials.None,
    isNameTaken: (name: String, excludeServerId: String?) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, hostname: String, port: Int, authType: AuthType, credentials: AuthCredentials, protocol: String?) -> Unit
) {
    val isEditMode = server != null

    // P3-6：对话框在切换编辑对象时并不会离开组合（宿主只改 server 参数），
    // 无 key 的 remember 会保留上一台服务器的字段 —— 尤其是账号/密码/token，
    // 保存时就会被写到当前这一台上。所有字段必须按「服务器 id + 已存凭据」重建。
    val formKey = server?.id to existingCredentials
    val initial = remember(formKey) { serverDialogInitialState(server, existingCredentials) }

    // Form state
    var name by remember(formKey) { mutableStateOf(initial.name) }
    // 编辑时把已保存的协议显示出来，让用户看得见当前选择（可改前缀或改回自动）
    var hostname by remember(formKey) { mutableStateOf(initial.hostname) }
    var port by remember(formKey) { mutableStateOf(initial.port) }

    // Authentication state
    var authType by remember(formKey) { mutableStateOf(initial.authType) }
    var username by remember(formKey) { mutableStateOf(initial.username) }
    var password by remember(formKey) { mutableStateOf(initial.password) }
    var token by remember(formKey) { mutableStateOf(initial.token) }
    var passwordVisible by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    // Error states
    var nameError by remember { mutableStateOf<String?>(null) }
    var hostnameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }

    // Focus requester for name field
    val nameFocusRequester = remember { FocusRequester() }

    // 允许用户输入带 scheme 的地址（http://host），校验只针对剥离后的纯主机名
    fun isValidHostname(value: String): Boolean = ServerUrlUtils.isValidHostname(value)

    // String resources
    val errorRequired = stringResource(R.string.error_required)
    val errorInvalidHostname = stringResource(R.string.error_invalid_hostname)
    val errorInvalidPort = stringResource(R.string.error_invalid_port)
    val errorNameTaken = stringResource(R.string.error_server_name_taken)

    // Validation function
    fun validate(): Boolean {
        var isValid = true
        nameError = null
        hostnameError = null
        portError = null
        usernameError = null
        passwordError = null
        tokenError = null

        val trimmedName = name.trim()
        val trimmedHostname = hostname.trim()
        val trimmedPort = port.trim()

        // Validate name
        if (trimmedName.isEmpty()) {
            nameError = errorRequired
            isValid = false
        } else if (isNameTaken(trimmedName, server?.id)) {
            nameError = errorNameTaken
            isValid = false
        }

        // Validate hostname
        if (trimmedHostname.isEmpty()) {
            hostnameError = errorRequired
            isValid = false
        } else if (!isValidHostname(trimmedHostname)) {
            hostnameError = errorInvalidHostname
            isValid = false
        }

        // Validate port
        if (trimmedPort.isEmpty()) {
            portError = errorRequired
            isValid = false
        } else {
            val portNum = trimmedPort.toIntOrNull()
            if (portNum == null || portNum !in 1..65535) {
                portError = errorInvalidPort
                isValid = false
            }
        }

        // Validate auth credentials
        when (authType) {
            AuthType.BASIC -> {
                if (username.trim().isEmpty()) {
                    usernameError = errorRequired
                    isValid = false
                }
                if (password.isEmpty()) {
                    passwordError = errorRequired
                    isValid = false
                }
            }
            AuthType.BEARER -> {
                if (token.trim().isEmpty()) {
                    tokenError = errorRequired
                    isValid = false
                }
            }
            AuthType.NONE, AuthType.BROWSER -> { /* No credential validation needed */ }
        }

        return isValid
    }

    // Build credentials from current state
    fun buildCredentials(): AuthCredentials {
        return when (authType) {
            AuthType.NONE -> AuthCredentials.None
            AuthType.BASIC -> AuthCredentials.Basic(username.trim(), password)
            AuthType.BEARER -> AuthCredentials.Bearer(token.trim())
            // Browser cookies are captured via BrowserAuthActivity at connection time
            AuthType.BROWSER -> AuthCredentials.None
        }
    }

    // Focus the name field when dialog opens
    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isEditMode) R.string.title_server_edit
                    else R.string.title_server_add
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Server name
                OutlinedTextField(
                    value = name,
                    onValueChange = { newValue ->
                        name = newValue
                        // Clear error on change
                        nameError = null
                    },
                    label = { Text(stringResource(R.string.label_server_name)) },
                    placeholder = { Text(stringResource(R.string.placeholder_server_name)) },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                )

                // Hostname
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { newValue ->
                        hostname = newValue
                        // Live validation
                        val trimmed = newValue.trim()
                        hostnameError = when {
                            trimmed.isEmpty() -> null
                            !isValidHostname(trimmed) -> errorInvalidHostname
                            else -> null
                        }
                    },
                    label = { Text(stringResource(R.string.hint_hostname)) },
                    placeholder = { Text(stringResource(R.string.placeholder_server_hostname)) },
                    isError = hostnameError != null,
                    supportingText = hostnameError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                // Port
                OutlinedTextField(
                    value = port,
                    onValueChange = { newValue ->
                        port = newValue
                        // Live validation
                        val trimmed = newValue.trim()
                        portError = when {
                            trimmed.isEmpty() -> null
                            else -> {
                                val portNum = trimmed.toIntOrNull()
                                if (portNum == null || portNum !in 1..65535) {
                                    errorInvalidPort
                                } else {
                                    null
                                }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.hint_port)) },
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Authentication section
                Text(
                    text = stringResource(R.string.label_auth_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Auth type selector using connected button group
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = authType == AuthType.NONE,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.NONE
                                usernameError = null
                                passwordError = null
                                tokenError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.option_auth_type_none)
                        )
                    }
                    ToggleButton(
                        checked = authType == AuthType.BASIC,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.BASIC
                                tokenError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Password,
                            contentDescription = stringResource(R.string.option_auth_type_basic)
                        )
                    }
                    ToggleButton(
                        checked = authType == AuthType.BEARER,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.BEARER
                                usernameError = null
                                passwordError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = stringResource(R.string.option_auth_type_bearer)
                        )
                    }
                    ToggleButton(
                        checked = authType == AuthType.BROWSER,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.BROWSER
                                usernameError = null
                                passwordError = null
                                tokenError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = stringResource(R.string.option_auth_type_browser)
                        )
                    }
                }

                // Basic auth fields
                AnimatedVisibility(visible = authType == AuthType.BASIC) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { newValue ->
                                username = newValue
                                usernameError = null
                            },
                            label = { Text(stringResource(R.string.label_auth_username)) },
                            isError = usernameError != null,
                            supportingText = usernameError?.let { { Text(it) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { newValue ->
                                password = newValue
                                passwordError = null
                            },
                            label = { Text(stringResource(R.string.label_auth_password)) },
                            isError = passwordError != null,
                            supportingText = passwordError?.let { { Text(it) } },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Browser auth info text
                AnimatedVisibility(visible = authType == AuthType.BROWSER) {
                    Text(
                        text = stringResource(R.string.label_auth_browser_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Bearer token field
                AnimatedVisibility(visible = authType == AuthType.BEARER) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { newValue ->
                            token = newValue
                            tokenError = null
                        },
                        label = { Text(stringResource(R.string.label_auth_token)) },
                        isError = tokenError != null,
                        supportingText = tokenError?.let { { Text(it) } },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (validate()) {
                        val (explicitProtocol, bareHostname) = ServerUrlUtils.splitProtocol(hostname.trim())
                        onSave(
                            name.trim(),
                            bareHostname,
                            port.trim().toInt(),
                            authType,
                            buildCredentials(),
                            explicitProtocol
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

/**
 * ServerDialog 表单的初始值（P3-6）。
 *
 * 抽成纯函数是为了让单元测试能直接钉住「字段由哪台服务器/哪份凭据决定」这一契约：
 * 切换编辑对象时，账号、密码、token 必须跟着换，绝不能把上一台的值留在输入框里。
 * 组合层用 `remember(server?.id to existingCredentials)` 调用它来重建状态。
 */
internal data class ServerDialogInitialState(
    val name: String,
    val hostname: String,
    val port: String,
    val authType: AuthType,
    val username: String,
    val password: String,
    val token: String
)

/** 按服务器与已保存凭据推导表单初值；新增服务器（server == null）时用默认值。 */
internal fun serverDialogInitialState(
    server: Server?,
    existingCredentials: AuthCredentials
): ServerDialogInitialState {
    // 新增分支必须给出空表单：即使调用方还持有上一台的凭据对象，也不能预填，
    // 否则同样会把旧账号带到新服务器上（P3-6 的另一种触发路径）。
    val credentials = if (server == null) AuthCredentials.None else existingCredentials
    return ServerDialogInitialState(
        name = server?.name ?: "",
        // 编辑时把已保存的协议一并显示出来，让用户看得见当前选择（可改前缀或改回自动）
        hostname = server?.protocol?.let { "$it://${server.hostname}" } ?: server?.hostname ?: "",
        port = server?.port?.toString() ?: "8188",
        authType = server?.authType ?: AuthType.NONE,
        username = (credentials as? AuthCredentials.Basic)?.username ?: "",
        password = (credentials as? AuthCredentials.Basic)?.password ?: "",
        token = (credentials as? AuthCredentials.Bearer)?.token ?: ""
    )
}
