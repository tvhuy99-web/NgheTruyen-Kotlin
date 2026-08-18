package vn.nghetruyen.app.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.ai.vietphrase.VietPhraseBinaryDictionaryCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseBundleCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseImportPlanner
import vn.nghetruyen.app.ai.vietphrase.VietPhrasePersistenceArchiveCodec
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import java.io.ByteArrayOutputStream
import java.io.IOException


class VietPhraseTransferManager(
    private val contentResolver: ContentResolver,
    private val repository: LibraryRepository,
) {
    data class ImportPreview(
        val sourceName: String,
        val sourceFormat: String,
        val incomingCount: Int,
        val duplicateCount: Int,
        val warnings: List<String>,
        val plan: VietPhraseImportPlanner.Plan,
        val dictionaryStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList(),
    ) {
        val errorCount: Int get() = plan.conflicts.count { it.severity.name == "ERROR" }
        val warningCount: Int get() = plan.conflicts.count { it.severity.name == "WARNING" }
    }

    suspend fun previewFrom(
        uri: Uri,
        forcedKind: VietPhraseDictionaryKind? = null,
    ): AppResult<ImportPreview> = withContext(Dispatchers.IO) {
        val effectiveKind = forcedKind ?: ReferenceVietPhraseRuntime.consumeImportKind()
        try {
            val name = displayName(uri).ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
                .ifBlank { effectiveKind?.fileName ?: "VietPhrase.txt" }
            val bytes = readBounded(uri)
            val lower = name.lowercase()
            val rules: List<VietPhraseRule>
            val format: String
            val duplicates: Int
            val warnings: List<String>
            val dictionaryStates: List<VietPhrasePersistenceArchiveCodec.DictionaryState>
            if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                require(effectiveKind == null) { "Hãy dùng NHẬP FILE ZIP để nhập gói nhiều từ điển." }
                val bundle = VietPhraseBundleCodec.decodeZip(bytes)
                rules = bundle.rules
                format = "ZIP_BUNDLE"
                duplicates = bundle.duplicateCount
                warnings = bundle.warnings
                dictionaryStates = bundle.dictionaryStates
            } else if (lower.endsWith(".dic") || lower.endsWith(".dat")) {
                val decoded = VietPhraseBinaryDictionaryCodec.decode(bytes, name)
                rules = if (effectiveKind == null) decoded.rules else decoded.rules.map { it.copy(kind = effectiveKind) }
                format = decoded.format.name
                duplicates = decoded.duplicateCount
                warnings = decoded.warnings
                dictionaryStates = emptyList()
            } else {
                val kind = effectiveKind ?: VietPhraseDictionaryKind.fromFileName(name) ?: VietPhraseDictionaryKind.VIET_PHRASE
                val decoded = VietPhraseDictionaryCodec.decode(bytes, name, kind)
                rules = decoded.rules
                format = "TEXT_${decoded.delimiter.replace("\t", "TAB")}" 
                duplicates = decoded.duplicateCount
                warnings = emptyList()
                dictionaryStates = emptyList()
            }
            if (rules.isEmpty()) throw IOException("Tệp không có quy tắc VietPhrase hợp lệ.")
            val plan = repository.previewVietPhraseImport(rules, rules.mapTo(linkedSetOf()) { it.kind })
            AppResult.Success(ImportPreview(name, format, rules.size, duplicates, warnings, plan, dictionaryStates))
        } catch (error: Exception) {
            AppResult.Failure("VIETPHRASE_PREVIEW_FAILED", error.message ?: "Không đọc được VietPhrase.", error)
        }
    }

    suspend fun commit(preview: ImportPreview): AppResult<VietPhraseSnapshotEntity> = withContext(Dispatchers.IO) {
        try {
            require(preview.plan.canCommit) { "Không thể nhập khi còn xung đột nghiêm trọng." }
            AppResult.Success(
                repository.commitVietPhraseImport(
                    preview.plan,
                    preview.sourceName,
                    preview.sourceFormat,
                    importedStates = preview.dictionaryStates,
                ),
            )
        } catch (error: Exception) {
            AppResult.Failure("VIETPHRASE_IMPORT_FAILED", error.message ?: "Không nhập được VietPhrase.", error)
        }
    }

    suspend fun importFrom(
        uri: Uri,
        forcedKind: VietPhraseDictionaryKind? = null,
    ): AppResult<Int> = when (val preview = previewFrom(uri, forcedKind)) {
        is AppResult.Failure -> preview
        is AppResult.Success -> when (val committed = commit(preview.value)) {
            is AppResult.Failure -> committed
            is AppResult.Success -> AppResult.Success(preview.value.plan.after.size)
        }
    }

    suspend fun exportTo(uri: Uri): AppResult<Int> = withContext(Dispatchers.IO) {
        try {
            val rules = repository.listAllVietPhraseRules()
            val dictionaryStates = repository.listVietPhraseDictionaryStates()
            val encoded = VietPhraseBundleCodec.encodeZip(rules, dictionaryStates)
            if (encoded.size > MAX_EXPORT_BYTES) throw IOException("Bộ VietPhrase quá lớn để xuất.")
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(encoded) }
                ?: throw IOException("Không tạo được gói VietPhrase.")
            AppResult.Success(rules.size)
        } catch (error: Exception) {
            AppResult.Failure("VIETPHRASE_EXPORT_FAILED", error.message ?: "Không xuất được VietPhrase.", error)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun readBounded(uri: Uri): ByteArray = contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FILE_BYTES) throw IOException("Tệp VietPhrase vượt 256 MiB.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("Không mở được tệp VietPhrase.")

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024 * 1024
        private const val MAX_EXPORT_BYTES = 128 * 1024 * 1024
    }
}
