package vn.nghetruyen.app.playback

import java.security.MessageDigest
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
    val dialogueGroupId: String? = null,
)

enum class PlaybackPreparationState {
    READY,
    PREPARING,
    FAILED,
}

enum class NarrationAutomationStage {
    IDLE,
    CURRENT_PLANNING,
    CURRENT_APPLYING,
    CURRENT_READY,
    NEXT_LOADING,
    NEXT_PLANNING,
    NEXT_READY,
    FAILED,
}

data class FreesoundTransferSummary(
    val downloadedAssets: Int = 0,
    val reusedAssets: Int = 0,
)

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
    val nextChapterPageUrl: String? = null,
    val nextChapterPageStartIndex: Int? = null,
    val isPlaying: Boolean = false,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val sleepTimerEndsAtMillis: Long? = null,
    val preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
    val preparationMessage: String? = null,
    val narrationStage: NarrationAutomationStage = NarrationAutomationStage.IDLE,
    val narrationProgress: Float = 0f,
    val narrationMessage: String? = null,
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
    private val narrationTransferLock = Any()
    private val prefetchedFreesoundTransfers = LinkedHashMap<String, FreesoundTransferSummary>()

    private const val MAX_PREFETCH_TRANSFER_ENTRIES = 8
    private const val MANUAL_NARRATION_REBUILD_MESSAGE = "Đang phân vai chương hiện tại."

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
        nextChapterPageUrl: String? = null,
        nextChapterPageStartIndex: Int? = null,
        startIndex: Int = 0,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f,
        keepPlaying: Boolean = false,
        preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
        preparationMessage: String? = null,
    ) {
        XpkPlaybackRuntime.resetCanonicalPlans()
        // Match VoiceCast:buildUnits(): one non-empty original line is one hidden parser paragraph.
        val normalized = XpkPlaybackRuntime.canonicalLines(paragraphs)
        val chunks = XpkPlaybackRuntime.buildSpeechTimeline(chapterTitle, normalized)
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
            nextChapterPageUrl = nextChapterPageUrl,
            nextChapterPageStartIndex = nextChapterPageStartIndex,
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
            nextChapterPageUrl = content.nextChapterPageUrl,
            nextChapterPageStartIndex = content.nextChapterPageStartIndex,
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

    fun setNarrationAutomation(
        stage: NarrationAutomationStage,
        progress: Float,
        message: String?,
    ) {
        if (
            stage == NarrationAutomationStage.CURRENT_PLANNING &&
            progress <= 0.2f &&
            message?.trim() == MANUAL_NARRATION_REBUILD_MESSAGE
        ) {
            clearCurrentNarrationRuntimeForManualRebuild()
        }
        mutable.value = mutable.value.copy(
            narrationStage = stage,
            narrationProgress = progress.coerceIn(0f, 1f),
            narrationMessage = message?.take(260),
        )
    }

    private fun clearCurrentNarrationRuntimeForManualRebuild() {
        XpkPlaybackRuntime.resetCanonicalPlans()
        val chapterId = mutable.value.chapterId.trim()
        if (chapterId.isNotEmpty()) {
            synchronized(narrationTransferLock) {
                prefetchedFreesoundTransfers.remove(chapterId)
            }
        }
    }

    fun publishPrefetchNarrationAutomation(
        parentChapterId: String,
        targetChapterId: String,
        stage: NarrationAutomationStage,
        progress: Float,
        parentMessage: String?,
        targetMessage: String?,
        downloadedAssets: Int,
        reusedAssets: Int,
    ) {
        val parentId = parentChapterId.trim()
        val targetId = targetChapterId.trim()
        rememberFreesoundTransferSummary(targetId, downloadedAssets, reusedAssets)

        val current = mutable.value
        when {
            parentId.isNotEmpty() && current.chapterId == parentId ->
                setNarrationAutomation(stage, progress, parentMessage)
            targetId.isNotEmpty() && current.chapterId == targetId &&
                current.narrationStage != NarrationAutomationStage.CURRENT_PLANNING &&
                current.narrationStage != NarrationAutomationStage.CURRENT_APPLYING ->
                setNarrationAutomation(
                    stage = if (stage == NarrationAutomationStage.NEXT_READY) NarrationAutomationStage.CURRENT_READY else stage,
                    progress = progress,
                    message = targetMessage,
                )
        }
    }

    fun rememberFreesoundTransferSummary(
        chapterId: String,
        downloadedAssets: Int,
        reusedAssets: Int,
    ) {
        val id = chapterId.trim()
        if (id.isEmpty()) return
        val incoming = FreesoundTransferSummary(
            downloadedAssets = downloadedAssets.coerceAtLeast(0),
            reusedAssets = reusedAssets.coerceAtLeast(0),
        )
        if (incoming.downloadedAssets == 0 && incoming.reusedAssets == 0) return
        synchronized(narrationTransferLock) {
            prefetchedFreesoundTransfers[id] = mergeFreesoundTransferSummaries(
                previous = prefetchedFreesoundTransfers[id],
                current = incoming,
            )
            while (prefetchedFreesoundTransfers.size > MAX_PREFETCH_TRANSFER_ENTRIES) {
                val oldest = prefetchedFreesoundTransfers.keys.firstOrNull() ?: break
                prefetchedFreesoundTransfers.remove(oldest)
            }
        }
    }

    fun consumeFreesoundTransferSummary(
        chapterId: String,
        currentDownloadedAssets: Int,
        currentReusedAssets: Int,
    ): FreesoundTransferSummary {
        val current = FreesoundTransferSummary(
            downloadedAssets = currentDownloadedAssets.coerceAtLeast(0),
            reusedAssets = currentReusedAssets.coerceAtLeast(0),
        )
        val id = chapterId.trim()
        if (id.isEmpty()) return current
        val prefetched = synchronized(narrationTransferLock) {
            prefetchedFreesoundTransfers.remove(id)
        } ?: return current
        return mergeFreesoundTransferSummaries(prefetched, current)
    }

    private fun mergeFreesoundTransferSummaries(
        previous: FreesoundTransferSummary?,
        current: FreesoundTransferSummary,
    ): FreesoundTransferSummary {
        if (previous == null) return current
        val previousDownloads = previous.downloadedAssets.coerceAtLeast(0)
        return FreesoundTransferSummary(
            downloadedAssets = previousDownloads + current.downloadedAssets.coerceAtLeast(0),
            reusedAssets = maxOf(
                previous.reusedAssets.coerceAtLeast(0),
                current.reusedAssets.coerceAtLeast(0) - previousDownloads,
            ).coerceAtLeast(0),
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

/** Normalizes chapter metadata/text once for the reader, cache and playback queue. */
object ReaderDocumentNormalizer {
    private val truyenFullChapterUrl = Regex(
        pattern = "^(https?://(?:www\\.)?truyenfull\\.live/[^?#]+?)/(?:chuong-[^/?#]+)/?(?:[?#].*)?$",
        option = RegexOption.IGNORE_CASE,
    )

    fun normalize(content: ChapterContent): ChapterContent {
        val repaired = repairMissingStoryId(content)
        val paragraphs = ReaderTextChunker.normalizeParagraphs(repaired.paragraphs)
        return if (paragraphs == repaired.paragraphs) repaired else repaired.copy(paragraphs = paragraphs)
    }

    private fun repairMissingStoryId(content: ChapterContent): ChapterContent {
        if (content.chapter.storyId.isNotBlank()) return content
        val chapterUrl = content.chapter.url.trim()
        val match = truyenFullChapterUrl.matchEntire(chapterUrl) ?: return content
        val canonicalStoryUrl = match.groupValues[1].trimEnd('/') + "/"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalStoryUrl.toByteArray(Charsets.UTF_8))
        val storyId = buildString(24) {
            for (index in 0 until 12) {
                val value = digest[index].toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return content.copy(chapter = content.chapter.copy(storyId = storyId))
    }

    private const val HEX = "0123456789abcdef"
}

object ReaderTextChunker {
    // Legacy helper retained for non-XPK callers/tests. XPK playback units are <= 1200 UTF-8 bytes.
    const val SAFE_TTS_CHARS = 3_000

    /** Produces the same non-empty line scaffold that XPK VoiceCast uses internally. */
    fun normalizeParagraphs(paragraphs: List<String>): List<String> = XpkPlaybackRuntime.canonicalLines(paragraphs)

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
