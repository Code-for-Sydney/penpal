package com.penpal.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.VectorStoreRepository
import com.penpal.core.data.ChatConversationDao
import com.penpal.core.data.ChatConversationEntity
import com.penpal.core.data.ChatMessageDao
import com.penpal.core.data.ChatMessageEntity
import com.penpal.core.data.ChunkEntity
import com.penpal.core.data.NotebookDao
import com.penpal.core.data.NotebookEntity
import com.penpal.core.processing.WorkerLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // Conversation history
    val conversations: List<ChatConversation> = emptyList(),
    val currentConversationId: String? = null,
    val currentConversationTitle: String = "New Chat",
    // Notebook attachments
    val attachedNotebooks: List<AttachedNotebook> = emptyList(),
    val pinnedFiles: List<PinnedFile> = emptyList()
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val sources: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatConversation(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AttachedNotebook(
    val notebookId: String,
    val title: String
)

data class PinnedFile(
    val uri: String,
    val name: String,
    val mimeType: String,
    val notebookId: String
)

enum class MessageRole { USER, ASSISTANT }

sealed class ChatEvent {
    data class UpdateInput(val text: String) : ChatEvent()
    data object SendMessage : ChatEvent()
    data object ClearChat : ChatEvent()
    data object DismissError : ChatEvent()
    // Conversation management
    data class CreateConversation(val title: String = "New Chat") : ChatEvent()
    data class LoadConversation(val conversationId: String) : ChatEvent()
    data class DeleteConversation(val conversationId: String) : ChatEvent()
    // Notebook attachment
    data class AttachNotebook(val notebookId: String) : ChatEvent()
    data class DetachNotebook(val notebookId: String) : ChatEvent()
    // File handling
    data class AddFile(val uri: Uri, val mimeType: String) : ChatEvent()
    data class RemovePinnedFile(val uri: String) : ChatEvent()
}

class ChatViewModel(
    private val vectorStore: VectorStoreRepository,
    private val inferenceBridge: InferenceBridge,
    private val chatMessageDao: ChatMessageDao? = null,
    private val chatConversationDao: ChatConversationDao? = null,
    private val notebookDao: NotebookDao? = null,
    private val workerLauncher: WorkerLauncher? = null
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var pendingAssistantMessageId: String? = null

    init {
        viewModelScope.launch {
            inferenceBridge.isReady.collect { isReady ->
                _uiState.update { it.copy(isModelReady = isReady) }
            }
        }

        loadConversations()
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UpdateInput -> _uiState.update { it.copy(inputText = event.text) }
            is ChatEvent.SendMessage -> sendMessage()
            is ChatEvent.ClearChat -> clearChat()
            is ChatEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is ChatEvent.CreateConversation -> createNewConversation(event.title)
            is ChatEvent.LoadConversation -> loadConversation(event.conversationId)
            is ChatEvent.DeleteConversation -> deleteConversation(event.conversationId)
            is ChatEvent.AttachNotebook -> attachNotebook(event.notebookId)
            is ChatEvent.DetachNotebook -> detachNotebook(event.notebookId)
            is ChatEvent.AddFile -> addFileToChat(event.uri, event.mimeType)
            is ChatEvent.RemovePinnedFile -> removePinnedFile(event.uri)
        }
    }

    private fun loadConversations() {
        chatConversationDao ?: return
        viewModelScope.launch {
            chatConversationDao.getAllConversations().collect { entities ->
                val conversations = entities.map { entity ->
                    ChatConversation(
                        id = entity.id,
                        title = entity.title,
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

    private fun createNewConversation(title: String) {
        chatConversationDao ?: return
        viewModelScope.launch {
            val conversationId = UUID.randomUUID().toString()
            val conversation = ChatConversationEntity(
                id = conversationId,
                title = title
            )
            chatConversationDao.insert(conversation)
            _uiState.update {
                it.copy(
                    currentConversationId = conversationId,
                    currentConversationTitle = title,
                    messages = emptyList(),
                    attachedNotebooks = emptyList(),
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
                // Load attached notebooks
                val notebookIds = try {
                    gson.fromJson(conversation.notebookIdsJson, Array<String>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }

                val attachedNotebooks = notebookIds.mapNotNull { notebookId ->
                    notebookDao?.getNotebook(notebookId)?.let { entity ->
                        AttachedNotebook(notebookId = entity.id, title = entity.title)
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        currentConversationId = conversationId,
                        currentConversationTitle = conversation.title,
                        attachedNotebooks = attachedNotebooks,
                        messages = emptyList()
                    )
                }

                // Load messages
                chatMessageDao?.getMessagesForConversation(conversationId)?.collect { entities ->
                    val msgs = entities.map { entity ->
                        ChatMessage(
                            id = entity.id,
                            role = if (entity.role == "USER") MessageRole.USER else MessageRole.ASSISTANT,
                            content = entity.content,
                            sources = try {
                                gson.fromJson(entity.sourcesJson, Array<String>::class.java).toList()
                            } catch (_: Exception) {
                                emptyList()
                            },
                            timestamp = entity.createdAt
                        )
                    }
                    _uiState.update { state ->
                        state.copy(messages = msgs)
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

    private fun attachNotebook(notebookId: String) {
        notebookDao ?: return
        chatConversationDao ?: return
        viewModelScope.launch {
            val notebook = notebookDao.getNotebook(notebookId) ?: return@launch
            val currentId = _uiState.value.currentConversationId ?: return@launch

            val conversation = chatConversationDao.getConversation(currentId)
            if (conversation != null) {
                val currentIds = try {
                    gson.fromJson(conversation.notebookIdsJson, Array<String>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (!currentIds.contains(notebookId)) {
                    val newIds = currentIds + notebookId
                    chatConversationDao.updateNotebookIds(
                        currentId,
                        gson.toJson(newIds),
                        System.currentTimeMillis()
                    )

                    val updatedAttached = _uiState.value.attachedNotebooks + AttachedNotebook(
                        notebookId = notebook.id,
                        title = notebook.title
                    )
                    _uiState.update { it.copy(attachedNotebooks = updatedAttached) }
                }
            }
        }
    }

    private fun detachNotebook(notebookId: String) {
        chatConversationDao ?: return
        viewModelScope.launch {
            val currentId = _uiState.value.currentConversationId ?: return@launch
            val conversation = chatConversationDao.getConversation(currentId) ?: return@launch

            val currentIds = try {
                gson.fromJson(conversation.notebookIdsJson, Array<String>::class.java).toList()
            } catch (_: Exception) {
                emptyList()
            }

            val newIds = currentIds.filter { it != notebookId }
            chatConversationDao.updateNotebookIds(
                currentId,
                gson.toJson(newIds),
                System.currentTimeMillis()
            )

            _uiState.update { state ->
                state.copy(attachedNotebooks = state.attachedNotebooks.filter { it.notebookId != notebookId })
            }
        }
    }

    private fun addFileToChat(uri: Uri, mimeType: String) {
        workerLauncher ?: return
        notebookDao ?: return
        chatConversationDao ?: return

        viewModelScope.launch {
            val currentId = _uiState.value.currentConversationId ?: return@launch
            val fileName = uri.lastPathSegment ?: "file"

            // Create or use existing notebook for this chat
            val conversation = chatConversationDao.getConversation(currentId)
            val notebookId = conversation?.let { conv ->
                try {
                    gson.fromJson(conv.notebookIdsJson, Array<String>::class.java).firstOrNull()
                } catch (_: Exception) {
                    null
                }
            } ?: UUID.randomUUID().toString()

            // Check if notebook exists
            val existingNotebook = notebookDao.getNotebook(notebookId)
            if (existingNotebook == null) {
                val notebook = NotebookEntity(
                    id = notebookId,
                    title = "Chat Files: ${_uiState.value.currentConversationTitle}",
                    blocksJson = "[]"
                )
                notebookDao.insert(notebook)

                chatConversationDao.updateNotebookIds(
                    currentId,
                    gson.toJson(listOf(notebookId)),
                    System.currentTimeMillis()
                )
            }

            // Add file as process block to notebook
            val notebook = notebookDao.getNotebook(notebookId)
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
                notebookDao.updateBlocks(notebookId, gson.toJson(updatedBlocks), System.currentTimeMillis())

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
        if (currentInput.isEmpty() || _uiState.value.isLoading) return

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

        pendingAssistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = pendingAssistantMessageId!!,
            role = MessageRole.ASSISTANT,
            content = "",
            sources = emptyList()
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage + assistantMessage,
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

            // Update conversation title if first message
            if (_uiState.value.messages.size == 2) {
                val title = currentInput.take(30)
                chatConversationDao?.updateTitle(conversationId, title, System.currentTimeMillis())
                _uiState.update { it.copy(currentConversationTitle = title) }
            }
        }

        viewModelScope.launch {
            try {
                // Retrieve context from vector store
                val relevantChunks = vectorStore.similaritySearch(currentInput, topK = 6)

                // Also retrieve from attached notebooks
                val attachedNotebookChunks = mutableListOf<ChunkEntity>()
                _uiState.value.attachedNotebooks.forEach { notebook ->
                    val chunks = vectorStore.getChunksForSource(notebook.notebookId)
                    attachedNotebookChunks.addAll(chunks)
                }

                // Combine and deduplicate
                val allChunks = (relevantChunks + attachedNotebookChunks)
                    .distinctBy { it.id }
                    .sortedByDescending { chunk ->
                        chunk.text.length
                    }
                    .take(10)

                _uiState.update { it.copy(retrievedContext = allChunks) }

                val contextPrompt = buildPrompt(currentInput, allChunks)
                val sourceIds = allChunks.map { it.id }

                if (!inferenceBridge.isReady.value) {
                    updateLastAssistantMessage("The AI model is not ready. Please download and load a model in Settings first.", sourceIds)
                    _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }
                    return@launch
                }

                inferenceBridge.runInference(
                    input = contextPrompt,
                    resultListener = { partialResult, done ->
                        updateLastAssistantMessage(partialResult, sourceIds)
                    },
                    cleanUpListener = {
                        _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }
                    },
                    onError = { error ->
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                error = error,
                                retrievedContext = emptyList()
                            )
                        }
                    }
                )

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

    private fun updateLastAssistantMessage(content: String, sources: List<String>) {
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            if (messages.isNotEmpty() && messages.last().role == MessageRole.ASSISTANT) {
                messages[messages.lastIndex] = messages.last().copy(
                    content = content,
                    sources = sources
                )
            }
            state.copy(messages = messages)
        }

        // Save assistant message to DB when streaming completes
        if (content.isNotBlank() && !_uiState.value.isLoading) {
            val conversationId = _uiState.value.currentConversationId
            if (conversationId != null) {
                viewModelScope.launch {
                    chatMessageDao?.insert(
                        ChatMessageEntity(
                            id = pendingAssistantMessageId ?: UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "ASSISTANT",
                            content = content,
                            sourcesJson = gson.toJson(sources),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
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

    private fun buildPrompt(userMessage: String, context: List<ChunkEntity>): String {
        val contextText = if (context.isNotEmpty()) {
            val contextItems = context.joinToString("\n\n") { chunk ->
                "[Document: ${chunk.sourceId}]\n${chunk.text}"
            }
            """
            |Context from your documents:
            |$contextItems
            |
            |Based on the above context, answer the following question.
            |If the context doesn't contain relevant information, say so.
            """.trimMargin()
        } else {
            "You are a helpful AI assistant. Answer the following question."
        }

        return """
            |$contextText
            |
            |User: $userMessage
            |Assistant:
        """.trimMargin()
    }
}
