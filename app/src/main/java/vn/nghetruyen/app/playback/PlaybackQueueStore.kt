package vn.nghetruyen.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.nghetruyen.app.core.model.ChapterContent

/**
 * One canonical XPK UNIT/DIALOGUE passed to TTS.
 *
 * [paragraphIndex] only links the unit back to reader progress. Voice casting and scene music use
 * [unitId] so multiple narration/dialogue units inside one reader paragraph remain independently
 * addressable.
 */
data class PlaybackSpeechChunk(
    val paragraphIndex: Int,
    val text: String,
    val unitId: String = "",
    val unitKind: String = "legacy",
    val fixedVoiceId: String? = null,
)

enum class PlaybackPreparationState {
    READY,
    PREPARING,
    FAILED,
}

data class PlaybackSnapshot(
    val sourceId: String = "",
    val storyId: String = "",
    val chapterId: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val chapterUrl: String = "",
    val paragraphs: List<String> = emptyList(),
    val paragraphIndex: Int = 0,
    val speechChunks: List<PlaybackSpeechChunk> = emptyList(),
    val speechChunkIndex: Int = 0,
    val nextChapterUrl: String? = null,
    val previousChapterUrl: String? = null,
    val isPlaying: Boolean = false,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val sleepTimerEndsAtMillis: Long? = null,
    val preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
    val preparationMessage: String? = null,
) {
    /** Full paragraph shown by the reader and referenced by persisted progress. */
    val currentParagraph: String?
        get() = paragraphs.getOrNull(paragraphIndex)

    val currentSpeechChunk: PlaybackSpeechChunk?
        get() = speechChunks.getOrNull(speechChunkIndex)
            ?.takeIf { it.paragraphIndex == paragraphIndex }

    /** Canonical XPK UNIT/DIALOGUE text passed to Android TTS/Sonic. */
    val currentSpeechText: String?
        get() = currentSpeechChunk?.text ?: currentParagraph

    val currentUnitId: String?
        get() = currentSpeechChunk?.unitId?.takeIf(String::isNotBlank)

    val currentUnitKind: String?
        get() = currentSpeechChunk?.unitKind?.takeIf(String::isNotBlank)

    val currentFixedVoiceId: String?
        get() = currentSpeechChunk?.fixedVoiceId?.takeIf(String::isNotBlank)

    val isFirstSpeechChunkOfParagraph: Boolean
        get() = speechChunks.getOrNull(speechChunkIndex - 1)?.paragraphIndex != paragraphIndex

    val progressFraction: Float
        get() = when {
            paragraphs.isEmpty() -> 0f
            speechChunks.isEmpty() -> (paragraphIndex + 1f) / paragraphs.size.toFloat()
            else -> (speechChunkIndex + 1f) / speechChunks.size.toFloat()
        }
}

object PlaybackQueueStore {
    private val mutable = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = mutable.asStateFlow()

    fun load(
        sourceId: String,
        storyId: String,
        chapterId: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterUrl: String = "",
        paragraphs: List<String>,
        nextChapterUrl: String? = null,
        previousChapterUrl: String? = null,
        startIndex: Int = 0,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f,
        keepPlaying: Boolean = false,
        preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
        preparationMessage: String? = null,
    ) {
        // XPK must see the same body text used by AI planning. Reader normalization is presentation/progress only.
        val chunks = XpkPlaybackRuntime.buildSpeechTimeline(chapterTitle, paragraphs)
        val normalized = ReaderTextChunker.normalizeParagraphs(paragraphs)
        val startParagraph = if (normalized.isEmpty()) 0 else startIndex.coerceIn(0, normalized.lastIndex)
        mutable.value = PlaybackSnapshot(
            sourceId = sourceId,
            storyId = storyId,
            chapterId = chapterId,
            chapterIndex = chapterIndex.coerceAtLeast(0),
            chapterTitle = chapterTitle,
            chapterUrl = chapterUrl,
            paragraphs = normalized,
            paragraphIndex = startParagraph,
            speechChunks = chunks,
            speechChunkIndex = firstChunkIndex(chunks, startParagraph),
            nextChapterUrl = nextChapterUrl,
            previousChapterUrl = previousChapterUrl,
            isPlaying = keepPlaying && normalized.isNotEmpty(),
            rate = rate.coerceIn(0.5f, 2.0f),
            pitch = pitch.coerceIn(0.5f, 2.0f),
            volume = volume.coerceIn(0.05f, 1.0f),
            sleepTimerEndsAtMillis = mutable.value.sleepTimerEndsAtMillis,
            preparationState = preparationState,
            preparationMessage = preparationMessage,
        )
    }

    fun loadContent(
        sourceId: String,
        content: ChapterContent,
        startIndex: Int = 0,
        rate: Float = state.value.rate,
        pitch: Float = state.value.pitch,
        volume: Float = state.value.volume,
        keepPlaying: Boolean = false,
        preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
        preparationMessage: String? = null,
    ) {
        load(
            sourceId = sourceId,
            storyId = content.chapter.storyId,
            chapterId = content.chapter.id,
            chapterIndex = content.chapter.index,
            chapterTitle = content.chapter.title,
            chapterUrl = content.chapter.url,
            paragraphs = content.paragraphs,
            nextChapterUrl = content.nextChapterUrl,
            previousChapterUrl = content.previousChapterUrl,
            startIndex = startIndex,
            rate = rate,
            pitch = pitch,
            volume = volume,
            keepPlaying = keepPlaying,
            preparationState = preparationState,
            preparationMessage = preparationMessage,
        )
    }

