package vn.nghetruyen.source.network

import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCookiePartition
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceWebSocketBroker
import vn.nghetruyen.source.api.SourceWebSocketFrame
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.api.SourceWebSocketResponse
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OkHttpSourceWebSocketBroker(
    private val cookiePartition: SourceCookiePartition = SourceCookiePartition.NONE,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build(),
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SourceWebSocketBroker {
    override fun exchange(manifest: SourceManifest, request: SourceWebSocketRequest): SourcePlatformResult<SourceWebSocketResponse> {
        val started = clockMs()
        return runCatching {
            require(request.sourceId == manifest.id) { "SOURCE_WEBSOCKET_SOURCE_ID_MISMATCH" }
            val capability = manifest.capabilities.websocket
            require(capability.enabled) { "SOURCE_WEBSOCKET_CAPABILITY_REQUIRED" }
            require(request.timeoutMs in 100L..capability.maxLifetimeMs) { "SOURCE_WEBSOCKET_TIMEOUT_INVALID" }
            require(request.maxResponses in 1..100) { "SOURCE_WEBSOCKET_RESPONSE_LIMIT_INVALID" }
            request.messages.forEach { require(it.toByteArray(Charsets.UTF_8).size <= capability.maxMessageBytes) { "SOURCE_WEBSOCKET_MESSAGE_TOO_LARGE" } }
            SourceHeaderPolicy.validate(request.headers)
            val uri = requireAllowedWebSocketUrl(manifest, request.url)
            PublicAddressPolicy.requirePublic(resolver(uri.host))
            val httpUrl = "https://${uri.rawAuthority}${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
            val builder = Request.Builder().url(request.url)
                .header("User-Agent", OkHttpSourceNetworkBroker.DEFAULT_USER_AGENT)
            request.headers.forEach(builder::header)
            readCookies(manifest, httpUrl)?.let { builder.header("Cookie", it) }
            val messages = mutableListOf<String>()
            val frames = mutableListOf<SourceWebSocketFrame>()
            val closeCode = AtomicReference<Int?>(null)
            val closeReason = AtomicReference<String?>(null)
            val failure = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)

            fun appendFrame(webSocket: WebSocket, frame: SourceWebSocketFrame) {
                val byteSize = if (frame.type == "binary") {
                    runCatching { Base64.getDecoder().decode(frame.data).size }.getOrDefault(capability.maxMessageBytes + 1)
                } else frame.data.toByteArray(Charsets.UTF_8).size
                if (byteSize > capability.maxMessageBytes) {
                    failure.compareAndSet(null, IllegalStateException("SOURCE_WEBSOCKET_MESSAGE_TOO_LARGE"))
                    webSocket.close(1009, "message too large")
                    latch.countDown()
                    return
                }
                synchronized(frames) {
                    if (frames.size < request.maxResponses) {
                        frames += frame
                        if (frame.type == "text") messages += frame.data
                    }
                    if (frames.size >= request.maxResponses) {
                        webSocket.close(1000, "complete")
                        latch.countDown()
                    }
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    writeCookies(manifest, httpUrl, response.headers("Set-Cookie"))
                    request.messages.forEach { message ->
                        if (!webSocket.send(message)) {
                            failure.compareAndSet(null, IllegalStateException("SOURCE_WEBSOCKET_SEND_FAILED"))
                            webSocket.cancel()
                            latch.countDown()
                            return
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) =
                    appendFrame(webSocket, SourceWebSocketFrame("text", text))

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    appendFrame(webSocket, SourceWebSocketFrame("binary", Base64.getEncoder().encodeToString(bytes.toByteArray())))

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode.set(code)
                    closeReason.set(reason.take(256))
                    webSocket.close(code, reason.take(123))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode.set(code)
                    closeReason.set(reason.take(256))
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set(t)
                    response?.let { writeCookies(manifest, httpUrl, it.headers("Set-Cookie")) }
                    latch.countDown()
                }
            }
            diagnostics.emit(event(manifest, request, "WEBSOCKET_STARTED"))
            val socket = client.newWebSocket(builder.build(), listener)
            val completed = latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                socket.cancel()
                error("SOURCE_WEBSOCKET_TIMEOUT")
            }
            failure.get()?.let { throw it }
            SourceWebSocketResponse(
                messages = messages.toList(),
                closeCode = closeCode.get(),
                closeReason = closeReason.get(),
                traceId = request.traceId,
                frames = frames.toList(),
            )
        }.fold(
            onSuccess = {
                diagnostics.emit(event(manifest, request, "WEBSOCKET_COMPLETED", durationMs = clockMs() - started, attributes = mapOf("frames" to it.frames.size.toString())))
                SourcePlatformResult.Success(it)
            },
            onFailure = { error ->
                val code = when {
                    error.message?.contains("MESSAGE_TOO_LARGE") == true -> SourceErrorCode.WEBSOCKET_MESSAGE_TOO_LARGE
                    error.message?.contains("TIMEOUT") == true -> SourceErrorCode.WEBSOCKET_TIMEOUT
                    else -> SourceErrorCode.WEBSOCKET_UNAVAILABLE
                }
                diagnostics.emit(event(manifest, request, "WEBSOCKET_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf("code" to code.name, "error" to (error.message ?: error.javaClass.simpleName))))
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_WEBSOCKET_FAILED", request.traceId, error))
            },
        )
    }

    private fun requireAllowedWebSocketUrl(manifest: SourceManifest, raw: String): URI {
        require(raw.length in 1..4096) { "SOURCE_WEBSOCKET_URL_INVALID" }
        val uri = runCatching { URI(raw) }.getOrNull() ?: error("SOURCE_WEBSOCKET_URL_INVALID")
        require(uri.scheme.equals("wss", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "SOURCE_WEBSOCKET_URL_INVALID"
        }
        val equivalent = URI("https", null, uri.host, uri.port, uri.path.orEmpty().ifBlank { "/" }, uri.query, null).toASCIIString()
        SourceOriginPolicy.requireInitialUrl(manifest, equivalent)
        return uri
    }

    private fun readCookies(manifest: SourceManifest, url: String): String? = when (manifest.capabilities.cookies) {
        SourceCookieMode.READ, SourceCookieMode.READ_WRITE, SourceCookieMode.BROWSER_SHARED -> cookiePartition.readCookieHeader(manifest.id, url)
        else -> null
    }

    private fun writeCookies(manifest: SourceManifest, url: String, headers: List<String>) {
        when (manifest.capabilities.cookies) {
            SourceCookieMode.WRITE, SourceCookieMode.READ_WRITE, SourceCookieMode.BROWSER_SHARED -> cookiePartition.mergeSetCookieHeaders(manifest.id, url, headers)
            else -> Unit
        }
    }

    private fun event(
        manifest: SourceManifest,
        request: SourceWebSocketRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        timestampEpochMs = clockMs(), traceId = request.traceId, sourceId = manifest.id,
        sourceVersion = manifest.version.toString(), category = DiagnosticCategory.NETWORK,
        name = name, severity = severity, durationMs = durationMs, attributes = attributes,
    )
}
