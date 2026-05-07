package com.penpal.core.ai

/**
 * Sealed class representing a structured part of an assistant message.
 *
 * Inspired by the opencode library's "parts" architecture, this allows messages
 * to contain distinct sections (text, reasoning, tool calls) that can be rendered
 * with different UI treatments.
 *
 * Messages are composed as a list of parts: List<MessagePart>
 */
sealed class MessagePart {
    /**
     * Regular text content from the assistant.
     */
    data class TextPart(
        val text: String
    ) : MessagePart()

    /**
     * Thinking/reasoning content (from <|channel>thought blocks).
     * Displayed in a collapsible UI section.
     */
    data class ReasoningPart(
        val text: String,
        val isComplete: Boolean = false
    ) : MessagePart()

    /**
     * Tool/function call invocation.
     * Parsed from <|tool_call>...<tool_call|> blocks.
     */
    data class ToolCallPart(
        val name: String,
        val callId: String,
        val arguments: Map<String, Any>,
        val rawJson: String,
        val status: ToolStatus = ToolStatus.PENDING
    ) : MessagePart()

    /**
     * Tool/function response after execution.
     * Parsed from <|tool_response>...<tool_response|> blocks.
     */
    data class ToolResponsePart(
        val name: String,
        val callId: String,
        val output: String,
        val isError: Boolean = false
    ) : MessagePart()

    /**
     * Image content part (for multimodal responses).
     */
    data class ImagePart(
        val description: String
    ) : MessagePart()

    /**
     * Audio content part (for multimodal responses).
     */
    data class AudioPart(
        val transcription: String
    ) : MessagePart()
}

/**
 * Status of a tool call during its lifecycle.
 */
enum class ToolStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR
}

/**
 * Event emitted by StreamingTokenFilter when the content mode transitions.
 * Used to track when a new MessagePart should be started.
 */
data class ModeTransitionEvent(
    val fromMode: ContentMode,
    val toMode: ContentMode,
    val textEmittedBeforeTransition: String
)

/**
 * Result from StreamingTokenFilter that includes both cleaned text and
 * any mode transitions that occurred while processing the chunk.
 */
data class FilteredChunkWithTransitions(
    val text: String,
    val mode: ContentMode,
    val transitions: List<ModeTransitionEvent> = emptyList()
)

/**
 * Aggregates streaming FilteredChunks into a list of MessageParts.
 *
 * Usage:
 * ```
 * val aggregator = MessagePartAggregator()
 * val chunk = filter.append(text)
 * val parts = aggregator.processChunk(chunk)
 * // parts contains the current list of MessageParts
 * ```
 */
class MessagePartAggregator {
    private val parts = mutableListOf<MessagePart>()
    private var currentMode = ContentMode.REGULAR
    private val currentBuffer = StringBuilder()
    private var currentToolCallBuffer: StringBuilder? = null
    private var lastToolCallPart: MessagePart.ToolCallPart? = null

    fun getParts(): List<MessagePart> = parts.toList()

    fun processChunk(chunk: FilteredChunkWithTransitions): List<MessagePart> {
        for (transition in chunk.transitions) {
            finalizeCurrentPart()
            currentMode = transition.toMode
        }

        currentBuffer.append(chunk.text)

        if (currentMode == ContentMode.TOOL_CALL) {
            if (currentToolCallBuffer == null) {
                currentToolCallBuffer = StringBuilder()
            }
            currentToolCallBuffer!!.append(chunk.text)
        }

        return buildCurrentParts()
    }

    fun finalize(): List<MessagePart> {
        finalizeCurrentPart()
        return parts.toList()
    }

    fun reset() {
        parts.clear()
        currentMode = ContentMode.REGULAR
        currentBuffer.clear()
        currentToolCallBuffer = null
        lastToolCallPart = null
    }

