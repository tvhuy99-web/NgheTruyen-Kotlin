package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Bounded integrated loudness estimate for normalization of local scene tracks. */
object PcmLoudnessEstimator {
    fun estimateLufs(wavFile: File): Float {
        val wave = WaveFileAssembler.inspect(wavFile)
        if (wave.audioFormat != 1 || wave.bitsPerSample != 16) throw IOException("Chỉ đo được loudness WAV PCM16.")
        val frameBytes = wave.channelCount * 2
        val buffer = ByteArray(64 * 1024 - (64 * 1024 % frameBytes))
        var squareSum = 0.0
        var sampleCount = 0L
        BufferedInputStream(FileInputStream(wavFile)).use { input ->
            skipFully(input, wave.dataOffset)
            var remaining = wave.dataLength
            while (remaining > 0) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                var readTotal = 0
                while (readTotal < wanted) {
                    val count = input.read(buffer, readTotal, wanted - readTotal)
                    if (count < 0) throw EOFException("PCM bị cắt ngắn.")
                    readTotal += count
                }
                var offset = 0
                while (offset + 1 < readTotal) {
                    val value = ((buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
                    squareSum += value * value
                    sampleCount++
                    offset += 2
                }
                remaining -= readTotal
            }
        }
        if (sampleCount == 0L) return -70f
        val rms = sqrt(squareSum / sampleCount).coerceAtLeast(1e-7)
        return (20.0 * log10(rms) - 0.691).toFloat().coerceIn(-70f, 0f)
    }

    fun normalizationGain(measuredLufs: Float, targetLufs: Float): Float {
        val delta = targetLufs.coerceIn(-30f, -10f) - measuredLufs.coerceIn(-70f, 0f)
        return 10.0.pow(delta / 20.0).toFloat().coerceIn(0.20f, 3.0f)
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else throw EOFException()
        }
    }
}
