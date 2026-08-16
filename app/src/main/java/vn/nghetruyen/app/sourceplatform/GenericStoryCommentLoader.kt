package vn.nghetruyen.app.sourceplatform

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponseMode
import vn.nghetruyen.source.api.SourcePlatformResult

 
class GenericStoryCommentLoader(
    private val network: SourceNetworkBroker,
    private val browser: SourceBrowserBroker,
) {
    fun load(manifest: SourceManifest, url: String): StoryCommentPage {
        val networkDocument = when (val response = network.execute(manifest, SourceNetworkRequest(
            sourceId = manifest.id,
            url = url,
            method = "GET",
            responseMode = SourceNetworkResponseMode.TEXT,
            allowHttpError = true,
            timeoutMs = 30_000,
        ))) {
            is SourcePlatformResult.Success -> response.value.takeIf { it.statusCode in 200..399 }
                ?.let { Jsoup.parse(it.bodyText(), it.finalUrl) }
            is SourcePlatformResult.Failure -> null
        }
        networkDocument?.let(::extract)?.takeIf { it.comments.isNotEmpty() }?.let { return it }

        if (manifest.capabilities.browser.navigate && manifest.capabilities.browser.domSnapshot) {
            val navigated = browser.execute(manifest, SourceBrowserRequest(manifest.id, SourceBrowserAction.NAVIGATE, url = url, timeoutMs = 45_000))
            if (navigated is SourcePlatformResult.Success) {
                val snapshot = browser.execute(manifest, SourceBrowserRequest(manifest.id, SourceBrowserAction.DOM_SNAPSHOT, timeoutMs = 15_000))
                if (snapshot is SourcePlatformResult.Success) {
                    val page = extract(Jsoup.parse(snapshot.value.value.orEmpty(), snapshot.value.finalUrl ?: url))
                    if (page.comments.isNotEmpty()) return page
                }
            }
        }
        return networkDocument?.let(::extract) ?: StoryCommentPage(emptyList())
    }

    private fun extract(document: Document): StoryCommentPage {
        val root = first(document, ROOT_SELECTORS) ?: document
        val candidates = linkedSetOf<Element>()
        ITEM_SELECTORS.forEach { selector -> runCatching { candidates += root.select(selector) } }
        val comments = candidates.asSequence().mapNotNull(::comment).distinctBy { "${it.user}|${it.time}|${it.text}" }.take(100).toList()
        val next = first(document, NEXT_SELECTORS)?.absUrl("href")?.takeIf(String::isNotBlank)
        return StoryCommentPage(comments, next)
    }

    private fun comment(element: Element): StoryComment? {
        val bodyNode = first(element, BODY_SELECTORS)
        val rawText = (bodyNode?.text() ?: element.text()).clean()
        if (rawText.isBlank() || rawText.length < 2) return null
        if (rawText.contains("gửi bình luận", true) && rawText.length < 80) return null
        val user = (first(element, USER_SELECTORS)?.text() ?: element.attr("data-author")).clean().ifBlank { "Người đọc" }
        val timeNode = first(element, TIME_SELECTORS)
        val time = (timeNode?.attr("datetime")?.takeIf(String::isNotBlank) ?: timeNode?.text().orEmpty()).clean()
        return StoryComment(user.take(200), time.take(200), rawText.take(20_000))
    }

    private fun first(root: Element, selectors: List<String>): Element? = selectors.firstNotNullOfOrNull { selector ->
        runCatching { root.selectFirst(selector) }.getOrNull()
    }

    private fun String.clean(): String = replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n").trim()

    companion object {
        private val ROOT_SELECTORS = listOf(
            "#comments", ".comments-area", ".comment-area", ".comment-section", ".comment-list",
            "[id*=comment]", "[class*=comment-list]", "[data-comments]",
        )
        private val ITEM_SELECTORS = listOf(
            "li.comment", ".comment-list li", ".comment-item", ".comment", "article.comment",
            "[data-comment-id]", "[class*=comment-item]", ".review-item", ".discussion-item",
        )
        private val USER_SELECTORS = listOf(
            ".comment-author .fn", ".comment-author", ".comment-user", ".author", ".username",
            ".user-name", "[data-author]", ".review-author",
        )
        private val TIME_SELECTORS = listOf("time", ".comment-metadata", ".comment-meta", ".date", ".time", ".created-at")
        private val BODY_SELECTORS = listOf(
            ".comment-content", ".comment-text", ".comment-body", ".content", ".message", ".review-content",
        )
        private val NEXT_SELECTORS = listOf(
            ".comments-pagination a.next", ".comment-pagination a.next", "a.next-comments", "a[rel=next]",
        )
    }
}
