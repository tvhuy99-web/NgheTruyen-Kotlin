package com.nghetruyen.source.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRhinoSandboxTest {
    @Test
    fun executesWithJsonShapedBindings() {
        val result = SafeRhinoSandbox().execute(
            JsSandboxRequest(
                script = "input.answer + 1",
                bindings = mapOf(
                    "input" to JsValue.ObjectValue(mapOf("answer" to JsValue.NumberValue(41.0))),
                ),
            ),
        )

        assertEquals(JsValue.NumberValue(42.0), result.value)
    }

    @Test
    fun javaInteropGlobalsAreAbsent() {
        val result = SafeRhinoSandbox().execute(
            JsSandboxRequest("[typeof Packages, typeof java, typeof JavaAdapter].join(',')"),
        )

        assertEquals(JsValue.StringValue("undefined,undefined,undefined"), result.value)
    }

    @Test
    fun instructionBudgetStopsInfiniteLoop() {
        val sandbox = SafeRhinoSandbox(
            JsSandboxPolicy(
                maxInstructions = 5_000,
                wallClockTimeoutMs = 1_000,
                instructionObserverThreshold = 100,
            ),
        )

        val failure = runCatching { sandbox.execute(JsSandboxRequest("while (true) {}")) }.exceptionOrNull()
        assertTrue(failure is JsSandboxException)
        assertEquals(JsSandboxFailure.INSTRUCTION_LIMIT, (failure as JsSandboxException).failure)
    }

    @Test
    fun resultBudgetRejectsOversizedOutput() {
        val sandbox = SafeRhinoSandbox(JsSandboxPolicy(maxResultUnits = 8))
        val failure = runCatching { sandbox.execute(JsSandboxRequest("'123456789'")) }.exceptionOrNull()

        assertTrue(failure is JsSandboxException)
        assertEquals(JsSandboxFailure.RESULT_TOO_LARGE, (failure as JsSandboxException).failure)
    }

    @Test
    fun lowLevelExecutorSharesHardeningWithCompatibilityEngines() {
        val result = SafeRhinoExecutor().execute { context, scope, _ ->
            ContextResult(context.evaluateString(
                scope,
                "[typeof Packages, typeof java, typeof JavaAdapter].join(',')",
                "compat-engine.js",
                1,
                null,
            ).toString())
        }

        assertEquals(ContextResult("undefined,undefined,undefined"), result.value)
    }

    @Test
    fun observedHeapGrowthBudgetFailsClosed() {
        var usedHeap = 100L
        val executor = SafeRhinoExecutor(
            policy = JsSandboxPolicy(maxHeapGrowthBytes = 10),
            memoryUsageBytes = { usedHeap },
        )

        val failure = runCatching {
            executor.execute { _, _, budget ->
                usedHeap = 111L
                budget.charge(1)
            }
        }.exceptionOrNull()

        assertTrue(failure is JsSandboxException)
        assertEquals(JsSandboxFailure.MEMORY_LIMIT, (failure as JsSandboxException).failure)
    }

    private data class ContextResult(val value: String)
}
