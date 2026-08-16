package vn.nghetruyen.source.vbook

import com.nghetruyen.source.sandbox.JsSandboxException
import com.nghetruyen.source.sandbox.JsSandboxFailure
import com.nghetruyen.source.sandbox.JsSandboxPolicy
import com.nghetruyen.source.sandbox.RhinoExecutionBudget
import com.nghetruyen.source.sandbox.SafeRhinoExecutor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceBrowserAction
import vn.nghetruyen.source.api.SourceBrowserDialog
import vn.nghetruyen.source.api.SourceBrowserRequest
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceCryptoRequest
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceGraphicsDrawOperation
import vn.nghetruyen.source.api.SourceGraphicsRequest
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNativeHookRequest
import vn.nghetruyen.source.api.SourceNetworkRequest
import vn.nghetruyen.source.api.SourceNetworkResponseMode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.api.SourceTranslationRequest
import vn.nghetruyen.source.api.SourceWebSocketRequest
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.util.Locale
import kotlin.math.max

class VBookJsRuntime(
    private val brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) {
    fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        val started = clockMs()
        if (manifest.runtime.mode !in setOf(SourceRuntimeMode.VBOOK_JS_COMPAT, SourceRuntimeMode.NATIVE_LUA_COMPAT)) {
            return failure(SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE, "VBOOK_RUNTIME_MODE_REQUIRED", request)
        }
        val action = manifest.actions[request.action]
            ?: return failure(SourceErrorCode.ACTION_NOT_FOUND, "SOURCE_ACTION_NOT_FOUND:${request.action}", request)
        val timeoutMs = action.timeoutMs ?: manifest.runtime.actionTimeoutMs
        diagnostics.emit(event(manifest, request, "VBOOK_ACTION_STARTED", attributes = mapOf(
            "action" to request.action.name,
            "timeoutMs" to timeoutMs.toString(),
            "deadlineEpochMs" to (started + timeoutMs).toString(),
        )))
        runCatching { JsonCodec.stringify(request.input) }.getOrNull()?.let { input ->
            captureEvidence(manifest, request, "executor-input.json", "application/json", input, mapOf("action" to request.action.name))
        }
        return runCatching {
            sandboxExecutor(manifest, timeoutMs).execute { cx, scope, budget ->
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_SANDBOX_ENTERED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "action" to request.action.name,
                    "timeoutMs" to timeoutMs.toString(),
                    "instructionBudget" to manifest.runtime.instructionBudget.toString(),
                    "memoryBudgetBytes" to manifest.runtime.memoryBudgetBytes.toString(),
                    "effectiveMemoryBudgetBytes" to (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toString(),
                    "hardInstructionLimit" to (manifest.runtime.instructionBudget.toLong() * if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64L else 16L).toString(),
                )))
                VBookSafeRhinoBoundary.installCurrentContext()
                installHostApi(cx, scope, manifest, resources, request, budget)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_HOST_API_READY", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                cx.evaluateString(scope, BOOTSTRAP, "vbook-bootstrap", 1, null)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_BOOTSTRAP_EVALUATED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "instructions" to budget.instructions.toString(),
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                val loaded = linkedSetOf<String>()
                val loader = ScriptLoader(cx, scope, resources, loaded, budget) { resourcePath, bytes ->
                    diagnostics.emit(event(manifest, request, "VBOOK_RESOURCE_LOADED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "path" to resourcePath.take(300),
                        "bytes" to bytes.toString(),
                        "loadedCount" to loaded.size.toString(),
                        "instructions" to budget.instructions.toString(),
                    )))
                }
                loader.install()
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_ENTRY_LOADING", DiagnosticSeverity.DEBUG, attributes = mapOf("entry" to action.entry.take(300))))
                loader.load(action.entry)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_ENTRY_LOADED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "entry" to action.entry.take(300),
                    "loadedResources" to loaded.size.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                val execute = ScriptableObject.getProperty(scope, "execute") as? Function
                    ?: error("VBOOK_EXECUTE_FUNCTION_MISSING:${action.entry}")
                val args = actionArguments(cx, scope, manifest, request)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_EXECUTOR_CALL", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "action" to request.action.name,
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                val rawResult = execute.call(cx, scope, scope, args)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_EXECUTOR_RETURNED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "resultType" to (rawResult?.javaClass?.simpleName ?: "null"),
                    "instructions" to budget.instructions.toString(),
                )))
                ScriptableObject.putProperty(scope, "__ngheResult", rawResult)
                val json = Context.toString(cx.evaluateString(scope, "JSON.stringify(__ngheResult)", "vbook-result", 1, null))
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_STRINGIFIED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "bytes" to json.toByteArray(Charsets.UTF_8).size.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                captureEvidence(manifest, request, "executor-result-raw.json", "application/json", json, mapOf("action" to request.action.name))
                require(json != "undefined" && json.toByteArray(Charsets.UTF_8).size <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                val parsed = JsonCodec.parse(json, maxDepth = 96, maxNodes = manifest.runtime.instructionBudget)
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_PARSED", DiagnosticSeverity.DEBUG, attributes = mapOf("instructions" to budget.instructions.toString())))
                val normalized = normalizeResult(request, parsed)
                val normalizedJson = JsonCodec.stringify(normalized)
                val bytes = normalizedJson.toByteArray(Charsets.UTF_8).size
                diagnostics.emit(event(manifest, request, "VBOOK_STAGE_RESULT_NORMALIZED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "bytes" to bytes.toString(),
                    "instructions" to budget.instructions.toString(),
                )))
                captureEvidence(manifest, request, "executor-result-normalized.json", "application/json", normalizedJson, mapOf("action" to request.action.name))
                require(bytes <= action.maxOutputBytes) { "VBOOK_OUTPUT_TOO_LARGE" }
                SourceActionResponse(normalized, request.traceId, budget.instructions.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }.value
        }.fold(
            onSuccess = { response ->
                diagnostics.emit(event(manifest, request, "VBOOK_ACTION_COMPLETED", durationMs = clockMs() - started, attributes = mapOf("instructions" to response.instructionCount.toString())))
                SourcePlatformResult.Success(response)
            },
            onFailure = { error ->
                val message = error.message.orEmpty()
                val code = when {
                    "ACTION_NOT_FOUND" in message -> SourceErrorCode.ACTION_NOT_FOUND
                    error is JsSandboxException && error.failure in setOf(
                        JsSandboxFailure.TIMEOUT,
                        JsSandboxFailure.INSTRUCTION_LIMIT,
                        JsSandboxFailure.MEMORY_LIMIT,
                    ) -> SourceErrorCode.RUNTIME_BUDGET_EXCEEDED
                    "TIMEOUT" in message || "INSTRUCTION" in message || "MEMORY" in message || "HEAP" in message.uppercase(Locale.ROOT) -> SourceErrorCode.RUNTIME_BUDGET_EXCEEDED
                    "OUTPUT_TOO_LARGE" in message -> SourceErrorCode.RUNTIME_OUTPUT_TOO_LARGE
                    "NETWORK_" in message -> SourceErrorCode.NETWORK_IO_ERROR
                    "BROWSER_" in message -> SourceErrorCode.BROWSER_UNAVAILABLE
                    else -> SourceErrorCode.VBOOK_SCRIPT_ERROR
                }
                diagnostics.emit(event(manifest, request, "VBOOK_ACTION_FAILED", DiagnosticSeverity.ERROR, clockMs() - started, mapOf("code" to code.name, "error" to (error.message ?: error.javaClass.simpleName))))
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "VBOOK_RUNTIME_FAILED", request.traceId, error))
            },
        )
    }

    fun validateScripts(manifest: SourceManifest, resources: SourceResourceProvider): VBookCompatibilityReport {
        val results = manifest.actions.map { (action, spec) ->
            runCatching {
                sandboxExecutor(manifest, manifest.runtime.actionTimeoutMs).execute { cx, scope, budget ->
                    VBookSafeRhinoBoundary.installCurrentContext()
                    installHostApi(cx, scope, manifest, resources, SourceActionRequest(manifest.id, action), budget)
                    cx.evaluateString(scope, BOOTSTRAP, "vbook-bootstrap", 1, null)
                    ScriptLoader(cx, scope, resources, linkedSetOf(), budget).apply { install(); load(spec.entry) }
                    require(ScriptableObject.getProperty(scope, "execute") is Function) { "VBOOK_EXECUTE_FUNCTION_MISSING" }
                }
            }.fold(
                { VBookActionCompatibility(action, true, "OK") },
                { VBookActionCompatibility(action, false, it.message ?: it.javaClass.simpleName) },
            )
        }
        return VBookCompatibilityReport(results, results.all(VBookActionCompatibility::compatible))
    }

    private fun sandboxExecutor(manifest: SourceManifest, timeoutMs: Long): SafeRhinoExecutor = SafeRhinoExecutor(
        policy = JsSandboxPolicy(
            maxInstructions = manifest.runtime.instructionBudget.toLong(),
            wallClockTimeoutMs = timeoutMs,
            instructionObserverThreshold = 1_000,
            maxHeapGrowthBytes = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toLong(),
            maxResultUnits = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).coerceAtLeast(1),
            maxCollectionItems = 20_000,
            maxValueDepth = 96,
            languageVersion = Context.VERSION_ES6,
            hardInstructionMultiplier = if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64 else 16,
        ),
        clockMs = clockMs,
    )

    private fun installHostApi(
        cx: Context,
        scope: Scriptable,
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
        budget: RhinoExecutionBudget,
    ) {
        fun fetchResponse(url: String, options: Scriptable?): FetchResponseObject {
            budget.charge(50)
            val method = options?.propertyString("method")?.uppercase(Locale.ROOT) ?: "GET"
            val headerObject = options?.propertyObject("headers")
            val headers = headerObject?.ids?.associate { id ->
                id.toString() to Context.toString(ScriptableObject.getProperty(headerObject, id.toString()))
            }.orEmpty()
            val body = options?.propertyString("body").orEmpty().toByteArray(Charsets.UTF_8)
            val response = when (val result = brokers.network.execute(manifest, SourceNetworkRequest(
                sourceId = manifest.id,
                url = url,
                method = method,
                headers = headers,
                body = body,
                contentType = options?.propertyString("contentType"),
                responseMode = SourceNetworkResponseMode.TEXT,
                allowHttpError = true,
                timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, 120_000L),
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("VBOOK_NETWORK_${result.error.code}:${result.error.message}")
            }
            return FetchResponseObject(cx, scope, response.statusCode, response.finalUrl, response.headers, response.bodyText())
        }

        ScriptableObject.putProperty(scope, "fetch", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any =
                fetchResponse(Context.toString(args.getOrNull(0) ?: ""), args.getOrNull(1) as? Scriptable)
        })

        val http = cx.newObject(scope).also { obj ->
            ScriptableObject.putProperty(obj, "get", hostFunction { args ->
                fetchResponse(Context.toString(args.getOrNull(0) ?: ""), httpOptions(cx, scope, "GET", null, args.getOrNull(1) as? Scriptable))
            })
            ScriptableObject.putProperty(obj, "post", hostFunction { args ->
                fetchResponse(Context.toString(args.getOrNull(0) ?: ""), httpOptions(cx, scope, "POST", args.getOrNull(1), args.getOrNull(2) as? Scriptable))
            })
            ScriptableObject.putProperty(obj, "fetch", ScriptableObject.getProperty(scope, "fetch"))
        }
        ScriptableObject.putProperty(scope, "Http", http)
        ScriptableObject.putProperty(scope, "HTTP", http)

        val html = cx.newObject(scope).also { obj ->
            ScriptableObject.putProperty(obj, "parse", hostFunction { args ->
                val content = Context.toString(args.getOrNull(0) ?: "")
                val baseUrl = Context.toString(args.getOrNull(1) ?: request.input.string("url").orEmpty())
                JsoupDocumentObject(Jsoup.parse(content, baseUrl), scope)
            })
        }
        ScriptableObject.putProperty(scope, "Html", html)
        ScriptableObject.putProperty(scope, "HTML", html)
        ScriptableObject.putProperty(scope, "Document", html)

        val storage = storageObject(cx, scope, manifest, request)
        ScriptableObject.putProperty(scope, "Storage", storage)
        ScriptableObject.putProperty(scope, "localConfig", localConfigObject(cx, scope, resources))
        ScriptableObject.putProperty(scope, "localStorage", storage)
        ScriptableObject.putProperty(scope, "cacheStorage", PrefixedStorageObject(storage, "cache:", scope))
        ScriptableObject.putProperty(scope, "Crypto", cryptoObject(cx, scope, manifest, request))
        ScriptableObject.putProperty(scope, "Graphics", graphicsObject(cx, scope, manifest, request))
        ScriptableObject.putProperty(scope, "Browser", browserObject(cx, scope, manifest, request, budget))
        ScriptableObject.putProperty(scope, "Engine", engineObject(cx, scope, manifest, request, budget))
        val websocketHost = websocketObject(cx, scope, manifest, request, budget)
        ScriptableObject.putProperty(scope, "WebSocketHost", websocketHost)
        ScriptableObject.putProperty(scope, "WebSocket", websocketConstructor(cx, scope, manifest, request, budget))
        ScriptableObject.putProperty(scope, "UserAgent", userAgentObject(cx, scope))
        ScriptableObject.putProperty(scope, "localCookie", localCookieObject(cx, scope, manifest))
        ScriptableObject.putProperty(scope, "Script", scriptObject(cx, scope, resources, budget))
        ScriptableObject.putProperty(scope, "Qt", translationObject(cx, scope, manifest, request))
        ScriptableObject.putProperty(scope, "sleep", hostFunction { args ->
            val millis = Context.toNumber(args.getOrNull(0) ?: 0).toLong().coerceIn(0L, 2_000L)
            budget.charge((millis / 10L).toInt().coerceAtLeast(1))
            if (millis > 0) Thread.sleep(millis)
            true
        })
        val log = diagnosticLogObject(cx, scope, manifest, request)
        ScriptableObject.putProperty(scope, "Log", log)
        ScriptableObject.putProperty(scope, "Console", log)
        ScriptableObject.putProperty(scope, "console", log)

        ScriptableObject.putProperty(scope, "__bridge", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                val operation = Context.toString(args.getOrNull(0) ?: "")
                if (operation == "host_command") {
                    val inputObject = args.getOrNull(1) as? Scriptable ?: error("SOURCE_HOST_COMMAND_INPUT_REQUIRED")
                    ScriptableObject.putProperty(scope, "__ngheHostCommandInput", inputObject)
                    val inputJson = try {
                        Context.toString(cx.evaluateString(
                            scope,
                            "JSON.stringify(__ngheHostCommandInput)",
                            "host-command-input",
                            1,
                            null,
                        ))
                    } finally {
                        ScriptableObject.deleteProperty(scope, "__ngheHostCommandInput")
                    }
                    budget.charge(25 + inputJson.toByteArray(Charsets.UTF_8).size / 256)
                    diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_HOST_COMMAND_STARTED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "inputBytes" to inputJson.toByteArray(Charsets.UTF_8).size.toString(),
                        "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                    )))
                    val output = when (val result = vn.nghetruyen.source.api.SourceHostKernelWireExecutor.execute(
                        broker = brokers.hostKernel,
                        sourceId = manifest.id,
                        rawCommandJson = inputJson,
                        traceId = request.traceId,
                    )) {
                        is SourcePlatformResult.Success -> {
                            diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_HOST_COMMAND_COMPLETED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                                "outputBytes" to result.value.toByteArray(Charsets.UTF_8).size.toString(),
                                "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                            )))
                            result.value
                        }
                        is SourcePlatformResult.Failure -> {
                            diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_HOST_COMMAND_FAILED", DiagnosticSeverity.ERROR, attributes = mapOf(
                                "code" to result.error.code.name,
                                "message" to result.error.message.take(500),
                            )))
                            error("VBOOK_HOST_COMMAND_${result.error.code}:${result.error.message}")
                        }
                    }
                    return cx.evaluateString(scope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(output))})", "host-command-output", 1, null)
                }
                require(operation == "native_hook") { "VBOOK_BRIDGE_OPERATION_DENIED:$operation" }
                val inputObject = args.getOrNull(1) as? Scriptable ?: error("NATIVE_LUA_HOOK_INPUT_REQUIRED")
                val hookName = inputObject.propertyString("name") ?: error("NATIVE_LUA_HOOK_NAME_REQUIRED")
                val sourceCode = resources.read("native/source.lua", 1024 * 1024)
                    ?: error("NATIVE_LUA_SOURCE_MISSING")
                val bridgeInput = VBookNativeHookBridgeInputCodec.resolve(inputObject.propertyString("input")) {
                    ScriptableObject.putProperty(scope, "__ngheNativeInput", inputObject)
                    try {
                        Context.toString(cx.evaluateString(
                            scope,
                            "JSON.stringify({value:__ngheNativeInput.value,args:__ngheNativeInput.args||{},context:__ngheNativeInput.context||{}})",
                            "native-hook-input-legacy",
                            1,
                            null,
                        ))
                    } finally {
                        ScriptableObject.deleteProperty(scope, "__ngheNativeInput")
                    }
                }
                val inputJson = bridgeInput.json
                diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_STARTED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                    "hook" to hookName.take(160),
                    "bridgeInputMode" to bridgeInput.mode,
                    "inputBytes" to inputJson.toByteArray(Charsets.UTF_8).size.toString(),
                    "sourceBytes" to sourceCode.size.toString(),
                    "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                )))
                captureEvidence(
                    manifest,
                    request,
                    "bridge-$hookName-input.json",
                    "application/json",
                    inputJson,
                    mapOf("hook" to hookName, "bridgeInputMode" to bridgeInput.mode),
                )
                val result = brokers.nativeHooks.execute(manifest, SourceNativeHookRequest(
                    sourceId = manifest.id,
                    sourceCode = sourceCode,
                    hookName = hookName,
                    inputJson = inputJson,
                    instructionBudget = manifest.runtime.instructionBudget,
                    timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, 30_000L),
                    memoryBudgetBytes = manifest.runtime.memoryBudgetBytes,
                    moduleSources = nativeModuleSources(resources),
                    resourceSources = nativeResourceSources(resources),
                    traceId = request.traceId,
                ))
                val output = when (result) {
                    is SourcePlatformResult.Success -> {
                        diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_COMPLETED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                            "hook" to hookName.take(160),
                            "outputBytes" to result.value.toByteArray(Charsets.UTF_8).size.toString(),
                            "remainingMs" to (budget.deadlineMs - clockMs()).coerceAtLeast(0L).toString(),
                        )))
                        captureEvidence(manifest, request, "bridge-$hookName-output.json", "application/json", result.value, mapOf("hook" to hookName))
                        result.value
                    }
                    is SourcePlatformResult.Failure -> {
                        diagnostics.emit(event(manifest, request, "VBOOK_BRIDGE_NATIVE_HOOK_FAILED", DiagnosticSeverity.ERROR, attributes = mapOf(
                            "hook" to hookName.take(160),
                            "code" to result.error.code.name,
                            "message" to result.error.message.take(500),
                        )))
                        error("NATIVE_LUA_${result.error.code}:${result.error.message}")
                    }
                }
                return cx.evaluateString(scope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(output))})", "native-hook-output", 1, null)
            }
        })
    }

    private fun nativeModuleSources(resources: SourceResourceProvider): Map<String, ByteArray> {
        val raw = resources.read("data/native-module-index.json", 1024 * 1024) ?: return emptyMap()
        val root = JsonCodec.parse(raw.toString(Charsets.UTF_8), maxDepth = 32, maxNodes = 10_000) as? JsonValue.Obj ?: return emptyMap()
        val modules = root.obj("modules") ?: return emptyMap()
        return modules.values.entries.take(256).mapNotNull { (name, value) ->
            val path = (value as? JsonValue.Str)?.value ?: return@mapNotNull null
            resources.read(path, 1024 * 1024)?.let { name to it }
        }.toMap(LinkedHashMap())
    }

    private fun localConfigObject(cx: Context, scope: Scriptable, resources: SourceResourceProvider): Scriptable =
        cx.newObject(scope).also { obj ->
            val config = runCatching {
                val raw = resources.read("plugin.json", 1024 * 1024) ?: return@runCatching emptyMap<String, String>()
                val root = JsonCodec.parse(raw.toString(Charsets.UTF_8), maxDepth = 48, maxNodes = 20_000) as? JsonValue.Obj
                    ?: return@runCatching emptyMap<String, String>()
                root.obj("config")?.values.orEmpty().entries.take(256).associate { (key, value) ->
                    key to when (value) {
                        is JsonValue.Str -> value.value
                        JsonValue.Null -> ""
                        else -> JsonCodec.stringify(value)
                    }
                }
            }.getOrDefault(emptyMap())
            ScriptableObject.putProperty(obj, "getItem", hostFunction { args ->
                config[Context.toString(args.getOrNull(0) ?: "")]
            })
            ScriptableObject.putProperty(obj, "key", hostFunction { args ->
                config.keys.sorted().getOrNull(Context.toNumber(args.getOrNull(0) ?: -1).toInt()) ?: Context.getUndefinedValue()
            })
            ScriptableObject.putProperty(obj, "length", config.size)
        }

    private fun localCookieObject(cx: Context, scope: Scriptable, manifest: SourceManifest): Scriptable =
        cx.newObject(scope).also { obj ->
            ScriptableObject.putProperty(obj, "getCookie", hostFunction { args ->
                val url = Context.toString(args.getOrNull(0) ?: manifest.origins.firstOrNull().orEmpty())
                brokers.cookies.readCookieHeader(manifest.id, url).orEmpty()
            })
            ScriptableObject.putProperty(obj, "setCookie", hostFunction { args ->
                val cookie = Context.toString(args.getOrNull(0) ?: "")
                val url = Context.toString(args.getOrNull(1) ?: manifest.origins.firstOrNull().orEmpty())
                require(url.startsWith("https://")) { "VBOOK_COOKIE_HTTPS_REQUIRED" }
                if (cookie.isNotBlank()) brokers.cookies.mergeSetCookieHeaders(manifest.id, url, listOf(cookie))
                true
            })
            ScriptableObject.putProperty(obj, "clear", hostFunction { brokers.cookies.clear(manifest.id); true })
        }

    private fun scriptObject(
        cx: Context,
        scope: Scriptable,
        resources: SourceResourceProvider,
        budget: RhinoExecutionBudget,
    ): Scriptable = cx.newObject(scope).also { obj ->
        ScriptableObject.putProperty(obj, "execute", hostFunction { args ->
            val rawPath = Context.toString(args.getOrNull(0) ?: "")
            val functionName = Context.toString(args.getOrNull(1) ?: "")
            val path = rawPath.replace('\\', '/').removePrefix("/").let { if (it.startsWith("src/")) it else "src/$it" }
            SourceManifest.requireSafeRelativePath(path)
            val code = resources.read(path, 2 * 1024 * 1024) ?: error("VBOOK_SCRIPT_RESOURCE_MISSING:$path")
            budget.charge(1 + code.size / 128)
            val requested = functionName.ifBlank { "execute" }
            require(requested.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$"))) { "VBOOK_SCRIPT_FUNCTION_INVALID" }
            val wrapper = buildString {
                append("(function(){\n")
                append(code.toString(Charsets.UTF_8))
                append("\n;return (typeof ").append(requested).append("==='function'?").append(requested)
                append(":(typeof execute==='function'?execute:null));})()")
            }
            val function = cx.evaluateString(scope, wrapper, path, 1, null) as? Function
                ?: error("VBOOK_SCRIPT_FUNCTION_MISSING:$requested")
            function.call(cx, scope, scope, args.drop(2).toTypedArray())
        })
    }

    private fun translationObject(
        cx: Context,
        scope: Scriptable,
        manifest: SourceManifest,
        request: SourceActionRequest,
    ): Scriptable = cx.newObject(scope).also { obj ->
        ScriptableObject.putProperty(obj, "translate", hostFunction { args ->
            val text = Context.toString(args.getOrNull(0) ?: "")
            val second = args.getOrNull(1)
            val options = (args.getOrNull(2) as? Scriptable) ?: (second as? Scriptable)
            val explicitTarget = second?.takeUnless { it is Scriptable || it == Context.getUndefinedValue() }?.let(Context::toString)
            val translated = when (val result = brokers.translation.translate(manifest, SourceTranslationRequest(
                sourceId = manifest.id,
                text = text,
                storyId = options?.propertyString("storyId") ?: request.input.string("storyId"),
                chapterId = options?.propertyString("chapterId") ?: request.input.string("chapterId"),
                sourceLanguage = options?.propertyString("sourceLanguage") ?: options?.propertyString("from"),
                targetLanguage = explicitTarget?.takeIf(String::isNotBlank)
                    ?: options?.propertyString("targetLanguage")
                    ?: options?.propertyString("to")
                    ?: "vi",
                instruction = options?.propertyString("instruction").orEmpty(),
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("VBOOK_TRANSLATION_${result.error.code}:${result.error.message}")
            }
            cx.newObject(scope).also { output ->
                ScriptableObject.putProperty(output, "translateText", translated.translatedText)
                val segments = if (translated.segmentMetadata.isNotEmpty()) {
                    translated.segmentMetadata.map { segment ->
                        cx.newObject(scope).also { value ->
                            ScriptableObject.putProperty(value, "srcStart", segment.srcStart)
                            ScriptableObject.putProperty(value, "srcLen", segment.srcLen)
                            ScriptableObject.putProperty(value, "transStart", segment.transStart)
                            ScriptableObject.putProperty(value, "transLen", segment.transLen)
                            ScriptableObject.putProperty(value, "type", segment.type)
                        }
                    }
                } else translated.segments
                ScriptableObject.putProperty(output, "segments", VBookRhinoValues.array(cx, scope, segments))
                ScriptableObject.putProperty(output, "provider", translated.provider.orEmpty())
            }
        })
    }

    private fun websocketConstructor(
        cx: Context,
        scope: Scriptable,
        manifest: SourceManifest,
        request: SourceActionRequest,
        budget: RhinoExecutionBudget,
    ): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any =
            BufferedWebSocketObject(
                cx = cx,
                ownerScope = scope,
                brokers = brokers,
                manifest = manifest,
                request = request,
                budget = budget,
                clockMs = clockMs,
                initialUrl = Context.toString(args.getOrNull(0) ?: ""),
            )
    }

    private fun nativeResourceSources(resources: SourceResourceProvider): Map<String, ByteArray> {
        val raw = resources.read("data/native-module-index.json", 1024 * 1024) ?: return emptyMap()
        val root = JsonCodec.parse(raw.toString(Charsets.UTF_8), maxDepth = 32, maxNodes = 10_000) as? JsonValue.Obj ?: return emptyMap()
        val resourceMap = root.obj("resources") ?: return emptyMap()
        return resourceMap.values.entries.take(256).mapNotNull { (name, value) ->
            val path = (value as? JsonValue.Str)?.value ?: return@mapNotNull null
            resources.read(path, 8 * 1024 * 1024)?.let { name to it }
        }.toMap(LinkedHashMap())
    }

    private fun userAgentObject(cx: Context, scope: Scriptable): Scriptable = cx.newObject(scope).also { obj ->
        ScriptableObject.putProperty(obj, "system", hostFunction { "NgheTruyen-VBook/2.5 Android" })
        ScriptableObject.putProperty(obj, "android", hostFunction { "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" })
        ScriptableObject.putProperty(obj, "chrome", hostFunction { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" })
        ScriptableObject.putProperty(obj, "ios", hostFunction { "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1" })
    }

    private fun graphicsObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest): Scriptable =
        cx.newObject(scope).also { obj ->
            ScriptableObject.putProperty(obj, "createImage", hostFunction { args ->
                cx.newObject(scope).also { image ->
                    ScriptableObject.putProperty(image, "__vbookImage", true)
                    ScriptableObject.putProperty(image, "base64", Context.toString(args.getOrNull(0) ?: ""))
                }
            })
            ScriptableObject.putProperty(obj, "createCanvas", hostFunction { args ->
                GraphicsCanvasObject(
                    cx = cx,
                    ownerScope = scope,
                    brokers = brokers,
                    manifest = manifest,
                    request = request,
                    width = Context.toNumber(args.getOrNull(0) ?: 1).toInt().coerceIn(1, 4096),
                    height = Context.toNumber(args.getOrNull(1) ?: 1).toInt().coerceIn(1, 4096),
                )
            })
        }

    private fun httpOptions(cx: Context, scope: Scriptable, method: String, body: Any?, headers: Scriptable?): Scriptable =
        cx.newObject(scope).also { options ->
            ScriptableObject.putProperty(options, "method", method)
            if (body != null && body != Context.getUndefinedValue()) ScriptableObject.putProperty(options, "body", Context.toString(body))
            headers?.let { ScriptableObject.putProperty(options, "headers", it) }
        }

    private fun diagnosticLogObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest): Scriptable =
        cx.newObject(scope).also { obj ->
            fun logger(severity: DiagnosticSeverity): BaseFunction = hostFunction { args ->
                val parsed = VBookDiagnosticLogParser.parse(
                    rawArguments = args.map { Context.toString(it) },
                    requestedSeverity = severity,
                    traceId = request.traceId,
                    action = request.action,
                )
                diagnostics.emit(event(manifest, request, parsed.name, parsed.severity, attributes = parsed.attributes))
                true
            }.apply {
                // NativeV2 uses Log.log.apply(...). Give the host logger the normal Function
                // prototype so JavaScript apply/call helpers remain available in Rhino.
                parentScope = scope
                prototype = ScriptableObject.getFunctionPrototype(scope)
            }
            ScriptableObject.putProperty(obj, "d", logger(DiagnosticSeverity.DEBUG))
            ScriptableObject.putProperty(obj, "i", logger(DiagnosticSeverity.INFO))
            ScriptableObject.putProperty(obj, "w", logger(DiagnosticSeverity.WARN))
            ScriptableObject.putProperty(obj, "e", logger(DiagnosticSeverity.ERROR))
            ScriptableObject.putProperty(obj, "debug", logger(DiagnosticSeverity.DEBUG))
            ScriptableObject.putProperty(obj, "info", logger(DiagnosticSeverity.INFO))
            ScriptableObject.putProperty(obj, "log", logger(DiagnosticSeverity.INFO))
            ScriptableObject.putProperty(obj, "warn", logger(DiagnosticSeverity.WARN))
            ScriptableObject.putProperty(obj, "error", logger(DiagnosticSeverity.ERROR))
        }

    private fun storageObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest): Scriptable =
        cx.newObject(scope).also { obj ->
            fun keys(prefix: String = ""): List<String> = when (val result = brokers.storage.keys(manifest, manifest.id, prefix, request.traceId)) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error(result.error.message)
            }
            fun refreshLength() = ScriptableObject.putProperty(obj, "length", keys().size)
            ScriptableObject.putProperty(obj, "get", hostFunction { args ->
                val key = Context.toString(args.getOrNull(0) ?: "")
                when (val result = brokers.storage.get(manifest, SourceStorageRequest(manifest.id, key, traceId = request.traceId))) {
                    is SourcePlatformResult.Success -> result.value?.toString(Charsets.UTF_8)
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            })
            ScriptableObject.putProperty(obj, "put", hostFunction { args ->
                val key = Context.toString(args.getOrNull(0) ?: "")
                val value = Context.toString(args.getOrNull(1) ?: "")
                when (val result = brokers.storage.put(manifest, SourceStorageRequest(manifest.id, key, value.toByteArray(), request.traceId))) {
                    is SourcePlatformResult.Success -> { refreshLength(); true }
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            })
            ScriptableObject.putProperty(obj, "remove", hostFunction { args ->
                val key = Context.toString(args.getOrNull(0) ?: "")
                when (val result = brokers.storage.delete(manifest, SourceStorageRequest(manifest.id, key, traceId = request.traceId))) {
                    is SourcePlatformResult.Success -> { refreshLength(); true }
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            })
            ScriptableObject.putProperty(obj, "keys", hostFunction { args ->
                val prefix = Context.toString(args.getOrNull(0) ?: "")
                VBookRhinoValues.strings(cx, scope, keys(prefix))
            })
            ScriptableObject.putProperty(obj, "key", hostFunction { args ->
                keys().getOrNull(Context.toNumber(args.getOrNull(0) ?: -1).toInt())
            })
            ScriptableObject.putProperty(obj, "clearPrefix", hostFunction { args ->
                val prefix = Context.toString(args.getOrNull(0) ?: "")
                when (val result = brokers.storage.clearPrefix(manifest, manifest.id, prefix, request.traceId)) {
                    is SourcePlatformResult.Success -> { refreshLength(); true }
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            })
            ScriptableObject.putProperty(obj, "clear", hostFunction {
                when (val result = brokers.storage.clear(manifest.id)) {
                    is SourcePlatformResult.Success -> { refreshLength(); true }
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            })
            ScriptableObject.putProperty(obj, "getItem", ScriptableObject.getProperty(obj, "get"))
            ScriptableObject.putProperty(obj, "setItem", ScriptableObject.getProperty(obj, "put"))
            ScriptableObject.putProperty(obj, "removeItem", ScriptableObject.getProperty(obj, "remove"))
            refreshLength()
        }

    private fun cryptoObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest): Scriptable =
        cx.newObject(scope).also { obj ->
            fun decodeBase64(value: String): ByteArray = Base64.getMimeDecoder().decode(value.filterNot(Char::isWhitespace))
            fun executeBytes(operation: SourceCryptoOperation, payload: ByteArray, key: ByteArray? = null): ByteArray {
                val result = brokers.crypto.execute(manifest, SourceCryptoRequest(
                    sourceId = manifest.id,
                    operation = operation,
                    payload = payload,
                    keyMaterial = key,
                    traceId = request.traceId,
                ))
                return when (result) {
                    is SourcePlatformResult.Success -> result.value
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
            }
            fun digest(operation: SourceCryptoOperation, payload: String, format: String): String {
                val bytes = executeBytes(operation, payload.toByteArray(Charsets.UTF_8))
                return if (format.equals("base64", true)) Base64.getEncoder().encodeToString(bytes) else bytes.toHex()
            }
            fun hmac(operation: SourceCryptoOperation, payload: String, key: String, format: String): String {
                val bytes = executeBytes(operation, payload.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
                return if (format.equals("base64", true)) Base64.getEncoder().encodeToString(bytes) else bytes.toHex()
            }
            fun digestBase64(operation: SourceCryptoOperation, base64: String): String =
                executeBytes(operation, decodeBase64(base64)).toHex()
            fun hmacBase64(operation: SourceCryptoOperation, dataBase64: String, keyBase64: String): String =
                executeBytes(operation, decodeBase64(dataBase64), decodeBase64(keyBase64)).toHex()

            fun deriveOpenSsl(passphrase: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
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

            fun aesCompat(args: Array<out Any>): String {
                require(SourceCryptoCapability.AES_COMPAT in manifest.capabilities.crypto) { "SOURCE_CRYPTO_AES_COMPAT_CAPABILITY_REQUIRED" }
                val operation = Context.toString(args.getOrNull(0) ?: "decrypt").lowercase(Locale.ROOT)
                var data = decodeBase64(Context.toString(args.getOrNull(1) ?: ""))
                require(data.size <= 8 * 1024 * 1024) { "VBOOK_AES_PAYLOAD_TOO_LARGE" }
                val keyType = Context.toString(args.getOrNull(2) ?: "raw").lowercase(Locale.ROOT)
                val keyBase64 = Context.toString(args.getOrNull(3) ?: "")
                val passphrase = Context.toString(args.getOrNull(4) ?: "")
                val ivBase64 = Context.toString(args.getOrNull(5) ?: "")
                val modeName = Context.toString(args.getOrNull(6) ?: "CBC").uppercase(Locale.ROOT).let { if (it == "ECB") "ECB" else "CBC" }
                val paddingName = if (Context.toString(args.getOrNull(7) ?: "PKCS7").equals("NoPadding", true)) "NoPadding" else "PKCS5Padding"
                val encrypting = operation == "encrypt"
                var salt = ByteArray(0)
                val key: ByteArray
                val iv: ByteArray
                if (keyType == "passphrase") {
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
                    key = decodeBase64(keyBase64)
                    require(key.size in setOf(16, 24, 32)) { "VBOOK_AES_KEY_LENGTH_INVALID" }
                    iv = if (ivBase64.isBlank()) ByteArray(16) else decodeBase64(ivBase64)
                }
                if (modeName == "CBC") require(iv.size == 16) { "VBOOK_AES_IV_LENGTH_INVALID" }
                if (paddingName == "NoPadding") require(data.size % 16 == 0) { "VBOOK_AES_NOPADDING_BLOCK_SIZE" }
                val cipher = Cipher.getInstance("AES/$modeName/$paddingName")
                val spec = SecretKeySpec(key, "AES")
                if (modeName == "ECB") cipher.init(if (encrypting) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec)
                else cipher.init(if (encrypting) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                var output = cipher.doFinal(data)
                if (keyType == "passphrase" && encrypting) output = "Salted__".toByteArray(Charsets.US_ASCII) + salt + output
                return Base64.getEncoder().encodeToString(output)
            }

            ScriptableObject.putProperty(obj, "md5", hostFunction { args -> digest(SourceCryptoOperation.MD5, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: "hex")) })
            ScriptableObject.putProperty(obj, "sha1", hostFunction { args -> digest(SourceCryptoOperation.SHA1, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: "hex")) })
            ScriptableObject.putProperty(obj, "sha256", hostFunction { args -> digest(SourceCryptoOperation.SHA256, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: "hex")) })
            ScriptableObject.putProperty(obj, "sha512", hostFunction { args -> digest(SourceCryptoOperation.SHA512, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: "hex")) })
            ScriptableObject.putProperty(obj, "hmacMd5", hostFunction { args -> hmac(SourceCryptoOperation.HMAC_MD5, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: "hex")) })
            ScriptableObject.putProperty(obj, "hmacSha1", hostFunction { args -> hmac(SourceCryptoOperation.HMAC_SHA1, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: "hex")) })
            ScriptableObject.putProperty(obj, "hmacSha256", hostFunction { args -> hmac(SourceCryptoOperation.HMAC_SHA256, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: "hex")) })
            ScriptableObject.putProperty(obj, "hmacSha512", hostFunction { args -> hmac(SourceCryptoOperation.HMAC_SHA512, Context.toString(args.getOrNull(0) ?: ""), Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: "hex")) })
            ScriptableObject.putProperty(obj, "hashBase64", hostFunction { args -> when (Context.toString(args.getOrNull(0) ?: "SHA-256").uppercase(Locale.ROOT).replace("-", "")) {
                "MD5" -> digestBase64(SourceCryptoOperation.MD5, Context.toString(args.getOrNull(1) ?: ""))
                "SHA1" -> digestBase64(SourceCryptoOperation.SHA1, Context.toString(args.getOrNull(1) ?: ""))
                "SHA512" -> digestBase64(SourceCryptoOperation.SHA512, Context.toString(args.getOrNull(1) ?: ""))
                else -> digestBase64(SourceCryptoOperation.SHA256, Context.toString(args.getOrNull(1) ?: ""))
            } })
            ScriptableObject.putProperty(obj, "hmacBase64", hostFunction { args -> when (Context.toString(args.getOrNull(0) ?: "HmacSHA256").uppercase(Locale.ROOT).replace("-", "")) {
                "HMACMD5" -> hmacBase64(SourceCryptoOperation.HMAC_MD5, Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: ""))
                "HMACSHA1" -> hmacBase64(SourceCryptoOperation.HMAC_SHA1, Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: ""))
                "HMACSHA512" -> hmacBase64(SourceCryptoOperation.HMAC_SHA512, Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: ""))
                else -> hmacBase64(SourceCryptoOperation.HMAC_SHA256, Context.toString(args.getOrNull(1) ?: ""), Context.toString(args.getOrNull(2) ?: ""))
            } })
            ScriptableObject.putProperty(obj, "utf8ToBase64", hostFunction { args -> Base64.getEncoder().encodeToString(Context.toString(args.getOrNull(0) ?: "").toByteArray(Charsets.UTF_8)) })
            ScriptableObject.putProperty(obj, "base64ToUtf8", hostFunction { args -> decodeBase64(Context.toString(args.getOrNull(0) ?: "")).toString(Charsets.UTF_8) })
            ScriptableObject.putProperty(obj, "latin1ToBase64", hostFunction { args -> Base64.getEncoder().encodeToString(Context.toString(args.getOrNull(0) ?: "").toByteArray(Charsets.ISO_8859_1)) })
            ScriptableObject.putProperty(obj, "base64ToLatin1", hostFunction { args -> decodeBase64(Context.toString(args.getOrNull(0) ?: "")).toString(Charsets.ISO_8859_1) })
            ScriptableObject.putProperty(obj, "hexToBase64", hostFunction { args -> Base64.getEncoder().encodeToString(Context.toString(args.getOrNull(0) ?: "").hexBytes()) })
            ScriptableObject.putProperty(obj, "base64ToHex", hostFunction { args -> decodeBase64(Context.toString(args.getOrNull(0) ?: "")).toHex() })
            ScriptableObject.putProperty(obj, "concatBase64", hostFunction { args -> Base64.getEncoder().encodeToString(decodeBase64(Context.toString(args.getOrNull(0) ?: "")) + decodeBase64(Context.toString(args.getOrNull(1) ?: ""))) })
            ScriptableObject.putProperty(obj, "randomBase64", hostFunction { args -> Base64.getEncoder().encodeToString(ByteArray(Context.toNumber(args.getOrNull(0) ?: 0).toInt().coerceIn(0, 65_536)).also(SecureRandom()::nextBytes)) })
            ScriptableObject.putProperty(obj, "base64Length", hostFunction { args -> decodeBase64(Context.toString(args.getOrNull(0) ?: "")).size })
            ScriptableObject.putProperty(obj, "aes", hostFunction(::aesCompat))
            ScriptableObject.putProperty(obj, "encrypt", hostFunction { args ->
                Base64.getEncoder().encodeToString(executeBytes(SourceCryptoOperation.AES_GCM_ENCRYPT, Context.toString(args.getOrNull(0) ?: "").toByteArray(Charsets.UTF_8)))
            })
            ScriptableObject.putProperty(obj, "decrypt", hostFunction { args ->
                val payload = decodeBase64(Context.toString(args.getOrNull(0) ?: ""))
                val result = brokers.crypto.execute(manifest, SourceCryptoRequest(manifest.id, SourceCryptoOperation.AES_GCM_DECRYPT, payload, traceId = request.traceId))
                when (result) { is SourcePlatformResult.Success -> result.value.toString(Charsets.UTF_8); is SourcePlatformResult.Failure -> error(result.error.message) }
            })
        }

    private fun browserObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest, budget: RhinoExecutionBudget): Scriptable =
        cx.newObject(scope).also { obj ->
            fun execute(action: SourceBrowserAction, args: Array<out Any?>): String {
                val response = brokers.browser.execute(manifest, SourceBrowserRequest(
                    sourceId = manifest.id, action = action,
                    url = args.getOrNull(0)?.let(Context::toString),
                    selector = args.getOrNull(1)?.let(Context::toString),
                    value = args.getOrNull(2)?.let(Context::toString),
                    script = if (action == SourceBrowserAction.EVALUATE_PAGE_SCRIPT) args.getOrNull(0)?.let(Context::toString) else null,
                    timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, 120_000L), traceId = request.traceId,
                ))
                return when (response) { is SourcePlatformResult.Success -> response.value.value.orEmpty(); is SourcePlatformResult.Failure -> error(response.error.message) }
            }
            ScriptableObject.putProperty(obj, "navigate", hostFunction { execute(SourceBrowserAction.NAVIGATE, it) })
            ScriptableObject.putProperty(obj, "html", hostFunction { execute(SourceBrowserAction.DOM_SNAPSHOT, it) })
            ScriptableObject.putProperty(obj, "click", hostFunction { execute(SourceBrowserAction.CLICK, arrayOf(null, it.getOrNull(0))) })
            ScriptableObject.putProperty(obj, "input", hostFunction { execute(SourceBrowserAction.INPUT, arrayOf(null, it.getOrNull(0), it.getOrNull(1))) })
            ScriptableObject.putProperty(obj, "eval", hostFunction { execute(SourceBrowserAction.EVALUATE_PAGE_SCRIPT, it) })
        }

    private fun engineObject(
        cx: Context,
        scope: Scriptable,
        manifest: SourceManifest,
        request: SourceActionRequest,
        budget: RhinoExecutionBudget,
    ): Scriptable = cx.newObject(scope).also { obj ->
        ScriptableObject.putProperty(obj, "newBrowser", hostFunction {
            BrowserCompatObject(cx, scope, brokers, manifest, request, budget, clockMs)
        })
        ScriptableObject.putProperty(obj, "browser", hostFunction {
            BrowserCompatObject(cx, scope, brokers, manifest, request, budget, clockMs)
        })
    }

    private fun websocketObject(cx: Context, scope: Scriptable, manifest: SourceManifest, request: SourceActionRequest, budget: RhinoExecutionBudget): Scriptable =
        cx.newObject(scope).also { obj ->
            ScriptableObject.putProperty(obj, "exchange", hostFunction { args ->
                val url = Context.toString(args.getOrNull(0) ?: "")
                val messages = (args.getOrNull(1) as? NativeArray)?.toStringList().orEmpty()
                val result = brokers.websocket.exchange(manifest, SourceWebSocketRequest(manifest.id, url, messages = messages, timeoutMs = (budget.deadlineMs - clockMs()).coerceAtLeast(100L), traceId = request.traceId))
                val values = when (result) { is SourcePlatformResult.Success -> result.value.messages; is SourcePlatformResult.Failure -> error(result.error.message) }
                VBookRhinoValues.strings(cx, scope, values)
            })
        }

    private fun hostFunction(block: (Array<out Any>) -> Any?): BaseFunction = object : BaseFunction() {
        // Function.prototype.apply(null, args) is valid JavaScript and Rhino forwards a null
        // thisObj. Kotlin must not insert a non-null check before the host callback can run.
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>): Any? = block(args)
    }

    private fun actionArguments(
        cx: Context,
        scope: Scriptable,
        manifest: SourceManifest,
        request: SourceActionRequest,
    ): Array<Any> {
        fun js(value: JsonValue?): Any = jsonToJs(cx, scope, value ?: JsonValue.Null)
        val page = if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) {
            // Native Source API 2 uses the second vBook argument as an opaque continuation URL.
            // Passing vBook's numeric item offset ("0", "30", ...) makes `$page` win over the
            // source's real first-page URL and sends requests to paths such as /0.
            request.input.string("pageToken").orEmpty()
        } else {
            (max(0, (request.input.int("page") ?: 1) - 1) * 30).toString()
        }
        return when (request.action) {
            SourceActionName.HOME -> arrayOf(
                js(request.input["input"] ?: request.input["category"] ?: JsonValue.Str("")),
                page,
            )
            SourceActionName.GENRE -> arrayOf(js(request.input["category"]), page)
            SourceActionName.SEARCH -> arrayOf(js(request.input["query"]), page)
            SourceActionName.DETAIL, SourceActionName.LATEST_CHAPTER, SourceActionName.TOC, SourceActionName.CHAPTER -> arrayOf(js(request.input["url"]))
            SourceActionName.COMMENTS -> arrayOf(js(request.input["url"]), page)
            SourceActionName.SUGGESTIONS -> arrayOf(js(request.input["query"] ?: request.input["url"]), page)
            SourceActionName.LOGIN, SourceActionName.UI_ACTION -> arrayOf(js(request.input))
            SourceActionName.TOC_PAGES -> arrayOf(
                js(request.input["url"]),
                js(request.input["pageToken"] ?: JsonValue.Str(page)),
            )
        }
    }

    private fun jsonToJs(cx: Context, scope: Scriptable, value: JsonValue): Any {
        val literal = JsonCodec.stringify(JsonValue.Str(JsonCodec.stringify(value)))
        return cx.evaluateString(scope, "JSON.parse($literal)", "vbook-input", 1, null)
    }

    private fun normalizeResult(request: SourceActionRequest, raw: JsonValue): JsonValue {
        val decoded = if (raw is JsonValue.Str) {
            runCatching { JsonCodec.parse(raw.value, maxDepth = 64, maxNodes = 200_000) }.getOrDefault(raw)
        } else raw
        val responseObject = decoded as? JsonValue.Obj
        val responseData2 = responseObject?.let { obj ->
            when (val value = obj["data2"] ?: obj["nextPageUrl"] ?: obj["next"]) {
                is JsonValue.Str -> value.value
                is JsonValue.Num -> value.raw
                else -> null
            }
        }?.trim()?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) }
        val unwrapped = (decoded as? JsonValue.Obj)?.let { obj ->
            val code = obj.int("code")
            when {
                code != null && code != 0 -> error("VBOOK_RESPONSE_ERROR:${obj.string("data") ?: obj.string("error").orEmpty()}")
                code == 0 -> obj["data"] ?: JsonValue.Null
                obj.bool("success") == false -> error("VBOOK_RESPONSE_ERROR:${obj.string("error").orEmpty()}")
                obj.bool("success") == true -> obj["data"] ?: JsonValue.Null
                else -> decoded
            }
        } ?: decoded
        return when (request.action) {
            SourceActionName.SEARCH, SourceActionName.HOME, SourceActionName.GENRE -> {
                val items = when (unwrapped) {
                    is JsonValue.Arr -> unwrapped.values
                    is JsonValue.Obj -> unwrapped.array("items")?.values.orEmpty()
                    else -> emptyList()
                }.mapNotNull { normalizeStory(it) }
                JsonValue.Obj(linkedMapOf(
                    "items" to JsonValue.Arr(items),
                    "nextPageUrl" to responseData2?.let(JsonValue::Str).orNull(),
                ))
            }
            SourceActionName.SUGGESTIONS -> {
                val items = when (unwrapped) {
                    is JsonValue.Arr -> unwrapped.values
                    is JsonValue.Obj -> unwrapped.array("items")?.values
                        ?: unwrapped.array("suggestions")?.values
                        ?: emptyList()
                    else -> emptyList()
                }.mapNotNull(::normalizeSuggestion).distinctBy { it.value.lowercase() }.take(20)
                JsonValue.Obj(linkedMapOf(
                    "items" to JsonValue.Arr(items),
                    "nextPageUrl" to responseData2?.let(JsonValue::Str).orNull(),
                ))
            }
            SourceActionName.DETAIL -> normalizeDetail(unwrapped, request.input.string("url").orEmpty())
            SourceActionName.LATEST_CHAPTER -> normalizeChapter(
                unwrapped,
                (unwrapped as? JsonValue.Obj)?.int("index") ?: 0,
                request.input.string("url").orEmpty(),
            ) ?: JsonValue.Null
            SourceActionName.TOC, SourceActionName.TOC_PAGES -> {
                val rawItems = when (unwrapped) {
                    is JsonValue.Arr -> unwrapped.values
                    is JsonValue.Obj -> unwrapped.array("chapters")?.values
                        ?: unwrapped.array("items")?.values
                        ?: unwrapped.array("data")?.values
                        ?: emptyList()
                    else -> emptyList()
                }
                JsonValue.Obj(linkedMapOf(
                    "chapters" to JsonValue.Arr(rawItems.mapIndexedNotNull { index, value ->
                        normalizeChapter(value, index, request.input.string("url").orEmpty())
                    }),
                    "nextPageUrl" to (responseData2
                        ?: (unwrapped as? JsonValue.Obj)?.string("nextPageUrl")
                        ?: (unwrapped as? JsonValue.Obj)?.string("next"))
                        ?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) }
                        ?.let(JsonValue::Str).orNull(),
                ))
            }
            SourceActionName.CHAPTER -> normalizeChapterContent(unwrapped, request.input.string("url").orEmpty())
            SourceActionName.COMMENTS -> normalizeCommentsPage(unwrapped)
            else -> unwrapped
        }
    }

    private fun normalizeStory(value: JsonValue): JsonValue.Obj? {
        val obj = value as? JsonValue.Obj ?: return null
        val url = absoluteUrl(obj.string("host"), obj.string("link") ?: obj.string("url")) ?: return null
        val title = obj.string("title") ?: obj.string("name") ?: return null
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)),
            "title" to JsonValue.Str(title),
            "author" to JsonValue.Str(obj.string("author") ?: obj.string("description").orEmpty()),
            "coverUrl" to (obj.string("coverUrl") ?: obj.string("cover"))?.let(JsonValue::Str).orNull(),
            "description" to JsonValue.Str(obj.string("description").orEmpty()),
            "url" to JsonValue.Str(url),
        ))
    }

    private fun normalizeSuggestion(value: JsonValue): JsonValue.Str? {
        val text = when (value) {
            is JsonValue.Str -> value.value
            is JsonValue.Obj -> value.string("query")
                ?: value.string("title")
                ?: value.string("name")
                ?: value.string("text")
                ?: value.string("input")
            else -> null
        }?.trim()?.takeIf(String::isNotBlank) ?: return null
        return JsonValue.Str(text.take(200))
    }

    private fun normalizeDetail(value: JsonValue, inputUrl: String): JsonValue {
        val obj = value as? JsonValue.Obj ?: return JsonValue.Null
        val url = absoluteUrl(obj.string("host"), obj.string("url") ?: obj.string("link") ?: inputUrl) ?: inputUrl
        val title = obj.string("title") ?: obj.string("name") ?: return JsonValue.Null
        val genres = obj.array("genres")?.values.orEmpty().mapNotNull { item ->
            when (item) { is JsonValue.Str -> item; is JsonValue.Obj -> item.string("title")?.let(JsonValue::Str); else -> null }
        }
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)), "title" to JsonValue.Str(title),
            "author" to JsonValue.Str(obj.string("author").orEmpty()),
            "coverUrl" to (obj.string("coverUrl") ?: obj.string("cover"))?.let(JsonValue::Str).orNull(),
            "description" to JsonValue.Str(obj.string("description").orEmpty()),
            "url" to JsonValue.Str(url), "genres" to JsonValue.Arr(genres),
            "status" to JsonValue.Str(obj.string("status") ?: if (obj.bool("ongoing") == true) "Đang ra" else "Hoàn thành"),
            "commentsUrl" to (obj.string("commentsUrl") ?: obj.string("commentUrl"))?.let(JsonValue::Str).orNull(),
            "comments" to normalizeCommentItems(obj.array("comments") ?: obj["comment"] ?: JsonValue.Null),
        ))
    }

    private fun normalizeCommentsPage(value: JsonValue): JsonValue.Obj {
        val root = value as? JsonValue.Obj
        val next = root?.string("nextPageUrl")
            ?: root?.string("nextUrl")
            ?: root?.string("next")
            ?: root?.string("cursor")
        return JsonValue.Obj(linkedMapOf(
            "items" to normalizeCommentItems(value),
            "nextPageUrl" to next?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) }
                ?.let(JsonValue::Str).orNull(),
        ))
    }

    private fun normalizeCommentItems(value: JsonValue): JsonValue.Arr {
        val candidates = when (value) {
            is JsonValue.Arr -> value.values
            is JsonValue.Obj -> value.array("items")?.values
                ?: value.array("comments")?.values
                ?: value.array("data")?.values
                ?: listOf(value)
            is JsonValue.Str -> listOf(value)
            else -> emptyList()
        }
        val comments = candidates.asSequence().mapNotNull { candidate ->
            when (candidate) {
                is JsonValue.Str -> candidate.value.trim().takeIf(String::isNotBlank)?.let { text ->
                    JsonValue.Obj(linkedMapOf(
                        "user" to JsonValue.Str("Người đọc"),
                        "time" to JsonValue.Str(""),
                        "text" to JsonValue.Str(text),
                    ))
                }
                is JsonValue.Obj -> {
                    val text = (candidate.string("text") ?: candidate.string("content") ?: candidate.string("description"))
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    JsonValue.Obj(linkedMapOf(
                        "user" to JsonValue.Str(candidate.string("user") ?: candidate.string("name") ?: candidate.string("author") ?: "Người đọc"),
                        "time" to JsonValue.Str(candidate.string("time") ?: candidate.string("date").orEmpty()),
                        "text" to JsonValue.Str(text),
                    ))
                }
                else -> null
            }
        }.take(100).toList()
        return JsonValue.Arr(comments)
    }

    private fun normalizeChapter(value: JsonValue, index: Int, storyUrl: String): JsonValue.Obj? {
        val obj = value as? JsonValue.Obj ?: return null
        val url = absoluteUrl(obj.string("host"), obj.string("url") ?: obj.string("link")) ?: return null
        val title = obj.string("title") ?: obj.string("name") ?: "Chương ${index + 1}"
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(stableId(storyUrl)),
            "index" to JsonValue.Num(index.toDouble(), index.toString()), "title" to JsonValue.Str(title), "url" to JsonValue.Str(url),
        ))
    }

    private fun normalizeChapterContent(value: JsonValue, url: String): JsonValue {
        val obj = value as? JsonValue.Obj
        val html = when (value) { is JsonValue.Str -> value.value; is JsonValue.Obj -> value.string("content") ?: value.string("html").orEmpty(); else -> "" }
        val paragraphs = Jsoup.parseBodyFragment(html).select("p,div,br").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }.ifEmpty {
            Jsoup.parseBodyFragment(html).text().split(Regex("\\n+|(?<=[.!?])\\s+(?=[A-ZÀ-Ỹ])")).map(String::trim).filter(String::isNotBlank)
        }.distinct()
        val title = obj?.string("title") ?: obj?.string("name") ?: "Chương"
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(""),
            "index" to JsonValue.Num(0.0, "0"), "title" to JsonValue.Str(title), "url" to JsonValue.Str(url),
            "paragraphs" to JsonValue.Arr(paragraphs.map(JsonValue::Str)),
            "previousChapterUrl" to (obj?.string("previousChapterUrl")?.let(JsonValue::Str) ?: JsonValue.Null),
            "nextChapterUrl" to (obj?.string("nextChapterUrl")?.let(JsonValue::Str) ?: JsonValue.Null),
        ))
    }

    private fun absoluteUrl(host: String?, path: String?): String? {
        val raw = path?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = host?.trimEnd('/') ?: return null
        return base + if (raw.startsWith('/')) raw else "/$raw"
    }

    private fun stableId(raw: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun JsonValue?.orNull(): JsonValue = this ?: JsonValue.Null

    private fun failure(code: SourceErrorCode, message: String, request: SourceActionRequest) =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId))

    private fun event(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMs: Long? = null,
        attributes: Map<String, String> = emptyMap(),
    ): DiagnosticEvent {
        val flow = attributes[DiagnosticOperationContract.FLOW] ?: attributes["flow"] ?: when {
            name.contains("BRIDGE") -> "bridge"
            name.contains("BROWSER") -> "browser"
            name.contains("NATIVE") -> "native"
            else -> "executor"
        }
        val state = attributes[DiagnosticOperationContract.STATE]?.let {
            runCatching { DiagnosticOperationState.valueOf(it) }.getOrNull()
        } ?: when {
            name == "VBOOK_ACTION_STARTED" -> DiagnosticOperationState.STARTED
            name == "VBOOK_ACTION_COMPLETED" -> DiagnosticOperationState.COMPLETED
            name == "VBOOK_ACTION_FAILED" -> DiagnosticOperationState.FAILED
            else -> DiagnosticOperationState.STAGE
        }
        val operation = DiagnosticOperationContract.attributes(
            id = "vbook:${request.traceId.ifBlank { "no-trace" }}:${request.action.name}",
            kind = request.action.name,
            flow = flow,
            state = state,
            stage = attributes["stage"] ?: name,
            timeoutMs = attributes["timeoutMs"]?.toLongOrNull(),
            deadlineEpochMs = attributes["deadlineEpochMs"]?.toLongOrNull(),
        )
        return DiagnosticEvent(
            clockMs(),
            request.traceId,
            manifest.id,
            manifest.version.toString(),
            DiagnosticCategory.RUNTIME,
            name,
            severity,
            durationMs,
            operation + attributes,
        )
    }

    private fun captureEvidence(
        manifest: SourceManifest,
        request: SourceActionRequest,
        name: String,
        contentType: String,
        text: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!evidence.enabled || text.isBlank()) return
        evidence.capture(
            DiagnosticEvidence(
                timestampEpochMs = clockMs(),
                traceId = request.traceId,
                sourceId = manifest.id,
                category = DiagnosticCategory.RUNTIME,
                name = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180),
                contentType = contentType,
                data = text.toByteArray(Charsets.UTF_8),
                attributes = attributes + mapOf("action" to request.action.name),
            ),
        )
    }

    companion object {
        private const val BOOTSTRAP = """
            'use strict';
            var Response = Object.freeze({
              success: function(data, data2) { return JSON.stringify({code:0, data:data, data2:(data2 === undefined ? null : data2)}); },
              error: function(message) { return JSON.stringify({code:1, data:String(message || 'VBook error')}); }
            });
            function __cryptoWord(o) {
              o = o || {};
              if (o.__base64 !== undefined) o.__base64 = String(o.__base64 || '').replace(/\s+/g, '');
              if (o.__hex !== undefined) o.__hex = String(o.__hex || '').replace(/\s+/g, '').toLowerCase();
              if (o.__text !== undefined) o.__text = String(o.__text == null ? '' : o.__text);
              o.toString = function(enc) { if (enc && enc.stringify) return enc.stringify(o); return __cryptoToHex(o); };
              o.concat = function(other) { o.__base64 = Crypto.concatBase64(__cryptoToBase64(o), __cryptoToBase64(other)); delete o.__hex; delete o.__text; o.sigBytes = Crypto.base64Length(o.__base64); return o; };
              o.clamp = function() { return o; };
              o.clone = function() { return __cryptoWord({__base64:__cryptoToBase64(o), sigBytes:o.sigBytes}); };
              if (o.sigBytes === undefined) o.sigBytes = Crypto.base64Length(__cryptoToBase64(o));
              return o;
            }
            function __cryptoToBase64(v) {
              if (v && typeof v === 'object') {
                if (v.ciphertext) return __cryptoToBase64(v.ciphertext);
                if (v.__base64 !== undefined) return String(v.__base64 || '');
                if (v.__hex !== undefined) return Crypto.hexToBase64(v.__hex);
                if (v.__text !== undefined) return Crypto.utf8ToBase64(String(v.__text || ''));
              }
              return Crypto.utf8ToBase64(String(v == null ? '' : v));
            }
            function __cryptoToHex(v) {
              if (v && typeof v === 'object' && v.__hex !== undefined) return String(v.__hex || '');
              return Crypto.base64ToHex(__cryptoToBase64(v));
            }
            function __cryptoRawText(v) {
              if (v && typeof v === 'object') {
                if (v.__text !== undefined) return String(v.__text || '');
                if (v.__base64 !== undefined) return Crypto.base64ToUtf8(v.__base64);
                if (v.__hex !== undefined) return Crypto.base64ToUtf8(Crypto.hexToBase64(v.__hex));
              }
              return String(v == null ? '' : v);
            }
            function __cryptoWordCreate(words, sigBytes) {
              if (words && words.__base64 !== undefined) return __cryptoWord({__base64:words.__base64, sigBytes:sigBytes});
              if (typeof words === 'string') return __cryptoWord({__text:words, sigBytes:sigBytes});
              words = Array.isArray(words) ? words : [];
              var needed = sigBytes == null ? words.length * 4 : Number(sigBytes), hex = '';
              for (var i=0; i<words.length && hex.length/2<needed; i++) {
                var w = Number(words[i]) >>> 0;
                for (var shift=24; shift>=0 && hex.length/2<needed; shift-=8) hex += ('0' + ((w>>>shift)&255).toString(16)).slice(-2);
              }
              return __cryptoWord({__base64:Crypto.hexToBase64(hex), sigBytes:Math.min(needed, hex.length/2)});
            }
            function __cryptoCipherParams(b64) {
              var p = {ciphertext:__cryptoWord({__base64:String(b64 || '')})};
              p.toString = function(format) { if (format && format.stringify) return format.stringify(p); return String(b64 || ''); };
              return p;
            }
            var CryptoJS = (function() {
              var enc = {
                Utf8:{parse:function(s){return __cryptoWord({__text:String(s==null?'':s)});}, stringify:function(w){return __cryptoRawText(w);}},
                Base64:{parse:function(s){return __cryptoWord({__base64:String(s||'')});}, stringify:function(w){return __cryptoToBase64(w);}},
                Hex:{parse:function(s){return __cryptoWord({__hex:String(s||'').toLowerCase()});}, stringify:function(w){return __cryptoToHex(w);}},
                Latin1:{parse:function(s){return __cryptoWord({__base64:Crypto.latin1ToBase64(String(s||''))});}, stringify:function(w){return Crypto.base64ToLatin1(__cryptoToBase64(w));}}
              };
              function digest(alg,v){return __cryptoWord({__hex:Crypto.hashBase64(alg,__cryptoToBase64(v))});}
              function hmac(alg,m,k){return __cryptoWord({__hex:Crypto.hmacBase64(alg,__cryptoToBase64(m),__cryptoToBase64(k))});}
              var mode={CBC:{__name:'CBC'},ECB:{__name:'ECB'}};
              var pad={Pkcs7:{__name:'PKCS7'},NoPadding:{__name:'NoPadding'}};
              function modeName(opts){return opts&&opts.mode&&opts.mode.__name?opts.mode.__name:'CBC';}
              function paddingName(opts){return opts&&opts.padding&&opts.padding.__name?opts.padding.__name:'PKCS7';}
              function cipherBase64(v){if(v&&typeof v==='object'&&v.ciphertext)return __cryptoToBase64(v.ciphertext);if(typeof v==='string')return v.replace(/\s+/g,'');return __cryptoToBase64(v);}
              var format={OpenSSL:{stringify:function(params){return __cryptoToBase64(params&&params.ciphertext?params.ciphertext:params);},parse:function(text){return __cryptoCipherParams(String(text||''));}}};
              var AES={
                encrypt:function(message,key,opts){opts=opts||{};var pass=typeof key==='string';var out=Crypto.aes('encrypt',__cryptoToBase64(message),pass?'passphrase':'raw',pass?'':__cryptoToBase64(key),pass?String(key):'',opts.iv?__cryptoToBase64(opts.iv):'',modeName(opts),paddingName(opts));return __cryptoCipherParams(out);},
                decrypt:function(ciphertext,key,opts){opts=opts||{};var pass=typeof key==='string';var out=Crypto.aes('decrypt',cipherBase64(ciphertext),pass?'passphrase':'raw',pass?'':__cryptoToBase64(key),pass?String(key):'',opts.iv?__cryptoToBase64(opts.iv):'',modeName(opts),paddingName(opts));return __cryptoWord({__base64:out});}
              };
              return {
                lib:{WordArray:{create:__cryptoWordCreate,random:function(n){return __cryptoWord({__base64:Crypto.randomBase64(Math.max(0,Number(n)||0))});}}},
                enc:enc,mode:mode,pad:pad,format:format,
                MD5:function(v){return digest('MD5',v);},SHA1:function(v){return digest('SHA-1',v);},SHA256:function(v){return digest('SHA-256',v);},SHA512:function(v){return digest('SHA-512',v);},
                HmacMD5:function(m,k){return hmac('HmacMD5',m,k);},HmacSHA1:function(m,k){return hmac('HmacSHA1',m,k);},HmacSHA256:function(m,k){return hmac('HmacSHA256',m,k);},HmacSHA512:function(m,k){return hmac('HmacSHA512',m,k);},
                AES:AES
              };
            })();
        """
    }
}

