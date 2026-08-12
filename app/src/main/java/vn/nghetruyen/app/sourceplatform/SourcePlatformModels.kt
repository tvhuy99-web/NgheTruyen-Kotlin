package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourcePermissionDiff
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SourcePackUiInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val installedVersions: List<String>,
    val canRollback: Boolean,
    val signerKeyId: String,
    val runtimeMode: String,
    val commentCapability: String,
    val commentFixtureCount: Int,
    val removable: Boolean = true,
    val ecosystem: String = "NATIVE",
    val contentType: String = "NOVEL",
    val compatibilityProfile: String = "",
    val configFields: List<SourceConfigFieldUi> = emptyList(),
    val loginAvailable: Boolean = false,
)

data class SourceConfigFieldUi(
    val key: String,
    val title: String,
    val subtitle: String,
    /** Sensitive values are always blank in UI snapshots. */
    val value: String,
    val defaultValue: String,
    val options: List<String>,
    val mode: String,
    val format: String,
    val sensitive: Boolean,
    val configured: Boolean,
)

data class SourceInstallPreview(
    val sourceId: String,
    val name: String,
    val version: String,
    val signerKeyId: String,
    val permissionDiff: SourcePermissionDiff,
    val permissionSummary: List<String>,
    val fixtureCount: Int,
)

data class SourceRepositoryUiInfo(
    val id: String,
    val name: String,
    val url: String,
    val generatedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val packageCount: Int,
    val signerKeyId: String,
)

data class SourceRepositoryPackageUiInfo(
    val repositoryId: String,
    val sourceId: String,
    val name: String,
    val version: String,
    val installedVersion: String?,
    val description: String,
    val changelog: String,
    val packageBytes: Int,
    val status: String,
    val canInstall: Boolean,
)

data class SourceDiagnosticUi(
    val timestampEpochMs: Long,
    val traceId: String,
    val sourceId: String,
    val category: String,
    val name: String,
    val severity: String,
    val durationMs: Long?,
    val detail: String,
)

data class SourceTrustKeyUi(
    val keyId: String,
    val algorithm: String,
    val fingerprint: String,
    val builtin: Boolean,
)

data class SourceTraceUi(
    val traceId: String,
    val sourceId: String,
    val eventCount: Int,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val failed: Boolean,
)

data class SourceSelectorInspectionUi(
    val selector: String,
    val matchCount: Int,
    val samples: List<String>,
)

/**
 * Plain-text report for the normal UI. This follows the Lua/XPK idea: errors explain what failed,
 * why it may have failed, and what the user can try, while raw events/evidence stay in the export.
 */
object DiagnosticHumanFormatter {
    fun formatEvents(events: List<DiagnosticEvent>, mode: String, title: String = "NHẬT KÝ CHẨN ĐOÁN"): String =
        buildReport(title, mode, events.sortedBy(DiagnosticEvent::timestampEpochMs).map { event ->
            Row(
                event.timestampEpochMs,
                event.sourceId,
                event.category.name,
                event.name,
                event.severity.name,
                event.durationMs,
                event.traceId,
                event.attributes,
            )
        })

    fun formatUi(events: List<SourceDiagnosticUi>, mode: String, title: String = "NHẬT KÝ CHẨN ĐOÁN"): String =
        buildReport(title, mode, events.sortedBy(SourceDiagnosticUi::timestampEpochMs).map { event ->
            Row(
                event.timestampEpochMs,
                event.sourceId,
                event.category,
                event.name,
                event.severity,
                event.durationMs,
                event.traceId,
                parseDetail(event.detail),
                event.detail,
            )
        })

