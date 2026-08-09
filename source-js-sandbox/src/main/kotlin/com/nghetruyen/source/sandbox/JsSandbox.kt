package com.nghetruyen.source.sandbox

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** JSON-shaped values are the default boundary. Arbitrary JVM objects are never injected. */
sealed interface JsValue {
    object Null : JsValue
    object UndefinedValue : JsValue
    data class Bool(val value: Boolean) : JsValue
    data class NumberValue(val value: Double) : JsValue
    data class StringValue(val value: String) : JsValue
    data class ArrayValue(val values: List<JsValue>) : JsValue
    data class ObjectValue(val values: Map<String, JsValue>) : JsValue
}

data class JsSandboxPolicy(
    val maxInstructions: Long = 500_000,
    val wallClockTimeoutMs: Long = 2_000,
    val instructionObserverThreshold: Int = 1_000,
    val maxResultUnits: Int = 1_000_000,
    val maxCollectionItems: Int = 20_000,
    val maxValueDepth: Int = 64,
    val languageVersion: Int = Context.VERSION_ES6,
) {
    init {
        require(maxInstructions > 0)
        require(wallClockTimeoutMs > 0)
        require(instructionObserverThreshold > 0)
        require(maxResultUnits > 0)
        require(maxCollectionItems > 0)
        require(maxValueDepth > 0)
    }
}

data class JsSandboxRequest(
    val script: String,
    val sourceName: String = "extension.js",
    val bindings: Map<String, JsValue> = emptyMap(),
) {
    init {
        require(script.isNotBlank()) { "script must not be blank" }
        require(sourceName.isNotBlank()) { "sourceName must not be blank" }
        bindings.keys.forEach { key ->
            require(IDENTIFIER.matches(key)) { "Invalid JavaScript binding name: $key" }
            require(key !in RESERVED_BINDINGS) { "Reserved JavaScript binding name: $key" }
        }
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val RESERVED_BINDINGS = setOf("Packages", "java", "javax", "org", "com", "edu", "net", "JavaAdapter", "JavaImporter")
    }
}

data class JsSandboxResult(
    val value: JsValue,
    val elapsedMs: Long,
    val observedInstructions: Long,
)

enum class JsSandboxFailure {
    TIMEOUT,
    INSTRUCTION_LIMIT,
    RESULT_TOO_LARGE,
    RESULT_TOO_DEEP,
    RESULT_TOO_MANY_ITEMS,
    SCRIPT_ERROR,
}

