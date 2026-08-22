package vn.nghetruyen.source.network

import vn.nghetruyen.source.api.SourceCookiePartition
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SourceCookieRecord(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtEpochMs: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val sameSite: String? = null,
    val createdAtEpochMs: Long,
) {
    val key: String get() = "${name.lowercase(Locale.ROOT)}\u0000${domain.lowercase(Locale.ROOT)}\u0000$path"
}

fun interface SourceCookiePersistence {
    fun save(sourceId: String, records: List<SourceCookieRecord>)

    fun load(sourceId: String): List<SourceCookieRecord> = emptyList()
    fun clear(sourceId: String) = save(sourceId, emptyList())

    companion object {
        val NONE = SourceCookiePersistence { _, _ -> }
    }
}

class PartitionedSourceCookieJar(
    private val persistence: SourceCookiePersistence = SourceCookiePersistence.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxCookiesPerSource: Int = 256,
    private val maxCookieBytes: Int = 8 * 1024,
) : SourceCookiePartition {
    private val lock = ReentrantLock()
    private val loaded = mutableSetOf<String>()
    private val records = linkedMapOf<String, LinkedHashMap<String, SourceCookieRecord>>()

    init {
        require(maxCookiesPerSource in 1..2_000) { "SOURCE_COOKIE_LIMIT_INVALID" }
        require(maxCookieBytes in 256..64 * 1024) { "SOURCE_COOKIE_BYTES_INVALID" }
    }

    override fun readCookieHeader(sourceId: String): String? = lock.withLock {
        bucket(sourceId).values.asSequence()
            .filterNot(::expired)
            .sortedWith(compareByDescending<SourceCookieRecord> { it.path.length }.thenBy { it.createdAtEpochMs })
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf(String::isNotBlank)
    }

    override fun readCookieHeader(sourceId: String, requestUrl: String): String? = lock.withLock {
        val uri = requireHttpUrl(requestUrl)
        prune(sourceId)
        bucket(sourceId).values.asSequence()
            .filter { cookie -> matches(cookie, uri) }
            .sortedWith(compareByDescending<SourceCookieRecord> { it.path.length }.thenBy { it.createdAtEpochMs })
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf(String::isNotBlank)
    }

    override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) =
        mergeSetCookieHeaders(sourceId, "https://invalid.local/", setCookieHeaders)

    override fun mergeSetCookieHeaders(sourceId: String, responseUrl: String, setCookieHeaders: List<String>) = lock.withLock {
        val uri = requireHttpUrl(responseUrl)
        val bucket = bucket(sourceId)
        setCookieHeaders.take(64).forEach { raw ->
            parse(raw, uri)?.let { parsed ->
                if (expired(parsed) || parsed.value.isEmpty()) bucket.remove(parsed.key) else bucket[parsed.key] = parsed
            }
        }
        prune(sourceId)
        if (bucket.size > maxCookiesPerSource) {
            bucket.values.sortedBy(SourceCookieRecord::createdAtEpochMs)
                .take(bucket.size - maxCookiesPerSource)
                .forEach { bucket.remove(it.key) }
        }
        persist(sourceId)
    }

    override fun exportSetCookieHeaders(sourceId: String, requestUrl: String): List<String> = lock.withLock {
        val uri = requireHttpUrl(requestUrl)
        prune(sourceId)
        bucket(sourceId).values.filter { matches(it, uri) }.map { cookie ->
            buildString {
                append(cookie.name).append('=').append(cookie.value)
                append("; Path=").append(cookie.path)
                if (!cookie.hostOnly) append("; Domain=").append(cookie.domain)
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
                cookie.expiresAtEpochMs?.let { append("; Expires=").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC))) }
                cookie.sameSite?.let { append("; SameSite=").append(it) }
            }
        }
    }

    override fun clear(sourceId: String) = lock.withLock {
        records.remove(sourceId)
        loaded += sourceId
        persistence.clear(sourceId)
    }

    fun snapshot(sourceId: String): List<SourceCookieRecord> = lock.withLock {
        prune(sourceId)
        bucket(sourceId).values.toList()
    }

    private fun bucket(sourceId: String): LinkedHashMap<String, SourceCookieRecord> {
        require(SOURCE_ID.matches(sourceId)) { "SOURCE_COOKIE_SOURCE_ID_INVALID" }
        if (loaded.add(sourceId)) {
            val restored = persistence.load(sourceId).take(maxCookiesPerSource)
                .filterNot(::expired)
                .associateByTo(LinkedHashMap(), SourceCookieRecord::key)
            records[sourceId] = restored
        }
        return records.getOrPut(sourceId) { linkedMapOf() }
    }

    private fun parse(raw: String, responseUri: URI): SourceCookieRecord? {
        if (raw.toByteArray(Charsets.UTF_8).size > maxCookieBytes) return null
        val parts = raw.split(';')
        val first = parts.firstOrNull()?.trim().orEmpty()
        val separator = first.indexOf('=')
        if (separator <= 0) return null
        val name = first.substring(0, separator).trim()
        val value = first.substring(separator + 1).trim()
        if (!TOKEN.matches(name) || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        val attributes = linkedMapOf<String, String?>()
        parts.drop(1).forEach { part ->
            val text = part.trim()
            if (text.isEmpty()) return@forEach
            val index = text.indexOf('=')
            val key = (if (index < 0) text else text.substring(0, index)).trim().lowercase(Locale.ROOT)
            val attrValue = if (index < 0) null else text.substring(index + 1).trim()
            attributes[key] = attrValue
        }
        val responseHost = responseUri.host.lowercase(Locale.ROOT).trimEnd('.')
        val domainAttribute = attributes["domain"]?.trim()?.trimStart('.')?.lowercase(Locale.ROOT)?.trimEnd('.')
        val hostOnly = domainAttribute.isNullOrBlank()
        val domain = domainAttribute?.takeIf { domainMatches(responseHost, it) } ?: if (hostOnly) responseHost else return null
        val path = attributes["path"]?.takeIf { it.startsWith('/') } ?: defaultPath(responseUri.path.orEmpty())
        val now = clockMs()
        val maxAge = attributes["max-age"]?.toLongOrNull()
        val expires = when {
            maxAge != null -> if (maxAge <= 0) 0L else now + maxAge.coerceAtMost(MAX_MAX_AGE_SECONDS) * 1_000L
            attributes["expires"] != null -> parseHttpDate(attributes["expires"].orEmpty())
            else -> null
        }
        return SourceCookieRecord(
            name = name,
            value = value,
            domain = domain,
            path = path.take(MAX_PATH_CHARS),
            expiresAtEpochMs = expires,
            secure = "secure" in attributes,
            httpOnly = "httponly" in attributes,
            hostOnly = hostOnly,
            sameSite = attributes["samesite"]?.lowercase(Locale.ROOT)?.let {
                when (it) { "strict" -> "Strict"; "lax" -> "Lax"; "none" -> "None"; else -> null }
            },
            createdAtEpochMs = now,
        )
    }

    private fun matches(cookie: SourceCookieRecord, uri: URI): Boolean {
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        if (cookie.secure && !uri.scheme.equals("https", true)) return false
        if (cookie.hostOnly) {
            if (host != cookie.domain) return false
        } else if (!domainMatches(host, cookie.domain)) return false
        val requestPath = uri.path.takeUnless(String::isNullOrEmpty) ?: "/"
        return pathMatches(requestPath, cookie.path)
    }

    private fun expired(cookie: SourceCookieRecord): Boolean = cookie.expiresAtEpochMs?.let { it <= clockMs() } == true

    private fun prune(sourceId: String) {
        val bucket = bucket(sourceId)
        val before = bucket.size
        bucket.entries.removeAll { expired(it.value) }
        if (bucket.size != before) persist(sourceId)
    }

    private fun persist(sourceId: String) = persistence.save(sourceId, bucket(sourceId).values.toList())

    private fun requireHttpUrl(raw: String): URI {
        val uri = runCatching { URI(raw) }.getOrNull() ?: error("SOURCE_COOKIE_URL_INVALID")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "SOURCE_COOKIE_URL_INVALID" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "SOURCE_COOKIE_URL_INVALID" }
        require(uri.port == -1 || uri.port in 1..65535) { "SOURCE_COOKIE_URL_INVALID" }
        return uri
    }

    companion object {
        private const val MAX_PATH_CHARS = 1024
        private const val MAX_MAX_AGE_SECONDS = 400L * 24 * 60 * 60
        private val SOURCE_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}$")

        internal fun domainMatches(host: String, domain: String): Boolean =
            host == domain || (host.endsWith(".$domain") && !host.substringBefore(".$domain").isBlank())

        internal fun pathMatches(requestPath: String, cookiePath: String): Boolean =
            requestPath == cookiePath || (requestPath.startsWith(cookiePath) && (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'))

        internal fun defaultPath(path: String): String {
            if (!path.startsWith('/') || path.count { it == '/' } <= 1) return "/"
            return path.substringBeforeLast('/').ifBlank { "/" }
        }

        private fun parseHttpDate(raw: String): Long? = runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
