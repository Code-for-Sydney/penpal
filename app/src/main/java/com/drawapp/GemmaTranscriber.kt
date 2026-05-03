package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
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

        // Default URL for Gemma transcription server
        const val DEFAULT_URL = "http://localhost:8080/transcribe"
        const val TIMEOUT_MS = 300000  // 5 minutes for large model warmup/inference
        const val CONNECT_TIMEOUT_MS = 10000  // 10 seconds to connect

        // Default prompt for transcription
        const val DEFAULT_PROMPT = """You are a speech-to-text transcription service. Transcribe the following audio content accurately. If you cannot hear clearly, say "Inaudible". Only output the transcription, nothing else."""
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Server URL configuration
    private var _serverUrl: String = DEFAULT_URL

    init {
        val prefs = getPrefs()
        _serverUrl = prefs.getString("gemma_server_url", DEFAULT_URL) ?: DEFAULT_URL
    }

    /**
     * Get current server URL
     */
    fun getServerUrl(): String = _serverUrl

    /**
     * Update the server URL
     */
    fun setServerUrl(url: String) {
        _serverUrl = url.trim().trimEnd('/')
        savePrefs()
    }

    /**
     * Check if server URL is configured
     */
    fun isConfigured(): Boolean = _serverUrl.isNotBlank() && _serverUrl.startsWith("http")

    /**
     * Check if server is reachable
     */
    suspend fun isServerAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false

        try {
            val url = URL(_serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 5000
            conn.doOutput = true
            // Send minimal body to check connectivity
            conn.outputStream.use { it.write(ByteArray(0)) }
            val response = conn.responseCode
            conn.disconnect()
            // Accept 200 or any response (server is up)
            response >= 200
        } catch (e: Exception) {
            Log.e(TAG, "Server check failed: ${e.message}")
            // Even if we can't connect, try anyway - might be server warming up
            true
        }
    }

    /**
     * Transcribe a complete audio file with automatic chunking for speed
     * @param audioFile The audio file to transcribe
     * @param customPrompt Optional custom instruction for processing
     */
    suspend fun transcribe(audioFile: File, customPrompt: String? = null): TranscriptionResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Transcribing file: ${audioFile.absolutePath}")

            if (!audioFile.exists()) {
                return@withContext TranscriptionResult(
                    success = false,
                    transcription = "",
                    errorMessage = "File not found"
                )
            }

            if (!isConfigured()) {
                return@withContext TranscriptionResult(
                    success = false,
                    transcription = "",
                    errorMessage = "Server URL not configured. Tap the gear icon to set it up."
                )
            }

            // Use AudioChunker to split into parallel-processable chunks
            val chunker = AudioChunker()
            val chunkingResult = chunker.chunkAudioFile(audioFile)

            if (chunkingResult.chunks.isEmpty()) {
                // No speech detected, return empty
                return@withContext TranscriptionResult(
                    success = true,
                    transcription = "",
                    errorMessage = null
                )
            }

            Log.d(TAG, "Created ${chunkingResult.chunks.size} chunks, processing in parallel...")

            // Process chunks in parallel
            return@withContext transcribeChunks(chunkingResult.chunks, customPrompt)
        }

    /**
     * Transcribe audio chunks in PARALLEL for speedup
     */
    suspend fun transcribeChunks(
        chunks: List<AudioChunker.ChunkResult>,
        customPrompt: String? = null
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Transcribing ${chunks.size} chunks in PARALLEL")

        if (chunks.isEmpty()) {
            return@withContext TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "No chunks to transcribe"
            )
        }

        // Process all chunks in parallel using async
        val deferredResults = chunks.mapIndexed { index, chunk ->
            async {
                try {
                    Log.d(TAG, "Processing chunk $index/${chunks.size} in parallel")
                    val result = sendTranscriptionRequest(chunk.file.readBytes(), customPrompt)
                    ChunkTranscription(
                        chunkIndex = index,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        transcription = result.transcription,
                        success = result.success
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Chunk $index failed: ${e.message}")
                    ChunkTranscription(
                        chunkIndex = index,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        transcription = "[Error: ${e.message}]",
                        success = false
                    )
                }
            }
        }

        // Wait for all to complete
        val results = deferredResults.awaitAll()

        // Check for errors
        val hasError = results.any { !it.success }
        val errorMessage = if (hasError) "Some chunks failed" else null

        // Build full transcript in order
        val fullTranscript = buildString {
            for (result in results.sortedBy { it.chunkIndex }) {
                if (result.transcription.isNotBlank() &&
                    !result.transcription.startsWith("[Error")) {
                    append(result.transcription)
                    append(" ")
                }
            }
        }.trim()

        Log.d(TAG, "Parallel transcription complete: ${results.size} chunks")

        return@withContext TranscriptionResult(
            success = !hasError,
            transcription = fullTranscript,
            errorMessage = errorMessage,
            chunkResults = results
        )
    }

    /**
     * Send audio data to Gemma server for transcription
     */
    private suspend fun sendTranscriptionRequest(
        audioData: ByteArray,
        customPrompt: String?
    ): TranscriptionResult {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val result = doSendRequest(audioData, customPrompt)
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

    private suspend fun doSendRequest(
        audioData: ByteArray,
        customPrompt: String?
    ): TranscriptionResult {
        val url = URL(_serverUrl)
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            // Send as multipart form data (form field name "audio", filename "audio.wav")
            val boundary = "----FormBoundary" + System.currentTimeMillis()
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val prompt = customPrompt?.trim() ?: DEFAULT_PROMPT

            // Build multipart body
            val body = buildMultipartBody(boundary, audioData, prompt)

            // Send request
            conn.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "Raw response: $response")

                // Parse JSON response - server returns "result" field
                try {
                    val jsonResponse = gson.fromJson(response, GemmaResponse::class.java)
                    Log.d(TAG, "Parsed response: result=${jsonResponse.result}, text=${jsonResponse.text}")

                    val transcription = jsonResponse.result
                        ?: jsonResponse.text
                        ?: jsonResponse.transcription
                        ?: ""
                    Log.d(TAG, "Final transcription: $transcription")

                    return TranscriptionResult(
                        success = true,
                        transcription = transcription,
                        errorMessage = null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
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
     * Build multipart form data body for file upload
     */
    private fun buildMultipartBody(boundary: String, audioData: ByteArray, prompt: String): ByteArray {
        val builder = StringBuilder()

        // Add prompt form field
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"text\"\r\n\r\n")
        builder.append("$prompt\r\n")

        // Add audio file
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n")
        builder.append("Content-Type: audio/wav\r\n\r\n")

        val bodyBytes = builder.toString().toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        return bodyBytes + audioData + footer
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

    private fun savePrefs() {
        val prefs = getPrefs()
        prefs.edit().putString("gemma_server_url", _serverUrl).apply()
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
        val result: String? = null,      // Primary transcription field
        val success: Boolean? = null,
        val text: String? = null,        // Alternative field
        val transcription: String? = null,
        val error: String? = null
    )
}
