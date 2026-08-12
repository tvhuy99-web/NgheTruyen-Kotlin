#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str, count: int = 1) -> None:
    file = ROOT / path
    value = file.read_text(encoding="utf-8")
    if old not in value:
        raise SystemExit(f"PATCH_TARGET_MISSING {path}: {old[:120]!r}")
    file.write_text(value.replace(old, new, count), encoding="utf-8")


browser = "app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt"

patch(browser, "import java.util.ArrayDeque\n", "import java.util.ArrayDeque\nimport java.util.UUID\n")
patch(browser, "import java.util.concurrent.atomic.AtomicReference\n", "import java.util.concurrent.atomic.AtomicReference\nimport java.util.concurrent.atomic.AtomicBoolean\n")

patch(
    browser,
    """                val session = ensureSession(manifest, request.url)\n                session.currentTraceId = request.traceId\n                captureBrowserEnvironment(session, request)\n""",
    """                val session = ensureSession(manifest, request.url)\n                if (request.action == SourceBrowserAction.NAVIGATE || request.action == SourceBrowserAction.LOAD_HTML) {\n                    session.navigationGeneration += 1\n                }\n                session.currentTraceId = request.traceId\n                captureBrowserEnvironment(session, request)\n""",
)
patch(
    browser,
    """                    \"requestId\" to request.traceId,\n                    \"url\" to diagnosticUrl(request.url.orEmpty()),\n""",
    """                    \"requestId\" to request.traceId,\n                    \"sessionId\" to session.sessionId,\n                    \"navigationGeneration\" to session.navigationGeneration.toString(),\n                    \"url\" to diagnosticUrl(request.url.orEmpty()),\n""",
)

state_capture = """        evidence.capture(DiagnosticEvidence(\n            timestampEpochMs = clockMs(),\n            traceId = trace,\n            sourceId = request.sourceId,\n            category = DiagnosticCategory.BROWSER,\n            name = \"browser-${reason}-state-${clockMs()}.json\",\n            contentType = \"application/json\",\n            data = JSONObject(stateAttributes).toString(2).toByteArray(Charsets.UTF_8),\n            attributes = mapOf(\"flow\" to \"browser\", \"stage\" to \"page_probe\"),\n        ))\n"""
patch(
    browser,
    state_capture,
    state_capture
    + """        val pageForensicsText = runCatching {\n            evaluate(session, BrowserForensics.pageScript, request.timeoutMs.coerceIn(200L, 3_000L))\n        }.getOrNull().orEmpty()\n        BrowserForensics.parse(pageForensicsText)?.let { pageForensics ->\n            val forensicAttributes = BrowserForensics.summary(\n                pageForensics,\n                session.sessionId,\n                session.navigationGeneration,\n            ).toMutableMap()\n            request.selector?.takeIf(String::isNotBlank)?.let { selector ->\n                forensicAttributes[\"selector\"] = selector.take(1_000)\n                forensicAttributes[\"selectorCount\"] = runCatching {\n                    evaluate(\n                        session,\n                        \"(()=>{try{return document.querySelectorAll(${jsString(selector)}).length}catch(e){return -2}})()\",\n                        request.timeoutMs.coerceIn(100L, 1_500L),\n                    ).toLongOrNull() ?: -1L\n                }.getOrDefault(-1L).toString()\n            }\n            diagnostics.emit(event(\n                session.manifest,\n                request,\n                \"BROWSER_PAGE_FORENSICS\",\n                DiagnosticSeverity.DEBUG,\n                attributes = forensicAttributes,\n            ))\n            evidence.capture(DiagnosticEvidence(\n                timestampEpochMs = clockMs(),\n                traceId = trace,\n                sourceId = request.sourceId,\n                category = DiagnosticCategory.BROWSER,\n                name = \"browser-${reason}-page-forensics-${clockMs()}.json\",\n                contentType = \"application/json\",\n                data = pageForensics.toString(2).toByteArray(Charsets.UTF_8),\n                attributes = mapOf(\"flow\" to \"browser\", \"stage\" to \"page_forensics\"),\n            ))\n        }\n""",
)

