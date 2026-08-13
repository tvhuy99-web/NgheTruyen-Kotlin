package vn.nghetruyen.source.lua

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceFullAuthorityPolicy
import vn.nghetruyen.source.packagekit.SourceManifestWriter
import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.vbook.VBookPluginImporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Imports one Lua file or a complete Native Source API 2 archive, including legacy embedded-vBook wrappers. */
object NativeLuaArchiveImporter {
    private const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_ROOT_LUA_BYTES = 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 256
    private const val MAX_ARCHIVE_INFLATED_BYTES = 48 * 1024 * 1024
    private const val MAX_EMBEDDED_VBOOK_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_EMBEDDED_VBOOK_FILES_JSON_BYTES = 12 * 1024 * 1024

    fun import(input: InputStream): Pair<VerifiedSourcePack, List<String>> {
        val original = readBounded(input, MAX_INPUT_BYTES)
        val archive = if (isZip(original)) extractArchive(original) else NativeLuaArchive(
            entryPath = "source.lua",
            files = linkedMapOf("source.lua" to original),
        )
        val source = archive.files.getValue(archive.entryPath)
        require(source.size <= MAX_ROOT_LUA_BYTES) { "NATIVE_LUA_SOURCE_TOO_LARGE" }

        // Older NgheTruyen builds shipped Wattpad as a Lua table that embeds the original vBook
        // manifest and JS file map. Keep this migration lane deliberately narrow: the wrapper is
        // evaluated inside the same hardened Lua sandbox, and only two bounded string fields are
        // accepted. Nothing from the wrapper receives an Android/JVM bridge.
        val sourceText = source.toString(Charsets.UTF_8)
        if (looksLikeEmbeddedVBookWrapper(sourceText)) {
            return importEmbeddedVBookWrapper(sourceText, original, archive.entryPath)
        }

        val imported = NativeLuaSourceImporter.import(source, archive.files, archive.entryPath)
        val authorityManifest = SourceFullAuthorityPolicy.apply(imported.manifest)
        val authorityEntries = LinkedHashMap(imported.entries).apply {
            // Keep the serialized package contract consistent with the manifest that will actually run.
            put("source.json", SourceManifestWriter.write(authorityManifest))
        }
        return verifiedPack(
            manifest = authorityManifest,
            entries = authorityEntries,
            original = original,
            signerKeyId = "local-native-lua-import",
        ) to (imported.warnings + "Authority: ${SourceFullAuthorityPolicy.AUTHORITY_ID}; mọi capability trong NgheTruyen được bật sau khi cài.")
    }

    private fun importEmbeddedVBookWrapper(
        sourceText: String,
        original: ByteArray,
        entryPath: String,
    ): Pair<VerifiedSourcePack, List<String>> {
        val sandbox = LuaSandbox(
            modules = emptyMap(),
            instructionBudget = 500_000,
            timeoutMs = 20_000,
            memoryBudgetBytes = 48 * 1024 * 1024,
        )
        val root = sandbox.evaluate(sourceText, "@legacy-vbook/$entryPath")
        require(root.istable()) { "LEGACY_VBOOK_LUA_TABLE_REQUIRED" }
        val source = root.get("source")
        require(source.istable()) { "LEGACY_VBOOK_LUA_SOURCE_REQUIRED" }
        val sourceTable = source.checktable()
        val manifestValue = sourceTable.get("vbook_manifest")
        val filesValue = sourceTable.get("vbook_files_json")
        require(manifestValue.isstring() && filesValue.isstring()) { "LEGACY_VBOOK_LUA_EMBEDDED_DATA_REQUIRED" }

        val manifestJson = manifestValue.checkjstring()
        val filesJson = filesValue.checkjstring()
        require(manifestJson.toByteArray(Charsets.UTF_8).size in 1..MAX_EMBEDDED_VBOOK_MANIFEST_BYTES) {
            "LEGACY_VBOOK_LUA_MANIFEST_TOO_LARGE"
        }
        require(filesJson.toByteArray(Charsets.UTF_8).size in 1..MAX_EMBEDDED_VBOOK_FILES_JSON_BYTES) {
            "LEGACY_VBOOK_LUA_FILES_TOO_LARGE"
        }

        val filesRoot = JsonCodec.parse(filesJson, maxDepth = 96, maxNodes = 200_000) as? JsonValue.Obj
            ?: error("LEGACY_VBOOK_LUA_FILES_INVALID")
        require(filesRoot.values.isNotEmpty() && filesRoot.values.size <= MAX_ZIP_ENTRIES) {
            "LEGACY_VBOOK_LUA_FILE_COUNT_INVALID"
        }
        var expandedBytes = 0L
        val files = linkedMapOf<String, ByteArray>()
        filesRoot.values.forEach { (path, value) ->
            val text = (value as? JsonValue.Str)?.value ?: error("LEGACY_VBOOK_LUA_FILE_INVALID:$path")
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_ENTRY_BYTES) { "LEGACY_VBOOK_LUA_FILE_TOO_LARGE:$path" }
            expandedBytes += bytes.size
            require(expandedBytes <= MAX_ARCHIVE_INFLATED_BYTES) { "LEGACY_VBOOK_LUA_EXPANDED_LIMIT" }
            files[path] = bytes
        }

