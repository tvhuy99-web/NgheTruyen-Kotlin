package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult

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
 * AI-facing narration assignment.
 *
 * [unitId] and [voiceId] are the canonical XPK contract. The legacy paragraph/character fields are
 * retained only as a compatibility bridge until the playback/database migration in milestone 5.
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

/**
 * Scene interval returned by the XPK JSON contract.
 * start/end unit ids are canonical; startParagraph remains a temporary runtime bridge.
 */
data class SceneMusicCue(
    val startParagraph: Int,
    val trackId: String,
    val volume: Float = 0.25f,
    val mood: String = "",
    val startUnitId: String = "",
    val endUnitId: String = "",
)

data class SceneMusicTrackOption(
    val id: String,
    val title: String,
    val tags: List<String>,
)

data class NarrationPlanContext(
    val previousChapterEnding: String = "",
    val activeTrackId: String? = null,
    val activeTrackTitle: String? = null,
    val previousMood: String = "",
)

data class NarrationPlanRequest(
    val storyId: String,
    val chapterId: String,
    val rawText: String,
    val chapterTitle: String = "",
    val includeVoiceCast: Boolean = true,
    val includeSceneMusic: Boolean = true,
    val tracks: List<SceneMusicTrackOption> = emptyList(),
    val context: NarrationPlanContext = NarrationPlanContext(),
)

data class NarrationPlan(
    val voiceCast: VoiceCastPlan = VoiceCastPlan(emptyList(), emptyList()),
    val musicCues: List<SceneMusicCue> = emptyList(),
)

interface TranslationEngine {
    suspend fun translate(request: TranslationRequest): AppResult<String>
}

interface VietPhraseImprovementEngine {
    suspend fun improveVietPhrase(request: VietPhraseImprovementRequest): AppResult<List<VietPhraseReplacementSuggestion>>
}

interface VoiceCastEngine {
    suspend fun planVoiceCast(
        storyId: String,
        chapterId: String,
        rawText: String,
        chapterTitle: String = "",
    ): AppResult<VoiceCastPlan>
}

interface SceneMusicPlanner {
    suspend fun planMusic(
        storyId: String,
        chapterId: String,
        rawText: String,
        tracks: List<SceneMusicTrackOption> = emptyList(),
    ): AppResult<List<SceneMusicCue>>
}

interface NarrationPlanner {
    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan>
}

class DisabledAiServices : TranslationEngine, VietPhraseImprovementEngine, VoiceCastEngine, SceneMusicPlanner, NarrationPlanner {
    private fun <T> disabled(): AppResult<T> = AppResult.Failure(
        code = "AI_NOT_CONFIGURED",
        message = "Chưa cấu hình nhà cung cấp AI. Ứng dụng không tự gửi nội dung ra ngoài.",
    )

    override suspend fun translate(request: TranslationRequest) = disabled<String>()
    override suspend fun improveVietPhrase(request: VietPhraseImprovementRequest) = disabled<List<VietPhraseReplacementSuggestion>>()
    override suspend fun planVoiceCast(storyId: String, chapterId: String, rawText: String, chapterTitle: String) = disabled<VoiceCastPlan>()
    override suspend fun planMusic(
        storyId: String,
        chapterId: String,
        rawText: String,
        tracks: List<SceneMusicTrackOption>,
    ) = disabled<List<SceneMusicCue>>()
    override suspend fun planNarration(request: NarrationPlanRequest) = disabled<NarrationPlan>()
}
