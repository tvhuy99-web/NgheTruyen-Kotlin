package vn.nghetruyen.app.freesound

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.ai.AiLineProtocol
import vn.nghetruyen.app.ai.NarrationPlanRequest
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.StoryAudioModeRouter
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.audio.SfxCadence

class FreesoundAutoAudioTest {
    @Test
    fun threeModesStayMutuallyExclusiveAndDefaultRequestDoesNotEnableFreesound() {
        assertTrue(StoryAudioModeRouter.usesManualLocal(StoryAudioSourceMode.LOCAL_MANUAL))
        assertTrue(StoryAudioModeRouter.usesAiLocal(StoryAudioSourceMode.AI_LOCAL))
        assertTrue(StoryAudioModeRouter.usesAiFreesound(StoryAudioSourceMode.AI_FREESOUND))
        assertFalse(StoryAudioModeRouter.usesAiLocal(StoryAudioSourceMode.AI_FREESOUND))
        assertFalse(
            NarrationPlanRequest(
                storyId = "s",
                chapterId = "c",
                rawText = "text",
            ).includeFreesoundAudioRequirements,
        )
    }

    @Test
    fun sameUnifiedJsonParsesVoiceIndependentFreesoundNeeds() {
        val raw = """
            {
              "freesound_requirements": [
                {"kind":"AMBIENCE","query":"night forest wind ambience","importance":"REQUIRED","start_id":"P0001-U01","end_id":"P0002-U01"},
                {"kind":"SFX","query":"close dry thunder strike","importance":"OPTIONAL","unit_id":"P0002-U01"}
              ]
            }
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = emptyList(),
                includeVoiceCast = false,
                includeFreesoundAudioRequirements = true,
                freesoundRequirementKinds = setOf(AudioAssetKind.AMBIENCE, AudioAssetKind.SFX),
            ),
        )
        assertEquals(2, plan.freesoundRequirements.size)
        assertTrue(plan.freesoundRequirementError.isBlank())
        assertEquals(AudioAssetKind.AMBIENCE, plan.freesoundRequirements.first().kind)
    }

    @Test
    fun parserRejectsARequirementForDisabledKindWithoutBreakingWholeJsonParser() {
        val raw = """
            {"freesound_requirements":[{"kind":"MUSIC","query":"dark tension music","start_id":"P0001-U01","end_id":"P0002-U01"}]}
        """.trimIndent()
        val plan = AiLineProtocol.parseXpkNarration(
            raw,
            AiLineProtocol.XpkParseOptions(
                validDialogueIds = emptyList(),
                validUnitIds = listOf("P0001-U01", "P0002-U01"),
                validVoiceIds = emptyList(),
                includeVoiceCast = false,
                includeFreesoundAudioRequirements = true,
                freesoundRequirementKinds = setOf(AudioAssetKind.AMBIENCE),
            ),
        )
        assertTrue(plan.freesoundRequirements.isEmpty())
        assertTrue(plan.freesoundRequirementError.isNotBlank())
    }

    @Test
    fun repeatedSimilarNeedsShareOneSearchButKeepEveryUsage() {
        val requirements = listOf(
            FreesoundAutoRequirement(
                kind = AudioAssetKind.SFX,
                query = "close sword clash sound effect",
                unitId = "P0001-U01",
            ),
            FreesoundAutoRequirement(
                kind = AudioAssetKind.SFX,
                query = "sword clash close sound effect",
                unitId = "P0003-U01",
                repeatCount = 3,
                cadence = SfxCadence.FAST,
            ),
        )
        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        assertEquals(1, needs.size)
        assertEquals(2, needs.single().usages.size)
    }

    @Test
    fun aggregatorKeepsAllDistinctAiDirectedNeedsWithoutPerKindQuota() {
        val rows = (1..30).map { index ->
            FreesoundAutoRequirement(
                kind = AudioAssetKind.SFX,
                query = "hit code$index",
                unitId = "P0001-U01",
            )
        }
        assertEquals(30, FreesoundAutoRequirementAggregator.aggregate(rows).size)
    }

    @Test
    fun candidateRankingPrefersQueryMatchOverUnrelatedFirstResult() {
        val unrelated = FreesoundSound(
            id = 1,
            name = "soft birds",
            description = "small birds morning",
            durationSeconds = 2.0,
            previewHqMp3 = "https://cdn.example/1.mp3",
            previewHqOgg = null,
        )
        val matching = FreesoundSound(
            id = 2,
            name = "Close Dry Thunder Strike",
            description = "powerful thunder impact",
            durationSeconds = 3.0,
            previewHqMp3 = "https://cdn.example/2.mp3",
            previewHqOgg = null,
        )
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.SFX,
            query = "close dry thunder strike",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, matching.copy(category = "Sound effects"), rankIndex = 4) >
                FreesoundAutoAudioResolver.scoreCandidate(need, unrelated.copy(category = "Sound effects"), rankIndex = 0),
        )
    }

    @Test
    fun candidateRankingPrefersCorrectTaxonomyAndUsefulQualityMetadata() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "forest wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val base = FreesoundSound(
            id = 1, name = "forest wind", description = "forest wind", durationSeconds = 60.0,
            previewHqMp3 = "https://cdn.example/a.mp3", previewHqOgg = null, tags = listOf("forest", "wind"),
        )
        val soundscape = base.copy(category = "Soundscapes", avgRating = 4.8, numRatings = 20, numDownloads = 5000)
        val wrong = base.copy(id = 2, category = "Music", avgRating = 5.0, numRatings = 100, numDownloads = 100000)
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, soundscape, 2) >
                FreesoundAutoAudioResolver.scoreCandidate(need, wrong, 0),
        )
    }

    @Test
    fun relaxedSearchStillRejectsAWeakGenericSemanticMatch() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "snow mountain wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val weak = FreesoundSound(
            id = 77,
            name = "wind",
            description = "strong wind",
            durationSeconds = 60.0,
            previewHqMp3 = "https://cdn.example/wind.mp3",
            previewHqOgg = null,
            category = "Soundscapes",
            tags = listOf("wind"),
            avgRating = 5.0,
            numRatings = 100,
            numDownloads = 100000,
        )
        val strong = weak.copy(
            id = 78,
            name = "snow mountain wind",
            description = "cold wind on snowy mountain",
            tags = listOf("snow", "mountain", "wind"),
        )
        assertFalse(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, weak))
        assertTrue(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, strong))
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, strong, 4) >
                FreesoundAutoAudioResolver.scoreCandidate(need, weak, 0),
        )
    }

    @Test
    fun requirementCachePayloadContainsNoRightsMetadataContract() {
        val requirement = FreesoundAutoRequirement(
            kind = AudioAssetKind.AMBIENCE,
            query = "mountain storm ambience",
            startUnitId = "P0001-U01",
            endUnitId = "P0002-U01",
        )
        val payload = JSONObject().put(
            FreesoundAutoRequirementCodec.JSON_KEY,
            FreesoundAutoRequirementCodec.toJson(listOf(requirement)),
        ).toString()
        assertFalse(payload.contains("license", ignoreCase = true))
        assertFalse(payload.contains("username", ignoreCase = true))
        assertFalse(payload.contains("author", ignoreCase = true))
    }

    @Test
    fun vagueMusicQueriesReceiveARealMusicStyleAnchor() {
        assertEquals(
            "mysterious magic cinematic",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("mysterious magic", AudioAssetKind.MUSIC),
        )
        assertEquals(
            "light fantasy cinematic",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("light fantasy", AudioAssetKind.MUSIC),
        )
        assertEquals(
            "fantasy orchestral",
            FreesoundAutoRequirementCodec.canonicalSearchQuery("fantasy orchestral", AudioAssetKind.MUSIC),
        )
    }

    @Test
    fun persistentBedsAreNotDownloadedAsOneShotSfxAndValidRowsSurvive() {
        assertEquals("", FreesoundAutoRequirementCodec.canonicalSearchQuery("ethereal drone", AudioAssetKind.SFX))
        assertEquals("wind gust", FreesoundAutoRequirementCodec.canonicalSearchQuery("forest wind", AudioAssetKind.SFX))

        val root = JSONObject(
            """{"freesound_requirements":[
                {"kind":"SFX","query":"ethereal drone","importance":"OPTIONAL","unit_id":"P0001-U01"},
                {"kind":"SFX","query":"paper burn","importance":"REQUIRED","unit_id":"P0002-U01"}
            ]}""",
        )
        val parsed = FreesoundAutoRequirementCodec.parse(
            root = root,
            validUnitIds = listOf("P0001-U01", "P0002-U01"),
            enabledKinds = setOf(AudioAssetKind.SFX),
        )
        assertEquals(1, parsed.size)
        assertEquals("paper burn", parsed.single().query)
    }

    @Test
    fun resolveResultCountsUniqueDownloadedAndReusedTracks() {
        val needA = FreesoundAutoSearchNeed(
            kind = vn.nghetruyen.app.audio.AudioAssetKind.AMBIENCE,
            query = "snow mountain wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val needB = FreesoundAutoSearchNeed(
            kind = vn.nghetruyen.app.audio.AudioAssetKind.SFX,
            query = "sword clash",
            importance = FreesoundRequirementImportance.OPTIONAL,
            usages = emptyList(),
        )
        val result = FreesoundAutoResolveResult(
            resolved = listOf(
                FreesoundAutoResolvedNeed(needA, "track-new", "FREESOUND"),
                FreesoundAutoResolvedNeed(needA, "track-new", "FREESOUND"),
                FreesoundAutoResolvedNeed(needB, "track-cache", "CACHE"),
            ),
            warnings = emptyList(),
            importedTrackIds = setOf("track-new"),
        )
        assertEquals(setOf("track-new", "track-cache"), result.resolvedTrackIds)
        assertEquals(1, result.downloadedTrackCount)
        assertEquals(1, result.reusedTrackCount)
    }

}
