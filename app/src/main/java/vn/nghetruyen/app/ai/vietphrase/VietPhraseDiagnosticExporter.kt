package vn.nghetruyen.app.ai.vietphrase

import android.content.Context
import org.json.JSONObject
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VietPhraseDiagnosticExport(
    val path: String,
    val summary: String,
    val preview: String,
    val traceCount: Int,
    val probeCount: Int = 0,
)

object VietPhraseDiagnosticExporter {
    private const val TRACE_LIMIT = 20_000
    private const val PROBE_LIMIT = 20_000

    fun export(
        context: Context,
        title: String,
        paragraphs: List<String>,
        rules: List<VietPhraseRule>,
        storyId: String?,
        fallbackHanViet: Boolean,
        diagnostics: SourceDiagnosticRuntime? = null,
        diagnosticTraceId: String = "",
        diagnosticSourceId: String = "vietphrase",
    ): Result<VietPhraseDiagnosticExport> = runCatching {
        val traceId = diagnosticTraceId.ifBlank { "vietphrase:${UUID.randomUUID()}" }
        val body = paragraphs.joinToString("\n\n").trim()
        require(body.isNotBlank()) { "Hãy mở một chương truyện trước khi tạo nhật ký VietPhrase." }
        val options = VietPhraseOptions(
            storyId = storyId,
            fallbackHanViet = fallbackHanViet,
            traceLimit = TRACE_LIMIT,
            diagnosticProbeLimit = PROBE_LIMIT,
        )
        val engine = VietPhraseEngine(rules)
        val result = engine.translateWithTrace(body, options)
        val translatedTitle = title.takeIf(String::isNotBlank)?.let { engine.translate(it, options.copy(traceLimit = 0, diagnosticProbeLimit = 0)) }.orEmpty()
        val now = Date()
        val stats = result.diagnostics
        val summary = buildString {
            appendLine("NHẬT KÝ VIETPHRASE - NGHE TRUYỆN")
            appendLine("Thời gian: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now)}")
            appendLine("Tiêu đề: ${title.ifBlank { "Không có tiêu đề" }}")
            appendLine("Độ dài nội dung gốc: ${body.toByteArray(Charsets.UTF_8).size} byte")
            appendLine("Số quyết định được ghi: ${result.trace.size}${if (result.traceTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine("Số probe candidate/failure: ${stats.probes.size}${if (stats.probesTruncated) " (BỊ CẮT DO GIỚI HẠN)" else ""}")
            appendLine()
            appendLine("CÀI ĐẶT")
            appendLine("fallback_hanviet=$fallbackHanViet")
            appendLine()
            appendLine("THỐNG KÊ ENGINE")
            appendLine("cursor_positions=${stats.cursorPositions}")
            appendLine("literal_lookups=${stats.literalLookups}")
            appendLine("literal_candidates=${stats.literalCandidates}")
            appendLine("direct_selections=${stats.directSelections}")
            appendLine("template_candidates=${stats.templateCandidates}")
            appendLine("template_attempts=${stats.templateAttempts}")
            appendLine("template_matches=${stats.templateMatches}")
            appendLine("template_selections=${stats.templateSelections}")
            appendLine("template_budget_exceeded=${stats.templateBudgetExceeded}")
            appendLine("capture_candidates=${stats.captureCandidates}")
            appendLine("fallback_selections=${stats.fallbackSelections}")
            appendLine("unmatched_codepoints=${stats.unmatchedCodePoints}")
            appendLine("ai_replace_selections=${stats.aiReplaceSelections}")
            appendLine("multi_meaning_selections=${stats.multiMeaningSelections}")
            appendLine("rule_count=${rules.size}")
            appendLine("rule_kinds=${rules.groupingBy { it.kind.fileName }.eachCount().toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}")
            appendLine()
            appendLine("THỐNG KÊ MATCH")
            VietPhraseDictionaryKind.entries.forEach { kind ->
                appendLine("${kind.fileName}: ${result.appliedByKind[kind] ?: 0}")
            }
        }.trimEnd()
        val traceLines = buildList {
            add("start\tend\tkind\tsource\treplacement\trule_id\tcaptures")
            result.trace.forEach { entry ->
                add(listOf(entry.inputStart, entry.inputEnd, entry.kind?.fileName.orEmpty(), entry.source.tsvSafe(), entry.replacement.tsvSafe(), entry.ruleId.orEmpty().tsvSafe(), entry.captures.entries.sortedBy(Map.Entry<Int, String>::key).joinToString(";") { (slot, value) -> "$slot=${value.tsvSafe()}" }).joinToString("\t"))
            }
        }
        val probeLines = buildList {
            add("position\tphase\tkind\trule_id\toutcome\tdetail")
            stats.probes.forEach { probe ->
                add(listOf(probe.position, probe.phase.tsvSafe(), probe.kind?.fileName.orEmpty(), probe.ruleId.orEmpty().tsvSafe(), probe.outcome.tsvSafe(), probe.detail.tsvSafe()).joinToString("\t"))
            }
        }
        val statsJson = JSONObject()
            .put("cursorPositions", stats.cursorPositions)
            .put("literalLookups", stats.literalLookups)
            .put("literalCandidates", stats.literalCandidates)
            .put("directSelections", stats.directSelections)
            .put("templateCandidates", stats.templateCandidates)
            .put("templateAttempts", stats.templateAttempts)
            .put("templateMatches", stats.templateMatches)
            .put("templateSelections", stats.templateSelections)
            .put("templateBudgetExceeded", stats.templateBudgetExceeded)
            .put("captureCandidates", stats.captureCandidates)
            .put("fallbackSelections", stats.fallbackSelections)
            .put("unmatchedCodePoints", stats.unmatchedCodePoints)
            .put("aiReplaceSelections", stats.aiReplaceSelections)
            .put("multiMeaningSelections", stats.multiMeaningSelections)
            .put("ruleCount", rules.size)
            .put("ruleKinds", JSONObject(rules.groupingBy { it.kind.fileName }.eachCount()))
            .put("probeCount", stats.probes.size)
            .put("probesTruncated", stats.probesTruncated)
            .toString(2)
        val preview = (traceLines.drop(1).take(40) + probeLines.drop(1).take(40)).joinToString("\n")
        val outputDir = diagnosticDirectory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(now)
        val target = File(outputDir, "vietphrase_diagnostic_$stamp.zip")
        val temp = File(outputDir, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        runCatching {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.addText("README.txt", "Gói này được tạo bởi chức năng Chẩn đoán VietPhrase.\nHãy gửi nguyên file ZIP để phân tích lỗi chất lượng dịch.\ntrace.tsv ghi quyết định được áp dụng; probes.tsv ghi candidate bị loại/match và lý do; engine_stats.json ghi bộ đếm của engine.\n")
                zip.addText("summary.txt", summary + "\n")
                zip.addText("engine_stats.json", statsJson + "\n")
                zip.addText("source.txt", title + "\n\n" + body)
                zip.addText("translated.txt", translatedTitle + "\n\n" + result.text)
                zip.addText("trace.tsv", traceLines.joinToString("\n") + "\n")
                zip.addText("probes.tsv", probeLines.joinToString("\n") + "\n")
            }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "Không đổi tên được file ZIP tạm." }
        }.onFailure {
            temp.delete()
            throw it
        }

        diagnostics?.mark(
            name = "VIETPHRASE_ENGINE_STATS",
            category = DiagnosticCategory.PARSER,
            severity = DiagnosticSeverity.INFO,
            sourceId = diagnosticSourceId,
            traceId = traceId,
            attributes = mapOf(
                "traceDecisions" to result.trace.size.toString(),
                "probes" to stats.probes.size.toString(),
                "literalCandidates" to stats.literalCandidates.toString(),
                "templateAttempts" to stats.templateAttempts.toString(),
                "templateMatches" to stats.templateMatches.toString(),
                "templateBudgetExceeded" to stats.templateBudgetExceeded.toString(),
                "fallbackSelections" to stats.fallbackSelections.toString(),
                "unmatchedCodePoints" to stats.unmatchedCodePoints.toString(),
                "aiReplaceSelections" to stats.aiReplaceSelections.toString(),
                "multiMeaningSelections" to stats.multiMeaningSelections.toString(),
                "ruleCount" to rules.size.toString(),
            ),
        )
        diagnostics?.evidence?.takeIf { it.enabled }?.let { sink ->
            fun evidence(name: String, value: String) = sink.capture(
                DiagnosticEvidence(System.currentTimeMillis(), traceId, diagnosticSourceId, DiagnosticCategory.PARSER, name, "text/plain", value.toByteArray(Charsets.UTF_8)),
            )
            evidence("vietphrase-summary.txt", summary)
            evidence("vietphrase-engine-stats.json", statsJson)
            evidence("vietphrase-trace.tsv", traceLines.joinToString("\n"))
            evidence("vietphrase-probes.tsv", probeLines.joinToString("\n"))
            evidence("vietphrase-source.txt", title + "\n\n" + body)
            evidence("vietphrase-translated.txt", translatedTitle + "\n\n" + result.text)
        }

        VietPhraseDiagnosticExport(target.absolutePath, summary, preview, result.trace.size, stats.probes.size)
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
