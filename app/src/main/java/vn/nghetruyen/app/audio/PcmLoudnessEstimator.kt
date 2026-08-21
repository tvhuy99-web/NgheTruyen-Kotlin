package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * XPK-compatible scene-music loudness analysis.
 *
 * The reference tool measures K-weighted integrated loudness with 400 ms blocks,
 * an absolute -70 LUFS gate and a relative -10 LU gate. It also stores the sample
 * peak so normalization can be capped at -1 dBFS without decoding the track again
 * when the user later changes the target LUFS.
 */
object PcmLoudnessEstimator {
    const val VERSION = 1
    const val DEFAULT_TARGET_LUFS = -24f
    const val MIN_TARGET_LUFS = -36f
    const val MAX_MUSIC_TARGET_LUFS = -18f
    const val MAX_TARGET_LUFS = -5f
    const val MIN_GAIN_DB = -36f
    const val MAX_GAIN_DB = 24f
    const val PEAK_CEILING_DBFS = -1f

    data class Analysis(
        val loudnessLufs: Float,
        val peakDbfs: Float,
    )

    data class Normalization(
        val targetLufs: Float,
        val gainDb: Float,
        val peakLimited: Boolean,
    )

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    private class FilterState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
    }

    private data class ChannelState(
        val shelf: FilterState = FilterState(),
        val highPass: FilterState = FilterState(),
    )

    private data class Segment(val energy: Double, val frames: Int)

    internal class StreamingAnalyzer(
        private val sampleRate: Int,
        private val channels: Int,
    ) {
        private val shelf: Coefficients
        private val highPass: Coefficients
        private val states: Array<ChannelState>
        private val weights: DoubleArray
        private val stepFrames: Int
        private val frameBytes: Int
        private val segments = mutableListOf<Segment>()
        private var segmentEnergy = 0.0
        private var segmentFrames = 0
        private var peak = 0.0
        private var finished = false

        init {
            require(sampleRate in 8_000..384_000) { "Sample rate PCM không hợp lệ." }
            require(channels in 1..8) { "Số kênh PCM không được hỗ trợ." }
            shelf = shelfCoefficients(sampleRate.toDouble())
            highPass = highPassCoefficients(sampleRate.toDouble())
            states = Array(channels) { ChannelState() }
            weights = channelWeights(channels)
            stepFrames = (sampleRate / 10.0 + 0.5).toInt().coerceAtLeast(1)
            frameBytes = channels * 2
        }

        fun acceptPcm16(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            check(!finished) { "Bộ đo loudness đã kết thúc." }
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            require(length % frameBytes == 0) { "PCM16 không thẳng hàng theo frame." }
            var cursor = offset
            val end = offset + length
            while (cursor < end) {
                var weighted = 0.0
                for (channel in 0 until channels) {
                    val lo = bytes[cursor].toInt() and 0xff
                    val hi = bytes[cursor + 1].toInt()
                    val sample = ((lo or (hi shl 8)).toShort().toDouble() / 32768.0)
                    peak = maxOf(peak, abs(sample))
                    val state = states[channel]
                    var filtered = filterSample(state.shelf, shelf, sample)
                    filtered = filterSample(state.highPass, highPass, filtered)
                    weighted += filtered * filtered * weights[channel]
                    cursor += 2
                }
                segmentEnergy += weighted
                segmentFrames += 1
                if (segmentFrames >= stepFrames) {
                    segments += Segment(segmentEnergy, segmentFrames)
                    segmentEnergy = 0.0
                    segmentFrames = 0
                }
            }
        }

        fun finish(): Analysis {
            check(!finished) { "Bộ đo loudness đã kết thúc." }
            finished = true
            if (segmentFrames > 0) {
                segments += Segment(segmentEnergy, segmentFrames)
                segmentEnergy = 0.0
                segmentFrames = 0
            }
            val loudness = integratedLoudness(segments)
                ?: throw IOException("Bản nhạc không có đủ tín hiệu để đo độ lớn.")
            val peakDbfs = if (peak > 0.0) 20.0 * log10(peak) else -120.0
            return Analysis(
                loudnessLufs = loudness.toFloat(),
                peakDbfs = peakDbfs.toFloat(),
            )
        }
    }

    fun analyze(wavFile: File): Analysis {
        val wave = WaveFileAssembler.inspect(wavFile)
        if (wave.audioFormat != 1 || wave.bitsPerSample != 16) {
            throw IOException("Chỉ đo được loudness WAV PCM16.")
        }
        val channels = wave.channelCount
        val sampleRate = wave.sampleRate.toInt()
        if (sampleRate < 8_000 || channels !in 1..8) {
            throw IOException("Định dạng âm thanh không được hỗ trợ để chuẩn hóa.")
        }

        val frameBytes = channels * 2
        val bufferSize = (64 * 1024 / frameBytes) * frameBytes
        val buffer = ByteArray(bufferSize.coerceAtLeast(frameBytes))
        val analyzer = StreamingAnalyzer(sampleRate, channels)

        BufferedInputStream(FileInputStream(wavFile)).use { input ->
            skipFully(input, wave.dataOffset)
            var remaining = wave.dataLength
            while (remaining > 0) {
                val wanted = min(buffer.size.toLong(), remaining).toInt()
                var readTotal = 0
                while (readTotal < wanted) {
                    val count = input.read(buffer, readTotal, wanted - readTotal)
                    if (count < 0) throw EOFException("PCM bị cắt ngắn.")
                    readTotal += count
                }
                val completeBytes = readTotal - (readTotal % frameBytes)
                if (completeBytes > 0) analyzer.acceptPcm16(buffer, 0, completeBytes)
                remaining -= readTotal
            }
        }
        return analyzer.finish()
    }

    /** Compatibility helper for callers that only need the measured LUFS value. */
    fun estimateLufs(wavFile: File): Float = analyze(wavFile).loudnessLufs

    fun calculateNormalization(
        loudnessLufs: Float,
        peakDbfs: Float?,
        targetLufs: Float,
    ): Normalization {
        val target = targetLufs.coerceIn(MIN_TARGET_LUFS, MAX_TARGET_LUFS)
        var desired = (target - loudnessLufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        var limited = false
        val finitePeak = peakDbfs?.takeIf(Float::isFinite)
        if (finitePeak != null) {
            val peakLimitedGain = PEAK_CEILING_DBFS - finitePeak
            if (desired > peakLimitedGain) {
                desired = peakLimitedGain
                limited = true
            }
        }
        return Normalization(
            targetLufs = target,
            gainDb = desired.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
            peakLimited = limited,
        )
    }

    fun gainDbToLinear(gainDb: Float): Float =
        10.0.pow(gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) / 20.0).toFloat()

    /** Legacy API kept for older export/runtime callers. */
    fun normalizationGain(measuredLufs: Float, targetLufs: Float): Float =
        gainDbToLinear(calculateNormalization(measuredLufs, null, targetLufs).gainDb)

    fun isReady(
        version: Int,
        error: String,
        loudnessLufs: Float,
        targetLufs: Float,
        storedTargetLufs: Float,
        gainDb: Float,
    ): Boolean =
        version >= VERSION &&
            error.isBlank() &&
            loudnessLufs.isFinite() &&
            gainDb.isFinite() &&
            storedTargetLufs.isFinite() &&
            abs(storedTargetLufs - targetLufs.coerceIn(MIN_TARGET_LUFS, MAX_TARGET_LUFS)) < 0.05f

    private fun shelfCoefficients(sampleRate: Double): Coefficients {
        val f0 = 1681.974450955533
        val gain = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gain / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Coefficients(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    private fun highPassCoefficients(sampleRate: Double): Coefficients {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Coefficients(
            b0 = 1.0 / a0,
            b1 = -2.0 / a0,
            b2 = 1.0 / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    private fun filterSample(state: FilterState, coefficients: Coefficients, input: Double): Double {
        val output = coefficients.b0 * input +
            coefficients.b1 * state.x1 +
            coefficients.b2 * state.x2 -
            coefficients.a1 * state.y1 -
            coefficients.a2 * state.y2
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = output
        return output
    }

    private fun channelWeights(channels: Int): DoubleArray = DoubleArray(channels) { 1.0 }.also { weights ->
        if (channels == 6) {
            weights[3] = 0.0
            weights[4] = 1.41
            weights[5] = 1.41
        } else if (channels >= 8) {
            weights[3] = 0.0
            for (index in 4 until channels) weights[index] = 1.41
        }
    }

    private fun integratedLoudness(segments: List<Segment>): Double? {
        val blocks = mutableListOf<Double>()
        if (segments.size >= 4) {
            for (index in 0..segments.size - 4) {
                var energy = 0.0
                var frames = 0
                for (offset in 0..3) {
                    energy += segments[index + offset].energy
                    frames += segments[index + offset].frames
                }
                if (frames > 0) blocks += energy / frames
            }
        } else if (segments.isNotEmpty()) {
            val energy = segments.sumOf(Segment::energy)
            val frames = segments.sumOf(Segment::frames)
            if (frames > 0) blocks += energy / frames
        }

        val absolute = blocks.filter { energy ->
            energy > 0.0 && (-0.691 + 10.0 * log10(energy)) > -70.0
        }
        if (absolute.isEmpty()) return null
        val ungatedEnergy = absolute.average()
        val ungatedLoudness = -0.691 + 10.0 * log10(ungatedEnergy)
        val threshold = maxOf(-70.0, ungatedLoudness - 10.0)
        val gated = absolute.filter { energy -> (-0.691 + 10.0 * log10(energy)) > threshold }
        if (gated.isEmpty()) return ungatedLoudness
        return -0.691 + 10.0 * log10(gated.average())
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() >= 0) remaining--
            else throw EOFException()
        }
    }
}
