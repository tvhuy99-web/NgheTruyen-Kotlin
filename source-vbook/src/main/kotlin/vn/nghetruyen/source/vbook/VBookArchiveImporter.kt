package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object VBookArchiveImporter {
    private const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 48 * 1024 * 1024

    fun import(input: InputStream): Pair<VerifiedSourcePack, List<String>> {
        val archive = readBounded(input, MAX_ARCHIVE_BYTES)
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/').removePrefix("/")
                require(path.isNotBlank() && !path.startsWith("../") && "/../" !in path && ':' !in path) { "VBOOK_ARCHIVE_PATH_INVALID" }
                require(files.size < MAX_ENTRIES) { "VBOOK_ARCHIVE_ENTRY_LIMIT" }
                val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "VBOOK_ARCHIVE_TOTAL_LIMIT" }
                require(files.put(path, bytes) == null) { "VBOOK_ARCHIVE_DUPLICATE:$path" }
            }
        }
        val pluginPath = files.keys.firstOrNull { it.equals("plugin.json", true) || it.endsWith("/plugin.json", true) }
            ?: error("VBOOK_PLUGIN_JSON_MISSING")
        val prefix = pluginPath.removeSuffix("plugin.json")
        val normalized = files.filterKeys { it != pluginPath }.mapKeys { (path, _) -> path.removePrefix(prefix) }
        val plugin = VBookPluginImporter.parse(files.getValue(pluginPath), normalized)
        val imported = VBookPluginImporter.import(plugin)
        val entries = imported.entries
        val hash = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        return VerifiedSourcePack(
            manifest = imported.manifest,
            entries = entries,
            packageSha256 = hash,
            signerKeyId = "local-vbook-import",
            signatureAlgorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
        ) to imported.warnings
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "VBOOK_ARCHIVE_TOO_LARGE" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
