package com.penpal.feature.stacks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.data.StackDao
import com.penpal.core.data.StackEntity
import com.penpal.core.data.PenpalDatabase
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * ViewModel for the notebook editor.
 * Manages the document's blocks and handles user interactions.
 */
class StackEditorViewModel(
    private val context: Context? = null,
    private val stackDao: StackDao? = null,
    private val workerLauncher: com.penpal.core.processing.WorkerLauncher? = null,
    private val inferenceBridge: InferenceBridge? = null
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow(StackEditorState())
    val uiState: StateFlow<StackEditorState> = _uiState.asStateFlow()

    // Current viewport transform for the graph canvas
    private val _canvasOffset = MutableStateFlow(Offset.Zero)
    val canvasOffset: StateFlow<Offset> = _canvasOffset.asStateFlow()

    private val _canvasScale = MutableStateFlow(1f)
    val canvasScale: StateFlow<Float> = _canvasScale.asStateFlow()

    // Graph editing state
    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    private val _isAddingEdge = MutableStateFlow(false)
    val isAddingEdge: StateFlow<Boolean> = _isAddingEdge.asStateFlow()

    private val _edgeStartNodeId = MutableStateFlow<String?>(null)
    val edgeStartNodeId: StateFlow<String?> = _edgeStartNodeId.asStateFlow()

    init {
        // Create a new empty document on init
        createNewDocument()

        // Observe extraction jobs and update matching process blocks
        observeExtractionJobs()
    }

    private fun observeExtractionJobs() {
        workerLauncher ?: return
        viewModelScope.launch {
            workerLauncher.observeJobs().collect { jobs ->
                val currentBlocks = _uiState.value.document.blocks
                val updatedBlocks = currentBlocks.map { block ->
                    if (block is Block.ProcessBlock && block.status != ProcessStatus.DONE && block.status != ProcessStatus.ERROR) {
                        // Find matching job by sourceUri
                        val matchingJob = jobs.find { it.sourceUri == block.sourceUri }
                        when (matchingJob?.status) {
                            "QUEUED" -> block.copy(status = ProcessStatus.QUEUED)
                            "RUNNING" -> block.copy(status = ProcessStatus.RUNNING)
                            "DONE" -> {
                                // Job completed - refresh extracted text
                                // Use a side effect to fetch the text if it's a text-based block
                                if (block.mediaType == MediaType.TEXT || block.mediaType == MediaType.IMAGE) {
                                    fetchExtractedTextForBlock(block.id, block.sourceUri)
                                }
                                block.copy(status = ProcessStatus.DONE)
                            }
                            "FAILED" -> block.copy(
                                status = ProcessStatus.ERROR,
                                errorMessage = "Extraction failed"
                            )
                            else -> block
                        }
                    } else block
                }

                if (updatedBlocks != currentBlocks) {
                    _uiState.update { state ->
                        state.copy(
                            document = state.document.copy(
                                blocks = updatedBlocks,
                                updatedAt = System.currentTimeMillis()
                            ),
                            isDirty = true
                        )
                    }
                }
            }
        }
    }

    /** Creates a new empty document */
    fun createNewDocument() {
        val docId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
document = StackDocument(
                    id = docId,
                    title = "Untitled",
                    blocks = listOf(
                        Block.TextBlock(
                            id = UUID.randomUUID().toString(),
                            content = ""
                        )
                    )
                ),
                selectedBlockId = null,
                isLoading = false,
                error = null,
                isDirty = false
            )
        }
    }

    /** Loads an existing document */
    fun loadDocument(document: StackDocument) {
        _uiState.update {
            it.copy(
                document = document,
                selectedBlockId = null,
                isLoading = false,
                error = null,
                isDirty = false
            )
        }
    }

    fun loadFromDatabase(stackId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val entity = stackDao?.getStack(stackId)
                if (entity != null) {
                    val blocks = deserializeBlocks(entity.blocksJson)
                    val document = StackDocument(
                        id = entity.id,
                        title = entity.title,
                        blocks = blocks,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt
                    )
                    loadDocument(document)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Stack not found"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load stack"
                    )
                }
            }
        }
    }

    /** Handles editor events */
    fun onEvent(event: StackEvent) {
        when (event) {
            is StackEvent.AddBlock -> addBlock(event.block, event.afterBlockId)
            is StackEvent.RemoveBlock -> removeBlock(event.blockId)
            is StackEvent.MoveBlock -> moveBlock(event.blockId, event.newIndex)
            is StackEvent.UpdateBlock -> updateBlock(event.block)
            is StackEvent.SelectBlock -> selectBlock(event.blockId)
            is StackEvent.UpdateGraphNode -> updateGraphNode(event.node)
            is StackEvent.AddGraphEdge -> addGraphEdge(event.edge)
            is StackEvent.UpdateDocumentTitle -> updateDocumentTitle(event.title)
            is StackEvent.UpdateSystemPrompt -> updateSystemPrompt(event.prompt)
            is StackEvent.UpdateAgentPrompt -> updateAgentPrompt(event.prompt)
            is StackEvent.ToggleProcessView -> toggleProcessView(event.blockId)
            is StackEvent.SaveDocument -> saveDocument()
            is StackEvent.LoadDocument -> loadDocument()
            is StackEvent.SetImageUri -> setImageUri(event.blockId, event.uri)
            is StackEvent.DeleteDocument -> deleteDocument()
            is StackEvent.AddProcessBlock -> addProcessBlock(event.mediaType, event.afterBlockId)
            is StackEvent.UpdateProcessBlockStatus -> updateProcessBlockStatus(event.blockId, event.status, event.text, event.error)
            is StackEvent.ReprocessBlock -> reprocessBlock(event.blockId)
            is StackEvent.ProcessBlockWithAI -> processBlockWithAI(event.blockId)
        }
    }

    /**
     * Sets the image URI for an ImageBlock
     */
    fun setImageUri(blockId: String, uri: Uri) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.ImageBlock && block.id == blockId) {
                            block.copy(uri = uri)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun addBlock(block: Block, afterBlockId: String? = null) {
        _uiState.update { state ->
            val blocks = state.document.blocks.toMutableList()
            val index = if (afterBlockId != null) {
                blocks.indexOfFirst { it.id == afterBlockId } + 1
            } else {
                blocks.size
            }
            blocks.add(index.coerceAtLeast(0), block)
            state.copy(
                document = state.document.copy(
                    blocks = blocks,
                    updatedAt = System.currentTimeMillis()
                ),
                selectedBlockId = block.id,
                isDirty = true
            )
        }
    }

    private fun removeBlock(blockId: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.filterNot { it.id == blockId },
                    updatedAt = System.currentTimeMillis()
                ),
                selectedBlockId = if (state.selectedBlockId == blockId) null else state.selectedBlockId,
                isDirty = true
            )
        }
    }

    private fun moveBlock(blockId: String, newIndex: Int) {
        _uiState.update { state ->
            val blocks = state.document.blocks.toMutableList()
            val currentIndex = blocks.indexOfFirst { it.id == blockId }
            if (currentIndex < 0 || newIndex < 0 || newIndex >= blocks.size) return@update state

            val block = blocks.removeAt(currentIndex)
            blocks.add(newIndex, block)

            state.copy(
                document = state.document.copy(
                    blocks = blocks,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun updateBlock(block: Block) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map {
                        if (it.id == block.id) block else it
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }

        // If this is a ProcessBlock with a URI set to PENDING, trigger processing
        if (block is Block.ProcessBlock && block.sourceUri.isNotBlank() && block.status == ProcessStatus.PENDING) {
            enqueueProcessBlock(block)
        }
    }

    private fun enqueueProcessBlock(block: Block.ProcessBlock) {
        workerLauncher ?: return
        val mimeType = when (block.mediaType) {
            MediaType.IMAGE -> "image"
            MediaType.AUDIO -> "audio"
            MediaType.VIDEO -> "video"
            MediaType.TEXT -> "text"
        }

        viewModelScope.launch {
            try {
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            blocks = state.document.blocks.map {
                                if (it.id == block.id) block.copy(status = ProcessStatus.QUEUED) else it
                            },
                            updatedAt = System.currentTimeMillis()
                        ),
                        isDirty = true
                    )
                }
                workerLauncher.enqueue(block.sourceUri, mimeType, "FULL_TEXT")
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            blocks = state.document.blocks.map {
                                if (it.id == block.id) block.copy(
                                    status = ProcessStatus.ERROR,
                                    errorMessage = e.message ?: "Failed to enqueue"
                                ) else it
                            },
                            updatedAt = System.currentTimeMillis()
                        ),
                        isDirty = true
                    )
                }
            }
        }
    }

    private fun reprocessBlock(blockId: String) {
        val block = _uiState.value.document.blocks.find { it.id == blockId } as? Block.ProcessBlock ?: return
        val updatedBlock = block.copy(
            status = ProcessStatus.PENDING,
            extractedText = "",
            errorMessage = null
        )
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { if (it.id == blockId) updatedBlock else it },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        enqueueProcessBlock(updatedBlock)
    }

    private fun processBlockWithAI(blockId: String) {
        val block = _uiState.value.document.blocks.find { it.id == blockId } as? Block.ProcessBlock ?: return
        val bridge = inferenceBridge

        if (bridge == null) {
            _uiState.update { state ->
                state.copy(
                    document = state.document.copy(
                        blocks = state.document.blocks.map {
                            if (it.id == blockId) block.copy(
                                status = ProcessStatus.ERROR,
                                errorMessage = "AI model not available"
                            ) else it
                        }
                    )
                )
            }
            return
        }

        if (!bridge.isReady.value) {
            _uiState.update { state ->
                state.copy(
                    document = state.document.copy(
                        blocks = state.document.blocks.map {
                            if (it.id == blockId) block.copy(
                                status = ProcessStatus.ERROR,
                                errorMessage = "Model not loaded. Please load the model first."
                            ) else it
                        }
                    )
                )
            }
            return
        }

        // Update status to running with initial progress
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map {
                        if (it.id == blockId) block.copy(
                            status = ProcessStatus.RUNNING,
                            progress = 10
                        ) else it
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }

        // Process based on media type
        viewModelScope.launch {
            try {
                // Pass hasMediaData=true for media types where we send actual data (not just text)
                val hasMediaData = block.mediaType in listOf(MediaType.IMAGE, MediaType.AUDIO, MediaType.VIDEO)
                val prompt = buildAIProcessingPrompt(block, hasMediaData)

                // For images, use multimodal inference
                if (block.mediaType == MediaType.IMAGE && block.sourceUri.isNotBlank()) {
                    val ctx = context ?: throw Exception("Context not available")
                    val bitmap = loadBitmapFromUri(ctx, block.sourceUri)
                    if (bitmap != null) {
                        var accumulatedProgress = 10
                        bridge.runInferenceWithImageFlow(prompt, bitmap).collect { result ->
                            accumulatedProgress = minOf(accumulatedProgress + 5, 90)
                            _uiState.update { state ->
                                state.copy(
                                    document = state.document.copy(
                                        blocks = state.document.blocks.map {
                                            if (it.id == blockId) block.copy(
                                                status = ProcessStatus.DONE,
                                                progress = 100,
                                                extractedText = result
                                            ) else it
                                        },
                                        updatedAt = System.currentTimeMillis()
                                    ),
                                    isDirty = true
                                )
                            }
                        }
                    } else {
                        throw Exception("Failed to load image")
                    }
                } else if (block.mediaType == MediaType.AUDIO && block.sourceUri.isNotBlank()) {
                    // For audio, use robust decoder to get 16kHz float32 PCM samples
                    val ctx = context ?: throw Exception("Context not available")
                    val audioSamples: FloatArray? = withContext(Dispatchers.IO) {
                        decodeAudioToFloat32Pcm(ctx, block.sourceUri)
                    }
                    if (audioSamples != null) {
                        var accumulatedProgress = 10
                        bridge.runInferenceWithAudioFlow(prompt, audioSamples).collect { result ->
                            accumulatedProgress = minOf(accumulatedProgress + 5, 90)
                            _uiState.update { state ->
                                state.copy(
                                    document = state.document.copy(
                                        blocks = state.document.blocks.map {
                                            if (it.id == blockId) block.copy(
                                                status = ProcessStatus.DONE,
                                                progress = 100,
                                                extractedText = result
                                            ) else it
                                        },
                                        updatedAt = System.currentTimeMillis()
                                    ),
                                    isDirty = true
                                )
                            }
                        }
                    } else {
                        throw Exception("Failed to decode audio file. Ensure it is a valid audio format.")
                    }
                } else if (block.mediaType == MediaType.VIDEO && block.sourceUri.isNotBlank()) {
                    // For videos, extract frames every 2 seconds and create a montage
                    val ctx = context ?: throw Exception("Context not available")
                    val duration = getVideoDuration(ctx, block.sourceUri)
                    val montage = extractVideoMontage(ctx, block.sourceUri, duration)
                    val durationSec = duration / 1000
                    
                    val sequencePrompt = """
                        |$prompt
                        |
                        |This is a sequence of frames from a video (duration: ${durationSec}s), extracted every 2 seconds.
                        |The frames are tiled in a grid. Analyze the actions and changes across these frames.
                    """.trimMargin()

                    if (montage != null) {
                        var accumulatedProgress = 10
                        bridge.runInferenceWithImageFlow(sequencePrompt, montage).collect { result ->
                            accumulatedProgress = minOf(accumulatedProgress + 5, 90)
                            _uiState.update { state ->
                                state.copy(
                                    document = state.document.copy(
                                        blocks = state.document.blocks.map {
                                            if (it.id == blockId) block.copy(
                                                status = ProcessStatus.DONE,
                                                progress = 100,
                                                extractedText = "Video (${durationSec}s):\n$result"
                                            ) else it
                                        },
                                        updatedAt = System.currentTimeMillis()
                                    ),
                                    isDirty = true
                                )
                            }
                        }
                    } else {
                        throw Exception("Failed to extract video frames")
                    }
                } else if (block.mediaType == MediaType.TEXT && block.sourceUri.isNotBlank()) {
                    // For text blocks, load the actual file content before sending to AI
                    val ctx = context ?: throw Exception("Context not available")
                    val textContent = withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(block.sourceUri)
                            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        } catch (e: Exception) {
                            Log.e("NotebookEditorVM", "Failed to load text content for analysis: ${e.message}")
                            null
                        }
                    }
                    
                    val finalPrompt = if (textContent != null) {
                        // Build prompt with the actual text content instead of the URI
                        buildAIProcessingPrompt(block.copy(extractedText = textContent), false)
                    } else {
                        prompt
                    }

                    var accumulatedProgress = 10
                    bridge.runInferenceFlow(finalPrompt).collect { result ->
                        accumulatedProgress = minOf(accumulatedProgress + 5, 90)
                        _uiState.update { state ->
                            state.copy(
                                document = state.document.copy(
                                    blocks = state.document.blocks.map {
                                        if (it.id == blockId) block.copy(
                                            status = ProcessStatus.DONE,
                                            progress = 100,
                                            extractedText = result
                                        ) else it
                                    },
                                    updatedAt = System.currentTimeMillis()
                                ),
                                isDirty = true
                            )
                        }
                    }
                } else {
                    // For other types, use text-based inference
                    var accumulatedProgress = 10
                    bridge.runInferenceFlow(prompt).collect { result ->
                        accumulatedProgress = minOf(accumulatedProgress + 5, 90)
                        _uiState.update { state ->
                            state.copy(
                                document = state.document.copy(
                                    blocks = state.document.blocks.map {
                                        if (it.id == blockId) block.copy(
                                            status = ProcessStatus.DONE,
                                            progress = 100,
                                            extractedText = result
                                        ) else it
                                    },
                                    updatedAt = System.currentTimeMillis()
                                ),
                                isDirty = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            blocks = state.document.blocks.map {
                                if (it.id == blockId) block.copy(
                                    status = ProcessStatus.ERROR,
                                    progress = 0,
                                    errorMessage = e.message ?: "AI processing failed"
                                ) else it
                            }
                        )
                    )
                }
            }
        }
    }

    private fun loadBitmapFromUri(context: Context, uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (uriString.isBlank()) {
                Log.e("NotebookEditorVM", "Cannot load bitmap: URI is blank")
                return null
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: run {
                Log.e("NotebookEditorVM", "Cannot load bitmap: openInputStream returned null for $uri")
                null
            }
        } catch (e: SecurityException) {
            Log.e("NotebookEditorVM", "Failed to load bitmap: missing permission for $uriString: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e("NotebookEditorVM", "Failed to load bitmap for $uriString: ${e.message}")
            null
        }
    }

    private fun decodeAudioToFloat32Pcm(context: Context, uriString: String): FloatArray? {
        return try {
            val uri = Uri.parse(uriString)
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(context, uri, null)
            
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    break
                }
            }
            
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            
            // Configure decoder for PCM output
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            val info = android.media.MediaCodec.BufferInfo()
            val allSamples = mutableListOf<Float>()
            var isEOS = false
            
            val targetSampleRate = 16000
            val inputSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val maxSamples = targetSampleRate * 30 // Limit to 30 seconds to avoid AI processing issues

            while (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                
                var outIndex = decoder.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    
                    val pcmData = ShortArray(info.size / 2)
                    buffer.asShortBuffer().get(pcmData)
                    
                    // Convert to float and downmix to mono
                    for (i in 0 until pcmData.size step channelCount) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            if (i + c < pcmData.size) {
                                sum += pcmData[i + c].toFloat() / 32768f
                            }
                        }
                        allSamples.add(sum / channelCount)
                        
                        // Check if we've reached the duration limit early (approximate based on input rate)
                        if (allSamples.size > (maxSamples * (inputSampleRate.toFloat() / targetSampleRate))) {
                            isEOS = true
                            break
                        }
                    }
                    
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (isEOS) break
                    outIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            }
            
            decoder.stop()
            decoder.release()
            extractor.release()
            
            var result = allSamples.toFloatArray()
            Log.d("NotebookEditorVM", "Decoded ${result.size} raw samples at ${inputSampleRate}Hz")
            
            // Simple linear resampling if needed
            if (inputSampleRate != targetSampleRate) {
                val ratio = inputSampleRate.toDouble() / targetSampleRate.toDouble()
                val newSize = (result.size / ratio).toInt().coerceAtMost(maxSamples)
                val resampled = FloatArray(newSize)
                for (i in 0 until newSize) {
                    val srcPos = i * ratio
                    val srcIdx = srcPos.toInt()
                    val frac = (srcPos - srcIdx).toFloat()
                    val s0 = result.getOrElse(srcIdx) { 0f }
                    val s1 = result.getOrElse(srcIdx + 1) { s0 }
                    resampled[i] = s0 * (1f - frac) + s1 * frac
                }
                result = resampled
                Log.d("NotebookEditorVM", "Resampled to ${result.size} samples at ${targetSampleRate}Hz")
            } else if (result.size > maxSamples) {
                result = result.copyOfRange(0, maxSamples)
            }
            
            if (result.isEmpty()) {
                Log.e("NotebookEditorVM", "Decoded audio is empty")
                return null
            }
            
            result
        } catch (e: Exception) {
            Log.e("NotebookEditorVM", "Failed to decode audio: ${e.message}", e)
            null
        }
    }

    private fun extractVideoMontage(context: Context, uriString: String, durationMs: Long): Bitmap? {
        return try {
            val intervalMs = 2000L
            val frameTimes = mutableListOf<Long>()
            var currentTime = 500L // Start a bit into the video
            while (currentTime < durationMs) {
                frameTimes.add(currentTime)
                currentTime += intervalMs
            }
            if (frameTimes.isEmpty()) frameTimes.add(durationMs / 2)
            
            // Limit to 9 frames for a 3x3 grid
            val finalTimes = if (frameTimes.size > 9) {
                val step = frameTimes.size / 9
                List(9) { i -> frameTimes[i * step] }
            } else frameTimes

            val frames = finalTimes.mapNotNull { loadVideoFrame(context, uriString, it) }
            if (frames.isEmpty()) return null
            
            // Create a grid
            val cols = if (frames.size <= 3) frames.size else 3
            val rows = (frames.size + cols - 1) / cols
            
            val frameWidth = 320
            val frameHeight = (frames[0].height * (frameWidth.toFloat() / frames[0].width)).toInt()
            
            val montage = Bitmap.createBitmap(cols * frameWidth, rows * frameHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(montage)
            
            frames.forEachIndexed { index, frame ->
                val r = index / cols
                val c = index % cols
                val scaled = Bitmap.createScaledBitmap(frame, frameWidth, frameHeight, true)
                canvas.drawBitmap(scaled, (c * frameWidth).toFloat(), (r * frameHeight).toFloat(), null)
            }
            
            montage
        } catch (e: Exception) {
            Log.e("NotebookEditorVM", "Failed to create video montage: ${e.message}", e)
            null
        }
    }

    private fun loadVideoFrame(context: Context, uriString: String, timeMs: Long = 0): Bitmap? {
        return try {
            if (uriString.isBlank()) {
                Log.e("NotebookEditorVM", "Cannot load video frame: URI is blank")
                return null
            }
            val uri = Uri.parse(uriString)
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            // Use requested time in microseconds
            val bitmap = retriever.getFrameAtTime(timeMs * 1000)
            retriever.release()
            bitmap
        } catch (e: SecurityException) {
            Log.e("NotebookEditorVM", "Failed to extract video frame: permission denied for $uriString: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e("NotebookEditorVM", "Failed to extract video frame for $uriString: ${e.message}")
            null
        }
    }

    private fun getVideoDuration(context: Context, uriString: String): Long {
        return try {
            val uri = Uri.parse(uriString)
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun getAudioDuration(context: Context, uriString: String): Long {
        return try {
            val uri = Uri.parse(uriString)
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun buildAIProcessingPrompt(block: Block.ProcessBlock, hasMediaData: Boolean = false): String {
        val (sourceInfo, task) = when (block.mediaType) {
            MediaType.IMAGE -> "an image" to """
                Analyze this image thoroughly. Provide:
                1. A detailed description of what's visible
                2. Any text or documents visible (OCR)
                3. Objects and their locations
                4. The overall context and setting
            """.trimIndent()
            MediaType.AUDIO -> "an audio file" to """
                Analyze this audio content thoroughly. Provide:
                1. What is being said or discussed
                2. Key speakers or voices (if identifiable)
                3. Important topics or themes
                4. Key takeaways or conclusions
            """.trimIndent()
            MediaType.VIDEO -> "a video frame" to """
                Analyze this video frame thoroughly. Provide:
                1. A detailed description of the visual content in this specific frame
                2. Key people, objects, or actions visible
                3. The overall context, setting, and mood
                4. Any text or identifying features present in the scene
            """.trimIndent()
            MediaType.TEXT -> "text content" to """
                Analyze this text thoroughly. Provide:
                1. A summary of the main points
                2. Key details and information
                3. Any code, formulas, or technical content
                4. Overall purpose and context
            """.trimIndent()
        }

        val content = when {
            block.extractedText.isNotEmpty() -> block.extractedText
            hasMediaData && block.sourceUri.isNotBlank() -> "See attached media content"
            block.sourceUri.isNotBlank() -> "Source: ${block.sourceUri}"
            else -> "No content available"
        }

        return """
            |You are analyzing $sourceInfo.
            |
            |$task
            |
            |Content to analyze:
            |$content
            |
            |Provide a comprehensive analysis with clear sections.
        """.trimMargin()
    }

    private fun fetchExtractedTextForBlock(blockId: String, sourceUri: String) {
        val ctx = context ?: return
        viewModelScope.launch {
            try {
                val db = PenpalDatabase.getInstance(ctx)
                // Collect chunks for this source and join them
                // Note: using first() on the flow from getChunksForSource
                val chunks = db.chunkDao().getChunksForSource(sourceUri).first()
                if (chunks.isNotEmpty()) {
                    val joinedText = chunks.joinToString("\n\n") { it.text }
                    _uiState.update { state ->
                        state.copy(
                            document = state.document.copy(
                                blocks = state.document.blocks.map { block ->
                                    if (block.id == blockId && block is Block.ProcessBlock) {
                                        block.copy(extractedText = joinedText)
                                    } else block
                                }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("NotebookEditorVM", "Failed to fetch extracted text: ${e.message}")
            }
        }
    }

    fun selectBlock(blockId: String?) {
        _uiState.update { it.copy(selectedBlockId = blockId) }
    }

    // ──────────────────────────────────────────────────────────────
    // Graph Node Editing
    // ──────────────────────────────────────────────────────────────

    /**
     * Updates a node's position (called during drag)
     */
    fun updateNodePosition(nodeId: String, newX: Float, newY: Float) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(
                                nodes = block.nodes.map { node ->
                                    if (node.id == nodeId) node.copy(posX = newX, posY = newY)
                                    else node
                                }
                            )
                        } else block
                    }
                ),
                isDirty = true
            )
        }
    }

    /**
     * Finalizes node position after drag ends (for undo support)
     */
    fun finalizeNodePosition(node: GraphNode) {
        updateGraphNode(node)
    }

    private fun updateGraphNode(node: GraphNode) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(
                                nodes = block.nodes.map { n ->
                                    if (n.id == node.id) node else n
                                }
                            )
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Adds a new node to a graph block
     */
    fun addNodeToGraph(graphBlockId: String, label: String, atX: Float, atY: Float) {
        val newNode = GraphNode(
            id = UUID.randomUUID().toString(),
            label = label,
            posX = atX,
            posY = atY
        )
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock && block.id == graphBlockId) {
                            block.copy(nodes = block.nodes + newNode)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        _selectedNodeId.value = newNode.id
    }

    /**
     * Adds an edge between two nodes
     */
    private fun addGraphEdge(edge: GraphEdge) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(edges = block.edges + edge)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Removes a node and its connected edges
     */
    fun removeNodeFromGraph(graphBlockId: String, nodeId: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock && block.id == graphBlockId) {
                            block.copy(
                                nodes = block.nodes.filterNot { it.id == nodeId },
                                edges = block.edges.filterNot {
                                    it.fromNodeId == nodeId || it.toNodeId == nodeId
                                }
                            )
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        if (_selectedNodeId.value == nodeId) {
            _selectedNodeId.value = null
        }
    }

    /**
     * Starts edge creation mode
     */
    fun startAddingEdge(fromNodeId: String) {
        _edgeStartNodeId.value = fromNodeId
        _isAddingEdge.value = true
    }

    /**
     * Completes edge creation or cancels
     */
    fun completeEdge(toNodeId: String) {
        val fromId = _edgeStartNodeId.value ?: return
        if (fromId != toNodeId) {
            addGraphEdge(
                GraphEdge(
                    id = UUID.randomUUID().toString(),
                    fromNodeId = fromId,
                    toNodeId = toNodeId
                )
            )
        }
        cancelEdgeCreation()
    }

    /**
     * Cancels edge creation mode
     */
    fun cancelEdgeCreation() {
        _edgeStartNodeId.value = null
        _isAddingEdge.value = false
    }

    // ──────────────────────────────────────────────────────────────
    // Canvas Transform
    // ──────────────────────────────────────────────────────────────

    fun updateCanvasOffset(offset: Offset) {
        _canvasOffset.value = offset
    }

    fun updateCanvasScale(scale: Float) {
        _canvasScale.value = scale.coerceIn(0.25f, 4f)
    }

    fun resetCanvasView() {
        _canvasOffset.value = Offset.Zero
        _canvasScale.value = 1f
    }

    // ──────────────────────────────────────────────────────────────
    // Document Management
    // ──────────────────────────────────────────────────────────────

    private fun updateDocumentTitle(title: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        
        // Auto-save title if it's not a new document
        val docId = _uiState.value.document.id
        if (docId.isNotBlank()) {
            viewModelScope.launch {
                stackDao?.updateTitle(docId, title, System.currentTimeMillis())
            }
        }
    }

    private fun updateSystemPrompt(prompt: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    systemPrompt = prompt,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun updateAgentPrompt(prompt: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    agentPrompt = prompt,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun toggleProcessView(blockId: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.ProcessBlock && block.id == blockId) {
                            block.copy(showParsedContent = !block.showParsedContent)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Saves the current document to the database
     */
    fun saveDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val document = _uiState.value.document
                val blocksJson = serializeBlocks(document.blocks)
                val entity = StackEntity(
                    id = document.id,
                    title = document.title,
                    blocksJson = blocksJson,
                    createdAt = document.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                stackDao?.insert(entity)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isDirty = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to save"
                    )
                }
            }
        }
    }

    /**
     * Deletes the current document from the database
     */
    private fun deleteDocument() {
        viewModelScope.launch {
            val docId = _uiState.value.document.id
            _uiState.update { it.copy(isLoading = true) }
            try {
                stackDao?.delete(docId)
                createNewDocument() // Reset to new document
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to delete"
                    )
                }
            }
        }
    }

    private fun loadDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // TODO: Load from Room database
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Serialization Helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Serializes blocks to JSON string for storage
     */
    private fun serializeBlocks(blocks: List<Block>): String {
        val serializableBlocks = blocks.map { block ->
            when (block) {
                is Block.TextBlock -> mapOf(
                    "type" to "text",
                    "id" to block.id,
                    "content" to block.content
                )
                is Block.ImageBlock -> mapOf(
                    "type" to "image",
                    "id" to block.id,
                    "uri" to (block.uri?.toString() ?: ""),
                    "caption" to block.caption
                )
                is Block.DrawingBlock -> mapOf(
                    "type" to "drawing",
                    "id" to block.id,
                    "pathData" to block.pathData,
                    "width" to block.width,
                    "height" to block.height
                )
                is Block.LatexBlock -> mapOf(
                    "type" to "latex",
                    "id" to block.id,
                    "expression" to block.expression
                )
                is Block.GraphBlock -> mapOf(
                    "type" to "graph",
                    "id" to block.id,
                    "graphId" to block.graphId,
                    "nodes" to block.nodes.map { node ->
                        mapOf(
                            "id" to node.id,
                            "label" to node.label,
                            "posX" to node.posX,
                            "posY" to node.posY,
                            "type" to node.type.name
                        )
                    },
                    "edges" to block.edges.map { edge ->
                        mapOf(
                            "id" to edge.id,
                            "fromNodeId" to edge.fromNodeId,
                            "toNodeId" to edge.toNodeId,
                            "label" to edge.label,
                            "type" to edge.type.name
                        )
                    }
                )
                is Block.EmbedBlock -> mapOf(
                    "type" to "embed",
                    "id" to block.id,
                    "sourceId" to block.sourceId,
                    "preview" to block.preview,
                    "embedType" to block.type.name
                )
                is Block.ProcessBlock -> mapOf(
                    "type" to "process",
                    "id" to block.id,
                    "sourceUri" to block.sourceUri,
                    "mediaType" to block.mediaType.name,
                    "status" to block.status.name,
                    "progress" to block.progress,
                    "extractedText" to block.extractedText,
                    "errorMessage" to (block.errorMessage ?: ""),
                    "showParsedContent" to block.showParsedContent
                )
            }
        }
        return gson.toJson(serializableBlocks)
    }

    /**
     * Deserializes blocks from JSON string
     */
    private fun deserializeBlocks(json: String): List<Block> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(json, type)
            data.mapNotNull { item ->
                when (item["type"] as? String) {
                    "text" -> Block.TextBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        content = item["content"] as? String ?: ""
                    )
                    "image" -> Block.ImageBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        uri = (item["uri"] as? String)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                        caption = item["caption"] as? String ?: ""
                    )
                    "drawing" -> Block.DrawingBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        pathData = item["pathData"] as? String ?: "",
                        width = (item["width"] as? Number)?.toFloat() ?: 800f,
                        height = (item["height"] as? Number)?.toFloat() ?: 600f
                    )
                    "latex" -> Block.LatexBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        expression = item["expression"] as? String ?: ""
                    )
                    "graph" -> {
                        val nodesData = item["nodes"] as? List<Map<String, Any>> ?: emptyList()
                        val edgesData = item["edges"] as? List<Map<String, Any>> ?: emptyList()
                        Block.GraphBlock(
                            id = item["id"] as? String ?: return@mapNotNull null,
                            graphId = item["graphId"] as? String ?: "",
                            nodes = nodesData.mapNotNull { node ->
                                GraphNode(
                                    id = node["id"] as? String ?: return@mapNotNull null,
                                    label = node["label"] as? String ?: "",
                                    posX = (node["posX"] as? Number)?.toFloat() ?: 0f,
                                    posY = (node["posY"] as? Number)?.toFloat() ?: 0f
                                )
                            },
                            edges = edgesData.mapNotNull { edge ->
                                GraphEdge(
                                    id = edge["id"] as? String ?: return@mapNotNull null,
                                    fromNodeId = edge["fromNodeId"] as? String ?: return@mapNotNull null,
                                    toNodeId = edge["toNodeId"] as? String ?: return@mapNotNull null
                                )
                            }
                        )
                    }
                    "embed" -> Block.EmbedBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        sourceId = item["sourceId"] as? String ?: "",
                        preview = item["preview"] as? String ?: ""
                    )
                    "process" -> Block.ProcessBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        sourceUri = item["sourceUri"] as? String ?: "",
                        mediaType = (item["mediaType"] as? String)?.let {
                            try { MediaType.valueOf(it) } catch (_: Exception) { MediaType.TEXT }
                        } ?: MediaType.TEXT,
                        status = (item["status"] as? String)?.let {
                            try { ProcessStatus.valueOf(it) } catch (_: Exception) { ProcessStatus.PENDING }
                        } ?: ProcessStatus.PENDING,
                        progress = (item["progress"] as? Number)?.toInt() ?: 0,
                        extractedText = item["extractedText"] as? String ?: "",
                        errorMessage = (item["errorMessage"] as? String)?.takeIf { it.isNotEmpty() },
                        showParsedContent = (item["showParsedContent"] as? Boolean) ?: true
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────────

    private fun addProcessBlock(mediaType: MediaType, afterBlockId: String? = null) {
        val block = Block.ProcessBlock(
            id = UUID.randomUUID().toString(),
            mediaType = mediaType,
            status = ProcessStatus.PENDING
        )
        addBlock(block, afterBlockId)
    }

    private fun updateProcessBlockStatus(blockId: String, status: ProcessStatus, text: String, error: String?) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.ProcessBlock && block.id == blockId) {
                            block.copy(
                                status = status,
                                extractedText = text,
                                errorMessage = error
                            )
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /** Generates a new block ID */
    fun newBlockId(): String = UUID.randomUUID().toString()

    /** Gets the currently selected block */
    fun getSelectedBlock(): Block? {
        val selectedId = _uiState.value.selectedBlockId ?: return null
        return _uiState.value.document.blocks.find { it.id == selectedId }
    }

    /** Gets a graph block by ID */
    fun getGraphBlock(graphId: String): Block.GraphBlock? {
        return _uiState.value.document.blocks
            .filterIsInstance<Block.GraphBlock>()
            .find { it.id == graphId }
    }
}