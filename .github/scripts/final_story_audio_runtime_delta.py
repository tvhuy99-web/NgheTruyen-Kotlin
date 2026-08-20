from pathlib import Path


def once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_reader() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
    once(
        path,
        "        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },\n        valueRange = min..max,\n",
        "        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },\n        valueRange = minimum..maximum,\n",
        "reader float slider",
    )
    once(
        path,
        "    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFFFFE8A3))\n",
        "    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFF5A4A1F))\n",
        "reader dark search color",
    )


def patch_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
    once(
        path,
        "import vn.nghetruyen.app.ai.ChapterAiWorkflow\nimport vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n",
        "import vn.nghetruyen.app.ai.ChapterAiWorkflow\nimport vn.nghetruyen.app.ai.XpkSceneMusicParity\nimport vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n",
        "service scene parity import",
    )
    once(
        path,
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.SonicPcmProcessor\n",
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.AudioDirectionPreferences\nimport vn.nghetruyen.app.audio.StoryAudioModeRouter\nimport vn.nghetruyen.app.audio.StoryAudioSourceMode\nimport vn.nghetruyen.app.audio.SonicPcmProcessor\n",
        "service audio mode imports",
    )
    once(
        path,
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport java.io.File\n",
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport vn.nghetruyen.app.freesound.FreesoundImporter\nimport org.json.JSONObject\nimport java.io.File\n",
        "service Freesound/JSON imports",
    )
    once(
        path,
        "    private var backgroundMusicEnabled = false\n    private var backgroundMusicAttackMillis = 1850\n",
        "    private var backgroundMusicEnabled = false\n    private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL\n    private var backgroundMusicAttackMillis = 1850\n",
        "service mode field",
    )
    once(
        path,
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        storyAudioSourceMode = container.storyAudioSourceModeStore.get()\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
        "service mode refresh",
    )
    once(
        path,
        '''        val enabledMusicTracks = if (useStoryProfile && chapterId.isNotBlank()) {
            container.libraryRepository.listEnabledSceneMusicTracks()
                .filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
        } else emptyList()
''',
        '''        val enabledMusicTracks = if (useStoryProfile && chapterId.isNotBlank()) {
            container.libraryRepository.listEnabledSceneMusicTracks()
                .filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
                .filter { track ->
                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||
                        FreesoundImporter.soundIdFromManagedUri(track.uri) != null
                }
        } else emptyList()
''',
        "Mode 3 managed music only",
    )
    once(
        path,
        "        val musicPlanUsable = backgroundMusicEnabled && autoSceneMusicEnabled && musicPlan?.sourceSha256 == musicSourceHash\n",
        "        val musicPlanUsable = !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) &&\n            autoSceneMusicEnabled &&\n            musicPlan?.sourceSha256 == musicSourceHash &&\n            scenePlanMatchesCurrentSourceMode(musicPlan.transformedText)\n",
        "service mode-owned music plan",
    )
    once(
        path,
        '''            if (!hasSceneMusicPlan()) {
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
''',
        '''            if (!hasSceneMusicPlan()) {
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
''',
        "service no-plan fallback isolation",
    )
    once(
        path,
        "    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()\n\n",
        '''    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()

    private fun scenePlanMatchesCurrentSourceMode(transformedText: String): Boolean = runCatching {
        JSONObject(transformedText).optString("audio_source_mode").trim() == storyAudioSourceMode.name
    }.getOrDefault(false)

''',
        "service source marker",
    )
    once(
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
        "service unit no-plan isolation",
    )
    once(
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
        "service intentional silence",
    )
    once(
        path,
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
        "service manual playlist guard",
    )
    once(
        path,
        "                    if (PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()) {\n",
        "                    if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n                        PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()\n                    ) {\n",
        "service playlist completion guard",
    )
    once(
        path,
        "        val usesTrackLibrary = backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
        "        val usesTrackLibrary = StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n            backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
        "service background library guard",
    )
    once(
        path,
        "    private fun shouldPlanAutoSceneMusic(): Boolean = backgroundMusicEnabled && autoSceneMusicEnabled\n\n",
        '''    private fun shouldPlanAutoSceneMusic(): Boolean =
        !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled

    private fun shouldPlanAutoStoryAudio(): Boolean {
        if (StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode)) return false
        val audio = AudioDirectionPreferences.currentSnapshot()
        return autoSceneMusicEnabled || audio.ambienceEnabled || audio.soundEffectsEnabled
    }

''',
        "service AI audio planning policy",
    )
    once(
        path,
        '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
''',
        '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        val planAudioWithoutVoice = !currentStoryAutoVoiceCastEnabled && shouldPlanAutoStoryAudio()
        if (planAudioWithoutVoice && snapshot.chapterId.isNotBlank()) {
            if (narrationPreparedChapterId == snapshot.chapterId) return false
            pendingPlay = true
            PlaybackQueueStore.setPlaying(false)
            if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true
            narrationPlanningChapterId = snapshot.chapterId
            narrationPlanJob?.cancel()
            narrationPlanJob = serviceScope.launch {
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.35f,
                    message = "Đang chuẩn bị âm thanh AI cho chương hiện tại.",
                )
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planResult = if (content == null) null else runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = content,
                        voice = false,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = sceneMusicController.activeTrackId,
                    )
                }.getOrNull()
                val configured = applyConfiguredVoice(useStoryProfile = true)
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    narrationPreparedChapterId = snapshot.chapterId
                    narrationPlanningChapterId = ""
                    voiceSettingsReady = configured
                    val warning = planResult?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.CURRENT_READY,
                        progress = 1f,
                        message = warning?.let { "Âm thanh AI đã chuẩn bị với cảnh báo: ${it.take(120)}" }
                            ?: "Đã chuẩn bị âm thanh AI cho chương hiện tại.",
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
            }
            return true
        }
        if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
''',
        "service audio-only foreground plan",
    )
    once(
        path,
        '''    ) {
        if (!prefetchNarrationPlansEnabled || !currentStoryAutoVoiceCastEnabled) return
        narrationPrefetchJob?.cancel()
        narrationPrefetchJob = serviceScope.launch {
            var current: ChapterContent? = content
''',
        '''    ) {
        val planVoice = currentStoryAutoVoiceCastEnabled
        val planAudio = shouldPlanAutoStoryAudio()
        if (!prefetchNarrationPlansEnabled || (!planVoice && !planAudio)) return
        narrationPrefetchJob?.cancel()
        narrationPrefetchJob = serviceScope.launch {
            var current: ChapterContent? = content
''',
        "service audio-aware prefetch guard",
    )
    once(
        path,
        '''                val attempt = runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = true,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
''',
        '''                val attempt = runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
''',
        "service prefetch voice contract",
    )
    once(
        path,
        "                    val assignmentCount = if (result == null) {\n                        0\n                    } else {\n",
        "                    val assignmentCount = if (!planVoice || result == null) {\n                        0\n                    } else {\n",
        "service prefetch assignments",
    )
    once(
        path,
        '''                    val failed = result == null || assignmentCount <= 0 || (
                        result.warnings.isNotEmpty() && !result.voicePlanCreated && !result.musicPlanCreated
                    )
''',
        "                    val failed = result == null || (planVoice && assignmentCount <= 0)\n",
        "service prefetch failure policy",
    )
    once(
        path,
        '''                    val baseMessage = when {
                        result == null -> "Không phân vai trước được chương tiếp theo: ${chapter.chapter.title}."
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}."
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}."
                        result.voicePlanCreated || result.musicPlanCreated ->
                            "Đã phân vai $assignmentCount mục$musicLabel cho chương tiếp theo: ${chapter.chapter.title}."
                        else -> "Chương tiếp theo đã có $assignmentCount mục phân vai$musicLabel hợp lệ: ${chapter.chapter.title}."
                    }
''',
        '''                    val baseMessage = when {
                        result == null -> "Không chuẩn bị AI trước được chương tiếp theo: ${chapter.chapter.title}."
                        !planVoice -> "Đã chuẩn bị âm thanh AI cho chương tiếp theo: ${chapter.chapter.title}."
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}."
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}."
                        result.voicePlanCreated || result.musicPlanCreated || result.audioPlanCreated || result.freesoundPlanCreated ->
                            "Đã phân vai $assignmentCount mục$musicLabel cho chương tiếp theo: ${chapter.chapter.title}."
                        else -> "Chương tiếp theo đã có $assignmentCount mục phân vai$musicLabel hợp lệ: ${chapter.chapter.title}."
                    }
''',
        "service prefetch message",
    )


