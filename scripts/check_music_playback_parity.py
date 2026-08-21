#!/usr/bin/env python3
from pathlib import Path

controller = Path("app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt").read_text()
service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt").read_text()

for token in [
    "duckAttackMillis = 1850",
    "duckReleaseMillis = 2050",
    "animateDuck",
    "looping: Boolean = true",
    "onCompletion: (() -> Unit)?",
    "baseVolume * duckMultiplier * sfxDuckMultiplier * slot.fadeMultiplier",
    "coerceIn(0f, 1f)",
]:
    if token not in controller:
        raise SystemExit("MUSIC_PLAYBACK controller missing: " + token)

for token in [
    "backgroundMusicEnabled = settings.backgroundMusicEnabled",
    "backgroundMusicAttackMillis = settings.backgroundMusicAttackMillis",
    "backgroundMusicReleaseMillis = settings.backgroundMusicReleaseMillis",
    "setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)",
    "chooseBackgroundPlaylistTrack",
    "ensureBackgroundPlaylist",
    "backgroundMusicShuffleBag",
    "crossfadeMillis = if (advance) 3_000 else 420",
]:
    if token not in service:
        raise SystemExit("MUSIC_PLAYBACK service missing: " + token)

# Source-mode parity: manual/local playlist playback and AI-authored scene music are
# intentionally separate flows. Requiring the old literal
# `backgroundMusicEnabled && autoSceneMusicEnabled` would incorrectly couple Mode 1
# playlist playback to the Mode 2/3 AI scene-music switch.
for token in [
    "private fun shouldPlanAutoSceneMusic(): Boolean =",
    "!StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled",
    "StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)",
    "if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false",
]:
    if token not in service:
        raise SystemExit("MUSIC_PLAYBACK source-mode routing missing: " + token)

print("MUSIC_PLAYBACK_PARITY=PASS")
