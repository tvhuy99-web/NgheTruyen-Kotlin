package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageStabilityPolicyTest {
    @Test
    fun domProbeWaitsUntilRendererReachesRunnableProgress() {
        assertFalse(BrowserPageStabilityPolicy.shouldProbeDom(10))
        assertFalse(BrowserPageStabilityPolicy.shouldProbeDom(99))
        assertTrue(BrowserPageStabilityPolicy.shouldProbeDom(100))
    }

    @Test
    fun challengePageNeverBecomesStableAndGetsSpecificTimeout() {
        val policy = BrowserPageStabilityPolicy(deadlineMs = 5_000)

        assertTrue(policy.evaluate(probe(now = 2_000, challenge = true)) is BrowserPageStabilityPolicy.Decision.Continue)
        val timeout = policy.evaluate(probe(now = 5_000, challenge = true)) as BrowserPageStabilityPolicy.Decision.Timeout

        assertEquals("SOURCE_BROWSER_CHALLENGE_UNRESOLVED", timeout.code)
    }

    @Test
    fun redirectOrReloadCannotReuseThePreviousFinishedPage() {
        val policy = BrowserPageStabilityPolicy(deadlineMs = 20_000)

        val decision = policy.evaluate(probe(
            now = 4_000,
            loading = true,
            progress = 10,
            lastFinished = 0,
        ))

        assertTrue(decision is BrowserPageStabilityPolicy.Decision.Continue)
    }

    @Test
    fun stablePageRequiresReadyAgeDomQuietAndRepeatedSignature() {
        val policy = BrowserPageStabilityPolicy(deadlineMs = 20_000)

        assertTrue(policy.evaluate(probe(now = 3_000)) is BrowserPageStabilityPolicy.Decision.Continue)
        assertTrue(policy.evaluate(probe(now = 3_350)) is BrowserPageStabilityPolicy.Decision.Continue)
        val stable = policy.evaluate(probe(now = 3_700))

        assertTrue(stable is BrowserPageStabilityPolicy.Decision.Stable)
    }

    private fun probe(
        now: Long,
        challenge: Boolean = false,
        loading: Boolean = false,
        progress: Int = 100,
        lastFinished: Long = 2_000,
    ) = BrowserPageStabilityPolicy.Probe(
        nowMs = now,
        url = "https://example.com/page",
        readyState = "complete",
        progress = progress,
        loading = loading,
        lastPageFinishedAtMs = lastFinished,
        lastPageEventAtMs = lastFinished,
        lastProgressAtMs = lastFinished,
        htmlLength = 20_000,
        textLength = 2_000,
        elementCount = 120,
        scrollHeight = 4_000,
        mutationAgeMs = 1_000,
        challenge = challenge,
    )
}
