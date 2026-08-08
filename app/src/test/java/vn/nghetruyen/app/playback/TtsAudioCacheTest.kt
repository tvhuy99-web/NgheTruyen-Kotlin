package vn.nghetruyen.app.playback

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsAudioCacheTest {
    @Test fun checksumDetectsTamperingAndLruRemainsBounded() {
        val dir = createTempDirectory("tts-cache-").toFile()
        try {
            val cache = TtsAudioCache(dir, TtsAudioCache.MIN_LIMIT_BYTES)
            val key = key("hello")
            val source = File(dir.parentFile, "source-${System.nanoTime()}.wav").apply { writeBytes(ByteArray(128) { 7 }) }
            cache.put(key, source)
            assertEquals(128L, cache.get(key)?.bytes)
            cache.get(key)!!.audioFile.appendBytes(byteArrayOf(9))
            assertNull(cache.get(key))
            assertTrue(cache.sizeBytes() <= TtsAudioCache.MIN_LIMIT_BYTES)
            source.delete()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun key(text: String) = TtsAudioCache.Key(
        text = text,
        enginePackage = "engine",
        voiceName = "voice",
        languageTag = "vi-VN",
        rate = 1f,
        pitch = 1f,
        volume = 1f,
        sonicSpeed = 1f,
        sonicPitch = 1f,
        pronunciationRevision = "r1",
    )
}
