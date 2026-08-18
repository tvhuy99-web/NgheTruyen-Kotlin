#!/usr/bin/env python3
"""Offline source gate for Milestone 5 playback resilience and persistence.

This verifies source-side behavior without claiming Android device certification.
"""
from __future__ import annotations

import re
import shutil
import sqlite3
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
KOTLIN = shutil.which("kotlin")


def require(path: str, *tokens: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} thiếu wiring M5: {missing}")
    return text


def run(cmd: list[str], timeout: int = 240) -> None:
    cp = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if cp.stdout:
        print(cp.stdout.strip())
    if cp.returncode:
        if cp.stderr:
            print(cp.stderr)
        raise SystemExit(cp.returncode)


def pure_runtime_gate() -> None:
    if not KOTLINC or not KOTLIN:
        raise SystemExit("M5_PLAYBACK_GATE_BLOCKED: thiếu kotlinc/kotlin")
    with tempfile.TemporaryDirectory(prefix="nghe_m5_playback_") as td:
        td = Path(td)
        smoke = td / "Smoke.kt"
        smoke.write_text(
            r'''
import vn.nghetruyen.app.playback.*
import java.io.File

private fun cacheKey(id:String)=TtsAudioCache.Key(id,null,null,"vi-VN",1f,1f,1f,1f,1f,"rules")
fun main(args:Array<String>){
  var state=SpeechRecoveryState()
  val sequence=mutableListOf<SpeechRecoveryAction>()
  repeat(4){
    val action=PlaybackRecoveryPolicy.next(state,true,"engine")
    sequence += action
    state=PlaybackRecoveryPolicy.after(state,action)
  }
  check(sequence==listOf(
    SpeechRecoveryAction.RETRY_WITHOUT_SONIC,
    SpeechRecoveryAction.RETRY_CURRENT_ENGINE,
    SpeechRecoveryAction.FALLBACK_TO_DEFAULT_ENGINE,
    SpeechRecoveryAction.STOP_SAFELY,
  )) { sequence }
  check(PlaybackWatchdogPolicy.speechTimeoutMillis(1,1f,false)==15_000L)
  check(PlaybackWatchdogPolicy.speechTimeoutMillis(999_999,0.5f,true)==240_000L)

  val generation=TtsGenerationGuard(); val old=generation.next(); val current=generation.next()
  check(!generation.isCurrent(old) && generation.isCurrent(current))
  val completion=PlaybackCompletionGuard(); completion.begin("chunk")
  check(completion.consume("chunk")); check(!completion.consume("chunk"))

  val mapping=MediaButtonMapping.fromNames("PLAY","FORWARD","REWIND","PAUSE")
  val gestures=MediaButtonGestureInterpreter(100)
  gestures.onKeyEvent(MediaKeyEvent(MediaKeyEvent.HEADSET_HOOK,MediaKeyEvent.ACTION_UP,0,10),true,mapping)
  check(gestures.flush(111,mapping)==MediaButtonCommand.PLAY)
  val long=gestures.onKeyEvent(MediaKeyEvent(MediaKeyEvent.MEDIA_PLAY_PAUSE,MediaKeyEvent.ACTION_DOWN,1,200,1,true),true,mapping)
  check(long.immediate==MediaButtonCommand.PAUSE)

  val deadline=SleepTimerPolicy.deadlineFromMinutes(1_000L,999)!!
  check(deadline==1_000L+SleepTimerPolicy.MAX_MINUTES*60_000L)
  check(SleepTimerPolicy.hasExpired(deadline,deadline))

  val longParagraph="a".repeat(7_200)
  PlaybackQueueStore.load("src","story","chapter",10,"Chapter",paragraphs=listOf(longParagraph,"next"))
  val initial=PlaybackQueueStore.state.value
  check(initial.paragraphs.size==2 && initial.speechChunks.size>=4)
  PlaybackQueueStore.restoreSpeechPosition(0,2)
  check(PlaybackQueueStore.state.value.paragraphIndex==0)
  check(PlaybackQueueStore.state.value.speechChunkIndex==2)
  PlaybackQueueStore.restoreSpeechPosition(1,2)
  check(PlaybackQueueStore.state.value.paragraphIndex==1)
  check(PlaybackQueueStore.state.value.currentSpeechText=="next")

  val monitor=PlaybackHealthMonitor(128)
  repeat(100_000){ i ->
    val token="t$i"; monitor.chunkStarted(i.toLong(),token)
    if(i%7==0) monitor.chunkFailed(i.toLong(),"E") else monitor.chunkCompleted(i.toLong(),token)
  }
  check(monitor.recent().size==128)
  check(!monitor.snapshot().contains("chapter text",ignoreCase=true))

  val root=File(args[0]); root.mkdirs()
  val cache=TtsAudioCache(File(root,"cache"),8L*1024L*1024L)
  fun source(name:String):File=File(root,name).apply { writeBytes(ByteArray(4*1024*1024){(it%251).toByte()}) }
  cache.put(cacheKey("one"),source("one.wav")); Thread.sleep(5)
  cache.put(cacheKey("two"),source("two.wav")); Thread.sleep(5)
  check(cache.get(cacheKey("one"))!=null); Thread.sleep(5)
  cache.put(cacheKey("three"),source("three.wav"))
  check(cache.sizeBytes()<=8L*1024L*1024L)
  check(cache.get(cacheKey("two"))==null)
  val entry=cache.get(cacheKey("one"))!!
  entry.audioFile.appendBytes(byteArrayOf(9))
  check(cache.get(cacheKey("one"))==null)
  println("M5_PLAYBACK_PURE_RUNTIME_OK events=${monitor.recent().size} cache=${cache.sizeBytes()}")
}
''',
            encoding="utf-8",
        )
        coroutines = Path(KOTLINC).resolve().parents[1] / "lib/kotlinx-coroutines-core-jvm.jar"
        sources = [
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PlaybackRecoveryPolicy.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/SleepTimerPolicy.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/TtsAudioCache.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/MediaButtonGestureInterpreter.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PlaybackHealthMonitor.kt",
            smoke,
        ]
        jar = td / "m5.jar"
        cmd = [KOTLINC, *map(str, sources)]
        if coroutines.is_file():
            cmd += ["-cp", str(coroutines)]
        cmd += ["-d", str(jar)]
        run(cmd, 300)
        cp = str(jar) + (f":{coroutines}" if coroutines.is_file() else "")
        run([KOTLIN, "-cp", cp, "SmokeKt", str(td / "run")], 120)


