#!/usr/bin/env python3
from pathlib import Path


def rep(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

p = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt")
s = p.read_text()

s = rep(
    s,
    "    private var backgroundMusicDuckFactor: Float = 0.63095734f\n",
    "    private var backgroundMusicDuckFactor: Float = 0.63095734f\n"
    "    private var backgroundMusicEnabled = false\n"
    "    private var backgroundMusicAttackMillis = 1850\n"
    "    private var backgroundMusicReleaseMillis = 2050\n"
    "    private var backgroundMusicTracks: List<SceneMusicTrackEntity> = emptyList()\n"
    "    private val backgroundMusicShuffleBag = ArrayDeque<String>()\n",
    "background playlist state",
)

s = rep(
    s,
    "        autoVoiceCastEnabled = settings.autoVoiceCastEnabled\n        autoSceneMusicEnabled = settings.autoSceneMusicEnabled\n",
    "        autoVoiceCastEnabled = settings.autoVoiceCastEnabled\n"
    "        backgroundMusicEnabled = settings.backgroundMusicEnabled\n"
    "        backgroundMusicDuckFactor = settings.backgroundMusicDuckFactor\n"
    "        backgroundMusicAttackMillis = settings.backgroundMusicAttackMillis.coerceIn(0, 2_000)\n"
    "        backgroundMusicReleaseMillis = settings.backgroundMusicReleaseMillis.coerceIn(0, 5_000)\n"
    "        sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)\n"
    "        autoSceneMusicEnabled = settings.autoSceneMusicEnabled\n",
    "runtime background settings",
)

old_cues = '''        sceneMusicCues = if (musicPlan?.sourceSha256 == musicSourceHash) {
            container.libraryRepository.listSceneMusicCues(chapterId)
        } else emptyList()
        sceneMusicTracks = if (sceneMusicCues.isNotEmpty()) {
            enabledMusicTracks.associateBy { it.id }
        } else emptyMap()
'''
new_cues = '''        val orderedMusicTracks = enabledMusicTracks.sortedWith(
            compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() },
        )
        val previousTrackIds = backgroundMusicTracks.map(SceneMusicTrackEntity::id)
        backgroundMusicTracks = orderedMusicTracks
        if (previousTrackIds != orderedMusicTracks.map(SceneMusicTrackEntity::id)) backgroundMusicShuffleBag.clear()
        sceneMusicCues = if (
            backgroundMusicEnabled && autoSceneMusicEnabled && musicPlan?.sourceSha256 == musicSourceHash
        ) {
            container.libraryRepository.listSceneMusicCues(chapterId)
        } else emptyList()
        sceneMusicTracks = if (sceneMusicCues.isNotEmpty()) {
            orderedMusicTracks.associateBy { it.id }
        } else emptyMap()
'''
s = rep(s, old_cues, new_cues, "AI music master switch gating")

old_no_cues = '''            if (sceneMusicCues.isEmpty()) {
                val carryingSceneAcrossChapter = sceneMusicContinueAcrossChapters && sceneMusicController.activeTrackId != null
                if (!carryingSceneAcrossChapter) {
                    sceneMusicController.stop(clearTrack = true)
                    configureBackgroundMusic(
                        settings.backgroundMusicUri,
                        settings.backgroundMusicEnabled,
                        settings.backgroundMusicVolume,
                        settings.backgroundMusicDuckFactor,
                    )
                } else {
                    configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                    sceneMusicController.keepCurrent(settings.backgroundMusicDuckFactor)
                }
            } else {
'''
new_no_cues = '''            if (sceneMusicCues.isEmpty()) {
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
s = rep(s, old_no_cues, new_no_cues, "fallback playlist instead of legacy URI")

s = rep(
    s,
    "    private fun updateSceneMusicForParagraph(paragraphIndex: Int) {\n        if (sceneMusicCues.isEmpty()) return\n",
    "    private fun updateSceneMusicForParagraph(paragraphIndex: Int) {\n"
    "        if (sceneMusicCues.isEmpty()) {\n"
    "            if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) ensureBackgroundPlaylist(advance = false)\n"
    "            return\n"
    "        }\n",
    "fallback playlist on play/paragraph",
)

configure_anchor = "    private fun configureBackgroundMusic(uri: String?, enabled: Boolean, volume: Float, duckFactor: Float) {\n"
playlist_methods = '''    private fun trackNormalizationGain(track: SceneMusicTrackEntity): Float {
        if (track.normalizationVersion < PcmLoudnessEstimator.VERSION || track.normalizationError.isNotBlank()) return 1f
        if (!track.loudnessLufsEstimate.isFinite() || !track.peakDbfs.isFinite()) return 1f
        val gainDb = PcmLoudnessEstimator.calculateNormalization(
            track.loudnessLufsEstimate,
            track.peakDbfs,
            sceneMusicTargetLufs,
        ).gainDb
        return PcmLoudnessEstimator.gainDbToLinear(gainDb)
    }

    private fun chooseBackgroundPlaylistTrack(advance: Boolean): SceneMusicTrackEntity? {
        val tracks = backgroundMusicTracks.filter(SceneMusicTrackEntity::enabled)
        if (tracks.isEmpty()) return null
        val currentId = sceneMusicController.activeTrackId
        if (!advance) return tracks.firstOrNull { it.id == currentId } ?: tracks.first()
        if (sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE && tracks.size > 1) {
            val valid = tracks.associateBy(SceneMusicTrackEntity::id)
            while (backgroundMusicShuffleBag.isNotEmpty() &&
                (backgroundMusicShuffleBag.first() !in valid || backgroundMusicShuffleBag.first() == currentId)
            ) {
                backgroundMusicShuffleBag.removeFirst()
            }
            if (backgroundMusicShuffleBag.isEmpty()) {
                tracks.map(SceneMusicTrackEntity::id)
                    .filter { it != currentId }
                    .shuffled()
                    .forEach(backgroundMusicShuffleBag::addLast)
            }
            val nextId = backgroundMusicShuffleBag.removeFirstOrNull()
            return nextId?.let(valid::get) ?: tracks.first()
        }
        val currentIndex = tracks.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % tracks.size
        return tracks[nextIndex]
    }

    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {
        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || sceneMusicCues.isNotEmpty()) return false
        val track = chooseBackgroundPlaylistTrack(advance) ?: return false
        val tracks = backgroundMusicTracks.filter(SceneMusicTrackEntity::enabled)
        val current = sceneMusicController.activeTrackId
        if (!advance && current == track.id) {
            sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
            sceneMusicController.keepCurrent(backgroundMusicDuckFactor)
            sceneMusicController.resume()
            activeSceneTrackId = current
            return true
        }
        sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = (track.volume * trackNormalizationGain(track)).coerceIn(0f, 1f),
            duckFactor = backgroundMusicDuckFactor,
            crossfadeMillis = if (advance) 3_000 else 420,
            looping = tracks.size == 1,
            onCompletion = if (tracks.size > 1) {
                {
                    if (PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && sceneMusicCues.isEmpty()) {
                        ensureBackgroundPlaylist(advance = true)
                    }
                }
            } else null,
        )
        sceneMusicController.setSpeaking(PlaybackQueueStore.state.value.isPlaying)
        activeSceneTrackId = track.id
        serviceScope.launch { container.libraryRepository.markSceneMusicPlayed(track.id) }
        return true
    }

