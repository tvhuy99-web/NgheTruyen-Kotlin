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
        raise SystemExit(f"missing migration anchor in {path}: {old[:160]!r}")
    write(path, value.replace(old, new, 1))


# Keep the root operation alive while nested HTTP/browser/bridge stages share the same trace.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
replace_once(
    path,
    '''        when {
            isStart(name) -> {
                active[traceId] = DiagnosticActiveOperation(
                    traceId = traceId,
                    sourceId = event.sourceId,
                    category = event.category,
                    startedAtEpochMs = event.timestampEpochMs,
                    lastEventAtEpochMs = event.timestampEpochMs,
                    startEvent = event.name,
                    lastEvent = event.name,
                )
                while (active.size > 100) active.remove(active.entries.first().key)
            }
            isTerminal(name) -> active.remove(traceId)
            traceId in active -> {
                val current = active.getValue(traceId)
                active[traceId] = current.copy(
                    lastEventAtEpochMs = event.timestampEpochMs,
                    lastEvent = event.name,
                )
            }
        }
''',
    '''        val current = active[traceId]
        when {
            isStart(name) && current == null -> {
                active[traceId] = DiagnosticActiveOperation(
                    traceId = traceId,
                    sourceId = event.sourceId,
                    category = event.category,
                    startedAtEpochMs = event.timestampEpochMs,
                    lastEventAtEpochMs = event.timestampEpochMs,
                    startEvent = event.name,
                    lastEvent = event.name,
                )
                while (active.size > 100) active.remove(active.entries.first().key)
            }
            isTerminal(name) && current != null && operationStem(name) == operationStem(current.startEvent.uppercase()) -> {
                active.remove(traceId)
            }
            current != null -> {
                active[traceId] = current.copy(
                    lastEventAtEpochMs = event.timestampEpochMs,
                    lastEvent = event.name,
                )
            }
        }
''',
)
replace_once(
    path,
    '''    private fun isTerminal(name: String): Boolean {
        if (name.endsWith("_ITEM_COMPLETED") || name.endsWith("_SEGMENT_COMPLETED")) return false
        return name.endsWith("_COMPLETED") ||
            name.endsWith("_FAILED") ||
            name.endsWith("_ERROR") ||
            name.endsWith("_DONE") ||
            name.endsWith("_CANCELLED") ||
            name.endsWith("_STOPPED")
    }
''',
    '''    private fun isTerminal(name: String): Boolean = TERMINAL_SUFFIXES.any(name::endsWith)

    private fun operationStem(name: String): String =
        (START_SUFFIXES + TERMINAL_SUFFIXES).firstOrNull(name::endsWith)?.let { suffix -> name.removeSuffix(suffix) } ?: name

    companion object {
        private val START_SUFFIXES = listOf("_STARTED", "_START")
        private val TERMINAL_SUFFIXES = listOf("_COMPLETED", "_FAILED", "_ERROR", "_DONE", "_CANCELLED", "_STOPPED")
    }
''',
)

# Wire the shared black box into online text AI.
path = "app/src/main/java/vn/nghetruyen/app/AppContainer.kt"
replace_once(
    path,
    '        OnlineTextAiServices(settingsRepository, aiCredentialStore, aiRequestGovernor, libraryRepository)',
    '        OnlineTextAiServices(settingsRepository, aiCredentialStore, aiRequestGovernor, libraryRepository, sourceDiagnostics)',
)

path = "app/src/main/java/vn/nghetruyen/app/ai/OnlineTextAiServices.kt"
write(path, '''package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime

/** Production-facing online AI surface for translation and VietPhrase. */
class OnlineTextAiServices(
    settingsRepository: SettingsRepository,
    credentialStore: AiCredentialStore,
    requestGovernor: AiRequestGovernor,
    libraryRepository: LibraryRepository,
    diagnostics: SourceDiagnosticRuntime? = null,
) : TranslationEngine, VietPhraseImprovementEngine {
    private val delegate = OnlineAiServices(
        settingsRepository = settingsRepository,
        credentialStore = credentialStore,
        requestGovernor = requestGovernor,
        libraryRepository = libraryRepository,
        diagnostics = diagnostics,
    )

    override suspend fun translate(request: TranslationRequest): AppResult<String> = delegate.translate(request)

    override suspend fun improveVietPhrase(
        request: VietPhraseImprovementRequest,
    ): AppResult<List<VietPhraseReplacementSuggestion>> = delegate.improveVietPhrase(request)

    suspend fun listModels(
        provider: AiProvider,
        endpoint: String,
        apiKeyOverride: String? = null,
    ): AppResult<List<String>> = delegate.listModels(provider, endpoint, apiKeyOverride)

    suspend fun listGeminiModels(): AppResult<List<String>> = delegate.listGeminiModels()
}
''')

# Deep AI translation/VietPhrase HTTP timeline and Advanced request/response evidence.
path = "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt"
replace_once(
    path,
    'import vn.nghetruyen.app.data.settings.SettingsRepository\nimport java.io.IOException\nimport java.util.concurrent.TimeUnit',
    'import vn.nghetruyen.app.data.settings.SettingsRepository\nimport vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime\nimport vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticEvidence\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport java.io.IOException\nimport java.net.URI\nimport java.util.UUID\nimport java.util.concurrent.TimeUnit',
)
replace_once(
    path,
    '''    private val libraryRepository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()''',
    '''    private val libraryRepository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()''',
)
# Add diagnostics after client so existing positional test constructors remain source-compatible.
replace_once(
    path,
    '''        .followSslRedirects(false)
        .build(),
) : TranslationEngine''',
    '''        .followSslRedirects(false)
        .build(),
    private val diagnostics: SourceDiagnosticRuntime? = null,
) : TranslationEngine''',
)