patch(
    browser,
    """                return true\n            }\n\n            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {\n""",
    """                return true\n            }\n\n            override fun onProgressChanged(view: WebView, newProgress: Int) {\n                val session = sessionRef.get() ?: return\n                val previous = session.lastProgressLogged\n                if (newProgress == 100 || previous < 0 || kotlin.math.abs(newProgress - previous) >= 10) {\n                    session.lastProgressLogged = newProgress\n                    diagnostics.emit(sessionEvent(manifest, session, \"BROWSER_PROGRESS\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                        \"stage\" to \"progress\",\n                        \"progress\" to newProgress.toString(),\n                        \"previousProgress\" to previous.toString(),\n                    )))\n                }\n            }\n\n            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {\n""",
)

patch(
    browser,
    """                val decision = sessionRef.get()?.recordDialog(\"alert\", message, null, url) ?: DialogDecision(false, null)\n                if (decision.accepted) result.confirm() else result.cancel()\n""",
    """                val session = sessionRef.get()\n                val decision = session?.recordDialog(\"alert\", message, null, url) ?: DialogDecision(false, null)\n                if (session != null) diagnostics.emit(sessionEvent(manifest, session, \"BROWSER_JS_DIALOG\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                    \"stage\" to \"js_dialog\",\n                    \"dialogType\" to \"alert\",\n                    \"accepted\" to decision.accepted.toString(),\n                    \"message\" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),\n                    \"url\" to diagnosticUrl(url),\n                )))\n                if (decision.accepted) result.confirm() else result.cancel()\n""",
)
patch(
    browser,
    """                val decision = sessionRef.get()?.recordDialog(\"confirm\", message, null, url) ?: DialogDecision(false, null)\n                if (decision.accepted) result.confirm() else result.cancel()\n""",
    """                val session = sessionRef.get()\n                val decision = session?.recordDialog(\"confirm\", message, null, url) ?: DialogDecision(false, null)\n                if (session != null) diagnostics.emit(sessionEvent(manifest, session, \"BROWSER_JS_DIALOG\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                    \"stage\" to \"js_dialog\",\n                    \"dialogType\" to \"confirm\",\n                    \"accepted\" to decision.accepted.toString(),\n                    \"message\" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),\n                    \"url\" to diagnosticUrl(url),\n                )))\n                if (decision.accepted) result.confirm() else result.cancel()\n""",
)
patch(
    browser,
    """                val decision = sessionRef.get()?.recordDialog(\"prompt\", message, defaultValue, url) ?: DialogDecision(false, null)\n                if (decision.accepted) result.confirm(decision.value ?: defaultValue.orEmpty()) else result.cancel()\n""",
    """                val session = sessionRef.get()\n                val decision = session?.recordDialog(\"prompt\", message, defaultValue, url) ?: DialogDecision(false, null)\n                if (session != null) diagnostics.emit(sessionEvent(manifest, session, \"BROWSER_JS_DIALOG\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                    \"stage\" to \"js_dialog\",\n                    \"dialogType\" to \"prompt\",\n                    \"accepted\" to decision.accepted.toString(),\n                    \"message\" to vn.nghetruyen.source.diagnostics.DiagnosticRedactor.redactLongText(message, 500),\n                    \"url\" to diagnosticUrl(url),\n                )))\n                if (decision.accepted) result.confirm(decision.value ?: defaultValue.orEmpty()) else result.cancel()\n""",
)

