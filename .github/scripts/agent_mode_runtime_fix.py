from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def ensure_import(text: str, anchor: str, import_line: str, label: str) -> str:
    if import_line in text:
        return text
    return replace_once(text, anchor, anchor + import_line, label)


def patch_reader_screen() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
    text = read(path)
    text = ensure_import(
        text,
        "import vn.nghetruyen.app.audio.AudioAssetKind\n",
        "import vn.nghetruyen.app.audio.StoryAudioSourceMode\n",
        "ReaderScreen StoryAudioSourceMode import",
    )
    if "var storyAudioSourceMode by remember" not in text:
        text = replace_once(
            text,
            "    var musicReleaseMs by remember { mutableIntStateOf(2050) }\n",
            "    var musicReleaseMs by remember { mutableIntStateOf(2050) }\n"
            "    var storyAudioSourceMode by remember { mutableStateOf(app.container.storyAudioSourceModeStore.get()) }\n",
            "ReaderScreen source-mode state",
        )
    if "storyAudioSourceMode = app.container.storyAudioSourceModeStore.get()" not in text:
        text = replace_once(
            text,
            "        if (showMusicDialog) {\n            val settings = app.container.settingsRepository.snapshot()\n",
            "        if (showMusicDialog) {\n            storyAudioSourceMode = app.container.storyAudioSourceModeStore.get()\n"
            "            val settings = app.container.settingsRepository.snapshot()\n",
            "ReaderScreen source-mode refresh",
        )

    if "val manualMode = storyAudioSourceMode == StoryAudioSourceMode.LOCAL_MANUAL" not in text:
        old = '''    if (showMusicDialog) {
        var musicModeExpanded by remember { mutableStateOf(false) }
        val musicTracks = state.sceneMusicTracks.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("NHẠC NỀN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật nhạc nền", Modifier.weight(1f))
                    Switch(musicEnabled, { musicEnabled = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                vn.nghetruyen.app.ui.components.AudioDirectionLayerSwitches(
                    musicTrackCount = musicTracks.size,
                    onManageMusic = {
                        val rows = musicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                        musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                        musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                        musicSearch = ""
                        showMusicLibrary = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Chế độ phát", fontWeight = FontWeight.SemiBold)
                Button(onClick = { musicModeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "Phát ngẫu nhiên" else "Phát lần lượt")
                }
                DropdownMenu(expanded = musicModeExpanded, onDismissRequest = { musicModeExpanded = false }) {
                    DropdownMenuItem(text = { Text("Phát lần lượt") }, onClick = { musicMode = SceneMusicPlaybackMode.SEQUENTIAL; musicModeExpanded = false })
                    DropdownMenuItem(text = { Text("Phát ngẫu nhiên") }, onClick = { musicMode = SceneMusicPlaybackMode.SHUFFLE; musicModeExpanded = false })
                }
                ReaderFloatSlider("Giảm nhạc khi giọng đọc phát", musicDuckDb, 0f, 24f, steps = 23, shown = { "%.0f dB".format(it) }) { musicDuckDb = it }
            } },
            confirmButton = { TextButton(onClick = {
                val activeCount = musicTracks.count { it.enabled }
                if (musicEnabled && activeCount == 0) onMessage("Hãy bật ít nhất một bài trong danh sách nhạc trước.")
                else scope.launch {
                    val settings = app.container.settingsRepository
                    settings.setBackgroundMusicEnabled(musicEnabled)
                    settings.setSceneMusicPlaybackMode(musicMode)
                    settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                    settings.setBackgroundMusicAttackMillis(musicAttackMs)
                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                    onMessage("Đã lưu cài đặt nhạc nền."); showMusicDialog = false
                }
            }) { Text("LƯU CÀI ĐẶT") } },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }
'''
        new = '''    if (showMusicDialog) {
        var musicModeExpanded by remember { mutableStateOf(false) }
        val musicTracks = state.sceneMusicTracks.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        val manualMode = storyAudioSourceMode == StoryAudioSourceMode.LOCAL_MANUAL
        AlertDialog(
            onDismissRequest = { showMusicDialog = false },
            title = { Text("ÂM THANH TRUYỆN") },
            text = { Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                vn.nghetruyen.app.ui.components.AudioDirectionLayerSwitches(
                    musicTrackCount = musicTracks.size,
                    onManageMusic = {
                        val rows = musicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                        musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                        musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                        musicSearch = ""
                        showMusicLibrary = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onSourceModeChanged = { mode ->
                        storyAudioSourceMode = mode
                        musicModeExpanded = false
                    },
                )
                if (manualMode) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật nhạc nền thủ công", Modifier.weight(1f))
                        Switch(musicEnabled, { musicEnabled = it })
                    }
                    Text("Chế độ phát", fontWeight = FontWeight.SemiBold)
                    Button(onClick = { musicModeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "Phát ngẫu nhiên" else "Phát lần lượt")
                    }
                    DropdownMenu(expanded = musicModeExpanded, onDismissRequest = { musicModeExpanded = false }) {
                        DropdownMenuItem(text = { Text("Phát lần lượt") }, onClick = { musicMode = SceneMusicPlaybackMode.SEQUENTIAL; musicModeExpanded = false })
                        DropdownMenuItem(text = { Text("Phát ngẫu nhiên") }, onClick = { musicMode = SceneMusicPlaybackMode.SHUFFLE; musicModeExpanded = false })
                    }
                    ReaderFloatSlider("Giảm nhạc khi giọng đọc phát", musicDuckDb, 0f, 24f, steps = 23, shown = { "%.0f dB".format(it) }) { musicDuckDb = it }
                }
            } },
            confirmButton = { TextButton(onClick = {
                if (!manualMode) {
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                    onMessage("Đã áp dụng ${storyAudioSourceMode.label}.")
                    showMusicDialog = false
                } else {
                    val activeCount = musicTracks.count { it.enabled }
                    if (musicEnabled && activeCount == 0) onMessage("Hãy bật ít nhất một bài trong danh sách nhạc trước.")
                    else scope.launch {
                        val settings = app.container.settingsRepository
                        settings.setBackgroundMusicEnabled(musicEnabled)
                        settings.setSceneMusicPlaybackMode(musicMode)
                        settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                        settings.setBackgroundMusicAttackMillis(musicAttackMs)
                        settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                        onMessage("Đã lưu cài đặt phát thủ công."); showMusicDialog = false
                    }
                }
            }) { Text(if (manualMode) "LƯU CÀI ĐẶT" else "ÁP DỤNG") } },
            dismissButton = { TextButton(onClick = { showMusicDialog = false }) { Text("ĐÓNG") } },
        )
    }
'''
        text = replace_once(text, old, new, "ReaderScreen strict mode dialog")
    write(path, text)


