package com.penpal.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.VectorStoreRepository
import com.penpal.core.data.ChunkEntity
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
    val isModelReady: Boolean = false
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val sources: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole { USER, ASSISTANT }

sealed class ChatEvent {
    data class UpdateInput(val text: String) : ChatEvent()
    data object SendMessage : ChatEvent()
    data object ClearChat : ChatEvent()
    data object DismissError : ChatEvent()
}

class ChatViewModel(
    private val vectorStore: VectorStoreRepository,
    private val inferenceBridge: InferenceBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var pendingAssistantMessageId: String? = null

    init {
        // Observe model readiness
        viewModelScope.launch {
            inferenceBridge.isReady.collect { isReady ->
                _uiState.update { it.copy(isModelReady = isReady) }
            }
        }
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.UpdateInput -> _uiState.update { it.copy(inputText = event.text) }
            is ChatEvent.SendMessage -> sendMessage()
            is ChatEvent.ClearChat -> clearChat()
            is ChatEvent.DismissError -> _uiState.update { it.copy(error = null) }
        }
    }

    private fun sendMessage() {
        val currentInput = _uiState.value.inputText.trim()
        if (currentInput.isEmpty() || _uiState.value.isLoading) return

        // Add user message immediately
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = currentInput
        )

        // Create placeholder for assistant message
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

        viewModelScope.launch {
            try {
                // 1. Retrieve relevant context from vector store
                val relevantChunks = vectorStore.similaritySearch(currentInput, topK = 6)
                _uiState.update { it.copy(retrievedContext = relevantChunks) }

                // 2. Build prompt with context
                val contextPrompt = buildPrompt(currentInput, relevantChunks)
                val sourceIds = relevantChunks.map { it.id }

                // 3. Check if model is ready
                if (!inferenceBridge.isReady.value) {
                    updateLastAssistantMessage("The AI model is not ready. Please download and load a model in Settings first.", sourceIds)
                    _uiState.update { it.copy(isLoading = false, retrievedContext = emptyList()) }
                    return@launch
                }

                // 4. Run inference through LiteRT-LM
                inferenceBridge.runInference(
                    input = contextPrompt,
                    resultListener = { partialResult, done ->
                        if (done) {
                            updateLastAssistantMessage(partialResult, sourceIds)
                        }
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
    }

    private fun buildPrompt(userMessage: String, context: List<ChunkEntity>): String {
        val contextText = if (context.isNotEmpty()) {
            val contextItems = context.joinToString("\n\n") { chunk ->
                "[Document: ${chunk.id}]\n${chunk.text}"
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

    private fun clearChat() {
        inferenceBridge.resetConversation()
        pendingAssistantMessageId = null
        _uiState.update { ChatUiState() }
    }
}