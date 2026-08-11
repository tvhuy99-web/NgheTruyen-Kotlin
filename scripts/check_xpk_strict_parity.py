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
    if violations:
        raise SystemExit("XPK_STRICT_PARITY legacy narration wiring:\n" + "\n".join(violations))


require(
    "app/src/main/java/vn/nghetruyen/app/AppContainer.kt",
    "val aiServices: OnlineTextAiServices",
    "val xpkNarrationAiServices: XpkNarrationAiServices",
    "NarrationPlanCoordinator(libraryRepository, settingsRepository, xpkNarrationAiServices)",
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
)

require(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "MAX_VOICE_PROFILES = 10",
    "VOICE_PROFILES_TOO_MANY",
    "ReferenceVoiceRoleExtras.load(appContext, role.id)",
    "profileSettingsById = profileSettingsById",
    "dialogueGroupByUnitId = bundle.units",
    "AI_SCENE_MUSIC_REQUIRES_VOICE_CAST",
    "StoryVoiceCastMode.GLOBAL -> libraryRepository.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID)",
    "expressiveAdjustment = profile?.expressiveAdjustment ?: false",
    "val timeoutMillis = config.global.timeoutMillis.coerceAtLeast(10_000)",
    ".connectTimeout(minOf(30_000, timeoutMillis).toLong(), TimeUnit.MILLISECONDS)",
    ".readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "customGuidance =",
    "MAX_VOICE_PROFILES = 40",
    "maxOutputTokens",
    "MAX_OUTPUT_TOKENS",
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
    "Nhạc theo cảnh AI chỉ được lập cùng phân vai TTS.",
    "suspend fun voicePlanAssignmentCount(content: ChapterContent): Int",
    "suspend fun shouldAutoVoiceCast(storyId: String): Boolean",
    "suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean",
    "suspend fun effectiveVoiceRoles(storyId: String): List<VoiceRoleEntity>",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt",
    "includeVoiceCast = false",
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    'private var narrationPreparedChapterId: String = ""',
    "if (prepareCurrentNarrationBeforePlayback(snapshot)) return",
    "if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false",
    "if (currentStoryExpressiveAdjustmentEnabled)",
    "val aiRateMultiplier = 1f + speedAdjustPct / 100f",
    "val aiPitchMultiplier = 1f + pitchAdjustPct / 100f",
    "val aiVolumeMultiplier = 1f + volumeAdjustPct / 100f",
    "volume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 1f)",
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
    "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    "narrationReloadPending",
    "maybeEnsureCurrentNarrationPlans()",
    "val aiRateMultiplier = (1f + speedAdjustPct / 100f).coerceIn(0.5f, 1.5f)",
    "volume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 0.6f)",
    "AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY",
    "Phân vai tự động chưa thành công; đang đọc bằng cấu hình/phân vai hiện có.",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "includeMusic = state.value.backgroundMusicEnabled && state.value.autoSceneMusicEnabled",
    "val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast",
    "NarrationAutomationStage.IDLE",
    "existingVoicePlanCount > 0",
    "ACTION_APPLY_NARRATION_AND_PLAY",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "Hãy thêm ít nhất một tệp nhạc cảnh đang bật.",
)

require(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    "LinearProgressIndicator(",
    "state.playback.narrationMessage",
    "Từ 75% chương",
    "if (state.autoVoiceCastEnabled)",
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
    "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",
    "startXpkSequentialTransition(next, XPK_SCENE_SWITCH_MILLIS)",
    "XPK_SCENE_SWITCH_MILLIS = 2_200",
    "val fadeOutMillis = if (old == null) 0 else duration / 2",
    "val fadeInMillis = if (old == null) duration else duration - fadeOutMillis",
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
