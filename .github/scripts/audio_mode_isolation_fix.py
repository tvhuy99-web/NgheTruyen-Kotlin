from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_reader_screen() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
    replace_once(
        path,
        "import vn.nghetruyen.app.audio.AudioAssetKind\n",
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.StoryAudioSourceMode\n",
    )
    replace_once(
        path,
        "    var musicReleaseMs by remember { mutableIntStateOf(2050) }\n",
        "    var musicReleaseMs by remember { mutableIntStateOf(2050) }\n"
        "    var storyAudioSourceMode by remember { mutableStateOf(app.container.storyAudioSourceModeStore.get()) }\n",
    )
    replace_once(
        path,
        "        if (showMusicDialog) {\n            val settings = app.container.settingsRepository.snapshot()\n",
        "        if (showMusicDialog) {\n            storyAudioSourceMode = app.container.storyAudioSourceModeStore.get()\n            val settings = app.container.settingsRepository.snapshot()\n",
    )

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
    replace_once(path, old, new)


def patch_playback_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
    replace_once(
        path,
        "import vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n",
        "import vn.nghetruyen.app.ai.XpkVoiceCastSplitter\nimport vn.nghetruyen.app.ai.XpkSceneMusicParity\n",
    )
    replace_once(
        path,
        "import vn.nghetruyen.app.audio.AudioAssetKind\n",
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.StoryAudioModeRouter\nimport vn.nghetruyen.app.audio.StoryAudioSourceMode\n",
    )
    replace_once(
        path,
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n",
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport org.json.JSONObject\n",
    )
    replace_once(
        path,
        "    private var backgroundMusicEnabled = false\n",
        "    private var backgroundMusicEnabled = false\n    private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL\n",
    )
    replace_once(
        path,
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        storyAudioSourceMode = container.storyAudioSourceModeStore.get()\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
    )
    replace_once(
        path,
        "        val musicPlanUsable = backgroundMusicEnabled && autoSceneMusicEnabled && musicPlan?.sourceSha256 == musicSourceHash\n",
        "        val musicPlanUsable = !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) &&\n            autoSceneMusicEnabled &&\n            musicPlan?.sourceSha256 == musicSourceHash &&\n            scenePlanMatchesCurrentSourceMode(musicPlan.transformedText)\n",
    )
    old = '''            if (!hasSceneMusicPlan()) {
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
    new = '''            if (!hasSceneMusicPlan()) {
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
                    // Mode 2/3 never fall back to Mode-1 sequential/shuffle/plain background playback.
                    sceneMusicController.stop(clearTrack = true)
                    activeSceneTrackId = null
                    configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                }
            } else {
'''
    replace_once(path, old, new)
    replace_once(
        path,
        "    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()\n\n",
        '''    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()

    private fun scenePlanMatchesCurrentSourceMode(transformedText: String): Boolean = runCatching {
        JSONObject(transformedText).optString("audio_source_mode") == storyAudioSourceMode.name
    }.getOrDefault(false)

''',
    )
    replace_once(
        path,
        '''        if (!hasSceneMusicPlan()) {
            if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) ensureBackgroundPlaylist(advance = false)
            return
        }
''',
        '''        if (!hasSceneMusicPlan()) {
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
''',
    )
    replace_once(
        path,
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
    )
    replace_once(
        path,
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
    )
    replace_once(
        path,
        "                    if (PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()) {\n",
        "                    if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n                        PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()\n                    ) {\n",
    )
    replace_once(
        path,
        "        val usesTrackLibrary = backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
        "        val usesTrackLibrary = StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n            backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
    )
    replace_once(
        path,
        "    private fun shouldPlanAutoSceneMusic(): Boolean = backgroundMusicEnabled && autoSceneMusicEnabled\n",
        "    private fun shouldPlanAutoSceneMusic(): Boolean =\n        !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled\n",
    )


def patch_xpk_ai_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt"
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
    replace_once(path, needle, replacement)


def patch_coordinator() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
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
    new = '''            // Validate/salvage the two layers independently. A malformed AMBIENCE cue must never
            // erase a valid SFX cue (and vice versa) just because they share one persisted payload.
            var ambienceCandidate = if (AudioAssetKind.AMBIENCE in kinds) {
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
    replace_once(path, old, new)
    replace_once(path, "                    plan = validated ?: AmbienceSfxPlan(),\n", "                    plan = validated,\n")


patch_reader_screen()
patch_playback_service()
patch_xpk_ai_service()
patch_coordinator()
print("audio mode isolation patch applied")
