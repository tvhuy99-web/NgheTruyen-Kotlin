from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Fix codec self-incompatibility: encode() includes engine, parseAndValidate() must accept it.
director = ROOT / "app/src/main/java/vn/nghetruyen/app/ai/XpkAmbienceSfxDirector.kt"
replace_once(
    director,
    '''        val keys = root.keys().asSequence().toSet()\n        require(keys == setOf("ambience_scenes", "sfx_cues")) {\n            "Kết quả audio direction nội bộ phải có ambience_scenes và sfx_cues."\n        }\n''',
    '''        val keys = root.keys().asSequence().toSet()\n        val allowedRootKeys = setOf("engine", "ambience_scenes", "sfx_cues")\n        require("ambience_scenes" in keys && "sfx_cues" in keys && keys.all { it in allowedRootKeys }) {\n            "Kết quả audio direction nội bộ phải có ambience_scenes và sfx_cues, không được có trường lạ."\n        }\n        if ("engine" in keys) {\n            require(root.optString("engine") == ENGINE) { "Audio direction dùng engine không hợp lệ." }\n        }\n''',
    "audio director root schema",
)

# 2) Make Mode-3 hashes chapter-stable. Global Freesound library growth must not invalidate
# an already-created chapter plan; referenced assets are validated separately during decode/runtime.
coordinator = ROOT / "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
replace_once(
    coordinator,
    '''            if (cached.sourceSha256 != musicSourceHash(content, musicTracks)) return false\n''',
    '''            if (cached.sourceSha256 != mode3MusicSourceHash(content)) return false\n''',
    "mode3 current music hash",
)
replace_once(
    coordinator,
    '''            val hash = audioDirectionSourceHash(\n                content,\n                ambienceTracks,\n                sfxTracks,\n                AudioAssetKind.AMBIENCE in kinds,\n                AudioAssetKind.SFX in kinds,\n            )\n''',
    '''            val hash = mode3AudioDirectionSourceHash(\n                content = content,\n                ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,\n                soundEffectsEnabled = AudioAssetKind.SFX in kinds,\n            )\n''',
    "mode3 current audio hash",
)
replace_once(
    coordinator,
    '''                sourceSha256 = musicSourceHash(content, tracks),\n''',
    '''                sourceSha256 = if (sourceMode == StoryAudioSourceMode.AI_FREESOUND) {\n                    mode3MusicSourceHash(content)\n                } else {\n                    musicSourceHash(content, tracks)\n                },\n''',
    "persist music hash",
)
replace_once(
    coordinator,
    '''                sourceSha256 = audioDirectionSourceHash(\n                    content,\n                    ambienceTracks,\n                    soundEffectTracks,\n                    ambienceEnabled,\n                    soundEffectsEnabled,\n                ),\n''',
    '''                sourceSha256 = if (sourceMode == StoryAudioSourceMode.AI_FREESOUND) {\n                    mode3AudioDirectionSourceHash(content, ambienceEnabled, soundEffectsEnabled)\n                } else {\n                    audioDirectionSourceHash(\n                        content,\n                        ambienceTracks,\n                        soundEffectTracks,\n                        ambienceEnabled,\n                        soundEffectsEnabled,\n                    )\n                },\n''',
    "persist audio hash",
)
replace_once(
    coordinator,
    '''        val sourceHash = audioDirectionSourceHash(\n            effectiveContent,\n            ambienceTracks,\n            soundEffectTracks,\n            effectiveAmbience,\n            effectiveSfx,\n        )\n''',
    '''        val sourceHash = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {\n            mode3AudioDirectionSourceHash(effectiveContent, effectiveAmbience, effectiveSfx)\n        } else {\n            audioDirectionSourceHash(\n                effectiveContent,\n                ambienceTracks,\n                soundEffectTracks,\n                effectiveAmbience,\n                effectiveSfx,\n            )\n        }\n''',
    "load audio hash",
)

