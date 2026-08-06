package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class WaveFileAssemblerTest {
    @Test
    fun joinsCompatiblePcmSegmentsAndSkipsUnknownChunks() {
        val dir = createTempDirectory("wave-test").toFile()
        val first = File(dir, "first.wav").also { it.writeBytes(wave(22_050, ByteArray(200) { 1 }, true)) }
        val second = File(dir, "second.wav").also { it.writeBytes(wave(22_050, ByteArray(160) { 2 }, false)) }
        val output = File(dir, "joined.wav")

        WaveFileAssembler.assemble(listOf(first, second), output)

        val joined = WaveFileAssembler.inspect(output)
        assertEquals(22_050L, joined.sampleRate)
        assertEquals(360L, joined.dataLength)
        assertTrue(output.length() >= 404L)
    }

    @Test(expected = IOException::class)
    fun rejectsSegmentsWithDifferentFormats() {
        val dir = createTempDirectory("wave-test").toFile()
        val first = File(dir, "first.wav").also { it.writeBytes(wave(22_050, ByteArray(20), false)) }
        val second = File(dir, "second.wav").also { it.writeBytes(wave(16_000, ByteArray(20), false)) }
        WaveFileAssembler.assemble(listOf(first, second), File(dir, "joined.wav"))
    }

    private fun wave(sampleRate: Int, payload: ByteArray, withJunk: Boolean): ByteArray {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(1)
            .putInt(sampleRate)
            .putInt(sampleRate * 2)
            .putShort(2)
            .putShort(16)
            .array()
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray())
            if (withJunk) {
                write("JUNK".toByteArray())
                write(le32(3))
                write(byteArrayOf(1, 2, 3, 0))
            }
            write("fmt ".toByteArray())
            write(le32(fmt.size))
            write(fmt)
            write("data".toByteArray())
            write(le32(payload.size))
            write(payload)
            if (payload.size % 2 != 0) write(0)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(le32(body.size))
            write(body)
        }.toByteArray()
    }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()
}
