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


data class SourceImportFileMetadata(
    val displayName: String = "",
    val mimeType: String = "",
    val declaredSizeBytes: Long? = null,
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
        private val effectiveCode: String
            get() = first(attributes, "code", "errorCode", "error_code")?.takeIf(String::isNotBlank) ?: code

        private val problem: Boolean
            get() = severity.equals("ERROR", true) || severity.equals("WARN", true)

        fun render(): String = buildString {
            val timestamp = TIME_FORMAT.get().format(Date(timestampEpochMs))
            val action = actionLabel(effectiveCode, category)
            val prefix = when {
                severity.equals("ERROR", true) -> "LỖI"
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
            appendLine("Mã lỗi: $effectiveCode")
            appendLine("Giai đoạn: ${stageLabel(effectiveCode, category, attributes)}")
            if (sourceId.isNotBlank()) appendLine("Nguồn: $sourceId")
            target(attributes)?.let { appendLine("Đối tượng: $it") }
            message(attributes, rawDetail)?.let { appendLine("Thông báo: $it") }
            appendLine("Nguyên nhân: ${cause(effectiveCode, category, attributes)}")
            appendLine("Gợi ý: ${suggestion(effectiveCode, category, attributes)}")
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
        code.contains("BUILTIN_LUA_SOURCE_CONFLICT", true) -> "Đồng bộ nguồn tích hợp"
        code.contains("INSTALL", true) -> "Cài đặt tiện ích"
        code.contains("PACKAGE", true) || code.contains("FETCH", true) -> "Tải dữ liệu"
        code.contains("NETWORK", true) || code.contains("HTTP", true) || category == "NETWORK" -> "Kết nối mạng"
        code.contains("PARSE", true) || code.contains("PARSER", true) || code.contains("SELECTOR", true) || category == "PARSER" -> "Phân tích dữ liệu"
        code.contains("BROWSER", true) || code.contains("WEBVIEW", true) || category == "BROWSER" -> "Trình duyệt / WebView"
        code.contains("LOGIN", true) || code.contains("SESSION", true) -> "Phiên đăng nhập"
        code.contains("REPOSITORY", true) -> "Kho tiện ích"
        code.contains("TRUST", true) || code.contains("SIGNATURE", true) || category == "TRUST" -> "Xác minh chữ ký"
        code.contains("TTS", true) || code.contains("VOICE", true) -> "TTS / giọng đọc"
        isAiCode(code) -> "AI / chuyển ngữ"
        code.contains("DOWNLOAD", true) -> "Tải truyện"
        code.contains("BACKUP", true) || code.contains("RESTORE", true) -> "Sao lưu / khôi phục"
        code.contains("ACTION", true) || code.contains("RUNTIME", true) || category == "RUNTIME" -> "Chạy tiện ích"
        else -> code.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun stageLabel(code: String, category: String, attributes: Map<String, String>): String =
        first(attributes, "stage", "operationStage", "installStage", "phase", "operation", "action", "step") ?: actionLabel(code, category)

    private fun target(attributes: Map<String, String>): String? =
        first(attributes, "target", "url", "uri", "path", "host", "selector", "sourceUrl")

    private fun message(attributes: Map<String, String>, rawDetail: String): String? =
        first(attributes, "message", "error", "reason", "detail", "exception", "status")
            ?: rawDetail.takeIf(String::isNotBlank)?.take(2_000)

    private fun cause(code: String, category: String, attributes: Map<String, String>): String {
        first(attributes, "cause", "error", "reason", "exception")?.let { return it.take(2_000) }
        val status = first(attributes, "status", "httpStatus", "statusCode")
        exactLuaCause(code, attributes)?.let { return it }
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
            isAiCode(code) -> "Yêu cầu AI không hoàn thành hoặc phản hồi không thỏa điều kiện xử lý."
            else -> "Thao tác kết thúc bất thường ở giai đoạn ${actionLabel(code, category)}."
        }
    }

    private fun exactLuaCause(code: String, attributes: Map<String, String>): String? = when (code.uppercase(Locale.ROOT)) {
        "NETWORK_API_MISSING" -> "Runtime của tiện ích không có API mạng cần thiết cho thao tác này."
        "NETWORK_HTTP_STATUS" -> "Máy chủ trả về HTTP ${first(attributes, "status", "httpStatus", "statusCode") ?: "không mong đợi"} thay vì dữ liệu hợp lệ."
        "NETWORK_EMPTY_RESPONSE" -> "Yêu cầu mạng hoàn tất nhưng nội dung phản hồi rỗng."
        "NETWORK_TIMEOUT" -> "Yêu cầu mạng vượt quá thời gian chờ trước khi nhận đủ phản hồi."
        "NETWORK_START_FAILED" -> "Yêu cầu không khởi động được ở lớp mạng hoặc bị từ chối trước khi gửi."
        "NETWORK_RESPONSE_TOO_LARGE" -> "Phản hồi vượt giới hạn an toàn mà runtime cho phép giữ trong bộ nhớ."
        "REPOSITORY_JSON_INVALID" -> "Nội dung repository không phải JSON hợp lệ."
        "REPOSITORY_SCHEMA_INVALID" -> "JSON repository đọc được nhưng thiếu hoặc sai các trường bắt buộc."
        "REPOSITORY_NO_NOVEL" -> "Repository hợp lệ nhưng không có gói nguồn truyện tương thích để cài."
        "REPOSITORY_ENTRY_INVALID" -> "Một mục trong repository có URL, ID, phiên bản hoặc metadata không hợp lệ."
        "REMOTE_URL_EMPTY" -> "Liên kết gói tiện ích bị rỗng."
        "REMOTE_URL_INVALID" -> "Liên kết gói tiện ích không phải URL hợp lệ hoặc không đáp ứng chính sách an toàn."
        "DIRECT_VBOOK_MANIFEST_ONLY" -> "Liên kết chỉ trỏ tới manifest vBook, chưa phải gói cài đặt đầy đủ mà APK có thể xác minh."
        "ZIP_INVALID" -> "Dữ liệu tải về không phải ZIP hợp lệ hoặc cấu trúc ZIP bị hỏng."
        "ZIP_MISSING_MANIFEST" -> "Gói ZIP không có manifest bắt buộc của tiện ích."
        "ZIP_LIMIT" -> "Gói ZIP vượt giới hạn số file, kích thước entry hoặc tổng dung lượng giải nén."
        "VBOOK_MANIFEST_INVALID" -> "Manifest vBook tồn tại nhưng không vượt qua bước phân tích hoặc kiểm tra tương thích."
        "INSTALL_VALIDATION_FAILED" -> "Gói tiện ích thất bại ở bước kiểm tra trước khi ghi vào vùng cài đặt."
        "INSTALL_IO_FAILED" -> "Không thể ghi, di chuyển hoặc hoàn tất file cài đặt trên bộ nhớ thiết bị."
        "INSTALL_FINAL_VALIDATION_FAILED" -> "Gói đã được ghi tạm nhưng kiểm tra cuối sau cài đặt không đạt yêu cầu."
        "PACKAGE_EMPTY" -> "Gói SourcePack không chứa entry hợp lệ sau khi đọc định dạng lưu trữ."
        "VBOOK_PLUGIN_JSON_MISSING" -> "Gói vBook không chứa plugin.json tại vị trí mà trình nhập có thể nhận diện."
        "VBOOK_RESOURCE_MISSING" -> "Manifest hoặc script đang tham chiếu tới một tài nguyên không có trong gói; đường dẫn thiếu nằm trong chi tiết kỹ thuật."
        "VBOOK_COMPATIBILITY_FAILED" -> "Một hoặc nhiều action của tiện ích không thể nạp đủ script/tài nguyên trong runtime tương thích."
        "UNKNOWN_INSTALL_ERROR" -> "Quá trình cài đặt kết thúc bằng lỗi chưa được phân loại cụ thể."
        "BUILTIN_LUA_SOURCE_CONFLICT_PRESERVED" -> "APK chứa một bản nguồn tích hợp khác với bản đang hoạt động; ứng dụng chủ động giữ bản hiện tại để tránh tự ghi đè dữ liệu hoặc lựa chọn của người dùng."
        else -> null
    }

    private fun exactLuaSuggestion(code: String): String? = when (code.uppercase(Locale.ROOT)) {
        "NETWORK_API_MISSING" -> "Cập nhật APK/runtime hoặc dùng nguồn tương thích; xuất tệp để kiểm tra capability và runtime mode."
        "NETWORK_HTTP_STATUS" -> "Mở trang gốc, kiểm tra phiên đăng nhập và quyền truy cập. Nếu trang mở được, xuất tệp để đối chiếu HTTP, redirect và HTML phản hồi."
        "NETWORK_EMPTY_RESPONSE" -> "Thử lại sau khi kiểm tra mạng và phiên nguồn; nếu lặp lại, xuất tệp để xem request, status và response metadata."
        "NETWORK_TIMEOUT" -> "Kiểm tra mạng, thử lại và xuất tệp nếu tái diễn để xem deadline, polling, request và stage cuối cùng."
        "NETWORK_START_FAILED" -> "Kiểm tra URL, kết nối, quyền mạng và cấu hình nguồn; xuất tệp để xác định lỗi xảy ra trước hay sau khi broker nhận yêu cầu."
        "NETWORK_RESPONSE_TOO_LARGE" -> "Kiểm tra nguồn có trả nhầm trang hoặc dữ liệu bất thường; dùng file xuất để xem status, content type và kích thước phản hồi."
        "REPOSITORY_JSON_INVALID" -> "Mở URL repository và xác nhận nội dung là JSON đúng định dạng, không phải HTML lỗi/chuyển hướng."
        "REPOSITORY_SCHEMA_INVALID" -> "Đối chiếu repository với schema hỗ trợ và sửa các trường bắt buộc trước khi làm mới kho."
        "REPOSITORY_NO_NOVEL" -> "Kiểm tra repository có khai báo ít nhất một gói NOVEL tương thích với phiên bản APK hiện tại."
        "REPOSITORY_ENTRY_INVALID" -> "Kiểm tra từng entry của repository, nhất là ID, version, URL gói, checksum/chữ ký và content type."
        "REMOTE_URL_EMPTY" -> "Bổ sung URL tải gói trong repository hoặc dùng một liên kết cài đặt đầy đủ."
        "REMOTE_URL_INVALID" -> "Dùng URL HTTPS hợp lệ tới gói tiện ích và tránh liên kết chuyển hướng/host không được phép."
        "DIRECT_VBOOK_MANIFEST_ONLY" -> "Đóng gói manifest cùng mã nguồn/tài nguyên thành ZIP tiện ích hợp lệ rồi cài lại."
        "ZIP_INVALID" -> "Tải lại gói từ nguồn tin cậy; nếu vẫn lỗi, kiểm tra file có thực sự là ZIP và không bị trang web/CDN thay bằng HTML."
        "ZIP_MISSING_MANIFEST" -> "Bổ sung manifest đúng vị trí trong ZIP và đóng gói lại tiện ích."
        "ZIP_LIMIT" -> "Giảm số file/kích thước gói, loại file thừa hoặc chia dữ liệu lớn ra khỏi gói cài đặt."
        "VBOOK_MANIFEST_INVALID" -> "Kiểm tra schema, runtime, action, permission và đường dẫn tài nguyên trong manifest; xuất tệp để xem lỗi validation cụ thể."
        "INSTALL_VALIDATION_FAILED" -> "Không bỏ qua validation. Sửa gói theo lỗi chi tiết rồi cài lại từ đầu."
        "INSTALL_IO_FAILED" -> "Kiểm tra dung lượng trống và quyền truy cập bộ nhớ ứng dụng, sau đó thử cài lại."
        "INSTALL_FINAL_VALIDATION_FAILED" -> "Xóa gói lỗi, kiểm tra manifest/tài nguyên sau giải nén và cài lại từ nguồn sạch."
        "PACKAGE_EMPTY" -> "Kiểm tra đúng tệp đã chọn, kích thước tệp và định dạng ZIP/.ntsource; gửi kèm persistent_install_failures.json nếu lỗi lặp lại."
        "VBOOK_PLUGIN_JSON_MISSING" -> "Mở gói ZIP và xác nhận có plugin.json cùng thư mục src; không dùng riêng tệp manifest."
        "VBOOK_RESOURCE_MISSING" -> "Đối chiếu đường dẫn được báo với nội dung ZIP. Nếu đó là thư viện host như crypto.js, kiểm tra chính sách thư viện tích hợp của runtime."
        "VBOOK_COMPATIBILITY_FAILED" -> "Xem từng action và tài nguyên thiếu trong chi tiết kỹ thuật; sửa action đầu tiên thất bại rồi chạy lại kiểm tra tương thích."
        "UNKNOWN_INSTALL_ERROR" -> "Xuất tệp chẩn đoán để lấy stack/trace/stage cuối và dùng mã lỗi phụ trong Chi tiết kỹ thuật để phân loại."
        "BUILTIN_LUA_SOURCE_CONFLICT_PRESERVED" -> "Đây là cảnh báo bảo toàn, không phải lỗi runtime. So sánh SHA-256, signer và phiên bản trước khi chủ động thay thế nguồn đang hoạt động."
        else -> null
    }

    private fun suggestion(code: String, category: String, attributes: Map<String, String>): String {
        first(attributes, "suggestion", "hint", "recovery")?.let { return it.take(2_000) }
        exactLuaSuggestion(code)?.let { return it }
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
            isAiCode(code) -> "Kiểm tra nhà cung cấp, model và kết nối. Tệp xuất giữ timing cần thiết nhưng vẫn che dữ liệu nhạy cảm."
            else -> "Thực hiện lại thao tác. Nếu lỗi lặp lại, dùng XUẤT TỆP để lấy đầy đủ trace, evidence và trạng thái runtime."
        }
    }

    private fun isAiCode(code: String): Boolean =
        code.uppercase(Locale.ROOT)
            .split(Regex("[^A-Z0-9]+"))
            .any { it == "AI" } || code.contains("TRANSLAT", ignoreCase = true)

    private fun compactDetail(attributes: Map<String, String>): String? {
        val useful = listOf(
            "operationFlow", "operationKind", "stage", "action", "requestId", "transport",
            "method", "status", "url", "selector", "count", "result",
        )
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