def patch_audio_direction_controls() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt"
    text = read(path)
    if "KHO NHẠC ĐÃ TẢI / FALLBACK" in text:
        old = '''                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                AudioManagerButton(
                    label = "CHUẨN HÓA / BẢO TRÌ FILE ÂM THANH MODE 3",
                    enabled = tracks.isNotEmpty(),
                    onClick = { openNormalization(AudioAssetKind.entries.toSet()) },
                )
                AudioManagerButton(
                    label = "KHO NHẠC ĐÃ TẢI / FALLBACK ($musicTrackCount)",
                    onClick = { managerKind = AudioAssetKind.MUSIC },
                )
                AudioManagerButton(
                    label = "KHO MÔI TRƯỜNG ĐÃ TẢI / FALLBACK (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }})",
                    onClick = { managerKind = AudioAssetKind.AMBIENCE },
                )
                AudioManagerButton(
                    label = "KHO SFX ĐÃ TẢI / FALLBACK (${tracks.count { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }})",
                    onClick = { managerKind = AudioAssetKind.SFX },
                )
'''
        text = replace_once(
            text,
            old,
            '                Text("File Freesound được tìm, tải và chuẩn hóa tự động theo kế hoạch. Mode 3 không mở kho local của Mode 1/2 để chọn hoặc fallback.")\n',
            "Mode-3 local fallback UI",
        )
    write(path, text)


