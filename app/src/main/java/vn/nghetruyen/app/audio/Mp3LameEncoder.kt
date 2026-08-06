package vn.nghetruyen.app.audio

import co.ntbl.lame.mp3.Lame
import co.ntbl.lame.mp3.MPEGMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Streaming PCM16/WAVE to MP3 encoder using the pure-Java LAME port. */
object Mp3LameEncoder {
    private const val FRAMES_PER_BLOCK = 2_304
    private const val ENCODED_BUFFER_BYTES = 16_384 + 128 * 1024

    fun encode(
        wav: File,
        destination: File,
        metadata: Id3v23Writer.Metadata,
        bitrateKbps: Int = bitrateFor(wav),
    ) {
        val segment = WaveFileAssembler.inspect(wav)
        if (segment.audioFormat != 1 || segment.bitsPerSample != 16 || segment.channelCount !in 1..2) {
            throw IOException("MP3 chỉ hỗ trợ WAV PCM16 mono hoặc stereo.")
        }
        val channels = segment.channelCount
        val sampleRate = segment.sampleRate.toInt()
        val lame = Lame()
        val flags = lame.flags
        flags.setInNumChannels(channels)
        flags.setInSampleRate(sampleRate)
        flags.setMode(if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO)
        flags.setBitRate(bitrateKbps.coerceIn(32, 320))
        flags.setQuality(Lame.QUALITY_HIGH)
        flags.setWriteId3tagAutomatic(false)
        flags.setFindReplayGain(true)
        val initResult = lame.initParams()
        if (initResult < 0) {
            lame.close()
            throw IOException("LAME không chấp nhận thông số MP3 ($initResult).")
        }

        destination.parentFile?.mkdirs()
        try {
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                Id3v23Writer.write(output, metadata)
                BufferedInputStream(FileInputStream(wav)).use { input ->
                    skipFully(input, segment.dataOffset)
                    var remaining = segment.dataLength
                    val bytesPerFrame = channels * 2
                    val pcm = ByteArray(FRAMES_PER_BLOCK * bytesPerFrame)
                    val left = FloatArray(FRAMES_PER_BLOCK)
                    val right = FloatArray(FRAMES_PER_BLOCK)
                    val encoded = ByteArray(ENCODED_BUFFER_BYTES)
                    while (remaining > 0L) {
                        val wanted = minOf(pcm.size.toLong(), remaining).toInt()
                        val read = input.readNBytes(pcm, 0, wanted)
                        if (read <= 0) throw EOFException("Dữ liệu WAV bị cắt ngắn.")
                        val aligned = read - (read % bytesPerFrame)
                        if (aligned <= 0) break
                        val frames = aligned / bytesPerFrame
                        var cursor = 0
                        for (index in 0 until frames) {
                            left[index] = sample16(pcm, cursor) * 65_536f
                            cursor += 2
                            right[index] = if (channels == 2) {
                                sample16(pcm, cursor).also { cursor += 2 } * 65_536f
                            } else left[index]
                        }
                        val produced = lame.encodeBuffer(left, right, frames, encoded)
                        if (produced < 0) throw IOException("LAME thất bại khi mã hóa ($produced).")
                        if (produced > 0) output.write(encoded, 0, produced)
                        remaining -= aligned.toLong()
                    }
                    val flushed = lame.encodeFlush(encoded)
                    if (flushed < 0) throw IOException("LAME thất bại khi kết thúc MP3 ($flushed).")
                    if (flushed > 0) output.write(encoded, 0, flushed)
                }
            }
        } finally {
            lame.close()
        }
        if (destination.length() <= 10L) throw IOException("Tệp MP3 đầu ra rỗng.")
    }

    fun bitrateFor(wav: File): Int = if (WaveFileAssembler.inspect(wav).channelCount == 1) 96 else 160

    private fun sample16(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset + 1].toInt() and 0xff) shl 8) or (bytes[offset].toInt() and 0xff)).toShort()

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() < 0) throw EOFException("Không tới được dữ liệu WAV.")
            else remaining--
        }
    }
}
