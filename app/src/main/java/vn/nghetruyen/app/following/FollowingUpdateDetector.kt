package vn.nghetruyen.app.following

object FollowingUpdateDetector {
    fun hasNewChapter(previous: String, latest: String): Boolean =
        normalize(previous).isNotBlank() && normalize(latest).isNotBlank() && normalize(previous) != normalize(latest)

    fun newChapterCount(
        previousTitle: String,
        previousIndex: Int,
        latestTitle: String,
        latestIndex: Int,
    ): Int {
        if (!hasNewChapter(previousTitle, latestTitle)) return 0
        if (previousIndex >= 0 && latestIndex > previousIndex) return latestIndex - previousIndex
        val previousNumber = extractChapterNumber(previousTitle)
        val latestNumber = extractChapterNumber(latestTitle)
        if (previousNumber != null && latestNumber != null && latestNumber > previousNumber) {
            return (latestNumber - previousNumber).coerceAtMost(10_000)
        }
        return 1
    }

    private fun extractChapterNumber(value: String): Int? = Regex("(?:chuong|chapter|chap|c)\\s*[-:#.]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(normalize(value))
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace('đ', 'd')
        .replace(Regex("\\s+"), " ")
        .trim()
}
