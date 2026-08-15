package vn.nghetruyen.app.sourceplatform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceCryptoRequest
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceGraphicsDrawOperation
import vn.nghetruyen.source.api.SourceGraphicsRequest
import vn.nghetruyen.source.api.SourceHostKernelWireExecutor
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponseMode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.api.SourceTranslationRequest
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookActionRuntime
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chromium-backed execution engine for canonical vBook compatibility actions.
 *
 * A fresh headless WebView is created and driven on Android's main thread for every action, while
 * synchronous host work is dispatched to a dedicated HandlerThread. Extension code cannot navigate
 * the action WebView and no Java object is installed with addJavascriptInterface. Synchronous vBook
 * host APIs cross one random-token prompt channel as bounded JSON, then re-enter the same
 * SourceCapabilityBrokers used by Rhino without blocking the main thread.
 *
 * This engine deliberately handles only the compatibility dispatch action. Other SourcePack action
 * shapes report VBOOK_RUNTIME_UNAVAILABLE before any script executes so the selector may use Rhino
 * without replaying side effects.
 */
class AndroidChromiumVBookRuntime(
    context: Context,
    private val brokers: SourceCapabilityBrokers,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val webViewCookieReader: SourceWebViewCookieReader? = null,
) : VBookActionRuntime, AutoCloseable {
    private val appContext = context.applicationContext
    private val engineThread = HandlerThread("NgheTruyen-VBook-Chromium").apply { start() }
    private val engine = Handler(engineThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        if (closed.get()) return unavailable("CHROMIUM_RUNTIME_CLOSED", request)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return unavailable("CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED", request)
        }
        if (manifest.runtime.mode != SourceRuntimeMode.VBOOK_JS_COMPAT) {
            return unavailable("CHROMIUM_VBOOK_MODE_REQUIRED", request)
        }
        if (request.action != SourceActionName.UI_ACTION) {
            return unavailable("CHROMIUM_COMPAT_DISPATCH_ACTION_REQUIRED:${request.action}", request)
        }
        val action = manifest.actions[request.action]
            ?: return failure(SourceErrorCode.ACTION_NOT_FOUND, "SOURCE_ACTION_NOT_FOUND:${request.action}", request)
        val entryBytes = resources.read(action.entry, MAX_SCRIPT_BYTES)
            ?: return failure(SourceErrorCode.ACTION_NOT_FOUND, "VBOOK_RESOURCE_MISSING:${action.entry}", request)
        val timeoutMs = (action.timeoutMs ?: manifest.runtime.actionTimeoutMs).coerceIn(100L, 120_000L)
        val started = clockMs()
        diagnostics.emit(event(manifest, request, "CHROMIUM_ACTION_STARTED", attributes = mapOf(
            "entry" to action.entry.take(300),
            "entryBytes" to entryBytes.size.toString(),
            "timeoutMs" to timeoutMs.toString(),
        )))
        captureEvidence(manifest, request, "chromium-entry.js", "text/javascript", entryBytes.toString(Charsets.UTF_8))

        val token = UUID.randomUUID().toString() + ":" + UUID.randomUUID().toString()
        val inputJson = JsonCodec.stringify(request.input)
        val program = ChromiumVBookPrelude.build(token, action.entry, inputJson)
        if (program.toByteArray(Charsets.UTF_8).size > MAX_PROGRAM_BYTES) {
            return failure(SourceErrorCode.RUNTIME_OUTPUT_TOO_LARGE, "CHROMIUM_PROGRAM_TOO_LARGE", request)
        }
        val deadlineMs = started + timeoutMs
        val bridge = BridgeSession(manifest, resources, request, deadlineMs)
        val evaluation = evaluate(program, token, bridge, timeoutMs, manifest, request)
        return evaluation.fold(
            onSuccess = { raw ->
                runCatching {
                    diagnostics.emit(event(manifest, request, "CHROMIUM_RESULT_DECODE_START", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "flow" to "decode",
                        "stage" to "decode-start",
                        "rawChars" to raw.length.toString(),
                    )))
                    val normalized = ChromiumVBookDispatchDecoder.decode(raw) { name, decoderAttributes ->
                        diagnostics.emit(event(
                            manifest,
                            request,
                            name,
                            DiagnosticSeverity.DEBUG,
                            attributes = decoderAttributes + mapOf(
                                "flow" to "decode",
                                "stage" to name.lowercase(Locale.ROOT),
                            ),
                        ))
                    }
                    diagnostics.emit(event(manifest, request, "CHROMIUM_RESULT_DECODE_OK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "flow" to "decode",
                        "stage" to "decode-ok",
                        "resultKeys" to normalized.values.size.toString(),
                    )))
                    diagnostics.emit(event(manifest, request, "CHROMIUM_PROCESS_DATA_START", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "flow" to "process",
                        "stage" to "serialize-output",
                    )))
                    val outputBytes = JsonCodec.stringify(normalized).toByteArray(Charsets.UTF_8).size
                    require(outputBytes <= action.maxOutputBytes) { "CHROMIUM_OUTPUT_TOO_LARGE" }
                    diagnostics.emit(event(manifest, request, "CHROMIUM_PROCESS_DATA_OK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "flow" to "process",
                        "stage" to "output-validated",
                        "outputBytes" to outputBytes.toString(),
                        "maxOutputBytes" to action.maxOutputBytes.toString(),
                    )))
                    SourceActionResponse(normalized, request.traceId, 0)
                }.fold(
                    onSuccess = { response ->
                        diagnostics.emit(event(manifest, request, "CHROMIUM_ACTION_COMPLETED", durationMs = clockMs() - started, attributes = mapOf(
                            "engine" to "android-webview-chromium",
                            "bridgeCalls" to bridge.calls.toString(),
                            "outputBytes" to JsonCodec.stringify(response.value).toByteArray(Charsets.UTF_8).size.toString(),
                        )))
                        SourcePlatformResult.Success(response)
                    },
                    onFailure = { error ->
                        val code = if (error.message?.contains("OUTPUT_TOO_LARGE") == true) SourceErrorCode.RUNTIME_OUTPUT_TOO_LARGE else SourceErrorCode.VBOOK_SCRIPT_ERROR
                        diagnostics.emit(event(manifest, request, "CHROMIUM_ACTION_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf(
                            "code" to code.name,
                            "error" to (error.message ?: error.javaClass.simpleName).take(800),
                            "bridgeCalls" to bridge.calls.toString(),
                        )))
                        SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "CHROMIUM_RESULT_FAILED", request.traceId, error))
                    },
                )
            },
            onFailure = { error ->
                val message = error.message ?: error.javaClass.simpleName
                val code = when {
                    "TIMEOUT" in message.uppercase(Locale.ROOT) -> SourceErrorCode.RUNTIME_BUDGET_EXCEEDED
                    "RENDERER" in message.uppercase(Locale.ROOT) -> SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE
                    "OUTPUT_TOO_LARGE" in message.uppercase(Locale.ROOT) -> SourceErrorCode.RUNTIME_OUTPUT_TOO_LARGE
                    else -> SourceErrorCode.VBOOK_SCRIPT_ERROR
                }
                diagnostics.emit(event(manifest, request, "CHROMIUM_ACTION_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf(
                    "code" to code.name,
                    "error" to message.take(800),
                    "bridgeCalls" to bridge.calls.toString(),
                )))
                SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId, error))
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluate(
        program: String,
        bridgeToken: String,
        bridge: BridgeSession,
        timeoutMs: Long,
        manifest: SourceManifest,
        request: SourceActionRequest,
    ): Result<String> {
        if (Looper.myLooper() == engineThread.looper) return Result.failure(IllegalStateException("CHROMIUM_REENTRANT_EXECUTION_DENIED"))
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<String>>()
        val webViewRef = AtomicReference<WebView?>()
        val completed = AtomicBoolean(false)
        val evaluationStarted = AtomicBoolean(false)

        fun destroyWebView() {
            val view = webViewRef.getAndSet(null) ?: return
            val destroy = { runCatching { view.stopLoading(); view.destroy() }; Unit }
            if (Looper.myLooper() == Looper.getMainLooper()) destroy() else main.post(destroy)
        }

        fun finish(result: Result<String>) {
            if (!completed.compareAndSet(false, true)) return
            outcome.set(result)
            destroyWebView()
            latch.countDown()
        }

        main.post {
            runCatching {
                val webView = WebView(appContext)
                webViewRef.set(webView)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    blockNetworkLoads = true
                    safeBrowsingEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onJsPrompt(
                        view: WebView,
                        url: String,
                        message: String,
                        defaultValue: String?,
                        result: JsPromptResult,
                    ): Boolean {
                        val tokenMatches = message == bridgeToken
                        diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_TOKEN_CHECK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                            "flow" to "bridge",
                            "stage" to "token-check",
                            "tokenMatches" to tokenMatches.toString(),
                            "messageChars" to message.length.toString(),
                        )))
                        if (!tokenMatches) {
                            diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_TOKEN_REJECTED", DiagnosticSeverity.WARN, attributes = mapOf(
                                "flow" to "bridge",
                                "stage" to "token-rejected",
                            )))
                            result.cancel()
                            return true
                        }
                        diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_TOKEN_OK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                            "flow" to "bridge",
                            "stage" to "token-ok",
                        )))
                        val raw = defaultValue.orEmpty()
                        engine.post {
                            val response = runCatching { bridge.handle(raw) }
                                .getOrElse { error -> bridgeEnvelopeError(error.message ?: error.javaClass.simpleName) }
                            main.post {
                                if (completed.get()) {
                                    diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_CONFIRM_STALE", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                        "flow" to "bridge",
                                        "stage" to "confirm-stale",
                                        "responseBytes" to response.toByteArray(Charsets.UTF_8).size.toString(),
                                    )))
                                    runCatching { result.cancel() }
                                } else {
                                    runCatching { result.confirm(response) }
                                        .onSuccess {
                                            diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_CONFIRM_OK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                                "flow" to "bridge",
                                                "stage" to "confirm-ok",
                                                "responseBytes" to response.toByteArray(Charsets.UTF_8).size.toString(),
                                            )))
                                        }
                                        .onFailure { error ->
                                            diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_CONFIRM_FAILED", DiagnosticSeverity.WARN, attributes = mapOf(
                                                "flow" to "bridge",
                                                "stage" to "confirm-failed",
                                                "error" to (error.message ?: error.javaClass.simpleName).take(800),
                                            )))
                                        }
                                }
                            }
                        }
                        return true
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val severity = when (consoleMessage.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> DiagnosticSeverity.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> DiagnosticSeverity.WARN
                            ConsoleMessage.MessageLevel.DEBUG -> DiagnosticSeverity.DEBUG
                            else -> DiagnosticSeverity.INFO
                        }
                        diagnostics.emit(event(manifest, request, "CHROMIUM_CONSOLE", severity, attributes = mapOf(
                            "message" to consoleMessage.message().orEmpty().take(4_000),
                            "line" to consoleMessage.lineNumber().toString(),
                            "source" to consoleMessage.sourceId().orEmpty().take(500),
                        )))
                        return true
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = blockedResponse()

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!evaluationStarted.compareAndSet(false, true) || completed.get()) return
                        diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_START", DiagnosticSeverity.DEBUG, attributes = mapOf(
                            "flow" to "evaluate",
                            "stage" to "evaluate-start",
                            "pageUrl" to url.orEmpty().take(500),
                            "programChars" to program.length.toString(),
                        )))
                        val guardedProgram = """
                  (function(){
                    try {
                      return ($program);
                    } catch (error) {
                      var message = String(error && (error.stack || error.message) || error || 'unknown');
                      return JSON.stringify({__ngheChromiumEvalError:message});
                    }
                  })()
              """.trimIndent()
                        view.evaluateJavascript(guardedProgram) { encoded ->
                            diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_CALLBACK_ENTER", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                "flow" to "evaluate",
                                "stage" to "callback-enter",
                                "encodedChars" to encoded.orEmpty().length.toString(),
                            )))
                            runCatching {
                                diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_CALLBACK_JSON_START", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                    "flow" to "evaluate",
                                    "stage" to "callback-json-start",
                                )))
                                val decoded = JSONTokener(encoded ?: "null").nextValue()
                                diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_CALLBACK_JSON_OK", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                    "flow" to "evaluate",
                                    "stage" to "callback-json-ok",
                                    "valueType" to when (decoded) {
                                        is String -> "string"
                                        null -> "null"
                                        else -> decoded.javaClass.simpleName.take(120)
                                    },
                                )))
                                when (decoded) {
                                    is String -> decoded
                                    null -> error("CHROMIUM_RESULT_NULL")
                                    else -> decoded.toString()
                                }.also { value ->
                                    diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_CALLBACK_VALUE", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                        "flow" to "evaluate",
                                        "stage" to "callback-value",
                                        "decodedChars" to value.length.toString(),
                                    )))
                                }
                            }.let(::finish)
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        diagnostics.emit(event(manifest, request, "CHROMIUM_RENDERER_GONE", DiagnosticSeverity.ERROR, attributes = mapOf(
                            "flow" to "evaluate",
                            "stage" to "renderer-gone",
                            "didCrash" to detail.didCrash().toString(),
                        )))
                        finish(Result.failure(IllegalStateException("CHROMIUM_RENDERER_GONE:${detail.didCrash()}")))
                        return true
                    }
                }
                webView.loadUrl("about:blank")
            }.onFailure { finish(Result.failure(it)) }
        }

        if (!latch.await(timeoutMs + CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
            diagnostics.emit(event(manifest, request, "CHROMIUM_EVALUATE_TIMEOUT", DiagnosticSeverity.ERROR, attributes = mapOf(
                "flow" to "evaluate",
                "stage" to "timeout",
                "timeoutMs" to timeoutMs.toString(),
                "graceMs" to CALLBACK_GRACE_MS.toString(),
            )))
            if (completed.compareAndSet(false, true)) {
                destroyWebView()
                latch.countDown()
            }
            return Result.failure(IllegalStateException("CHROMIUM_ACTION_TIMEOUT"))
        }
        return outcome.get() ?: Result.failure(IllegalStateException("CHROMIUM_RESULT_MISSING"))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engineThread.quitSafely()
    }

    private inner class BridgeSession(
        private val manifest: SourceManifest,
        private val resources: SourceResourceProvider,
        private val request: SourceActionRequest,
        private val deadlineMs: Long,
    ) {
        var calls: Int = 0
            private set

        fun handle(raw: String): String {
            checkpoint("CHROMIUM_BRIDGE_PARSE_ENTER", "parse-enter", mapOf(
                "rawBytes" to raw.toByteArray(Charsets.UTF_8).size.toString(),
                "remainingMs" to remainingMs().toString(),
            ))
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BRIDGE_BYTES) { "CHROMIUM_BRIDGE_INPUT_TOO_LARGE" }
            require(clockMs() <= deadlineMs) { "CHROMIUM_BRIDGE_TIMEOUT" }
            checkpoint("CHROMIUM_BRIDGE_JSON_START", "json-start")
            val root = JsonCodec.parse(raw, maxDepth = 64, maxNodes = 50_000) as? JsonValue.Obj
                ?: error("CHROMIUM_BRIDGE_REQUEST_OBJECT_REQUIRED")
            checkpoint("CHROMIUM_BRIDGE_JSON_OK", "json-ok", mapOf(
                "objectKeys" to root.values.size.toString(),
            ))
            val operation = root.string("op")?.trim().orEmpty()
            val payload = root.obj("payload") ?: JsonValue.Obj()
            calls += 1
            diagnostics.emit(event(manifest, request, "CHROMIUM_BRIDGE_CALL", DiagnosticSeverity.DEBUG, attributes = mapOf(
                "flow" to "bridge",
                "stage" to operation.take(120),
                "operation" to operation.take(120),
                "bridgeCalls" to calls.toString(),
                "remainingMs" to remainingMs().toString(),
                "requestId" to request.traceId,
            )))
            require(calls <= MAX_BRIDGE_CALLS) { "CHROMIUM_BRIDGE_CALL_LIMIT" }
            checkpoint("CHROMIUM_BRIDGE_DISPATCH_START", "dispatch-start", mapOf(
                "operation" to operation.take(120),
                "bridgeCalls" to calls.toString(),
                "payloadKeys" to payload.values.size.toString(),
            ))
            val value = when (operation) {
                "resource_read" -> resourceRead(payload)
                "host_command" -> hostCommand(payload)
                "network_fetch" -> networkFetch(payload)
                "storage_get" -> storageGet(payload)
                "storage_put" -> storagePut(payload)
                "storage_remove" -> storageRemove(payload)
                "storage_keys" -> storageKeys(payload)
                "storage_clear_prefix" -> storageClearPrefix(payload)
                "cookie_get" -> cookieGet(payload)
                "cookie_set" -> cookieSet(payload)
                "cookie_clear" -> cookieClear()
                "crypto_digest" -> cryptoDigest(payload)
                "crypto_hmac" -> cryptoHmac(payload)
                "crypto_aes" -> cryptoAes(payload)
                "crypto_gcm_encrypt" -> cryptoGcmEncrypt(payload)
                "crypto_gcm_decrypt" -> cryptoGcmDecrypt(payload)
                "browser_action" -> browserAction(payload)
                "websocket_exchange" -> websocketExchange(payload)
                "translate" -> translate(payload)
                "graphics_render" -> graphicsRender(payload)
                "sleep" -> sleep(payload)
                "log" -> log(payload)
                "user_agent" -> JsonValue.Str(WebSettings.getDefaultUserAgent(appContext))
                "native_hook" -> error("CHROMIUM_NATIVE_HOOK_UNAVAILABLE")
                else -> error("CHROMIUM_BRIDGE_OPERATION_DENIED:$operation")
            }
            checkpoint("CHROMIUM_BRIDGE_DISPATCH_OK", "dispatch-ok", mapOf(
                "operation" to operation.take(120),
                "bridgeCalls" to calls.toString(),
                "valueType" to jsonValueType(value),
            ))
            val encoded = bridgeEnvelopeSuccess(value)
            val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size
            checkpoint("CHROMIUM_BRIDGE_RESULT_ENCODED", "result-encoded", mapOf(
                "operation" to operation.take(120),
                "responseBytes" to encodedBytes.toString(),
            ))
            require(encodedBytes <= MAX_BRIDGE_BYTES) { "CHROMIUM_BRIDGE_OUTPUT_TOO_LARGE" }
            return encoded
        }

        private fun checkpoint(
            name: String,
            stage: String,
            attributes: Map<String, String> = emptyMap(),
        ) {
            diagnostics.emit(event(manifest, request, name, DiagnosticSeverity.DEBUG, attributes = attributes + mapOf(
                "flow" to "bridge",
                "stage" to stage,
                "bridgeCalls" to calls.toString(),
                "requestId" to request.traceId,
            )))
        }

        private fun resourceRead(payload: JsonValue.Obj): JsonValue {
            val rawPath = payload.string("path")?.trim().orEmpty()
            val clean = rawPath.replace('\\', '/').removePrefix("/")
            val path = if (clean.startsWith("src/")) clean else "src/$clean"
            SourceManifest.requireSafeRelativePath(path)
            val bytes = resources.read(path, MAX_SCRIPT_BYTES) ?: error("VBOOK_RESOURCE_MISSING:$path")
            return JsonValue.Str(bytes.toString(Charsets.UTF_8))
        }

        private fun hostCommand(payload: JsonValue.Obj): JsonValue = when (val result = SourceHostKernelWireExecutor.execute(
            broker = brokers.hostKernel,
            sourceId = manifest.id,
            rawCommandJson = JsonCodec.stringify(payload),
            traceId = request.traceId,
        )) {
            is SourcePlatformResult.Success -> JsonCodec.parse(result.value, maxDepth = 64, maxNodes = 50_000)
            is SourcePlatformResult.Failure -> error("CHROMIUM_HOST_COMMAND_${result.error.code}:${result.error.message}")
        }

        private fun networkFetch(payload: JsonValue.Obj): JsonValue {
            val url = ChromiumVBookNetworkCompatibility.normalizeUrl(payload.string("url").orEmpty())
            syncBrowserSharedCookies(manifest, url)
            val headers = payload.obj("headers")?.values.orEmpty().mapNotNull { (key, value) ->
                (value as? JsonValue.Str)?.value?.let { key to it }
            }.toMap(LinkedHashMap())
            val requestedTimeout = payload.long("timeoutMs") ?: 0L
            val timeout = when {
                requestedTimeout > 0 -> requestedTimeout.coerceIn(100L, remainingMs())
                else -> remainingMs().coerceIn(100L, 120_000L)
            }
            val result = brokers.network.execute(manifest, SourceNetworkRequest(
                sourceId = manifest.id,
                url = url,
                method = payload.string("method")?.uppercase(Locale.ROOT) ?: "GET",
                headers = headers,
                body = payload.string("body").orEmpty().toByteArray(Charsets.UTF_8),
                contentType = payload.string("contentType")?.takeIf(String::isNotBlank),
                responseMode = SourceNetworkResponseMode.TEXT,
                allowHttpError = true,
                timeoutMs = timeout,
                traceId = request.traceId,
            ))
            val response = when (result) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("CHROMIUM_NETWORK_${result.error.code}:${result.error.message}")
            }
            return JsonValue.Obj(linkedMapOf(
                "status" to number(response.statusCode),
                "url" to JsonValue.Str(response.finalUrl),
                "body" to JsonValue.Str(response.bodyText()),
                "headers" to JsonValue.Obj(response.headers.mapValuesTo(linkedMapOf()) { (_, values) -> JsonValue.Str(values.joinToString(", ")) }),
            ))
        }

        private fun storageGet(payload: JsonValue.Obj): JsonValue = when (val result = brokers.storage.get(
            manifest,
            SourceStorageRequest(manifest.id, payload.string("key").orEmpty(), traceId = request.traceId),
        )) {
            is SourcePlatformResult.Success -> result.value?.toString(Charsets.UTF_8)?.let(JsonValue::Str) ?: JsonValue.Null
            is SourcePlatformResult.Failure -> error(result.error.message)
        }

        private fun storagePut(payload: JsonValue.Obj): JsonValue = when (val result = brokers.storage.put(
            manifest,
            SourceStorageRequest(
                manifest.id,
                payload.string("key").orEmpty(),
                payload.string("value").orEmpty().toByteArray(Charsets.UTF_8),
                request.traceId,
            ),
        )) {
            is SourcePlatformResult.Success -> JsonValue.Bool(true)
            is SourcePlatformResult.Failure -> error(result.error.message)
        }

        private fun storageRemove(payload: JsonValue.Obj): JsonValue = when (val result = brokers.storage.delete(
            manifest,
            SourceStorageRequest(manifest.id, payload.string("key").orEmpty(), traceId = request.traceId),
        )) {
            is SourcePlatformResult.Success -> JsonValue.Bool(true)
            is SourcePlatformResult.Failure -> error(result.error.message)
        }

        private fun storageKeys(payload: JsonValue.Obj): JsonValue = when (val result = brokers.storage.keys(
            manifest,
            manifest.id,
            payload.string("prefix").orEmpty(),
            request.traceId,
        )) {
            is SourcePlatformResult.Success -> JsonValue.Arr(result.value.map(JsonValue::Str))
            is SourcePlatformResult.Failure -> error(result.error.message)
        }

        private fun storageClearPrefix(payload: JsonValue.Obj): JsonValue = when (val result = brokers.storage.clearPrefix(
            manifest,
            manifest.id,
            payload.string("prefix").orEmpty(),
            request.traceId,
        )) {
            is SourcePlatformResult.Success -> JsonValue.Bool(true)
            is SourcePlatformResult.Failure -> error(result.error.message)
        }

        private fun cookieGet(payload: JsonValue.Obj): JsonValue {
            val url = payload.string("url")?.takeIf(String::isNotBlank) ?: manifest.origins.firstOrNull().orEmpty()
            return JsonValue.Str(brokers.cookies.readCookieHeader(manifest.id, url).orEmpty())
        }

        private fun cookieSet(payload: JsonValue.Obj): JsonValue {
            val url = payload.string("url")?.takeIf(String::isNotBlank) ?: manifest.origins.firstOrNull().orEmpty()
            val cookie = payload.string("cookie").orEmpty()
            require(url.startsWith("https://")) { "VBOOK_COOKIE_HTTPS_REQUIRED" }
            if (cookie.isNotBlank()) brokers.cookies.mergeSetCookieHeaders(manifest.id, url, listOf(cookie))
            return JsonValue.Bool(true)
        }

        private fun cookieClear(): JsonValue {
            brokers.cookies.clear(manifest.id)
            return JsonValue.Bool(true)
        }

        private fun cryptoDigest(payload: JsonValue.Obj): JsonValue {
            val operation = digestOperation(payload.string("algorithm").orEmpty())
            val data = decodeBase64(payload.string("dataBase64").orEmpty())
            val bytes = crypto(operation, data)
            return JsonValue.Str(bytes.toHex())
        }

        private fun cryptoHmac(payload: JsonValue.Obj): JsonValue {
            val operation = hmacOperation(payload.string("algorithm").orEmpty())
            val data = decodeBase64(payload.string("dataBase64").orEmpty())
            val key = decodeBase64(payload.string("keyBase64").orEmpty())
            val bytes = crypto(operation, data, key)
            return JsonValue.Str(bytes.toHex())
        }

        private fun crypto(operation: SourceCryptoOperation, data: ByteArray, key: ByteArray? = null): ByteArray = when (val result = brokers.crypto.execute(
            manifest,
            SourceCryptoRequest(manifest.id, operation, data, keyMaterial = key, traceId = request.traceId),
        )) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("CHROMIUM_CRYPTO_${result.error.code}:${result.error.message}")
        }

        private fun cryptoAes(payload: JsonValue.Obj): JsonValue {
            val operation = payload.string("operation")?.lowercase() ?: "decrypt"
            var data = decodeBase64(payload.string("dataBase64").orEmpty())
            require(data.size <= 8 * 1024 * 1024) { "VBOOK_AES_PAYLOAD_TOO_LARGE" }
            val keyType = payload.string("keyType")?.lowercase() ?: "raw"
            val modeName = payload.string("mode")?.uppercase(Locale.ROOT).let { if (it == "ECB") "ECB" else "CBC" }
            val paddingName = if (payload.string("padding").equals("NoPadding", true)) "NoPadding" else "PKCS5Padding"
            val encrypting = operation == "encrypt"
            var salt = ByteArray(0)
            val key: ByteArray
            val iv: ByteArray
            if (keyType == "passphrase") {
                val passphrase = payload.string("passphrase").orEmpty()
                if (encrypting) {
                    salt = ByteArray(8).also(SecureRandom()::nextBytes)
                } else {
                    require(data.size >= 16 && data.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "Salted__") { "VBOOK_AES_OPENSSL_HEADER_REQUIRED" }
                    salt = data.copyOfRange(8, 16)
                    data = data.copyOfRange(16, data.size)
                }
                val derived = deriveOpenSsl(passphrase, salt)
                key = derived.first
                iv = derived.second
            } else {
                key = decodeBase64(payload.string("keyBase64").orEmpty())
                require(key.size in setOf(16, 24, 32)) { "VBOOK_AES_KEY_LENGTH_INVALID" }
                val rawIv = payload.string("ivBase64").orEmpty()
                iv = if (rawIv.isBlank()) ByteArray(16) else decodeBase64(rawIv)
            }
            if (modeName == "CBC") require(iv.size == 16) { "VBOOK_AES_IV_LENGTH_INVALID" }
            if (paddingName == "NoPadding") require(data.size % 16 == 0) { "VBOOK_AES_NOPADDING_BLOCK_SIZE" }
            val cipher = Cipher.getInstance("AES/$modeName/$paddingName")
            val spec = SecretKeySpec(key, "AES")
            if (modeName == "ECB") cipher.init(if (encrypting) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec)
            else cipher.init(if (encrypting) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
            var output = cipher.doFinal(data)
            if (keyType == "passphrase" && encrypting) output = "Salted__".toByteArray(Charsets.US_ASCII) + salt + output
            return JsonValue.Str(Base64.getEncoder().encodeToString(output))
        }

        private fun cryptoGcmEncrypt(payload: JsonValue.Obj): JsonValue = JsonValue.Str(
            Base64.getEncoder().encodeToString(crypto(
                SourceCryptoOperation.AES_GCM_ENCRYPT,
                payload.string("text").orEmpty().toByteArray(Charsets.UTF_8),
            )),
        )

        private fun cryptoGcmDecrypt(payload: JsonValue.Obj): JsonValue = JsonValue.Str(
            crypto(SourceCryptoOperation.AES_GCM_DECRYPT, decodeBase64(payload.string("dataBase64").orEmpty())).toString(Charsets.UTF_8),
        )

        private fun browserAction(payload: JsonValue.Obj): JsonValue {
            val actionName = payload.string("action").orEmpty()
            val action = runCatching { SourceBrowserAction.valueOf(actionName) }.getOrElse { error("CHROMIUM_BROWSER_ACTION_INVALID:$actionName") }
            val timeout = (payload.long("timeoutMs") ?: 0L).takeIf { it > 0 }?.coerceIn(100L, remainingMs())
                ?: remainingMs().coerceIn(100L, 120_000L)
            val options = payload.obj("options")?.stringMap().orEmpty()
            val values = payload.array("values")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
            val response = when (val result = brokers.browser.execute(manifest, SourceBrowserRequest(
                sourceId = manifest.id,
                action = action,
                url = payload.string("url")?.takeIf(String::isNotBlank),
                selector = payload.string("selector")?.takeIf(String::isNotBlank),
                value = payload.string("value"),
                script = payload.string("script")?.takeIf(String::isNotBlank),
                values = values,
                options = options,
                timeoutMs = timeout,
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("CHROMIUM_BROWSER_${result.error.code}:${result.error.message}")
            }
            return JsonValue.Obj(linkedMapOf(
                "value" to response.value?.let(JsonValue::Str).orNull(),
                "finalUrl" to response.finalUrl?.let(JsonValue::Str).orNull(),
                "title" to response.title?.let(JsonValue::Str).orNull(),
                "metadata" to JsonValue.Arr(response.requestMetadata.map { item ->
                    JsonValue.Obj(linkedMapOf(
                        "url" to JsonValue.Str(item.url),
                        "method" to JsonValue.Str(item.method),
                        "mainFrame" to JsonValue.Bool(item.mainFrame),
                        "resourceType" to item.resourceType?.let(JsonValue::Str).orNull(),
                        "headerNames" to JsonValue.Arr(item.headerNames.map(JsonValue::Str)),
                        "timestampEpochMs" to number(item.timestampEpochMs),
                    ))
                }),
                "dialogs" to JsonValue.Arr(response.dialogs.map { dialog ->
                    JsonValue.Obj(linkedMapOf(
                        "id" to number(dialog.id),
                        "type" to JsonValue.Str(dialog.type),
                        "message" to JsonValue.Str(dialog.message),
                        "defaultValue" to dialog.defaultValue?.let(JsonValue::Str).orNull(),
                        "pageUrl" to dialog.pageUrl?.let(JsonValue::Str).orNull(),
                        "accepted" to dialog.accepted?.let(JsonValue::Bool).orNull(),
                        "responseValue" to dialog.responseValue?.let(JsonValue::Str).orNull(),
                    ))
                }),
            ))
        }

        private fun websocketExchange(payload: JsonValue.Obj): JsonValue {
            val messages = payload.array("messages")?.values.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
            val maxResponses = (payload.int("maxResponses") ?: 1).coerceIn(1, 256)
            val response = when (val result = brokers.websocket.exchange(manifest, SourceWebSocketRequest(
                sourceId = manifest.id,
                url = payload.string("url").orEmpty(),
                messages = messages,
                maxResponses = maxResponses,
                timeoutMs = remainingMs().coerceIn(100L, 120_000L),
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("CHROMIUM_WEBSOCKET_${result.error.code}:${result.error.message}")
            }
            return JsonValue.Obj(linkedMapOf(
                "messages" to JsonValue.Arr(response.messages.map(JsonValue::Str)),
                "closeCode" to response.closeCode?.let(::number).orNull(),
                "closeReason" to response.closeReason?.let(JsonValue::Str).orNull(),
            ))
        }

        private fun translate(payload: JsonValue.Obj): JsonValue {
            val response = when (val result = brokers.translation.translate(manifest, SourceTranslationRequest(
                sourceId = manifest.id,
                text = payload.string("text").orEmpty(),
                storyId = payload.string("storyId")?.takeIf(String::isNotBlank),
                chapterId = payload.string("chapterId")?.takeIf(String::isNotBlank),
                sourceLanguage = payload.string("sourceLanguage")?.takeIf(String::isNotBlank),
                targetLanguage = payload.string("targetLanguage")?.takeIf(String::isNotBlank) ?: "vi",
                instruction = payload.string("instruction").orEmpty(),
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("CHROMIUM_TRANSLATION_${result.error.code}:${result.error.message}")
            }
            return JsonValue.Obj(linkedMapOf(
                "translateText" to JsonValue.Str(response.translatedText),
                "segments" to JsonValue.Arr(response.segmentMetadata.map { segment ->
                    JsonValue.Obj(linkedMapOf(
                        "srcStart" to number(segment.srcStart),
                        "srcLen" to number(segment.srcLen),
                        "transStart" to number(segment.transStart),
                        "transLen" to number(segment.transLen),
                        "type" to number(segment.type),
                    ))
                }),
                "provider" to response.provider?.let(JsonValue::Str).orNull(),
            ))
        }

        private fun graphicsRender(payload: JsonValue.Obj): JsonValue {
            val operations = payload.array("operations")?.values.orEmpty().mapNotNull { raw ->
                val obj = raw as? JsonValue.Obj ?: return@mapNotNull null
                SourceGraphicsDrawOperation(
                    imageBase64 = obj.string("imageBase64").orEmpty(),
                    args = obj.array("args")?.values.orEmpty().mapNotNull { (it as? JsonValue.Num)?.raw?.toDoubleOrNull() },
                    alpha = obj.double("alpha") ?: 1.0,
                )
            }
            val response = when (val result = brokers.graphics.render(manifest, SourceGraphicsRequest(
                sourceId = manifest.id,
                width = (payload.int("width") ?: 1).coerceIn(1, 4096),
                height = (payload.int("height") ?: 1).coerceIn(1, 4096),
                operations = operations,
                format = payload.string("format")?.uppercase(Locale.ROOT) ?: "PNG",
                quality = (payload.int("quality") ?: 100).coerceIn(0, 100),
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("CHROMIUM_GRAPHICS_${result.error.code}:${result.error.message}")
            }
            return JsonValue.Str(response)
        }

        private fun sleep(payload: JsonValue.Obj): JsonValue {
            val millis = (payload.long("millis") ?: 0L).coerceIn(0L, minOf(2_000L, remainingMs()))
            if (millis > 0) Thread.sleep(millis)
            return JsonValue.Bool(true)
        }

        private fun log(payload: JsonValue.Obj): JsonValue {
            val severity = when (payload.string("level")?.uppercase(Locale.ROOT)) {
                "ERROR" -> DiagnosticSeverity.ERROR
                "WARN" -> DiagnosticSeverity.WARN
                "DEBUG" -> DiagnosticSeverity.DEBUG
                else -> DiagnosticSeverity.INFO
            }
            diagnostics.emit(event(manifest, request, "CHROMIUM_LOG", severity, attributes = mapOf(
                "message" to payload.string("message").orEmpty().take(2_000),
            )))
            return JsonValue.Bool(true)
        }

        private fun remainingMs(): Long = (deadlineMs - clockMs()).coerceAtLeast(100L)
    }

    private fun syncBrowserSharedCookies(manifest: SourceManifest, url: String) {
        if (manifest.capabilities.cookies != SourceCookieMode.BROWSER_SHARED || !url.startsWith("https://", ignoreCase = true)) return
        val header = webViewCookieReader?.readWebViewCookieHeader(manifest.id, url).orEmpty()
        if (header.isBlank()) return
        val cookies = header.split(';')
            .map(String::trim)
            .filter { it.isNotBlank() && it.contains('=') }
            .take(128)
            .map { "$it; Path=/; Secure; SameSite=Lax" }
        if (cookies.isNotEmpty()) brokers.cookies.mergeSetCookieHeaders(manifest.id, url, cookies)
    }

    private fun deriveOpenSsl(passphrase: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<Byte>(48)
        var previous = ByteArray(0)
        while (output.size < 48) {
            val digest = MessageDigest.getInstance("MD5")
            if (previous.isNotEmpty()) digest.update(previous)
            digest.update(passphrase.toByteArray(Charsets.UTF_8))
            digest.update(salt)
            previous = digest.digest()
            previous.forEach { output += it }
        }
        val all = output.toByteArray()
        return all.copyOfRange(0, 32) to all.copyOfRange(32, 48)
    }

    private fun digestOperation(raw: String): SourceCryptoOperation = when (raw.uppercase(Locale.ROOT).replace("-", "")) {
        "MD5" -> SourceCryptoOperation.MD5
        "SHA1" -> SourceCryptoOperation.SHA1
        "SHA512" -> SourceCryptoOperation.SHA512
        else -> SourceCryptoOperation.SHA256
    }

    private fun hmacOperation(raw: String): SourceCryptoOperation = when (raw.uppercase(Locale.ROOT).replace("-", "")) {
        "HMACMD5" -> SourceCryptoOperation.HMAC_MD5
        "HMACSHA1" -> SourceCryptoOperation.HMAC_SHA1
        "HMACSHA512" -> SourceCryptoOperation.HMAC_SHA512
        else -> SourceCryptoOperation.HMAC_SHA256
    }

    private fun decodeBase64(raw: String): ByteArray = Base64.getMimeDecoder().decode(raw.filterNot(Char::isWhitespace))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun unavailable(message: String, request: SourceActionRequest): SourcePlatformResult.Failure =
        SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE, message, request.traceId))

    private fun failure(code: SourceErrorCode, message: String, request: SourceActionRequest): SourcePlatformResult.Failure =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId))

    private fun event(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        timestampEpochMs = clockMs(),
        traceId = request.traceId,
        sourceId = manifest.id,
        sourceVersion = manifest.version.toString(),
        category = DiagnosticCategory.RUNTIME,
        name = name,
        severity = severity,
        durationMs = durationMs,
        attributes = attributes,
    )

    private fun captureEvidence(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        contentType: String,
        text: String,
    ) {
        if (!evidence.enabled || text.isBlank()) return
        evidence.capture(DiagnosticEvidence(
            timestampEpochMs = clockMs(),
            traceId = request.traceId,
            sourceId = manifest.id,
            category = DiagnosticCategory.RUNTIME,
            name = name,
            contentType = contentType,
            data = text.toByteArray(Charsets.UTF_8),
            attributes = mapOf("engine" to "android-webview-chromium", "action" to request.action.name),
        ))
    }

    companion object {
        private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
        private const val MAX_PROGRAM_BYTES = 8 * 1024 * 1024
        private const val MAX_BRIDGE_BYTES = 8 * 1024 * 1024
        private const val MAX_BRIDGE_CALLS = 20_000
        private const val CALLBACK_GRACE_MS = 2_000L
    }
}

private fun bridgeEnvelopeSuccess(value: JsonValue): String = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
    "ok" to JsonValue.Bool(true),
    "value" to value,
)))

