package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticUi
import vn.nghetruyen.app.ui.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lua/XPK-style global diagnostic chrome.
 *
 * The reference XPK owns a diagnostic bar immediately above the bottom navigation. The diagnostic
 * button does not exist while diagnostics are OFF, changes its text while a runtime operation is
 * active, and opens one combined log surface. Keep that lifecycle here instead of letting individual
 * screens invent their own always-visible diagnostic buttons.
 */
@Composable
fun ReferenceDiagnosticsChrome(
    state: MainUiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    if (state.diagnosticsMode == "off") return

    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(state.diagnosticsMode) {
        if (state.diagnosticsMode == "off") showLog = false
    }

    val recording = diagnosticsRecording(state)
    val label = when {
        recording -> "ĐANG GHI NHẬT KÝ..."
        state.sourceDiagnosticCount > 0 -> "XEM NHẬT KÝ"
        else -> "CHƯA CÓ NHẬT KÝ"
    }

    Surface(color = Color(0xFFE5E5EA), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { showLog = true },
            modifier = Modifier.fillMaxWidth().padding(3.dp),
        ) {
            Text(label)
        }
    }

    if (showLog) {
        ReferenceDiagnosticsDialog(
            state = state,
            onExport = onExport,
            onClear = onClear,
            onDismiss = { showLog = false },
        )
    }
}

