package com.drawapp

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice Activity Detection and Audio Chunking for Gemma processing.
 *
 * Gemma can process 15-30 seconds of audio as text at a time.
 * This class:
 * 1. Detects speech vs silence (VAD)
 * 2. Splits audio at natural pause points
 * 3. Creates chunks ready for transcription
 */
class AudioChunker {

    companion object {
        const val TAG = "AudioChunker"

        // Gemma processing limits (in seconds)
        const val MIN_CHUNK_DURATION_MS = 10000L   // 10 seconds minimum
        const val MAX_CHUNK_DURATION_MS = 30000L   // 30 seconds maximum
        const val PREFERRED_CHUNK_DURATION_MS = 20000L  // 20 seconds preferred

        // VAD parameters
        const val SPEECH_RMS_THRESHOLD = 300f       // RMS above this = speech
        const val SILENCE_RMS_THRESHOLD = 100f       // RMS below this = silence
        const val SILENCE_DURATION_MS = 1500L        // 1.5s of silence = end of speech segment
        const val PRE_SPEECH_BUFFER_MS = 300L       // Include 300ms before detected speech
        const val POST_SPEECH_BUFFER_MS = 500L      // Include 500ms after detected speech

        // Audio format (from AudioRecorder)
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS
    }

    /**
     * Result of chunking a recording
     */
    data class ChunkResult(
        val file: File,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationMs: Long,
        val hasSpeech: Boolean  // true if chunk contains detected speech
    )

    /**
     * Result of processing an entire recording
     */
    data class ChunkingResult(
        val chunks: List<ChunkResult>,
        val totalDurationMs: Long,
        val speechDurationMs: Long,
        val silenceDurationMs: Long
    )

    /**
     * Analyze a WAV file and detect speech segments
     */
    data class SpeechSegment(
        val startSample: Long,
        val endSample: Long,
        val peakRms: Float,
        val isComplete: Boolean = true  // false if cut off at recording end
    ) {
        val durationSamples: Long get() = endSample - startSample
        val durationMs: Long get() = (durationSamples * 1000) / SAMPLE_RATE
    }

    /**
     * Chunk a WAV file into segments suitable for Gemma processing.
     * Returns list of chunk files and metadata.
     */
    fun chunkAudioFile(wavFile: File): ChunkingResult {
        Log.d(TAG, "Chunking audio file: ${wavFile.absolutePath}")

        // Read WAV data
        val audioData = readWavData(wavFile) ?: return ChunkingResult(
            emptyList(), 0, 0, 0
        )

        val totalSamples = audioData.size.toLong()
        val totalDurationMs = (totalSamples * 1000L) / SAMPLE_RATE

        Log.d(TAG, "Audio: $totalSamples samples, ${totalDurationMs}ms duration")

        // Detect speech segments
        val segments = detectSpeechSegments(audioData)

        Log.d(TAG, "Found ${segments.size} speech segments")

        // Create chunk files
        val chunks = createChunks(wavFile, audioData, segments)

        // Calculate statistics
        val speechDuration = segments.sumOf { it.durationMs }
        val silenceDuration = totalDurationMs - speechDuration

        return ChunkingResult(
            chunks = chunks,
            totalDurationMs = totalDurationMs,
            speechDurationMs = speechDuration,
            silenceDurationMs = silenceDuration
        )
    }

    /**
     * Read WAV file and return raw PCM samples
     */
    private fun readWavData(wavFile: File): ShortArray? {
        return try {
            FileInputStream(wavFile).use { fis ->
                // Skip WAV header (44 bytes)
                fis.skip(44)

                val dataSize = wavFile.length().toInt() - 44
                val buffer = ByteArray(dataSize)
                val bytesRead = fis.read(buffer)

                if (bytesRead != dataSize) {
                    Log.w(TAG, "Read $bytesRead bytes, expected $dataSize")
                }

                // Convert bytes to shorts (little-endian)
                val samples = ShortArray(bytesRead / 2)
                for (i in samples.indices) {
                    val low = buffer[i * 2].toInt() and 0xFF
                    val high = buffer[i * 2 + 1].toInt()
                    samples[i] = ((high shl 8) or low).toShort()
                }

                samples
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WAV file: ${e.message}")
            null
        }
    }

    /**
     * Detect speech segments using VAD
     */
    private fun detectSpeechSegments(audioData: ShortArray): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()

        // Analyze in 50ms windows
        val windowSize = (SAMPLE_RATE * 50 / 1000).toInt() // 800 samples
        val windowCount = audioData.size / windowSize

        var speechStart: Long? = null
        var peakRms = 0f
        var silenceCount = 0
        val silenceWindows = (SILENCE_DURATION_MS / 50).toInt()

        for (windowIdx in 0 until windowCount) {
            val startIdx = windowIdx * windowSize
            val endIdx = minOf(startIdx + windowSize, audioData.size)
            val windowData = audioData.copyOfRange(startIdx, endIdx)

            val rms = calculateRms(windowData, windowData.size)
            val isSpeech = rms > SPEECH_RMS_THRESHOLD
            val isSilence = rms < SILENCE_RMS_THRESHOLD

            if (isSpeech) {
                if (speechStart == null) {
                    // Start of new speech segment
                    speechStart = startIdx.toLong()
                    peakRms = rms
                } else {
                    // Continuing speech, update peak
                    if (rms > peakRms) peakRms = rms
                }
                silenceCount = 0
            } else if (speechStart != null) {
                // In speech segment, checking for silence
                silenceCount++

                if (silenceCount >= silenceWindows) {
                    // End of speech segment
                    val endSample = (windowIdx - silenceWindows + 1) * windowSize.toLong()
                    segments.add(SpeechSegment(
                        startSample = speechStart,
                        endSample = endSample,
                        peakRms = peakRms,
                        isComplete = true
                    ))
                    speechStart = null
                    peakRms = 0f
                    silenceCount = 0
                }
            }
        }

        // Handle incomplete final segment (still speaking when recording stopped)
        if (speechStart != null) {
            val endSample = audioData.size.toLong()
            segments.add(SpeechSegment(
                startSample = speechStart,
                endSample = endSample,
                peakRms = peakRms,
                isComplete = false  // Incomplete - recording ended
            ))
        }

        return segments
    }

