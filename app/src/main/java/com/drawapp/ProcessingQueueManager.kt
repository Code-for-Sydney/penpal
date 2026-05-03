package com.drawapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Processing Queue Manager for offline AI transcription.
 *
 * Key insight: Model load (~2min) + first inference (~30s) is slow,
 * but subsequent inferences are fast (~5s). This queue:
 * 1. Queues recordings for later processing
 * 2. Processes immediately if model ready, queues otherwise
 * 3. Keeps model warm for faster subsequent calls
 * 4. Handles batch processing efficiently
 *
 * Uses the existing HandwritingRecognizer (Gemma4) for inference.
 */
class ProcessingQueueManager private constructor(private val context: Context) {

    companion object {
        const val TAG = "ProcessingQueue"

        // Processing priorities
        const val PRIORITY_HIGH = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_LOW = 2

        // Storage keys
        private const val KEY_QUEUE = "processing_queue"
        private const val KEY_RESULTS = "processing_results"

        // Singleton accessor
        @JvmStatic
        operator fun invoke(context: Context): ProcessingQueueManager {
            return getInstance(context)
        }

        @JvmStatic
        fun getInstance(context: Context): ProcessingQueueManager {
            return instance ?: synchronized(this) {
                instance ?: ProcessingQueueManager(context.applicationContext).also { instance = it }
            }
        }

        @Volatile
        private var instance: ProcessingQueueManager? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("processing_queue", Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()

    // Queue state
    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItemsFlow: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessingFlow: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _processedCount = MutableStateFlow(0)
    val processedCountFlow: StateFlow<Int> = _processedCount.asStateFlow()

    // Listeners for UI updates
    private val progressListeners = mutableListOf<(QueueItem, ProcessingStatus) -> Unit>()

    init {
        // Load saved queue on init
        loadQueue()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Queue Item Data Class
    // ═══════════════════════════════════════════════════════════════════

    data class QueueItem(
        val id: String,
        val audioFile: File,
        val customPrompt: String? = null,
        val priority: Int = PRIORITY_NORMAL,
        val addedAt: Long = System.currentTimeMillis(),
        val status: ProcessingStatus = ProcessingStatus.QUEUED,
        val progress: Float = 0f,
        val result: String? = null,
        val error: String? = null,
        val processedAt: Long? = null
    )

    enum class ProcessingStatus {
        QUEUED,       // Waiting in queue
        LOADING,      // Model is being loaded
        PROCESSING,   // Currently being transcribed
        COMPLETED,    // Successfully transcribed
        FAILED,       // Transcription failed
        CANCELLED     // User cancelled
    }

    // ═══════════════════════════════════════════════════════════════════
    // Queue Management
    // ══════════════════════════════════════════

    /**
     * Add a recording to the processing queue
     */
    fun enqueue(
        audioFile: File,
        customPrompt: String? = null,
        priority: Int = PRIORITY_NORMAL
    ): QueueItem {
        val item = QueueItem(
            id = System.currentTimeMillis().toString() + "_" + audioFile.name.hashCode(),
            audioFile = audioFile,
            customPrompt = customPrompt,
            priority = priority
        )

        val currentQueue = _queueItems.value.toMutableList()

        // Insert based on priority
        val insertIndex = currentQueue.indexOfFirst { it.priority > priority }.takeIf { it >= 0 }
            ?: currentQueue.size
        currentQueue.add(insertIndex, item)

        _queueItems.value = currentQueue
        saveQueue()

        Log.d(TAG, "Enqueued: ${audioFile.name} (priority: $priority, queue size: ${currentQueue.size})")

        return item
    }

    /**
     * Remove item from queue
     */
    fun remove(itemId: String) {
        val currentQueue = _queueItems.value.toMutableList()
        currentQueue.removeAll { it.id == itemId }
        _queueItems.value = currentQueue
        saveQueue()
    }

    /**
     * Clear completed items
     */
    fun clearCompleted() {
        val currentQueue = _queueItems.value.toMutableList()
        currentQueue.removeAll { it.status == ProcessingStatus.COMPLETED }
        _queueItems.value = currentQueue
        saveQueue()
    }

    /**
     * Clear all items
     */
    fun clearAll() {
        _queueItems.value = emptyList()
        saveQueue()
    }

    /**
     * Move item to front of queue (high priority)
     */
    fun boostPriority(itemId: String) {
        updateItem(itemId) { it.copy(priority = PRIORITY_HIGH) }
    }

    /**
     * Retry failed item
     */
    fun retry(itemId: String) {
        updateItem(itemId) {
            it.copy(
                status = ProcessingStatus.QUEUED,
                error = null,
                progress = 0f
            )
        }
        // Trigger processing if not running
        processNextIfIdle()
    }

    private fun updateItem(itemId: String, update: (QueueItem) -> QueueItem) {
        val currentQueue = _queueItems.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            currentQueue[index] = update(currentQueue[index])
            _queueItems.value = currentQueue
            saveQueue()
        }
    }

    private fun updateItemStatus(itemId: String, status: ProcessingStatus, progress: Float? = null, result: String? = null, error: String? = null) {
        updateItem(itemId) {
            it.copy(
                status = status,
                progress = progress ?: it.progress,
                result = result ?: it.result,
                error = error,
                processedAt = if (status == ProcessingStatus.COMPLETED) System.currentTimeMillis() else it.processedAt
            )
        }
        // Notify listeners
        val item = _queueItems.value.find { it.id == itemId }
        item?.let { notifyProgress(it, status) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Processing
    // ═══════════════════════════════════════════════════════════════════

    private var isProcessingLoopRunning = false
    private var currentProcessingJob: Job? = null

    /**
     * Start the processing loop if not already running
     */
    fun startProcessing() {
        if (isProcessingLoopRunning) return
        isProcessingLoopRunning = true
        processNextIfIdle()
    }

    /**
     * Stop the processing loop
     */
    fun stopProcessing() {
        isProcessingLoopRunning = false
        currentProcessingJob?.cancel()
        currentProcessingJob = null
    }

    /**
     * Process next item if not busy
     */
    private fun processNextIfIdle() {
        if (!isProcessingLoopRunning) return
        if (_isProcessing.value) return

        val nextItem = _queueItems.value
            .filter { it.status == ProcessingStatus.QUEUED }
            .minWithOrNull(compareBy({ item -> item.priority }, { item -> item.addedAt }))
            ?: return

        currentProcessingJob = scope.launch {
            processItem(nextItem)
            // Process next item after completion
            processNextIfIdle()
        }
    }

    /**
     * Process a single queue item
     */
    private suspend fun processItem(item: QueueItem) {
        if (!item.audioFile.exists()) {
            updateItemStatus(item.id, ProcessingStatus.FAILED, error = "Audio file not found")
            return
        }

        _isProcessing.value = true

        try {
            // Update status to processing
            updateItemStatus(item.id, ProcessingStatus.PROCESSING, progress = 0.2f)

            // For audio transcription, we use the GemmaServerClient
            // The queue keeps the server warm for faster processing
            updateItemStatus(item.id, ProcessingStatus.PROCESSING, progress = 0.4f)

            val result = withContext(Dispatchers.IO) {
                try {
                    // Use the shared server from Application
                    val app = context.applicationContext as? PenpalApplication
                    val server = app?.gemmaServer

                    if (server == null) {
                        return@withContext "[Error] Server not available. Please restart the app."
                    }

                    // Use the parallel chunk processing for speed
                    val transcriptionResult = server.transcribeWithAutoChunking(item.audioFile, item.customPrompt)

                    if (transcriptionResult?.success == true) {
                        transcriptionResult.transcription
                    } else {
                        "[Queued] ${transcriptionResult?.errorMessage ?: "Server unavailable"}"
                    }
                } catch (e: Exception) {
                    "[Queued] Error: ${e.message}"
                }
            }

            updateItemStatus(item.id, ProcessingStatus.COMPLETED, progress = 1f, result = result)

            // Increment processed count
            _processedCount.value++

            Log.d(TAG, "Completed: ${item.audioFile.name} -> $result")

        } catch (e: Exception) {
            Log.e(TAG, "Processing failed: ${e.message}")
            updateItemStatus(item.id, ProcessingStatus.FAILED, error = e.message)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Prepare audio file for Gemma processing.
     * Since Gemma is multimodal, we convert audio to a visual representation
     * or use the server for actual transcription with cached warm state.
     */
    private fun prepareAudioForGemma(audioFile: File): ByteArray? {
        // Read WAV header to validate
        return try {
            FileInputStream(audioFile).use { fis ->
                // Skip WAV header (44 bytes)
                fis.skip(44)
                val dataSize = (audioFile.length() - 44).toInt()
                val buffer = ByteArray(dataSize)
                fis.read(buffer)
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Simulate transcription (placeholder for real implementation).
     * In production, this would integrate with on-device audio models.
     */
    private suspend fun simulateTranscription(item: QueueItem): String {
        // Simulate processing time based on file size
        val fileSizeSeconds = item.audioFile.length() / 64000 // ~16kHz mono
        val processingTime = minOf(fileSizeSeconds * 100L, 30000L) // Max 30s

        delay(processingTime)

        // For demo, return a placeholder
        // In production, you'd call the on-device model here
        return "[Queued for server transcription: ${item.audioFile.name}]"
    }

    // ═══════════════════════════════════════════════════════════════════
    // Results Management
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get result for a specific item
     */
    fun getResult(itemId: String): String? {
        return _queueItems.value.find { it.id == itemId }?.result
    }

    /**
     * Get all completed items
     */
    fun getCompletedItems(): List<QueueItem> {
        return _queueItems.value.filter { it.status == ProcessingStatus.COMPLETED }
    }

    /**
     * Get all failed items
     */
    fun getFailedItems(): List<QueueItem> {
        return _queueItems.value.filter { it.status == ProcessingStatus.FAILED }
    }

    /**
     * Get queue statistics
     */
    fun getStats(): QueueStats {
        val items = _queueItems.value
        return QueueStats(
            totalQueued = items.count { it.status == ProcessingStatus.QUEUED },
            inProgress = items.count { it.status == ProcessingStatus.PROCESSING || it.status == ProcessingStatus.LOADING },
            completed = items.count { it.status == ProcessingStatus.COMPLETED },
            failed = items.count { it.status == ProcessingStatus.FAILED },
            totalProcessed = _processedCount.value
        )
    }

    data class QueueStats(
        val totalQueued: Int,
        val inProgress: Int,
        val completed: Int,
        val failed: Int,
        val totalProcessed: Int
    )

    // ═══════════════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════════════

    private fun saveQueue() {
        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(_queueItems.value.map { it.toStorable() })
                prefs.edit().putString(KEY_QUEUE, json).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue: ${e.message}")
            }
        }
    }

    private fun loadQueue() {
        try {
            val json = prefs.getString(KEY_QUEUE, null)
            if (json != null) {
                val items = gson.fromJson(json, Array<StorableQueueItem>::class.java)
                    .map { it.toQueueItem() }
                    // Filter out items whose files no longer exist
                    .filter { it.audioFile.exists() }
                _queueItems.value = items
                Log.d(TAG, "Loaded ${items.size} queue items")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue: ${e.message}")
        }
    }

    // Storable version (excludes File object)
    private data class StorableQueueItem(
        val id: String,
        val audioFilePath: String,
        val customPrompt: String?,
        val priority: Int,
        val addedAt: Long,
        val status: String,
        val progress: Float,
        val result: String?,
        val error: String?,
        val processedAt: Long?
    ) {
        fun toQueueItem() = QueueItem(
            id = id,
            audioFile = File(audioFilePath),
            customPrompt = customPrompt,
            priority = priority,
            addedAt = addedAt,
            status = ProcessingStatus.valueOf(status),
            progress = progress,
            result = result,
            error = error,
            processedAt = processedAt
        )
    }

    private fun QueueItem.toStorable() = StorableQueueItem(
        id = id,
        audioFilePath = audioFile.absolutePath,
        customPrompt = customPrompt,
        priority = priority,
        addedAt = addedAt,
        status = status.name,
        progress = progress,
        result = result,
        error = error,
        processedAt = processedAt
    )

    // ═══════════════════════════════════════════════════════════════════
    // Event Listeners
    // ═══════════════════════════════════════════════════════════════════

    fun addProgressListener(listener: (QueueItem, ProcessingStatus) -> Unit) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: (QueueItem, ProcessingStatus) -> Unit) {
        progressListeners.remove(listener)
    }

    private fun notifyProgress(item: QueueItem, status: ProcessingStatus) {
        progressListeners.forEach { it(item, status) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════════════════

    fun cleanup() {
        stopProcessing()
        scope.cancel()
        instance = null
    }
}