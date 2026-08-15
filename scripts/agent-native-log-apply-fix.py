from pathlib import Path

runtime_path = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
text = runtime_path.read_text(encoding="utf-8")
start = text.index("    private fun diagnosticLogObject")
end = text.index("\n    private fun storageObject", start)
section = text[start:end]
needle = '''                true
            }
            ScriptableObject.putProperty(obj, "d", logger(DiagnosticSeverity.DEBUG))'''
replacement = '''                true
            }.apply {
                // NativeV2 uses Log.log.apply(...). A host BaseFunction without the normal
                // Function prototype has no apply/call helpers in Rhino, and the adapter's
                // compatibility logger intentionally swallows that failure. Keep this logger a
                // real Rhino function so Native Lua micro-checkpoints reach diagnostics.
                parentScope = scope
                prototype = ScriptableObject.getFunctionPrototype(scope)
            }
            ScriptableObject.putProperty(obj, "d", logger(DiagnosticSeverity.DEBUG))'''
if section.count(needle) != 1:
    raise SystemExit(f"expected exactly one diagnostic logger tail, found {section.count(needle)}")
section = section.replace(needle, replacement, 1)
text = text[:start] + section + text[end:]
runtime_path.write_text(text, encoding="utf-8")

test_path = Path("source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookNativeLogApplyIntegrationTest.kt")
test_path.write_text(r'''package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider

class VBookNativeLogApplyIntegrationTest {
    @Test
    fun nativeAdapterApplyStyleLogEmitsTransformMicroCheckpoints() {
        val events = mutableListOf<DiagnosticEvent>()
        val runtime = VBookJsRuntime(diagnostics = DiagnosticSink { events += it })
        val sourceId = "test.vbook.native-log"
        val entry = "src/native-log.js"
        val manifest = SourceManifest(
            schemaVersion = 2,
            id = sourceId,
            name = "Native log apply fixture",
            version = SemanticVersion(1, 0, 0),
            apiVersion = 2,
            contentType = SourceContentType.NOVEL,
            runtime = SourceRuntimePolicy(
                mode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
                instructionBudget = 200_000,
                memoryBudgetBytes = 16 * 1024 * 1024,
            ),
            origins = setOf("https://x.example"),
            capabilities = SourceCapabilities(),
            actions = mapOf(
                SourceActionName.DETAIL to SourceActionSpec(entry),
                SourceActionName.TOC to SourceActionSpec(entry),
                SourceActionName.CHAPTER to SourceActionSpec(entry),
            ),
        )
        val script = """
            function execute(url, page) {
              Log.log.apply(null, ["NATIVE_V2", "TRANSFORM_START", "step.1", "ops=2", "stringBytes=123"]);
              Log.log.apply(null, ["NATIVE_V2", "TRANSFORM_OP", "step.1", "index=1", "op=split", "arrayLength=2"]);
              Log.log.apply(null, ["NATIVE_V2", "TRANSFORM_DONE", "step.1", "arrayLength=2"]);
              return Response.success([{name:"Chương 1", url:"https://x.example/chapter-1"}], "NO_NEXT");
            }
        """.trimIndent()
        val resources = object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? =
                if (path == entry) script.toByteArray().takeIf { it.size <= maxBytes } else null
        }
        val traceId = "native-log-apply-e2e"

        val result = runtime.execute(
            manifest,
            resources,
            SourceActionRequest(
                sourceId = sourceId,
                action = SourceActionName.TOC,
                input = JsonValue.Obj(linkedMapOf("url" to JsonValue.Str("https://x.example/story"))),
                traceId = traceId,
            ),
        )

        // This regression isolates the host logging contract. The synthetic action result is not
        // the subject of the test: if Log.log.apply reaches the host, these events must exist even
        // if a later result-normalization rule rejects this intentionally tiny fixture.
        val nativeEvents = events.filter { it.name.startsWith("NATIVE_V2_TRANSFORM_") }
        assertEquals(
            "runtime=$result allEvents=${events.map(DiagnosticEvent::name)}",
            listOf("NATIVE_V2_TRANSFORM_START", "NATIVE_V2_TRANSFORM_OP", "NATIVE_V2_TRANSFORM_DONE"),
            nativeEvents.map(DiagnosticEvent::name),
        )
        assertTrue(nativeEvents.all { it.traceId == traceId })
        assertTrue(nativeEvents.all { it.attributes["flow"] == "native" })
        assertTrue(nativeEvents.all { it.attributes["nativeRuntime"] == "NATIVE_V2" })
        assertEquals("2", nativeEvents.first().attributes["ops"])
        assertEquals("split", nativeEvents[1].attributes["op"])
        assertEquals("2", nativeEvents.last().attributes["arrayLength"])
    }
}
''', encoding="utf-8")

print("patched diagnostic logger prototype and wrote NativeV2 apply integration regression")
