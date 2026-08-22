#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java"


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(path: str, *needles: str) -> None:
    source = text(path)
    missing = [needle for needle in needles if needle not in source]
    if missing:
        raise SystemExit(f"XPK_STRICT_PARITY missing in {path}: {missing}")


def forbid(path: str, *needles: str) -> None:
    source = text(path)
    present = [needle for needle in needles if needle in source]
    if present:
        raise SystemExit(f"XPK_STRICT_PARITY forbidden in {path}: {present}")


def forbid_production_legacy_narration() -> None:
    allowed_direct_service = {
        Path("vn/nghetruyen/app/ai/OnlineAiServices.kt"),
        Path("vn/nghetruyen/app/ai/OnlineTextAiServices.kt"),
    }
    violations: list[str] = []
    for file in MAIN.rglob("*.kt"):
        rel = file.relative_to(MAIN)
        source = file.read_text(encoding="utf-8")
        if "OnlineAiServices(" in source and rel not in allowed_direct_service:
            violations.append(f"{rel}: direct OnlineAiServices construction")
        if ".aiServices.planVoiceCast(" in source:
            violations.append(f"{rel}: aiServices.planVoiceCast")
        if ".aiServices.planMusic(" in source:
            violations.append(f"{rel}: aiServices.planMusic")
        if ".aiServices.planNarration(" in source:
            violations.append(f"{rel}: aiServices.planNarration")
        if "AudioDirectionAiServices(" in source:
            violations.append(f"{rel}: duplicate audio-direction AI transport")
    if violations:
        raise SystemExit("XPK_STRICT_PARITY legacy narration wiring:\n" + "\n".join(violations))


