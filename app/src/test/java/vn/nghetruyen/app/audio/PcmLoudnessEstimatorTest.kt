package vn.nghetruyen.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

class PcmLoudnessEstimatorTest {
    @Test
    fun louderWaveProducesHigherEstimateAndLowerNormalizationGain() {
        val root = createTempDirectory("loudness-test-").toFile()
        try {
            val quiet = File(root, "quiet.wav")
            val loud = File(root, "loud.wav")
            writeSineWave(quiet, amplitude = 1_000, frames = 44_100)
            writeSineWave(loud, amplitude = 8_000, frames = 44_100)
            val quietLufs = PcmLoudnessEstimator.estimateLufs(quiet)
            val loudLufs = PcmLoudnessEstimator.estimateLufs(loud)
            assertTrue(loudLufs > quietLufs)
            assertTrue(PcmLoudnessEstimator.normalizationGain(loudLufs, -18f) < PcmLoudnessEstimator.normalizationGain(quietLufs, -18f))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSineWave(file: File, amplitude: Int, frames: Int) {
        val sampleRate = 22_050
        val frequencyHz = 440.0
        FileOutputStream(file).use { output ->
            fun le16(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
            fun le32(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff); output.write((value ushr 16) and 0xff); output.write((value ushr 24) and 0xff) }
            output.write("RIFF".toByteArray()); le32(36 + frames * 2); output.write("WAVEfmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            output.write("data".toByteArray()); le32(frames * 2)
            repeat(frames) { frame ->
                val sample = (sin(2.0 * PI * frequencyHz * frame / sampleRate) * amplitude).toInt()
                le16(sample)
            }
        }
    }
}
