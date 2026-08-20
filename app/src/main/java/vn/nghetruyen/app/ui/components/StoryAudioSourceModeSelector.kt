package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.playback.ReaderPlaybackService

/** One selector owns the mutually-exclusive story-audio source mode; three switches cannot conflict. */
@Composable
fun StoryAudioSourceModeSelector(
    modifier: Modifier = Modifier,
    onModeChanged: (StoryAudioSourceMode) -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as NgheTruyenApplication
    val store = application.container.storyAudioSourceModeStore
    val scope = rememberCoroutineScope()
    var current by remember(store) { mutableStateOf(store.get()) }
    var expanded by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }

    fun changeMode(mode: StoryAudioSourceMode) {
        if (changing || mode == current) {
            expanded = false
            return
        }
        expanded = false
        changing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                store.set(mode)
                val settingsRepository = application.container.settingsRepository
                val audioPreferences = AudioDirectionPreferences.shared(application)
                if (mode != StoryAudioSourceMode.LOCAL_MANUAL) {
                    // The manual background-music toggle is hidden in AI modes. Keep the shared
                    // runtime gate open so Mode 2/3 music plans cannot be disabled by stale Mode-1 state.
                    settingsRepository.setBackgroundMusicEnabled(true)
                }
                if (mode == StoryAudioSourceMode.AI_FREESOUND) {
                    // Mode 3 previously allowed all three story-audio layers to remain OFF. In that
                    // state the unified AI request legitimately contained voice-cast only, which looked
                    // exactly like a broken Freesound runtime: voices were assigned but no search ran.
                    // Activate the intended Mode-3 defaults only when the user has no active audio layer;
                    // an explicit partial selection (for example SFX-only) is preserved.
                    val appSettings = settingsRepository.snapshot()
                    val audioSettings = audioPreferences.snapshot()
                    if (!appSettings.autoSceneMusicEnabled &&
                        !audioSettings.ambienceEnabled &&
                        !audioSettings.soundEffectsEnabled
                    ) {
                        settingsRepository.setAutoSceneMusicEnabled(true)
                        audioPreferences.setAmbienceEnabled(true)
                        audioPreferences.setSoundEffectsEnabled(true)
                    }
                }
                // Standard runtime transforms are source-mode specific. Remove only AUDIO transforms;
                // voice-cast transforms remain untouched. The Mode-3 requirement marker is retained so
                // switching back can reuse its query/cache without another AI call when still current.
                val dao = application.container.database.chapterTransformDao()
                dao.listAll()
                    .filter {
                        it.kind == ChapterAiWorkflow.KIND_SCENE_MUSIC ||
                            it.kind == NarrationPlanCoordinator.KIND_AUDIO_DIRECTION
                    }
                    .forEach { dao.delete(it.chapterId, it.kind) }
            }
            current = mode
            changing = false
            onModeChanged(mode)
            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text("CHẾ ĐỘ ÂM THANH TRUYỆN", style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth()) {
            Button(
                onClick = { expanded = true },
                enabled = !changing,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Chế độ âm thanh truyện hiện tại: ${current.label}. ${current.description}"
                },
            ) {
                Text(if (changing) "ĐANG CHUYỂN CHẾ ĐỘ…" else "${current.label} ▼")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                StoryAudioSourceMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text((if (mode == current) "✓ " else "") + mode.label) },
                        onClick = { changeMode(mode) },
                        modifier = Modifier.semantics {
                            contentDescription = "${mode.label}. ${mode.description}"
                        },
                    )
                }
            }
        }
        Text(current.description, style = MaterialTheme.typography.bodySmall)
        if (current == StoryAudioSourceMode.AI_FREESOUND) {
            Text(
                "Freesound chỉ dùng để tìm và tải trước. Khi đọc truyện, Mode 3 chỉ phát các file Freesound đã resolve cho kế hoạch AI hiện tại; không dùng playlist tuần tự/ngẫu nhiên của Mode 1. Nếu trước khi vào Mode 3 cả ba lớp âm thanh đều đang tắt, ứng dụng sẽ tự bật MUSIC + AMBIENCE + SFX để tránh trạng thái chỉ phân vai giọng nhưng không hề yêu cầu âm thanh.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}