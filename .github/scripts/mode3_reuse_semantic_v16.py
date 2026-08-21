from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


service = 'app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt'
requirements = 'app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoRequirements.kt'
prompt = 'app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt'
audio_test = 'app/src/test/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioTest.kt'
policy_test = 'app/src/test/java/vn/nghetruyen/app/playback/NarrationAutomaticPlanPolicyTest.kt'

# Automatic foreground/prefetch planning must never force-delete a valid in-flight/cached plan.
# planningMutex already serializes callers; force=false lets a foreground caller wait for prefetch,
# then reuse the exact completed plan instead of invoking AI/Freesound a second time.
replace_once(
    service,
    '''private data class ActiveSpeechAttempt(
    val text: String,
    val config: RuntimeVoiceConfig,
    val usedSonic: Boolean,
    val recovery: SpeechRecoveryState = SpeechRecoveryState(),
)

class ReaderPlaybackService : Service() {
''',
    '''private data class ActiveSpeechAttempt(
    val text: String,
    val config: RuntimeVoiceConfig,
    val usedSonic: Boolean,
    val recovery: SpeechRecoveryState = SpeechRecoveryState(),
)

internal object NarrationAutomaticPlanPolicy {
    // Automatic playback preparation is cache-first. Explicit user re-cast paths may still force.
    const val FORCE_REGENERATION = false
}

class ReaderPlaybackService : Service() {
'''
)

replace_once(
    service,
    '''                    runCatching {
                        if (attempt == 1) {
                            container.narrationPlanCoordinator.resetChapterNarrationState(
                                content = content,
                                clearFreesoundCaches = true,
                            )
                        }
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = true,
                            music = shouldPlanAutoSceneMusic(),
                            force = true,
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
''',
    '''                    runCatching {
                        // Do not reset/force here. If prefetch is still running, ensurePlans waits on
                        // the coordinator mutex and then reuses its completed transforms/assets.
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = true,
                            music = shouldPlanAutoSceneMusic(),
                            force = NarrationAutomaticPlanPolicy.FORCE_REGENERATION,
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
'''
)

replace_once(
    service,
    '''                val attempt = runCatching {
                    if (planVoice) {
                        container.narrationPlanCoordinator.resetChapterNarrationState(
                            content = chapter,
                            clearFreesoundCaches = true,
                        )
                    }
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        force = planVoice,
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
''',
    '''                val attempt = runCatching {
                    // Prefetch is idempotent: reuse an existing valid plan and only create missing/
                    // stale pieces. This prevents repeated prefetches from redownloading assets.
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        force = NarrationAutomaticPlanPolicy.FORCE_REGENERATION,
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
'''
)

# Semantic query sanitizer. Long files remain allowed: there is intentionally no duration change.
replace_once(
    requirements,
    '''                val query = canonicalSearchQuery(oneLine(row.optString("query")).take(MAX_QUERY_CHARS), kind)
                require(query.isNotBlank()) { "$JSON_KEY[$index] thiếu query Freesound." }
''',
    '''                val query = canonicalSearchQuery(oneLine(row.optString("query")).take(MAX_QUERY_CHARS), kind)
                // A semantically wrong-layer query is safer to omit than to download an unrelated
                // asset. Other valid requirements in the same AI response must still survive.
                if (query.isBlank()) continue
'''
)

replace_once(
    requirements,
    '''        val selected = tokens.take(MAX_QUERY_TERMS)
        if (kind == AudioAssetKind.SFX && "wind" in selected && selected.none(SFX_EVENT_TERMS::contains)) {
            return "wind gust"
        }
        return selected.joinToString(" ")
''',
    '''        val selected = tokens.take(MAX_QUERY_TERMS)
        if (kind == AudioAssetKind.MUSIC && selected.none(MUSIC_ANCHOR_TERMS::contains)) {
            // Abstract mood-only queries such as "mysterious magic" are poor music searches.
            // Add a neutral musical style anchor without imposing any duration restriction.
            return (selected.take(MAX_QUERY_TERMS - 1) + "cinematic")
                .distinct()
                .take(MAX_QUERY_TERMS)
                .joinToString(" ")
        }
        if (kind == AudioAssetKind.SFX) {
            if ("wind" in selected && selected.none(SFX_EVENT_TERMS::contains)) return "wind gust"
            if (selected.any(SFX_PERSISTENT_BED_TERMS::contains) && selected.none(SFX_EVENT_TERMS::contains)) {
                // Persistent beds belong to AMBIENCE. Do not waste a network request/download on a
                // wrong-layer SFX such as "ethereal drone" or "room tone".
                return ""
            }
        }
        return selected.joinToString(" ")
'''
)

