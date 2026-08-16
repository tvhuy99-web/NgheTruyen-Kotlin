package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.LinkedHashMap



class HttpTextClient(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val governor: HostRequestGovernor = HostRequestGovernor(),
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) : TextDocumentClient {
    private data class CachedText(val value: String, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, CachedText>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedText>?): Boolean =
            size > cacheCapacity
    }

    override suspend fun getText(
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        require(allowedHosts.isNotEmpty()) { "Allowlist miền không được để trống." }
        var currentUrl = url.toHttpUrl().also { it.requireAllowedTextHost(allowedHosts) }
        var redirects = 0

        while (true) {
            cached(currentUrl.toString())?.let { return@withContext it }
            governor.awaitTurn(currentUrl.host)
            val builder = Request.Builder().url(currentUrl).get()
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
            val response = client.newCall(builder.build()).execute()
            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Nguồn chuyển hướng quá $MAX_REDIRECTS lần.")
                    val location = response.header("Location")
                        ?: throw IOException("Phản hồi chuyển hướng thiếu Location.")
                    currentUrl = currentUrl.resolve(location)
                        ?.also { it.requireAllowedTextHost(allowedHosts) }
                        ?: throw IOException("Địa chỉ chuyển hướng không hợp lệ.")
                    redirects += 1
                    continue
                }
                if (!response.isSuccessful) throw HttpSourceException(
                    response.code,
                    "Máy chủ trả về HTTP ${response.code}.",
                )
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                val bytes = body.byteStream().readTextBounded(maxResponseBytes)
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val value = String(bytes, charset)
                remember(currentUrl.toString(), value)
                return@withContext value
            } finally {
                response.close()
            }
        }

        @Suppress("UNREACHABLE_CODE")
        error("Vòng tải văn bản kết thúc ngoài dự kiến.")
    }

    private fun cached(key: String): String? = synchronized(cache) {
        val value = cache[key] ?: return@synchronized null
        if (value.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key)
            null
        } else value.value
    }

    private fun remember(key: String, value: String) {
        if (cacheCapacity <= 0 || cacheTtlMillis <= 0) return
        synchronized(cache) {
            cache[key] = CachedText(value, System.currentTimeMillis() + cacheTtlMillis)
        }
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_CACHE_TTL_MILLIS = 90_000L
        const val DEFAULT_CACHE_CAPACITY = 24
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(45))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun HttpUrl.requireAllowedTextHost(allowedHosts: Set<String>) {
    require(isHttps) { "Nguồn văn bản chỉ được tải qua HTTPS." }
    require(allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
        "Miền $host không nằm trong allowlist của nguồn."
    }
}

private fun InputStream.readTextBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
