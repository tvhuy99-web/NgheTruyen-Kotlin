package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class Pcm16WaveConverterTest {
    @Test
    fun convertsUnsignedEightBitMonoToPcm16AndAppliesGain() {
        val dir = createTempDirectory("pcm-convert").toFile()
        val input = File(dir, "input.wav").also {
            it.writeBytes(wave(audioFormat = 1, bits = 8, payload = byteArrayOf(0, 64, 128.toByte(), 192.toByte(), 255.toByte())))
        }
        val output = File(dir, "output.wav")

        val result = Pcm16WaveConverter.convert(input, output, gain = 0.5f)

        assertEquals(1, result.audioFormat)
        assertEquals(16, result.bitsPerSample)
        assertEquals(1, result.channelCount)
        assertEquals(8_000L, result.sampleRate)
        assertEquals(10L, result.dataLength)
    }

    @Test
    fun convertsFloat32ToPcm16() {
        val samples = floatArrayOf(-1f, -0.25f, 0f, 0.25f, 1f)
        val payload = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> samples.forEach(buffer::putFloat) }
            .array()
        val dir = createTempDirectory("pcm-float").toFile()
        val input = File(dir, "input.wav").also { it.writeBytes(wave(3, 32, payload)) }
        val output = File(dir, "output.wav")

        val result = Pcm16WaveConverter.convert(input, output)

        assertEquals(1, result.audioFormat)
        assertEquals(16, result.bitsPerSample)
        assertEquals(10L, result.dataLength)
    }

    private fun wave(audioFormat: Int, bits: Int, payload: ByteArray): ByteArray {
        val channels = 1
        val sampleRate = 8_000
        val blockAlign = channels * bits / 8
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(audioFormat.toShort())
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * blockAlign)
            .putShort(blockAlign.toShort())
            .putShort(bits.toShort())
            .array()
        val body = ByteArrayOutputStream().apply {
            write("WAVEfmt ".toByteArray())
            write(le32(fmt.size)); write(fmt)
            write("data".toByteArray())
            write(le32(payload.size)); write(payload)
            if (payload.size % 2 != 0) write(0)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(le32(body.size)); write(body)
        }.toByteArray()
    }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()
}
