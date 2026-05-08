package com.drawapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
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
import com.penpal.feature.chat.ChatEvent
import com.penpal.feature.chat.ChatScreen
import com.penpal.feature.chat.ChatViewModel
import com.penpal.feature.notebooks.NotebookEditorViewModel
import com.penpal.feature.notebooks.NotebookScreen
import com.penpal.feature.notebooks.NotebookListScreen
import com.penpal.feature.notebooks.NotebookListViewModel
import com.penpal.feature.settings.SettingsScreen
import com.penpal.feature.settings.SettingsViewModel
import com.penpal.core.ai.ModelStatus

/**
 * Screen routes for bottom navigation.
 * Ordered as: Chat, Think, Settings
 */
sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Notebooks : Screen("notebooks", "Think", Icons.Default.AutoAwesome)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

/**
 * Ordered list of bottom navigation tabs.
 */
val bottomNavScreens = listOf(
    Screen.Chat,
    Screen.Notebooks,
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

object ChatRoutes {
    const val CHAT = "chat"
    const val CHAT_WITH_NOTEBOOK = "chat/{notebookId}"

    fun chatWithNotebookRoute(notebookId: String) = "chat/$notebookId"
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
    val database = remember { com.penpal.core.data.PenpalDatabase.getInstance(app) }

    // Shared notebook list view model for picker
    val notebookListViewModel = remember {
        NotebookListViewModel(notebookDao = database.notebookDao())
    }

    // Shared model status across all tabs
    val isModelReady by app.inferenceBridge.isReady.collectAsState()
    val modelStatus by app.inferenceBridge.modelStatus.collectAsState()
    val isModelUnloading by app.inferenceBridge.isUnloading.collectAsState()

    // Toggle model handler - load/unload
    val onToggleModel: () -> Unit = {
        val bridge = app.inferenceBridge
        if (isModelReady) {
            bridge.unloadModel()
        } else if (modelStatus == ModelStatus.DOWNLOADED) {
            val modelPath = com.penpal.core.ai.ModelManager.findExistingModel(app)
            if (modelPath != null) {
                bridge.loadModel(app, modelPath) { }
            }
        }
    }

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
                        inferenceBridge = app.inferenceBridge,
                        application = app,
                        chatMessageDao = database.chatMessageDao(),
                        chatConversationDao = database.chatConversationDao(),
                        notebookDao = database.notebookDao(),
                        workerLauncher = app.workerLauncher
                    )
                }
                val uiState by viewModel.uiState.collectAsState()
                ChatScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    onNavigateToNotebooks = {
                        navController.navigate(Screen.Notebooks.route)
                    },
                    onNavigateToChatWithNotebook = { notebookId ->
                        navController.navigate(ChatRoutes.chatWithNotebookRoute(notebookId))
                    },
                    notebookListViewModel = notebookListViewModel,
                    isModelReady = isModelReady,
                    isModelLoading = modelStatus == ModelStatus.DOWNLOADING,
                    isModelUnloading = isModelUnloading,
                    modelStatus = modelStatus,
                    onToggleModel = if (modelStatus == ModelStatus.DOWNLOADED || isModelReady) onToggleModel else null
                )
            }

            // Chat with pre-attached notebook
            composable(ChatRoutes.CHAT_WITH_NOTEBOOK) { backStackEntry ->
                val notebookId = backStackEntry.arguments?.getString("notebookId")
                val viewModel = remember {
                    ChatViewModel(
                        vectorStore = app.vectorStore,
                        inferenceBridge = app.inferenceBridge,
                        application = app,
                        chatMessageDao = database.chatMessageDao(),
                        chatConversationDao = database.chatConversationDao(),
                        notebookDao = database.notebookDao(),
                        workerLauncher = app.workerLauncher
                    )
                }
                val uiState by viewModel.uiState.collectAsState()

                // Auto-attach notebook when entering this route
                LaunchedEffect(notebookId) {
                    notebookId?.let { id ->
                        viewModel.onEvent(ChatEvent.AttachNotebook(id))
                    }
                }

                ChatScreen(
                    uiState = uiState,
                    onEvent = viewModel::onEvent,
                    onNavigateToNotebooks = {
                        navController.navigate(Screen.Notebooks.route)
                    },
                    onNavigateToChatWithNotebook = { navNotebookId ->
                        navController.navigate(ChatRoutes.chatWithNotebookRoute(navNotebookId))
                    },
                    notebookListViewModel = notebookListViewModel,
                    isModelReady = isModelReady,
                    isModelLoading = modelStatus == ModelStatus.DOWNLOADING,
                    isModelUnloading = isModelUnloading,
                    modelStatus = modelStatus,
                    onToggleModel = if (modelStatus == ModelStatus.DOWNLOADED || isModelReady) onToggleModel else null
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Think Tab (Notebooks)
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Notebooks.route) {
                NotebookListScreen(
                    viewModel = notebookListViewModel,
                    onNotebookSelected = { notebookId ->
                        navController.navigate(NotebookRoutes.editorRoute(notebookId))
                    },
                    onCreateNew = {
                        navController.navigate(NotebookRoutes.EDITOR)
                    },
                    onChatWithNotebook = { notebookId ->
                        navController.navigate(ChatRoutes.chatWithNotebookRoute(notebookId)) {
                            popUpTo(Screen.Chat.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    isModelReady = isModelReady,
                    isModelLoading = modelStatus == ModelStatus.DOWNLOADING,
                    isModelUnloading = isModelUnloading,
                    modelStatus = modelStatus,
                    onToggleModel = if (modelStatus == ModelStatus.DOWNLOADED || isModelReady) onToggleModel else null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Notebook editor (nested route)
            composable(NotebookRoutes.EDITOR) {
                val viewModel = remember {
                    NotebookEditorViewModel(
                        notebookDao = database.notebookDao(),
                        workerLauncher = app.workerLauncher
                    )
                }
                NotebookScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToHome = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onChatWithNotebook = { notebookId ->
                        navController.navigate(ChatRoutes.chatWithNotebookRoute(notebookId)) {
                            popUpTo(Screen.Chat.route) { inclusive = true }
                            launchSingleTop = true
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
                        notebookDao = database.notebookDao(),
                        workerLauncher = app.workerLauncher
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
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onChatWithNotebook = { navNotebookId ->
                        navController.navigate(ChatRoutes.chatWithNotebookRoute(navNotebookId)) {
                            popUpTo(Screen.Chat.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
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
                    onEvent = viewModel::onEvent,
                    isModelReady = isModelReady,
                    isModelLoading = modelStatus == ModelStatus.DOWNLOADING,
                    isModelUnloading = isModelUnloading,
                    modelStatus = modelStatus,
                    onToggleModel = if (modelStatus == ModelStatus.DOWNLOADED || isModelReady) onToggleModel else null
                )
            }
        }
    }
}