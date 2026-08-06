package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bounded time-domain speed and pitch processor for PCM16 speech segments.
 * It uses resampling plus synchronized overlap-add so pitch and duration can be
 * controlled independently without native code.
 */
object SonicPcmProcessor {
    private const val MAX_PCM_BYTES = 64L * 1024L * 1024L

    fun process(source: File, destination: File, speed: Float, pitch: Float): WaveSegment {
        val wave = WaveFileAssembler.inspect(source)
        if (wave.audioFormat != 1 || wave.bitsPerSample != 16 || wave.channelCount !in 1..2) {
            throw IOException("Sonic chỉ hỗ trợ WAV PCM16 mono hoặc stereo.")
        }
        if (wave.dataLength > MAX_PCM_BYTES) throw IOException("Đoạn PCM quá lớn để xử lý Sonic an toàn.")
        val normalizedSpeed = speed.coerceIn(0.5f, 2f)
        val normalizedPitch = pitch.coerceIn(0.5f, 2f)
        if (abs(normalizedSpeed - 1f) < 0.005f && abs(normalizedPitch - 1f) < 0.005f) {
            source.copyTo(destination, overwrite = true)
            return WaveFileAssembler.inspect(destination)
        }
        val samples = readPcm16(wave)
        val pitched = resample(samples, wave.channelCount, normalizedPitch)
        val stretchSpeed = (normalizedSpeed / normalizedPitch).coerceIn(0.25f, 4f)
        val stretched = timeStretch(
            pitched,
            channels = wave.channelCount,
            sampleRate = wave.sampleRate.toInt(),
            speed = stretchSpeed,
        )
        val originalFrames = samples.size / wave.channelCount
        val targetFrames = max(1, (originalFrames / normalizedSpeed).roundToInt())
        val exact = resizeFrames(stretched, wave.channelCount, targetFrames)
        writePcm16(destination, wave.channelCount, wave.sampleRate.toInt(), exact)
        return WaveFileAssembler.inspect(destination)
    }

