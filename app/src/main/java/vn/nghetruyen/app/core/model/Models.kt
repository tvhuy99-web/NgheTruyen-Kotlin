package vn.nghetruyen.app.core.model

data class StorySummary(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val description: String = "",
    val url: String = "",
)

data class StoryComment(
    val user: String = "Người đọc",
    val time: String = "",
    val text: String,
)

data class StoryCommentPage(
    val comments: List<StoryComment>,
    val nextPageUrl: String? = null,
)

data class StoryDetail(
    val story: StorySummary,
    val genres: List<String> = emptyList(),
    val status: String = "",
    val chapters: List<ChapterSummary> = emptyList(),
    val nextChapterPageUrl: String? = null,
    val commentsUrl: String? = null,
    val comments: List<StoryComment> = emptyList(),
)

data class ChapterPage(
    val chapters: List<ChapterSummary>,
    val nextPageUrl: String? = null,
)

data class ChapterSummary(
    val id: String,
    val storyId: String,
    val index: Int,
    val title: String,
    val url: String = "",
)

data class ChapterContent(
    val chapter: ChapterSummary,
    val paragraphs: List<String>,
    val nextChapterUrl: String? = null,
    val previousChapterUrl: String? = null,
    /** Catalog continuation used when this is the last chapter on the loaded TOC page. */
    val nextChapterPageUrl: String? = null,
    val nextChapterPageStartIndex: Int? = null,
)

data class ImportedBook(
    val title: String,
    val author: String = "",
    val chapters: List<ImportedChapter>,
)

data class ImportedChapter(
    val title: String,
    val paragraphs: List<String>,
)

data class TtsEngineOption(
    val packageName: String,
    val label: String,
    val isDefault: Boolean,
)

data class TtsVoiceOption(
    val name: String,
    val displayName: String,
    val languageTag: String,
    val networkRequired: Boolean,
    val quality: Int,
    val enginePackage: String? = null,
)

/** Editable per-character TTS profile shared by Compose, playback preview and persistence. */
data class VoiceRoleDraft(
    val roleName: String,
    val originalRoleId: String? = null,
    val aliases: String = "",
    val description: String = "",
    val isNarrator: Boolean = false,
    val enginePackage: String? = null,
    val voiceName: String? = null,
    val languageTag: String = "vi-VN",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val sonicVolume: Float = 1f,
    val expression: VoiceExpression = VoiceExpression.NEUTRAL,
    val expressionStrength: Float = 0.5f,
    val sonicSpeed: Float = 1f,
    val sonicPitch: Float = 1f,
    val processingMethod: String = "system",
    val sonicAccurate: Boolean = false,
    val enabled: Boolean = true,
)

enum class AudioExportFormat(val extension: String, val mimeType: String) {
    WAV("wav", "audio/wav"),
    M4A("m4a", "audio/mp4"),
    MP3("mp3", "audio/mpeg"),
}

enum class AudioInterruptionMode {
    PAUSE,
    CONTINUE_DUCKED,
}

enum class VoiceExpression {
    NEUTRAL,
    CALM,
    WARM,
    SAD,
    TENSE,
    ANGRY,
    EXCITED,
    WHISPER,
}

enum class SceneMusicPlaybackMode {
    SEQUENTIAL,
    SHUFFLE,
    SMART_AVOID_REPEAT,
}


enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

enum class DownloadSelectionMode { SINGLE, RANGE, UNREAD, ALL }

enum class SearchSortMode { RELEVANCE, TITLE, AUTHOR, SOURCE }

enum class SourceHealth { READY, NEEDS_LOGIN, DEGRADED, DISABLED, NOT_PORTED }


enum class ReaderMode { TEXT, TTS }

enum class ReaderThemeMode { SYSTEM, LIGHT, DARK, SEPIA }

enum class ReaderLayoutMode { SCROLL, PAGED }

data class ReaderDisplaySettings(
    val theme: ReaderThemeMode = ReaderThemeMode.SYSTEM,
    val layoutMode: ReaderLayoutMode = ReaderLayoutMode.SCROLL,
    val fontSizeSp: Int = 20,
    val lineHeightPercent: Int = 155,
    val horizontalPaddingDp: Int = 12,
    val paragraphSpacingDp: Int = 8,
    val keepScreenOn: Boolean = false,
    val volumeKeysNavigate: Boolean = false,
)
