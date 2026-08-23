package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class DescriptionFirstMatchingTest {
    @Test
    fun localLibraryIgnoresTitleAndUsesDescription() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.SFX,
            query = "sword clash",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val titleOnly = track(
            id = "title-only",
            title = "sword clash",
            description = "type:sfx, gentle rain on roof",
        )
        val descriptionMatch = track(
            id = "description-match",
            title = "random file 001",
            description = "type:sfx, sword clash metal impact",
        )

        assertNull(Mode3LibraryAssetMatcher.evaluate(need, listOf(titleOnly), 0L).accepted)
        assertEquals(
            "description-match",
            Mode3LibraryAssetMatcher.evaluate(need, listOf(descriptionMatch), 0L).accepted?.track?.id,
        )
    }

    @Test
    fun remoteUsesAllThreeWithDescriptionStrongest() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "forest wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val base = FreesoundSound(
            id = 1,
            name = "misc recording",
            description = "personal uploader note",
            durationSeconds = 60.0,
            previewHqMp3 = "https://cdn.example/a.mp3",
            previewHqOgg = null,
            category = "Soundscapes",
        )
        val byDescription = base.copy(id = 10, description = "forest wind")
        val byName = base.copy(id = 11, name = "forest wind")
        val byTags = base.copy(id = 12, tags = listOf("forest", "wind"))

        val descriptionCoverage = FreesoundAutoAudioResolver.candidateLexicalCoverage(need, byDescription)
        val nameCoverage = FreesoundAutoAudioResolver.candidateLexicalCoverage(need, byName)
        val tagCoverage = FreesoundAutoAudioResolver.candidateLexicalCoverage(need, byTags)

        assertTrue(descriptionCoverage > nameCoverage)
        assertTrue(nameCoverage > tagCoverage)
        assertTrue(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, byDescription))
        assertTrue(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, byName))
        assertTrue(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, byTags))
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, byDescription, 0) >
                FreesoundAutoAudioResolver.scoreCandidate(need, byName, 0),
        )
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, byName, 0) >
                FreesoundAutoAudioResolver.scoreCandidate(need, byTags, 0),
        )
    }

    private fun track(
        id: String,
        title: String,
        description: String,
    ) = SceneMusicTrackEntity(
        id = id,
        title = title,
        uri = "file:///tmp/$id.mp3",
        tagsCsv = description,
        volume = 1.0f,
        enabled = true,
        updatedAt = 1L,
    )
}
