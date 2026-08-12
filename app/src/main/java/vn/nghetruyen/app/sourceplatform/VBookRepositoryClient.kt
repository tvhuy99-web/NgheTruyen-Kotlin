package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.vbook.VBookAggregatedItem
import vn.nghetruyen.source.vbook.VBookCatalog
import vn.nghetruyen.source.vbook.VBookCatalogParser
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookRepositoryAggregator
import vn.nghetruyen.source.vbook.VBookRepositoryFetchResult
import vn.nghetruyen.source.vbook.VBookRepositoryFetcher
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Repository/catalog/direct-package transport with Lua-style automatic HTTPS input recognition. */
class VBookRepositoryClient(
    network: OkHttpSourceNetworkBroker? = null,
    private val cache: VBookRepositoryCacheStore? = null,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) {
    private val traceContext = ThreadLocal<String>()
    private val network = network ?: OkHttpSourceNetworkBroker(diagnostics = diagnostics)
    private val fetcher = VBookRepositoryFetcher { url, maxBytes ->
        runCatching {
            val bytes = fetchBytes(url, maxBytes)
            val body = bytes.toString(Charsets.UTF_8)
            cache?.write(url, body)
            VBookRepositoryFetchResult(url, body, sha256(bytes))
        }.getOrElse { networkError ->
            val cached = cache?.read(url, maxBytes) ?: throw networkError
            val bytes = cached.body.toByteArray(Charsets.UTF_8)
            val traceId = traceContext.get().orEmpty().ifBlank { "repository-cache:${UUID.randomUUID()}" }
            emit(
                traceId,
                "VBOOK_REPOSITORY_CACHE_FALLBACK",
                DiagnosticSeverity.WARN,
                mapOf(
                    "url" to url,
                    "ageMs" to (System.currentTimeMillis() - cached.updatedAtEpochMs).coerceAtLeast(0L).toString(),
                    "networkError" to (networkError.message ?: networkError.javaClass.simpleName).take(500),
                ),
            )
            VBookRepositoryFetchResult(url, cached.body, sha256(bytes))
        }
    }
    private val aggregator = VBookRepositoryAggregator(fetcher)

    fun snapshot(indexUrl: String = OFFICIAL_INDEX, strict: Boolean = false): VBookRepositorySnapshot =
        withTrace("repository") { traceId ->
            emit(traceId, "VBOOK_REPOSITORY_INPUT_STARTED", attributes = mapOf("url" to indexUrl))
            val indexAttempt = runCatching { aggregator.fetchIndex(indexUrl, strict) }
            if (indexAttempt.isSuccess) {
                val snapshot = indexAttempt.getOrThrow()
                emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                    "kind" to "repository-index",
                    "catalogs" to snapshot.repositories.size.toString(),
                    "items" to snapshot.items.size.toString(),
                ))
                return@withTrace snapshot
            }

            val indexError = indexAttempt.exceptionOrNull()
            val canonical = canonicalUrl(indexUrl)
            val directBytesAttempt = runCatching { fetchBytes(canonical, MAX_PACKAGE_BYTES) }
            if (directBytesAttempt.isSuccess) {
                val directBytes = directBytesAttempt.getOrThrow()
                val directBody = directBytes.toString(Charsets.UTF_8)
                val directCatalogAttempt = runCatching {
                    val fetched = VBookRepositoryFetchResult(canonical, directBody, sha256(directBytes))
                    val catalog = VBookCatalogParser.parse(directBody)
                    directCatalogSnapshot(fetched, catalog, strict)
                }
                if (directCatalogAttempt.isSuccess) {
                    val snapshot = directCatalogAttempt.getOrThrow()
                    emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                        "kind" to "direct-vbook-catalog",
                        "catalogs" to snapshot.repositories.size.toString(),
                        "items" to snapshot.items.size.toString(),
                        "indexFailure" to indexError?.message.orEmpty(),
                    ))
                    return@withTrace snapshot
                }

                val directPackageAttempt = runCatching {
                    val pkg = VBookPackageReader.read(directBytes)
                    val manifest = VBookManifestParser.parse(pkg.pluginJson())
                    val syntheticBody = directPackageCatalogBody(canonical, manifest)
                    val fetched = VBookRepositoryFetchResult(canonical, syntheticBody, sha256(directBytes))
                    val catalog = VBookCatalogParser.parse(syntheticBody)
                    directCatalogSnapshot(fetched, catalog, strict).copy(indexSha256 = sha256(directBytes))
                }
                if (directPackageAttempt.isSuccess) {
                    val snapshot = directPackageAttempt.getOrThrow()
                    emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                        "kind" to "direct-vbook-package",
                        "catalogs" to snapshot.repositories.size.toString(),
                        "items" to snapshot.items.size.toString(),
                        "sha256" to sha256(directBytes),
                    ))
                    return@withTrace snapshot
                }

                val cleanBody = directBody.trim()
                if (runCatching { VBookManifestParser.parse(cleanBody) }.isSuccess) {
                    error("DIRECT_VBOOK_MANIFEST_ONLY: URL này là plugin.json; hãy dùng liên kết plugin.zip để cài tiện ích.")
                }
                if (cleanBody.startsWith("<!doctype", ignoreCase = true) || cleanBody.startsWith("<html", ignoreCase = true)) {
                    error("DIRECT_WEBPAGE_NOT_EXTENSION: URL trả về trang web HTML, không phải repository, catalog hoặc plugin.zip.")
                }
                if (cleanBody.startsWith("{") || cleanBody.startsWith("[")) {
                    error("DIRECT_JSON_UNSUPPORTED: JSON không đúng định dạng repository/catalog vBook được hỗ trợ.")
                }

                val directError = directCatalogAttempt.exceptionOrNull()
                val packageError = directPackageAttempt.exceptionOrNull()
                val message = listOf(indexError?.message, directError?.message, packageError?.message)
                    .filterNotNull().filter(String::isNotBlank).joinToString(" | ")
                emit(traceId, "VBOOK_REPOSITORY_INPUT_FAILED", DiagnosticSeverity.ERROR, mapOf("url" to indexUrl, "error" to message))
                error(message.ifBlank { "VBOOK_REPOSITORY_INPUT_UNRECOGNIZED" })
            }

            // Preserve the previous offline behavior for cached direct catalogs when the network is unavailable.
            val directAttempt = runCatching {
                val fetched = fetcher.fetch(canonical, VBookRepositoryAggregator.MAX_CATALOG_BYTES)
                val catalog = VBookCatalogParser.parse(fetched.body)
                directCatalogSnapshot(fetched, catalog, strict)
            }
            if (directAttempt.isSuccess) {
                val snapshot = directAttempt.getOrThrow()
                emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                    "kind" to "direct-vbook-catalog-cache",
                    "catalogs" to snapshot.repositories.size.toString(),
                    "items" to snapshot.items.size.toString(),
                    "indexFailure" to indexError?.message.orEmpty(),
                ))
                return@withTrace snapshot
            }

            val message = listOf(
                indexError?.message,
                directBytesAttempt.exceptionOrNull()?.message,
                directAttempt.exceptionOrNull()?.message,
            ).filterNotNull().filter(String::isNotBlank).joinToString(" | ")
            emit(traceId, "VBOOK_REPOSITORY_INPUT_FAILED", DiagnosticSeverity.ERROR, mapOf("url" to indexUrl, "error" to message))
            error(message.ifBlank { "VBOOK_REPOSITORY_INPUT_UNRECOGNIZED" })
        }

    fun evictCachedDocument(url: String) {
        cache?.remove(url)
    }

    fun downloadPackage(item: VBookAggregatedItem): ByteArray = withTrace("package") { traceId ->
        emit(traceId, "VBOOK_PACKAGE_DOWNLOAD_STARTED", attributes = mapOf("url" to item.item.packageUrl, "name" to item.item.name))
        val bytes = fetchBytes(item.item.packageUrl, MAX_PACKAGE_BYTES)
        emit(traceId, "VBOOK_PACKAGE_DOWNLOAD_COMPLETED", attributes = mapOf("bytes" to bytes.size.toString(), "sha256" to sha256(bytes)))
        bytes
    }

    private fun directCatalogSnapshot(
        fetched: VBookRepositoryFetchResult,
        catalog: VBookCatalog,
        strict: Boolean,
    ): VBookRepositorySnapshot {
        val wrapperUrl = syntheticWrapperUrl(fetched.url)
        val wrapperBody = JsonCodec.stringify(JsonValue.Arr(listOf(JsonValue.Obj(linkedMapOf(
            "link" to JsonValue.Str(fetched.url),
            "author" to JsonValue.Str(catalog.metadata.author),
            "description" to JsonValue.Str(catalog.metadata.description),
        )))))
        val wrapper = VBookRepositoryFetchResult(wrapperUrl, wrapperBody, sha256(wrapperBody.toByteArray(Charsets.UTF_8)))
        val directFetcher = VBookRepositoryFetcher { url, maxBytes ->
            when (url) {
                wrapperUrl -> wrapper
                fetched.url -> fetched
                else -> fetcher.fetch(url, maxBytes)
            }
        }
        return VBookRepositoryAggregator(directFetcher)
            .fetchIndex(wrapperUrl, strict)
            .copy(indexUrl = fetched.url, indexSha256 = fetched.sha256)
    }

    private fun directPackageCatalogBody(url: String, manifest: vn.nghetruyen.source.vbook.VBookExtensionManifest): String =
        JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "metadata" to JsonValue.Obj(linkedMapOf(
                "author" to JsonValue.Str(manifest.metadata.author),
                "description" to JsonValue.Str(manifest.metadata.description),
            )),
            "data" to JsonValue.Arr(listOf(JsonValue.Obj(linkedMapOf(
                "name" to JsonValue.Str(manifest.metadata.name.ifBlank { "vBook extension" }),
                "author" to JsonValue.Str(manifest.metadata.author),
                "path" to JsonValue.Str(url),
                "version" to JsonValue.Str(manifest.metadata.version.toString()),
                "source" to JsonValue.Str(manifest.metadata.source),
                "description" to JsonValue.Str(manifest.metadata.description),
                "type" to JsonValue.Str(manifest.metadata.rawType?.takeIf(String::isNotBlank) ?: manifest.metadata.type.name.lowercase()),
                "locale" to JsonValue.Str(manifest.metadata.locale),
            )))),
        )))

    private fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_PACKAGE_BYTES) { "VBOOK_REPOSITORY_FETCH_LIMIT_INVALID" }
        val traceId = traceContext.get().orEmpty().ifBlank { "vbook-fetch:${UUID.randomUUID()}" }
        emit(traceId, "VBOOK_FETCH_STARTED", attributes = mapOf("url" to url, "maxBytes" to maxBytes.toString()))
        val result = network.execute(
            manifest(maxBytes),
            SourceNetworkRequest(
                sourceId = FETCHER_SOURCE_ID,
                url = url,
                method = "GET",
                allowHttpError = false,
                timeoutMs = 45_000,
                traceId = traceId,
            ),
        )
        val response = when (result) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> {
                emit(traceId, "VBOOK_FETCH_FAILED", DiagnosticSeverity.ERROR, mapOf("url" to url, "code" to result.error.code.name, "error" to result.error.message))
                error("VBOOK_REPOSITORY_FETCH_${result.error.code}:${result.error.message}")
            }
        }
        require(response.body.size <= maxBytes) { "VBOOK_REPOSITORY_RESPONSE_TOO_LARGE" }
        val bytes = response.body.copyOf()
        val digest = sha256(bytes)
        emit(traceId, "VBOOK_FETCH_COMPLETED", attributes = mapOf(
            "url" to url,
            "status" to response.statusCode.toString(),
            "bytes" to bytes.size.toString(),
            "sha256" to digest,
            "finalUrl" to response.finalUrl,
        ))
        if (evidence.enabled) {
            val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
            evidence.capture(DiagnosticEvidence(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId,
                sourceId = FETCHER_SOURCE_ID,
                category = DiagnosticCategory.NETWORK,
                name = "repository-fetch-${sha256(url.toByteArray()).take(12)}-${digest.take(12)}.${if (zip) "zip" else "json"}",
                contentType = if (zip) "application/zip" else "application/json",
                data = bytes,
                attributes = mapOf("url" to url, "finalUrl" to response.finalUrl, "status" to response.statusCode.toString()),
            ))
        }
        return bytes
    }

    private fun <T> withTrace(prefix: String, block: (String) -> T): T {
        val previous = traceContext.get()
        val traceId = "$prefix:${UUID.randomUUID()}"
        traceContext.set(traceId)
        return try {
            block(traceId)
        } finally {
            if (previous == null) traceContext.remove() else traceContext.set(previous)
        }
    }

    private fun emit(
        traceId: String,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        attributes: Map<String, String> = emptyMap(),
    ) {
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = traceId,
            sourceId = FETCHER_SOURCE_ID,
            sourceVersion = "1.0.0",
            category = if (name.contains("CLASSIFIED") || name.contains("INPUT")) DiagnosticCategory.PARSER else DiagnosticCategory.NETWORK,
            name = name,
            severity = severity,
            attributes = attributes,
        ))
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
        const val MAX_PACKAGE_BYTES = 20 * 1024 * 1024
        private const val FETCHER_SOURCE_ID = "vbook.repository.transport"

        private fun canonicalUrl(raw: String): String {
            val uri = URI(raw.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "VBOOK_REPOSITORY_HTTPS_REQUIRED" }
            require(uri.userInfo == null && uri.fragment == null) { "VBOOK_REPOSITORY_URL_INVALID" }
            return URI("https", null, uri.host.lowercase(), uri.port, uri.path.ifBlank { "/" }, uri.query, null).toASCIIString()
        }

        private fun syntheticWrapperUrl(catalogUrl: String): String {
            val uri = URI(catalogUrl)
            val query = listOfNotNull(uri.query?.takeIf(String::isNotBlank), "__nghetruyen_direct_catalog=1").joinToString("&")
            return URI("https", null, uri.host.lowercase(), uri.port, uri.path.ifBlank { "/" }, query, null).toASCIIString()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
