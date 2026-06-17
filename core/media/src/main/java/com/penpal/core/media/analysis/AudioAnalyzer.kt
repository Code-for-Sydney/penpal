package com.penpal.core.media.analysis

import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioAnalyzer {

    companion object {
        private const val TAG = "AudioAnalyzer"
        const val NUM_BINS = 12
    }

    var onSpectrumUpdate: ((FloatArray) -> Unit)? = null

    private var specThread: Thread? = null
    private var isAnalyzing = false
    private var pendingBuffer: ShortArray? = null
    private var pendingLength = 0
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun feedPcmData(buffer: ShortArray, length: Int) {
        synchronized(lock) {
            pendingBuffer = buffer.copyOf(length)
            pendingLength = length
        }
    }

    fun startAnalyzing() {
        isAnalyzing = true
        specThread = Thread { analysisLoop() }
        specThread?.start()
    }

    fun stop() {
        isAnalyzing = false
        specThread?.join(500)
        specThread = null
    }

    private fun analysisLoop() {
        while (isAnalyzing) {
            val buf: ShortArray?
            val len: Int
            synchronized(lock) {
                buf = pendingBuffer
                len = pendingLength
                pendingBuffer = null
            }

            if (buf != null && len > 0) {
                val spectrum = computeSpectrum(buf, len)
                mainHandler.post { onSpectrumUpdate?.invoke(spectrum) }
            }

            try { Thread.sleep(80) } catch (_: InterruptedException) { break }
        }
    }

    fun computeSpectrum(buffer: ShortArray, length: Int): FloatArray {
        val fftSize = nextPowerOfTwo(length)
        val samples = FloatArray(fftSize) { i ->
            if (i < length) buffer[i].toFloat() / 32768f else 0f
        }

        applyHanningWindow(samples)

        val real = samples.copyOf()
        val imag = FloatArray(fftSize)
        fft(real, imag)

        val magnitudes = FloatArray(fftSize / 2)
        for (i in 0 until fftSize / 2) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        val binEdges = logSpaceBins(NUM_BINS, fftSize / 2)
        val bins = FloatArray(NUM_BINS)
        for (b in 0 until NUM_BINS) {
            val start = binEdges[b]
            val end = binEdges[b + 1]
            var sum = 0f
            var count = 0
            for (i in start until end) {
                sum += magnitudes[i]
                count++
            }
            bins[b] = if (count > 0) sum / count else 0f
        }

        val maxBin = bins.maxOrNull() ?: 1f
        if (maxBin > 0f) {
            for (i in bins.indices) {
                bins[i] = (bins[i] / maxBin).coerceIn(0f, 1f)
            }
        }

        return bins
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var x = 1
        while (x < n) x = x shl 1
        return x
    }

    private fun applyHanningWindow(data: FloatArray) {
        val n = data.size
        for (i in data.indices) {
            data[i] = data[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat())
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var step = 1
        while (step < n) {
            val halfStep = step
            step = step shl 1
            val wlenR = cos(PI / halfStep).toFloat()
            val wlenI = (-sin(PI / halfStep)).toFloat()
            for (k in 0 until n step step) {
                var wr = 1f
                var wi = 0f
                for (l in 0 until halfStep) {
                    val a = k + l
                    val b = a + halfStep
                    val tr = wr * real[b] - wi * imag[b]
                    val ti = wr * imag[b] + wi * real[b]
                    real[b] = real[a] - tr
                    imag[b] = imag[a] - ti
                    real[a] += tr
                    imag[a] += ti
                    val newWr = wr * wlenR - wi * wlenI
                    val newWi = wr * wlenI + wi * wlenR
                    wr = newWr; wi = newWi
                }
            }
        }
    }

    private fun logSpaceBins(numBins: Int, maxBin: Int): IntArray {
        val edges = IntArray(numBins + 1)
        val logMin = log10(2.0)
        val logMax = log10(maxBin.toDouble())
        val step = (logMax - logMin) / numBins
        for (i in 0..numBins) {
            edges[i] = (exp((logMin + i * step) * ln(10.0))).toInt().coerceIn(0, maxBin)
        }
        return edges
    }
}
