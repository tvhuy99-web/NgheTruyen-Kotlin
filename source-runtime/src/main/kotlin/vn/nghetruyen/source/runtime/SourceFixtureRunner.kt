package vn.nghetruyen.source.runtime

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceFixtureSpec
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.util.UUID

data class SourceFixtureResult(
    val name: String,
    val action: SourceActionName,
    val passed: Boolean,
    val detail: String,
    val traceId: String,
)

data class SourceFixtureReport(
    val results: List<SourceFixtureResult>,
) {
    val passed: Int get() = results.count(SourceFixtureResult::passed)
    val failed: Int get() = results.size - passed
    val allPassed: Boolean get() = results.isNotEmpty() && failed == 0
}

fun interface SourceFixtureExecutor {
    fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
        replayNetwork: SourceNetworkBroker?,
    ): SourcePlatformResult<vn.nghetruyen.source.api.SourceActionResponse>
}

class SourceFixtureRunner(
    private val executor: SourceFixtureExecutor,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    constructor(
        runtime: DeclarativeSourceRuntime,
        diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    ) : this(
        SourceFixtureExecutor { manifest, resources, request, replayNetwork ->
            runtime.execute(manifest, resources, request, replayNetwork)
        },
        diagnostics,
    )
    fun run(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
    ): SourceFixtureReport = SourceFixtureReport(
        manifest.fixtures.map { fixture -> runOne(manifest, resources, fixture) },
    )

    private fun runOne(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        fixture: SourceFixtureSpec,
    ): SourceFixtureResult {
        val traceId = UUID.randomUUID().toString()
        val request = SourceActionRequest(
            sourceId = manifest.id,
            action = fixture.action,
            input = fixtureInput(resources, fixture),
            traceId = traceId,
        )
        val expectedBytes = resources.read(fixture.expected, 4 * 1024 * 1024)
            ?: return failed(manifest, fixture, traceId, "EXPECTED_RESOURCE_MISSING:${fixture.expected}")
        val expected = runCatching { JsonCodec.parse(expectedBytes.toString(Charsets.UTF_8)) }
            .getOrElse { return failed(manifest, fixture, traceId, "EXPECTED_JSON_INVALID:${it.message}") }
        val replayBroker = fixture.fixture?.let { SnapshotReplayNetworkBroker.fromResource(resources, it) }
        val execution = executor.execute(manifest, resources, request, replayBroker)
        val actual = when (execution) {
            is SourcePlatformResult.Success -> execution.value.value
            is SourcePlatformResult.Failure -> return failed(
                manifest,
                fixture,
                traceId,
                "${execution.error.code}:${execution.error.message}",
            )
        }
        val mismatch = subsetMismatch(expected, actual, "$")
        return if (mismatch == null) {
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = manifest.id,
                    sourceVersion = manifest.version.toString(),
                    category = DiagnosticCategory.REPLAY,
                    name = "FIXTURE_PASSED",
                    attributes = mapOf("fixture" to fixture.name, "action" to fixture.action.name),
                ),
            )
            SourceFixtureResult(fixture.name, fixture.action, true, "PASS", traceId)
        } else failed(manifest, fixture, traceId, mismatch)
    }

    private fun failed(
        manifest: SourceManifest,
        fixture: SourceFixtureSpec,
        traceId: String,
        detail: String,
    ): SourceFixtureResult {
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId,
                sourceId = manifest.id,
                sourceVersion = manifest.version.toString(),
                category = DiagnosticCategory.REPLAY,
                name = "FIXTURE_FAILED",
                severity = DiagnosticSeverity.ERROR,
                attributes = mapOf("fixture" to fixture.name, "action" to fixture.action.name, "detail" to detail),
            ),
        )
        return SourceFixtureResult(fixture.name, fixture.action, false, detail, traceId)
    }

    private fun fixtureInput(resources: SourceResourceProvider, fixture: SourceFixtureSpec): JsonValue.Obj {
        val resourceInput = runCatching {
            SourceManifest.requireSafeRelativePath(fixture.input)
            resources.read(fixture.input, 256 * 1024)?.toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (resourceInput != null) {
            val parsed = runCatching { JsonCodec.parse(resourceInput, maxDepth = 32, maxNodes = 20_000) }.getOrNull()
            if (parsed is JsonValue.Obj) return parsed
        }

        return JsonValue.Obj(
            when (fixture.action) {
                SourceActionName.SEARCH, SourceActionName.SUGGESTIONS -> linkedMapOf(
                    "query" to JsonValue.Str(fixture.input),
                    "page" to JsonValue.Num(1.0, "1"),
                )
                SourceActionName.GENRE, SourceActionName.HOME -> linkedMapOf(
                    "category" to JsonValue.Str(fixture.input),
                    "input" to JsonValue.Str(fixture.input),
                    "page" to JsonValue.Num(1.0, "1"),
                )
                SourceActionName.TOC_PAGES -> linkedMapOf(
                    "url" to JsonValue.Str(fixture.input),
                    "pageToken" to JsonValue.Str(""),
                    "page" to JsonValue.Num(1.0, "1"),
                )
                SourceActionName.LOGIN -> linkedMapOf("input" to JsonValue.Str(fixture.input))
                else -> linkedMapOf("url" to JsonValue.Str(fixture.input))
            },
        )
    }

    private fun subsetMismatch(expected: JsonValue, actual: JsonValue, path: String): String? {
        return when (expected) {
            is JsonValue.Obj -> {
                val actualObj = actual as? JsonValue.Obj ?: return "TYPE_MISMATCH:$path:object"
                expected.values.entries.firstNotNullOfOrNull { (key, value) ->
                    val actualValue = actualObj[key]
                        ?: return@firstNotNullOfOrNull "MISSING_FIELD:$path.$key"
                    subsetMismatch(value, actualValue, "$path.$key")
                }
            }
            is JsonValue.Arr -> {
                val actualArray = actual as? JsonValue.Arr ?: return "TYPE_MISMATCH:$path:array"
                if (actualArray.values.size < expected.values.size) return "ARRAY_TOO_SHORT:$path"
                expected.values.indices.firstNotNullOfOrNull { index ->
                    subsetMismatch(expected.values[index], actualArray.values[index], "$path[$index]")
                }
            }
            is JsonValue.Str -> if ((actual as? JsonValue.Str)?.value == expected.value) null else "VALUE_MISMATCH:$path"
            is JsonValue.Num -> if ((actual as? JsonValue.Num)?.raw == expected.raw) null else "VALUE_MISMATCH:$path"
            is JsonValue.Bool -> if ((actual as? JsonValue.Bool)?.value == expected.value) null else "VALUE_MISMATCH:$path"
            JsonValue.Null -> if (actual == JsonValue.Null) null else "VALUE_MISMATCH:$path"
        }
    }
}
