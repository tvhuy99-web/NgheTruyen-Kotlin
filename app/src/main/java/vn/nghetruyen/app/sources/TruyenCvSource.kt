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









class TruyenCvSource(
    private val documentClient: HtmlDocumentClient = HttpHtmlClient(),
) : StorySource {

    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "TruyenCV",
        baseUrl = BASE_URL,
        health = SourceHealth.DEGRADED,
        categories = CATEGORY_URLS.keys.toList(),
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = category("Mới cập nhật", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (page <= 1 && isAllowedSourceUrl(trimmed)) {
            val document = documentClient.getDocument(trimmed, ALLOWED_HOSTS)
            return@guarded listOf(TruyenCvParser.parseStoryDetail(document, trimmed).story)
        }
        val target = if (trimmed.isBlank()) {
            wordpressPage(CATEGORY_URLS.getValue("Mới cập nhật"), page)
        } else {
            val firstPage = "$BASE_URL/".toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmed)
                .build()
                .toString()
            searchPage(firstPage, page)
        }
        TruyenCvParser.parseStoryList(documentClient.getDocument(target, ALLOWED_HOSTS))
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val base = CATEGORY_URLS[category]
            ?: throw SourceParseException("Không nhận ra thể loại: $category")
        TruyenCvParser.parseStoryList(
            documentClient.getDocument(wordpressPage(base, page), ALLOWED_HOSTS),
        )
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        val baseUrl = normalizeStoryBase(url)
        val detailDocument = documentClient.getDocument(baseUrl, ALLOWED_HOSTS)
        val detail = TruyenCvParser.parseStoryDetail(detailDocument, baseUrl)
        val highestPage = TruyenCvParser.findHighestChapterPage(detailDocument)
        val chapterPageUrl = if (highestPage > 1) {
            "${baseUrl}chuong/page/$highestPage/"
        } else {
            baseUrl
        }
        val chapterDocument = if (chapterPageUrl == baseUrl) {
            detailDocument
        } else {
            documentClient.getDocument(chapterPageUrl, ALLOWED_HOSTS)
        }
        val chapterPage = TruyenCvParser.parseChapterPage(
            document = chapterDocument,
            storyId = detail.story.id,
            startIndex = 0,
            currentUrl = chapterPageUrl,
        )
        detail.copy(
            chapters = chapterPage.chapters,
            nextChapterPageUrl = chapterPage.nextPageUrl,
        )
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val baseUrl = normalizeStoryBase(url)
        val document = documentClient.getDocument(baseUrl, ALLOWED_HOSTS)
        val storyId = TruyenCvParser.parseStoryDetail(document, baseUrl).story.id



        TruyenCvParser.parseChapterPage(document, storyId, 0, baseUrl).chapters.lastOrNull()
    }

    override suspend fun chapterPage(
        storyId: String,
        url: String,
        startIndex: Int,
    ): AppResult<ChapterPage> = guarded("CHAPTER_PAGE_FAILED") {
        TruyenCvParser.parseChapterPage(
            document = documentClient.getDocument(url, ALLOWED_HOSTS),
            storyId = storyId,
            startIndex = startIndex,
            currentUrl = url,
        )
    }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        TruyenCvParser.parseChapterContent(
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
        AppResult.Failure(code, error.message ?: "Không thể tải dữ liệu từ TruyenCV.", error)
    }

    companion object {
        const val ID = "truyencv"
        const val BASE_URL = "https://truyencv.io"

        val ALLOWED_HOSTS = setOf("truyencv.io", "www.truyencv.io")

        val CATEGORY_URLS = linkedMapOf(
            "Mới cập nhật" to "$BASE_URL/moi-cap-nhat/",
            "Truyện hay" to "$BASE_URL/the-loai/hay/",
            "Huyền Huyễn" to "$BASE_URL/the-loai/huyen-huyen/",
            "Hệ Thống" to "$BASE_URL/the-loai/he-thong/",
            "Đô Thị" to "$BASE_URL/the-loai/do-thi/",
            "Xuyên Không" to "$BASE_URL/the-loai/xuyen-khong/",
            "Tiên Hiệp" to "$BASE_URL/the-loai/tien-hiep/",
            "Ngôn Tình" to "$BASE_URL/the-loai/ngon-tinh/",
            "Đồng Nhân" to "$BASE_URL/the-loai/dong-nhan/",
            "Dị Năng" to "$BASE_URL/the-loai/di-nang/",
            "Hậu Cung" to "$BASE_URL/the-loai/hau-cung/",
            "Dị Giới" to "$BASE_URL/the-loai/di-gioi/",
        )

        internal fun normalizeStoryBase(value: String): String {
            val parsed = value.toHttpUrl()
            parsed.requireTruyenCvHost()
            val normalizedPath = parsed.encodedPath
                .replace(Regex("/chuong/page/\\d+/?$"), "/")
                .trimEnd('/') + "/"
            return parsed.newBuilder()
                .encodedPath(normalizedPath)
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }

        internal fun isAllowedSourceUrl(value: String): Boolean {
            val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
            return parsed.isHttps && ALLOWED_HOSTS.any { parsed.host == it || parsed.host.endsWith(".$it") }
        }

        internal fun wordpressPage(baseUrl: String, page: Int): String {
            if (page <= 1) return baseUrl
            return baseUrl.trimEnd('/').replace(Regex("/page/\\d+$"), "") + "/page/$page/"
        }

        internal fun searchPage(baseUrl: String, page: Int): String {
            if (page <= 1) return baseUrl
            return baseUrl.toHttpUrl().newBuilder()
                .setQueryParameter("paged", page.toString())
                .build()
                .toString()
        }

        private fun okhttp3.HttpUrl.requireTruyenCvHost() {
            require(isHttps) { "Nguồn TruyenCV chỉ hỗ trợ HTTPS." }
            require(ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                "Miền $host không thuộc TruyenCV."
            }
        }
    }
}