@Composable
private fun ReferenceDiagnosticsDialog(
    state: MainUiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val events = state.sourceDiagnostics.take(200)
    val traces = state.sourceTraces.take(100)
    val errorCount = events.count { it.severity == "ERROR" }
    val warningCount = events.count { it.severity == "WARN" }
    val grouped = events.groupBy(::diagnosticGroup)
    val timestamp = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NHẬT KÝ CHẨN ĐOÁN") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Mức: ${diagnosticsModeLabel(state.diagnosticsMode)} • ${state.sourceDiagnosticCount} sự kiện • $errorCount lỗi • $warningCount cảnh báo",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.diagnosticsMode == "advanced") {
                        "Advanced đang giữ trace chi tiết, browser/network evidence, DOM/HTML đã khử bí mật và hộp đen crash-safe 64 MiB."
                    } else {
                        "Basic ghi INFO/WARN/ERROR. Chọn Gỡ lỗi nâng cao để ghi DEBUG và evidence browser/runtime."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f).padding(2.dp)) {
                        Text(if (state.diagnosticsMode == "advanced") "XUẤT HỘP ĐEN" else "XUẤT NHẬT KÝ")
                    }
                    Button(onClick = onClear, modifier = Modifier.weight(1f).padding(2.dp)) {
                        Text("XÓA NHẬT KÝ")
                    }
                }

                DiagnosticSection("TRẠNG THÁI RUNTIME") {
                    val p = state.playback
                    Text(
                        "Đích=${state.destination} • tab=${state.rootTab} • source=${state.selectedSourceId} • loading=${state.loading} • AI=${state.aiBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "TTS: playing=${p.isPlaying} • prep=${p.preparationState} • story=${p.storyId.take(32)} • chapter=${p.chapterId.take(32)} • đoạn=${p.paragraphIndex + 1} • unit=${p.currentUnitId ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Giọng: rate=${"%.2f".format(Locale.ROOT, p.rate)} • pitch=${"%.2f".format(Locale.ROOT, p.pitch)} • volume=${"%.2f".format(Locale.ROOT, p.volume)} • Sonic=${state.sonicProcessingEnabled}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                DiagnosticSection("VIETPHRASE / AI") {
                    Text(
                        "VietPhrase=${state.vietPhraseEnabled} • rules=${state.vietPhraseRules.size} • dictionaries=${state.vietPhraseDictionaryStates.size} • suggestions=${state.vietPhraseSuggestions.size} • onlineBusy=${state.vietPhraseOnlineBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "AI provider=${state.aiOnline.provider} • enabled=${state.aiOnline.enabled} • model=${state.aiOnline.model.take(80)} • requestBusy=${state.aiBusy} • modelDiscovery=${state.aiModelDiscoveryBusy}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                val activeDownloads = state.downloads.filter { it.state != "COMPLETED" }.take(20)
                val failedChapters = state.downloadFailures.take(20)
                if (activeDownloads.isNotEmpty() || failedChapters.isNotEmpty()) {
                    DiagnosticSection("TẢI TRUYỆN") {
                        activeDownloads.forEach { job ->
                            Text(
                                "${job.state} • ${job.sourceId}/${job.storyId.take(24)} • ${job.completedChapters}/${job.totalChapters} • ${job.currentChapterTitle.take(80)} • retry=${job.retryCount}${job.errorMessage?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        failedChapters.forEach { failure ->
                            Text(
                                "LỖI CHƯƠNG ${failure.chapterIndex + 1} • ${failure.chapterTitle.take(80)} • ${failure.errorMessage.take(180)} • retry=${failure.retryCount}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                val exportJobs = state.audioExports.filter { it.state != "COMPLETED" }.take(20)
                if (exportJobs.isNotEmpty()) {
                    DiagnosticSection("XUẤT SÁCH NÓI") {
                        exportJobs.forEach { job ->
                            Text(
                                "${job.state}/${job.stage} • ${job.storyTitle.take(80)} • ${job.outputFormat} • ${job.completedSegments}/${job.totalSegments}${job.errorMessage?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                if (state.backupLogText.isNotBlank()) {
                    DiagnosticSection("SAO LƯU / KHÔI PHỤC") {
                        Text("Tệp: ${state.backupLogPath}", style = MaterialTheme.typography.bodySmall)
                        Text(state.backupLogText.takeLast(8_000), style = MaterialTheme.typography.bodySmall)
                    }
                }

                DIAGNOSTIC_GROUP_ORDER.forEach { group ->
                    val rows = grouped[group].orEmpty()
                    if (rows.isNotEmpty()) {
                        DiagnosticSection("$group (${rows.size})") {
                            rows.forEach { event ->
                                val time = timestamp.format(Date(event.timestampEpochMs))
                                val duration = event.durationMs?.let { " • ${it}ms" }.orEmpty()
                                Text(
                                    "$time • ${event.severity} • ${event.category}/${event.name}$duration",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    "${event.sourceId} • trace=${event.traceId.take(18)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (traces.isNotEmpty()) {
                    DiagnosticSection("TRACE (${traces.size})") {
                        traces.forEach { trace ->
                            Text(
                                "${if (trace.failed) "LỖI" else "OK"} • ${trace.sourceId} • ${trace.eventCount} sự kiện • ${(trace.endedAtEpochMs - trace.startedAtEpochMs).coerceAtLeast(0)}ms • ${trace.traceId}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                if (events.isEmpty()) {
                    Text(
                        "Chưa có sự kiện. Nhật ký sẽ bắt đầu xuất hiện ngay khi có request nguồn, WebView, AI, TTS hoặc tác vụ nền.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun DiagnosticSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
    )
    content()
}

private fun diagnosticsRecording(state: MainUiState): Boolean =
    state.loading ||
        state.aiBusy ||
        state.storyCommentsLoading ||
        state.sourceRepositoryRefreshing ||
        state.sourceHealthChecking.isNotEmpty() ||
        state.vietPhraseOnlineBusy ||
        state.aiModelDiscoveryBusy ||
        state.ttsVoiceLoading ||
        state.downloads.any { it.state == "RUNNING" } ||
        state.audioExports.any { it.state == "RUNNING" }

private fun diagnosticsModeLabel(mode: String): String = when (mode) {
    "advanced" -> "Gỡ lỗi nâng cao"
    "basic" -> "Gỡ lỗi cơ bản"
    else -> "Tắt"
}

private fun diagnosticGroup(event: SourceDiagnosticUi): String {
    val key = "${event.category}/${event.name}".uppercase(Locale.ROOT)
    return when {
        event.severity == "ERROR" || event.severity == "WARN" -> "LỖI & CẢNH BÁO"
        "BROWSER" in key || "WEBVIEW" in key || "RENDERER" in key || "DOM" in key -> "TRÌNH DUYỆT / WEBVIEW / DOM"
        "NETWORK" in key || "HTTP" in key || "SSL" in key || "COOKIE" in key || "WEBSOCKET" in key -> "MẠNG / HTTP / COOKIE"
        "AI_" in key || "VOICE_CAST" in key || "SCENE_MUSIC" in key || "NARRATION" in key -> "AI / PHÂN VAI / NHẠC CẢNH"
        "TTS" in key || "SONIC" in key || "PLAYBACK" in key || "AUDIO_FOCUS" in key -> "TTS / SONIC / PLAYBACK"
        "DOWNLOAD" in key || "AUDIO_EXPORT" in key || "VIETPHRASE" in key || "BACKUP" in key || "RESTORE" in key -> "TẢI / XUẤT / VIETPHRASE / BACKUP"
        "PACKAGE" in key || "STORE" in key || "SOURCE" in key || "VBOOK" in key || "LUA" in key || "PARSER" in key -> "NGUỒN / VBOOK / EXTENSION / PARSER"
        "SECURITY" in key || "TRUST" in key || "REPLAY" in key -> "BẢO MẬT / TRUST / REPLAY"
        else -> "RUNTIME / KHÁC"
    }
}

private val DIAGNOSTIC_GROUP_ORDER = listOf(
    "LỖI & CẢNH BÁO",
    "TRÌNH DUYỆT / WEBVIEW / DOM",
    "MẠNG / HTTP / COOKIE",
    "NGUỒN / VBOOK / EXTENSION / PARSER",
    "AI / PHÂN VAI / NHẠC CẢNH",
    "TTS / SONIC / PLAYBACK",
    "TẢI / XUẤT / VIETPHRASE / BACKUP",
    "BẢO MẬT / TRUST / REPLAY",
    "RUNTIME / KHÁC",
)