def patch_playback_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
    text = read(path)
    text = ensure_import(text, "import vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n", "import vn.nghetruyen.app.ai.XpkSceneMusicParity\n", "ReaderPlayback XpkSceneMusicParity import")
    text = ensure_import(text, "import vn.nghetruyen.app.audio.AudioAssetKind\n", "import vn.nghetruyen.app.audio.StoryAudioModeRouter\nimport vn.nghetruyen.app.audio.StoryAudioSourceMode\nimport vn.nghetruyen.app.audio.AudioDirectionPreferences\n", "ReaderPlayback mode imports")
    text = ensure_import(text, "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n", "import org.json.JSONObject\n", "ReaderPlayback JSONObject import")

    if "private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL" not in text:
        text = replace_once(
            text,
            "    private var backgroundMusicEnabled = false\n",
            "    private var backgroundMusicEnabled = false\n    private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL\n",
            "ReaderPlayback source-mode state",
        )

    if "val previousStoryAudioSourceMode = storyAudioSourceMode" not in text:
        simple = "        storyAudioSourceMode = container.storyAudioSourceModeStore.get()\n"
        reset = '''        val previousStoryAudioSourceMode = storyAudioSourceMode
        storyAudioSourceMode = container.storyAudioSourceModeStore.get()
        if (previousStoryAudioSourceMode != storyAudioSourceMode) {
            narrationPlanJob?.cancel()
            narrationPrefetchJob?.cancel()
            narrationPlanningChapterId = ""
            narrationPreparedChapterId = ""
            backgroundMusicShuffleBag.clear()
        }
'''
        if simple in text:
            text = replace_once(text, simple, reset, "ReaderPlayback source-mode reset")
        else:
            text = replace_once(
                text,
                "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
                "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n" + reset +
                "        val storyId = PlaybackQueueStore.state.value.storyId\n",
                "ReaderPlayback source-mode refresh",
            )

    old_plan = "        val musicPlanUsable = backgroundMusicEnabled && autoSceneMusicEnabled && musicPlan?.sourceSha256 == musicSourceHash\n"
    new_plan = '''        val musicPlanUsable = !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) &&
            autoSceneMusicEnabled &&
            musicPlan?.sourceSha256 == musicSourceHash &&
            scenePlanMatchesCurrentSourceMode(musicPlan.transformedText)
'''
    if old_plan in text:
        text = replace_once(text, old_plan, new_plan, "ReaderPlayback AI plan source gate")

    if "private fun scenePlanMatchesCurrentSourceMode" not in text:
        text = replace_once(
            text,
            "    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()\n\n",
            '''    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()

    private fun scenePlanMatchesCurrentSourceMode(transformedText: String): Boolean = runCatching {
        JSONObject(transformedText).optString("audio_source_mode") == storyAudioSourceMode.name
    }.getOrDefault(false)

''',
            "ReaderPlayback source-mode plan helper",
        )

    old_fallback = '''            if (!hasSceneMusicPlan()) {
                if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) {
                    configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                    val activeId = sceneMusicController.activeTrackId
                    if (activeId != null && backgroundMusicTracks.none { it.id == activeId }) {
                        sceneMusicController.stop(clearTrack = true)
                    }
                    if (PlaybackQueueStore.state.value.isPlaying) ensureBackgroundPlaylist(advance = false)
                    else sceneMusicController.pause()
                } else {
                    sceneMusicController.stop(clearTrack = true)
                    configureBackgroundMusic(
                        settings.backgroundMusicUri,
                        settings.backgroundMusicEnabled,
                        settings.backgroundMusicVolume,
                        settings.backgroundMusicDuckFactor,
                    )
                }
            } else {
'''
    new_fallback = '''            if (!hasSceneMusicPlan()) {
                if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) {
                    if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) {
                        configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                        val activeId = sceneMusicController.activeTrackId
                        if (activeId != null && backgroundMusicTracks.none { it.id == activeId }) {
                            sceneMusicController.stop(clearTrack = true)
                        }
                        if (PlaybackQueueStore.state.value.isPlaying) ensureBackgroundPlaylist(advance = false)
                        else sceneMusicController.pause()
                    } else {
                        sceneMusicController.stop(clearTrack = true)
                        activeSceneTrackId = null
                        configureBackgroundMusic(
                            settings.backgroundMusicUri,
                            settings.backgroundMusicEnabled,
                            settings.backgroundMusicVolume,
                            settings.backgroundMusicDuckFactor,
                        )
                    }
                } else {
                    sceneMusicController.stop(clearTrack = true)
                    activeSceneTrackId = null
                    configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                }
            } else {
'''
    if old_fallback in text:
        text = replace_once(text, old_fallback, new_fallback, "ReaderPlayback no-plan fallback gate")

    old_no_plan = '''        if (!hasSceneMusicPlan()) {
            if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) ensureBackgroundPlaylist(advance = false)
            return
        }
'''
    new_no_plan = '''        if (!hasSceneMusicPlan()) {
            if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&
                backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()
            ) {
                ensureBackgroundPlaylist(advance = false)
            } else if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) {
                sceneMusicController.stop(clearTrack = true)
                activeSceneTrackId = null
            }
            return
        }
'''
    if old_no_plan in text:
        text = replace_once(text, old_no_plan, new_no_plan, "ReaderPlayback no-plan unit gate")

    silence_marker = "        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {\n"
    if silence_marker not in text:
        text = replace_once(
            text,
            "        val snapshot = PlaybackQueueStore.state.value\n        val track = requestedTrackId?.let { selectedTrackId ->\n",
            '''        val snapshot = PlaybackQueueStore.state.value
        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {
            sceneMusicController.stop(clearTrack = true)
            activeSceneTrackId = null
            transitionMessage = null
            return
        }
        val track = requestedTrackId?.let { selectedTrackId ->
''',
            "ReaderPlayback intentional silence",
        )

    if "if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false" not in text:
        text = replace_once(
            text,
            "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
            "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
            "ReaderPlayback manual playlist entry",
        )

    old_completion = "                    if (PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()) {\n"
    if old_completion in text:
        text = replace_once(
            text,
            old_completion,
            "                    if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n                        PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()\n                    ) {\n",
            "ReaderPlayback manual playlist completion",
        )

    old_uses = "        val usesTrackLibrary = backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n"
    if old_uses in text:
        text = replace_once(
            text,
            old_uses,
            "        val usesTrackLibrary = StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n            backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
            "ReaderPlayback manual track-library gate",
        )

    old_should = "    private fun shouldPlanAutoSceneMusic(): Boolean = backgroundMusicEnabled && autoSceneMusicEnabled\n"
    new_should = '''    private fun shouldPlanAutoSceneMusic(): Boolean =
        !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled

    private fun shouldPlanAutoStoryAudio(): Boolean {
        if (StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode)) return false
        val audio = AudioDirectionPreferences.shared(this).snapshot()
        return autoSceneMusicEnabled || audio.ambienceEnabled || audio.soundEffectsEnabled
    }
'''
    if old_should in text:
        text = replace_once(text, old_should, new_should, "ReaderPlayback story-audio planning gate")
    elif "private fun shouldPlanAutoStoryAudio()" not in text:
        current_should = '''    private fun shouldPlanAutoSceneMusic(): Boolean =
        !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled
'''
        text = replace_once(text, current_should, new_should, "ReaderPlayback add all-audio planning gate")

    start = text.find("    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {")
    end = text.find("    private fun maybePrefetchNarrationPlans(", start)
    if start < 0 or end < 0:
        raise SystemExit("ReaderPlayback prepareCurrentNarrationBeforePlayback markers not found")
    current_prepare = text[start:end]
    if "val planAudio = shouldPlanAutoStoryAudio()" not in current_prepare:
        replacement = '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        val planVoice = currentStoryAutoVoiceCastEnabled
        val planMusic = shouldPlanAutoSceneMusic()
        val planAudio = shouldPlanAutoStoryAudio()
        if ((!planVoice && !planAudio) || snapshot.chapterId.isBlank()) return false
        if (narrationPreparedChapterId == snapshot.chapterId) return false
        pendingPlay = true
        PlaybackQueueStore.setPlaying(false)
        if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true

        narrationPlanningChapterId = snapshot.chapterId
        narrationPlanJob?.cancel()
        narrationPlanJob = serviceScope.launch {
            var attempt = 0
            while (PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {
                if (planVoice && !currentStoryAutoVoiceCastEnabled) break
                if (!planVoice && !shouldPlanAutoStoryAudio()) break
                attempt += 1
                val taskLabel = when {
                    planVoice && planAudio -> "phân vai và lập kế hoạch âm thanh"
                    planVoice -> "phân vai"
                    else -> "lập kế hoạch âm thanh AI"
                }
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.35f,
                    message = if (attempt == 1) "Đang $taskLabel cho chương hiện tại." else "Đang thử $taskLabel lại lần $attempt.",
                )
                transitionMessage = "Đang chuẩn bị kế hoạch kể chuyện…"
                withContext(Dispatchers.Main) {
                    updateMediaState()
                    updateNotification()
                }

                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planningAttempt = if (content == null) {
                    Result.failure(IllegalStateException("Không tải được chương để chuẩn bị kế hoạch."))
                } else {
                    runCatching {
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = planVoice,
                            music = planMusic,
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
                }
                val planResult = planningAttempt.getOrNull()
                val warnings = planResult?.warnings ?: listOf(
                    planningAttempt.exceptionOrNull()?.message ?: "Không chuẩn bị được kế hoạch kể chuyện.",
                )
                val assignmentCount = if (!planVoice || content == null) 0
                else container.narrationPlanCoordinator.voicePlanAssignmentCount(content)
                val voiceReady = !planVoice || assignmentCount > 0

                if (planResult != null && voiceReady) {
                    val configured = applyConfiguredVoice(useStoryProfile = true)
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        narrationPreparedChapterId = snapshot.chapterId
                        narrationPlanningChapterId = ""
                        voiceSettingsReady = configured
                        val musicApplied = hasSceneMusicPlan()
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.CURRENT_READY,
                            progress = 1f,
                            message = when {
                                planVoice && planAudio -> "Đã phân vai xong $assignmentCount mục và chuẩn bị âm thanh. Đang bắt đầu phát."
                                planVoice -> "Đã phân vai xong $assignmentCount mục. Đang bắt đầu phát."
                                musicApplied -> "Đã chuẩn bị âm thanh AI và áp dụng nhạc cảnh. Đang bắt đầu phát."
                                else -> "Đã chuẩn bị âm thanh AI; các lớp không có asset phù hợp sẽ giữ im lặng."
                            },
                        )
                        transitionMessage = null
                        if (configured && pendingPlay) {
                            pendingPlay = false
                            play()
                        } else {
                            updateMediaState()
                            updateNotification()
                        }
                    }
                    return@launch
                }

                if (!planVoice) {
                    val configured = applyConfiguredVoice(useStoryProfile = true)
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        narrationPreparedChapterId = snapshot.chapterId
                        narrationPlanningChapterId = ""
                        voiceSettingsReady = configured
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.CURRENT_READY,
                            progress = 1f,
                            message = "Không tạo được kế hoạch âm thanh AI; tiếp tục phát với các lớp tương ứng im lặng.",
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
                    return@launch
                }

                val castDisabled = warnings.any { warning ->
                    warning.contains("phân vai TTS đang tắt", ignoreCase = true)
                }
                if (castDisabled) {
                    withContext(Dispatchers.Main) {
                        narrationPlanningChapterId = ""
                        pendingPlay = false
                        PlaybackQueueStore.setPlaying(false)
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.FAILED,
                            progress = 1f,
                            message = "Phân vai TTS đang tắt cho truyện này. Chưa tự động phát.",
                        )
                        transitionMessage = warnings.firstOrNull()?.take(180)
                        updateMediaState()
                        updateNotification()
                    }
                    return@launch
                }

                val warningSuffix = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                    ?.let { " ${it.take(120)}" }
                    .orEmpty()
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    PlaybackQueueStore.setPlaying(false)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.$warningSuffix",
                    )
                    transitionMessage = "Phân vai chưa thành công; đang chờ thử lại."
                    updateMediaState()
                    updateNotification()
                }
                delay(NARRATION_RETRY_DELAY_MS)
            }
            narrationPlanningChapterId = ""
        }
        return true
    }

