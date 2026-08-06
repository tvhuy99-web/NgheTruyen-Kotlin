package vn.nghetruyen.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToLong

/** Decodes a user-selected audio document and normalizes it to narration PCM16 WAV. */
object AndroidAudioTrackDecoder {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_DECODED_PCM_BYTES = 64L * 1024L * 1024L

    fun decodeToWave(
        context: Context,
        uri: Uri,
        targetSampleRate: Int,
        targetChannels: Int,
        destination: File,
    ) {
        require(targetSampleRate in 8_000..192_000)
        require(targetChannels in 1..2)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val raw = File(destination.parentFile, "${destination.name}.decoded.pcm")
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Tệp nhạc không có track âm thanh.")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Tệp nhạc thiếu MIME codec.")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var inputDone = false
            var outputDone = false
            var decodedRate = -1
            var decodedChannels = -1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var written = 0L
            val info = MediaCodec.BufferInfo()
            BufferedOutputStream(FileOutputStream(raw)).use { output ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: throw IOException("Audio decoder không cung cấp input buffer.")
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            decodedRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            decodedChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            if (decodedRate !in 8_000..384_000 || decodedChannels !in 1..8) {
                                throw IOException("Thông số PCM giải mã không hợp lệ.")
                            }
                            if (pcmEncoding !in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                                throw IOException("PCM ${pcmEncoding} của track chưa được hỗ trợ.")
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("Audio decoder không cung cấp output buffer.")
                            if (info.size > 0) {
                                if (decodedRate <= 0 || decodedChannels <= 0) {
                                    val format = codec.outputFormat
                                    decodedRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    decodedChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                val normalized = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> floatPcmTo16(bytes)
                                    else -> bytes
                                }
                                written += normalized.size
                                if (written > MAX_DECODED_PCM_BYTES) {
                                    throw IOException("Track nhạc sau giải mã vượt giới hạn 64 MiB PCM.")
                                }
                                output.write(normalized)
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            if (decodedRate <= 0 || decodedChannels <= 0 || raw.length() <= 0L) {
                throw IOException("Không giải mã được PCM từ track nhạc.")
            }
            Pcm16Resampler.convertRaw(
                raw = raw,
                sourceSampleRate = decodedRate,
                sourceChannels = decodedChannels,
                targetSampleRate = targetSampleRate,
                targetChannels = targetChannels,
                destination = destination,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            raw.delete()
        }
    }

    private fun floatPcmTo16(bytes: ByteArray): ByteArray {
        if (bytes.size % 4 != 0) throw IOException("PCM float không thẳng hàng theo mẫu.")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(bytes.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 4) {
            val sample = input.float.coerceIn(-1f, 1f)
            output.putShort((sample * Short.MAX_VALUE).toInt().toShort())
        }
        return output.array()
    }
}

/** Bounded linear resampler used only for user-selected scene music. */
object Pcm16Resampler {
    fun convertRaw(
        raw: File,
        sourceSampleRate: Int,
        sourceChannels: Int,
        targetSampleRate: Int,
        targetChannels: Int,
        destination: File,
    ) {
        val bytes = raw.readBytes()
        val sourceFrameBytes = sourceChannels * 2
        if (bytes.isEmpty() || bytes.size % sourceFrameBytes != 0) {
            throw IOException("PCM track bị cắt ngắn hoặc sai số kênh.")
        }
        val sourceFrames = bytes.size / sourceFrameBytes
        val targetFrames = (sourceFrames.toDouble() * targetSampleRate / sourceSampleRate)
            .roundToLong().coerceAtLeast(1L)
        val dataBytes = targetFrames * targetChannels * 2L
        if (dataBytes > 0xfffffff0L) throw IOException("Track sau resample vượt giới hạn WAV.")
        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            writeWaveHeader(output, targetSampleRate, targetChannels, dataBytes)
            for (targetFrame in 0 until targetFrames) {
                val sourcePosition = targetFrame.toDouble() * sourceSampleRate / targetSampleRate
                val leftIndex = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
                val rightIndex = (leftIndex + 1).coerceAtMost(sourceFrames - 1)
                val fraction = (sourcePosition - leftIndex).toFloat()
                if (targetChannels == 1) {
                    writeLe16(output, interpolate(mono(bytes, leftIndex, sourceChannels), mono(bytes, rightIndex, sourceChannels), fraction))
                } else {
                    writeLe16(output, interpolate(sampleAt(bytes, leftIndex, sourceChannels, 0), sampleAt(bytes, rightIndex, sourceChannels, 0), fraction))
                    val rightChannel = if (sourceChannels == 1) 0 else 1
                    writeLe16(output, interpolate(sampleAt(bytes, leftIndex, sourceChannels, rightChannel), sampleAt(bytes, rightIndex, sourceChannels, rightChannel), fraction))
                }
            }
        }
    }

    private fun mono(bytes: ByteArray, frame: Int, channels: Int): Int {
        var sum = 0L
        for (channelIndex in 0 until channels) sum += sampleAt(bytes, frame, channels, channelIndex)
        return (sum / channels).toInt()
    }

    private fun sampleAt(bytes: ByteArray, frame: Int, channels: Int, channel: Int): Int {
        val offset = (frame * channels + channel.coerceIn(0, channels - 1)) * 2
        return (((bytes[offset + 1].toInt() and 0xff) shl 8) or (bytes[offset].toInt() and 0xff)).toShort().toInt()
    }

    private fun interpolate(a: Int, b: Int, fraction: Float): Int =
        (a + (b - a) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun writeWaveHeader(output: BufferedOutputStream, sampleRate: Int, channels: Int, dataBytes: Long) {
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(output, 36L + dataBytes)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeLe32(output, 16L)
        writeLe16(output, 1)
        writeLe16(output, channels)
        writeLe32(output, sampleRate.toLong())
        writeLe32(output, sampleRate.toLong() * channels * 2L)
        writeLe16(output, channels * 2)
        writeLe16(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeLe32(output, dataBytes)
    }

    private fun writeLe16(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLe32(output: BufferedOutputStream, value: Long) {
        output.write((value and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 24) and 0xff).toInt())
    }
}
