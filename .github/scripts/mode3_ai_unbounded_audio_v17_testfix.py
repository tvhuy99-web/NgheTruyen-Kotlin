from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:100]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


director_test = 'app/src/test/java/vn/nghetruyen/app/ai/XpkAmbienceSfxDirectorTest.kt'
music_test = 'app/src/test/java/vn/nghetruyen/app/ai/XpkSceneMusicParityTest.kt'
prompt_test = 'app/src/test/java/vn/nghetruyen/app/ai/XpkAudioPromptQualityTest.kt'
freesound_test = 'app/src/test/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioTest.kt'

replace_once(
    director_test,
    '''    @Test
    fun rejectsUnknownIdsThirdAmbienceLayerAndFourthConcurrentSfx() {
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
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
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
        }
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
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
        }
    }
''',
    '''    @Test
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
''',
)
replace_once(
    director_test,
    '''    @Test
    fun rejectsOneUnitAmbienceFlicker() {
        assertFails {
            XpkAmbienceSfxDirector.parseAndValidate(
                raw = """{"ambience_scenes":[{"start_id":"P0002-U01","end_id":"P0002-U01","ambience_id":"rain"}],"sfx_cues":[]}""",
                validUnitIds = units,
                validAmbienceIds = setOf("rain"),
                validSfxIds = emptySet(),
                ambienceEnabled = true,
                soundEffectsEnabled = false,
            )
        }
    }
''',
    '''    @Test
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
''',
)

replace_once(
    music_test,
    '''    @Test
    fun validatorRejectsOneUnitMiddleSceneFlicker() {
        val units = (1..5).map { "P${it.toString().padStart(4, '0')}-U01" }
        assertFails {
            XpkSceneMusicParity.validateScenes(
                listOf(
                    XpkSceneMusicParity.RawScene(units[0], units[1], "a"),
                    XpkSceneMusicParity.RawScene(units[2], units[2], "b"),
                    XpkSceneMusicParity.RawScene(units[3], units[4], "a"),
                ),
                units,
                listOf("a", "b"),
            )
        }
    }
''',
    '''    @Test
    fun validatorAllowsOneUnitMusicSceneWhileKeepingOneTrackAtATime() {
        val units = (1..5).map { "P${it.toString().padStart(4, '0')}-U01" }
        val scenes = XpkSceneMusicParity.validateScenes(
            listOf(
                XpkSceneMusicParity.RawScene(units[0], units[1], "a"),
                XpkSceneMusicParity.RawScene(units[2], units[2], "b"),
                XpkSceneMusicParity.RawScene(units[3], units[4], "a"),
            ),
            units,
            listOf("a", "b"),
        )
        assertEquals(3, scenes.size)
        assertEquals("b", scenes[1].trackId)
    }
''',
)

replace_once(
    prompt_test,
    '        assertTrue(prompt.contains("MAX_SFX_CUES_THIS_CHAPTER chỉ là TRẦN an toàn"))',
    '        assertTrue(prompt.contains("Không có trần số SFX trong chương"))',
)
replace_once(
    prompt_test,
    '        assertTrue(prompt.contains("không tạo một lớp chỉ cho một UNIT"))',
    '        assertTrue(prompt.contains("Không có độ dài tối thiểu cho AMBIENCE"))',
)

replace_once(
    freesound_test,
    '''    @Test
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
''',
    '''    @Test
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
''',
)

print('V17 regression tests updated for AI-directed unbounded AMBIENCE/SFX and one-at-a-time MUSIC.')
