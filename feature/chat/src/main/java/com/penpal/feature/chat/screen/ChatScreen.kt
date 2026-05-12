package com.penpal.feature.chat.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.penpal.core.ai.messaging.MessagePart
import com.penpal.core.ai.messaging.ToolStatus
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ai.tools.MessageRole
import com.penpal.core.data.knowledge.ChunkEntity
import com.penpal.core.ui.component.StatusIndicator
import androidx.compose.ui.graphics.Color as ComposeColor

import com.penpal.core.ui.theme.PenpalTheme
import com.penpal.feature.chat.component.MarkdownText
import com.penpal.feature.chat.viewmodel.AttachedStack
import com.penpal.feature.chat.viewmodel.ChatConversation
import com.penpal.feature.chat.viewmodel.ChatEvent
import com.penpal.feature.chat.viewmodel.ChatMessage
import com.penpal.feature.chat.viewmodel.ChatUiState
import com.penpal.feature.chat.viewmodel.PinnedFile
import com.penpal.core.ui.picker.PickerDialog
import com.penpal.core.ui.picker.PickerViewModel
import com.penpal.core.processing.speech.SpeechResult
import com.penpal.core.processing.speech.StreamingSpeechRecognizer

private data class StatusColors(
    val dotColor: ComposeColor,
    val text: String,
    val containerColor: ComposeColor,
    val textColor: ComposeColor,
    val clickable: Boolean
)