    private fun resample(input: ShortArray, channels: Int, factor: Float): ShortArray {
        val frames = input.size / channels
        val outFrames = max(1, (frames / factor).roundToInt())
        val output = ShortArray(outFrames * channels)
        for (frame in 0 until outFrames) {
            val sourcePosition = frame * factor
            val left = sourcePosition.toInt().coerceIn(0, frames - 1)
            val right = min(left + 1, frames - 1)
            val fraction = sourcePosition - left
            for (channel in 0 until channels) {
                val a = input[left * channels + channel].toInt()
                val b = input[right * channels + channel].toInt()
                output[frame * channels + channel] = (a + (b - a) * fraction).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return output
    }

    private fun timeStretch(input: ShortArray, channels: Int, sampleRate: Int, speed: Float): ShortArray {
        if (abs(speed - 1f) < 0.005f) return input
        val inputFrames = input.size / channels
        if (inputFrames < 512) return resizeFrames(input, channels, max(1, (inputFrames / speed).roundToInt()))
        val window = (sampleRate * 40 / 1000).coerceIn(384, 2048).coerceAtMost(inputFrames)
        val overlap = (window / 4).coerceAtLeast(64)
        val synthesisHop = window - overlap
        val analysisHop = synthesisHop * speed
        val search = (sampleRate * 8 / 1000).coerceIn(32, overlap)
        val estimatedFrames = max(window, (inputFrames / speed).roundToInt() + window)
        val output = FloatArray(estimatedFrames * channels)
        var outputFrames = window
        for (frame in 0 until window) for (channel in 0 until channels) {
            output[frame * channels + channel] = input[frame * channels + channel].toFloat()
        }
        var analysisPosition = analysisHop
        while (outputFrames + synthesisHop < estimatedFrames) {
            val expected = analysisPosition.roundToInt()
            if (expected + window >= inputFrames) break
            val candidate = findBestOffset(input, channels, expected, search, overlap, output, outputFrames)
            val outStart = outputFrames - overlap
            for (frame in 0 until overlap) {
                val mix = frame.toFloat() / overlap.toFloat()
                for (channel in 0 until channels) {
                    val index = (outStart + frame) * channels + channel
                    val incoming = input[(candidate + frame) * channels + channel].toFloat()
                    output[index] = output[index] * (1f - mix) + incoming * mix
                }
            }
            val copyFrames = min(synthesisHop, inputFrames - (candidate + overlap))
            for (frame in 0 until copyFrames) for (channel in 0 until channels) {
                output[(outputFrames + frame) * channels + channel] =
                    input[(candidate + overlap + frame) * channels + channel].toFloat()
            }
            outputFrames += copyFrames
            analysisPosition += analysisHop
            if (copyFrames < synthesisHop) break
        }
        return ShortArray(outputFrames * channels) { index ->
            output[index].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun findBestOffset(
        input: ShortArray,
        channels: Int,
        expected: Int,
        search: Int,
        overlap: Int,
        output: FloatArray,
        outputFrames: Int,
    ): Int {
        val inputFrames = input.size / channels
        var best = expected.coerceIn(0, inputFrames - overlap - 1)
        var bestScore = Double.NEGATIVE_INFINITY
        val outStart = outputFrames - overlap
        val low = max(0, expected - search)
        val high = min(inputFrames - overlap - 1, expected + search)
        var candidate = low
        while (candidate <= high) {
            var dot = 0.0
            var aa = 0.0
            var bb = 0.0
            var frame = 0
            while (frame < overlap) {
                var outMono = 0.0
                var inMono = 0.0
                for (channel in 0 until channels) {
                    outMono += output[(outStart + frame) * channels + channel]
                    inMono += input[(candidate + frame) * channels + channel]
                }
                dot += outMono * inMono
                aa += outMono * outMono
                bb += inMono * inMono
                frame += 2
            }
            val score = if (aa > 1.0 && bb > 1.0) dot / kotlin.math.sqrt(aa * bb) else Double.NEGATIVE_INFINITY
            if (score > bestScore) { bestScore = score; best = candidate }
            candidate += 2
        }
        return best
    }

    private fun resizeFrames(input: ShortArray, channels: Int, targetFrames: Int): ShortArray {
        val sourceFrames = input.size / channels
        if (sourceFrames == targetFrames) return input
        if (sourceFrames <= 1) return ShortArray(targetFrames * channels) { input.getOrElse(it % channels) { 0 } }
        val output = ShortArray(targetFrames * channels)
        val scale = (sourceFrames - 1).toFloat() / max(1, targetFrames - 1)
        for (frame in 0 until targetFrames) {
            val position = frame * scale
            val left = position.toInt().coerceIn(0, sourceFrames - 1)
            val right = min(left + 1, sourceFrames - 1)
            val fraction = position - left
            for (channel in 0 until channels) {
                val a = input[left * channels + channel].toInt()
                val b = input[right * channels + channel].toInt()
                output[frame * channels + channel] = (a + (b - a) * fraction).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return output
    }

    private fun readPcm16(wave: WaveSegment): ShortArray {
        val bytes = ByteArray(wave.dataLength.toInt())
        BufferedInputStream(FileInputStream(wave.file)).use { input ->
            skipFully(input, wave.dataOffset)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("PCM bị cắt ngắn.")
                offset += count
            }
        }
        return ShortArray(bytes.size / 2) { index ->
            val offset = index * 2
            ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()
        }
    }

    private fun writePcm16(file: File, channels: Int, sampleRate: Int, samples: ShortArray) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size.toLong() * 2L
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII)); output.writeU32(36L + dataBytes)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); output.writeU32(16); output.writeU16(1)
            output.writeU16(channels); output.writeU32(sampleRate.toLong()); output.writeU32(sampleRate.toLong() * channels * 2L)
            output.writeU16(channels * 2); output.writeU16(16); output.write("data".toByteArray(Charsets.US_ASCII)); output.writeU32(dataBytes)
            samples.forEach { sample -> output.write(sample.toInt() and 0xff); output.write((sample.toInt() ushr 8) and 0xff) }
        }
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else throw EOFException()
        }
    }

    private fun OutputStream.writeU16(value: Int) { write(value and 0xff); write((value ushr 8) and 0xff) }
    private fun OutputStream.writeU32(value: Long) {
        write((value and 0xff).toInt()); write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt()); write(((value ushr 24) and 0xff).toInt())
    }
}
