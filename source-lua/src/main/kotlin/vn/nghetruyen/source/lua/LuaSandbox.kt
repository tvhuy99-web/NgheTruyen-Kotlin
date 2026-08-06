package vn.nghetruyen.source.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class LuaExecutionBudget(
    private val instructionLimit: Int,
    timeoutMs: Long,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val deadlineMs = clockMs() + timeoutMs.coerceIn(100L, 120_000L)
    private val instructions = AtomicInteger()

    fun charge(amount: Int = HOOK_GRANULARITY) {
        if (instructions.addAndGet(amount.coerceAtLeast(1)) > instructionLimit) {
            throw LuaError("NATIVE_LUA_INSTRUCTION_BUDGET_EXCEEDED")
        }
        if (clockMs() > deadlineMs) throw LuaError("NATIVE_LUA_TIMEOUT")
    }

    companion object { const val HOOK_GRANULARITY = 1_000 }
}


internal class LuaMemoryBudget(
    private val limitBytes: Long,
) {
    private val runtime = Runtime.getRuntime()
    private val baselineBytes = usedBytes()
    private val reservedBytes = AtomicLong()

    fun reserve(bytes: Long) {
        if (bytes <= 0) return
        if (reservedBytes.addAndGet(bytes) > limitBytes) throw LuaError("NATIVE_LUA_MEMORY_BUDGET_EXCEEDED")
        sample()
    }

    fun sample() {
        val delta = (usedBytes() - baselineBytes).coerceAtLeast(0L)
        if (delta > limitBytes) throw LuaError("NATIVE_LUA_MEMORY_BUDGET_EXCEEDED")
    }

    private fun usedBytes(): Long = runtime.totalMemory() - runtime.freeMemory()
}