class JsSandboxException(
    val failure: JsSandboxFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Trusted engine adapter hook. vBook and Legado bindings must be installed separately. */
fun interface JsSandboxExtension {
    fun install(context: Context, scope: ScriptableObject)
}

class SafeRhinoSandbox(
    private val policy: JsSandboxPolicy = JsSandboxPolicy(),
) {
    private val factory = BudgetContextFactory(policy)

    fun execute(
        request: JsSandboxRequest,
        extensions: List<JsSandboxExtension> = emptyList(),
    ): JsSandboxResult {
        val budget = ExecutionBudget(policy)
        factory.currentBudget.set(budget)
        return try {
            val started = System.nanoTime()
            val value = factory.call(ContextAction { context ->
                configureContext(context)
                val scope = context.initSafeStandardObjects()
                removeInteropGlobals(scope)
                request.bindings.forEach { (name, binding) ->
                    ScriptableObject.putProperty(scope, name, toNative(context, scope, binding, 0))
                }
                extensions.forEach { it.install(context, scope) }
                val evaluated = context.evaluateString(scope, request.script, request.sourceName, 1, null)
                fromNative(evaluated, 0)
            })
            enforceResultBudget(value)
            JsSandboxResult(
                value = value,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                observedInstructions = budget.instructions,
            )
        } catch (e: BudgetExceededException) {
            throw JsSandboxException(e.failure, e.message ?: e.failure.name, e)
        } catch (e: RhinoException) {
            throw JsSandboxException(JsSandboxFailure.SCRIPT_ERROR, e.message ?: "JavaScript execution failed", e)
        } finally {
            factory.currentBudget.remove()
        }
    }

    private fun configureContext(context: Context) {
        context.setLanguageVersion(policy.languageVersion)
        context.setOptimizationLevel(-1)
        context.setClassShutter(ClassShutter { false })
    }

    private fun removeInteropGlobals(scope: ScriptableObject) {
        INTEROP_GLOBALS.forEach { ScriptableObject.deleteProperty(scope, it) }
    }

    private fun toNative(context: Context, scope: Scriptable, value: JsValue, depth: Int): Any? {
        ensureDepth(depth)
        return when (value) {
            JsValue.Null -> null
            JsValue.UndefinedValue -> Undefined.instance
            is JsValue.Bool -> value.value
            is JsValue.NumberValue -> value.value
            is JsValue.StringValue -> value.value
            is JsValue.ArrayValue -> {
                ensureItems(value.values.size)
                context.newArray(scope, value.values.map { toNative(context, scope, it, depth + 1) }.toTypedArray())
            }
            is JsValue.ObjectValue -> {
                ensureItems(value.values.size)
                val obj = context.newObject(scope)
                value.values.forEach { (key, child) ->
                    ScriptableObject.putProperty(obj, key, toNative(context, scope, child, depth + 1))
                }
                obj
            }
        }
    }

    private fun fromNative(value: Any?, depth: Int): JsValue {
        ensureDepth(depth)
        return when (value) {
            null -> JsValue.Null
            Undefined.instance -> JsValue.UndefinedValue
            is Boolean -> JsValue.Bool(value)
            is Number -> JsValue.NumberValue(value.toDouble())
            is CharSequence -> JsValue.StringValue(value.toString())
            is NativeArray -> {
                val length = value.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ensureItems(length)
                JsValue.ArrayValue((0 until length).map { index ->
                    fromNative(ScriptableObject.getProperty(value, index), depth + 1)
                })
            }
            is Scriptable -> {
                val ids = value.ids
                ensureItems(ids.size)
                val result = LinkedHashMap<String, JsValue>(ids.size)
                ids.forEach { id ->
                    val key = id.toString()
                    val child = when (id) {
                        is Int -> ScriptableObject.getProperty(value, id)
                        else -> ScriptableObject.getProperty(value, key)
                    }
                    result[key] = fromNative(child, depth + 1)
                }
                JsValue.ObjectValue(result)
            }
            else -> JsValue.StringValue(Context.toString(value))
        }
    }

    private fun enforceResultBudget(value: JsValue) {
        val units = resultUnits(value, 0)
        if (units > policy.maxResultUnits) {
            throw JsSandboxException(
                JsSandboxFailure.RESULT_TOO_LARGE,
                "JavaScript result exceeds ${policy.maxResultUnits} units",
            )
        }
    }

    private fun resultUnits(value: JsValue, depth: Int): Long {
        ensureDepth(depth)
        return when (value) {
            JsValue.Null, JsValue.UndefinedValue -> 1
            is JsValue.Bool -> 1
            is JsValue.NumberValue -> 8
            is JsValue.StringValue -> value.value.length.toLong()
            is JsValue.ArrayValue -> 2 + value.values.sumOf { resultUnits(it, depth + 1) }
            is JsValue.ObjectValue -> 2 + value.values.entries.sumOf { (key, child) -> key.length + resultUnits(child, depth + 1) }
        }
    }

    private fun ensureDepth(depth: Int) {
        if (depth > policy.maxValueDepth) {
            throw JsSandboxException(JsSandboxFailure.RESULT_TOO_DEEP, "JavaScript value depth exceeds ${policy.maxValueDepth}")
        }
    }

    private fun ensureItems(size: Int) {
        if (size > policy.maxCollectionItems) {
            throw JsSandboxException(
                JsSandboxFailure.RESULT_TOO_MANY_ITEMS,
                "JavaScript collection exceeds ${policy.maxCollectionItems} items",
            )
        }
    }

    private class BudgetContextFactory(private val policy: JsSandboxPolicy) : ContextFactory() {
        val currentBudget = ThreadLocal<ExecutionBudget>()

        override fun makeContext(): Context {
            val context = super.makeContext()
            context.instructionObserverThreshold = policy.instructionObserverThreshold
            return context
        }

        override fun observeInstructionCount(context: Context, instructionCount: Int) {
            val budget = currentBudget.get() ?: return
            budget.instructions += instructionCount.toLong()
            if (budget.instructions > policy.maxInstructions) {
                throw BudgetExceededException(
                    JsSandboxFailure.INSTRUCTION_LIMIT,
                    "JavaScript exceeded ${policy.maxInstructions} observed instructions",
                )
            }
            if (System.nanoTime() > budget.deadlineNanos) {
                throw BudgetExceededException(
                    JsSandboxFailure.TIMEOUT,
                    "JavaScript exceeded ${policy.wallClockTimeoutMs} ms",
                )
            }
        }
    }

    private class ExecutionBudget(policy: JsSandboxPolicy) {
        val deadlineNanos = System.nanoTime() + policy.wallClockTimeoutMs * 1_000_000
        var instructions: Long = 0
    }

    private class BudgetExceededException(
        val failure: JsSandboxFailure,
        message: String,
    ) : RuntimeException(message)

    companion object {
        private val INTEROP_GLOBALS = listOf(
            "Packages", "java", "javax", "org", "com", "edu", "net",
            "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass",
        )
    }
}
