package vn.nghetruyen.app.sourceplatform

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.nghetruyen.source.network.PublicAddressPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration

internal class SourceRepositoryHttpClient(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    client: OkHttpClient? = null,
) {
    private val dns = Dns { host -> PublicAddressPolicy.requirePublic(resolver(host)) }
    private val client = client ?: OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(40))
        .callTimeout(Duration.ofSeconds(70))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    fun fetchIndex(url: String, maxBytes: Int): ByteArray = get(url, maxBytes, expectedSha256 = null)

    fun fetchPackage(url: String, maxBytes: Int, expectedBytes: Int, expectedSha256: String): ByteArray {
        val bytes = get(url, maxBytes, expectedSha256)
        require(bytes.size == expectedBytes) { "SOURCE_REPOSITORY_PACKAGE_SIZE_MISMATCH" }
        return bytes
    }

    private fun get(rawUrl: String, maxBytes: Int, expectedSha256: String?): ByteArray {
        var current = rawUrl.toHttpUrl().also(::requirePublicHttps)
        var redirects = 0
        while (true) {
            PublicAddressPolicy.requirePublic(resolver(current.host))
            val request = Request.Builder()
                .url(current)
                .get()
                .header("User-Agent", "NgheTruyen-Repository/1 Android")
                .header("Accept", "application/json,application/zip,application/octet-stream")
                .build()
            val redirected = client.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    require(redirects < MAX_REDIRECTS) { "SOURCE_REPOSITORY_TOO_MANY_REDIRECTS" }
                    val location = response.header("Location") ?: error("SOURCE_REPOSITORY_REDIRECT_LOCATION_MISSING")
                    current = response.request.url.resolve(location)?.also(::requirePublicHttps)
                        ?: error("SOURCE_REPOSITORY_REDIRECT_URL_INVALID")
                    redirects += 1
                    true
                } else {
                    require(response.isSuccessful) { "SOURCE_REPOSITORY_HTTP_ERROR:${response.code}" }
                    val declared = response.body.contentLength()
                    require(declared < 0 || declared <= maxBytes) { "SOURCE_REPOSITORY_DOWNLOAD_TOO_LARGE" }
                    val bytes = response.body.byteStream().readBounded(maxBytes)
                    expectedSha256?.let { expected ->
                        require(sha256(bytes) == expected) { "SOURCE_REPOSITORY_PACKAGE_HASH_MISMATCH" }
                    }
                    return bytes
                }
            }
            if (redirected) continue
        }
    }

    private fun requirePublicHttps(url: HttpUrl) {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "SOURCE_REPOSITORY_HTTPS_REQUIRED"
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("SOURCE_REPOSITORY_DOWNLOAD_TOO_LARGE")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
