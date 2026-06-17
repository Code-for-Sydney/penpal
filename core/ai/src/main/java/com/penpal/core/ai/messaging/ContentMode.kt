package com.penpal.core.ai.messaging

enum class ContentMode {
    REGULAR,
    THINKING,
    IMAGE,
    AUDIO,
    TOOL_CALL,
    TOOL_RESPONSE,
    SYSTEM
}

data class ModeTransitionEvent(
    val fromMode: ContentMode,
    val toMode: ContentMode,
    val textEmittedBeforeTransition: String
)

data class FilteredChunk(
    val text: String,
    val mode: ContentMode
)

data class FilteredChunkWithTransitions(
    val text: String,
    val mode: ContentMode,
    val transitions: List<ModeTransitionEvent> = emptyList()
)