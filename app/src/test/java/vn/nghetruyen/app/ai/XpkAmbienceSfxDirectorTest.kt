package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import vn.nghetruyen.app.audio.SfxCadence

class XpkAmbienceSfxDirectorTest {
    private val units = listOf(
        "P0001-U01",
        "P0002-U01",
        "P0003-U01",
        "P0004-U01",
        "P0005-U01",
    )

    @Test
    fun acceptsSparseAmbienceAndUnitAnchoredSfx() {
        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {
                  "ambience_scenes": [
                    {"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"rain"},
                    {"start_id":"P0004-U01","end_id":"P0005-U01","ambience_id":"forest"}
                  ],
                  "sfx_cues": [
                    {"unit_id":"P0003-U01","effect_id":"thunder"}
                  ]
                }
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = setOf("rain", "forest"),
            validSfxIds = setOf("thunder"),
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )

        assertEquals(2, plan.ambienceScenes.size)
        assertEquals("P0004-U01", plan.ambienceScenes[1].startUnitId)
        assertEquals(1, plan.soundEffectCues.size)
        assertEquals("P0003-U01", plan.soundEffectCues.single().unitId)
    }

    @Test
    fun acceptsTwoCompatibleLogicalAmbienceLayersOnSameUnits() {
        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {
                  "ambience_scenes": [
                    {"start_id":"P0001-U01","end_id":"P0004-U01","ambience_id":"rain"},
                    {"start_id":"P0001-U01","end_id":"P0004-U01","ambience_id":"forest"}
                  ],
                  "sfx_cues": []
                }
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = setOf("rain", "forest"),
            validSfxIds = emptySet(),
            ambienceEnabled = true,
            soundEffectsEnabled = false,
        )

