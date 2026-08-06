package vn.nghetruyen.app.sources

interface SourceSessionStore {
    fun cookieHeader(sourceId: String): String?
    fun replaceCookieHeader(sourceId: String, cookieHeader: String)
    fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>)
    fun clear(sourceId: String)
    fun hasSession(sourceId: String): Boolean = !cookieHeader(sourceId).isNullOrBlank()
}

class InMemorySourceSessionStore : SourceSessionStore {
    private val values = LinkedHashMap<String, String>()

    @Synchronized
    override fun cookieHeader(sourceId: String): String? = values[sourceId]

    @Synchronized
    override fun replaceCookieHeader(sourceId: String, cookieHeader: String) {
        val normalized = CookieHeaderCodec.normalize(cookieHeader)
        if (normalized.isBlank()) values.remove(sourceId) else values[sourceId] = normalized
    }

    @Synchronized
    override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
        replaceCookieHeader(
            sourceId,
            CookieHeaderCodec.merge(cookieHeader(sourceId).orEmpty(), setCookieHeaders),
        )
    }

    @Synchronized
    override fun clear(sourceId: String) {
        values.remove(sourceId)
    }
}

internal object CookieHeaderCodec {
    fun normalize(raw: String): String {
        val cookies = parseCookieHeader(raw)
        require(cookies.size <= MAX_COOKIE_COUNT) { "Nguồn trả về quá nhiều cookie phiên." }
        val normalized = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_COOKIE_HEADER_BYTES) {
            "Cookie phiên vượt giới hạn an toàn."
        }
        return normalized
    }

    fun merge(existing: String, setCookieHeaders: List<String>): String {
        val cookies = parseCookieHeader(existing).toMutableMap()
        setCookieHeaders.forEach { header ->
            val first = header.substringBefore(';').trim()
            val separator = first.indexOf('=')
            if (separator <= 0) return@forEach
            val name = first.substring(0, separator).trim()
            val value = first.substring(separator + 1).trim()
            val expired = header.contains("max-age=0", ignoreCase = true) ||
                header.contains("expires=thu, 01 jan 1970", ignoreCase = true)
            if (expired || value.isBlank()) cookies.remove(name) else cookies[name] = value
        }
        return normalize(cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
    }

    fun cookieNames(raw: String): List<String> = parseCookieHeader(raw).keys.toList()

    private fun parseCookieHeader(raw: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.split(';').forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@forEach
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (name.isNotBlank() && value.isNotBlank()) result[name] = value
        }
        return result
    }

    private const val MAX_COOKIE_COUNT = 128
    private const val MAX_COOKIE_HEADER_BYTES = 32 * 1024
}
