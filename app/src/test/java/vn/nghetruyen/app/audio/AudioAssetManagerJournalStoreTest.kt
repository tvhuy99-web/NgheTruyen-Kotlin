package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioAssetManagerJournalStoreTest {
    @Test
    fun journalCodecDeduplicatesAndPreservesTransientIds() {
        val encoded = AudioAssetManagerJournalStore.encode(listOf("a", "b", "a", " ", "c"))
        val decoded = AudioAssetManagerJournalStore.decode(encoded)
        assertEquals(linkedSetOf("a", "b", "c"), decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun journalCodecRejectsUnknownVersion() {
        AudioAssetManagerJournalStore.decode("{\"version\":999,\"transient_track_ids\":[\"a\"]}")
    }
}
