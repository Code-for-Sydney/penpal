package com.penpal.core.ai

interface TextEmbedder {
    val dimension: Int
    suspend fun embed(text: String): FloatArray
}