def extract_migration(name: str, next_name: str) -> list[str]:
    text = (ROOT / "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt").read_text(encoding="utf-8")
    start = text.index(f"val {name}")
    end_marker = next_name if next_name.startswith("fun ") else f"val {next_name}"
    end = text.index(end_marker, start)
    block = text[start:end]
    sql: list[str] = []
    pattern = r'db\.execSQL\(\s*(?:"""(.*?)"""\.trimIndent\(\)|"((?:[^"\\]|\\.)*)"(?:\s*\+\s*"((?:[^"\\]|\\.)*)")?)\s*,?\s*\)'
    for match in re.finditer(pattern, block, re.S):
        if match.group(1) is not None:
            value = match.group(1)
        else:
            value = bytes((match.group(2) or "") + (match.group(3) or ""), "utf-8").decode("unicode_escape")
        sql.append(value.strip())
    return sql


def migration_gate() -> None:
    db = sqlite3.connect(":memory:")
    db.executescript(
        """
        CREATE TABLE playback_checkpoint (
          id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, storyId TEXT NOT NULL,
          chapterId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, paragraphIndex INTEGER NOT NULL,
          wasPlaying INTEGER NOT NULL, activeSceneTrackId TEXT, updatedAt INTEGER NOT NULL
        );
        INSERT INTO playback_checkpoint VALUES ('reader','src','story','chapter',7,3,1,'track',1234);
        """
    )
    migration_14_15 = extract_migration("MIGRATION_14_15", "MIGRATION_15_16")
    assert len(migration_14_15) == 5, migration_14_15
    for sql in migration_14_15:
        db.execute(sql)
    row = db.execute(
        "SELECT chapterId,paragraphIndex,speechChunkIndex,nextChapterUrl,previousChapterUrl,sleepTimerEndsAtMillis,sessionId,wasPlaying FROM playback_checkpoint"
    ).fetchone()
    assert row == ("chapter", 3, 0, None, None, None, "", 1), row



    migration_15_16 = extract_migration("MIGRATION_15_16", "MIGRATION_16_17")
    assert migration_15_16, migration_15_16
    for sql in migration_15_16:
        db.execute(sql)
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "playback_queue_chapters" in tables
    columns = [r[1] for r in db.execute("PRAGMA table_info(playback_queue_chapters)")]
    assert columns == [
        "position", "sourceId", "storyId", "chapterId", "chapterIndex", "chapterTitle",
        "chapterUrl", "nextChapterUrl", "previousChapterUrl", "updatedAt",
    ], columns
    for position in range(5):
        db.execute(
            "INSERT INTO playback_queue_chapters VALUES (?,?,?,?,?,?,?,?,?,?)",
            (position, "src", "story", f"c{position}", position, f"C {position}", f"u{position}", None, None, 1),
        )
    assert db.execute("SELECT COUNT(*) FROM playback_queue_chapters").fetchone()[0] == 5
    print(f"M5_MIGRATION_14_16_SQLITE_OK statements={len(migration_14_15)+len(migration_15_16)}")


