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
 * Client for communicating with the Gemma transcription server.
 * Supports REST (single/batch) and WebSocket streaming.
 */
class GemmaServerClient(private val context: Context) {

    companion object {
        const val TAG = "GemmaServerClient"

        // Default URLs
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 8000
        const val DEFAULT_BASE_URL = "http://$DEFAULT_HOST:$DEFAULT_PORT"
        const val TRANSCRIBE_ENDPOINT = "/transcribe"
        const val BATCH_ENDPOINT = "/batch"
        const val HEALTH_ENDPOINT = "/health"
        const val STREAM_ENDPOINT = "/stream/audio"

        // Timeouts
        const val CONNECT_TIMEOUT_MS = 10000
        const val READ_TIMEOUT_MS = 300000  // 5 minutes for inference
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Server configuration
    private var _baseUrl: String = DEFAULT_BASE_URL

    init {
        val prefs = getPrefs()
        _baseUrl = prefs.getString("gemma_server_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    /**
     * Get base URL (without endpoint)
     */
    fun getBaseUrl(): String = _baseUrl

    /**
     * Get full URL with endpoint
     */
    fun getUrl(endpoint: String): String = "${_baseUrl.trimEnd('/')}$endpoint"

    /**
     * Update server URL
     */
    fun setServerUrl(url: String) {
        _baseUrl = url.trim().trimEnd('/')
        savePrefs()
    }

    /**
     * Check if server URL is configured
     */
    fun isConfigured(): Boolean = _baseUrl.isNotBlank() && _baseUrl.startsWith("http")

    // ══════════════════════════════════════════════════════════════════════
    // Health Check
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Check if server is healthy
     */
    suspend fun checkHealth(): ServerHealth = withContext(Dispatchers.IO) {
        try {
            val url = URL(getUrl(HEALTH_ENDPOINT))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 5000

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                return@withContext gson.fromJson(response, ServerHealth::class.java)
            }

            conn.disconnect()
            return@withContext ServerHealth(
                status = "error",
                error = "HTTP $responseCode"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}")
            return@withContext ServerHealth(
                status = "error",
                error = e.message ?: "Connection failed"
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Single File Transcription (REST)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transcribe a single audio file
     * @param audioFile The audio file to transcribe
     * @param customPrompt Optional custom instruction
     */
    suspend fun transcribe(
        audioFile: File,
        customPrompt: String? = null,
        promptStyle: String = "asr",
        sourceLang: String = "English",
        targetLang: String = "Korean"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Transcribing file: ${audioFile.name}")

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
                errorMessage = "Server URL not configured"
            )
        }

        return@withContext sendTranscribeRequest(
            audioFile.readBytes(),
            customPrompt,
            promptStyle,
            sourceLang,
            targetLang
        )
    }

    private suspend fun sendTranscribeRequest(
        audioData: ByteArray,
        customPrompt: String?,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): TranscriptionResult {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val result = doSendTranscribeRequest(
                        audioData,
                        customPrompt,
                        promptStyle,
                        sourceLang,
                        targetLang
                    )
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

    private fun doSendTranscribeRequest(
        audioData: ByteArray,
        customPrompt: String?,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): TranscriptionResult {
        val url = URL(getUrl(TRANSCRIBE_ENDPOINT))
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val prompt = customPrompt ?: "Transcribe this audio accurately."

            val body = buildMultipartBody(boundary, audioData, prompt, promptStyle, sourceLang, targetLang)

            conn.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "Transcribe response: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                return parseServerResponse(response)
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

    // ══════════════════════════════════════════════════════════════════════
    // Batch Transcription (REST)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transcribe multiple audio files in batch
     * @param audioFiles List of audio files to transcribe
     * @param customPrompt Optional custom instruction for all files
     */
    suspend fun transcribeBatch(
        audioFiles: List<File>,
        customPrompt: String? = null,
        promptStyle: String = "asr",
        sourceLang: String = "English",
        targetLang: String = "Korean"
    ): BatchTranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Batch transcribing ${audioFiles.size} files")

        if (audioFiles.isEmpty()) {
            return@withContext BatchTranscriptionResult(
                success = false,
                results = emptyList(),
                errorMessage = "No files provided"
            )
        }

        if (!isConfigured()) {
            return@withContext BatchTranscriptionResult(
                success = false,
                results = emptyList(),
                errorMessage = "Server URL not configured"
            )
        }

        return@withContext sendBatchRequest(
            audioFiles,
            customPrompt,
            promptStyle,
            sourceLang,
            targetLang
        )
    }

    private suspend fun sendBatchRequest(
        audioFiles: List<File>,
        customPrompt: String?,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): BatchTranscriptionResult {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val result = doSendBatchRequest(
                        audioFiles,
                        customPrompt,
                        promptStyle,
                        sourceLang,
                        targetLang
                    )
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resume(BatchTranscriptionResult(
                        success = false,
                        results = emptyList(),
                        errorMessage = e.message
                    ))
                }
            }
        }
    }

    private fun doSendBatchRequest(
        audioFiles: List<File>,
        customPrompt: String?,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): BatchTranscriptionResult {
        val url = URL(getUrl(BATCH_ENDPOINT))
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS * audioFiles.size  // Scale timeout for batch

            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val prompt = customPrompt ?: "Transcribe this audio accurately."

            val body = buildBatchMultipartBody(boundary, audioFiles, prompt, promptStyle, sourceLang, targetLang)

            conn.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "Batch response: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                return parseBatchResponse(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                return BatchTranscriptionResult(
                    success = false,
                    results = emptyList(),
                    errorMessage = error
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Parallel Chunk Transcription (Client-side parallelization)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Transcribe audio chunks in PARALLEL for maximum speed
     * This is typically the fastest approach for client-side processing
     */
    suspend fun transcribeChunksParallel(
        chunks: List<AudioChunker.ChunkResult>,
        customPrompt: String? = null,
        promptStyle: String = "asr",
        sourceLang: String = "English",
        targetLang: String = "Korean",
        maxConcurrency: Int = 4
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing ${chunks.size} chunks with max concurrency: $maxConcurrency")

        if (chunks.isEmpty()) {
            return@withContext TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "No chunks to transcribe"
            )
        }

        if (!isConfigured()) {
            return@withContext TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "Server URL not configured"
            )
        }

        // Use semaphore to limit concurrency
        val semaphore = Semaphore(maxConcurrency)
        val lock = kotlinx.coroutines.sync.Mutex()

        // Process chunks with semaphore-limited concurrency
        val results = mutableListOf<ChunkTranscription>()

        chunks.forEachIndexed { index, chunk ->
            semaphore.acquire()
            try {
                val result = async(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Processing chunk $index in parallel")
                        val audioData = chunk.file.readBytes()
                        val result = sendTranscribeRequest(
                            audioData,
                            customPrompt,
                            promptStyle,
                            sourceLang,
                            targetLang
                        )
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
                    } finally {
                        semaphore.release()
                    }
                }.await()

                lock.lock()
                results.add(result)
                lock.unlock()
            } catch (e: CancellationException) {
                semaphore.release()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process chunk $index: ${e.message}")
                semaphore.release()
                lock.lock()
                results.add(ChunkTranscription(
                    chunkIndex = index,
                    startTimeMs = chunk.startTimeMs,
                    endTimeMs = chunk.endTimeMs,
                    transcription = "[Error: ${e.message}]",
                    success = false
                ))
                lock.unlock()
            }
        }

