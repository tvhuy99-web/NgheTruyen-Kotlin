package vn.nghetruyen.source.runtime

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserBroker
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceCryptoBroker
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceCryptoRequest
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkResponseMode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.api.SourceWebSocketBroker
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import kotlin.math.min

fun interface SourceResourceProvider {
    fun read(path: String, maxBytes: Int): ByteArray?
}

class MapSourceResourceProvider(private val resources: Map<String, ByteArray>) : SourceResourceProvider {
    override fun read(path: String, maxBytes: Int): ByteArray? = resources[path]?.takeIf { it.size <= maxBytes }?.copyOf()
}

class DirectorySourceResourceProvider(private val root: java.io.File) : SourceResourceProvider {
    private val canonicalRoot = root.canonicalFile

    override fun read(path: String, maxBytes: Int): ByteArray? {
        SourceManifest.requireSafeRelativePath(path)
        val file = java.io.File(canonicalRoot, path).canonicalFile
        require(file.path.startsWith(canonicalRoot.path + java.io.File.separator)) { "RUNTIME_RESOURCE_PATH_ESCAPE" }
        if (!file.isFile || file.length() > maxBytes) return null
        return file.readBytes()
    }
}

class DeclarativeSourceRuntime(
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val networkBroker: SourceNetworkBroker = SourceNetworkBroker.DENY_ALL,
    private val browserBroker: SourceBrowserBroker = SourceBrowserBroker.DENY_ALL,
    private val storageBroker: SourceStorageBroker = SourceStorageBroker.DENY_ALL,
    private val cryptoBroker: SourceCryptoBroker = SourceCryptoBroker.DENY_ALL,
    private val webSocketBroker: SourceWebSocketBroker = SourceWebSocketBroker.DENY_ALL,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
        networkBrokerOverride: SourceNetworkBroker? = null,
    ): SourcePlatformResult<SourceActionResponse> {
        val started = clockMs()
        if (manifest.runtime.mode != SourceRuntimeMode.DECLARATIVE) {
            return failure(SourceErrorCode.RUNTIME_INVALID_PROGRAM, "RUNTIME_MODE_UNSUPPORTED", request)
        }
        val action = manifest.actions[request.action]
            ?: return failure(SourceErrorCode.ACTION_NOT_FOUND, "SOURCE_ACTION_NOT_FOUND:${request.action}", request)
        diagnostics.emit(event(manifest, request, "ACTION_STARTED", DiagnosticSeverity.INFO, attributes = mapOf("action" to request.action.name)))
        return runCatching {
            val programBytes = resources.read(action.entry, min(manifest.runtime.memoryBudgetBytes, 1024 * 1024))
                ?: error("RUNTIME_RESOURCE_MISSING:${action.entry}")
            val program = JsonCodec.parse(programBytes.toString(Charsets.UTF_8)) as? JsonValue.Obj
                ?: error("RUNTIME_PROGRAM_NOT_OBJECT")
            require(program.int("version") == 1) { "RUNTIME_PROGRAM_VERSION_UNSUPPORTED" }
            val steps = program.array("steps")?.values ?: error("RUNTIME_STEPS_MISSING")
            require(steps.size <= 256) { "RUNTIME_TOO_MANY_STEPS" }
            val context = ExecutionContext(
                manifest = manifest,
                request = request,
                resources = resources,
                instructionBudget = manifest.runtime.instructionBudget,
                memoryBudgetBytes = manifest.runtime.memoryBudgetBytes,
                deadlineMs = started + (action.timeoutMs ?: manifest.runtime.actionTimeoutMs),
                diagnostics = diagnostics,
                networkBroker = networkBrokerOverride ?: networkBroker,
                browserBroker = browserBroker,
                storageBroker = storageBroker,
                cryptoBroker = cryptoBroker,
                webSocketBroker = webSocketBroker,
                clockMs = clockMs,
            )
            context.variables["input"] = request.input
            var output: JsonValue? = null
            steps.forEachIndexed { index, rawStep ->
                val step = rawStep as? JsonValue.Obj ?: error("RUNTIME_STEP_NOT_OBJECT:$index")
                output = context.executeStep(index, step) ?: output
            }
            val result = output ?: error("RUNTIME_EMIT_MISSING")
            val outputBytes = JsonCodec.stringify(result).toByteArray(Charsets.UTF_8).size
            require(outputBytes <= action.maxOutputBytes) { "RUNTIME_OUTPUT_TOO_LARGE:$outputBytes" }
            SourceActionResponse(result, request.traceId, context.instructions)
        }.fold(
            onSuccess = { response ->
                diagnostics.emit(
                    event(
                        manifest, request, "ACTION_COMPLETED", DiagnosticSeverity.INFO,
                        durationMs = clockMs() - started,
                        attributes = mapOf("instructions" to response.instructionCount.toString()),
                    ),
                )
                SourcePlatformResult.Success(response)
            },
            onFailure = { error ->
                val message = error.message.orEmpty()
                val networkCode = if (message.startsWith("RUNTIME_NETWORK_")) {
                    val raw = message.substringAfter("RUNTIME_NETWORK_").substringBefore(':')
                    runCatching { SourceErrorCode.valueOf(raw) }.getOrNull()
                } else null
                val code = networkCode ?: when {
                    message.contains("BUDGET") || message.contains("TIMEOUT") -> SourceErrorCode.RUNTIME_BUDGET_EXCEEDED
                    message.contains("OUTPUT_TOO_LARGE") -> SourceErrorCode.RUNTIME_OUTPUT_TOO_LARGE
                    message.contains("RESOURCE") -> SourceErrorCode.RUNTIME_RESOURCE_MISSING
                    message.contains("TYPE") -> SourceErrorCode.RUNTIME_TYPE_ERROR
                    else -> SourceErrorCode.RUNTIME_INVALID_PROGRAM
                }
                diagnostics.emit(
                    event(
                        manifest, request, "ACTION_FAILED", DiagnosticSeverity.ERROR,
                        durationMs = clockMs() - started,
                        attributes = mapOf("code" to code.name, "error" to (error.message ?: error.javaClass.simpleName)),
                    ),
                )
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "RUNTIME_FAILED", request.traceId, error))
            },
        )
    }

    private fun failure(code: SourceErrorCode, message: String, request: SourceActionRequest): SourcePlatformResult.Failure =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId))

    private fun event(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        severity: DiagnosticSeverity,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ): DiagnosticEvent = DiagnosticEvent(
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
}

