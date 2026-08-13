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
    private val directPackageBytes = VBookDirectPackageByteCache()

    /**
     * Fetch the root URL once, then classify those exact bytes as repository index, direct catalog,
     * or direct plugin.zip. Child catalogs still use the normal cached fetcher. This avoids the old
     * probe-then-download race where a direct package URL could be requested twice before preview.
     */
    fun snapshot(indexUrl: String = OFFICIAL_INDEX, strict: Boolean = false): VBookRepositorySnapshot =
        withTrace("repository") { traceId ->
            emit(traceId, "VBOOK_REPOSITORY_INPUT_STARTED", attributes = mapOf("url" to indexUrl))
            val canonical = canonicalUrl(indexUrl)
            val rootAttempt = runCatching { fetchBytes(canonical, MAX_PACKAGE_BYTES) }
            if (rootAttempt.isSuccess) {
                return@withTrace classifyRootBytes(
                    canonical = canonical,
                    bytes = rootAttempt.getOrThrow(),
                    strict = strict,
                    traceId = traceId,
                    fromCache = false,
                    allowDirectPackage = true,
                )
            }

            // Preserve offline repository/direct-catalog behavior without issuing another root request.
            val cached = cache?.read(canonical, VBookRepositoryAggregator.MAX_CATALOG_BYTES)
            if (cached != null) {
                emit(traceId, "VBOOK_REPOSITORY_CACHE_FALLBACK", DiagnosticSeverity.WARN, mapOf(
                    "url" to canonical,
                    "ageMs" to (System.currentTimeMillis() - cached.updatedAtEpochMs).coerceAtLeast(0L).toString(),
                    "networkError" to rootAttempt.exceptionOrNull()?.message.orEmpty().take(500),
                ))
                return@withTrace classifyRootBytes(
                    canonical = canonical,
                    bytes = cached.body.toByteArray(Charsets.UTF_8),
                    strict = strict,
                    traceId = traceId,
                    fromCache = true,
                    allowDirectPackage = false,
                )
            }

            val message = rootAttempt.exceptionOrNull()?.message.orEmpty().ifBlank { "VBOOK_REPOSITORY_INPUT_UNRECOGNIZED" }
            emit(traceId, "VBOOK_REPOSITORY_INPUT_FAILED", DiagnosticSeverity.ERROR, mapOf("url" to indexUrl, "error" to message))
            error(message)
        }

    fun evictCachedDocument(url: String) {
        cache?.remove(url)
        directPackageBytes.removeUrl(runCatching { canonicalUrl(url) }.getOrDefault(url.trim()))
    }

    fun downloadPackage(item: VBookAggregatedItem): ByteArray = withTrace("package") { traceId ->
        emit(traceId, "VBOOK_PACKAGE_DOWNLOAD_STARTED", attributes = mapOf("url" to item.item.packageUrl, "name" to item.item.name))
        val reused = directPackageBytes.take(item.installIdentity, item.item.packageUrl)
        val bytes = if (reused != null) {
            val digest = sha256(reused.bytes)
            require(digest == reused.sha256) { "VBOOK_DIRECT_PACKAGE_PIN_HASH_MISMATCH" }
            emit(traceId, "VBOOK_PACKAGE_REUSED_CLASSIFIED_BYTES", attributes = mapOf(
                "url" to item.item.packageUrl,
                "bytes" to reused.bytes.size.toString(),
                "sha256" to digest,
            ))
            reused.bytes
        } else {
            fetchBytes(item.item.packageUrl, MAX_PACKAGE_BYTES)
        }
        emit(traceId, "VBOOK_PACKAGE_DOWNLOAD_COMPLETED", attributes = mapOf(
            "bytes" to bytes.size.toString(),
            "sha256" to sha256(bytes),
            "reusedClassifiedBytes" to (reused != null).toString(),
        ))
        bytes
    }

    private fun classifyRootBytes(
        canonical: String,
        bytes: ByteArray,
        strict: Boolean,
        traceId: String,
        fromCache: Boolean,
        allowDirectPackage: Boolean,
    ): VBookRepositorySnapshot {
        val body = bytes.toString(Charsets.UTF_8)
        val digest = sha256(bytes)
        val fetched = VBookRepositoryFetchResult(canonical, body, digest)
        val rootPinnedFetcher = VBookRepositoryFetcher { url, maxBytes ->
            if (url == canonical) {
                require(bytes.size <= maxBytes) { "VBOOK_REPOSITORY_RESPONSE_TOO_LARGE" }
                fetched
            } else {
                fetcher.fetch(url, maxBytes)
            }
        }

        val indexAttempt = runCatching { VBookRepositoryAggregator(rootPinnedFetcher).fetchIndex(canonical, strict) }
        if (indexAttempt.isSuccess) {
            val snapshot = indexAttempt.getOrThrow()
            if (!fromCache) cache?.write(canonical, body)
            emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                "kind" to if (fromCache) "repository-index-cache" else "repository-index",
                "catalogs" to snapshot.repositories.size.toString(),
                "items" to snapshot.items.size.toString(),
                "rootFetchCount" to if (fromCache) "0" else "1",
            ))
            return snapshot
        }

        val directCatalogAttempt = runCatching {
            val catalog = VBookCatalogParser.parse(body)
            directCatalogSnapshot(fetched, catalog, strict)
        }
        if (directCatalogAttempt.isSuccess) {
            val snapshot = directCatalogAttempt.getOrThrow()
            if (!fromCache) cache?.write(canonical, body)
            emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                "kind" to if (fromCache) "direct-vbook-catalog-cache" else "direct-vbook-catalog",
                "catalogs" to snapshot.repositories.size.toString(),
                "items" to snapshot.items.size.toString(),
                "indexFailure" to indexAttempt.exceptionOrNull()?.message.orEmpty(),
                "rootFetchCount" to if (fromCache) "0" else "1",
            ))
            return snapshot
        }

        var packageError: Throwable? = null
        if (allowDirectPackage) {
            val directPackageAttempt = runCatching {
                val pkg = VBookPackageReader.read(bytes)
                val manifest = VBookManifestParser.parse(pkg.pluginJson())
                val syntheticBody = directPackageCatalogBody(canonical, manifest)
                val syntheticFetched = VBookRepositoryFetchResult(canonical, syntheticBody, digest)
                val catalog = VBookCatalogParser.parse(syntheticBody)
                directCatalogSnapshot(syntheticFetched, catalog, strict).copy(indexSha256 = digest)
            }
            if (directPackageAttempt.isSuccess) {
                val snapshot = directPackageAttempt.getOrThrow()
                snapshot.items.singleOrNull()?.let { item ->
                    directPackageBytes.put(
                        installIdentity = item.installIdentity,
                        packageUrl = item.item.packageUrl,
                        sha256 = digest,
                        bytes = bytes,
                    )
                }
                emit(traceId, "VBOOK_REPOSITORY_INPUT_CLASSIFIED", attributes = mapOf(
                    "kind" to "direct-vbook-package",
                    "catalogs" to snapshot.repositories.size.toString(),
                    "items" to snapshot.items.size.toString(),
                    "sha256" to digest,
                    "exactBytesPinned" to "true",
                    "rootFetchCount" to "1",
                ))
                return snapshot
            }
            packageError = directPackageAttempt.exceptionOrNull()
        }

        val cleanBody = body.trim()
        if (runCatching { VBookManifestParser.parse(cleanBody) }.isSuccess) {
            error("DIRECT_VBOOK_MANIFEST_ONLY: URL này là plugin.json; hãy dùng liên kết plugin.zip để cài tiện ích.")
        }
        if (cleanBody.startsWith("<!doctype", ignoreCase = true) || cleanBody.startsWith("<html", ignoreCase = true)) {
            error("DIRECT_WEBPAGE_NOT_EXTENSION: URL trả về trang web HTML, không phải repository, catalog hoặc plugin.zip.")
        }
        if (cleanBody.startsWith("{") || cleanBody.startsWith("[")) {
            error("DIRECT_JSON_UNSUPPORTED: JSON không đúng định dạng repository/catalog vBook được hỗ trợ.")
        }

        val message = listOf(
            indexAttempt.exceptionOrNull()?.message,
            directCatalogAttempt.exceptionOrNull()?.message,
            packageError?.message,
        ).filterNotNull().filter(String::isNotBlank).joinToString(" | ")
        emit(traceId, "VBOOK_REPOSITORY_INPUT_FAILED", DiagnosticSeverity.ERROR, mapOf(
            "url" to canonical,
            "error" to message,
            "fromCache" to fromCache.toString(),
        ))
        error(message.ifBlank { "VBOOK_REPOSITORY_INPUT_UNRECOGNIZED" })
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
