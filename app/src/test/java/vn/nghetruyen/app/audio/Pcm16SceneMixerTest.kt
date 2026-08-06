package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class Pcm16SceneMixerTest {
    @Test
    fun mixesLoopingLayerWithoutLoadingNarrationIntoMemory() {
        val root = createTempDir(prefix = "scene-mix-test-")
        try {
            val voice = File(root, "voice.wav")
            val music = File(root, "music.wav")
            val output = File(root, "mixed.wav")
            writeConstantWave(voice, 1_000, 4_096)
            writeConstantWave(music, 2_000, 128)

            Pcm16SceneMixer.mix(
                voice,
                listOf(SceneMixLayer(music, 0, 4_096, volume = 0.25f)),
                output,
            )

            val segment = WaveFileAssembler.inspect(output)
            RandomAccessFile(output, "r").use { input ->
                input.seek(segment.dataOffset)
                val value = ((input.read() and 0xff) or ((input.read() and 0xff) shl 8)).toShort().toInt()
                assertEquals(1_500, value)
            }
        } finally {
            root.deleteRecursively()
        }
    }

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
