package com.penpal.core.media

object WavConstants {
    const val SAMPLE_RATE = 16000
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS
    const val WAV_HEADER_SIZE = 44
}

fun intToByteArray(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte()
)

fun shortToByteArray(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte()
)