    fun setPreparation(state: PlaybackPreparationState, message: String? = null) {
        mutable.value = mutable.value.copy(
            preparationState = state,
            preparationMessage = message?.take(240),
            isPlaying = if (state == PlaybackPreparationState.READY) mutable.value.isPlaying else false,
        )
    }

    fun setPlaying(value: Boolean) {
        mutable.value = mutable.value.copy(isPlaying = value)
    }

    /** Moves by a reader paragraph and resets speech to its first XPK unit. */
    fun moveTo(index: Int): Boolean {
        val current = mutable.value
        if (current.paragraphs.isEmpty()) return false
        val target = index.coerceIn(0, current.paragraphs.lastIndex)
        mutable.value = current.copy(
            paragraphIndex = target,
            speechChunkIndex = firstChunkIndex(current.speechChunks, target),
        )
        return target == index
    }

    fun moveBy(delta: Int): Boolean = moveTo(mutable.value.paragraphIndex + delta)

    /** Restores the exact deterministic XPK unit index when it still belongs to the same paragraph. */
    fun restoreSpeechPosition(paragraphIndex: Int, speechChunkIndex: Int) {
        val current = mutable.value
        if (current.paragraphs.isEmpty()) return
        val paragraph = paragraphIndex.coerceIn(0, current.paragraphs.lastIndex)
        val fallback = firstChunkIndex(current.speechChunks, paragraph)
        val requested = current.speechChunks.getOrNull(speechChunkIndex)
            ?.takeIf { it.paragraphIndex == paragraph }
            ?.let { speechChunkIndex }
            ?: fallback
        mutable.value = current.copy(paragraphIndex = paragraph, speechChunkIndex = requested)
    }

    /**
     * Advances to the next XPK UNIT/DIALOGUE inside the current reader paragraph. Returns false at
     * the paragraph boundary so existing reader progress/navigation remains stable.
     */
    fun advanceSpeechChunk(): Boolean {
        val current = mutable.value
        val nextIndex = current.speechChunkIndex + 1
        val next = current.speechChunks.getOrNull(nextIndex) ?: return false
        if (next.paragraphIndex != current.paragraphIndex) return false
        mutable.value = current.copy(speechChunkIndex = nextIndex)
        return true
    }

    fun updateVoice(rate: Float, pitch: Float, volume: Float = state.value.volume) {
        mutable.value = mutable.value.copy(
            rate = rate.coerceIn(0.5f, 2.0f),
            pitch = pitch.coerceIn(0.5f, 2.0f),
            volume = volume.coerceIn(0.05f, 1.0f),
        )
    }

    fun setSleepTimer(endsAtMillis: Long?) {
        mutable.value = mutable.value.copy(sleepTimerEndsAtMillis = endsAtMillis)
    }

    private fun firstChunkIndex(chunks: List<PlaybackSpeechChunk>, paragraphIndex: Int): Int =
        chunks.indexOfFirst { it.paragraphIndex == paragraphIndex }.takeIf { it >= 0 } ?: 0
}

object NextChapterCache {
    private val lock = Any()
    private var parentChapterId: String = ""
    private var content: ChapterContent? = null

    fun put(parentId: String, chapter: ChapterContent) = synchronized(lock) {
        parentChapterId = parentId
        content = chapter
    }

    fun take(parentId: String): ChapterContent? = synchronized(lock) {
        if (parentChapterId != parentId) return@synchronized null
        val value = content
        parentChapterId = ""
        content = null
        value
    }

    fun has(parentId: String): Boolean = synchronized(lock) {
        parentChapterId == parentId && content != null
    }

    fun clear() = synchronized(lock) {
        parentChapterId = ""
        content = null
    }
}

object NextChapterNormalizer {
    fun normalize(
        parent: PlaybackSnapshot,
        requestedUrl: String,
        fetched: ChapterContent,
    ): ChapterContent = ReaderDocumentNormalizer.normalize(
        fetched.copy(
            chapter = fetched.chapter.copy(
                storyId = parent.storyId,
                index = parent.chapterIndex + 1,
                url = fetched.chapter.url.ifBlank { requestedUrl },
            ),
            previousChapterUrl = fetched.previousChapterUrl
                ?: parent.chapterUrl.takeIf(String::isNotBlank),
        ),
    )
}

/** Normalizes chapter text once for the reader, cache and playback queue. */
object ReaderDocumentNormalizer {
    fun normalize(content: ChapterContent): ChapterContent {
        val paragraphs = ReaderTextChunker.normalizeParagraphs(content.paragraphs)
        return if (paragraphs == content.paragraphs) content else content.copy(paragraphs = paragraphs)
    }
}

object ReaderTextChunker {
    // Legacy helper retained for non-XPK callers/tests. XPK playback units are <= 1200 UTF-8 bytes.
    const val SAFE_TTS_CHARS = 3_000

    /** Produces canonical reader paragraphs without changing their persisted indexes. */
    fun normalizeParagraphs(paragraphs: List<String>): List<String> = paragraphs
        .asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter(String::isNotBlank)
        .toList()

    /** Compatibility alias retained for callers that only need reader text. */
    fun normalize(paragraphs: List<String>): List<String> = normalizeParagraphs(paragraphs)

    fun chunkParagraphs(paragraphs: List<String>): List<PlaybackSpeechChunk> = buildList {
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            splitLongParagraph(paragraph).forEach { text ->
                add(PlaybackSpeechChunk(paragraphIndex, text))
            }
        }
    }

    private fun splitLongParagraph(text: String): List<String> {
        if (text.length <= SAFE_TTS_CHARS) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > SAFE_TTS_CHARS) {
                result += current.toString()
                current.clear()
            }
            if (sentence.length > SAFE_TTS_CHARS) {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                result += sentence.chunked(SAFE_TTS_CHARS)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
