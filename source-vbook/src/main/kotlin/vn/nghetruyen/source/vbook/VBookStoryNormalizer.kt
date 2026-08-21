package vn.nghetruyen.source.vbook

import org.jsoup.Jsoup
import vn.nghetruyen.source.api.JsonValue
import java.net.URI
import java.security.MessageDigest

data class VBookStoryRecord(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String,
    val url: String,
)

data class VBookStoryDetailRecord(
    val story: VBookStoryRecord,
    val genres: List<String>,
    val status: String,
    val dynamicActions: List<VBookDynamicAction>,
)

data class VBookChapterRecord(
    val id: String,
    val storyId: String,
    val index: Int,
    val title: String,
    val url: String,
)

data class VBookChapterBody(
    val title: String,
    val html: String,
    val paragraphs: List<String>,
    val previousUrl: String?,
    val nextUrl: String?,
)

/** Pure vBook-result normalization. No Android/Compose/Room dependency is allowed here. */
object VBookStoryNormalizer {
    fun stories(data: JsonValue, fallbackHost: String = ""): List<VBookStoryRecord> =
        sequenceOfItems(data).mapNotNull { story(it, fallbackHost) }

    /**
     * Current explore.js returns section objects whose `items` contain story cards. Keep this
     * separate from [stories] so arbitrary nested arrays from detail/chapter payloads are never
     * mistaken for top-level story results.
     */
    fun exploreStories(data: JsonValue, fallbackHost: String = ""): List<VBookStoryRecord> {
        val candidates = when (data) {
            is JsonValue.Arr -> data.values.flatMap { value ->
                val section = value as? JsonValue.Obj
                val nested = section?.array("items")?.values
                if (nested != null && (section.string("type") != null || section.string("id") != null || section.string("title") != null)) nested
                else listOf(value)
            }
            is JsonValue.Obj -> data.array("items")?.values.orEmpty()
            else -> emptyList()
        }
        return candidates.asSequence()
            .take(MAX_EXPLORE_ITEMS)
            .mapNotNull { story(it, fallbackHost) }
            .distinctBy(VBookStoryRecord::url)
            .toList()
    }

    fun story(value: JsonValue, fallbackHost: String = ""): VBookStoryRecord? {
        val obj = value as? JsonValue.Obj ?: return null
        val title = obj.string("name") ?: obj.string("title") ?: return null
        val host = obj.string("host") ?: fallbackHost
        val url = resolveUrl(host, obj.string("link") ?: obj.string("url")) ?: return null
        return VBookStoryRecord(
            id = stableId(url),
            title = title.trim(),
            author = (obj.string("author") ?: "").trim(),
            coverUrl = resolveUrl(host, obj.string("cover") ?: obj.string("coverUrl")),
            description = (obj.string("description") ?: "").trim(),
            url = url,
        )
    }

    fun detail(data: JsonValue, inputUrl: String, fallbackHost: String = ""): VBookStoryDetailRecord? {
        val obj = data as? JsonValue.Obj ?: return null
        val host = obj.string("host") ?: fallbackHost
        val resolvedUrl = resolveUrl(host, obj.string("url") ?: obj.string("link") ?: inputUrl) ?: inputUrl
        val title = obj.string("name") ?: obj.string("title") ?: return null
        val description = (obj.string("description") ?: obj.string("detail") ?: "").trim()
        val genres = buildList {
            listOf("genres", "tags").forEach { key ->
                obj.array(key)?.values.orEmpty().mapNotNullTo(this) { value ->
                    when (value) {
                        is JsonValue.Str -> value.value
                        is JsonValue.Obj -> value.string("title") ?: value.string("name")
                        else -> null
                    }?.trim()?.takeIf(String::isNotBlank)
                }
            }
        }.distinct()
        val status = obj.string("status")?.trim()?.takeIf(String::isNotBlank)
            ?: when (obj.bool("ongoing")) {
                true -> "Đang ra"
                false -> "Hoàn thành"
                null -> ""
            }
        return VBookStoryDetailRecord(
            story = VBookStoryRecord(
                id = stableId(resolvedUrl),
                title = title.trim(),
                author = (obj.string("author") ?: "").trim(),
                coverUrl = resolveUrl(host, obj.string("cover") ?: obj.string("coverUrl")),
                description = description,
                url = resolvedUrl,
            ),
            genres = genres,
            status = status,
            dynamicActions = VBookDynamicActionCollector.collect(data),
        )
    }