patch(
    browser,
    """            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {\n""",
    """            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {\n                sessionRef.get()?.apply {\n                    diagnostics.emit(sessionEvent(\n                        manifest,\n                        this,\n                        \"BROWSER_HTTP_ERROR\",\n                        if (request.isForMainFrame) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARN,\n                        DiagnosticCategory.NETWORK,\n                        mapOf(\n                            \"stage\" to \"http_response\",\n                            \"statusCode\" to errorResponse.statusCode.toString(),\n                            \"reasonPhrase\" to errorResponse.reasonPhrase.orEmpty().take(300),\n                            \"mimeType\" to errorResponse.mimeType.orEmpty().take(200),\n                            \"encoding\" to errorResponse.encoding.orEmpty().take(100),\n                            \"responseHeaderNames\" to errorResponse.responseHeaders?.keys?.sorted()?.take(64)?.joinToString(\",\").orEmpty(),\n                            \"url\" to diagnosticUrl(request.url.toString()),\n                            \"mainFrame\" to request.isForMainFrame.toString(),\n                        ),\n                    ))\n                }\n            }\n\n            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {\n""",
)

patch(
    browser,
    """    private fun evaluate(session: Session, expression: String, timeoutMs: Long): String {\n        val latch = CountDownLatch(1)\n        val output = AtomicReference<String?>()\n        val error = AtomicReference<Throwable?>()\n        main.post {\n            try {\n                session.webView.evaluateJavascript(expression, ValueCallback { raw ->\n                    output.set(decodeJavascriptResult(raw))\n                    latch.countDown()\n                })\n            } catch (t: Throwable) {\n                error.set(t)\n                latch.countDown()\n            }\n        }\n        if (!latch.await(timeoutMs.coerceAtMost(120_000), TimeUnit.MILLISECONDS)) error(\"SOURCE_BROWSER_TIMEOUT\")\n        error.get()?.let { throw it }\n        return output.get().orEmpty()\n    }\n""",
    """    private fun evaluate(session: Session, expression: String, timeoutMs: Long): String {\n        val startedAt = clockMs()\n        val boundedTimeout = timeoutMs.coerceAtMost(120_000)\n        val latch = CountDownLatch(1)\n        val output = AtomicReference<String?>()\n        val error = AtomicReference<Throwable?>()\n        val timedOut = AtomicBoolean(false)\n        diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_STARTED\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n            \"stage\" to \"evaluate\",\n            \"timeoutMs\" to boundedTimeout.toString(),\n        )))\n        main.post {\n            try {\n                session.webView.evaluateJavascript(expression, ValueCallback { raw ->\n                    val decoded = decodeJavascriptResult(raw)\n                    output.set(decoded)\n                    val late = timedOut.get()\n                    if (late) session.lateCallbacks += 1\n                    diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_CALLBACK\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                        \"stage\" to \"evaluate_callback\",\n                        \"late\" to late.toString(),\n                        \"lateCallbacks\" to session.lateCallbacks.toString(),\n                        \"outputBytes\" to decoded.toByteArray(Charsets.UTF_8).size.toString(),\n                        \"elapsedMs\" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),\n                    )))\n                    latch.countDown()\n                })\n            } catch (t: Throwable) {\n                error.set(t)\n                diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_ERROR\", DiagnosticSeverity.WARN, attributes = mapOf(\n                    \"stage\" to \"evaluate\",\n                    \"error\" to (t.message ?: t.javaClass.simpleName).take(500),\n                    \"elapsedMs\" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),\n                )))\n                latch.countDown()\n            }\n        }\n        if (!latch.await(boundedTimeout, TimeUnit.MILLISECONDS)) {\n            timedOut.set(true)\n            diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_TIMEOUT\", DiagnosticSeverity.WARN, attributes = mapOf(\n                \"stage\" to \"evaluate\",\n                \"timeoutMs\" to boundedTimeout.toString(),\n                \"elapsedMs\" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),\n            )))\n            error(\"SOURCE_BROWSER_TIMEOUT\")\n        }\n        error.get()?.let { throw it }\n        return output.get().orEmpty().also { decoded ->\n            diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_COMPLETED\", DiagnosticSeverity.DEBUG, attributes = mapOf(\n                \"stage\" to \"evaluate\",\n                \"elapsedMs\" to (clockMs() - startedAt).coerceAtLeast(0L).toString(),\n                \"outputBytes\" to decoded.toByteArray(Charsets.UTF_8).size.toString(),\n            )))\n        }\n    }\n""",
)

