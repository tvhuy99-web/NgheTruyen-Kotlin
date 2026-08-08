package vn.nghetruyen.app.ai.vietphrase

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VietPhraseDiagnosticExport(
    val path: String,
    val summary: String,
    val preview: String,
    val traceCount: Int,
)

object VietPhraseDiagnosticExporter {
    private const val TRACE_LIMIT = 20_000

    fun export(
        context: Context,
        title: String,
        paragraphs: List<String>,
        rules: List<VietPhraseRule>,
        storyId: String?,
        fallbackHanViet: Boolean,
    ): Result<VietPhraseDiagnosticExport> = runCatching {
        val body = paragraphs.joinToString("\n\n").trim()
        require(body.isNotBlank()) { "Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase." }
        val options = VietPhraseOptions(
            storyId = storyId,
            fallbackHanViet = fallbackHanViet,
            traceLimit = TRACE_LIMIT,
        )
        val engine = VietPhraseEngine(rules)
        val result = engine.translateWithTrace(body, options)
        val translatedTitle = title.takeIf(String::isNotBlank)?.let { engine.translate(it, options.copy(traceLimit = 0)) }.orEmpty()
        val now = Date()
        val summary = buildString {
            appendLine("NHẬT KÝ VIETPHRASE - NGHE TRUYỆN")
            appendLine("Thời gian: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now)}")
            appendLine("Tiêu đề: ${title.ifBlank { "Không có tiêu đề" }}")
            appendLine("Độ dài nội dung gốc: ${body.toByteArray(Charsets.UTF_8).size} byte")
            appendLine("Số quyết định được ghi: ${result.trace.size}${if (result.traceTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine()
            appendLine("CÀI ĐẶT")
            appendLine("fallback_hanviet=$fallbackHanViet")
            appendLine()
            appendLine("THỐNG KÊ MATCH")
            VietPhraseDictionaryKind.entries.forEach { kind ->
                appendLine("${kind.fileName}: ${result.appliedByKind[kind] ?: 0}")
            }
        }.trimEnd()
        val traceLines = buildList {
            add("start\tend\tkind\tsource\treplacement\trule_id\tcaptures")
            result.trace.forEach { entry ->
                add(
                    listOf(
                        entry.inputStart,
                        entry.inputEnd,
                        entry.kind?.fileName.orEmpty(),
                        entry.source.tsvSafe(),
                        entry.replacement.tsvSafe(),
                        entry.ruleId.orEmpty().tsvSafe(),
                        entry.captures.entries.sortedBy(Map.Entry<Int, String>::key)
                            .joinToString(";") { (slot, value) -> "$slot=${value.tsvSafe()}" },
                    ).joinToString("\t"),
                )
            }
        }
        val preview = traceLines.drop(1).take(60).joinToString("\n")
        val outputDir = diagnosticDirectory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(now)
        val target = File(outputDir, "vietphrase_diagnostic_$stamp.zip")
        val temp = File(outputDir, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        runCatching {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.addText(
                    "README.txt",
                    "Gói này được tạo bởi chức năng Chẩn đoán VietPhrase.\n" +
                        "Hãy gửi nguyên file ZIP để phân tích lỗi chất lượng dịch.\n" +
                        "trace.tsv ghi từng quyết định phân đoạn; summary.txt ghi thống kê tổng hợp.\n",
                )
                zip.addText("summary.txt", summary + "\n")
                zip.addText("source.txt", title + "\n\n" + body)
                zip.addText("translated.txt", translatedTitle + "\n\n" + result.text)
                zip.addText("trace.tsv", traceLines.joinToString("\n") + "\n")
            }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Không đổi tên được file ZIP tạm." }
        }.onFailure {
            temp.delete()
            throw it
        }
        VietPhraseDiagnosticExport(
            path = target.absolutePath,
            summary = summary,
            preview = preview,
            traceCount = result.trace.size,
        )
    }

    private fun diagnosticDirectory(context: Context): File {
        val candidates = buildList {
            add(File("/storage/emulated/0/NgheTruyen/diagnostics"))
            add(File("/storage/emulated/0/Download/NgheTruyen/diagnostics"))
            context.getExternalFilesDir(null)?.let { add(File(it, "diagnostics")) }
            add(File(context.filesDir, "diagnostics"))
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                if (!candidate.exists()) candidate.mkdirs()
                require(candidate.isDirectory)
                val probe = File(candidate, ".vp_probe_${System.nanoTime()}")
                probe.writeText("ok")
                probe.delete()
                true
            }.getOrDefault(false)
        } ?: error("Không tạo được thư mục nhật ký VietPhrase.")
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.tsvSafe(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}
