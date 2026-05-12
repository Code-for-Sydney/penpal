package com.penpal.core.ai.vectorstore

import com.penpal.core.ai.embedding.TextEmbedder
import com.penpal.core.ai.tools.web.RawChunk

interface VectorStoreRepository {
    suspend fun embed(chunks: List<RawChunk>)
    suspend fun similaritySearch(query: String, topK: Int): List<com.penpal.core.data.knowledge.ChunkEntity>
    suspend fun getChunksForSource(sourceId: String): List<com.penpal.core.data.knowledge.ChunkEntity>
    suspend fun deleteChunksForSource(sourceId: String)
    suspend fun getAllSourceIds(): List<String>
    suspend fun getCachedChunkCount(): Int
    suspend fun hasCachedData(): Boolean
}