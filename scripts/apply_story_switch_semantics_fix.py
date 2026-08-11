#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt",
    '''    suspend fun shouldAutoVoiceCast(storyId: String): Boolean {
        val appEnabled = settings.snapshot().autoVoiceCastEnabled
        if (!appEnabled) return false
        val profile = library.getStoryAiProfile(storyId) ?: return true
        val raw = profile.voiceCastNote
        val storyVoice = StoryVoiceCastReferenceCodec.decode(raw)
        if (storyVoice.mode == StoryVoiceCastMode.OFF) return false
        return if (StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) storyVoice.autoRunOnOpenTts else true
    }
''',
    '''    suspend fun shouldAutoVoiceCast(storyId: String): Boolean {
        val appEnabled = settings.snapshot().autoVoiceCastEnabled
        if (!appEnabled) return false
        val profile = library.getStoryAiProfile(storyId) ?: return false
        val raw = profile.voiceCastNote
        if (!StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) return false
        val storyVoice = StoryVoiceCastReferenceCodec.decode(raw)
        if (storyVoice.mode == StoryVoiceCastMode.OFF) return false
        return storyVoice.autoRunOnOpenTts
    }
''',
)

replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    '''    val effectiveAutoVoiceCastEnabled = state.autoVoiceCastEnabled && when {
        storyAiProfile == null -> true
        !StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> true
        storyVoiceReference?.mode == StoryVoiceCastMode.OFF -> false
        else -> storyVoiceReference?.autoRunOnOpenTts == true
    }
''',
    '''    val effectiveAutoVoiceCastEnabled = state.autoVoiceCastEnabled && when {
        storyAiProfile == null -> false
        !StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> false
        storyVoiceReference?.mode == StoryVoiceCastMode.OFF -> false
        else -> storyVoiceReference?.autoRunOnOpenTts == true
    }
''',
)

replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''                    val existingVoicePlanCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(enriched)
                    val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast(enriched.chapter.storyId)
                    val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS &&
                        (autoVoiceCastOnOpen || existingVoicePlanCount > 0)
''',
    '''                    val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast(enriched.chapter.storyId)
                    val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS && autoVoiceCastOnOpen
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''                            ReaderPlaybackService.ACTION_APPLY_NARRATION_AND_PLAY,
''',
    '''                            ReaderPlaybackService.ACTION_PLAY,
''',
)

replace_once(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''    private var narrationPlanningChapterId: String = ""
    private var narrationPreparedChapterId: String = ""
    private var transitionMessage: String? = null
''',
    '''    private var narrationPlanningChapterId: String = ""
    private var narrationPreparedChapterId: String = ""
    private var manualNarrationChapterId: String = ""
    private var transitionMessage: String? = null
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''            ACTION_REFRESH -> refreshVoiceAndNotification()
            ACTION_APPLY_NARRATION_AND_PLAY -> refreshVoiceAndNotification(playAfterRefresh = true)
            ACTION_MUSIC_PREVIEW_BEGIN -> beginMusicPreview()
''',
    '''            ACTION_REFRESH -> refreshVoiceAndNotification()
            ACTION_APPLY_NARRATION_AND_PLAY -> {
                manualNarrationChapterId = PlaybackQueueStore.state.value.chapterId
                refreshVoiceAndNotification(playAfterRefresh = true)
            }
            ACTION_MUSIC_PREVIEW_BEGIN -> beginMusicPreview()
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        val playbackSnapshot = PlaybackQueueStore.state.value
        val chapterId = playbackSnapshot.chapterId
        voiceRoles = if (useStoryProfile && storyId.isNotBlank()) {
            container.narrationPlanCoordinator.effectiveVoiceRoles(storyId)
        } else emptyList()
        val originalChapter = if (useStoryProfile && chapterId.isNotBlank()) {
''',
    '''        val playbackSnapshot = PlaybackQueueStore.state.value
        val chapterId = playbackSnapshot.chapterId
        if (manualNarrationChapterId.isNotBlank() && manualNarrationChapterId != chapterId) {
            manualNarrationChapterId = ""
        }
        val voicePlanEnabled = currentStoryAutoVoiceCastEnabled || manualNarrationChapterId == chapterId
        voiceRoles = if (useStoryProfile && storyId.isNotBlank() && voicePlanEnabled) {
            container.narrationPlanCoordinator.effectiveVoiceRoles(storyId)
        } else emptyList()
        val originalChapter = if (useStoryProfile && chapterId.isNotBlank()) {
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        val voicePlan = if (originalHash != null) {
            container.libraryRepository.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_VOICE_CAST)
        } else null
''',
    '''        val voicePlan = if (voicePlanEnabled && originalHash != null) {
            container.libraryRepository.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_VOICE_CAST)
        } else null
''',
)

replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''    "suspend fun shouldAutoVoiceCast(storyId: String): Boolean",
    "suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean",
''',
    '''    "suspend fun shouldAutoVoiceCast(storyId: String): Boolean",
    "val profile = library.getStoryAiProfile(storyId) ?: return false",
    "if (!StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) return false",
    "return storyVoice.autoRunOnOpenTts",
    "suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean",
''',
)
replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''    'private var narrationPreparedChapterId: String = ""',
    "if (prepareCurrentNarrationBeforePlayback(snapshot)) return",
''',
    '''    'private var narrationPreparedChapterId: String = ""',
    'private var manualNarrationChapterId: String = ""',
    "if (prepareCurrentNarrationBeforePlayback(snapshot)) return",
''',
)
replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''    "ACTION_APPLY_NARRATION_AND_PLAY",
    "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.",
''',
    '''    "ACTION_APPLY_NARRATION_AND_PLAY",
    "manualNarrationChapterId = PlaybackQueueStore.state.value.chapterId",
    "val voicePlanEnabled = currentStoryAutoVoiceCastEnabled || manualNarrationChapterId == chapterId",
    "if (voicePlanEnabled && originalHash != null)",
    "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.",
''',
)
replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''    "val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast",
    "NarrationAutomationStage.IDLE",
    "existingVoicePlanCount > 0",
    "ACTION_APPLY_NARRATION_AND_PLAY",
''',
    '''    "val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast",
    "val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS && autoVoiceCastOnOpen",
    "NarrationAutomationStage.IDLE",
    "ReaderPlaybackService.ACTION_PLAY",
    "ACTION_APPLY_NARRATION_AND_PLAY",
''',
)
replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''forbid(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "Hãy thêm ít nhất một tệp nhạc cảnh đang bật.",
)
''',
    '''forbid(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "Hãy thêm ít nhất một tệp nhạc cảnh đang bật.",
    "existingVoicePlanCount > 0",
)
''',
)
replace_once(
    "scripts/check_xpk_strict_parity.py",
    '''    "StoryVoiceCastReferenceCodec.hasStoredSettings",
    "view.announceForAccessibility(announcement)",
''',
    '''    "StoryVoiceCastReferenceCodec.hasStoredSettings",
    "storyAiProfile == null -> false",
    "!StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> false",
    "view.announceForAccessibility(announcement)",
''',
)

# Lock the two per-story switches to OFF-by-default UI semantics.
with (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt").open(encoding="utf-8") as fh:
    story_ui = fh.read()
for needle in (
    "mutableStateOf(voiceReference.autoRunOnOpenTts)",
    "mutableStateOf(aiProfile?.expressiveAdjustment ?: false)",
    "StoryVoiceCastReferenceCodec.encode(storyVoiceMode, storyVoiceAuto, storyVoiceNote)",
    "expressiveEnabled",
):
    if needle not in story_ui:
        raise SystemExit(f"StoryDetailScreen missing switch invariant: {needle}")

print("STORY_SWITCH_SEMANTICS_FIX_APPLIED")
