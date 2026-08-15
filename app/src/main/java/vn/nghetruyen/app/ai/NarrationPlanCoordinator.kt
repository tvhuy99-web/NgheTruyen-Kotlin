package vn.nghetruyen.app.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.playback.XpkPlaybackRuntime
import java.util.UUID

/**
 * Creates and caches one coordinated XPK chapter plan. Voice, music, ambience and SFX are generated
 * from one model response whenever any enabled layer needs to be refreshed.
 */
class NarrationPlanCoordinator(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val ai: XpkNarrationAiServices,
) {
    private val planningMutex = Mutex()

    data class Result(
        val voicePlanCreated: Boolean,
        val musicPlanCreated: Boolean,
        val warnings: List<String>,
        val usedUnifiedRequest: Boolean = false,
        val audioPlanCreated: Boolean = false,
    )

    suspend fun ensurePlans(
        content: ChapterContent,
        voice: Boolean,
        music: Boolean,
        force: Boolean = false,
        activeTrackId: String? = null,
    ): Result = planningMutex.withLock {
        ensurePlansLocked(content, voice, music, force, activeTrackId)
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
            music = appSettings.backgroundMusicEnabled && appSettings.autoSceneMusicEnabled,
            force = force,
            activeTrackId = activeTrackId,
        )
    }

    private suspend fun ensurePlansLocked(
        content: ChapterContent,
        voice: Boolean,
        music: Boolean,
        force: Boolean,
        activeTrackId: String?,
    ): Result {
        // Legacy parity phrase retained for old static checks only:
        // "Nhạc theo cảnh AI chỉ được lập cùng phân vai TTS."
        val warnings = mutableListOf<String>()
        val audioSettings = AudioDirectionPreferences.currentSnapshot()
        val enabledAssets = library.listEnabledSceneMusicTracks()
        val musicTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        val ambienceTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        val soundEffectTracks = enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }

        val storyVoice = storyVoiceSettings(content.chapter.storyId)
        val effectiveVoice = voice && storyVoice.mode != StoryVoiceCastMode.OFF
        if (voice && !effectiveVoice) warnings += "Phân vai TTS đang tắt cho truyện này."

        val effectiveMusic = music && musicTracks.isNotEmpty()
        if (music && musicTracks.isEmpty()) warnings += "Chưa có tệp nhạc cảnh đang bật."

        val effectiveAmbience = audioSettings.ambienceEnabled && ambienceTracks.isNotEmpty()
        if (audioSettings.ambienceEnabled && ambienceTracks.isEmpty()) {
            warnings += "Âm thanh môi trường đang bật nhưng chưa có asset AMBIENCE."
        }
        val effectiveSfx = audioSettings.soundEffectsEnabled && soundEffectTracks.isNotEmpty()
        if (audioSettings.soundEffectsEnabled && soundEffectTracks.isEmpty()) {
            warnings += "Hiệu ứng âm thanh đang bật nhưng chưa có asset SFX."
        }

        if (!effectiveVoice && !effectiveMusic && !effectiveAmbience && !effectiveSfx) {
            return Result(false, false, warnings.distinct())
        }

        val voiceNeeded = effectiveVoice && needsVoicePlan(content, force)
        val musicNeeded = effectiveMusic && needsMusicPlan(content, musicTracks, force)
        val audioNeeded = (effectiveAmbience || effectiveSfx) && needsAudioDirectionPlan(
            content = content,
            ambienceTracks = if (effectiveAmbience) ambienceTracks else emptyList(),
            soundEffectTracks = if (effectiveSfx) soundEffectTracks else emptyList(),
            ambienceEnabled = effectiveAmbience,
            soundEffectsEnabled = effectiveSfx,
            force = force,
        )
        if (!voiceNeeded && !musicNeeded && !audioNeeded) {
            return Result(false, false, warnings.distinct())
        }

        val baseContext = buildContinuityContext(content, activeTrackId, musicTracks)
        val context = baseContext.copy(
            incomingAmbienceId = buildIncomingAmbienceId(content, ambienceTracks),
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
                    tracks = musicTracks.map { it.toOption() },
                    ambienceTracks = ambienceTracks.map { it.toOption() },
                    soundEffectTracks = soundEffectTracks.map { it.toOption() },
                    context = context,
                ),
            )
        ) {
            is AppResult.Failure -> Result(
                voicePlanCreated = false,
                musicPlanCreated = false,
                warnings = (warnings + outcome.message).distinct(),
                usedUnifiedRequest = true,
                audioPlanCreated = false,
            )
            is AppResult.Success -> {
                if (effectiveVoice) warnings += outcome.value.voiceCast.warnings
                outcome.value.musicSceneError.takeIf(String::isNotBlank)?.let(warnings::add)
                outcome.value.audioDirectionError.takeIf(String::isNotBlank)?.let(warnings::add)

                val voiceCreated = if (effectiveVoice) {
                    runCatching { persistVoicePlan(content, outcome.value.voiceCast) }
                        .fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch giọng."; false })
                } else false
                val musicCreated = if (effectiveMusic) {
                    runCatching {
                        persistMusicPlan(content, musicTracks, outcome.value.musicCues, outcome.value.musicSceneError)
                    }.fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch nhạc."; false })
                } else false
                val audioCreated = if (effectiveAmbience || effectiveSfx) {
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
                        )
                    }.fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch ambience/SFX."; false })
                } else false
                Result(
                    voicePlanCreated = voiceCreated,
                    musicPlanCreated = musicCreated,
                    warnings = warnings.distinct(),
                    usedUnifiedRequest = true,
                    audioPlanCreated = audioCreated,
                )
            }
        }
    }

    suspend fun loadAudioDirectionPlan(content: ChapterContent): AmbienceSfxPlan? {
        val audioSettings = AudioDirectionPreferences.currentSnapshot()
        if (!audioSettings.ambienceEnabled && !audioSettings.soundEffectsEnabled) return AmbienceSfxPlan()
        val enabledAssets = library.listEnabledSceneMusicTracks()
        val ambienceTracks = if (audioSettings.ambienceEnabled) {
            enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        } else emptyList()
        val soundEffectTracks = if (audioSettings.soundEffectsEnabled) {
            enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        } else emptyList()
        val effectiveAmbience = audioSettings.ambienceEnabled && ambienceTracks.isNotEmpty()
        val effectiveSfx = audioSettings.soundEffectsEnabled && soundEffectTracks.isNotEmpty()
        if (!effectiveAmbience && !effectiveSfx) return AmbienceSfxPlan()

        val sourceHash = audioDirectionSourceHash(
            content,
            ambienceTracks,
            soundEffectTracks,
            effectiveAmbience,
            effectiveSfx,
        )
        val cached = library.getChapterTransform(content.chapter.id, KIND_AUDIO_DIRECTION) ?: return null
        if (cached.sourceSha256 != sourceHash) return null
        val unitIds = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content)).map { it.id }
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
        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
            ?: return 0
        if (cached.sourceSha256 != sourceHash) return 0
        if (!isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, content)) return 0
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
        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
        if (cached?.sourceSha256 != sourceHash) return true
        return !isCurrentTimelineTransform(cached.transformedText, VOICE_TRANSFORM_ENGINE, content)
    }

    private suspend fun needsMusicPlan(
        content: ChapterContent,
        tracks: List<SceneMusicTrackEntity>,
        force: Boolean,
    ): Boolean {
        if (force) return true
        if (tracks.isEmpty()) return false
        val sourceHash = musicSourceHash(content, tracks)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC)
        if (cached?.sourceSha256 != sourceHash) return true
        return !isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)
    }

    private suspend fun needsAudioDirectionPlan(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
        soundEffectTracks: List<SceneMusicTrackEntity>,
        ambienceEnabled: Boolean,
        soundEffectsEnabled: Boolean,
        force: Boolean,
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
        return cached?.sourceSha256 != sourceHash
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
                sourceSha256 = ChapterAiWorkflow.sha256(content.paragraphs),
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
    ) {
        val allowed = tracks.associateBy { it.id }
        library.replaceSceneMusicCues(content.chapter.storyId, content.chapter.id, emptyList())
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(content.chapter.storyId, appSettings.aiOnline.provider.name, appSettings.aiOnline.model)
        val unitScenes = JSONArray().also { array ->
            plannedCues.forEach { cue ->
                if (cue.startUnitId.isNotBlank() && cue.endUnitId.isNotBlank() && allowed.containsKey(cue.trackId)) {
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
                sourceSha256 = musicSourceHash(content, tracks),
                transformedText = JSONObject()
                    .put("engine", MUSIC_TRANSFORM_ENGINE)
                    .put("mode", XpkSceneMusicParity.MODE)
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
    ) {
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(
            content.chapter.storyId,
            appSettings.aiOnline.provider.name,
            appSettings.aiOnline.model,
        )
        val payload = JSONObject(XpkAmbienceSfxDirector.encode(plan))
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
                sourceSha256 = audioDirectionSourceHash(
                    content,
                    ambienceTracks,
                    soundEffectTracks,
                    ambienceEnabled,
                    soundEffectsEnabled,
                ),
                transformedText = payload,
                updatedAt = System.currentTimeMillis(),
            ),
        )
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

    private suspend fun buildIncomingAmbienceId(
        content: ChapterContent,
        ambienceTracks: List<SceneMusicTrackEntity>,
    ): String? {
        if (ambienceTracks.isEmpty()) return null
        val allowed = ambienceTracks.map(SceneMusicTrackEntity::id).toSet()
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index) ?: return null
        val transform = library.getChapterTransform(previous.chapter.id, KIND_AUDIO_DIRECTION) ?: return null
        return runCatching {
            val scenes = JSONObject(transform.transformedText).optJSONArray("ambience_scenes") ?: return@runCatching null
            scenes.optJSONObject(scenes.length() - 1)
                ?.optString("ambience_id")
                ?.trim()
                ?.takeIf { it in allowed }
        }.getOrNull()
    }

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

    private fun canonicalParagraphs(content: ChapterContent): List<String> =
        XpkPlaybackRuntime.canonicalLines(content.paragraphs)

    private fun timelineFingerprint(content: ChapterContent): String =
        XpkPlaybackRuntime.timelineFingerprint(content.chapter.title, canonicalParagraphs(content))

    private fun chapterBody(content: ChapterContent): String = canonicalParagraphs(content).joinToString("\n")

    // Keep this formula identical to ReaderPlaybackService so saved XPK scene plans are loadable now.
    private fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String =
        ChapterAiWorkflow.sha256(content.paragraphs + tracks.flatMap { listOf(it.id, it.tagsCsv, it.title) })

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
        .filterNot { line ->
            val lower = line.trim().lowercase()
            lower.startsWith("type:") || lower.startsWith("type=") ||
                lower in setOf("[music]", "[ambience]", "[environment]", "[sfx]")
        }
        .joinToString(" ")
        .trim()
        .take(300)

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
        private const val VOICE_TRANSFORM_ENGINE = "xpk-unit-v8"
        private const val MUSIC_TRANSFORM_ENGINE = "xpk-ai-full-authority-v1"
    }
}
