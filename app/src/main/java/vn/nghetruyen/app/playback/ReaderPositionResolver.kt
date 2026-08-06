package vn.nghetruyen.app.playback

/**
 * Resolves a persisted or explicitly requested reader paragraph without ever
 * returning an index outside the normalized chapter.
 *
 * The explicit index is used for bookmark/note navigation. Persisted progress
 * is accepted only when it belongs to the chapter being opened. Both paths are
 * clamped because a source may edit a chapter between two reading sessions.
 */
object ReaderPositionResolver {
    fun resolve(
        chapterId: String,
        paragraphCount: Int,
        forcedParagraphIndex: Int? = null,
        savedChapterId: String? = null,
        savedParagraphIndex: Int? = null,
    ): Int {
        if (paragraphCount <= 0) return 0
        val candidate = forcedParagraphIndex
            ?: savedParagraphIndex?.takeIf { savedChapterId == chapterId }
            ?: 0
        return candidate.coerceIn(0, paragraphCount - 1)
    }
}
