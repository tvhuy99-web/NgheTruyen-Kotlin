package vn.nghetruyen.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * One PCM16 audio layer positioned on the narration timeline.
 * MUSIC/AMBIENCE use [looping]=true; SFX is normally non-looping, while an explicitly
 * action-bounded repeating SFX may loop only until [endFrameExclusive].
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
 * Source PCM is memory-mapped once per unique WAV instead of copied into one heap ByteArray per scene.
 * The timeline is swept block-by-block, keeping only layers and SFX duck envelopes that can intersect
 * the current block. This keeps long audiobook exports bounded by unique assets and active scene count
 * rather than multiplying RAM/CPU by the total number of scene cues.
 *
 * Looping layers use an internal overlap/crossfade at the source seam instead of modulo hard-looping.
 * A deterministic phase derived from source + timeline start prevents simultaneous ambience layers
 * with similar durations from exposing synchronized repetition in exported audiobooks. Non-looping
 * one-shot layers are treated as important SFX and apply a brief smooth duck only to looping
 * background layers; narration and the SFX itself are never ducked by that envelope.
 */
object Pcm16SceneMixer {
    private const val BLOCK_FRAMES = 2_048
    private const val MAX_LAYER_BYTES = 64L * 1024 * 1024
    private const val MIN_LOOPING_FADE_MILLIS = 1_200L
    private const val SFX_BACKGROUND_DUCK = 0.72f
    private const val SFX_DUCK_ATTACK_MILLIS = 120L
    private const val SFX_DUCK_RELEASE_MILLIS = 360L

