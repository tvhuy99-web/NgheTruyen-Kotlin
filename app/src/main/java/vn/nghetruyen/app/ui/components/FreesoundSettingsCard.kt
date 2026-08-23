package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.freesound.FreesoundCredentialStore
import vn.nghetruyen.app.freesound.Mode3E5SemanticEngine
import vn.nghetruyen.app.freesound.Mode3LibraryAssetMatcher

@Composable
fun FreesoundSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val credentialStore = application.container.freesoundCredentialStore
    val client = application.container.freesoundClient
    val scope = rememberCoroutineScope()

    var apiKeyDraft by remember { mutableStateOf("") }
    var hasStoredKey by remember(credentialStore) { mutableStateOf(credentialStore.hasApiKey()) }
    var testing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var semanticStatus by remember { mutableStateOf(Mode3E5SemanticEngine.status()) }
    var semanticBusy by remember { mutableStateOf(false) }
    var semanticProgress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(statusMessage) {
        statusMessage?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }

    Card(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FREESOUND", fontWeight = FontWeight.SemiBold)
            Text(
                if (hasStoredKey) "Trạng thái: Đã cấu hình" else "Trạng thái: Chưa cấu hình",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Chỉ cần Khóa bí mật/khóa API của khách hàng. Mã khách hàng chưa cần dùng.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it.take(FreesoundCredentialStore.MAX_KEY_LENGTH) },
                label = { Text("Khóa API Freesound") },
                placeholder = {
                    Text(if (hasStoredKey) "Đã lưu — nhập khóa mới để thay thế" else "Nhập khóa API")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching { credentialStore.saveApiKey(apiKeyDraft) }
                            .onSuccess {
                                apiKeyDraft = ""
                                hasStoredKey = true
                                statusMessage = "Đã lưu khóa API Freesound trên thiết bị."
                            }
                            .onFailure {
                                statusMessage = it.message ?: "Không lưu được khóa API Freesound."
                            }
                    },
                    enabled = !testing && apiKeyDraft.trim().length >= FreesoundCredentialStore.MIN_KEY_LENGTH,
                    modifier = Modifier.weight(1f),
                ) { Text("LƯU KHÓA") }
                Button(
                    onClick = {
                        testing = true
                        statusMessage = null
                        scope.launch {
                            try {
                                statusMessage = client.testConnection().message
                            } finally {
                                testing = false
                                hasStoredKey = credentialStore.hasApiKey()
                            }
                        }
                    },
                    enabled = !testing && hasStoredKey,
                    modifier = Modifier.weight(1f),
                ) { Text(if (testing) "ĐANG KIỂM TRA…" else "KIỂM TRA") }
            }
            TextButton(
                onClick = {
                    credentialStore.clearApiKey()
                    apiKeyDraft = ""
                    hasStoredKey = false
                    statusMessage = "Đã xóa khóa API Freesound khỏi thiết bị."
                },
                enabled = !testing && hasStoredKey,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("XÓA KHÓA") }
            statusMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }

            Text("MÔ HÌNH TÌM KIẾM NGỮ NGHĨA", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    semanticBusy -> "Trạng thái: Đang xử lý"
                    semanticStatus.ready -> "Trạng thái: Multilingual E5 Small INT8 đã sẵn sàng"
                    semanticStatus.installed -> "Trạng thái: Đã tải, đang khởi tạo"
                    else -> "Trạng thái: Chưa tải • khoảng 136 MB"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Mô hình được lưu riêng khỏi APK và dùng lại qua các lần cập nhật ứng dụng. " +
                    "Nếu chưa tải mô hình, tìm kiếm ngữ nghĩa cục bộ sẽ không hoạt động.",
                style = MaterialTheme.typography.bodySmall,
            )
            semanticProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!semanticStatus.installed) {
                Button(
                    onClick = {
                        semanticBusy = true
                        semanticProgress = "Đang bắt đầu tải…"
                        scope.launch {
                            val result = Mode3E5SemanticEngine.install { progress ->
                                val percent = (progress.fraction * 100.0).toInt().coerceIn(0, 100)
                                semanticProgress = "$percent% • ${progress.currentFile}"
                            }
                            semanticBusy = false
                            semanticStatus = Mode3E5SemanticEngine.status()
                            result.onSuccess {
                                semanticProgress = "Đã tải xong. Đang lập chỉ mục mô tả âm thanh ở nền."
                                val tracks = application.container.database.sceneMusicTrackDao().listAll()
                                Mode3LibraryAssetMatcher.prewarmSemanticIndex(tracks)
                            }.onFailure { semanticProgress = it.message ?: "Không tải được mô hình." }
                        }
                    },
                    enabled = !semanticBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("TẢI MÔ HÌNH NGỮ NGHĨA") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                semanticBusy = true
                                semanticProgress = "Đang lập chỉ mục các mô tả còn thiếu…"
                                val tracks = application.container.database.sceneMusicTrackDao().listAll()
                                Mode3LibraryAssetMatcher.prewarmSemanticIndex(tracks)
                                semanticBusy = false
                                semanticStatus = Mode3E5SemanticEngine.status()
                                semanticProgress = "Đã yêu cầu lập chỉ mục ở nền."
                            }
                        },
                        enabled = !semanticBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("LẬP CHỈ MỤC") }
                    TextButton(
                        onClick = {
                            semanticBusy = true
                            val deleted = Mode3E5SemanticEngine.deleteModel()
                            semanticBusy = false
                            semanticStatus = Mode3E5SemanticEngine.status()
                            semanticProgress = if (deleted) "Đã xóa mô hình; âm thanh trong thư viện không bị xóa." else "Không xóa được mô hình."
                        },
                        enabled = !semanticBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("XÓA MÔ HÌNH") }
                }
            }
        }
    }
}
