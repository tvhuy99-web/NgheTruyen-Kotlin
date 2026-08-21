package vn.nghetruyen.app.freesound

enum class FreesoundCategory(
    val label: String,
    internal val recommendedDurationFilter: String?,
) {
    ALL("Tất cả", null),
    MUSIC("Nhạc nền", "category:\"Music\" duration:[30 TO 900]"),
    AMBIENCE("Âm thanh môi trường", "category:\"Soundscapes\" duration:[10 TO 300]"),
    SFX("Hiệu ứng âm thanh", "category:\"Sound effects\" duration:[0.1 TO 15]"),
}

enum class FreesoundDuration(
    val label: String,
) {
    RECOMMENDED("Phù hợp loại đang quản lý"),
    ALL("Tất cả thời lượng"),
    SHORT("Ngắn: 0–15 giây"),
    MEDIUM("Trung bình: 15–60 giây"),
    LONG("Dài: 1–5 phút"),
    VERY_LONG("Rất dài: trên 5 phút"),
    ;

    internal fun apiFilter(category: FreesoundCategory): String? = when (this) {
        RECOMMENDED -> category.recommendedDurationFilter
        ALL -> null
        SHORT -> "duration:[0 TO 15]"
        MEDIUM -> "duration:[15 TO 60]"
        LONG -> "duration:[60 TO 300]"
        VERY_LONG -> "duration:[300 TO *]"
    }
}

enum class FreesoundSort(
    val label: String,
    internal val apiValue: String,
) {
    RELEVANCE("Liên quan nhất", "score"),
    SHORTEST("Ngắn nhất", "duration_asc"),
    LONGEST("Dài nhất", "duration_desc"),
}

data class FreesoundSearchRequest(
    val query: String,
    val category: FreesoundCategory = FreesoundCategory.ALL,
    val duration: FreesoundDuration = FreesoundDuration.RECOMMENDED,
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
    val description: String,
    val durationSeconds: Double,
    val previewHqMp3: String?,
    val previewHqOgg: String?,
    val username: String = "",
    val license: String = "",
    val webUrl: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "",
    val subcategory: String = "",
    val categoryCode: String = "",
    val avgRating: Double = 0.0,
    val numRatings: Int = 0,
    val numDownloads: Int = 0,
    val searchScore: Double = 0.0,
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

enum class FreesoundImportQueueStatus {
    QUEUED,
    IMPORTING,
    IMPORTED,
    FAILED,
    DUPLICATE,
    CANCELLED,
}

data class FreesoundImportQueueSummary(
    val queued: Int,
    val importing: Int,
    val imported: Int,
    val failed: Int,
    val duplicate: Int,
    val cancelled: Int,
) {
    val total: Int
        get() = queued + importing + imported + failed + duplicate + cancelled
}

internal fun summarizeFreesoundQueue(
    states: Collection<FreesoundImportQueueStatus>,
): FreesoundImportQueueSummary = FreesoundImportQueueSummary(
    queued = states.count { it == FreesoundImportQueueStatus.QUEUED },
    importing = states.count { it == FreesoundImportQueueStatus.IMPORTING },
    imported = states.count { it == FreesoundImportQueueStatus.IMPORTED },
    failed = states.count { it == FreesoundImportQueueStatus.FAILED },
    duplicate = states.count { it == FreesoundImportQueueStatus.DUPLICATE },
    cancelled = states.count { it == FreesoundImportQueueStatus.CANCELLED },
)
