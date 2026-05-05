package com.drawapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.penpal.feature.chat.ChatScreen
import com.penpal.feature.chat.ChatViewModel
import com.penpal.feature.inference.InferenceScreen
import com.penpal.feature.inference.InferenceViewModel
import com.penpal.feature.notebooks.NotebookEditorViewModel
import com.penpal.feature.notebooks.NotebookScreen
import com.penpal.feature.notebooks.NotebookListScreen
import com.penpal.feature.notebooks.NotebookListViewModel
import com.penpal.feature.process.ProcessScreen
import com.penpal.feature.process.ProcessViewModel
import com.penpal.feature.settings.SettingsScreen
import com.penpal.feature.settings.SettingsViewModel
import com.penpal.core.processing.NetworkMonitor

/**
 * Screen routes for bottom navigation.
 * Ordered as: Chat, Think, Process, Inference, Settings
 */
sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Notebooks : Screen("notebooks", "Think", Icons.Default.AutoAwesome)
    data object Process : Screen("process", "Process", Icons.Default.CloudUpload)
    data object Inference : Screen("inference", "Inference", Icons.Default.Psychology)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

/**
 * Ordered list of bottom navigation tabs.
 */
val bottomNavScreens = listOf(
    Screen.Chat,
    Screen.Notebooks,
    Screen.Process,
    Screen.Inference,
    Screen.Settings
)

/**
 * Sub-routes for nested navigation within tabs.
 */
object NotebookRoutes {
    const val LIST = "notebooks/list"
    const val EDITOR = "notebooks/editor"
    const val EDITOR_WITH_ID = "notebooks/editor/{notebookId}"

    fun editorRoute(notebookId: String) = "notebooks/editor/$notebookId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToNotebook: (Long) -> Unit = {},
    onNavigateToNotebooks: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val app = LocalContext.current.applicationContext as PenpalApplication

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ──────────────────────────────────────────────────────────────
            // Chat Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Chat.route) {
                val viewModel = remember {
                    ChatViewModel(
                        vectorStore = app.vectorStore,
                        inferenceBridge = app.inferenceBridge
                    )
                }
                val uiState by viewModel.uiState.collectAsState()
                ChatScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Think Tab (Notebooks)
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Notebooks.route) {
                val viewModel = remember {
                    NotebookListViewModel(
                        notebookDao = com.penpal.core.data.PenpalDatabase.getInstance(app).notebookDao()
                    )
                }
                NotebookListScreen(
                    viewModel = viewModel,
                    onNotebookSelected = { notebookId ->
                        navController.navigate(NotebookRoutes.editorRoute(notebookId))
                    },
                    onCreateNew = {
                        navController.navigate(NotebookRoutes.EDITOR)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Notebook editor (nested route)
            composable(NotebookRoutes.EDITOR) {
                val viewModel = remember {
                    NotebookEditorViewModel(
                        notebookDao = com.penpal.core.data.PenpalDatabase.getInstance(app).notebookDao()
                    )
                }
                NotebookScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToHome = {
                        navController.navigate(Screen.Process.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Notebook editor with ID (load existing notebook)
            composable(NotebookRoutes.EDITOR_WITH_ID) { backStackEntry ->
                val notebookId = backStackEntry.arguments?.getString("notebookId")
                val viewModel = remember {
                    NotebookEditorViewModel(
                        notebookDao = com.penpal.core.data.PenpalDatabase.getInstance(app).notebookDao()
                    )
                }

                // Load notebook by ID if provided
                LaunchedEffect(notebookId) {
                    notebookId?.let { id ->
                        viewModel.loadFromDatabase(id)
                    }
                }

                NotebookScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToHome = {
                        navController.navigate(Screen.Process.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Process Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Process.route) {
                val viewModel = remember {
                    ProcessViewModel(
                        extractionJobDao = com.penpal.core.data.PenpalDatabase.getInstance(app).extractionJobDao(),
                        workerLauncher = app.workerLauncher,
                        networkMonitor = NetworkMonitor.getInstance(app),
                        getCachedChunkCount = { app.vectorStore.getCachedChunkCount() }
                    )
                }
                val uiState by viewModel.uiState.collectAsState()
                ProcessScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Inference Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Inference.route) {
                val viewModel = remember {
                    InferenceViewModel(
                        application = app,
                        inferenceBridge = app.inferenceBridge
                    )
                }
                val uiState by viewModel.uiState.collectAsState()
                InferenceScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Settings Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Settings.route) {
                val viewModel = remember {
                    SettingsViewModel(
                        application = app,
                        inferenceBridge = app.inferenceBridge
                    )
                }
                val uiState by viewModel.uiState.collectAsState()
                SettingsScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }
        }
    }
}