    private fun finalizeCurrentPart() {
        val text = currentBuffer.toString()
        if (text.isBlank() && currentMode != ContentMode.TOOL_CALL) {
            currentBuffer.clear()
            return
        }

        when (currentMode) {
            ContentMode.REGULAR, ContentMode.SYSTEM -> {
                if (text.isNotBlank()) {
                    parts.add(MessagePart.TextPart(text.trimEnd()))
                }
            }
            ContentMode.THINKING -> {
                if (text.isNotBlank()) {
                    parts.add(MessagePart.ReasoningPart(text.trimEnd(), isComplete = true))
                }
            }
            ContentMode.TOOL_CALL -> {
                val toolJson = currentToolCallBuffer?.toString() ?: text
                if (toolJson.isNotBlank()) {
                    val parsed = parseToolCall(toolJson)
                    parts.add(parsed)
                    lastToolCallPart = parsed
                }
                currentToolCallBuffer = null
            }
            ContentMode.TOOL_RESPONSE -> {
                if (text.isNotBlank()) {
                    val callId = lastToolCallPart?.callId ?: ""
                    val name = lastToolCallPart?.name ?: ""
                    parts.add(MessagePart.ToolResponsePart(name, callId, text.trimEnd()))
                }
            }
            ContentMode.IMAGE -> {
                if (text.isNotBlank()) {
                    parts.add(MessagePart.ImagePart(text.trimEnd()))
                }
            }
            ContentMode.AUDIO -> {
                if (text.isNotBlank()) {
                    parts.add(MessagePart.AudioPart(text.trimEnd()))
                }
            }
        }

        currentBuffer.clear()
    }

    private fun buildCurrentParts(): List<MessagePart> {
        val result = parts.toMutableList()
        val currentText = currentBuffer.toString()

        when (currentMode) {
            ContentMode.REGULAR, ContentMode.SYSTEM -> {
                if (currentText.isNotBlank()) {
                    result.add(MessagePart.TextPart(currentText))
                }
            }
            ContentMode.THINKING -> {
                if (currentText.isNotBlank()) {
                    result.add(MessagePart.ReasoningPart(currentText, isComplete = false))
                }
            }
            ContentMode.TOOL_CALL -> {
                val toolJson = currentToolCallBuffer?.toString() ?: currentText
                if (toolJson.isNotBlank()) {
                    result.add(
                        MessagePart.ToolCallPart(
                            name = extractToolName(toolJson),
                            callId = generateCallId(),
                            arguments = emptyMap(),
                            rawJson = toolJson,
                            status = ToolStatus.PENDING
                        )
                    )
                }
            }
            ContentMode.TOOL_RESPONSE -> {
                if (currentText.isNotBlank()) {
                    val callId = lastToolCallPart?.callId ?: ""
                    val name = lastToolCallPart?.name ?: ""
                    result.add(MessagePart.ToolResponsePart(name, callId, currentText))
                }
            }
            ContentMode.IMAGE -> {
                if (currentText.isNotBlank()) {
                    result.add(MessagePart.ImagePart(currentText))
                }
            }
            ContentMode.AUDIO -> {
                if (currentText.isNotBlank()) {
                    result.add(MessagePart.AudioPart(currentText))
                }
            }
        }

        return result
    }

    private fun parseToolCall(json: String): MessagePart.ToolCallPart {
        return try {
            val cleaned = json.trim()
            val (name, args) = parseToolJson(cleaned)
            MessagePart.ToolCallPart(
                name = name,
                callId = generateCallId(),
                arguments = args,
                rawJson = cleaned,
                status = ToolStatus.PENDING
            )
        } catch (_: Exception) {
            MessagePart.ToolCallPart(
                name = extractToolName(json),
                callId = generateCallId(),
                arguments = emptyMap(),
                rawJson = json.trim(),
                status = ToolStatus.PENDING
            )
        }
    }

    private fun parseToolJson(json: String): Pair<String, Map<String, Any>> {
        val name = extractToolName(json)
        val args = mutableMapOf<String, Any>()

        val argsMatch = Regex("\"arguments\"\\s*:\\s*\\{([^}]*)\\}").find(json)
        if (argsMatch != null) {
            val argsText = argsMatch.groupValues[1]
            val keyValueRegex = Regex("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|\\d+|true|false|null)")
            keyValueRegex.findAll(argsText).forEach { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].trim('"')
                args[key] = value
            }
        }

        return name to args
    }

    private fun extractToolName(json: String): String {
        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)
        if (nameMatch != null) {
            return nameMatch.groupValues[1]
        }
        val funcMatch = Regex("\"(\\w+)\"\\s*:\\s*\"").find(json)
        return funcMatch?.groupValues?.get(1) ?: "unknown"
    }

    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
}
