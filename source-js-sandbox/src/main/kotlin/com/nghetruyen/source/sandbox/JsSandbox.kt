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
    /**
     * Compatibility/telemetry threshold, not the immediate kill switch.
     *
     * Real vBook sources can legitimately cross the historical 500k Rhino observer count while
     * still completing quickly. Treating this number as a hard limit made the Kotlin host reject
     * sources that complete in the Lua host. The actual runaway breaker is derived by
     * [hardInstructionMultiplier] and is still combined with the wall-clock deadline.
     */
    val maxInstructions: Long = 500_000,
    val wallClockTimeoutMs: Long = 2_000,
    val instructionObserverThreshold: Int = 1_000,
    /** Hard runaway ceiling = maxInstructions * hardInstructionMultiplier. */
    val hardInstructionMultiplier: Int = 16,
    /** Maximum positive heap growth observed during one execution. Null disables this guard. */
    val maxHeapGrowthBytes: Long? = null,
    val maxResultUnits: Int = 1_000_000,
    val maxCollectionItems: Int = 20_000,
    val maxValueDepth: Int = 64,
    val languageVersion: Int = Context.VERSION_ES6,
) {
    val hardInstructionLimit: Long
        get() = if (maxInstructions > Long.MAX_VALUE / hardInstructionMultiplier) {
            Long.MAX_VALUE
        } else {
            maxInstructions * hardInstructionMultiplier
        }

    init {
        require(maxInstructions > 0)
        require(wallClockTimeoutMs > 0)
        require(instructionObserverThreshold > 0)
        require(hardInstructionMultiplier >= 2)
        require(maxHeapGrowthBytes == null || maxHeapGrowthBytes > 0)
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
    val softInstructionLimitExceeded: Boolean = false,
)

enum class JsSandboxFailure {
    TIMEOUT,
    INSTRUCTION_LIMIT,
    MEMORY_LIMIT,
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

data class RhinoExecutionResult<T>(
    val value: T,
    val elapsedMs: Long,
    val observedInstructions: Long,
    val peakHeapGrowthBytes: Long,
    val softInstructionLimitExceeded: Boolean = false,
)

/**
 * Shared low-level Rhino executor for compatibility engines.
 *
 * Engines install their own ABI inside [block], while context hardening and budgets remain shared.
 * The heap guard is deliberately conservative: it observes positive process-heap growth at every
 * instruction/host charge boundary and never exposes JVM objects to the script scope.
 *
 * Instruction accounting is deliberately two-tiered. [JsSandboxPolicy.maxInstructions] is a soft
 * compatibility threshold used for diagnostics; execution is aborted only at the derived hard
 * runaway ceiling or the wall-clock/heap deadline. This preserves sandbox safety without making
 * Rhino observer counts an accidental incompatibility with the Lua host.
 */
class SafeRhinoExecutor(
    private val policy: JsSandboxPolicy = JsSandboxPolicy(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val memoryUsageBytes: () -> Long = {
        val runtime = Runtime.getRuntime()
        runtime.totalMemory() - runtime.freeMemory()
    },
) {
    fun <T> execute(block: (Context, ScriptableObject, RhinoExecutionBudget) -> T): RhinoExecutionResult<T> {
        val started = clockMs()
        val budget = RhinoExecutionBudget(
            policy = policy,
            deadlineMs = started + policy.wallClockTimeoutMs,
            clockMs = clockMs,
            memoryUsageBytes = memoryUsageBytes,
        )
        val factory = BudgetContextFactory(policy, budget)
        return try {
            val value = factory.call(ContextAction<T> { context ->
                configureContext(context, policy)
                val scope = context.initSafeStandardObjects()
                removeInteropGlobals(scope)
                budget.beginMeasuring()
                block(context, scope, budget)
            })
            budget.checkpoint()
            RhinoExecutionResult(
                value = value,
                elapsedMs = (clockMs() - started).coerceAtLeast(0L),
                observedInstructions = budget.instructions,
                peakHeapGrowthBytes = budget.peakHeapGrowthBytes,
                softInstructionLimitExceeded = budget.softInstructionLimitExceeded,
            )
        } catch (e: BudgetExceededException) {
            throw JsSandboxException(e.failure, e.message ?: e.failure.name, e)
        } catch (e: RhinoException) {
            throw JsSandboxException(JsSandboxFailure.SCRIPT_ERROR, e.message ?: "JavaScript execution failed", e)
        }
    }
}

class RhinoExecutionBudget internal constructor(
    private val policy: JsSandboxPolicy,
    val deadlineMs: Long,
    private val clockMs: () -> Long,
    private val memoryUsageBytes: () -> Long,
) {
    private var baselineHeapBytes: Long? = null

    val softInstructionLimit: Long get() = policy.maxInstructions
    val hardInstructionLimit: Long get() = policy.hardInstructionLimit

    var instructions: Long = 0
        private set

    var peakHeapGrowthBytes: Long = 0
        private set

    var softInstructionLimitExceeded: Boolean = false
        private set

    fun charge(value: Int) {
        instructions += value.coerceAtLeast(1).toLong()
        checkpoint()
    }

    internal fun beginMeasuring() {
        baselineHeapBytes = memoryUsageBytes()
        peakHeapGrowthBytes = 0
    }

    fun checkpoint() {
        if (instructions > policy.maxInstructions) {
            softInstructionLimitExceeded = true
        }
        if (instructions > policy.hardInstructionLimit) {
            throw BudgetExceededException(
                JsSandboxFailure.INSTRUCTION_LIMIT,
                "JavaScript exceeded hard runaway limit ${policy.hardInstructionLimit} observed instructions " +
                    "(soft compatibility budget ${policy.maxInstructions}, observed $instructions)",
            )
        }
        if (clockMs() > deadlineMs) {
            throw BudgetExceededException(
                JsSandboxFailure.TIMEOUT,
                "JavaScript exceeded ${policy.wallClockTimeoutMs} ms " +
                    "(observedInstructions=$instructions, softInstructionBudget=${policy.maxInstructions}, " +
                    "hardInstructionLimit=${policy.hardInstructionLimit})",
            )
        }
        val baseline = baselineHeapBytes ?: memoryUsageBytes().also { baselineHeapBytes = it }
        val growth = (memoryUsageBytes() - baseline).coerceAtLeast(0L)
        peakHeapGrowthBytes = maxOf(peakHeapGrowthBytes, growth)
        val limit = policy.maxHeapGrowthBytes
        if (limit != null && growth > limit) {
            throw BudgetExceededException(
                JsSandboxFailure.MEMORY_LIMIT,
                "JavaScript exceeded $limit bytes of observed heap growth " +
                    "(observedInstructions=$instructions, peakHeapGrowthBytes=$peakHeapGrowthBytes)",
            )
        }
    }
}

class SafeRhinoSandbox(
    private val policy: JsSandboxPolicy = JsSandboxPolicy(),
) {
    private val executor = SafeRhinoExecutor(policy)

    fun execute(
        request: JsSandboxRequest,
        extensions: List<JsSandboxExtension> = emptyList(),
    ): JsSandboxResult {
        val execution = executor.execute { context, scope, _ ->
            request.bindings.forEach { (name, binding) ->
                ScriptableObject.putProperty(scope, name, toNative(context, scope, binding, 0))
            }
            extensions.forEach { it.install(context, scope) }
            val evaluated = context.evaluateString(scope, request.script, request.sourceName, 1, null)
            fromNative(evaluated, 0)
        }
        enforceResultBudget(execution.value)
        return JsSandboxResult(
            value = execution.value,
            elapsedMs = execution.elapsedMs,
            observedInstructions = execution.observedInstructions,
            softInstructionLimitExceeded = execution.softInstructionLimitExceeded,
        )
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

}

private fun configureContext(context: Context, policy: JsSandboxPolicy) {
    context.setLanguageVersion(policy.languageVersion)
    context.setOptimizationLevel(-1)
    context.setClassShutter(ClassShutter { false })
}

private fun removeInteropGlobals(scope: ScriptableObject) {
    INTEROP_GLOBALS.forEach { ScriptableObject.deleteProperty(scope, it) }
}

private class BudgetContextFactory(
    private val policy: JsSandboxPolicy,
    private val budget: RhinoExecutionBudget,
) : ContextFactory() {
    override fun makeContext(): Context = super.makeContext().apply {
        instructionObserverThreshold = policy.instructionObserverThreshold
    }

    override fun observeInstructionCount(context: Context, instructionCount: Int) {
        budget.charge(instructionCount)
    }
}

private class BudgetExceededException(
    val failure: JsSandboxFailure,
    message: String,
) : RuntimeException(message)

private val INTEROP_GLOBALS = listOf(
    "Packages", "java", "javax", "org", "com", "edu", "net",
    "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass",
)
