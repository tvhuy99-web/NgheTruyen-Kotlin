package vn.nghetruyen.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast Mode-3 loudness measurement. The source MP3/OGG is never rewritten. MediaCodec only
 * exposes decoded PCM buffers in memory and they are fed directly to the LUFS estimator at the
 * source sample rate/channel count. This deliberately avoids temporary WAV I/O and resampling.
 */
object AndroidAudioLoudnessAnalyzer {
    private const val TIMEOUT_US = 10_000L

    fun analyze(
        context: Context,
        uri: Uri,
        maxDecodeDurationUs: Long,
    ): PcmLoudnessEstimator.Analysis {
        require(maxDecodeDurationUs > 0L)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Tệp âm thanh không có track âm thanh.")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Tệp âm thanh thiếu MIME codec.")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var inputDone = false
            var outputDone = false
            var analyzer: PcmLoudnessEstimator.StreamingAnalyzer? = null
            var analyzerRate = -1
            var analyzerChannels = -1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()

            fun ensureAnalyzer(format: MediaFormat): PcmLoudnessEstimator.StreamingAnalyzer {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                if (encoding !in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                    throw IOException("PCM $encoding của track chưa được hỗ trợ.")
                }
                pcmEncoding = encoding
                val current = analyzer
                if (current == null) {
                    analyzerRate = rate
                    analyzerChannels = channels
                    return PcmLoudnessEstimator.StreamingAnalyzer(rate, channels).also { analyzer = it }
                }
                if (rate != analyzerRate || channels != analyzerChannels) {
                    throw IOException("Định dạng PCM thay đổi giữa lúc phân tích.")
                }
                return current
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Audio decoder không cung cấp input buffer.")
                        inputBuffer.clear()
                        val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                        val durationReached = sampleTimeUs >= maxDecodeDurationUs
                        val size = if (durationReached) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureAnalyzer(codec.outputFormat)
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(outputIndex)
                                    ?: throw IOException("Audio decoder không cung cấp output buffer.")
                                val activeAnalyzer = ensureAnalyzer(codec.outputFormat)
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                output.get(bytes)
                                val pcm16 = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> floatPcmTo16(bytes)
                                    else -> bytes
                                }
                                activeAnalyzer.acceptPcm16(pcm16)
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            return analyzer?.finish()
                ?: throw IOException("Không giải mã được PCM từ track âm thanh.")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
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
