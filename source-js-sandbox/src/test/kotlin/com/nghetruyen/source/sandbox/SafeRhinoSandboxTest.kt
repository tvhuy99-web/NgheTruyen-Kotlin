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
}