internal class LuaSandbox(
    private val modules: Map<String, String>,
    private val resources: Map<String, ByteArray> = emptyMap(),
    private val instructionBudget: Int,
    private val timeoutMs: Long,
    private val maxNodes: Int = 100_000,
    private val memoryBudgetBytes: Int = 32 * 1024 * 1024,
) {
    private val budget = LuaExecutionBudget(instructionBudget, timeoutMs)
    private val memory = LuaMemoryBudget(memoryBudgetBytes.toLong().coerceIn(1024L * 1024L, 256L * 1024L * 1024L))
    private val moduleCache = linkedMapOf<String, LuaValue>()
    val globals: Globals = JsePlatform.standardGlobals().also(::harden)

    fun evaluate(source: String, chunkName: String): LuaValue {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= MAX_SOURCE_BYTES) { "NATIVE_LUA_SOURCE_TOO_LARGE" }
        require(!source.startsWith("\u001bLua")) { "NATIVE_LUA_BYTECODE_DENIED" }
        memory.reserve(sourceBytes.size.toLong())
        budget.charge()
        return globals.load(source, chunkName).call().also { budget.charge() }
    }

    fun requireModule(name: String): LuaValue {
        moduleCache[name]?.let { return it }
        val source = modules[name] ?: modules[normalizeModuleName(name)] ?: throw LuaError("NATIVE_LUA_MODULE_DENIED:$name")
        memory.reserve(source.toByteArray(Charsets.UTF_8).size.toLong())
        budget.charge()
        return globals.load(source, "@$name").call().also { loaded ->
            moduleCache[name] = loaded
            budget.charge()
        }
    }

    fun jsonToLua(value: JsonValue): LuaValue {
        memory.reserve(estimateJsonBytes(value))
        return jsonToLuaInternal(value)
    }

    private fun jsonToLuaInternal(value: JsonValue): LuaValue = when (value) {
        JsonValue.Null -> LuaValue.NIL
        is JsonValue.Bool -> LuaValue.valueOf(value.value)
        is JsonValue.Num -> LuaValue.valueOf(value.value)
        is JsonValue.Str -> LuaValue.valueOf(value.value)
        is JsonValue.Arr -> LuaTable().also { table -> value.values.forEachIndexed { index, item -> table.set(index + 1, jsonToLuaInternal(item)) } }
        is JsonValue.Obj -> LuaTable().also { table -> value.values.forEach { (key, item) -> table.set(key, jsonToLuaInternal(item)) } }
    }

    fun luaToJson(value: LuaValue): JsonValue = luaToJson(value, 0, AtomicInteger())

    fun hookContext(context: JsonValue.Obj): LuaTable = LuaTable().also { ctx ->
        context.values.forEach { (key, value) -> ctx.set(key, jsonToLua(value)) }
        ctx.set("string", stringHelpers())
        ctx.set("regex", regexHelpers())
        ctx.set("json", jsonHelpers())
        ctx.set("resource", resourceHelpers())
    }

    private fun harden(globals: Globals) {
        globals.load(DebugLib())
        globals.set("__native_budget_hook", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                budget.charge()
                memory.sample()
                return LuaValue.NONE
            }
        })
        globals.load("debug.sethook(__native_budget_hook, '', ${LuaExecutionBudget.HOOK_GRANULARITY})", "@native-budget").call()
        globals.set("__native_budget_hook", LuaValue.NIL)
        globals.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = requireModule(arg.checkjstring())
        })
        listOf(
            "luajava", "io", "os", "debug", "package", "dofile", "loadfile", "load", "loadstring",
            "collectgarbage", "module", "print",
        ).forEach { globals.set(it, LuaValue.NIL) }
    }

    private fun stringHelpers(): LuaTable = LuaTable().also { table ->
        table.set("trim", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(arg.tojstring().trim())
        })
        table.set("lower", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(arg.tojstring().lowercase(Locale.ROOT))
        })
        table.set("upper", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(arg.tojstring().uppercase(Locale.ROOT))
        })
        table.set("contains", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue = LuaValue.valueOf(a.tojstring().contains(b.tojstring()))
        })
    }

    private fun regexHelpers(): LuaTable = LuaTable().also { table ->
        table.set("replace", object : ThreeArgFunction() {
            override fun call(value: LuaValue, pattern: LuaValue, replacement: LuaValue): LuaValue = runCatching {
                LuaValue.valueOf(Regex(pattern.checkjstring()).replace(value.tojstring(), replacement.tojstring()))
            }.getOrElse { throw LuaError("NATIVE_LUA_REGEX_INVALID:${it.message}") }
        })
        table.set("matches", object : TwoArgFunction() {
            override fun call(value: LuaValue, pattern: LuaValue): LuaValue = runCatching {
                LuaValue.valueOf(Regex(pattern.checkjstring()).containsMatchIn(value.tojstring()))
            }.getOrElse { throw LuaError("NATIVE_LUA_REGEX_INVALID:${it.message}") }
        })
        table.set("find", object : TwoArgFunction() {
            override fun call(value: LuaValue, pattern: LuaValue): LuaValue = runCatching {
                Regex(pattern.checkjstring()).find(value.tojstring())?.value?.let(LuaValue::valueOf) ?: LuaValue.NIL
            }.getOrElse { throw LuaError("NATIVE_LUA_REGEX_INVALID:${it.message}") }
        })
    }

    private fun resourceHelpers(): LuaTable = LuaTable().also { table ->
        table.set("exists", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(normalizeResourcePath(arg.checkjstring()) in resources)
        })
        table.set("text", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val path = normalizeResourcePath(arg.checkjstring())
                val bytes = resources[path] ?: return LuaValue.NIL
                require(bytes.size <= MAX_RESOURCE_BYTES) { "NATIVE_LUA_RESOURCE_TOO_LARGE:$path" }
                memory.reserve(bytes.size.toLong())
                return LuaValue.valueOf(bytes.toString(Charsets.UTF_8))
            }
        })
        table.set("base64", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val path = normalizeResourcePath(arg.checkjstring())
                val bytes = resources[path] ?: return LuaValue.NIL
                require(bytes.size <= MAX_RESOURCE_BYTES) { "NATIVE_LUA_RESOURCE_TOO_LARGE:$path" }
                memory.reserve((bytes.size * 4L / 3L) + 64L)
                return LuaValue.valueOf(java.util.Base64.getEncoder().encodeToString(bytes))
            }
        })
        table.set("list", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val prefix = normalizeResourcePrefix(arg.optjstring(""))
                val result = LuaTable()
                resources.keys.filter { it.startsWith(prefix) }.sorted().take(512).forEachIndexed { index, value -> result.set(index + 1, LuaValue.valueOf(value)) }
                return result
            }
        })
    }

    private fun jsonHelpers(): LuaTable = LuaTable().also { table ->
        table.set("decode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = jsonToLua(JsonCodec.parse(arg.checkjstring(), maxDepth = 96, maxNodes = maxNodes))
        })
        table.set("encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(JsonCodec.stringify(luaToJson(arg)))
        })
    }

    private fun luaToJson(value: LuaValue, depth: Int, nodes: AtomicInteger): JsonValue {
        require(depth <= 96) { "NATIVE_LUA_OUTPUT_TOO_DEEP" }
        require(nodes.incrementAndGet() <= maxNodes) { "NATIVE_LUA_OUTPUT_TOO_COMPLEX" }
        memory.reserve(64L + if (value.isstring()) value.tojstring().toByteArray(Charsets.UTF_8).size else 0)
        return when {
            value.isnil() -> JsonValue.Null
            value.isboolean() -> JsonValue.Bool(value.toboolean())
            value.isnumber() -> {
                val number = value.todouble()
                require(number.isFinite()) { "NATIVE_LUA_NUMBER_INVALID" }
                JsonValue.Num(number, if (number % 1.0 == 0.0) number.toLong().toString() else number.toString())
            }
            value.isstring() -> JsonValue.Str(value.tojstring())
            value.istable() -> tableToJson(value.checktable(), depth, nodes)
            else -> error("NATIVE_LUA_OUTPUT_TYPE_UNSUPPORTED")
        }
    }

    private fun tableToJson(table: LuaTable, depth: Int, nodes: AtomicInteger): JsonValue {
        val keys = table.keys().toList()
        val numeric = keys.mapNotNull { if (it.isint()) it.toint() else null }
        val isArray = keys.isNotEmpty() && numeric.size == keys.size && numeric.minOrNull() == 1 && numeric.maxOrNull() == numeric.size
        if (keys.isEmpty()) return JsonValue.Obj()
        if (isArray) return JsonValue.Arr((1..numeric.size).map { luaToJson(table.get(it), depth + 1, nodes) })
        val values = linkedMapOf<String, JsonValue>()
        keys.sortedBy(LuaValue::tojstring).forEach { key ->
            if (key.isstring() || key.isnumber()) values[key.tojstring()] = luaToJson(table.get(key), depth + 1, nodes)
        }
        return JsonValue.Obj(LinkedHashMap(values))
    }

    private fun estimateJsonBytes(value: JsonValue): Long = when (value) {
        JsonValue.Null -> 16L
        is JsonValue.Bool, is JsonValue.Num -> 24L
        is JsonValue.Str -> 32L + value.value.toByteArray(Charsets.UTF_8).size
        is JsonValue.Arr -> 48L + value.values.sumOf(::estimateJsonBytes)
        is JsonValue.Obj -> 64L + value.values.entries.sumOf { (key, item) -> key.toByteArray(Charsets.UTF_8).size.toLong() + estimateJsonBytes(item) }
    }

    private fun normalizeModuleName(raw: String): String = raw.trim().removeSuffix(".lua").replace('/', '.').replace('\\', '.')

    private fun normalizeResourcePath(raw: String): String {
        val value = raw.trim().replace('\\', '/').removePrefix("./").removePrefix("/")
        require(value.isNotBlank() && value.length <= 512 && value.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "NATIVE_LUA_RESOURCE_PATH_INVALID"
        }
        return value
    }

    private fun normalizeResourcePrefix(raw: String): String {
        if (raw.isBlank()) return ""
        return normalizeResourcePath(raw).removeSuffix("/") + "/"
    }

    companion object {
        const val MAX_SOURCE_BYTES = 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
    }
}
