package vn.nghetruyen.app.sourceplatform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes a vBook network request through Android System WebView's real Chromium network stack.
 *
 * This is intentionally below the vBook fetch contract. Extensions still call the same synchronous
 * fetch()/Http surface; replay yields before entering this broker, so no WebView work happens while
 * the action WebView owns a pending prompt. A short-lived sibling WebView shares Chromium's process
 * profile and CookieManager without replacing the extension's Browser page.
 */
internal class AndroidWebViewSessionNetworkBroker(
    context: Context,
    private val cookiePartition: SourceCookiePartition,
    resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceNetworkBroker {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val navigationPolicy = BrowserNavigationPolicy(resolver)
    private val operationLock = Any()

    override fun execute(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure(SourceErrorCode.NETWORK_UNAVAILABLE, "SOURCE_BROWSER_NETWORK_BLOCKING_CALL_ON_MAIN", request)
        }
        return synchronized(operationLock) {
            runCatching { executeBlocking(manifest, request) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    val message = error.message ?: error.javaClass.simpleName
                    val code = when {
                        "TIMEOUT" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_TIMEOUT
                        "RESPONSE_TOO_LARGE" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_RESPONSE_TOO_LARGE
                        "REQUEST_TOO_LARGE" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_REQUEST_TOO_LARGE
                        "METHOD" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_METHOD_DENIED
                        "DNS" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_DNS_BLOCKED
                        "URL" in message.uppercase(Locale.ROOT) || "NAVIGATION" in message.uppercase(Locale.ROOT) -> SourceErrorCode.NETWORK_URL_INVALID
                        else -> SourceErrorCode.NETWORK_IO_ERROR
                    }
                    SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId, error))
                },
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun executeBlocking(
        manifest: SourceManifest,
        request: SourceNetworkRequest,
    ): SourcePlatformResult<SourceNetworkResponse> {
        require(request.sourceId == manifest.id) { "SOURCE_NETWORK_SOURCE_ID_MISMATCH" }
        val capability = manifest.capabilities.network ?: error("SOURCE_NETWORK_CAPABILITY_DENIED")
        val method = request.method.trim().uppercase(Locale.ROOT)
        require(method in capability.methods) { "SOURCE_NETWORK_METHOD_DENIED:$method" }
        require(request.body.size <= capability.maxRequestBytes) { "SOURCE_NETWORK_REQUEST_TOO_LARGE" }
        require(request.timeoutMs in 100L..120_000L) { "SOURCE_NETWORK_TIMEOUT_INVALID" }

        val approved = navigationPolicy.preflightInitial(manifest, request.url)
        val transportUrl = (approved as? BrowserNavigationPolicy.Decision.Allowed)?.transportUrl
            ?: error("SOURCE_BROWSER_NETWORK_URL_DENIED:${policyCode(approved)}")
        val baseUrl = originBaseUrl(transportUrl)
        val startedAt = clockMs()
        val deadline = startedAt + request.timeoutMs
        val cookieManager = CookieManager.getInstance()

        importPartitionCookies(manifest, transportUrl, cookieManager)
        request.header("Cookie")?.takeIf(String::isNotBlank)?.let { cookieHeader ->
            importExplicitCookieHeader(transportUrl, cookieHeader, cookieManager)
        }
        cookieManager.flush()

        val webView = runOnMain(minOf(10_000L, remaining(deadline))) {
            WebView(appContext).also { view ->
                ExtensionWebViewAuthority.apply(appContext, view)
                request.header("User-Agent")?.takeIf(String::isNotBlank)?.let { explicit ->
                    view.settings.userAgentString = explicit.take(1_000)
                }
            }
        }

        try {
            val ready = CountDownLatch(1)
            runOnMain(minOf(5_000L, remaining(deadline))) {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        ready.countDown()
                    }

                    override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
                        val url = resource.url.toString()
                        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
                        return when (navigationPolicy.preflightRedirect(manifest, url)) {
                            is BrowserNavigationPolicy.Decision.Allowed -> null
                            else -> blockedResponse()
                        }
                    }
                }
                webView.loadDataWithBaseURL(
                    baseUrl,
                    BOOTSTRAP_HTML,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            if (!ready.await(remaining(deadline), TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_NETWORK_BOOTSTRAP_TIMEOUT")

            val token = "nghe_fetch_${request.traceId.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)}_${UUID.randomUUID().toString().replace("-", "")}"
            val fetchScript = buildFetchScript(token, transportUrl, method, request)
            val metadataRaw = evaluate(webView, fetchScript, remaining(deadline))
            val metadata = JSONObject(metadataRaw)
            if (!metadata.optBoolean("ok")) {
                error("SOURCE_BROWSER_NETWORK_FETCH_FAILED:${metadata.optString("error").take(500)}")
            }

            val bodyChars = metadata.optLong("bodyChars", 0L)
            val maxBase64Chars = ((capability.maxResponseBytes.toLong() + 2L) / 3L) * 4L + 16L
            require(bodyChars in 0L..maxBase64Chars) { "SOURCE_NETWORK_RESPONSE_TOO_LARGE" }
            val bodyBase64 = StringBuilder(bodyChars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            var offset = 0L
            while (offset < bodyChars) {
                val end = minOf(bodyChars, offset + BODY_CHUNK_CHARS)
                val chunk = evaluate(
                    webView,
                    "String((((window.__ngheVBookNetworkFetch||{})[${jsString(token)}]||{}).bodyBase64)||'').slice($offset,$end)",
                    remaining(deadline),
                )
                bodyBase64.append(chunk)
                offset = end
            }
            runCatching {
                evaluate(webView, "delete (window.__ngheVBookNetworkFetch||{})[${jsString(token)}]", minOf(2_000L, remaining(deadline)))
            }

            val body = if (bodyBase64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(bodyBase64.toString())
            require(body.size <= capability.maxResponseBytes) { "SOURCE_NETWORK_RESPONSE_TOO_LARGE" }

            val finalUrl = metadata.optString("url").takeIf(String::isNotBlank) ?: transportUrl
            val responseHeaders = jsonHeaders(metadata.optJSONObject("headers"))
            val statusCode = metadata.optInt("status", 0)
            val statusText = metadata.optString("statusText")
            val completedAt = clockMs()
            val currentUserAgent = runOnMain(minOf(2_000L, remaining(deadline))) { webView.settings.userAgentString.orEmpty() }
            val finalCookieHeader = runOnMain(minOf(2_000L, remaining(deadline))) { cookieManager.getCookie(finalUrl).orEmpty() }
            syncPartitionCookies(manifest, finalUrl, finalCookieHeader)
            cookieManager.flush()

            if (statusCode !in 200..299 && !request.allowHttpError) {
                return SourcePlatformResult.Failure(SourcePlatformFailure(
                    SourceErrorCode.NETWORK_HTTP_ERROR,
                    "SOURCE_NETWORK_HTTP_$statusCode",
                    request.traceId,
                ))
            }

            val actualRequestHeaders = linkedMapOf<String, List<String>>()
            request.headers.forEach { (name, value) ->
                if (!name.equals("Cookie", true) && !name.equals("User-Agent", true)) {
                    actualRequestHeaders[name] = listOf(value)
                }
            }
            if (currentUserAgent.isNotBlank()) actualRequestHeaders["User-Agent"] = listOf(currentUserAgent)
            if (finalCookieHeader.isNotBlank()) actualRequestHeaders["Cookie"] = listOf(finalCookieHeader)

            return SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = statusCode,
                finalUrl = finalUrl,
                headers = responseHeaders,
                body = body,
                charsetName = responseCharset(responseHeaders),
                timing = SourceNetworkTiming(startedAt, completedAt),
                traceId = request.traceId,
                statusText = statusText,
                requestUrl = transportUrl,
                requestHeaders = actualRequestHeaders,
            ))
        } finally {
            runCatching {
                runOnMain(5_000L) {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                }
            }
        }
    }

    private fun buildFetchScript(
        token: String,
        url: String,
        method: String,
        request: SourceNetworkRequest,
    ): String {
        val publicHeaders = linkedMapOf<String, String>()
        request.headers.forEach { (name, value) ->
            if (!isBrowserForbiddenHeader(name)) publicHeaders[name] = value
        }
        val contentType = request.contentType
        if (!contentType.isNullOrBlank() && publicHeaders.keys.none { it.equals("Content-Type", true) }) {
            publicHeaders["Content-Type"] = contentType
        }
        val headersJson = JSONObject(publicHeaders as Map<*, *>).toString()
        val bodyBase64 = Base64.getEncoder().encodeToString(request.body)
        val referrer = request.header("Referer").orEmpty()
        val hasBody = request.body.isNotEmpty() && method !in setOf("GET", "HEAD")
        return """
            (()=>{
              window.__ngheVBookNetworkFetch=window.__ngheVBookNetworkFetch||Object.create(null);
              const token=${jsString(token)};
              const headers=$headersJson;
              const options={method:${jsString(method)},headers:headers,credentials:'include',redirect:'follow',cache:'no-store'};
              ${if (referrer.isNotBlank()) "options.referrer=${jsString(referrer)};" else ""}
              ${if (hasBody) "const raw=atob(${jsString(bodyBase64)});const bytes=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i)&255;options.body=bytes;" else ""}
              return fetch(${jsString(url)},options).then(async response=>{
                const bytes=new Uint8Array(await response.arrayBuffer());
                let binary='';
                const step=32768;
                for(let i=0;i<bytes.length;i+=step) binary+=String.fromCharCode.apply(null,bytes.subarray(i,Math.min(bytes.length,i+step)));
                const bodyBase64=btoa(binary);
                const responseHeaders={};
                response.headers.forEach((value,name)=>{responseHeaders[name]=value;});
                window.__ngheVBookNetworkFetch[token]={bodyBase64:bodyBase64};
                return JSON.stringify({ok:true,status:response.status,statusText:response.statusText||'',url:response.url||${jsString(url)},headers:responseHeaders,bodyChars:bodyBase64.length});
              }).catch(error=>{
                window.__ngheVBookNetworkFetch[token]={bodyBase64:''};
                return JSON.stringify({ok:false,error:String(error&&(error.message||error)||'unknown'),bodyChars:0});
              });
            })()
        """.trimIndent()
    }

    private fun importPartitionCookies(manifest: SourceManifest, url: String, manager: CookieManager) {
        if (manifest.capabilities.cookies.name != "BROWSER_SHARED") return
        cookiePartition.exportSetCookieHeaders(manifest.id, url).forEach { manager.setCookie(url, it, null) }
    }

    private fun importExplicitCookieHeader(url: String, header: String, manager: CookieManager) {
        val secure = url.startsWith("https://", true)
        header.split(';').map(String::trim).filter { it.contains('=') }.take(128).forEach { token ->
            manager.setCookie(url, buildString {
                append(token).append("; Path=/")
                if (secure) append("; Secure")
            }, null)
        }
    }

    private fun syncPartitionCookies(manifest: SourceManifest, url: String, header: String) {
        if (manifest.capabilities.cookies.name != "BROWSER_SHARED" || header.isBlank()) return
        val secure = url.startsWith("https://", true)
        cookiePartition.mergeSetCookieHeaders(
            manifest.id,
            url,
            header.split("; ").filter(String::isNotBlank).map { token ->
                buildString {
                    append(token).append("; Path=/")
                    if (secure) append("; Secure")
                }
            },
        )
    }

    private fun jsonHeaders(json: JSONObject?): Map<String, List<String>> {
        if (json == null) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = listOf(json.optString(key))
        }
        return out
    }

    private fun responseCharset(headers: Map<String, List<String>>): String? {
        val value = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull().orEmpty()
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { Charset.forName(charsetName).name() }.getOrNull()
    }

    private fun isBrowserForbiddenHeader(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.ROOT)
        return lower in FORBIDDEN_HEADERS || lower.startsWith("sec-") || lower.startsWith("proxy-")
    }

    private fun SourceNetworkRequest.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun originBaseUrl(url: String): String {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("SOURCE_NETWORK_URL_INVALID")
        val host = uri.host ?: error("SOURCE_NETWORK_URL_INVALID")
        return buildString {
            append(scheme).append("://").append(host)
            val defaultPort = if (scheme == "https") 443 else 80
            if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
            append('/')
        }
    }

    private fun policyCode(decision: BrowserNavigationPolicy.Decision): String = when (decision) {
        is BrowserNavigationPolicy.Decision.Denied -> decision.code
        is BrowserNavigationPolicy.Decision.NeedsDns -> "SOURCE_BROWSER_DNS_PREFLIGHT_REQUIRED"
        is BrowserNavigationPolicy.Decision.Allowed -> "allowed"
    }

    private fun remaining(deadline: Long): Long = (deadline - clockMs()).coerceAtLeast(1L)

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun <T> runOnMain(timeoutMs: Long, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_NETWORK_TIMEOUT")
        return result.get().getOrThrow()
    }

    private fun evaluate(webView: WebView, expression: String, timeoutMs: Long): String {
        val result = AtomicReference<String?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                webView.evaluateJavascript(expression, ValueCallback { raw ->
                    result.set(decodeJavascriptResult(raw))
                    latch.countDown()
                })
            }.onFailure {
                error.set(it)
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) error("SOURCE_BROWSER_NETWORK_TIMEOUT")
        error.get()?.let { throw it }
        return result.get().orEmpty()
    }

    private fun decodeJavascriptResult(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue()?.toString().orEmpty() }.getOrDefault(raw)
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun failure(code: SourceErrorCode, message: String, request: SourceNetworkRequest) =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId))

    companion object {
        private const val BODY_CHUNK_CHARS = 256L * 1024L
        private const val BOOTSTRAP_HTML = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
        private val FORBIDDEN_HEADERS = setOf(
            "accept-encoding",
            "connection",
            "content-length",
            "cookie",
            "cookie2",
            "host",
            "origin",
            "referer",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "user-agent",
            "via",
        )
    }
}
