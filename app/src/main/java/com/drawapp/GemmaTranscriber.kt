package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Service for processing audio through Gemma model for transcription.
 *
 * Usage:
 * 1. Record audio with AudioRecorder
 * 2. Chunk with AudioChunker (optional)
 * 3. Transcribe with GemmaTranscriber
 */
class GemmaTranscriber(private val context: Context) {

    companion object {
        const val TAG = "GemmaTranscriber"

        // Server configuration
        private const val DEFAULT_HOST = "localhost"
        private const val DEFAULT_PORT = 8080
        private const val TRANSCRIBE_ENDPOINT = "/transcribe"
        private const val CHUNK_ENDPOINT = "/transcribe_chunk"
        private const val TIMEOUT_MS = 30000  // 30 seconds per chunk

        // Prompt for Gemma transcription
        const val TRANSCRIPTION_PROMPT = """You are a speech-to-text transcription service. Transcribe the following audio content accurately. If you cannot hear clearly, say "Inaudible". Only output the transcription, nothing else."""
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Server configuration (can be updated)
    var serverHost: String
    var serverPort: Int

    init {
        val prefs = getPrefs()
        serverHost = prefs.getString("gemma_host", DEFAULT_HOST) ?: DEFAULT_HOST
        serverPort = prefs.getInt("gemma_port", DEFAULT_PORT)
    }

    /**
     * Check if server is reachable
     */
    suspend fun isServerAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$serverHost:$serverPort/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val response = conn.responseCode
            conn.disconnect()
            response == 200
        } catch (e: Exception) {
            Log.e(TAG, "Server check failed: ${e.message}")
            false
        }
    }

    /**
     * Transcribe a complete audio file (no chunking)
     */
    suspend fun transcribe(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Transcribing file: ${audioFile.absolutePath}")

        if (!audioFile.exists()) {
            return@withContext TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "File not found"
            )
        }

        return@withContext sendTranscriptionRequest(audioFile.readBytes())
    }

    /**
     * Transcribe audio chunks and merge results
     */
    suspend fun transcribeChunks(chunks: List<AudioChunker.ChunkResult>): TranscriptionResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Transcribing ${chunks.size} chunks")

            val results = mutableListOf<ChunkTranscription>()
            var hasError = false
            var errorMessage = ""

            for ((index, chunk) in chunks.withIndex()) {
                Log.d(TAG, "Processing chunk $index/${chunks.size}: ${chunk.file.name}")

                try {
                    val result = sendTranscriptionRequest(chunk.file.readBytes())
                    results.add(ChunkTranscription(
                        chunkIndex = index,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        transcription = result.transcription,
                        success = result.success
                    ))

                    if (!result.success) {
                        hasError = true
                        errorMessage = result.errorMessage ?: "Chunk $index failed"
                    }

                    // Small delay between chunks to avoid overwhelming server
                    if (index < chunks.size - 1) {
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Chunk $index failed: ${e.message}")
                    hasError = true
                    errorMessage = e.message ?: "Unknown error"
                    results.add(ChunkTranscription(
                        chunkIndex = index,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        transcription = "[Error: ${e.message}]",
                        success = false
                    ))
                }
            }

            // Merge transcriptions
            val mergedText = mergeTranscriptions(results)
            val fullTranscript = buildString {
                for (result in results) {
                    if (result.transcription.isNotBlank() &&
                        !result.transcription.startsWith("[Error")) {
                        append(result.transcription)
                        append(" ")
                    }
                }
            }.trim()

            return@withContext TranscriptionResult(
                success = !hasError,
                transcription = fullTranscript,
                errorMessage = if (hasError) errorMessage else null,
                chunkResults = results
            )
        }

    /**
     * Send audio data to Gemma server for transcription
     */
    private suspend fun sendTranscriptionRequest(audioData: ByteArray): TranscriptionResult {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val result = doSendRequest(audioData)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resume(TranscriptionResult(
                        success = false,
                        transcription = "",
                        errorMessage = e.message
                    ))
                }
            }
        }
    }

    private suspend fun doSendRequest(audioData: ByteArray): TranscriptionResult {
        val url = URL("http://$serverHost:$serverPort$TRANSCRIBE_ENDPOINT")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/octet-stream")

            // Send audio data
            conn.outputStream.use { output ->
                output.write(audioData)
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "Response: $response")

                // Parse JSON response
                try {
                    val jsonResponse = gson.fromJson(response, GemmaResponse::class.java)
                    return TranscriptionResult(
                        success = jsonResponse.success ?: true,
                        transcription = jsonResponse.text ?: jsonResponse.transcription ?: "",
                        errorMessage = jsonResponse.error
                    )
                } catch (e: Exception) {
                    // Response might be plain text
                    return TranscriptionResult(
                        success = true,
                        transcription = response
                    )
                }
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                return TranscriptionResult(
                    success = false,
                    transcription = "",
                    errorMessage = error
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Merge chunk transcriptions into coherent text
     */
    private fun mergeTranscriptions(chunks: List<ChunkTranscription>): String {
        val merged = StringBuilder()

        for (chunk in chunks.sortedBy { it.chunkIndex }) {
            val text = chunk.transcription.trim()
            if (text.isNotBlank() && !text.startsWith("[Error")) {
                merged.append(text)
                merged.append(" ")
            }
        }

        return merged.toString().trim()
    }

    /**
     * Save transcription to history
     */
    fun saveToHistory(
        audioFileName: String,
        transcription: String,
        durationMs: Long
    ) {
        val prefs = getPrefs()
        val historyJson = prefs.getString("transcription_history", "[]") ?: "[]"
        val history = try {
            gson.fromJson(historyJson, Array<TranscriptionHistoryItem>::class.java)?.toMutableList()
                ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }

        history.add(0, TranscriptionHistoryItem(
            audioFileName = audioFileName,
            transcription = transcription,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs
        ))

        // Keep only last 50 entries
        val trimmed = history.take(50)

        prefs.edit()
            .putString("transcription_history", gson.toJson(trimmed))
            .apply()
    }

    /**
     * Get transcription history
     */
    fun getHistory(): List<TranscriptionHistoryItem> {
        val prefs = getPrefs()
        val historyJson = prefs.getString("transcription_history", "[]") ?: "[]"
        return try {
            gson.fromJson(historyJson, Array<TranscriptionHistoryItem>::class.java)?.toList()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences("gemma_transcriber", Context.MODE_PRIVATE)
    }

    // Data classes

    data class TranscriptionResult(
        val success: Boolean,
        val transcription: String,
        val errorMessage: String? = null,
        val chunkResults: List<ChunkTranscription>? = null
    )

    data class ChunkTranscription(
        val chunkIndex: Int,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val transcription: String,
        val success: Boolean
    )

    data class TranscriptionHistoryItem(
        val audioFileName: String,
        val transcription: String,
        val timestamp: Long,
        val durationMs: Long
    )

    // Server response format
    data class GemmaResponse(
        val success: Boolean? = null,
        val text: String? = null,
        val transcription: String? = null,
        val error: String? = null
    )
}