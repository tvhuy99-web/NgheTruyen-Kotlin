#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch(path: str, old: str, new: str, count: int = 1) -> None:
    file = ROOT / path
    source = file.read_text(encoding="utf-8")
    found = source.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old[:120]!r}")
    file.write_text(source.replace(old, new, count), encoding="utf-8")

# Story-level automation metadata must be distinguishable from an untouched/default profile.
patch(
    "app/src/main/java/vn/nghetruyen/app/ai/StoryVoiceCastReference.kt",
    '''object StoryVoiceCastReferenceCodec {\n    private const val PREFIX = "@NGHETRUYEN_VOICE_CAST|"\n\n    fun decode(raw: String): StoryVoiceCastReferenceSettings {''',
    '''object StoryVoiceCastReferenceCodec {\n    private const val PREFIX = "@NGHETRUYEN_VOICE_CAST|"\n\n    fun hasStoredSettings(raw: String): Boolean =\n        raw.lineSequence().firstOrNull().orEmpty().startsWith(PREFIX)\n\n    fun decode(raw: String): StoryVoiceCastReferenceSettings {''',
)

# Centralize effective auto-cast semantics and keep manual/global roles usable when automation is OFF.
patch(
    "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt",
    '''    suspend fun voicePlanAssignmentCount(content: ChapterContent): Int {\n        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)\n        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)\n            ?: return 0\n        if (cached.sourceSha256 != sourceHash) return 0\n        if (!isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, content)) return 0\n        return runCatching {\n            JSONObject(cached.transformedText).optJSONArray("assignments")?.length() ?: 0\n        }.getOrDefault(0)\n    }\n\n    private suspend fun storyVoiceSettings(storyId: String): StoryVoiceCastReferenceSettings =\n        library.getStoryAiProfile(storyId)?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }\n            ?: StoryVoiceCastReferenceSettings()\n\n    private suspend fun effectiveRoles(storyId: String): List<VoiceRoleEntity> {\n        val appSettings = settings.snapshot()\n        return when (storyVoiceSettings(storyId).mode) {\n            StoryVoiceCastMode.OFF -> emptyList()\n            StoryVoiceCastMode.PRIVATE -> library.listVoiceRoles(storyId).filter(VoiceRoleEntity::enabled)\n            StoryVoiceCastMode.GLOBAL -> if (appSettings.autoVoiceCastEnabled) {\n                library.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)\n            } else emptyList()\n        }\n    }''',
    '''    suspend fun voicePlanAssignmentCount(content: ChapterContent): Int {\n        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)\n        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)\n            ?: return 0\n        if (cached.sourceSha256 != sourceHash) return 0\n        if (!isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, content)) return 0\n        return runCatching {\n            JSONObject(cached.transformedText).optJSONArray("assignments")?.length() ?: 0\n        }.getOrDefault(0)\n    }\n\n    suspend fun shouldAutoVoiceCast(storyId: String): Boolean {\n        val appEnabled = settings.snapshot().autoVoiceCastEnabled\n        if (!appEnabled) return false\n        val profile = library.getStoryAiProfile(storyId) ?: return true\n        val raw = profile.voiceCastNote\n        val storyVoice = StoryVoiceCastReferenceCodec.decode(raw)\n        if (storyVoice.mode == StoryVoiceCastMode.OFF) return false\n        return if (StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) storyVoice.autoRunOnOpenTts else true\n    }\n\n    suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean =\n        library.getStoryAiProfile(storyId)?.expressiveAdjustment == true\n\n    private suspend fun storyVoiceSettings(storyId: String): StoryVoiceCastReferenceSettings =\n        library.getStoryAiProfile(storyId)?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }\n            ?: StoryVoiceCastReferenceSettings()\n\n    suspend fun effectiveVoiceRoles(storyId: String): List<VoiceRoleEntity> =\n        when (storyVoiceSettings(storyId).mode) {\n            StoryVoiceCastMode.OFF -> emptyList()\n            StoryVoiceCastMode.PRIVATE -> library.listVoiceRoles(storyId).filter(VoiceRoleEntity::enabled)\n            StoryVoiceCastMode.GLOBAL -> library.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)\n        }''',
)

