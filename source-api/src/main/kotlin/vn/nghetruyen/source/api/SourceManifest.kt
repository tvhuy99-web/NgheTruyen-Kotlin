package vn.nghetruyen.source.api

import java.net.URI

const val SOURCE_PACK_SCHEMA_VERSION = 2
const val SOURCE_API_VERSION = 2

enum class SourceRuntimeMode { DECLARATIVE, VBOOK_JS_COMPAT, NATIVE_LUA_COMPAT }
enum class SourceContentType { NOVEL, COMIC, AUDIO, MIXED }
enum class SourceCookieMode { NONE, READ, WRITE, READ_WRITE, BROWSER_SHARED }
enum class SourceCryptoCapability { MD5, SHA1, SHA256, SHA512, HMAC_MD5, HMAC_SHA1, HMAC_SHA256, HMAC_SHA512, AES_COMPAT, AES_GCM_SECRET }
enum class SourceActionName {
    HOME, GENRE, SEARCH, DETAIL, LATEST_CHAPTER, TOC_PAGES, TOC, CHAPTER, COMMENTS, SUGGESTIONS, LOGIN;

    val manifestKey: String get() = name.lowercase().replace("toc_pages", "tocPages")
}

data class SourceRuntimePolicy(
    val mode: SourceRuntimeMode,
    val entry: String? = null,
    val instructionBudget: Int = 200_000,
    val memoryBudgetBytes: Int = 16 * 1024 * 1024,
    val actionTimeoutMs: Long = 30_000,
)

data class SourceNetworkCapability(
    val methods: Set<String> = setOf("GET"),
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val maxRequestBytes: Int = 0,
    val requestsPerMinute: Int = 60,
    val maxConcurrent: Int = 2,
)

data class SourceBrowserCapability(
    val navigate: Boolean = false,
    val domSnapshot: Boolean = false,
    val click: Boolean = false,
    val input: Boolean = false,
    val requestMetadata: Boolean = false,
    val serviceWorkerCapture: Boolean = false,
    val pageJavaScript: Boolean = false,
)

data class SourceWebSocketCapability(
    val enabled: Boolean = false,
    val maxMessageBytes: Int = 64 * 1024,
    val maxLifetimeMs: Long = 60_000,
)

data class SourceCapabilities(
    val network: SourceNetworkCapability? = null,
    val cookies: SourceCookieMode = SourceCookieMode.NONE,
    val browser: SourceBrowserCapability = SourceBrowserCapability(),
    val storageBytes: Int = 0,
    val crypto: Set<SourceCryptoCapability> = emptySet(),
    val websocket: SourceWebSocketCapability = SourceWebSocketCapability(),
)

data class SourceActionSpec(
    val entry: String,
    val timeoutMs: Long? = null,
    val maxOutputBytes: Int = 1024 * 1024,
)

data class SourcePrivacyDisclosure(
    val sendsContentToThirdParty: Boolean = false,
    val thirdParties: List<String> = emptyList(),
    val note: String = "",
)

data class SourceFixtureSpec(
    val name: String,
    val action: SourceActionName,
    val input: String,
    val fixture: String? = null,
    val expected: String,
)

