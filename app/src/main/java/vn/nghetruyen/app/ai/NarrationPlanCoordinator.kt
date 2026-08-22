package vn.nghetruyen.app.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.StoryAudioModeRouter
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.audio.StoryAudioSourceModeStore
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.freesound.FreesoundAutoAudioResolver
import vn.nghetruyen.app.freesound.FreesoundAutoPlanBuilder
import vn.nghetruyen.app.freesound.FreesoundAutoRequirement
import vn.nghetruyen.app.freesound.FreesoundAutoResolvedNeed
import vn.nghetruyen.app.freesound.FreesoundAutoSearchNeed
import vn.nghetruyen.app.freesound.FreesoundAutoRequirementCodec
import vn.nghetruyen.app.freesound.FreesoundRequirementImportance
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.XpkPlaybackRuntime
import java.util.UUID

/**
 * Creates and caches one coordinated XPK chapter plan. Voice casting stays independent, while
 * MUSIC/AMBIENCE/SFX are routed through one of three mutually exclusive source modes. Modes 2 and 3
 * share the same enabled physical asset library; only Mode 3 may extend it from Freesound.
 */
class NarrationPlanCoordinator(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val ai: XpkNarrationAiServices,
    private val storyAudioModeStore: StoryAudioSourceModeStore,
    private val freesoundResolver: FreesoundAutoAudioResolver,
) {
    private val planningMutex = Mutex()
    private val freesoundRetryExhaustedChapters = linkedSetOf<String>()

    data class Result(
        val voicePlanCreated: Boolean,
        val musicPlanCreated: Boolean,
        val warnings: List<String>,
        val usedUnifiedRequest: Boolean = false,
        val audioPlanCreated: Boolean = false,
        val freesoundPlanCreated: Boolean = false,
        val freesoundResolvedAssets: Int = 0,
        val freesoundDownloadedAssets: Int = 0,
        val freesoundReusedAssets: Int = 0,
        val freesoundRetryRequired: Boolean = false,
        val freesoundRetryAttempts: Int = 0,
        val freesoundRetryExhausted: Boolean = false,
        val freesoundDiagnostics: List<String> = emptyList(),
    )

    private data class FreesoundApplyResult(
        val musicCreated: Boolean,
        val audioCreated: Boolean,
        val resolvedAssets: Int,
        val warnings: List<String>,
        val downloadedTrackIds: Set<String> = emptySet(),
        val reusedTrackIds: Set<String> = emptySet(),
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
        val attempts: Int = 0,
        val retryExhausted: Boolean = false,
    )

    private data class FreesoundContinuityContext(
        val musicQuery: String? = null,
        val ambienceQueries: List<String> = emptyList(),
    )

    suspend fun ensurePlans(
        content: ChapterContent,
        voice: Boolean,
        music: Boolean,
        force: Boolean = false,
        activeTrackId: String? = null,
    ): Result = planningMutex.withLock {
        ensurePlansLocked(currentPlaybackContent(content), voice, music, force, activeTrackId)
    }

    /** Used by the Ambience/SFX runtime when voice auto-cast itself is disabled. */
    suspend fun ensureActivePlans(
        content: ChapterContent,
        force: Boolean = false,
        activeTrackId: String? = null,
    ): Result {
        val appSettings = settings.snapshot()
        return ensurePlans(
            content = content,
            voice = shouldAutoVoiceCast(content.chapter.storyId),
            music = appSettings.autoSceneMusicEnabled,
            force = force,
            activeTrackId = activeTrackId,
        )
    }

    fun storyAudioSourceMode(): StoryAudioSourceMode = storyAudioModeStore.get()

    suspend fun resetChapterNarrationState(
        content: ChapterContent,
        clearFreesoundCaches: Boolean = true,
    ) = planningMutex.withLock {
        val effectiveContent = currentPlaybackContent(content)
        val chapterId = effectiveContent.chapter.id
        val storyId = effectiveContent.chapter.storyId
        listOf(
            ChapterAiWorkflow.KIND_VOICE_CAST,
            ChapterAiWorkflow.KIND_SCENE_MUSIC,
            KIND_AUDIO_DIRECTION,
            KIND_FREESOUND_AUTO_AUDIO,
        ).forEach { kind -> library.deleteChapterTransform(chapterId, kind) }
        library.replaceVoiceAssignments(storyId, chapterId, emptyList())
        library.replaceSceneMusicCues(storyId, chapterId, emptyList())
        freesoundRetryExhaustedChapters.remove(chapterId)
        if (clearFreesoundCaches) freesoundResolver.clearResolutionCaches()
    }

    private suspend fun ensurePlansLocked(
        content: ChapterContent,
        voice: Boolean,
        music: Boolean,
        force: Boolean,
        activeTrackId: String?,
    ): Result {
        val warnings = mutableListOf<String>()
        val sourceMode = storyAudioModeStore.get()
        val audioSettings = AudioDirectionPreferences.currentSnapshot()
        val enabledAssets = library.listEnabledSceneMusicTracks()
        val musicTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        val ambienceTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        val soundEffectTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }

        val storyVoice = storyVoiceSettings(content.chapter.storyId)
        val effectiveVoice = voice && storyVoice.mode != StoryVoiceCastMode.OFF
        if (voice && !effectiveVoice) warnings += "Phân vai TTS đang tắt cho truyện này."

        val localAiMode = StoryAudioModeRouter.usesAiLocal(sourceMode)
        val freesoundMode = StoryAudioModeRouter.usesAiFreesound(sourceMode)

        val effectiveMusic = localAiMode && music && musicTracks.isNotEmpty()
        if (localAiMode && music && musicTracks.isEmpty()) warnings += "Chưa có tệp nhạc cảnh đang bật."
        val effectiveAmbience = localAiMode && audioSettings.ambienceEnabled && ambienceTracks.isNotEmpty()
        if (localAiMode && audioSettings.ambienceEnabled && ambienceTracks.isEmpty()) {
            warnings += "Âm thanh môi trường đang bật nhưng chưa có asset AMBIENCE."
        }
        val effectiveSfx = localAiMode && audioSettings.soundEffectsEnabled && soundEffectTracks.isNotEmpty()
        if (localAiMode && audioSettings.soundEffectsEnabled && soundEffectTracks.isEmpty()) {
            warnings += "Hiệu ứng âm thanh đang bật nhưng chưa có asset SFX."
        }

        val freesoundDiagnostics = mutableListOf<String>()
        val freesoundKinds = if (freesoundMode) buildSet {
            if (music) add(AudioAssetKind.MUSIC)
            if (audioSettings.ambienceEnabled) add(AudioAssetKind.AMBIENCE)
            if (audioSettings.soundEffectsEnabled) add(AudioAssetKind.SFX)
        } else emptySet()
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_STATE mode=${sourceMode.name} musicRequested=$music ambienceEnabled=${audioSettings.ambienceEnabled} sfxEnabled=${audioSettings.soundEffectsEnabled} kinds=${freesoundKinds.map(AudioAssetKind::name).sorted().joinToString(",")}"
        }
        if (freesoundMode && freesoundKinds.isEmpty()) {
            warnings += "Mode 3 đang được chọn nhưng MUSIC, AMBIENCE và SFX đều tắt; lượt AI này chỉ có thể phân vai giọng."
            freesoundDiagnostics += "COORDINATOR_NO_LAYERS all Mode 3 audio layers are disabled"
        }

        var restoredFreesound = FreesoundApplyResult(false, false, 0, emptyList())
        val cachedFreesoundRequirements = if (freesoundMode && !force && freesoundKinds.isNotEmpty()) {
            loadFreesoundRequirements(content, freesoundKinds)
        } else null
        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)
        val cachedFreesoundRetryExhausted = cachedFreesoundRequirements != null && freesoundResolutionRetryExhausted(content)
        val cachedFreesoundEmpty = cachedFreesoundRequirements?.isEmpty() == true
        val cachedFreesoundEmptyRetryDue = cachedFreesoundEmpty && freesoundEmptyAiRetryDue(content)
        if (cachedFreesoundEmpty && !cachedFreesoundEmptyRetryDue) {
            restoredFreesound = FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("AI chưa trả yêu cầu âm thanh tự động; sẽ yêu cầu AI lập lại sau thời gian chờ."),
                retryableFailure = true,
                diagnostics = listOf("AI_REQUIREMENTS_EMPTY_CACHE retryDue=false"),
            )
            warnings += restoredFreesound.warnings
        }
        if (cachedFreesoundRetryExhausted) {
            freesoundRetryExhaustedChapters += content.chapter.id
            val reusable = cachedFreesoundRequirements?.let { cachedFreesoundTrackIds(it, freesoundKinds) }.orEmpty()
            restoredFreesound = FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = reusable.size,
                warnings = listOf(
                    if (reusable.isEmpty()) "Âm thanh Mode 3 còn thiếu sau 3 lần; phần TTS vẫn được phát và có thể phân vai lại để thử âm thanh mới."
                    else "Một số âm thanh Mode 3 còn thiếu sau 3 lần; vẫn phát ${reusable.size} asset đã chuẩn bị hợp lệ.",
                ),
                reusedTrackIds = reusable,
                retryableFailure = false,
                diagnostics = listOf("RESOLVE_PARTIAL_REUSE retryExhausted=true attempts=3 reusable=${reusable.size}"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
            warnings += restoredFreesound.warnings
        }
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired retryExhausted=$cachedFreesoundRetryExhausted empty=$cachedFreesoundEmpty emptyRetryDue=$cachedFreesoundEmptyRetryDue force=$force"
        }
        if (!cachedFreesoundRetryExhausted && cachedFreesoundRequirements != null && cachedFreesoundRequirements.isNotEmpty() &&
            (cachedFreesoundRetryRequired || !standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements))
        ) {
            restoredFreesound = applyFreesoundRequirements(content, cachedFreesoundRequirements, freesoundKinds)
            warnings += restoredFreesound.warnings
            runCatching {
                persistFreesoundRequirements(
                    content,
                    freesoundKinds,
                    cachedFreesoundRequirements,
                    retryRequired = restoredFreesound.retryableFailure,
                    resolvedAssets = restoredFreesound.resolvedAssets,
                    retryAttempts = restoredFreesound.attempts,
                    retryExhausted = restoredFreesound.retryExhausted,
                )
            }.onFailure { warnings += it.message ?: "Không cập nhật được trạng thái retry Mode 3." }
        }

        if (
            freesoundMode &&
            restoredFreesound.resolvedAssets == 0 &&
            !cachedFreesoundRetryRequired &&
            !cachedFreesoundRetryExhausted &&
            cachedFreesoundRequirements != null &&
            cachedFreesoundRequirements.isNotEmpty() &&
            standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements)
        ) {
            val reusedTrackIds = cachedFreesoundTrackIds(cachedFreesoundRequirements, freesoundKinds)
            if (reusedTrackIds.isNotEmpty()) {
                restoredFreesound = restoredFreesound.copy(
                    resolvedAssets = reusedTrackIds.size,
                    reusedTrackIds = reusedTrackIds,
                    diagnostics = (restoredFreesound.diagnostics +
                        "CACHE_PLAN_REUSE uniqueTracks=${reusedTrackIds.size}").distinct(),
                )
            }
        }

        val voiceNeeded = effectiveVoice && needsVoicePlan(content, force)
        val musicNeeded = effectiveMusic && needsMusicPlan(content, musicTracks, force, StoryAudioSourceMode.AI_LOCAL)
        val audioNeeded = (effectiveAmbience || effectiveSfx) && needsAudioDirectionPlan(
            content = content,
            ambienceTracks = if (effectiveAmbience) ambienceTracks else emptyList(),
            soundEffectTracks = if (effectiveSfx) soundEffectTracks else emptyList(),
            ambienceEnabled = effectiveAmbience,
            soundEffectsEnabled = effectiveSfx,
            force = force,
            expectedMode = StoryAudioSourceMode.AI_LOCAL,
        )
        val freesoundNeeded = freesoundMode && freesoundKinds.isNotEmpty() &&
            (force || cachedFreesoundRequirements == null || cachedFreesoundEmptyRetryDue)
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_NEEDS voiceNeeded=$voiceNeeded localMusicNeeded=$musicNeeded localAudioNeeded=$audioNeeded freesoundNeeded=$freesoundNeeded"
        }

        if (!voiceNeeded && !musicNeeded && !audioNeeded && !freesoundNeeded) {
            return Result(
                voicePlanCreated = false,
                musicPlanCreated = restoredFreesound.musicCreated,
                warnings = warnings.distinct(),
                audioPlanCreated = restoredFreesound.audioCreated,
                freesoundPlanCreated = restoredFreesound.musicCreated || restoredFreesound.audioCreated,
                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
                freesoundDownloadedAssets = restoredFreesound.downloadedTrackIds.size,
                freesoundReusedAssets = (restoredFreesound.reusedTrackIds - restoredFreesound.downloadedTrackIds).size,
                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundRetryAttempts = restoredFreesound.attempts,
                freesoundRetryExhausted = restoredFreesound.retryExhausted,
                freesoundDiagnostics = (freesoundDiagnostics + restoredFreesound.diagnostics + "COORDINATOR_REUSE no new AI/Mode3 work required").distinct(),
            )
        }

        if (!effectiveVoice && !effectiveMusic && !effectiveAmbience && !effectiveSfx && !freesoundNeeded) {
            return Result(false, false, warnings.distinct())
        }

        val baseContext = buildContinuityContext(content, activeTrackId, musicTracks)
        val incomingAmbienceIds = if (localAiMode) buildIncomingAmbienceIds(content, ambienceTracks) else emptyList()
        val freesoundContinuity = if (freesoundMode) buildFreesoundContinuityContext(content) else FreesoundContinuityContext()
        val context = baseContext.copy(
            incomingAmbienceId = incomingAmbienceIds.firstOrNull(),
            incomingAmbienceIds = incomingAmbienceIds,
            incomingFreesoundMusicQuery = freesoundContinuity.musicQuery,
            incomingFreesoundAmbienceQueries = freesoundContinuity.ambienceQueries,
        )
        return when (
            val outcome = ai.planNarration(
                NarrationPlanRequest(
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    chapterTitle = content.chapter.title,
                    rawText = chapterBody(content),
                    includeVoiceCast = effectiveVoice,
                    includeSceneMusic = effectiveMusic,
                    includeAmbience = effectiveAmbience,
                    includeSoundEffects = effectiveSfx,
                    includeFreesoundAudioRequirements = freesoundNeeded,
                    freesoundRequirementKinds = if (freesoundNeeded) freesoundKinds else emptySet(),
                    tracks = if (effectiveMusic) musicTracks.map { it.toOption() } else emptyList(),
                    ambienceTracks = if (effectiveAmbience) ambienceTracks.map { it.toOption() } else emptyList(),
                    soundEffectTracks = if (effectiveSfx) soundEffectTracks.map { it.toOption() } else emptyList(),
                    context = context,
                ),
            )
        ) {
            is AppResult.Failure -> Result(
                voicePlanCreated = false,
                musicPlanCreated = restoredFreesound.musicCreated,
                warnings = (warnings + outcome.message).distinct(),
                usedUnifiedRequest = true,
                audioPlanCreated = restoredFreesound.audioCreated,
                freesoundPlanCreated = restoredFreesound.musicCreated || restoredFreesound.audioCreated,
                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
                freesoundDownloadedAssets = restoredFreesound.downloadedTrackIds.size,
                freesoundReusedAssets = (restoredFreesound.reusedTrackIds - restoredFreesound.downloadedTrackIds).size,
                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundRetryAttempts = restoredFreesound.attempts,
                freesoundRetryExhausted = restoredFreesound.retryExhausted,
                freesoundDiagnostics = (freesoundDiagnostics + restoredFreesound.diagnostics + "AI_PLAN_FAILURE error=${outcome.message.take(220)}").distinct(),
            )
            is AppResult.Success -> {
                if (effectiveVoice) warnings += outcome.value.voiceCast.warnings
                outcome.value.musicSceneError.takeIf(String::isNotBlank)?.let(warnings::add)
                outcome.value.audioDirectionError.takeIf(String::isNotBlank)?.let(warnings::add)
                outcome.value.freesoundRequirementError.takeIf(String::isNotBlank)?.let(warnings::add)
                if (freesoundMode) {
                    freesoundDiagnostics += "AI_REQUIREMENTS_RESULT requested=$freesoundNeeded count=${outcome.value.freesoundRequirements.size} error=${outcome.value.freesoundRequirementError.take(220)}"
                }

                val voiceCreated = if (effectiveVoice) {
                    runCatching { persistVoicePlan(content, outcome.value.voiceCast) }
                        .fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch giọng."; false })
                } else false
                val localMusicCreated = if (effectiveMusic) {
                    runCatching {
                        persistMusicPlan(
                            content,
                            musicTracks,
                            outcome.value.musicCues,
                            outcome.value.musicSceneError,
                            StoryAudioSourceMode.AI_LOCAL,
                        )
                    }.fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch nhạc."; false })
                } else false
                val localAudioCreated = if (effectiveAmbience || effectiveSfx) {
                    runCatching {
                        persistAudioDirectionPlan(
                            content = content,
                            ambienceTracks = if (effectiveAmbience) ambienceTracks else emptyList(),
                            soundEffectTracks = if (effectiveSfx) soundEffectTracks else emptyList(),
                            plan = AmbienceSfxPlan(
                                ambienceScenes = outcome.value.ambienceScenes,
                                soundEffectCues = outcome.value.soundEffectCues,
                            ),
                            error = outcome.value.audioDirectionError,
                            ambienceEnabled = effectiveAmbience,
                            soundEffectsEnabled = effectiveSfx,
                            sourceMode = StoryAudioSourceMode.AI_LOCAL,
                        )
                    }.fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch ambience/SFX."; false })
                } else false

                var autoApplied = restoredFreesound
                var markerCreated = false
                if (freesoundNeeded && outcome.value.freesoundRequirementError.isBlank()) {
                    if (outcome.value.freesoundRequirements.isEmpty()) {
                        warnings += "AI không trả yêu cầu MUSIC/AMBIENCE/SFX Mode 3; sẽ tự yêu cầu AI lập lại sau thời gian chờ."
                        autoApplied = FreesoundApplyResult(
                            musicCreated = false,
                            audioCreated = false,
                            resolvedAssets = 0,
                            warnings = emptyList(),
                            retryableFailure = true,
                            diagnostics = listOf("AI_REQUIREMENTS_EMPTY fresh=true"),
                        )
                    } else {
                        autoApplied = applyFreesoundRequirements(content, outcome.value.freesoundRequirements, freesoundKinds)
                        warnings += autoApplied.warnings
                    }
                    runCatching {
                        persistFreesoundRequirements(
                            content,
                            freesoundKinds,
                            outcome.value.freesoundRequirements,
                            retryRequired = autoApplied.retryableFailure,
                            resolvedAssets = autoApplied.resolvedAssets,
                            retryAttempts = autoApplied.attempts,
                            retryExhausted = autoApplied.retryExhausted,
                        )
                    }.onSuccess { markerCreated = true }
                        .onFailure { warnings += it.message ?: "Không lưu được cache kế hoạch Mode 3 tự động." }
                }

                Result(
                    voicePlanCreated = voiceCreated,
                    musicPlanCreated = localMusicCreated || autoApplied.musicCreated,
                    warnings = warnings.distinct(),
                    usedUnifiedRequest = true,
                    audioPlanCreated = localAudioCreated || autoApplied.audioCreated,
                    freesoundPlanCreated = markerCreated || autoApplied.musicCreated || autoApplied.audioCreated,
                    freesoundResolvedAssets = autoApplied.resolvedAssets,
                    freesoundDownloadedAssets = autoApplied.downloadedTrackIds.size,
                    freesoundReusedAssets = (autoApplied.reusedTrackIds - autoApplied.downloadedTrackIds).size,
                    freesoundRetryRequired = autoApplied.retryableFailure,
                    freesoundRetryAttempts = autoApplied.attempts,
                    freesoundRetryExhausted = autoApplied.retryExhausted,
                    freesoundDiagnostics = (freesoundDiagnostics + autoApplied.diagnostics).distinct(),
                )
            }
        }
    }

    suspend fun loadAudioDirectionPlan(content: ChapterContent): AmbienceSfxPlan? {
        val sourceMode = storyAudioModeStore.get()
        if (StoryAudioModeRouter.usesManualLocal(sourceMode)) return AmbienceSfxPlan()
        val effectiveContent = currentPlaybackContent(content)
        val audioSettings = AudioDirectionPreferences.currentSnapshot()
        if (!audioSettings.ambienceEnabled && !audioSettings.soundEffectsEnabled) return AmbienceSfxPlan()
        val enabledAssets = library.listEnabledSceneMusicTracks()
        val sourceAssets = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            val usableIds = freesoundResolver.usableLibraryTrackIds(setOf(AudioAssetKind.AMBIENCE, AudioAssetKind.SFX))
            enabledAssets.filter { it.id in usableIds }
        } else enabledAssets
        val ambienceTracks = if (audioSettings.ambienceEnabled) {
            sourceAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        } else emptyList()
        val soundEffectTracks = if (audioSettings.soundEffectsEnabled) {
            sourceAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        } else emptyList()
        val effectiveAmbience = audioSettings.ambienceEnabled && ambienceTracks.isNotEmpty()
        val effectiveSfx = audioSettings.soundEffectsEnabled && soundEffectTracks.isNotEmpty()
        if (!effectiveAmbience && !effectiveSfx) return AmbienceSfxPlan()

        val sourceHash = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            mode3AudioDirectionSourceHash(effectiveContent, effectiveAmbience, effectiveSfx)
        } else {
            audioDirectionSourceHash(
                effectiveContent,
                ambienceTracks,
                soundEffectTracks,
                effectiveAmbience,
                effectiveSfx,
            )
        }
        val cached = library.getChapterTransform(effectiveContent.chapter.id, KIND_AUDIO_DIRECTION) ?: return null
        if (cached.sourceSha256 != sourceHash) return null
        if (!isExpectedAudioSourceMode(cached.transformedText, sourceMode)) return null
        if (!isCurrentTimelineTransform(cached.transformedText, XpkAmbienceSfxDirector.ENGINE, effectiveContent)) return null
        val unitIds = XpkVoiceCastSplitter.buildUnits(
            effectiveContent.chapter.title,
            chapterBody(effectiveContent),
        ).map { it.id }
        return runCatching {
            XpkAmbienceSfxDirector.decodePersisted(
                text = cached.transformedText,
                validUnitIds = unitIds,
                validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                validSfxIds = soundEffectTracks.map(SceneMusicTrackEntity::id).toSet(),
                ambienceEnabled = effectiveAmbience,
                soundEffectsEnabled = effectiveSfx,
            )
        }.getOrNull()
    }

    suspend fun voicePlanAssignmentCount(content: ChapterContent): Int {
        val effectiveContent = currentPlaybackContent(content)
        val sourceHash = voiceSourceHash(effectiveContent)
        val cached = library.getChapterTransform(effectiveContent.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
            ?: return 0
        if (cached.sourceSha256 != sourceHash) return 0
        if (!isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, effectiveContent)) return 0
        return runCatching {
            JSONObject(cached.transformedText).optJSONArray("assignments")?.length() ?: 0
        }.getOrDefault(0)
    }

    suspend fun shouldAutoVoiceCast(storyId: String): Boolean {
        val appEnabled = settings.snapshot().autoVoiceCastEnabled
        if (!appEnabled) return false
        val profile = library.getStoryAiProfile(storyId) ?: return false
        val raw = profile.voiceCastNote
        if (!StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) return false
        val storyVoice = StoryVoiceCastReferenceCodec.decode(raw)
        if (storyVoice.mode == StoryVoiceCastMode.OFF) return false
        return storyVoice.autoRunOnOpenTts
    }

    suspend fun expressiveAdjustmentEnabled(storyId: String): Boolean =
        library.getStoryAiProfile(storyId)?.expressiveAdjustment == true

    suspend fun musicSourceHashForPlayback(
        content: ChapterContent,
        tracks: List<SceneMusicTrackEntity>,
    ): String {
        val effectiveContent = currentPlaybackContent(content)
        return if (StoryAudioModeRouter.usesAiFreesound(storyAudioModeStore.get())) {
            mode3MusicSourceHash(effectiveContent)
        } else {
            musicSourceHash(effectiveContent, tracks)
        }
    }

    private suspend fun storyVoiceSettings(storyId: String): StoryVoiceCastReferenceSettings =
        library.getStoryAiProfile(storyId)?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }
            ?: StoryVoiceCastReferenceSettings()

    suspend fun effectiveVoiceRoles(storyId: String): List<VoiceRoleEntity> =
        when (storyVoiceSettings(storyId).mode) {
            StoryVoiceCastMode.OFF -> emptyList()
            StoryVoiceCastMode.PRIVATE -> library.listVoiceRoles(storyId).filter(VoiceRoleEntity::enabled)
            StoryVoiceCastMode.GLOBAL -> library.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
        }

    private suspend fun needsVoicePlan(content: ChapterContent, force: Boolean): Boolean {
        if (storyVoiceSettings(content.chapter.storyId).mode == StoryVoiceCastMode.OFF) return false
        if (force) return true
        val sourceHash = voiceSourceHash(content)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
        if (cached?.sourceSha256 != sourceHash) return true
        return !isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, content)
    }

    private suspend fun needsMusicPlan(
        content: ChapterContent,
        tracks: List<SceneMusicTrackEntity>,
        force: Boolean,
        expectedMode: StoryAudioSourceMode,
    ): Boolean {
        if (force) return true
        if (tracks.isEmpty()) return false
        val sourceHash = musicSourceHash(content, tracks)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC)
        if (cached?.sourceSha256 != sourceHash) return true
        if (!isExpectedAudioSourceMode(cached.transformedText, expectedMode)) return true
        return !isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)
    }

    private suspend fun needsAudioDirectionPlan(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
        soundEffectTracks: List<SceneMusicTrackEntity>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
        force: Boolean,
        expectedMode: StoryAudioSourceMode,
    ): Boolean {
        if (!ambienceEnabled && !soundEffectsEnabled) return false
        if (force) return true
        val sourceHash = audioDirectionSourceHash(
            content,
            ambienceTracks,
            soundEffectTracks,
            ambienceEnabled,
            soundEffectsEnabled,
        )
        val cached = library.getChapterTransform(content.chapter.id, KIND_AUDIO_DIRECTION)
        if (cached?.sourceSha256 != sourceHash) return true
        if (!isExpectedAudioSourceMode(cached.transformedText, expectedMode)) return true
        return !isCurrentTimelineTransform(cached.transformedText, XpkAmbienceSfxDirector.ENGINE, content)
    }

    private suspend fun cachedFreesoundTrackIds(
        requirements: List<FreesoundAutoRequirement>,
        kinds: Set<AudioAssetKind>,
    ): Set<String> {
        if (requirements.isEmpty() || kinds.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        requirements.forEach { requirement ->
            if (requirement.kind in kinds) {
                freesoundResolver.cachedLibraryTrackId(requirement.kind, requirement.query)?.let(result::add)
            }
        }
        return result
    }

    private suspend fun standardFreesoundPlanCurrent(
        content: ChapterContent,
        kinds: Set<AudioAssetKind>,
        requirements: List<FreesoundAutoRequirement>,
    ): Boolean {
        if (requirements.isEmpty()) return false
        val usableIds = freesoundResolver.usableLibraryTrackIds(kinds)
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { it.id in usableIds }
        val unitIds = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content)).map { it.id }
        var musicCues = emptyList<SceneMusicCue>()
        var audioPlan = AmbienceSfxPlan()

        if (AudioAssetKind.MUSIC in kinds) {
            val musicTracks = enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
            val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC) ?: return false
            if (cached.sourceSha256 != mode3MusicSourceHash(content)) return false
            if (!isExpectedAudioSourceMode(cached.transformedText, StoryAudioSourceMode.AI_FREESOUND)) return false
            if (!isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)) return false
            val rows = JSONObject(cached.transformedText).optJSONArray("music_scenes") ?: return false
            val raw = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: return false
                    add(XpkSceneMusicParity.RawScene(row.optString("start_id"), row.optString("end_id"), row.optString("track_id")))
                }
            }
            musicCues = runCatching {
                XpkSceneMusicParity.validateScenes(raw, unitIds, musicTracks.map(SceneMusicTrackEntity::id))
            }.getOrNull() ?: return false
        }

        if (AudioAssetKind.AMBIENCE in kinds || AudioAssetKind.SFX in kinds) {
            val ambienceTracks = if (AudioAssetKind.AMBIENCE in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
            } else emptyList()
            val sfxTracks = if (AudioAssetKind.SFX in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
            } else emptyList()
            val cached = library.getChapterTransform(content.chapter.id, KIND_AUDIO_DIRECTION) ?: return false
            if (cached.sourceSha256 != mode3AudioDirectionSourceHash(
                    content,
                    AudioAssetKind.AMBIENCE in kinds,
                    AudioAssetKind.SFX in kinds,
                )
            ) return false
            if (!isExpectedAudioSourceMode(cached.transformedText, StoryAudioSourceMode.AI_FREESOUND)) return false
            if (!isCurrentTimelineTransform(cached.transformedText, XpkAmbienceSfxDirector.ENGINE, content)) return false
            audioPlan = runCatching {
                XpkAmbienceSfxDirector.decodePersisted(
                    text = cached.transformedText,
                    validUnitIds = unitIds,
                    validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                    validSfxIds = sfxTracks.map(SceneMusicTrackEntity::id).toSet(),
                    ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                    soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                )
            }.getOrNull() ?: return false
        }

        val required = requirements.filter { it.importance == FreesoundRequirementImportance.REQUIRED }
        if (required.isEmpty()) return true
        val resolvedRequired = mutableListOf<FreesoundAutoResolvedNeed>()
        required.forEach { usage ->
            val trackId = freesoundResolver.cachedLibraryTrackId(usage.kind, usage.query) ?: return false
            val track = enabled.firstOrNull { it.id == trackId && AudioAssetClassifier.classify(it) == usage.kind } ?: return false
            resolvedRequired += FreesoundAutoResolvedNeed(
                need = FreesoundAutoSearchNeed(
                    kind = usage.kind,
                    query = usage.query,
                    importance = FreesoundRequirementImportance.REQUIRED,
                    usages = listOf(usage),
                ),
                trackId = track.id,
                source = "CACHE",
            )
        }
        return FreesoundAutoPlanBuilder.requiredCoverage(
            resolved = resolvedRequired,
            validUnitIds = unitIds,
            musicCues = musicCues,
            ambienceScenes = audioPlan.ambienceScenes,
            soundEffectCues = audioPlan.soundEffectCues,
        ).complete
    }

    private suspend fun applyFreesoundRequirements(
        content: ChapterContent,
        requirements: List<FreesoundAutoRequirement>,
        kinds: Set<AudioAssetKind>,
    ): FreesoundApplyResult {
        if (requirements.isEmpty()) {
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("AI chưa trả yêu cầu âm thanh Mode 3; chưa có truy vấn để retry runtime."),
                retryableFailure = true,
                diagnostics = listOf("RUNTIME_RETRY_SKIPPED reason=AI_REQUIREMENTS_EMPTY"),
                attempts = 0,
                retryExhausted = false,
            )
        }
        if (content.chapter.id in freesoundRetryExhaustedChapters) {
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("Mode 3 đã thất bại sau 3 lần; cần bắt đầu một lượt phân vai mới."),
                retryableFailure = true,
                diagnostics = listOf("RUNTIME_RETRY_BLOCKED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
        }

        var latest = FreesoundApplyResult(false, false, 0, emptyList())
        val warnings = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val downloadedTrackIds = linkedSetOf<String>()
        val reusedTrackIds = linkedSetOf<String>()
        for (attempt in 1..MAX_FREESOUND_RUNTIME_ATTEMPTS) {
            if (attempt > 1) {
                delay(FREESOUND_RUNTIME_RETRY_DELAY_MS)
                freesoundResolver.clearNetworkSearchCache()
            }
            diagnostics += "RUNTIME_RETRY_START attempt=$attempt/$MAX_FREESOUND_RUNTIME_ATTEMPTS"
            latest = applyFreesoundRequirementsOnce(content, requirements, kinds, attempt)
            downloadedTrackIds += latest.downloadedTrackIds
            reusedTrackIds += latest.reusedTrackIds
            warnings += latest.warnings
            diagnostics += latest.diagnostics
            diagnostics += "RUNTIME_RETRY_RESULT attempt=$attempt resolved=${latest.resolvedAssets} retryRequired=${latest.retryableFailure}"
            if (!latest.retryableFailure) {
                freesoundRetryExhaustedChapters.remove(content.chapter.id)
                return latest.copy(
                    warnings = warnings.distinct(),
                    downloadedTrackIds = downloadedTrackIds,
                    reusedTrackIds = reusedTrackIds - downloadedTrackIds,
                    diagnostics = diagnostics.distinct(),
                    attempts = attempt,
                    retryExhausted = false,
                )
            }
        }

        freesoundRetryExhaustedChapters += content.chapter.id
        val partialResolved = latest.resolvedAssets > 0
        return latest.copy(
            warnings = (warnings + if (partialResolved) {
                "Một số âm thanh Mode 3 còn thiếu sau 3 lần; ứng dụng sẽ phát phần đã resolve hợp lệ thay vì làm câm cả chương."
            } else {
                "Mode 3 chưa resolve được asset nào sau 3 lần; TTS vẫn được phép phát."
            }).distinct(),
            downloadedTrackIds = downloadedTrackIds,
            reusedTrackIds = reusedTrackIds - downloadedTrackIds,
            retryableFailure = false,
            diagnostics = (diagnostics + "RUNTIME_RETRY_EXHAUSTED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS partialResolved=$partialResolved").distinct(),
            attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
            retryExhausted = true,
        )
    }

    private suspend fun applyFreesoundRequirementsOnce(
        content: ChapterContent,
        requirements: List<FreesoundAutoRequirement>,
        kinds: Set<AudioAssetKind>,
        retryAttempt: Int,
    ): FreesoundApplyResult {
        val resolved = freesoundResolver.resolve(
            requirements = requirements,
            retryAttempt = retryAttempt,
            retryMax = MAX_FREESOUND_RUNTIME_ATTEMPTS,
        )
        val warnings = resolved.warnings.toMutableList()
        val diagnostics = resolved.diagnostics.toMutableList()
        val units = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content))
        val unitIds = units.map { it.id }
        val usableIds = freesoundResolver.usableLibraryTrackIds(kinds)
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { it.id in usableIds }
        diagnostics += "PLAN_BUILD_START requirements=${requirements.size} units=${unitIds.size} libraryTracks=${enabled.size} kinds=${kinds.map(AudioAssetKind::name).sorted().joinToString(",")}"
        var musicCreated = false
        var audioCreated = false
        val requiredKinds = requirements
            .filter { it.importance == FreesoundRequirementImportance.REQUIRED }
            .map(FreesoundAutoRequirement::kind)
            .toSet()
        var requiredMusicMissing = false
        var requiredAmbienceMissing = false
        var requiredSfxMissing = false

        if (AudioAssetKind.MUSIC in kinds) {
            val musicTracks = enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
            val musicIds = musicTracks.map(SceneMusicTrackEntity::id).toSet()
            val rawCues = FreesoundAutoPlanBuilder.musicCues(resolved.resolved, unitIds, musicIds)
            val validated = runCatching {
                XpkSceneMusicParity.validateScenes(
                    rawCues.map { cue -> XpkSceneMusicParity.RawScene(cue.startUnitId, cue.endUnitId, cue.trackId) },
                    unitIds,
                    musicTracks.map(SceneMusicTrackEntity::id),
                )
            }.getOrElse { firstError ->
                warnings += "Kế hoạch MUSIC Mode 3 có vùng chưa hợp lệ; chỉ vùng lỗi được đưa về im lặng/ghép ổn định thay vì làm im lặng cả chương: ${firstError.message.orEmpty()}"
                val salvaged = FreesoundAutoPlanBuilder.salvageMusicCues(rawCues, unitIds, musicIds)
                runCatching {
                    XpkSceneMusicParity.validateScenes(
                        salvaged.map { cue -> XpkSceneMusicParity.RawScene(cue.startUnitId, cue.endUnitId, cue.trackId) },
                        unitIds,
                        musicTracks.map(SceneMusicTrackEntity::id),
                    )
                }.getOrElse {
                    XpkSceneMusicParity.fallbackScene(unitIds, emptyList(), null)
                }
            }
            val musicCoverage = FreesoundAutoPlanBuilder.requiredCoverage(
                resolved = resolved.resolved.filter { it.need.kind == AudioAssetKind.MUSIC },
                validUnitIds = unitIds,
                musicCues = validated,
            )
            requiredMusicMissing = musicCoverage.missingMusicUsages > 0
            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} libraryMusicTracks=${musicTracks.size} requiredMissing=$requiredMusicMissing"
            runCatching {
                persistMusicPlan(
                    content,
                    musicTracks,
                    validated,
                    "",
                    StoryAudioSourceMode.AI_FREESOUND,
                )
            }.onSuccess {
                musicCreated = true
                diagnostics += "PLAN_MUSIC_PERSIST success=true"
            }.onFailure {
                warnings += it.message ?: "Không lưu được MUSIC Mode 3."
                diagnostics += "PLAN_MUSIC_PERSIST success=false error=${(it.message ?: it::class.java.simpleName).take(220)}"
            }
        }

        if (AudioAssetKind.AMBIENCE in kinds || AudioAssetKind.SFX in kinds) {
            val ambienceTracks = if (AudioAssetKind.AMBIENCE in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
            } else emptyList()
            val sfxTracks = if (AudioAssetKind.SFX in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
            } else emptyList()

            val ambienceCandidate = if (AudioAssetKind.AMBIENCE in kinds) {
                FreesoundAutoPlanBuilder.ambienceScenes(resolved.resolved, unitIds)
            } else emptyList()
            val originalAmbienceCount = ambienceCandidate.size
            val validatedAmbience = runCatching {
                XpkAmbienceSfxDirector.parseAndValidate(
                    XpkAmbienceSfxDirector.encode(AmbienceSfxPlan(ambienceScenes = ambienceCandidate)),
                    validUnitIds = unitIds,
                    validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                    validSfxIds = emptySet(),
                    ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                    soundEffectsEnabled = false,
                )
            }.getOrElse { error ->
                warnings += "AMBIENCE Mode 3 còn cue không hợp lệ sau bước sắp xếp/lọc xung đột: ${error.message.orEmpty()}"
                AmbienceSfxPlan()
            }

            val sfxCandidate = if (AudioAssetKind.SFX in kinds) {
                FreesoundAutoPlanBuilder.soundEffectCues(resolved.resolved, unitIds)
            } else emptyList()
            val originalSfxCount = sfxCandidate.size
            val validatedSfx = runCatching {
                XpkAmbienceSfxDirector.parseAndValidate(
                    XpkAmbienceSfxDirector.encode(AmbienceSfxPlan(soundEffectCues = sfxCandidate)),
                    validUnitIds = unitIds,
                    validAmbienceIds = emptySet(),
                    validSfxIds = sfxTracks.map(SceneMusicTrackEntity::id).toSet(),
                    ambienceEnabled = false,
                    soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                )
            }.getOrElse { error ->
                warnings += "SFX Mode 3 còn cue không hợp lệ sau bước sắp xếp/lọc xung đột: ${error.message.orEmpty()}"
                AmbienceSfxPlan()
            }

            val audioCoverage = FreesoundAutoPlanBuilder.requiredCoverage(
                resolved = resolved.resolved.filter { it.need.kind == AudioAssetKind.AMBIENCE || it.need.kind == AudioAssetKind.SFX },
                validUnitIds = unitIds,
                ambienceScenes = validatedAmbience.ambienceScenes,
                soundEffectCues = validatedSfx.soundEffectCues,
            )

            val validated = AmbienceSfxPlan(
                ambienceScenes = validatedAmbience.ambienceScenes,
                soundEffectCues = validatedSfx.soundEffectCues,
            )
            requiredAmbienceMissing = audioCoverage.missingAmbienceUsages > 0
            requiredSfxMissing = audioCoverage.missingSfxUsages > 0
            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=${validatedAmbience.ambienceScenes.size} ambienceTracks=${ambienceTracks.size} ambienceRequiredMissing=$requiredAmbienceMissing sfxCandidates=$originalSfxCount sfxValidated=${validatedSfx.soundEffectCues.size} sfxTracks=${sfxTracks.size} sfxRequiredMissing=$requiredSfxMissing"
            runCatching {
                persistAudioDirectionPlan(
                    content = content,
                    ambienceTracks = ambienceTracks,
                    soundEffectTracks = sfxTracks,
                    plan = validated,
                    error = "",
                    ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                    soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                    sourceMode = StoryAudioSourceMode.AI_FREESOUND,
                )
            }.onSuccess {
                audioCreated = true
                diagnostics += "PLAN_AUDIO_PERSIST success=true"
            }.onFailure {
                warnings += it.message ?: "Không lưu được AMBIENCE/SFX Mode 3."
                diagnostics += "PLAN_AUDIO_PERSIST success=false error=${(it.message ?: it::class.java.simpleName).take(220)}"
            }
        }

        val requiredPlanMissing = requiredMusicMissing || requiredAmbienceMissing || requiredSfxMissing
        val retryRecommended = resolved.shouldRetryIncomplete || requiredPlanMissing
        if (retryRecommended && requirements.isNotEmpty()) {
            warnings += if (requiredPlanMissing) {
                "Mode 3 đã resolve asset nhưng chưa tạo được cue bắt buộc hợp lệ; ứng dụng sẽ tự thử lại tối đa 3 lần."
            } else {
                "Mode 3 chưa resolve đủ âm thanh quan trọng; ứng dụng sẽ tự thử lại mà không cần phân vai lại."
            }
        }
        diagnostics += "PLAN_REQUIRED_COVERAGE musicMissing=$requiredMusicMissing ambienceMissing=$requiredAmbienceMissing sfxMissing=$requiredSfxMissing"
        val resolvedTrackIds = resolved.resolved.mapNotNull { it.trackId?.takeIf(String::isNotBlank) }.toSet()
        val downloadedTrackIds = resolved.importedTrackIds
        val reusedTrackIds = resolvedTrackIds - downloadedTrackIds
        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} uniqueTracks=${resolvedTrackIds.size} downloaded=${downloadedTrackIds.size} reused=${reusedTrackIds.size} unresolved=${resolved.unresolvedCount} unresolvedRequired=${resolved.unresolvedRequiredCount} retryableFailure=${resolved.retryableFailure} requiredPlanMissing=$requiredPlanMissing retryRecommended=$retryRecommended"
        return FreesoundApplyResult(
            musicCreated = musicCreated,
            audioCreated = audioCreated,
            resolvedAssets = resolved.resolvedCount,
            warnings = warnings.distinct(),
            downloadedTrackIds = downloadedTrackIds,
            reusedTrackIds = reusedTrackIds,
            retryableFailure = retryRecommended,
            diagnostics = diagnostics.distinct(),
            attempts = 1,
            retryExhausted = false,
        )
    }

    /** Canonical XPK assignments live in chapter_transforms; paragraph rows are legacy-only. */
    private suspend fun persistVoicePlan(content: ChapterContent, plan: VoiceCastPlan) {
        val appSettings = settings.snapshot()
        val canonicalAssignments = plan.assignments
            .filter { it.unitId.isNotBlank() && it.voiceId.isNotBlank() }
            .distinctBy { it.unitId }

        library.replaceVoiceAssignments(content.chapter.storyId, content.chapter.id, emptyList())
        val timelineFingerprint = timelineFingerprint(content)
        val payload = JSONObject()
            .put("engine", VOICE_TRANSFORM_ENGINE)
            .put("splitter_version", XpkVoiceCastSplitter.ENGINE_VERSION)
            .put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)
            .put("timeline_fingerprint", timelineFingerprint)
            .put(
                "assignments",
                JSONArray().also { array ->
                    canonicalAssignments.forEach { assignment ->
                        array.put(
                            JSONObject()
                                .put("id", assignment.unitId)
                                .put("voice", assignment.voiceId)
                                .put("speed_adjust_pct", assignment.speedAdjustPct.toDouble())
                                .put("pitch_adjust_pct", assignment.pitchAdjustPct.toDouble())
                                .put("volume_adjust_pct", assignment.volumeAdjustPct.toDouble()),
                        )
                    }
                },
            )
            .put("warnings", JSONArray(plan.warnings))
            .toString()
        val (provider, model) = effectiveAiMetadata(
            content.chapter.storyId,
            appSettings.aiOnline.provider.name,
            appSettings.aiOnline.model,
        )
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = ChapterAiWorkflow.KIND_VOICE_CAST,
                provider = provider,
                model = model,
                sourceSha256 = voiceSourceHash(content),
                transformedText = payload,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistMusicPlan(
        content: ChapterContent,
        tracks: List<SceneMusicTrackEntity>,
        plannedCues: List<SceneMusicCue>,
        musicSceneError: String,
        sourceMode: StoryAudioSourceMode,
    ) {
        val allowed = tracks.associateBy { it.id }
        library.replaceSceneMusicCues(content.chapter.storyId, content.chapter.id, emptyList())
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(content.chapter.storyId, appSettings.aiOnline.provider.name, appSettings.aiOnline.model)
        val unitScenes = JSONArray().also { array ->
            plannedCues.forEach { cue ->
                val validTrack = cue.trackId == XpkSceneMusicParity.SILENCE_TRACK_ID || allowed.containsKey(cue.trackId)
                if (cue.startUnitId.isNotBlank() && cue.endUnitId.isNotBlank() && validTrack) {
                    array.put(
                        JSONObject()
                            .put("start_id", cue.startUnitId)
                            .put("end_id", cue.endUnitId)
                            .put("track_id", cue.trackId),
                    )
                }
            }
        }
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = ChapterAiWorkflow.KIND_SCENE_MUSIC,
                provider = provider,
                model = model,
                sourceSha256 = if (sourceMode == StoryAudioSourceMode.AI_FREESOUND) {
                    mode3MusicSourceHash(content)
                } else {
                    musicSourceHash(content, tracks)
                },
                transformedText = JSONObject()
                    .put("engine", MUSIC_TRANSFORM_ENGINE)
                    .put("mode", XpkSceneMusicParity.MODE)
                    .put("audio_source_mode", sourceMode.name)
                    .put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)
                    .put("timeline_fingerprint", timelineFingerprint(content))
                    .put("music_scenes", unitScenes)
                    .put("music_scene_error", musicSceneError)
                    .toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistAudioDirectionPlan(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
        soundEffectTracks: List<SceneMusicTrackEntity>,
        plan: AmbienceSfxPlan,
        error: String,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
        sourceMode: StoryAudioSourceMode,
    ) {
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(
            content.chapter.storyId,
            appSettings.aiOnline.provider.name,
            appSettings.aiOnline.model,
        )
        val payload = JSONObject(XpkAmbienceSfxDirector.encode(plan))
            .put("audio_source_mode", sourceMode.name)
            .put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)
            .put("timeline_fingerprint", timelineFingerprint(content))
            .put("audio_direction_error", error)
            .toString()
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, KIND_AUDIO_DIRECTION),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = KIND_AUDIO_DIRECTION,
                provider = provider,
                model = model,
                sourceSha256 = if (sourceMode == StoryAudioSourceMode.AI_FREESOUND) {
                    mode3AudioDirectionSourceHash(content, ambienceEnabled, soundEffectsEnabled)
                } else {
                    audioDirectionSourceHash(
                        content,
                        ambienceTracks,
                        soundEffectTracks,
                        ambienceEnabled,
                        soundEffectsEnabled,
                    )
                },
                transformedText = payload,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistFreesoundRequirements(
        content: ChapterContent,
        kinds: Set<AudioAssetKind>,
        requirements: List<FreesoundAutoRequirement>,
        retryRequired: Boolean = false,
        resolvedAssets: Int = 0,
        retryAttempts: Int = 0,
        retryExhausted: Boolean = false,
    ) {
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(
            content.chapter.storyId,
            appSettings.aiOnline.provider.name,
            appSettings.aiOnline.model,
        )
        val payload = JSONObject()
            .put("engine", FREESOUND_AUTO_ENGINE)
            .put("audio_source_mode", StoryAudioSourceMode.AI_FREESOUND.name)
            .put("timeline_fingerprint_version", XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION)
            .put("timeline_fingerprint", timelineFingerprint(content))
            .put("enabled_kinds", JSONArray(kinds.map(AudioAssetKind::name).sorted()))
            .put("requirement_count", requirements.size)
            .put("resolved_asset_count", resolvedAssets.coerceAtLeast(0))
            .put("resolution_state", when {
                requirements.isEmpty() -> "AI_EMPTY"
                retryExhausted && resolvedAssets > 0 -> "PARTIAL"
                retryExhausted -> "FAILED"
                retryRequired -> "INCOMPLETE"
                else -> "COMPLETE"
            })
            .put("resolution_retry_required", retryRequired && !retryExhausted)
            .put("resolution_retry_attempts", retryAttempts.coerceIn(0, MAX_FREESOUND_RUNTIME_ATTEMPTS))
            .put("resolution_retry_exhausted", retryExhausted)
            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))
            .toString()
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = KIND_FREESOUND_AUTO_AUDIO,
                provider = provider,
                model = model,
                sourceSha256 = freesoundSourceHash(content, kinds),
                transformedText = payload,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun loadFreesoundRequirements(
        content: ChapterContent,
        kinds: Set<AudioAssetKind>,
    ): List<FreesoundAutoRequirement>? {
        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return null
        if (cached.sourceSha256 != freesoundSourceHash(content, kinds)) return null
        val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return null
        if (root.optString("engine") != FREESOUND_AUTO_ENGINE) return null
        if (root.optString("audio_source_mode") != StoryAudioSourceMode.AI_FREESOUND.name) return null
        if (!isCurrentTimelineTransform(cached.transformedText, FREESOUND_AUTO_ENGINE, content)) return null
        val unitIds = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content)).map { it.id }
        return runCatching { FreesoundAutoRequirementCodec.parse(root, unitIds, kinds) }.getOrNull()
    }

    private suspend fun freesoundResolutionRetryRequired(content: ChapterContent): Boolean {
        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return false
        val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false
        return root.optBoolean("resolution_retry_required", false)
    }

    private suspend fun freesoundResolutionRetryExhausted(content: ChapterContent): Boolean {
        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return false
        val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false
        return root.optBoolean("resolution_retry_exhausted", false)
    }

    private suspend fun freesoundEmptyAiRetryDue(content: ChapterContent): Boolean {
        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return true
        return System.currentTimeMillis() - cached.updatedAt >= FREESOUND_EMPTY_AI_RETRY_COOLDOWN_MS
    }

    private suspend fun buildContinuityContext(
        content: ChapterContent,
        activeTrackId: String?,
        tracks: List<SceneMusicTrackEntity>,
    ): NarrationPlanContext {
        val validTracks = tracks.associateBy(SceneMusicTrackEntity::id)
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index)
        val previousTail = previous?.let {
            XpkSceneMusicParity.continuityTailForPrompt(
                title = it.chapter.title,
                body = chapterBody(it),
                maxUnits = 5,
            )
        }.orEmpty()
        val previousTransform = previous?.chapter?.id?.let { chapterId ->
            library.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_SCENE_MUSIC)
        }
        val plannedFinalTrack = if (previous != null && previousTransform != null &&
            isCurrentTimelineTransform(previousTransform.transformedText, MUSIC_TRANSFORM_ENGINE, previous)
        ) {
            runCatching {
                val scenes = JSONObject(previousTransform.transformedText).optJSONArray("music_scenes")
                    ?: return@runCatching null
                scenes.optJSONObject(scenes.length() - 1)?.optString("track_id")?.trim()?.takeIf(String::isNotBlank)
            }.getOrNull()
        } else null
        val previousCue = previous?.chapter?.id
            ?.let { library.listSceneMusicCues(it).maxByOrNull(SceneMusicCueEntity::startParagraph) }
        val plannedCandidate = (plannedFinalTrack ?: previousCue?.trackId)?.takeIf(validTracks::containsKey)
        val currentCandidate = activeTrackId?.trim()?.takeIf { it.isNotBlank() && validTracks.containsKey(it) }
        val continuityTrackId = plannedCandidate ?: currentCandidate
        val continuitySource = when {
            plannedCandidate != null -> "final_scene"
            currentCandidate != null -> "current_track"
            else -> "none"
        }
        val track = continuityTrackId?.let(validTracks::get)
        return NarrationPlanContext(
            previousChapterEnding = previousTail,
            activeTrackId = continuityTrackId,
            activeTrackTitle = track?.title,
            previousMood = previousCue?.mood.orEmpty(),
            incomingSource = continuitySource,
        )
    }

    private suspend fun buildIncomingAmbienceIds(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
    ): List<String> {
        if (ambienceTracks.isEmpty()) return emptyList()
        val allowed = ambienceTracks.map(SceneMusicTrackEntity::id).toSet()
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index) ?: return emptyList()
        val transform = library.getChapterTransform(previous.chapter.id, KIND_AUDIO_DIRECTION) ?: return emptyList()
        if (!isCurrentTimelineTransform(transform.transformedText, XpkAmbienceSfxDirector.ENGINE, previous)) return emptyList()
        val finalUnitId = XpkVoiceCastSplitter.buildUnits(previous.chapter.title, chapterBody(previous))
            .lastOrNull()
            ?.id
            ?: return emptyList()
        return runCatching {
            val scenes = JSONObject(transform.transformedText).optJSONArray("ambience_scenes") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until scenes.length()) {
                    val row = scenes.optJSONObject(index) ?: continue
                    if (row.optString("end_id").trim() != finalUnitId) continue
                    val ambienceId = row.optString("ambience_id").trim()
                    if (ambienceId in allowed && ambienceId !in this) add(ambienceId)
                    if (size >= AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE) break
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun buildFreesoundContinuityContext(content: ChapterContent): FreesoundContinuityContext {
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index)
            ?: return FreesoundContinuityContext()
        val transform = library.getChapterTransform(previous.chapter.id, KIND_FREESOUND_AUTO_AUDIO)
            ?: return FreesoundContinuityContext()
        if (!isCurrentTimelineTransform(transform.transformedText, FREESOUND_AUTO_ENGINE, previous)) {
            return FreesoundContinuityContext()
        }
        val root = runCatching { JSONObject(transform.transformedText) }.getOrNull()
            ?: return FreesoundContinuityContext()
        if (root.optString("audio_source_mode") != StoryAudioSourceMode.AI_FREESOUND.name) {
            return FreesoundContinuityContext()
        }
        val enabledKinds = buildSet {
            val stored = root.optJSONArray("enabled_kinds")
            if (stored != null) {
                for (index in 0 until stored.length()) {
                    runCatching { AudioAssetKind.valueOf(stored.optString(index).trim()) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.ifEmpty { AudioAssetKind.entries.toSet() }
        val units = XpkVoiceCastSplitter.buildUnits(previous.chapter.title, chapterBody(previous))
        val unitIds = units.map { it.id }
        val finalUnitId = unitIds.lastOrNull() ?: return FreesoundContinuityContext()
        val order = unitIds.withIndex().associate { it.value to it.index }
        val requirements = runCatching {
            FreesoundAutoRequirementCodec.parse(root, unitIds, enabledKinds)
        }.getOrDefault(emptyList())
        val boundaryRows = requirements.filter { requirement ->
            requirement.kind in setOf(AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE) &&
                requirement.endUnitId == finalUnitId
        }
        val priority = compareBy<FreesoundAutoRequirement> {
            it.importance != FreesoundRequirementImportance.REQUIRED
        }.thenByDescending { requirement ->
            requirement.startUnitId?.let(order::get) ?: -1
        }
        val musicQuery = boundaryRows
            .filter { it.kind == AudioAssetKind.MUSIC }
            .sortedWith(priority)
            .firstOrNull()
            ?.query
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val ambienceQueries = boundaryRows
            .filter { it.kind == AudioAssetKind.AMBIENCE }
            .sortedWith(priority)
            .map { it.query.trim() }
            .filter(String::isNotBlank)
            .distinct()
        return FreesoundContinuityContext(
            musicQuery = musicQuery,
            ambienceQueries = ambienceQueries,
        )
    }

    private fun isExpectedAudioSourceMode(transformedText: String, expected: StoryAudioSourceMode): Boolean = runCatching {
        val stored = JSONObject(transformedText).optString("audio_source_mode").trim()
        if (stored.isBlank()) expected == StoryAudioSourceMode.AI_LOCAL else stored == expected.name
    }.getOrDefault(false)

    private fun isCurrentTimelineTransform(
        transformedText: String,
        expectedEngine: String,
        content: ChapterContent,
    ): Boolean = runCatching {
        val root = JSONObject(transformedText)
        root.optString("engine") == expectedEngine &&
            root.optInt("timeline_fingerprint_version", 0) == XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION &&
            root.optString("timeline_fingerprint").trim() == timelineFingerprint(content)
    }.getOrDefault(false)

    private fun currentPlaybackContent(content: ChapterContent): ChapterContent {
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.chapterId != content.chapter.id || snapshot.paragraphs.isEmpty()) return content
        val runtimeParagraphs = XpkPlaybackRuntime.canonicalLines(snapshot.paragraphs)
        return if (runtimeParagraphs == canonicalParagraphs(content)) content
        else content.copy(paragraphs = runtimeParagraphs)
    }

    private fun canonicalParagraphs(content: ChapterContent): List<String> =
        XpkPlaybackRuntime.canonicalLines(content.paragraphs)

    private fun timelineFingerprint(content: ChapterContent): String =
        XpkPlaybackRuntime.timelineFingerprint(content.chapter.title, canonicalParagraphs(content))

    private fun chapterBody(content: ChapterContent): String = canonicalParagraphs(content).joinToString("\n")

    private suspend fun voiceSourceHash(content: ChapterContent): String {
        val source = library.loadCachedChapter(content.chapter.id)
        return ChapterAiWorkflow.sha256(source?.paragraphs ?: content.paragraphs)
    }

    private fun mode3MusicSourceHash(content: ChapterContent): String = ChapterAiWorkflow.sha256(
        canonicalParagraphs(content) + listOf(
            "timeline=${timelineFingerprint(content)}",
            "mode=${StoryAudioSourceMode.AI_FREESOUND.name}",
            "engine=$MUSIC_TRANSFORM_ENGINE",
        ),
    )

    private fun mode3AudioDirectionSourceHash(
        content: ChapterContent,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): String = ChapterAiWorkflow.sha256(
        canonicalParagraphs(content) + listOf(
            "timeline=${timelineFingerprint(content)}",
            "mode=${StoryAudioSourceMode.AI_FREESOUND.name}",
            "engine=${XpkAmbienceSfxDirector.ENGINE}",
            "ambience=$ambienceEnabled",
            "sfx=$soundEffectsEnabled",
        ),
    )

    private suspend fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String {
        val source = library.loadCachedChapter(content.chapter.id)
        val sourceParagraphs = source?.paragraphs ?: content.paragraphs
        return ChapterAiWorkflow.sha256(sourceParagraphs + tracks.flatMap { listOf(it.id, it.tagsCsv, it.title) })
    }

    private fun audioDirectionSourceHash(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
        soundEffectTracks: List<SceneMusicTrackEntity>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
    ): String = ChapterAiWorkflow.sha256(
        content.paragraphs +
            listOf(
                "timeline=${timelineFingerprint(content)}",
                "ambience=$ambienceEnabled",
                "sfx=$soundEffectsEnabled",
            ) +
            ambienceTracks.flatMap { listOf("A:${it.id}", it.title, it.tagsCsv) } +
            soundEffectTracks.flatMap { listOf("S:${it.id}", it.title, it.tagsCsv) },
    )

    private fun freesoundSourceHash(content: ChapterContent, kinds: Set<AudioAssetKind>): String = ChapterAiWorkflow.sha256(
        canonicalParagraphs(content) + listOf(
            "timeline=${timelineFingerprint(content)}",
            "mode=${StoryAudioSourceMode.AI_FREESOUND.name}",
            "kinds=${kinds.map(AudioAssetKind::name).sorted().joinToString(",")}",
            "engine=$FREESOUND_AUTO_ENGINE",
        ),
    )

    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption {
        val description = aiDescription(tagsCsv)
        return SceneMusicTrackOption(
            id = id,
            title = title,
            tags = description.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
            description = description,
        )
    }

    private fun aiDescription(value: String): String = value.lineSequence()
        .map(::stripLeadingAudioTypeMarker)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .trim()
        .take(300)

    private fun stripLeadingAudioTypeMarker(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return AUDIO_TYPE_PREFIX.replaceFirst(trimmed, "").trim()
    }

    private suspend fun effectiveAiMetadata(storyId: String, globalProvider: String, globalModel: String): Pair<String, String> {
        val profile = library.getStoryAiProfile(storyId)
        val provider = if (profile?.overrideProvider == true) profile.provider else globalProvider
        val model = if (profile?.overrideProvider == true && profile.model.isNotBlank()) profile.model else globalModel
        return provider to model
    }

    private fun stableId(first: String, second: String): String =
        UUID.nameUUIDFromBytes("$first\u0000$second".toByteArray()).toString()

    companion object {
        const val KIND_AUDIO_DIRECTION = "AUDIO_DIRECTION"
        const val KIND_FREESOUND_AUTO_AUDIO = "FREESOUND_AUTO_AUDIO"
        private const val VOICE_TRANSFORM_ENGINE = "xpk-unit-v8"
        private const val MUSIC_TRANSFORM_ENGINE = "xpk-ai-full-authority-v1"
        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v3-semantic-parity"
        private const val MAX_FREESOUND_RUNTIME_ATTEMPTS = 3
        private const val FREESOUND_RUNTIME_RETRY_DELAY_MS = 1_200L
        private const val FREESOUND_EMPTY_AI_RETRY_COOLDOWN_MS = 60_000L
        private val AUDIO_TYPE_PREFIX = Regex(
            "(?i)^(?:type\\s*[:=]\\s*(?:music|ambience|environment|sfx|sound[_-]?effect|sfx[_-]?continuous|continuous)|\\[(?:music|ambience|environment|sfx|continuous|sfx[_-]?continuous)\\])(?:\\s*[,;|]\\s*|\\s+|$)",
        )
    }
}