internal object TruyenCvParser {
    private const val STORY_LINK_SELECTOR =
        "h1 a[href*='/truyen/'], h2 a[href*='/truyen/'], h3 a[href*='/truyen/'], " +
            "h4 a[href*='/truyen/'], .post-title a[href*='/truyen/'], " +
            ".manga-title a[href*='/truyen/'], .item-title a[href*='/truyen/']"
    private const val CHAPTER_LINK_SELECTOR =
        "#chapter-list a[href*='/chuong-'], .chapter-list a[href*='/chuong-']"
    private const val CONTENT_SELECTOR =
        ".reading-content .text-left, .chapter-content .text-left, .chapter-content-inner, " +
            ".chapter-content, #chapter-content, #chapter-c, .chapter-c, [itemprop=articleBody]"

    private val chapterNumberRegex = Regex("(?i)\\bch(?:ương|uong)?\\s*(\\d+)")
    private val whitespaceRegex = Regex("[\\t\\x0B\\f\\r ]+")
    private val blankLinesRegex = Regex("\\n{3,}")

    fun parseStoryList(document: Document): List<StorySummary> {
        ensureNotChallenge(document)
        val stories = document.select(STORY_LINK_SELECTOR)
            .mapNotNull { link ->
                val url = link.safeAbsoluteUrl("href") ?: return@mapNotNull null
                val title = link.text().normalizeText()
                if (title.isBlank()) return@mapNotNull null
                StorySummary(
                    id = storyId(url),
                    sourceId = TruyenCvSource.ID,
                    title = title,
                    author = link.closest("article, .item-summary, .page-item-detail, .row")
                        ?.selectFirst("a[href*='/tac-gia/'], a[href*='/author/'], .author")
                        ?.text()
                        .orEmpty()
                        .normalizeText(),
                    coverUrl = link.closest("article, .item-summary, .page-item-detail, .row")
                        ?.selectFirst("img")
                        ?.safeImageUrl(),
                    url = url,
                )
            }
            .distinctBy(StorySummary::url)

        if (stories.isEmpty() && !looksLikeEmptyResult(document)) {
            throw SourceParseException("Không tìm thấy danh sách truyện TruyenCV theo selector đã biết.")
        }
        return stories
    }