        assertEquals(2, plan.ambienceScenes.size)
        assertEquals(setOf("rain", "forest"), plan.ambienceScenes.map { it.ambienceId }.toSet())
    }

    @Test
    fun mergesAdjacentSameAmbience() {
        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {
                  "ambience_scenes": [
                    {"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"rain"},
                    {"start_id":"P0003-U01","end_id":"P0004-U01","ambience_id":"rain"}
                  ],
                  "sfx_cues": []
                }
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = setOf("rain"),
            validSfxIds = emptySet(),
            ambienceEnabled = true,
            soundEffectsEnabled = false,
        )

        assertEquals(1, plan.ambienceScenes.size)
        assertEquals("P0001-U01", plan.ambienceScenes.single().startUnitId)
        assertEquals("P0004-U01", plan.ambienceScenes.single().endUnitId)
    }

    @Test
    fun rejectsCuesForDisabledLayers() {
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[{"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"rain"}],"sfx_cues":[]}""",
                validUnitIds = units,
                validAmbienceIds = setOf("rain"),
                validSfxIds = emptySet(),
                ambienceEnabled = false,
                soundEffectsEnabled = false,
            )
        }
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[],"sfx_cues":[{"unit_id":"P0002-U01","effect_id":"door"}]}""",
                validUnitIds = units,
                validAmbienceIds = emptySet(),
                validSfxIds = setOf("door"),
                ambienceEnabled = false,
                soundEffectsEnabled = false,
            )
        }
    }

    @Test
    fun rejectsUnknownIdsButAllowsManyAmbienceLayersAndConcurrentSfx() {
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[],"sfx_cues":[{"unit_id":"P0002-U01","effect_id":"missing"}]}""",
                validUnitIds = units,
                validAmbienceIds = emptySet(),
                validSfxIds = setOf("door"),
                ambienceEnabled = false,
                soundEffectsEnabled = true,
            )
        }
        val ambiencePlan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {"ambience_scenes":[
                  {"start_id":"P0001-U01","end_id":"P0004-U01","ambience_id":"rain"},
                  {"start_id":"P0001-U01","end_id":"P0004-U01","ambience_id":"forest"},
                  {"start_id":"P0001-U01","end_id":"P0004-U01","ambience_id":"wind"}
                ],"sfx_cues":[]}
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = setOf("rain", "forest", "wind"),
            validSfxIds = emptySet(),
            ambienceEnabled = true,
            soundEffectsEnabled = false,
        )
        assertEquals(3, ambiencePlan.ambienceScenes.size)

        val sfxPlan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {"ambience_scenes":[],"sfx_cues":[
                  {"unit_id":"P0001-U01","effect_id":"a","stop_unit_id":"P0004-U01","loop_until_stop":true},
                  {"unit_id":"P0001-U01","effect_id":"b","stop_unit_id":"P0004-U01","loop_until_stop":true},
                  {"unit_id":"P0001-U01","effect_id":"c","stop_unit_id":"P0004-U01","loop_until_stop":true},
                  {"unit_id":"P0002-U01","effect_id":"d"}
                ]}
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = emptySet(),
            validSfxIds = setOf("a", "b", "c", "d"),
            ambienceEnabled = false,
            soundEffectsEnabled = true,
        )
        assertEquals(4, sfxPlan.soundEffectCues.size)
    }

    @Test
    fun acceptsOverlapCountedRepeatAndBoundedLoop() {
        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """
                {"ambience_scenes":[],"sfx_cues":[
                  {"unit_id":"P0001-U01","effect_id":"gallop","stop_unit_id":"P0004-U01","loop_until_stop":true},
                  {"unit_id":"P0002-U01","effect_id":"neigh"},
                  {"unit_id":"P0002-U01","effect_id":"hammer","repeat_count":5,"cadence":"FAST"}
                ]}
            """.trimIndent(),
            validUnitIds = units,
            validAmbienceIds = emptySet(),
            validSfxIds = setOf("gallop", "neigh", "hammer"),
            ambienceEnabled = false,
            soundEffectsEnabled = true,
        )

        assertEquals(3, plan.soundEffectCues.size)
        assertTrue(plan.soundEffectCues[0].loopUntilStop)
        assertEquals("P0004-U01", plan.soundEffectCues[0].stopUnitId)
        assertEquals(5, plan.soundEffectCues[2].repeatCount)
        assertEquals(SfxCadence.FAST, plan.soundEffectCues[2].cadence)
    }

    @Test
    fun rejectsInvalidLoopBoundaryAndRepeatCombination() {
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[],"sfx_cues":[{"unit_id":"P0002-U01","effect_id":"gallop","loop_until_stop":true}]}""",
                validUnitIds = units,
                validAmbienceIds = emptySet(),
                validSfxIds = setOf("gallop"),
                ambienceEnabled = false,
                soundEffectsEnabled = true,
            )
        }
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[],"sfx_cues":[{"unit_id":"P0002-U01","effect_id":"gallop","stop_unit_id":"P0004-U01","loop_until_stop":true,"repeat_count":5}]}""",
                validUnitIds = units,
                validAmbienceIds = emptySet(),
                validSfxIds = setOf("gallop"),
                ambienceEnabled = false,
                soundEffectsEnabled = true,
            )
        }
    }

    @Test
    fun acceptsOneUnitAmbienceWhenAiDecidesTheSourceIsThatBrief() {
        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """{"ambience_scenes":[{"start_id":"P0002-U01","end_id":"P0002-U01","ambience_id":"rain"}],"sfx_cues":[]}""",
            validUnitIds = units,
            validAmbienceIds = setOf("rain"),
            validSfxIds = emptySet(),
            ambienceEnabled = true,
            soundEffectsEnabled = false,
        )
        assertEquals(1, plan.ambienceScenes.size)
    }

    @Test
    fun rejectsExtraJsonFields() {
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[],"sfx_cues":[],"reason":"extra"}""",
                validUnitIds = units,
                validAmbienceIds = emptySet(),
                validSfxIds = emptySet(),
                ambienceEnabled = false,
                soundEffectsEnabled = false,
            )
        }
    }

    @Test
    fun persistedCodecKeepsOnlyUnitAnchoredFields() {
        val original = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """{"ambience_scenes":[{"start_id":"P0001-U01","end_id":"P0002-U01","ambience_id":"rain"}],"sfx_cues":[{"unit_id":"P0004-U01","effect_id":"door"}]}""",
            validUnitIds = units,
            validAmbienceIds = setOf("rain"),
            validSfxIds = setOf("door"),
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )
        val encoded = XpkAmbienceSfxDirector.encode(original)
        assertTrue(encoded.contains("\"engine\":\"${XpkAmbienceSfxDirector.ENGINE}\""))
        assertTrue(!encoded.contains("volume"))
        assertTrue(!encoded.contains("time"))

        val decoded = XpkAmbienceSfxDirector.decodePersisted(
            encoded,
            units,
            setOf("rain"),
            setOf("door"),
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )
        assertEquals(original, decoded)
    }

    @Test
    fun persistedCodecKeepsAdvancedSfxFields() {
        val original = XpkAmbienceSfxDirector.parseAndValidate(
            raw = """{"ambience_scenes":[],"sfx_cues":[{"unit_id":"P0001-U01","effect_id":"gallop","stop_unit_id":"P0004-U01","loop_until_stop":true},{"unit_id":"P0002-U01","effect_id":"hammer","repeat_count":5,"cadence":"SLOW"}]}""",
            validUnitIds = units,
            validAmbienceIds = emptySet(),
            validSfxIds = setOf("gallop", "hammer"),
            ambienceEnabled = false,
            soundEffectsEnabled = true,
        )
        val encoded = XpkAmbienceSfxDirector.encode(original)
        val decoded = XpkAmbienceSfxDirector.decodePersisted(
            encoded,
            units,
            emptySet(),
            setOf("gallop", "hammer"),
            ambienceEnabled = false,
            soundEffectsEnabled = true,
        )
        assertEquals(original, decoded)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
