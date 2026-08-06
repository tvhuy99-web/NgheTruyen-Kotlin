package vn.nghetruyen.app.importers

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ImportedBook
import vn.nghetruyen.app.core.model.ImportedChapter
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

class BookImporter(private val resolver: ContentResolver) {
    suspend fun import(uri: Uri): AppResult<ImportedBook> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryName(uri) ?: "Truyện nhập"
            val title = name.substringBeforeLast('.', name)
            when (name.substringAfterLast('.', "").lowercase()) {
                "txt" -> importText(uri, title)
                "epub" -> importEpub(uri, title)
                "docx" -> importDocx(uri, title)
                "mobi", "prc", "azw", "azw3" -> importMobi(uri, title)
                else -> importText(uri, title)
            }.also { book ->
                require(book.chapters.isNotEmpty()) { "Tệp không chứa chương có thể đọc." }
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure("IMPORT_FAILED", it.message ?: "Không thể nhập tệp.", it) },
        )
    }

    private fun importText(uri: Uri, title: String): ImportedBook {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readBounded(input, MAX_PLAIN_TEXT_BYTES)
        } ?: error("Không mở được tệp.")
        return ImportedBook(title = title, chapters = splitIntoChapters(decodeText(bytes)))
    }

    private fun importMobi(uri: Uri, fallbackTitle: String): ImportedBook {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readBounded(input, MAX_PLAIN_TEXT_BYTES)
        } ?: error("Không mở được tệp MOBI/AZW.")
        val mobi = MobiParser.parse(bytes, fallbackTitle)
        val document = Jsoup.parse(mobi.text)
        val plainText = document.body()?.wholeText().orEmpty().ifBlank { document.wholeText() }
        return ImportedBook(
            title = mobi.title.ifBlank { fallbackTitle },
            chapters = splitIntoChapters(plainText),
        )
    }

    private fun importDocx(uri: Uri, title: String): ImportedBook {
        val entries = zipTextEntries(uri)
        val xml = entries["word/document.xml"] ?: error("DOCX thiếu word/document.xml")
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val text = document.getElementsByTag("w:p")
            .map { paragraph -> paragraph.text().trim() }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .ifBlank { document.text() }
        return ImportedBook(title = title, chapters = splitIntoChapters(text))
    }

    private fun importEpub(uri: Uri, fallbackTitle: String): ImportedBook {
        val entries = zipTextEntries(uri)
        val containerXml = entries["META-INF/container.xml"]
            ?: error("EPUB thiếu META-INF/container.xml")
        val container = Jsoup.parse(containerXml, "", Parser.xmlParser())
        val packagePath = container.getElementsByTag("rootfile")
            .firstOrNull()
            ?.attr("full-path")
            ?.takeIf(String::isNotBlank)
            ?: error("EPUB không khai báo gói OPF.")
        val packageXml = entries[packagePath] ?: error("EPUB thiếu tệp OPF: $packagePath")
        val packageDoc = Jsoup.parse(packageXml, "", Parser.xmlParser())

        val metadataTitle = packageDoc.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
        val metadataAuthor = packageDoc.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
        val manifest = packageDoc.getElementsByTag("item").associate { item ->
            item.attr("id") to ManifestItem(
                href = item.attr("href"),
                mediaType = item.attr("media-type"),
            )
        }
        val spinePaths = packageDoc.getElementsByTag("itemref")
            .mapNotNull { itemRef -> manifest[itemRef.attr("idref")] }
            .filter { it.mediaType.contains("html", ignoreCase = true) || it.href.endsWith(".xhtml", true) }
            .map { resolveZipPath(packagePath, it.href) }

        val orderedPaths = spinePaths.ifEmpty {
            entries.keys.filter { name ->
                name.endsWith(".xhtml", true) || name.endsWith(".html", true) || name.endsWith(".htm", true)
            }.sorted()
        }
        val chapters = orderedPaths.distinct().mapNotNull { path ->
            val html = entries[path] ?: return@mapNotNull null
            val doc = Jsoup.parse(html, path)
            val text = doc.body()?.wholeText().orEmpty().trim()
            if (text.isBlank()) return@mapNotNull null
            ImportedChapter(
                title = doc.title().ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
                paragraphs = paragraphs(text),
            )
        }
        require(chapters.isNotEmpty()) { "EPUB không có nội dung XHTML/HTML có thể đọc." }
        return ImportedBook(
            title = metadataTitle.ifBlank { fallbackTitle },
            author = metadataAuthor,
            chapters = chapters,
        )
    }

    private fun zipTextEntries(uri: Uri): Map<String, String> {
        val result = linkedMapOf<String, String>()
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                var totalBytes = 0L
                var entryCount = 0
                while (entry != null) {
                    entryCount += 1
                    require(entryCount <= MAX_ZIP_ENTRIES) { "Tệp nén chứa quá nhiều mục." }
                    if (!entry.isDirectory && isTextEntry(entry.name)) {
                        val normalizedName = normalizeArchiveEntryName(entry.name)
                        require(normalizedName !in result) { "Tệp nén chứa mục trùng lặp: $normalizedName" }
                        val bytes = readBounded(zip, MAX_TEXT_ENTRY_BYTES)
                        totalBytes += bytes.size
                        require(totalBytes <= MAX_TOTAL_TEXT_BYTES) { "Tệp nén chứa quá nhiều dữ liệu văn bản." }
                        result[normalizedName] = decodeText(bytes)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Không mở được tệp nén.")
        return result
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Một mục trong tệp nén vượt quá giới hạn an toàn." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isTextEntry(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf("xml", "opf", "ncx", "xhtml", "html", "htm", "txt", "css")
    }

    private fun resolveZipPath(baseFile: String, relativePath: String): String {
        val cleanRelative = relativePath.substringBefore('#').substringBefore('?').replace('\\', '/')
        require(!cleanRelative.startsWith('/')) { "EPUB chứa đường dẫn tuyệt đối không hợp lệ." }
        val baseDirectory = baseFile.substringBeforeLast('/', "")
        return normalizeRelativePath(
            listOf(baseDirectory, cleanRelative).filter(String::isNotBlank).joinToString("/"),
        )
    }

    private fun normalizeArchiveEntryName(path: String): String {
        val normalizedSeparators = path.replace('\\', '/')
        require(!normalizedSeparators.startsWith('/')) { "Tệp nén chứa đường dẫn tuyệt đối." }
        require(!Regex("^[A-Za-z]:").containsMatchIn(normalizedSeparators)) {
            "Tệp nén chứa đường dẫn ổ đĩa không hợp lệ."
        }
        require(normalizedSeparators.split('/').none { it == ".." }) {
            "Tệp nén chứa đường dẫn thoát thư mục."
        }
        return normalizeRelativePath(normalizedSeparators)
    }

    private fun normalizeRelativePath(path: String): String {
        val stack = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> {
                    require(stack.isNotEmpty()) { "Đường dẫn trong sách thoát khỏi thư mục gốc." }
                    stack.removeLast()
                }
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return bytes.copyOfRange(offset, bytes.size).toString(charset)
    }

    private fun splitIntoChapters(text: String): List<ImportedChapter> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(normalized.isNotBlank()) { "Tệp không có nội dung văn bản." }
        val marker = Regex("(?im)^(chương|chapter|hồi|phần)\\s+[0-9ivxlcdm一二三四五六七八九十]+[^\\n]*$")
        val matches = marker.findAll(normalized).toList()
        if (matches.isEmpty()) {
            return normalized.chunked(MAX_CHAPTER_CHARS).mapIndexed { index, chunk ->
                ImportedChapter("Phần ${index + 1}", paragraphs(chunk))
            }
        }
        return matches.mapIndexed { index, match ->
            val start = match.range.first
            val end = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
            ImportedChapter(match.value.trim(), paragraphs(normalized.substring(start, end)))
        }
    }

    private fun paragraphs(text: String): List<String> = text
        .split(Regex("\\n\\s*\\n|(?<=[.!?…])\\s+(?=[A-ZÀ-Ỹ])"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter(String::isNotBlank)

    private fun queryName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private data class ManifestItem(val href: String, val mediaType: String)

    companion object {
        private const val MAX_PLAIN_TEXT_BYTES = 64L * 1024 * 1024
        private const val MAX_TEXT_ENTRY_BYTES = 8L * 1024 * 1024
        private const val MAX_TOTAL_TEXT_BYTES = 64L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 10_000
        private const val MAX_CHAPTER_CHARS = 30_000
    }
}
