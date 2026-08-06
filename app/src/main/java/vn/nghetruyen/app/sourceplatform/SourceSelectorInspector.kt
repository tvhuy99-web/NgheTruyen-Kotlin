package vn.nghetruyen.app.sourceplatform

import org.jsoup.Jsoup
import vn.nghetruyen.source.diagnostics.SourceSnapshotSanitizer

data class SourceSelectorInspection(
    val selector: String,
    val matchCount: Int,
    val samples: List<String>,
    val sanitizedSnapshot: String,
)

object SourceSelectorInspector {
    fun inspect(html: String, selector: String, baseUrl: String = "https://example.invalid/"): SourceSelectorInspection {
        require(selector.length in 1..512) { "Selector không hợp lệ." }
        val sanitized = SourceSnapshotSanitizer.sanitizeHtml(html)
        val elements = Jsoup.parse(sanitized, baseUrl).select(selector)
        return SourceSelectorInspection(
            selector = selector,
            matchCount = elements.size,
            samples = elements.take(20).map { element ->
                buildString {
                    append(element.tagName())
                    element.id().takeIf(String::isNotBlank)?.let { append('#').append(it) }
                    element.classNames().take(4).forEach { append('.').append(it) }
                    element.text().takeIf(String::isNotBlank)?.let { append(" • ").append(it.take(160)) }
                }
            },
            sanitizedSnapshot = sanitized,
        )
    }
}