    /**
     * Create chunk files from speech segments
     */
    private fun createChunks(
        originalFile: File,
        audioData: ShortArray,
        segments: List<SpeechSegment>
    ): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        val outputDir = File(originalFile.parent, "chunks").apply { mkdirs() }

        // If no segments, create single chunk for entire recording
        if (segments.isEmpty()) {
            val chunkFile = File(outputDir, "chunk_0.wav")
            writeWavChunk(chunkFile, audioData, 0, audioData.size)
            chunks.add(ChunkResult(
                file = chunkFile,
                startTimeMs = 0,
                endTimeMs = (audioData.size.toLong() * 1000L) / SAMPLE_RATE,
                durationMs = (audioData.size.toLong() * 1000L) / SAMPLE_RATE,
                hasSpeech = false
            ))
            return chunks
        }

        // Group segments into chunks based on duration
        var currentChunkSamples = mutableListOf<SpeechSegment>()
        var currentDurationMs = 0L

        for (segment in segments) {
            val segmentDuration = segment.durationMs

            // If adding this segment would exceed max, save current chunk and start new
            if (currentDurationMs + segmentDuration > MAX_CHUNK_DURATION_MS &&
                currentChunkSamples.isNotEmpty()) {
                // Save current chunk
                saveChunk(outputDir, audioData, currentChunkSamples, chunks, chunks.size)

                // Start new chunk
                currentChunkSamples = mutableListOf(segment)
                currentDurationMs = segmentDuration
            } else {
                currentChunkSamples.add(segment)
                currentDurationMs += segmentDuration
            }
        }

        // Save any remaining samples as final chunk
        if (currentChunkSamples.isNotEmpty()) {
            saveChunk(outputDir, audioData, currentChunkSamples, chunks, chunks.size)
        }

        return chunks
    }

    /**
     * Save a chunk file and add to results
     */
    private fun saveChunk(
        outputDir: File,
        audioData: ShortArray,
        segments: List<SpeechSegment>,
        chunks: MutableList<ChunkResult>,
        chunkIndex: Int
    ) {
        if (segments.isEmpty()) return

        // Find start with pre-buffer
        var startSample = segments.first().startSample
        startSample = maxOf(0, startSample - (PRE_SPEECH_BUFFER_MS * SAMPLE_RATE / 1000))

        // Find end with post-buffer
        var endSample = segments.last().endSample
        val maxEnd = audioData.size.toLong()
        endSample = minOf(maxEnd, endSample + (POST_SPEECH_BUFFER_MS * SAMPLE_RATE / 1000))

        // Ensure chunk meets minimum duration
        var actualEnd = endSample
        val minSamples = (MIN_CHUNK_DURATION_MS * SAMPLE_RATE / 1000).toLong()
        if (actualEnd - startSample < minSamples) {
            actualEnd = minOf(maxEnd, startSample + minSamples)
        }

        val startMs = (startSample * 1000) / SAMPLE_RATE
        val endMs = (endSample * 1000) / SAMPLE_RATE
        val durationMs = endMs - startMs

        // Create chunk file
        val chunkFile = File(outputDir, "chunk_$chunkIndex.wav")
        writeWavChunk(chunkFile, audioData, startSample.toInt(), actualEnd.toInt())

        Log.d(TAG, "Created chunk $chunkIndex: ${durationMs}ms (${startMs}-${endMs}ms)")

        chunks.add(ChunkResult(
            file = chunkFile,
            startTimeMs = startMs,
            endTimeMs = endMs,
            durationMs = durationMs,
            hasSpeech = true
        ))
    }

    /**
     * Write a WAV chunk file
     */
    private fun writeWavChunk(file: File, audioData: ShortArray, startIdx: Int, endIdx: Int) {
        val sampleCount = endIdx - startIdx
        val dataSize = sampleCount * 2

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(dataSize + 36))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16))
            fos.write(shortToByteArray(1))  // PCM
            fos.write(shortToByteArray(1))  // Mono
            fos.write(intToByteArray(SAMPLE_RATE))
            fos.write(intToByteArray(BYTES_PER_SECOND))
            fos.write(shortToByteArray(2))  // Block align
            fos.write(shortToByteArray(16)) // 16-bit

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            // Write audio samples
            val buffer = ByteArray(sampleCount * 2)
            for (i in startIdx until endIdx) {
                val sample = audioData[i].toInt()
                buffer[(i - startIdx) * 2] = (sample and 0xFF).toByte()
                buffer[(i - startIdx) * 2 + 1] = (sample shr 8).toByte()
            }
            fos.write(buffer)
        }
    }

    /**
     * Calculate RMS amplitude for a buffer
     */
    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Process a recording file and return chunk information
     */
    fun processRecording(recordingFile: File): ChunkingResult {
        return chunkAudioFile(recordingFile)
    }

    /**
     * Clean up chunk files from a previous processing
     */
    fun cleanupChunks(recordingFile: File) {
        val chunkDir = File(recordingFile.parent, "chunks")
        chunkDir.listFiles()?.forEach { it.delete() }
        chunkDir.delete()
    }
}