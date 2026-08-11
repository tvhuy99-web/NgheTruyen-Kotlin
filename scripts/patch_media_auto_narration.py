#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    source = target.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}\n--- old ---\n{old[:500]}")
    target.write_text(source.replace(old, new, 1), encoding="utf-8")


# 1) Keep all speech playback on the media stream, including rendered/Sonic speech.
service = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
replace_once(
    service,
    '''        if (ttsReady) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {''',
    '''        if (ttsReady) {
            tts.setAudioAttributes(speechAudioAttributes())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {''',
)
replace_once(
    service,
    '''                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )''',
    '''                setAudioAttributes(speechAudioAttributes())''',
)
replace_once(
    service,
    '''        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )''',
    '''        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAudioAttributes())''',
)
replace_once(
    service,
    '''    private fun requestAudioFocus(): Boolean {''',
    '''    private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestAudioFocus(): Boolean {''',
)

# 2) Expose auto narration/prefetch state to the UI.
queue = "app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt"
replace_once(
    queue,
    '''enum class PlaybackPreparationState {
    READY,
    PREPARING,
    FAILED,
}
''',
    '''enum class PlaybackPreparationState {
    READY,
    PREPARING,
    FAILED,
}

enum class NarrationAutomationStage {
    IDLE,
    CURRENT_PLANNING,
    CURRENT_APPLYING,
    CURRENT_READY,
    NEXT_LOADING,
    NEXT_PLANNING,
    NEXT_READY,
    FAILED,
}
''',
)
replace_once(
    queue,
    '''    val preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
    val preparationMessage: String? = null,
) {''',
    '''    val preparationState: PlaybackPreparationState = PlaybackPreparationState.READY,
    val preparationMessage: String? = null,
    val narrationStage: NarrationAutomationStage = NarrationAutomationStage.IDLE,
    val narrationProgress: Float = 0f,
    val narrationMessage: String? = null,
) {''',
)
replace_once(
    queue,
    '''    fun setPlaying(value: Boolean) {
        mutable.value = mutable.value.copy(isPlaying = value)
    }
''',
    '''    fun setNarrationAutomation(
        stage: NarrationAutomationStage,
        progress: Float,
        message: String?,
    ) {
        mutable.value = mutable.value.copy(
            narrationStage = stage,
            narrationProgress = progress.coerceIn(0f, 1f),
            narrationMessage = message?.take(260),
        )
    }

    fun setPlaying(value: Boolean) {
        mutable.value = mutable.value.copy(isPlaying = value)
    }
''',
)

# 3) Manual PHÂN VAI AI and automatic voice-cast use the same scene-music policy.
vm = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_once(
    vm,
    '''    fun voiceCast() = planNarrationForCurrentChapter(includeVoice = true, includeMusic = false)''',
    '''    fun voiceCast() = planNarrationForCurrentChapter(
        includeVoice = true,
        includeMusic = state.value.backgroundMusicEnabled && state.value.autoSceneMusicEnabled,
    )''',
)

