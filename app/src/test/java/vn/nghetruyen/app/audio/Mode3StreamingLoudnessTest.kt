package vn.nghetruyen.app.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode3StreamingLoudnessTest {
    @Test
    fun streamingAnalyzerMeasuresChunkedNativePcmWithoutWaveConversion() {
        val sampleRate = 48_000
        val channels = 2
        val frames = sampleRate * 2
        val pcm = ByteArray(frames * channels * 2)
        var offset = 0
        for (frame in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 0.25 * Short.MAX_VALUE).toInt().toShort()
            repeat(channels) {
                pcm[offset++] = (sample.toInt() and 0xff).toByte()
                pcm[offset++] = ((sample.toInt() ushr 8) and 0xff).toByte()
            }
        }
        val analyzer = PcmLoudnessEstimator.StreamingAnalyzer(sampleRate, channels)
        val frameBytes = channels * 2
        var cursor = 0
        while (cursor < pcm.size) {
            val bytes = minOf(12_000, pcm.size - cursor)
            val aligned = bytes - (bytes % frameBytes)
            analyzer.acceptPcm16(pcm, cursor, aligned)
            cursor += aligned
        }
        val result = analyzer.finish()
        assertTrue(result.loudnessLufs.isFinite())
        assertTrue(result.peakDbfs in -13f..-11f)
    }
}
