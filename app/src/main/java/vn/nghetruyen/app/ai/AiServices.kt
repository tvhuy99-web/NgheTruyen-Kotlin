package vn.nghetruyen.app.ai

import vn.nghetruyen.app.audio.AmbienceScene
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.freesound.FreesoundAutoRequirement

data class TranslationRequest(
    val storyId: String,
    val chapterId: String,
    val sourceText: String,
    val instruction: String,
    val chapterTitle: String = "",
)

data class VietPhraseImprovementRequest(
    val storyId: String,
    val chapterId: String,
    val chapterTitle: String,
    val sourceText: String,
    val vietPhraseText: String,
)

data class VietPhraseReplacementSuggestion(
    val type: String,
    val original: String,
    val replacement: String,
    val reason: String = "",
)

data class VoiceRole(
    val character: String,
    val aliases: List<String> = emptyList(),
    val expression: String = "NEUTRAL",
)

/**
 * Canonical XPK narration assignment. [unitId] and [voiceId] are authoritative; paragraph/character
 * fields remain serialized compatibility metadata for older stored data only.
 */
data class ParagraphVoiceAssignment(
    val paragraphIndex: Int = -1,
    val character: String = "",
    val confidence: Float = 1f,
    val speedAdjustPct: Float = 0f,
    val pitchAdjustPct: Float = 0f,
    val volumeAdjustPct: Float = 0f,
    val unitId: String = "",
    val voiceId: String = "",
)

data class VoiceCastPlan(
    val roles: List<VoiceRole>,
    val assignments: List<ParagraphVoiceAssignment>,
    val warnings: List<String> = emptyList(),
)

/** Inclusive XPK scene interval. Unit ids are authoritative; paragraph indexes are legacy metadata. */
data class SceneMusicCue(
    val startParagraph: Int,
    val trackId: String,
    val volume: Float = 1f,
    val mood: String = "",
    val startUnitId: String = "",
    val endUnitId: String = "",
    val endParagraph: Int = startParagraph,
)

data class SceneMusicTrackOption(
    val id: String,
    val title: String,
    val tags: List<String>,
    val description: String = tags.joinToString(" "),
)

data class NarrationPlanContext(
    /** Serialized [PREVIOUS_UNIT ...] tail. It is context only and never a target timeline. */
    val previousChapterEnding: String = "",
    val activeTrackId: String? = null,
    val activeTrackTitle: String? = null,
    val previousMood: String = "",
    val incomingSource: String = "",
    /** Legacy single field used by the prompt transport; multiple ids are pipe-delimited internally. */
    var incomingAmbienceId: String? = null,
    /** Up to two ambience layers that were active at the end of the previous chapter. */
    val incomingAmbienceIds: List<String> = emptyList(),
) {
    init {
        val normalized = incomingAmbienceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(2)
        if (normalized.isNotEmpty()) incomingAmbienceId = normalized.joinToString("|")
    }
}

data class NarrationPlanRequest(
    val storyId: String,
    val chapterId: String,
    val rawText: String,
    val includeVoiceCast: Boolean = true,
    val includeSceneMusic: Boolean = true,
    val includeAmbience: Boolean = false,
    val includeSoundEffects: Boolean = false,
    /** Mode 3 only: ask the SAME narration request for English Freesound search needs. */
    val includeFreesoundAudioRequirements: Boolean = false,
    val freesoundRequirementKinds: Set<AudioAssetKind> = emptySet(),
    val tracks: List<SceneMusicTrackOption> = emptyList(),
    val ambienceTracks: List<SceneMusicTrackOption> = emptyList(),
    val soundEffectTracks: List<SceneMusicTrackOption> = emptyList(),
    val context: NarrationPlanContext = NarrationPlanContext(),
    val chapterTitle: String = "",
)

data class NarrationPlan(
    val voiceCast: VoiceCastPlan = VoiceCastPlan(emptyList(), emptyList()),
    val musicCues: List<SceneMusicCue> = emptyList(),
    val musicSceneError: String = "",
    val ambienceScenes: List<AmbienceScene> = emptyList(),
    val soundEffectCues: List<SoundEffectCue> = emptyList(),
    val audioDirectionError: String = "",
    val freesoundRequirements: List<FreesoundAutoRequirement> = emptyList(),
)

interface TranslationEngine {
    suspend fun translate(request: TranslationRequest): AppResult<String>
}

interface VietPhraseImprovementEngine {
    suspend fun improveVietPhrase(request: VietPhraseImprovementRequest): AppResult<List<VietPhraseReplacementSuggestion>>
}

/** Legacy paragraph-era surface. Production narration is XpkNarrationAiServices only. */
@Deprecated("Use XpkNarrationAiServices; paragraph voice-cast protocol is retired from production wiring")
interface VoiceCastEngine {
    suspend fun planVoiceCast(storyId: String, chapterId: String, rawText: String): AppResult<VoiceCastPlan>
}

/** Legacy paragraph-era surface. Production scene planning is XpkNarrationAiServices only. */
@Deprecated("Use XpkNarrationAiServices; paragraph scene-cue protocol is retired from production wiring")
interface SceneMusicPlanner {
    suspend fun planMusic(
        storyId: String,
        chapterId: String,
        rawText: String,
        tracks: List<SceneMusicTrackOption> = emptyList(),
    ): AppResult<List<SceneMusicCue>>
}

/** Legacy interface retained for binary/source compatibility. Production uses the concrete XPK service. */
@Deprecated("Use XpkNarrationAiServices; legacy narration planner is retired from production wiring")
interface NarrationPlanner {
    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan>
}

@Suppress("DEPRECATION")
class DisabledAiServices : TranslationEngine, VietPhraseImprovementEngine, VoiceCastEngine, SceneMusicPlanner, NarrationPlanner {
    private fun <T> disabled(): AppResult<T> = AppResult.Failure(
        code = "AI_NOT_CONFIGURED",
        message = "Chưa cấu hình nhà cung cấp AI. Ứng dụng không tự gửi nội dung ra ngoài.",
    )

    override suspend fun translate(request: TranslationRequest) = disabled<String>()
    override suspend fun improveVietPhrase(request: VietPhraseImprovementRequest) = disabled<List<VietPhraseReplacementSuggestion>>()
    override suspend fun planVoiceCast(storyId: String, chapterId: String, rawText: String) = disabled<VoiceCastPlan>()
    override suspend fun planMusic(
        storyId: String,
        chapterId: String,
        rawText: String,
        tracks: List<SceneMusicTrackOption>,
    ) = disabled<List<SceneMusicCue>>()
    override suspend fun planNarration(request: NarrationPlanRequest) = disabled<NarrationPlan>()
}
