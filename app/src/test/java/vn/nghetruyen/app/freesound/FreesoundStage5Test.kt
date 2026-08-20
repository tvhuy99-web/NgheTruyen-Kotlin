package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundStage5Test {
    @Test
    fun similarSearchUsesOfficialSoundEndpointAndKeepsTokenOutOfUrl() {
        val url = FreesoundClient.buildSimilarUrl(
            soundId = 80408,
            request = FreesoundSearchRequest(
                query = "",
                category = FreesoundCategory.AMBIENCE,
                duration = FreesoundDuration.RECOMMENDED,
                page = 2,
                pageSize = 20,
            ),
        )

        assertEquals("/apiv2/sounds/80408/similar/", url.encodedPath)
        assertEquals("duration:[10 TO 300]", url.queryParameter("filter"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("page_size"))
        assertNull(url.queryParameter("query"))
        assertNull(url.queryParameter("sort"))
        assertNull(url.queryParameter("token"))
        val fields = url.queryParameter("fields").orEmpty()
        assertTrue(fields.contains("name"))
        assertTrue(fields.contains("description"))
        assertTrue(fields.contains("duration"))
        assertTrue(fields.contains("previews"))
        assertFalse(fields.contains("license"))
        assertFalse(fields.contains("username"))
    }

    @Test
    fun recentQueriesAreDeduplicatedCaseInsensitivelyAndNewestFirst() {
        val merged = FreesoundSearchPreferences.mergeRecentQueries(
            query = " Thunder ",
            previous = listOf("rain", "thunder", "forest"),
        )

        assertEquals(listOf("Thunder", "rain", "forest"), merged)
    }

    @Test
    fun recentQueriesAreLimitedToEight() {
        val previous = (1..20).map { "query-$it" }
        val merged = FreesoundSearchPreferences.mergeRecentQueries("new", previous)

        assertEquals(FreesoundSearchPreferences.MAX_RECENT_QUERIES, merged.size)
        assertEquals("new", merged.first())
        assertEquals("query-1", merged[1])
    }

    @Test
    fun eachManagedAudioKindHasUsefulPresets() {
        assertTrue(FreesoundSearchPreferences.presets(FreesoundCategory.MUSIC).size >= 6)
        assertTrue(FreesoundSearchPreferences.presets(FreesoundCategory.AMBIENCE).size >= 6)
        assertTrue(FreesoundSearchPreferences.presets(FreesoundCategory.SFX).size >= 6)
        assertTrue(FreesoundSearchPreferences.presets(FreesoundCategory.ALL).isEmpty())
    }
}