# Public runtime hash entry point; ReaderPlaybackService must use the same rule as persistence.
needle = '''    suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean =\n        library.getStoryAiProfile(storyId)?.expressiveAdjustment == true\n\n'''
insert = '''    suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean =\n        library.getStoryAiProfile(storyId)?.expressiveAdjustment == true\n\n    suspend fun musicSourceHashForPlayback(\n        content: ChapterContent,\n        tracks: List<SceneMusicTrackEntity>,\n    ): String {\n        val effectiveContent = currentPlaybackContent(content)\n        return if (StoryAudioModeRouter.usesAiFreesound(storyAudioModeStore.get())) {\n            mode3MusicSourceHash(effectiveContent)\n        } else {\n            musicSourceHash(effectiveContent, tracks)\n        }\n    }\n\n'''
replace_once(coordinator, needle, insert, "runtime music hash helper")

# Insert stable Mode-3 hash helpers immediately before legacy asset-sensitive hashes.
needle = '''    private suspend fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String {\n'''
insert = '''    private fun mode3MusicSourceHash(content: ChapterContent): String = ChapterAiWorkflow.sha256(\n        canonicalParagraphs(content) + listOf(\n            "timeline=${timelineFingerprint(content)}",\n            "mode=${StoryAudioSourceMode.AI_FREESOUND.name}",\n            "engine=$MUSIC_TRANSFORM_ENGINE",\n        ),\n    )\n\n    private fun mode3AudioDirectionSourceHash(\n        content: ChapterContent,\n        ambienceEnabled: Boolean,\n        soundEffectsEnabled: Boolean,\n    ): String = ChapterAiWorkflow.sha256(\n        canonicalParagraphs(content) + listOf(\n            "timeline=${timelineFingerprint(content)}",\n            "mode=${StoryAudioSourceMode.AI_FREESOUND.name}",\n            "engine=${XpkAmbienceSfxDirector.ENGINE}",\n            "ambience=$ambienceEnabled",\n            "sfx=$soundEffectsEnabled",\n        ),\n    )\n\n    private suspend fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String {\n'''
replace_once(coordinator, needle, insert, "stable mode3 hash helpers")

# 3) Required audio must not be declared complete when plan validation produced no playable cue.
replace_once(
    coordinator,
    '''        var musicCreated = false\n        var audioCreated = false\n\n''',
    '''        var musicCreated = false\n        var audioCreated = false\n        val requiredKinds = requirements\n            .filter { it.importance == FreesoundRequirementImportance.REQUIRED }\n            .map(FreesoundAutoRequirement::kind)\n            .toSet()\n        var requiredMusicMissing = false\n        var requiredAmbienceMissing = false\n        var requiredSfxMissing = false\n\n''',
    "required coverage state",
)
replace_once(
    coordinator,
    '''            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} managedMusicTracks=${musicTracks.size}"\n''',
    '''            requiredMusicMissing = AudioAssetKind.MUSIC in requiredKinds &&\n                validated.none { it.trackId != XpkSceneMusicParity.SILENCE_TRACK_ID }\n            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} managedMusicTracks=${musicTracks.size} requiredMissing=$requiredMusicMissing"\n''',
    "required music coverage",
)
replace_once(
    coordinator,
    '''            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=${validatedAmbience.ambienceScenes.size} ambienceTracks=${ambienceTracks.size} sfxCandidates=$originalSfxCount sfxValidated=${validatedSfx.soundEffectCues.size} sfxTracks=${sfxTracks.size}"\n''',
    '''            requiredAmbienceMissing = AudioAssetKind.AMBIENCE in requiredKinds && validatedAmbience.ambienceScenes.isEmpty()\n            requiredSfxMissing = AudioAssetKind.SFX in requiredKinds && validatedSfx.soundEffectCues.isEmpty()\n            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=${validatedAmbience.ambienceScenes.size} ambienceTracks=${ambienceTracks.size} ambienceRequiredMissing=$requiredAmbienceMissing sfxCandidates=$originalSfxCount sfxValidated=${validatedSfx.soundEffectCues.size} sfxTracks=${sfxTracks.size} sfxRequiredMissing=$requiredSfxMissing"\n''',
    "required audio coverage",
)
replace_once(
    coordinator,
    '''        val retryRecommended = resolved.shouldRetryIncomplete\n        if (retryRecommended && requirements.isNotEmpty()) {\n            warnings += "Freesound chưa resolve đủ âm thanh quan trọng; ứng dụng sẽ tự thử lại mà không cần phân vai lại."\n        }\n        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} unresolved=${resolved.unresolvedCount} unresolvedRequired=${resolved.unresolvedRequiredCount} retryableFailure=${resolved.retryableFailure} retryRecommended=$retryRecommended"\n''',
    '''        val requiredPlanMissing = requiredMusicMissing || requiredAmbienceMissing || requiredSfxMissing\n        val retryRecommended = resolved.shouldRetryIncomplete || requiredPlanMissing\n        if (retryRecommended && requirements.isNotEmpty()) {\n            warnings += if (requiredPlanMissing) {\n                "Freesound đã có asset nhưng chưa tạo được cue bắt buộc hợp lệ; ứng dụng sẽ tự thử lại tối đa 3 lần."\n            } else {\n                "Freesound chưa resolve đủ âm thanh quan trọng; ứng dụng sẽ tự thử lại mà không cần phân vai lại."\n            }\n        }\n        diagnostics += "PLAN_REQUIRED_COVERAGE musicMissing=$requiredMusicMissing ambienceMissing=$requiredAmbienceMissing sfxMissing=$requiredSfxMissing"\n        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} unresolved=${resolved.unresolvedCount} unresolvedRequired=${resolved.unresolvedRequiredCount} retryableFailure=${resolved.retryableFailure} requiredPlanMissing=$requiredPlanMissing retryRecommended=$retryRecommended"\n''',
    "required plan retry",
)

