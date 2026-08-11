#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, value: str) -> None:
    (ROOT / path).write_text(value, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    value = read(path)
    if new in value:
        return
    if old not in value:
        raise SystemExit(f"missing Stage D anchor in {path}: {old[:180]!r}")
    write(path, value.replace(old, new, 1))


# 1) The diagnostic browser's local log level must never suppress the shared black box.
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt"
replace_once(
    path,
    '''                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (logLevel >= 2) {
                        record("CONSOLE", "${message.messageLevel()}@${message.lineNumber()}", sanitize(message.message(), 800))
                    }
                    return true
                }
''',
    '''                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    record("CONSOLE", "${message.messageLevel()}@${message.lineNumber()}", sanitize(message.message(), 800))
                    return true
                }
''',
)
replace_once(
    path,
    '''            if (logLevel >= 1) record("NAV", "NAVIGATION", redactUrl(target))
''',
    '''            record("NAV", "NAVIGATION", redactUrl(target))
''',
)
replace_once(
    path,
    '''            if (logLevel >= 1) record("PAGE", "START", redactUrl(url))
''',
    '''            record("PAGE", "START", redactUrl(url))
''',
)
replace_once(
    path,
    '''            if (logLevel >= 1) record("PAGE", "FINISH", redactUrl(url))
''',
    '''            record("PAGE", "FINISH", redactUrl(url))
''',
)
replace_once(
    path,
    '''            if (logLevel >= 2) {
                record(
                    "REQUEST",
                    request.method,
                    "${redactUrl(request.url.toString())} main=${request.isForMainFrame} headers=${request.requestHeaders.keys.sorted().joinToString()}",
                )
            }
''',
    '''            record(
                "REQUEST",
                request.method,
                "${redactUrl(request.url.toString())} main=${request.isForMainFrame} headers=${request.requestHeaders.keys.sorted().joinToString()}",
            )
''',
)
replace_once(
    path,
    '''            if (logLevel >= 1) {
                record(
                    "ERROR",
                    "WEB_${error.errorCode}",
                    "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())} desc=${sanitize(error.description.toString(), 300)}",
                )
            }
''',
    '''            record(
                "ERROR",
                "WEB_${error.errorCode}",
                "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())} desc=${sanitize(error.description.toString(), 300)}",
            )
''',
)
replace_once(
    path,
    '''            if (logLevel >= 1) {
                record("HTTP", "HTTP_${errorResponse.statusCode}", "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())}")
            }
''',
    '''            record("HTTP", "HTTP_${errorResponse.statusCode}", "main=${request.isForMainFrame} url=${redactUrl(request.url.toString())}")
''',
)
replace_once(
    path,
    '''        if (logLevel >= 1) record("NAV", "LOAD_URL", redactUrl(target))
''',
    '''        record("NAV", "LOAD_URL", redactUrl(target))
''',
)
replace_once(
    path,
    '''    private fun record(level: String, category: String, detail: String) {
        val safeDetail = sanitize(detail, 2_000)
        mirrorGlobal(level, category, safeDetail)
        if (logLevel == 0 && level !in setOf("SECURITY", "PROBE")) return
        entries.addLast(DiagnosticEntry(System.currentTimeMillis(), level, category, safeDetail))
        while (entries.size > MAX_LOG_ENTRIES) entries.removeFirst()
    }
''',
    '''    private fun record(level: String, category: String, detail: String) {
        val safeDetail = sanitize(detail, 2_000)
        mirrorGlobal(level, category, safeDetail)
        val normalized = level.uppercase(Locale.ROOT)
        val keepLocal = when (normalized) {
            "SECURITY", "PROBE" -> true
            "REQUEST", "CONSOLE" -> logLevel >= 2
            else -> logLevel >= 1
        }
        if (!keepLocal) return
        entries.addLast(DiagnosticEntry(System.currentTimeMillis(), level, category, safeDetail))
        while (entries.size > MAX_LOG_ENTRIES) entries.removeFirst()
    }
''',
)

# 2) The real login browser logs every request in Advanced, samples only when not Advanced, and
# handles renderer death rather than leaving Android to kill the process/activity unpredictably.
path = "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt"
replace_once(
    path,
    '''                    if (requestCount <= 20 || requestCount % 25 == 0) {
''',
    '''                    if (diagnostics.advanced || requestCount <= 20 || requestCount % 25 == 0) {
''',
)
replace_once(
    path,
    '''                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    diagnostic(
                        name = "SOURCE_LOGIN_RENDERER_GONE",
                        severity = DiagnosticSeverity.ERROR,
                        attributes = mapOf("didCrash" to detail.didCrash().toString(), "requestCount" to requestCount.toString()),
                    )
                    return false
                }
''',
    '''                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    diagnostic(
                        name = "SOURCE_LOGIN_RENDERER_GONE",
                        severity = DiagnosticSeverity.ERROR,
                        attributes = mapOf("didCrash" to detail.didCrash().toString(), "requestCount" to requestCount.toString()),
                    )
                    status.text = "Tiến trình WebView đã dừng. Nhật ký đã ghi lại sự cố."
                    runCatching { view.destroy() }
                    finish()
                    return true
                }
''',
)
replace_once(
    path,
    '''        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
''',
    '''        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
''',
)

# 3) Never persist a raw console source URL in Advanced evidence.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt"
replace_once(
    path,
    '''                    data = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} $message".toByteArray(Charsets.UTF_8),
''',
    '''                    data = "${diagnosticUrl(consoleMessage.sourceId().orEmpty())}:${consoleMessage.lineNumber()} $message".toByteArray(Charsets.UTF_8),
''',
)

# 4) Model discovery is a real diagnostic operation, including HTTP status and bounded Advanced
# response evidence, so the dashboard's recording label is truthful during model refresh.
path = "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt"
start = read(path).index("    suspend fun listModels(\n")
end = read(path).index("\n    suspend fun listGeminiModels", start)
value = read(path)
old_block = value[start:end]
new_block = r'''    suspend fun listModels(
        provider: AiProvider,
        endpoint: String,
        apiKeyOverride: String? = null,
    ): AppResult<List<String>> = withContext(Dispatchers.IO) {
        val traceId = "ai-models:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        diagnostic(
            traceId,
            "AI_MODEL_DISCOVERY_STARTED",
            DiagnosticSeverity.INFO,
            attributes = mapOf("provider" to provider.name),
        )
        fun modelFailure(code: String, message: String, cause: Throwable? = null): AppResult.Failure {
            diagnostic(
                traceId,
                "AI_MODEL_DISCOVERY_FAILED",
                DiagnosticSeverity.WARN,
                durationMs = System.currentTimeMillis() - startedAt,
                attributes = mapOf("provider" to provider.name, "code" to code, "message" to message.take(500), "cause" to (cause?.javaClass?.simpleName ?: "")),
            )
            return failure(code, message, cause)
        }

        val apiKey = apiKeyOverride?.trim()?.takeIf(String::isNotBlank)
            ?: credentialStore.apiKey(provider)?.trim()?.takeIf(String::isNotBlank)
        val request = when (provider) {
            AiProvider.GEMINI -> {
                val geminiKey = apiKey
                    ?: return@withContext modelFailure("AI_KEY_MISSING", "Chưa lưu API key cho ${providerLabel(provider)}.")
                Request.Builder()
                    .url("$GEMINI_API_BASE/models?pageSize=100")
                    .header("Accept", "application/json")
                    .header("x-goog-api-key", geminiKey)
                    .get()
                    .build()
            }
            AiProvider.OPENAI_COMPATIBLE -> {
                val chatEndpoint = endpoint.trim().ifBlank { settingsRepository.snapshot().aiOnline.endpoint }
                AiEndpointPolicy.validate(chatEndpoint).exceptionOrNull()?.let {
                    return@withContext modelFailure("AI_ENDPOINT_INVALID", it.message ?: "Endpoint AI không hợp lệ.", it)
                }
                val base = chatEndpoint.trimEnd('/')
                    .removeSuffix("/chat/completions")
                    .removeSuffix("/responses")
                    .trimEnd('/')
                Request.Builder()
                    .url("$base/models")
                    .header("Accept", "application/json")
                    .apply {
                        apiKey?.let { header("Authorization", "Bearer $it") }
                    }
                    .get()
                    .build()
            }
        }
        val endpointForLog = diagnosticEndpoint(request.url.toString())
        diagnostic(
            traceId,
            "AI_MODEL_DISCOVERY_HTTP_STARTED",
            DiagnosticSeverity.INFO,
            DiagnosticCategory.NETWORK,
            attributes = mapOf("provider" to provider.name, "endpoint" to endpointForLog),
        )
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.charStream()?.use { reader ->
                    buildString {
                        val buffer = CharArray(8_192)
                        while (length <= MAX_MODEL_LIST_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_MODEL_LIST_CHARS + 1 - length))
                            if (count < 0) break
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                diagnostic(
                    traceId,
                    "AI_MODEL_DISCOVERY_HTTP_RESPONSE",
                    if (response.isSuccessful) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                    DiagnosticCategory.NETWORK,
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf(
                        "provider" to provider.name,
                        "endpoint" to endpointForLog,
                        "status" to response.code.toString(),
                        "responseChars" to raw.length.toString(),
                    ),
                )
                captureAiEvidence(
                    traceId,
                    "model_discovery",
                    "response-http${response.code}.json",
                    raw,
                    mapOf("provider" to provider.name, "endpoint" to endpointForLog, "status" to response.code.toString()),
                )
                if (response.isRedirect) return@withContext modelFailure("AI_REDIRECT_BLOCKED", "Models API trả redirect.")
                if (raw.length > MAX_MODEL_LIST_CHARS) {
                    return@withContext modelFailure("AI_RESPONSE_TOO_LARGE", "Danh sách model vượt giới hạn an toàn.")
                }
                if (!response.isSuccessful) {
                    return@withContext modelFailure(
                        "AI_HTTP_${response.code}",
                        extractError(provider, raw)?.take(400) ?: "Models API trả lỗi HTTP ${response.code}.",
                    )
                }
                val root = JSONObject(raw)
                val models = when (provider) {
                    AiProvider.GEMINI -> {
                        val array = root.optJSONArray("models") ?: JSONArray()
                        buildList {
                            for (index in 0 until array.length()) {
                                val item = array.optJSONObject(index) ?: continue
                                val methods = item.optJSONArray("supportedGenerationMethods")
                                val supportsGenerateContent = methods == null || (0 until methods.length()).any {
                                    methods.optString(it) == "generateContent"
                                }
                                val name = item.optString("name").removePrefix("models/").trim()
                                if (supportsGenerateContent && isSuitableGeminiTextModel(name)) add(name)
                            }
                        }
                    }
                    AiProvider.OPENAI_COMPATIBLE -> {
                        val array = root.optJSONArray("data") ?: JSONArray()
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optJSONObject(index)?.optString("id")?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                }.distinct().sorted()
                if (models.isEmpty()) {
                    modelFailure("AI_MODELS_EMPTY", "API không trả model phù hợp.")
                } else {
                    diagnostic(
                        traceId,
                        "AI_MODEL_DISCOVERY_COMPLETED",
                        DiagnosticSeverity.INFO,
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("provider" to provider.name, "models" to models.size.toString()),
                    )
                    AppResult.Success(models)
                }
            }
        } catch (error: IOException) {
            modelFailure("AI_NETWORK_ERROR", error.message ?: "Không tải được danh sách model.", error)
        } catch (error: Exception) {
            modelFailure("AI_BAD_RESPONSE", error.message ?: "Không đọc được danh sách model.", error)
        }
    }
'''
if old_block != new_block:
    write(path, value[:start] + new_block + value[end:])

# 5) Add one more layer of VietPhrase reproducibility: rule-set identity/counts and explicit
# multi-meaning statistics, without recording dictionary values in the shared event stream.
path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseModels.kt"
replace_once(
    path,
    '''    val aiReplaceSelections: Int = 0,
    val probes: List<VietPhraseProbeEntry> = emptyList(),
''',
    '''    val aiReplaceSelections: Int = 0,
    val multiMeaningSelections: Int = 0,
    val probes: List<VietPhraseProbeEntry> = emptyList(),
''',
)

path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt"
replace_once(
    path,
    '''        var aiReplaceSelections = 0
        private val probes = ArrayList<VietPhraseProbeEntry>()
''',
    '''        var aiReplaceSelections = 0
        var multiMeaningSelections = 0
        private val probes = ArrayList<VietPhraseProbeEntry>()
''',
)
replace_once(
    path,
    '''            aiReplaceSelections = aiReplaceSelections,
            probes = probes.toList(),
''',
    '''            aiReplaceSelections = aiReplaceSelections,
            multiMeaningSelections = multiMeaningSelections,
            probes = probes.toList(),
''',
)
# Count meaning alternatives at the selected-rule sites without changing translation behavior.
replace_once(
    path,
    '''                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)
                appendSmart(base, replacement)
''',
    '''                if (meaningCount(direct.replacement, direct.rule.kind) > 1) diagnostics.multiMeaningSelections += 1
                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)
                appendSmart(base, replacement)
''',
)
replace_once(
    path,
    '''                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)
                    appendSmart(base, replacement)
''',
    '''                    if (meaningCount(fallback.replacement, fallback.rule.kind) > 1) diagnostics.multiMeaningSelections += 1
                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)
                    appendSmart(base, replacement)
''',
)
replace_once(
    path,
    '''    private fun resolveMeaning(raw: String, kind: VietPhraseDictionaryKind, oneMeaning: Boolean): String {
        val decoded = raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n").replace("\\t", "\t")
            .replace(Regex("<[bB][rR]\\s*/?>"), "\n").replace("&nbsp;", " ")
        val hasDictionaryMetadata = decoded.contains("Hán Việt:") || decoded.contains("✚[")
        val numberedLines = decoded.lineSequence().mapNotNull { line ->
            NUMBERED_MEANING.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.let(::cleanMeaning)
        }.filter(String::isNotBlank).toList()
        val meanings = when {
            numberedLines.isNotEmpty() -> numberedLines
            hasDictionaryMetadata -> decoded.lineSequence().map { cleanMeaning(it) }.filter { it.isNotBlank() && !it.contains("Hán Việt:") && !it.contains("✚[") }.toList()
            kind == VietPhraseDictionaryKind.LAC_VIET && decoded.contains('\n') -> decoded.lineSequence().map(::cleanMeaning).filter(String::isNotBlank).toList()
            else -> decoded.split('/', '|').map(::cleanMeaning).filter(String::isNotBlank)
        }
        if (meanings.isEmpty()) return cleanMeaning(decoded)
        return if (oneMeaning) meanings.first() else meanings.take(4).joinToString(" / ")
    }
''',
    '''    private fun resolveMeaning(raw: String, kind: VietPhraseDictionaryKind, oneMeaning: Boolean): String {
        val meanings = meaningCandidates(raw, kind)
        if (meanings.isEmpty()) return cleanMeaning(decodeMeaningText(raw))
        return if (oneMeaning) meanings.first() else meanings.take(4).joinToString(" / ")
    }

    private fun meaningCount(raw: String, kind: VietPhraseDictionaryKind): Int = meaningCandidates(raw, kind).size

    private fun meaningCandidates(raw: String, kind: VietPhraseDictionaryKind): List<String> {
        val decoded = decodeMeaningText(raw)
        val hasDictionaryMetadata = decoded.contains("Hán Việt:") || decoded.contains("✚[")
        val numberedLines = decoded.lineSequence().mapNotNull { line ->
            NUMBERED_MEANING.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.let(::cleanMeaning)
        }.filter(String::isNotBlank).toList()
        return when {
            numberedLines.isNotEmpty() -> numberedLines
            hasDictionaryMetadata -> decoded.lineSequence().map { cleanMeaning(it) }.filter { it.isNotBlank() && !it.contains("Hán Việt:") && !it.contains("✚[") }.toList()
            kind == VietPhraseDictionaryKind.LAC_VIET && decoded.contains('\n') -> decoded.lineSequence().map(::cleanMeaning).filter(String::isNotBlank).toList()
            else -> decoded.split('/', '|').map(::cleanMeaning).filter(String::isNotBlank)
        }
    }

    private fun decodeMeaningText(raw: String): String = raw
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .replace("\\t", "\t")
        .replace(Regex("<[bB][rR]\\s*/?>"), "\n")
        .replace("&nbsp;", " ")
''',
)

path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt"
replace_once(
    path,
    '''            appendLine("ai_replace_selections=${stats.aiReplaceSelections}")
            appendLine()
''',
    '''            appendLine("ai_replace_selections=${stats.aiReplaceSelections}")
            appendLine("multi_meaning_selections=${stats.multiMeaningSelections}")
            appendLine("rule_count=${rules.size}")
            appendLine("rule_kinds=${rules.groupingBy { it.kind.fileName }.eachCount().toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}")
            appendLine()
''',
)
replace_once(
    path,
    '''            .put("aiReplaceSelections", stats.aiReplaceSelections)
            .put("probeCount", stats.probes.size)
''',
    '''            .put("aiReplaceSelections", stats.aiReplaceSelections)
            .put("multiMeaningSelections", stats.multiMeaningSelections)
            .put("ruleCount", rules.size)
            .put("ruleKinds", JSONObject(rules.groupingBy { it.kind.fileName }.eachCount()))
            .put("probeCount", stats.probes.size)
''',
)
replace_once(
    path,
    '''                "aiReplaceSelections" to stats.aiReplaceSelections.toString(),
            ),
''',
    '''                "aiReplaceSelections" to stats.aiReplaceSelections.toString(),
                "multiMeaningSelections" to stats.multiMeaningSelections.toString(),
                "ruleCount" to rules.size.toString(),
            ),
''',
)

# 6) Extend the regression gate for the final hardening.
path = "scripts/check_lua_diagnostics_ui_parity.py"
replace_once(
    path,
    '''    "AI Advanced request response evidence": "captureAiEvidence" in ai_text and "DiagnosticEvidence" in ai_text,
''',
    '''    "AI Advanced request response evidence": "captureAiEvidence" in ai_text and "DiagnosticEvidence" in ai_text,
    "AI model discovery operation": all(marker in ai_text for marker in (
        "AI_MODEL_DISCOVERY_STARTED",
        "AI_MODEL_DISCOVERY_HTTP_RESPONSE",
        "AI_MODEL_DISCOVERY_COMPLETED",
        "AI_MODEL_DISCOVERY_FAILED",
    )),
''',
)
replace_once(
    path,
    '''    "diagnostic browser mirrors global trace": "mirrorGlobal" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STARTED" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STOPPED" in diagnostic_browser,
''',
    '''    "diagnostic browser mirrors global trace": "mirrorGlobal" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STARTED" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STOPPED" in diagnostic_browser,
    "diagnostic browser global log independent of local level": all(token not in diagnostic_browser for token in (
        'if (logLevel >= 1) record("NAV"',
        'if (logLevel >= 1) record("PAGE"',
        'if (logLevel >= 2) {\\n                record(\\n                    "REQUEST"',
    )) and "val keepLocal = when" in diagnostic_browser,
    "login renderer handled": "SOURCE_LOGIN_RENDERER_GONE" in login and "return true" in login and "diagnostics.advanced || requestCount" in login,
''',
)
replace_once(
    path,
    '''    "VietPhrase rich diagnostic bundle": all(token in vp_export for token in ("engine_stats.json", "probes.tsv", "VIETPHRASE_ENGINE_STATS", "vietphrase-source.txt")),
''',
    '''    "VietPhrase rich diagnostic bundle": all(token in vp_export for token in ("engine_stats.json", "probes.tsv", "VIETPHRASE_ENGINE_STATS", "vietphrase-source.txt", "multi_meaning_selections", "rule_count")),
''',
)
replace_once(
    path,
    '''    "crash-safe text evidence redacted on disk": "redactEvidenceForDisk" in runtime and "redactHtmlPreservingStructure" in runtime,
''',
    '''    "crash-safe text evidence redacted on disk": "redactEvidenceForDisk" in runtime and "redactHtmlPreservingStructure" in runtime,
    "browser console evidence URL sanitized": "diagnosticUrl(consoleMessage.sourceId().orEmpty())" in browser,
''',
)

print("DIAGNOSTICS_DEEPENING_STAGE_D=APPLIED")
