package com.drawapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ai.model.ModelManager
import com.penpal.feature.chat.screen.ChatScreen
import com.penpal.feature.chat.viewmodel.ChatEvent
import com.penpal.feature.chat.viewmodel.ChatViewModel
import com.penpal.feature.settings.screen.SettingsScreen
import com.penpal.feature.settings.viewmodel.SettingsViewModel
import com.penpal.feature.stacks.viewmodel.StackEditorViewModel
import com.penpal.feature.stacks.screen.StackListScreen
import com.penpal.feature.stacks.viewmodel.StackListViewModel
import com.penpal.feature.stacks.screen.StackScreen

import com.drawapp.NotebooksScreen

/** Screen routes for bottom navigation. Ordered as: Chat, Think, Settings */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Stacks : Screen("stacks", "Think", Icons.Default.AutoAwesome)
    data object Notebooks : Screen("notebooks", "Notebooks", Icons.Default.Book)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

/** Ordered list of bottom navigation tabs. */
val bottomNavScreens = listOf(Screen.Notebooks, Screen.Stacks, Screen.Settings)

/** Sub-routes for nested navigation within tabs. */
object StackRoutes {
    const val LIST = "stacks/list"
    const val EDITOR = "stacks/editor"
    const val EDITOR_WITH_ID = "stacks/editor/{stackId}"

    fun editorRoute(stackId: String) = "stacks/editor/$stackId"
}

object ChatRoutes {
    const val CHAT = "chat"
    const val CHAT_WITH_STACK = "chat/{stackId}"

    fun chatWithStackRoute(stackId: String) = "chat/$stackId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onNavigateToStack: (Long) -> Unit = {}, onNavigateToStacks: () -> Unit = {}) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Track if user has ever opened chat via FAB (to show FAB on tabs after first use)
    var hasOpenedChatViaFab by remember { mutableStateOf(false) }

    val app = LocalContext.current.applicationContext as PenpalApplication
    val database = remember { com.penpal.core.data.PenpalDatabase.getInstance(app) }

    // Shared stack list view model for picker
    val stackListViewModel = remember { StackListViewModel(stackDao = database.stackDao()) }

    // Shared tool registry for chat
    val toolRegistry = remember {
        com.penpal.core.ai.tools.ToolRegistry().also { registry ->
            registry.register(com.penpal.core.ai.tools.SearchKnowledgeTool(app.vectorStore))
            registry.register(com.penpal.core.ai.tools.ReadStackTool(database.stackDao()))
            registry.register(com.penpal.core.ai.tools.GetConversationHistoryTool())
            registry.register(com.penpal.core.ai.tools.ListAttachedStacksTool())
            registry.register(com.penpal.core.ai.tools.web.WebSearchTool())
            registry.register(com.penpal.core.ai.tools.web.FetchUrlContentTool())
            registry.register(com.penpal.core.ai.tools.web.StoreWebContentTool(app.vectorStore))
            registry.register(com.penpal.core.ai.tools.web.ListStoredSourcesTool(app.vectorStore))
            registry.register(com.penpal.core.ai.tools.web.DeleteStoredSourceTool(app.vectorStore))
        }
    }

    // Shared model status across all tabs
    val isModelReady by app.inferenceBridge.isReady.collectAsState()
    val rawModelStatus by app.inferenceBridge.modelStatus.collectAsState()
    val modelStatus =
            remember(rawModelStatus) {
                // If model file exists but status shows NOT_DOWNLOADED, treat as DOWNLOADED
                if (rawModelStatus == ModelStatus.NOT_DOWNLOADED) {
val modelPath = com.penpal.core.ai.model.ModelManager.findExistingModel(app)
                    if (modelPath != null) ModelStatus.DOWNLOADED else rawModelStatus
                } else {
                    rawModelStatus
                }
            }
    val isModelUnloading by app.inferenceBridge.isUnloading.collectAsState()

    // Toggle model handler - load/unload
    val onToggleModel: () -> Unit = {
        val bridge = app.inferenceBridge
        if (isModelReady) {
            bridge.unloadModel()
        } else if (modelStatus == ModelStatus.DOWNLOADED || modelStatus == ModelStatus.READY) {
            // Model is downloaded but not loaded - load it
            val modelPath = com.penpal.core.ai.model.ModelManager.findExistingModel(app)
            if (modelPath != null) {
                bridge.loadModel(app, modelPath) {}
            }
        }
    }