        val plugin = VBookPluginImporter.parse(manifestJson.toByteArray(Charsets.UTF_8), files)
        val imported = VBookPluginImporter.import(plugin)
        val authorityManifest = SourceFullAuthorityPolicy.apply(imported.manifest)
        val authorityEntries = LinkedHashMap(imported.entries).apply {
            put("source.json", SourceManifestWriter.write(authorityManifest))
            // Preserve the original wrapper for diagnostics/export provenance without executing it at runtime.
            put("legacy/source.lua", sourceText.toByteArray(Charsets.UTF_8))
        }
        val warnings = imported.warnings + listOf(
            "Đã chuyển wrapper Lua vBook cũ sang VBOOK_JS_COMPAT bằng manifest/files nhúng; wrapper không được cấp Android/JVM bridge.",
            "Authority: ${SourceFullAuthorityPolicy.AUTHORITY_ID}; mọi capability trong NgheTruyen được bật sau khi cài.",
        )
        return verifiedPack(
            manifest = authorityManifest,
            entries = authorityEntries,
            original = original,
            signerKeyId = "local-legacy-vbook-lua-import",
        ) to warnings
    }

    private fun looksLikeEmbeddedVBookWrapper(sourceText: String): Boolean =
        "vbook_manifest" in sourceText && "vbook_files_json" in sourceText

    private fun verifiedPack(
        manifest: vn.nghetruyen.source.api.SourceManifest,
        entries: Map<String, ByteArray>,
        original: ByteArray,
        signerKeyId: String,
    ): VerifiedSourcePack = VerifiedSourcePack(
        manifest = manifest,
        entries = entries,
        packageSha256 = sha256(original),
        signerKeyId = signerKeyId,
        signatureAlgorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
    )

    private fun extractArchive(bytes: ByteArray): NativeLuaArchive {
        val files = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var inflatedBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ZIP_ENTRIES) { "NATIVE_LUA_ARCHIVE_ENTRY_LIMIT" }
                if (entry.isDirectory) continue
                val path = normalizePath(entry.name)
                val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension !in ALLOWED_EXTENSIONS) continue
                val content = readBounded(zip, MAX_ENTRY_BYTES)
                inflatedBytes += content.size
                require(inflatedBytes <= MAX_ARCHIVE_INFLATED_BYTES) { "NATIVE_LUA_ARCHIVE_INFLATED_LIMIT" }
                require(files.put(path, content) == null) { "NATIVE_LUA_ARCHIVE_DUPLICATE_ENTRY:$path" }
            }
        }
        val lua = files.filterKeys { it.endsWith(".lua", true) }
        require(lua.isNotEmpty()) { "NATIVE_LUA_ARCHIVE_SOURCE_MISSING" }
        val entryPath = chooseEntry(lua.keys)
        return NativeLuaArchive(entryPath, files)
    }

    private fun chooseEntry(paths: Set<String>): String {
        val priorities = listOf("source.lua", "main.lua", "init.lua")
        priorities.forEach { name ->
            paths.filter { it.equals(name, true) || it.endsWith("/$name", true) }
                .minByOrNull { it.count { c -> c == '/' } * 10_000 + it.length }
                ?.let { return it }
        }
        return paths.singleOrNull() ?: error("NATIVE_LUA_ARCHIVE_ENTRY_AMBIGUOUS")
    }

    private fun normalizePath(raw: String): String {
        val path = raw.replace('\\', '/').trimStart('/')
        require(path.isNotBlank() && path.length <= 512) { "NATIVE_LUA_ARCHIVE_PATH_INVALID" }
        require(!path.startsWith("../") && "/../" !in path && path != ".." && ':' !in path && '\u0000' !in path) {
            "NATIVE_LUA_ARCHIVE_PATH_INVALID"
        }
        return path.split('/').filter(String::isNotBlank).joinToString("/")
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "NATIVE_LUA_INPUT_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val ALLOWED_EXTENSIONS = setOf(
        "lua", "json", "txt", "html", "htm", "js", "css", "xml",
        "png", "jpg", "jpeg", "webp", "gif", "svg", "dat", "dic",
    )
}

private data class NativeLuaArchive(
    val entryPath: String,
    val files: Map<String, ByteArray>,
)
