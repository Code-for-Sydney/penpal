package com.penpal.core.ai.tools.web

data class RawChunk(
    val id: String,
    val sourceId: String,
    val text: String,
    val position: Int
)