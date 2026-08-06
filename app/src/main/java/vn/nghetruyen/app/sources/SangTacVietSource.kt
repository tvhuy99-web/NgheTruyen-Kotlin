package vn.nghetruyen.app.sources

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
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

class SangTacVietSource(
    private val sessionStore: SourceSessionStore = InMemorySourceSessionStore(),
    private val networkClient: SessionHttpClient = SessionHttpClient(sessionStore),
) : StorySource {
    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "Sáng Tác Việt",
        baseUrl = BASE_URL,
        health = SourceHealth.DEGRADED,
        categories = CATEGORY_URLS.keys.toList(),
        loginUrl = BASE_URL,
        privacyNote = "Phiên đăng nhập được mã hóa cục bộ; mật khẩu chỉ nhập trực tiếp trên trang Sáng Tác Việt.",
        allowedHosts = ALLOWED_HOSTS,
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = category("Mới cập nhật", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (page <= 1 && isStoryTarget(trimmed)) {
            return@guarded listOf(loadStoryDetail(trimmed).story)
        }
        val target = "$BASE_URL/search/".toHttpUrl().newBuilder()
            .addQueryParameter("find", "")
            .addQueryParameter("findinname", trimmed)
            .addQueryParameter("minc", "0")
            .addQueryParameter("tag", "")
            .apply { if (page > 1) addQueryParameter("p", page.toString()) }
            .build().toString()
        SangTacVietParser.parseStoryList(networkClient.getDocument(ID, target, ALLOWED_HOSTS))
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val base = CATEGORY_URLS[category] ?: throw SourceParseException("Không nhận ra danh mục Sáng Tác Việt: $category")
        val target = base.toHttpUrl().newBuilder().apply { if (page > 1) setQueryParameter("p", page.toString()) }.build().toString()
        SangTacVietParser.parseStoryList(networkClient.getDocument(ID, target, ALLOWED_HOSTS))
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        val detail = loadStoryDetail(url)
        val page = loadChapterPage(url, detail.story.id, 0)
        detail.copy(chapters = page.chapters, nextChapterPageUrl = page.nextPageUrl)
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val route = SangTacVietRoute.parseStory(url)
        val all = SangTacVietParser.parseChapterApi(
            networkClient.getText(
                ID,
                route.chapterApiUrl(),
                ALLOWED_HOSTS,
                apiHeaders(route.storyUrl),
            ),
            route,
        )
        all.lastOrNull()
    }

    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int): AppResult<ChapterPage> =
        guarded("CHAPTER_PAGE_FAILED") { loadChapterPage(url, storyId, startIndex) }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        val route = SangTacVietRoute.parseChapter(url)
        val raw = networkClient.postEmpty(
            ID,
            route.contentApiUrl(),
            ALLOWED_HOSTS,
            apiHeaders(route.chapterUrl),
        )
        SangTacVietParser.parseChapterApiResponse(raw, route)
    }

    private suspend fun loadStoryDetail(url: String): StoryDetail {
        val route = SangTacVietRoute.parseStory(url)
        return SangTacVietParser.parseStoryDetail(
            networkClient.getDocument(ID, route.storyUrl, ALLOWED_HOSTS),
            route.storyUrl,
        )
    }

    private suspend fun loadChapterPage(url: String, storyId: String, startIndex: Int): ChapterPage {
        val pageNumber = runCatching { url.toHttpUrl().queryParameter(PAGE_PARAMETER)?.toIntOrNull() }.getOrNull()
            ?.coerceAtLeast(1) ?: (startIndex / CHAPTER_PAGE_SIZE + 1)
        val route = SangTacVietRoute.parseStory(url)
        val all = SangTacVietParser.parseChapterApi(
            networkClient.getText(ID, route.chapterApiUrl(), ALLOWED_HOSTS, apiHeaders(route.storyUrl)),
            route,
        )
        val start = (pageNumber - 1) * CHAPTER_PAGE_SIZE
        val selected = all.drop(start).take(CHAPTER_PAGE_SIZE).mapIndexed { offset, chapter ->
            chapter.copy(storyId = storyId, index = startIndex + offset)
        }
        if (selected.isEmpty() && all.isNotEmpty()) throw SourceParseException("Trang mục lục vượt quá số chương hiện có.")
        val next = if (start + selected.size < all.size) route.storyUrl.toHttpUrl().newBuilder()
            .setQueryParameter(PAGE_PARAMETER, (pageNumber + 1).toString()).build().toString() else null
        return ChapterPage(selected, next)
    }

    private fun apiHeaders(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "Origin" to referer.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'),
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json,text/plain,*/*",
    )

    private suspend fun <T> guarded(code: String, block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (error: SourceLoginRequiredException) {
        AppResult.Failure("SOURCE_LOGIN_REQUIRED", error.message ?: "Nguồn yêu cầu đăng nhập.", error)
    } catch (error: ResponseTooLargeException) {
        AppResult.Failure("SOURCE_RESPONSE_TOO_LARGE", error.message ?: "Phản hồi quá lớn.", error)
    } catch (error: HttpSourceException) {
        AppResult.Failure("SOURCE_HTTP_${error.statusCode}", error.message ?: "Lỗi HTTP.", error)
    } catch (error: IllegalArgumentException) {
        AppResult.Failure("SOURCE_URL_REJECTED", error.message ?: "URL Sáng Tác Việt không hợp lệ.", error)
    } catch (error: SourceChallengeException) {
        AppResult.Failure("SOURCE_BROWSER_VERIFICATION_REQUIRED", error.message ?: "Nguồn yêu cầu xác minh.", error)
    } catch (error: SourceParseException) {
        AppResult.Failure("SOURCE_LAYOUT_CHANGED", error.message ?: "Cấu trúc Sáng Tác Việt đã thay đổi.", error)
    } catch (error: Exception) {
        AppResult.Failure(code, error.message ?: "Không thể tải Sáng Tác Việt.", error)
    }

    companion object {
        const val ID = "sangtacviet"
        const val BASE_URL = "https://sangtacviet.vip"
        const val PAGE_PARAMETER = "__nghe_toc_page"
        const val CHAPTER_PAGE_SIZE = 100
        val ALLOWED_HOSTS = setOf(
            "sangtacviet.vip", "www.sangtacviet.vip",
            "sangtacviet.com", "www.sangtacviet.com",
            "sangtacviet.app", "www.sangtacviet.app",
            "sangtacviet.xyz", "www.sangtacviet.xyz",
        )
        val CATEGORY_URLS = linkedMapOf(
            "Mới cập nhật" to "$BASE_URL/search/?find=&minc=0&sort=update&tag=",
            "Xem nhiều tuần" to "$BASE_URL/search/?find=&minc=0&tag=&__vbook_sort=Lượt xem tuần",
            "Hoàn thành" to "$BASE_URL/search/?find=&minc=0&step=3&tag=",
            "Huyền huyễn" to "$BASE_URL/search/?find=&minc=0&category=hh&tag=",
            "Đô thị" to "$BASE_URL/search/?find=&minc=0&category=dt&tag=",
            "Ngôn tình" to "$BASE_URL/search/?find=&minc=0&category=nt&tag=",
            "Đồng nhân" to "$BASE_URL/search/?find=&minc=0&category=dn&tag=",
            "Khoa học viễn tưởng" to "$BASE_URL/search/?find=&minc=0&category=khvt&tag=",
        )

        internal fun isStoryTarget(value: String): Boolean = runCatching { SangTacVietRoute.parseStory(value) }.isSuccess
    }
}

internal data class SangTacVietRoute(
    val origin: String,
    val sourceKey: String,
    val style: String,
    val bookId: String,
    val chapterId: String? = null,
) {
    val storyUrl: String get() = "$origin/truyen/$sourceKey/$style/$bookId/"
    val chapterUrl: String get() = chapterId?.let { "$origin/truyen/$sourceKey/$style/$bookId/$it/" } ?: storyUrl

    fun chapterApiUrl(): String = "$origin/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("ngmar", "chapterlist")
        .addQueryParameter("h", sourceKey)
        .addQueryParameter("bookid", bookId)
        .addQueryParameter("sajax", "getchapterlist")
        .build().toString()

    fun contentApiUrl(): String {
        val chapter = requireNotNull(chapterId) { "URL không chứa mã chương." }
        return "$origin/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("bookid", bookId)
            .addQueryParameter("h", sourceKey)
            .addQueryParameter("c", chapter)
            .addQueryParameter("ngmar", "readc")
            .addQueryParameter("sajax", "readchapter")
            .addQueryParameter("sty", style.ifBlank { "1" })
            .addQueryParameter("exts", "")
            .build().toString()
    }

    fun chapterUrl(id: String): String = "$origin/truyen/$sourceKey/$style/$bookId/$id/"

    companion object {
        fun parseStory(value: String): SangTacVietRoute {
            val url = value.toHttpUrl()
            require(url.isHttps && SangTacVietSource.ALLOWED_HOSTS.any { url.host == it || url.host.endsWith(".$it") }) {
                "URL không thuộc Sáng Tác Việt."
            }
            val segments = url.pathSegments.filter(String::isNotBlank)
            require(segments.size >= 4 && segments[0] == "truyen") { "URL không phải trang truyện Sáng Tác Việt." }
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return SangTacVietRoute(origin, segments[1], segments[2], segments[3])
        }

        fun parseChapter(value: String): SangTacVietRoute {
            val story = parseStory(value)
            val segments = value.toHttpUrl().pathSegments.filter(String::isNotBlank)
            require(segments.size >= 5) { "URL không phải chương Sáng Tác Việt." }
            return story.copy(chapterId = segments[4])
        }
    }
}

internal object SangTacVietParser {
    private val whitespace = Regex("[\\t\\x0B\\f\\r ]+")
    private val blankLines = Regex("\\n{3,}")

    fun parseStoryList(document: Document): List<StorySummary> {
        ensureNotChallenge(document)
        val values = LinkedHashMap<String, StorySummary>()
        document.select("a[href*='/truyen/']").forEach { link ->
            val url = link.safeUrl("href") ?: return@forEach
            val route = runCatching { SangTacVietRoute.parseStory(url) }.getOrNull() ?: return@forEach
            val pathSegments = url.toHttpUrl().pathSegments.filter(String::isNotBlank)
            if (pathSegments.size != 4) return@forEach
            val box = link.closest("article, li, .book, .novel, .item, .row, .card, [class*=book], [class*=novel]") ?: link.parent()
            val title = listOfNotNull(
                link.attr("title").normalized().takeIf(String::isNotBlank),
                link.text().normalized().takeIf(String::isNotBlank),
                box?.selectFirst("h1,h2,h3,h4,[class*=title]")?.text()?.normalized()?.takeIf(String::isNotBlank),
            ).firstOrNull() ?: return@forEach
            val cover = box?.selectFirst("img")?.let { image -> image.safeUrl("data-src") ?: image.safeUrl("src") }
            val canonical = route.storyUrl
            values.putIfAbsent(
                canonical,
                StorySummary(
                    id = stableId("story", canonical),
                    sourceId = SangTacVietSource.ID,
                    title = title,
                    coverUrl = cover,
                    url = canonical,
                ),
            )
        }
        if (values.isEmpty() && !looksEmpty(document)) {
            throw SourceParseException("Danh sách Sáng Tác Việt cần JavaScript hoặc cấu trúc trang đã thay đổi.")
        }
        return values.values.toList()
    }

    fun parseStoryDetail(document: Document, storyUrl: String): StoryDetail {
        ensureNotChallenge(document)
        val route = SangTacVietRoute.parseStory(storyUrl)
        val title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().normalized()
            .ifBlank { document.selectFirst("h1, .book-title, [class*=title]")?.text().orEmpty().normalized() }
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện Sáng Tác Việt.")
        val author = document.selectFirst("meta[property='og:novel:author']")?.attr("content").orEmpty().normalized()
            .ifBlank { document.selectFirst("a[href*=author], .author")?.text().orEmpty().normalized() }
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: document.selectFirst("img.bookcover, .book-cover img")?.safeUrl("src")
        val description = document.selectFirst("#booksummary, #bookdesc, #description, [id*=summary], [class*=book-summary], [class*=book-description]")
            ?.text().orEmpty().normalizedMultiline()
            .ifBlank { document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty().normalized() }
        val genres = document.selectFirst("meta[property='og:novel:category']")?.attr("content")
            ?.split(',', '/', '|')?.map { it.normalized() }?.filter(String::isNotBlank).orEmpty()
            .ifEmpty { document.select("a[href*=category], a[href*=tag]").map { it.text().normalized() }.filter(String::isNotBlank).distinct() }
        val status = document.selectFirst("meta[property='og:novel:status']")?.attr("content").orEmpty().normalized()
        return StoryDetail(
            story = StorySummary(
                id = stableId("story", route.storyUrl),
                sourceId = SangTacVietSource.ID,
                title = title,
                author = author,
                coverUrl = cover,
                description = description,
                url = route.storyUrl,
            ),
            genres = genres,
            status = status,
        )
    }

    fun parseChapterApi(raw: String, route: SangTacVietRoute): List<ChapterSummary> {
        val json = MiniJsonObject.parse(extractJson(raw))
        val code = json.int("code") ?: 0
        if (code != 0) throw apiFailure(code, json.string("err") ?: json.string("info"))
        val data = json.string("data").orEmpty()
        if (data.isBlank()) throw SourceParseException("API mục lục Sáng Tác Việt trả dữ liệu rỗng.")
        val result = LinkedHashMap<String, ChapterSummary>()
        data.split("-//-").forEach { record ->
            val parts = record.split("-/-")
            if (parts.size < 3) return@forEach
            val chapterId = parts[1].trim()
            val title = parts[2].trim().normalized()
            if (chapterId.isBlank() || title.isBlank()) return@forEach
            val url = route.chapterUrl(chapterId)
            result.putIfAbsent(
                url,
                ChapterSummary(
                    id = stableId("chapter", url),
                    storyId = stableId("story", route.storyUrl),
                    index = result.size,
                    title = title,
                    url = url,
                ),
            )
        }
        if (result.isEmpty()) throw SourceParseException("Không phân tích được mục lục Sáng Tác Việt.")
        return result.values.toList()
    }

    fun parseChapterApiResponse(raw: String, route: SangTacVietRoute): ChapterContent {
        val json = MiniJsonObject.parse(extractJson(raw))
        val code = json.int("code") ?: -1
        val error = json.string("err") ?: json.string("info")
        if (code != 0) throw apiFailure(code, error)
        val contentHtml = json.string("data").orEmpty()
        if (contentHtml.isBlank()) throw SourceLoginRequiredException(
            "Chương chưa được mở. Hãy đăng nhập hoặc mở phiên Sáng Tác Việt rồi thử lại.",
        )
        val title = json.string("chaptername").orEmpty().normalized().ifBlank { "Chương truyện" }
        val fragment = Jsoup.parseBodyFragment(contentHtml, route.chapterUrl)
        fragment.select("script, style, iframe, form, nav, .ads, .advertisement").remove()
        fragment.select("br").forEach { it.after("\n") }
        fragment.select("p, div").forEach { it.after("\n") }
        val paragraphs = fragment.body().wholeText().replace('\r', '\n').replace(blankLines, "\n\n")
            .lineSequence().map { it.normalized() }.filter(String::isNotBlank)
            .filterNot { it.contains("nhấp vào để tải chương", true) || it.contains("click to load", true) }
            .toList()
        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương Sáng Tác Việt rỗng sau khi làm sạch.")
        val previous = json.string("prev")?.takeUnless { it.isBlank() || it == "0" || it == "nil" }?.let(route::chapterUrl)
        val next = json.string("next")?.takeUnless { it.isBlank() || it == "0" || it == "nil" }?.let(route::chapterUrl)
        val currentUrl = route.chapterUrl
        return ChapterContent(
            chapter = ChapterSummary(
                id = stableId("chapter", currentUrl),
                storyId = stableId("story", route.storyUrl),
                index = chapterIndex(title),
                title = title,
                url = currentUrl,
            ),
            paragraphs = paragraphs,
            previousChapterUrl = previous,
            nextChapterUrl = next,
        )
    }

    private fun apiFailure(code: Int, message: String?): Exception {
        val detail = message.orEmpty().normalized()
        return if (code == 4005 || detail.contains("login", true) || detail.contains("đăng nhập", true) ||
            detail.contains("cookie", true) || detail.contains("phiên", true)
        ) SourceLoginRequiredException("Sáng Tác Việt yêu cầu phiên đăng nhập hợp lệ${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}.")
        else SourceParseException("API Sáng Tác Việt trả code $code${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}.")
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw SourceParseException("Phản hồi Sáng Tác Việt không phải JSON.")
        return raw.substring(start, end + 1)
    }

    private fun ensureNotChallenge(document: Document) {
        val text = (document.title() + " " + document.body().text()).lowercase()
        if (text.contains("checking your browser") || text.contains("verify you are human") || text.contains("cloudflare")) {
            throw SourceChallengeException("Sáng Tác Việt yêu cầu xác minh trình duyệt.")
        }
    }

    private fun looksEmpty(document: Document): Boolean {
        val text = document.body().text().lowercase()
        return text.contains("không tìm thấy") || text.contains("no result") || text.contains("0 truyện")
    }

    private fun chapterIndex(title: String): Int = Regex("(?i)(?:chương|chapter)\\s*(\\d+)")
        .find(title)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return prefix + ":" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun Element.safeUrl(attribute: String): String? = absUrl(attribute).ifBlank { attr(attribute) }
        .takeIf(String::isNotBlank)?.let { runCatching { canonical(it) }.getOrNull() }
    private fun canonical(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path, uri.query, null).toString()
    }.getOrDefault(url)
    private fun String.normalized(): String = trim().replace(whitespace, " ")
    private fun String.normalizedMultiline(): String = replace('\r', '\n').replace(blankLines, "\n\n")
        .lineSequence().map { it.normalized() }.filter(String::isNotBlank).joinToString("\n\n")
}