    fun parseStoryDetail(document: Document, storyUrl: String): StoryDetail {
        ensureNotChallenge(document)
        val title = document.selectFirst("h1")?.text().orEmpty().normalizeText()
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện TruyenCV.")

        val author = document.selectFirst("a[href*='/tac-gia/'], a[href*='/author/']")
            ?.text()
            .orEmpty()
            .normalizeText()
            .ifBlank { labeledText(document, "Tác giả") }
        val cover = document.selectFirst(
            "meta[property=og:image], .summary_image img, .book img, .profile-manga img, img.wp-post-image",
        )?.let { element ->
            if (element.tagName() == "meta") element.safeAbsoluteUrl("content") else element.safeImageUrl()
        }
        val descriptionElement = document.selectFirst(
            ".summary__content, .description-summary, .manga-excerpt, .summary-content, " +
                ".tab-summary .summary__content, [itemprop=description]",
        )
        val description = descriptionElement?.text().orEmpty().normalizeMultilineText()
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content").orEmpty().normalizeText() }
        val genres = document.select("a[href*='/the-loai/']")
            .map { it.text().normalizeText() }
            .filter(String::isNotBlank)
            .distinct()
        val bodyText = document.body()?.text().orEmpty()
        val status = when {
            bodyText.contains("Trọn bộ", ignoreCase = true) || bodyText.contains("Hoàn thành", ignoreCase = true) -> "Trọn bộ"
            bodyText.contains("Đang tiến hành", ignoreCase = true) || bodyText.contains("Đang ra", ignoreCase = true) -> "Đang tiến hành"
            else -> ""
        }

        val normalizedUrl = TruyenCvSource.normalizeStoryBase(storyUrl)
        return StoryDetail(
            story = StorySummary(
                id = storyId(normalizedUrl),
                sourceId = TruyenCvSource.ID,
                title = title,
                author = author,
                coverUrl = cover,
                description = description,
                url = normalizedUrl,
            ),
            genres = genres,
            status = status,
        )
    }

