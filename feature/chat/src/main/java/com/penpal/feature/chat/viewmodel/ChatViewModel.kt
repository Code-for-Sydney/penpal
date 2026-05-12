package com.penpal.feature.chat.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.penpal.core.ai.inference.InferenceBridge
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ai.messaging.MessagePart
import com.penpal.core.ai.tools.ChatMessageInfo
import com.penpal.core.ai.tools.MessageRole
import com.penpal.core.ai.tools.ToolExecutionContext
import com.penpal.core.ai.tools.ToolRegistry
import com.penpal.core.ai.tools.ToolResult
import com.penpal.core.ai.vectorstore.VectorStoreRepository
import com.penpal.core.data.chat.ChatConversationDao
import com.penpal.core.data.chat.ChatConversationEntity
import com.penpal.core.data.chat.ChatMessageDao
import com.penpal.core.data.chat.ChatMessageEntity
import com.penpal.core.data.knowledge.ChunkEntity
import com.penpal.core.data.stack.StackDao
import com.penpal.core.data.stack.StackEntity
import com.penpal.core.processing.worker.WorkerLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val retrievedContext: List<ChunkEntity> = emptyList(),
    val isModelReady: Boolean = false,
    val modelStatus: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val needsTitleGeneration: Boolean = false,
    // Retry functionality
    val pendingRetryMessage: String? = null,
    val pendingRetryError: String? = null,
    // Conversation history
    val conversations: List<ChatConversation> = emptyList(),
    val currentConversationId: String? = null,
    val currentConversationTitle: String = "New Chat",
    // Stack attachments
    val attachedStacks: List<AttachedStack> = emptyList(),
    val pinnedFiles: List<PinnedFile> = emptyList(),
    // System prompt (per-conversation override + default)
    val systemPrompt: String = "",
    val defaultSystemPrompt: String = "",
    // Feature toggles
    val toolsEnabled: Boolean = true,
    val thinkingEnabled: Boolean = false,
    // Individual tool toggles
    val availableTools: List<ToolInfo> = emptyList(),
    val enabledTools: Map<String, Boolean> = emptyMap()
)

