package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class FreesoundStage6Test {
    @Test
    fun keywordPlanParsesJsonFencesAndDeduplicatesQueries() {
        val raw = """
            ```json
            {
              "queries": [
                {"query":"forest night ambience","reason":"Rừng đêm xuất hiện nhiều lần"},
                {"query":"Forest Night Ambience","reason":"trùng"},
                {"query":"thunder strike","reason":"Có sấm gần"}
              ]
            }
            ```
        """.trimIndent()

        val plan = FreesoundAiAssistant.parseKeywordPlan(raw, "GEMINI", "gemini-test")
        assertEquals("GEMINI", plan.provider)
        assertEquals("gemini-test", plan.model)
        assertEquals(2, plan.suggestions.size)
        assertEquals("forest night ambience", plan.suggestions.first().query)
    }

    @Test
    fun semanticPlanKeepsShortUniqueEnglishQueries() {
        val plan = FreesoundAiAssistant.parseSemanticPlan(
            "{\"queries\":[\"heavy thunder strike\",\"Heavy Thunder Strike\",\"violent thunder\"]}",
        )
        assertEquals(listOf("heavy thunder strike", "violent thunder"), plan.queries)
    }

    @Test
    fun vietnameseAndEnglishTextNormalizeForCoverageComparison() {
        assertEquals("tieng thunder rat lon", FreesoundLibraryAnalyzer.normalize("Tiếng sấm rất lớn"))
        assertTrue(FreesoundLibraryAnalyzer.tokens("forest night ambience").contains("forest"))
        assertTrue(FreesoundLibraryAnalyzer.tokens("mưa trong rừng").containsAll(setOf("rain", "forest")))
        assertTrue(FreesoundLibraryAnalyzer.jaccard(setOf("rain", "storm"), setOf("rain", "storm")) > 0.99)
    }

    @Test
    fun localSemanticFallbackUsesVietnameseNormalizationAndCurrentKindOnly() {
        val ambience = FreesoundSemanticSearchEngine.fallbackQueries(
            "mưa lớn trong rừng ban đêm",
            AudioAssetKind.AMBIENCE,
        )
        assertTrue(ambience.isNotEmpty())
        assertTrue(ambience.first().contains("rain"))
        assertTrue(ambience.first().contains("forest"))
        assertTrue(ambience.first().contains("ambience"))
        assertFalse(ambience.any { it.contains("music") || it.contains("sound effect") })

        val sfx = FreesoundSemanticSearchEngine.fallbackQueries("tiếng sấm rất lớn", AudioAssetKind.SFX)
        assertTrue(sfx.first().contains("thunder"))
        assertTrue(sfx.first().contains("sound effect"))
        assertFalse(sfx.any { it.contains("ambience") || it.contains("music") })
    }

    @Test
    fun exactTitleDuplicatesAreDetected() {
        val tracks = listOf(
            track("a", "Thunder Strike", "type:sfx, loud close thunder"),
            track("b", "Thunder Strike.wav", "type:sfx, loud close thunder"),
            track("c", "Sword Clash", "type:sfx, sword metal impact"),
        )
        val duplicates = FreesoundLibraryAnalyzer.findNearDuplicates(tracks)
        assertTrue(duplicates.any { setOf(it.firstTrackId, it.secondTrackId) == setOf("a", "b") })
        assertFalse(duplicates.any { setOf(it.firstTrackId, it.secondTrackId) == setOf("a", "c") })
    }

    @Test
    fun exactHashScanSkipsFilesWithUniqueKnownSizes() {
        val selected = FreesoundExactDuplicateAnalyzer.candidateSizesToHash(
            listOf(10L, 20L, 20L, 30L, -1L, 40L),
        )
        assertEquals(setOf(20L, -1L), selected)
    }

    @Test
    fun sparseLibraryReportsCoverageGaps() {
        val tracks = listOf(track("a", "Mưa trong rừng", "type:ambience, mưa đều trong rừng"))
        val gaps = FreesoundLibraryAnalyzer.findMissingTopics(AudioAssetKind.AMBIENCE, tracks)
        assertTrue(gaps.isNotEmpty())
        assertFalse(gaps.any { it.query == "rain ambience" })
        assertFalse(gaps.any { it.query == "forest ambience" })
        assertTrue(gaps.any { it.query == "cave ambience" })
    }

    private fun track(id: String, title: String, tags: String): SceneMusicTrackEntity = SceneMusicTrackEntity(
        id = id,
        title = title,
        uri = "content://test/$id",
        tagsCsv = tags,
        volume = 1f,
        enabled = true,
        updatedAt = 1L,
    )
}