private fun bridgeEnvelopeError(message: String): String = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
    "ok" to JsonValue.Bool(false),
    "error" to JsonValue.Str(message.take(4_000)),
)))

private fun jsonValueType(value: JsonValue): String = when (value) {
    is JsonValue.Obj -> "object"
    is JsonValue.Arr -> "array"
    is JsonValue.Str -> "string"
    is JsonValue.Num -> "number"
    is JsonValue.Bool -> "boolean"
    JsonValue.Null -> "null"
}

private fun JsonValue.Obj.string(name: String): String? = (values[name] as? JsonValue.Str)?.value
private fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = values[name] as? JsonValue.Obj
private fun JsonValue.Obj.array(name: String): JsonValue.Arr? = values[name] as? JsonValue.Arr
private fun JsonValue.Obj.int(name: String): Int? = (values[name] as? JsonValue.Num)?.raw?.toIntOrNull()
private fun JsonValue.Obj.long(name: String): Long? = (values[name] as? JsonValue.Num)?.raw?.toLongOrNull()
private fun JsonValue.Obj.double(name: String): Double? = (values[name] as? JsonValue.Num)?.raw?.toDoubleOrNull()
private fun JsonValue.Obj.bool(name: String): Boolean? = (values[name] as? JsonValue.Bool)?.value
private fun JsonValue.Obj.stringMap(): Map<String, String> = values.mapNotNull { (key, value) ->
    (value as? JsonValue.Str)?.value?.let { key to it }
}.toMap(LinkedHashMap())
private fun number(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())
private fun JsonValue?.orNull(): JsonValue = this ?: JsonValue.Null

internal object ChromiumVBookNetworkCompatibility {
    fun normalizeUrl(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0 || raw.substring(0, schemeEnd).lowercase(Locale.ROOT) !in setOf("http", "https")) return raw
        val authorityStart = schemeEnd + 3
        val pathStart = raw.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        if (pathStart < 0 || raw[pathStart] != '/') return raw
        var duplicateEnd = pathStart
        while (duplicateEnd + 1 < raw.length && raw[duplicateEnd + 1] == '/') duplicateEnd += 1
        return if (duplicateEnd == pathStart) raw else raw.removeRange(pathStart + 1, duplicateEnd + 1)
    }
}
