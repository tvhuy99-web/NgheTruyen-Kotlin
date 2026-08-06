package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Duration
import java.util.LinkedHashMap

class HttpHtmlClient(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val governor: HostRequestGovernor = HostRequestGovernor(),
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) : HtmlDocumentClient {
    private data class CachedHtml(
        val html: String,
        val baseUri: String,
        val expiresAt: Long,
    )

    private val cache = object : LinkedHashMap<String, CachedHtml>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHtml>?): Boolean =
            size > cacheCapacity
    }

    override suspend fun getDocument(
        url: String,
        allowedHosts: Set<String>,
    ): Document = withContext(Dispatchers.IO) {
        require(allowedHosts.isNotEmpty()) { "Allowlist miền không được để trống." }

        var currentUrl = url.toHttpUrl().also { it.requireAllowed(allowedHosts) }
        var redirectCount = 0

        while (true) {
            cached(currentUrl.toString())?.let { cached ->
                return@withContext Jsoup.parse(cached.html, cached.baseUri)
            }

            governor.awaitTurn(currentUrl.host)
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.6")
                .get()
                .build()

            val response = client.newCall(request).execute()
            try {
                if (response.code in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw IOException("Nguồn chuyển hướng quá $MAX_REDIRECTS lần.")
                    }
                    val location = response.header("Location")
                        ?: throw IOException("Phản hồi chuyển hướng thiếu tiêu đề Location.")
                    val nextUrl = currentUrl.resolve(location)
                        ?: throw IOException("Địa chỉ chuyển hướng không hợp lệ.")
                    nextUrl.requireAllowed(allowedHosts)
                    currentUrl = nextUrl
                    redirectCount += 1
                    continue
                }

                if (!response.isSuccessful) {
                    throw HttpSourceException(response.code, "Máy chủ trả về HTTP ${response.code}.")
                }

                val responseBody = response.body
                val declaredLength = responseBody.contentLength()
                if (declaredLength > maxResponseBytes) {
                    throw ResponseTooLargeException(maxResponseBytes)
                }
                val bytes = responseBody.byteStream().readBounded(maxResponseBytes)
                val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val html = bytes.toString(charset)
                remember(currentUrl.toString(), html)
                return@withContext Jsoup.parse(html, currentUrl.toString())
            } finally {
                response.close()
            }
        }

        @Suppress("UNREACHABLE_CODE")
        error("Vòng tải tài liệu kết thúc ngoài dự kiến.")
    }

    private fun cached(key: String): CachedHtml? = synchronized(cache) {
        val value = cache[key] ?: return@synchronized null
        if (value.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key)
            null
        } else {
            value
        }
    }

    private fun remember(key: String, html: String) {
        if (cacheTtlMillis <= 0L || cacheCapacity <= 0) return
        synchronized(cache) {
            cache[key] = CachedHtml(
                html = html,
                baseUri = key,
                expiresAt = System.currentTimeMillis() + cacheTtlMillis,
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36 NgheTruyen/1.0"
        const val MAX_REDIRECTS = 5
        const val DEFAULT_CACHE_TTL_MILLIS = 90_000L
        const val DEFAULT_CACHE_CAPACITY = 24

        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

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

class HttpSourceException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class ResponseTooLargeException(maxResponseBytes: Int) : IOException(
    "Trang vượt giới hạn an toàn ${maxResponseBytes / 1024} KiB.",
)

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
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

private fun HttpUrl.requireAllowed(allowedHosts: Set<String>) {
    require(isHttps) { "Nguồn truyện chỉ được tải qua HTTPS." }
    require(allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
        "Miền $host không nằm trong allowlist của nguồn."
    }
}

private fun ByteArray.toString(charset: Charset): String = String(this, charset)