def source_gate() -> None:
    service = require(
        "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
        "TtsGenerationGuard", "PlaybackCompletionGuard", "PlaybackWatchdogPolicy",
        "recoverActiveSpeech", "persistCheckpoint(wasPlaying = preserveResumeIntent",
        "normalizeRenderedSpeech", "TtsAudioCache.Key", "MAX_PERSISTED_QUEUE_CHAPTERS = 5",
        "persistPlaybackQueue", "loadPlaybackQueue", "ACTION_SLEEP_TIMER_EXPIRED",
    )
    assert "override fun onInit" not in service
    fail_block = service[service.index("private fun failPlaybackSafely"):service.index("private fun synthesizeAndPlaySonic")]
    assert "preserveResumeIntent" not in fail_block
    assert "persistCheckpoint(wasPlaying = false" in fail_block

    require(
        "app/src/main/java/vn/nghetruyen/app/playback/ReaderSleepTimerReceiver.kt",
        "setAndAllowWhileIdle", "ACTION_BOOT_COMPLETED", "ACTION_MY_PACKAGE_REPLACED",
        "ReaderSleepTimerStore", "ACTION_SLEEP_TIMER_EXPIRED",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/playback/TtsAudioCache.kt",
        "sha256(audio)", "renameTo(audio)", "trim()", "MIN_LIMIT_BYTES", "MAX_LIMIT_BYTES",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/playback/MediaButtonGestureInterpreter.kt",
        "MediaButtonMapping", "fromNames", "mapping.longPress", "mapping.singleClick",
    )
    database = require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "version = 18", "MIGRATION_14_15", "MIGRATION_15_16", "playback_queue_chapters",
        "speechChunkIndex", "sleepTimerEndsAtMillis", "sessionId",
    )
    assert "PlaybackQueueChapterEntity::class" in database
    require(
        "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
        "headsetSingleClickAction", "headsetDoubleClickAction", "headsetTripleClickAction",
        "headsetLongPressAction", "ttsCacheEnabled", "ttsCacheLimitMiB",
        "normalizeTtsVolumeEnabled", "ttsTargetLufs",
    )
    backup = require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15", 'name("headsetSingleClickAction")', 'name("ttsCacheEnabled")',
        'name("normalizeTtsVolumeEnabled")', 'name("ttsTargetLufs")',
    )
    assert "playback_queue_chapters" not in backup and "PlaybackQueueChapterEntity" not in backup
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "MediaMappingButton", "Bộ nhớ đệm giọng TTS/Sonic có checksum",
        "Chuẩn hóa âm lượng giữa giọng và engine", "Mức giọng mục tiêu",
    )
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    ET.parse(manifest)
    manifest_text = manifest.read_text(encoding="utf-8")
    for token in ("RECEIVE_BOOT_COMPLETED", ".playback.ReaderSleepTimerReceiver", "BOOT_COMPLETED", "MY_PACKAGE_REPLACED"):
        assert token in manifest_text, token
    require(
        "app/build.gradle.kts",
        "versionCode = 28", 'versionName = "2.8.0-ai-narration-priority2-complete"',
    )
    print("M5_PLAYBACK_SOURCE_STRUCTURE_OK")


def main() -> None:
    source_gate()
    pure_runtime_gate()
    migration_gate()
    print("ROADMAP_MILESTONE5_PLAYBACK_COMPLETE_GATE=PASS")


if __name__ == "__main__":
    main()
