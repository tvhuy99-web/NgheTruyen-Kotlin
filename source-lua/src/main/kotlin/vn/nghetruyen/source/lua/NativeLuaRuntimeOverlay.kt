package vn.nghetruyen.source.lua

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.packagekit.VerifiedSourcePack

/**
 * Refreshes only NgheTruyen-generated NativeV2 JavaScript for an already-installed Native Lua pack.
 *
 * The signed/source-owned bytes stay untouched on disk. Older app builds generated src/native_v2_*.js
 * at import time, so updating the APK alone previously left installed sources pinned to an old host
 * adapter forever. This overlay reconstructs the original Lua archive from preserved native archive bytes
 * and regenerates only host-owned src/native_v2_* resources in memory.
 */
object NativeLuaRuntimeOverlay {
    const val HOST_RUNTIME_MARKER = "NGHETRUYEN_NATIVE_V2_HOST_RUNTIME:2026-08-15.2"
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
