package sh.hnet.comfychair.ui.screens

import android.app.LocaleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LanguageDropdown
import sh.hnet.comfychair.ui.components.LanguageOption
import sh.hnet.comfychair.ui.components.LogViewerDialog
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.util.CrashLogger
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.workflow.routing.RouterProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplicationSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    /** 进入「外观配色」（调色盘）二级页面 */
    onNavigateToAppearance: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLivePreviewEnabled by viewModel.isLivePreviewEnabled.collectAsStateWithLifecycle()
    val isMemoryFirstCache by viewModel.isMemoryFirstCache.collectAsStateWithLifecycle()
    val isMediaCacheDisabled by viewModel.isMediaCacheDisabled.collectAsStateWithLifecycle()
    val isDebugLoggingEnabled by viewModel.isDebugLoggingEnabled.collectAsStateWithLifecycle()
    val isAutoConnectEnabled by viewModel.isAutoConnectEnabled.collectAsStateWithLifecycle()
    val isShowBuiltInWorkflows by viewModel.isShowBuiltInWorkflows.collectAsStateWithLifecycle()
    val isOfflineMode by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val edgeRouterId by viewModel.edgeRouterId.collectAsStateWithLifecycle()
    val isPromptSpellCheckEnabled by viewModel.isPromptSpellCheckEnabled.collectAsStateWithLifecycle()
    val isPromptExpandEnabled by viewModel.isPromptExpandEnabled.collectAsStateWithLifecycle()

    // State and effects
    // Backup/restore state
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // Debug logging state
    var showLogViewer by remember { mutableStateOf(false) }
    // 崩溃日志查看（独立于"调试日志"开关：崩溃日志始终写入，release 也要能看）
    var showCrashLogViewer by remember { mutableStateOf(false) }

    // File picker for creating backup
    val backupSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.createBackup(context, it) }
    }

    // File picker for restoring backup
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.startRestore(it) }
    }

    // File picker for saving debug logs
    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { saveUri ->
            try {
                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    outputStream.write(DebugLogger.exportToString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, R.string.msg_debug_log_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.error_debug_log_save, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is SettingsEvent.ShowToastWithCount -> {
                    Toast.makeText(
                        context,
                        context.getString(event.messageResId, event.count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is SettingsEvent.RefreshNeeded -> {
                    // Handled by SettingsContainerActivity
                }
                is SettingsEvent.ShowRestoreDialog -> {
                    pendingRestoreUri = event.uri
                    showRestoreDialog = true
                }
                is SettingsEvent.NavigateToLogin -> {
                    onNavigateToLogin()
                }
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreDialog = false
                pendingRestoreUri = null
            },
            title = { Text(stringResource(R.string.title_backup_restore_confirm)) },
            text = { Text(stringResource(R.string.msg_backup_restore_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUri?.let { viewModel.restoreBackup(context, it) }
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.button_backup_restore_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Language options - System default first, then alphabetical by native name
    val languageOptions = remember {
        listOf(
            LanguageOption("", R.string.language_system_default),
            LanguageOption("de", R.string.language_name_de),
            LanguageOption("en", R.string.language_name_en),
            LanguageOption("es", R.string.language_name_es),
            LanguageOption("fr", R.string.language_name_fr),
            LanguageOption("pl", R.string.language_name_pl),
            LanguageOption("zh", R.string.language_name_zh)
        )
    }

    // Get current app locale from LocaleManager
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLocaleTag = if (currentLocales.isEmpty) {
        ""
    } else {
        currentLocales.get(0)?.language ?: ""
    }

    SettingsScreenScaffold(
        title = stringResource(R.string.title_application_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Language Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_language),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                LanguageDropdown(
                    languages = languageOptions,
                    selectedLocaleTag = currentLocaleTag,
                    onLanguageSelected = { localeTag ->
                        val newLocales = if (localeTag.isEmpty()) {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(localeTag)
                        }
                        localeManager.applicationLocales = newLocales
                        // Activity will be recreated automatically by the system
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt spell check toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_prompt_spell_check),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_prompt_spell_check),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPromptSpellCheckEnabled,
                        onCheckedChange = { viewModel.setPromptSpellCheckEnabled(context, it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance Card（调色盘入口）
        // 文案按调色盘页面的口径写死中文：颜色名与配色说明是专业术语，
        // 逐语言翻译收益很低，反而容易出现各语言说法不一致。
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToAppearance)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "外观配色",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "自定义强调色、底色与文字色；可预览、可还原出厂配色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_connection),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-connect toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_auto_connect),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_auto_connect),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAutoConnectEnabled,
                        onCheckedChange = { viewModel.setAutoConnectEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 强制离线开关。
                // 不再因「内存优先缓存」而置灰：离线读取的是 cacheDir/gallery_cache 那份
                // 磁盘数据，它是独立于内存缓存策略的另一层（内存优先不再删它），
                // 置灰只会让用户卡在离线态里关不掉开关。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_offline_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.desc_offline_mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isOfflineMode,
                        onCheckedChange = { viewModel.setOfflineMode(context, it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Generation Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_generation),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_generation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Show live preview toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_live_preview),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_live_preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isLivePreviewEnabled,
                        onCheckedChange = { viewModel.setLivePreviewEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Show built-in workflows toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_show_built_in_workflows),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_show_built_in_workflows),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isShowBuiltInWorkflows,
                        onCheckedChange = { viewModel.setShowBuiltInWorkflows(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt expand toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_prompt_expand),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_prompt_expand),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPromptExpandEnabled,
                        onCheckedChange = { viewModel.setPromptExpandEnabled(context, it) }
                    )
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Workflow Editor Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_workflow_editor),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_workflow_editor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Edge style label
                Text(
                    text = stringResource(R.string.label_edge_style),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Edge router selector using connected button group
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = edgeRouterId == "hermite",
                        onCheckedChange = { isChecked ->
                            if (isChecked) viewModel.setEdgeRouterId(context, "hermite")
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ) {
                        Text(stringResource(R.string.option_edge_router_hermite))
                    }
                    ToggleButton(
                        checked = edgeRouterId == "bezier",
                        onCheckedChange = { isChecked ->
                            if (isChecked) viewModel.setEdgeRouterId(context, "bezier")
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    ) {
                        Text(stringResource(R.string.option_edge_router_bezier))
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cache and Storage Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_cache),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_cache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // In-memory first cache toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_memory_first_cache),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_memory_first_cache),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isMemoryFirstCache) {
                            Text(
                                text = stringResource(R.string.desc_memory_first_cache_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Switch(
                        checked = isMemoryFirstCache,
                        onCheckedChange = { viewModel.setMemoryFirstCache(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Disable media cache toggle (disabled when disk-first mode)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_disable_media_cache),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isMemoryFirstCache)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        Text(
                            text = stringResource(R.string.desc_disable_media_cache),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isMemoryFirstCache)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                    Switch(
                        checked = isMediaCacheDisabled,
                        onCheckedChange = { viewModel.setMediaCacheDisabled(context, it) },
                        enabled = isMemoryFirstCache
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Clear cache button
                Button(
                    onClick = { viewModel.clearCache(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_clear_cache))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Backup and Restore Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_backup_restore),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_backup_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        backupSaveLauncher.launch("comfychair_backup_$timestamp.json")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_backup_create))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        backupRestoreLauncher.launch("application/json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.button_backup_restore))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_reset),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_reset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reset prompts and library button
                Button(
                    onClick = { viewModel.resetPromptsAndLibrary(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_reset_prompts_and_library))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Restore defaults button
                Button(
                    onClick = { viewModel.restoreDefaults(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_restore_defaults))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Debug Logging Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_debug_logging),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_debug_logging),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Enable toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_debug_logging_enable),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_debug_logging_enable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isDebugLoggingEnabled,
                        onCheckedChange = { viewModel.setDebugLoggingEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // View logs button
                Button(
                    onClick = { showLogViewer = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isDebugLoggingEnabled
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.button_debug_logging_view))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 崩溃日志 Card（P0 可观测性）
        // 与"调试日志"分开：崩溃日志**始终写入**，不受调试开关影响，release 版也要能看。
        // 只做"能看 + 能复制"，不引入文件选择器、不上传网络。
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    // P0-R6：文案一律走已翻译的资源，不硬编码中文（本项目默认 values 是英文，
                    // 另有 de/es/fr/pl/zh 五套资源；硬编码会让非中文用户在设置页看到中文）。
                    // 崩溃日志改用独立文案（title_crash_log / button_crash_log_view），
                    // 与上方「调试日志」卡片区分——两者用途不同，不能同名同按钮。
                    text = stringResource(R.string.title_crash_log),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { showCrashLogViewer = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.button_crash_log_view))
                }
            }
        }

        // Bottom padding
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Log viewer dialog
    if (showLogViewer) {
        LogViewerDialog(
            onDismiss = { showLogViewer = false },
            onSave = {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                logSaveLauncher.launch("comfychair_log_$timestamp.txt")
            }
        )
    }

    // 崩溃日志查看对话框（列文件名/大小/时间，顶部可一键复制全部内容）
    if (showCrashLogViewer) {
        CrashLogViewerDialog(onDismiss = { showCrashLogViewer = false })
    }
}

/**
 * 崩溃日志查看对话框。
 *
 * 只读当前 `filesDir/crash/` 下的日志文件（[CrashLogger.listCrashFiles]），
 * 顶部「复制」把 [CrashLogger.exportAll] 的结果放进剪贴板。
 * 不做文件选择器、不做网络上传 —— 用户自己决定把内容发给谁。
 */
@Composable
private fun CrashLogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 打开对话框时快照一次即可：崩溃日志只会在进程崩溃时新增，当前进程还活着就不会变
    val files = remember { CrashLogger.listCrashFiles(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏：复制 / 关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.title_crash_log),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        // 剪贴板预览标题也用应用名（已翻译的常量），不硬编码中文
                                        context.getString(R.string.app_name),
                                        CrashLogger.exportAll(context)
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    R.string.msg_copied_to_clipboard,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.tag_copy_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.button_copy_to_clipboard))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.content_description_close))
                    }
                }

                HorizontalDivider()

                if (files.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.msg_debug_log_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatCrashFileSize(file.length())} · " +
                                        formatCrashFileTime(file.lastModified()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** 崩溃日志文件大小：B / KB / MB，保留一位小数 */
private fun formatCrashFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 崩溃日志修改时间（本地时区） */
private fun formatCrashFileTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
