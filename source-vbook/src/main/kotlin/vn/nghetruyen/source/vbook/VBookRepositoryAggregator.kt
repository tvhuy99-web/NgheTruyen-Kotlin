package vn.nghetruyen.source.vbook

import java.net.URI
import java.security.MessageDigest

data class VBookRepositoryFetchResult(
    val url: String,
    val body: String,
    val sha256: String,
)

fun interface VBookRepositoryFetcher {
    fun fetch(url: String, maxBytes: Int): VBookRepositoryFetchResult
}

data class VBookRepositoryError(
    val url: String,
    val code: String,
    val message: String,
)

data class VBookAggregatedItem(
    val repositoryId: String,
    val repository: VBookRepositoryDescriptor,
    val item: VBookCatalogItem,
) {
    val remoteIdentity: String = item.stableRemoteIdentity(repository.link)
    val installIdentity: String = "${repositoryId}:${remoteIdentity}"
}

data class VBookRepositorySnapshot(
    val indexUrl: String,
    val indexSha256: String,
    val repositories: List<VBookRepositoryCatalog>,
    val items: List<VBookAggregatedItem>,
    val errors: List<VBookRepositoryError>,
) {
    val complete: Boolean get() = errors.isEmpty()
    val summary: VBookRepositoryCorpusSummary get() = VBookRepositoryCorpus.summarize(repositories)

    fun find(repositoryId: String, remoteIdentity: String): VBookAggregatedItem? =
        items.firstOrNull { it.repositoryId == repositoryId && it.remoteIdentity == remoteIdentity }
}








class VBookRepositoryAggregator(
    private val fetcher: VBookRepositoryFetcher,
) {
    fun fetchIndex(
        indexUrl: String,
        strict: Boolean = false,
    ): VBookRepositorySnapshot {
        val canonicalIndex = canonicalUrl(indexUrl)
        val index = fetcher.fetch(canonicalIndex, MAX_INDEX_BYTES)
        val descriptors = VBookRepositoryIndexParser.parse(index.body)
        require(descriptors.size <= MAX_REPOSITORIES) { "VBOOK_REPOSITORY_INDEX_TOO_LARGE" }

        val repositories = mutableListOf<VBookRepositoryCatalog>()
        val errors = mutableListOf<VBookRepositoryError>()
        val items = linkedMapOf<String, VBookAggregatedItem>()

        descriptors.forEach { descriptor ->
            val catalogUrl = canonicalUrl(descriptor.link)
            val repositoryId = repositoryId(catalogUrl)
            val catalog = runCatching {
                val fetched = fetcher.fetch(catalogUrl, MAX_CATALOG_BYTES)
                VBookCatalogParser.parse(fetched.body)
            }.getOrElse { error ->
                errors += VBookRepositoryError(
                    url = catalogUrl,
                    code = errorCode(error),
                    message = (error.message ?: error.javaClass.simpleName).take(500),
                )
                return@forEach
            }
            val normalizedDescriptor = descriptor.copy(link = catalogUrl)
            val row = VBookRepositoryCatalog(normalizedDescriptor, catalog)
            repositories += row
            catalog.items.forEach { item ->
                val aggregated = VBookAggregatedItem(repositoryId, normalizedDescriptor, item)

                val previous = items.putIfAbsent(aggregated.installIdentity, aggregated)
                if (previous != null && previous.item.packageUrl != item.packageUrl) {
                    errors += VBookRepositoryError(
                        url = catalogUrl,
                        code = "VBOOK_REPOSITORY_IDENTITY_COLLISION",
                        message = aggregated.installIdentity,
                    )
                }
            }
        }

        if (strict && errors.isNotEmpty()) {
            error("VBOOK_REPOSITORY_SNAPSHOT_INCOMPLETE:${errors.joinToString { it.code }}")
        }
        return VBookRepositorySnapshot(
            indexUrl = canonicalIndex,
            indexSha256 = index.sha256,
            repositories = repositories,
            items = items.values.sortedWith(compareBy({ it.repositoryId }, { it.item.name.lowercase() }, { it.remoteIdentity })),
            errors = errors,
        )
    }

    companion object {
        const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        const val MAX_CATALOG_BYTES = 16 * 1024 * 1024
        const val MAX_REPOSITORIES = 256

        fun repositoryId(catalogUrl: String): String = "vbook-repo-" + sha256(canonicalUrl(catalogUrl)).take(24)

        private fun canonicalUrl(raw: String): String {
            val uri = URI(raw.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "VBOOK_REPOSITORY_HTTPS_REQUIRED" }
            require(uri.userInfo == null && uri.fragment == null) { "VBOOK_REPOSITORY_URL_INVALID" }
            return URI(
                "https",
                null,
                uri.host.lowercase(),
                uri.port,
                uri.path.ifBlank { "/" },
                uri.query,
                null,
            ).toASCIIString()
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun errorCode(error: Throwable): String = when {
            error.message?.contains("HTTP", true) == true -> "VBOOK_REPOSITORY_HTTP_ERROR"
            error.message?.contains("JSON", true) == true -> "VBOOK_REPOSITORY_JSON_INVALID"
            else -> "VBOOK_REPOSITORY_FETCH_FAILED"
        }
    }
}