/**
 * Main Chat screen with conversation history, notebook attachments, file handling, and drag-and-drop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    onNavigateBack: () -> Unit = {},
    onNavigateToStacks: () -> Unit = {},
    onNavigateToChatWithStack: (String) -> Unit = {},
    stackPickerViewModel: PickerViewModel? = null,
    onStartSubChat: ((String) -> Unit)? = null,
    // Shared model status from MainScreen
    isModelReady: Boolean = uiState.isModelReady,
    isModelLoading: Boolean = false,
    isModelUnloading: Boolean = false,
    modelStatus: ModelStatus = uiState.modelStatus,
    onToggleModel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showContext by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showStackPicker by remember { mutableStateOf(false) }
    var showSpeechInput by remember { mutableStateOf(false) }

    // Over-drag panel for system prompt
    var isPanelExpanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val panelHeight = if (isPanelExpanded) 200 else 0

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Track if list is at top for over-drag gesture
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            onEvent(ChatEvent.AddFile(it, getMimeType(it)))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationHistoryDrawer(
                conversations = uiState.conversations,
                currentConversationId = uiState.currentConversationId,
                onSelectConversation = { id ->
                    onEvent(ChatEvent.LoadConversation(id))
                    scope.launch { drawerState.close() }
                },
                onNewConversation = {
                    onEvent(ChatEvent.CreateConversation("New Chat"))
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    onEvent(ChatEvent.DeleteConversation(id))
                }
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
        ) {
            // Hidden panel (revealed by over-drag)
            if (panelHeight > 0) {
                ChatPromptsPanel(
                    systemPrompt = uiState.systemPrompt,
                    onSystemPromptChange = { onEvent(ChatEvent.UpdateSystemPrompt(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                )
            } else if (isAtTop && uiState.systemPrompt.isNotBlank()) {
                // Show subtle indicator that a system prompt is set
                Text(
                    text = "\u25BC drag down for system prompt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // Top bar with drawer toggle and context toggle
            ChatTopBar(
                title = uiState.currentConversationTitle,
                hasContext = uiState.retrievedContext.isNotEmpty(),
                showContext = showContext,
                onToggleContext = { showContext = !showContext },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNavigateBack = onNavigateBack,
                isModelReady = isModelReady,
                isModelLoading = isModelLoading,
                isModelUnloading = isModelUnloading,
                modelStatus = modelStatus,
                onToggleModel = onToggleModel,
                toolsEnabled = uiState.toolsEnabled,
                thinkingEnabled = uiState.thinkingEnabled,
                onToggleTools = { onEvent(ChatEvent.ToggleTools) },
                onToggleThinking = { onEvent(ChatEvent.ToggleThinking) }
            )

            // Context panel (collapsible)
            AnimatedVisibility(visible = showContext && uiState.retrievedContext.isNotEmpty()) {
                ContextPanel(
                    chunks = uiState.retrievedContext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }

            // Attached stacks panel
            if (uiState.attachedStacks.isNotEmpty()) {
                AttachedStacksPanel(
                    stacks = uiState.attachedStacks,
                    onDetach = { onEvent(ChatEvent.DetachStack(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Pinned files panel
            if (uiState.pinnedFiles.isNotEmpty()) {
                PinnedFilesPanel(
                    files = uiState.pinnedFiles,
                    onRemove = { onEvent(ChatEvent.RemovePinnedFile(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Message list
            var menuMessageId by remember { mutableStateOf<String?>(null) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = if (panelHeight > 0) (panelHeight.dp) else 0.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { menuMessageId = null })
                    }
                    .pointerInput(isAtTop) {
                        if (isAtTop) {
                            detectVerticalDragGestures(
                                onDragStart = { dragOffset = 0f },
                                onDragEnd = {
                                    if (dragOffset > 120) {
                                        isPanelExpanded = true
                                    } else {
                                        isPanelExpanded = false
                                    }
                                    dragOffset = 0f
                                },
                                onDragCancel = { dragOffset = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    dragOffset += dragAmount
                                    change.consume()
                                }
                            )
                        }
                    },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages, key = { it.id + it.content.length + it.parts.size }) { message ->
                    MessageBubble(
                        message = message,
                        modifier = Modifier.fillMaxWidth(),
                        onStartSubChat = onStartSubChat
                    )
                }

                if (uiState.isLoading) {
                    item {
                        LoadingIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }

            // Error snackbar
            uiState.error?.let { error ->
                ErrorBanner(
                    message = error,
                    onDismiss = { onEvent(ChatEvent.DismissError) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }

            // Input area with file attachment, notebook, and speech
            ChatInputArea(
                text = uiState.inputText,
                onTextChange = { onEvent(ChatEvent.UpdateInput(it)) },
                onSend = { onEvent(ChatEvent.SendMessage) },
                onCancel = { onEvent(ChatEvent.Cancel) },
                onAttachFile = { filePickerLauncher.launch("*/*") },
                onAttachStack = { showStackPicker = true },
                onSpeechInput = { showSpeechInput = true },
                isLoading = uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }

    // Stack picker dialog
    if (showStackPicker && stackPickerViewModel != null) {
        PickerDialog(
            viewModel = stackPickerViewModel,
            title = "Attach Stack",
            onItemSelected = { item ->
                onEvent(ChatEvent.AttachStack(item.id))
                showStackPicker = false
            },
            onDismiss = { showStackPicker = false },
            emptyMessage = "No stacks available. Create one in the Think tab."
        )
    }

    // Speech input dialog
    if (showSpeechInput) {
        SpeechInputDialog(
            onResult = { text ->
                if (text.isNotBlank()) {
                    onEvent(ChatEvent.UpdateInput(uiState.inputText + text))
                }
                showSpeechInput = false
            },
            onDismiss = { showSpeechInput = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    hasContext: Boolean,
    showContext: Boolean,
    onToggleContext: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateBack: () -> Unit,
    isModelReady: Boolean = false,
    isModelLoading: Boolean = false,
    isModelUnloading: Boolean = false,
    modelStatus: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    onToggleModel: (() -> Unit)? = null,
    toolsEnabled: Boolean = true,
    thinkingEnabled: Boolean = false,
    onToggleTools: () -> Unit = {},
    onToggleThinking: () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Conversation history"
                )
            }
        },
        actions = {
            // Tools toggle
            IconButton(onClick = onToggleTools) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = if (toolsEnabled) "Tools enabled" else "Tools disabled",
                    tint = if (toolsEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            // Thinking toggle
            IconButton(onClick = onToggleThinking) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = if (thinkingEnabled) "Thinking enabled" else "Thinking disabled",
                    tint = if (thinkingEnabled) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            val statusColors = when {
                modelStatus == ModelStatus.READY || isModelReady ->
                    StatusColors(
                        dotColor = ComposeColor(0xFF4CAF50),
                        text = "ON",
                        containerColor = ComposeColor(0xFF4CAF50).copy(alpha = 0.2f),
                        textColor = ComposeColor(0xFF2E7D32),
                        clickable = true
                    )
                modelStatus == ModelStatus.LOADING || isModelLoading ->
                    StatusColors(
                        dotColor = ComposeColor(0xFFFFC107),
                        text = "Loading...",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = false
                    )
                isModelUnloading ->
                    StatusColors(
                        dotColor = ComposeColor(0xFFFFC107),
                        text = "Unloading...",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = false
                    )
                modelStatus == ModelStatus.DOWNLOADING ->
                    StatusColors(
                        dotColor = ComposeColor(0xFFFFC107),
                        text = "Downloading...",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = false
                    )
                modelStatus == ModelStatus.ERROR ->
                    StatusColors(
                        dotColor = ComposeColor(0xFFF44336),
                        text = "ERR",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = false
                    )
                modelStatus == ModelStatus.DOWNLOADED ->
                    StatusColors(
                        dotColor = ComposeColor(0xFF9E9E9E),
                        text = "DL'd",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = true
                    )
                else ->
                    StatusColors(
                        dotColor = ComposeColor(0xFF9E9E9E),
                        text = "OFF",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        clickable = false
                    )
            }
            StatusIndicator(
                text = statusColors.text,
                dotColor = statusColors.dotColor,
                containerColor = statusColors.containerColor,
                textColor = statusColors.textColor,
                isClickable = statusColors.clickable && onToggleModel != null,
                onClick = onToggleModel
            )
            if (hasContext) {
                IconButton(onClick = onToggleContext) {
                    Icon(
                        imageVector = if (showContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showContext) "Hide context" else "Show context"
                    )
                }
            }
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close chat",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun ConversationHistoryDrawer(
    conversations: List<ChatConversation>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
                .widthIn(min = 250.dp, max = 300.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversations",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onNewConversation) {
                    Icon(Icons.Default.Add, contentDescription = "New conversation")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(conversations) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        isSelected = conversation.id == currentConversationId,
                        onClick = { onSelectConversation(conversation.id) },
                        onDelete = { onDeleteConversation(conversation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: ChatConversation,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = "${conversation.messageCount} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachedStacksPanel(
    stacks: List<AttachedStack>,
    onDetach: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Attached Stacks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                stacks.forEach { stack ->
                    InputChip(
                        selected = true,
                        onClick = { },
                        label = { Text(stack.title, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Book,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { onDetach(stack.stackId) }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Detach", modifier = Modifier.size(12.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedFilesPanel(
    files: List<PinnedFile>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Pinned Files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                files.forEach { file ->
                    InputChip(
                        selected = false,
                        onClick = { },
                        label = { Text(file.name, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { onRemove(file.uri) }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextPanel(
    chunks: List<ChunkEntity>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Retrieved ${chunks.size} context chunks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            chunks.take(3).forEach { chunk ->
                Text(
                    text = chunk.text.take(100) + if (chunk.text.length > 100) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            if (chunks.size > 3) {
                Text(
                    text = "+${chunks.size - 3} more chunks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onStartSubChat: ((String) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val isAssistant = !isUser
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    var showMenu by remember { mutableStateOf(false) }
    var showTimestamp by remember { mutableStateOf(false) }
    var showRawMessage by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val contentText = remember(message) {
        if (isUser || message.parts.isEmpty()) {
            message.content
        } else {
            message.parts.filterIsInstance<MessagePart.TextPart>().joinToString("\n") { it.text }
        }
    }

    Row(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -120f) {
                            showMenu = true
                        } else if (offsetX > 120f) {
                            showTimestamp = !showTimestamp
                        }
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    }
                )
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column {
            Row(
                modifier = Modifier
                    .then(if (isAssistant) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp))
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (isUser) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (message.parts.isEmpty()) {
                        MarkdownText(
                            text = message.content,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    } else {
                        message.parts.forEach { part ->
                            when (part) {
                                is MessagePart.TextPart -> {
                                    MarkdownText(
                                        text = part.text,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                                is MessagePart.ReasoningPart -> {
                                    ReasoningBlock(part = part)
                                }
                                is MessagePart.ToolCallPart -> {
                                    ToolCallBlock(part = part)
                                }
                                is MessagePart.ToolResponsePart -> {
                                    ToolResponseBlock(part = part)
                                }
                                is MessagePart.ImagePart -> {
                                    Text(
                                        text = "[Image: ${part.description.take(100)}]",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                is MessagePart.AudioPart -> {
                                    Text(
                                        text = "[Audio: ${part.transcription.take(100)}]",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    if (message.sources.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${message.sources.size} sources",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Box {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(contentText))
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Show all message") },
                            onClick = {
                                showRawMessage = true
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                        )
                        if (isAssistant) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, contentText)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share message"))
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )
                            if (onStartSubChat != null) {
                                DropdownMenuItem(
                                    text = { Text("Start sub-chat") },
                                    onClick = {
                                        onStartSubChat(message.content)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Menu, contentDescription = null) }
                                )
                            }
                        }
                    }
                }
            }

            if (showTimestamp) {
                Text(
                    text = dateFormat.format(java.util.Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp)
                )
            }

            if (showRawMessage) {
                RawMessageDialog(
                    content = message.content,
                    onDismiss = { showRawMessage = false }
                )
            }
        }
    }
}

@Composable
private fun ReasoningBlock(part: MessagePart.ReasoningPart) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (part.isComplete) "Thinking" else "Thinking...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = part.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallBlock(part: MessagePart.ToolCallPart) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (part.status) {
                        ToolStatus.PENDING -> MaterialTheme.colorScheme.primary
                        ToolStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                        ToolStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        ToolStatus.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tool: ${part.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = part.rawJson,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolResponseBlock(part: MessagePart.ToolResponsePart) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (part.isError)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = if (part.isError) "Error" else "Result",
                style = MaterialTheme.typography.labelSmall,
                color = if (part.isError)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = part.output,
                style = MaterialTheme.typography.bodySmall,
                color = if (part.isError)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ChatPromptsPanel(
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "System Prompt",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "This overrides the default system prompt for this conversation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            label = { Text("System Prompt") },
            placeholder = { Text("You are a helpful AI assistant...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onAttachFile: () -> Unit,
    onAttachStack: () -> Unit,
    onSpeechInput: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isLoading) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(onClick = onAttachFile) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach file"
                )
            }

            IconButton(onClick = onAttachStack) {
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = "Attach stack"
                )
            }

            IconButton(onClick = onSpeechInput) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice input"
                )
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about your documents...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!isLoading) onSend() }),
            singleLine = true,
            enabled = !isLoading
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getMimeType(uri: Uri): String {
    // Best-effort mime type from URI extension
    val path = uri.toString().lowercase()
    return when {
        path.endsWith(".pdf") -> "application/pdf"
        path.endsWith(".txt") -> "text/plain"
        path.endsWith(".md") -> "text/markdown"
        path.endsWith(".py") -> "text/x-python"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".kt") -> "text/x-kotlin"
        path.endsWith(".java") -> "text/x-java"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") -> "image/*"
        path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".m4a") -> "audio/*"
        else -> "application/octet-stream"
    }
}

/**
 * Dialog for speech input using Android SpeechRecognizer.
 */
@Composable
private fun SpeechInputDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var recognizedText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val speechRecognizer = remember { com.penpal.core.processing.speech.StreamingSpeechRecognizer(context) }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    LaunchedEffect(Unit) {
        if (!speechRecognizer.isAvailable()) {
            errorMessage = "Speech recognition not available on this device"
            return@LaunchedEffect
        }
        isListening = true
        speechRecognizer.streamTranscription(timeoutMs = 30000).collect { result ->
            when (result) {
                is com.penpal.core.processing.speech.SpeechResult.Partial -> {
                    recognizedText = result.text
                }
                is com.penpal.core.processing.speech.SpeechResult.Final -> {
                    recognizedText = result.text
                    isListening = false
                }
                is com.penpal.core.processing.speech.SpeechResult.Error -> {
                    errorMessage = result.message
                    isListening = false
                }
                else -> {}
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            speechRecognizer.stopListening()
            onResult(recognizedText)
            onDismiss()
        },
        title = { Text(if (isListening) "Listening..." else "Voice Input") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isListening) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Speak now")
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (recognizedText.isNotBlank()) {
                    Text(recognizedText)
                } else {
                    Text("No speech recognized")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                speechRecognizer.stopListening()
                onResult(recognizedText)
                onDismiss()
            }) {
                Text("Use Text")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                speechRecognizer.stopListening()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RawMessageDialog(
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Full Message") },
        text = {
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
