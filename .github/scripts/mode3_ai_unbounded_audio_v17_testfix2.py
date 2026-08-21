from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

music_test = 'app/src/test/java/vn/nghetruyen/app/ai/XpkSceneMusicParityTest.kt'
regression_test = 'app/src/test/java/vn/nghetruyen/app/freesound/FreesoundMode3RegressionTest.kt'

replace_once(
    music_test,
    '        assertTrue(block.instructions.contains("Ổn định quan trọng hơn phản ứng theo từng câu"))',
    '        assertTrue(block.instructions.contains("Ổn định quan trọng hơn phản ứng máy móc theo từ khóa"))',
)

replace_once(
    regression_test,
    '''    @Test
    fun parserExpandsOneUnitMusicAndAmbienceToDurableRanges() {
        val units = listOf("U1", "U2", "U3", "U4")
        val root = org.json.JSONObject(
            """{"freesound_requirements":[
              {"kind":"MUSIC","query":"tense guqin","start_id":"U2","end_id":"U2"},
              {"kind":"AMBIENCE","query":"forest wind","start_id":"U3","end_id":"U3"}
            ]}""",
        )
        val parsed = FreesoundAutoRequirementCodec.parse(root, units, setOf(AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE))
        assertTrue(units.indexOf(parsed[0].endUnitId) - units.indexOf(parsed[0].startUnitId) + 1 >= 2)
        assertTrue(units.indexOf(parsed[1].endUnitId) - units.indexOf(parsed[1].startUnitId) + 1 >= 2)
    }
''',
    '''    @Test
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
''',
)

print('Remaining V17 regression expectations updated for no artificial duration/quantity quotas.')
