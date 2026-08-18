package vn.nghetruyen.source.lua

import org.luaj.vm2.LuaValue
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNativeHookBroker
import vn.nghetruyen.source.api.SourceNativeHookRequest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode

class LuaNativeHookBroker : SourceNativeHookBroker {
    override fun execute(manifest: SourceManifest, request: SourceNativeHookRequest): SourcePlatformResult<String> = runCatching {
        require(request.hookName.matches(HOOK_NAME)) { "NATIVE_LUA_HOOK_NAME_INVALID" }
        require(request.sourceCode.size in 1..LuaSandbox.MAX_SOURCE_BYTES) { "NATIVE_LUA_SOURCE_TOO_LARGE" }
        require(request.inputJson.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "NATIVE_LUA_HOOK_INPUT_TOO_LARGE" }
        require(request.moduleSources.size <= MAX_MODULES) { "NATIVE_LUA_MODULE_LIMIT" }
        require(request.moduleSources.values.sumOf { it.size.toLong() } <= MAX_MODULE_BYTES) { "NATIVE_LUA_MODULE_BYTES_LIMIT" }
        require(request.resourceSources.size <= MAX_RESOURCES) { "NATIVE_LUA_RESOURCE_LIMIT" }
        require(request.resourceSources.values.sumOf { it.size.toLong() } <= MAX_RESOURCE_BYTES) { "NATIVE_LUA_RESOURCE_BYTES_LIMIT" }
        val nativeApi = LuaNativeHookBroker::class.java.getResourceAsStream(NATIVE_API_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("NATIVE_LUA_RUNTIME_RESOURCE_MISSING:$NATIVE_API_RESOURCE")
        val modules = linkedMapOf(NATIVE_API_MODULE to nativeApi).apply {
            request.moduleSources.forEach { (name, bytes) ->
                require(name.matches(MODULE_NAME)) { "NATIVE_LUA_MODULE_NAME_INVALID:$name" }
                require(bytes.size <= LuaSandbox.MAX_SOURCE_BYTES) { "NATIVE_LUA_MODULE_TOO_LARGE:$name" }
                put(name, bytes.toString(Charsets.UTF_8))
            }
        }
        val resources = request.resourceSources.mapKeys { (name, _) ->
            require(name.matches(RESOURCE_NAME)) { "NATIVE_LUA_RESOURCE_NAME_INVALID:$name" }
            name
        }
        val sandbox = LuaSandbox(
            modules = modules,
            resources = resources,
            instructionBudget = request.instructionBudget,
            timeoutMs = request.timeoutMs,
            memoryBudgetBytes = request.memoryBudgetBytes,
        )
        val packageValue = sandbox.evaluate(request.sourceCode.toString(Charsets.UTF_8), "@native/source.lua")
        val source = packageValue.get("source")
        val hook = source.get("hooks").get(request.hookName)
        require(hook.isfunction()) { "NATIVE_LUA_HOOK_NOT_FOUND:${request.hookName}" }
        val input = JsonCodec.parse(request.inputJson, maxDepth = 96, maxNodes = 100_000) as? JsonValue.Obj
            ?: error("NATIVE_LUA_HOOK_INPUT_INVALID")
        val value = sandbox.jsonToLua(input["value"] ?: JsonValue.Null)
        val args = sandbox.jsonToLua(input["args"] ?: JsonValue.Obj())
        val context = (input["context"] as? JsonValue.Obj) ?: JsonValue.Obj()
        val output = hook.call(sandbox.hookContext(context), value, args)
        val encoded = JsonCodec.stringify(sandbox.luaToJson(output))
        require(encoded.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "NATIVE_LUA_HOOK_OUTPUT_TOO_LARGE" }





        val wireOutput = if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) {
            JsonCodec.stringify(JsonValue.Str(encoded))
        } else {
            encoded
        }
        require(wireOutput.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "NATIVE_LUA_HOOK_OUTPUT_TOO_LARGE" }
        wireOutput
    }.fold(
        onSuccess = { SourcePlatformResult.Success(it) },
        onFailure = { error ->
            val message = error.message ?: "NATIVE_LUA_HOOK_FAILED"
            val code = when {
                "HOOK_NOT_FOUND" in message -> SourceErrorCode.NATIVE_LUA_HOOK_NOT_FOUND
                "TIMEOUT" in message || "INSTRUCTION" in message || "MEMORY_BUDGET" in message -> SourceErrorCode.RUNTIME_BUDGET_EXCEEDED
                else -> SourceErrorCode.NATIVE_LUA_SCRIPT_ERROR
            }
            SourcePlatformResult.Failure(SourcePlatformFailure(code, message, request.traceId, error))
        },
    )

    companion object {
        private const val NATIVE_API_MODULE = "app.sources.native_api"
        private const val NATIVE_API_RESOURCE = "/vn/nghetruyen/source/lua/native_api.lua"
        private val HOOK_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
        private val MODULE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,255}$")
        private val RESOURCE_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_./-]{0,511}$")
        private const val MAX_INPUT_BYTES = 512 * 1024
        private const val MAX_MODULES = 256
        private const val MAX_MODULE_BYTES = 32L * 1024L * 1024L
        private const val MAX_RESOURCES = 256
        private const val MAX_RESOURCE_BYTES = 48L * 1024L * 1024L
    }
}
