package com.penpal.core.ai.embedding

interface TextEmbedder {
    val dimension: Int
    suspend fun embed(text: String): FloatArray
}