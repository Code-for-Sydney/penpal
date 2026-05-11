package com.penpal.core.ai

import kotlinx.coroutines.flow.Flow

enum class MessageRole { USER, ASSISTANT }

data class ChatMessageInfo(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolExecutionContext(
    val toolCallId: String,
    val arguments: Map<String, Any>,
    val conversationHistory: List<ChatMessageInfo>,
    val attachedStackIds: List<String>,
    val userId: String? = null
)

interface Tool {
    val name: String
    val description: String
    val schema: ToolSchema

    suspend fun execute(context: ToolExecutionContext): ToolResult
}

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class StreamOutput(val chunks: Flow<String>) : ToolResult()
}