    private fun buildReport(title: String, mode: String, rows: List<Row>): String = buildString {
        appendLine(title)
        appendLine("Chế độ: ${modeLabel(mode)}")
        appendLine("Số mốc: ${rows.size}")
        appendLine()
        if (rows.isEmpty()) {
            append("Chưa có nhật ký. Hãy thực hiện lại thao tác cần kiểm tra rồi mở nhật ký.")
            return@buildString
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) appendLine()
            append(row.render())
        }
    }.trimEnd()

    private data class Row(
        val timestampEpochMs: Long,
        val sourceId: String,
        val category: String,
        val code: String,
        val severity: String,
        val durationMs: Long?,
        val traceId: String,
        val attributes: Map<String, String>,
        val rawDetail: String = "",
    ) {
        private val problem: Boolean
            get() = severity.equals("ERROR", true) || severity.equals("WARN", true) ||
                code.contains("FAILED", true) || code.contains("ERROR", true)

        fun render(): String = buildString {
            val timestamp = TIME_FORMAT.get().format(Date(timestampEpochMs))
            val action = actionLabel(code, category)
            val prefix = when {
                severity.equals("ERROR", true) || code.contains("FAILED", true) -> "LỖI"
                severity.equals("WARN", true) -> "CẢNH BÁO"
                else -> "MỐC"
            }
            append("$timestamp • $prefix • $action")
            durationMs?.let { append(" • ${it} ms") }
            appendLine()
            if (!problem) {
                val pieces = mutableListOf<String>()
                if (sourceId.isNotBlank() && sourceId != "app") pieces += "Nguồn: $sourceId"
                compactDetail(attributes)?.let(pieces::add)
                append(pieces.joinToString(" • "))
                return@buildString
            }
            appendLine("Mã lỗi: $code")
            appendLine("Giai đoạn: ${stageLabel(code, category, attributes)}")
            if (sourceId.isNotBlank()) appendLine("Nguồn: $sourceId")
            target(attributes)?.let { appendLine("Đối tượng: $it") }
            message(attributes, rawDetail)?.let { appendLine("Thông báo: $it") }
            appendLine("Nguyên nhân: ${cause(code, category, attributes)}")
            appendLine("Gợi ý: ${suggestion(code, category, attributes)}")
            if (traceId.isNotBlank()) appendLine("Trace: $traceId")
            technicalDetail(attributes, rawDetail)?.let { append("Chi tiết kỹ thuật: $it") }
        }.trimEnd()
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "basic", "screen" -> "Gỡ lỗi theo màn hình"
        "advanced", "advanced_crash", "continuous" -> "Gỡ lỗi nối liền"
        else -> "Tắt"
    }

    private fun actionLabel(code: String, category: String): String = when {
        code.contains("INSTALL", true) -> "Cài đặt tiện ích"
        code.contains("PACKAGE", true) || code.contains("FETCH", true) -> "Tải dữ liệu"
        code.contains("NETWORK", true) || code.contains("HTTP", true) || category == "NETWORK" -> "Kết nối mạng"
        code.contains("PARSE", true) || code.contains("PARSER", true) || code.contains("SELECTOR", true) || category == "PARSER" -> "Phân tích dữ liệu"
        code.contains("BROWSER", true) || code.contains("WEBVIEW", true) || category == "BROWSER" -> "Trình duyệt / WebView"
        code.contains("LOGIN", true) || code.contains("SESSION", true) -> "Phiên đăng nhập"
        code.contains("REPOSITORY", true) -> "Kho tiện ích"
        code.contains("TRUST", true) || code.contains("SIGNATURE", true) || category == "TRUST" -> "Xác minh chữ ký"
        code.contains("TTS", true) || code.contains("VOICE", true) -> "TTS / giọng đọc"
        code.contains("AI", true) || code.contains("TRANSLAT", true) -> "AI / chuyển ngữ"
        code.contains("DOWNLOAD", true) -> "Tải truyện"
        code.contains("BACKUP", true) || code.contains("RESTORE", true) -> "Sao lưu / khôi phục"
        code.contains("ACTION", true) || code.contains("RUNTIME", true) || category == "RUNTIME" -> "Chạy tiện ích"
        else -> code.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun stageLabel(code: String, category: String, attributes: Map<String, String>): String =
        first(attributes, "stage", "phase", "operation", "action", "step") ?: actionLabel(code, category)

    private fun target(attributes: Map<String, String>): String? =
        first(attributes, "target", "url", "uri", "path", "host", "selector", "sourceUrl")

    private fun message(attributes: Map<String, String>, rawDetail: String): String? =
        first(attributes, "message", "error", "reason", "detail", "exception", "status")
            ?: rawDetail.takeIf(String::isNotBlank)?.take(2_000)

    private fun cause(code: String, category: String, attributes: Map<String, String>): String {
        first(attributes, "cause", "error", "reason", "exception")?.let { return it.take(2_000) }
        val status = first(attributes, "status", "httpStatus", "statusCode")
        return when {
            code.contains("TIMEOUT", true) -> "Thao tác vượt quá thời gian chờ trước khi nhận được kết quả hợp lệ."
            code.contains("HTTP", true) && status != null -> "Máy chủ trả về trạng thái HTTP $status thay vì dữ liệu ứng dụng mong đợi."
            code.contains("401", true) || code.contains("403", true) -> "Máy chủ từ chối yêu cầu, thường do phiên đăng nhập hoặc quyền truy cập không còn hợp lệ."
            code.contains("EMPTY", true) -> "Nguồn trả về dữ liệu rỗng hoặc nội dung cần thiết chưa xuất hiện."
            code.contains("SELECTOR", true) || code.contains("PARSE", true) || category == DiagnosticCategory.PARSER.name -> "Cấu trúc dữ liệu/HTML thực tế không khớp với cách phân tích hiện tại."
            code.contains("BROWSER", true) || code.contains("WEBVIEW", true) || category == DiagnosticCategory.BROWSER.name -> "Luồng trình duyệt hoặc JavaScript chưa đạt trạng thái mà tiện ích cần."
            code.contains("SIGNATURE", true) || code.contains("TRUST", true) || category == DiagnosticCategory.TRUST.name -> "Gói hoặc khóa nhà phát hành không vượt qua bước xác minh tin cậy."
            code.contains("ZIP", true) || code.contains("PACKAGE", true) || category == DiagnosticCategory.PACKAGE.name -> "Gói tiện ích không đáp ứng cấu trúc, giới hạn hoặc điều kiện kiểm tra bắt buộc."
            code.contains("REPOSITORY", true) -> "Danh mục kho không tải được, không hợp lệ hoặc không khớp dữ liệu gói."
            code.contains("NETWORK", true) || category == DiagnosticCategory.NETWORK.name -> "Kết nối mạng không hoàn thành đúng như nguồn yêu cầu."
            code.contains("TTS", true) || code.contains("VOICE", true) -> "Bộ đọc, giọng hoặc trạng thái phát âm thanh không sẵn sàng cho thao tác hiện tại."
            code.contains("AI", true) || code.contains("TRANSLAT", true) -> "Yêu cầu AI không hoàn thành hoặc phản hồi không thỏa điều kiện xử lý."
            else -> "Thao tác kết thúc bất thường ở giai đoạn ${actionLabel(code, category)}."
        }
    }

    private fun suggestion(code: String, category: String, attributes: Map<String, String>): String {
        first(attributes, "suggestion", "hint", "recovery")?.let { return it.take(2_000) }
        return when {
            code.contains("401", true) || code.contains("403", true) || code.contains("LOGIN", true) || code.contains("SESSION", true) ->
                "Mở lại phiên đăng nhập của nguồn rồi thực hiện lại thao tác. Nếu vẫn lỗi, xuất tệp để kiểm tra request, redirect và WebView."
            code.contains("TIMEOUT", true) ->
                "Kiểm tra mạng, thử lại một lần và xuất tệp nếu lỗi lặp lại để xem mốc thời gian, request và trạng thái trình duyệt."
            code.contains("HTTP", true) || code.contains("NETWORK", true) || category == DiagnosticCategory.NETWORK.name ->
                "Kiểm tra kết nối và phiên nguồn. Nếu trang gốc vẫn mở được nhưng ứng dụng lỗi, xuất tệp để đối chiếu HTTP, redirect và HTML."
            code.contains("SELECTOR", true) || code.contains("PARSE", true) || category == DiagnosticCategory.PARSER.name ->
                "Mở trang gốc hoặc trình duyệt chẩn đoán, sau đó xuất tệp. Gói xuất giữ HTML/DOM và trace để xác định cấu trúc đã thay đổi."
            code.contains("BROWSER", true) || code.contains("WEBVIEW", true) || category == DiagnosticCategory.BROWSER.name ->
                "Thử mở lại phiên trình duyệt nguồn và chờ trang tải xong. Nếu tiếp tục lỗi, xuất tệp để kiểm tra DOM, console, bridge và timeline."
            code.contains("SIGNATURE", true) || code.contains("TRUST", true) || category == DiagnosticCategory.TRUST.name ->
                "Không bỏ qua xác minh. Kiểm tra lại gói, nguồn tải và khóa nhà phát hành trước khi cài."
            code.contains("ZIP", true) || code.contains("PACKAGE", true) || category == DiagnosticCategory.PACKAGE.name ->
                "Tải lại gói từ nguồn tin cậy và kiểm tra đúng định dạng. Xuất tệp nếu cần xác định file hoặc manifest gây lỗi."
            code.contains("REPOSITORY", true) -> "Kiểm tra URL repository, kết nối và chữ ký danh mục, sau đó làm mới kho rồi thử lại."
            code.contains("TTS", true) || code.contains("VOICE", true) -> "Kiểm tra bộ đọc TTS, giọng đã chọn và thử nghe mẫu. Xuất tệp nếu lỗi chỉ xảy ra với một truyện hoặc giọng."
            code.contains("AI", true) || code.contains("TRANSLAT", true) -> "Kiểm tra nhà cung cấp, model và kết nối. Tệp xuất giữ timing cần thiết nhưng vẫn che dữ liệu nhạy cảm."
            else -> "Thực hiện lại thao tác. Nếu lỗi lặp lại, dùng XUẤT TỆP để lấy đầy đủ trace, evidence và trạng thái runtime."
        }
    }

    private fun compactDetail(attributes: Map<String, String>): String? {
        val useful = listOf("stage", "action", "url", "status", "count", "result")
            .mapNotNull { key -> attributes[key]?.takeIf(String::isNotBlank)?.let { "$key=$it" } }
        return useful.takeIf(List<String>::isNotEmpty)?.joinToString(" • ")
    }

    private fun technicalDetail(attributes: Map<String, String>, rawDetail: String): String? = when {
        attributes.isNotEmpty() -> attributes.entries.joinToString(" • ") { (key, value) -> "$key=$value" }.take(8_000)
        rawDetail.isNotBlank() -> rawDetail.take(8_000)
        else -> null
    }

    private fun first(attributes: Map<String, String>, vararg keys: String): String? {
        keys.forEach { wanted ->
            attributes.entries.firstOrNull { it.key.equals(wanted, true) }
                ?.value?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    private fun parseDetail(detail: String): Map<String, String> {
        if (detail.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        detail.split(" • ").forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) {
                val key = part.substring(0, index).trim()
                val value = part.substring(index + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) out[key] = value
            }
        }
        return out
    }

    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
    }
}
