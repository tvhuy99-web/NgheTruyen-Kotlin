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

 
data class WaveSegment(
    val file: File,
    val formatPayload: ByteArray,
    val audioFormat: Int,
    val channelCount: Int,
    val sampleRate: Long,
    val byteRate: Long,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataLength: Long,
)








object WaveFileAssembler {
    private const val MAX_RIFF_DATA_BYTES = 0xfffffff0L
    private const val MAX_FMT_CHUNK_BYTES = 4_096
    private const val COPY_BUFFER_BYTES = 64 * 1024

    fun inspect(file: File): WaveSegment {
        require(file.isFile && file.length() >= 44L) { "Tệp âm thanh WAV không hợp lệ." }
        RandomAccessFile(file, "r").use { input ->
            if (input.readAscii(4) != "RIFF") throw IOException("Tệp âm thanh không phải RIFF.")
            input.readUInt32Le() 
            if (input.readAscii(4) != "WAVE") throw IOException("Tệp RIFF không phải WAVE.")

            var formatPayload: ByteArray? = null
            var audioFormat = 0
            var channels = 0
            var sampleRate = 0L
            var byteRate = 0L
            var blockAlign = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataLength = -1L

            while (input.filePointer + 8L <= input.length()) {
                val chunkId = input.readAscii(4)
                val chunkSize = input.readUInt32Le()
                val chunkStart = input.filePointer
                val chunkEnd = chunkStart + chunkSize
                if (chunkEnd > input.length()) throw IOException("Chunk WAV vượt quá kích thước tệp.")

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize !in 16L..MAX_FMT_CHUNK_BYTES.toLong()) {
                            throw IOException("Chunk fmt WAV không được hỗ trợ.")
                        }
                        val payload = ByteArray(chunkSize.toInt())
                        input.readFully(payload)
                        formatPayload = payload
                        audioFormat = payload.u16(0)
                        channels = payload.u16(2)
                        sampleRate = payload.u32(4)
                        byteRate = payload.u32(8)
                        blockAlign = payload.u16(12)
                        bitsPerSample = payload.u16(14)
                    }
                    "data" -> {
                        if (dataOffset < 0L) {
                            dataOffset = chunkStart
                            dataLength = chunkSize
                        }
                        input.seek(chunkEnd)
                    }
                    else -> input.seek(chunkEnd)
                }
                if ((chunkSize and 1L) != 0L && input.filePointer < input.length()) input.skipBytes(1)
            }

            val fmt = formatPayload ?: throw IOException("Tệp WAV thiếu chunk fmt.")
            if (dataOffset < 0L || dataLength < 0L) throw IOException("Tệp WAV thiếu dữ liệu âm thanh.")
            if (channels !in 1..8 || sampleRate !in 8_000L..384_000L || byteRate <= 0L || blockAlign <= 0) {
                throw IOException("Thông số WAV không hợp lệ.")
            }
            if (audioFormat !in setOf(1, 3, 0xfffe)) {
                throw IOException("Định dạng WAV ${audioFormat} chưa được hỗ trợ.")
            }
            return WaveSegment(
                file = file,
                formatPayload = fmt,
                audioFormat = audioFormat,
                channelCount = channels,
                sampleRate = sampleRate,
                byteRate = byteRate,
                blockAlign = blockAlign,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset,
                dataLength = dataLength,
            )
        }
    }

    fun assemble(files: List<File>, destination: File): Long {
        require(files.isNotEmpty()) { "Không có đoạn âm thanh để ghép." }
        val segments = files.map(::inspect)
        val reference = segments.first()
        if (segments.any { !it.formatPayload.contentEquals(reference.formatPayload) }) {
            throw IOException("Bộ máy TTS thay đổi định dạng giữa các đoạn; không thể ghép WAV an toàn.")
        }
        val dataBytes = segments.sumOf(WaveSegment::dataLength)
        if (dataBytes <= 0L || dataBytes > MAX_RIFF_DATA_BYTES) {
            throw IOException("Tệp WAV đầu ra vượt giới hạn RIFF 4 GiB.")
        }
        val fmtBytes = reference.formatPayload.size.toLong()
        val fmtPadding = fmtBytes and 1L
        val dataPadding = dataBytes and 1L
        val riffPayloadBytes = 4L + 8L + fmtBytes + fmtPadding + 8L + dataBytes + dataPadding
        if (riffPayloadBytes > 0xffffffffL) throw IOException("Tệp WAV đầu ra quá lớn.")

        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            output.writeAscii("RIFF")
            output.writeUInt32Le(riffPayloadBytes)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeUInt32Le(fmtBytes)
            output.write(reference.formatPayload)
            if (fmtPadding != 0L) output.write(0)
            output.writeAscii("data")
            output.writeUInt32Le(dataBytes)
            segments.forEach { segment -> copyData(segment, output) }
            if (dataPadding != 0L) output.write(0)
        }
        return destination.length()
    }

    private fun copyData(segment: WaveSegment, output: OutputStream) {
        FileInputStream(segment.file).use { raw ->
            val input = BufferedInputStream(raw)
            var skipped = 0L
            while (skipped < segment.dataOffset) {
                val value = input.skip(segment.dataOffset - skipped)
                if (value <= 0L) {
                    if (input.read() < 0) throw EOFException("Không tới được dữ liệu WAV.")
                    skipped += 1L
                } else skipped += value
            }
            var remaining = segment.dataLength
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Dữ liệu WAV bị cắt ngắn.")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b3 < 0) throw EOFException()
        return (b0.toLong() and 0xffL) or
            ((b1.toLong() and 0xffL) shl 8) or
            ((b2.toLong() and 0xffL) shl 16) or
            ((b3.toLong() and 0xffL) shl 24)
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeUInt32Le(value: Long) {
        require(value in 0L..0xffffffffL)
        write((value and 0xffL).toInt())
        write(((value ushr 8) and 0xffL).toInt())
        write(((value ushr 16) and 0xffL).toInt())
        write(((value ushr 24) and 0xffL).toInt())
    }
}
