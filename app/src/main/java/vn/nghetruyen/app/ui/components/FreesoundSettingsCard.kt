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
        }
    }
}
