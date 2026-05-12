package com.penpal.core.media.analysis

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

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

    fun play(file: File): Boolean {
        Log.d(TAG, "Playing: ${file.name}, size: ${file.length()} bytes")

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

        if (!isValidWavFile(file)) {
            Log.e(TAG, "Invalid WAV file format")
            onError?.invoke("Invalid audio format")
            return false
        }

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
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaPlayer: ${e.message}", e)
            mediaPlayer?.release()
            mediaPlayer = null
            onError?.invoke("Cannot play file: ${e.message}")
            return false
        }
    }

    private fun isValidWavFile(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }

            val riff = String(header, 0, 4)
            val wave = String(header, 8, 4)

            if (riff != "RIFF" || wave != "WAVE") {
                Log.e(TAG, "Invalid WAV header: riff=$riff, wave=$wave")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating WAV header: ${e.message}")
            false
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                Log.d(TAG, "Playback paused")
            }
        }
    }

    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                pause()
            } else {
                resume()
            }
        }
    }

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

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        Log.d(TAG, "Seeked to: $positionMs ms")
    }

    fun formatTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

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