# 4) Make the automatic flow observable and keep prefetch at the existing 75% chapter milestone.
replace_once(
    service,
    '''    private fun maybePrefetchNextChapter(snapshot: PlaybackSnapshot) {
        if (snapshot.progressFraction < PREFETCH_THRESHOLD) return
        if (snapshot.chapterId.isBlank() || NextChapterCache.has(snapshot.chapterId)) return
        if (prefetchParentId == snapshot.chapterId && prefetchJob?.isActive == true) return
        if (snapshot.sourceId != "offline" && snapshot.nextChapterUrl.isNullOrBlank()) return

        prefetchJob?.cancel()
        prefetchParentId = snapshot.chapterId
        prefetchJob = serviceScope.launch {
            val next = loadNextChapter(snapshot)
            if (next != null && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {
                NextChapterCache.put(snapshot.chapterId, next)
                persistPlaybackQueue(snapshot)
                maybePrefetchNarrationPlans(next, snapshot.sourceId)
            }
        }
    }
''',
    '''    private fun maybePrefetchNextChapter(snapshot: PlaybackSnapshot) {
        if (snapshot.progressFraction < PREFETCH_THRESHOLD) return
        if (snapshot.chapterId.isBlank() || NextChapterCache.has(snapshot.chapterId)) return
        if (prefetchParentId == snapshot.chapterId && prefetchJob?.isActive == true) return
        if (snapshot.sourceId != "offline" && snapshot.nextChapterUrl.isNullOrBlank()) return

        if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled) {
            PlaybackQueueStore.setNarrationAutomation(
                stage = NarrationAutomationStage.NEXT_LOADING,
                progress = 0.15f,
                message = "Đã tới 75% chương. Đang tải chương tiếp theo để phân vai trước…",
            )
        }
        prefetchJob?.cancel()
        prefetchParentId = snapshot.chapterId
        prefetchJob = serviceScope.launch {
            val next = loadNextChapter(snapshot)
            if (next != null && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {
                if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled) {
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.NEXT_PLANNING,
                        progress = 0.45f,
                        message = "Đã tải ${next.chapter.title}. Đang phân vai chương tiếp theo…",
                    )
                }
                NextChapterCache.put(snapshot.chapterId, next)
                persistPlaybackQueue(snapshot)
                maybePrefetchNarrationPlans(next, snapshot.sourceId)
            } else if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled &&
                PlaybackQueueStore.state.value.chapterId == snapshot.chapterId
            ) {
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.FAILED,
                    progress = 1f,
                    message = "Không tải được chương tiếp theo để phân vai trước.",
                )
            }
        }
    }
''',
)
replace_once(
    service,
    '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        if (!autoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
        if (narrationPreparedChapterId == snapshot.chapterId) return false
        pendingPlay = true
        PlaybackQueueStore.setPlaying(false)
        transitionMessage = "Đang phân vai và chuẩn bị nhạc nền…"
        updateMediaState()
        updateNotification()
        if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true

        narrationPlanningChapterId = snapshot.chapterId
        narrationPlanJob?.cancel()
        narrationPlanJob = serviceScope.launch {
            val warnings = runCatching {
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                    ?: error("Không tải được chương để chuẩn bị phân vai.")
                container.narrationPlanCoordinator.ensurePlans(
                    content = content,
                    voice = true,
                    music = autoSceneMusicEnabled,
                    activeTrackId = sceneMusicController.activeTrackId,
                ).warnings
            }.getOrElse { error ->
                listOf(error.message ?: "Không chuẩn bị được phân vai TTS.")
            }
            val configured = applyConfiguredVoice(useStoryProfile = true)
            withContext(Dispatchers.Main) {
                if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                narrationPreparedChapterId = snapshot.chapterId
                narrationPlanningChapterId = ""
                voiceSettingsReady = configured
                transitionMessage = warnings.firstOrNull()?.take(180)
                if (configured && pendingPlay) {
                    pendingPlay = false
                    play()
                } else {
                    updateMediaState()
                    updateNotification()
                }
            }
        }
        return true
    }
