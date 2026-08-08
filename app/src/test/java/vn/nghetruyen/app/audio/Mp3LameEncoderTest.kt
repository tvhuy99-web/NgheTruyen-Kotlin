package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

class Mp3LameEncoderTest {
    @Test
    fun encodesPcm16WaveWithId3AndMp3Frames() {
        val root = createTempDirectory("mp3-lame-test-").toFile()
        try {
            val wav = File(root, "input.wav")
            val mp3 = File(root, "output.mp3")
            writeSineWave(wav)

            Mp3LameEncoder.encode(
                wav,
                mp3,
                Id3v23Writer.Metadata(title = "Chương 1", artist = "Tác giả", album = "Truyện"),
                bitrateKbps = 96,
            )

            val bytes = mp3.readBytes()
            assertTrue(bytes.size > 1_000)
            assertEquals("ID3", bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII))
            val tagSize = ((bytes[6].toInt() and 0x7f) shl 21) or
                ((bytes[7].toInt() and 0x7f) shl 14) or
                ((bytes[8].toInt() and 0x7f) shl 7) or
                (bytes[9].toInt() and 0x7f)
            val frame = 10 + tagSize
            assertTrue(frame + 1 < bytes.size)
            assertEquals(0xff, bytes[frame].toInt() and 0xff)
            assertEquals(0xe0, bytes[frame + 1].toInt() and 0xe0)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSineWave(file: File) {
        val sampleRate = 22_050
        val frames = sampleRate
        val dataSize = frames * 2
        FileOutputStream(file).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLe32(36 + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(sampleRate)
            output.writeLe32(sampleRate * 2)
            output.writeLe16(2)
            output.writeLe16(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLe32(dataSize)
            repeat(frames) { index ->
                val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * 8_000).toInt()
                output.writeLe16(sample)
            }
        }
    }

    private fun FileOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun FileOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
