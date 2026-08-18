package vn.nghetruyen.app.playback









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