data class SourceManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: SemanticVersion,
    val apiVersion: Int,
    val minAppVersion: SemanticVersion? = null,
    val maxAppVersion: SemanticVersion? = null,
    val locale: String = "vi-VN",
    val contentType: SourceContentType = SourceContentType.NOVEL,
    val adult: Boolean = false,
    val runtime: SourceRuntimePolicy,
    val urlPatterns: List<String> = emptyList(),
    val origins: Set<String>,
    val redirectOrigins: Set<String> = emptySet(),
    val capabilities: SourceCapabilities,
    val actions: Map<SourceActionName, SourceActionSpec>,
    val privacy: SourcePrivacyDisclosure = SourcePrivacyDisclosure(),
    val fixtures: List<SourceFixtureSpec> = emptyList(),
) {
    fun validate() {
        require(schemaVersion == SOURCE_PACK_SCHEMA_VERSION) { "SOURCE_SCHEMA_UNSUPPORTED" }
        require(apiVersion == SOURCE_API_VERSION) { "SOURCE_API_UNSUPPORTED" }
        require(ID_PATTERN.matches(id)) { "SOURCE_ID_INVALID" }
        require(name.isNotBlank() && name.length <= 120) { "SOURCE_NAME_INVALID" }
        require(description.length <= 1000 && author.length <= 120) { "SOURCE_METADATA_TOO_LONG" }
        require(LOCALE_PATTERN.matches(locale)) { "SOURCE_LOCALE_INVALID" }
        require(origins.isNotEmpty() && origins.size <= 32) { "SOURCE_ORIGINS_INVALID" }
        (origins + redirectOrigins).forEach(::validateOrigin)
        require(actions.keys.containsAll(REQUIRED_ACTIONS)) { "SOURCE_REQUIRED_ACTION_MISSING" }
        actions.values.forEach { action ->
            requireSafeRelativePath(action.entry)
            require(action.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_ACTION_OUTPUT_LIMIT_INVALID" }
            action.timeoutMs?.let { require(it in 500..120_000) { "SOURCE_ACTION_TIMEOUT_INVALID" } }
        }
        runtime.entry?.let(::requireSafeRelativePath)
        require(runtime.instructionBudget in 1_000..1_000_000) { "SOURCE_INSTRUCTION_BUDGET_INVALID" }
        require(runtime.memoryBudgetBytes in 1024 * 1024..64 * 1024 * 1024) { "SOURCE_MEMORY_BUDGET_INVALID" }
        require(runtime.actionTimeoutMs in 1_000..120_000) { "SOURCE_TIMEOUT_INVALID" }
        require(capabilities.storageBytes in 0..16 * 1024 * 1024) { "SOURCE_STORAGE_LIMIT_INVALID" }
        capabilities.network?.let { network ->
            require(network.methods.isNotEmpty() && network.methods.all { it in ALLOWED_METHODS }) { "SOURCE_METHOD_INVALID" }
            require(network.maxResponseBytes in 1024..16 * 1024 * 1024) { "SOURCE_RESPONSE_LIMIT_INVALID" }
            require(network.maxRequestBytes in 0..4 * 1024 * 1024) { "SOURCE_REQUEST_LIMIT_INVALID" }
            require(network.requestsPerMinute in 1..600) { "SOURCE_RATE_INVALID" }
            require(network.maxConcurrent in 1..8) { "SOURCE_CONCURRENCY_INVALID" }
        }
        require(urlPatterns.size <= 32 && urlPatterns.all { it.length <= 500 }) { "SOURCE_URL_PATTERN_INVALID" }
        fixtures.forEach { fixture ->
            fixture.fixture?.let(::requireSafeRelativePath)
            requireSafeRelativePath(fixture.expected)
        }
    }

    companion object {
        private val ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val LOCALE_PATTERN = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
        private val REQUIRED_ACTIONS = setOf(SourceActionName.DETAIL, SourceActionName.TOC, SourceActionName.CHAPTER)
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

        fun requireSafeRelativePath(path: String) {
            require(path.isNotBlank() && path.length <= 240) { "SOURCE_PATH_INVALID" }
            require(!path.startsWith('/') && !path.startsWith('\\')) { "SOURCE_PATH_ABSOLUTE" }
            require('\\' !in path && '\u0000' !in path && ':' !in path.substringBefore('/')) { "SOURCE_PATH_INVALID" }
            val parts = path.split('/')
            require(parts.none { it.isBlank() || it == "." || it == ".." }) { "SOURCE_PATH_TRAVERSAL" }
        }

        private fun validateOrigin(origin: String) {
            require(origin.length <= 300) { "SOURCE_ORIGIN_TOO_LONG" }
            val wildcard = origin.startsWith("https://*.")
            val normalized = if (wildcard) origin.replaceFirst("https://*.", "https://") else origin
            val uri = runCatching { URI(normalized) }.getOrNull() ?: error("SOURCE_ORIGIN_INVALID")
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.path.orEmpty().isEmpty()) {
                "SOURCE_ORIGIN_INVALID"
            }
            require(uri.query == null && uri.fragment == null) { "SOURCE_ORIGIN_INVALID" }
            require(uri.port == -1 || uri.port in 1..65535) { "SOURCE_ORIGIN_PORT_INVALID" }
        }
    }
}

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return when {
            preRelease == null && other.preRelease != null -> 1
            preRelease != null && other.preRelease == null -> -1
            else -> compareValues(preRelease, other.preRelease)
        }
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        preRelease?.let { append('-').append(it) }
    }

    companion object {
        private val PATTERN = Regex("^([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9A-Za-z.-]+))?$")
        fun parse(raw: String): SemanticVersion {
            val match = PATTERN.matchEntire(raw) ?: error("SOURCE_VERSION_INVALID")
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                preRelease = match.groupValues[4].ifBlank { null },
            )
        }
    }
}
