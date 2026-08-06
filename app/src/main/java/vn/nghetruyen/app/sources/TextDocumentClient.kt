package vn.nghetruyen.app.sources

fun interface TextDocumentClient {
    suspend fun getText(
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String>,
    ): String
}
