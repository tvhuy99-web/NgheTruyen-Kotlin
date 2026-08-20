package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import vn.nghetruyen.app.freesound.FreesoundCategory
import vn.nghetruyen.app.freesound.FreesoundCredentialStore
import vn.nghetruyen.app.freesound.FreesoundPreviewPlayer
import vn.nghetruyen.app.freesound.FreesoundSearchPage
import vn.nghetruyen.app.freesound.FreesoundSearchRequest
import vn.nghetruyen.app.freesound.FreesoundSearchResult
import vn.nghetruyen.app.freesound.FreesoundSort
import vn.nghetruyen.app.freesound.FreesoundSound

@Composable
fun FreesoundSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val credentialStore = application.container.freesoundCredentialStore
    val client = application.container.freesoundClient
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { FreesoundPreviewPlayer() }

    var apiKeyDraft by remember { mutableStateOf("") }
    var hasStoredKey by remember(credentialStore) { mutableStateOf(credentialStore.hasApiKey()) }
    var testing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FreesoundCategory.ALL) }
    var sort by remember { mutableStateOf(FreesoundSort.RELEVANCE) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf<FreesoundSearchPage?>(null) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var previewLoadingId by remember { mutableStateOf<Int?>(null) }
    var previewPlayingId by remember { mutableStateOf<Int?>(null) }

    fun stopPreview() {
        previewPlayer.stop()
        previewLoadingId = null
        previewPlayingId = null
    }

    fun search(targetPage: Int) {
        if (!hasStoredKey || searching) return
        stopPreview()
        searching = true
        searchStatus = "Đang tìm trên Freesound…"
        scope.launch {
            try {
                when (
                    val result = client.search(
                        FreesoundSearchRequest(
                            query = query,
                            category = category,
                            sort = sort,
                            page = targetPage,
                        ),
                    )
                ) {
                    is FreesoundSearchResult.Success -> {
                        searchPage = result.page
                        searchStatus = if (result.page.results.isEmpty()) {
                            "Không tìm thấy âm thanh phù hợp."
                        } else {
                            "Tìm thấy ${result.page.count} kết quả. Trang ${result.page.page}."
                        }
                    }
                    is FreesoundSearchResult.Failure -> {
                        searchStatus = result.message
                    }
                }
            } finally {
                searching = false
            }
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer.release() }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }
    LaunchedEffect(searchStatus) {
        searchStatus?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
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
                    stopPreview()
                    credentialStore.clearApiKey()
                    apiKeyDraft = ""
                    hasStoredKey = false
                    searchPage = null
                    searchStatus = null
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

    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TÌM ÂM THANH", fontWeight = FontWeight.SemiBold)
            if (!hasStoredKey) {
                Text(
                    "Hãy lưu khóa API Freesound ở phía trên trước khi tìm kiếm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(FreesoundSearchRequest.MAX_QUERY_LENGTH) },
                    label = { Text("Từ khóa") },
                    placeholder = { Text("Ví dụ: thunder storm, forest ambience, sword clash") },
                    singleLine = true,
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Loại âm thanh", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { categoryExpanded = true },
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${category.label} ▼") }
                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                    ) {
                        FreesoundCategory.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text((if (category == option) "✓ " else "") + option.label) },
                                onClick = {
                                    category = option
                                    categoryExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(categoryDescription(category), style = MaterialTheme.typography.bodySmall)

                Text("Sắp xếp", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { sortExpanded = true },
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${sort.label} ▼") }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                    ) {
                        FreesoundSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text((if (sort == option) "✓ " else "") + option.label) },
                                onClick = {
                                    sort = option
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = { search(1) },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "ĐANG TÌM…" else "TÌM TRÊN FREESOUND") }

                searchStatus?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }

                searchPage?.let { page ->
                    val totalPages = if (page.count <= 0) 1 else (page.count + page.pageSize - 1) / page.pageSize
                    Text(
                        "${page.count} kết quả • trang ${page.page}/$totalPages",
                        fontWeight = FontWeight.SemiBold,
                    )
                    page.results.forEach { sound ->
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        FreesoundResult(
                            sound = sound,
                            previewLoading = previewLoadingId == sound.id,
                            previewPlaying = previewPlayingId == sound.id,
                            onPreview = {
                                val previewUrl = sound.preferredPreviewUrl
                                if (previewUrl == null) {
                                    searchStatus = "Âm thanh này không có preview HQ khả dụng."
                                } else if (previewLoadingId == sound.id || previewPlayingId == sound.id) {
                                    stopPreview()
                                    searchStatus = "Đã dừng nghe thử."
                                } else {
                                    stopPreview()
                                    previewLoadingId = sound.id
                                    searchStatus = "Đang tải nghe thử ${sound.name}."
                                    previewPlayer.play(
                                        soundId = sound.id,
                                        previewUrl = previewUrl,
                                        onStarted = {
                                            previewLoadingId = null
                                            previewPlayingId = sound.id
                                            searchStatus = "Đang nghe thử ${sound.name}."
                                        },
                                        onStopped = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            searchStatus = "Đã phát xong ${sound.name}."
                                        },
                                        onError = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            searchStatus = "Không thể phát preview của ${sound.name}."
                                        },
                                    )
                                }
                            },
                        )
                    }
                    if (page.results.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { search(page.page - 1) },
                                enabled = !searching && page.hasPrevious,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG TRƯỚC") }
                            Button(
                                onClick = { search(page.page + 1) },
                                enabled = !searching && page.hasNext,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG SAU") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreesoundResult(
    sound: FreesoundSound,
    previewLoading: Boolean,
    previewPlaying: Boolean,
    onPreview: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(sound.name, fontWeight = FontWeight.SemiBold)
        Text(
            "${sound.username} • ${licenseLabel(sound.license)} • ${formatDuration(sound.durationSeconds)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "★ ${"%.1f".format(sound.avgRating)} (${sound.numRatings}) • ${sound.numDownloads} lượt tải",
            style = MaterialTheme.typography.bodySmall,
        )
        sound.tags.take(8).takeIf(List<String>::isNotEmpty)?.let { tags ->
            Text(tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        }
        val technical = buildList {
            sound.fileType.takeIf { it != "?" }?.uppercase()?.let(::add)
            sound.channels.takeIf { it > 0 }?.let { add("$it kênh") }
            sound.sampleRate.takeIf { it > 0 }?.let { add("${it / 1000.0} kHz") }
        }
        if (technical.isNotEmpty()) {
            Text(technical.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onPreview,
            enabled = sound.preferredPreviewUrl != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    previewLoading -> "ĐANG TẢI PREVIEW…"
                    previewPlaying -> "DỪNG NGHE THỬ"
                    sound.preferredPreviewUrl == null -> "KHÔNG CÓ PREVIEW HQ"
                    else -> "NGHE THỬ HQ"
                },
            )
        }
    }
}

private fun categoryDescription(category: FreesoundCategory): String = when (category) {
    FreesoundCategory.ALL -> "Không giới hạn thời lượng."
    FreesoundCategory.MUSIC -> "Ưu tiên nhạc nền dài 30 giây đến 15 phút."
    FreesoundCategory.AMBIENCE -> "Âm thanh môi trường từ 10 giây đến 5 phút."
    FreesoundCategory.SFX -> "Hiệu ứng âm thanh ngắn từ 0,1 đến 15 giây."
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val minutes = total / 60
    val remaining = total % 60
    return if (minutes > 0) "$minutes:${remaining.toString().padStart(2, '0')}" else "$remaining giây"
}

private fun licenseLabel(value: String): String {
    val normalized = value.lowercase()
    return when {
        "publicdomain/zero" in normalized || "cc0" in normalized -> "CC0"
        "/by-nc" in normalized -> "CC BY-NC"
        "/by/" in normalized || normalized.endsWith("/by") -> "CC BY"
        value.isBlank() -> "Không rõ giấy phép"
        else -> value.take(80)
    }
}
