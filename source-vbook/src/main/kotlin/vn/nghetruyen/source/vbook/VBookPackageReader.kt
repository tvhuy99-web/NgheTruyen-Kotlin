package vn.nghetruyen.source.vbook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class VBookPackageLimits(
    val maxZipBytes: Int = 16 * 1024 * 1024,
    val maxEntries: Int = 1024,
    val maxEntryBytes: Int = 4 * 1024 * 1024,
    val maxExpandedBytes: Int = 48 * 1024 * 1024,
)

data class VBookPackage(
    val pluginJsonBytes: ByteArray,
    val iconBytes: ByteArray?,
    val scripts: Map<String, ByteArray>,
    val otherFiles: Set<String>,
) {
    fun pluginJson(): String = strictUtf8(pluginJsonBytes, "plugin.json")

    fun decodeScripts(decoder: VBookScriptPayloadDecoder = VBookScriptPayloadDecoder.PLAIN_UTF8): Map<String, String> {
        val manifest = VBookManifestParser.parse(pluginJson())
        return scripts.mapValues { (path, payload) -> decoder.decode(manifest, path, payload) }
    }
}

fun interface VBookScriptPayloadDecoder {
    fun decode(manifest: VBookExtensionManifest, path: String, payload: ByteArray): String

    companion object {
        val PLAIN_UTF8 = VBookScriptPayloadDecoder { manifest, path, payload ->
            runCatching { strictUtf8(payload, path) }.getOrElse { cause ->
                if (manifest.metadata.encrypt) {
                    error("VBOOK_ENCRYPTED_SCRIPT_DECODER_REQUIRED:$path:${cause.message}")
                }
                throw cause
            }
        }
    }
}

object VBookPackageReader {
    fun read(bytes: ByteArray, limits: VBookPackageLimits = VBookPackageLimits()): VBookPackage {
        require(bytes.isNotEmpty() && bytes.size <= limits.maxZipBytes) { "VBOOK_ZIP_SIZE_INVALID" }
        val files = linkedMapOf<String, ByteArray>()
        val other = linkedSetOf<String>()
        var entryCount = 0
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= limits.maxEntries) { "VBOOK_ZIP_ENTRY_LIMIT" }
                val path = safeZipPath(entry.name)
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val output = ByteArrayOutputStream(minOf(limits.maxEntryBytes, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var entrySize = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entrySize += read
                    expanded += read.toLong()
                    require(entrySize <= limits.maxEntryBytes) { "VBOOK_ZIP_ENTRY_TOO_LARGE:$path" }
                    require(expanded <= limits.maxExpandedBytes) { "VBOOK_ZIP_EXPANDED_LIMIT" }
                    output.write(buffer, 0, read)
                }
                val keep = path == "plugin.json" || path == "icon.png" || (path.startsWith("src/") && path.endsWith(".js", true))
                if (keep) {
                    require(path !in files) { "VBOOK_ZIP_DUPLICATE_ENTRY:$path" }
                    files[path] = output.toByteArray()
                } else {
                    other += path
                }
                zip.closeEntry()
            }
        }
        val plugin = files.remove("plugin.json") ?: error("VBOOK_PLUGIN_JSON_MISSING")
        val icon = files.remove("icon.png")
        val scripts = files.filterKeys { it.startsWith("src/") && it.endsWith(".js", true) }
        require(scripts.isNotEmpty()) { "VBOOK_PACKAGE_SCRIPTS_MISSING" }
        strictUtf8(plugin, "plugin.json") // fail early on malformed metadata
        return VBookPackage(plugin, icon, scripts, other)
    }

    private fun safeZipPath(raw: String): String {
        val value = raw.replace('\\', '/').removePrefix("/")
        require(value.isNotBlank() && value.length <= 300) { "VBOOK_ZIP_PATH_INVALID" }
        val parts = value.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "VBOOK_ZIP_PATH_TRAVERSAL" }
        require(':' !in parts.first() && '\u0000' !in value) { "VBOOK_ZIP_PATH_INVALID" }
        return value
    }
}

private fun strictUtf8(bytes: ByteArray, label: String): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        .getOrElse { throw IllegalArgumentException("VBOOK_UTF8_INVALID:$label", it) }
}
