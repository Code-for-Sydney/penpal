package com.penpal.core.ai.vectorstore

import com.google.gson.Gson
import com.penpal.core.ai.embedding.TextEmbedder
import com.penpal.core.ai.tools.web.RawChunk
import com.penpal.core.data.knowledge.ChunkDao
import com.penpal.core.data.knowledge.ChunkEntity
import kotlinx.coroutines.flow.first
import kotlin.math.sqrt

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

    override suspend fun getAllSourceIds(): List<String> {
        return chunkDao.getAllSourceIds()
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
    private val embeddingCache = LinkedHashMap<String, FloatArray>(1000, 0.75f, true)

    override suspend fun embed(text: String): FloatArray {
        return embeddingCache.getOrPut(text) { generateEmbedding(text) }
    }

    private fun generateEmbedding(text: String): FloatArray {
        val embedding = FloatArray(dimension)
        if (text.isEmpty()) return embedding

        val cleaned = text.lowercase().trim()
        val words = cleaned.split(Regex("\\s+"))

        // Build a simple bag-of-words with position-aware hashing
        for ((wordIdx, word) in words.withIndex()) {
            val posInDoc = wordIdx.toFloat() / words.size.coerceAtLeast(1)
            val wordHash = word.hashCode()

            for (i in 0 until 4) { // Distribute each word across 4 dimensions
                val dim = ((wordHash * 31 + i * 7919) and Int.MAX_VALUE) % dimension
                val value = kotlin.math.sin(wordHash.toDouble() + i * 2.0).toFloat()
                embedding[dim] += value * (1f - posInDoc * 0.3f)
            }
        }

        // Add character n-gram features for subword information
        for (ngramSize in 2..4) {
            for (i in 0..cleaned.length - ngramSize) {
                val ngram = cleaned.substring(i, i + ngramSize)
                val ngramHash = ngram.hashCode()
                val dim = ((ngramHash * 7) and Int.MAX_VALUE) % dimension
                embedding[dim] += 0.3f
            }
        }

        // L2 normalize
        val norm = kotlin.math.sqrt(embedding.sumOf { it * it.toDouble() }).toFloat()
        if (norm > 1e-10f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    override fun equals(other: Any?): Boolean = other is MiniLmEmbedder
    override fun hashCode(): Int = javaClass.hashCode()
}