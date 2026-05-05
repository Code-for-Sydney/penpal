package com.penpal.core.ai

import com.google.gson.Gson
import com.penpal.core.data.ChunkDao
import com.penpal.core.data.ChunkEntity
import kotlinx.coroutines.flow.first
import kotlin.math.sqrt

data class RawChunk(
    val id: String,
    val sourceId: String,
    val text: String,
    val position: Int
)

interface VectorStoreRepository {
    suspend fun embed(chunks: List<RawChunk>)
    suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity>
    suspend fun getChunksForSource(sourceId: String): List<ChunkEntity>
    suspend fun deleteChunksForSource(sourceId: String)

    /**
     * Returns the count of all cached chunks.
     * Useful for checking if offline data is available.
     */
    suspend fun getCachedChunkCount(): Int

    /**
     * Returns true if any chunks have been cached.
     */
    suspend fun hasCachedData(): Boolean
}

class VectorStoreRepositoryImpl(
    private val chunkDao: ChunkDao,
    private val textEmbedder: TextEmbedder,
    private val gson: Gson
) : VectorStoreRepository {

    private val embeddingCache = LinkedHashMap<String, FloatArray>(MAX_CACHE_SIZE)

    override suspend fun embed(chunks: List<RawChunk>) {
        val entities = chunks.map { chunk ->
            val embedding = textEmbedder.embed(chunk.text)
            if (embeddingCache.size >= MAX_CACHE_SIZE) {
                val oldestKey = embeddingCache.keys.firstOrNull()
                if (oldestKey != null) embeddingCache.remove(oldestKey)
            }
            embeddingCache[chunk.id] = embedding
            ChunkEntity(
                id = chunk.id,
                sourceId = chunk.sourceId,
                text = chunk.text,
                embeddingJson = gson.toJson(embedding),
                position = chunk.position
            )
        }
        chunkDao.insert(entities)
    }

    override suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity> {
        val queryEmbedding = textEmbedder.embed(query)
        val candidates = chunkDao.getAllPaged(1000, 0).first()

        val candidatesWithScore = candidates.map { chunk ->
            val cachedEmbedding = embeddingCache[chunk.id]
            val embedding = cachedEmbedding
                ?: gson.fromJson(chunk.embeddingJson, Array<Float>::class.java).toFloatArray()
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            Pair(chunk, similarity)
        }.sortedByDescending { it.second }.take(topK)

        return candidatesWithScore.map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f
        else (dotProduct / (sqrt(normA.toDouble()) * sqrt(normB.toDouble()))).toFloat()
    }

    override suspend fun getChunksForSource(sourceId: String): List<ChunkEntity> {
        return chunkDao.getChunksForSource(sourceId).first()
    }

    override suspend fun deleteChunksForSource(sourceId: String) {
        val chunks = chunkDao.getChunksForSource(sourceId).first()
        chunks.forEach { embeddingCache.remove(it.id) }
        chunkDao.deleteForSource(sourceId)
    }

    override suspend fun getCachedChunkCount(): Int {
        return chunkDao.getCount()
    }

    override suspend fun hasCachedData(): Boolean {
        return chunkDao.getCount() > 0
    }

    companion object { private const val MAX_CACHE_SIZE = 10_000 }
}

class MiniLmEmbedder : TextEmbedder {
    override val dimension: Int = 384
    private val mockEmbeddings = mutableMapOf<String, FloatArray>()
    private var callCount = 0

    override suspend fun embed(text: String): FloatArray {
        callCount++
        if (callCount % 100 == 0) mockEmbeddings.clear()
        return mockEmbeddings.getOrPut(text) {
            text.hashCode().let { hash ->
                FloatArray(dimension) { i ->
                    (((hash xor (i * 31)) and 0xFFFF).toFloat() / 0xFFFF.toFloat()) * 2f - 1f
                }
            }
        }
    }
}