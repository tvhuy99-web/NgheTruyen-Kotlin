package vn.nghetruyen.app.sourceplatform


internal class BrowserPageStabilityPolicy(
    private val deadlineMs: Long,
) {
    private var signature = ""
    private var matchingProbes = 0
    private var sawChallenge = false

    fun evaluate(probe: Probe): Decision {
        sawChallenge = sawChallenge || probe.challenge
        if (probe.nowMs >= deadlineMs) {
            return Decision.Timeout(if (sawChallenge) "SOURCE_BROWSER_CHALLENGE_UNRESOLVED" else "SOURCE_BROWSER_TIMEOUT")
        }

        val renderReady = !probe.loading &&
            probe.readyState in setOf("interactive", "complete") &&
            probe.progress >= 100 &&
            probe.lastPageFinishedAtMs > 0L
        if (!renderReady || probe.challenge) {
            resetMatches()
            return Decision.Continue(if (probe.challenge) "challenge" else "not-render-ready", matchingProbes)
        }

        val currentSignature = listOf(
            probe.url,
            probe.htmlLength,
            probe.textLength,
            probe.elementCount,
            probe.scrollHeight,
        ).joinToString("|")
        if (currentSignature == signature) matchingProbes += 1 else {
            signature = currentSignature
            matchingProbes = 1
        }

        val readyAge = probe.nowMs - probe.lastPageFinishedAtMs
        val progressQuietAge = probe.nowMs - probe.lastProgressAtMs
        val pageQuietAge = probe.nowMs - maxOf(probe.lastPageEventAtMs, probe.lastPageFinishedAtMs)
        val stable = matchingProbes >= REQUIRED_MATCHING_PROBES &&
            readyAge >= MIN_READY_AGE_MS &&
            probe.mutationAgeMs >= DOM_QUIET_MS &&
            progressQuietAge >= PROGRESS_QUIET_MS &&
            pageQuietAge >= PAGE_EVENT_QUIET_MS
        return if (stable) {
            Decision.Stable(matchingProbes)
        } else {
            Decision.Continue("settling", matchingProbes)
        }
    }

    private fun resetMatches() {
        signature = ""
        matchingProbes = 0
    }

    data class Probe(
        val nowMs: Long,
        val url: String,
        val readyState: String,
        val progress: Int,
        val loading: Boolean,
        val lastPageFinishedAtMs: Long,
        val lastPageEventAtMs: Long,
        val lastProgressAtMs: Long,
        val htmlLength: Int,
        val textLength: Int,
        val elementCount: Int,
        val scrollHeight: Int,
        val mutationAgeMs: Long,
        val challenge: Boolean,
    )

    sealed interface Decision {
        data class Continue(val reason: String, val matchingProbes: Int) : Decision
        data class Stable(val matchingProbes: Int) : Decision
        data class Timeout(val code: String) : Decision
    }

    companion object {
        const val MIN_DOM_PROBE_PROGRESS = 100
        const val READINESS_DIAGNOSTIC_INTERVAL_MS = 5_000L
        const val PROBE_INTERVAL_MS = 350L
        const val DOCUMENT_READY_FALLBACK_MS = 800L
        const val MIN_READY_AGE_MS = 1_500L
        const val DOM_QUIET_MS = 700L
        const val PROGRESS_QUIET_MS = 350L
        const val PAGE_EVENT_QUIET_MS = 350L
        const val REQUIRED_MATCHING_PROBES = 3

        fun shouldProbeDom(progress: Int): Boolean = progress >= MIN_DOM_PROBE_PROGRESS
    }
}
