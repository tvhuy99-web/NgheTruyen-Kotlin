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
        assertEquals("heavy landing thud", query)
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

    @Test
    fun parserPreservesOneUnitMusicAndAmbienceWhenAiChoosesExactBriefRanges() {
        val units = listOf("U1", "U2", "U3", "U4")
        val root = org.json.JSONObject(
            """{"freesound_requirements":[
              {"kind":"MUSIC","query":"tense guqin","start_id":"U2","end_id":"U2"},
              {"kind":"AMBIENCE","query":"forest wind","start_id":"U3","end_id":"U3"}
            ]}""",
        )
        val parsed = FreesoundAutoRequirementCodec.parse(root, units, setOf(AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE))
        assertEquals("U2", parsed[0].startUnitId)
        assertEquals("U2", parsed[0].endUnitId)
        assertEquals("U3", parsed[1].startUnitId)
        assertEquals("U3", parsed[1].endUnitId)
    }

    @Test
    fun planBuilderSortsTimelineInsteadOfDroppingValidLateAndEarlyCues() {
        val units = listOf("U1", "U2", "U3", "U4", "U5", "U6")
        val lateNeed = FreesoundAutoSearchNeed(
            AudioAssetKind.SFX, "door slam", FreesoundRequirementImportance.REQUIRED,
            listOf(FreesoundAutoRequirement(AudioAssetKind.SFX, "door slam", FreesoundRequirementImportance.REQUIRED, unitId = "U5")),
        )
        val earlyNeed = FreesoundAutoSearchNeed(
            AudioAssetKind.SFX, "sword clash", FreesoundRequirementImportance.REQUIRED,
            listOf(FreesoundAutoRequirement(AudioAssetKind.SFX, "sword clash", FreesoundRequirementImportance.REQUIRED, unitId = "U2")),
        )
        val cues = FreesoundAutoPlanBuilder.soundEffectCues(
            listOf(FreesoundAutoResolvedNeed(lateNeed, "late", "CACHE"), FreesoundAutoResolvedNeed(earlyNeed, "early", "CACHE")),
            units,
        )
        assertEquals(listOf("U2", "U5"), cues.map { it.unitId })
    }

    @Test
    fun requiredCoverageChecksEveryUsageNotJustTheKind() {
        val units = listOf("U1", "U2", "U3", "U4")
        val first = FreesoundAutoRequirement(AudioAssetKind.SFX, "sword clash", FreesoundRequirementImportance.REQUIRED, unitId = "U1")
        val second = FreesoundAutoRequirement(AudioAssetKind.SFX, "door slam", FreesoundRequirementImportance.REQUIRED, unitId = "U3")
        val resolved = listOf(
            FreesoundAutoResolvedNeed(FreesoundAutoSearchNeed(AudioAssetKind.SFX, first.query, first.importance, listOf(first)), "a", "CACHE"),
            FreesoundAutoResolvedNeed(FreesoundAutoSearchNeed(AudioAssetKind.SFX, second.query, second.importance, listOf(second)), "b", "CACHE"),
        )
        val coverage = FreesoundAutoPlanBuilder.requiredCoverage(
            resolved = resolved,
            validUnitIds = units,
            soundEffectCues = listOf(SoundEffectCue(unitId = "U1", effectId = "a")),
        )
        assertEquals(1, coverage.missingSfxUsages)
        assertFalse(coverage.complete)
    }

    @Test
    fun planBuilderPreservesExactOneUnitMusicRunChosenByAi() {
        val units = listOf("U1", "U2", "U3")
        val usage = FreesoundAutoRequirement(
            kind = AudioAssetKind.MUSIC,
            query = "tense guqin",
            importance = FreesoundRequirementImportance.REQUIRED,
            startUnitId = "U2",
            endUnitId = "U2",
        )
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.MUSIC,
            query = usage.query,
            importance = usage.importance,
            usages = listOf(usage),
        )
        val cues = FreesoundAutoPlanBuilder.musicCues(
            resolved = listOf(FreesoundAutoResolvedNeed(need, "music-1", "CACHE")),
            validUnitIds = units,
            validTrackIds = setOf("music-1"),
        )
        assertTrue(
            cues.any {
                it.trackId == "music-1" && it.startUnitId == "U2" && it.endUnitId == "U2"
            },
        )
    }

    @Test
    fun planBuilderKeepsPartiallyOverlappingSameAmbienceSoTailIsNotLost() {
        val units = listOf("U1", "U2", "U3", "U4", "U5")
        val first = FreesoundAutoRequirement(
            kind = AudioAssetKind.AMBIENCE,
            query = "snow mountain wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            startUnitId = "U1",
            endUnitId = "U3",
        )
        val second = first.copy(startUnitId = "U3", endUnitId = "U5")
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = first.query,
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = listOf(first, second),
        )
        val scenes = FreesoundAutoPlanBuilder.ambienceScenes(
            resolved = listOf(FreesoundAutoResolvedNeed(need, "amb-1", "CACHE")),
            validUnitIds = units,
        )
        assertEquals(2, scenes.size)
        assertTrue(scenes.any { it.startUnitId == "U1" && it.endUnitId == "U3" && it.ambienceId == "amb-1" })
        assertTrue(scenes.any { it.startUnitId == "U3" && it.endUnitId == "U5" && it.ambienceId == "amb-1" })
    }

    @Test
    fun authFailuresAreNotRetriedButTransientSearchFailuresAre() {
        assertFalse(FreesoundAutoAudioResolver.isRetryableSearchFailure(401))
        assertFalse(FreesoundAutoAudioResolver.isRetryableSearchFailure(403))
        assertTrue(FreesoundAutoAudioResolver.isRetryableSearchFailure(429))
        assertTrue(FreesoundAutoAudioResolver.isRetryableSearchFailure(500))
        assertTrue(FreesoundAutoAudioResolver.isRetryableSearchFailure(null))
    }
}
