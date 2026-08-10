package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

class SonicPcmProcessorTest {
    @Test
    fun speedChangesDurationWhileKeepingValidPcm16Wave() {
        val root = createTempDirectory("sonic-test-").toFile()
        val previousGain = ReferenceSonicRuntime.outputGain
        try {
            ReferenceSonicRuntime.outputGain = 1f
            val input = File(root, "input.wav")
            val output = File(root, "output.wav")
            writeSineWave(input, sampleRate = 22_050, frames = 22_050, frequencyHz = 440.0)
            val processed = SonicPcmProcessor.process(input, output, speed = 2f, pitch = 1f)
            assertEquals(1, processed.audioFormat)
            assertEquals(16, processed.bitsPerSample)
            val frames = processed.dataLength / processed.blockAlign
            assertTrue("2x speed should be close to half duration, got $frames frames", frames in 10_000L..12_000L)
        } finally {
            ReferenceSonicRuntime.outputGain = previousGain
            root.deleteRecursively()
        }
    }

    @Test
    fun pitchChangesFrequencyWithoutChangingDuration() {
        val root = createTempDirectory("sonic-pitch-test-").toFile()
        val previousGain = ReferenceSonicRuntime.outputGain
        try {
            ReferenceSonicRuntime.outputGain = 1f
            val sampleRate = 22_050
            val input = File(root, "input.wav")
            val output = File(root, "output.wav")
            writeSineWave(input, sampleRate = sampleRate, frames = sampleRate * 2, frequencyHz = 440.0)

            val processed = SonicPcmProcessor.process(
                input,
                output,
                speed = 1f,
                pitch = 1.5f,
                accurate = true,
            )
            val frames = processed.dataLength / processed.blockAlign
            assertTrue("Pitch-only processing should preserve duration, got $frames frames", frames in 42_000L..46_000L)

            val samples = readMonoPcm16(processed)
            val frequency = estimateFrequency(samples, sampleRate)
            assertTrue("Expected pitch near 660 Hz, got $frequency Hz", frequency in 610.0..710.0)
        } finally {
            ReferenceSonicRuntime.outputGain = previousGain
            root.deleteRecursively()
        }
    }

    private fun writeSineWave(file: File, sampleRate: Int, frames: Int, frequencyHz: Double) {
        FileOutputStream(file).use { output ->
            fun le16(value: Int) {
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            }
            fun le32(value: Int) {
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
                output.write((value ushr 16) and 0xff)
                output.write((value ushr 24) and 0xff)
            }
            output.write("RIFF".toByteArray())
            le32(36 + frames * 2)
            output.write("WAVEfmt ".toByteArray())
            le32(16)
            le16(1)
            le16(1)
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)
            le16(16)
            output.write("data".toByteArray())
            le32(frames * 2)
            repeat(frames) { index ->
                le16((sin(2.0 * PI * frequencyHz * index / sampleRate) * 9_000).toInt())
            }
        }
    }

    private fun readMonoPcm16(wave: WaveSegment): ShortArray {
        val bytes = ByteArray(wave.dataLength.toInt())
        BufferedInputStream(FileInputStream(wave.file)).use { input ->
            var remaining = wave.dataOffset
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) remaining -= skipped else {
                    check(input.read() >= 0)
                    remaining--
                }
            }
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count >= 0)
                offset += count
            }
        }
        return ShortArray(bytes.size / 2) { index ->
            val offset = index * 2
            ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()
        }
    }

    private fun estimateFrequency(samples: ShortArray, sampleRate: Int): Double {
        val start = (sampleRate / 4).coerceAtMost(samples.size / 4)
        val end = (samples.size - sampleRate / 4).coerceAtLeast(start + 2)
        var crossings = 0
        for (index in start + 1 until end) {
            if (samples[index - 1] <= 0 && samples[index] > 0) crossings++
        }
        val seconds = (end - start).toDouble() / sampleRate
        return crossings / seconds
    }
}
