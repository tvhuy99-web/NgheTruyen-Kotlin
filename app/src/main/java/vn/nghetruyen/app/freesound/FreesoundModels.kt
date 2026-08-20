package vn.nghetruyen.app.freesound

enum class FreesoundCategory(
    val label: String,
    internal val filter: String?,
) {
    ALL("Tất cả", null),
    MUSIC("Nhạc nền", "duration:[30 TO 900]"),
    AMBIENCE("Âm thanh môi trường", "duration:[10 TO 300]"),
    SFX("Hiệu ứng âm thanh", "duration:[0.1 TO 15]"),
}

enum class FreesoundSort(
    val label: String,
    internal val apiValue: String,
) {
    RELEVANCE("Liên quan nhất", "score"),
    TOP_RATED("Đánh giá cao", "rating_desc"),
    MOST_DOWNLOADED("Tải nhiều", "downloads_desc"),
    LONGEST("Dài nhất", "duration_desc"),
    SHORTEST("Ngắn nhất", "duration_asc"),
}

data class FreesoundSearchRequest(
    val query: String,
    val category: FreesoundCategory = FreesoundCategory.ALL,
    val sort: FreesoundSort = FreesoundSort.RELEVANCE,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    fun normalized(): FreesoundSearchRequest = copy(
        query = query.trim().take(MAX_QUERY_LENGTH),
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE),
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val MAX_QUERY_LENGTH = 240
    }
}

data class FreesoundSound(
    val id: Int,
    val name: String,
    val username: String,
    val license: String,
    val durationSeconds: Double,
    val tags: List<String>,
    val previewHqMp3: String?,
    val previewHqOgg: String?,
    val avgRating: Double,
    val numRatings: Int,
    val numDownloads: Int,
    val webUrl: String?,
    val fileType: String,
    val channels: Int,
    val sampleRate: Int,
) {
    val preferredPreviewUrl: String?
        get() = previewHqMp3 ?: previewHqOgg
}

data class FreesoundSearchPage(
    val count: Int,
    val page: Int,
    val pageSize: Int,
    val results: List<FreesoundSound>,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

sealed interface FreesoundSearchResult {
    data class Success(val page: FreesoundSearchPage) : FreesoundSearchResult
    data class Failure(
        val message: String,
        val httpCode: Int? = null,
    ) : FreesoundSearchResult
}