data class VBookActionCompatibility(val action: SourceActionName, val compatible: Boolean, val detail: String)
data class VBookCompatibilityReport(val actions: List<VBookActionCompatibility>, val allCompatible: Boolean)

private class ScriptLoader(
    private val cx: Context,
    private val scope: Scriptable,
    private val resources: SourceResourceProvider,
    private val loaded: MutableSet<String>,
    private val budget: RhinoExecutionBudget,
    private val onLoad: (String, Int) -> Unit = { _, _ -> },
) {
    fun install() {
        ScriptableObject.putProperty(scope, "load", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                load(Context.toString(args.getOrNull(0) ?: ""))
                return true
            }
        })
    }

    fun load(raw: String) {
        val target = VBookLoadPolicy.resolve(raw)
        if (target.kind == VBookLoadKind.BUNDLED_CRYPTO) return
        val path = requireNotNull(target.path) { "VBOOK_LOAD_TARGET_PATH_REQUIRED" }
        if (!loaded.add(path)) return
        val bytes = resources.read(path, 2 * 1024 * 1024) ?: error("VBOOK_RESOURCE_MISSING:$path")
        budget.charge(1 + bytes.size / 128)
        cx.evaluateString(scope, bytes.toString(Charsets.UTF_8), path, 1, null)
        onLoad(path, bytes.size)
    }

}

