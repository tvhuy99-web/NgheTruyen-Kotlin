package vn.nghetruyen.app.sources

import org.jsoup.nodes.Document

fun interface HtmlDocumentClient {
    suspend fun getDocument(
        url: String,
        allowedHosts: Set<String>,
    ): Document
}