data class ToolInfo(
    val name: String,
    val description: String
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val parts: List<MessagePart> = emptyList(),
    val sources: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatConversation(
    val id: String,
    val title: String,
    val parentId: String? = null,  // For sub-chats (1 level only)
    val messageCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AttachedStack(
    val stackId: String,
    val title: String
)

data class PinnedFile(
    val uri: String,
    val name: String,
    val mimeType: String,
    val notebookId: String
)

sealed class ChatEvent {
    data class UpdateInput(val text: String) : ChatEvent()
    data object SendMessage : ChatEvent()
    data object ClearChat : ChatEvent()
    data object DismissError : ChatEvent()
    data object RetryLastMessage : ChatEvent()
    // Conversation management
    data class CreateConversation(val title: String = "New Chat", val parentId: String? = null) : ChatEvent()
    data class LoadConversation(val conversationId: String) : ChatEvent()
    data class DeleteConversation(val conversationId: String) : ChatEvent()
    // Stack attachment
    data class AttachStack(val stackId: String) : ChatEvent()
    data class DetachStack(val stackId: String) : ChatEvent()
    // File handling
    data class AddFile(val uri: Uri, val mimeType: String) : ChatEvent()
    data class RemovePinnedFile(val uri: String) : ChatEvent()
    // System prompt
    data class UpdateSystemPrompt(val prompt: String) : ChatEvent()
    data object ToggleModel : ChatEvent()
    data object Cancel : ChatEvent()
    // Feature toggles
    data object ToggleTools : ChatEvent()
    data object ToggleThinking : ChatEvent()
    data class ToggleTool(val toolName: String) : ChatEvent()
    // Message actions
    data class ShareMessage(val messageId: String) : ChatEvent()
}

class ChatViewModel(
    private val vectorStore: VectorStoreRepository,
    private val inferenceBridge: InferenceBridge,
    private val application: android.app.Application,
    private val chatMessageDao: ChatMessageDao? = null,
    private val chatConversationDao: ChatConversationDao? = null,
    private val stackDao: StackDao? = null,
    private val workerLauncher: WorkerLauncher? = null,
    private val onLoadModel: (() -> Unit)? = null,
    private val toolRegistry: ToolRegistry? = null
) : ViewModel() {

    private val gson = Gson()
    private val toolExecutorMaxIterations = 3

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var pendingAssistantMessageId: String? = null

    init {
        viewModelScope.launch {
            // Load default system prompt from SharedPreferences
            val prefs = application.getSharedPreferences("penpal_app_prefs", android.content.Context.MODE_PRIVATE)
            val defaultSystemPrompt = prefs.getString("default_system_prompt", "") ?: ""
            val toolsEnabled = prefs.getBoolean("tools_enabled", true)
            val thinkingEnabled = prefs.getBoolean("thinking_enabled", false)
            
            // Load available tools from registry
            val availableTools = toolRegistry?.getAll()?.map { tool ->
                ToolInfo(name = tool.name, description = tool.description)
            } ?: emptyList()
            
            // Load individual tool enabled states (default to true for all)
            val enabledTools = availableTools.associate { tool ->
                val enabled = prefs.getBoolean("tool_enabled_${tool.name}", true)
                tool.name to enabled
            }
            
            _uiState.update { it.copy(
                defaultSystemPrompt = defaultSystemPrompt,
                systemPrompt = defaultSystemPrompt,
                toolsEnabled = toolsEnabled,
                thinkingEnabled = thinkingEnabled,
                availableTools = availableTools,
                enabledTools = enabledTools
            ) }

            inferenceBridge.isReady.collect { isReady ->
                val previousReady = _uiState.value.isModelReady
                _uiState.update { it.copy(isModelReady = isReady) }

                // Auto-retry when model becomes ready
                if (isReady && !previousReady && _uiState.value.pendingRetryMessage != null) {
                    retryLastMessage()
                }
            }
        }

        viewModelScope.launch {
            // Collect current value first
            _uiState.update { it.copy(modelStatus = inferenceBridge.modelStatus.value) }
            // Then collect updates
            inferenceBridge.modelStatus.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
            }
        }

        loadConversations()
    }

    private fun retryLastMessage() {
        val pendingMessage = _uiState.value.pendingRetryMessage
        if (pendingMessage == null || !_uiState.value.isModelReady) {
            return
        }

        // Check if there's already an assistant message (from "Loading AI model...") to continue
        val allAssistantMessages = _uiState.value.messages.filter { it.role == MessageRole.ASSISTANT }
        val existingAssistantMessage = allAssistantMessages.lastOrNull { it.content.contains("Loading AI model") }
        
        // Clear pending retry message first
        _uiState.update { it.copy(pendingRetryMessage = null, pendingRetryError = null) }
        
        if (existingAssistantMessage != null) {
            // Continue with the existing message - just run inference
            runInferenceWithPendingMessage(pendingMessage)
        } else {
            // Fallback to normal send
            _uiState.update { it.copy(inputText = pendingMessage) }
            sendMessage()
        }
    }

    private fun runInferenceWithPendingMessage(contextPrompt: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, retrievedContext = emptyList()) }

                inferenceBridge.runInferenceFlowParts(contextPrompt)
                    .catch { error ->
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                error = error.message ?: "Inference error",
                                retrievedContext = emptyList()
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val lastMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val finalContent = lastMessage?.content
                        _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }

                        if (finalContent != null && finalContent.isNotBlank()) {
                            val conversationId = _uiState.value.currentConversationId
                            if (conversationId != null) {
                                viewModelScope.launch {
                                    chatMessageDao?.insert(
                                        ChatMessageEntity(
                                            id = pendingAssistantMessageId ?: UUID.randomUUID().toString(),
                                            conversationId = conversationId,
                                            role = "ASSISTANT",
                                            content = finalContent,
                                            sourcesJson = "[]",
                                            createdAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }

                        logLastAssistantReply()

                        if (cause == null && _uiState.value.error == null) {
                            val nextUserMessage = findNextPendingUserMessage()
                            if (nextUserMessage != null) {
                                startTurnForUserMessage(nextUserMessage)
                            }
                        }
                    }
                    .collect { parts ->
                        updateLastAssistantMessage(parts, emptyList())
                    }

            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = "Failed to process message: ${e.message}",
                        retrievedContext = emptyList()
                    )
                }
            }
        }
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UpdateInput -> _uiState.update { it.copy(inputText = event.text) }
            is ChatEvent.SendMessage -> sendMessage()
            is ChatEvent.ClearChat -> clearChat()
            is ChatEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is ChatEvent.RetryLastMessage -> retryLastMessage()
            is ChatEvent.CreateConversation -> createNewConversation(event.title, event.parentId)
            is ChatEvent.LoadConversation -> loadConversation(event.conversationId)
            is ChatEvent.DeleteConversation -> deleteConversation(event.conversationId)
            is ChatEvent.AttachStack -> attachStack(event.stackId)
            is ChatEvent.DetachStack -> detachStack(event.stackId)
            is ChatEvent.AddFile -> addFileToChat(event.uri, event.mimeType)
            is ChatEvent.RemovePinnedFile -> removePinnedFile(event.uri)
            is ChatEvent.UpdateSystemPrompt -> {
                _uiState.update { it.copy(systemPrompt = event.prompt) }
                // Persist to database
                viewModelScope.launch {
                    _uiState.value.currentConversationId?.let { convId ->
                        chatConversationDao?.updateSystemPrompt(convId, event.prompt, System.currentTimeMillis())
                    }
                }
            }
            is ChatEvent.ToggleModel -> {
                // ToggleModel is handled at MainScreen level to avoid duplication
                // The indicator in ChatTopBar uses onToggleModel directly from MainScreen
            }
            is ChatEvent.ToggleTools -> {
                val newState = !_uiState.value.toolsEnabled
                _uiState.update { it.copy(toolsEnabled = newState) }
                application.getSharedPreferences("penpal_app_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("tools_enabled", newState).apply()
            }
            is ChatEvent.ToggleTool -> {
                val currentEnabled = _uiState.value.enabledTools[event.toolName] ?: true
                val newEnabled = !currentEnabled
                _uiState.update { state ->
                    state.copy(enabledTools = state.enabledTools + (event.toolName to newEnabled))
                }
                application.getSharedPreferences("penpal_app_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("tool_enabled_${event.toolName}", newEnabled).apply()
            }
            is ChatEvent.ToggleThinking -> {
                val newState = !_uiState.value.thinkingEnabled
                _uiState.update { it.copy(thinkingEnabled = newState) }
                application.getSharedPreferences("penpal_app_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("thinking_enabled", newState).apply()
            }
            is ChatEvent.Cancel -> cancelInference()
            is ChatEvent.ShareMessage -> shareMessage(event.messageId)
        }
    }

    private fun cancelInference() {
        inferenceBridge.stopInference()
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                pendingRetryMessage = null,
                pendingRetryError = null
            )
        }
    }

    private fun shareMessage(messageId: String) {
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        val shareText = buildString {
            append("${message.role.name}: ${message.content}")
            if (message.parts.isNotEmpty()) {
                message.parts.forEach { part ->
                    when (part) {
                        is MessagePart.TextPart -> append("\n${part.text}")
                        is MessagePart.ReasoningPart -> append("\n[Thinking: ${part.text}]")
                        is MessagePart.ToolCallPart -> append("\n[Tool: ${part.name}]")
                        is MessagePart.ToolResponsePart -> append("\n[Tool Result: ${part.output}]")
                        else -> {}
                    }
                }
            }
        }
        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "Share message")
        shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(shareIntent)
    }

    private fun loadConversations() {
        chatConversationDao ?: return
        viewModelScope.launch {
            chatConversationDao.getAllConversations().collect { entities ->
                val conversations = entities.map { entity ->
                    ChatConversation(
                        id = entity.id,
                        title = entity.title,
                        parentId = entity.parentId,
                        updatedAt = entity.updatedAt
                    )
                }
                _uiState.update { it.copy(conversations = conversations) }

                // Set first conversation as current if none selected
                if (_uiState.value.currentConversationId == null && conversations.isNotEmpty()) {
                    loadConversation(conversations.first().id)
                }
            }
        }
    }

    private fun createNewConversation(title: String, parentId: String? = null) {
        chatConversationDao ?: return
        viewModelScope.launch {
            val conversationId = UUID.randomUUID().toString()
            val conversation = ChatConversationEntity(
                id = conversationId,
                title = title,
                parentId = parentId
            )
            chatConversationDao.insert(conversation)
            inferenceBridge.resetConversation()
            _uiState.update {
                it.copy(
                    currentConversationId = conversationId,
                    currentConversationTitle = title,
                    messages = emptyList(),
                    attachedStacks = emptyList(),
                    pinnedFiles = emptyList()
                )
            }
        }
    }

    private fun loadConversation(conversationId: String) {
        chatConversationDao ?: return
        viewModelScope.launch {
            val conversation = chatConversationDao.getConversation(conversationId)
            if (conversation != null) {
                inferenceBridge.resetConversation()
                // Load attached stacks
                val stackIds = try {
                    gson.fromJson(conversation.stackIdsJson, Array<String>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }

                val attachedStacks = stackIds.mapNotNull { stackId ->
                    stackDao?.getStack(stackId)?.let { entity ->
                        AttachedStack(stackId = entity.id, title = entity.title)
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        currentConversationId = conversationId,
                        currentConversationTitle = conversation.title,
                        attachedStacks = attachedStacks,
                        messages = emptyList(),
                        systemPrompt = conversation.systemPrompt
                    )
                }

                // Load messages
                chatMessageDao?.getMessagesForConversation(conversationId)?.collect { entities ->
                    val msgs = entities.map { entity ->
                        ChatMessage(
                            id = entity.id,
                            role = if (entity.role == "USER") MessageRole.USER else MessageRole.ASSISTANT,
                            content = entity.content.replace("\\n", "\n"),
                            sources = try {
                                gson.fromJson(entity.sourcesJson, Array<String>::class.java).toList()
                            } catch (_: Exception) {
                                emptyList()
                            },
                            timestamp = entity.createdAt
                        )
                    }
                    _uiState.update { state ->
                        // If there's a pending assistant message (isLoading=true), preserve it
                        val pendingAssistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isEmpty() }
                        if (pendingAssistant != null && state.isLoading) {
                            state.copy(messages = msgs + pendingAssistant)
                        } else {
                            state.copy(messages = msgs)
                        }
                    }
                }
            }
        }
    }

    private fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatMessageDao?.deleteForConversation(conversationId)
            chatConversationDao?.delete(conversationId)

            if (_uiState.value.currentConversationId == conversationId) {
                val remaining = _uiState.value.conversations.filter { it.id != conversationId }
                if (remaining.isNotEmpty()) {
                    loadConversation(remaining.first().id)
                } else {
                    createNewConversation("New Chat")
                }
            }
        }
    }

    private fun attachStack(stackId: String) {
        stackDao ?: return
        chatConversationDao ?: return
        viewModelScope.launch {
            val notebook = stackDao.getStack(stackId) ?: return@launch
            val currentId = _uiState.value.currentConversationId ?: return@launch

            val conversation = chatConversationDao.getConversation(currentId)
            if (conversation != null) {
                val currentIds = try {
                    gson.fromJson(conversation.stackIdsJson, Array<String>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (!currentIds.contains(stackId)) {
                    val newIds = currentIds + stackId
                    chatConversationDao.updateStackIds(
                        currentId,
                        gson.toJson(newIds),
                        System.currentTimeMillis()
                    )

                    val updatedAttached = _uiState.value.attachedStacks + AttachedStack(
                        stackId = notebook.id,
                        title = notebook.title
                    )
                    _uiState.update { it.copy(attachedStacks = updatedAttached) }
                }
            }
        }
    }

    private fun detachStack(stackId: String) {
        chatConversationDao ?: return
        viewModelScope.launch {
            val currentId = _uiState.value.currentConversationId ?: return@launch
            val conversation = chatConversationDao.getConversation(currentId) ?: return@launch

            val currentIds = try {
                gson.fromJson(conversation.stackIdsJson, Array<String>::class.java).toList()
            } catch (_: Exception) {
                emptyList()
            }

            val newIds = currentIds.filter { it != stackId }
            chatConversationDao.updateStackIds(
                currentId,
                gson.toJson(newIds),
                System.currentTimeMillis()
            )

            _uiState.update { state ->
                state.copy(attachedStacks = state.attachedStacks.filter { it.stackId != stackId })
            }
        }
    }

    private fun addFileToChat(uri: Uri, mimeType: String) {
        workerLauncher ?: return
        stackDao ?: return
        chatConversationDao ?: return

        viewModelScope.launch {
            val currentId = _uiState.value.currentConversationId ?: return@launch
            val fileName = uri.lastPathSegment ?: "file"

            // Create or use existing notebook for this chat
            val conversation = chatConversationDao.getConversation(currentId)
            val notebookId = conversation?.let { conv ->
                try {
                    gson.fromJson(conv.stackIdsJson, Array<String>::class.java).firstOrNull()
                } catch (_: Exception) {
                    null
                }
            } ?: UUID.randomUUID().toString()

            // Check if stack exists
            val existingNotebook = stackDao.getStack(notebookId)
            if (existingNotebook == null) {
                val notebook = StackEntity(
                    id = notebookId,
                    title = "Chat Files: ${_uiState.value.currentConversationTitle}",
                    blocksJson = "[]"
                )
                stackDao.insert(notebook)

                chatConversationDao.updateStackIds(
                    currentId,
                    gson.toJson(listOf(notebookId)),
                    System.currentTimeMillis()
                )
            }

            // Add file as process block to stack
            val notebook = stackDao.getStack(notebookId)
            if (notebook != null) {
                val blocks = try {
                    val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                    gson.fromJson<List<Map<String, Any>>>(notebook.blocksJson, type)
                } catch (_: Exception) {
                    emptyList()
                } ?: emptyList()

                val newBlock = mapOf(
                    "type" to "process",
                    "id" to UUID.randomUUID().toString(),
                    "sourceUri" to uri.toString(),
                    "sourceType" to when {
                        mimeType.contains("pdf") -> "PDF"
                        mimeType.contains("image") -> "IMAGE"
                        mimeType.contains("audio") -> "AUDIO"
                        mimeType.contains("text") -> "CODE"
                        else -> "FILE"
                    },
                    "status" to "PENDING",
                    "extractedText" to "",
                    "errorMessage" to ""
                )

                val updatedBlocks = blocks + newBlock
                stackDao.updateBlocks(notebookId, gson.toJson(updatedBlocks), System.currentTimeMillis())

                // Enqueue processing
                val processMimeType = when {
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("image") -> "image"
                    mimeType.contains("audio") -> "audio"
                    mimeType.contains("text") -> "code"
                    else -> "pdf"
                }
                workerLauncher.enqueue(uri.toString(), processMimeType, "FULL_TEXT")

                // Pin file to conversation
                val pinnedFile = PinnedFile(
                    uri = uri.toString(),
                    name = fileName,
                    mimeType = mimeType,
                    notebookId = notebookId
                )
                _uiState.update { state ->
                    state.copy(pinnedFiles = state.pinnedFiles + pinnedFile)
                }
            }
        }
    }

    private fun removePinnedFile(uri: String) {
        _uiState.update { state ->
            state.copy(pinnedFiles = state.pinnedFiles.filter { it.uri != uri })
        }
    }

    private fun sendMessage() {
        val currentInput = _uiState.value.inputText.trim()
        if (currentInput.isEmpty()) return

        val conversationId = _uiState.value.currentConversationId
        if (conversationId == null) {
            createNewConversation("New Chat")
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = currentInput
        )

        // If model is already responding, queue this message
        if (_uiState.value.isLoading) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + userMessage,
                    inputText = ""
                )
            }

            viewModelScope.launch {
                chatMessageDao?.insert(
                    ChatMessageEntity(
                        id = userMessage.id,
                        conversationId = conversationId,
                        role = "USER",
                        content = userMessage.content,
                        sourcesJson = "[]",
                        createdAt = userMessage.timestamp
                    )
                )
            }
            return
        }

        pendingAssistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = pendingAssistantMessageId!!,
            role = MessageRole.ASSISTANT,
            content = "",
            sources = emptyList()
        )

        _uiState.update { state ->
            val newMessages = state.messages + userMessage + assistantMessage
            state.copy(
                messages = newMessages,
                inputText = "",
                isLoading = true,
                error = null
            )
        }

        // Save user message to DB
        viewModelScope.launch {
            chatMessageDao?.insert(
                ChatMessageEntity(
                    id = userMessage.id,
                    conversationId = conversationId,
                    role = "USER",
                    content = userMessage.content,
                    sourcesJson = "[]",
                    createdAt = userMessage.timestamp
                )
            )

            // Set temporary title from first message - will be replaced by AI-generated title
            if (_uiState.value.messages.size == 2) {
                val tempTitle = currentInput.take(30)
                chatConversationDao?.updateTitle(conversationId, tempTitle, System.currentTimeMillis())
                _uiState.update { it.copy(currentConversationTitle = tempTitle, needsTitleGeneration = true) }
            }
        }

        viewModelScope.launch {
            try {
                // Retrieve context from vector store
                val relevantChunks = vectorStore.similaritySearch(currentInput, topK = 6)

                // Also retrieve from attached stacks
                val attachedStackChunks = mutableListOf<ChunkEntity>()
                _uiState.value.attachedStacks.forEach { stack ->
                    val chunks = vectorStore.getChunksForSource(stack.stackId)
                    attachedStackChunks.addAll(chunks)
                }

                // Combine and deduplicate
                val allChunks = (relevantChunks + attachedStackChunks)
                    .distinctBy { it.id }
                    .sortedByDescending { chunk ->
                        chunk.text.length
                    }
                    .take(10)

                _uiState.update { it.copy(retrievedContext = allChunks) }

                val contextPrompt = buildPrompt(currentInput, allChunks, userMessage.id)
                val sourceIds = allChunks.map { it.id }

                val isReadyNow = inferenceBridge.isReady.value
                
                if (!isReadyNow) {
                    // Auto-load the model
                    onLoadModel?.invoke()
                    // Show loading message while model loads
                    updateLastAssistantMessage(
                        listOf(MessagePart.TextPart("Loading AI model...")),
                        sourceIds
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            retrievedContext = emptyList(),
                            pendingRetryMessage = currentInput,
                            pendingRetryError = null
                        )
                    }
                    return@launch
                }

                inferenceBridge.runInferenceFlowParts(contextPrompt)
                    .catch { error ->
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                error = error.message ?: "Inference error",
                                retrievedContext = emptyList()
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val lastMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val finalParts = lastMessage?.parts ?: emptyList()
                        val finalContent = lastMessage?.content

                        // Execute tool calls and continue inference if needed
                        val toolCalls = finalParts.filterIsInstance<MessagePart.ToolCallPart>()
                        if (toolCalls.isNotEmpty() && toolRegistry != null) {
                            // Execute tools and continue inference loop
                            continueWithToolResults(toolCalls, currentInput, allChunks, sourceIds)
                            return@onCompletion
                        }

                        _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }

                        // Save assistant message to DB
                        if (finalContent != null && finalContent.isNotBlank()) {
                            val conversationId = _uiState.value.currentConversationId
                            if (conversationId != null) {
                                viewModelScope.launch {
                                    chatMessageDao?.insert(
                                        ChatMessageEntity(
                                            id = pendingAssistantMessageId ?: UUID.randomUUID().toString(),
                                            conversationId = conversationId,
                                            role = "ASSISTANT",
                                            content = finalContent,
                                            sourcesJson = gson.toJson(sourceIds),
                                            createdAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }

                        logLastAssistantReply()

                        // Generate title after first successful exchange
                        if (_uiState.value.needsTitleGeneration) {
                            generateConversationTitle()
                            _uiState.update { it.copy(needsTitleGeneration = false) }
                        }

                        // Continue with queued user messages if any
                        if (cause == null && _uiState.value.error == null) {
                            val nextUserMessage = findNextPendingUserMessage()
                            if (nextUserMessage != null) {
                                startTurnForUserMessage(nextUserMessage)
                            }
                        }
                    }
                    .collect { parts ->
                        updateLastAssistantMessage(parts, sourceIds)
                    }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to process message", e)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = "Failed to process message: ${e.message}",
                        retrievedContext = emptyList()
                    )
                }
            }
        }
    }

    private suspend fun executeToolAndContinue(
        toolCall: MessagePart.ToolCallPart,
        currentPrompt: String,
        contextPrompt: String,
        allChunks: List<ChunkEntity>
    ): Pair<String, List<MessagePart>> {
        val registry = toolRegistry
        if (registry == null || !registry.has(toolCall.name)) {
            return currentPrompt to emptyList()
        }

        val conversationHistory = _uiState.value.messages.map { msg ->
            ChatMessageInfo(
                id = msg.id,
                role = if (msg.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT,
                content = msg.content,
                timestamp = msg.timestamp
            )
        }

        val attachedStackIds = _uiState.value.attachedStacks.map { it.stackId }

        val toolContext = ToolExecutionContext(
            toolCallId = toolCall.callId,
            arguments = toolCall.arguments,
            conversationHistory = conversationHistory,
            attachedStackIds = attachedStackIds
        )

        val result = registry.execute(toolCall.name, toolContext)

        val toolResponseText = when (result) {
            is ToolResult.Success -> result.output
            is ToolResult.Error -> "Error: ${result.message}"
            is ToolResult.StreamOutput -> {
                val outputBuilder = StringBuilder()
                result.chunks.collect { chunk ->
                    outputBuilder.append(chunk)
                }
                outputBuilder.toString()
            }
        }

        val toolResponseJson = """
            {
                "call_id": "${toolCall.callId}",
                "name": "${toolCall.name}",
                "output": ${gson.toJson(toolResponseText)}
            }
        """.trimIndent()

        val responsePart = MessagePart.ToolResponsePart(
            name = toolCall.name,
            callId = toolCall.callId,
            output = toolResponseText,
            isError = result is ToolResult.Error
        )

        val continuationPrompt = buildToolContinuationPrompt(
            currentPrompt,
            toolCall,
            toolResponseJson
        )

        return continuationPrompt to listOf(responsePart)
    }

    private fun buildToolContinuationPrompt(
        originalResponse: String,
        toolCall: MessagePart.ToolCallPart,
        toolResponse: String
    ): String {
        return buildString {
            appendLine("<|turn>model")
            appendLine(originalResponse)
            appendLine("<|tool_call>call:${toolCall.name}{}")
            appendLine("<tool_call|>")
            appendLine("<|tool_response>response:${toolCall.name}{result:<|\"|>$toolResponse<|\"|>}")
            appendLine("<tool_response|>")
            append("<|turn>model")
        }
    }

    private fun executeToolCallsAfterInference(toolCalls: List<MessagePart.ToolCallPart>) {
        viewModelScope.launch {
            val registry = toolRegistry ?: return@launch

            for (toolCall in toolCalls) {

                val conversationHistory = _uiState.value.messages.map { msg ->
                    ChatMessageInfo(
                        id = msg.id,
                        role = if (msg.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT,
                        content = msg.content,
                        timestamp = msg.timestamp
                    )
                }

                val attachedStackIds = _uiState.value.attachedStacks.map { it.stackId }

                val toolContext = ToolExecutionContext(
                    toolCallId = toolCall.callId,
                    arguments = toolCall.arguments,
                    conversationHistory = conversationHistory,
                    attachedStackIds = attachedStackIds
                )

                val result = registry.execute(toolCall.name, toolContext)

                val toolResponseText = when (result) {
                    is ToolResult.Success -> result.output
                    is ToolResult.Error -> "Error: ${result.message}"
                    is ToolResult.StreamOutput -> {
                        val outputBuilder = StringBuilder()
                        result.chunks.collect { chunk ->
                            outputBuilder.append(chunk)
                        }
                        outputBuilder.toString()
                    }
                }

                val responsePart = MessagePart.ToolResponsePart(
                    name = toolCall.name,
                    callId = toolCall.callId,
                    output = toolResponseText,
                    isError = result is ToolResult.Error
                )

                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                        val lastMsg = messages.last()
                        val updatedParts = lastMsg.parts + responsePart
                        val updatedContent = lastMsg.content + "\n\n[Tool: ${toolCall.name}]\n" + toolResponseText
                        messages[messages.lastIndex] = lastMsg.copy(parts = updatedParts, content = updatedContent)
                    }
                    state.copy(messages = messages)
                }

            }
        }
    }

    private fun continueWithToolResults(
        toolCalls: List<MessagePart.ToolCallPart>,
        userMessage: String,
        context: List<ChunkEntity>,
        sourceIds: List<String>
    ) {
        viewModelScope.launch {
            val registry = toolRegistry ?: return@launch

            val conversationHistory = _uiState.value.messages.map { msg ->
                ChatMessageInfo(
                    id = msg.id,
                    role = if (msg.role == MessageRole.USER) MessageRole.USER else MessageRole.ASSISTANT,
                    content = msg.content,
                    timestamp = msg.timestamp
                )
            }

            val attachedStackIds = _uiState.value.attachedStacks.map { it.stackId }

            val toolResponsesJson = StringBuilder()
            var iteration = 0
            val maxIterations = 3

            while (iteration < maxIterations) {
                iteration++
                android.util.Log.d("ChatViewModel", "=== Tool Loop Iteration $iteration ===")

                for (toolCall in toolCalls) {
                    val toolContext = ToolExecutionContext(
                        toolCallId = toolCall.callId,
                        arguments = toolCall.arguments,
                        conversationHistory = conversationHistory,
                        attachedStackIds = attachedStackIds
                    )

                    val result = registry.execute(toolCall.name, toolContext)

                    val toolResponseText = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                        is ToolResult.StreamOutput -> {
                            val outputBuilder = StringBuilder()
                            result.chunks.collect { chunk ->
                                outputBuilder.append(chunk)
                            }
                            outputBuilder.toString()
                        }
                    }

                    val toolResponseJson = """
                    {
                        "call_id": "${toolCall.callId}",
                        "name": "${toolCall.name}",
                        "output": ${gson.toJson(toolResponseText)}
                    }
                    """.trimIndent()

                    toolResponsesJson.appendLine(toolResponseJson)

                    val responsePart = MessagePart.ToolResponsePart(
                        name = toolCall.name,
                        callId = toolCall.callId,
                        output = toolResponseText,
                        isError = result is ToolResult.Error
                    )

                    _uiState.update { state ->
                        val messages = state.messages.toMutableList()
                        if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                            val lastMsg = messages.last()
                            val updatedParts = lastMsg.parts + responsePart
                            val updatedContent = lastMsg.content + "\n\n[Tool: ${toolCall.name}]\n" + toolResponseText
                            messages[messages.lastIndex] = lastMsg.copy(parts = updatedParts, content = updatedContent)
                        }
                        state.copy(messages = messages)
                    }
                }

                // Build continuation prompt with tool results
                val continuationPrompt = buildToolContinuationPrompt(
                    userMessage,
                    toolResponsesJson.toString()
                )

                // Continue inference with tool results
                val hasMoreToolCalls = runInferenceWithToolResponse(continuationPrompt, context, sourceIds)

                if (!hasMoreToolCalls) {
                    break
                }

                // Check for new tool calls in the latest message
                val latestMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val newToolCalls = latestMessage?.parts?.filterIsInstance<MessagePart.ToolCallPart>() ?: emptyList()

                if (newToolCalls.isEmpty()) {
                    break
                }
            }

            // Done with tool loop - finish up
            _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }

            // Save final message
            val finalMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            if (finalMessage?.content?.isNotBlank() == true) {
                val conversationId = _uiState.value.currentConversationId
                if (conversationId != null) {
                    viewModelScope.launch {
                        chatMessageDao?.insert(
                            ChatMessageEntity(
                                id = pendingAssistantMessageId ?: UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "ASSISTANT",
                                content = finalMessage.content,
                                sourcesJson = gson.toJson(sourceIds),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            // Generate title if needed
            if (_uiState.value.needsTitleGeneration) {
                generateConversationTitle()
                _uiState.update { it.copy(needsTitleGeneration = false) }
            }

            // Continue with queued messages
            val nextUserMessage = findNextPendingUserMessage()
            if (nextUserMessage != null) {
                startTurnForUserMessage(nextUserMessage)
            }
        }
    }

    private suspend fun runInferenceWithToolResponse(
        continuationPrompt: String,
        context: List<ChunkEntity>,
        sourceIds: List<String>
    ): Boolean {
        var hasToolCalls = false

        try {
            inferenceBridge.runInferenceFlowParts(continuationPrompt)
                .catch { error ->
                    android.util.Log.e("ChatViewModel", "Tool continuation inference error: ${error.message}")
                }
                .collect { parts ->
                    updateLastAssistantMessage(parts, sourceIds)
                }

            // Check if the response has new tool calls
            val lastMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            hasToolCalls = lastMessage?.parts?.any { it is MessagePart.ToolCallPart } == true

        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error in tool continuation: ${e.message}")
        }

        return hasToolCalls
    }

    private fun buildToolContinuationPrompt(
        userMessage: String,
        toolResponsesJson: String
    ): String {
        // Get the last assistant message (which contained the tool call)
        val lastAssistantMsg = _uiState.value.messages
            .filter { it.role == MessageRole.ASSISTANT }
            .lastOrNull { it.parts.any { part -> part is MessagePart.ToolCallPart } }

        val assistantContent = lastAssistantMsg?.content ?: ""

        return buildString {
            // System
            appendLine("<|turn>system")
            appendLine("You are a helpful assistant.")
            appendLine("<turn|>")

            // Previous turns (excluding the last assistant message with tool call)
            val historyBeforeTool = _uiState.value.messages
                .dropLastWhile { it.role == MessageRole.ASSISTANT && it.parts.any { p -> p is MessagePart.ToolCallPart } }
            
            historyBeforeTool.forEach { msg ->
                val role = if (msg.role == MessageRole.USER) "user" else "model"
                appendLine("<|turn>$role")
                appendLine(msg.content)
                appendLine("<turn|>")
            }

            // Assistant's tool call
            appendLine("<|turn>model")
            appendLine(assistantContent)
            appendLine("<turn|>")

            // Tool response
            appendLine("<|tool_response|>")
            appendLine(toolResponsesJson)
            appendLine("<tool_response|>")

            // Continue generation
            append("<|turn>model")
        }
    }

    private fun updateLastAssistantMessage(parts: List<MessagePart>, sources: List<String>) {
        // Build content string from text parts for backward compatibility
        val content = buildString {
            for (part in parts) {
                when (part) {
                    is MessagePart.TextPart -> append(part.text)
                    is MessagePart.ReasoningPart -> append(part.text)
                    is MessagePart.ToolCallPart -> append(part.rawJson)
                    is MessagePart.ToolResponsePart -> append(part.output)
                    is MessagePart.ImagePart -> append(part.description)
                    is MessagePart.AudioPart -> append(part.transcription)
                }
            }
        }.trim()

        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                messages[messages.lastIndex] = messages.last().copy(
                    content = content,
                    parts = parts,
                    sources = sources
                )
            }
            state.copy(messages = messages)
        }
    }

    private fun logLastAssistantReply() {
        val lastAssistant = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
        if (lastAssistant != null) {
            Log.d(
                "ChatViewModel",
                "Last assistant reply: ${lastAssistant.content.take(400).replace("\n", " ").trim()}"
            )
        } else {
            Log.d("ChatViewModel", "No assistant reply available yet")
        }
    }

    private fun clearChat() {
        inferenceBridge.resetConversation()
        pendingAssistantMessageId = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                retrievedContext = emptyList()
            )
        }

        // Clear messages from current conversation in DB
        viewModelScope.launch {
            _uiState.value.currentConversationId?.let { id ->
                chatMessageDao?.deleteForConversation(id)
            }
        }
    }

    /**
     * Generates a concise title for the conversation using the LLM.
     * Called after the first successful user-assistant exchange.
     */
    private fun generateConversationTitle() {
        val conversationId = _uiState.value.currentConversationId ?: return
        val messages = _uiState.value.messages
        if (messages.size < 2) return

        val userMessage = messages.find { it.role == MessageRole.USER }?.content ?: return
        val assistantMessage = messages.findLast { it.role == MessageRole.ASSISTANT }?.content ?: return

        val titlePrompt = """
            Based on the following conversation, generate a very short, concise title (3-5 words maximum).
            Do not use quotes. Do not add any explanation. Just output the title.
            
            User: $userMessage
            Assistant: ${assistantMessage.take(200)}
            
            Title:
        """.trimIndent()

        viewModelScope.launch {
            try {
                var generatedTitle = ""
                inferenceBridge.runInferenceFlow(titlePrompt)
                    .catch { /* Silently fail - keep the temporary title */ }
                    .collect { partialResult ->
                        generatedTitle = partialResult.trim()
                    }

                if (generatedTitle.isNotBlank()) {
                    // Clean up the title
                    val cleanTitle = generatedTitle
                        .replace("\"", "")
                        .replace("'", "")
                        .take(40)
                        .trim()
                    if (cleanTitle.isNotBlank()) {
                        chatConversationDao?.updateTitle(
                            conversationId,
                            cleanTitle,
                            System.currentTimeMillis()
                        )
                        _uiState.update { it.copy(currentConversationTitle = cleanTitle) }
                    }
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Title generation failed: ${e.message}")
            }
        }
    }

    private fun buildPrompt(userMessage: String, context: List<ChunkEntity>, currentUserMessageId: String? = null): String {
        val effectiveSystemPrompt = _uiState.value.systemPrompt.ifBlank { _uiState.value.defaultSystemPrompt }
        val toolsEnabled = _uiState.value.toolsEnabled
        val thinkingEnabled = _uiState.value.thinkingEnabled

        val systemPromptBuilder = StringBuilder()

        if (thinkingEnabled) {
            systemPromptBuilder.appendLine("You are in thinking mode. Wrap your reasoning and internal deliberation inside <|think|> ... <|think|> tags.")
        }

        if (effectiveSystemPrompt.isNotBlank()) {
            systemPromptBuilder.appendLine(effectiveSystemPrompt)
        }

        // Build tool declarations if enabled
        var toolDeclarations = ""
        if (toolsEnabled && toolRegistry != null) {
            val activeTools = _uiState.value.enabledTools.filter { it.value }.keys
            val decls = if (activeTools.isNotEmpty()) {
                toolRegistry.getGemmaToolDeclarations(activeTools)
            } else ""
            if (decls.isNotBlank()) {
                toolDeclarations = decls
            }
        }

        val systemContent = systemPromptBuilder.toString()
        val conversationHistory = buildConversationHistory(currentUserMessageId)

        return buildString {
            appendLine("<|turn>system")
            appendLine("You are a helpful assistant.")
            if (systemContent.isNotBlank()) {
                appendLine(systemContent)
            }
            // Tool declarations go inside system turn
            if (toolDeclarations.isNotBlank()) {
                append(toolDeclarations)
            }
            appendLine("<turn|>")

            // Add conversation history
            if (conversationHistory.isNotBlank()) {
                append(conversationHistory)
            }

            // Add context if available
            if (context.isNotEmpty()) {
                appendLine("<|turn>user")
                appendLine("Use the following context to answer:")
                context.forEach { chunk ->
                    appendLine("[Document: ${chunk.sourceId}]")
                    appendLine(chunk.text)
                }
                appendLine("<turn|>")
            }

            // User message
            appendLine("<|turn>user")
            appendLine(userMessage)
            appendLine("<turn|>")

            // Model turn prefix
            append("<|turn>model")
        }
    }

    private fun buildConversationHistory(currentUserMessageId: String? = null): String {
        var historyMessages = _uiState.value.messages
        if (historyMessages.isEmpty()) return ""

        if (currentUserMessageId != null) {
            val currentIndex = historyMessages.indexOfFirst { it.id == currentUserMessageId }
            if (currentIndex > 0) {
                historyMessages = historyMessages.take(currentIndex)
            } else {
                return ""
            }
        } else {
            // Exclude current turn: last user message + pending empty assistant message
            val completedMessages = if (historyMessages.size >= 2) {
                val lastMsg = historyMessages.last()
                val secondLastMsg = historyMessages[historyMessages.size - 2]
                if (lastMsg.role == MessageRole.ASSISTANT && lastMsg.content.isBlank() &&
                    secondLastMsg.role == MessageRole.USER
                ) {
                    historyMessages.dropLast(2)
                } else {
                    historyMessages
                }
            } else {
                historyMessages
            }
            historyMessages = completedMessages
        }

        if (historyMessages.isEmpty()) return ""

        return buildString {
            historyMessages.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "model"
                }
                appendLine("<|turn>$role")
                appendLine(msg.content)
                appendLine("<turn|>")
            }
        }
    }

    private fun findNextPendingUserMessage(): ChatMessage? {
        val messages = _uiState.value.messages
        val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIndex < 0 || lastAssistantIndex >= messages.lastIndex) return null
        return messages.drop(lastAssistantIndex + 1).firstOrNull { it.role == MessageRole.USER }
    }

    private fun startTurnForUserMessage(userMessage: ChatMessage) {
        val conversationId = _uiState.value.currentConversationId ?: return

        val userMessageIndex = _uiState.value.messages.indexOfFirst { it.id == userMessage.id }

        pendingAssistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = pendingAssistantMessageId!!,
            role = MessageRole.ASSISTANT,
            content = "",
            sources = emptyList()
        )

        _uiState.update { state ->
            val newMessages = state.messages.toMutableList()
            if (userMessageIndex >= 0 && userMessageIndex < newMessages.lastIndex) {
                newMessages.add(userMessageIndex + 1, assistantMessage)
            } else {
                newMessages.add(assistantMessage)
            }
            state.copy(
                messages = newMessages,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                val relevantChunks = vectorStore.similaritySearch(userMessage.content, topK = 6)
                val attachedStackChunks = mutableListOf<ChunkEntity>()
                _uiState.value.attachedStacks.forEach { stack ->
                    val chunks = vectorStore.getChunksForSource(stack.stackId)
                    attachedStackChunks.addAll(chunks)
                }
                val allChunks = (relevantChunks + attachedStackChunks)
                    .distinctBy { it.id }
                    .sortedByDescending { chunk -> chunk.text.length }
                    .take(10)

                _uiState.update { it.copy(retrievedContext = allChunks) }

                val contextPrompt = buildPrompt(userMessage.content, allChunks, userMessage.id)
                val sourceIds = allChunks.map { it.id }

                val isReadyNow = inferenceBridge.isReady.value
                if (!isReadyNow) {
                    onLoadModel?.invoke()
                    updateLastAssistantMessage(
                        listOf(MessagePart.TextPart("Loading AI model...")),
                        sourceIds
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            retrievedContext = emptyList(),
                            pendingRetryMessage = userMessage.content,
                            pendingRetryError = null
                        )
                    }
                    return@launch
                }

                inferenceBridge.runInferenceFlowParts(contextPrompt)
                    .catch { error ->
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                error = error.message ?: "Inference error",
                                retrievedContext = emptyList()
                            )
                        }
                    }
                    .onCompletion { cause ->
                        val lastMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val finalParts = lastMessage?.parts ?: emptyList()
                        val finalContent = lastMessage?.content

                        val toolCalls = finalParts.filterIsInstance<MessagePart.ToolCallPart>()
                        if (toolCalls.isNotEmpty() && toolRegistry != null) {
                            continueWithToolResults(toolCalls, userMessage.content, allChunks, sourceIds)
                            return@onCompletion
                        }

                        _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }

                        if (finalContent != null && finalContent.isNotBlank()) {
                            val convId = _uiState.value.currentConversationId
                            if (convId != null) {
                                viewModelScope.launch {
                                    chatMessageDao?.insert(
                                        ChatMessageEntity(
                                            id = pendingAssistantMessageId ?: UUID.randomUUID().toString(),
                                            conversationId = convId,
                                            role = "ASSISTANT",
                                            content = finalContent,
                                            sourcesJson = gson.toJson(sourceIds),
                                            createdAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }

                        if (_uiState.value.needsTitleGeneration) {
                            generateConversationTitle()
                            _uiState.update { it.copy(needsTitleGeneration = false) }
                        }

                        if (cause == null && _uiState.value.error == null) {
                            val nextUserMessage = findNextPendingUserMessage()
                            if (nextUserMessage != null) {
                                startTurnForUserMessage(nextUserMessage)
                            }
                        }
                    }
                    .collect { parts ->
                        updateLastAssistantMessage(parts, sourceIds)
                    }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to process message", e)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = "Failed to process message: ${e.message}",
                        retrievedContext = emptyList()
                    )
                }
            }
        }
    }
}