replace_once(
    path,
    '''    override suspend fun translate(request: TranslationRequest): AppResult<String> {
        val source = request.sourceText.trim()
        if (source.isBlank()) return failure("AI_EMPTY_INPUT", "Chương không có nội dung để dịch.")
        if (source.length > MAX_TRANSLATION_CHARS) return failure("AI_INPUT_TOO_LARGE", "Chương vượt giới hạn dịch trong một lượt.")
''',
    '''    override suspend fun translate(request: TranslationRequest): AppResult<String> {
        val traceId = "ai-translation:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val source = request.sourceText.trim()
        diagnostic(
            traceId,
            "AI_TRANSLATION_STARTED",
            DiagnosticSeverity.INFO,
            attributes = mapOf(
                "storyId" to request.storyId,
                "chapterTitle" to request.chapterTitle.take(160),
                "inputChars" to source.length.toString(),
            ),
        )
        if (source.isBlank()) return operationFailure(traceId, "AI_TRANSLATION_FAILED", "AI_EMPTY_INPUT", "Chương không có nội dung để dịch.", startedAt)
        if (source.length > MAX_TRANSLATION_CHARS) return operationFailure(traceId, "AI_TRANSLATION_FAILED", "AI_INPUT_TOO_LARGE", "Chương vượt giới hạn dịch trong một lượt.", startedAt)
''',
)
replace_once(
    path,
    '''        return when (val result = chat(prompt, maxOutputTokens = 12_000, config = config, jsonMode = true)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val translated = runCatching {
                    val obj = JSONObject(result.value)
                    obj.optString("content").trim().takeIf(String::isNotBlank) ?: result.value.trim()
                }.getOrDefault(result.value.trim())
                AppResult.Success(translated)
            }
        }
''',
    '''        return when (val result = chat(prompt, maxOutputTokens = 12_000, config = config, jsonMode = true, traceId = traceId, operation = "translation")) {
            is AppResult.Failure -> {
                diagnostic(traceId, "AI_TRANSLATION_FAILED", DiagnosticSeverity.WARN, durationMs = System.currentTimeMillis() - startedAt, attributes = mapOf("code" to result.code, "message" to result.message.take(500)))
                result
            }
            is AppResult.Success -> {
                val translated = runCatching {
                    val obj = JSONObject(result.value)
                    obj.optString("content").trim().takeIf(String::isNotBlank) ?: result.value.trim()
                }.getOrDefault(result.value.trim())
                diagnostic(traceId, "AI_TRANSLATION_COMPLETED", DiagnosticSeverity.INFO, durationMs = System.currentTimeMillis() - startedAt, attributes = mapOf("outputChars" to translated.length.toString()))
                AppResult.Success(translated)
            }
        }
''',
)
replace_once(
    path,
    '''    ): AppResult<List<VietPhraseReplacementSuggestion>> {
        val source = request.sourceText.trim()
        val vietPhrase = request.vietPhraseText.trim()
        if (source.isBlank() || vietPhrase.isBlank()) return failure("AI_EMPTY_INPUT", "Thiếu bản gốc hoặc bản VietPhrase để đối chiếu.")
        if (source.length + vietPhrase.length > MAX_IMPROVEMENT_CHARS) {
            return failure("AI_INPUT_TOO_LARGE", "Nội dung đối chiếu vượt giới hạn cải thiện VietPhrase trong một lượt.")
        }
''',
    '''    ): AppResult<List<VietPhraseReplacementSuggestion>> {
        val traceId = "ai-vietphrase:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val source = request.sourceText.trim()
        val vietPhrase = request.vietPhraseText.trim()
        diagnostic(
            traceId,
            "AI_VIETPHRASE_IMPROVEMENT_STARTED",
            DiagnosticSeverity.INFO,
            attributes = mapOf(
                "storyId" to request.storyId,
                "chapterTitle" to request.chapterTitle.take(160),
                "sourceChars" to source.length.toString(),
                "vietPhraseChars" to vietPhrase.length.toString(),
            ),
        )
        if (source.isBlank() || vietPhrase.isBlank()) return operationFailure(traceId, "AI_VIETPHRASE_IMPROVEMENT_FAILED", "AI_EMPTY_INPUT", "Thiếu bản gốc hoặc bản VietPhrase để đối chiếu.", startedAt)
        if (source.length + vietPhrase.length > MAX_IMPROVEMENT_CHARS) {
            return operationFailure(traceId, "AI_VIETPHRASE_IMPROVEMENT_FAILED", "AI_INPUT_TOO_LARGE", "Nội dung đối chiếu vượt giới hạn cải thiện VietPhrase trong một lượt.", startedAt)
        }
''',
)
replace_once(
    path,
    '''        return when (val result = chat(prompt, maxOutputTokens = 6_000, config = config, jsonMode = true)) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatching { parseVietPhraseSuggestions(result.value, vietPhrase) }
                .fold(
                    { AppResult.Success(it) },
                    { failure("AI_BAD_RESPONSE", it.message ?: "Kết quả cải thiện VietPhrase không hợp lệ.") },
                )
        }
''',
    '''        return when (val result = chat(prompt, maxOutputTokens = 6_000, config = config, jsonMode = true, traceId = traceId, operation = "vietphrase_improvement")) {
            is AppResult.Failure -> {
                diagnostic(traceId, "AI_VIETPHRASE_IMPROVEMENT_FAILED", DiagnosticSeverity.WARN, durationMs = System.currentTimeMillis() - startedAt, attributes = mapOf("code" to result.code, "message" to result.message.take(500)))
                result
            }
            is AppResult.Success -> runCatching { parseVietPhraseSuggestions(result.value, vietPhrase) }
                .fold(
                    {
                        diagnostic(traceId, "AI_VIETPHRASE_IMPROVEMENT_COMPLETED", DiagnosticSeverity.INFO, durationMs = System.currentTimeMillis() - startedAt, attributes = mapOf("suggestions" to it.size.toString()))
                        AppResult.Success(it)
                    },
                    {
                        operationFailure(traceId, "AI_VIETPHRASE_IMPROVEMENT_FAILED", "AI_BAD_RESPONSE", it.message ?: "Kết quả cải thiện VietPhrase không hợp lệ.", startedAt, it)
                    },
                )
        }
''',
)

