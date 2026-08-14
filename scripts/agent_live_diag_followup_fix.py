from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new))


# 1) Stamp current host-generated NativeV2 runtime so installed packages can be upgraded in-memory.
adapter = "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua"
replace_exact(
    adapter,
    'var RUNTIME_VERSION=__NATIVE_V2_RUNTIME_VERSION__;\nvar MAX_HOOK_INPUT_BYTES=__NATIVE_V2_MAX_HOOK_INPUT_BYTES__;',
    'var RUNTIME_VERSION=__NATIVE_V2_RUNTIME_VERSION__;\n// NGHETRUYEN_NATIVE_V2_HOST_RUNTIME:2026-08-15.1\nvar MAX_HOOK_INPUT_BYTES=__NATIVE_V2_MAX_HOOK_INPUT_BYTES__;',
)

# 2) Add a host-owned runtime overlay. It never mutates the signed/stored package; it only rebuilds
# generated NativeV2 JS from the original preserved Lua bytes when the embedded host runtime is old.
overlay_path = Path("source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaRuntimeOverlay.kt")
if overlay_path.exists():
    raise SystemExit(f"unexpected existing file: {overlay_path}")
overlay_path.write_text(r'''package vn.nghetruyen.source.lua

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.packagekit.VerifiedSourcePack

/**
 * Refreshes only NgheTruyen-generated NativeV2 JavaScript for an already-installed Native Lua pack.
 *
 * The signed/source-owned bytes stay untouched on disk. Older app builds generated src/native_v2_*.js
 * at import time, so updating the APK alone previously left installed sources pinned to an old host
 * adapter forever. This overlay reconstructs the original Lua archive from preserved native/* bytes
 * and regenerates only host-owned src/native_v2_* resources in memory.
 */
object NativeLuaRuntimeOverlay {
    const val HOST_RUNTIME_MARKER = "NGHETRUYEN_NATIVE_V2_HOST_RUNTIME:2026-08-15.1"
    private const val CORE_PATH = "src/native_v2_core.js"
    private const val SOURCE_PATH = "native/source.lua"
    private const val MODULE_INDEX_PATH = "data/native-module-index.json"
    private const val ARCHIVE_PREFIX = "native/archive/"
    private const val GENERATED_PREFIX = "src/native_v2_"

    data class Result(
        val entries: Map<String, ByteArray>,
        val refreshed: Boolean,
        val generatedEntryCount: Int,
    )

    fun refresh(pack: VerifiedSourcePack): Result {
        if (pack.manifest.runtime.mode != SourceRuntimeMode.NATIVE_LUA_COMPAT) {
            return Result(pack.entries, refreshed = false, generatedEntryCount = 0)
        }
        val currentCore = pack.entries[CORE_PATH]?.toString(Charsets.UTF_8).orEmpty()
        if (HOST_RUNTIME_MARKER in currentCore) {
            return Result(pack.entries, refreshed = false, generatedEntryCount = 0)
        }

        val sourceBytes = requireNotNull(pack.entries[SOURCE_PATH]) {
            "NATIVE_LUA_RUNTIME_OVERLAY_SOURCE_MISSING"
        }
        val entryPath = readEntryPath(pack.entries[MODULE_INDEX_PATH]) ?: "source.lua"
        val archiveFiles = linkedMapOf<String, ByteArray>(entryPath to sourceBytes)
        pack.entries.forEach { (path, bytes) ->
            if (path.startsWith(ARCHIVE_PREFIX)) {
                archiveFiles[path.removePrefix(ARCHIVE_PREFIX)] = bytes
            }
        }
        val rebuilt = NativeLuaSourceImporter.import(
            sourceBytes = sourceBytes,
            archiveFiles = archiveFiles,
            entryPath = entryPath,
        )
        val generated = rebuilt.entries.filterKeys { it.startsWith(GENERATED_PREFIX) }
        require(generated.isNotEmpty()) { "NATIVE_LUA_RUNTIME_OVERLAY_EMPTY" }
        require(HOST_RUNTIME_MARKER in generated.getValue(CORE_PATH).toString(Charsets.UTF_8)) {
            "NATIVE_LUA_RUNTIME_OVERLAY_REVISION_MISMATCH"
        }

        return Result(
            entries = LinkedHashMap(pack.entries).apply { putAll(generated) },
            refreshed = true,
            generatedEntryCount = generated.size,
        )
    }

    private fun readEntryPath(raw: ByteArray?): String? = raw?.let { bytes ->
        runCatching {
            val root = JsonCodec.parse(bytes.toString(Charsets.UTF_8), maxDepth = 16, maxNodes = 2_000) as? JsonValue.Obj
            root?.string("entryPath")?.trim()?.takeIf(String::isNotBlank)
        }.getOrNull()
    }
}
''')

