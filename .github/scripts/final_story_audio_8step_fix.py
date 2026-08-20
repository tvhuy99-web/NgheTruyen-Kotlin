from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_reader_screen() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
    replace_once(
        path,
        "        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },\n        valueRange = min..max,\n",
        "        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },\n        valueRange = minimum..maximum,\n",
        "ReaderFloatSlider range",
    )
    replace_once(
        path,
        "    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFFFFE8A3))\n",
        "    ReaderThemeMode.DARK -> ReaderPalette(Color(0xFF111315), Color(0xFF1C1F22), Color(0xFFE9ECEF), Color(0xFF263B4D), Color(0xFF5A4A1F))\n",
        "Reader dark search color",
    )


def patch_playback_service() -> None:
    path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
    replace_once(
        path,
        "import vn.nghetruyen.app.ai.ChapterAiWorkflow\nimport vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n",
        "import vn.nghetruyen.app.ai.ChapterAiWorkflow\nimport vn.nghetruyen.app.ai.XpkSceneMusicParity\nimport vn.nghetruyen.app.ai.XpkVoiceCastSplitter\n",
        "scene music parity import",
    )
    replace_once(
        path,
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.SonicPcmProcessor\n",
        "import vn.nghetruyen.app.audio.AudioAssetKind\nimport vn.nghetruyen.app.audio.AudioDirectionPreferences\nimport vn.nghetruyen.app.audio.StoryAudioModeRouter\nimport vn.nghetruyen.app.audio.StoryAudioSourceMode\nimport vn.nghetruyen.app.audio.SonicPcmProcessor\n",
        "audio mode imports",
    )
    replace_once(
        path,
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport java.io.File\n",
        "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\nimport org.json.JSONObject\nimport java.io.File\n",
        "JSONObject import",
    )
    replace_once(
        path,
        "    private var backgroundMusicEnabled = false\n    private var backgroundMusicAttackMillis = 1850\n",
        "    private var backgroundMusicEnabled = false\n    private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL\n    private var backgroundMusicAttackMillis = 1850\n",
        "source mode field",
    )
    replace_once(
        path,
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
        "        val settings = container.settingsRepository.snapshot()\n        applyRuntimeSettings(settings)\n        storyAudioSourceMode = container.storyAudioSourceModeStore.get()\n        val storyId = PlaybackQueueStore.state.value.storyId\n",
        "refresh source mode",
    )
    replace_once(
        path,
        "        val musicPlanUsable = backgroundMusicEnabled && autoSceneMusicEnabled && musicPlan?.sourceSha256 == musicSourceHash\n",
        "        val musicPlanUsable = !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) &&\n            autoSceneMusicEnabled &&\n            musicPlan?.sourceSha256 == musicSourceHash &&\n            scenePlanMatchesCurrentSourceMode(musicPlan.transformedText)\n",
        "mode-owned scene plan",
    )
    old_no_plan = '''            if (!hasSceneMusicPlan()) {
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
    new_no_plan = '''            if (!hasSceneMusicPlan()) {
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
    replace_once(path, old_no_plan, new_no_plan, "mode-specific no-plan playback")
    replace_once(
        path,
        "    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()\n\n",
        '''    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()

    private fun scenePlanMatchesCurrentSourceMode(transformedText: String): Boolean = runCatching {
        JSONObject(transformedText).optString("audio_source_mode").trim() == storyAudioSourceMode.name
    }.getOrDefault(false)

''',
        "scene-plan source-mode marker",
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
        "no-plan unit fallback",
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
        "intentional scene silence",
    )
    replace_once(
        path,
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
        "    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {\n        if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false\n        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false\n",
        "manual playlist guard",
    )
    replace_once(
        path,
        "                    if (PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()) {\n",
        "                    if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n                        PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()\n                    ) {\n",
        "playlist completion guard",
    )
    replace_once(
        path,
        "        val usesTrackLibrary = backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
        "        val usesTrackLibrary = StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&\n            backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()\n",
        "track-library runtime guard",
    )
    replace_once(
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
        "AI audio planning policy",
    )

    audio_only_anchor = '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
'''
    audio_only_replacement = '''    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
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
'''
    replace_once(path, audio_only_anchor, audio_only_replacement, "audio-only foreground preparation")

    replace_once(
        path,
        "        if (!prefetchNarrationPlansEnabled || !currentStoryAutoVoiceCastEnabled) return\n        narrationPrefetchJob?.cancel()\n",
        '''        val planVoice = currentStoryAutoVoiceCastEnabled
        val planAudio = shouldPlanAutoStoryAudio()
        if (!prefetchNarrationPlansEnabled || (!planVoice && !planAudio)) return
        narrationPrefetchJob?.cancel()
''',
        "audio-aware prefetch guard",
    )
    replace_once(
        path,
        "                        voice = true,\n                        music = shouldPlanAutoSceneMusic(),\n",
        "                        voice = planVoice,\n                        music = shouldPlanAutoSceneMusic(),\n",
        "prefetch voice flag",
    )
    replace_once(
        path,
        "                    val assignmentCount = if (result == null) {\n                        0\n                    } else {\n",
        "                    val assignmentCount = if (!planVoice || result == null) {\n                        0\n                    } else {\n",
        "prefetch assignment requirement",
    )
    replace_once(
        path,
        '''                    val failed = result == null || assignmentCount <= 0 || (
                        result.warnings.isNotEmpty() && !result.voicePlanCreated && !result.musicPlanCreated
                    )
''',
        '''                    val failed = result == null || (planVoice && assignmentCount <= 0)
''',
        "prefetch failure policy",
    )
    replace_once(
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
        "prefetch status message",
    )


patch_reader_screen()
patch_playback_service()
print("final story audio 8-step patch applied")
