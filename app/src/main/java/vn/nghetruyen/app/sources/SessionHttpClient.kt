package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration

class SessionHttpClient(
    private val sessionStore: SourceSessionStore,
    private val client: OkHttpClient = defaultClient(),
    private val governor: HostRequestGovernor = HostRequestGovernor(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val userAgent: String = HttpHtmlClient.DEFAULT_USER_AGENT,
) {
    suspend fun getDocument(
        sourceId: String,
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String> = emptyMap(),
    ): Document {
        val result = execute(sourceId, "GET", url, allowedHosts, headers, null)
        return Jsoup.parse(result.body, result.finalUrl)
    }

    suspend fun getText(
        sourceId: String,
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String> = emptyMap(),
    ): String = execute(sourceId, "GET", url, allowedHosts, headers, null).body

    suspend fun postForm(
        sourceId: String,
        url: String,
        allowedHosts: Set<String>,
        fields: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return execute(sourceId, "POST", url, allowedHosts, headers, body).body
    }

    suspend fun postEmpty(
        sourceId: String,
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String> = emptyMap(),
    ): String = execute(
        sourceId,
        "POST",
        url,
        allowedHosts,
        headers,
        ByteArray(0).toRequestBody("application/x-www-form-urlencoded".toMediaType()),
    ).body

    private suspend fun execute(
        sourceId: String,
        method: String,
        url: String,
        allowedHosts: Set<String>,
        headers: Map<String, String>,
        requestBody: okhttp3.RequestBody?,
    ): NetworkTextResult = withContext(Dispatchers.IO) {
        require(allowedHosts.isNotEmpty()) { "Allowlist miền không được để trống." }
        var current = url.toHttpUrl().also { it.requireAllowedSourceHost(allowedHosts) }
        var redirects = 0
        var currentMethod = method
        var currentBody = requestBody

        while (true) {
            governor.awaitTurn(current.host)
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.6")
                .header("Accept", "text/html,application/json,text/plain,*/*")
            sessionStore.cookieHeader(sourceId)?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            headers.forEach { (name, value) -> builder.header(name, value) }
            when (currentMethod) {
                "POST" -> builder.post(currentBody ?: ByteArray(0).toRequestBody(null))
                else -> builder.get()
            }

            val response = client.newCall(builder.build()).execute()
            try {
                sessionStore.mergeSetCookieHeaders(sourceId, response.headers("Set-Cookie"))
                if (response.code in REDIRECTS) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Nguồn chuyển hướng quá $MAX_REDIRECTS lần.")
                    val location = response.header("Location") ?: throw IOException("Chuyển hướng thiếu Location.")
                    val next = current.resolve(location) ?: throw IOException("Địa chỉ chuyển hướng không hợp lệ.")
                    next.requireAllowedSourceHost(allowedHosts)
                    if (response.code == 303 || ((response.code == 301 || response.code == 302) && currentMethod == "POST")) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    current = next
                    redirects += 1
                    continue
                }
                if (!response.isSuccessful) throw HttpSourceException(response.code, "Máy chủ trả về HTTP ${response.code}.")
                val declared = response.body.contentLength()
                if (declared > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                val bytes = response.body.byteStream().readBoundedSource(maxResponseBytes)
                val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return@withContext NetworkTextResult(String(bytes, charset), current.toString())
            } finally {
                response.close()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Vòng tải dữ liệu kết thúc ngoài dự kiến.")
    }

    private data class NetworkTextResult(val body: String, val finalUrl: String)

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(35))
            .callTimeout(Duration.ofSeconds(50))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun HttpUrl.requireAllowedSourceHost(allowedHosts: Set<String>) {
    require(isHttps) { "Nguồn truyện chỉ được tải qua HTTPS." }
    require(allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
        "Miền $host không nằm trong allowlist của nguồn."
    }
}

private fun InputStream.readBoundedSource(maxBytes: Int): ByteArray {
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