patch(
    browser,
    """    private fun sessionEvent(\n        manifest: SourceManifest,\n        session: Session,\n        name: String,\n        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n        category: DiagnosticCategory = DiagnosticCategory.BROWSER,\n        attributes: Map<String, String> = emptyMap(),\n    ) = DiagnosticEvent(\n        clockMs(),\n        session.currentTraceId.ifBlank { \"browser-session:${manifest.id}\" },\n        manifest.id,\n        manifest.version.toString(),\n        category,\n        name,\n        severity,\n        null,\n        attributes,\n    )\n""",
    """    private fun sessionEvent(\n        manifest: SourceManifest,\n        session: Session,\n        name: String,\n        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n        category: DiagnosticCategory = DiagnosticCategory.BROWSER,\n        attributes: Map<String, String> = emptyMap(),\n    ): DiagnosticEvent {\n        val base = mapOf(\n            \"flow\" to \"browser\",\n            \"sessionId\" to session.sessionId,\n            \"navigationGeneration\" to session.navigationGeneration.toString(),\n            \"loaded\" to (session.pageFinishedCount > 0 && session.pendingError.get().isNullOrBlank()).toString(),\n            \"currentUrl\" to diagnosticUrl(session.logicalPageUrl.orEmpty()),\n        )\n        return DiagnosticEvent(\n            clockMs(),\n            session.currentTraceId.ifBlank { \"browser-session:${manifest.id}\" },\n            manifest.id,\n            manifest.version.toString(),\n            category,\n            name,\n            severity,\n            null,\n            base + attributes,\n        )\n    }\n""",
)

patch(
    browser,
    """    private class Session(\n        val manifest: SourceManifest,\n        val webView: WebView,\n    ) {\n        val metadata = ArrayDeque<SourceBrowserRequestMetadata>()\n""",
    """    private class Session(\n        val manifest: SourceManifest,\n        val webView: WebView,\n    ) {\n        val sessionId: String = \"browser:${manifest.id}:${UUID.randomUUID().toString().take(12)}\"\n        val metadata = ArrayDeque<SourceBrowserRequestMetadata>()\n""",
)
patch(
    browser,
    """        @Volatile var pageFinishedCount: Int = 0\n\n        fun record(request: WebResourceRequest, resourceType: String?) {\n""",
    """        @Volatile var pageFinishedCount: Int = 0\n        @Volatile var navigationGeneration: Long = 0\n        @Volatile var lastProgressLogged: Int = -1\n\n        fun record(request: WebResourceRequest, resourceType: String?) {\n""",
)