def patch_coordinator() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
    once(
        path,
        "import vn.nghetruyen.app.freesound.FreesoundAutoRequirementCodec\n",
        "import vn.nghetruyen.app.freesound.FreesoundAutoRequirementCodec\nimport vn.nghetruyen.app.freesound.FreesoundImporter\n",
        "coordinator managed Freesound import",
    )
    once(
        path,
        '''        val enabledAssets = library.listEnabledSceneMusicTracks()
        val ambienceTracks = if (audioSettings.ambienceEnabled) {
            enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        } else emptyList()
        val soundEffectTracks = if (audioSettings.soundEffectsEnabled) {
            enabledAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        } else emptyList()
''',
        '''        val enabledAssets = library.listEnabledSceneMusicTracks()
        val sourceAssets = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            enabledAssets.filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }
        } else enabledAssets
        val ambienceTracks = if (audioSettings.ambienceEnabled) {
            sourceAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
        } else emptyList()
        val soundEffectTracks = if (audioSettings.soundEffectsEnabled) {
            sourceAssets.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
        } else emptyList()
''',
        "coordinator load managed Mode3 assets",
    )
    once(
        path,
        "        val enabled = library.listEnabledSceneMusicTracks()\n        if (AudioAssetKind.MUSIC in kinds) {\n",
        "        val enabled = library.listEnabledSceneMusicTracks()\n            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        if (AudioAssetKind.MUSIC in kinds) {\n",
        "coordinator current Mode3 plan assets",
    )
    once(
        path,
        "        val enabled = library.listEnabledSceneMusicTracks()\n        var musicCreated = false\n",
        "        val enabled = library.listEnabledSceneMusicTracks()\n            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        var musicCreated = false\n",
        "coordinator applied Mode3 assets",
    )
    once(
        path,
        '        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v1"\n',
        '        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v2"\n',
        "coordinator Mode3 cache generation",
    )


patch_reader()
patch_service()
patch_coordinator()
print("final runtime delta applied")