''',
    '''    private fun shouldPlanAutoSceneMusic(): Boolean = backgroundMusicEnabled && autoSceneMusicEnabled

    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        if (!autoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
        if (narrationPreparedChapterId == snapshot.chapterId) return false
        pendingPlay = true
        PlaybackQueueStore.setPlaying(false)
        PlaybackQueueStore.setNarrationAutomation(
            stage = NarrationAutomationStage.CURRENT_PLANNING,
            progress = 0.55f,
            message = "Đang kiểm tra/phân vai chương hiện tại: ${snapshot.chapterTitle}",
        )
        transitionMessage = "Đang phân vai và chuẩn bị nhạc nền…"
        updateMediaState()
        updateNotification()
        if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true

        narrationPlanningChapterId = snapshot.chapterId
        narrationPlanJob?.cancel()
        narrationPlanJob = serviceScope.launch {
            val planningAttempt = runCatching {
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                    ?: error("Không tải được chương để chuẩn bị phân vai.")
                container.narrationPlanCoordinator.ensurePlans(
                    content = content,
                    voice = true,
                    music = shouldPlanAutoSceneMusic(),
                    activeTrackId = sceneMusicController.activeTrackId,
                )
            }
            val planResult = planningAttempt.getOrNull()
            val warnings = planResult?.warnings ?: listOf(
                planningAttempt.exceptionOrNull()?.message ?: "Không chuẩn bị được phân vai TTS.",
            )
            PlaybackQueueStore.setNarrationAutomation(
                stage = NarrationAutomationStage.CURRENT_APPLYING,
                progress = 0.85f,
                message = if (planResult == null) {
                    "Phân vai tự động gặp lỗi. Đang áp dụng cấu hình giọng hiện có…"
                } else {
                    "Đã nhận kế hoạch. Đang áp dụng giọng${if (shouldPlanAutoSceneMusic()) " và nhạc cảnh" else ""}…"
                },
            )
            val configured = applyConfiguredVoice(useStoryProfile = true)
            withContext(Dispatchers.Main) {
                if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                narrationPreparedChapterId = snapshot.chapterId
                narrationPlanningChapterId = ""
                voiceSettingsReady = configured
                val created = planResult?.let { it.voicePlanCreated || it.musicPlanCreated } == true
                val musicApplied = hasSceneMusicPlan()
                val statusMessage = when {
                    planResult == null -> "Phân vai tự động lỗi; đang đọc bằng cấu hình/phân vai hiện có."
                    created -> "Đã áp dụng phân vai mới${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                    else -> "Đã áp dụng phân vai đã lưu${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                } + warnings.firstOrNull()?.takeIf(String::isNotBlank)?.let { " • ${it.take(120)}" }.orEmpty()
                PlaybackQueueStore.setNarrationAutomation(
                    stage = if (planResult == null) NarrationAutomationStage.FAILED else NarrationAutomationStage.CURRENT_READY,
                    progress = 1f,
                    message = statusMessage,
                )
                transitionMessage = warnings.firstOrNull()?.take(180)
                if (configured && pendingPlay) {
                    pendingPlay = false
                    play()
                } else {
                    updateMediaState()
                    updateNotification()
                }
            }
        }
        return true
    }
''',
)
replace_once(
    service,
    '''    private fun maybePrefetchNarrationPlans(content: ChapterContent, sourceId: String) {
        if (!prefetchNarrationPlansEnabled || !autoVoiceCastEnabled) return
        narrationPrefetchJob?.cancel()
        narrationPrefetchJob = serviceScope.launch {
            var current: ChapterContent? = content
            repeat(narrationPrefetchWindowChapters.coerceIn(1, 5)) {
                val chapter = current ?: return@repeat
                container.narrationPlanCoordinator.ensurePlans(
                    content = chapter,
                    voice = true,
                    music = autoSceneMusicEnabled,
                    activeTrackId = if (chapter.chapter.id == content.chapter.id) sceneMusicController.activeTrackId else null,
                )
                val cached = container.libraryRepository.loadNextCachedChapter(
                    storyId = chapter.chapter.storyId,
                    chapterIndex = chapter.chapter.index,
                )
                current = cached ?: run {
                    val nextUrl = chapter.nextChapterUrl?.takeIf(String::isNotBlank) ?: return@run null
                    if (sourceId == "offline") return@run null
                    val source = container.sourceRegistry.get(sourceId) ?: return@run null
                    when (val loaded = source.chapter(nextUrl)) {
                        is AppResult.Success -> NextChapterNormalizer.normalize(
                            PlaybackQueueStore.state.value.copy(
                                chapterId = chapter.chapter.id,
                                chapterIndex = chapter.chapter.index,
                                storyId = chapter.chapter.storyId,
                                sourceId = sourceId,
                            ),
                            nextUrl,
                            loaded.value,
                        ).also { container.libraryRepository.cacheChapter(it) }
                        is AppResult.Failure -> null
                    }
                }
            }
        }
    }
''',
    '''    private fun maybePrefetchNarrationPlans(content: ChapterContent, sourceId: String) {
        if (!prefetchNarrationPlansEnabled || !autoVoiceCastEnabled) return
        narrationPrefetchJob?.cancel()
        narrationPrefetchJob = serviceScope.launch {
            var current: ChapterContent? = content
            repeat(narrationPrefetchWindowChapters.coerceIn(1, 5)) { offset ->
                val chapter = current ?: return@repeat
                val attempt = runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = true,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
                val result = attempt.getOrNull()
                if (offset == 0) {
                    val failed = result == null || (
                        result.warnings.isNotEmpty() && !result.voicePlanCreated && !result.musicPlanCreated
                    )
                    val musicLabel = if (shouldPlanAutoSceneMusic()) " + nhạc cảnh" else ""
                    val baseMessage = when {
                        result == null -> "Không phân vai trước được chương tiếp theo: ${chapter.chapter.title}."
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}."
                        result.voicePlanCreated || result.musicPlanCreated ->
                            "Đã phân vai$musicLabel chương tiếp theo: ${chapter.chapter.title}."
                        else -> "Chương tiếp theo đã có phân vai$musicLabel hợp lệ: ${chapter.chapter.title}."
                    }
                    val warning = result?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                        ?: attempt.exceptionOrNull()?.message
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = if (failed) NarrationAutomationStage.FAILED else NarrationAutomationStage.NEXT_READY,
                        progress = 1f,
                        message = baseMessage + warning?.let { " • ${it.take(120)}" }.orEmpty(),
                    )
                    if (failed) return@launch
                } else if (result == null) {
                    return@launch
                }
                val cached = container.libraryRepository.loadNextCachedChapter(
                    storyId = chapter.chapter.storyId,
                    chapterIndex = chapter.chapter.index,
                )
                current = cached ?: run {
                    val nextUrl = chapter.nextChapterUrl?.takeIf(String::isNotBlank) ?: return@run null
                    if (sourceId == "offline") return@run null
                    val source = container.sourceRegistry.get(sourceId) ?: return@run null
                    when (val loaded = source.chapter(nextUrl)) {
                        is AppResult.Success -> NextChapterNormalizer.normalize(
                            PlaybackQueueStore.state.value.copy(
                                chapterId = chapter.chapter.id,
                                chapterIndex = chapter.chapter.index,
                                storyId = chapter.chapter.storyId,
                                sourceId = sourceId,
                            ),
                            nextUrl,
                            loaded.value,
                        ).also { container.libraryRepository.cacheChapter(it) }
                        is AppResult.Failure -> null
                    }
                }
            }
        }
    }
