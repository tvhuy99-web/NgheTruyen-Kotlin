package vn.nghetruyen.app.sources

import okhttp3.HttpUrl.Companion.toHttpUrl
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import java.security.MessageDigest
import kotlin.math.ceil








class TruyenYySource(
    private val textClient: TextDocumentClient = HttpTextClient(),
) : StorySource {
    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "TruyenYY",
        baseUrl = BASE_URL,
        health = SourceHealth.DEGRADED,
        categories = CATEGORY_URLS.keys.toList(),
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = category("Mới cập nhật", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            if (!isStoryTarget(trimmed)) {
                throw IllegalArgumentException("Chỉ chấp nhận URL truyện TruyenYY qua HTTPS.")
            }
            return@guarded listOf(loadStory(trimmed).story)
        }
        val target = if (trimmed.isBlank()) {
            pagedUrl(CATEGORY_URLS.getValue("Mới cập nhật"), page)
        } else {
            val searchUrl = "$BASE_URL/tim-kiem/nang-cao".toHttpUrl().newBuilder()
                .setQueryParameter("q", trimmed)
                .build()
                .toString()
            pagedUrl(searchUrl, page)
        }
        TruyenYyParser.parseStoryList(fetch(target))
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val target = CATEGORY_URLS[category]
            ?: throw SourceParseException("Không nhận ra thể loại TruyenYY: $category")
        TruyenYyParser.parseStoryList(fetch(pagedUrl(target, page)))
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        loadStory(url)
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        TruyenYyParser.parseChapter(fetch(url), canonicalTarget(url))
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val storyUrl = normalizeStoryUrl(url)
        val firstTarget = tocUrl(storyUrl, 1)
        val first = TruyenYyParser.parseChapterList(fetch(firstTarget), storyId(storyUrl), 0, firstTarget)
        if (first.totalChapters <= first.page.chapters.size || first.totalChapters <= 0) {
            return@guarded first.page.chapters.lastOrNull()
        }
        val lastPage = ceil(first.totalChapters / CHAPTER_PAGE_SIZE.toDouble()).toInt().coerceAtLeast(1)
        val lastTarget = tocUrl(storyUrl, lastPage)
        TruyenYyParser.parseChapterList(
            fetch(lastTarget),
            storyId(storyUrl),
            (lastPage - 1) * CHAPTER_PAGE_SIZE,
            lastTarget,
        ).page.chapters.lastOrNull()
    }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = guarded("CHAPTER_PAGE_FAILED") {
        val page = pageNumber(url).takeIf { it > 1 }
            ?: (startIndex / CHAPTER_PAGE_SIZE + 1).coerceAtLeast(1)
        val target = tocUrl(url, page)
        TruyenYyParser.parseChapterList(fetch(target), storyId, startIndex, target).page
    }

    private suspend fun loadStory(url: String): StoryDetail {
        val target = normalizeStoryUrl(url)
        val detail = TruyenYyParser.parseStoryDetail(fetch(target), target)
        val tocTarget = tocUrl(target, 1)
        val chapters = TruyenYyParser.parseChapterList(
            fetch(tocTarget),
            detail.story.id,
            0,
            tocTarget,
        ).page
        return detail.copy(chapters = chapters.chapters, nextChapterPageUrl = chapters.nextPageUrl)
    }

    private suspend fun fetch(target: String): String {
        val canonical = canonicalTarget(target)
        if (!isAllowedTarget(canonical)) throw IllegalArgumentException("URL TruyenYY không hợp lệ.")
        return textClient.getText(
            url = jinaUrl(canonical),
            allowedHosts = setOf(JINA_HOST),
            headers = REQUEST_HEADERS,
        )
    }

    private suspend fun <T> guarded(code: String, block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (error: ResponseTooLargeException) {
        AppResult.Failure("SOURCE_RESPONSE_TOO_LARGE", error.message ?: "Trang nguồn quá lớn.", error)
    } catch (error: HttpSourceException) {
        AppResult.Failure("SOURCE_HTTP_${error.statusCode}", error.message ?: "Lỗi HTTP.", error)
    } catch (error: IllegalArgumentException) {
        AppResult.Failure("SOURCE_URL_REJECTED", error.message ?: "Địa chỉ nguồn không hợp lệ.", error)
    } catch (error: SourceChallengeException) {
        AppResult.Failure("SOURCE_BROWSER_VERIFICATION_REQUIRED", error.message ?: "Nguồn yêu cầu xác minh.", error)
    } catch (error: SourceParseException) {
        AppResult.Failure("SOURCE_LAYOUT_CHANGED", error.message ?: "Cấu trúc nguồn đã thay đổi.", error)
    } catch (error: Exception) {
        AppResult.Failure(code, error.message ?: "Không thể tải dữ liệu từ TruyenYY.", error)
    }

    companion object {
        const val ID = "truyenyy"
        const val BASE_URL = "https://truyenyy.co"
        const val JINA_HOST = "r.jina.ai"
        const val CHAPTER_PAGE_SIZE = 100

        val CATEGORY_URLS = linkedMapOf(
            "Mới cập nhật" to "$BASE_URL/truyen-moi-cap-nhat",
            "Truyện mới đăng" to "$BASE_URL/truyen-moi-dang",
            "Truyện dịch YY" to "$BASE_URL/truyen-dich-yy",
            "Ngôn tình" to "$BASE_URL/ngon-tinh/danh-sach",
            "Huyền huyễn" to "$BASE_URL/huyen-huyen/danh-sach",
            "Đô thị" to "$BASE_URL/do-thi/danh-sach",
            "Xuyên không" to "$BASE_URL/xuyen-khong/danh-sach",
            "Tiên hiệp" to "$BASE_URL/tien-hiep/danh-sach",
            "Kiếm hiệp" to "$BASE_URL/kiem-hiep/danh-sach",
            "Khoa huyễn" to "$BASE_URL/khoa-huyen/danh-sach",
            "Linh dị" to "$BASE_URL/linh-di/danh-sach",
            "Lịch sử" to "$BASE_URL/lich-su/danh-sach",
        )

        val REQUEST_HEADERS = mapOf(
            "Accept" to "text/plain,text/markdown,*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.7",
            "Cache-Control" to "no-cache",
            "X-Respond-With" to "markdown",
            "X-Remove-Selector" to "header,nav,footer,aside,script,style",
            "X-With-Images-Summary" to "false",
            "X-With-Links-Summary" to "false",
            "X-Max-Tokens" to "24000",
            "User-Agent" to HttpHtmlClient.DEFAULT_USER_AGENT,
        )

        fun canonicalTarget(value: String): String {
            val parsed = value.trim().substringBefore('#').toHttpUrl()
            require(parsed.host == "truyenyy.co" || parsed.host == "www.truyenyy.co") {
                "Miền TruyenYY không hợp lệ."
            }
            require(parsed.isHttps) { "TruyenYY chỉ chấp nhận HTTPS." }
            return parsed.newBuilder().host("truyenyy.co").build().toString()
        }

        fun normalizeStoryUrl(value: String): String {
            val parsed = canonicalTarget(value).toHttpUrl()
            val segments = parsed.pathSegments.filter(String::isNotBlank)
            require(segments.size >= 2 && segments[0] == "truyen") { "URL truyện TruyenYY không hợp lệ." }
            return parsed.newBuilder()
                .encodedPath("/truyen/${segments[1]}")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
        }

        fun tocUrl(value: String, page: Int): String {
            val story = normalizeStoryUrl(value)
            val base = "$story/danh-sach-chuong".toHttpUrl().newBuilder()
            if (page > 1) base.setQueryParameter("p", page.toString())
            return base.build().toString()
        }

        fun pagedUrl(value: String, page: Int): String {
            val url = canonicalTarget(value).toHttpUrl().newBuilder()
            if (page > 1) url.setQueryParameter("p", page.toString())
            else url.removeAllQueryParameters("p")
            return url.build().toString()
        }

        fun pageNumber(value: String): Int = runCatching {
            canonicalTarget(value).toHttpUrl().queryParameter("p")?.toIntOrNull() ?: 1
        }.getOrDefault(1)

        fun jinaUrl(value: String): String {
            val target = canonicalTarget(value)
                .replaceFirst("https://truyenyy.co", "http://truyenyy.co")
            return "https://$JINA_HOST/$target"
        }

        fun isAllowedTarget(value: String): Boolean = runCatching {
            val url = value.toHttpUrl()
            url.isHttps && (url.host == "truyenyy.co" || url.host == "www.truyenyy.co")
        }.getOrDefault(false)

        fun isStoryTarget(value: String): Boolean = runCatching {
            val url = canonicalTarget(value).toHttpUrl()
            val segments = url.pathSegments.filter(String::isNotBlank)
            segments.size >= 2 && segments[0] == "truyen" && segments[1].isNotBlank()
        }.getOrDefault(false)

        fun storyId(url: String): String = stableId("story", normalizeStoryUrl(url))
        fun chapterId(url: String): String = stableId("chapter", canonicalTarget(url))

        private fun stableId(prefix: String, value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "$prefix:$digest"
        }
    }
}