replace_once(
    path,
    '''    private suspend fun chat(
        prompt: String,
        maxOutputTokens: Int,
        config: EffectiveAiConfiguration,
        jsonMode: Boolean,
    ): AppResult<String> = withContext(Dispatchers.IO) {
''',
    '''    private suspend fun chat(
        prompt: String,
        maxOutputTokens: Int,
        config: EffectiveAiConfiguration,
        jsonMode: Boolean,
        traceId: String = "ai:${UUID.randomUUID()}",
        operation: String = "generic",
    ): AppResult<String> = withContext(Dispatchers.IO) {
        diagnostic(
            traceId,
            "AI_REQUEST_CONTEXT",
            DiagnosticSeverity.INFO,
            attributes = mapOf(
                "operation" to operation,
                "provider" to config.provider.name,
                "model" to config.model.take(160),
                "promptChars" to prompt.length.toString(),
                "maxOutputTokens" to maxOutputTokens.toString(),
                "jsonMode" to jsonMode.toString(),
            ),
        )
''',
)
replace_once(
    path,
    '''            candidateLoop@ for ((candidateIndex, candidateData) in candidates.withIndex()) {
                val builder = Request.Builder()
''',
    '''            candidateLoop@ for ((candidateIndex, candidateData) in candidates.withIndex()) {
                val requestStartedAt = System.currentTimeMillis()
                val endpoint = diagnosticEndpoint(candidateData.url)
                diagnostic(
                    traceId,
                    "AI_HTTP_ATTEMPT_STARTED",
                    DiagnosticSeverity.INFO,
                    DiagnosticCategory.NETWORK,
                    attributes = mapOf(
                        "operation" to operation,
                        "provider" to config.provider.name,
                        "model" to config.model.take(160),
                        "attempt" to (attempt + 1).toString(),
                        "candidate" to (candidateIndex + 1).toString(),
                        "candidateCount" to candidates.size.toString(),
                        "endpoint" to endpoint,
                        "requestChars" to candidateData.body.length.toString(),
                    ),
                )
                captureAiEvidence(traceId, operation, "request-a${attempt + 1}-c${candidateIndex + 1}.json", candidateData.body, mapOf("endpoint" to endpoint, "provider" to config.provider.name))
                val builder = Request.Builder()
''',
)
replace_once(
    path,
    '''                            }.orEmpty()
                            if (raw.length > MAX_RESPONSE_CHARS) {
''',
    '''                            }.orEmpty()
                            diagnostic(
                                traceId,
                                "AI_HTTP_RESPONSE_RECEIVED",
                                if (response.isSuccessful) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                                DiagnosticCategory.NETWORK,
                                durationMs = System.currentTimeMillis() - requestStartedAt,
                                attributes = mapOf(
                                    "operation" to operation,
                                    "provider" to config.provider.name,
                                    "status" to response.code.toString(),
                                    "responseChars" to raw.length.toString(),
                                    "attempt" to (attempt + 1).toString(),
                                    "candidate" to (candidateIndex + 1).toString(),
                                    "endpoint" to endpoint,
                                ),
                            )
                            captureAiEvidence(traceId, operation, "response-a${attempt + 1}-c${candidateIndex + 1}-http${response.code}.json", raw, mapOf("endpoint" to endpoint, "status" to response.code.toString(), "provider" to config.provider.name))
                            if (raw.length > MAX_RESPONSE_CHARS) {
''',
)
replace_once(
    path,
    '''                                if (content.isNotBlank()) {
                                    requestGovernor.finish(permit, content.length, retries, null)
                                    return@withContext AppResult.Success(content.trim())
                                }
''',
    '''                                if (content.isNotBlank()) {
                                    diagnostic(traceId, "AI_HTTP_CONTENT_PARSED", DiagnosticSeverity.INFO, DiagnosticCategory.NETWORK, attributes = mapOf("operation" to operation, "contentChars" to content.length.toString(), "provider" to config.provider.name, "model" to config.model.take(160)))
                                    requestGovernor.finish(permit, content.length, retries, null)
                                    return@withContext AppResult.Success(content.trim())
                                }
''',
)
replace_once(
    path,
    '''                    if (fallbackToNext) continue@candidateLoop
                } catch (error: IOException) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                    if (attempt < permit.maxRetries) {
                        shouldRetry = true
                    }
                } catch (error: Exception) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                }
''',
    '''                    if (fallbackToNext) {
                        diagnostic(traceId, "AI_HTTP_ENDPOINT_FALLBACK", DiagnosticSeverity.WARN, DiagnosticCategory.NETWORK, attributes = mapOf("operation" to operation, "from" to endpoint, "nextCandidate" to (candidateIndex + 2).toString(), "status" to (lastFailure?.code ?: "")))
                        continue@candidateLoop
                    }
                } catch (error: IOException) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                    diagnostic(traceId, "AI_HTTP_NETWORK_ERROR", DiagnosticSeverity.WARN, DiagnosticCategory.NETWORK, durationMs = System.currentTimeMillis() - requestStartedAt, attributes = mapOf("operation" to operation, "endpoint" to endpoint, "type" to error.javaClass.simpleName, "message" to (error.message ?: "").take(500)))
                    if (attempt < permit.maxRetries) {
                        shouldRetry = true
                    }
                } catch (error: Exception) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                    diagnostic(traceId, "AI_HTTP_RUNTIME_ERROR", DiagnosticSeverity.ERROR, DiagnosticCategory.NETWORK, durationMs = System.currentTimeMillis() - requestStartedAt, attributes = mapOf("operation" to operation, "endpoint" to endpoint, "type" to error.javaClass.simpleName, "message" to (error.message ?: "").take(500)))
                }
''',
)
replace_once(
    path,
    '''            if (shouldRetry) {
                retries += 1
                delay(retryDelayMillis(permit.retryBaseDelayMillis, attempt, retryAfterMillis))
                continue
            }
''',
    '''            if (shouldRetry) {
                retries += 1
                val retryDelay = retryDelayMillis(permit.retryBaseDelayMillis, attempt, retryAfterMillis)
                diagnostic(traceId, "AI_HTTP_RETRY_SCHEDULED", DiagnosticSeverity.WARN, DiagnosticCategory.NETWORK, attributes = mapOf("operation" to operation, "retry" to retries.toString(), "delayMs" to retryDelay.toString(), "lastCode" to (lastFailure?.code ?: "")))
                delay(retryDelay)
                continue
            }
''',
)
replace_once(
    path,
    '''    private fun providerLabel(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        AiProvider.GEMINI -> "Gemini"
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) = AppResult.Failure(code, message, cause)
''',
    '''    private fun providerLabel(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        AiProvider.GEMINI -> "Gemini"
    }

    private fun diagnostic(
        traceId: String,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        diagnostics?.mark(name = name, category = category, severity = severity, sourceId = "ai", traceId = traceId, durationMs = durationMs, attributes = attributes)
    }

    private fun captureAiEvidence(
        traceId: String,
        operation: String,
        name: String,
        body: String,
        attributes: Map<String, String>,
    ) {
        val sink = diagnostics?.evidence ?: return
        if (!sink.enabled || body.isBlank()) return
        sink.capture(
            DiagnosticEvidence(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId,
                sourceId = "ai",
                category = DiagnosticCategory.NETWORK,
                name = "ai-$operation-$name",
                contentType = "application/json",
                data = body.toByteArray(Charsets.UTF_8),
                attributes = attributes + ("operation" to operation),
            ),
        )
    }

    private fun diagnosticEndpoint(value: String): String = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) "[redacted-endpoint]"
        else "https://${uri.host}${uri.rawPath.orEmpty().take(300)}"
    }.getOrDefault("[invalid-endpoint]")

    private fun operationFailure(
        traceId: String,
        eventName: String,
        code: String,
        message: String,
        startedAt: Long,
        cause: Throwable? = null,
    ): AppResult.Failure {
        diagnostic(traceId, eventName, DiagnosticSeverity.WARN, durationMs = System.currentTimeMillis() - startedAt, attributes = mapOf("code" to code, "message" to message.take(500), "cause" to (cause?.javaClass?.simpleName ?: "")))
        return failure(code, message, cause)
    }

    private fun failure(code: String, message: String, cause: Throwable? = null) = AppResult.Failure(code, message, cause)
''',
)

