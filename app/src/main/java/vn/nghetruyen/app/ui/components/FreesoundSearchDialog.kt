package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.freesound.FreesoundCategory
import vn.nghetruyen.app.freesound.FreesoundDuration
import vn.nghetruyen.app.freesound.FreesoundPreviewPlayer
import vn.nghetruyen.app.freesound.FreesoundSearchPage
import vn.nghetruyen.app.freesound.FreesoundSearchRequest
import vn.nghetruyen.app.freesound.FreesoundSearchResult
import vn.nghetruyen.app.freesound.FreesoundSort
import vn.nghetruyen.app.freesound.FreesoundSound
import vn.nghetruyen.app.playback.ReaderPlaybackService

@Composable
fun FreesoundSearchDialog(
    kind: AudioAssetKind,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val application = context.applicationContext as NgheTruyenApplication
    val credentialStore = application.container.freesoundCredentialStore
    val client = application.container.freesoundClient
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { FreesoundPreviewPlayer() }
    val category = remember(kind) { kind.toFreesoundCategory() }

    var query by remember(kind) { mutableStateOf("") }
    var duration by remember(kind) { mutableStateOf(FreesoundDuration.RECOMMENDED) }
    var sort by remember(kind) { mutableStateOf(FreesoundSort.RELEVANCE) }
    var durationExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf<FreesoundSearchPage?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewLoadingId by remember { mutableStateOf<Int?>(null) }
    var previewPlayingId by remember { mutableStateOf<Int?>(null) }

    val hasApiKey = credentialStore.hasApiKey()

    fun endPlaybackPreview() {
        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
    }

    fun stopPreview() {
        previewPlayer.stop()
        previewLoadingId = null
        previewPlayingId = null
        endPlaybackPreview()
    }

    fun close() {
        stopPreview()
        onDismiss()
    }

    fun runSearch(targetPage: Int) {
        if (!hasApiKey || searching || query.isBlank()) return
        stopPreview()
        searching = true
        status = "Đang tìm trên Freesound…"
        scope.launch {
            try {
                when (
                    val result = client.search(
                        FreesoundSearchRequest(
                            query = query,
                            category = category,
                            duration = duration,
                            sort = sort,
                            page = targetPage,
                        ),
                    )
                ) {
                    is FreesoundSearchResult.Success -> {
                        searchPage = result.page
                        status = if (result.page.results.isEmpty()) {
                            "Không tìm thấy âm thanh phù hợp."
                        } else {
                            "Tìm thấy ${result.page.count} kết quả. Trang ${result.page.page}."
                        }
                    }
                    is FreesoundSearchResult.Failure -> status = result.message
                }
            } finally {
                searching = false
            }
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
            endPlaybackPreview()
        }
    }

    LaunchedEffect(status) {
        status?.takeIf(String::isNotBlank)?.let(view::announceForAccessibility)
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("TÌM TRÊN FREESOUND — ${category.label.uppercase()}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!hasApiKey) {
                    Text(
                        "Chưa có khóa API Freesound. Hãy cấu hình khóa trong Cài đặt → Nguồn âm thanh trực tuyến → Freesound.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }

                Text("Loại: ${category.label}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(FreesoundSearchRequest.MAX_QUERY_LENGTH) },
                    label = { Text("Từ khóa") },
                    placeholder = { Text(searchHint(kind)) },
                    singleLine = true,
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Thời lượng", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { durationExpanded = true },
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${duration.label} ▼") }
                    DropdownMenu(
                        expanded = durationExpanded,
                        onDismissRequest = { durationExpanded = false },
                    ) {
                        FreesoundDuration.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text((if (duration == option) "✓ " else "") + option.label) },
                                onClick = {
                                    duration = option
                                    durationExpanded = false
                                },
                            )
                        }
                    }
                }

                Text("Thứ tự", fontWeight = FontWeight.SemiBold)
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
                    onClick = { runSearch(1) },
                    enabled = !searching && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "ĐANG TÌM…" else "TÌM") }

                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                searchPage?.let { page ->
                    val totalPages = if (page.count <= 0) 1 else (page.count + page.pageSize - 1) / page.pageSize
                    Text(
                        "${page.count} kết quả • trang ${page.page}/$totalPages",
                        fontWeight = FontWeight.SemiBold,
                    )
                    page.results.forEach { sound ->
                        HorizontalDivider(Modifier.padding(vertical = 5.dp))
                        FreesoundSearchResultRow(
                            sound = sound,
                            previewLoading = previewLoadingId == sound.id,
                            previewPlaying = previewPlayingId == sound.id,
                            onPreview = {
                                val previewUrl = sound.preferredPreviewUrl
                                if (previewUrl == null) {
                                    status = "Âm thanh này không có preview HQ khả dụng."
                                } else if (previewLoadingId == sound.id || previewPlayingId == sound.id) {
                                    stopPreview()
                                    status = "Đã dừng nghe thử."
                                } else {
                                    stopPreview()
                                    ReaderPlaybackService.command(
                                        context,
                                        ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN,
                                    )
                                    previewLoadingId = sound.id
                                    status = "Đang tải nghe thử ${sound.name}."
                                    previewPlayer.play(
                                        soundId = sound.id,
                                        previewUrl = previewUrl,
                                        onStarted = {
                                            previewLoadingId = null
                                            previewPlayingId = sound.id
                                            status = "Đang nghe thử ${sound.name}."
                                        },
                                        onStopped = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            endPlaybackPreview()
                                            status = "Đã phát xong ${sound.name}."
                                        },
                                        onError = {
                                            previewLoadingId = null
                                            previewPlayingId = null
                                            endPlaybackPreview()
                                            status = "Không thể phát preview của ${sound.name}."
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
                                onClick = { runSearch(page.page - 1) },
                                enabled = !searching && page.hasPrevious,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG TRƯỚC") }
                            Button(
                                onClick = { runSearch(page.page + 1) },
                                enabled = !searching && page.hasNext,
                                modifier = Modifier.weight(1f),
                            ) { Text("TRANG SAU") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = ::close) { Text("ĐÓNG") }
        },
    )
}

@Composable
private fun FreesoundSearchResultRow(
    sound: FreesoundSound,
    previewLoading: Boolean,
    previewPlaying: Boolean,
    onPreview: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(sound.name, fontWeight = FontWeight.SemiBold)
        Text(formatDuration(sound.durationSeconds), style = MaterialTheme.typography.bodySmall)
        if (sound.description.isNotBlank()) {
            Text(sound.description, style = MaterialTheme.typography.bodySmall)
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

private fun AudioAssetKind.toFreesoundCategory(): FreesoundCategory = when (this) {
    AudioAssetKind.MUSIC -> FreesoundCategory.MUSIC
    AudioAssetKind.AMBIENCE -> FreesoundCategory.AMBIENCE
    AudioAssetKind.SFX -> FreesoundCategory.SFX
}

private fun searchHint(kind: AudioAssetKind): String = when (kind) {
    AudioAssetKind.MUSIC -> "Ví dụ: fantasy music, battle music"
    AudioAssetKind.AMBIENCE -> "Ví dụ: forest ambience, thunderstorm"
    AudioAssetKind.SFX -> "Ví dụ: sword clash, magic spell"
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val minutes = total / 60
    val remaining = total % 60
    return if (minutes > 0) "$minutes:${remaining.toString().padStart(2, '0')}" else "$remaining giây"
}
