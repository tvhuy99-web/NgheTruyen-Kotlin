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
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Kotlin port of the Truyện Com API-v2 source bundled in the original XPK.
 * It remains DEGRADED until the selectors have passed live device tests.
 */
class TruyenComSource(
    private val documentClient: HtmlDocumentClient = HttpHtmlClient(),
) : StorySource {

    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "Truyện Com",
        baseUrl = BASE_URL,
        health = SourceHealth.DEGRADED,
        categories = CATEGORY_URLS.keys.toList(),
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = category("Mới cập nhật", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (page <= 1 && isAllowedSourceUrl(trimmed)) {
            val document = documentClient.getDocument(trimmed, ALLOWED_HOSTS)
            return@guarded listOf(TruyenComParser.parseStoryDetail(document, trimmed).story)
        }
        val base = if (trimmed.isBlank()) {
            CATEGORY_URLS.getValue("Mới cập nhật")
        } else {
            "$BASE_URL/searching/${searchSlug(trimmed)}/"
        }
        TruyenComParser.parseStoryList(
            documentClient.getDocument(pagedUrl(base, page), ALLOWED_HOSTS),
        )
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val base = CATEGORY_URLS[category]
            ?: throw SourceParseException("Không nhận ra thể loại Truyện Com: $category")
        TruyenComParser.parseStoryList(
            documentClient.getDocument(pagedUrl(base, page), ALLOWED_HOSTS),
        )
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        val document = documentClient.getDocument(url, ALLOWED_HOSTS)
        val detail = TruyenComParser.parseStoryDetail(document, url)
        val page = TruyenComParser.parseChapterPage(document, detail.story.id, 0, url)
        detail.copy(chapters = page.chapters, nextChapterPageUrl = page.nextPageUrl)
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val firstDocument = documentClient.getDocument(url, ALLOWED_HOSTS)
        val storyId = TruyenComParser.parseStoryDetail(firstDocument, url).story.id
        val lastPageUrl = TruyenComParser.findLastChapterPageUrl(firstDocument, url)
        val lastDocument = if (lastPageUrl == url) firstDocument
        else documentClient.getDocument(lastPageUrl, ALLOWED_HOSTS)
        TruyenComParser.parseChapterPage(lastDocument, storyId, 0, lastPageUrl).chapters.lastOrNull()
    }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = guarded("CHAPTER_PAGE_FAILED") {
        TruyenComParser.parseChapterPage(
            document = documentClient.getDocument(url, ALLOWED_HOSTS),
            storyId = storyId,
            startIndex = startIndex,
            currentUrl = url,
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        TruyenComParser.parseChapterContent(
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
        AppResult.Failure(code, error.message ?: "Không thể tải dữ liệu từ Truyện Com.", error)
    }

    companion object {
        const val ID = "truyencom"
        const val BASE_URL = "https://truyencom.com"

        val ALLOWED_HOSTS = setOf(
            "truyencom.com",
            "www.truyencom.com",
            "dtruyen.com",
            "www.dtruyen.com",
        )

        val CATEGORY_URLS = linkedMapOf(
            "Mới cập nhật" to "$BASE_URL/truyen-moi-cap-nhat/",
            "Truyện mới đăng" to "$BASE_URL/truyen-moi-dang/",
            "Truyện Hot" to "$BASE_URL/truyen-hot/",
            "Truyện Full" to "$BASE_URL/truyen-full/",
            "Tiên Hiệp" to "$BASE_URL/truyen-tien-hiep/full/",
            "Kiếm Hiệp" to "$BASE_URL/truyen-kiem-hiep/full/",
            "Ngôn Tình" to "$BASE_URL/truyen-ngon-tinh/full/",
            "Xuyên Không" to "$BASE_URL/truyen-xuyen-khong/full/",
            "Linh Dị" to "$BASE_URL/truyen-linh-di/full/",
            "Huyền Huyễn" to "$BASE_URL/truyen-huyen-huyen/full/",
            "Đô Thị" to "$BASE_URL/truyen-do-thi/full/",
            "Trọng Sinh" to "$BASE_URL/truyen-trong-sinh/full/",
            "Đam Mỹ" to "$BASE_URL/truyen-dam-my/full/",
            "Bách Hợp" to "$BASE_URL/truyen-bach-hop/full/",
            "Light Novel" to "$BASE_URL/truyen-light-novel/full/",
        )

        internal fun isAllowedSourceUrl(value: String): Boolean {
            val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
            return parsed.isHttps && ALLOWED_HOSTS.any { parsed.host == it || parsed.host.endsWith(".$it") }
        }

        internal fun pagedUrl(baseUrl: String, page: Int): String {
            if (page <= 1) return baseUrl
            val clean = baseUrl.substringBefore('#').substringBefore('?')
                .replace(Regex("/trang-\\d+/?$"), "")
                .trimEnd('/')
            return "$clean/trang-$page/"
        }

        internal fun searchSlug(value: String): String {
            val normalized = Normalizer.normalize(value.lowercase().replace('đ', 'd'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            require(normalized.isNotBlank()) { "Từ khóa tìm kiếm không hợp lệ." }
            return normalized
        }
    }
}

internal object TruyenComParser {
    private val storyPathRegex = Regex("^/[a-z0-9-]+\\.\\d+/?$", RegexOption.IGNORE_CASE)
    private val chapterNumberRegex = Regex("(?i)\\bch(?:ương|uong)\\s*(\\d+)")
    private val whitespaceRegex = Regex("[\\t\\x0B\\f\\r ]+")

    fun parseStoryList(document: Document): List<StorySummary> {
        ensureNotChallenge(document)
        val stories = document.select(
            ".list-truyen .truyen-title a, .list-truyen h3 a, .list-truyen h2 a, " +
                "h3.truyen-title a, .story-title a, main h3 a[href]",
        ).mapNotNull { link ->
            val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
            if (!isStoryUrl(url)) return@mapNotNull null
            val title = link.text().normalizeText()
            if (title.isBlank()) return@mapNotNull null
            val box = link.closest("article, li, .row, .item, .story, [class*=truyen]")
            StorySummary(
                id = storyId(url),
                sourceId = TruyenComSource.ID,
                title = title,
                author = box?.selectFirst(".author, [itemprop=author], a[href*=/tac-gia/]")
                    ?.text().orEmpty().normalizeText(),
                description = box?.selectFirst(".desc, .description")
                    ?.text().orEmpty().normalizeText(),
                url = url,
            )
        }.distinctBy(StorySummary::url)

        if (stories.isEmpty() && !looksLikeEmptyResult(document)) {
            throw SourceParseException("Không tìm thấy danh sách truyện Truyện Com theo selector đã biết.")
        }
        return stories
    }

    fun parseStoryDetail(document: Document, storyUrl: String): StoryDetail {
        ensureNotChallenge(document)
        val title = document.selectFirst("h3.title, h1.title, h1, [itemprop=name]")
            ?.text().orEmpty().normalizeText()
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện Truyện Com.")
        val author = document.selectFirst("a[href*=/tac-gia/], .info a[itemprop=author], [itemprop=author]")
            ?.text().orEmpty().normalizeText()
        val cover = document.selectFirst("meta[property=og:image]")?.safeAbsoluteUrl("content")
            ?: document.selectFirst(".book img, .info-holder img, img[itemprop=image]")?.safeAbsoluteUrl("src")
        val description = document.selectFirst(".desc-text, .description, .desc, [itemprop=description]")
            ?.text().orEmpty().normalizeMultilineText()
        val genres = document.select("a[href*=/the-loai/], .info a[itemprop=genre]")
            .map { it.text().normalizeText() }
            .filter(String::isNotBlank)
            .distinct()
        val status = document.body()?.text().orEmpty().let {
            when {
                it.contains("Hoàn thành", ignoreCase = true) -> "Hoàn thành"
                it.contains("Đang ra", ignoreCase = true) || it.contains("Đang cập nhật", ignoreCase = true) -> "Đang ra"
                else -> ""
            }
        }
        return StoryDetail(
            story = StorySummary(
                id = storyId(storyUrl),
                sourceId = TruyenComSource.ID,
                title = title,
                author = author,
                coverUrl = cover,
                description = description,
                url = storyUrl,
            ),
            genres = genres,
            status = status,
        )
    }

    fun parseChapterPage(
        document: Document,
        storyId: String,
        startIndex: Int,
        currentUrl: String,
    ): ChapterPage {
        ensureNotChallenge(document)
        val chapters = document.select(
            ".list-chapter a[href*=/chuong-], ul.list-chapter a[href*=/chuong-], " +
                "#list-chapter a[href*=/chuong-]",
        ).mapNotNull { link ->
            val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
            val title = link.text().normalizeText()
            if (title.isBlank() || !title.contains("chương", ignoreCase = true)) return@mapNotNull null
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
        if (chapters.isEmpty()) throw SourceParseException("Không đọc được mục lục Truyện Com.")
        return ChapterPage(
            chapters = chapters,
            nextPageUrl = document.firstSafeUrl(
                ".pagination li.active + li a, a[rel=next], .pagination a.next, .pagination .next a",
            )?.takeIf { it != currentUrl },
        )
    }

    fun findLastChapterPageUrl(document: Document, currentUrl: String): String {
        return document.select(".pagination a[href], ul.pagination a[href]")
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

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        ensureNotChallenge(document)
        val content = document.selectFirst("#chapter-c, .chapter-c, #chapter-content, .chapter-content")?.clone()
            ?: throw SourceParseException("Không đọc được nội dung chương Truyện Com.")
        content.select("script, style, iframe, noscript, .ads, .chapter-nav, .navigation").remove()
        content.select("br").forEach { it.after("\n") }
        content.select("p, div, section, blockquote").forEach { it.after("\n") }
        val paragraphs = content.wholeText()
            .replace('\u00A0', ' ')
            .split(Regex("\\n+"))
            .map { it.normalizeText() }
            .filter(String::isNotBlank)
            .filterNot(::isKnownBoilerplate)
        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương Truyện Com rỗng sau khi làm sạch.")

        val title = document.selectFirst(".chapter-title, h2, h1")?.text()
            .orEmpty().normalizeText().ifBlank { "Chương truyện" }
        val number = chapterNumberRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return ChapterContent(
            chapter = ChapterSummary(
                id = chapterId(chapterUrl),
                storyId = storyIdFromChapterUrl(chapterUrl),
                index = (number?.minus(1) ?: 0).coerceAtLeast(0),
                title = title,
                url = chapterUrl,
            ),
            paragraphs = paragraphs,
            previousChapterUrl = document.firstSafeUrl("a#prev_chap, a[rel=prev], .chapter-nav a.prev"),
            nextChapterUrl = document.firstSafeUrl("a#next_chap, a[rel=next], .chapter-nav a.next"),
        )
    }

    private fun ensureNotChallenge(document: Document) {
        val title = document.title().lowercase()
        val body = document.body()?.text().orEmpty().lowercase()
        if (title.contains("just a moment") || body.contains("verify you are human") ||
            body.contains("xác minh bạn là con người") ||
            document.selectFirst("#challenge-platform, .cf-challenge-running") != null
        ) {
            throw SourceChallengeException(
                "Truyện Com đang yêu cầu xác minh trình duyệt; adapter không tự vượt cơ chế bảo vệ.",
            )
        }
    }

    private fun looksLikeEmptyResult(document: Document): Boolean {
        val text = document.body()?.text().orEmpty()
        return text.contains("không tìm thấy", ignoreCase = true) ||
            text.contains("chưa có truyện", ignoreCase = true)
    }

    private fun isStoryUrl(url: String): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        return storyPathRegex.matches(parsed.encodedPath)
    }

    private fun isKnownBoilerplate(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("nguồn truyện:") ||
            normalized.contains("đọc truyện tại truyencom") ||
            normalized.contains("truyện được đăng tại")
    }

    private fun storyIdFromChapterUrl(url: String): String {
        val parsed = url.toHttpUrl()
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        val storySegments = if (segments.size >= 2) segments.dropLast(1) else segments
        return stableId("story", "${parsed.host}/${storySegments.joinToString("/")}")
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
        if (!parsed.isHttps || TruyenComSource.ALLOWED_HOSTS.none { parsed.host == it || parsed.host.endsWith(".$it") }) {
            return null
        }
        return parsed.toString()
    }

    private fun Document.firstSafeUrl(selector: String): String? =
        select(selector).firstNotNullOfOrNull { it.safeAbsoluteUrl("href") }
}
