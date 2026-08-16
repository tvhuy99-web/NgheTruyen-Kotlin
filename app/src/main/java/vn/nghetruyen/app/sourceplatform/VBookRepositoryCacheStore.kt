package vn.nghetruyen.app.sourceplatform

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class VBookCachedRepositoryDocument(
    val body: String,
    val updatedAtEpochMs: Long,
)







class VBookRepositoryCacheStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    @Synchronized
    fun read(url: String, maxBytes: Int): VBookCachedRepositoryDocument? {
        if (maxBytes <= 0) return null
        val file = fileFor(url)
        if (!file.isFile || file.length() <= 0L || file.length() > maxBytes.toLong()) return null
        return runCatching {
            VBookCachedRepositoryDocument(
                body = file.readText(Charsets.UTF_8),
                updatedAtEpochMs = file.lastModified().coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    @Synchronized
    fun write(url: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        val first = body.firstOrNull { !it.isWhitespace() }
        if (first != '{' && first != '[') return

        directory.mkdirs()
        val target = fileFor(url)
        val temp = File(directory, "${target.name}.tmp-${System.nanoTime()}")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            prune()
        }.onFailure {
            temp.delete()
        }
    }

    @Synchronized
    fun remove(url: String) {
        runCatching { fileFor(url).delete() }
    }

    private fun prune() {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.contains(".tmp-") }
            .sortedByDescending(File::lastModified)

        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length().coerceAtLeast(0L)
            if (index >= MAX_ENTRIES || total > MAX_TOTAL_BYTES) {
                runCatching { file.delete() }
            }
        }
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }

    companion object {
        private const val DIRECTORY = "vbook_repository_catalog_cache_v1"
        private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
        private const val MAX_ENTRIES = 256
    }
}
