package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class Pcm16SceneMixerTest {
    @Test
    fun mixesLoopingLayerWithSmoothBoundaryFade() {
        val root = createTempDirectory("scene-mix-test-").toFile()
        try {
            val voice = File(root, "voice.wav")
            val music = File(root, "music.wav")
            val output = File(root, "mixed.wav")
            writeConstantWave(voice, 1_000, 8_192)
            writeConstantWave(music, 2_000, 128)

            Pcm16SceneMixer.mix(
                voice,
                listOf(SceneMixLayer(music, 0, 8_192, volume = 0.25f)),
                output,
            )

            val segment = WaveFileAssembler.inspect(output)
            RandomAccessFile(output, "r").use { input ->
                input.seek(segment.dataOffset)
                val first = readSample(input)
                assertEquals(1_000, first)

                val middleFrame = 4_096L
                input.seek(segment.dataOffset + middleFrame * segment.blockAlign)
                val middle = readSample(input)
                assertEquals(1_500, middle)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oneShotSfxDucksOnlyLoopingBackgroundNotNarration() {
        val root = createTempDirectory("scene-sfx-duck-test-").toFile()
        try {
            val voice = File(root, "voice.wav")
            val ambience = File(root, "ambience.wav")
            val sfx = File(root, "sfx.wav")
            val output = File(root, "mixed.wav")
            writeConstantWave(voice, 1_000, 8_192)
            writeConstantWave(ambience, 2_000, 256)
            writeConstantWave(sfx, 0, 256)

            Pcm16SceneMixer.mix(
                voice,
                listOf(
                    SceneMixLayer(ambience, 0, 8_192, volume = 0.25f, looping = true),
                    SceneMixLayer(sfx, 4_096, 8_192, volume = 1f, fadeFrames = 0, looping = false),
                ),
                output,
            )

            val segment = WaveFileAssembler.inspect(output)
            RandomAccessFile(output, "r").use { input ->
                input.seek(segment.dataOffset + 3_000L * segment.blockAlign)
                assertEquals(1_500, readSample(input))

                input.seek(segment.dataOffset + 4_096L * segment.blockAlign)
                // Voice stays at 1000; only the 500-point ambience contribution is ducked to 72%.
                assertEquals(1_360, readSample(input))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readSample(input: RandomAccessFile): Int =
        ((input.read() and 0xff) or ((input.read() and 0xff) shl 8)).toShort().toInt()

    private fun writeConstantWave(file: File, sample: Int, frames: Int) {
        FileOutputStream(file).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLe32(36 + frames * 2)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(22_050)
            output.writeLe32(44_100)
            output.writeLe16(2)
            output.writeLe16(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLe32(frames * 2)
            repeat(frames) { output.writeLe16(sample) }
        }
    }

    private fun FileOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun FileOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
