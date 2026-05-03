package com.drawapp

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Audio playback for recorded files.
 * Simple wrapper around MediaPlayer for playing WAV files.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        const val TAG = "AudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProgress: ((currentMs: Int, totalMs: Int) -> Unit)? = null

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying ?: false

    val currentPosition: Int
        get() = mediaPlayer?.currentPosition ?: 0

    val duration: Int
        get() = mediaPlayer?.duration ?: 0

    /**
     * Play a WAV file
     */
    fun play(file: File): Boolean {
        Log.d(TAG, "Playing: ${file.absolutePath}, size: ${file.length()} bytes")

        if (!file.exists()) {
            Log.e(TAG, "File does not exist: ${file.absolutePath}")
            onError?.invoke("File not found")
            return false
        }

        if (file.length() < 44) {
            Log.e(TAG, "File too small to be valid WAV: ${file.length()} bytes")
            onError?.invoke("Invalid audio file")
            return false
        }

        // Validate WAV header
        if (!isValidWavFile(file)) {
            Log.e(TAG, "Invalid WAV file format")
            onError?.invoke("Invalid audio format")
            return false
        }

        // Stop and release any existing MediaPlayer
        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player: ${e.message}")
            }
        }
        mediaPlayer = null

        try {
            mediaPlayer = MediaPlayer()

            mediaPlayer?.apply {
                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared, duration: ${mp.duration}ms")
                    try {
                        start()
                        Log.d(TAG, "Playback started")
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Failed to start: ${e.message}")
                        onError?.invoke("Playback failed: ${e.message}")
                    }
                }
                setOnCompletionListener {
                    Log.d(TAG, "Playback completed")
                    onCompletion?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Playback error: what=$what ($extra)")
                    onError?.invoke("Playback error: $what")
                    true
                }
                setDataSource(file.absolutePath)
                prepareAsync()
            }
            currentFile = file
            Log.d(TAG, "prepareAsync called for playback")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaPlayer: ${e.message}", e)
            mediaPlayer?.release()
            mediaPlayer = null
            onError?.invoke("Cannot play file: ${e.message}")
            return false
        }
    }

    /**
     * Validate WAV file format by checking header
     */
    private fun isValidWavFile(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }

            // Check RIFF header
            val riff = String(header, 0, 4)
            val wave = String(header, 8, 4)

            if (riff != "RIFF" || wave != "WAVE") {
                Log.e(TAG, "Invalid WAV header: riff=$riff, wave=$wave")
                return false
            }

            Log.d(TAG, "WAV header validated: RIFF/WAVE found")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating WAV header: ${e.message}")
            false
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                Log.d(TAG, "Playback paused")
            }
        }
    }

    /**
     * Resume playback
     */
    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                pause()
            } else {
                resume()
            }
        }
    }

    /**
     * Stop playback
     */
    fun stop() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping: ${e.message}")
            }
        }
        mediaPlayer = null
        currentFile = null
        Log.d(TAG, "Playback stopped")
    }

    /**
     * Seek to position
     */
    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        Log.d(TAG, "Seeked to: $positionMs ms")
    }

    /**
     * Get formatted time string (mm:ss)
     */
    fun formatTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Get current playback progress
     */
    fun getProgress(): Pair<Int, Int> {
        val current = mediaPlayer?.currentPosition ?: 0
        val total = mediaPlayer?.duration ?: 0
        return Pair(current, total)
    }

    fun cleanup() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up: ${e.message}")
            }
        }
        mediaPlayer = null
        currentFile = null
    }
}