data class TruyenYyChapterList(
    val page: ChapterPage,
    val totalChapters: Int,
)

object TruyenYyParser {
    private val storyLink = Regex(
        "(?m)^#{2,4}\\s+\\[([^]\\n]+)]\\((https?://(?:www\\.)?truyenyy\\.co/truyen/[^)\\s]+)\\)",
    )
    private val chapterLink = Regex(
        "(?m)^\\s*\\*\\s+\\[([^]\\n]+)]\\((https?://(?:www\\.)?truyenyy\\.co/truyen/[^/\\s]+/[^)\\s]+)\\)",
    )
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]+\\)")
    private val markdownImage = Regex("!\\[[^]]*]\\([^)]+\\)")

    fun parseStoryList(markdown: String): List<StorySummary> {
        ensureUsable(markdown)
        val items = storyLink.findAll(markdown).mapNotNull { match ->
            val title = clean(match.groupValues[1])
            val url = match.groupValues[2].trimEnd(')', '/', ' ')
            if (title.isBlank()) null else StorySummary(
                id = TruyenYySource.storyId(url),
                sourceId = TruyenYySource.ID,
                title = title,
                url = TruyenYySource.normalizeStoryUrl(url),
            )
        }.distinctBy(StorySummary::url).take(60).toList()
        if (items.isEmpty() && !markdown.contains("không tìm thấy", ignoreCase = true)) {
            throw SourceParseException("Không đọc được danh sách TruyenYY từ Markdown.")
        }
        return items
    }

    fun parseStoryDetail(markdown: String, storyUrl: String): StoryDetail {
        ensureUsable(markdown)
        val title = Regex("(?m)^#\\s+([^\\n]+)").find(markdown)?.groupValues?.get(1)?.let(::clean)
            .orEmpty()
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện TruyenYY.")
        val author = Regex("(?im)^Tác giả\\s*:?\\s*([^\\n]+)").find(markdown)?.groupValues?.get(1)?.let(::clean)
            ?: markdown.lineSequence().dropWhile { !it.trim().startsWith("# ") }.drop(1)
                .map(String::trim).firstOrNull { it.isNotBlank() && !it.startsWith("[") }.orEmpty()
        val genres = Regex("(?im)^Thể loại\\s*:?\\s*([^\\n]+)").find(markdown)
            ?.groupValues?.get(1)?.split(',', '/', '•')?.map(::clean)?.filter(String::isNotBlank).orEmpty()
        val status = Regex("(?im)^Trạng thái\\s*:?\\s*([^\\n]+)").find(markdown)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val description = extractDescription(markdown)
        return StoryDetail(
            story = StorySummary(
                id = TruyenYySource.storyId(storyUrl),
                sourceId = TruyenYySource.ID,
                title = title,
                author = author,
                description = description,
                url = TruyenYySource.normalizeStoryUrl(storyUrl),
            ),
            genres = genres,
            status = status,
        )
    }

    fun parseChapterList(
        markdown: String,
        storyId: String,
        startIndex: Int,
        currentUrl: String,
    ): TruyenYyChapterList {
        ensureUsable(markdown)
        val raw = chapterLink.findAll(markdown).mapNotNull { match ->
            val name = clean(match.groupValues[1])
            val url = match.groupValues[2].trimEnd(')', ' ')
            if (!url.contains("/chuong-", ignoreCase = true)) return@mapNotNull null
            val number = Regex("^\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("chuong-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            Triple(name, url, number)
        }.distinctBy { it.second }.sortedWith(compareBy<Triple<String, String, Int?>> { it.third ?: Int.MAX_VALUE }.thenBy { it.first }).toList()
        if (raw.isEmpty()) throw SourceParseException("Không đọc được mục lục TruyenYY.")
        val chapters = raw.mapIndexed { offset, (name, url, number) ->
            val title = if (name.startsWith("Chương", ignoreCase = true)) name else "Chương $name"
            ChapterSummary(
                id = TruyenYySource.chapterId(url),
                storyId = storyId,
                index = startIndex + offset,
                title = title,
                url = TruyenYySource.canonicalTarget(url),
            )
        }
        val total = Regex("(?i)Truyện có\\s*(\\d+)\\s*chương").find(markdown)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val pageNumber = TruyenYySource.pageNumber(currentUrl)
        val hasNext = total > pageNumber * TruyenYySource.CHAPTER_PAGE_SIZE ||
            (total == 0 && chapters.size >= 95)
        val next = if (hasNext) TruyenYySource.tocUrl(currentUrl, pageNumber + 1) else null
        return TruyenYyChapterList(ChapterPage(chapters, next), total)
    }

    fun parseChapter(markdown: String, chapterUrl: String): ChapterContent {
        ensureUsable(markdown)
        if (markdown.contains("Chương VIP chỉ đọc trên app", ignoreCase = true) ||
            markdown.contains("Mua Chương VIP", ignoreCase = true)
        ) {
            throw SourceChallengeException("Chương TruyenYY yêu cầu quyền truy cập hoặc mua chương.")
        }
        val title = Regex("(?m)^#\\s+([^\\n]+)").find(markdown)?.groupValues?.get(1)?.let(::clean)
            ?.ifBlank { "Chương truyện" } ?: "Chương truyện"
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Phiên bản", ignoreCase = true) }
        val bodyLines = if (start >= 0) lines.drop(start + 1) else lines.dropWhile { !it.trim().startsWith("# ") }.drop(1)
        val content = bodyLines.takeWhile { line ->
            val value = line.trim()
            !value.startsWith("Bạn đang đọc", ignoreCase = true) &&
                !value.contains("Thông Tin Chương Truyện", ignoreCase = true)
        }.joinToString("\n")
        val paragraphs = cleanMarkdown(content).split(Regex("\\n\\s*\\n+"))
            .map(::clean)
            .filter(String::isNotBlank)
            .filterNot { it.startsWith("Phiên bản", ignoreCase = true) }
        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương TruyenYY rỗng sau khi làm sạch.")
        val previous = Regex(
            "\\[(?:Trước|Chương trước)]\\((https?://(?:www\\.)?truyenyy\\.co/truyen/[^)]+/chuong-[^)]+)\\)",
            RegexOption.IGNORE_CASE,
        ).find(markdown)?.groupValues?.get(1)?.let(TruyenYySource::canonicalTarget)
        val next = Regex(
            "\\[(?:Tiếp|Sau|Chương sau)]\\((https?://(?:www\\.)?truyenyy\\.co/truyen/[^)]+/chuong-[^)]+)\\)",
            RegexOption.IGNORE_CASE,
        ).find(markdown)?.groupValues?.get(1)?.let(TruyenYySource::canonicalTarget)
        val canonical = TruyenYySource.canonicalTarget(chapterUrl)
        val storyUrl = TruyenYySource.normalizeStoryUrl(canonical)
        val number = Regex("(?i)Chương\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("chuong-(\\d+)", RegexOption.IGNORE_CASE).find(canonical)?.groupValues?.get(1)?.toIntOrNull()
        return ChapterContent(
            chapter = ChapterSummary(
                id = TruyenYySource.chapterId(canonical),
                storyId = TruyenYySource.storyId(storyUrl),
                index = (number?.minus(1) ?: 0).coerceAtLeast(0),
                title = title,
                url = canonical,
            ),
            paragraphs = paragraphs,
            previousChapterUrl = previous,
            nextChapterUrl = next,
        )
    }

    private fun extractDescription(markdown: String): String {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val marker = Regex("(?im)^#{0,3}\\s*(?:Giói|Giới) Thiệu Truyện[^\\n]*$").find(normalized)
            ?: return ""
        val tail = normalized.substring(marker.range.last + 1)
        val block = tail.lineSequence().takeWhile { line ->
            val value = line.trim()
            !(value.startsWith("#") && value.isNotBlank()) &&
                !value.startsWith("Thế Giới Truyện", ignoreCase = true) &&
                !value.startsWith("Tải App", ignoreCase = true)
        }.joinToString("\n")
        return cleanMarkdown(block).lineSequence().map(::clean).filter(String::isNotBlank).joinToString("\n")
    }

    private fun cleanMarkdown(value: String): String = value
        .replace(markdownImage, "")
        .replace(markdownLink) { it.groupValues[1] }
        .lineSequence()
        .map { it.replace(Regex("^[#*\\-\\s]+"), "").trim() }
        .joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun ensureUsable(markdown: String) {
        if (markdown.isBlank()) throw SourceParseException("Phản hồi TruyenYY rỗng.")
        val lower = markdown.lowercase()
        if (lower.contains("verify you are human") || lower.contains("just a moment") ||
            lower.contains("cloudflare") && lower.contains("challenge")
        ) {
            throw SourceChallengeException("TruyenYY đang yêu cầu xác minh trình duyệt.")
        }
    }
}