replace_once(
    requirements,
    '''    private val SFX_EVENT_TERMS = setOf(
        "gust", "whoosh", "slash", "hit", "thud", "crash", "clash", "strike", "slam", "break",
        "burst", "snap", "drop", "knock", "creak", "step", "steps", "footstep", "footsteps", "splash",
    )
''',
    '''    private val MUSIC_ANCHOR_TERMS = setOf(
        "music", "cinematic", "orchestral", "orchestra", "score", "trailer", "ambient", "electronic",
        "classical", "folk", "rock", "jazz", "acoustic", "guzheng", "guqin", "erhu", "dizi", "koto",
        "shamisen", "flute", "piano", "violin", "cello", "harp", "strings", "drums", "percussion",
        "choir", "synth",
    )
    private val SFX_EVENT_TERMS = setOf(
        "gust", "whoosh", "slash", "hit", "thud", "crash", "clash", "strike", "slam", "break",
        "burst", "pulse", "snap", "drop", "knock", "creak", "step", "steps", "footstep", "footsteps", "splash",
        "shout", "bang", "boom", "click", "ring", "tear", "rip", "burn",
    )
    private val SFX_PERSISTENT_BED_TERMS = setOf(
        "drone", "hum", "humming", "tone", "roomtone", "room", "ambience", "ambient", "atmosphere",
        "forest", "river", "ocean", "sea", "crowd", "rain", "storm", "waterfall", "traffic",
    )
'''
)

# Strengthen the AI contract so the validator is a backstop, not the first line of defense.
replace_once(
    prompt,
    '''                appendLine("- Query MUSIC mô tả mood + nhạc cụ/phong cách nghe được, không mô tả cốt truyện. Ví dụ: tense guqin, sad flute, epic drums.")
''',
    '''                appendLine("- Query MUSIC bắt buộc có ít nhất một neo âm nhạc nghe được (nhạc cụ, dàn nhạc hoặc phong cách như guzheng, flute, strings, orchestral, cinematic); không dùng query chỉ gồm khái niệm/mood như mysterious magic hoặc light fantasy. Ví dụ: tense guqin, sad flute, epic drums.")
'''
)
replace_once(
    prompt,
    '''                appendLine("- Nguồn kéo dài như heavy wind, forest wind, steady rain thuộc AMBIENCE, không phải SFX.")
''',
    '''                appendLine("- Nguồn kéo dài như heavy wind, forest wind, steady rain, drone, hum hoặc room tone thuộc AMBIENCE, không phải SFX. Query SFX bắt buộc có một sự kiện nghe được rời rạc như hit, burst, pulse, clash, shout, splash.")
'''
)

# Extend Freesound semantic regression coverage.
p = ROOT / audio_test
text = p.read_text(encoding='utf-8')
needle = '\n}\n'
if not text.endswith(needle):
    raise SystemExit(f'{audio_test}: unexpected file ending')
extra = r'''

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
'''
p.write_text(text[:-len(needle)] + extra + needle, encoding='utf-8')

# Automatic planning policy regression test.
p = ROOT / policy_test
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text('''package vn.nghetruyen.app.playback

import org.junit.Assert.assertFalse
import org.junit.Test

class NarrationAutomaticPlanPolicyTest {
    @Test
    fun automaticForegroundAndPrefetchNeverForceRegeneration() {
        assertFalse(NarrationAutomaticPlanPolicy.FORCE_REGENERATION)
    }
}
''', encoding='utf-8')

print('Mode 3 V16 plan reuse + semantic query validation patch applied; long-file downloads remain allowed.')