    fun findHighestChapterPage(document: Document): Int = document
        .select("a[href*='/chuong/page/']")
        .mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            Regex("/chuong/page/(\\d+)/").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }
        .maxOrNull()
        ?: 1

    fun parseChapterPage(
        document: Document,
        storyId: String,
        startIndex: Int,
        currentUrl: String,
    ): ChapterPage {
        ensureNotChallenge(document)
        val unique = LinkedHashMap<String, Pair<String, String>>()
        document.select(CHAPTER_LINK_SELECTOR).forEach { link ->
            val url = link.safeAbsoluteUrl("href") ?: return@forEach
            val title = link.text().normalizeText()
            if (!title.contains("Chương", ignoreCase = true) && !title.contains("Chuong", ignoreCase = true)) {
                return@forEach
            }
            unique.putIfAbsent(url, title to url)
        }

        val ordered = unique.values.toList().asReversed()
        if (ordered.isEmpty()) {
            throw SourceParseException("Không tìm thấy chương TruyenCV theo selector đã biết.")
        }
        val chapters = ordered.mapIndexed { offset, (title, url) ->
            ChapterSummary(
                id = chapterId(url),
                storyId = storyId,
                index = startIndex + offset,
                title = title,
                url = url,
            )
        }
        return ChapterPage(
            chapters = chapters,
            nextPageUrl = previousChapterIndexPage(currentUrl),
        )
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        ensureNotChallenge(document)
        val title = document.selectFirst("h1, h2, .chapter-title")
            ?.text()
            .orEmpty()
            .normalizeText()
            .ifBlank { document.title().substringBefore("|").normalizeText() }
        if (title.isBlank()) throw SourceParseException("Không đọc được tiêu đề chương TruyenCV.")

        val content = document.selectFirst(CONTENT_SELECTOR)
            ?: throw SourceParseException("Không tìm thấy vùng nội dung chương TruyenCV.")
        content.select(
            "script, style, iframe, noscript, form, nav, .ads, .advertisement, .chapter-nav, " +
                ".navigation, .social-share, .comments-area, #comments, .related, .related-posts, " +
                ".c-selectpicker, .selectpicker",
        ).remove()
        content.select("br").forEach { it.after("\n") }
        content.select("p, div").forEach { block -> block.after("\n") }

        val paragraphs = content.wholeText()
            .replace('\r', '\n')
            .replace(blankLinesRegex, "\n\n")
            .lineSequence()
            .map { it.normalizeText() }
            .filter(String::isNotBlank)
            .filterNot(::isKnownBoilerplate)
            .toList()
        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương TruyenCV rỗng.")

        val parsedNumber = chapterNumberRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val chapter = ChapterSummary(
            id = chapterId(chapterUrl),
            storyId = storyIdFromChapterUrl(chapterUrl),
            index = parsedNumber?.minus(1)?.coerceAtLeast(0) ?: 0,
            title = title,
            url = chapterUrl,
        )
        return ChapterContent(
            chapter = chapter,
            paragraphs = paragraphs,
            previousChapterUrl = document.selectFirst(
                "a.prev_page[href], a[rel=prev][href], .chapter-nav a:first-child[href]",
            )?.safeAbsoluteUrl("href"),
            nextChapterUrl = document.selectFirst(
                "a.next_page[href], a[rel=next][href], .chapter-nav a:last-child[href]",
            )?.safeAbsoluteUrl("href"),
        )
    }

    private fun previousChapterIndexPage(currentUrl: String): String? {
        val page = Regex("/chuong/page/(\\d+)/?$").find(currentUrl)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return null
        if (page <= 1) return null
        return currentUrl.replace(Regex("/chuong/page/\\d+/?$"), "/chuong/page/${page - 1}/")
    }

    private fun labeledText(document: Document, label: String): String {
        val pattern = Regex("(?i)$label\\s*:\\s*([^\\n|]+)")
        return pattern.find(document.body()?.wholeText().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .normalizeText()
    }

    private fun ensureNotChallenge(document: Document) {
        val text = (document.title() + " " + document.body()?.text().orEmpty()).lowercase()
        if (
            "just a moment" in text ||
            "checking your browser" in text ||
            "verify you are human" in text ||
            "cloudflare" in text ||
            "captcha" in text
        ) {
            throw SourceChallengeException("TruyenCV đang yêu cầu xác minh bằng trình duyệt.")
        }
    }

    private fun looksLikeEmptyResult(document: Document): Boolean {
        val text = document.body()?.text().orEmpty().lowercase()
        return "không tìm thấy" in text || "no results" in text || "0 kết quả" in text
    }

    private fun isKnownBoilerplate(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("nguồn:") ||
            normalized.startsWith("truyện được đăng") ||
            normalized.contains("truyencv.io") && normalized.length < 140
    }

    private fun Element.safeImageUrl(): String? = sequenceOf("data-src", "data-lazy-src", "src")
        .mapNotNull { attribute -> safeAbsoluteUrl(attribute) }
        .firstOrNull()

    private fun Element.safeAbsoluteUrl(attribute: String): String? {
        val absolute = absUrl(attribute).ifBlank { attr(attribute) }.trim()
        if (absolute.isBlank() || absolute == "#" || absolute.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        val parsed = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return null
        if (!parsed.isHttps || TruyenCvSource.ALLOWED_HOSTS.none { parsed.host == it || parsed.host.endsWith(".$it") }) {
            return null
        }
        return parsed.toString()
    }

    private fun storyIdFromChapterUrl(url: String): String {
        val parsed = url.toHttpUrl()
        val segments = parsed.pathSegments.filter(String::isNotBlank)
        val chapterPosition = segments.indexOfFirst { it.startsWith("chuong-") }
        val storySegments = if (chapterPosition > 0) segments.take(chapterPosition) else segments.dropLast(1)
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
}
