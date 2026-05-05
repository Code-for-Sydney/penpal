package com.penpal.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.VectorStoreRepository
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
    val retrievedContext: List<com.penpal.core.data.ChunkEntity> = emptyList()
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

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = currentInput
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                inputText = "",
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                val relevantChunks = vectorStore.similaritySearch(currentInput, topK = 6)
                _uiState.update { it.copy(retrievedContext = relevantChunks) }

                val responseText = if (inferenceBridge.isReady.value) {
                    "I'm ready to help. (stub response)"
                } else {
                    "Hello! I'm your AI assistant. Ask me questions about your documents."
                }

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = responseText,
                    sources = relevantChunks.map { it.id }
                )

                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + assistantMessage,
                        isLoading = false,
                        retrievedContext = emptyList()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(isLoading = false, error = e.message)
                }
            }
        }
    }

    private fun clearChat() {
        _uiState.update { ChatUiState() }
    }
}