require(
    "app/src/main/java/vn/nghetruyen/app/AppContainer.kt",
    "val aiServices: OnlineTextAiServices",
    "val xpkNarrationAiServices: XpkNarrationAiServices",
    "NarrationPlanCoordinator(",
    "library = libraryRepository",
    "settings = settingsRepository",
    "ai = xpkNarrationAiServices",
    "storyAudioModeStore = storyAudioSourceModeStore",
    "freesoundResolver = freesoundAutoAudioResolver",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/AppContainer.kt",
    "val aiServices: OnlineAiServices",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/OnlineTextAiServices.kt",
    ") : TranslationEngine, VietPhraseImprovementEngine",
    "private val delegate = OnlineAiServices",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ai/OnlineTextAiServices.kt",
    ": VoiceCastEngine",
    ": SceneMusicPlanner",
    ": NarrationPlanner",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt",
    "paragraph voice-cast protocol is retired from production wiring",
    "paragraph scene-cue protocol is retired from production wiring",
    "legacy narration planner is retired from production wiring",
    "includeAmbience: Boolean = false",
    "includeSoundEffects: Boolean = false",
    "ambienceScenes: List<AmbienceScene>",
    "soundEffectCues: List<SoundEffectCue>",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "MAX_VOICE_PROFILES = 10",
    "VOICE_PROFILES_TOO_MANY",
    "ReferenceVoiceRoleExtras.load(appContext, role.id)",
    "profileSettingsById = profileSettingsById",
    "dialogueGroupByUnitId = bundle.units",
    "XpkUnifiedNarrationPrompt.compose(",
    "includeAmbience = request.includeAmbience",
    "includeSoundEffects = request.includeSoundEffects",
    "validAmbienceIds = validAmbienceIds",
    "validSfxIds = validSfxIds",
    "StoryVoiceCastMode.GLOBAL -> libraryRepository.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID)",
    "expressiveAdjustment = profile?.expressiveAdjustment ?: false",
    "val timeoutMillis = config.global.timeoutMillis.coerceAtLeast(10_000)",
    ".connectTimeout(minOf(30_000, timeoutMillis).toLong(), TimeUnit.MILLISECONDS)",
    ".readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "AI_SCENE_MUSIC_REQUIRES_VOICE_CAST",
    "customGuidance =",
    "MAX_VOICE_PROFILES = 40",
    "maxOutputTokens",
    "MAX_OUTPUT_TOKENS",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt",
    '"assignments"',
    '"music_scenes"',
    '"ambience_scenes"',
    '"sfx_cues"',
    "XpkVoiceCastPrompt.unitsForScenePrompt(base.units)",
    "Không dùng thời gian theo giây/mili-giây",
    "AMBIENCE_CATALOG",
    "SFX_CATALOG",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkVoiceCastPrompt.kt",
    'require(profiles.size <= 10) { "Tối đa 10 giọng" }',
    '"id": "ID_THỰC_TẾ_1"',
    '"id": "ID_THỰC_TẾ_2"',
    "PromptProfileSettings",
    'appendLine("  Mô tả: ${row.description}")',
    'format(Locale.ROOT, settings.speed)',
    'val sceneTask = sceneBlock?.instructions?.let { "\\n\\n$it" }.orEmpty()',
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/AiLineProtocol.kt",
    "dialogueGroupByUnitId: Map<String, String>",
    "explicitCharacterVoiceById",
    "groupVoice[group] = explicitVoice",
    "validAmbienceIds: Set<String>",
    "validSfxIds: Set<String>",
    "XpkAmbienceSfxDirector.parseAndValidate(",
    '@Deprecated("Use parseXpkNarration; paragraph ROLE/ASSIGN protocol is not used by XPK narration runtime")',
    '@Deprecated("Use parseXpkNarration; paragraph CUE protocol is not used by XPK narration runtime")',
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt",
    "XpkPlaybackRuntime.resetCanonicalPlans()",
    "XpkPlaybackRuntime.canonicalLines(paragraphs)",
    "val dialogueGroupId: String? = null",
    "enum class NarrationAutomationStage",
    "val narrationProgress: Float = 0f",
    "fun setNarrationAutomation(",
    "val nextChapterPageUrl: String? = null",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/ChapterPagination.kt",
    "object ChapterPageCursorCodec",
    "object ChapterCatalogMerger",
    "class ChapterPageNavigationCache",
    "nextChapterPageStartIndex",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/XpkPlaybackRuntime.kt",
    "TIMELINE_FINGERPRINT_VERSION = 2",
    "chunk.dialogueGroupId.orEmpty()",
    "canonicalVoicePlanActive",
    "canonicalScenePlanActive",
    "groupVoice[group] = voice",
    "Transform XPK dùng phiên bản timeline fingerprint cũ",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/VoiceExpressionProcessor.kt",
    "XpkPlaybackRuntime.shouldBypassLocalExpression(text)",
    "rateMultiplier = 1f",
    "pitchMultiplier = 1f",
    "volumeMultiplier = 1f",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkSceneMusicParity.kt",
    ".let { utf8Tail(it, 3000) }",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt",
    'put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)',
    "isCurrentTimelineTransform(previousTransform.transformedText, MUSIC_TRANSFORM_ENGINE, previous)",
    "XpkPlaybackRuntime.canonicalLines(content.paragraphs)",
    "planningMutex.withLock",
    "includeAmbience = effectiveAmbience",
    "includeSoundEffects = effectiveSfx",
    "persistAudioDirectionPlan(",
    "suspend fun loadAudioDirectionPlan(content: ChapterContent)",
    "suspend fun voicePlanAssignmentCount(content: ChapterContent): Int",
    "suspend fun shouldAutoVoiceCast(storyId: String): Boolean",
    "val profile = library.getStoryAiProfile(storyId) ?: return false",
    "if (!StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) return false",
    "return storyVoice.autoRunOnOpenTts",
    "suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean",
    "suspend fun effectiveVoiceRoles(storyId: String): List<VoiceRoleEntity>",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt",
    "private val narrationPlanCoordinator: NarrationPlanCoordinator",
    "narrationPlanCoordinator.ensureActivePlans",
    "narrationPlanCoordinator.loadAudioDirectionPlan",
    "maxConcurrent = Int.MAX_VALUE",
)
require(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioDirectionModels.kt",
    "const val MAX_CONCURRENT_AMBIENCE = Int.MAX_VALUE",
    "const val MAX_CONCURRENT_SFX = Int.MAX_VALUE",
    "const val MAX_SFX_REPEAT_COUNT = Int.MAX_VALUE",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    'private var narrationPreparedChapterId: String = ""',
    'private var manualNarrationChapterId: String = ""',
    "if (prepareCurrentNarrationBeforePlayback(snapshot)) return",
    "if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false",
    "if (currentStoryExpressiveAdjustmentEnabled)",
    "val aiRateMultiplier = 1f + speedAdjustPct / 100f",
    "val aiPitchMultiplier = 1f + pitchAdjustPct / 100f",
    "val aiVolumeMultiplier = 1f + volumeAdjustPct / 100f",
    "val effectiveSceneVolume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 1f)",
    "volume = effectiveSceneVolume",
    "tts.setAudioAttributes(speechAudioAttributes())",
    "private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()",
    ".setUsage(AudioAttributes.USAGE_MEDIA)",
    "PREFETCH_THRESHOLD = 0.75f",
    "NarrationAutomationStage.NEXT_LOADING",
    "NarrationAutomationStage.NEXT_PLANNING",
    "NarrationAutomationStage.NEXT_READY",
    "shouldPlanAutoSceneMusic()",
    "voicePlanAssignmentCount(content)",
    "NARRATION_RETRY_DELAY_MS = 5_000L",
    "ACTION_APPLY_NARRATION_AND_PLAY",
    "manualNarrationChapterId = PlaybackQueueStore.state.value.chapterId",
    "val voicePlanEnabled = currentStoryAutoVoiceCastEnabled || manualNarrationChapterId == chapterId",
    "if (voicePlanEnabled && originalHash != null)",
    "Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần.",
    "Chưa chuẩn bị xong. Sẽ thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS)",
    "if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled)",
    "if (!PlaybackQueueStore.state.value.isPlaying) pendingPlay = false",
    "private val speechCompletionMonitor = SpeechCompletionMonitor()",
    '"TTS_COMPLETION_WATCHDOG_RECOVERY"',
    "loadNextChapterForAdvance(snapshot)",
    '"TTS_CHAPTER_ADVANCE_WAIT_PREFETCH"',
    "loadNextChapterFromCatalogPage(snapshot)",
    '"TTS_CHAPTER_CATALOG_PAGE_LOAD"',
    "ChapterPageCursorCodec.encode(",
    "pendingPlay = false\n                        play()",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    "narrationReloadPending",
    "maybeEnsureCurrentNarrationPlans()",
    "val aiRateMultiplier = (1f + speedAdjustPct / 100f).coerceIn(0.5f, 1.5f)",
    "volume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 0.6f)",
    "AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY",
    "Phân vai tự động chưa thành công; đang đọc bằng cấu hình/phân vai hiện có.",
    "if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled)",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
    "snapshotFlow",
    "CHAPTER_PAGE_PREFETCH_DISTANCE",
    "state.chapterPageLoading",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
    'Text("TẢI THÊM")',
    'Text("NẠP TOÀN BỘ MỤC LỤC")',
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/PlaybackRecoveryPolicy.kt",
    "class SpeechCompletionMonitor(",
    "PlaybackWatchdogPolicy.QUIET_COMPLETION_CONFIRMATIONS",
    "SpeechCompletionObservation.COMPLETED",
    "object NextChapterAdvancePolicy",
    "PREFETCH_WAIT_MILLIS = 15_000L",
    "LOAD_ATTEMPTS = 3",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "includeMusic = state.value.backgroundMusicEnabled && state.value.autoSceneMusicEnabled",
    "val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast",
    "val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS && autoVoiceCastOnOpen",
    "NarrationAutomationStage.IDLE",
    "ReaderPlaybackService.ACTION_PLAY",
    "ACTION_APPLY_NARRATION_AND_PLAY",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "Hãy thêm ít nhất một tệp nhạc cảnh đang bật.",
    "existingVoicePlanCount > 0",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    "LinearProgressIndicator(",
    "state.playback.narrationMessage",
    "Từ 75% chương",
    "if (effectiveAutoVoiceCastEnabled)",
    "StoryVoiceCastReferenceCodec.hasStoredSettings",
    "storyAiProfile == null -> false",
    "!StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> false",
    "view.announceForAccessibility(announcement)",
)