'''
        text = text[:start] + replacement + text[end:]
    write(path, text)


def patch_audio_direction_runtime() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt"
    text = read(path)
    text = ensure_import(text, "import vn.nghetruyen.app.audio.SoundEffectCue\n", "import vn.nghetruyen.app.audio.StoryAudioModeRouter\n", "AudioDirectionRuntime mode import")
    if "val sourceMode = narrationPlanCoordinator.storyAudioSourceMode()" not in text:
        text = replace_once(
            text,
            "        val now = System.currentTimeMillis()\n        val fastKey = buildFastKey(snapshot, settings)\n",
            "        val now = System.currentTimeMillis()\n        val sourceMode = narrationPlanCoordinator.storyAudioSourceMode()\n"
            "        val fastKey = buildFastKey(snapshot, settings) + \"|mode=${sourceMode.name}\"\n",
            "AudioDirectionRuntime mode-aware fast key",
        )
    elif 'buildFastKey(snapshot, settings) + "|mode=${sourceMode.name}"' not in text:
        text = replace_once(
            text,
            "        val fastKey = buildFastKey(snapshot, settings)\n",
            "        val fastKey = buildFastKey(snapshot, settings) + \"|mode=${sourceMode.name}\"\n",
            "AudioDirectionRuntime fast-key mode",
        )
    if 'signatureParts += "mode=${sourceMode.name}"' not in text:
        text = replace_once(
            text,
            '        signatureParts += "chapter=${snapshot.chapterId}"\n',
            '        signatureParts += "chapter=${snapshot.chapterId}"\n        signatureParts += "mode=${sourceMode.name}"\n',
            "AudioDirectionRuntime signature mode",
        )
    old_empty = "        if (activeAudioAssets.isEmpty()) {\n"
    new_empty = "        if (activeAudioAssets.isEmpty() && !StoryAudioModeRouter.usesAiFreesound(sourceMode)) {\n"
    if old_empty in text:
        text = replace_once(text, old_empty, new_empty, "AudioDirectionRuntime Mode-3 empty library")
    post_import = "            if (StoryAudioModeRouter.usesAiFreesound(sourceMode) && outcome.freesoundResolvedAssets > 0) {\n"
    if post_import not in text:
        text = replace_once(
            text,
            '''            if (outcome == null) {
                markFailure(signature)
                return false
            }
            plan = narrationPlanCoordinator.loadAudioDirectionPlan(content)
