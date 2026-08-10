package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import sonic.Sonic

/**
 * PCM16 WAV adapter around Bill Cox's upstream Sonic Java implementation.
 *
 * The DSP algorithm itself lives in the vendored, unmodified `sonic.Sonic` source.
 * This class only validates WAV input, streams little-endian PCM into Sonic, and
 * writes the processed samples back to a standard PCM16 WAV container.
 */
object SonicPcmProcessor {
    private const val MAX_PCM_BYTES = 64L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024

    fun process(
        source: File,
        destination: File,
        speed: Float,
        pitch: Float,
        accurate: Boolean = ReferenceSonicRuntime.accurateMode,
    ): WaveSegment {
        val wave = WaveFileAssembler.inspect(source)
        if (
            wave.audioFormat != 1 ||
            wave.bitsPerSample != 16 ||
            wave.channelCount !in 1..2 ||
            wave.blockAlign != wave.channelCount * 2
        ) {
            throw IOException("Sonic chỉ hỗ trợ WAV PCM16 mono hoặc stereo.")
        }
        if (wave.dataLength > MAX_PCM_BYTES) {
            throw IOException("Đoạn PCM quá lớn để xử lý Sonic an toàn.")
        }

        val normalizedSpeed = speed.coerceIn(0.25f, 3f)
        val normalizedPitch = pitch.coerceIn(0.5f, 2f)
        // Keep the existing reference-volume behavior intentionally: the converter
        // applies the first gain stage and upstream Sonic applies this second stage.
        val gain = ReferenceSonicRuntime.outputGain.coerceIn(0f, 2f)
        if (
            abs(normalizedSpeed - 1f) < 0.005f &&
            abs(normalizedPitch - 1f) < 0.005f &&
            abs(gain - 1f) < 0.005f
        ) {
            source.copyTo(destination, overwrite = true)
            return WaveFileAssembler.inspect(destination)
        }

        val sampleRate = wave.sampleRate.toInt()
        val channels = wave.channelCount
        val frameBytes = channels * 2
        val sonic = Sonic(sampleRate, channels).apply {
            setSpeed(normalizedSpeed)
            setPitch(normalizedPitch)
            setRate(1f)
            setVolume(gain)
            setQuality(if (accurate) 1 else 0)
        }

        destination.parentFile?.mkdirs()
        var outputDataBytes = 0L
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            writeHeader(output, channels, sampleRate, dataBytes = 0L)
            BufferedInputStream(FileInputStream(source)).use { input ->
                skipFully(input, wave.dataOffset)
                val inputBuffer = ByteArray(BUFFER_BYTES - (BUFFER_BYTES % frameBytes))
                val outputBuffer = ByteArray(BUFFER_BYTES - (BUFFER_BYTES % frameBytes))
                var remaining = wave.dataLength
                while (remaining > 0L) {
                    val wanted = minOf(inputBuffer.size.toLong(), remaining).toInt()
                    if (wanted % frameBytes != 0) {
                        throw IOException("Frame PCM16 cuối bị cắt ngắn.")
                    }
                    readFully(input, inputBuffer, wanted)
                    sonic.writeBytesToStream(inputBuffer, wanted)
                    outputDataBytes += drain(sonic, output, outputBuffer)
                    remaining -= wanted
                }
                sonic.flushStream()
                outputDataBytes += drain(sonic, output, outputBuffer)
            }
        }

        if (outputDataBytes > 0xffff_ffffL) {
            destination.delete()
            throw IOException("WAV Sonic đầu ra vượt giới hạn 4 GiB.")
        }
        patchHeaderSizes(destination, outputDataBytes)
        return WaveFileAssembler.inspect(destination)
    }

    private fun drain(sonic: Sonic, output: OutputStream, buffer: ByteArray): Long {
        var written = 0L
        while (sonic.samplesAvailable() > 0) {
            val count = sonic.readBytesFromStream(buffer, buffer.size)
            if (count <= 0) break
            output.write(buffer, 0, count)
            written += count
        }
        return written
    }

    private fun writeHeader(output: OutputStream, channels: Int, sampleRate: Int, dataBytes: Long) {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeU32(36L + dataBytes)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.writeU32(16)
        output.writeU16(1)
        output.writeU16(channels)
        output.writeU32(sampleRate.toLong())
        output.writeU32(sampleRate.toLong() * channels * 2L)
        output.writeU16(channels * 2)
        output.writeU16(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeU32(dataBytes)
    }

    private fun patchHeaderSizes(file: File, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeU32Le(36L + dataBytes)
            raf.seek(40)
            raf.writeU32Le(dataBytes)
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) throw EOFException("PCM bị cắt ngắn.")
            offset += count
        }
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() >= 0) remaining--
            else throw EOFException("Không tới được dữ liệu PCM.")
        }
    }

    private fun OutputStream.writeU16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun OutputStream.writeU32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private fun RandomAccessFile.writeU32Le(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }
}
