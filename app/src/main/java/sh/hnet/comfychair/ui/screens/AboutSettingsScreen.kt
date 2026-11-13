package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.update.UpdateChecker
import sh.hnet.comfychair.update.UpdateRepository

@Composable
fun AboutSettingsScreen(
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }

    // UI composition
    SettingsScreenScaffold(
        title = stringResource(R.string.title_about_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout,
        horizontalPadding = 0.dp
    ) {
        Column(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // App Logo (300dp)
            Image(
                painter = painterResource(id = R.drawable.ic_comfychair_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(300.dp)
            )

            // App Name
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Version
            Text(
                text = stringResource(R.string.label_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 检查更新（v4.6.0 内置更新模块的手动入口）
            var checkUpdateMessageRes by remember { mutableStateOf<Int?>(null) }
            var checkUpdateErrorArg by remember { mutableStateOf<String?>(null) }
            var showUpdateDialog by remember { mutableStateOf(false) }
            Text(
                text = if (checkingUpdate) {
                    stringResource(R.string.update_checking)
                } else {
                    stringResource(R.string.update_action_check)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !checkingUpdate) {
                    checkingUpdate = true
                    scope.launch(Dispatchers.IO) {
                        val info = UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE, manualCheck = true)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            checkingUpdate = false
                            when {
                                info != null -> {
                                    UpdateRepository.publishAvailable(info)
                                    showUpdateDialog = true
                                }
                                UpdateChecker.lastCheckError != null -> {
                                    checkUpdateErrorArg = UpdateChecker.lastCheckError
                                    checkUpdateMessageRes = R.string.update_manual_check_failed
                                }
                                else -> checkUpdateMessageRes = R.string.update_already_latest
                            }
                        }
                    }
                }
            )
            checkUpdateMessageRes?.let { resId ->
                Text(
                    text = if (resId == R.string.update_manual_check_failed) {
                        stringResource(resId, checkUpdateErrorArg ?: "")
                    } else {
                        stringResource(resId)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showUpdateDialog) {
                sh.hnet.comfychair.ui.components.shared.UpdateDialog(
                    showDialog = true,
                    onDismiss = { showUpdateDialog = false }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // License Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.title_about_license),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.label_about_license_name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // GitHub Link Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/legal-hkr/comfychair")
                        )
                        context.startActivity(intent)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.title_about_source),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "github.com/legal-hkr/comfychair",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