# VietPhrase result model now carries bounded candidate/failure probes and aggregate engine counters.
path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseModels.kt"
replace_once(
    path,
    '    val traceLimit: Int = 2_000,\n)',
    '    val traceLimit: Int = 2_000,\n    val diagnosticProbeLimit: Int = 0,\n)',
)
replace_once(
    path,
    '''data class VietPhraseResult(
    val text: String,
    val trace: List<VietPhraseTraceEntry>,
    val traceTruncated: Boolean,
    val appliedByKind: Map<VietPhraseDictionaryKind, Int>,
)
''',
    '''data class VietPhraseProbeEntry(
    val position: Int,
    val phase: String,
    val kind: VietPhraseDictionaryKind?,
    val ruleId: String?,
    val outcome: String,
    val detail: String = "",
)

data class VietPhraseEngineDiagnostics(
    val cursorPositions: Int = 0,
    val literalLookups: Int = 0,
    val literalCandidates: Int = 0,
    val directSelections: Int = 0,
    val templateCandidates: Int = 0,
    val templateAttempts: Int = 0,
    val templateMatches: Int = 0,
    val templateSelections: Int = 0,
    val templateBudgetExceeded: Int = 0,
    val captureCandidates: Int = 0,
    val fallbackSelections: Int = 0,
    val unmatchedCodePoints: Int = 0,
    val aiReplaceSelections: Int = 0,
    val probes: List<VietPhraseProbeEntry> = emptyList(),
    val probesTruncated: Boolean = false,
)

data class VietPhraseResult(
    val text: String,
    val trace: List<VietPhraseTraceEntry>,
    val traceTruncated: Boolean,
    val appliedByKind: Map<VietPhraseDictionaryKind, Int>,
    val diagnostics: VietPhraseEngineDiagnostics = VietPhraseEngineDiagnostics(),
)
''',
)

