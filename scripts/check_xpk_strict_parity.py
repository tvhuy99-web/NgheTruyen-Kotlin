#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


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
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "MAX_VOICE_PROFILES = 10",
    "VOICE_PROFILES_TOO_MANY",
    "ReferenceVoiceRoleExtras.load(appContext, role.id)",
    "profileSettingsById = profileSettingsById",
    "dialogueGroupByUnitId = bundle.units",
)
forbid(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    "customGuidance =",
    "MAX_VOICE_PROFILES = 40",
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
)

require(
    "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",
    "startXpkSequentialTransition(next, XPK_SCENE_SWITCH_MILLIS)",
    "XPK_SCENE_SWITCH_MILLIS = 2_200",
    "val fadeOutMillis = if (old == null) 0 else duration / 2",
    "val fadeInMillis = if (old == null) duration else duration - fadeOutMillis",
)

print("XPK_STRICT_PARITY=PASS")