# XPK planner: role availability is not the same thing as auto-run, and expressive defaults OFF.
patch(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    '''import vn.nghetruyen.app.core.common.AppResult\nimport vn.nghetruyen.app.data.local.VoiceRoleEntity''',
    '''import vn.nghetruyen.app.core.common.AppResult\nimport vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID\nimport vn.nghetruyen.app.data.local.VoiceRoleEntity''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    '''        val profiles = if (request.includeVoiceCast) {\n            libraryRepository.listEffectiveVoiceRoles(\n                request.storyId,\n                settingsRepository.snapshot().autoVoiceCastEnabled,\n            ).filter(VoiceRoleEntity::enabled)\n        } else emptyList()''',
    '''        val storyVoice = StoryVoiceCastReferenceCodec.decode(config.voiceCastNote)\n        val profiles = if (request.includeVoiceCast) {\n            when (storyVoice.mode) {\n                StoryVoiceCastMode.OFF -> emptyList()\n                StoryVoiceCastMode.PRIVATE -> libraryRepository.listVoiceRoles(request.storyId).filter(VoiceRoleEntity::enabled)\n                StoryVoiceCastMode.GLOBAL -> libraryRepository.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)\n            }\n        } else emptyList()''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
    '''            expressiveAdjustment = profile?.expressiveAdjustment ?: true,''',
    '''            expressiveAdjustment = profile?.expressiveAdjustment ?: false,''',
)