path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt"
replace_once(
    path,
    '''    private data class TemplateMatch(
        val rule: VietPhraseRule,
        val end: Int,
        val replacement: String,
        val captures: Map<Int, String>,
    )
''',
    '''    private data class TemplateMatch(
        val rule: VietPhraseRule,
        val end: Int,
        val replacement: String,
        val captures: Map<Int, String>,
    )

    private class MutableEngineDiagnostics(private val probeLimit: Int) {
        var cursorPositions = 0
        var literalLookups = 0
        var literalCandidates = 0
        var directSelections = 0
        var templateCandidates = 0
        var templateAttempts = 0
        var templateMatches = 0
        var templateSelections = 0
        var templateBudgetExceeded = 0
        var captureCandidates = 0
        var fallbackSelections = 0
        var unmatchedCodePoints = 0
        var aiReplaceSelections = 0
        private val probes = ArrayList<VietPhraseProbeEntry>()
        private var probesTruncated = false

        fun probe(position: Int, phase: String, rule: VietPhraseRule?, outcome: String, detail: String = "") {
            if (probeLimit <= 0) return
            if (probes.size >= probeLimit) {
                probesTruncated = true
                return
            }
            probes += VietPhraseProbeEntry(position, phase, rule?.kind, rule?.id, outcome, detail.take(300))
        }

        fun snapshot() = VietPhraseEngineDiagnostics(
            cursorPositions = cursorPositions,
            literalLookups = literalLookups,
            literalCandidates = literalCandidates,
            directSelections = directSelections,
            templateCandidates = templateCandidates,
            templateAttempts = templateAttempts,
            templateMatches = templateMatches,
            templateSelections = templateSelections,
            templateBudgetExceeded = templateBudgetExceeded,
            captureCandidates = captureCandidates,
            fallbackSelections = fallbackSelections,
            unmatchedCodePoints = unmatchedCodePoints,
            aiReplaceSelections = aiReplaceSelections,
            probes = probes.toList(),
            probesTruncated = probesTruncated,
        )
    }
''',
)
replace_once(
    path,
    '''        val cacheable = options.traceLimit <= 0 && maxCacheEntries > 0
        val key = cacheKey(text, options)
        if (cacheable) cache[key]?.let { return it }

        val trace = ArrayList<VietPhraseTraceEntry>()
''',
    '''        val cacheable = options.traceLimit <= 0 && options.diagnosticProbeLimit <= 0 && maxCacheEntries > 0
        val key = cacheKey(text, options)
        if (cacheable) cache[key]?.let { return it }

        val diagnostics = MutableEngineDiagnostics(options.diagnosticProbeLimit)
        val trace = ArrayList<VietPhraseTraceEntry>()
''',
)
replace_once(
    path,
    '''        while (cursor < text.length) {
            val chLength = Character.charCount(Character.codePointAt(text, cursor))
            val direct = bestLiteral(text, cursor, baseLiteralTrie, options)
            val template = if (options.useRules) bestTemplate(text, cursor, options) else null
''',
    '''        while (cursor < text.length) {
            diagnostics.cursorPositions += 1
            val chLength = Character.charCount(Character.codePointAt(text, cursor))
            val direct = bestLiteral(text, cursor, baseLiteralTrie, options, diagnostics, "base_literal")
            val template = if (options.useRules) bestTemplate(text, cursor, options, diagnostics) else null
''',
)
replace_once(
    path,
    '''            if (choiceIsTemplate) {
                val match = requireNotNull(template)
''',
    '''            if (choiceIsTemplate) {
                val match = requireNotNull(template)
                diagnostics.templateSelections += 1
                diagnostics.probe(cursor, "selection", match.rule, "template_selected", "end=${match.end}")
''',
)
replace_once(
    path,
    '''            } else if (direct != null) {
                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)
''',
    '''            } else if (direct != null) {
                diagnostics.directSelections += 1
                diagnostics.probe(cursor, "selection", direct.rule, "direct_selected", "end=${direct.end}")
                val replacement = resolveMeaning(direct.replacement, direct.rule.kind, options.oneMeaning)
''',
)
replace_once(
    path,
    '''                val fallback = if (options.fallbackHanViet) bestLiteral(text, cursor, fallbackHanVietTrie, options) else null
                if (fallback != null) {
                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)
''',
    '''                val fallback = if (options.fallbackHanViet) bestLiteral(text, cursor, fallbackHanVietTrie, options, diagnostics, "hanviet_fallback") else null
                if (fallback != null) {
                    diagnostics.fallbackSelections += 1
                    diagnostics.probe(cursor, "selection", fallback.rule, "fallback_selected", "end=${fallback.end}")
                    val replacement = resolveMeaning(fallback.replacement, fallback.rule.kind, options.oneMeaning)
''',
)
replace_once(
    path,
    '''                } else {
                    val raw = text.substring(cursor, cursor + chLength)
                    base.append(if (options.normalizePunctuation) PUNCTUATION[raw] ?: raw else raw)
                    cursor += chLength
                }
''',
    '''                } else {
                    diagnostics.unmatchedCodePoints += 1
                    diagnostics.probe(cursor, "selection", null, "unmatched_codepoint", "codePoint=${Character.codePointAt(text, cursor)}")
                    val raw = text.substring(cursor, cursor + chLength)
                    base.append(if (options.normalizePunctuation) PUNCTUATION[raw] ?: raw else raw)
                    cursor += chLength
                }
''',
)
replace_once(
    path,
    '''            val finalPass = applyFinalReplacement(output, options, trace, counts)
''',
    '''            val finalPass = applyFinalReplacement(output, options, trace, counts, diagnostics)
''',
)
replace_once(
    path,
    '''        val result = VietPhraseResult(output, trace.toList(), truncated, counts.toMap())
''',
    '''        val result = VietPhraseResult(output, trace.toList(), truncated, counts.toMap(), diagnostics.snapshot())
''',
)
replace_once(
    path,
    '''    private fun literalMatches(text: String, start: Int, trie: TrieNode, options: VietPhraseOptions): List<LiteralMatch> {
        val matches = mutableListOf<LiteralMatch>()
''',
    '''    private fun literalMatches(
        text: String,
        start: Int,
        trie: TrieNode,
        options: VietPhraseOptions,
        diagnostics: MutableEngineDiagnostics,
        phase: String,
    ): List<LiteralMatch> {
        diagnostics.literalLookups += 1
        val matches = mutableListOf<LiteralMatch>()
''',
)
replace_once(
    path,
    '''            for (rule in node.terminalRules) {
                if (!ruleVisible(rule, options) || !matchesAt(text, start, rule)) continue
                val end = start + rule.source.length
                if (end == cursor && safeBoundaries(text, start, end, rule.source)) matches += LiteralMatch(rule, end, rule.target)
            }
''',
    '''            for (rule in node.terminalRules) {
                diagnostics.literalCandidates += 1
                if (!ruleVisible(rule, options)) {
                    diagnostics.probe(start, phase, rule, "scope_hidden")
                    continue
                }
                if (!matchesAt(text, start, rule)) {
                    diagnostics.probe(start, phase, rule, "case_or_text_mismatch")
                    continue
                }
                val end = start + rule.source.length
                if (end != cursor) continue
                if (!safeBoundaries(text, start, end, rule.source)) {
                    diagnostics.probe(start, phase, rule, "boundary_rejected")
                    continue
                }
                diagnostics.probe(start, phase, rule, "candidate_matched", "end=$end")
                matches += LiteralMatch(rule, end, rule.target)
            }
''',
)
replace_once(
    path,
    '''    private fun bestLiteral(text: String, start: Int, trie: TrieNode, options: VietPhraseOptions): LiteralMatch? =
        literalMatches(text, start, trie, options).maxWithOrNull(
''',
    '''    private fun bestLiteral(
        text: String,
        start: Int,
        trie: TrieNode,
        options: VietPhraseOptions,
        diagnostics: MutableEngineDiagnostics,
        phase: String,
    ): LiteralMatch? =
        literalMatches(text, start, trie, options, diagnostics, phase).maxWithOrNull(
''',
)
replace_once(
    path,
    '''    private fun bestTemplate(text: String, start: Int, options: VietPhraseOptions): TemplateMatch? {
        var best: TemplateMatch? = null
        val candidates = templateBuckets[bucketKey(text[start])].orEmpty() + wildcardTemplates
        for (compiled in candidates) {
            if (!ruleVisible(compiled.rule, options)) continue
            val match = matchTemplate(text, start, compiled, options) ?: continue
''',
    '''    private fun bestTemplate(text: String, start: Int, options: VietPhraseOptions, diagnostics: MutableEngineDiagnostics): TemplateMatch? {
        var best: TemplateMatch? = null
        val candidates = templateBuckets[bucketKey(text[start])].orEmpty() + wildcardTemplates
        diagnostics.templateCandidates += candidates.size
        for (compiled in candidates) {
            if (!ruleVisible(compiled.rule, options)) {
                diagnostics.probe(start, "template", compiled.rule, "scope_hidden")
                continue
            }
            diagnostics.templateAttempts += 1
            val match = matchTemplate(text, start, compiled, options, diagnostics)
            if (match == null) {
                diagnostics.probe(start, "template", compiled.rule, "no_match")
                continue
            }
            diagnostics.templateMatches += 1
            diagnostics.probe(start, "template", compiled.rule, "candidate_matched", "end=${match.end}")
''',
)
replace_once(
    path,
    '''    private fun matchTemplate(text: String, start: Int, compiled: CompiledTemplate, options: VietPhraseOptions): TemplateMatch? {
        var budget = 0
        fun walk(partIndex: Int, cursor: Int, captures: Map<Int, LiteralMatch>): Pair<Int, Map<Int, LiteralMatch>>? {
            budget += 1
            if (budget > MAX_TEMPLATE_STEPS) return null
''',
    '''    private fun matchTemplate(text: String, start: Int, compiled: CompiledTemplate, options: VietPhraseOptions, diagnostics: MutableEngineDiagnostics): TemplateMatch? {
        var budget = 0
        fun walk(partIndex: Int, cursor: Int, captures: Map<Int, LiteralMatch>): Pair<Int, Map<Int, LiteralMatch>>? {
            budget += 1
            if (budget > MAX_TEMPLATE_STEPS) {
                diagnostics.templateBudgetExceeded += 1
                diagnostics.probe(start, "template", compiled.rule, "budget_exceeded", "steps=$budget")
                return null
            }
''',
)
replace_once(
    path,
    '''            val candidates = literalMatches(text, cursor, captureTrie, options).asSequence()
                .filter { !containsBoundary(text.substring(cursor, it.end)) }
''',
    '''            val candidates = literalMatches(text, cursor, captureTrie, options, diagnostics, "template_capture").asSequence()
                .filter { !containsBoundary(text.substring(cursor, it.end)) }
''',
)
replace_once(
    path,
    '''                .take(MAX_CAPTURE_CANDIDATES)
                .toList()
            var best: Pair<Int, Map<Int, LiteralMatch>>? = null
''',
    '''                .take(MAX_CAPTURE_CANDIDATES)
                .toList()
            diagnostics.captureCandidates += candidates.size
            var best: Pair<Int, Map<Int, LiteralMatch>>? = null
''',
)
replace_once(
    path,
    '''        counts: MutableMap<VietPhraseDictionaryKind, Int>,
    ): Pair<String, Boolean> {
''',
    '''        counts: MutableMap<VietPhraseDictionaryKind, Int>,
        diagnostics: MutableEngineDiagnostics,
    ): Pair<String, Boolean> {
''',
)
replace_once(
    path,
    '''            val match = bestLiteral(input, cursor, aiReplaceTrie, options)
''',
    '''            val match = bestLiteral(input, cursor, aiReplaceTrie, options, diagnostics, "ai_replace")
''',
)
replace_once(
    path,
    '''            } else {
                out.append(match.replacement)
                counts[VietPhraseDictionaryKind.AI_REPLACE]''',
    '''            } else {
                diagnostics.aiReplaceSelections += 1
                diagnostics.probe(cursor, "selection", match.rule, "ai_replace_selected", "end=${match.end}")
                out.append(match.replacement)
                counts[VietPhraseDictionaryKind.AI_REPLACE]''',
)

