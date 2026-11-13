package sh.hnet.comfychair.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.AppearanceSettingsScreen
import sh.hnet.comfychair.ui.screens.ApplicationSettingsScreen
import androidx.compose.material.icons.filled.MenuBook
import sh.hnet.comfychair.ui.screens.ModelIntroScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@Composable
fun SettingsNavHost(
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            BottomAppBar(
                actions = {
                    // Add left padding to align with screen content
                    Spacer(modifier = Modifier.width(12.dp))

                    // Workflows Settings (first)
                    if (currentRoute == SettingsRoute.Workflows.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Workflows.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 模型介绍（按目录浏览全部模型的名称与介绍）
                    if (currentRoute == SettingsRoute.ModelIntro.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = stringResource(R.string.nav_model_intro)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.ModelIntro.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = stringResource(R.string.nav_model_intro),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Application Settings（外观配色是它的二级页面，一并算选中，
                    // 否则进了调色页底部四个图标全变未选中态，看起来像迷路了）
                    if (currentRoute == SettingsRoute.Application.route ||
                        currentRoute == SettingsRoute.Appearance.route
                    ) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Application.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Server Settings
                    if (currentRoute == SettingsRoute.Server.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Server.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // About Settings
                    if (currentRoute == SettingsRoute.About.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.nav_about_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.About.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.nav_about_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                floatingActionButton = {
                    // Back to generation FAB
                    FloatingActionButton(
                        onClick = onNavigateToGeneration,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.menu_generation),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = SettingsRoute.Workflows.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            // Order matches BottomAppBar: Workflows → Application → Server → About
            composable(SettingsRoute.Workflows.route) {
                WorkflowsSettingsScreen(
                    viewModel = workflowManagementViewModel,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.ModelIntro.route) {
                ModelIntroScreen(onBack = { navController.popBackStack() })
            }

            composable(SettingsRoute.Application.route) {
                ApplicationSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout,
                    onNavigateToAppearance = {
                        navController.navigate(SettingsRoute.Appearance.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(SettingsRoute.Appearance.route) {
                AppearanceSettingsScreen(
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(SettingsRoute.Server.route) {
                ServerSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.About.route) {
                AboutSettingsScreen(
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }
        }
    }
}
