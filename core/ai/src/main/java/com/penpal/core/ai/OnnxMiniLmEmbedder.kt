package com.penpal.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime-based embedder using MiniLM models.
 *
 * Expected model format: ONNX model file (e.g., all-MiniLM-L6-v2.onnx)
 * that takes input_ids and attention_mask as inputs and outputs token embeddings.
 *
 * If the model file is not found, this falls back to mock embeddings.
 */
class OnnxMiniLmEmbedder(
    private val modelPath: String,
    tokenizer: WordPieceTokenizer? = null
) : TextEmbedder {

    override val dimension: Int = 384
    private val tag = "OnnxMiniLmEmbedder"

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val tokenizer: WordPieceTokenizer = tokenizer ?: WordPieceTokenizer.fallback()

    init {
        try {
            val modelFile = File(modelPath)
            if (modelFile.exists()) {
                environment = OrtEnvironment.getEnvironment()
                session = environment?.createSession(modelPath, OrtSession.SessionOptions())
                Log.i(tag, "ONNX session created successfully from $modelPath")
            } else {
                Log.w(tag, "Model file not found at $modelPath, embedder not initialized")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize ONNX session", e)
        }
    }

    val isInitialized: Boolean get() = session != null

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val sess = session
        if (sess == null) {
            Log.w(tag, "ONNX session not available, returning mock embedding")
            return@withContext generateMockEmbedding(text)
        }

        try {
            // Tokenize
            val tokens = tokenizer.tokenize(text)
            val inputIds = tokens.map { it.toLong() }.toLongArray()
            val attentionMask = LongArray(inputIds.size) { 1L }

            // Create tensors
            val inputShape = longArrayOf(1, inputIds.size.toLong())
            val inputIdsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), inputShape)
            val attentionMaskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(attentionMask), inputShape)

            // Run inference
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            val results = sess.run(inputs)
            val outputTensor = results.get(0)
            val embeddings = outputTensor.value as Array<Array<FloatArray>>

            // Mean pooling over sequence length
            val sequenceLength = embeddings[0].size
            val hiddenSize = embeddings[0][0].size
            val pooled = FloatArray(hiddenSize)

            for (j in 0 until hiddenSize) {
                var sum = 0f
                for (i in 0 until sequenceLength) {
                    sum += embeddings[0][i][j]
                }
                pooled[j] = sum / sequenceLength
            }

            // Normalize
            val norm = kotlin.math.sqrt(pooled.sumOf { it * it.toDouble() }).toFloat()
            val normalized = if (norm > 0) pooled.map { it / norm }.toFloatArray() else pooled

            inputIdsTensor.close()
            attentionMaskTensor.close()
            results.close()

            // Ensure correct dimension
            if (normalized.size == dimension) {
                normalized
            } else if (normalized.size > dimension) {
                normalized.copyOf(dimension)
            } else {
                normalized.copyOf(dimension)
            }
        } catch (e: Exception) {
            Log.e(tag, "ONNX inference failed, falling back to mock", e)
            generateMockEmbedding(text)
        }
    }

    private fun generateMockEmbedding(text: String): FloatArray {
        return text.hashCode().let { hash ->
            FloatArray(dimension) { i ->
                (((hash xor (i * 31)) and 0xFFFF).toFloat() / 0xFFFF.toFloat()) * 2f - 1f
            }
        }
    }

    fun close() {
        try {
            session?.close()
            environment?.close()
        } catch (_: Exception) { }
    }

    companion object {
        /** Suggested model file name */
        const val MODEL_FILE_NAME = "all-MiniLM-L6-v2.onnx"

        /** HuggingFace URL for the ONNX MiniLM model (optimum exported) */
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"

        fun modelFile(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "models")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, MODEL_FILE_NAME)
        }
    }
}