# 3) Apply that overlay to active Native Lua sources, cached per signed package hash.
manager = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt"
replace_exact(
    manager,
    'import vn.nghetruyen.source.lua.LuaNativeHookBroker\nimport vn.nghetruyen.source.lua.NativeLuaArchiveImporter',
    'import vn.nghetruyen.source.lua.LuaNativeHookBroker\nimport vn.nghetruyen.source.lua.NativeLuaArchiveImporter\nimport vn.nghetruyen.source.lua.NativeLuaRuntimeOverlay',
)
replace_exact(
    manager,
    '    private val nativeHookBroker = LuaNativeHookBroker()\n    private val graphicsBroker = AndroidSourceGraphicsBroker()',
    '    private val nativeHookBroker = LuaNativeHookBroker()\n    private val nativeRuntimeOverlayCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, ByteArray>>()\n    private val graphicsBroker = AndroidSourceGraphicsBroker()',
)
replace_exact(
    manager,
    '''    fun activeStorySources(): List<StorySource> = store.list()
        .filter { it.enabled && it.active != null }
        .mapNotNull { installed -> store.readActivePack(installed.sourceId) }
        .map { pack -> SourcePackStorySource(pack, executor, genericCommentLoader) }

    fun installedPacks(): List<SourcePackUiInfo> = store.list().map { installed ->''',
    '''    fun activeStorySources(): List<StorySource> = store.list()
        .filter { it.enabled && it.active != null }
        .mapNotNull { installed -> store.readActivePack(installed.sourceId) }
        .map(::runtimePack)
        .map { pack -> SourcePackStorySource(pack, executor, genericCommentLoader) }

    private fun runtimePack(pack: VerifiedSourcePack): VerifiedSourcePack {
        if (pack.manifest.runtime.mode != SourceRuntimeMode.NATIVE_LUA_COMPAT) return pack
        val runtimeEntries = nativeRuntimeOverlayCache[pack.packageSha256] ?: try {
            NativeLuaRuntimeOverlay.refresh(pack).entries.also { entries ->
                nativeRuntimeOverlayCache[pack.packageSha256] = entries
            }
        } catch (error: Exception) {
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = "native-runtime-overlay:${UUID.randomUUID()}",
                    sourceId = pack.manifest.id,
                    sourceVersion = pack.manifest.version.toString(),
                    category = DiagnosticCategory.RUNTIME,
                    name = "NATIVE_LUA_RUNTIME_OVERLAY_FAILED",
                    severity = DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        "errorType" to error.javaClass.name.take(240),
                        "error" to error.message.orEmpty().take(1_000),
                    ),
                ),
            )
            return pack
        }
        return if (runtimeEntries === pack.entries) pack else pack.copy(entries = runtimeEntries)
    }

    fun installedPacks(): List<SourcePackUiInfo> = store.list().map { installed ->''',
)

