package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt


object Pcm16WaveConverter {
    private const val BUFFER_BYTES = 64 * 1024

    fun convert(source: File, destination: File, gain: Float = 1f): WaveSegment {
        val input = WaveFileAssembler.inspect(source)
        if (input.audioFormat !in setOf(1, 3)) {
            throw IOException("WAV mở rộng chưa được hỗ trợ khi chuẩn hóa âm lượng.")
        }
        if (input.audioFormat == 3 && input.bitsPerSample != 32) {
            throw IOException("Chỉ hỗ trợ WAV float 32-bit.")
        }
        if (input.audioFormat == 1 && input.bitsPerSample !in setOf(8, 16, 24, 32)) {
            throw IOException("Độ sâu PCM ${input.bitsPerSample}-bit chưa được hỗ trợ.")
        }
        val bytesPerSample = input.bitsPerSample / 8
        val expectedBlock = bytesPerSample * input.channelCount
        if (expectedBlock <= 0 || input.blockAlign != expectedBlock || input.dataLength % expectedBlock != 0L) {
            throw IOException("Dữ liệu PCM không thẳng hàng theo frame.")
        }
        val frames = input.dataLength / expectedBlock
        val outputDataBytes = frames * input.channelCount * 2L
        if (outputDataBytes > 0xffffffffL) throw IOException("WAV PCM16 đầu ra vượt giới hạn 4 GiB.")
        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            writeHeader(output, input.channelCount, input.sampleRate.toInt(), outputDataBytes)
            FileInputStream(source).use { raw ->
                val stream = BufferedInputStream(raw)
                skipFully(stream, input.dataOffset)
                val inputBuffer = ByteArray(BUFFER_BYTES - (BUFFER_BYTES % expectedBlock))
                val outputBuffer = ByteArray((inputBuffer.size / bytesPerSample) * 2)
                var remaining = input.dataLength
                while (remaining > 0L) {
                    val wanted = minOf(inputBuffer.size.toLong(), remaining).toInt()
                    val aligned = wanted - (wanted % expectedBlock)
                    if (aligned <= 0) throw IOException("Frame WAV cuối bị cắt ngắn.")
                    readFully(stream, inputBuffer, aligned)
                    var src = 0
                    var dst = 0
                    while (src < aligned) {
                        val sample = decode(inputBuffer, src, input.audioFormat, input.bitsPerSample)
                        val scaled = (sample * gain.coerceIn(0f, 2f)).coerceIn(-1f, 1f)
                        val pcm16 = (scaled * 32767f).roundToInt().coerceIn(-32768, 32767)
                        outputBuffer[dst] = (pcm16 and 0xff).toByte()
                        outputBuffer[dst + 1] = ((pcm16 ushr 8) and 0xff).toByte()
                        src += bytesPerSample
                        dst += 2
                    }
                    output.write(outputBuffer, 0, dst)
                    remaining -= aligned
                }
            }
        }
        return WaveFileAssembler.inspect(destination)
    }

    private fun decode(bytes: ByteArray, offset: Int, format: Int, bits: Int): Float {
        if (format == 3) {
            val raw = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                (bytes[offset + 3].toInt() shl 24)
            return Float.fromBits(raw).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        }
        return when (bits) {
            8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128f
            16 -> {
                val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
                value.toShort() / 32768f
            }
            24 -> {
                var value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                value / 8388608f
            }
            32 -> {
                val value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    (bytes[offset + 3].toInt() shl 24)
                value / 2147483648f
            }
            else -> 0f
        }
    }

    private fun writeHeader(output: OutputStream, channels: Int, sampleRate: Int, dataBytes: Long) {
        val byteRate = sampleRate.toLong() * channels * 2L
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeU32(36L + dataBytes)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.writeU32(16)
        output.writeU16(1)
        output.writeU16(channels)
        output.writeU32(sampleRate.toLong())
        output.writeU32(byteRate)
        output.writeU16(channels * 2)
        output.writeU16(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeU32(dataBytes)
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() >= 0) remaining -= 1 else throw EOFException("Không tới được dữ liệu WAV.")
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("Dữ liệu WAV bị cắt ngắn.")
            offset += read
        }
    }

    private fun OutputStream.writeU16(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff)
    }

    private fun OutputStream.writeU32(value: Long) {
        write((value and 0xff).toInt()); write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt()); write(((value ushr 24) and 0xff).toInt())
    }
}
