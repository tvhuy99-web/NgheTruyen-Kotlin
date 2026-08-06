package vn.nghetruyen.source.lua

import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** Imports either one Lua file or a complete Native Source API 2 archive. */
object NativeLuaArchiveImporter {
    private const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_ROOT_LUA_BYTES = 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 256
    private const val MAX_ARCHIVE_INFLATED_BYTES = 48 * 1024 * 1024

    fun import(input: InputStream): Pair<VerifiedSourcePack, List<String>> {
        val original = readBounded(input, MAX_INPUT_BYTES)
        val archive = if (isZip(original)) extractArchive(original) else NativeLuaArchive(
            entryPath = "source.lua",
            files = linkedMapOf("source.lua" to original),
        )
        val source = archive.files.getValue(archive.entryPath)
        require(source.size <= MAX_ROOT_LUA_BYTES) { "NATIVE_LUA_SOURCE_TOO_LARGE" }
        val imported = NativeLuaSourceImporter.import(source, archive.files, archive.entryPath)
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        return VerifiedSourcePack(
            manifest = imported.manifest,
            entries = imported.entries,
            packageSha256 = hash,
            signerKeyId = "local-native-lua-import",
            signatureAlgorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
        ) to imported.warnings
    }

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

    private val ALLOWED_EXTENSIONS = setOf(
        "lua", "json", "txt", "html", "htm", "js", "css", "xml",
        "png", "jpg", "jpeg", "webp", "gif", "svg", "dat", "dic",
    )
}

private data class NativeLuaArchive(
    val entryPath: String,
    val files: Map<String, ByteArray>,
)
