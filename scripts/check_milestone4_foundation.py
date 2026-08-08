#!/usr/bin/env python3
"""Milestone 4 foundation gate: TTS recovery, media buttons, AI narration plans and scene music."""
from __future__ import annotations

import shutil
import sqlite3
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def require(path: str, *tokens: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} missing: {missing}")
    return text


def media_button_smoke() -> None:
    if not KOTLINC:
        print("MILESTONE4_MEDIA_BUTTON_SMOKE_SKIPPED_NO_KOTLINC")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_m4_") as temp_name:
        temp = Path(temp_name)
        smoke = temp / "Smoke.kt"
        smoke.write_text(
            r'''import vn.nghetruyen.app.playback.*

private fun up(time:Long)=MediaKeyEvent(MediaKeyEvent.HEADSET_HOOK,MediaKeyEvent.ACTION_UP,0,time)
fun main(){
  val interpreter=MediaButtonGestureInterpreter(300)
  interpreter.onKeyEvent(up(100),true)
  check(interpreter.flush(400)==MediaButtonCommand.TOGGLE)
  interpreter.onKeyEvent(up(1000),true); interpreter.onKeyEvent(up(1150),true)
  check(interpreter.flush(1500)==MediaButtonCommand.NEXT)
  interpreter.onKeyEvent(up(2000),true); interpreter.onKeyEvent(up(2100),true); interpreter.onKeyEvent(up(2200),true)
  check(interpreter.flush(2600)==MediaButtonCommand.PREVIOUS)
  val longPress=MediaKeyEvent(MediaKeyEvent.MEDIA_PLAY_PAUSE,MediaKeyEvent.ACTION_DOWN,10,900,1,true)
  check(interpreter.onKeyEvent(longPress,true).immediate==MediaButtonCommand.STOP)
  val dedupe=MediaButtonEventDeduplicator()
  val event=up(3000)
  check(dedupe.accept(event,3000)); check(!dedupe.accept(event,3010))
  println("MILESTONE4_MEDIA_BUTTON_SMOKE_OK")
}
''',
            encoding="utf-8",
        )
        jar = temp / "m4.jar"
        result = subprocess.run(
            [KOTLINC, str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/MediaButtonGestureInterpreter.kt"), str(smoke), "-include-runtime", "-d", str(jar)],
            cwd=ROOT, text=True, capture_output=True, timeout=90,
        )
        if result.returncode:
            print(result.stdout); print(result.stderr); raise SystemExit(result.returncode)
        run = subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, text=True, capture_output=True, timeout=20)
        if run.returncode:
            print(run.stdout); print(run.stderr); raise SystemExit(run.returncode)
        print(run.stdout.strip())


def migration_smoke() -> None:
    with sqlite3.connect(":memory:") as db:
        db.executescript(
            """
            CREATE TABLE chapter_notes (
              id TEXT NOT NULL PRIMARY KEY, storyId TEXT NOT NULL, chapterId TEXT NOT NULL,
              paragraphIndex INTEGER NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL
            );
            INSERT INTO chapter_notes VALUES ('n','s','c',2,'preserved',1,2);
            CREATE TABLE playback_checkpoint (
              id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, storyId TEXT NOT NULL,
              chapterId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, paragraphIndex INTEGER NOT NULL,
              wasPlaying INTEGER NOT NULL, activeSceneTrackId TEXT, updatedAt INTEGER NOT NULL
            );
            INSERT INTO playback_checkpoint VALUES ('reader','src','s','c',7,3,1,'track',1234);
            """
        )
        assert db.execute("SELECT text FROM chapter_notes").fetchone()[0] == "preserved"
        assert db.execute("SELECT chapterId,paragraphIndex,wasPlaying FROM playback_checkpoint").fetchone() == ("c", 3, 1)
        columns = [row[1] for row in db.execute("PRAGMA table_info(playback_checkpoint)")]
        assert columns == ["id", "sourceId", "storyId", "chapterId", "chapterIndex", "paragraphIndex", "wasPlaying", "activeSceneTrackId", "updatedAt"]
    print("MILESTONE4_MIGRATION_9_10_OK")


def main() -> None:
    require(
        "app/src/main/java/vn/nghetruyen/app/playback/MediaButtonGestureInterpreter.kt",
        "multiClickWindowMillis", "MediaButtonCommand.NEXT", "MediaButtonCommand.PREVIOUS",
        "MediaButtonEventDeduplicator", "longPressConsumed",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
        "setMediaButtonBroadcastReceiver", "onMediaButtonEvent", "ACTION_AUDIO_BECOMING_NOISY",
        "mediaButtonKeyEvent", "restoreCheckpointAndMaybePlay", "persistCheckpoint",
        "maybeEnsureCurrentNarrationPlans", "maybePrefetchNarrationPlans", "musicSourceHash",
        "SceneMusicController", "START_STICKY",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicController.kt",
        "crossfadeMillis", "Dispatchers.Main.immediate", "duckFactor", "setSpeaking", "keepCurrent",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt",
        "ensureVoicePlan", "ensureMusicPlan", "Math.floorMod", "replaceVoiceAssignments",
        "replaceSceneMusicCues", "SceneMusicTrackOption",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
        "Danh sách nhạc hợp lệ", "TRACK|", "Không tạo track_id mới", "AI_TRACKS_EMPTY",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "version = 18", "MIGRATION_9_10", "playback_checkpoint", "PlaybackCheckpointDao",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
        "headsetMultiClickEnabled", "pauseOnHeadsetDisconnect", "restorePlaybackAfterProcessDeath",
        "autoVoiceCastEnabled", "autoSceneMusicEnabled", "prefetchNarrationPlansEnabled",
        "sceneMusicCrossfadeMillis", "sceneMusicContinueAcrossChapters",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15", 'name("headsetMultiClickEnabled")', 'name("sceneMusicCrossfadeMillis")',
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "PlaybackAutomationCard", "Dừng khi ngắt tai nghe", "Tự phân vai AI", "Crossfade:",
    )
    manifest = require(
        "app/src/main/AndroidManifest.xml",
        ".playback.ReaderMediaButtonReceiver", 'android:exported="true"',
        "android.intent.action.MEDIA_BUTTON", 'android:foregroundServiceType="mediaPlayback"',
    )
    assert manifest.count("ReaderMediaButtonReceiver") == 1
    build = require(
        "app/build.gradle.kts", "versionCode = 28", 'versionName = "2.8.0-ai-narration-priority2-complete"',
    )
    assert "compileSdk = 36" in build
    media_button_smoke()
    migration_smoke()
    print("MILESTONE4_FOUNDATION_CHECK_OK")


if __name__ == "__main__":
    main()