# 4) Reader runtime must exclude stale/missing Mode-3 music files and use the same stable hash.
reader = ROOT / "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
replace_once(
    reader,
    '''                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||\n                        FreesoundImporter.soundIdFromManagedUri(track.uri) != null\n''',
    '''                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||\n                        (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&\n                            FreesoundImporter.managedFileExists(applicationContext, track.uri))\n''',
    "reader mode3 physical music filter",
)
replace_once(
    reader,
    '''        val musicSourceHash = originalChapter?.let { chapter ->\n            ChapterAiWorkflow.sha256(\n                chapter.paragraphs + enabledMusicTracks.flatMap { track ->\n                    listOf(track.id, track.tagsCsv, track.title)\n                },\n            )\n        }\n''',
    '''        val musicSourceHash = originalChapter?.let { chapter ->\n            container.narrationPlanCoordinator.musicSourceHashForPlayback(chapter, enabledMusicTracks)\n        }\n''',
    "reader stable music hash",
)

# 5) Regression test: the audio-direction codec must accept its own encode() output.
test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundMode3RegressionTest.kt"
text = test.read_text(encoding="utf-8")
for imp in [
    "import vn.nghetruyen.app.ai.XpkAmbienceSfxDirector\n",
    "import vn.nghetruyen.app.audio.AmbienceScene\n",
    "import vn.nghetruyen.app.audio.AmbienceSfxPlan\n",
    "import vn.nghetruyen.app.audio.SoundEffectCue\n",
]:
    if imp not in text:
        text = text.replace("import vn.nghetruyen.app.audio.AudioAssetKind\n", "import vn.nghetruyen.app.audio.AudioAssetKind\n" + imp)

method = '''\n    @Test\n    fun ambienceSfxDirectorAcceptsItsOwnEncodedPayload() {\n        val units = (1..12).map { "U$it" }\n        val plan = AmbienceSfxPlan(\n            ambienceScenes = listOf(AmbienceScene("U1", "U12", "amb-1")),\n            soundEffectCues = listOf(SoundEffectCue(unitId = "U4", effectId = "sfx-1")),\n        )\n        val parsed = XpkAmbienceSfxDirector.parseAndValidate(\n            raw = XpkAmbienceSfxDirector.encode(plan),\n            validUnitIds = units,\n            validAmbienceIds = setOf("amb-1"),\n            validSfxIds = setOf("sfx-1"),\n            ambienceEnabled = true,\n            soundEffectsEnabled = true,\n        )\n        assertEquals(1, parsed.ambienceScenes.size)\n        assertEquals(1, parsed.soundEffectCues.size)\n    }\n'''
if "ambienceSfxDirectorAcceptsItsOwnEncodedPayload" not in text:
    pos = text.rfind("}\n")
    if pos < 0:
        raise RuntimeError("test class closing brace not found")
    text = text[:pos] + method + text[pos:]
test.write_text(text, encoding="utf-8")

print("Mode 3 V10 runtime plan patch applied successfully.")
