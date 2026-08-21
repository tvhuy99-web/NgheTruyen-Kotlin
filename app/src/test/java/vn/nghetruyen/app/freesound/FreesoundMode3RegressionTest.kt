package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.ai.XpkAmbienceSfxDirector

class FreesoundMode3RegressionTest {
    @Test
    fun resolveResultCarriesRetryableFailureWithoutPretendingSilenceIsFinal() {
        val result = FreesoundAutoResolveResult(
            resolved = emptyList(),
            warnings = listOf("network"),
            importedTrackIds = emptySet(),
            retryableFailure = true,
        )
        assertTrue(result.retryableFailure)
        assertEquals(0, result.resolvedCount)
    }

    @Test
    fun unresolvedRequiredNeedForcesRetryEvenWithoutNetworkFailure() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "thunder storm",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val result = FreesoundAutoResolveResult(
            resolved = listOf(FreesoundAutoResolvedNeed(need, null, "UNRESOLVED")),
            warnings = emptyList(),
            importedTrackIds = emptySet(),
            retryableFailure = false,
        )
        assertEquals(0, result.resolvedCount)
        assertEquals(1, result.unresolvedRequiredCount)
        assertTrue(result.shouldRetryIncomplete)
    }

    @Test
    fun fullyResolvedRequiredNeedDoesNotForceRetry() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.SFX,
            query = "sword hit",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val result = FreesoundAutoResolveResult(
            resolved = listOf(FreesoundAutoResolvedNeed(need, "track-1", "CACHE")),
            warnings = emptyList(),
            importedTrackIds = emptySet(),
            retryableFailure = false,
        )
        assertEquals(1, result.resolvedCount)
        assertEquals(0, result.unresolvedRequiredCount)
        assertFalse(result.shouldRetryIncomplete)
    }

    @Test
    fun preferredPreviewUsesRealMp3ThenRealOgg() {
        val both = FreesoundSound(
            id = 1,
            name = "test",
            description = "",
            durationSeconds = 1.0,
            previewHqMp3 = "https://cdn.example/a.mp3",
            previewHqOgg = "https://cdn.example/a.ogg",
        )
        assertEquals("https://cdn.example/a.mp3", both.preferredPreviewUrl)

        val oggOnly = both.copy(previewHqMp3 = null)
        assertEquals("https://cdn.example/a.ogg", oggOnly.preferredPreviewUrl)
        assertFalse(FreesoundPreviewPlayer.previewCandidates(oggOnly.preferredPreviewUrl!!).isEmpty())
    }

    @Test
    fun ambienceSfxDirectorAcceptsItsOwnEncodedPayload() {
        val units = (1..12).map { "U$it" }
        val plan = AmbienceSfxPlan(
            ambienceScenes = listOf(AmbienceScene("U1", "U12", "amb-1")),
            soundEffectCues = listOf(SoundEffectCue(unitId = "U4", effectId = "sfx-1")),
        )
        val parsed = XpkAmbienceSfxDirector.parseAndValidate(
            raw = XpkAmbienceSfxDirector.encode(plan),
            validUnitIds = units,
            validAmbienceIds = setOf("amb-1"),
            validSfxIds = setOf("sfx-1"),
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )
        assertEquals(1, parsed.ambienceScenes.size)
        assertEquals(1, parsed.soundEffectCues.size)
    }

    @Test
    fun canonicalSearchQueryCapsProseToThreeUsefulTerms() {
        val query = FreesoundAutoRequirementCodec.canonicalSearchQuery(
            "heavy landing thud on wood",
            AudioAssetKind.SFX,
        )
        assertEquals("landing thud wood", query)
        assertTrue(query.split(' ').size <= 3)
    }

    @Test
    fun aggregatorPrefersShorterEquivalentQuery() {
        val long = FreesoundAutoRequirement(
            kind = AudioAssetKind.SFX,
            query = "metal debris wall stone crash",
            unitId = "U1",
        )
        val short = long.copy(query = "debris wall stone crash", unitId = "U2")
        val need = FreesoundAutoRequirementAggregator.aggregate(listOf(long, short)).single()
        assertEquals("debris wall stone crash", need.query)
    }

    @Test
    fun vagueContinuousWindIsNotKeptAsAnSfxQuery() {
        assertEquals(
            "wind gust",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("heavy wind", AudioAssetKind.SFX),
        )
        assertEquals(
            "heavy wind",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("heavy wind", AudioAssetKind.AMBIENCE),
        )
        assertEquals(
            "wind whoosh",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("wind whoosh", AudioAssetKind.SFX),
        )
    }
}
