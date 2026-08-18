package vn.nghetruyen.source.diagnostics

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class DiagnosticLevel { OFF, BASIC, VERBOSE }
enum class DiagnosticSeverity { DEBUG, INFO, WARN, ERROR }
enum class DiagnosticCategory { PACKAGE, TRUST, STORE, RUNTIME, NETWORK, BROWSER, PARSER, REPLAY, SECURITY }
enum class DiagnosticOperationState { STARTED, STAGE, COMPLETED, FAILED, CANCELLED, TIMEOUT }

data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val traceId: String,
    val sourceId: String,
    val sourceVersion: String? = null,
    val category: DiagnosticCategory,
    val name: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    val durationMs: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)








object DiagnosticOperationContract {
    const val ID = "operationId"
    const val KIND = "operationKind"
    const val FLOW = "operationFlow"
    const val STATE = "operationState"
    const val STAGE = "stage"
    const val TIMEOUT_MS = "timeoutMs"
    const val DEADLINE_EPOCH_MS = "deadlineEpochMs"

    fun attributes(
        id: String,
        kind: String,
        flow: String,
        state: DiagnosticOperationState,
        stage: String,
        timeoutMs: Long? = null,
        deadlineEpochMs: Long? = null,
    ): Map<String, String> = buildMap {
        put(ID, id.take(500))
        put(KIND, kind.take(160))
        put(FLOW, flow.take(80))
        put(STATE, state.name)
        put(STAGE, stage.take(300))
        timeoutMs?.let { put(TIMEOUT_MS, it.coerceAtLeast(0L).toString()) }
        deadlineEpochMs?.let { put(DEADLINE_EPOCH_MS, it.coerceAtLeast(0L).toString()) }
    }

    fun state(event: DiagnosticEvent): DiagnosticOperationState? = event.attributes[STATE]
        ?.uppercase(Locale.ROOT)
        ?.let { runCatching { DiagnosticOperationState.valueOf(it) }.getOrNull() }

    fun id(event: DiagnosticEvent): String? = event.attributes[ID]?.takeIf(String::isNotBlank)
}






fun DiagnosticEvent.shouldRetainWhenDiagnosticsOff(): Boolean =
    severity == DiagnosticSeverity.ERROR ||
        (severity == DiagnosticSeverity.WARN && category in setOf(
            DiagnosticCategory.PACKAGE,
            DiagnosticCategory.TRUST,
            DiagnosticCategory.STORE,
            DiagnosticCategory.SECURITY,
        ))

fun interface DiagnosticSink {
    fun emit(event: DiagnosticEvent)

    companion object {
        val NONE = DiagnosticSink { }
    }
}

data class DiagnosticRecorderStats(
    val itemCount: Int,
    val evictedEvents: Long,
    val staleScreenEventsDropped: Long = 0,
    val screenRotationEventsDiscarded: Long = 0,
    val screenHandoffEventsRetained: Long = 0,
)









