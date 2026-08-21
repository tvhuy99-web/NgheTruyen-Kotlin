package vn.nghetruyen.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class UnifiedAudioAssetManagerOrderingTest {
    @Test
    fun legacyEqualOrderIndexUsesOldestTimestampNotAlphabeticalTitle() {
        val older = track(id = "older", title = "Z old", orderIndex = 0, updatedAt = 100L)
        val newer = track(id = "newer", title = "A new", orderIndex = 0, updatedAt = 200L)

        assertEquals(listOf("older", "newer"), audioAssetRowsOldestFirst(listOf(newer, older)).map { it.id })
    }

    @Test
    fun appendedAssetWithHigherOrderIndexStaysAtBottom() {
        val oldest = track(id = "one", title = "One", orderIndex = 0, updatedAt = 300L)
        val middle = track(id = "two", title = "Two", orderIndex = 1, updatedAt = 100L)
        val newest = track(id = "three", title = "Three", orderIndex = 2, updatedAt = 50L)

        assertEquals(
            listOf("one", "two", "three"),
            audioAssetRowsOldestFirst(listOf(newest, middle, oldest)).map { it.id },
        )
    }

    @Test
    fun equalTimestampHasStableIdTieBreak() {
        val b = track(id = "b", title = "B", orderIndex = 0, updatedAt = 100L)
        val a = track(id = "a", title = "A", orderIndex = 0, updatedAt = 100L)

        assertEquals(listOf("a", "b"), audioAssetRowsOldestFirst(listOf(b, a)).map { it.id })
    }

    private fun track(
        id: String,
        title: String,
        orderIndex: Int,
        updatedAt: Long,
    ) = SceneMusicTrackEntity(
        id = id,
        title = title,
        uri = "file:///$id.mp3",
        tagsCsv = "type:music",
        volume = 1f,
        enabled = true,
        orderIndex = orderIndex,
        updatedAt = updatedAt,
    )
}