private class FetchResponseObject(
    cx: Context,
    scope: Scriptable,
    status: Int,
    url: String,
    headers: Map<String, List<String>>,
    private val body: String,
) : ScriptableObject() {
    init {
        parentScope = scope
        prototype = ScriptableObject.getObjectPrototype(scope)
        put("ok", this, status in 200..299)
        put("status", this, status)
        put("statusCode", this, status)
        put("url", this, url)
        put("body", this, body)
        put("headers", this, VBookRhinoValues.stringMap(cx, scope, headers.mapValues { it.value.joinToString(", ") }))
        put("text", this, function { body })
        put("string", this, function { body })
        put("json", this, function { cx.evaluateString(scope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(body))})", "fetch-json", 1, null) })
        put("html", this, function { JsoupDocumentObject(Jsoup.parse(body, url), scope) })
        put("document", this, function { JsoupDocumentObject(Jsoup.parse(body, url), scope) })
    }
    override fun getClassName(): String = "VBookFetchResponse"
    private fun function(block: () -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block()
    }
}

private class JsoupDocumentObject(private val document: Document, private val ownerScope: Scriptable) : ScriptableObject() {
    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName() = "VBookDocument"
    override fun get(name: String, start: Scriptable): Any = when (name) {
        "select" -> fn { args -> JsoupElementsObject(document.select(Context.toString(args.getOrNull(0) ?: "")), ownerScope) }
        "selectFirst", "first" -> fn { args -> document.selectFirst(Context.toString(args.getOrNull(0) ?: ""))?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "text" -> fn { document.text() }
        "html" -> fn { document.html() }
        "outerHtml" -> fn { document.outerHtml() }
        "title" -> fn { document.title() }
        "location", "baseUri" -> fn { document.location() }
        "body" -> fn { JsoupElementObject(document.body(), ownerScope) }
        else -> super.get(name, start)
    }
    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}

private class JsoupElementsObject(private val elements: Elements, private val ownerScope: Scriptable) : ScriptableObject() {
    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName() = "VBookElements"
    override fun getIds(): Array<Any> = Array(elements.size + 1) { index -> if (index < elements.size) index else "length" }
    override fun get(index: Int, start: Scriptable): Any = elements.getOrNull(index)?.let { JsoupElementObject(it, ownerScope) } ?: Scriptable.NOT_FOUND
    override fun get(name: String, start: Scriptable): Any = when (name) {
        "length" -> elements.size
        "size" -> fn { elements.size }
        "get", "eq" -> fn { args -> elements.getOrNull(Context.toNumber(args.getOrNull(0) ?: 0).toInt())?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "first" -> fn { elements.firstOrNull()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "last" -> fn { elements.lastOrNull()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "text" -> fn { elements.text() }
        "html" -> fn { elements.joinToString("\n") { it.html() } }
        "outerHtml" -> fn { elements.joinToString("\n") { it.outerHtml() } }
        "attr" -> fn { args -> elements.firstOrNull()?.attr(Context.toString(args.getOrNull(0) ?: "")).orEmpty() }
        "eachText", "texts" -> fn { VBookRhinoValues.strings(Context.getCurrentContext(), ownerScope, elements.map(Element::text)) }
        "toArray" -> fn { VBookRhinoValues.array(Context.getCurrentContext(), ownerScope, elements.map { JsoupElementObject(it, ownerScope) }) }
        else -> super.get(name, start)
    }
    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}

private class JsoupElementObject(private val element: Element, private val ownerScope: Scriptable) : ScriptableObject() {
    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName() = "VBookElement"
    override fun get(name: String, start: Scriptable): Any = when (name) {
        "select" -> fn { args -> JsoupElementsObject(element.select(Context.toString(args.getOrNull(0) ?: "")), ownerScope) }
        "selectFirst" -> fn { args -> element.selectFirst(Context.toString(args.getOrNull(0) ?: ""))?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "text" -> fn { element.text() }
        "ownText" -> fn { element.ownText() }
        "wholeText" -> fn { element.wholeText() }
        "html" -> fn { element.html() }
        "outerHtml" -> fn { element.outerHtml() }
        "attr" -> fn { args -> element.attr(Context.toString(args.getOrNull(0) ?: "")) }
        "absUrl" -> fn { args -> element.absUrl(Context.toString(args.getOrNull(0) ?: "")) }
        "id" -> fn { element.id() }
        "tagName" -> fn { element.tagName() }
        "hasClass" -> fn { args -> element.hasClass(Context.toString(args.getOrNull(0) ?: "")) }
        "parent" -> fn { element.parent()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }
        "children" -> fn { JsoupElementsObject(Elements(element.children()), ownerScope) }
        else -> super.get(name, start)
    }
    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}

private class PrefixedStorageObject(
    private val delegate: Scriptable,
    private val prefix: String,
    private val ownerScope: Scriptable,
) : ScriptableObject() {
    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName(): String = "VBookPrefixedStorage"
    override fun get(name: String, start: Scriptable): Any = when (name) {
        "get", "getItem" -> fn { cx, args -> callDelegate(cx, "get", arrayOf(prefix + Context.toString(args.getOrNull(0) ?: ""))) }
        "put", "setItem" -> fn { cx, args -> callDelegate(cx, "put", arrayOf(prefix + Context.toString(args.getOrNull(0) ?: ""), args.getOrNull(1) ?: "")) }
        "remove", "removeItem" -> fn { cx, args -> callDelegate(cx, "remove", arrayOf(prefix + Context.toString(args.getOrNull(0) ?: ""))) }
        "clear" -> fn { cx, _ -> callDelegate(cx, "clearPrefix", arrayOf(prefix)) }
        "keys" -> fn { cx, _ -> prefixedKeys(cx) }
        "key" -> fn { cx, args ->
            val values = prefixedKeyList(cx)
            values.getOrNull(Context.toNumber(args.getOrNull(0) ?: -1).toInt()) ?: Context.getUndefinedValue()
        }
        "length" -> prefixedKeyList(null).size
        else -> super.get(name, start)
    }
    private fun prefixedKeys(cx: Context): Any = VBookRhinoValues.strings(cx, ownerScope, prefixedKeyList(cx))
    private fun prefixedKeyList(cx: Context?): List<String> {
        val function = ScriptableObject.getProperty(delegate, "keys") as? Function ?: return emptyList()
        val context = cx ?: Context.getCurrentContext() ?: return emptyList()
        val raw = function.call(context, ownerScope, delegate, arrayOf(prefix))
        return when (raw) {
            is NativeArray -> raw.toStringList().map { it.removePrefix(prefix) }
            is Scriptable -> (0 until ((ScriptableObject.getProperty(raw, "length") as? Number)?.toInt() ?: 0)).mapNotNull { index ->
                raw.get(index, raw).takeUnless { it == Scriptable.NOT_FOUND }?.let(Context::toString)?.removePrefix(prefix)
            }
            else -> emptyList()
        }
    }
    private fun callDelegate(cx: Context, name: String, args: Array<Any>): Any {
        val function = ScriptableObject.getProperty(delegate, name) as? Function ?: return Context.getUndefinedValue()
        return function.call(cx, ownerScope, delegate, args) ?: Context.getUndefinedValue()
    }
    private fun fn(block: (Context, Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(cx, args)
    }
}

private class BufferedWebSocketObject(
    private val cx: Context,
    private val ownerScope: Scriptable,
    private val brokers: SourceCapabilityBrokers,
    private val manifest: SourceManifest,
    private val request: SourceActionRequest,
    private val budget: RhinoExecutionBudget,
    private val clockMs: () -> Long,
    initialUrl: String,
) : ScriptableObject() {
    private var url: String = initialUrl
    private val outgoing = mutableListOf<String>()
    private val incoming = ArrayDeque<String>()
    private var connected = false
    private var closed = false
    private var closeCode: Int? = null
    private var closeReason: String? = null

    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName(): String = "VBookWebSocket"

    override fun get(name: String, start: Scriptable): Any = when (name) {
        "connect" -> fn { args ->
            if (args.isNotEmpty()) url = Context.toString(args[0])
            require(url.startsWith("wss://")) { "VBOOK_WEBSOCKET_WSS_REQUIRED" }
            connected = true
            closed = false
            true
        }
        "send" -> fn { args ->
            require(connected && !closed) { "VBOOK_WEBSOCKET_NOT_CONNECTED" }
            val message = Context.toString(args.getOrNull(0) ?: "")
            require(message.toByteArray().size <= manifest.capabilities.websocket.maxMessageBytes) { "VBOOK_WEBSOCKET_MESSAGE_TOO_LARGE" }
            outgoing += message
            true
        }
        "message", "receive" -> fn { args ->
            require(connected && !closed) { "VBOOK_WEBSOCKET_NOT_CONNECTED" }
            if (incoming.isEmpty()) exchange(Context.toNumber(args.getOrNull(0) ?: 1).toInt().coerceIn(1, 100))
            incoming.removeFirstOrNull() ?: Context.getUndefinedValue()
        }
        "messages" -> fn { args ->
            if (incoming.isEmpty() && connected && !closed) exchange(Context.toNumber(args.getOrNull(0) ?: 100).toInt().coerceIn(1, 100))
            val values = incoming.toList()
            incoming.clear()
            VBookRhinoValues.strings(cx, ownerScope, values)
        }
        "close" -> fn {
            closed = true
            connected = false
            true
        }
        "isConnected", "connected" -> connected && !closed
        "closeCode" -> closeCode ?: Context.getUndefinedValue()
        "closeReason" -> closeReason ?: Context.getUndefinedValue()
        else -> super.get(name, start)
    }

    private fun exchange(maxResponses: Int) {
        budget.charge(50)
        val result = brokers.websocket.exchange(manifest, SourceWebSocketRequest(
            sourceId = manifest.id,
            url = url,
            messages = outgoing.toList(),
            maxResponses = maxResponses,
            timeoutMs = (budget.deadlineMs - clockMs()).coerceIn(100L, manifest.capabilities.websocket.maxLifetimeMs),
            traceId = request.traceId,
        ))
        when (result) {
            is SourcePlatformResult.Success -> {
                incoming.addAll(result.value.messages)
                outgoing.clear()
                closeCode = result.value.closeCode
                closeReason = result.value.closeReason
                if (closeCode != null) { connected = false; closed = true }
            }
            is SourcePlatformResult.Failure -> error("VBOOK_WEBSOCKET_${result.error.code}:${result.error.message}")
        }
    }

    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}

private class GraphicsCanvasObject(
    private val cx: Context,
    private val ownerScope: Scriptable,
    private val brokers: SourceCapabilityBrokers,
    private val manifest: SourceManifest,
    private val request: SourceActionRequest,
    private val width: Int,
    private val height: Int,
) : ScriptableObject() {
    private val operations = mutableListOf<SourceGraphicsDrawOperation>()

    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName(): String = "VBookGraphicsCanvas"

    override fun get(name: String, start: Scriptable): Any = when (name) {
        "width" -> width
        "height" -> height
        "drawImage" -> fn { args ->
            require(operations.size < 128) { "VBOOK_GRAPHICS_OPERATION_LIMIT" }
            val image = args.getOrNull(0)
            val base64 = when (image) {
                is Scriptable -> ScriptableObject.getProperty(image, "base64").takeUnless { it == Scriptable.NOT_FOUND }?.let(Context::toString).orEmpty()
                else -> Context.toString(image ?: "")
            }
            require(base64.isNotBlank()) { "VBOOK_GRAPHICS_IMAGE_REQUIRED" }
            val numeric = args.drop(1).mapNotNull { value -> Context.toNumber(value).takeIf(Double::isFinite) }
            operations += SourceGraphicsDrawOperation(base64, numeric)
            this
        }
        "capture", "toDataURL" -> fn { args ->
            val format = Context.toString(args.getOrNull(0) ?: "PNG").substringAfter('/').uppercase(Locale.ROOT)
            val quality = Context.toNumber(args.getOrNull(1) ?: 100).toInt().coerceIn(1, 100)
            when (val result = brokers.graphics.render(manifest, SourceGraphicsRequest(
                sourceId = manifest.id,
                width = width,
                height = height,
                operations = operations.toList(),
                format = format,
                quality = quality,
                traceId = request.traceId,
            ))) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("VBOOK_GRAPHICS_${result.error.code}:${result.error.message}")
            }
        }
        else -> super.get(name, start)
    }

    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}

private class BrowserCompatObject(
    private val cx: Context,
    private val ownerScope: Scriptable,
    private val brokers: SourceCapabilityBrokers,
    private val manifest: SourceManifest,
    private val request: SourceActionRequest,
    private val budget: RhinoExecutionBudget,
    private val clockMs: () -> Long,
) : ScriptableObject() {
    private var lastUrl: String = ""
    private var lastHtml: String = ""
    private var closed = false
    private var replayMode: String = "auto"
    private var replayKey: String = ""

    init { parentScope = ownerScope; prototype = ScriptableObject.getObjectPrototype(ownerScope) }
    override fun getClassName(): String = "VBookBrowserCompat"

    override fun get(name: String, start: Scriptable): Any = when (name) {
        "launch" -> fn { args ->
            val url = Context.toString(args.getOrNull(0) ?: "")
            val timeout = timeout(args.getOrNull(1))
            navigate(url, timeout)
            snapshot(0L, timeout)
        }
        "launchAsync" -> fn { args -> navigate(Context.toString(args.getOrNull(0) ?: ""), timeout(null)); true }
        "loadHtml" -> fn { args ->
            val baseUrl = Context.toString(args.getOrNull(0) ?: lastUrl)
            val html = Context.toString(args.getOrNull(1) ?: "")
            val response = execute(SourceBrowserAction.LOAD_HTML, url = baseUrl, value = html, timeoutMs = timeout(null))
            lastUrl = response.finalUrl ?: baseUrl
            this
        }
        "currentUrl" -> fn { lastUrl }
        "waitSelector" -> fn { args -> waitSelector(args.getOrNull(0), timeout(args.getOrNull(1))) }
        "waitUrl" -> fn { args -> waitUrl(args.getOrNull(0), timeout(args.getOrNull(1))) }
        "waitRequest" -> fn { args -> waitRequest(args.getOrNull(0), timeout(args.getOrNull(1)), args.getOrNull(2) as? Scriptable) }
        "requests" -> fn { args -> requests(args.getOrNull(0) as? Scriptable) }
        "urls" -> fn { VBookRhinoValues.strings(cx, ownerScope, requestMetadata().map { it.url }.distinct()) }
        "html" -> fn { args -> snapshot(Context.toNumber(args.getOrNull(0) ?: 0).toLong().coerceIn(0L, 2_000L), timeout(null)) }
        "callJs", "evaluate" -> fn { args -> evaluate(Context.toString(args.getOrNull(0) ?: ""), timeout(args.getOrNull(1))) }
        "callJson" -> fn { args ->
            val raw = evaluate(Context.toString(args.getOrNull(0) ?: ""), timeout(args.getOrNull(1)))
            runCatching { cx.evaluateString(ownerScope, "JSON.parse(${JsonCodec.stringify(JsonValue.Str(raw))})", "browser-json", 1, null) }
                .getOrElse { Context.getUndefinedValue() }
        }
        "callJsAsync", "evaluate_async" -> fn { args ->
            execute(SourceBrowserAction.EVALUATE_PAGE_SCRIPT_ASYNC, script = Context.toString(args.getOrNull(0) ?: ""), timeoutMs = timeout(args.getOrNull(1))).value.orEmpty()
        }
        "tapSelector", "tap_selector" -> fn { args ->
            execute(SourceBrowserAction.CLICK, selector = Context.toString(args.getOrNull(0) ?: ""), timeoutMs = timeout(args.getOrNull(1))).value.orEmpty() == "true"
        }
        "getVariable" -> fn { args -> evaluate(Context.toString(args.getOrNull(0) ?: ""), timeout(null)) }
        "cookie" -> fn { args -> brokers.cookies.readCookieHeader(manifest.id, Context.toString(args.getOrNull(0) ?: lastUrl)).orEmpty() }
        "cookieSnapshot" -> fn { args -> cookieSnapshot(Context.toString(args.getOrNull(0) ?: lastUrl)) }
        "syncSession" -> fn { args ->
            val url = Context.toString(args.getOrNull(0) ?: lastUrl)
            val direction = Context.toString(args.getOrNull(1) ?: "both")
            execute(SourceBrowserAction.SYNC_SESSION, url = url, options = mapOf("direction" to direction), timeoutMs = timeout(null))
            cookieSnapshot(url)
        }
        "setCookies" -> fn { args ->
            val url = Context.toString(args.getOrNull(1) ?: lastUrl)
            val cookies = stringList(args.getOrNull(0))
            execute(SourceBrowserAction.SET_COOKIES, url = url, values = cookies, timeoutMs = timeout(null))
            cookieSnapshot(url)
        }
        "clearCookies" -> fn { args ->
            val url = Context.toString(args.getOrNull(0) ?: lastUrl)
            execute(SourceBrowserAction.CLEAR_COOKIES, url = url, values = stringList(args.getOrNull(1)), timeoutMs = timeout(null)).value.orEmpty()
        }
        "block" -> fn { args ->
            execute(SourceBrowserAction.SET_BLOCK_PATTERNS, values = stringList(args.getOrNull(0)), timeoutMs = timeout(null))
            this
        }
        "setUserAgent" -> fn { args ->
            execute(SourceBrowserAction.SET_USER_AGENT, value = Context.toString(args.getOrNull(0) ?: ""), timeoutMs = timeout(null))
            this
        }
        "setReplayPolicy" -> fn { args ->
            replayMode = Context.toString(args.getOrNull(0) ?: "auto").lowercase(Locale.ROOT).takeIf { it in setOf("auto", "fresh", "keyed") } ?: "auto"
            replayKey = Context.toString(args.getOrNull(1) ?: "").take(256)
            this
        }
        "setDialogPolicy" -> fn { args ->
            val policy = args.getOrNull(0) as? Scriptable
            execute(SourceBrowserAction.SET_DIALOG_POLICY, options = mapOf(
                "defaultAction" to (policy?.propertyString("default_action") ?: policy?.propertyString("defaultAction") ?: "dismiss"),
                "defaultValue" to (policy?.propertyString("default_value") ?: policy?.propertyString("defaultValue") ?: ""),
            ), timeoutMs = timeout(null))
            this
        }
        "dialogs" -> fn { args -> dialogArray(execute(SourceBrowserAction.DIALOGS, timeoutMs = timeout(null)).dialogs.takeLast(Context.toNumber(args.getOrNull(0) ?: 50).toInt().coerceIn(1, 100))) }
        "lastDialog" -> fn { execute(SourceBrowserAction.DIALOGS, timeoutMs = timeout(null)).dialogs.lastOrNull()?.let(::dialogObject) ?: Context.getUndefinedValue() }
        "waitDialog" -> fn { args ->
            val options = args.getOrNull(0) as? Scriptable
            val response = execute(SourceBrowserAction.WAIT_DIALOG, options = mapOf(
                "type" to (options?.propertyString("type") ?: "any"),
                "match" to (options?.propertyString("match") ?: ""),
                "matchMode" to (options?.propertyString("match_mode") ?: options?.propertyString("mode") ?: "contains"),
                "afterId" to (options?.propertyString("after_id") ?: "0"),
            ), timeoutMs = options?.propertyString("timeout")?.toLongOrNull()?.coerceIn(100L, 120_000L) ?: 15_000L)
            response.dialogs.lastOrNull()?.let(::dialogObject) ?: Context.getUndefinedValue()
        }
        "close" -> fn { closed = true; execute(SourceBrowserAction.CLOSE_SESSION, timeoutMs = timeout(null)); true }
        else -> super.get(name, start)
    }

    private fun navigate(url: String, timeoutMs: Long) {
        require(!closed) { "VBOOK_BROWSER_CLOSED" }
        val response = execute(SourceBrowserAction.NAVIGATE, url = url, timeoutMs = timeoutMs)
        lastUrl = response.finalUrl ?: url
    }

    private fun snapshot(waitMs: Long, timeoutMs: Long): JsoupDocumentObject {
        if (waitMs > 0) Thread.sleep(waitMs)
        val response = execute(SourceBrowserAction.DOM_SNAPSHOT, timeoutMs = timeoutMs)
        lastUrl = response.finalUrl ?: lastUrl
        lastHtml = response.value.orEmpty()
        return JsoupDocumentObject(Jsoup.parse(lastHtml, lastUrl), ownerScope)
    }

    private fun evaluate(script: String, timeoutMs: Long): String {
        val response = execute(SourceBrowserAction.EVALUATE_PAGE_SCRIPT, script = script, timeoutMs = timeoutMs)
        lastUrl = response.finalUrl ?: lastUrl
        return response.value.orEmpty()
    }

    private fun waitSelector(raw: Any?, timeoutMs: Long): Any {
        val selectors = stringList(raw)
        if (selectors.isEmpty()) return false
        val perSelector = (timeoutMs / selectors.size).coerceAtLeast(250L)
        selectors.forEach { selector ->
            val response = brokers.browser.execute(manifest, SourceBrowserRequest(
                sourceId = manifest.id,
                action = SourceBrowserAction.WAIT_SELECTOR,
                selector = selector,
                timeoutMs = perSelector,
                traceId = request.traceId,
            ))
            if (response is SourcePlatformResult.Success) {
                lastUrl = response.value.finalUrl ?: lastUrl
                return selector
            }
        }
        return false
    }

    private fun waitUrl(raw: Any?, timeoutMs: Long): Any {
        val patterns = stringList(raw)
        val deadline = clockMs() + timeoutMs
        do {
            runCatching {
                val response = execute(SourceBrowserAction.DOM_SNAPSHOT, timeoutMs = minOf(2_000L, (deadline - clockMs()).coerceAtLeast(100L)))
                lastUrl = response.finalUrl ?: lastUrl
            }
            patterns.firstOrNull { matches(lastUrl, it) }?.let { return lastUrl }
            Thread.sleep(100)
        } while (clockMs() < deadline)
        return false
    }

    private fun waitRequest(raw: Any?, timeoutMs: Long, options: Scriptable?): Any {
        val patterns = stringList(raw)
        val deadline = clockMs() + timeoutMs
        do {
            val found = requestMetadata().firstOrNull { metadata ->
                (patterns.isEmpty() || patterns.any { matches(metadata.url, it) }) &&
                    (options?.propertyString("method").isNullOrBlank() || metadata.method.equals(options.propertyString("method"), true)) &&
                    (options?.propertyString("mainFrame")?.toBooleanStrictOrNull() != true || metadata.mainFrame)
            }
            if (found != null) return metadataObject(found)
            Thread.sleep(100)
        } while (clockMs() < deadline)
        return false
    }

    private fun requests(options: Scriptable?): Any {
        val patterns = options?.propertyObject("patterns")?.let(::stringList).orEmpty()
        val method = options?.propertyString("method").orEmpty()
        val mainFrame = options?.propertyString("mainFrame")?.toBooleanStrictOrNull() == true
        val limit = options?.propertyString("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val values = requestMetadata().filter { metadata ->
            (patterns.isEmpty() || patterns.any { matches(metadata.url, it) }) &&
                (method.isBlank() || metadata.method.equals(method, true)) && (!mainFrame || metadata.mainFrame)
        }.takeLast(limit).map(::metadataObject)
        return VBookRhinoValues.array(cx, ownerScope, values)
    }

    private fun requestMetadata(): List<vn.nghetruyen.source.api.SourceBrowserRequestMetadata> {
        val response = execute(SourceBrowserAction.REQUEST_METADATA, timeoutMs = 2_000L)
        lastUrl = response.finalUrl ?: lastUrl
        return response.requestMetadata
    }

    private fun metadataObject(metadata: vn.nghetruyen.source.api.SourceBrowserRequestMetadata): Scriptable = cx.newObject(ownerScope).also { obj ->
        ScriptableObject.putProperty(obj, "url", metadata.url)
        ScriptableObject.putProperty(obj, "method", metadata.method)
        ScriptableObject.putProperty(obj, "mainFrame", metadata.mainFrame)
        ScriptableObject.putProperty(obj, "resourceType", metadata.resourceType.orEmpty())
        ScriptableObject.putProperty(obj, "headerNames", VBookRhinoValues.strings(cx, ownerScope, metadata.headerNames.sorted()))
        ScriptableObject.putProperty(obj, "timestamp", metadata.timestampEpochMs)
    }

    private fun cookieSnapshot(url: String): Scriptable = cx.newObject(ownerScope).also { obj ->
        val cookie = brokers.cookies.readCookieHeader(manifest.id, url).orEmpty()
        ScriptableObject.putProperty(obj, "url", url)
        ScriptableObject.putProperty(obj, "cookie", cookie)
        ScriptableObject.putProperty(obj, "names", VBookRhinoValues.strings(cx, ownerScope, cookieNames(cookie)))
    }

    private fun execute(
        action: SourceBrowserAction,
        url: String? = null,
        selector: String? = null,
        value: String? = null,
        script: String? = null,
        values: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
        timeoutMs: Long,
    ): vn.nghetruyen.source.api.SourceBrowserResponse {
        budget.charge(25)
        val result = brokers.browser.execute(manifest, SourceBrowserRequest(
            sourceId = manifest.id,
            action = action,
            url = url,
            selector = selector,
            value = value,
            script = script,
            values = values,
            options = options + mapOf("replayMode" to replayMode, "replayKey" to replayKey),
            timeoutMs = minOf(timeoutMs, (budget.deadlineMs - clockMs()).coerceAtLeast(100L)),
            traceId = request.traceId,
        ))
        return when (result) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("VBOOK_BROWSER_${result.error.code}:${result.error.message}")
        }
    }

    private fun dialogArray(dialogs: List<SourceBrowserDialog>): Any =
        VBookRhinoValues.array(cx, ownerScope, dialogs.map(::dialogObject))

    private fun dialogObject(dialog: SourceBrowserDialog): Scriptable = cx.newObject(ownerScope).also { obj ->
        ScriptableObject.putProperty(obj, "id", dialog.id)
        ScriptableObject.putProperty(obj, "type", dialog.type)
        ScriptableObject.putProperty(obj, "message", dialog.message)
        ScriptableObject.putProperty(obj, "defaultValue", dialog.defaultValue ?: Context.getUndefinedValue())
        ScriptableObject.putProperty(obj, "url", dialog.pageUrl ?: "")
        ScriptableObject.putProperty(obj, "accepted", dialog.accepted ?: Context.getUndefinedValue())
        ScriptableObject.putProperty(obj, "value", dialog.responseValue ?: Context.getUndefinedValue())
        ScriptableObject.putProperty(obj, "timestamp", dialog.timestampEpochMs)
    }

    private fun timeout(raw: Any?): Long = Context.toNumber(raw ?: 15_000).toLong().coerceIn(100L, 120_000L)
    private fun stringList(value: Any?): List<String> = when (value) {
        null, Context.getUndefinedValue() -> emptyList()
        is NativeArray -> value.toStringList()
        is Scriptable -> (0 until (ScriptableObject.getProperty(value, "length") as? Number)?.toInt().orZero()).mapNotNull { index ->
            value.get(index, value).takeUnless { it == Scriptable.NOT_FOUND }?.let(Context::toString)
        }
        else -> listOf(Context.toString(value))
    }.map(String::trim).filter(String::isNotBlank)

    private fun matches(value: String, pattern: String): Boolean =
        VBookBrowserUrlMatcher.matches(value, pattern)

    private fun cookieNames(cookie: String): List<String> = cookie.split(';').mapNotNull { token -> token.substringBefore('=').trim().takeIf(String::isNotBlank) }.distinct()
    private fun Int?.orZero(): Int = this ?: 0

    private fun fn(block: (Array<out Any>) -> Any): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any = block(args)
    }
}


private fun Scriptable.propertyString(name: String): String? = ScriptableObject.getProperty(this, name).takeUnless { it == Scriptable.NOT_FOUND || it == Context.getUndefinedValue() }?.let(Context::toString)
private fun Scriptable.propertyObject(name: String): Scriptable? = ScriptableObject.getProperty(this, name) as? Scriptable
private fun NativeArray.toStringList(): List<String> = (0 until length.toInt()).map { Context.toString(get(it, this)) }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.hexBytes(): ByteArray {
    val clean = filterNot(Char::isWhitespace)
    require(clean.length % 2 == 0 && clean.all { it.digitToIntOrNull(16) != null }) { "VBOOK_HEX_INVALID" }
    return ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
