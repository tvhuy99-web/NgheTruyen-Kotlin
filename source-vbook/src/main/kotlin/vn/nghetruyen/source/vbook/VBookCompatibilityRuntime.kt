package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider

/**
 * Contract-aware facade over the mature vBook host API implementation.
 *
 * The existing VBookJsRuntime owns browser/network/storage/crypto bridges. This facade owns
 * vBook contract semantics: profile detection, exact positional string args, config constants,
 * dynamic package scripts, Response shape and load('crypto.js') behavior.
 */
class VBookCompatibilityRuntime(
    private val runtime: VBookJsRuntime = VBookJsRuntime(),
) {
    data class ExecutionResult(
        val data: JsonValue,
        val continuation: VBookContinuation,
        val profile: VBookContractProfile,
        val rawEnvelope: JsonValue,
        val instructionCount: Int,
        val traceId: String,
    )

    fun executeDeclared(
        sourceManifest: SourceManifest,
        resources: SourceResourceProvider,
        role: VBookScriptRole,
        input: String = "",
        continuation: VBookContinuation = VBookContinuation(),
        text: String = "",
        voiceId: String = "",
        from: String = "",
        to: String = "",
        source: String = "",
        persistedConfig: Map<String, String> = emptyMap(),
        runtimeConfig: Map<String, String> = emptyMap(),
        traceId: String = "",
    ): SourcePlatformResult<ExecutionResult> {
        val plugin = loadPlugin(resources) ?: return failure(SourceErrorCode.VBOOK_SCRIPT_ERROR, "VBOOK_PLUGIN_JSON_MISSING", traceId)
        val profile = detectProfile(plugin, resources)
        if (profile == VBookContractProfile.UNKNOWN) {
            return failure(SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE, "VBOOK_CONTRACT_PROFILE_AMBIGUOUS", traceId)
        }
        val script = plugin.script(role)
            ?: return failure(SourceErrorCode.ACTION_NOT_FOUND, "VBOOK_SCRIPT_ROLE_MISSING:${role.manifestKey}", traceId)
        val invocation = if (profile == VBookContractProfile.CURRENT_JS) {
            VBookInvocationPlanner.current(role, script, input, continuation, text, voiceId, from, to, source)
        } else {
            // Legacy core signatures are stable for the common roles. Unknown/dynamic legacy calls use executeDynamic.
            val args = when (role) {
                VBookScriptRole.HOME, VBookScriptRole.EXPLORE, VBookScriptRole.GENRE,
                VBookScriptRole.VOICE, VBookScriptRole.LANGUAGE -> emptyList()
                VBookScriptRole.SEARCH -> listOf(input, continuation.token)
                VBookScriptRole.DETAIL, VBookScriptRole.TOC, VBookScriptRole.CHAP,
                VBookScriptRole.PAGE, VBookScriptRole.TRACK -> listOf(input)
                VBookScriptRole.TTS -> listOf(text, voiceId)
                VBookScriptRole.TRANSLATE -> listOf(text, from, to, source)
            }
            VBookInvocationPlanner.legacy(script, args)
        }
        return execute(
            sourceManifest, resources, plugin, profile, invocation,
            persistedConfig, runtimeConfig, traceId,
        )
    }

    fun executeDynamic(
        sourceManifest: SourceManifest,
        resources: SourceResourceProvider,
        scriptPath: String,
        args: List<String>,
        persistedConfig: Map<String, String> = emptyMap(),
        runtimeConfig: Map<String, String> = emptyMap(),
        traceId: String = "",
    ): SourcePlatformResult<ExecutionResult> {
        val plugin = loadPlugin(resources) ?: return failure(SourceErrorCode.VBOOK_SCRIPT_ERROR, "VBOOK_PLUGIN_JSON_MISSING", traceId)
        val profile = detectProfile(plugin, resources)
        if (profile == VBookContractProfile.UNKNOWN) {
            return failure(SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE, "VBOOK_CONTRACT_PROFILE_AMBIGUOUS", traceId)
        }
        return execute(
            sourceManifest = sourceManifest,
            resources = resources,
            plugin = plugin,
            profile = profile,
            invocation = VBookScriptInvocation(VBookPaths.normalizeScriptPath(scriptPath), args.map(String::toString)),
            persistedConfig = persistedConfig,
            runtimeConfig = runtimeConfig,
            traceId = traceId,
        )
    }

    private fun execute(
        sourceManifest: SourceManifest,
        resources: SourceResourceProvider,
        plugin: VBookExtensionManifest,
        profile: VBookContractProfile,
        invocation: VBookScriptInvocation,
        persistedConfig: Map<String, String>,
        runtimeConfig: Map<String, String>,
        traceId: String,
    ): SourcePlatformResult<ExecutionResult> {
        val normalizedScript = VBookPaths.normalizeScriptPath(invocation.scriptPath)
        if (resources.read(normalizedScript, 2 * 1024 * 1024) == null) {
            return failure(SourceErrorCode.ACTION_NOT_FOUND, "VBOOK_SCRIPT_RESOURCE_MISSING:$normalizedScript", traceId)
        }
        val config = VBookConfigValues.resolve(plugin, persistedConfig, runtimeConfig)
        val dispatcher = buildDispatcher(profile, config)
        val overlay = OverlayResources(resources, DISPATCH_PATH, dispatcher.toByteArray(Charsets.UTF_8))
        val manifest = sourceManifest.copy(
            actions = mapOf(
                SourceActionName.UI_ACTION to SourceActionSpec(
                    entry = DISPATCH_PATH,
                    timeoutMs = sourceManifest.runtime.actionTimeoutMs,
                    maxOutputBytes = 4 * 1024 * 1024,
                ),
            ),
        )
        val input = JsonValue.Obj(linkedMapOf(
            "script" to JsonValue.Str(normalizedScript.removePrefix("src/")),
            "args" to JsonValue.Arr(invocation.args.map(JsonValue::Str)),
        ))
        val request = SourceActionRequest(
            sourceId = sourceManifest.id,
            action = SourceActionName.UI_ACTION,
            input = input,
            traceId = traceId,
        )
        return when (val result = runtime.execute(manifest, overlay, request)) {
            is SourcePlatformResult.Failure -> result
            is SourcePlatformResult.Success -> decodeDispatchResult(result.value, profile, traceId)
        }
    }

    private fun decodeDispatchResult(
        response: SourceActionResponse,
        profile: VBookContractProfile,
        requestedTraceId: String,
    ): SourcePlatformResult<ExecutionResult> = runCatching {
        val obj = response.value as? JsonValue.Obj ?: error("VBOOK_DISPATCH_RESULT_OBJECT_REQUIRED")
        val encoded = obj.string(RAW_RESULT_KEY) ?: error("VBOOK_DISPATCH_RAW_RESULT_MISSING")
        val rawValue = JsonValue.Str(encoded)
        val envelope = VBookResponseEnvelopeParser.parse(rawValue, profile)
        ExecutionResult(
            data = envelope.data,
            continuation = envelope.continuation,
            profile = profile,
            rawEnvelope = envelope.raw,
            instructionCount = response.instructionCount,
            traceId = response.traceId,
        )
    }.fold(
        onSuccess = { SourcePlatformResult.Success(it) },
        onFailure = { error ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.VBOOK_SCRIPT_ERROR,
                error.message ?: "VBOOK_RESPONSE_DECODE_FAILED",
                requestedTraceId,
                error,
            ))
        },
    )

    private fun loadPlugin(resources: SourceResourceProvider): VBookExtensionManifest? {
        val bytes = resources.read("plugin.json", 1024 * 1024) ?: return null
        return runCatching { VBookManifestParser.parse(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun detectProfile(plugin: VBookExtensionManifest, resources: SourceResourceProvider): VBookContractProfile {
        val sources = plugin.allDeclaredScriptPaths().associateWith { path ->
            resources.read(path, 2 * 1024 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        }
        return VBookContractDetector.detect(plugin, sources).profile
    }

    private fun buildDispatcher(profile: VBookContractProfile, config: VBookConfigValues): String {
        val configJson = JsonCodec.stringify(JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            config.values.forEach { (key, value) -> put(key, JsonValue.Str(value)) }
        }))
        val prelude = VBookConfigPrelude.build(profile, config)
        val responsePrelude = when (profile) {
            VBookContractProfile.CURRENT_JS -> "" // host bootstrap already implements code 0/1
            VBookContractProfile.LEGACY_JS -> """
                Response = Object.freeze({
                  success:function(data,data2){return JSON.stringify({code:200,data:data,data2:(data2===undefined?null:data2)});},
                  error:function(message){return JSON.stringify({code:403,data:String(message||'VBook error')});}
                });
            """.trimIndent()
            VBookContractProfile.UNKNOWN -> error("VBOOK_CONTRACT_PROFILE_REQUIRED")
        }
        return """
            'use strict';
            $responsePrelude
            var __vbookConfigValues = $configJson;
            localConfig = Object.freeze({
              getItem:function(key){ key=String(key||''); return Object.prototype.hasOwnProperty.call(__vbookConfigValues,key) ? __vbookConfigValues[key] : undefined; },
              key:function(index){ var keys=Object.keys(__vbookConfigValues).sort(); return keys[Number(index)||0]; },
              length:Object.keys(__vbookConfigValues).length
            });
            var __vbookPackageLoad = load;
            var __vbookInsideLoad = false;
            load = function(name) {
              name = String(name || '');
              if (name.toLowerCase() === 'crypto.js') return true;
              if (__vbookInsideLoad) throw new Error('VBOOK_RECURSIVE_LOAD_NOT_ALLOWED');
              __vbookInsideLoad = true;
              try { return __vbookPackageLoad(name); }
              finally { __vbookInsideLoad = false; }
            };
            var __vbookNativeFetch = fetch;
            fetch = function(url, options) {
              options = options || {};
              url = String(url || '');
              if (options.queries) {
                var parts = [], keys = Object.keys(options.queries);
                for (var qi=0; qi<keys.length; qi++) {
                  var qk=keys[qi], qv=options.queries[qk];
                  parts.push(encodeURIComponent(String(qk)) + '=' + encodeURIComponent(String(qv == null ? '' : qv)));
                }
                if (parts.length) url += (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&');
              }
              var response = __vbookNativeFetch(url, options);
              if (typeof response.header !== 'function') {
                response.header = function(name) {
                  name = String(name || '').toLowerCase();
                  var headers = response.headers || {}, keys = Object.keys(headers);
                  for (var i=0;i<keys.length;i++) if (keys[i].toLowerCase() === name) return headers[keys[i]];
                  return undefined;
                };
              }
              if (response.statusText === undefined) response.statusText = '';
              if (!response.request) response.request = {url:url, headers:options.headers || {}};
              if (typeof response.base64 !== 'function') response.base64 = function(){ return Crypto.utf8ToBase64(response.text()); };
              if (typeof response.blob !== 'function') response.blob = function(){
                var b64=response.base64(), type=response.header('content-type') || '';
                return {size:Crypto.base64Length(b64), type:String(type).split(';')[0], base64:function(){return b64;}};
              };
              return response;
            };
            $prelude
            function execute(payload) {
              payload = payload || {};
              var script = String(payload.script || '');
              var args = payload.args || [];
              var raw;
              switch (args.length) {
                case 0: raw = Script.execute(script, 'execute'); break;
                case 1: raw = Script.execute(script, 'execute', String(args[0])); break;
                case 2: raw = Script.execute(script, 'execute', String(args[0]), String(args[1])); break;
                case 3: raw = Script.execute(script, 'execute', String(args[0]), String(args[1]), String(args[2])); break;
                case 4: raw = Script.execute(script, 'execute', String(args[0]), String(args[1]), String(args[2]), String(args[3])); break;
                default: throw new Error('VBOOK_SCRIPT_ARGUMENT_COUNT_UNSUPPORTED:' + args.length);
              }
              if (typeof raw !== 'string') raw = JSON.stringify(raw);
              return JSON.stringify({code:0,data:{"$RAW_RESULT_KEY":String(raw)},data2:null});
            }
        """.trimIndent()
    }

    private fun failure(code: SourceErrorCode, message: String, traceId: String) =
        SourcePlatformResult.Failure(SourcePlatformFailure(code, message, traceId))

    private class OverlayResources(
        private val delegate: SourceResourceProvider,
        private val overlayPath: String,
        private val overlayBytes: ByteArray,
    ) : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? {
            if (path == overlayPath) {
                require(overlayBytes.size <= maxBytes) { "VBOOK_DISPATCH_RESOURCE_TOO_LARGE" }
                return overlayBytes.copyOf()
            }
            return delegate.read(path, maxBytes)
        }
    }

    companion object {
        private const val DISPATCH_PATH = "src/__nghe_vbook_dispatch.js"
        private const val RAW_RESULT_KEY = "__ngheVBookRawResult"
    }
}
