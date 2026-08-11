package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
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

/** Creates and caches a coordinated XPK-compatible voice-cast and scene-music plan. */
class NarrationPlanCoordinator(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val ai: XpkNarrationAiServices,
) {
    data class Result(
        val voicePlanCreated: Boolean,
        val musicPlanCreated: Boolean,
        val warnings: List<String>,
        val usedUnifiedRequest: Boolean = false,
    )

    suspend fun ensurePlans(
        content: ChapterContent,
        voice: Boolean,
        music: Boolean,
        force: Boolean = false,
        activeTrackId: String? = null,
    ): Result {
        if (!voice && !music) return Result(false, false, emptyList())
        if (!voice) {
            return Result(false, false, listOf("Nhạc theo cảnh AI chỉ được lập cùng phân vai TTS."))
        }
        val storyVoice = storyVoiceSettings(content.chapter.storyId)
        val voiceAllowed = storyVoice.mode != StoryVoiceCastMode.OFF
        if (!voiceAllowed) {
            return Result(false, false, listOf("Phân vai TTS đang tắt cho truyện này."))
        }
        val tracks = if (music) library.listEnabledSceneMusicTracks() else emptyList()
        val voiceNeeded = needsVoicePlan(content, force)
        val musicNeeded = music && needsMusicPlan(content, tracks, force)
        if (!voiceNeeded && !musicNeeded) return Result(false, false, emptyList())

        if (music) {
            if (tracks.isEmpty()) {
                val voiceOutcome = if (voiceNeeded) ensureVoicePlan(content, force) else AppResult.Success(false)
                return when (voiceOutcome) {
                    is AppResult.Success -> Result(voiceOutcome.value, false, listOf("Chưa có tệp nhạc cảnh đang bật."))
                    is AppResult.Failure -> Result(false, false, listOf(voiceOutcome.message, "Chưa có tệp nhạc cảnh đang bật.").distinct())
                }
            }
            val context = buildContinuityContext(content, activeTrackId, tracks)
            return when (
                val outcome = ai.planNarration(
                    NarrationPlanRequest(
                        storyId = content.chapter.storyId,
                        chapterId = content.chapter.id,
                        chapterTitle = content.chapter.title,
                        rawText = chapterBody(content),
                        includeVoiceCast = true,
                        includeSceneMusic = true,
                        tracks = tracks.map { it.toOption() },
                        context = context,
                    ),
                )
            ) {
                is AppResult.Failure -> Result(false, false, listOf(outcome.message), usedUnifiedRequest = true)
                is AppResult.Success -> {
                    val warnings = mutableListOf<String>()
                    warnings += outcome.value.voiceCast.warnings
                    outcome.value.musicSceneError.takeIf(String::isNotBlank)?.let(warnings::add)
                    val voiceCreated = runCatching { persistVoicePlan(content, outcome.value.voiceCast) }
                        .fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch giọng."; false })
                    val musicCreated = runCatching {
                        persistMusicPlan(content, tracks, outcome.value.musicCues, outcome.value.musicSceneError)
                    }.fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch nhạc."; false })
                    Result(voiceCreated, musicCreated, warnings.distinct(), usedUnifiedRequest = true)
                }
            }
        }

        val warnings = mutableListOf<String>()
        var voiceCreated = false
        if (voiceNeeded) {
            when (val outcome = ensureVoicePlan(content, force)) {
                is AppResult.Success -> voiceCreated = outcome.value
                is AppResult.Failure -> warnings += outcome.message
            }
        }
        return Result(voiceCreated, false, warnings.distinct())
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
        val profile = library.getStoryAiProfile(storyId) ?: return true
        val raw = profile.voiceCastNote
        val storyVoice = StoryVoiceCastReferenceCodec.decode(raw)
        if (storyVoice.mode == StoryVoiceCastMode.OFF) return false
        return if (StoryVoiceCastReferenceCodec.hasStoredSettings(raw)) storyVoice.autoRunOnOpenTts else true
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
        if (tracks.isEmpty()) return true
        val sourceHash = musicSourceHash(content, tracks)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC)
        if (cached?.sourceSha256 != sourceHash) return true
        return !isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)
    }

    private suspend fun ensureVoicePlan(content: ChapterContent, force: Boolean): AppResult<Boolean> {
        if (storyVoiceSettings(content.chapter.storyId).mode == StoryVoiceCastMode.OFF) {
            return AppResult.Failure("VOICE_CAST_DISABLED", "Phân vai TTS đang tắt cho truyện này.")
        }
        if (!needsVoicePlan(content, force)) return AppResult.Success(false)
        return when (
            val result = ai.planVoiceCast(
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                chapterTitle = content.chapter.title,
                rawText = chapterBody(content),
            )
        ) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatching { persistVoicePlan(content, result.value) }
                .fold(
                    { AppResult.Success(true) },
                    { AppResult.Failure("VOICE_PLAN_SAVE_FAILED", it.message ?: "Không lưu được kế hoạch giọng.", it) },
                )
        }
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

    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption {
        val description = tagsCsv.trim()
        return SceneMusicTrackOption(
            id = id,
            title = title,
            tags = description.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
            description = description,
        )
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
        private const val VOICE_TRANSFORM_ENGINE = "xpk-unit-v8"
        private const val MUSIC_TRANSFORM_ENGINE = "xpk-ai-full-authority-v1"
    }
}