'''
s = rep(s, configure_anchor, playlist_methods + configure_anchor, "playlist methods")

old_update_music = '''    private fun updateBackgroundMusic(ducked: Boolean, pause: Boolean = false) {
        if (sceneMusicCues.isNotEmpty() || sceneMusicController.activeTrackId != null) {
            if (pause) sceneMusicController.pause() else {
                sceneMusicController.setSpeaking(ducked)
                sceneMusicController.resume()
            }
        }
        val player = backgroundPlayer ?: return
'''
new_update_music = '''    private fun updateBackgroundMusic(ducked: Boolean, pause: Boolean = false) {
        val usesTrackLibrary = backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()
        if (sceneMusicCues.isNotEmpty() || sceneMusicController.activeTrackId != null || usesTrackLibrary) {
            sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
            if (pause) {
                sceneMusicController.pause()
            } else {
                if (sceneMusicCues.isEmpty() && sceneMusicController.activeTrackId == null && usesTrackLibrary) {
                    ensureBackgroundPlaylist(advance = false)
                }
                sceneMusicController.setSpeaking(ducked)
                sceneMusicController.resume()
            }
        }
        val player = backgroundPlayer ?: return
'''
s = rep(s, old_update_music, new_update_music, "runtime playlist resume")

# Reset the shuffle bag when a full stop occurs so the next listening session starts cleanly.
s = s.replace(
    "        narrationReloadPending = false\n        sleepTimerJob = null\n",
    "        narrationReloadPending = false\n        backgroundMusicShuffleBag.clear()\n        sleepTimerJob = null\n",
    1,
)

p.write_text(s)

# Runtime gate: controls in the dialog must actually drive playback.
Path("scripts/check_music_playback_parity.py").write_text('''#!/usr/bin/env python3
from pathlib import Path
controller = Path("app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt").read_text()
service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt").read_text()
for token in ["duckAttackMillis = 1850", "duckReleaseMillis = 2050", "animateDuck", "looping: Boolean = true", "onCompletion: (() -> Unit)?", "baseVolume * duckMultiplier * slot.fadeMultiplier", "coerceIn(0f, 1f)"]:
    if token not in controller: raise SystemExit("MUSIC_PLAYBACK controller missing: " + token)
for token in ["backgroundMusicEnabled = settings.backgroundMusicEnabled", "backgroundMusicAttackMillis = settings.backgroundMusicAttackMillis", "backgroundMusicReleaseMillis = settings.backgroundMusicReleaseMillis", "setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)", "chooseBackgroundPlaylistTrack", "ensureBackgroundPlaylist", "backgroundMusicShuffleBag", "crossfadeMillis = if (advance) 3_000 else 420", "backgroundMusicEnabled && autoSceneMusicEnabled"]:
    if token not in service: raise SystemExit("MUSIC_PLAYBACK service missing: " + token)
print("MUSIC_PLAYBACK_PARITY=PASS")
''')

p = Path("scripts/m0_gate.sh")
s = p.read_text()
marker = "  scripts/check_music_runtime_parity.py\n"
if "check_music_playback_parity.py" not in s:
    s = rep(s, marker, marker + "  scripts/check_music_playback_parity.py\n", "m0 music playback gate")
p.write_text(s)