''',
)

# 5) Reader status bar: make automatic planning visible instead of invisible background work.
reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
replace_once(
    reader,
    '''import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme''',
    '''import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme''',
)
replace_once(
    reader,
    '''            Row(Modifier.fillMaxWidth()) {
                ReaderButton("XEM NHẬT KÝ", { showDiagnosticLogDialog = true }, Modifier.weight(1f), normalColor = ReferenceGray)
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }''',
    '''            if (state.autoVoiceCastEnabled) {
                val autoNarrationStatus = state.playback.narrationMessage ?: if (state.prefetchNarrationPlansEnabled) {
                    "Tự phân vai đang bật. Từ 75% chương, ứng dụng sẽ tải và phân vai trước chương tiếp theo."
                } else {
                    "Tự phân vai đang bật. Tải/phân vai trước chương tiếp theo đang tắt trong cài đặt."
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("TỰ PHÂN VAI", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { state.playback.narrationProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(autoNarrationStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ReaderButton("XEM NHẬT KÝ", { showDiagnosticLogDialog = true }, Modifier.weight(1f), normalColor = ReferenceGray)
                ReaderButton(if (state.aiBusy) "AI ĐANG CHẠY…" else if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION) "DỊCH LẠI" else "DỊCH AI", onAiTranslate, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = ReferencePurple)
                ReaderButton("PHÂN VAI AI", onVoiceCast, Modifier.weight(1f), enabled = !state.aiBusy, normalColor = Color(0xFFAF52DE))
            }''',
)

print("PATCH_MEDIA_AUTO_NARRATION_OK")
