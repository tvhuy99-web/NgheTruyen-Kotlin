package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.sourceplatform.DiagnosticHumanFormatter
import vn.nghetruyen.app.ui.MainUiState

/**
 * Lua-style global diagnostic entry point.
 *
 * The normal UI deliberately shows one readable timeline only. Structured events, traces, browser
 * evidence, runtime state and continuous history stay behind XUẤT TỆP so the screen does not turn
 * into a telemetry dashboard. XUẤT TỆP is intentionally the only export label here; the old
 * separate black-box/diagnostic export labels belong to the retired multi-panel UI.
 */
@Composable
fun ReferenceDiagnosticsChrome(
    state: MainUiState,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit = {},
) {
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(state.diagnosticsMode) {
        if (state.diagnosticsMode == "off") {
            showLog = false
            onVisibilityChanged(false)
        }
    }
    LaunchedEffect(showLog) {
        onVisibilityChanged(showLog)
    }
    DisposableEffect(Unit) {
        onDispose { onVisibilityChanged(false) }
    }

    if (state.diagnosticsMode == "off" && state.diagnosticPersistentCriticalCount == 0) return

    val recording = state.diagnosticActiveOperations.isNotEmpty()
    val label = when {
        recording -> "ĐANG GHI NHẬT KÝ..."
        state.diagnosticsMode == "off" && state.diagnosticPersistentCriticalCount > 0 ->
            "XEM ${state.diagnosticPersistentCriticalCount} LỖI CÀI ĐẶT"
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
            onDismiss = { showLog = false; onVisibilityChanged(false) },
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
    val clipboard = LocalClipboardManager.current
    val timelineTitle = if (state.diagnosticsMode == "off") {
        "LỖI CÀI/IMPORT ĐƯỢC GIỮ LẠI"
    } else {
        "NHẬT KÝ TRANG HIỆN TẠI"
    }
    val timeline = DiagnosticHumanFormatter.formatUi(
        events = state.sourceDiagnostics,
        mode = state.diagnosticsMode,
        title = timelineTitle,
    )
    val logText = buildString {
        when (state.diagnosticsMode) {
            "off" -> {
                appendLine("TRẠNG THÁI HỘP ĐEN")
                appendLine("Lỗi cài/import được giữ bền vững: ${state.diagnosticPersistentCriticalCount}")
                appendLine("Ghi theo phiên đang tắt; các lỗi cài/import quan trọng bên dưới vẫn được giữ.")
            }
            "basic", "screen" -> appendLine("PHIÊN GỠ LỖI THEO MÀN HÌNH")
            else -> appendLine("PHIÊN GỠ LỖI NỐI LIỀN")
        }
        if (state.diagnosticActiveOperations.isNotEmpty()) {
            appendLine()
            appendLine("THAO TÁC ĐANG HOẠT ĐỘNG (${state.diagnosticActiveOperations.size})")
            state.diagnosticActiveOperations.forEach { appendLine("• $it") }
        }
        appendLine()
        append(timeline)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NHẬT KÝ") },
        text = {
            SelectionContainer {
                Text(
                    logText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(logText)) },
                    modifier = Modifier.weight(1f),
                ) { Text("SAO CHÉP") }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                ) { Text("XÓA") }
                TextButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) { Text("XUẤT TỆP") }
            }
        },
    )
}