    fun mix(narrationWav: File, layers: List<SceneMixLayer>, destination: File) {
        if (layers.isEmpty()) {
            narrationWav.copyTo(destination, overwrite = true)
            return
        }
        val narration = WaveFileAssembler.inspect(narrationWav)
        requirePcm16(narration)

        val sourceCache = linkedMapOf<String, PreparedSource>()
        val prepared = layers.map { layer ->
            require(layer.startFrame >= 0L && layer.endFrameExclusive > layer.startFrame) { "Khoảng lớp âm thanh không hợp lệ." }
            val cacheKey = runCatching { layer.sourceWav.canonicalPath }.getOrElse { layer.sourceWav.absolutePath }
            val source = sourceCache.getOrPut(cacheKey) {
                val segment = WaveFileAssembler.inspect(layer.sourceWav)
                requirePcm16(segment)
                if (segment.sampleRate != narration.sampleRate || segment.channelCount != narration.channelCount) {
                    throw IOException("Lớp âm thanh phải cùng sample rate và số kênh với lời đọc.")
                }
                if (segment.dataLength > MAX_LAYER_BYTES) {
                    throw IOException("Một tệp âm thanh vượt giới hạn 64 MiB PCM.")
                }
                PreparedSource(
                    pcm = mapPcm(segment),
                    blockAlign = narration.blockAlign,
                )
            }
            PreparedLayer(layer, source, narration.sampleRate.toInt())
        }

        val sampleTimeline = prepared.sortedBy(PreparedLayer::sampleStartFrame)
        val duckTimeline = prepared.filterNot { it.layer.looping }.sortedBy(PreparedLayer::duckStartFrame)
        val activeSamples = ArrayList<PreparedLayer>()
        val activeDucks = ArrayList<PreparedLayer>()
        var nextSample = 0
        var nextDuck = 0

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
                        val framesInBlock = aligned / bytesPerFrame
                        val blockEndFrame = framePosition + framesInBlock

                        activeSamples.removeAll { it.layer.endFrameExclusive <= framePosition }
                        while (nextSample < sampleTimeline.size && sampleTimeline[nextSample].layer.startFrame < blockEndFrame) {
                            val candidate = sampleTimeline[nextSample++]
                            if (candidate.layer.endFrameExclusive > framePosition) activeSamples += candidate
                        }
                        activeDucks.removeAll { it.duckEndFrame <= framePosition }
                        while (nextDuck < duckTimeline.size && duckTimeline[nextDuck].duckStartFrame < blockEndFrame) {
                            val candidate = duckTimeline[nextDuck++]
                            if (candidate.duckEndFrame > framePosition) activeDucks += candidate
                        }

                        mixBlock(
                            buffer = buffer,
                            bytes = aligned,
                            bytesPerFrame = bytesPerFrame,
                            blockStartFrame = framePosition,
                            channels = narration.channelCount,
                            activeLayers = activeSamples,
                            activeDucks = activeDucks,
                        )
                        output.write(buffer, 0, aligned)
                        remaining -= aligned
                        framePosition += framesInBlock
                    }
                }
            }
            WaveFileAssembler.writeHeader(
                destination = destination,
                sampleRate = narration.sampleRate,
                channelCount = narration.channelCount,
                pcmBytes = tempData.length(),
            )
            FileOutputStream(destination, true).channel.use { out ->
                FileInputStream(tempData).channel.use { source ->
                    var position = 0L
                    while (position < source.size()) {
                        val moved = source.transferTo(position, source.size() - position, out)
                        if (moved <= 0L) throw IOException("Không thể ghép dữ liệu PCM đã mix.")
                        position += moved
                    }
                }
            }
        } finally {
            tempData.delete()
            sourceCache.values.forEach(PreparedSource::close)
        }
    }

    private class PreparedSource(
        val pcm: ByteBuffer,
        val blockAlign: Int,
    ) : AutoCloseable {
        val frames: Long = pcm.capacity().toLong() / blockAlign
        override fun close() = Unit
    }

    private class PreparedLayer(
        val layer: SceneMixLayer,
        val source: PreparedSource,
        sampleRate: Int,
    ) {
        private val sourceFrames = source.frames.coerceAtLeast(1L)
        private val requestedFadeFrames = layer.fadeFrames.coerceAtLeast(0).toLong()
        private val minimumLoopFadeFrames = sampleRate.toLong() * MIN_LOOPING_FADE_MILLIS / 1_000L
        private val maxFadeFrames = (sourceFrames / 2L).coerceAtLeast(0L)
        private val effectiveLoopFadeFrames = if (layer.looping) {
            maxOf(requestedFadeFrames, minimumLoopFadeFrames).coerceAtMost(maxFadeFrames)
        } else 0L
        private val cycleFrames = if (layer.looping && effectiveLoopFadeFrames in 1 until sourceFrames) {
            sourceFrames - effectiveLoopFadeFrames
        } else sourceFrames
        private val phaseFrames = if (layer.looping && cycleFrames > 1L) {
            Math.floorMod(stableSeed(layer), cycleFrames)
        } else 0L
        val duckStartFrame: Long = layer.startFrame
        val duckEndFrame: Long = if (layer.looping) layer.endFrameExclusive else {
            minOf(layer.endFrameExclusive, layer.startFrame + sourceFrames + sampleRate * SFX_DUCK_RELEASE_MILLIS / 1_000L)
        }

        val sampleStartFrame: Long = layer.startFrame

        fun sample(frame: Long, channel: Int, channels: Int): Double {
            val relative = frame - layer.startFrame
            if (relative < 0L || frame >= layer.endFrameExclusive) return 0.0
            if (!layer.looping && relative >= sourceFrames) return 0.0
            val sourceFrame = if (layer.looping) Math.floorMod(relative + phaseFrames, cycleFrames) else relative
            if (!layer.looping || effectiveLoopFadeFrames <= 0L || sourceFrame < cycleFrames - effectiveLoopFadeFrames) {
                return sampleAt(sourceFrame, channel, channels)
            }
            val overlapStart = cycleFrames - effectiveLoopFadeFrames
            val overlapPosition = sourceFrame - overlapStart
            val fade = overlapPosition.toDouble() / effectiveLoopFadeFrames.toDouble()
            val tailFrame = sourceFrame
            val headFrame = overlapPosition
            return sampleAt(tailFrame, channel, channels) * (1.0 - fade) +
                sampleAt(headFrame, channel, channels) * fade
        }

        private fun sampleAt(frame: Long, channel: Int, channels: Int): Double {
            val safeChannel = channel.coerceIn(0, channels - 1)
            val byteOffset = (frame * source.blockAlign + safeChannel * 2L).toInt()
            val lo = source.pcm.get(byteOffset).toInt() and 0xff
            val hi = source.pcm.get(byteOffset + 1).toInt()
            return ((hi shl 8) or lo).toShort().toDouble()
        }
    }

    private fun mixBlock(
        buffer: ByteArray,
        bytes: Int,
        bytesPerFrame: Int,
        blockStartFrame: Long,
        channels: Int,
        activeLayers: List<PreparedLayer>,
        activeDucks: List<PreparedLayer>,
    ) {
        val frames = bytes / bytesPerFrame
        for (frameOffset in 0 until frames) {
            val absoluteFrame = blockStartFrame + frameOffset
            val backgroundDuck = activeDucks.fold(1.0) { current, layer ->
                minOf(current, duckAt(absoluteFrame, layer))
            }
            for (channel in 0 until channels) {
                val offset = frameOffset * bytesPerFrame + channel * 2
                val original = (((buffer[offset + 1].toInt()) shl 8) or (buffer[offset].toInt() and 0xff)).toShort().toDouble()
                var mixed = original
                activeLayers.forEach { layer ->
                    val duck = if (layer.layer.looping) backgroundDuck else 1.0
                    mixed += layer.sample(absoluteFrame, channel, channels) * layer.layer.volume * duck
                }
                val clipped = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer[offset] = (clipped and 0xff).toByte()
                buffer[offset + 1] = ((clipped ushr 8) and 0xff).toByte()
            }
        }
    }

    private fun duckAt(frame: Long, layer: PreparedLayer): Double {
        if (frame < layer.duckStartFrame || frame >= layer.duckEndFrame) return 1.0
        val attackFrames = SFX_DUCK_ATTACK_MILLIS.coerceAtLeast(1L)
        val releaseFrames = SFX_DUCK_RELEASE_MILLIS.coerceAtLeast(1L)
        val span = (layer.duckEndFrame - layer.duckStartFrame).coerceAtLeast(1L)
        val relative = frame - layer.duckStartFrame
        val attack = minOf(1.0, relative.toDouble() / minOf(span, attackFrames).toDouble())
        val releaseStart = (span - releaseFrames).coerceAtLeast(0L)
        val release = if (relative <= releaseStart) 1.0 else {
            ((span - relative).toDouble() / (span - releaseStart).coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
        }
        val envelope = minOf(attack, release)
        return 1.0 - (1.0 - SFX_BACKGROUND_DUCK) * envelope
    }

    private fun stableSeed(layer: SceneMixLayer): Long {
        var hash = 1125899906842597L
        val key = "${layer.sourceWav.absolutePath}|${layer.startFrame}|${layer.endFrameExclusive}"
        key.forEach { char -> hash = hash * 31L + char.code }
        return hash
    }

    private fun mapPcm(segment: WaveFileAssembler.WaveInfo): ByteBuffer {
        FileInputStream(segment.file).channel.use { channel ->
            return channel.map(FileChannel.MapMode.READ_ONLY, segment.dataOffset, segment.dataLength)
        }
    }

    private fun requirePcm16(info: WaveFileAssembler.WaveInfo) {
        if (info.bitsPerSample != 16) throw IOException("Chỉ hỗ trợ PCM16 cho bộ mix âm thanh.")
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() >= 0) remaining--
            else throw EOFException("Không thể bỏ qua header WAV.")
        }
    }

    private fun readUpTo(input: BufferedInputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val read = input.read(buffer, total, wanted - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}