# Enhanced VietPhrase diagnostic ZIP and mirror its stats/evidence into the global black box.
path = "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt"
write(path, '''package vn.nghetruyen.app.ai.vietphrase

import android.content.Context
import org.json.JSONObject
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VietPhraseDiagnosticExport(
    val path: String,
    val summary: String,
    val preview: String,
    val traceCount: Int,
    val probeCount: Int = 0,
)

object VietPhraseDiagnosticExporter {
    private const val TRACE_LIMIT = 20_000
    private const val PROBE_LIMIT = 20_000

    fun export(
        context: Context,
        title: String,
        paragraphs: List<String>,
        rules: List<VietPhraseRule>,
        storyId: String?,
        fallbackHanViet: Boolean,
        diagnostics: SourceDiagnosticRuntime? = null,
        diagnosticTraceId: String = "",
        diagnosticSourceId: String = "vietphrase",
    ): Result<VietPhraseDiagnosticExport> = runCatching {
        val traceId = diagnosticTraceId.ifBlank { "vietphrase:${UUID.randomUUID()}" }
        val body = paragraphs.joinToString("\n\n").trim()
        require(body.isNotBlank()) { "Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase." }
        val options = VietPhraseOptions(
            storyId = storyId,
            fallbackHanViet = fallbackHanViet,
            traceLimit = TRACE_LIMIT,
            diagnosticProbeLimit = PROBE_LIMIT,
        )
        val engine = VietPhraseEngine(rules)
        val result = engine.translateWithTrace(body, options)
        val translatedTitle = title.takeIf(String::isNotBlank)?.let { engine.translate(it, options.copy(traceLimit = 0, diagnosticProbeLimit = 0)) }.orEmpty()
        val now = Date()
        val stats = result.diagnostics
        val summary = buildString {
            appendLine("NHẬT KÝ VIETPHRASE - NGHE TRUYỆN")
            appendLine("Thời gian: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now)}")
            appendLine("Tiêu đề: ${title.ifBlank { "Không có tiêu đề" }}")
            appendLine("Độ dài nội dung gốc: ${body.toByteArray(Charsets.UTF_8).size} byte")
            appendLine("Số quyết định được ghi: ${result.trace.size}${if (result.traceTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine("Số probe candidate/failure: ${stats.probes.size}${if (stats.probesTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine()
            appendLine("CÀI ĐẶT")
            appendLine("fallback_hanviet=$fallbackHanViet")
            appendLine()
            appendLine("THỐNG KÊ ENGINE")
            appendLine("cursor_positions=${stats.cursorPositions}")
            appendLine("literal_lookups=${stats.literalLookups}")
            appendLine("literal_candidates=${stats.literalCandidates}")
            appendLine("direct_selections=${stats.directSelections}")
            appendLine("template_candidates=${stats.templateCandidates}")
            appendLine("template_attempts=${stats.templateAttempts}")
            appendLine("template_matches=${stats.templateMatches}")
            appendLine("template_selections=${stats.templateSelections}")
            appendLine("template_budget_exceeded=${stats.templateBudgetExceeded}")
            appendLine("capture_candidates=${stats.captureCandidates}")
            appendLine("fallback_selections=${stats.fallbackSelections}")
            appendLine("unmatched_codepoints=${stats.unmatchedCodePoints}")
            appendLine("ai_replace_selections=${stats.aiReplaceSelections}")
            appendLine()
            appendLine("THỐNG KÊ MATCH")
            VietPhraseDictionaryKind.entries.forEach { kind ->
                appendLine("${kind.fileName}: ${result.appliedByKind[kind] ?: 0}")
            }
        }.trimEnd()
        val traceLines = buildList {
            add("start\tend\tkind\tsource\treplacement\trule_id\tcaptures")
            result.trace.forEach { entry ->
                add(listOf(entry.inputStart, entry.inputEnd, entry.kind?.fileName.orEmpty(), entry.source.tsvSafe(), entry.replacement.tsvSafe(), entry.ruleId.orEmpty().tsvSafe(), entry.captures.entries.sortedBy(Map.Entry<Int, String>::key).joinToString(";") { (slot, value) -> "$slot=${value.tsvSafe()}" }).joinToString("\t"))
            }
        }
        val probeLines = buildList {
            add("position\tphase\tkind\trule_id\toutcome\tdetail")
            stats.probes.forEach { probe ->
                add(listOf(probe.position, probe.phase.tsvSafe(), probe.kind?.fileName.orEmpty(), probe.ruleId.orEmpty().tsvSafe(), probe.outcome.tsvSafe(), probe.detail.tsvSafe()).joinToString("\t"))
            }
        }
        val statsJson = JSONObject()
            .put("cursorPositions", stats.cursorPositions)
            .put("literalLookups", stats.literalLookups)
            .put("literalCandidates", stats.literalCandidates)
            .put("directSelections", stats.directSelections)
            .put("templateCandidates", stats.templateCandidates)
            .put("templateAttempts", stats.templateAttempts)
            .put("templateMatches", stats.templateMatches)
            .put("templateSelections", stats.templateSelections)
            .put("templateBudgetExceeded", stats.templateBudgetExceeded)
            .put("captureCandidates", stats.captureCandidates)
            .put("fallbackSelections", stats.fallbackSelections)
            .put("unmatchedCodePoints", stats.unmatchedCodePoints)
            .put("aiReplaceSelections", stats.aiReplaceSelections)
            .put("probeCount", stats.probes.size)
            .put("probesTruncated", stats.probesTruncated)
            .toString(2)
        val preview = (traceLines.drop(1).take(40) + probeLines.drop(1).take(40)).joinToString("\n")
        val outputDir = diagnosticDirectory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(now)
        val target = File(outputDir, "vietphrase_diagnostic_$stamp.zip")
        val temp = File(outputDir, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        runCatching {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.addText("README.txt", "Gói này được tạo bởi chức năng Chẩn đoán VietPhrase.\nHãy gửi nguyên file ZIP để phân tích lỗi chất lượng dịch.\ntrace.tsv ghi quyết định được áp dụng; probes.tsv ghi candidate bị loại/match và lý do; engine_stats.json ghi bộ đếm của engine.\n")
                zip.addText("summary.txt", summary + "\n")
                zip.addText("engine_stats.json", statsJson + "\n")
                zip.addText("source.txt", title + "\n\n" + body)
                zip.addText("translated.txt", translatedTitle + "\n\n" + result.text)
                zip.addText("trace.tsv", traceLines.joinToString("\n") + "\n")
                zip.addText("probes.tsv", probeLines.joinToString("\n") + "\n")
            }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Không đổi tên được file ZIP tạm." }
        }.onFailure {
            temp.delete()
            throw it
        }

        diagnostics?.mark(
            name = "VIETPHRASE_ENGINE_STATS",
            category = DiagnosticCategory.PARSER,
            severity = DiagnosticSeverity.INFO,
            sourceId = diagnosticSourceId,
            traceId = traceId,
            attributes = mapOf(
                "traceDecisions" to result.trace.size.toString(),
                "probes" to stats.probes.size.toString(),
                "literalCandidates" to stats.literalCandidates.toString(),
                "templateAttempts" to stats.templateAttempts.toString(),
                "templateMatches" to stats.templateMatches.toString(),
                "templateBudgetExceeded" to stats.templateBudgetExceeded.toString(),
                "fallbackSelections" to stats.fallbackSelections.toString(),
                "unmatchedCodePoints" to stats.unmatchedCodePoints.toString(),
                "aiReplaceSelections" to stats.aiReplaceSelections.toString(),
            ),
        )
        diagnostics?.evidence?.takeIf { it.enabled }?.let { sink ->
            fun evidence(name: String, value: String) = sink.capture(
                DiagnosticEvidence(System.currentTimeMillis(), traceId, diagnosticSourceId, DiagnosticCategory.PARSER, name, "text/plain", value.toByteArray(Charsets.UTF_8)),
            )
            evidence("vietphrase-summary.txt", summary)
            evidence("vietphrase-engine-stats.json", statsJson)
            evidence("vietphrase-trace.tsv", traceLines.joinToString("\n"))
            evidence("vietphrase-probes.tsv", probeLines.joinToString("\n"))
            evidence("vietphrase-source.txt", title + "\n\n" + body)
            evidence("vietphrase-translated.txt", translatedTitle + "\n\n" + result.text)
        }

        VietPhraseDiagnosticExport(target.absolutePath, summary, preview, result.trace.size, stats.probes.size)
    }

    private fun diagnosticDirectory(context: Context): File {
        val candidates = buildList {
            add(File("/storage/emulated/0/NgheTruyen/diagnostics"))
            add(File("/storage/emulated/0/Download/NgheTruyen/diagnostics"))
            context.getExternalFilesDir(null)?.let { add(File(it, "diagnostics")) }
            add(File(context.filesDir, "diagnostics"))
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                if (!candidate.exists()) candidate.mkdirs()
                require(candidate.isDirectory)
                val probe = File(candidate, ".vp_probe_${System.nanoTime()}")
                probe.writeText("ok")
                probe.delete()
                true
            }.getOrDefault(false)
        } ?: error("Không tạo được thư mục nhật ký VietPhrase.")
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.tsvSafe(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}
''')