''',
            '''            if (outcome == null) {
                markFailure(signature)
                return false
            }
            if (StoryAudioModeRouter.usesAiFreesound(sourceMode) && outcome.freesoundResolvedAssets > 0) {
                clearPreparedPlan()
                return ensurePlan(snapshot, settings, force = false)
            }
            plan = narrationPlanCoordinator.loadAudioDirectionPlan(content)
''',
            "AudioDirectionRuntime post-Freesound refresh",
        )
    write(path, text)


def patch_freesound_resolver() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
    text = read(path)
    text = text.replace(
        "private enum class FreesoundAutoResolutionSource { CACHE, FREESOUND, LOCAL_FALLBACK, UNRESOLVED }",
        "private enum class FreesoundAutoResolutionSource { CACHE, FREESOUND, UNRESOLVED }",
    )
    text = text.replace(
        " * network search/import -> semantic local fallback -> silence. This keeps Freesound as the primary\n"
        " * source without downloading the same need again in later chapters.\n",
        " * network search/import -> silence. Mode 3 never substitutes a normal Mode-2 local-library asset.\n"
        " * Previously resolved Freesound assets may still be reused from the query cache.\n",
    )
    if "val fallbackTracks =" in text:
        old = '''            val fallbackTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                .filter { it.enabled && AudioAssetClassifier.classify(it) == need.kind }
            val local = fallbackTracks
                .map { it to FreesoundLibraryAnalyzer.coverageScore(need.query, listOf(it)) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= LOCAL_FALLBACK_MIN_SCORE }
                ?.first
            if (local != null) {
                resolutions += FreesoundAutoResolvedNeed(need, local.id, FreesoundAutoResolutionSource.LOCAL_FALLBACK.name)
                warnings += "Freesound không giải quyết được ‘${need.query}’; đã dùng asset local ‘${local.title}’."
            } else {
                resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
                val prefix = if (need.importance == FreesoundRequirementImportance.REQUIRED) "Âm thanh quan trọng" else "Âm thanh tùy chọn"
                warnings += "$prefix ‘${need.query}’ chưa tìm được; khoảng đó sẽ im lặng ở lớp tương ứng."
            }
