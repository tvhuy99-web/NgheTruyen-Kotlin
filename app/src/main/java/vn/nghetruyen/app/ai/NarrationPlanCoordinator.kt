package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.util.Locale
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
        val storyVoice = storyVoiceSettings(content.chapter.storyId)
        val voiceAllowed = voice && storyVoice.mode != StoryVoiceCastMode.OFF
        if (!voiceAllowed && !music) {
            return Result(false, false, listOf("Phân vai TTS đang tắt cho truyện này."))
        }
        val tracks = if (music) library.listEnabledSceneMusicTracks() else emptyList()
        val voiceNeeded = voiceAllowed && needsVoicePlan(content, force)
        val musicNeeded = music && needsMusicPlan(content, tracks, force)
        if (!voiceNeeded && !musicNeeded) return Result(false, false, emptyList())

        if (voiceAllowed && music) {
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
                    val voiceCreated = runCatching { persistVoicePlan(content, outcome.value.voiceCast) }
                        .fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch giọng."; false })
                    val musicCreated = runCatching { persistMusicPlan(content, tracks, outcome.value.musicCues) }
                        .fold({ true }, { warnings += it.message ?: "Không lưu được kế hoạch nhạc."; false })
                    Result(voiceCreated, musicCreated, warnings.distinct(), usedUnifiedRequest = true)
                }
            }
        }

        val warnings = mutableListOf<String>()
        var voiceCreated = false
        var musicCreated = false
        if (voiceNeeded) {
            when (val outcome = ensureVoicePlan(content, force)) {
                is AppResult.Success -> voiceCreated = outcome.value
                is AppResult.Failure -> warnings += outcome.message
            }
        }
        if (musicNeeded) {
            when (val outcome = ensureMusicPlan(content, tracks, force, activeTrackId)) {
                is AppResult.Success -> musicCreated = outcome.value
                is AppResult.Failure -> warnings += outcome.message
            }
        }
        return Result(voiceCreated, musicCreated, warnings.distinct())
    }

    private suspend fun storyVoiceSettings(storyId: String): StoryVoiceCastReferenceSettings =
        library.getStoryAiProfile(storyId)?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }
            ?: StoryVoiceCastReferenceSettings()

    private suspend fun effectiveRoles(storyId: String): List<VoiceRoleEntity> {
        val appSettings = settings.snapshot()
        return when (storyVoiceSettings(storyId).mode) {
            StoryVoiceCastMode.OFF -> emptyList()
            StoryVoiceCastMode.PRIVATE -> library.listVoiceRoles(storyId).filter(VoiceRoleEntity::enabled)
            StoryVoiceCastMode.GLOBAL -> if (appSettings.autoVoiceCastEnabled) {
                library.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
            } else emptyList()
        }
    }

    private suspend fun needsVoicePlan(content: ChapterContent, force: Boolean): Boolean {
        if (storyVoiceSettings(content.chapter.storyId).mode == StoryVoiceCastMode.OFF) return false
        if (force) return true
        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
        if (cached?.sourceSha256 != sourceHash) return true
        if (cached.transformedText.contains("\"engine\":\"$VOICE_TRANSFORM_ENGINE\"")) return false
        return library.listVoiceAssignments(content.chapter.id).isEmpty()
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
        return cached?.sourceSha256 != sourceHash || library.listSceneMusicCues(content.chapter.id).isEmpty()
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

    private suspend fun ensureMusicPlan(
        content: ChapterContent,
        tracks: List<SceneMusicTrackEntity>,
        force: Boolean,
        activeTrackId: String?,
    ): AppResult<Boolean> {
        if (tracks.isEmpty()) return AppResult.Failure("AI_TRACKS_EMPTY", "Chưa có tệp nhạc cảnh đang bật.")
        if (!needsMusicPlan(content, tracks, force)) return AppResult.Success(false)
        val context = buildContinuityContext(content, activeTrackId, tracks)
        return when (
            val result = ai.planNarration(
                NarrationPlanRequest(
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    chapterTitle = content.chapter.title,
                    rawText = chapterBody(content),
                    includeVoiceCast = false,
                    includeSceneMusic = true,
                    tracks = tracks.map { it.toOption() },
                    context = context,
                ),
            )
        ) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatching { persistMusicPlan(content, tracks, result.value.musicCues) }
                .fold(
                    { AppResult.Success(true) },
                    { AppResult.Failure("MUSIC_PLAN_SAVE_FAILED", it.message ?: "Không lưu được kế hoạch nhạc.", it) },
                )
        }
    }

    /**
     * Saves the lossless unit-ID assignment set in chapter_transforms. The old paragraph table remains
     * a temporary playback bridge until milestone 5 migrates the runtime/database to unit positions.
     */
    private suspend fun persistVoicePlan(content: ChapterContent, plan: VoiceCastPlan) {
        val appSettings = settings.snapshot()
        val roles = effectiveRoles(content.chapter.storyId)
        val roleByPromptId = roles.associateBy(XpkVoiceCastPrompt::promptVoiceId)
        val canonicalAssignments = plan.assignments
            .filter { it.unitId.isNotBlank() && it.voiceId.isNotBlank() }
            .distinctBy { it.unitId }
        val legacyAssignments = canonicalAssignments
            .filter { it.paragraphIndex in content.paragraphs.indices }
            .distinctBy { it.paragraphIndex }
            .map { assignment ->
                val roleName = roleByPromptId[assignment.voiceId]?.roleName ?: assignment.character.ifBlank { NARRATOR }
                ChapterVoiceAssignmentEntity(
                    id = stableId(content.chapter.id, assignment.paragraphIndex.toString()),
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    paragraphIndex = assignment.paragraphIndex,
                    roleName = roleName,
                    confidence = assignment.confidence.coerceIn(0f, 1f),
                    speedAdjustPct = assignment.speedAdjustPct,
                    pitchAdjustPct = assignment.pitchAdjustPct,
                    volumeAdjustPct = assignment.volumeAdjustPct,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        library.replaceVoiceAssignments(content.chapter.storyId, content.chapter.id, legacyAssignments)

        val payload = JSONObject()
            .put("engine", VOICE_TRANSFORM_ENGINE)
            .put("splitter_version", XpkVoiceCastSplitter.ENGINE_VERSION)
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
    ) {
        val allowed = tracks.associateBy { it.id }
        val cues = plannedCues
            .filter { it.startParagraph in content.paragraphs.indices && allowed.containsKey(it.trackId) }
            .distinctBy { it.startParagraph }
            .map { cue ->
                SceneMusicCueEntity(
                    id = stableId(content.chapter.id, cue.startParagraph.toString()),
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    startParagraph = cue.startParagraph,
                    trackId = cue.trackId,
                    volume = cue.volume.coerceIn(0f, 1f),
                    mood = cue.mood.take(160),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        library.replaceSceneMusicCues(content.chapter.storyId, content.chapter.id, cues)
        val appSettings = settings.snapshot()
        val (provider, model) = effectiveAiMetadata(content.chapter.storyId, appSettings.aiOnline.provider.name, appSettings.aiOnline.model)
        val unitScenes = JSONArray().also { array ->
            plannedCues.forEach { cue ->
                if (cue.startUnitId.isNotBlank() && cue.endUnitId.isNotBlank()) {
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
                    .put("music_scenes", unitScenes)
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
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index)
        val previousEnding = previous?.paragraphs
            ?.takeLast(6)
            ?.joinToString("\n")
            ?.takeLast(4_000)
            .orEmpty()
        val previousCue = previous?.chapter?.id
            ?.let { library.listSceneMusicCues(it).maxByOrNull(SceneMusicCueEntity::startParagraph) }
        val continuityTrackId = activeTrackId?.takeIf(String::isNotBlank) ?: previousCue?.trackId
        val track = tracks.firstOrNull { it.id == continuityTrackId }
        return NarrationPlanContext(
            previousChapterEnding = previousEnding,
            activeTrackId = continuityTrackId,
            activeTrackTitle = track?.title,
            previousMood = previousCue?.mood.orEmpty(),
        )
    }

    private fun chapterBody(content: ChapterContent): String = content.paragraphs.joinToString("\n")

    private fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String =
        ChapterAiWorkflow.sha256(content.paragraphs + tracks.flatMap { listOf(it.id, it.tagsCsv, it.title) })

    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption = SceneMusicTrackOption(
        id = id,
        title = title,
        // Legacy column name. XPK treats this as one freeform AI description.
        tags = tagsCsv.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
    )

    private suspend fun effectiveAiMetadata(storyId: String, globalProvider: String, globalModel: String): Pair<String, String> {
        val profile = library.getStoryAiProfile(storyId)
        val provider = if (profile?.overrideProvider == true) profile.provider else globalProvider
        val model = if (profile?.overrideProvider == true && profile.model.isNotBlank()) profile.model else globalModel
        return provider to model
    }

    private fun stableId(first: String, second: String): String =
        UUID.nameUUIDFromBytes("$first\u0000$second".toByteArray()).toString()

    companion object {
        private const val NARRATOR = "Người kể chuyện"
        private const val VOICE_TRANSFORM_ENGINE = "xpk-unit-v8"
        private const val MUSIC_TRANSFORM_ENGINE = "xpk-unit-scene-v1"
    }
}