require(
    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
    "prefs[Keys.backgroundMusicDuckFactor] ?: 0.63095734f",
    "prefs[Keys.backgroundMusicAttackMillis] ?: 1850",
    "prefs[Keys.backgroundMusicReleaseMillis] ?: 2050",
    "prefs[Keys.sceneMusicTargetLufs] ?: -24.0f",
)

require(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    "container.narrationPlanCoordinator.effectiveVoiceRoles(job.storyId)",
    "container.narrationPlanCoordinator.expressiveAdjustmentEnabled(job.storyId)",
    "XpkPlaybackRuntime.buildSpeechTimeline",
    "unitId: String",
    "loadAudioDirectionPlan(content)",
    "AudioAssetKind.AMBIENCE",
    "AudioAssetKind.SFX",
    "looping = false",
    "PcmLoudnessEstimator.gainDbToLinear(track.normalizationGainDb)",
    "settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)",
    "volume = gain.coerceIn(0f, 1f)",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    "settings.backgroundMusicVolume.coerceIn(0f, 1f) * settings.backgroundMusicDuckFactor",
    "volume = gain.coerceIn(0f, 0.6f)",
)

require(
    "app/src/main/java/vn/nghetruyen/app/audio/Pcm16SceneMixer.kt",
    "val looping: Boolean = true",
    "if (!layer.looping && local >= totalFrames)",
    "sourceCache.getOrPut(cacheKey)",
    "activeSamples.removeAll",
    "activeDucks.removeAll",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt",
    "AudioAssetKind.MUSIC",
    "AudioAssetKind.AMBIENCE",
    "AudioAssetKind.SFX",
    "UnifiedAudioAssetManagerDialog(",
)
require(
    "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt",
    "ActivityResultContracts.OpenMultipleDocuments()",
    'Text("THÊM TỆP")',
    'UnifiedAssetActionButton("NGHE THỬ")',
    'UnifiedAssetActionButton("CHUẨN HÓA")',
    'UnifiedAssetActionButton("SỬA TÊN / MÔ TẢ")',
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",
    "private val pendingPlayers = linkedSetOf<MediaPlayer>()",
    "private var transitionGeneration = 0L",
    "if (releasedController || generation != transitionGeneration)",
    "startCrossfadeTransition(next, duration)",
    "XPK_DEFAULT_CROSSFADE_MILLIS = 2_200",
    "baseVolume * duckMultiplier * sfxDuckMultiplier * slot.fadeMultiplier",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/reference/ReferenceVoiceRolePersistence.kt",
    'val activeVolume = if (method == "sonic")',
    "draft.sonicVolume.coerceIn(0f, 2f)",
    "volume = activeVolume",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt",
    "ReferenceVoiceRoleExtras.stageProcessorValuesForNextSave",
    "value.copy(rate = 1f, pitch = 1f, volume = value.sonicVolume)",
)

forbid_production_legacy_narration()
print("XPK_STRICT_PARITY=PASS")