'''
        new = '''            resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
            val prefix = if (need.importance == FreesoundRequirementImportance.REQUIRED) "Âm thanh quan trọng" else "Âm thanh tùy chọn"
            warnings += "$prefix ‘${need.query}’ chưa tìm được trên Freesound; khoảng đó sẽ im lặng ở lớp tương ứng."
'''
        text = replace_once(text, old, new, "Freesound strict no-local-fallback")
    text = text.replace("        private const val LOCAL_FALLBACK_MIN_SCORE = 0.62\n", "")
    write(path, text)


def patch_xpk_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt"
    text = read(path)
    if "AI_AUDIO_SOURCE_MODE_MIXED" not in text:
        needle = '''        if (request.includeFreesoundAudioRequirements && request.freesoundRequirementKinds.isEmpty()) {
            return failure("AI_FREESOUND_KINDS_EMPTY", "Chế độ Freesound tự động chưa bật lớp âm thanh nào.")
        }
'''
        replacement = needle + '''        if (
            request.includeFreesoundAudioRequirements &&
            (request.includeSceneMusic || request.includeAmbience || request.includeSoundEffects)
        ) {
            return failure(
                "AI_AUDIO_SOURCE_MODE_MIXED",
                "Mode 3 không được gửi catalog MUSIC/AMBIENCE/SFX local hoặc dùng bộ quy tắc chọn track_id của Mode 2.",
            )
        }
