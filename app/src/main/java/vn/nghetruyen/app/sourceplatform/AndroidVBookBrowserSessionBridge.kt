package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android WebView side of the vBook browser/HTTP shared session. */
internal class AndroidVBookBrowserSessionBridge(
    context: Context,
    private val cookieReader: SourceWebViewCookieReader?,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun userAgent(): String = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("")

    /**
     * Prefer the source-scoped browser reader but also read Android's process WebView profile.
     * Lua/XPK reads CookieManager directly for the target HTTP URL; the scoped reader can be empty
     * when no SourceBrowser session is active even though challenge/CSRF cookies still exist.
     */
    fun readCookies(sourceId: String, requestUrl: String): String? {
        if (sourceId.isBlank() || !requestUrl.startsWith("https://", true)) return null
        val scoped = runCatching { cookieReader?.readWebViewCookieHeader(sourceId, requestUrl) }.getOrNull().orEmpty()
        val global = readGlobalCookies(requestUrl).orEmpty()
        return mergeCookieHeaders(listOf(scoped, global)).takeIf(String::isNotBlank)
    }

    fun writeCookies(sourceId: String, responseUrl: String, setCookies: List<String>) {
        if (sourceId.isBlank() || !responseUrl.startsWith("https://", true) || setCookies.isEmpty()) return
        val write = {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            setCookies.take(128).filter(String::isNotBlank).forEach { cookie -> manager.setCookie(responseUrl, cookie) }
            manager.flush()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(write)
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try { runCatching(write) } finally { latch.countDown() }
        }
        latch.await(COOKIE_BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun readGlobalCookies(requestUrl: String): String? {
        val read = {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            val headers = cookieCandidateUrls(requestUrl).mapNotNull { candidate ->
                runCatching { manager.getCookie(candidate) }.getOrNull()?.takeIf(String::isNotBlank)
            }
            mergeCookieHeaders(headers)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(read).getOrNull()
        val value = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        main.post {
            try { value.set(runCatching(read).getOrNull()) } finally { latch.countDown() }
        }
        if (!latch.await(COOKIE_BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        return value.get()
    }

    private fun cookieCandidateUrls(requestUrl: String): List<String> = runCatching {
        val uri = URI(requestUrl)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank()) return@runCatching listOf(requestUrl)
        val port = when {
            uri.port == -1 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val candidates = linkedSetOf(requestUrl, "$scheme://$host$port/")
        parentDomain(host)?.let { candidates += "$scheme://$it/" }
        candidates.toList()
    }.getOrDefault(listOf(requestUrl))

    /** Lua removes exactly the first host label when probing a parent-domain cookie candidate. */
    private fun parentDomain(host: String): String? {
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return null
        val separator = host.indexOf('.')
        if (separator <= 0 || separator >= host.lastIndex) return null
        return host.substring(separator + 1)
    }

    private fun mergeCookieHeaders(headers: List<String>): String {
        val byName = LinkedHashMap<String, String>()
        headers.forEach { header ->
            header.split(';').forEach cookie@{ raw ->
                val token = raw.trim()
                val separator = token.indexOf('=')
                if (separator <= 0) return@cookie
                val name = token.substring(0, separator).trim()
                if (name.isNotBlank() && name !in byName) byName[name] = token
            }
        }
        return byName.values.joinToString("; ")
    }

    private companion object {
        const val COOKIE_BRIDGE_TIMEOUT_SECONDS = 5L
    }
}
