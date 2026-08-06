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

class WikidichSource(
    private val documentClient: HtmlDocumentClient = HttpHtmlClient(),
) : StorySource {
    override val descriptor = SourceDescriptor(
        id = ID,
        displayName = "WikiDich",
        baseUrl = BASE_URL,
        health = SourceHealth.DEGRADED,
        categories = CATEGORY_URLS.keys.toList(),
        allowedHosts = ALLOWED_HOSTS,
    )

    override suspend fun home(page: Int): AppResult<List<StorySummary>> = category("Mới cập nhật", page)

    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> = guarded("SEARCH_FAILED") {
        val trimmed = query.trim()
        if (page <= 1 && isStoryTarget(trimmed)) {
            return@guarded listOf(WikidichParser.parseStoryDetail(documentClient.getDocument(trimmed, ALLOWED_HOSTS), trimmed).story)
        }
        val target = if (trimmed.isBlank()) pagedUrl(BASE_URL, page)
        else "$BASE_URL/tim-kiem".toHttpUrl().newBuilder()
            .addQueryParameter("tu-khoa", trimmed)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build().toString()
        WikidichParser.parseStoryList(documentClient.getDocument(target, ALLOWED_HOSTS))
    }

    override suspend fun category(category: String, page: Int): AppResult<List<StorySummary>> = guarded("CATEGORY_FAILED") {
        val base = CATEGORY_URLS[category] ?: throw SourceParseException("Không nhận ra danh mục WikiDich: $category")
        WikidichParser.parseStoryList(documentClient.getDocument(pagedUrl(base, page), ALLOWED_HOSTS))
    }

    override suspend fun story(url: String): AppResult<StoryDetail> = guarded("STORY_FAILED") {
        val document = documentClient.getDocument(url, ALLOWED_HOSTS)
        val detail = WikidichParser.parseStoryDetail(document, url)
        val page = WikidichParser.parseChapterPage(document, detail.story.id, 0, url)
        detail.copy(chapters = page.chapters, nextChapterPageUrl = page.nextPageUrl)
    }

    override suspend fun latestChapter(url: String): AppResult<ChapterSummary?> = guarded("LATEST_CHAPTER_FAILED") {
        val first = documentClient.getDocument(url, ALLOWED_HOSTS)
        val detail = WikidichParser.parseStoryDetail(first, url)
        val lastPage = WikidichParser.lastChapterPage(first, url)
        val document = if (lastPage == url) first else documentClient.getDocument(lastPage, ALLOWED_HOSTS)
        WikidichParser.parseChapterPage(document, detail.story.id, 0, lastPage).chapters.lastOrNull()
    }

    override suspend fun chapterPage(storyId: String, url: String, startIndex: Int): AppResult<ChapterPage> =
        guarded("CHAPTER_PAGE_FAILED") {
            WikidichParser.parseChapterPage(
                documentClient.getDocument(url, ALLOWED_HOSTS),
                storyId,
                startIndex,
                url,
            )
        }

    override suspend fun chapter(url: String): AppResult<ChapterContent> = guarded("CHAPTER_FAILED") {
        WikidichParser.parseChapterContent(documentClient.getDocument(url, ALLOWED_HOSTS), url)
    }

    private suspend fun <T> guarded(code: String, block: suspend () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (error: ResponseTooLargeException) {
        AppResult.Failure("SOURCE_RESPONSE_TOO_LARGE", error.message ?: "Trang nguồn quá lớn.", error)
    } catch (error: HttpSourceException) {
        AppResult.Failure("SOURCE_HTTP_${error.statusCode}", error.message ?: "Lỗi HTTP.", error)
    } catch (error: IllegalArgumentException) {
        AppResult.Failure("SOURCE_URL_REJECTED", error.message ?: "Địa chỉ WikiDich không hợp lệ.", error)
    } catch (error: SourceChallengeException) {
        AppResult.Failure("SOURCE_BROWSER_VERIFICATION_REQUIRED", error.message ?: "Nguồn yêu cầu xác minh.", error)
    } catch (error: SourceParseException) {
        AppResult.Failure("SOURCE_LAYOUT_CHANGED", error.message ?: "Cấu trúc WikiDich đã thay đổi.", error)
    } catch (error: Exception) {
        AppResult.Failure(code, error.message ?: "Không thể tải WikiDich.", error)
    }

    companion object {
        const val ID = "wikidich"
        const val BASE_URL = "https://wikidichvn.com"
        val ALLOWED_HOSTS = setOf("wikidichvn.com", "www.wikidichvn.com", "wikidich.vn", "www.wikidich.vn")
        val CATEGORY_URLS = linkedMapOf(
            "Mới cập nhật" to BASE_URL,
            "Truyện Full" to "$BASE_URL/danh-sach/truyen-full",
            "Truyện Hot" to "$BASE_URL/danh-sach/truyen-hot",
            "Ngôn Tình Hay" to "$BASE_URL/danh-sach/truyen-ngon-tinh-hay",
            "Đam Mỹ Hay" to "$BASE_URL/danh-sach/truyen-dam-my-hay",
            "Tiên Hiệp" to "$BASE_URL/the-loai/tien-hiep",
            "Kiếm Hiệp" to "$BASE_URL/the-loai/kiem-hiep",
            "Ngôn Tình" to "$BASE_URL/the-loai/ngon-tinh",
            "Đam Mỹ" to "$BASE_URL/the-loai/dam-my",
            "Bách Hợp" to "$BASE_URL/the-loai/bach-hop",
            "Huyền Huyễn" to "$BASE_URL/the-loai/huyen-huyen",
            "Đô Thị" to "$BASE_URL/the-loai/do-thi",
            "Xuyên Không" to "$BASE_URL/the-loai/xuyen-khong",
            "Trọng Sinh" to "$BASE_URL/the-loai/trong-sinh",
            "Linh Dị" to "$BASE_URL/the-loai/linh-di",
            "Light Novel" to "$BASE_URL/the-loai/light-novel",
        )

        internal fun pagedUrl(baseUrl: String, page: Int): String {
            if (page <= 1) return baseUrl
            return baseUrl.toHttpUrl().newBuilder().setQueryParameter("page", page.toString()).build().toString()
        }

        internal fun isStoryTarget(value: String): Boolean {
            val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return false
            if (!url.isHttps || ALLOWED_HOSTS.none { url.host == it || url.host.endsWith(".$it") }) return false
            val path = url.encodedPath.trim('/')
            if (path.isBlank() || '/' in path) return false
            return !path.startsWith("danh-sach") && !path.startsWith("the-loai") && !path.startsWith("tac-gia") &&
                !path.startsWith("tim-kiem") && !path.startsWith("chuong-")
        }
    }
}

internal object WikidichParser {
    private val whitespace = Regex("[\\t\\x0B\\f\\r ]+")
    private val blankLines = Regex("\\n{3,}")
    private val chapterNumber = Regex("(?i)(?:quyển\\s*\\d+\\s*[-–:]\\s*)?ch(?:ương|uong)\\s*(\\d+)")
    private val forbiddenStoryPrefixes = listOf("danh-sach/", "the-loai/", "tac-gia/", "chuong-", "tim-kiem")

    fun parseStoryList(document: Document): List<StorySummary> {
        ensureNotChallenge(document)
        val result = LinkedHashMap<String, StorySummary>()
        document.select("h3 a[href], .list-truyen h3 a[href], .story-title a[href], article h2 a[href]")
            .forEach { link ->
                val url = link.safeUrl("href") ?: return@forEach
                if (!isStoryUrl(url)) return@forEach
                val title = link.text().normalized()
                if (title.isBlank()) return@forEach
                val container = link.closest("article, li, .row, .item, .book-item, .story-item") ?: link.parent()
                val author = container?.selectFirst("a[href*='/tac-gia/'], .author")?.text().orEmpty().normalized()
                val cover = container?.selectFirst("img")?.let { image ->
                    image.safeUrl("data-src") ?: image.safeUrl("src")
                }
                result.putIfAbsent(
                    canonical(url),
                    StorySummary(
                        id = storyId(url),
                        sourceId = WikidichSource.ID,
                        title = title,
                        author = author,
                        coverUrl = cover,
                        url = canonical(url),
                    ),
                )
            }
        if (result.isEmpty() && !looksEmpty(document)) throw SourceParseException("Không tìm thấy truyện WikiDich.")
        return result.values.toList()
    }

    fun parseStoryDetail(document: Document, storyUrl: String): StoryDetail {
        ensureNotChallenge(document)
        val title = document.selectFirst("h1, .title, .book-title")?.text().orEmpty().normalized()
        if (title.isBlank()) throw SourceParseException("Không đọc được tên truyện WikiDich.")
        val body = document.body().text().normalized()
        val author = document.selectFirst("a[href*='/tac-gia/'], .author a, .author")?.text().orEmpty().normalized()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: document.selectFirst(".book img, .story-cover img, img[itemprop=image]")?.safeUrl("src")
        val description = document.selectFirst(".desc-text, .description, .summary, [itemprop=description]")
            ?.text().orEmpty().normalizedMultiline()
            .ifBlank { document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content").orEmpty().normalized() }
        val genres = document.select("a[href*='/the-loai/']")
            .map { it.text().normalized() }.filter(String::isNotBlank).distinct()
        val status = Regex("(?i)Trạng thái\\s*:\\s*([^\\n]+?)(?=\\s+(?:Đánh giá|Bạn đang đọc|#|Giới thiệu|Số chương)|$)")
            .find(body)?.groupValues?.get(1)?.normalized().orEmpty()
            .ifBlank { if (body.contains("Đang cập nhật", true)) "Đang cập nhật" else if (body.contains("Full", true)) "Full" else "" }
        return StoryDetail(
            story = StorySummary(
                id = storyId(storyUrl),
                sourceId = WikidichSource.ID,
                title = title,
                author = author,
                coverUrl = cover,
                description = description,
                url = canonical(storyUrl),
            ),
            genres = genres,
            status = status,
        )
    }

    fun parseChapterPage(document: Document, storyId: String, startIndex: Int, currentUrl: String): ChapterPage {
        ensureNotChallenge(document)
        val scoped = document.select("#list-chapter a[href*='/chuong-'], .list-chapter a[href*='/chuong-'], [class*=chapter-list] a[href*='/chuong-']")
        val links = if (scoped.isNotEmpty()) scoped else document.select("a[href*='/chuong-']")
        val unique = LinkedHashMap<String, Pair<String, Int?>>()
        links.forEach { link ->
            val url = link.safeUrl("href") ?: return@forEach
            val title = link.text().normalized()
            if (title.isBlank()) return@forEach
            val number = chapterNumber.find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("/chuong-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            unique.putIfAbsent(canonical(url), title to number)
        }
        val ordered = unique.entries.sortedWith(compareBy({ it.value.second ?: Int.MAX_VALUE }, { it.key }))
        if (ordered.isEmpty()) throw SourceParseException("Không tìm thấy mục lục WikiDich.")
        val chapters = ordered.mapIndexed { offset, entry ->
            ChapterSummary(
                id = chapterId(entry.key),
                storyId = storyId,
                index = startIndex + offset,
                title = entry.value.first,
                url = entry.key,
            )
        }
        val currentPage = currentUrl.toHttpUrl().queryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val total = Regex("(?i)Số chương\\s*:\\s*([0-9.]+)").find(document.body().text())
            ?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()
        val maxPage = document.select("a[href*='page=']").mapNotNull { link ->
            runCatching { link.absUrl("href").toHttpUrl().queryParameter("page")?.toIntOrNull() }.getOrNull()
        }.maxOrNull()
        val hasNext = when {
            total != null -> currentPage * PAGE_SIZE < total
            maxPage != null -> currentPage < maxPage
            else -> chapters.size >= PAGE_SIZE
        }
        return ChapterPage(chapters, if (hasNext) WikidichSource.pagedUrl(canonicalStoryUrl(currentUrl), currentPage + 1) else null)
    }

    fun lastChapterPage(document: Document, storyUrl: String): String {
        val total = Regex("(?i)Số chương\\s*:\\s*([0-9.]+)").find(document.body().text())
            ?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()
        val maxFromLinks = document.select("a[href*='page=']").mapNotNull { link ->
            runCatching { link.absUrl("href").toHttpUrl().queryParameter("page")?.toIntOrNull() }.getOrNull()
        }.maxOrNull()
        val page = maxOf(maxFromLinks ?: 1, total?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE } ?: 1)
        return if (page <= 1) canonicalStoryUrl(storyUrl) else WikidichSource.pagedUrl(canonicalStoryUrl(storyUrl), page)
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        ensureNotChallenge(document)
        val title = document.selectFirst("h1, h2, .chapter-title, [itemprop=headline]")?.text().orEmpty().normalized()
            .ifBlank { document.title().substringBefore("|").normalized() }
        if (title.isBlank()) throw SourceParseException("Không đọc được tên chương WikiDich.")
        val contentNode = document.selectFirst("#chapter-c, #chapter-content, .chapter-content, .content-chapter, [itemprop=articleBody]")
        val paragraphs = if (contentNode != null) {
            contentNode.select("script, style, iframe, form, nav, .chapter-nav, .ads, .advertisement").remove()
            contentNode.select("br").forEach { it.after("\n") }
            contentNode.select("p, div").forEach { it.after("\n") }
            contentNode.wholeText().toParagraphs()
        } else {
            val raw = document.body().wholeText()
            Regex("(?is)Chương tiếp\\s*》\\s*(.*?)\\s*《\\s*Chương trước")
                .find(raw)?.groupValues?.get(1).orEmpty().toParagraphs()
        }
        if (paragraphs.isEmpty()) throw SourceParseException("Nội dung chương WikiDich rỗng.")
        val previous = navigationUrl(document, listOf("a[rel=prev]", "a:matchesOwn((?i)Chương trước)", ".prev a"))
        val next = navigationUrl(document, listOf("a[rel=next]", "a:matchesOwn((?i)Chương tiếp)", ".next a"))
        val number = chapterNumber.find(title)?.groupValues?.get(1)?.toIntOrNull()
        return ChapterContent(
            chapter = ChapterSummary(
                id = chapterId(chapterUrl),
                storyId = storyIdFromChapter(chapterUrl),
                index = number?.minus(1)?.coerceAtLeast(0) ?: 0,
                title = title,
                url = canonical(chapterUrl),
            ),
            paragraphs = paragraphs,
            previousChapterUrl = previous,
            nextChapterUrl = next,
        )
    }

    private fun navigationUrl(document: Document, selectors: List<String>): String? = selectors.asSequence()
        .mapNotNull { selector -> document.selectFirst(selector)?.safeUrl("href") }
        .firstOrNull { it.contains("/chuong-", true) }
        ?.let(::canonical)

    private fun ensureNotChallenge(document: Document) {
        val text = (document.title() + " " + document.body().text()).lowercase()
        if (text.contains("checking your browser") || text.contains("verify you are human") || text.contains("cloudflare")) {
            throw SourceChallengeException("WikiDich yêu cầu xác minh trình duyệt.")
        }
    }

    private fun looksEmpty(document: Document): Boolean {
        val text = document.body().text().lowercase()
        return text.contains("không tìm thấy") || text.contains("chưa có truyện") || text.contains("0 kết quả")
    }

    private fun isStoryUrl(url: String): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        val path = parsed.encodedPath.trim('/')
        if (path.isBlank() || '/' in path) return false
        return forbiddenStoryPrefixes.none { path.startsWith(it, true) }
    }

    private fun canonicalStoryUrl(url: String): String {
        val parsed = url.toHttpUrl()
        return parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    private fun storyIdFromChapter(url: String): String {
        val parsed = url.toHttpUrl()
        val slug = parsed.pathSegments.firstOrNull().orEmpty()
        return stableId("story", "${parsed.host}/$slug")
    }

    private fun storyId(url: String): String = stableId("story", canonical(url))
    private fun chapterId(url: String): String = stableId("chapter", canonical(url))
    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return prefix + ":" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun canonical(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path.trimEnd('/'), uri.query, null).toString()
    }.getOrDefault(url.trimEnd('/'))

    private fun Element.safeUrl(attribute: String): String? = absUrl(attribute).ifBlank { attr(attribute) }
        .takeIf(String::isNotBlank)
        ?.let { runCatching { canonical(it) }.getOrNull() }

    private fun String.normalized(): String = trim().replace(whitespace, " ")
    private fun String.normalizedMultiline(): String = replace('\r', '\n').replace(blankLines, "\n\n")
        .lineSequence().map { it.normalized() }.filter(String::isNotBlank).joinToString("\n\n")
    private fun String.toParagraphs(): List<String> = replace('\r', '\n').replace(blankLines, "\n\n")
        .lineSequence().map { it.normalized() }.filter(String::isNotBlank)
        .filterNot { line -> line.contains("Chương tiếp", true) || line.contains("Chương trước", true) }
        .toList()

    private const val PAGE_SIZE = 100
}
