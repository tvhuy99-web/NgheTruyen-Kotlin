package vn.nghetruyen.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class PcmLoudnessEstimatorTest {
    @Test
    fun louderWaveProducesHigherEstimateAndLowerNormalizationGain() {
        val root = createTempDir(prefix = "loudness-test-")
        try {
            val quiet = File(root, "quiet.wav")
            val loud = File(root, "loud.wav")
            writeConstantWave(quiet, 1_000, 8_000)
            writeConstantWave(loud, 8_000, 8_000)
            val quietLufs = PcmLoudnessEstimator.estimateLufs(quiet)
            val loudLufs = PcmLoudnessEstimator.estimateLufs(loud)
            assertTrue(loudLufs > quietLufs)
            assertTrue(PcmLoudnessEstimator.normalizationGain(loudLufs, -18f) < PcmLoudnessEstimator.normalizationGain(quietLufs, -18f))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeConstantWave(file: File, sample: Int, frames: Int) {
        FileOutputStream(file).use { output ->
            fun le16(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
            fun le32(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff); output.write((value ushr 16) and 0xff); output.write((value ushr 24) and 0xff) }
            output.write("RIFF".toByteArray()); le32(36 + frames * 2); output.write("WAVEfmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(22_050); le32(44_100); le16(2); le16(16)
            output.write("data".toByteArray()); le32(frames * 2); repeat(frames) { le16(sample) }
        }
    }
}
