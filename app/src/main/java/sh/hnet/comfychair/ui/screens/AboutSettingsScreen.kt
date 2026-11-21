package sh.hnet.comfychair.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.History
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
import sh.hnet.comfychair.update.UpdateCleanupPolicy
import sh.hnet.comfychair.update.UpdateRepository

@Composable
fun AboutSettingsScreen(
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToChangelog: () -> Unit
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
                    // 手动检查统一走 UpdateRepository.manualCheck（v4.18.0）：
                    // 1. R4：先看磁盘上有没有「校验通过且版本更高」的已下载包 → 直接给「安装」；
                    // 2. R1：手动检查不受「一版只主动弹一次」的配额限制，有更新就弹；
                    // 3. R3：确认已是最新时取消残留 Worker + 清通知，并给「已是最新」提示；
                    // 4. 失败原因仍从 lastCheckError 透传（与原行为一致）。
                    val appContext = context.applicationContext
                    scope.launch(Dispatchers.IO) {
                        // 已装版本用 PackageManager 读（不能用 BuildConfig：编译期常量，
                        // 装完新版本后旧进程里读到的是旧值）。
                        val result = UpdateRepository.manualCheck(
                            appContext,
                            UpdateCleanupPolicy.installedVersionCode(appContext).toInt()
                        )
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            checkingUpdate = false
                            when (result) {
                                is UpdateRepository.ManualCheckResult.UpdateAvailable ->
                                    showUpdateDialog = true
                                UpdateRepository.ManualCheckResult.ReadyToInstall ->
                                    showUpdateDialog = true
                                UpdateRepository.ManualCheckResult.AlreadyLatest ->
                                    checkUpdateMessageRes = R.string.update_already_latest
                                is UpdateRepository.ManualCheckResult.Failed -> {
                                    checkUpdateErrorArg = result.message
                                    checkUpdateMessageRes = R.string.update_manual_check_failed
                                }
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
                    onDismiss = {
                        showUpdateDialog = false
                        // R1：忽略 = 放弃本次提示。只隐藏弹窗会把状态留在 AVAILABLE，
                        // 回到主界面时 UpdateDialogHost 会把它再弹一次 —— 正是用户抱怨的
                        // 「点了忽略还是每次都弹」。这里同步收回 IDLE。
                        if (UpdateRepository.state.value.phase ==
                            sh.hnet.comfychair.update.UpdatePhase.AVAILABLE
                        ) {
                            UpdateRepository.reset()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 更新日志入口：离线读 assets/CHANGELOG.md，能看全部历史版本的说明
            // （更新弹窗只能显示"这一版"的内容，旧客户端永远看不到新 UI 的说明）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { onNavigateToChangelog() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.changelog_entry_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.changelog_entry_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                        // P3-10：设备可能没有任何能处理网页的应用（精简 ROM / 无浏览器），
                        // 不兜底会直接崩溃；这里提示用户而不是闪退。
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/kuramity/Comfychair")
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                R.string.error_open_link_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
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
                            text = "github.com/kuramity/Comfychair",
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
