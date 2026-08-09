package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookRepositoryAggregator
import vn.nghetruyen.source.vbook.VBookRepositoryFetchResult
import vn.nghetruyen.source.vbook.VBookRepositoryFetcher
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot
import java.security.MessageDigest

/** Repository/catalog/package transport that reuses the source platform's public-address policy. */
class VBookRepositoryClient(
    private val network: OkHttpSourceNetworkBroker = OkHttpSourceNetworkBroker(),
) {
    private val fetcher = VBookRepositoryFetcher { url, maxBytes ->
        val bytes = fetchBytes(url, maxBytes)
        VBookRepositoryFetchResult(
            url = url,
            body = bytes.toString(Charsets.UTF_8),
            sha256 = sha256(bytes),
        )
    }
    private val aggregator = VBookRepositoryAggregator(fetcher)

    fun snapshot(indexUrl: String = OFFICIAL_INDEX, strict: Boolean = false): VBookRepositorySnapshot =
        aggregator.fetchIndex(indexUrl, strict)

    fun downloadPackage(item: VBookAggregatedItem): ByteArray =
        fetchBytes(item.item.packageUrl, MAX_PACKAGE_BYTES)

    private fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_PACKAGE_BYTES) { "VBOOK_REPOSITORY_FETCH_LIMIT_INVALID" }
        val result = network.execute(
            manifest(maxBytes),
            SourceNetworkRequest(
                sourceId = FETCHER_SOURCE_ID,
                url = url,
                method = "GET",
                allowHttpError = false,
                timeoutMs = 45_000,
                traceId = "vbook-repository",
            ),
        )
        val response = when (result) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("VBOOK_REPOSITORY_FETCH_${result.error.code}:${result.error.message}")
        }
        require(response.body.size <= maxBytes) { "VBOOK_REPOSITORY_RESPONSE_TOO_LARGE" }
        return response.body.copyOf()
    }

    private fun manifest(maxBytes: Int) = SourceManifest(
        schemaVersion = 2,
        id = FETCHER_SOURCE_ID,
        name = "vBook repository transport",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://vbook.invalid"),
        capabilities = SourceCapabilities(
            network = SourceNetworkCapability(
                methods = setOf("GET"),
                maxResponseBytes = maxBytes.coerceAtLeast(1024),
                maxRequestBytes = 0,
                requestsPerMinute = 120,
                maxConcurrent = 4,
                publicInternet = true,
                allowCleartext = false,
            ),
        ),
        actions = emptyMap(),
    )

    companion object {
        const val OFFICIAL_INDEX = "https://raw.githubusercontent.com/Darkrai9x/vbook-extensions/master/repository.json"
        const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
        private const val FETCHER_SOURCE_ID = "vbook.repository.transport"

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
