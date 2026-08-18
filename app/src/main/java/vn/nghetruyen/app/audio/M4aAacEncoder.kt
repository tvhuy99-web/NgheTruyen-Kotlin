package vn.nghetruyen.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException


object M4aAacEncoder {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TIMEOUT_US = 10_000L

    fun encode(sourceWav: File, destination: File, bitrate: Int = 96_000) {
        val wave = WaveFileAssembler.inspect(sourceWav)
        if (wave.audioFormat != 1 || wave.bitsPerSample != 16 || wave.channelCount !in 1..2) {
            throw IOException("M4A yêu cầu WAV PCM16 mono hoặc stereo.")
        }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) throw IOException("Không thay được tệp M4A tạm.")

        val format = MediaFormat.createAudioFormat(MIME, wave.sampleRate.toInt(), wave.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(32_000, 256_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            FileInputStream(sourceWav).use { raw ->
                val input = BufferedInputStream(raw)
                skipFully(input, wave.dataOffset)
                var remaining = wave.dataLength
                var submittedFrames = 0L
                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)
                                ?: throw IOException("AAC encoder không cung cấp input buffer.")
                            buffer.clear()
                            val maxRead = minOf(buffer.remaining().toLong(), remaining).toInt()
                            if (maxRead <= 0) {
                                val pts = submittedFrames * 1_000_000L / wave.sampleRate
                                codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val aligned = maxRead - (maxRead % wave.blockAlign)
                                if (aligned <= 0) throw IOException("Input AAC không thẳng hàng theo frame.")
                                val bytes = ByteArray(aligned)
                                readFully(input, bytes)
                                buffer.put(bytes)
                                val pts = submittedFrames * 1_000_000L / wave.sampleRate
                                codec.queueInputBuffer(inputIndex, 0, aligned, pts, 0)
                                submittedFrames += aligned / wave.blockAlign
                                remaining -= aligned
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IOException("AAC encoder đổi format nhiều lần.")
                            val activeMuxer = muxer
                            trackIndex = activeMuxer.addTrack(codec.outputFormat)
                            activeMuxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("AAC encoder không cung cấp output buffer.")
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0) {
                                if (!muxerStarted || trackIndex < 0) throw IOException("M4A muxer chưa sẵn sàng.")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            if (!muxerStarted) throw IOException("Không tạo được track AAC cho tệp M4A.")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            val activeMuxer = muxer
            if (muxerStarted && activeMuxer != null) runCatching { activeMuxer.stop() }
            if (activeMuxer != null) runCatching { activeMuxer.release() }
        }
        if (!destination.isFile || destination.length() <= 0L) {
            throw IOException("Không tạo được tệp M4A hợp lệ.")
        }
    }

    private fun skipFully(input: BufferedInputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() >= 0) remaining-- else throw EOFException("Không tới được dữ liệu PCM.")
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException("PCM bị cắt ngắn.")
            offset += read
        }
    }
}