class BoundedDiagnosticRecorder(
    private val maxEvents: Int = 2_000,
    @Volatile var level: DiagnosticLevel = DiagnosticLevel.BASIC,
    private val mirror: DiagnosticSink = DiagnosticSink.NONE,
    private val alwaysMirror: DiagnosticSink = DiagnosticSink.NONE,
) : DiagnosticSink {
    private val lock = ReentrantLock()
    private val events = ArrayDeque<DiagnosticEvent>(maxEvents.coerceAtLeast(1))
    private val operationOrigins = linkedMapOf<String, Long>()
    private val traceOrigins = linkedMapOf<String, Long>()
    private var evictedEvents: Long = 0
    private var screenGeneration: Long = 0
    private var staleScreenEventsDropped: Long = 0
    private var screenRotationEventsDiscarded: Long = 0
    private var screenHandoffEventsRetained: Long = 0

    init {
        require(maxEvents in 1..100_000) { "DIAGNOSTIC_CAPACITY_INVALID" }
    }

    override fun emit(event: DiagnosticEvent) {
        emitScoped(event, explicitOriginGeneration = null)
    }






    fun emitScoped(event: DiagnosticEvent, originGeneration: Long) {
        emitScoped(event, explicitOriginGeneration = originGeneration)
    }

    private fun emitScoped(event: DiagnosticEvent, explicitOriginGeneration: Long?) {
        val redacted = event.copy(attributes = DiagnosticRedactor.redact(event.attributes))
        var scoped = redacted
        var retainInTimeline = false

        lock.withLock {
            val inferredOrigin = explicitOriginGeneration ?: inferOriginGenerationLocked(redacted)
            val currentGeneration = screenGeneration
            scoped = redacted.copy(
                attributes = redacted.attributes + mapOf(
                    "diagnosticScreenGeneration" to inferredOrigin.toString(),
                    "diagnosticScreenDisposition" to if (inferredOrigin == currentGeneration) "current" else "stale",
                ),
            )
            updateOriginBindingsLocked(scoped, inferredOrigin)
            retainInTimeline = inferredOrigin == currentGeneration
        }



        runCatching { alwaysMirror.emit(scoped) }
        if (level == DiagnosticLevel.OFF) return
        if (level == DiagnosticLevel.BASIC && scoped.severity == DiagnosticSeverity.DEBUG) return

        lock.withLock {
            if (retainInTimeline && scoped.attributes["diagnosticScreenGeneration"] == screenGeneration.toString()) {
                while (events.size >= maxEvents) {
                    events.removeFirst()
                    evictedEvents += 1
                }
                events.addLast(scoped)
            } else {
                staleScreenEventsDropped += 1
            }
        }
        runCatching { mirror.emit(scoped) }
    }


    fun restore(restored: List<DiagnosticEvent>) = lock.withLock {
        restored.takeLast(maxEvents).forEach { event ->
            val safe = event.copy(attributes = DiagnosticRedactor.redact(event.attributes))
            while (events.size >= maxEvents) {
                events.removeFirst()
                evictedEvents += 1
            }
            events.addLast(safe)
        }
    }

    fun stats(): DiagnosticRecorderStats = lock.withLock {
        DiagnosticRecorderStats(
            itemCount = events.size,
            evictedEvents = evictedEvents,
            staleScreenEventsDropped = staleScreenEventsDropped,
            screenRotationEventsDiscarded = screenRotationEventsDiscarded,
            screenHandoffEventsRetained = screenHandoffEventsRetained,
        )
    }

    fun snapshot(sourceId: String? = null, traceId: String? = null): List<DiagnosticEvent> = lock.withLock {
        events.filter { event ->
            (sourceId == null || event.sourceId == sourceId) && (traceId == null || event.traceId == traceId)
        }
    }

    fun currentScreenGeneration(): Long = lock.withLock { screenGeneration }

    fun originGenerationForTrace(traceId: String): Long? = lock.withLock {
        traceOrigins[traceId.trim().takeIf(String::isNotBlank)]
    }







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

        val activeOperationIds = linkedSetOf<String>()
        retained.forEach { event ->
            val operationId = DiagnosticOperationContract.id(event) ?: return@forEach
            val state = DiagnosticOperationContract.state(event)
            val upper = event.name.uppercase(Locale.ROOT)
            if (state in TERMINAL_STATES || state == null && isLegacyTerminal(upper)) {
                activeOperationIds.remove(operationId)
            } else {
                activeOperationIds.add(operationId)
            }
        }

        screenRotationEventsDiscarded = (events.size - retained.size).coerceAtLeast(0).toLong()
        screenHandoffEventsRetained = retained.size.toLong()
        screenGeneration = nextGeneration
        events.clear()
        retained.forEach(events::addLast)
        selected.forEach { traceId -> rememberOrigin(traceOrigins, traceId, nextGeneration, overwrite = true) }
        activeOperationIds.forEach { operationId ->
            rememberOrigin(operationOrigins, operationId, nextGeneration, overwrite = true)
        }
        selected
    }


    fun retainActiveOperationTraces(): Set<String> = rotateScreen(emptySet())

    fun clear(sourceId: String? = null) = lock.withLock {
        if (sourceId == null) {
            events.clear()
            operationOrigins.clear()
            traceOrigins.clear()
            evictedEvents = 0
            staleScreenEventsDropped = 0
            screenRotationEventsDiscarded = 0
            screenHandoffEventsRetained = 0
            screenGeneration = 0
        } else {
            val retained = events.filterNot { it.sourceId == sourceId }
            events.clear()
            retained.forEach(events::addLast)
        }
    }

    private fun inferOriginGenerationLocked(event: DiagnosticEvent): Long {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val explicitId = DiagnosticOperationContract.id(event)
        val operationId = explicitId ?: legacyOperationKey(event, upper)
        val isStart = state == DiagnosticOperationState.STARTED || state == null && isLegacyStart(upper)

        if (isStart) {
            if (operationId.isNotBlank()) rememberOrigin(operationOrigins, operationId, screenGeneration)
            if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, screenGeneration, overwrite = true)
            return screenGeneration
        }

        return operationOrigins[operationId]
            ?: traceOrigins[traceId]
            ?: screenGeneration
    }

    private fun updateOriginBindingsLocked(event: DiagnosticEvent, origin: Long) {
        val traceId = event.traceId.trim()
        val upper = event.name.uppercase(Locale.ROOT)
        val state = DiagnosticOperationContract.state(event)
        val operationId = DiagnosticOperationContract.id(event) ?: legacyOperationKey(event, upper)

        if (traceId.isNotBlank()) rememberOrigin(traceOrigins, traceId, origin)
        if (operationId.isNotBlank() && state !in TERMINAL_STATES && !(state == null && isLegacyTerminal(upper))) {
            rememberOrigin(operationOrigins, operationId, origin)
        }

        if (state in TERMINAL_STATES || state == null && isLegacyTerminal(upper)) {
            operationOrigins.remove(operationId)
        }
    }

    private fun legacyOperationKey(event: DiagnosticEvent, upperName: String): String {
        val traceId = event.traceId.trim()
        if (traceId.isBlank()) return ""
        val stem = legacyOperationStem(upperName)
        return "legacy:$traceId:$stem"
    }

    private fun legacyOperationStem(name: String): String {
        if (name.endsWith("_VERIFIED")) return name.removeSuffix("_VERIFIED") + "_VERIFY"
        val suffix = (LEGACY_START_SUFFIXES + LEGACY_TERMINAL_SUFFIXES).firstOrNull(name::endsWith)
        return suffix?.let(name::removeSuffix) ?: name
    }

    private fun isLegacyStart(name: String): Boolean = LEGACY_START_SUFFIXES.any(name::endsWith)
    private fun isLegacyTerminal(name: String): Boolean = LEGACY_TERMINAL_SUFFIXES.any(name::endsWith)

    private fun rememberOrigin(
        map: LinkedHashMap<String, Long>,
        key: String,
        generation: Long,
        overwrite: Boolean = false,
    ) {
        if (key.isBlank()) return
        if (overwrite || key !in map) map[key] = generation
        while (map.size > MAX_ORIGIN_BINDINGS) map.remove(map.entries.first().key)
    }

    companion object {
        private const val MAX_ORIGIN_BINDINGS = 8_192
        private val LEGACY_START_SUFFIXES = listOf("_STARTED", "_START")
        private val LEGACY_TERMINAL_SUFFIXES = listOf(
            "_COMPLETED", "_FAILED", "_ERROR", "_DONE", "_CANCELLED", "_STOPPED", "_TIMEOUT",
            "_VERIFIED", "_SUCCEEDED", "_SUCCESS", "_FINISHED",
        )
        private val TERMINAL_STATES = setOf(
            DiagnosticOperationState.COMPLETED,
            DiagnosticOperationState.FAILED,
            DiagnosticOperationState.CANCELLED,
            DiagnosticOperationState.TIMEOUT,
        )
    }
}

