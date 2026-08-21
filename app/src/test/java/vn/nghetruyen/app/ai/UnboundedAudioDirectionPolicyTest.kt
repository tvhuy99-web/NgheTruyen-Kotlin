package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.freesound.FreesoundAutoRequirement
import vn.nghetruyen.app.freesound.FreesoundAutoRequirementAggregator
import vn.nghetruyen.app.freesound.FreesoundAutoRequirementCodec

class UnboundedAudioDirectionPolicyTest {
    @Test
    fun localPlanAcceptsManyAmbienceLayersSimultaneousSfxAndExactRepeat() {
        val unit = "P0001-U01"
        val ambience = (1..5).map { "a$it" }.toSet()
        val sfx = (1..8).map { "s$it" }.toSet()
        val root = JSONObject()
        root.put("ambience_scenes", JSONArray().also { rows ->
            ambience.forEach { id ->
                rows.put(JSONObject().put("start_id", unit).put("end_id", unit).put("ambience_id", id))
            }
        })
        root.put("sfx_cues", JSONArray().also { rows ->
            sfx.forEachIndexed { index, id ->
                rows.put(
                    JSONObject()
                        .put("unit_id", unit)
                        .put("effect_id", id)
                        .put("repeat_count", if (index == 0) 25 else 1),
                )
            }
        })

        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = root.toString(),
            validUnitIds = listOf(unit),
            validAmbienceIds = ambience,
            validSfxIds = sfx,
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )
        assertEquals(5, plan.ambienceScenes.size)
        assertEquals(8, plan.soundEffectCues.size)
        assertEquals(25, plan.soundEffectCues.first().repeatCount)
    }

    @Test
    fun freesoundParserAndAggregatorDoNotApplyOldQuantityCaps() {
        val unit = "P0001-U01"
        val rows = JSONArray()
        repeat(100) { index ->
            rows.put(
                JSONObject()
                    .put("kind", "SFX")
                    .put("query", "hit code$index")
                    .put("importance", "OPTIONAL")
                    .put("unit_id", unit)
                    .put("repeat_count", if (index == 0) 40 else 1),
            )
        }
        val parsed = FreesoundAutoRequirementCodec.parse(
            root = JSONObject().put(FreesoundAutoRequirementCodec.JSON_KEY, rows),
            validUnitIds = listOf(unit),
            enabledKinds = setOf(AudioAssetKind.SFX),
        )
        assertEquals(100, parsed.size)
        assertEquals(40, parsed.first().repeatCount)
        assertEquals(100, FreesoundAutoRequirementAggregator.aggregate(parsed).size)
    }

    @Test
    fun compatibilityLimitsNoLongerClipAiAuthoredAudio() {
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_CONCURRENT_SFX)
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_SFX_REPEAT_COUNT)
        assertEquals(1, AudioDirectionLimits.MIN_AMBIENCE_SCENE_UNITS)
        assertTrue(FreesoundAutoRequirementAggregator.MAX_SFX_SEARCHES > 1_000_000)
    }
}