private class ExecutionContext(
    private val manifest: SourceManifest,
    private val request: SourceActionRequest,
    private val resources: SourceResourceProvider,
    private val instructionBudget: Int,
    private val memoryBudgetBytes: Int,
    private val deadlineMs: Long,
    private val diagnostics: DiagnosticSink,
    private val networkBroker: SourceNetworkBroker,
    private val browserBroker: SourceBrowserBroker,
    private val storageBroker: SourceStorageBroker,
    private val cryptoBroker: SourceCryptoBroker,
    private val webSocketBroker: SourceWebSocketBroker,
    private val clockMs: () -> Long,
) {
    val variables = linkedMapOf<String, JsonValue>()
    var instructions: Int = 0
        private set

    fun executeStep(index: Int, step: JsonValue.Obj): JsonValue? {
        checkBudget(1)
        val op = step.string("op") ?: error("RUNTIME_STEP_OP_MISSING:$index")
        val started = clockMs()
        val output = when (op) {
            "resourceJson" -> resourceJson(step)
            "template" -> template(step)
            "fetch" -> fetch(step)
            "browser" -> browser(step)
            "storageGet" -> storageGet(step)
            "storageSet" -> storageSet(step)
            "storageDelete" -> storageDelete(step)
            "crypto" -> crypto(step)
            "websocketExchange" -> websocketExchange(step)
            "parseJson" -> parseJson(step)
            "selectHtmlArray" -> selectHtmlArray(step)
            "selectHtmlObject" -> selectHtmlObject(step)
            "htmlParagraphs" -> htmlParagraphs(step)
            "projectArray" -> projectArray(step)
            "projectObject" -> projectObject(step)
            "composeObject" -> composeObject(step)
            "path" -> selectPath(step)
            "filterText" -> filterText(step)
            "filterArrayContains" -> filterArrayContains(step)
            "paginate" -> paginate(step)
            "find" -> find(step)
            "constant" -> constant(step)
            "emit" -> emit(step)
            else -> error("RUNTIME_OPERATION_UNSUPPORTED:$op")
        }
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = clockMs(),
                traceId = request.traceId,
                sourceId = manifest.id,
                sourceVersion = manifest.version.toString(),
                category = DiagnosticCategory.RUNTIME,
                name = "OPERATION_COMPLETED",
                severity = DiagnosticSeverity.DEBUG,
                durationMs = clockMs() - started,
                attributes = mapOf("index" to index.toString(), "op" to op, "instructions" to instructions.toString()),
            ),
        )
        return output
    }

    private fun resourceJson(step: JsonValue.Obj): JsonValue? {
        val path = step.requiredString("path")
        SourceManifest.requireSafeRelativePath(path)
        val bytes = resources.read(path, memoryBudgetBytes) ?: error("RUNTIME_RESOURCE_MISSING:$path")
        checkBudget(1 + bytes.size / 4096)
        val value = JsonCodec.parse(bytes.toString(Charsets.UTF_8), maxDepth = 64, maxNodes = instructionBudget)
        set(step.requiredString("as"), value)
        return null
    }

    private fun template(step: JsonValue.Obj): JsonValue? {
        val rendered = renderTemplate(step.requiredString("value"))
        set(step.requiredString("as"), JsonValue.Str(rendered))
        return null
    }

    private fun fetch(step: JsonValue.Obj): JsonValue? {
        val url = when {
            !step.string("url").isNullOrBlank() -> renderTemplate(step.requiredString("url"))
            !step.string("urlFrom").isNullOrBlank() -> scalar(variable(step.requiredString("urlFrom")))
            else -> error("RUNTIME_FETCH_URL_REQUIRED")
        }
        val method = step.string("method")?.uppercase(Locale.ROOT) ?: "GET"
        val headers = step.obj("headers")?.values.orEmpty().mapValues { (_, value) ->
            renderTemplate(scalar(value))
        }
        val bodyText = when {
            step.string("body") != null -> renderTemplate(step.string("body").orEmpty())
            step.string("bodyFrom") != null -> scalar(variable(step.requiredString("bodyFrom")))
            else -> ""
        }
        val responseMode = step.string("response")?.let {
            runCatching { SourceNetworkResponseMode.valueOf(it.uppercase(Locale.ROOT)) }
                .getOrElse { error("RUNTIME_FETCH_RESPONSE_MODE_INVALID:$it") }
        } ?: SourceNetworkResponseMode.TEXT
        val networkRequest = SourceNetworkRequest(
            sourceId = manifest.id,
            url = url,
            method = method,
            headers = headers,
            body = bodyText.toByteArray(Charsets.UTF_8),
            contentType = step.string("contentType"),
            responseMode = responseMode,
            allowHttpError = step.bool("allowHttpError") ?: false,
            timeoutMs = (deadlineMs - clockMs()).coerceIn(100L, 120_000L),
            traceId = request.traceId,
        )
        val response = when (val result = networkBroker.execute(manifest, networkRequest)) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("RUNTIME_NETWORK_${result.error.code}:${result.error.message}")
        }
        checkBudget(10 + response.body.size / 4096)
        set(step.requiredString("as"), responseJson(response, responseMode))
        return null
    }


    private fun browser(step: JsonValue.Obj): JsonValue? {
        val actionRaw = step.requiredString("action")
        val action = runCatching { SourceBrowserAction.valueOf(actionRaw.uppercase(Locale.ROOT)) }
            .getOrElse { error("RUNTIME_BROWSER_ACTION_INVALID:$actionRaw") }
        val url = step.string("url")?.let(::renderTemplate)
            ?: step.string("urlFrom")?.let { scalar(variable(it)) }
        val request = SourceBrowserRequest(
            sourceId = manifest.id,
            action = action,
            url = url,
            selector = step.string("selector")?.let(::renderTemplate),
            value = step.string("value")?.let(::renderTemplate)
                ?: step.string("valueFrom")?.let { scalar(variable(it)) },
            script = step.string("script")?.let(::renderTemplate),
            timeoutMs = (deadlineMs - clockMs()).coerceIn(100L, 120_000L),
            maxOutputBytes = step.int("maxOutputBytes")?.coerceIn(1024, 4 * 1024 * 1024) ?: 2 * 1024 * 1024,
            traceId = request.traceId,
        )
        val response = when (val result = browserBroker.execute(manifest, request)) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("RUNTIME_BROWSER_${result.error.code}:${result.error.message}")
        }
        checkBudget(10 + (response.value?.length ?: 0) / 4096)
        val metadata = response.requestMetadata.take(500).map { item ->
            JsonValue.Obj(linkedMapOf(
                "url" to JsonValue.Str(item.url),
                "method" to JsonValue.Str(item.method),
                "mainFrame" to JsonValue.Bool(item.mainFrame),
                "resourceType" to (item.resourceType?.let(JsonValue::Str) ?: JsonValue.Null),
                "headerNames" to JsonValue.Arr(item.headerNames.sorted().map(JsonValue::Str)),
                "timestampEpochMs" to JsonValue.Num(item.timestampEpochMs.toDouble(), item.timestampEpochMs.toString()),
            ))
        }
        set(step.requiredString("as"), JsonValue.Obj(linkedMapOf(
            "url" to (response.finalUrl?.let(JsonValue::Str) ?: JsonValue.Null),
            "title" to (response.title?.let(JsonValue::Str) ?: JsonValue.Null),
            "value" to (response.value?.let(JsonValue::Str) ?: JsonValue.Null),
            "requests" to JsonValue.Arr(metadata),
            "degradedIsolation" to JsonValue.Bool(response.degradedIsolation),
            "rendererRecovered" to JsonValue.Bool(response.rendererRecovered),
        )))
        return null
    }

    private fun storageGet(step: JsonValue.Obj): JsonValue? {
        val key = renderTemplate(step.requiredString("key"))
        val storageRequest = SourceStorageRequest(manifest.id, key, traceId = request.traceId)
        val bytes = when (val result = storageBroker.get(manifest, storageRequest)) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("RUNTIME_STORAGE_${result.error.code}:${result.error.message}")
        }
        val mode = step.string("encoding")?.uppercase(Locale.ROOT) ?: "TEXT"
        val value = when {
            bytes == null -> JsonValue.Null
            mode == "BASE64" -> JsonValue.Str(Base64.getEncoder().encodeToString(bytes))
            else -> JsonValue.Str(bytes.toString(Charsets.UTF_8))
        }
        set(step.requiredString("as"), value)
        return null
    }

    private fun storageSet(step: JsonValue.Obj): JsonValue? {
        val key = renderTemplate(step.requiredString("key"))
        val raw = step.string("value")?.let(::renderTemplate)
            ?: step.string("from")?.let { scalar(variable(it)) }
            ?: ""
        val bytes = if (step.string("encoding")?.equals("BASE64", true) == true) {
            runCatching { Base64.getDecoder().decode(raw) }.getOrElse { error("RUNTIME_STORAGE_BASE64_INVALID") }
        } else raw.toByteArray(Charsets.UTF_8)
        val storageRequest = SourceStorageRequest(manifest.id, key, bytes, request.traceId)
        when (val result = storageBroker.put(manifest, storageRequest)) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> error("RUNTIME_STORAGE_${result.error.code}:${result.error.message}")
        }
        step.string("as")?.let { set(it, JsonValue.Bool(true)) }
        return null
    }

    private fun storageDelete(step: JsonValue.Obj): JsonValue? {
        val key = renderTemplate(step.requiredString("key"))
        when (val result = storageBroker.delete(manifest, SourceStorageRequest(manifest.id, key, traceId = request.traceId))) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> error("RUNTIME_STORAGE_${result.error.code}:${result.error.message}")
        }
        step.string("as")?.let { set(it, JsonValue.Bool(true)) }
        return null
    }

    private fun crypto(step: JsonValue.Obj): JsonValue? {
        val operationRaw = step.requiredString("operation")
        val operation = runCatching { SourceCryptoOperation.valueOf(operationRaw.uppercase(Locale.ROOT)) }
            .getOrElse { error("RUNTIME_CRYPTO_OPERATION_INVALID:$operationRaw") }
        val raw = step.string("value")?.let(::renderTemplate)
            ?: step.string("from")?.let { scalar(variable(it)) }
            ?: ""
        val payload = if (step.string("inputEncoding")?.equals("BASE64", true) == true) {
            runCatching { Base64.getDecoder().decode(raw) }.getOrElse { error("RUNTIME_CRYPTO_BASE64_INVALID") }
        } else raw.toByteArray(Charsets.UTF_8)
        val keyMaterial = step.string("key")?.let(::renderTemplate)?.toByteArray(Charsets.UTF_8)
        val resultBytes = when (val result = cryptoBroker.execute(manifest, SourceCryptoRequest(
            sourceId = manifest.id,
            operation = operation,
            payload = payload,
            keyMaterial = keyMaterial,
            associatedData = step.string("associatedData")?.let(::renderTemplate)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
            traceId = request.traceId,
        ))) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("RUNTIME_CRYPTO_${result.error.code}:${result.error.message}")
        }
        val output = if (step.string("outputEncoding")?.equals("TEXT", true) == true) {
            JsonValue.Str(resultBytes.toString(Charsets.UTF_8))
        } else JsonValue.Str(Base64.getEncoder().encodeToString(resultBytes))
        set(step.requiredString("as"), output)
        return null
    }

    private fun websocketExchange(step: JsonValue.Obj): JsonValue? {
        val url = step.string("url")?.let(::renderTemplate)
            ?: step.string("urlFrom")?.let { scalar(variable(it)) }
            ?: error("RUNTIME_WEBSOCKET_URL_REQUIRED")
        val messages = step.array("messages")?.values.orEmpty().map { renderTemplate(scalar(it)) }
        val headers = step.obj("headers")?.values.orEmpty().mapValues { renderTemplate(scalar(it.value)) }
        val response = when (val result = webSocketBroker.exchange(manifest, SourceWebSocketRequest(
            sourceId = manifest.id,
            url = url,
            headers = headers,
            messages = messages,
            maxResponses = step.int("maxResponses")?.coerceIn(1, 100) ?: 1,
            timeoutMs = (deadlineMs - clockMs()).coerceIn(100L, manifest.capabilities.websocket.maxLifetimeMs),
            traceId = request.traceId,
        ))) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("RUNTIME_WEBSOCKET_${result.error.code}:${result.error.message}")
        }
        set(step.requiredString("as"), JsonValue.Obj(linkedMapOf(
            "messages" to JsonValue.Arr(response.messages.map(JsonValue::Str)),
            "closeCode" to (response.closeCode?.let { JsonValue.Num(it.toDouble(), it.toString()) } ?: JsonValue.Null),
            "closeReason" to (response.closeReason?.let(JsonValue::Str) ?: JsonValue.Null),
        )))
        return null
    }

    private fun parseJson(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from"))
        val text = when (source) {
            is JsonValue.Str -> source.value
            else -> scalar(resolvePath(source, step.string("path").orEmpty()) ?: source)
        }
        val value = JsonCodec.parse(text, maxDepth = 64, maxNodes = instructionBudget)
        set(step.requiredString("as"), value)
        return null
    }

    private fun selectHtmlArray(step: JsonValue.Obj): JsonValue? {
        val html = htmlText(step)
        val baseUrl = htmlBaseUrl(step)
        val document = Jsoup.parse(html, baseUrl)
        val selector = step.requiredString("selector")
        val fields = step.obj("fields") ?: error("RUNTIME_HTML_FIELDS_REQUIRED")
        val limit = step.int("limit")?.coerceIn(1, 5_000) ?: 2_000
        val items = document.select(selector).take(limit).mapIndexed { index, element ->
            checkBudget(2 + fields.values.size)
            htmlProjection(element, fields, index)
        }
        set(step.requiredString("as"), JsonValue.Arr(items))
        return null
    }

    private fun selectHtmlObject(step: JsonValue.Obj): JsonValue? {
        val document = Jsoup.parse(htmlText(step), htmlBaseUrl(step))
        val selector = step.requiredString("selector")
        val fields = step.obj("fields") ?: error("RUNTIME_HTML_FIELDS_REQUIRED")
        val element = document.selectFirst(selector)
        set(step.requiredString("as"), element?.let { htmlProjection(it, fields, 0) } ?: JsonValue.Null)
        return null
    }

    private fun htmlParagraphs(step: JsonValue.Obj): JsonValue? {
        val document = Jsoup.parse(htmlText(step), htmlBaseUrl(step))
        val content = document.selectFirst(step.requiredString("selector"))?.clone()
            ?: error("RUNTIME_HTML_CONTENT_SELECTOR_NOT_FOUND")
        step.string("remove")?.takeIf(String::isNotBlank)?.let { content.select(it).remove() }
        content.select("br").forEach { it.after("\n") }
        content.select("p,div,section,article,blockquote,li,h1,h2,h3,h4,h5,h6").forEach { it.after("\n") }
        val paragraphs = content.wholeText().split(Regex("\\n+")).map { it.trim() }
            .filter(String::isNotBlank).take(step.int("limit")?.coerceIn(1, 20_000) ?: 10_000)
        checkBudget(1 + paragraphs.size)
        set(step.requiredString("as"), JsonValue.Arr(paragraphs.map(JsonValue::Str)))
        return null
    }

    private fun htmlText(step: JsonValue.Obj): String {
        val source = variable(step.requiredString("from"))
        val value = step.string("path")?.let { resolvePath(source, it) } ?: source
        return when (value) {
            is JsonValue.Str -> value.value
            else -> scalar(value)
        }
    }

    private fun htmlBaseUrl(step: JsonValue.Obj): String {
        step.string("baseUrl")?.let { return renderTemplate(it) }
        step.string("baseUrlFrom")?.let { name ->
            val source = variable(name)
            val value = step.string("baseUrlPath")?.let { resolvePath(source, it) } ?: source
            return scalar(value)
        }
        return manifest.origins.first()
    }

    private fun htmlProjection(element: Element, fields: JsonValue.Obj, index: Int): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            fields.values.forEach { (name, rawSpec) ->
                val spec = rawSpec as? JsonValue.Obj
                val value: JsonValue = when {
                    rawSpec is JsonValue.Str -> JsonValue.Str(element.selectFirst(rawSpec.value)?.text().orEmpty())
                    spec == null -> rawSpec
                    spec["value"] != null -> spec["value"] ?: JsonValue.Null
                    spec.string("template") != null -> {
                        val raw = renderTemplate(spec.string("template").orEmpty())
                        val transformed = if (spec.bool("sha256") == true) java.security.MessageDigest.getInstance("SHA-256")
                            .digest(raw.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) } else raw
                        JsonValue.Str(transformed)
                    }
                    spec.bool("index") == true -> JsonValue.Num(index.toDouble(), index.toString())
                    spec.bool("texts") == true -> {
                        val selected = spec.string("select")?.let(element::select) ?: org.jsoup.select.Elements(element)
                        JsonValue.Arr(selected.map { JsonValue.Str(it.text().trim()) }.filter { it.value.isNotBlank() })
                    }
                    else -> {
                        val selected = spec.string("select")?.takeIf(String::isNotBlank)?.let(element::selectFirst) ?: element
                        val extracted = when {
                            spec.string("attr") != null && spec.bool("absolute") == true -> selected.absUrl(spec.string("attr").orEmpty())
                            spec.string("attr") != null -> selected.attr(spec.string("attr").orEmpty())
                            spec.bool("html") == true -> selected.html()
                            spec.bool("outerHtml") == true -> selected.outerHtml()
                            else -> selected.text()
                        }.trim()
                        val transformed = when {
                            spec.bool("sha256") == true -> java.security.MessageDigest.getInstance("SHA-256")
                                .digest(extracted.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
                            else -> extracted
                        }
                        if (transformed.isBlank() && spec["default"] != null) spec["default"] ?: JsonValue.Null else JsonValue.Str(transformed)
                    }
                }
                put(name, value)
            }
        })

    private fun projectArray(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from")) as? JsonValue.Arr ?: error("RUNTIME_TYPE_ARRAY_REQUIRED")
        val fields = step.obj("fields") ?: error("RUNTIME_PROJECT_FIELDS_REQUIRED")
        val limit = step.int("limit")?.coerceIn(1, 2_000) ?: 2_000
        val projected = source.values.take(limit).mapIndexedNotNull { index, item ->
            checkBudget(1 + fields.values.size)
            if (item !is JsonValue.Obj) return@mapIndexedNotNull null
            variables["item"] = item
            variables["itemIndex"] = JsonValue.Num(index.toDouble(), index.toString())
            JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
                fields.values.forEach { (name, spec) -> put(name, projectValue(spec, item)) }
            })
        }
        variables.remove("item")
        variables.remove("itemIndex")
        set(step.requiredString("as"), JsonValue.Arr(projected))
        return null
    }

    private fun projectObject(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from"))
        val fields = step.obj("fields") ?: error("RUNTIME_PROJECT_FIELDS_REQUIRED")
        variables["item"] = source
        val projected = JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            fields.values.forEach { (name, spec) -> put(name, projectValue(spec, source)) }
        })
        variables.remove("item")
        set(step.requiredString("as"), projected)
        return null
    }

    private fun projectValue(spec: JsonValue, item: JsonValue): JsonValue = when (spec) {
        is JsonValue.Str -> resolvePath(item, spec.value) ?: JsonValue.Null
        is JsonValue.Obj -> {
            val value = when {
                spec.string("template") != null -> JsonValue.Str(renderTemplate(spec.string("template").orEmpty()))
                spec.string("path") != null -> resolvePath(item, spec.string("path").orEmpty())
                spec["value"] != null -> spec["value"]
                else -> null
            }
            value ?: spec["default"] ?: JsonValue.Null
        }
        else -> spec
    }

    private fun composeObject(step: JsonValue.Obj): JsonValue? {
        val fields = step.obj("fields") ?: error("RUNTIME_COMPOSE_FIELDS_REQUIRED")
        val output = JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            fields.values.forEach { (name, raw) ->
                val spec = raw as? JsonValue.Obj
                val value = when {
                    spec == null -> raw
                    spec["value"] != null -> spec["value"] ?: JsonValue.Null
                    spec.string("template") != null -> JsonValue.Str(renderTemplate(spec.string("template").orEmpty()))
                    spec.string("from") != null -> {
                        val source = variable(spec.string("from").orEmpty())
                        spec.string("path")?.let { resolvePath(source, it) } ?: source
                    }
                    else -> JsonValue.Null
                }
                put(name, value)
            }
        })
        set(step.requiredString("as"), output)
        return null
    }

    private fun selectPath(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from"))
        val value = resolvePath(source, step.requiredString("path")) ?: JsonValue.Null
        set(step.requiredString("as"), value)
        return null
    }

    private fun filterText(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from")) as? JsonValue.Arr ?: error("RUNTIME_TYPE_ARRAY_REQUIRED")
        val fields = step.stringArray("fields")
        val query = inputString(step.requiredString("queryInput"))
        val needle = normalize(query)
        val filtered = if (needle.isBlank()) source.values else source.values.filter { value ->
            checkBudget(1)
            val obj = value as? JsonValue.Obj ?: return@filter false
            fields.any { field -> normalize(obj.string(field).orEmpty()).contains(needle) }
        }
        set(step.requiredString("as"), JsonValue.Arr(filtered))
        return null
    }

    private fun filterArrayContains(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from")) as? JsonValue.Arr ?: error("RUNTIME_TYPE_ARRAY_REQUIRED")
        val field = step.requiredString("field")
        val needle = normalize(inputString(step.requiredString("queryInput")))
        val filtered = if (needle.isBlank()) source.values else source.values.filter { value ->
            checkBudget(1)
            val obj = value as? JsonValue.Obj ?: return@filter false
            obj.array(field)?.values.orEmpty().any { item -> normalize((item as? JsonValue.Str)?.value.orEmpty()) == needle }
        }
        set(step.requiredString("as"), JsonValue.Arr(filtered))
        return null
    }

    private fun paginate(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from")) as? JsonValue.Arr ?: error("RUNTIME_TYPE_ARRAY_REQUIRED")
        val page = inputInt(step.requiredString("pageInput"), 1).coerceAtLeast(1)
        val pageSize = step.int("pageSize")?.coerceIn(1, 200) ?: 20
        val start = ((page - 1).toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val end = min(source.values.size, start + pageSize)
        val items = if (start >= source.values.size) emptyList() else source.values.subList(start, end)
        checkBudget(items.size)
        val result = JsonValue.Obj(linkedMapOf(
            "items" to JsonValue.Arr(items),
            "nextPage" to if (end < source.values.size) JsonValue.Num((page + 1).toDouble(), (page + 1).toString()) else JsonValue.Null,
        ))
        set(step.requiredString("as"), result)
        return null
    }

    private fun find(step: JsonValue.Obj): JsonValue? {
        val source = variable(step.requiredString("from")) as? JsonValue.Arr ?: error("RUNTIME_TYPE_ARRAY_REQUIRED")
        val fields = step.stringArray("fields")
        val expected = inputString(step.requiredString("input"))
        val result = source.values.firstOrNull { value ->
            checkBudget(1)
            val obj = value as? JsonValue.Obj ?: return@firstOrNull false
            fields.any { field -> obj.string(field) == expected }
        } ?: JsonValue.Null
        set(step.requiredString("as"), result)
        return null
    }

    private fun constant(step: JsonValue.Obj): JsonValue? {
        val value = step["value"] ?: JsonValue.Null
        set(step.requiredString("as"), value)
        return null
    }

    private fun emit(step: JsonValue.Obj): JsonValue = variable(step.requiredString("from"))

    private fun variable(name: String): JsonValue = variables[name] ?: error("RUNTIME_VARIABLE_MISSING:$name")

    private fun set(name: String, value: JsonValue) {
        require(VARIABLE.matches(name)) { "RUNTIME_VARIABLE_NAME_INVALID" }
        val approximateBytes = JsonCodec.stringify(value).toByteArray(Charsets.UTF_8).size
        require(approximateBytes <= memoryBudgetBytes) { "RUNTIME_MEMORY_BUDGET_EXCEEDED" }
        variables[name] = value
    }

    private fun resolvePath(root: JsonValue, path: String): JsonValue? {
        if (path.isBlank()) return root
        var current: JsonValue? = root
        path.split('.').forEach { segment ->
            checkBudget(1)
            current = when (val value = current) {
                is JsonValue.Obj -> value[segment]
                is JsonValue.Arr -> segment.toIntOrNull()?.let(value.values::getOrNull)
                else -> null
            }
        }
        return current
    }

    private fun responseJson(response: SourceNetworkResponse, mode: SourceNetworkResponseMode): JsonValue.Obj {
        val body = when (mode) {
            SourceNetworkResponseMode.JSON -> JsonCodec.parse(response.bodyText(), maxDepth = 64, maxNodes = instructionBudget)
            SourceNetworkResponseMode.TEXT -> JsonValue.Str(response.bodyText())
            SourceNetworkResponseMode.BASE64, SourceNetworkResponseMode.BYTES -> JsonValue.Str(Base64.getEncoder().encodeToString(response.body))
        }
        val headers = linkedMapOf<String, JsonValue>()
        response.headers.toSortedMap().forEach { (name, values) ->
            headers[name] = JsonValue.Arr(values.take(32).map(JsonValue::Str))
        }
        return JsonValue.Obj(linkedMapOf(
            "status" to JsonValue.Num(response.statusCode.toDouble(), response.statusCode.toString()),
            "url" to JsonValue.Str(response.finalUrl),
            "headers" to JsonValue.Obj(headers),
            "body" to body,
            "redirectCount" to JsonValue.Num(response.redirectChain.size.toDouble(), response.redirectChain.size.toString()),
            "fromReplay" to JsonValue.Bool(response.fromReplay),
        ))
    }

    private fun renderTemplate(template: String): String = TEMPLATE.replace(template) { match ->
        val expression = match.groupValues[1].trim()
        val parts = expression.split('|').map(String::trim)
        val raw = resolveTemplateValue(parts.first())
        parts.drop(1).fold(raw) { value, filter ->
            when (filter.lowercase(Locale.ROOT)) {
                "urlencode" -> URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
                "lower" -> value.lowercase(Locale.ROOT)
                "upper" -> value.uppercase(Locale.ROOT)
                "trim" -> value.trim()
                else -> error("RUNTIME_TEMPLATE_FILTER_UNSUPPORTED:$filter")
            }
        }
    }

    private fun resolveTemplateValue(path: String): String {
        val segments = path.split('.')
        val root = when (segments.firstOrNull()) {
            "input" -> request.input
            else -> variables[segments.firstOrNull()] ?: error("RUNTIME_TEMPLATE_VARIABLE_MISSING:$path")
        }
        val tail = if (segments.firstOrNull() == "input") segments.drop(1) else segments.drop(1)
        val value = if (tail.isEmpty()) root else resolvePath(root, tail.joinToString(".")) ?: JsonValue.Null
        return scalar(value)
    }

    private fun scalar(value: JsonValue): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> ""
        else -> JsonCodec.stringify(value)
    }

    private fun inputString(name: String): String = when (val value = request.input[name]) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        null, JsonValue.Null -> ""
        else -> error("RUNTIME_INPUT_SCALAR_REQUIRED:$name")
    }

    private fun inputInt(name: String, default: Int): Int = when (val value = request.input[name]) {
        is JsonValue.Num -> value.value.toInt()
        is JsonValue.Str -> value.value.toIntOrNull() ?: default
        else -> default
    }

    private fun checkBudget(cost: Int) {
        instructions += cost.coerceAtLeast(1)
        require(instructions <= instructionBudget) { "RUNTIME_INSTRUCTION_BUDGET_EXCEEDED" }
        require(clockMs() <= deadlineMs) { "RUNTIME_TIMEOUT" }
    }

    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .lowercase(Locale.ROOT)
        .trim()

    private fun JsonValue.Obj.requiredString(name: String): String = string(name)?.takeIf(String::isNotBlank)
        ?: error("RUNTIME_FIELD_REQUIRED:$name")

    private fun JsonValue.Obj.stringArray(name: String): List<String> = array(name)?.values.orEmpty().map { value ->
        (value as? JsonValue.Str)?.value ?: error("RUNTIME_STRING_ARRAY_REQUIRED:$name")
    }

    companion object {
        private val VARIABLE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val TEMPLATE = Regex("\\{\\{([^{}]{1,256})}}")
    }
}