data class DiagnosticEvidence(
    val timestampEpochMs: Long,
    val traceId: String,
    val sourceId: String,
    val category: DiagnosticCategory,
    val name: String,
    val contentType: String,
    val data: ByteArray,
    val attributes: Map<String, String> = emptyMap(),
)

interface DiagnosticEvidenceSink {
    val enabled: Boolean
    fun capture(evidence: DiagnosticEvidence)

    companion object {
        val NONE: DiagnosticEvidenceSink = object : DiagnosticEvidenceSink {
            override val enabled: Boolean = false
            override fun capture(evidence: DiagnosticEvidence) = Unit
        }
    }
}

data class DiagnosticEvidenceStats(
    val itemCount: Int,
    val retainedBytes: Long,
    val evictedItems: Long,
    val truncatedItems: Long,
    val staleScreenItemsDropped: Long = 0,
    val screenRotationItemsDiscarded: Long = 0,
    val screenHandoffItemsRetained: Long = 0,
)

class BoundedDiagnosticEvidenceRecorder(
    private val maxBytes: Long = 64L * 1024L * 1024L,
    private val maxItems: Int = 2_048,
    private val maxItemBytes: Int = 8 * 1024 * 1024,
    private val mirror: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : DiagnosticEvidenceSink {
    private val lock = ReentrantLock()
    private val items = ArrayDeque<DiagnosticEvidence>()
    private var retainedBytes: Long = 0
    private var evictedItems: Long = 0
    private var truncatedItems: Long = 0
    private var staleScreenItemsDropped: Long = 0
    private var screenRotationItemsDiscarded: Long = 0
    private var screenHandoffItemsRetained: Long = 0
    @Volatile override var enabled: Boolean = false

    init {
        require(maxBytes in 1L..256L * 1024L * 1024L) { "DIAGNOSTIC_EVIDENCE_CAPACITY_INVALID" }
        require(maxItems in 1..10_000) { "DIAGNOSTIC_EVIDENCE_ITEMS_INVALID" }
        require(maxItemBytes in 1..32 * 1024 * 1024) { "DIAGNOSTIC_EVIDENCE_ITEM_LIMIT_INVALID" }
    }

    override fun capture(evidence: DiagnosticEvidence) {
        if (!enabled) return
        val truncated = evidence.data.size > maxItemBytes
        val payload = if (truncated) evidence.data.copyOf(maxItemBytes) else evidence.data.copyOf()
        val stored = evidence.copy(
            name = evidence.name.take(512),
            data = payload,
            attributes = if (truncated) evidence.attributes + ("truncated" to "true") else evidence.attributes,
        )
        lock.withLock {
            if (truncated) truncatedItems += 1
            while (items.isNotEmpty() && (items.size >= maxItems || retainedBytes + payload.size > maxBytes)) {
                val removed = items.removeFirst()
                retainedBytes -= removed.data.size.toLong()
                evictedItems += 1
            }
            if (payload.size.toLong() > maxBytes) {
                evictedItems += 1
                return
            }
            items.addLast(stored)
            retainedBytes += payload.size.toLong()
        }
        runCatching { mirror.capture(stored) }
    }

    fun noteStaleScreenDrop() = lock.withLock {
        staleScreenItemsDropped += 1
    }

    fun snapshot(): List<DiagnosticEvidence> = lock.withLock { items.toList() }






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

    fun clear() = lock.withLock {
        items.clear()
        retainedBytes = 0
        evictedItems = 0
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
}





class ScreenScopedDiagnosticEvidenceSink(
    private val scope: BoundedDiagnosticRecorder,
    private val delegate: BoundedDiagnosticEvidenceRecorder,
) : DiagnosticEvidenceSink {
    override val enabled: Boolean get() = delegate.enabled

    override fun capture(evidence: DiagnosticEvidence) {
        if (!delegate.enabled) return
        val current = scope.currentScreenGeneration()
        val origin = scope.originGenerationForTrace(evidence.traceId) ?: current
        if (origin != current) {
            delegate.noteStaleScreenDrop()
            return
        }
        delegate.capture(
            evidence.copy(
                attributes = evidence.attributes + ("diagnosticScreenGeneration" to origin.toString()),
            ),
        )
    }
}

object DiagnosticRedactor {
    private val sensitiveName = Regex(
        "(?i)(authorization|proxy-authorization|cookie|set-cookie|api[-_]?key|token|secret|password|passwd|session|credential)",
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val keyValue = Regex("(?i)(api[_-]?key|token|secret|password)=([^&\\s]+)")

    fun redact(attributes: Map<String, String>): Map<String, String> = attributes.mapValues { (key, value) ->
        when {
            sensitiveName.containsMatchIn(key) -> "<redacted:${fingerprint(value)}>"
            else -> redactText(value)
        }
    }

    private val cookieLike = Regex("(?i)\\b(cookie|set-cookie|authorization|session|credential)=([^&\\s;]+)")
    private val htmlSensitiveAttribute = Regex("(?is)(\\s(?:value|authorization|data-token|data-secret|data-password|data-cookie|data-session)\\s*=\\s*)([\\\"'])(.*?)\\2")

    fun redactText(value: String): String = redactLongText(value, 4_096)

    fun redactLongText(value: String, maxChars: Int = 8 * 1024 * 1024): String {
        require(maxChars in 1..16 * 1024 * 1024) { "DIAGNOSTIC_REDACTION_LIMIT_INVALID" }
        val bearerSafe = bearer.replace(value, "Bearer <redacted>")
        val keySafe = keyValue.replace(bearerSafe) { match -> "${match.groupValues[1]}=<redacted>" }
        return cookieLike.replace(keySafe) { match -> "${match.groupValues[1]}=<redacted>" }.take(maxChars)
    }


    fun redactHtmlPreservingStructure(raw: String, maxChars: Int = 8 * 1024 * 1024): String =
        htmlSensitiveAttribute.replace(redactLongText(raw, maxChars)) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>${match.groupValues[2]}"
        }.take(maxChars)

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}






object DiagnosticThrowableFormatter {
    fun attributes(
        error: Throwable,
        maxCauses: Int = 8,
        maxFrames: Int = 96,
        maxChars: Int = 16_000,
    ): Map<String, String> {
        require(maxCauses in 1..16) { "DIAGNOSTIC_CAUSE_LIMIT_INVALID" }
        require(maxFrames in 1..256) { "DIAGNOSTIC_FRAME_LIMIT_INVALID" }
        require(maxChars in 512..64_000) { "DIAGNOSTIC_STACK_SIZE_INVALID" }

        val causes = generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .take(maxCauses)
            .toList()
        var retainedFrames = 0
        var truncated = false
        val framesPerCause = (maxFrames / causes.size.coerceAtLeast(1)).coerceAtLeast(1)
        val stack = buildString {
            causes.forEachIndexed { index, cause ->
                if (index > 0) append("Caused by: ")
                append(cause.javaClass.name)
                cause.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                appendLine()
                val allowance = minOf(framesPerCause, maxFrames - retainedFrames)
                cause.stackTrace.take(allowance).forEach { frame ->
                    if (length >= maxChars) {
                        truncated = true
                        return@forEach
                    }
                    append("\tat ").append(frame).appendLine()
                    retainedFrames += 1
                }
                if (cause.stackTrace.size > allowance) truncated = true
            }
        }.take(maxChars)
        val chain = causes.joinToString(" -> ") { cause ->
            "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
        }.take(maxChars / 2)
        return DiagnosticRedactor.redact(
            mapOf(
                "errorType" to error.javaClass.name,
                "causeChain" to chain,
                "stackTrace" to stack,
                "stackFrameCount" to retainedFrames.toString(),
                "stackTraceTruncated" to (truncated || stack.length >= maxChars).toString(),
            ),
        )
    }
}

data class DiagnosticArtifactMetadata(
    val displayName: String = "",
    val mimeType: String = "",
    val declaredSizeBytes: Long? = null,
)


object DiagnosticArtifactInspector {
    fun inspect(bytes: ByteArray, metadata: DiagnosticArtifactMetadata): Map<String, String> = buildMap {
        put("fileName", safeDisplayName(metadata.displayName))
        put("mimeType", metadata.mimeType.trim().replace(Regex("[\\p{Cntrl}\\s]+"), " ").take(160))
        metadata.declaredSizeBytes?.takeIf { it >= 0L }?.let { put("declaredBytes", it.toString()) }
        put("actualBytes", bytes.size.toString())
        put("contentSha256", sha256(bytes))
        put("magicHex", bytes.take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) })
        put("detectedContainer", detectedContainer(bytes, metadata.displayName))
        zipEntryCount(bytes)?.let {
            put("zipEntryCount", it.toString())
            put("zipEntryCountMethod", "central-directory-signature")
        }
    }

    fun safeDisplayName(raw: String): String = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}]+"), "_")
        .trim()
        .take(180)
        .ifBlank { "unknown-file" }

    private fun detectedContainer(bytes: ByteArray, displayName: String): String {
        if (bytes.isEmpty()) return "EMPTY"
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return "ZIP"
        val sample = bytes.take(4_096).toByteArray().toString(Charsets.UTF_8).trimStart()
        return when {
            displayName.endsWith(".lua", true) || sample.startsWith("return ") || sample.startsWith("local ") -> "LUA_TEXT"
            sample.startsWith("{") || sample.startsWith("[") -> "JSON_TEXT"
            sample.isNotBlank() && sample.count { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' } >= sample.length * 9 / 10 -> "TEXT"
            else -> "BINARY"
        }
    }

    private fun zipEntryCount(bytes: ByteArray): Int? {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return null
        var count = 0
        var index = 0
        while (index <= bytes.size - 4) {
            if (bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x01.toByte() && bytes[index + 3] == 0x02.toByte()
            ) {
                count += 1
                if (count > MAX_ZIP_ENTRIES_TO_COUNT) return -1
            }
            index += 1
        }
        return count
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private const val MAX_ZIP_ENTRIES_TO_COUNT = 4_096
}

object DiagnosticJsonExporter {
    fun eventLine(event: DiagnosticEvent): String = JsonCodec.stringify(eventJson(event))

    fun export(events: List<DiagnosticEvent>): ByteArray {
        val root = JsonValue.Obj(linkedMapOf(
            "formatVersion" to JsonValue.Num(1.0, "1"),
            "generatedAt" to JsonValue.Str(Instant.now().toString()),
            "events" to JsonValue.Arr(events.map(::eventJson)),
        ))
        return JsonCodec.stringify(root).toByteArray(StandardCharsets.UTF_8)
    }

    private fun eventJson(event: DiagnosticEvent): JsonValue = JsonValue.Obj(linkedMapOf(
        "timestampEpochMs" to JsonValue.Num(event.timestampEpochMs.toDouble(), event.timestampEpochMs.toString()),
        "traceId" to JsonValue.Str(event.traceId),
        "sourceId" to JsonValue.Str(event.sourceId),
        "sourceVersion" to (event.sourceVersion?.let(JsonValue::Str) ?: JsonValue.Null),
        "category" to JsonValue.Str(event.category.name),
        "name" to JsonValue.Str(event.name),
        "severity" to JsonValue.Str(event.severity.name),
        "durationMs" to (event.durationMs?.let { JsonValue.Num(it.toDouble(), it.toString()) } ?: JsonValue.Null),
        "attributes" to JsonValue.Obj(LinkedHashMap(event.attributes.mapValues { JsonValue.Str(it.value) })),
    ))
}

data class SourceTraceSummary(
    val traceId: String,
    val sourceId: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val durationMs: Long,
    val eventCount: Int,
    val errorCount: Int,
    val categories: Set<DiagnosticCategory>,
    val finalEvent: String,
)

object SourceTraceExplorer {
    fun summarize(events: List<DiagnosticEvent>): List<SourceTraceSummary> = events
        .groupBy(DiagnosticEvent::traceId)
        .mapNotNull { (traceId, traceEvents) ->
            if (traceId.isBlank() || traceEvents.isEmpty()) return@mapNotNull null
            val sorted = traceEvents.sortedBy(DiagnosticEvent::timestampEpochMs)
            val first = sorted.first()
            val last = sorted.last()
            SourceTraceSummary(
                traceId = traceId,
                sourceId = first.sourceId,
                startedAtEpochMs = first.timestampEpochMs,
                completedAtEpochMs = last.timestampEpochMs,
                durationMs = (last.timestampEpochMs - first.timestampEpochMs).coerceAtLeast(0L),
                eventCount = sorted.size,
                errorCount = sorted.count { it.severity == DiagnosticSeverity.ERROR },
                categories = sorted.map(DiagnosticEvent::category).toSet(),
                finalEvent = last.name,
            )
        }
        .sortedByDescending(SourceTraceSummary::startedAtEpochMs)

    fun eventsForTrace(events: List<DiagnosticEvent>, traceId: String): List<DiagnosticEvent> =
        events.filter { it.traceId == traceId }.sortedBy(DiagnosticEvent::timestampEpochMs)
}

data class SourceReplayDifference(
    val path: String,
    val kind: String,
    val before: String?,
    val after: String?,
)

object SourceReplayDiff {
    fun compare(before: JsonValue, after: JsonValue, limit: Int = 500): List<SourceReplayDifference> {
        require(limit in 1..10_000) { "SOURCE_REPLAY_DIFF_LIMIT_INVALID" }
        val output = mutableListOf<SourceReplayDifference>()
        walk("$", before, after, output, limit)
        return output
    }

    private fun walk(path: String, before: JsonValue?, after: JsonValue?, output: MutableList<SourceReplayDifference>, limit: Int) {
        if (output.size >= limit || before == after) return
        when {
            before == null -> output += SourceReplayDifference(path, "ADDED", null, preview(after))
            after == null -> output += SourceReplayDifference(path, "REMOVED", preview(before), null)
            before is JsonValue.Obj && after is JsonValue.Obj -> {
                (before.values.keys + after.values.keys).toSortedSet().forEach { key ->
                    walk("$path.${escapePath(key)}", before[key], after[key], output, limit)
                }
            }
            before is JsonValue.Arr && after is JsonValue.Arr -> {
                val max = maxOf(before.values.size, after.values.size)
                for (index in 0 until max) walk("$path[$index]", before.values.getOrNull(index), after.values.getOrNull(index), output, limit)
            }
            before::class != after::class -> output += SourceReplayDifference(path, "TYPE_CHANGED", preview(before), preview(after))
            else -> output += SourceReplayDifference(path, "CHANGED", preview(before), preview(after))
        }
    }

    private fun preview(value: JsonValue?): String? = value?.let(JsonCodec::stringify)?.let(DiagnosticRedactor::redactText)?.take(512)
    private fun escapePath(value: String) = value.replace(".", "\\.")
}

object SourceSnapshotSanitizer {
    private val script = Regex("(?is)<script\\b[^>]*>.*?</script>")
    private val style = Regex("(?is)<style\\b[^>]*>.*?</style>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val sensitiveAttributes = Regex("(?i)\\s+(value|authorization|data-token|data-secret|data-password)\\s*=\\s*(['\"]).*?\\2")
    private val passwordInput = Regex("(?is)(<input\\b[^>]*type\\s*=\\s*(['\"])password\\2[^>]*)(>)")

    fun sanitizeHtml(raw: String, maxChars: Int = 2 * 1024 * 1024): String {
        require(maxChars in 1024..8 * 1024 * 1024) { "SOURCE_SNAPSHOT_LIMIT_INVALID" }
        return passwordInput.replace(
            sensitiveAttributes.replace(
                comments.replace(style.replace(script.replace(raw, ""), ""), ""),
            ) { match -> " ${match.groupValues[1]}=\"<redacted>\"" },
        ) { match -> match.groupValues[1].replace(Regex("(?i)\\s+value\\s*=\\s*(['\"]).*?\\1"), " value=\"<redacted>\"") + match.groupValues[3] }
            .let(DiagnosticRedactor::redactText)
            .take(maxChars)
    }
}
