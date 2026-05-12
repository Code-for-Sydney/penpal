package com.penpal.core.media.capture

import android.util.Log
import com.penpal.core.media.WavConstants
import com.penpal.core.media.intToByteArray
import com.penpal.core.media.shortToByteArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

class AudioChunker {

    companion object {
        const val TAG = "AudioChunker"

        const val MIN_CHUNK_DURATION_MS = 10000L
        const val MAX_CHUNK_DURATION_MS = 30000L
        const val PREFERRED_CHUNK_DURATION_MS = 20000L

        const val SPEECH_RMS_THRESHOLD = 300f
        const val SILENCE_RMS_THRESHOLD = 100f
        const val SILENCE_DURATION_MS = 1500L
        const val PRE_SPEECH_BUFFER_MS = 300L
        const val POST_SPEECH_BUFFER_MS = 500L

        private const val SAMPLE_RATE = WavConstants.SAMPLE_RATE
        private const val BITS_PER_SAMPLE = WavConstants.BITS_PER_SAMPLE
        private const val BYTES_PER_SECOND = WavConstants.BYTES_PER_SECOND
    }

    data class ChunkResult(
        val file: File,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationMs: Long,
        val hasSpeech: Boolean
    )

    data class ChunkingResult(
        val chunks: List<ChunkResult>,
        val totalDurationMs: Long,
        val speechDurationMs: Long,
        val silenceDurationMs: Long
    )

    data class SpeechSegment(
        val startSample: Long,
        val endSample: Long,
        val peakRms: Float,
        val isComplete: Boolean = true
    ) {
        val durationSamples: Long get() = endSample - startSample
        val durationMs: Long get() = (durationSamples * 1000) / SAMPLE_RATE
    }

    fun chunkAudioFile(wavFile: File): ChunkingResult {
        Log.d(TAG, "Chunking audio file: ${wavFile.name}")

        val audioData = readWavData(wavFile) ?: return ChunkingResult(
            emptyList(), 0, 0, 0
        )

        val totalSamples = audioData.size.toLong()
        val totalDurationMs = (totalSamples * 1000L) / SAMPLE_RATE

        val segments = detectSpeechSegments(audioData)
        val chunks = createChunks(wavFile, audioData, segments)

        val speechDuration = segments.sumOf { it.durationMs }
        val silenceDuration = totalDurationMs - speechDuration

        return ChunkingResult(
            chunks = chunks,
            totalDurationMs = totalDurationMs,
            speechDurationMs = speechDuration,
            silenceDurationMs = silenceDuration
        )
    }

    private fun readWavData(wavFile: File): ShortArray? {
        return try {
            FileInputStream(wavFile).use { fis ->
                fis.skip(WavConstants.WAV_HEADER_SIZE.toLong())

                val dataSize = wavFile.length().toInt() - WavConstants.WAV_HEADER_SIZE
                val buffer = ByteArray(dataSize)
                val bytesRead = fis.read(buffer)

                if (bytesRead != dataSize) {
                    Log.w(TAG, "Read $bytesRead bytes, expected $dataSize")
                }

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

    private fun detectSpeechSegments(audioData: ShortArray): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()

        val windowSize = (SAMPLE_RATE * 50 / 1000).toInt()
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
                    speechStart = startIdx.toLong()
                    peakRms = rms
                } else {
                    if (rms > peakRms) peakRms = rms
                }
                silenceCount = 0
            } else if (speechStart != null) {
                silenceCount++

                if (silenceCount >= silenceWindows) {
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

        if (speechStart != null) {
            val endSample = audioData.size.toLong()
            segments.add(SpeechSegment(
                startSample = speechStart,
                endSample = endSample,
                peakRms = peakRms,
                isComplete = false
            ))
        }

        return segments
    }

    private fun createChunks(
        originalFile: File,
        audioData: ShortArray,
        segments: List<SpeechSegment>
    ): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        val outputDir = File(originalFile.parent, "chunks").apply { mkdirs() }

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

        var currentChunkSamples = mutableListOf<SpeechSegment>()
        var currentDurationMs = 0L

        for (segment in segments) {
            val segmentDuration = segment.durationMs

            if (currentDurationMs + segmentDuration > MAX_CHUNK_DURATION_MS &&
                currentChunkSamples.isNotEmpty()) {
                saveChunk(outputDir, audioData, currentChunkSamples, chunks, chunks.size)

                currentChunkSamples = mutableListOf(segment)
                currentDurationMs = segmentDuration
            } else {
                currentChunkSamples.add(segment)
                currentDurationMs += segmentDuration
            }
        }

        if (currentChunkSamples.isNotEmpty()) {
            saveChunk(outputDir, audioData, currentChunkSamples, chunks, chunks.size)
        }

        return chunks
    }

    private fun saveChunk(
        outputDir: File,
        audioData: ShortArray,
        segments: List<SpeechSegment>,
        chunks: MutableList<ChunkResult>,
        chunkIndex: Int
    ) {
        if (segments.isEmpty()) return

        var startSample = segments.first().startSample
        startSample = maxOf(0, startSample - (PRE_SPEECH_BUFFER_MS * SAMPLE_RATE / 1000))

        var endSample = segments.last().endSample
        val maxEnd = audioData.size.toLong()
        endSample = minOf(maxEnd, endSample + (POST_SPEECH_BUFFER_MS * SAMPLE_RATE / 1000))

        var actualEnd = endSample
        val minSamples = (MIN_CHUNK_DURATION_MS * SAMPLE_RATE / 1000).toLong()
        if (actualEnd - startSample < minSamples) {
            actualEnd = minOf(maxEnd, startSample + minSamples)
        }

        val startMs = (startSample * 1000) / SAMPLE_RATE
        val endMs = (endSample * 1000) / SAMPLE_RATE
        val durationMs = endMs - startMs

        val chunkFile = File(outputDir, "chunk_$chunkIndex.wav")
        writeWavChunk(chunkFile, audioData, startSample.toInt(), actualEnd.toInt())

        chunks.add(ChunkResult(
            file = chunkFile,
            startTimeMs = startMs,
            endTimeMs = endMs,
            durationMs = durationMs,
            hasSpeech = true
        ))
    }

    private fun writeWavChunk(file: File, audioData: ShortArray, startIdx: Int, endIdx: Int) {
        val sampleCount = endIdx - startIdx
        val dataSize = sampleCount * 2

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(dataSize + 36))
            fos.write("WAVE".toByteArray())

            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16))
            fos.write(shortToByteArray(1))
            fos.write(shortToByteArray(1))
            fos.write(intToByteArray(SAMPLE_RATE))
            fos.write(intToByteArray(BYTES_PER_SECOND))
            fos.write(shortToByteArray(2))
            fos.write(shortToByteArray(16))

            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            val buffer = ByteArray(sampleCount * 2)
            for (i in startIdx until endIdx) {
                val sample = audioData[i].toInt()
                buffer[(i - startIdx) * 2] = (sample and 0xFF).toByte()
                buffer[(i - startIdx) * 2 + 1] = (sample shr 8).toByte()
            }
            fos.write(buffer)
        }
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    fun processRecording(recordingFile: File): ChunkingResult {
        return chunkAudioFile(recordingFile)
    }

    fun cleanupChunks(recordingFile: File) {
        val chunkDir = File(recordingFile.parent, "chunks")
        chunkDir.listFiles()?.forEach { it.delete() }
        chunkDir.delete()
    }
}
