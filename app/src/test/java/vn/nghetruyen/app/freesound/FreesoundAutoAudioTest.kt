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
    fun aggregatorEnforcesPerKindSearchCaps() {
        val rows = (1..30).map { index ->
            FreesoundAutoRequirement(
                kind = AudioAssetKind.SFX,
                query = "unique impact number $index",
                unitId = "P0001-U01",
            )
        }
        assertEquals(FreesoundAutoRequirementAggregator.MAX_SFX_SEARCHES, FreesoundAutoRequirementAggregator.aggregate(rows).size)
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
        val query = "close dry thunder strike"
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(query, matching, rankIndex = 4) >
                FreesoundAutoAudioResolver.scoreCandidate(query, unrelated, rankIndex = 0),
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
}