# One stable VietPhrase trace from button click through engine stats, evidence, ZIP and terminal outcome.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
replace_once(
    path,
    '''        vietPhraseDiagnosticBusy = true
        app.container.sourceDiagnostics.mark(
            name = "VIETPHRASE_DIAGNOSTIC_START",
            sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
            attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "rules" to rules.size.toString()),
        )
''',
    '''        vietPhraseDiagnosticBusy = true
        val diagnosticTraceId = "vietphrase:${content.chapter.id}:${UUID.randomUUID()}"
        val diagnosticSourceId = storyDetail?.story?.sourceId ?: "vietphrase"
        app.container.sourceDiagnostics.mark(
            name = "VIETPHRASE_DIAGNOSTIC_STARTED",
            sourceId = diagnosticSourceId,
            traceId = diagnosticTraceId,
            severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
            attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "rules" to rules.size.toString()),
        )
''',
)
replace_once(
    path,
    '''                    fallbackHanViet = state.vietPhraseFallbackHanViet,
                )
''',
    '''                    fallbackHanViet = state.vietPhraseFallbackHanViet,
                    diagnostics = app.container.sourceDiagnostics,
                    diagnosticTraceId = diagnosticTraceId,
                    diagnosticSourceId = diagnosticSourceId,
                )
''',
)
replace_once(
    path,
    '''                    name = "VIETPHRASE_DIAGNOSTIC_COMPLETED",
                    sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id),
''',
    '''                    name = "VIETPHRASE_DIAGNOSTIC_COMPLETED",
                    sourceId = diagnosticSourceId,
                    traceId = diagnosticTraceId,
                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "traceCount" to it.traceCount.toString(), "probeCount" to it.probeCount.toString()),
''',
)
replace_once(
    path,
    '''                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR,
                    sourceId = storyDetail?.story?.sourceId ?: "vietphrase",
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "error" to (error.message ?: error.javaClass.simpleName)),
''',
    '''                    severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR,
                    sourceId = diagnosticSourceId,
                    traceId = diagnosticTraceId,
                    attributes = mapOf("storyId" to storyId, "chapterId" to content.chapter.id, "error" to (error.message ?: error.javaClass.simpleName)),
''',
)