'''
        text = replace_once(text, needle, replacement, "XPK mixed audio-source contract")
    write(path, text)


def patch_coordinator() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
    text = read(path)
    if "validatedAmbience" not in text or "validatedSfx" not in text:
        old = '''            var candidate = AmbienceSfxPlan(
                ambienceScenes = if (AudioAssetKind.AMBIENCE in kinds) FreesoundAutoPlanBuilder.ambienceScenes(resolved.resolved) else emptyList(),
                soundEffectCues = if (AudioAssetKind.SFX in kinds) FreesoundAutoPlanBuilder.soundEffectCues(resolved.resolved) else emptyList(),
            )
            var validated: AmbienceSfxPlan? = null
            while (validated == null) {
                validated = runCatching {
                    XpkAmbienceSfxDirector.parseAndValidate(
                        XpkAmbienceSfxDirector.encode(candidate),
                        validUnitIds = unitIds,
                        validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                        validSfxIds = sfxTracks.map(SceneMusicTrackEntity::id).toSet(),
                        ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                        soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                    )
                }.getOrNull()
                if (validated != null) break
                candidate = when {
                    candidate.soundEffectCues.isNotEmpty() -> candidate.copy(soundEffectCues = candidate.soundEffectCues.dropLast(1))
                    candidate.ambienceScenes.isNotEmpty() -> candidate.copy(ambienceScenes = candidate.ambienceScenes.dropLast(1))
                    else -> AmbienceSfxPlan()
                }
                if (candidate.ambienceScenes.isEmpty() && candidate.soundEffectCues.isEmpty()) {
                    validated = AmbienceSfxPlan()
                }
            }
