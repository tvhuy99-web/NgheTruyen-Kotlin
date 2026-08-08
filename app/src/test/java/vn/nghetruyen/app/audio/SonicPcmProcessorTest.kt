package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

class SonicPcmProcessorTest {
    @Test
    fun speedChangesDurationWhileKeepingValidPcm16Wave() {
        val root = createTempDirectory("sonic-test-").toFile()
        try {
            val input = File(root, "input.wav")
            val output = File(root, "output.wav")
            writeSineWave(input, sampleRate = 22_050, frames = 22_050)
            val processed = SonicPcmProcessor.process(input, output, speed = 2f, pitch = 1f)
            assertEquals(1, processed.audioFormat)
            assertEquals(16, processed.bitsPerSample)
            val frames = processed.dataLength / processed.blockAlign
            assertTrue(frames in 10_500L..11_600L)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSineWave(file: File, sampleRate: Int, frames: Int) {
        FileOutputStream(file).use { output ->
            fun le16(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
            fun le32(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff); output.write((value ushr 16) and 0xff); output.write((value ushr 24) and 0xff) }
            output.write("RIFF".toByteArray()); le32(36 + frames * 2); output.write("WAVEfmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            output.write("data".toByteArray()); le32(frames * 2)
            repeat(frames) { index -> le16((sin(2.0 * PI * 440.0 * index / sampleRate) * 9_000).toInt()) }
        }
    }
}
