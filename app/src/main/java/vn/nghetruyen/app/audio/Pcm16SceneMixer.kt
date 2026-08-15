package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * One PCM16 audio layer positioned on the narration timeline.
 * MUSIC/AMBIENCE use [looping]=true; SFX uses [looping]=false and naturally ends with its source.
 */
data class SceneMixLayer(
    val sourceWav: File,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val volume: Float = 0.18f,
    val fadeFrames: Int = 0,
    val looping: Boolean = true,
)

/**
 * Streaming narration + MUSIC + AMBIENCE + SFX mixer.
 *
 * Looping layers use an internal overlap/crossfade at the source seam instead of modulo hard-looping.
 * A deterministic phase derived from source + timeline start prevents simultaneous ambience layers
 * with similar durations from exposing synchronized repetition in exported audiobooks.
 */
object Pcm16SceneMixer {
    private const val BLOCK_FRAMES = 2_048
    private const val MAX_LAYER_BYTES = 64L * 1024 * 1024
    private const val MIN_LOOPING_FADE_MILLIS = 1_200L

    fun mix(narrationWav: File, layers: List<SceneMixLayer>, destination: File) {
        if (layers.isEmpty()) {
            narrationWav.copyTo(destination, overwrite = true)
            return
        }
        val narration = WaveFileAssembler.inspect(narrationWav)
        requirePcm16(narration)
        val prepared = layers.map { layer ->
            require(layer.startFrame >= 0L && layer.endFrameExclusive > layer.startFrame) { "Khoảng lớp âm thanh không hợp lệ." }
            val segment = WaveFileAssembler.inspect(layer.sourceWav)
            requirePcm16(segment)
            if (segment.sampleRate != narration.sampleRate || segment.channelCount != narration.channelCount) {
                throw IOException("Lớp âm thanh phải cùng sample rate và số kênh với lời đọc.")
            }
            if (segment.dataLength > MAX_LAYER_BYTES) throw IOException("Một lớp âm thanh vượt giới hạn 64 MiB PCM.")
            PreparedLayer(layer, readPcm(segment), narration.blockAlign, narration.sampleRate.toInt())
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

    private data class PreparedLayer(
        val layer: SceneMixLayer,
        val pcm: ByteArray,
        val blockAlign: Int,
        val sampleRate: Int,
    ) {
        private val totalFrames = pcm.size / blockAlign
        private val layerFrames = (layer.endFrameExclusive - layer.startFrame)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
        private val requestedBoundaryFadeFrames = if (layer.looping) {
            maxOf(layer.fadeFrames, (sampleRate * MIN_LOOPING_FADE_MILLIS / 1_000L).toInt())
        } else layer.fadeFrames
        private val boundaryFadeFrames = requestedBoundaryFadeFrames
            .coerceAtMost((layerFrames / 2).coerceAtLeast(1))
        private val loopBlendFrames = if (layer.looping && totalFrames > 8) {
            boundaryFadeFrames
                .coerceAtLeast(1)
                .coerceAtMost((totalFrames / 4).coerceAtLeast(1))
        } else 0
        private val loopCycleFrames = if (loopBlendFrames > 0) {
            (totalFrames - loopBlendFrames).coerceAtLeast(1)
        } else totalFrames.coerceAtLeast(1)
        private val phaseFrames = if (layer.looping && loopCycleFrames > 1) {
            positiveHash(layer.sourceWav.name, layer.startFrame) % loopCycleFrames
        } else 0

        fun sample(frame: Long, channel: Int, channels: Int): Int {
            if (frame !in layer.startFrame until layer.endFrameExclusive || totalFrames <= 0) return 0
            val local = frame - layer.startFrame
            if (!layer.looping && local >= totalFrames) return 0
            val audibleRemaining = if (layer.looping) {
                layer.endFrameExclusive - frame
            } else {
                min(layer.endFrameExclusive - frame, totalFrames.toLong() - local)
            }
            val gain = layer.volume.coerceIn(0f, 1f) * fadeGain(local, audibleRemaining, boundaryFadeFrames)
            if (gain <= 0f) return 0

            val rawSample = if (!layer.looping) {
                sourceSample(local.toInt(), channel, channels)
            } else if (loopBlendFrames <= 0 || totalFrames <= loopBlendFrames * 2) {
                val sourceFrame = ((local + phaseFrames) % totalFrames.toLong()).toInt()
                sourceSample(sourceFrame, channel, channels)
            } else {
                seamlessLoopSample(local, channel, channels)
            }
            return (rawSample * gain).toInt()
        }

        private fun seamlessLoopSample(local: Long, channel: Int, channels: Int): Int {
            val cyclePosition = ((local + phaseFrames) % loopCycleFrames.toLong()).toInt()
            val normalFrames = (loopCycleFrames - loopBlendFrames).coerceAtLeast(0)
            if (cyclePosition < normalFrames) {
                val sourceFrame = loopBlendFrames + cyclePosition
                return sourceSample(sourceFrame.coerceAtMost(totalFrames - 1), channel, channels)
            }

            val blendPosition = (cyclePosition - normalFrames).coerceIn(0, loopBlendFrames - 1)
            val tailFrame = (totalFrames - loopBlendFrames + blendPosition).coerceIn(0, totalFrames - 1)
            val headFrame = blendPosition.coerceIn(0, totalFrames - 1)
            val fraction = blendPosition.toFloat() / loopBlendFrames.coerceAtLeast(1).toFloat()
            val tail = sourceSample(tailFrame, channel, channels)
            val head = sourceSample(headFrame, channel, channels)
            return (tail * (1f - fraction) + head * fraction).toInt()
        }

        private fun sourceSample(sourceFrame: Int, channel: Int, channels: Int): Int {
            val safeFrame = sourceFrame.coerceIn(0, totalFrames - 1)
            val offset = safeFrame * blockAlign + channel.coerceAtMost(channels - 1) * 2
            return sample16(pcm, offset).toInt()
        }
    }

    private fun fadeGain(elapsed: Long, remaining: Long, fadeFrames: Int): Float {
        if (fadeFrames <= 0) return 1f
        val fadeIn = (elapsed.toFloat() / fadeFrames).coerceIn(0f, 1f)
        val fadeOut = (remaining.toFloat() / fadeFrames).coerceIn(0f, 1f)
        return min(fadeIn, fadeOut)
    }

    private fun positiveHash(name: String, startFrame: Long): Int {
        val hash = 31L * name.hashCode().toLong() + startFrame
        return hash.and(0x7fffffffL).toInt()
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
                if (read < 0) throw EOFException("Lớp âm thanh bị cắt ngắn.")
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
