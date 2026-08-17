from pathlib import Path

BROKER = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
POLICY = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserPageStabilityPolicy.kt")
TEST = Path("app/src/test/java/vn/nghetruyen/app/sourceplatform/BrowserPageStabilityPolicyTest.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


broker = BROKER.read_text()
broker = replace_once(
    broker,
    "        var probeCount = 0\n        var lastUrl: String? = session.logicalPageUrl\n        while (true) {",
    "        var probeCount = 0\n        var lastReadinessDiagnosticAt = -1L\n        var lastUrl: String? = session.logicalPageUrl\n        while (true) {",
    "add readiness diagnostic state",
)
broker = replace_once(
    broker,
    """            val rawResult = runCatching {
                evaluate(session, PAGE_STABILITY_SCRIPT, minOf(1_200L, remaining.coerceAtLeast(100L)))
            }
            if (rawResult.isFailure) {""",
    """            if (!BrowserPageStabilityPolicy.shouldProbeDom(session.currentProgress)) {
                if (lastReadinessDiagnosticAt < 0L ||
                    now - lastReadinessDiagnosticAt >= BrowserPageStabilityPolicy.READINESS_DIAGNOSTIC_INTERVAL_MS
                ) {
                    lastReadinessDiagnosticAt = now
                    diagnostics.emit(event(session.manifest, request, "BROWSER_DOM_STABILITY_WAITING", DiagnosticSeverity.DEBUG, attributes = mapOf(
                        "flow" to "browser",
                        "stage" to "dom_stability_waiting",
                        "progress" to session.currentProgress.toString(),
                        "loading" to session.pageLoading.toString(),
                        "pageFinished" to (session.lastPageFinishedAtMs > 0L).toString(),
                        "remainingMs" to remaining.toString(),
                    )))
                }
                Thread.sleep(minOf(BrowserPageStabilityPolicy.PROBE_INTERVAL_MS, remaining.coerceAtLeast(1L)))
                continue
            }

            val rawResult = runCatching {
                evaluate(
                    session,
                    PAGE_STABILITY_SCRIPT,
                    minOf(1_200L, remaining.coerceAtLeast(100L)),
                    timeoutSeverity = DiagnosticSeverity.DEBUG,
                )
            }
            if (rawResult.isFailure) {""",
    "gate and soften stability probe",
)
broker = replace_once(
    broker,
    "    private fun evaluate(session: Session, expression: String, timeoutMs: Long): String {",
    """    private fun evaluate(
        session: Session,
        expression: String,
        timeoutMs: Long,
        timeoutSeverity: DiagnosticSeverity = DiagnosticSeverity.WARN,
    ): String {""",
    "add evaluate timeout severity",
)
broker = replace_once(
    broker,
    "            diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_TIMEOUT\", DiagnosticSeverity.WARN, attributes = mapOf(",
    "            diagnostics.emit(sessionEvent(session.manifest, session, \"BROWSER_EVAL_TIMEOUT\", timeoutSeverity, attributes = mapOf(",
    "use requested timeout severity",
)
BROKER.write_text(broker)

policy = POLICY.read_text()
policy = replace_once(
    policy,
    "    companion object {\n        const val PROBE_INTERVAL_MS = 350L",
    "    companion object {\n        const val MIN_DOM_PROBE_PROGRESS = 100\n        const val READINESS_DIAGNOSTIC_INTERVAL_MS = 5_000L\n        const val PROBE_INTERVAL_MS = 350L",
    "add DOM probe readiness constants",
)
policy = replace_once(
    policy,
    "        const val REQUIRED_MATCHING_PROBES = 3\n    }\n}",
    "        const val REQUIRED_MATCHING_PROBES = 3\n\n        fun shouldProbeDom(progress: Int): Boolean = progress >= MIN_DOM_PROBE_PROGRESS\n    }\n}",
    "add DOM probe readiness predicate",
)
POLICY.write_text(policy)

test = TEST.read_text()
test = replace_once(
    test,
    "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue",
    "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue",
    "add assertFalse import",
)
test = replace_once(
    test,
    "class BrowserPageStabilityPolicyTest {\n    @Test",
    """class BrowserPageStabilityPolicyTest {
    @Test
    fun domProbeWaitsUntilRendererReachesRunnableProgress() {
        assertFalse(BrowserPageStabilityPolicy.shouldProbeDom(10))
        assertFalse(BrowserPageStabilityPolicy.shouldProbeDom(99))
        assertTrue(BrowserPageStabilityPolicy.shouldProbeDom(100))
    }

    @Test""",
    "add probe readiness regression test",
)
TEST.write_text(test)
