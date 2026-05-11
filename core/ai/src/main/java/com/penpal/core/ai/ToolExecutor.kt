package com.penpal.core.ai

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

class ToolExecutor(
    private val registry: ToolRegistry,
    private val maxIterations: Int = 5
) {
    suspend fun executeWithTools(
        initialPrompt: String,
        context: ToolExecutionContext,
        inferenceFlow: suspend (String) -> Flow<String>
    ): Flow<String> = flow {
        var currentPrompt = initialPrompt
        var iteration = 0
        var hasMoreToolCalls = true

        while (hasMoreToolCalls && iteration < maxIterations) {
            iteration++
            Log.d("ToolExecutor", "=== Tool Execution Iteration $iteration ===")

            val toolCallsFound = mutableListOf<MessagePart.ToolCallPart>()
            var currentText = StringBuilder()

            inferenceFlow(currentPrompt)
                .onCompletion { cause ->
                    if (cause != null) {
                        Log.e("ToolExecutor", "Inference flow completed with error: ${cause.message}")
                    }
                }
                .collect { chunk ->
                    currentText.append(chunk)

                    val parts = MessagePartAggregator().apply {
                        val filtered = FilteredChunkWithTransitions(chunk, ContentMode.REGULAR)
                        processChunk(filtered)
                    }.getParts()

                    toolCallsFound.addAll(parts.filterIsInstance<MessagePart.ToolCallPart>())
                }

            val responseText = currentText.toString()
            emit(responseText)

            if (toolCallsFound.isEmpty()) {
                hasMoreToolCalls = false
                Log.d("ToolExecutor", "No more tool calls found, finishing")
            } else {
                Log.d("ToolExecutor", "Found ${toolCallsFound.size} tool call(s)")

                for (toolCall in toolCallsFound) {
                    val toolContext = context.copy(
                        toolCallId = toolCall.callId,
                        arguments = toolCall.arguments
                    )

                    val result = registry.execute(toolCall.name, toolContext)

                    val toolResponseJson = when (result) {
                        is ToolResult.Success -> {
                            """
                            |{
                            |    "call_id": "${toolCall.callId}",
                            |    "name": "${toolCall.name}",
                            |    "output": ${gson.toJson(result.output)}
                            |}
                            """.trimMargin()
                        }
                        is ToolResult.Error -> {
                            """
                            |{
                            |    "call_id": "${toolCall.callId}",
                            |    "name": "${toolCall.name}",
                            |    "error": ${gson.toJson(result.message)}
                            |}
                            """.trimMargin()
                        }
                        is ToolResult.StreamOutput -> {
                            val outputBuilder = StringBuilder()
                            result.chunks.collect { chunk ->
                                outputBuilder.append(chunk)
                            }
                            """
                            |{
                            |    "call_id": "${toolCall.callId}",
                            |    "name": "${toolCall.name}",
                            |    "output": ${gson.toJson(outputBuilder.toString())}
                            |}
                            """.trimMargin()
                        }
                    }

                    emit("\n<|tool_response|>\n$toolResponseJson\n<|tool_response|>\n")

                    currentPrompt = buildToolContinuationPrompt(responseText, toolCall, toolResponseJson)
                }
            }
        }

        if (iteration >= maxIterations) {
            Log.w("ToolExecutor", "Reached max iterations ($maxIterations)")
        }
    }

    private fun buildToolContinuationPrompt(
        originalResponse: String,
        toolCall: MessagePart.ToolCallPart,
        toolResponse: String
    ): String {
        return """
        |<|start_header_id|>model<|end_header_id|>

        $originalResponse
        <|tool_response|>
        $toolResponse
        <|tool_response|>
        <|start_header_id|>model<|end_header_id|>

        """
    }

    suspend fun executeSingleTool(
        toolCall: MessagePart.ToolCallPart,
        context: ToolExecutionContext
    ): ToolResult {
        return registry.execute(toolCall.name, context.copy(arguments = toolCall.arguments))
    }

    companion object {
        private val gson = com.google.gson.Gson()
    }
}
