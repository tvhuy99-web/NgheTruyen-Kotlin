package vn.nghetruyen.app.transfer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AppDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/** Final compatibility layer: preserves every legacy download payload and converts safe chapter text. */
class LegacyXpkEverythingRestoreCoordinator(
    context: Context,
    private val completeCoordinator: LegacyXpkCompleteRestoreCoordinator,
    private val database: AppDatabase,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class RestoreSummary(
        val complete: LegacyXpkCompleteRestoreCoordinator.RestoreSummary,
        val downloadedChapters: Int = 0,
        val downloadFilesPreserved: Int = 0,
        val downloadFilesUnconverted: Int = 0,
        val warnings: List<String> = emptyList(),
    ) {
        fun userMessage(): String = buildString {
            append(complete.userMessage())
            if (downloadedChapters > 0) {
                append(" Đã nhập nội dung ").append(downloadedChapters).append(" chương đã tải.")
            }
            if (downloadFilesPreserved > 0) {
                append(" Đã bảo tồn nguyên gốc ").append(downloadFilesPreserved).append(" tệp tải xuống XPK.")
            }
            if (downloadFilesUnconverted > 0) {
                append(" ").append(downloadFilesUnconverted)
                    .append(" tệp response thô không đủ chắc chắn để biến thành nội dung chương nên chỉ được bảo tồn nguyên bản.")
            }
            if (warnings.isNotEmpty()) append(" Có ").append(warnings.size).append(" cảnh báo tải xuống.")
        }
    }

    suspend fun inspect(source: Uri): AppResult<LegacyXpkBackupImporter.Inspection> = completeCoordinator.inspect(source)

    suspend fun restoreFrom(
        source: Uri,
        requestedComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    ): AppResult<RestoreSummary> = withContext(Dispatchers.IO) {
        val requested = requestedComponents.ifEmpty { BackupComponent.entries.toSet() }
        when (val base = completeCoordinator.restoreFrom(source, requested)) {
            is AppResult.Failure -> base
            is AppResult.Success -> {
                if (BackupComponent.LIBRARY !in requested) {
                    return@withContext AppResult.Success(RestoreSummary(complete = base.value))
                }
                val stageRoot = File(appContext.cacheDir, "legacy_xpk_downloads_${System.nanoTime()}")
                try {
                    if (!stageRoot.mkdirs()) throw IOException("Không tạo được vùng tạm để nhập chương tải xuống XPK.")
                    val input = resolver.openInputStream(source)
                        ?: throw IOException("Không mở lại được tệp sao lưu XPK.")
                    input.use { extractDownloads(it, stageRoot) }
                    val warnings = mutableListOf<String>()
                    val restored = restoreDownloads(stageRoot, warnings)
                    AppResult.Success(
                        RestoreSummary(
                            complete = base.value,
                            downloadedChapters = restored.converted,
                            downloadFilesPreserved = restored.preserved,
                            downloadFilesUnconverted = restored.unconverted,
                            warnings = warnings,
                        ),
                    )
                } catch (error: Exception) {
                    AppResult.Failure(
                        "LEGACY_XPK_DOWNLOAD_RESTORE_FAILED",
                        error.message ?: "Không nhập được dữ liệu chương đã tải từ XPK.",
                        error,
                    )
                } finally {
                    stageRoot.deleteRecursively()
                }
            }
        }
    }

    private fun extractDownloads(input: InputStream, stageRoot: File) {
        var count = 0
        var total = 0L
        val seen = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                require(count <= MAX_ENTRY_COUNT) { "Bản sao lưu có quá nhiều mục." }
                val name = entry.name
                validateEntryName(name)
                require(seen.add(name)) { "Bản sao lưu có mục trùng lặp: $name" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val shouldExtract = name == LEGACY_DB_ENTRY ||
                    (name.startsWith("downloads/") && safeLeaf(name.substringAfter("downloads/")))
                if (shouldExtract) {
                    val target = File(stageRoot, name).canonicalFile
                    require(target.path.startsWith(stageRoot.canonicalPath + File.separator)) { "Đường dẫn XPK không an toàn." }
                    target.parentFile?.mkdirs()
                    val max = if (name == LEGACY_DB_ENTRY) MAX_DATABASE_BYTES else MAX_DOWNLOAD_BYTES
                    FileOutputStream(target).use { output -> total += copyBounded(zip, output, max) }
                    require(total <= MAX_TOTAL_BYTES) { "Dữ liệu chương tải xuống XPK vượt giới hạn an toàn." }
                }
                zip.closeEntry()
            }
        }
    }

    private data class DownloadRestore(val converted: Int, val preserved: Int, val unconverted: Int)

    private suspend fun restoreDownloads(stageRoot: File, warnings: MutableList<String>): DownloadRestore {
        val downloadsRoot = stageRoot.resolve("downloads")
        if (!downloadsRoot.isDirectory) return DownloadRestore(0, 0, 0)

        val files = downloadsRoot.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        if (files.isEmpty()) return DownloadRestore(0, 0, 0)
        files.forEach { preserveRaw(it) }

        val legacyDbFile = stageRoot.resolve(LEGACY_DB_ENTRY)
        if (!legacyDbFile.isFile) {
            warnings += "Backup có tệp chương tải xuống nhưng không có database thư viện để nối URL chương."
            return DownloadRestore(0, files.size, files.size)
        }

        val legacyDb = SQLiteDatabase.openDatabase(legacyDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        var converted = 0
        val matchedFiles = mutableSetOf<String>()
        try {
            legacyDb.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Database thư viện XPK không toàn vẹn."
                }
            }
            val hasChapters = legacyDb.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='chapters' LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            if (!hasChapters) return DownloadRestore(0, files.size, files.size)

            legacyDb.rawQuery(
                "SELECT url,offline_path,downloaded_at FROM chapters WHERE offline_path<>''",
                null,
            ).use { cursor ->
                val urlIndex = cursor.getColumnIndex("url")
                val pathIndex = cursor.getColumnIndex("offline_path")
                val timeIndex = cursor.getColumnIndex("downloaded_at")
                while (cursor.moveToNext()) {
                    val url = if (urlIndex >= 0 && !cursor.isNull(urlIndex)) cursor.getString(urlIndex).orEmpty() else ""
                    val oldPath = if (pathIndex >= 0 && !cursor.isNull(pathIndex)) cursor.getString(pathIndex).orEmpty() else ""
                    if (url.isBlank()) continue
                    val primaryLeaf = oldPath.substringAfterLast('/').substringAfterLast('\\')
                    val fallbackLeaf = md5Hex(url) + ".txt"
                    val leaf = primaryLeaf.takeIf(::safeLeaf) ?: fallbackLeaf
                    val payload = downloadsRoot.resolve(leaf).takeIf(File::isFile)
                        ?: downloadsRoot.resolve(fallbackLeaf).takeIf(File::isFile)
                        ?: continue
                    matchedFiles += payload.name

                    val chapter = database.chapterDao().get(stableChapterId(url)) ?: continue
                    val normalized = decodeChapterPayload(payload) ?: continue
                    val downloadedAt = if (timeIndex >= 0 && !cursor.isNull(timeIndex)) cursor.getLong(timeIndex) else 0L
                    database.chapterDao().upsert(
                        chapter.copy(
                            content = normalized,
                            downloadedAt = downloadedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        ),
                    )
                    converted += 1
                }
            }
        } finally {
            legacyDb.close()
        }
        val unconverted = files.count { it.name !in matchedFiles } + matchedFiles.size - converted
        return DownloadRestore(converted, files.size, unconverted.coerceAtLeast(0))
    }

    /**
     * Legacy download files contain raw source responses. Convert only shapes with a strong signal:
     * explicit JSON content/body/text, a known chapter-content HTML container, or actual plain text.
     */
    private fun decodeChapterPayload(file: File): String? {
        if (file.length() !in 1..MAX_CONVERTIBLE_DOWNLOAD_BYTES) return null
        val bytes = file.readBytes()
        val text = decodeUtf8(bytes)?.trim().orEmpty()
        if (text.isBlank()) return null

        val jsonContent = extractJsonContent(text)
        if (!jsonContent.isNullOrBlank()) return normalizeParagraphs(jsonContent)

        if (looksLikeHtml(text)) {
            val document = Jsoup.parse(text)
            val selectors = listOf(
                "#chapter-c",
                ".chapter-c",
                "#chapter-content",
                ".chapter-content",
                ".chapter-body",
                "[itemprop=articleBody]",
                "article.chapter",
            )
            val container = selectors.asSequence()
                .mapNotNull { selector -> document.selectFirst(selector) }
                .firstOrNull { it.text().length >= MIN_CHAPTER_TEXT_CHARS }
                ?: return null
            val paragraphs = container.select("p")
                .map { it.text().trim() }
                .filter(String::isNotBlank)
            return if (paragraphs.isNotEmpty()) {
                normalizeParagraphs(paragraphs.joinToString("\n"))
            } else {
                normalizeParagraphs(container.wholeText())
            }
        }

        if (looksLikePlainText(text)) return normalizeParagraphs(text)
        return null
    }

    private fun extractJsonContent(text: String): String? {
        if (!text.startsWith('{')) return null
        return runCatching {
            fun find(obj: JSONObject, depth: Int): String? {
                if (depth > 4) return null
                listOf("content", "body", "text", "chapter_content", "chapterContent").forEach { key ->
                    val value = obj.optString(key)
                    if (value.length >= MIN_CHAPTER_TEXT_CHARS) return value
                }
                listOf("data", "chapter", "result", "payload").forEach { key ->
                    val nested = obj.optJSONObject(key) ?: return@forEach
                    find(nested, depth + 1)?.let { return it }
                }
                return null
            }
            find(JSONObject(text), 0)
        }.getOrNull()
    }

    private fun normalizeParagraphs(raw: String): String? {
        var total = 0
        val paragraphs = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\\n+"))
            .asSequence()
            .map { it.trim().replace(Regex("[\\t ]+"), " ") }
            .filter(String::isNotBlank)
            .take(MAX_PARAGRAPHS)
            .takeWhile {
                total += it.length
                total <= MAX_NORMALIZED_CHARS
            }
            .toList()
        if (paragraphs.isEmpty() || paragraphs.sumOf(String::length) < MIN_CHAPTER_TEXT_CHARS) return null
        return paragraphs.joinToString(PARAGRAPH_SEPARATOR)
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.take(32_000).lowercase(Locale.ROOT)
        return "<html" in head || "<body" in head || "<p" in head || "<div" in head
    }

    private fun looksLikePlainText(text: String): Boolean {
        val sample = text.take(16_000)
        if (sample.startsWith('{') || sample.startsWith('[')) return false
        val angleCount = sample.count { it == '<' || it == '>' }
        return angleCount <= sample.length / 200 && sample.length >= MIN_CHAPTER_TEXT_CHARS
    }

    private fun preserveRaw(source: File) {
        val hash = sha256(source)
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140).ifBlank { "download.txt" }
        val target = appContext.filesDir.resolve("legacy-xpk-preserved/downloads/${hash.take(16)}-$safeName")
        atomicCopy(source, target)
    }

    private fun stableChapterId(rawUrl: String): String {
        val value = runCatching {
            val uri = URI(rawUrl.trim())
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (host.isBlank()) rawUrl.trim().trimEnd('/') else "$host$path"
        }.getOrElse { rawUrl.trim().trimEnd('/') }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "chapter:$digest"
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && name.length <= 1024) { "Tên mục XPK không hợp lệ." }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Đường dẫn XPK tuyệt đối không được phép." }
        require('\\' !in name && '\u0000' !in name) { "Tên mục XPK không an toàn." }
        require(name.split('/').none { it == ".." }) { "Đường dẫn XPK thoát thư mục." }
    }

    private fun safeLeaf(value: String): Boolean = value.isNotBlank() &&
        !value.contains('/') && !value.contains('\\') && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() || it in "._-" }

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Mục XPK vượt giới hạn an toàn." }
            output.write(buffer, 0, count)
        }
        output.flush()
        return total
    }

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        source.inputStream().use { input -> FileOutputStream(temp).use(input::copyTo) }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LEGACY_DB_ENTRY = "database/accessible_reader.db"
        private const val MAX_ENTRY_COUNT = 10_000
        private const val MAX_DATABASE_BYTES = 256L * 1024L * 1024L
        private const val MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_CONVERTIBLE_DOWNLOAD_BYTES = 16L * 1024L * 1024L
        private const val MAX_NORMALIZED_CHARS = 8 * 1024 * 1024
        private const val MAX_PARAGRAPHS = 50_000
        private const val MIN_CHAPTER_TEXT_CHARS = 40
        private const val PARAGRAPH_SEPARATOR = "\n\u0000\n"
    }
}