# Persisted settings must fall back to the same XPK/default values as AppSettings.
patch(
    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
    '''backgroundMusicDuckFactor = normalizeDuckFactor(prefs[Keys.backgroundMusicDuckFactor] ?: 0.25f),''',
    '''backgroundMusicDuckFactor = normalizeDuckFactor(prefs[Keys.backgroundMusicDuckFactor] ?: 0.63095734f),''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
    '''backgroundMusicAttackMillis = normalizeMusicAttackMillis(prefs[Keys.backgroundMusicAttackMillis] ?: 250),''',
    '''backgroundMusicAttackMillis = normalizeMusicAttackMillis(prefs[Keys.backgroundMusicAttackMillis] ?: 1850),''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
    '''backgroundMusicReleaseMillis = normalizeMusicReleaseMillis(prefs[Keys.backgroundMusicReleaseMillis] ?: 900),''',
    '''backgroundMusicReleaseMillis = normalizeMusicReleaseMillis(prefs[Keys.backgroundMusicReleaseMillis] ?: 2050),''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
    '''sceneMusicTargetLufs = normalizeTargetLufs(prefs[Keys.sceneMusicTargetLufs] ?: -18.0f),''',
    '''sceneMusicTargetLufs = normalizeTargetLufs(prefs[Keys.sceneMusicTargetLufs] ?: -24.0f),''',
)

# Playback: cache the effective per-story switches and gate AI prosody at the point of use.
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''    private var autoVoiceCastEnabled = false\n    private var autoSceneMusicEnabled = false''',
    '''    private var autoVoiceCastEnabled = false\n    private var currentStoryAutoVoiceCastEnabled = false\n    private var currentStoryExpressiveAdjustmentEnabled = false\n    private var autoSceneMusicEnabled = false''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        if (previousAutoVoiceCastEnabled && !autoVoiceCastEnabled) {\n            narrationPlanJob?.cancel()\n            narrationPlanningChapterId = ""\n            narrationPreparedChapterId = ""''',
    '''        if (previousAutoVoiceCastEnabled && !autoVoiceCastEnabled) {\n            currentStoryAutoVoiceCastEnabled = false\n            narrationPlanJob?.cancel()\n            narrationPrefetchJob?.cancel()\n            narrationPlanningChapterId = ""\n            narrationPreparedChapterId = ""''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        val speedAdjustPct = (unitAssignment?.speedAdjustPct ?: legacyAssignment?.speedAdjustPct ?: 0f).coerceIn(-100f, 100f)\n        val pitchAdjustPct = (unitAssignment?.pitchAdjustPct ?: legacyAssignment?.pitchAdjustPct ?: 0f).coerceIn(-100f, 100f)\n        val volumeAdjustPct = (unitAssignment?.volumeAdjustPct ?: legacyAssignment?.volumeAdjustPct ?: 0f).coerceIn(-100f, 100f)''',
    '''        val speedAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {\n            (unitAssignment?.speedAdjustPct ?: legacyAssignment?.speedAdjustPct ?: 0f).coerceIn(-100f, 100f)\n        } else 0f\n        val pitchAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {\n            (unitAssignment?.pitchAdjustPct ?: legacyAssignment?.pitchAdjustPct ?: 0f).coerceIn(-100f, 100f)\n        } else 0f\n        val volumeAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {\n            (unitAssignment?.volumeAdjustPct ?: legacyAssignment?.volumeAdjustPct ?: 0f).coerceIn(-100f, 100f)\n        } else 0f''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        if (!autoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false''',
    '''        if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''            while (autoVoiceCastEnabled && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {''',
    '''            while (currentStoryAutoVoiceCastEnabled && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        if (!prefetchNarrationPlansEnabled || !autoVoiceCastEnabled) return''',
    '''        if (!prefetchNarrationPlansEnabled || !currentStoryAutoVoiceCastEnabled) return''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''            val existingVoicePlanCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(next)\n            val shouldAutoStart = autoVoiceCastEnabled || existingVoicePlanCount > 0''',
    '''            val existingVoicePlanCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(next)\n            val shouldAutoStart = container.narrationPlanCoordinator.shouldAutoVoiceCast(next.chapter.storyId) ||\n                existingVoicePlanCount > 0''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        val storyId = PlaybackQueueStore.state.value.storyId\n        val profile = if (useStoryProfile && storyId.isNotBlank()) {\n            container.libraryRepository.getStoryTtsProfile(storyId)\n        } else null''',
    '''        val storyId = PlaybackQueueStore.state.value.storyId\n        val previousStoryAutoVoiceCastEnabled = currentStoryAutoVoiceCastEnabled\n        currentStoryAutoVoiceCastEnabled = if (useStoryProfile && storyId.isNotBlank()) {\n            container.narrationPlanCoordinator.shouldAutoVoiceCast(storyId)\n        } else false\n        currentStoryExpressiveAdjustmentEnabled = if (useStoryProfile && storyId.isNotBlank()) {\n            container.narrationPlanCoordinator.expressiveAdjustmentEnabled(storyId)\n        } else false\n        if (previousStoryAutoVoiceCastEnabled && !currentStoryAutoVoiceCastEnabled) {\n            narrationPlanJob?.cancel()\n            narrationPrefetchJob?.cancel()\n            narrationPlanningChapterId = ""\n            narrationPreparedChapterId = ""\n            PlaybackQueueStore.setNarrationAutomation(\n                stage = NarrationAutomationStage.IDLE,\n                progress = 0f,\n                message = null,\n            )\n        }\n        val profile = if (useStoryProfile && storyId.isNotBlank()) {\n            container.libraryRepository.getStoryTtsProfile(storyId)\n        } else null''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        voiceRoles = if (useStoryProfile && storyId.isNotBlank()) {\n            container.libraryRepository.listEffectiveVoiceRoles(storyId, settings.autoVoiceCastEnabled)\n        } else emptyList()''',
    '''        voiceRoles = if (useStoryProfile && storyId.isNotBlank()) {\n            container.narrationPlanCoordinator.effectiveVoiceRoles(storyId)\n        } else emptyList()''',
)