# 4) Strict-screen activity tracker: retain old operations internally for late completion but never
# expose them in the current screen's UI/export. Also close START -> *_OK micro-stage pairs.
diag = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
replace_exact(
    diag,
    '''    val lastEventAtEpochMs: Long,
    val startEvent: String,
    val lastEvent: String,
)''',
    '''    val lastEventAtEpochMs: Long,
    val startEvent: String,
    val lastEvent: String,
    val screenGeneration: Long?,
)''',
)
replace_exact(
    diag,
    '    fun activitySnapshot(): List<DiagnosticActiveOperation> = activityTracker.snapshot()',
    '''    fun activitySnapshot(): List<DiagnosticActiveOperation> =
        if (mode == MODE_SCREEN) activityTracker.snapshot(recorder.currentScreenGeneration())
        else activityTracker.snapshot()''',
)
replace_exact(
    diag,
    '        put("activeOperationCount", activityTracker.snapshot().size)',
    '        put("activeOperationCount", activitySnapshot().size)',
)
replace_exact(
    diag,
    '''    private fun activeOperationsJson(): JSONArray = JSONArray().apply {
        activityTracker.snapshot().forEach { operation ->''',
    '''    private fun activeOperationsJson(): JSONArray = JSONArray().apply {
        activitySnapshot().forEach { operation ->''',
)
replace_exact(
    diag,
    '''                put("startEvent", operation.startEvent)
                put("lastEvent", operation.lastEvent)''',
    '''                put("startEvent", operation.startEvent)
                put("lastEvent", operation.lastEvent)
                put("screenGeneration", operation.screenGeneration ?: JSONObject.NULL)''',
)
replace_exact(
    diag,
    '''                    startEvent = event.name,
                    lastEvent = event.name,
                )''',
    '''                    startEvent = event.name,
                    lastEvent = event.name,
                    screenGeneration = event.attributes["diagnosticScreenGeneration"]?.toLongOrNull(),
                )''',
)
replace_exact(
    diag,
    '''    fun snapshot(): List<DiagnosticActiveOperation> = synchronized(lock) {
        active.values.sortedByDescending(DiagnosticActiveOperation::lastEventAtEpochMs)
    }''',
    '''    fun snapshot(screenGeneration: Long? = null): List<DiagnosticActiveOperation> = synchronized(lock) {
        active.values
            .asSequence()
            .filter { screenGeneration == null || it.screenGeneration == screenGeneration }
            .sortedByDescending(DiagnosticActiveOperation::lastEventAtEpochMs)
            .toList()
    }''',
)
replace_exact(
    diag,
    '''            "_VERIFIED", "_SUCCEEDED", "_SUCCESS", "_FINISHED",
        )''',
    '''            "_VERIFIED", "_SUCCEEDED", "_SUCCESS", "_FINISHED", "_OK",
        )''',
)

# 5) Unit coverage for *_OK closure and generation-scoped active operation snapshots.
tracker_test = "app/src/test/java/vn/nghetruyen/app/sourceplatform/DiagnosticActivityTrackerTest.kt"
replace_exact(
    tracker_test,
    '''    @Test
    fun timeoutSuffixClosesLegacyOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("BROWSER_WAIT_STARTED", "browser-wait", DiagnosticCategory.BROWSER))
        tracker.emit(event("BROWSER_WAIT_TIMEOUT", "browser-wait", DiagnosticCategory.BROWSER))
        assertTrue(tracker.snapshot().isEmpty())
    }

    private fun event(''',
    '''    @Test
    fun timeoutSuffixClosesLegacyOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("BROWSER_WAIT_STARTED", "browser-wait", DiagnosticCategory.BROWSER))
        tracker.emit(event("BROWSER_WAIT_TIMEOUT", "browser-wait", DiagnosticCategory.BROWSER))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun okSuffixClosesChromiumMicroStageOperation() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("CHROMIUM_DECODE_JSON_START", "decode-json", generation = 7L))
        assertEquals(1, tracker.snapshot(7L).size)
        tracker.emit(event("CHROMIUM_DECODE_JSON_OK", "decode-json", generation = 7L))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun activeSnapshotCanBeScopedToCurrentScreenGeneration() {
        val tracker = DiagnosticActivityTracker()
        tracker.emit(event("CHROMIUM_PROCESS_DATA_START", "old-process", generation = 8L))
        tracker.emit(event("SOURCE_CHECK_STARTED", "current-check", generation = 9L))

        val current = tracker.snapshot(9L)
        assertEquals(1, current.size)
        assertEquals("current-check", current.single().traceId)
        assertEquals(9L, current.single().screenGeneration)
        assertEquals(2, tracker.snapshot().size)
    }

    private fun event(''',
)
replace_exact(
    tracker_test,
    '''        traceId: String,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
    ) = DiagnosticEvent(''',
    '''        traceId: String,
        category: DiagnosticCategory = DiagnosticCategory.RUNTIME,
        generation: Long? = null,
    ) = DiagnosticEvent(''',
)
replace_exact(
    tracker_test,
    '        attributes = emptyMap(),',
    '        attributes = generation?.let { mapOf("diagnosticScreenGeneration" to it.toString()) }.orEmpty(),',
)