    // Shared ChatViewModel factory for proper ViewModel scoping
    val chatViewModelFactory = remember {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
                    vectorStore = app.vectorStore,
                    inferenceBridge = app.inferenceBridge,
                    application = app,
                    chatMessageDao = database.chatMessageDao(),
                    chatConversationDao = database.chatConversationDao(),
                    stackDao = database.stackDao(),
                    workerLauncher = app.workerLauncher,
                    onLoadModel = { onToggleModel?.invoke() },
                    toolRegistry = toolRegistry
                ) as T
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
                                selected =
                                        currentDestination?.hierarchy?.any {
                                            it.route == screen.route
                                        } == true,
                                onClick = {
                                    // If in chat, pop back first (same as X button), then navigate
                                    val isInChat =
                                            currentDestination?.route == Screen.Chat.route ||
                                                    currentDestination?.route?.startsWith(
                                                            "chat/"
                                                    ) == true
                                    if (isInChat) {
                                        // Pop back to close chat properly (same as X button)
                                        navController.popBackStack()
                                    }
                                    // Then navigate to the tab
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
            },
            floatingActionButton = {
                val route = currentDestination?.route
                val isChatRoute = route == Screen.Chat.route || route?.startsWith("chat/") == true

                // Show FAB when:
                // 1. On tabs AND (first time OR user has opened chat before) - allows entering chat
                // 2. Never show FAB when in chat
                // 3. Hide when on Stacks route (has its own FABs)
                if (!isChatRoute) {
                    val isStacksRoute = route == Screen.Stacks.route || route?.startsWith("stacks/") == true
                    val isNotebooksRoute = route == Screen.Notebooks.route
                    val showChatFab = !isStacksRoute && !isNotebooksRoute

                    if (showChatFab) {
                        FloatingActionButton(
                                onClick = {
                                    hasOpenedChatViaFab = true
                                    navController.navigate(Screen.Chat.route)
                                },
                                containerColor = MaterialTheme.colorScheme.primary
                        ) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") }
                    }
                }
            }
    ) { innerPadding ->
        NavHost(
                navController = navController,
                startDestination = Screen.Notebooks.route,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            // ──────────────────────────────────────────────────────────────
            // Chat Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Chat.route) {
                val viewModel = viewModel<ChatViewModel>(factory = chatViewModelFactory)
                val uiState by viewModel.uiState.collectAsState()
                ChatScreen(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToStacks = { navController.navigate(Screen.Stacks.route) },
                        onNavigateToChatWithStack = { stackId ->
                            navController.navigate(ChatRoutes.chatWithStackRoute(stackId))
                        },
                        stackPickerViewModel = stackListViewModel,
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null
                )
            }

            // Chat with pre-attached stack
            composable(ChatRoutes.CHAT_WITH_STACK) { backStackEntry ->
                val stackId = backStackEntry.arguments?.getString("stackId")
                val viewModel = viewModel<ChatViewModel>(factory = chatViewModelFactory)
                val uiState by viewModel.uiState.collectAsState()

                // Auto-attach stack when entering this route
                LaunchedEffect(stackId) {
                    stackId?.let { id -> viewModel.onEvent(ChatEvent.AttachStack(id)) }
                }

                ChatScreen(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToStacks = { navController.navigate(Screen.Stacks.route) },
                        onNavigateToChatWithStack = { navStackId ->
                            navController.navigate(ChatRoutes.chatWithStackRoute(navStackId))
                        },
                        stackPickerViewModel = stackListViewModel,
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Think Tab (Stacks)
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Stacks.route) {
                StackListScreen(
                        viewModel = stackListViewModel,
                        onStackSelected = { stackId ->
                            navController.navigate(StackRoutes.editorRoute(stackId))
                        },
                        onCreateNew = { navController.navigate(StackRoutes.EDITOR) },
                        onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                        onChatWithStack = { stackId ->
                            navController.navigate(ChatRoutes.chatWithStackRoute(stackId)) {
                                popUpTo(Screen.Chat.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null,
                        modifier = Modifier.fillMaxSize()
                )
            }

            // Stack editor (nested route)
            composable(StackRoutes.EDITOR) {
                val viewModel = remember {
                    StackEditorViewModel(
                            context = app,
                            stackDao = database.stackDao(),
                            workerLauncher = app.workerLauncher,
                            inferenceBridge = app.inferenceBridge,
                            onLoadModel = { onToggleModel?.invoke() }
                    )
                }
                StackScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToHome = { navController.popBackStack() },
                        onChatWithStack = { stackId ->
                            navController.navigate(ChatRoutes.chatWithStackRoute(stackId)) {
                                popUpTo(Screen.Chat.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null,
                        modifier = Modifier.fillMaxSize()
                )
            }

            // Stack editor with ID (load existing stack)
            composable(StackRoutes.EDITOR_WITH_ID) { backStackEntry ->
                val stackId = backStackEntry.arguments?.getString("stackId")
                val viewModel = remember {
                    StackEditorViewModel(
                            context = app,
                            stackDao = database.stackDao(),
                            workerLauncher = app.workerLauncher,
                            inferenceBridge = app.inferenceBridge,
                            onLoadModel = { onToggleModel?.invoke() }
                    )
                }

                // Load stack by ID if provided
                LaunchedEffect(stackId) { stackId?.let { id -> viewModel.loadFromDatabase(id) } }

                StackScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToHome = { navController.popBackStack() },
                        onChatWithStack = { navStackId ->
                            navController.navigate(ChatRoutes.chatWithStackRoute(navStackId)) {
                                popUpTo(Screen.Chat.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null,
                        modifier = Modifier.fillMaxSize()
                )
            }

            // Notebooks Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Notebooks.route) {
                NotebooksScreen(
                    modifier = Modifier.fillMaxSize(),
                    onNavigateToChat = { navController.navigate(Screen.Chat.route) }
                )
            }

            // Settings Tab
            // ──────────────────────────────────────────────────────────────
            composable(Screen.Settings.route) {
                val viewModel = remember {
                    SettingsViewModel(application = app, inferenceBridge = app.inferenceBridge)
                }
                val uiState by viewModel.uiState.collectAsState()
                SettingsScreen(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        isModelReady = isModelReady,
                        isModelLoading =
                                modelStatus == ModelStatus.DOWNLOADING ||
                                        modelStatus == ModelStatus.LOADING,
                        isModelUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel =
                                if (modelStatus == ModelStatus.DOWNLOADED ||
                                                modelStatus == ModelStatus.READY
                                )
                                        onToggleModel
                                else null
                )
            }
        }
    }
}
