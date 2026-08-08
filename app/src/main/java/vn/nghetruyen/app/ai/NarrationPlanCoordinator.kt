package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.playback.TtsVoiceCatalog
import java.util.Locale
import java.util.UUID

/** Creates and caches a coordinated voice-cast and scene-music plan used by playback and export. */
class NarrationPlanCoordinator(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val ai: OnlineAiServices,
    private val voiceCatalog: TtsVoiceCatalog,
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
            val options = tracks.map { it.toOption() }
            val context = buildContinuityContext(content, activeTrackId, tracks)
            return when (val outcome = ai.planNarration(
                NarrationPlanRequest(
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    rawText = ChapterAiWorkflow.markedParagraphs(content.paragraphs),
                    includeVoiceCast = true,
                    includeSceneMusic = true,
                    tracks = options,
                    context = context,
                ),
            )) {
                is AppResult.Failure -> Result(false, false, listOf(outcome.message), usedUnifiedRequest = true)
                is AppResult.Success -> {
                    val warnings = mutableListOf<String>()
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

    private suspend fun effectiveRoles(storyId: String, appSettings: vn.nghetruyen.app.data.settings.AppSettings): List<VoiceRoleEntity> {
        return when (storyVoiceSettings(storyId).mode) {
            StoryVoiceCastMode.OFF -> emptyList()
            StoryVoiceCastMode.PRIVATE -> library.listVoiceRoles(storyId).filter(VoiceRoleEntity::enabled)
            StoryVoiceCastMode.GLOBAL -> if (appSettings.autoVoiceCastEnabled) {
                library.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun needsVoicePlan(content: ChapterContent, force: Boolean): Boolean {
        if (storyVoiceSettings(content.chapter.storyId).mode == StoryVoiceCastMode.OFF) return false
        if (force) return true
        val sourceHash = ChapterAiWorkflow.sha256(content.paragraphs)
        val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
        return cached?.sourceSha256 != sourceHash || library.listVoiceAssignments(content.chapter.id).isEmpty()
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
        return when (val result = ai.planVoiceCast(
            content.chapter.storyId,
            content.chapter.id,
            ChapterAiWorkflow.markedParagraphs(content.paragraphs),
        )) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatching { persistVoicePlan(content, result.value) }
                .fold({ AppResult.Success(true) }, { AppResult.Failure("VOICE_PLAN_SAVE_FAILED", it.message ?: "Không lưu được kế hoạch giọng.", it) })
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
        return when (val result = ai.planNarration(
            NarrationPlanRequest(
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                rawText = ChapterAiWorkflow.markedParagraphs(content.paragraphs),
                includeVoiceCast = false,
                includeSceneMusic = true,
                tracks = tracks.map { it.toOption() },
                context = context,
            ),
        )) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatching { persistMusicPlan(content, tracks, result.value.musicCues) }
                .fold({ AppResult.Success(true) }, { AppResult.Failure("MUSIC_PLAN_SAVE_FAILED", it.message ?: "Không lưu được kế hoạch nhạc.", it) })
        }
    }

    private suspend fun persistVoicePlan(content: ChapterContent, plan: VoiceCastPlan) {
        val appSettings = settings.snapshot()
        val storyMode = storyVoiceSettings(content.chapter.storyId).mode
        val existingRoles = effectiveRoles(content.chapter.storyId, appSettings)
        val existing = existingRoles.associateBy { it.roleName.trim().lowercase(Locale.ROOT) }
        val voices = when (val scan = voiceCatalog.load(appSettings.ttsEnginePackage)) {
            is AppResult.Success -> scan.value
            is AppResult.Failure -> emptyList()
        }
        if (storyMode == StoryVoiceCastMode.PRIVATE) {
            plan.roles.forEach { role ->
                val key = role.character.trim().lowercase(Locale.ROOT)
                val current = existing[key]
                val narrator = role.character.equals(NARRATOR, ignoreCase = true)
                val allocated = current?.voiceName?.let { name -> voices.firstOrNull { it.name == name } }
                    ?: allocateVoice(role.character, narrator, voices, appSettings.ttsVoiceName)
                val variation = voiceVariation(role.character, narrator)
                val aliases = (current?.aliasesCsv.orEmpty().split(',') + role.aliases)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(20)
                    .joinToString(",")
                library.saveVoiceRole(
                    storyId = content.chapter.storyId,
                    roleName = role.character,
                    aliasesCsv = aliases,
                    voiceName = allocated?.name ?: current?.voiceName ?: appSettings.ttsVoiceName,
                    languageTag = allocated?.languageTag ?: current?.languageTag ?: appSettings.ttsLanguageTag,
                    rate = current?.rate ?: (appSettings.ttsRate * variation.first).coerceIn(0.25f, 3f),
                    pitch = current?.pitch ?: (appSettings.ttsPitch * variation.second).coerceIn(0.5f, 2f),
                    volume = current?.volume ?: appSettings.ttsVolume,
                    isNarrator = narrator,
                    enginePackage = current?.enginePackage ?: appSettings.ttsEnginePackage,
                    expression = current?.expression ?: role.expression,
                    expressionStrength = current?.expressionStrength ?: if (narrator) 0.25f else 0.65f,
                    sonicSpeed = current?.sonicSpeed ?: appSettings.sonicDefaultSpeed,
                    sonicPitch = current?.sonicPitch ?: appSettings.sonicDefaultPitch,
                    enabled = current?.enabled ?: true,
                ).getOrThrow()
            }
        }
        val allowedRoles = if (existingRoles.isNotEmpty()) {
            existingRoles.map { it.roleName.lowercase(Locale.ROOT) }.toSet()
        } else {
            plan.roles.map { it.character.lowercase(Locale.ROOT) }.toSet()
        }
        val assignments = plan.assignments
            .filter { it.paragraphIndex in content.paragraphs.indices }
            .filter { assignment -> storyMode == StoryVoiceCastMode.PRIVATE || assignment.character.lowercase(Locale.ROOT) in allowedRoles }
            .map { assignment ->
                val roleName = if (storyMode == StoryVoiceCastMode.GLOBAL && assignment.character.lowercase(Locale.ROOT) !in allowedRoles) {
                    NARRATOR
                } else {
                    assignment.character
                }
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
        library.replaceVoiceAssignments(content.chapter.storyId, content.chapter.id, assignments)
        val (provider, model) = effectiveAiMetadata(content.chapter.storyId, appSettings.aiOnline.provider.name, appSettings.aiOnline.model)
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = ChapterAiWorkflow.KIND_VOICE_CAST,
                provider = provider,
                model = model,
                sourceSha256 = ChapterAiWorkflow.sha256(content.paragraphs),
                transformedText = "roles=${plan.roles.size};assignments=${assignments.size};mode=${storyMode.name}",
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
        library.saveChapterTransform(
            ChapterTransformEntity(
                id = stableId(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC),
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                kind = ChapterAiWorkflow.KIND_SCENE_MUSIC,
                provider = provider,
                model = model,
                sourceSha256 = musicSourceHash(content, tracks),
                transformedText = "cues=${cues.size};unified=1",
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

    private fun musicSourceHash(content: ChapterContent, tracks: List<SceneMusicTrackEntity>): String =
        ChapterAiWorkflow.sha256(content.paragraphs + tracks.flatMap { listOf(it.id, it.tagsCsv, it.title) })

    private fun SceneMusicTrackEntity.toOption(): SceneMusicTrackOption = SceneMusicTrackOption(
        id = id,
        title = title,
        tags = tagsCsv.split(',').map(String::trim).filter(String::isNotBlank),
    )

    private suspend fun effectiveAiMetadata(storyId: String, globalProvider: String, globalModel: String): Pair<String, String> {
        val profile = library.getStoryAiProfile(storyId)
        val provider = if (profile?.overrideProvider == true) profile.provider else globalProvider
        val model = if (profile?.overrideProvider == true && profile.model.isNotBlank()) profile.model else globalModel
        return provider to model
    }

    private fun allocateVoice(
        roleName: String,
        narrator: Boolean,
        voices: List<TtsVoiceOption>,
        preferredVoice: String?,
    ): TtsVoiceOption? {
        preferredVoice?.let { preferred ->
            if (narrator) voices.firstOrNull { it.name == preferred }?.let { return it }
        }
        val suitable = voices.filter { it.languageTag.startsWith("vi", ignoreCase = true) }.ifEmpty { voices }
        if (suitable.isEmpty()) return null
        val hash = roleName.lowercase(Locale.ROOT).hashCode() + if (narrator) 0 else 1
        return suitable[Math.floorMod(hash, suitable.size)]
    }

    private fun voiceVariation(roleName: String, narrator: Boolean): Pair<Float, Float> {
        if (narrator) return 1f to 1f
        val hash = roleName.lowercase(Locale.ROOT).hashCode()
        val rate = 0.95f + Math.floorMod(hash, 11) / 100f
        val pitch = 0.90f + Math.floorMod(hash / 11, 21) / 100f
        return rate to pitch
    }

    private fun stableId(first: String, second: String): String =
        UUID.nameUUIDFromBytes("$first\u0000$second".toByteArray()).toString()

    companion object {
        private const val NARRATOR = "Người kể chuyện"
    }
}
