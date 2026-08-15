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
    "sfxDuckMultiplier",
    "desiredLevel(slot)",
    "slot.baseVolume * duckMultiplier * sfxDuckMultiplier * slot.fadeMultiplier",
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
    "backgroundMusicEnabled && autoSceneMusicEnabled",
]:
    if token not in service:
        raise SystemExit("MUSIC_PLAYBACK service missing: " + token)

print("MUSIC_PLAYBACK_PARITY=PASS")