        val hasError = results.any { r -> !r.success }

        val fullTranscript = buildString {
            for (result in results.sortedBy { r -> r.chunkIndex }) {
                if (result.transcription.isNotBlank() && !result.transcription.startsWith("[Error")) {
                    append(result.transcription)
                    append(" ")
                }
            }
        }.trim()

        return@withContext TranscriptionResult(
            success = !hasError,
            transcription = fullTranscript,
            errorMessage = if (hasError) "Some chunks failed" else null,
            chunkResults = results
        )
    }

    /**
     * Auto-chunk and transcribe with parallel processing
     */
    suspend fun transcribeWithAutoChunking(
        audioFile: File,
        customPrompt: String? = null,
        promptStyle: String = "asr",
        sourceLang: String = "English",
        targetLang: String = "Korean"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Auto-chunking and transcribing: ${audioFile.name}")

        if (!audioFile.exists()) {
            return@withContext TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "File not found"
            )
        }

        val chunker = AudioChunker()
        val chunkingResult = chunker.chunkAudioFile(audioFile)

        if (chunkingResult.chunks.isEmpty()) {
            return@withContext TranscriptionResult(
                success = true,
                transcription = "",
                errorMessage = null
            )
        }

        Log.d(TAG, "Created ${chunkingResult.chunks.size} chunks, processing in parallel...")

        return@withContext transcribeChunksParallel(
            chunkingResult.chunks,
            customPrompt,
            promptStyle,
            sourceLang,
            targetLang
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper Methods
    // ══════════════════════════════════════════════════════════════════════

    private fun buildMultipartBody(
        boundary: String,
        audioData: ByteArray,
        prompt: String,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): ByteArray {
        val builder = StringBuilder()

        // Audio file
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n")
        builder.append("Content-Type: audio/wav\r\n\r\n")

        val headerBytes = builder.toString().toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        return headerBytes + audioData + footer
    }

    private fun buildBatchMultipartBody(
        boundary: String,
        audioFiles: List<File>,
        prompt: String,
        promptStyle: String,
        sourceLang: String,
        targetLang: String
    ): ByteArray {
        val builder = StringBuilder()

        // Add prompt style parameters
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"prompt_style\"\r\n\r\n")
        builder.append("$promptStyle\r\n")

        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"source_lang\"\r\n\r\n")
        builder.append("$sourceLang\r\n")

        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"target_lang\"\r\n\r\n")
        builder.append("$targetLang\r\n")

        // Add audio files
        for ((index, file) in audioFiles.withIndex()) {
            builder.append("--$boundary\r\n")
            builder.append("Content-Disposition: form-data; name=\"files\"; filename=\"${file.name}\"\r\n")
            builder.append("Content-Type: audio/wav\r\n\r\n")
        }

        var bodyBytes = builder.toString().toByteArray(Charsets.UTF_8)

        // Append audio data for each file
        for (file in audioFiles) {
            bodyBytes += file.readBytes()
            bodyBytes += "\r\n".toByteArray(Charsets.UTF_8)
        }

        val footer = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        return bodyBytes + footer
    }

    private fun parseServerResponse(response: String): TranscriptionResult {
        return try {
            val jsonResponse = gson.fromJson(response, ServerTranscribeResponse::class.java)
            TranscriptionResult(
                success = true,
                transcription = jsonResponse.result ?: response,
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            TranscriptionResult(
                success = true,
                transcription = response,
                errorMessage = null
            )
        }
    }

    private fun parseBatchResponse(response: String): BatchTranscriptionResult {
        return try {
            val jsonResponse = gson.fromJson(response, ServerBatchResponse::class.java)
            val results = jsonResponse.results?.mapIndexed { index, r ->
                BatchItem(
                    index = index,
                    filename = r.filename ?: "file_$index",
                    result = r.result ?: "",
                    totalTime = r.totalTime ?: 0.0,
                    success = true
                )
            } ?: emptyList()

            BatchTranscriptionResult(
                success = true,
                results = results,
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Batch parse error: ${e.message}")
            BatchTranscriptionResult(
                success = false,
                results = emptyList(),
                errorMessage = e.message
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WebSocket Event Listener
    // ══════════════════════════════════════════════════════════════════════

    interface ServerEventListener {
        fun onTranscriptionProgress(progress: Float, partialText: String)
        fun onTranscriptionComplete(fullText: String)
        fun onWebSearchResult(query: String, results: List<SearchResult>)
        fun onError(message: String)
    }

    private var eventListener: ServerEventListener? = null

    fun setEventListener(listener: ServerEventListener?) {
        this.eventListener = listener
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun savePrefs() {
        getPrefs().edit().putString("gemma_server_url", _baseUrl).apply()
    }

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences("gemma_server_client", Context.MODE_PRIVATE)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════════════════════════════════

    data class TranscriptionResult(
        val success: Boolean,
        val transcription: String,
        val errorMessage: String? = null,
        val chunkResults: List<ChunkTranscription>? = null,
        val webSearchResults: List<SearchResult>? = null
    )

    data class ChunkTranscription(
        val chunkIndex: Int,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val transcription: String,
        val success: Boolean
    )

    data class BatchTranscriptionResult(
        val success: Boolean,
        val results: List<BatchItem>,
        val errorMessage: String? = null
    )

    data class BatchItem(
        val index: Int,
        val filename: String,
        val result: String,
        val totalTime: Double,
        val success: Boolean
    )

    data class ServerHealth(
        val status: String? = null,
        val ready: Boolean = false,
        val modelLoaded: Boolean = false,
        val device: String? = null,
        val error: String? = null
    )

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String = ""
    )

    // Server response formats
    data class ServerTranscribeResponse(
        val result: String? = null,
        val filename: String? = null,
        val totalTime: Double? = null,
        val promptStyle: String? = null,
        val sourceLang: String? = null,
        val targetLang: String? = null,
        val modelDevice: String? = null
    )

    data class ServerBatchResponse(
        val results: List<ServerBatchItem>? = null,
        val totalFiles: Int? = null,
        val totalTime: Double? = null
    )

    data class ServerBatchItem(
        val result: String? = null,
        val filename: String? = null,
        val totalTime: Double? = null
    )

    // Server event types
    sealed class ServerEvent {
        data class Transcription(
            val text: String,
            val isFinal: Boolean,
            val progress: Float
        ) : ServerEvent()

        data class WebSearch(
            val query: String,
            val results: List<SearchResult>
        ) : ServerEvent()

        data class Error(val message: String) : ServerEvent()
    }

    // WebSocket streaming
    sealed class StreamEvent {
        data class PartialResult(val text: String, val progress: Float) : StreamEvent()
        data class FinalResult(val text: String, val totalTime: Double) : StreamEvent()
        data class Error(val message: String) : StreamEvent()
        data class WebSearchAvailable(val query: String, val results: List<SearchResult>) : StreamEvent()
    }
}
