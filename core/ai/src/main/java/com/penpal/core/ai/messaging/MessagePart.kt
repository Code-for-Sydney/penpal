package com.penpal.core.ai.messaging

sealed class MessagePart {
    data class TextPart(
        val text: String
    ) : MessagePart()

    data class ReasoningPart(
        val text: String,
        val isComplete: Boolean = false
    ) : MessagePart()

    data class ToolCallPart(
        val name: String,
        val callId: String,
        val arguments: Map<String, Any>,
        val rawJson: String,
        val status: ToolStatus = ToolStatus.PENDING
    ) : MessagePart()

    data class ToolResponsePart(
        val name: String,
        val callId: String,
        val output: String,
        val isError: Boolean = false
    ) : MessagePart()

    data class ImagePart(
        val description: String
    ) : MessagePart()

    data class AudioPart(
        val transcription: String
    ) : MessagePart()
}

enum class ToolStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR
}

class MessagePartAggregator {
    private val parts = mutableListOf<MessagePart>()
    private var currentMode = ContentMode.REGULAR
    private val currentBuffer = StringBuilder()
    private var currentToolCallBuffer: StringBuilder? = null
    private var lastToolCallPart: MessagePart.ToolCallPart? = null

    fun getParts(): List<MessagePart> = parts.toList()

    fun processChunk(chunk: FilteredChunkWithTransitions): List<MessagePart> {
        var lastEmittedPos = 0

        for (transition in chunk.transitions) {
            val segmentEnd = transition.textEmittedBeforeTransition.length
            val segmentText = if (lastEmittedPos < segmentEnd) {
                transition.textEmittedBeforeTransition.substring(lastEmittedPos, segmentEnd)
            } else {
                ""
            }

            if (segmentText.isNotEmpty()) {
                appendTextForMode(segmentText, currentMode)
            }

            finalizeCurrentPart()
            currentMode = transition.toMode
            lastEmittedPos = segmentEnd
        }

        val remainingText = if (lastEmittedPos < chunk.text.length) {
            chunk.text.substring(lastEmittedPos)
        } else {
            ""
        }
        if (remainingText.isNotEmpty()) {
            appendTextForMode(remainingText, currentMode)
        }

        return buildCurrentParts()
    }

    private fun appendTextForMode(text: String, mode: ContentMode) {
        when (mode) {
            ContentMode.REGULAR, ContentMode.SYSTEM,
            ContentMode.THINKING, ContentMode.TOOL_RESPONSE,
            ContentMode.IMAGE, ContentMode.AUDIO -> {
                currentBuffer.append(text)
            }
            ContentMode.TOOL_CALL -> {
                if (currentToolCallBuffer == null) {
                    currentToolCallBuffer = StringBuilder()
                }
                currentToolCallBuffer!!.append(text)
            }
        }
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
                currentBuffer.clear() // Clear any leftover text in currentBuffer
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
        } catch (e: Exception) {
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

        // Try Gemma 4 format first: call:tool_name{param1:<|"|>value1<|"|>,param2:<|"|>value2<|"|>}
        val gemmaMatch = Regex("call:(\\w+)\\{(.*?)\\}$").find(json.trim())
        if (gemmaMatch != null) {
            val argsText = gemmaMatch.groupValues[2]
            // Parse params: key:<|"|>value<|"|> or key:value
            val keyValueRegex = Regex("""(\w+):(?:<\|"\|>(.*?)<\|"\|>|([^,}]*))""")
            keyValueRegex.findAll(argsText).forEach { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].ifBlank { match.groupValues[3] }.trim()
                args[key] = value
            }
            return name to args
        }

        // Fallback to JSON format
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
        val trimmed = json.trim()

        // Try Gemma 4 format: call:tool_name{...}
        val gemmaMatch = Regex("^call:(\\w+)").find(trimmed)
        if (gemmaMatch != null) {
            return gemmaMatch.groupValues[1]
        }

        // Try JSON format
        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
        if (nameMatch != null) {
            return nameMatch.groupValues[1]
        }

        return "unknown"
    }

    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
}