'''
        new = '''            var ambienceCandidate = if (AudioAssetKind.AMBIENCE in kinds) {
                FreesoundAutoPlanBuilder.ambienceScenes(resolved.resolved)
            } else emptyList()
            val originalAmbienceCount = ambienceCandidate.size
            var validatedAmbience = AmbienceSfxPlan()
            while (true) {
                val parsed = runCatching {
                    XpkAmbienceSfxDirector.parseAndValidate(
                        XpkAmbienceSfxDirector.encode(AmbienceSfxPlan(ambienceScenes = ambienceCandidate)),
                        validUnitIds = unitIds,
                        validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                        validSfxIds = emptySet(),
                        ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                        soundEffectsEnabled = false,
                    )
                }.getOrNull()
                if (parsed != null) {
                    validatedAmbience = parsed
                    break
                }
                if (ambienceCandidate.isEmpty()) break
                ambienceCandidate = ambienceCandidate.dropLast(1)
            }
            if (validatedAmbience.ambienceScenes.size < originalAmbienceCount) {
                warnings += "Đã loại ${originalAmbienceCount - validatedAmbience.ambienceScenes.size} cue AMBIENCE không hợp lệ; SFX được giữ nguyên."
            }

            var sfxCandidate = if (AudioAssetKind.SFX in kinds) {
                FreesoundAutoPlanBuilder.soundEffectCues(resolved.resolved)
            } else emptyList()
            val originalSfxCount = sfxCandidate.size
            var validatedSfx = AmbienceSfxPlan()
            while (true) {
                val parsed = runCatching {
                    XpkAmbienceSfxDirector.parseAndValidate(
                        XpkAmbienceSfxDirector.encode(AmbienceSfxPlan(soundEffectCues = sfxCandidate)),
                        validUnitIds = unitIds,
                        validAmbienceIds = emptySet(),
                        validSfxIds = sfxTracks.map(SceneMusicTrackEntity::id).toSet(),
                        ambienceEnabled = false,
                        soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                    )
                }.getOrNull()
                if (parsed != null) {
                    validatedSfx = parsed
                    break
                }
                if (sfxCandidate.isEmpty()) break
                sfxCandidate = sfxCandidate.dropLast(1)
            }
            if (validatedSfx.soundEffectCues.size < originalSfxCount) {
                warnings += "Đã loại ${originalSfxCount - validatedSfx.soundEffectCues.size} cue SFX không hợp lệ; AMBIENCE được giữ nguyên."
            }
            val validated = AmbienceSfxPlan(
                ambienceScenes = validatedAmbience.ambienceScenes,
                soundEffectCues = validatedSfx.soundEffectCues,
            )
'''
        text = replace_once(text, old, new, "Coordinator independent layer validation")
        text = text.replace("                    plan = validated ?: AmbienceSfxPlan(),\n", "                    plan = validated,\n")
    write(path, text)


patch_reader_screen()
patch_audio_direction_controls()
patch_playback_service()
patch_audio_direction_runtime()
patch_freesound_resolver()
patch_xpk_service()
patch_coordinator()
print("consolidated story-audio mode isolation patch applied")