# 6) Strengthen STV regression: stale generated core + ~168 KiB/1000-record payload like live device.
stv_test = "source-lua/src/test/kotlin/vn/nghetruyen/source/lua/SangTacVietTocBudgetRegressionTest.kt"
replace_exact(
    stv_test,
    '''        val oldInstalledManifest = pack.manifest.copy(
            runtime = pack.manifest.runtime.copy(memoryBudgetBytes = 32 * 1024 * 1024),
        )

        val responseBody = realisticChapterApiResponse()
        val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
        assertTrue("fixture should resemble the 55-69 KiB device response: ${responseBytes.size}", responseBytes.size in 50 * 1024..90 * 1024)
''',
    '''        val oldInstalledManifest = pack.manifest.copy(
            runtime = pack.manifest.runtime.copy(memoryBudgetBytes = 32 * 1024 * 1024),
        )
        val currentCore = requireNotNull(pack.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
        assertTrue(currentCore.contains(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER))
        val stalePack = pack.copy(entries = LinkedHashMap(pack.entries).apply {
            put(
                "src/native_v2_core.js",
                currentCore.replace(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER, "STALE_NATIVE_V2_HOST_RUNTIME")
                    .toByteArray(Charsets.UTF_8),
            )
        })
        val overlay = NativeLuaRuntimeOverlay.refresh(stalePack)
        assertTrue("old installed NativeV2 core must be refreshed", overlay.refreshed)
        assertTrue(
            requireNotNull(overlay.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
                .contains(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER),
        )

        val responseBody = realisticChapterApiResponse()
        val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
        assertTrue(
            "fixture should resemble the 167,907-byte live device response: ${responseBytes.size}",
            responseBytes.size in 155 * 1024..180 * 1024,
        )
''',
)
replace_exact(
    stv_test,
    '        val result = runtime.execute(oldInstalledManifest, MapSourceResourceProvider(pack.entries), request)',
    '        val result = runtime.execute(oldInstalledManifest, MapSourceResourceProvider(overlay.entries), request)',
)
replace_exact(
    stv_test,
    '''    private fun realisticChapterApiResponse(): String {
        val padding = "x".repeat(250)
        val records = (1..100).joinToString("-//-") { index ->
            "$padding-/-$index-/-Chương $index-/-$padding"
        }''',
    '''    private fun realisticChapterApiResponse(): String {
        val padding = "x".repeat(70)
        val records = (1..1_000).joinToString("-//-") { index ->
            "$padding-/-$index-/-Chương $index-/-$padding"
        }''',
)

# 7) A successful navigation creates a new Story generation after the source call. Emit a compact,
# destination-owned success summary immediately after rotating so the Story ZIP is not empty while
# still keeping the detailed Explore trace on its original screen.
view_model = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_exact(
    view_model,
    '''            when (val result = source.story(story.url.ifBlank { story.id })) {
                is AppResult.Success -> {
                    resetChapterPagination(result.value.story.id)''',
    '''            val storyLoadStartedAt = System.currentTimeMillis()
            val storyOriginGeneration = container.sourceDiagnostics.recorder.currentScreenGeneration()
            when (val result = source.story(story.url.ifBlank { story.id })) {
                is AppResult.Success -> {
                    val destinationStoryKey = "story:${result.value.story.id}"
                    container.sourceDiagnostics.onScreenChanged(destinationStoryKey)
                    container.sourceDiagnostics.mark(
                        name = "STORY_SCREEN_READY",
                        category = vn.nghetruyen.source.diagnostics.DiagnosticCategory.RUNTIME,
                        severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
                        sourceId = result.value.story.sourceId,
                        durationMs = (System.currentTimeMillis() - storyLoadStartedAt).coerceAtLeast(0L),
                        attributes = mapOf(
                            "action" to "STORY",
                            "status" to "success",
                            "chapterCount" to result.value.chapters.size.toString(),
                            "hasNextChapterPage" to (!result.value.nextChapterPageUrl.isNullOrBlank()).toString(),
                            "originScreenGeneration" to storyOriginGeneration.toString(),
                            "handoff" to "source-action-to-story-screen",
                        ),
                    )
                    resetChapterPagination(result.value.story.id)''',
)

print("live diagnostics follow-up patch applied")
