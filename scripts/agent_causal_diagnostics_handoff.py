from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"exact-match failed for {path}: expected 1, got {count}\n--- needle ---\n{old[:1200]}")
    p.write_text(text.replace(old, new, 1))


def write_new(path: str, content: str) -> None:
    p = Path(path)
    if p.exists():
        raise SystemExit(f"refusing to overwrite existing file: {path}")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


# 1) A coroutine-safe causal trace context. It survives SourcePackStorySource's Dispatchers.IO hop.
write_new(
    "app/src/main/java/vn/nghetruyen/app/sources/DiagnosticCausalTrace.kt",
    '''package vn.nghetruyen.app.sources

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Correlates one user-facing source action with every nested runtime action/evidence item.
 * CoroutineContext is used instead of global or ThreadLocal state so concurrent source calls cannot
 * inherit each other's trace and the value survives dispatcher changes.
 */
internal class DiagnosticCausalTrace(
    val traceId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticCausalTrace>
}

internal suspend fun currentDiagnosticCausalTraceId(): String? =
    coroutineContext[DiagnosticCausalTrace]
        ?.traceId
        ?.trim()
        ?.takeIf(String::isNotBlank)
''',
)

# 2) The top-level StorySource wrapper owns/reuses the causal trace and installs it around delegates.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sources/DiagnosticStorySource.kt",
    "import kotlinx.coroutines.CancellationException\n",
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.withContext\n",
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sources/DiagnosticStorySource.kt",
    '''        val operationId = "source-action:${descriptor.id.take(120)}:${action.lowercase()}:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val base = baseAttributes(action) + attributes
''',
    '''        val operationId = currentDiagnosticCausalTraceId()
            ?: "source-action:${descriptor.id.take(120)}:${action.lowercase()}:${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val base = baseAttributes(action) + attributes + mapOf("diagnosticRootTraceId" to operationId)
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sources/DiagnosticStorySource.kt",
    '''        return try {
            when (val result = block()) {
''',
    '''        return try {
            when (val result = withContext(DiagnosticCausalTrace(operationId)) { block() }) {
''',
)

# 3) Every nested source-pack action reuses that traceId. DETAIL/TOC/native hooks/network/evidence
# now have the same causal identity as the outer STORY operation.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt",
    "import vn.nghetruyen.app.sources.SourceUiSurface\n",
    "import vn.nghetruyen.app.sources.SourceUiSurface\nimport vn.nghetruyen.app.sources.currentDiagnosticCausalTraceId\n",
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt",
    '''    private fun execute(action: SourceActionName, input: JsonValue.Obj): JsonValue? {
        if (action !in pack.manifest.actions) return null
        return when (val result = executor.execute(pack, resources, SourceActionRequest(pack.manifest.id, action, input))) {
            is SourcePlatformResult.Success -> result.value.value
            is SourcePlatformResult.Failure -> throw SourcePackRuntimeFailure(result.error.code.name, result.error.message)
        }
    }
''',
    '''    private suspend fun execute(action: SourceActionName, input: JsonValue.Obj): JsonValue? {
        if (action !in pack.manifest.actions) return null
        val causalTraceId = currentDiagnosticCausalTraceId()
        val request = if (causalTraceId == null) {
            SourceActionRequest(pack.manifest.id, action, input)
        } else {
            SourceActionRequest(pack.manifest.id, action, input, traceId = causalTraceId)
        }
        return when (val result = executor.execute(pack, resources, request)) {
            is SourcePlatformResult.Success -> result.value.value
            is SourcePlatformResult.Failure -> throw SourcePackRuntimeFailure(result.error.code.name, result.error.message)
        }
    }
''',
)

# 4) Recorder rotation can selectively hand off exactly one causal trace instead of deleting it or
# carrying the entire previous screen.
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''data class DiagnosticRecorderStats(
    val itemCount: Int,
    val evictedEvents: Long,
    val staleScreenEventsDropped: Long = 0,
)
''',
    '''data class DiagnosticRecorderStats(
    val itemCount: Int,
    val evictedEvents: Long,
    val staleScreenEventsDropped: Long = 0,
    val screenRotationEventsDiscarded: Long = 0,
    val screenHandoffEventsRetained: Long = 0,
)
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''    private var evictedEvents: Long = 0
    private var screenGeneration: Long = 0
    private var staleScreenEventsDropped: Long = 0
''',
    '''    private var evictedEvents: Long = 0
    private var screenGeneration: Long = 0
    private var staleScreenEventsDropped: Long = 0
    private var screenRotationEventsDiscarded: Long = 0
    private var screenHandoffEventsRetained: Long = 0
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''    fun stats(): DiagnosticRecorderStats = lock.withLock {
        DiagnosticRecorderStats(events.size, evictedEvents, staleScreenEventsDropped)
    }
''',
    '''    fun stats(): DiagnosticRecorderStats = lock.withLock {
        DiagnosticRecorderStats(
            itemCount = events.size,
            evictedEvents = evictedEvents,
            staleScreenEventsDropped = staleScreenEventsDropped,
            screenRotationEventsDiscarded = screenRotationEventsDiscarded,
            screenHandoffEventsRetained = screenHandoffEventsRetained,
        )
    }
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''    /**
     * Screen-mode rotation now has strict Lua-style provenance: nothing from the previous screen is
     * copied into the new screen. Origin bindings remain so late callbacks can be identified and
     * suppressed from the new timeline while still reaching lifecycle mirrors.
     *
     * The historical method name is retained for source compatibility. It intentionally returns an
     * empty set so legacy callers also clear, rather than carry, browser/network evidence.
     */
    fun retainActiveOperationTraces(): Set<String> = lock.withLock {
        screenGeneration = if (screenGeneration == Long.MAX_VALUE) 1L else screenGeneration + 1L
        events.clear()
        evictedEvents = 0
        staleScreenEventsDropped = 0
        emptySet()
    }
''',
    '''    /**
     * Starts a new screen generation while retaining only explicitly selected causal traces.
     * Retained events are re-stamped as handoff evidence for the destination screen; unrelated
     * previous-screen events are discarded and counted. Trace origin is moved to the new generation
     * so a late callback from the handed-off navigation can still complete on the destination screen.
     */
    fun rotateScreen(
        retainTraceIds: Set<String> = emptySet(),
        handoffAttributes: Map<String, String> = emptyMap(),
    ): Set<String> = lock.withLock {
        val selected = retainTraceIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_ORIGIN_BINDINGS)
            .toCollection(linkedSetOf())
        val previousGeneration = screenGeneration
        val nextGeneration = if (screenGeneration == Long.MAX_VALUE) 1L else screenGeneration + 1L
        val retained = events.asSequence()
            .filter { it.traceId in selected }
            .map { event ->
                val origin = event.attributes["diagnosticOriginScreenGeneration"]
                    ?: event.attributes["diagnosticScreenGeneration"]
                    ?: previousGeneration.toString()
                event.copy(
                    attributes = event.attributes + handoffAttributes + mapOf(
                        "diagnosticOriginScreenGeneration" to origin,
                        "diagnosticScreenGeneration" to nextGeneration.toString(),
                        "diagnosticScreenDisposition" to "handoff",
                        "diagnosticHandoffReason" to "navigation-result",
                    ),
                )
            }
            .toList()

        screenRotationEventsDiscarded = (events.size - retained.size).coerceAtLeast(0).toLong()
        screenHandoffEventsRetained = retained.size.toLong()
        screenGeneration = nextGeneration
        events.clear()
        retained.forEach(events::addLast)
        selected.forEach { traceId -> rememberOrigin(traceOrigins, traceId, nextGeneration, overwrite = true) }
        selected
    }

    /** Legacy strict rotation: carry nothing. */
    fun retainActiveOperationTraces(): Set<String> = rotateScreen(emptySet())
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''            evictedEvents = 0
            staleScreenEventsDropped = 0
            screenGeneration = 0
''',
    '''            evictedEvents = 0
            staleScreenEventsDropped = 0
            screenRotationEventsDiscarded = 0
            screenHandoffEventsRetained = 0
            screenGeneration = 0
''',
)

# 5) Evidence follows the same selective handoff contract and reports rotation discards truthfully.
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''data class DiagnosticEvidenceStats(
    val itemCount: Int,
    val retainedBytes: Long,
    val evictedItems: Long,
    val truncatedItems: Long,
    val staleScreenItemsDropped: Long = 0,
)
''',
    '''data class DiagnosticEvidenceStats(
    val itemCount: Int,
    val retainedBytes: Long,
    val evictedItems: Long,
    val truncatedItems: Long,
    val staleScreenItemsDropped: Long = 0,
    val screenRotationItemsDiscarded: Long = 0,
    val screenHandoffItemsRetained: Long = 0,
)
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''    private var evictedItems: Long = 0
    private var truncatedItems: Long = 0
    private var staleScreenItemsDropped: Long = 0
    @Volatile override var enabled: Boolean = false
''',
    '''    private var evictedItems: Long = 0
    private var truncatedItems: Long = 0
    private var staleScreenItemsDropped: Long = 0
    private var screenRotationItemsDiscarded: Long = 0
    private var screenHandoffItemsRetained: Long = 0
    @Volatile override var enabled: Boolean = false
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''    /** Legacy screen-rotation API. Passing an empty set clears all previous-screen evidence. */
    fun retainTraces(traceIds: Set<String>) = lock.withLock {
        val retained = items.filter { it.traceId in traceIds }
        items.clear()
        retained.forEach(items::addLast)
        retainedBytes = retained.sumOf { it.data.size.toLong() }
        evictedItems = 0
        truncatedItems = retained.count { it.attributes["truncated"].equals("true", ignoreCase = true) }.toLong()
        staleScreenItemsDropped = 0
    }
''',
    '''    /**
     * Screen rotation retains only evidence belonging to the causal traces explicitly handed off.
     * Unlike eviction counters, rotation discard counters describe the latest screen boundary and are
     * never silently reset by the boundary itself.
     */
    fun retainTraces(
        traceIds: Set<String>,
        targetGeneration: Long? = null,
        handoffAttributes: Map<String, String> = emptyMap(),
    ) = lock.withLock {
        val selected = traceIds.asSequence().map(String::trim).filter(String::isNotBlank).toSet()
        val retained = items.asSequence()
            .filter { it.traceId in selected }
            .map { item ->
                if (targetGeneration == null) item else {
                    val origin = item.attributes["diagnosticOriginScreenGeneration"]
                        ?: item.attributes["diagnosticScreenGeneration"]
                    val provenance = buildMap {
                        if (origin != null) put("diagnosticOriginScreenGeneration", origin)
                        put("diagnosticScreenGeneration", targetGeneration.toString())
                        put("diagnosticScreenDisposition", "handoff")
                        put("diagnosticHandoffReason", "navigation-result")
                        putAll(handoffAttributes)
                    }
                    item.copy(attributes = item.attributes + provenance)
                }
            }
            .toList()
        screenRotationItemsDiscarded = (items.size - retained.size).coerceAtLeast(0).toLong()
        screenHandoffItemsRetained = retained.size.toLong()
        items.clear()
        retained.forEach(items::addLast)
        retainedBytes = retained.sumOf { it.data.size.toLong() }
    }
''',
)
replace_once(
    "source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt",
    '''        evictedItems = 0
        truncatedItems = 0
        staleScreenItemsDropped = 0
    }

    fun stats(): DiagnosticEvidenceStats = lock.withLock {
        DiagnosticEvidenceStats(items.size, retainedBytes, evictedItems, truncatedItems, staleScreenItemsDropped)
    }
''',
    '''        evictedItems = 0
        truncatedItems = 0
        staleScreenItemsDropped = 0
        screenRotationItemsDiscarded = 0
        screenHandoffItemsRetained = 0
    }

    fun stats(): DiagnosticEvidenceStats = lock.withLock {
        DiagnosticEvidenceStats(
            itemCount = items.size,
            retainedBytes = retainedBytes,
            evictedItems = evictedItems,
            truncatedItems = truncatedItems,
            staleScreenItemsDropped = staleScreenItemsDropped,
            screenRotationItemsDiscarded = screenRotationItemsDiscarded,
            screenHandoffItemsRetained = screenHandoffItemsRetained,
        )
    }
''',
)

# 6) Runtime navigation API performs selective handoff and exposes honest loss/retention accounting.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt",
    '''    fun onScreenChanged(screenKey: String): Boolean {
''',
    '''    fun onScreenChanged(screenKey: String, handoffTraceIds: Set<String> = emptySet()): Boolean {
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt",
    '''            MODE_SCREEN -> {
                // Historical API name retained in source-diagnostics; strict screen mode now returns
                // an empty carry set and advances the immutable screen generation.
                recorder.retainActiveOperationTraces()
                evidence.retainTraces(emptySet())
                mark(
                    name = "DIAGNOSTIC_SCREEN_STARTED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf(
                        "screen" to next,
                        "previousScreen" to previous,
                        "screenGeneration" to recorder.currentScreenGeneration().toString(),
                        "screenIsolation" to "strict-origin",
                        "carriedActiveTraceCount" to "0",
                    ),
                )
                true
            }
''',
    '''            MODE_SCREEN -> {
                val selectedHandoff = handoffTraceIds.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .take(64)
                    .toSet()
                val handoffAttributes = mapOf(
                    "screen" to next,
                    "screenSessionId" to activeScreenSessionId,
                )
                recorder.rotateScreen(selectedHandoff, handoffAttributes)
                evidence.retainTraces(
                    traceIds = selectedHandoff,
                    targetGeneration = recorder.currentScreenGeneration(),
                    handoffAttributes = handoffAttributes,
                )
                val recorderStats = recorder.stats()
                val evidenceStats = evidence.stats()
                mark(
                    name = "DIAGNOSTIC_SCREEN_STARTED",
                    category = DiagnosticCategory.RUNTIME,
                    severity = DiagnosticSeverity.INFO,
                    attributes = mapOf(
                        "screen" to next,
                        "previousScreen" to previous,
                        "screenGeneration" to recorder.currentScreenGeneration().toString(),
                        "screenIsolation" to if (selectedHandoff.isEmpty()) "strict-origin" else "selective-causal-handoff",
                        "handoffTraceCount" to selectedHandoff.size.toString(),
                        "handoffEventCount" to recorderStats.screenHandoffEventsRetained.toString(),
                        "handoffEvidenceCount" to evidenceStats.screenHandoffItemsRetained.toString(),
                        "rotationDiscardedEventCount" to recorderStats.screenRotationEventsDiscarded.toString(),
                        "rotationDiscardedEvidenceCount" to evidenceStats.screenRotationItemsDiscarded.toString(),
                        "carriedActiveTraceCount" to "0",
                    ),
                )
                true
            }
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt",
    '''                put("staleScreenEventsDropped", recorderStats.staleScreenEventsDropped)
                put("staleScreenEvidenceDropped", evidenceStats.staleScreenItemsDropped)
                put("screenIsolationDropsIntentional", true)
                if (recorderStats.staleScreenEventsDropped > 0 || evidenceStats.staleScreenItemsDropped > 0) {
                    put("lossVisible", true)
                }
''',
    '''                put("staleScreenEventsDropped", recorderStats.staleScreenEventsDropped)
                put("staleScreenEvidenceDropped", evidenceStats.staleScreenItemsDropped)
                put("screenRotationEventsDiscarded", recorderStats.screenRotationEventsDiscarded)
                put("screenHandoffEventsRetained", recorderStats.screenHandoffEventsRetained)
                put("screenRotationEvidenceDiscarded", evidenceStats.screenRotationItemsDiscarded)
                put("screenHandoffEvidenceRetained", evidenceStats.screenHandoffItemsRetained)
                put("screenIsolationDropsIntentional", true)
                if (
                    recorderStats.staleScreenEventsDropped > 0 || evidenceStats.staleScreenItemsDropped > 0 ||
                    recorderStats.screenRotationEventsDiscarded > 0 || evidenceStats.screenRotationItemsDiscarded > 0
                ) {
                    put("lossVisible", true)
                    put("lossReason", "screen-isolation-discard")
                }
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt",
    '''        appendLine("- screen-scoped: strict provenance. A screen change starts a new immutable generation; no previous-screen event or evidence is copied into it. Late callbacks remain visible to lifecycle tracking but are dropped from the new screen timeline/evidence and counted explicitly.")
''',
    '''        appendLine("- screen-scoped: strict provenance with selective causal handoff. A screen change starts a new immutable generation; only the trace that directly produced the destination screen may be handed off. Unrelated previous-screen data is discarded, late unrelated callbacks are dropped, and both are counted explicitly.")
''',
)

# 7) Opening a story owns the causal trace so the destination can hand off the exact runtime history.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    "import vn.nghetruyen.app.sources.SourceDiagnosticBrowserActivity\n",
    "import vn.nghetruyen.app.sources.SourceDiagnosticBrowserActivity\nimport vn.nghetruyen.app.sources.DiagnosticCausalTrace\n",
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''            val storyLoadStartedAt = System.currentTimeMillis()
            val storyOriginGeneration = container.sourceDiagnostics.recorder.currentScreenGeneration()
            when (val result = source.story(story.url.ifBlank { story.id })) {
                is AppResult.Success -> {
                    val destinationStoryKey = "story:${result.value.story.id}"
                    container.sourceDiagnostics.onScreenChanged(destinationStoryKey)
''',
    '''            val storyLoadStartedAt = System.currentTimeMillis()
            val storyOriginGeneration = container.sourceDiagnostics.recorder.currentScreenGeneration()
            val storyDiagnosticTraceId = "story-open:${UUID.randomUUID()}"
            val result = withContext(DiagnosticCausalTrace(storyDiagnosticTraceId)) {
                source.story(story.url.ifBlank { story.id })
            }
            when (result) {
                is AppResult.Success -> {
                    val destinationStoryKey = "story:${result.value.story.id}"
                    container.sourceDiagnostics.onScreenChanged(
                        destinationStoryKey,
                        handoffTraceIds = setOf(storyDiagnosticTraceId),
                    )
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''                        sourceId = result.value.story.sourceId,
                        durationMs = (System.currentTimeMillis() - storyLoadStartedAt).coerceAtLeast(0L),
                        attributes = mapOf(
''',
    '''                        sourceId = result.value.story.sourceId,
                        traceId = storyDiagnosticTraceId,
                        durationMs = (System.currentTimeMillis() - storyLoadStartedAt).coerceAtLeast(0L),
                        attributes = mapOf(
''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''                            "originScreenGeneration" to storyOriginGeneration.toString(),
                            "handoff" to "source-action-to-story-screen",
''',
    '''                            "originScreenGeneration" to storyOriginGeneration.toString(),
                            "diagnosticRootTraceId" to storyDiagnosticTraceId,
                            "handoff" to "selective-causal-source-action-to-story-screen",
''',
)

# 8) Regression coverage: selective event/evidence handoff, truthful accounting, and dispatcher-safe causal context.
replace_once(
    "source-diagnostics/src/test/kotlin/vn/nghetruyen/source/diagnostics/ScreenDiagnosticRotationTest.kt",
    '''    @Test
    fun evidenceFromOldTraceIsDroppedAfterScreenRotation() {
''',
    '''    @Test
    fun selectiveNavigationHandoffRetainsOnlyCausalTraceAndRestampsGeneration() {
        val recorder = BoundedDiagnosticRecorder(50, DiagnosticLevel.VERBOSE)
        recorder.emit(operationEvent(1, "story-open", "SOURCE_ACTION_STARTED", DiagnosticOperationState.STARTED))
        recorder.emit(stageEvent(2, "story-open", "VBOOK_ACTION_STARTED"))
        recorder.emit(stageEvent(3, "unrelated", "BACKGROUND_REFRESH"))

        recorder.rotateScreen(
            retainTraceIds = setOf("story-open"),
            handoffAttributes = mapOf("screen" to "story:42", "screenSessionId" to "session-2"),
        )

        val retained = recorder.snapshot()
        assertEquals(2, retained.size)
        assertTrue(retained.all { it.traceId == "story-open" })
        assertTrue(retained.all { it.attributes["diagnosticScreenDisposition"] == "handoff" })
        assertTrue(retained.all { it.attributes["diagnosticScreenGeneration"] == "1" })
        assertTrue(retained.all { it.attributes["diagnosticOriginScreenGeneration"] == "0" })
        assertTrue(retained.all { it.attributes["screen"] == "story:42" })
        assertEquals(1L, recorder.stats().screenRotationEventsDiscarded)
        assertEquals(2L, recorder.stats().screenHandoffEventsRetained)

        recorder.emit(stageEvent(4, "story-open", "LATE_NAVIGATION_CALLBACK"))
        assertEquals("current", recorder.snapshot().last().attributes["diagnosticScreenDisposition"])
    }

    @Test
    fun evidenceSelectiveHandoffKeepsOnlyCausalTraceAndCountsDiscard() {
        val evidence = BoundedDiagnosticEvidenceRecorder(
            maxBytes = 4096,
            maxItems = 10,
            maxItemBytes = 2048,
        ).apply { enabled = true }
        evidence.capture(evidence(1, "story-open", "executor-input.json", "input"))
        evidence.capture(evidence(2, "other", "other.json", "other"))

        evidence.retainTraces(
            traceIds = setOf("story-open"),
            targetGeneration = 7,
            handoffAttributes = mapOf("screen" to "story:42"),
        )

        val retained = evidence.snapshot().single()
        assertEquals("story-open", retained.traceId)
        assertEquals("handoff", retained.attributes["diagnosticScreenDisposition"])
        assertEquals("7", retained.attributes["diagnosticScreenGeneration"])
        assertEquals("story:42", retained.attributes["screen"])
        assertEquals(1L, evidence.stats().screenRotationItemsDiscarded)
        assertEquals(1L, evidence.stats().screenHandoffItemsRetained)
    }

    @Test
    fun evidenceFromOldTraceIsDroppedAfterScreenRotation() {
''',
)
write_new(
    "app/src/test/java/vn/nghetruyen/app/sources/DiagnosticCausalTraceTest.kt",
    '''package vn.nghetruyen.app.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticCausalTraceTest {
    @Test
    fun causalTraceSurvivesDispatcherChange() = runBlocking {
        val observed = withContext(DiagnosticCausalTrace("story-open:test")) {
            withContext(Dispatchers.IO) { currentDiagnosticCausalTraceId() }
        }
        assertEquals("story-open:test", observed)
    }
}
''',
)

print("causal diagnostics handoff patch applied")
