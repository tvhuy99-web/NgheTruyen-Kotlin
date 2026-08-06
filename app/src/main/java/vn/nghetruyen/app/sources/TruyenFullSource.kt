package vn.nghetruyen.app.sources

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterPage
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.SourceHealth
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import java.net.URI
import java.security.MessageDigest

class TruyenFullSource(
    private val documentClient: HtmlDocumentClient = HttpHtmlClient(),
) : StorySource {

    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "Truyện Full",
        baseUrl = BASE_URL,
        health = SourceHealth.READY,
        categories = CATEGORY_URLS.keys.toList(),
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = search("", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (page <= 1 && isAllowedSourceUrl(trimmed)) {
            val document = documentClient.getDocument(trimmed, ALLOWED_HOSTS)
            return@guarded listOf(TruyenFullParser.parseStoryDetail(document, trimmed).story)
        }
        val target = if (trimmed.isBlank()) {
            pagedUrl("$BASE_URL/danh-sach/truyen-moi/", page)
        } else {
            val firstPage = "$BASE_URL/tim-kiem/".toHttpUrl().newBuilder()
                .addQueryParameter("tukhoa", trimmed)
                .build()
                .toString()
            pagedUrl(firstPage, page)
        }
        TruyenFullParser.parseStoryList(documentClient.getDocument(target, ALLOWED_HOSTS))
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val base = CATEGORY_URLS[category]
            ?: throw SourceParseException("Không nhận ra thể loại: $category")
        val target = pagedUrl(base, page)
        TruyenFullParser.parseStoryList(documentClient.getDocument(target, ALLOWED_HOSTS))
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        val document = documentClient.getDocument(url, ALLOWED_HOSTS)
        val parsed = TruyenFullParser.parseStoryDetail(document, url)
        val page = TruyenFullParser.parseChapterPage(
            document = document,
            storyId = parsed.story.id,
            startIndex = 0,
            currentUrl = url,
        )
        parsed.copy(
            chapters = page.chapters,
            nextChapterPageUrl = page.nextPageUrl,
        )
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val firstDocument = documentClient.getDocument(url, ALLOWED_HOSTS)
        val storyId = TruyenFullParser.parseStoryDetail(firstDocument, url).story.id
        val lastPageUrl = TruyenFullParser.findLastChapterPageUrl(firstDocument, url)
        val lastDocument = if (lastPageUrl == url) firstDocument
        else documentClient.getDocument(lastPageUrl, ALLOWED_HOSTS)
        TruyenFullParser.parseChapterPage(lastDocument, storyId, 0, lastPageUrl).chapters.lastOrNull()
    }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = guarded("CHAPTER_PAGE_FAILED") {
        val document = documentClient.getDocument(url, ALLOWED_HOSTS)
        TruyenFullParser.parseChapterPage(
            document = document,
            storyId = storyId,
            startIndex = startIndex,
            currentUrl = url,
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        TruyenFullParser.parseChapterContent(
            document = documentClient.getDocument(url, ALLOWED_HOSTS),
            chapterUrl = url,
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
        AppResult.Failure(
            "SOURCE_BROWSER_VERIFICATION_REQUIRED",
            error.message ?: "Nguồn yêu cầu xác minh trên trình duyệt.",
            error,
        )
    } catch (error: SourceParseException) {
        AppResult.Failure("SOURCE_LAYOUT_CHANGED", error.message ?: "Cấu trúc trang đã thay đổi.", error)
    } catch (error: Exception) {
        AppResult.Failure(code, error.message ?: "Không thể tải dữ liệu từ Truyện Full.", error)
    }

    companion object {
        const val ID = "truyenfull"
        const val BASE_URL = "https://truyenfull.live"

        val ALLOWED_HOSTS = setOf(
            "truyenfull.live",
            "truyenfull.today",
            "truyenfull.vn",
            "truyenfull.net",
            "truyenfull.io",
            "truyenfull.bio",
            "truyenfull.vision",
        )

        val CATEGORY_URLS = linkedMapOf(
            "Truyện Hot" to "$BASE_URL/danh-sach/truyen-hot/",
            "Truyện Full" to "$BASE_URL/danh-sach/truyen-full/",
            "Tiên Hiệp" to "$BASE_URL/the-loai/tien-hiep/",
            "Kiếm Hiệp" to "$BASE_URL/the-loai/kiem-hiep/",
            "Ngôn Tình" to "$BASE_URL/the-loai/ngon-tinh/",
            "Đô Thị" to "$BASE_URL/the-loai/do-thi/",
            "Huyền Huyễn" to "$BASE_URL/the-loai/huyen-huyen/",
            "Xuyên Không" to "$BASE_URL/the-loai/xuyen-khong/",
            "Trọng Sinh" to "$BASE_URL/the-loai/trong-sinh/",
            "Linh Dị" to "$BASE_URL/the-loai/linh-di/",
            "Đam Mỹ" to "$BASE_URL/the-loai/dam-my/",
            "Bách Hợp" to "$BASE_URL/the-loai/bach-hop/",
            "Light Novel" to "$BASE_URL/the-loai/light-novel/",
        )

        internal fun isAllowedSourceUrl(value: String): Boolean {
            val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
            return parsed.isHttps && ALLOWED_HOSTS.any { parsed.host == it || parsed.host.endsWith(".$it") }
        }

        internal fun pagedUrl(baseUrl: String, page: Int): String {
            if (page <= 1) return baseUrl
            val parsed = baseUrl.toHttpUrl()
            if (parsed.queryParameter("tukhoa") != null) {
                return parsed.newBuilder().addQueryParameter("page", page.toString()).build().toString()
            }
            return baseUrl.trimEnd('/').replace(Regex("/trang-\\d+$"), "") + "/trang-$page/"
        }
    }
}

internal object TruyenFullParser {
    private val chapterNumberRegex = Regex("(?i)\\bch(?:ương|uong)\\s*(\\d+)")
    private val titleSuffixRegex = Regex("(?i)\\s+[|\\-]\\s+Truy(?:ệ|e)n Full.*$")
    private val whitespaceRegex = Regex("[\\t\\x0B\\f\\r ]+")
    private val blankLinesRegex = Regex("\\n{3,}")

    fun parseStoryList(document: Document): List<StorySummary> {
        ensureNotChallenge(document)
        val stories = document.select(".list-truyen div[itemscope]")
            .mapNotNull { item ->
                val link = item.selectFirst(".truyen-title > a") ?: return@mapNotNull null
                val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
                val title = link.text().normalizeText()
                if (title.isBlank()) return@mapNotNull null
                StorySummary(
                    id = storyId(url),
                    sourceId = TruyenFullSource.ID,
                    title = title,
                    author = item.selectFirst("[itemprop=author], .author, .list-author")
                        ?.text()
                        .orEmpty()
                        .normalizeText(),
                    description = item.selectFirst(".desc, .description")
                        ?.text()
                        .orEmpty()
                        .normalizeText(),
                    url = url,
                )
            }
            .distinctBy(StorySummary::url)

        if (stories.isEmpty() && !looksLikeEmptyResult(document)) {
            throw SourceParseException("Không tìm thấy danh sách truyện theo selector đã biết.")
        }
        return stories
    }

    fun parseStoryDetail(document: Document, storyUrl: String): StoryDetail {
        ensureNotChallenge(document)
        val title = document.selectFirst("h3.title, h1.title, [itemprop=name]")
            ?.text()
            .orEmpty()
            .normalizeText()
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện.")

        val author = document.selectFirst("a[itemprop=author], .info a[href*=tac-gia], .info .author")
            ?.text()
            .orEmpty()
            .normalizeText()
        val cover = document.selectFirst("div.book img, img[itemprop=image]")
            ?.safeAbsoluteUrl("src")
        val description = document.selectFirst("div.desc-text, [itemprop=description]")
            ?.text()
            .orEmpty()
            .normalizeMultilineText()
        val genres = document.select(".info a[itemprop=genre], a[href*=/the-loai/]")
            .map { it.text().normalizeText() }
            .filter(String::isNotBlank)
            .distinct()
        val statusText = document.selectFirst(
            ".info .text-primary, .info .text-success, [itemprop=bookFormat], .info > div:nth-child(4)",
        )?.text().orEmpty().normalizeText()

        return StoryDetail(
            story = StorySummary(
                id = storyId(storyUrl),
                sourceId = TruyenFullSource.ID,
                title = title,
                author = author,
                coverUrl = cover,
                description = description,
                url = storyUrl,
            ),
            genres = genres,
            status = statusText,
        )
    }

    fun parseChapterPage(
        document: Document,
        storyId: String,
        startIndex: Int,
        currentUrl: String,
    ): ChapterPage {
        ensureNotChallenge(document)
        val links = document.select(
            "#list-chapter li a, ul.list-chapter li a, .list-chapter li a",
        )
        val chapters = links.mapNotNull { link ->
            val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
            val title = link.text().normalizeText()
            if (title.isBlank() || !looksLikeChapterLink(url, title)) return@mapNotNull null
            title to url
        }.distinctBy { it.second }
            .mapIndexed { offset, (title, url) ->
                ChapterSummary(
                    id = chapterId(url),
                    storyId = storyId,
                    index = startIndex + offset,
                    title = title,
                    url = url,
                )
            }

        if (chapters.isEmpty()) {
            throw SourceParseException("Không đọc được danh sách chương.")
        }
        return ChapterPage(
            chapters = chapters,
            nextPageUrl = nextChapterPageUrl(document, currentUrl, chapters.size),
        )
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        ensureNotChallenge(document)
        val content = document.selectFirst("div.chapter-c, [itemprop=articleBody]")?.clone()
            ?: throw SourceParseException("Không đọc được nội dung chương.")
        content.select(
            "noscript, script, iframe, style, div.ads-responsive, .ads, .advertisement, " +
                "[style*=\"font-size:0\"], [style*=\"font-size: 0\"]",
        ).remove()
        content.select("a").remove()
        content.select("br").forEach { it.after("\n") }
        content.select("p, div, section, blockquote").forEach { it.after("\n") }

        val paragraphs = content.wholeText()
            .replace('\u00A0', ' ')
            .replace(blankLinesRegex, "\n\n")
            .split(Regex("\\n+"))
            .map { it.normalizeText() }
            .filter(String::isNotBlank)
            .filterNot { it.contains("Chương này có nội dung ảnh", ignoreCase = true) }
            .filterNot(::isKnownBoilerplate)

        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương rỗng sau khi làm sạch.")

        val rawTitle = document.selectFirst("h2, h1, .chapter-title")?.text()
            ?: document.title()
        val title = rawTitle.replace(titleSuffixRegex, "").normalizeText().ifBlank { "Chương truyện" }
        val storyId = storyIdFromChapterUrl(chapterUrl)
        val chapterNumber = chapterNumberRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val index = (chapterNumber?.minus(1) ?: 0).coerceAtLeast(0)

        return ChapterContent(
            chapter = ChapterSummary(
                id = chapterId(chapterUrl),
                storyId = storyId,
                index = index,
                title = title,
                url = chapterUrl,
            ),
            paragraphs = paragraphs,
            previousChapterUrl = document.firstSafeUrl(
                "a#prev_chap, .chapter-nav a[rel=prev], a[rel=prev]",
            ),
            nextChapterUrl = document.firstSafeUrl(
                "a#next_chap, .chapter-nav a[rel=next], a[rel=next]",
            ),
        )
    }

    internal fun findLastChapterPageUrl(document: Document, currentUrl: String): String {
        return document.select("#list-chapter .pagination a, .pagination a, ul.pagination a")
            .mapNotNull { link ->
                val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
                val page = Regex("/trang-(\\d+)/?").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: link.text().trim().toIntOrNull()
                    ?: if (url.trimEnd('/') == currentUrl.trimEnd('/')) 1 else null
                page?.let { it to url }
            }
            .maxByOrNull { it.first }
            ?.second
            ?: currentUrl
    }

    internal fun nextChapterPageUrl(document: Document, currentUrl: String, chapterCount: Int): String? {
        val direct = document.firstSafeUrl(
            "#list-chapter .pagination li.active + li a, " +
                ".pagination li.active + li a, ul.pagination li.active + li a, a[rel=next]",
        )
        if (direct != null && direct != currentUrl && isChapterListPage(direct)) return direct

        val currentPage = Regex("/trang-(\\d+)/?").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: currentUrl.toHttpUrl().queryParameter("page")?.toIntOrNull()
            ?: 1
        val wantedPage = currentPage + 1
        val paginationUrls = document.select(".pagination a[href]")
            .mapNotNull { it.safeAbsoluteUrl("href") }
        paginationUrls.firstOrNull { pageNumber(it) == wantedPage }?.let { return it }

        if (chapterCount < EXPECTED_PAGE_SIZE) return null
        val cleaned = currentUrl.substringBefore('#').substringBefore('?')
            .replace(Regex("/trang-\\d+/?$"), "")
            .trimEnd('/')
        return if (cleaned.isBlank()) null else "$cleaned/trang-$wantedPage/"
    }

    private fun ensureNotChallenge(document: Document) {
        val title = document.title().lowercase()
        val body = document.body()?.text().orEmpty().lowercase()
        val challenge = title.contains("just a moment") ||
            body.contains("verify you are human") ||
            body.contains("xác minh bạn là con người") ||
            document.selectFirst("#challenge-platform, .cf-challenge-running") != null
        if (challenge) {
            throw SourceChallengeException(
                "Truyện Full đang yêu cầu xác minh trình duyệt; adapter không tự vượt cơ chế bảo vệ.",
            )
        }
    }

    private fun looksLikeEmptyResult(document: Document): Boolean {
        val text = document.body()?.text().orEmpty()
        return text.contains("không tìm thấy", ignoreCase = true) ||
            text.contains("chưa có truyện", ignoreCase = true)
    }

    private fun looksLikeChapterLink(url: String, title: String): Boolean {
        if (title.contains("trang tiếp", ignoreCase = true) || title.matches(Regex("\\d+"))) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return path.isNotBlank() && !path.contains("/trang-") &&
            (title.contains("chương", ignoreCase = true) || path.count { it == '/' } >= 2)
    }

    private fun isChapterListPage(url: String): Boolean =
        url.contains("/trang-") || url.toHttpUrl().queryParameter("page") != null

    private fun pageNumber(url: String): Int? =
        Regex("/trang-(\\d+)/?").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: runCatching { url.toHttpUrl().queryParameter("page")?.toIntOrNull() }.getOrNull()

    private fun isKnownBoilerplate(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("nguồn truyện:") ||
            normalized == "truyện full" ||
            normalized.contains("đọc truyện tại truyenfull")
    }

    private fun storyIdFromChapterUrl(url: String): String {
        val parsed = url.toHttpUrl()
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        val storyPath = if (segments.size >= 2) segments.dropLast(1).joinToString("/") else segments.joinToString("/")
        return stableId("story", "${parsed.host}/$storyPath")
    }

    private fun storyId(url: String): String = stableId("story", canonicalUrlKey(url))
    private fun chapterId(url: String): String = stableId("chapter", canonicalUrlKey(url))

    private fun canonicalUrlKey(url: String): String {
        val parsed = url.toHttpUrl()
        return "${parsed.host}${parsed.encodedPath.trimEnd('/')}"
    }

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$prefix:$digest"
    }

    private fun String.normalizeText(): String = trim().replace(whitespaceRegex, " ")

    private fun String.normalizeMultilineText(): String = lineSequence()
        .map { it.normalizeText() }
        .filter(String::isNotBlank)
        .joinToString("\n")

    private fun Element.safeAbsoluteUrl(attribute: String): String? {
        val absolute = absUrl(attribute).ifBlank { attr(attribute) }.trim()
        if (absolute.isBlank() || absolute.startsWith("javascript:", ignoreCase = true) || absolute == "#") {
            return null
        }
        val parsed = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return null
        if (!parsed.isHttps || TruyenFullSource.ALLOWED_HOSTS.none { parsed.host == it || parsed.host.endsWith(".$it") }) {
            return null
        }
        return parsed.toString()
    }

    private fun Document.firstSafeUrl(selector: String): String? =
        select(selector).firstNotNullOfOrNull { it.safeAbsoluteUrl("href") }

    private const val EXPECTED_PAGE_SIZE = 50
}