# Stronger parity gate for the new AI/VP black-box depth.
path = "scripts/check_lua_diagnostics_ui_parity.py"
replace_once(
    path,
    'ai = text("app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt")\n',
    'ai = text("app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt")\nai_text = text("app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt")\nvp_engine = text("app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt")\nvp_export = text("app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt")\n',
)
replace_once(
    path,
    '    "download diagnostics": all(\n',
    '''    "AI text HTTP diagnostics": all(marker in ai_text for marker in (
        "AI_TRANSLATION_STARTED",
        "AI_TRANSLATION_COMPLETED",
        "AI_VIETPHRASE_IMPROVEMENT_STARTED",
        "AI_HTTP_ATTEMPT_STARTED",
        "AI_HTTP_RESPONSE_RECEIVED",
        "AI_HTTP_ENDPOINT_FALLBACK",
        "AI_HTTP_RETRY_SCHEDULED",
    )),
    "AI Advanced request response evidence": "captureAiEvidence" in ai_text and "DiagnosticEvidence" in ai_text,
    "VietPhrase candidate probe diagnostics": all(token in vp_engine for token in ("literalCandidates", "templateAttempts", "templateBudgetExceeded", "unmatchedCodePoints", "diagnosticProbeLimit")),
    "VietPhrase rich diagnostic bundle": all(token in vp_export for token in ("engine_stats.json", "probes.tsv", "VIETPHRASE_ENGINE_STATS", "vietphrase-source.txt")),
    "download diagnostics": all(
''',
)
# The Reader marker is now grammatically STARTED so the operation tracker can pair it with COMPLETED/FAILED.
value = read(path).replace('"VIETPHRASE_DIAGNOSTIC_START",', '"VIETPHRASE_DIAGNOSTIC_STARTED",')
write(path, value)

print("DIAGNOSTICS_DEEPENING_STAGE_B=APPLIED")