    fun chapters(
        data: JsonValue,
        storyUrl: String,
        fallbackHost: String = "",
        startIndex: Int = 0,
    ): List<VBookChapterRecord> = sequenceOfItems(data).mapIndexedNotNull { offset, value ->
        val obj = value as? JsonValue.Obj ?: return@mapIndexedNotNull null
        if (obj.string("type")?.equals("section", ignoreCase = true) == true) return@mapIndexedNotNull null
        val host = obj.string("host") ?: fallbackHost
        val url = resolveUrl(host, obj.string("url") ?: obj.string("link")) ?: return@mapIndexedNotNull null
        val index = startIndex + offset
        VBookChapterRecord(
            id = stableId(url),
            storyId = stableId(storyUrl),
            index = index,
            title = (obj.string("name") ?: obj.string("title") ?: "Chương ${index + 1}").trim(),
            url = url,
        )
    }

    fun chapterBody(data: JsonValue): VBookChapterBody {
        val obj = data as? JsonValue.Obj
        val html = when (data) {
            is JsonValue.Str -> data.value
            is JsonValue.Obj -> data.string("content") ?: data.string("html") ?: data.string("data") ?: ""
            else -> ""
        }
        return VBookChapterBody(
            title = obj?.string("title") ?: obj?.string("name") ?: "",
            html = html,
            paragraphs = htmlToParagraphs(html),
            previousUrl = obj?.string("previousChapterUrl") ?: obj?.string("prev") ?: obj?.string("previous"),
            nextUrl = obj?.string("nextChapterUrl") ?: obj?.string("next"),
        )
    }

    fun dynamicActions(data: JsonValue): List<VBookDynamicAction> = VBookDynamicActionCollector.collect(data)

    private fun sequenceOfItems(data: JsonValue): List<JsonValue> = when (data) {
        is JsonValue.Arr -> data.values
        is JsonValue.Obj -> data.array("items")?.values
            ?: data.array("data")?.values
            ?: data.array("chapters")?.values
            ?: emptyList()
        else -> emptyList()
    }

    private fun htmlToParagraphs(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe,template,[hidden]").remove()
        doc.select("[style]").forEach { element ->
            val style = element.attr("style").lowercase().replace(Regex("""\s+"""), "")
            if ("display:none" in style || "visibility:hidden" in style) element.remove()
        }
        doc.select("br").forEach { it.after("\n") }
        doc.select("p,blockquote,li,div,section,article").forEach { element ->
            element.before("\n")
            element.after("\n")
        }
        val lines = doc.body().wholeText()
            .split(Regex("""[\r\n]+"""))
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter(String::isNotBlank)
        val withoutDuplicateHeader = if (lines.firstOrNull()?.matches(CHAPTER_HEADER_PATTERN) == true) {
            lines.drop(1)
        } else {
            lines
        }
        val cleaned = withoutDuplicateHeader.filterNot(::isChapterBoilerplate)
        if (cleaned.isNotEmpty()) return cleaned
        val fallback = doc.text().replace(Regex("""\s+"""), " ").trim()
        return listOfNotNull(
            fallback.takeIf(String::isNotBlank)
                ?.takeUnless { CHAPTER_HEADER_PATTERN.matches(it) || isChapterBoilerplate(it) },
        )
    }

    private fun isChapterBoilerplate(text: String): Boolean {
        val tokens = text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        return tokens.all { token -> token in TRUYENFULL_BOILERPLATE_TOKENS }
    }

    fun resolveUrl(host: String?, raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val direct = runCatching { URI(value) }.getOrNull()
        if (direct?.isAbsolute == true && !direct.host.isNullOrBlank()) return direct.toASCIIString()
        val base = host?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val baseUri = URI(if (base.endsWith('/')) base else "$base/")
            baseUri.resolve(value).toASCIIString()
        }.getOrNull()
    }

    fun stableId(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val CHAPTER_HEADER_PATTERN = Regex("""(?i)^chương\s+\d+\s*[:：].*""")
    private val TRUYENFULL_BOILERPLATE_TOKENS = setOf(
        "truyen", "full", "truyenfull", "truyenfullvn", "truyenfulllive",
    )
    private const val MAX_EXPLORE_ITEMS = 20_000
}