# ViewModel: chapter-open automation uses the same effective switch; saving story switches refreshes service immediately.
patch(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''                    val existingVoicePlanCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(enriched)\n                    val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS &&\n                        (settings.autoVoiceCastEnabled || existingVoicePlanCount > 0)''',
    '''                    val existingVoicePlanCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(enriched)\n                    val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast(enriched.chapter.storyId)\n                    val shouldAutoStartNarration = settings.readerMode == ReaderMode.TTS &&\n                        (autoVoiceCastOnOpen || existingVoicePlanCount > 0)''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''            ).onSuccess { showMessage("Đã lưu cấu hình AI riêng cho truyện.") }\n                .onFailure { showMessage(it.message ?: "Không lưu được cấu hình AI theo truyện.") }''',
    '''            ).onSuccess {\n                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n                showMessage("Đã lưu cấu hình AI riêng cho truyện.")\n            }.onFailure { showMessage(it.message ?: "Không lưu được cấu hình AI theo truyện.") }''',
)

# Audio export: use the same role library and prosody switch as playback, then make scene-music gain identical to live playback.
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''            val roles = container.libraryRepository.listEffectiveVoiceRoles(job.storyId, settings.autoVoiceCastEnabled)''',
    '''            val roles = container.narrationPlanCoordinator.effectiveVoiceRoles(job.storyId)''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''        val normalizedSegments = ArrayList<File>(chunks.size)\n        val baseVoice = baseVoice(settings, profile)''',
    '''        val normalizedSegments = ArrayList<File>(chunks.size)\n        val expressiveAdjustmentEnabled = container.narrationPlanCoordinator.expressiveAdjustmentEnabled(job.storyId)\n        val baseVoice = baseVoice(settings, profile)''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''                    val aiRateMultiplier = (1f + (assigned?.speedAdjustPct ?: 0f) / 100f).coerceIn(0.5f, 1.5f)\n                    val aiPitchMultiplier = (1f + (assigned?.pitchAdjustPct ?: 0f) / 100f).coerceIn(0.5f, 1.5f)\n                    val aiVolumeMultiplier = (1f + (assigned?.volumeAdjustPct ?: 0f) / 100f).coerceIn(0.2f, 2f)''',
    '''                    val aiRateMultiplier = if (expressiveAdjustmentEnabled) {\n                        1f + (assigned?.speedAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f\n                    } else 1f\n                    val aiPitchMultiplier = if (expressiveAdjustmentEnabled) {\n                        1f + (assigned?.pitchAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f\n                    } else 1f\n                    val aiVolumeMultiplier = if (expressiveAdjustmentEnabled) {\n                        1f + (assigned?.volumeAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f\n                    } else 1f''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''                val loudnessGain = PcmLoudnessEstimator.normalizationGain(\n                    track.loudnessLufsEstimate,\n                    settings.sceneMusicTargetLufs,\n                )\n                val gain = cue.volume.coerceIn(0f, 1f) * track.volume.coerceIn(0f, 1f) * loudnessGain *\n                    settings.backgroundMusicVolume.coerceIn(0f, 1f) * settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)\n                layers += SceneMixLayer(\n                    sourceWav = trackWav,\n                    startFrame = layerStart,\n                    endFrameExclusive = layerEnd,\n                    volume = gain.coerceIn(0f, 0.6f),''',
    '''                val normalizationGain = if (\n                    PcmLoudnessEstimator.isReady(\n                        version = track.normalizationVersion,\n                        error = track.normalizationError,\n                        loudnessLufs = track.loudnessLufsEstimate,\n                        targetLufs = settings.sceneMusicTargetLufs,\n                        storedTargetLufs = track.normalizationTargetLufs,\n                        gainDb = track.normalizationGainDb,\n                    )\n                ) {\n                    PcmLoudnessEstimator.gainDbToLinear(track.normalizationGainDb)\n                } else 1f\n                val gain = cue.volume.coerceIn(0f, 1f) * track.volume.coerceIn(0f, 1f) * normalizationGain *\n                    settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)\n                layers += SceneMixLayer(\n                    sourceWav = trackWav,\n                    startFrame = layerStart,\n                    endFrameExclusive = layerEnd,\n                    volume = gain.coerceIn(0f, 1f),''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''            settings.sonicDefaultSpeed, settings.sonicDefaultPitch, settings.sceneMusicTargetLufs,\n        ).joinToString("|"))''',
    '''            settings.sonicDefaultSpeed, settings.sonicDefaultPitch, settings.sceneMusicTargetLufs,\n            settings.backgroundMusicDuckFactor,\n        ).joinToString("|"))''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
    '''                it.id, it.uri, it.volume, it.enabled, it.loudnessLufsEstimate,\n                it.playCount, it.lastPlayedAt, it.orderIndex, it.updatedAt,''',
    '''                it.id, it.uri, it.volume, it.enabled, it.loudnessLufsEstimate,\n                it.normalizationTargetLufs, it.normalizationGainDb, it.normalizationVersion, it.normalizationError,\n                it.playCount, it.lastPlayedAt, it.orderIndex, it.updatedAt,''',
)

# Static regression gates for both switch behavior and export/live mix parity.
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "AI_SCENE_MUSIC_REQUIRES_VOICE_CAST",\n    "val timeoutMillis = config.global.timeoutMillis.coerceAtLeast(10_000)",''',
    '''    "AI_SCENE_MUSIC_REQUIRES_VOICE_CAST",\n    "StoryVoiceCastMode.GLOBAL -> libraryRepository.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID)",\n    "expressiveAdjustment = profile?.expressiveAdjustment ?: false",\n    "val timeoutMillis = config.global.timeoutMillis.coerceAtLeast(10_000)",''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "suspend fun voicePlanAssignmentCount(content: ChapterContent): Int",\n)''',
    '''    "suspend fun voicePlanAssignmentCount(content: ChapterContent): Int",\n    "suspend fun shouldAutoVoiceCast(storyId: String): Boolean",\n    "suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean",\n    "suspend fun effectiveVoiceRoles(storyId: String): List<VoiceRoleEntity>",\n)''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "if (!autoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false",''',
    '''    "if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false",\n    "if (currentStoryExpressiveAdjustmentEnabled)",''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "includeMusic = state.value.backgroundMusicEnabled && state.value.autoSceneMusicEnabled",\n    "NarrationAutomationStage.IDLE",''',
    '''    "includeMusic = state.value.backgroundMusicEnabled && state.value.autoSceneMusicEnabled",\n    "val autoVoiceCastOnOpen = container.narrationPlanCoordinator.shouldAutoVoiceCast",\n    "NarrationAutomationStage.IDLE",''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''require(\n    "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",''',
    '''require(\n    "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",\n    "prefs[Keys.backgroundMusicDuckFactor] ?: 0.63095734f",\n    "prefs[Keys.backgroundMusicAttackMillis] ?: 1850",\n    "prefs[Keys.backgroundMusicReleaseMillis] ?: 2050",\n    "prefs[Keys.sceneMusicTargetLufs] ?: -24.0f",\n)\n\nrequire(\n    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",\n    "container.narrationPlanCoordinator.effectiveVoiceRoles(job.storyId)",\n    "container.narrationPlanCoordinator.expressiveAdjustmentEnabled(job.storyId)",\n    "PcmLoudnessEstimator.gainDbToLinear(track.normalizationGainDb)",\n    "settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)",\n    "volume = gain.coerceIn(0f, 1f)",\n)\nforbid(\n    "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",\n    "settings.backgroundMusicVolume.coerceIn(0f, 1f) * settings.backgroundMusicDuckFactor",\n    "volume = gain.coerceIn(0f, 0.6f)",\n)\n\nrequire(\n    "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",''',
)

Path(__file__).unlink()
print("SWITCH_AND_AUDIO_MIX_FIX=APPLIED")
