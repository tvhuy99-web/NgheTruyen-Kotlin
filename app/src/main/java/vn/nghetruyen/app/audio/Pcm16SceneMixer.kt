package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min


data class SceneMixLayer(
    val sourceWav: File,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val volume: Float = 0.18f,
    val fadeFrames: Int = 0,
)





object Pcm16SceneMixer {
    private const val BLOCK_FRAMES = 2_048
    private const val MAX_LAYER_BYTES = 64L * 1024 * 1024

    fun mix(narrationWav: File, layers: List<SceneMixLayer>, destination: File) {
        if (layers.isEmpty()) {
            narrationWav.copyTo(destination, overwrite = true)
            return
        }
        val narration = WaveFileAssembler.inspect(narrationWav)
        requirePcm16(narration)
        val prepared = layers.map { layer ->
            require(layer.startFrame >= 0L && layer.endFrameExclusive > layer.startFrame) { "Khoảng nhạc cảnh không hợp lệ." }
            val segment = WaveFileAssembler.inspect(layer.sourceWav)
            requirePcm16(segment)
            if (segment.sampleRate != narration.sampleRate || segment.channelCount != narration.channelCount) {
                throw IOException("Nhạc cảnh phải cùng sample rate và số kênh với lời đọc.")
            }
            if (segment.dataLength > MAX_LAYER_BYTES) throw IOException("Một bản nhạc cảnh vượt giới hạn 64 MiB PCM.")
            PreparedLayer(layer, readPcm(segment), narration.blockAlign)
        }
        val tempData = File(destination.parentFile ?: narrationWav.parentFile, "${destination.name}.pcm.tmp")
        try {
            BufferedInputStream(FileInputStream(narrationWav)).use { input ->
                skipFully(input, narration.dataOffset)
                BufferedOutputStream(FileOutputStream(tempData)).use { output ->
                    val bytesPerFrame = narration.blockAlign
                    val buffer = ByteArray(BLOCK_FRAMES * bytesPerFrame)
                    var remaining = narration.dataLength
                    var framePosition = 0L
                    while (remaining > 0L) {
                        val wanted = min(buffer.size.toLong(), remaining).toInt()
                        val read = readUpTo(input, buffer, wanted)
                        if (read <= 0) throw EOFException("Dữ liệu lời đọc bị cắt ngắn.")
                        val aligned = read - read % bytesPerFrame
                        if (aligned <= 0) throw EOFException("Dữ liệu lời đọc kết thúc giữa một frame PCM.")
                        var offset = 0
                        while (offset < aligned) {
                            for (channel in 0 until narration.channelCount) {
                                val sampleOffset = offset + channel * 2
                                var mixed = sample16(buffer, sampleOffset).toInt()
                                prepared.forEach { layer ->
                                    mixed += layer.sample(framePosition, channel, narration.channelCount)
                                }
                                writeSample16(buffer, sampleOffset, mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
                            }
                            offset += bytesPerFrame
                            framePosition++
                        }
                        output.write(buffer, 0, aligned)
                        remaining -= aligned.toLong()
                    }
                }
            }
            writeWave(narration, tempData, destination)
        } finally {
            tempData.delete()
        }
    }

    private data class PreparedLayer(val layer: SceneMixLayer, val pcm: ByteArray, val blockAlign: Int) {
        private val totalFrames = pcm.size / blockAlign
        fun sample(frame: Long, channel: Int, channels: Int): Int {
            if (frame !in layer.startFrame until layer.endFrameExclusive || totalFrames <= 0) return 0
            val local = frame - layer.startFrame
            val loopFrame = (local % totalFrames).toInt()
            val gain = layer.volume.coerceIn(0f, 1f) * fadeGain(local, layer.endFrameExclusive - frame, layer.fadeFrames)
            val offset = loopFrame * blockAlign + channel.coerceAtMost(channels - 1) * 2
            return (sample16(pcm, offset) * gain).toInt()
        }
    }

    private fun fadeGain(elapsed: Long, remaining: Long, fadeFrames: Int): Float {
        if (fadeFrames <= 0) return 1f
        val fadeIn = (elapsed.toFloat() / fadeFrames).coerceIn(0f, 1f)
        val fadeOut = (remaining.toFloat() / fadeFrames).coerceIn(0f, 1f)
        return min(fadeIn, fadeOut)
    }

    private fun requirePcm16(segment: WaveSegment) {
        if (segment.audioFormat != 1 || segment.bitsPerSample != 16 || segment.channelCount !in 1..2) {
            throw IOException("Bộ trộn chỉ hỗ trợ WAV PCM16 mono hoặc stereo.")
        }
    }

    private fun readPcm(segment: WaveSegment): ByteArray = FileInputStream(segment.file).use { raw ->
        val input = BufferedInputStream(raw)
        skipFully(input, segment.dataOffset)
        ByteArray(segment.dataLength.toInt()).also { data ->
            var offset = 0
            while (offset < data.size) {
                val read = input.read(data, offset, data.size - offset)
                if (read < 0) throw EOFException("Nhạc cảnh bị cắt ngắn.")
                offset += read
            }
        }
    }

    private fun writeWave(reference: WaveSegment, rawPcm: File, destination: File) {
        val dataSize = rawPcm.length()
        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLe32(36L + dataSize)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(reference.channelCount)
            output.writeLe32(reference.sampleRate)
            output.writeLe32(reference.sampleRate * reference.blockAlign)
            output.writeLe16(reference.blockAlign)
            output.writeLe16(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLe32(dataSize)
            FileInputStream(rawPcm).use { it.copyTo(output) }
        }
    }

    private fun readUpTo(input: BufferedInputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, total, length - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        return total
    }

    private fun sample16(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset + 1].toInt() and 0xff) shl 8) or (bytes[offset].toInt() and 0xff)).toShort()

    private fun writeSample16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() < 0) throw EOFException()
            else remaining--
        }
    }

    private fun BufferedOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun BufferedOutputStream.writeLe32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }
}