deep = "app/src/main/java/vn/nghetruyen/app/sourceplatform/DiagnosticDeepBlackBox.kt"
patch(
    deep,
    """                    put(\"traceId\", last.traceId)\n                    put(\"sourceId\", last.sourceId)\n                    put(\"startedAtEpochMs\", sorted.first().timestampEpochMs)\n""",
    """                    put(\"traceId\", last.traceId)\n                    put(\"sourceId\", last.sourceId)\n                    put(\"sessionId\", latest(sorted, \"sessionId\"))\n                    put(\"navigationGeneration\", latest(sorted, \"navigationGeneration\"))\n                    put(\"startedAtEpochMs\", sorted.first().timestampEpochMs)\n""",
)
patch(
    deep,
    """                    put(\"selectorProbeCount\", sorted.count { it.name.contains(\"SELECTOR_PROBE\", true) })\n                    put(\"rendererGone\", sorted.any { it.name.contains(\"RENDERER_GONE\", true) })\n                    put(\"lastError\", lastError?.let(::detail).orEmpty())\n                    put(\"environment\", JSONObject(DiagnosticRedactor.redact(environment)))\n""",
    """                    put(\"selectorProbeCount\", sorted.count { it.name.contains(\"SELECTOR_PROBE\", true) })\n                    put(\"evaluationCount\", sorted.count { it.name == \"BROWSER_EVAL_STARTED\" })\n                    put(\"evaluationTimeoutCount\", sorted.count { it.name == \"BROWSER_EVAL_TIMEOUT\" })\n                    put(\"httpErrorCount\", sorted.count { it.name == \"BROWSER_HTTP_ERROR\" })\n                    put(\"dialogCount\", sorted.count { it.name == \"BROWSER_JS_DIALOG\" })\n                    put(\"progress\", latest(sorted, \"progress\"))\n                    put(\"rendererGone\", sorted.any { it.name.contains(\"RENDERER_GONE\", true) })\n                    put(\"urlHistory\", JSONArray(sorted.mapNotNull { it.attributes[\"url\"] ?: it.attributes[\"currentUrl\"] }.filter { it.isNotBlank() && it != \"[redacted-url]\" }.distinct().takeLast(50)))\n                    put(\"lastError\", lastError?.let(::detail).orEmpty())\n                    put(\"environment\", JSONObject(DiagnosticRedactor.redact(environment)))\n                    val pageForensics = sorted.lastOrNull { it.name == \"BROWSER_PAGE_FORENSICS\" }?.attributes.orEmpty()\n                    put(\"pageForensics\", JSONObject(DiagnosticRedactor.redact(pageForensics)))\n""",
)

gate = "scripts/check_lua_deep_diagnostics_parity.py"
patch(
    gate,
    """    \"browser page state snapshot\": all(token in browser for token in (\n""",
    """    \"Lua JS compatibility matrix and safe page forensics\": all(token in (browser + text(\"app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt\")) for token in (\n        \"BROWSER_PAGE_FORENSICS\",\n        \"XMLHttpRequest\",\n        \"WebSocket\",\n        \"cryptoSubtle\",\n        \"TextEncoder\",\n        \"TextDecoder\",\n        \"URLSearchParams\",\n        \"IndexedDB\",\n        \"WebAssembly\",\n        \"MutationObserver\",\n        \"IntersectionObserver\",\n        \"ResizeObserver\",\n        \"AbortController\",\n        \"structuredClone\",\n        \"recentResources\",\n        \"mutationAgeMs\",\n        \"htmlLength\",\n        \"textLength\",\n        \"elementCount\",\n    )),\n    \"page forensics excludes resource URLs and storage values\": (\n        'name:String(e.name' not in text(\"app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt\")\n        and \"localStorageKeys\" not in text(\"app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt\")\n        and \"sessionStorageKeys\" not in text(\"app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt\")\n        and \"cookieNames\" not in text(\"app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt\")\n    ),\n    \"browser HTTP/progress/dialog timeline\": all(token in browser for token in (\n        \"BROWSER_HTTP_ERROR\",\n        \"onReceivedHttpError\",\n        \"BROWSER_PROGRESS\",\n        \"onProgressChanged\",\n        \"BROWSER_JS_DIALOG\",\n    )),\n    \"browser evaluate forensic timeline\": all(token in browser for token in (\n        \"BROWSER_EVAL_STARTED\",\n        \"BROWSER_EVAL_CALLBACK\",\n        \"BROWSER_EVAL_COMPLETED\",\n        \"BROWSER_EVAL_TIMEOUT\",\n        \"BROWSER_EVAL_ERROR\",\n        \"AtomicBoolean\",\n    )),\n    \"browser session generation metadata\": all(token in browser for token in (\n        \"sessionId\",\n        \"navigationGeneration\",\n        \"UUID.randomUUID\",\n    )),\n    \"browser page state snapshot\": all(token in browser for token in (\n""",
)

print("DEEP_BROWSER_FORENSICS_PATCH=APPLIED")
