package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GemmaTranscriber(private val context: Context) {

    companion object {
        const val TAG = "GemmaTranscriber"
        const val DEFAULT_URL = "http://localhost:8080/transcribe"
        const val TIMEOUT_MS = 300000
        const val CONNECT_TIMEOUT_MS = 10000
        const val DEFAULT_PROMPT = """You are a speech-to-text transcription service. Transcribe the following audio content accurately. If you cannot hear clearly, say "Inaudible". Only output the transcription, nothing else."""

        @Volatile
        private var instance: GemmaTranscriber? = null

        fun getInstance(context: Context): GemmaTranscriber {
            return instance ?: synchronized(this) {
                instance ?: GemmaTranscriber(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceService = InferenceService.getInstance(context)

    private var _serverUrl: String = DEFAULT_URL
    private var useLocalEngine: Boolean = false

    init {
        val prefs = getPrefs()
        _serverUrl = prefs.getString("gemma_server_url", DEFAULT_URL) ?: DEFAULT_URL
    }

    fun getServerUrl(): String = _serverUrl

    fun setServerUrl(url: String) {
        _serverUrl = url.trim().trimEnd('/')
        savePrefs()
    }

    fun isConfigured(): Boolean = _serverUrl.isNotBlank() && _serverUrl.startsWith("http")

    fun setUseLocalEngine(enabled: Boolean) {
        useLocalEngine = enabled
    }

    fun isUsingLocalEngine(): Boolean = useLocalEngine

    fun isLocalEngineReady(): Boolean = inferenceService.isReady()

    fun initializeLocalEngine(modelPath: String, callback: (Boolean, String) -> Unit) {
        inferenceService.initialize(modelPath, InferenceService.Config(), callback)
    }

    fun setLocalModelPath(path: String) {
        // InferenceService handles model path internally
    }

    fun getLocalModelPath(): String? = null

    suspend fun isServerAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val url = URL(_serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            val response = conn.responseCode
            conn.disconnect()
            response >= 200
        } catch (e: Exception) {
            Log.e(TAG, "Server check failed: ${e.message}")
            true
        }
    }

    suspend fun transcribe(audioFile: File, customPrompt: String? = null, useLocalInference: Boolean = false): TranscriptionResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Transcribing file: ${audioFile.absolutePath}")

            if (!audioFile.exists()) {
                return@withContext TranscriptionResult(
                    success = false,
                    transcription = "",
                    errorMessage = "File not found"
                )
            }

            val useLocal = useLocalInference || useLocalEngine

            if (useLocal) {
                return@withContext transcribeWithLocalEngine(audioFile, customPrompt)
            }

            val chunker = AudioChunker()
            val chunkingResult = chunker.chunkAudioFile(audioFile)

            if (chunkingResult.chunks.isEmpty()) {
                return@withContext TranscriptionResult(success = true, transcription = "", errorMessage = null)
            }

            Log.d(TAG, "Created ${chunkingResult.chunks.size} chunks, processing in parallel...")
            return@withContext transcribeChunks(chunkingResult.chunks, customPrompt)
        }

    private suspend fun transcribeWithLocalEngine(audioFile: File, customPrompt: String?): TranscriptionResult {
        if (!inferenceService.isReady()) {
            return TranscriptionResult(
                success = false,
                transcription = "",
                errorMessage = "Local inference engine not ready. Please initialize first."
            )
        }

        return try {
            val audioData = audioFile.readBytes()
            val text = inferenceService.transcribeAudio(audioData, customPrompt ?: DEFAULT_PROMPT)
            TranscriptionResult(success = true, transcription = text.trim(), errorMessage = null)
        } catch (e: Exception) {
            Log.e(TAG, "Local inference failed: ${e.message}")
            TranscriptionResult(success = false, transcription = "", errorMessage = "Local inference failed: ${e.message}")
        }
    }

    suspend fun transcribeChunks(
        chunks: List<AudioChunker.ChunkResult>,
        customPrompt: String? = null,
        maxConcurrency: Int = 4
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Transcribing ${chunks.size} chunks with max concurrency: $maxConcurrency")

        if (chunks.isEmpty()) {
            return@withContext TranscriptionResult(success = false, transcription = "", errorMessage = "No chunks to transcribe")
        }

        val semaphore = Semaphore(maxConcurrency)
        val lock = kotlinx.coroutines.sync.Mutex()
        val results = mutableListOf<ChunkTranscription>()

        chunks.forEachIndexed { index, chunk ->
            semaphore.acquire()
            try {
                val result = async {
                    try {
                        Log.d(TAG, "Processing chunk $index/${chunks.size} in parallel")
                        val res = sendTranscriptionRequest(chunk.file.readBytes(), customPrompt)
                        ChunkTranscription(chunkIndex = index, startTimeMs = chunk.startTimeMs, endTimeMs = chunk.endTimeMs, transcription = res.transcription, success = res.success)
                    } catch (e: Exception) {
                        Log.e(TAG, "Chunk $index failed: ${e.message}")
                        ChunkTranscription(chunkIndex = index, startTimeMs = chunk.startTimeMs, endTimeMs = chunk.endTimeMs, transcription = "[Error: ${e.message}]", success = false)
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
                results.add(ChunkTranscription(chunkIndex = index, startTimeMs = chunk.startTimeMs, endTimeMs = chunk.endTimeMs, transcription = "[Error: ${e.message}]", success = false))
                lock.unlock()
            }
        }

        val hasError = results.any { !it.success }
        val fullTranscript = buildString {
            for (result in results.sortedBy { it.chunkIndex }) {
                if (result.transcription.isNotBlank() && !result.transcription.startsWith("[Error")) {
                    append(result.transcription)
                    append(" ")
                }
            }
        }.trim()

        Log.d(TAG, "Parallel transcription complete: ${results.size} chunks")
        return@withContext TranscriptionResult(success = !hasError, transcription = fullTranscript, errorMessage = if (hasError) "Some chunks failed" else null, chunkResults = results)
    }

    private suspend fun sendTranscriptionRequest(audioData: ByteArray, customPrompt: String?): TranscriptionResult {
        return suspendCoroutine { continuation ->
            scope.launch {
                try {
                    val result = doSendRequest(audioData, customPrompt)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resume(TranscriptionResult(success = false, transcription = "", errorMessage = e.message))
                }
            }
        }
    }

    private suspend fun doSendRequest(audioData: ByteArray, customPrompt: String?): TranscriptionResult {
        val url = URL(_serverUrl)
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val boundary = "----FormBoundary" + System.currentTimeMillis()
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val prompt = customPrompt?.trim() ?: DEFAULT_PROMPT
            val body = buildMultipartBody(boundary, audioData, prompt)
            conn.outputStream.use { output -> output.write(body) }

            val responseCode = conn.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "Raw response: $response")

                try {
                    val jsonResponse = gson.fromJson(response, GemmaResponse::class.java)
                    Log.d(TAG, "Parsed response: result=${jsonResponse.result}, text=${jsonResponse.text}")
                    val transcription = jsonResponse.result ?: jsonResponse.text ?: jsonResponse.transcription ?: ""
                    Log.d(TAG, "Final transcription: $transcription")
                    return TranscriptionResult(success = true, transcription = transcription, errorMessage = null)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                    return TranscriptionResult(success = true, transcription = response)
                }
            } else {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                return TranscriptionResult(success = false, transcription = "", errorMessage = error)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipartBody(boundary: String, audioData: ByteArray, prompt: String): ByteArray {
        val builder = StringBuilder()
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"text\"\r\n\r\n")
        builder.append("$prompt\r\n")
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n")
        builder.append("Content-Type: audio/wav\r\n\r\n")
        val bodyBytes = builder.toString().toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return bodyBytes + audioData + footer
    }

    suspend fun recognizeHandwriting(bitmap: Bitmap, prompt: String? = null): TranscriptionResult {
        if (!inferenceService.isReady()) {
            return TranscriptionResult(success = false, transcription = "", errorMessage = "Local inference engine not ready")
        }

        return try {
            val text = inferenceService.recognizeHandwriting(bitmap, prompt)
            TranscriptionResult(success = true, transcription = text.trim(), errorMessage = null)
        } catch (e: Exception) {
            TranscriptionResult(success = false, transcription = "", errorMessage = "Handwriting recognition failed: ${e.message}")
        }
    }

    suspend fun understandImage(bitmap: Bitmap, question: String): TranscriptionResult {
        if (!inferenceService.isReady()) {
            return TranscriptionResult(success = false, transcription = "", errorMessage = "Local inference engine not ready")
        }

        return try {
            val text = inferenceService.understandImage(bitmap, question)
            TranscriptionResult(success = true, transcription = text.trim(), errorMessage = null)
        } catch (e: Exception) {
            TranscriptionResult(success = false, transcription = "", errorMessage = "Image understanding failed: ${e.message}")
        }
    }

    suspend fun processYouTube(url: String, callback: (Float) -> Unit = {}): TranscriptionResult {
        if (!inferenceService.isReady()) {
            return TranscriptionResult(success = false, transcription = "", errorMessage = "Local inference engine not ready")
        }

        return try {
            val result = inferenceService.processYouTube(url) { progress ->
                callback(progress)
            }
            result
        } catch (e: Exception) {
            TranscriptionResult(success = false, transcription = "", errorMessage = "YouTube processing failed: ${e.message}")
        }
    }

    suspend fun transcribeBatch(files: List<File>, customPrompt: String? = null, maxConcurrency: Int = 4): TranscriptionResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Batch transcribing ${files.size} files")

            if (files.isEmpty()) {
                return@withContext TranscriptionResult(success = false, transcription = "", errorMessage = "No files provided")
            }

            val useLocal = useLocalEngine && inferenceService.isReady()

            if (useLocal) {
                return@withContext inferenceService.batchTranscribeAudio(files, customPrompt)
            }

            val client = GemmaServerClient(context)
            val serverResult = client.transcribeBatch(
                audioFiles = files,
                customPrompt = customPrompt
            )

            val texts = serverResult.results.joinToString("\n\n") { "${it.index + 1}. ${it.result}" }
            TranscriptionResult(success = serverResult.success, transcription = texts, errorMessage = serverResult.errorMessage)
        }

    suspend fun askAudio(audioFile: File, question: String, customPrompt: String? = null): TranscriptionResult {
        if (!inferenceService.isReady()) {
            return TranscriptionResult(success = false, transcription = "", errorMessage = "Local inference engine not ready")
        }

        return try {
            val audioData = audioFile.readBytes()
            val transcription = inferenceService.transcribeAudio(audioData, customPrompt ?: DEFAULT_PROMPT)

            val finalPrompt = """
                Audio transcription: "$transcription"
                
                Question: $question
                
                Please answer based on the audio content above.
            """.trimIndent()

            val answer = inferenceService.runInference(finalPrompt)
            TranscriptionResult(success = true, transcription = answer.trim(), errorMessage = null)
        } catch (e: Exception) {
            TranscriptionResult(success = false, transcription = "", errorMessage = "Audio prompt processing failed: ${e.message}")
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
