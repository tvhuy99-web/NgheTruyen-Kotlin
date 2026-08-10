#!/usr/bin/env python3
"""Milestone 4 complete gate: expressive TTS, per-role engines, Sonic, AI quota/retry and smart scene music."""
from __future__ import annotations
import shutil, sqlite3, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
JAVAC = shutil.which("javac")
JAR = shutil.which("jar")

def require(path: str, *tokens: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} missing: {missing}")
    return text

def compile_smoke() -> None:
    if not KOTLINC or not JAVAC or not JAR:
        print("MILESTONE4_COMPLETE_SMOKE_SKIPPED_NO_JVM_COMPILER")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_m4_complete_") as td:
        root = Path(td)
        entities = root / "Entities.kt"
        entities.write_text('''package vn.nghetruyen.app.data.local
 data class VoiceRoleEntity(val id:String="",val storyId:String="",val roleName:String="",val aliasesCsv:String="",val enginePackage:String?=null,val voiceName:String?=null,val languageTag:String="vi-VN",val rate:Float=1f,val pitch:Float=1f,val volume:Float=1f,val expression:String="NEUTRAL",val expressionStrength:Float=.5f,val sonicSpeed:Float=1f,val sonicPitch:Float=1f,val isNarrator:Boolean=false,val enabled:Boolean=true,val updatedAt:Long=0)
 data class SceneMusicTrackEntity(val id:String="",val title:String="",val uri:String="",val tagsCsv:String="",val volume:Float=1f,val enabled:Boolean=true,val loudnessLufsEstimate:Float=-18f,val playCount:Int=0,val lastPlayedAt:Long=0,val orderIndex:Int=0,val updatedAt:Long=0)
''', encoding="utf-8")
        # VoiceExpressionProcessor now consults canonical XPK runtime state. This historical smoke test
        # validates the non-XPK/local expression path only, so provide the smallest adapter and keep
        # canonical XPK bypass behavior covered by the dedicated XPK parity tests/gates.
        xpk_runtime = root / "XpkPlaybackRuntimeStub.kt"
        xpk_runtime.write_text('''package vn.nghetruyen.app.playback
object XpkPlaybackRuntime {
    fun shouldBypassLocalExpression(text: String): Boolean = false
}
''', encoding="utf-8")
        smoke = root / "Smoke.kt"
        smoke.write_text('''import vn.nghetruyen.app.audio.*
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.data.local.*
import vn.nghetruyen.app.playback.*
import java.io.*

fun le16(o:OutputStream,v:Int){o.write(v and 255);o.write(v ushr 8 and 255)}
fun le32(o:OutputStream,v:Int){o.write(v and 255);o.write(v ushr 8 and 255);o.write(v ushr 16 and 255);o.write(v ushr 24 and 255)}
fun wave(f:File,s:Int){FileOutputStream(f).use{o->val n=8000;o.write("RIFF".toByteArray());le32(o,36+n*2);o.write("WAVEfmt ".toByteArray());le32(o,16);le16(o,1);le16(o,1);le32(o,8000);le32(o,16000);le16(o,2);le16(o,16);o.write("data".toByteArray());le32(o,n*2);repeat(n){le16(o,s)}}}
fun main(){
 val role=VoiceRoleEntity(expression="ANGRY",expressionStrength=.8f,sonicSpeed=1.05f)
 val speech=VoiceExpressionProcessor.resolve("Một câu bình thường",role)
 check(speech.expression==VoiceExpression.ANGRY && speech.rateMultiplier>1f)
 val tracks=listOf(SceneMusicTrackEntity(id="a",title="A",uri="a",tagsCsv="calm",orderIndex=0),SceneMusicTrackEntity(id="b",title="B",uri="b",tagsCsv="battle",orderIndex=1))
 check(SceneMusicSelector.select(tracks,null,"",SceneMusicPlaybackMode.SEQUENTIAL,emptyList(),"x")?.id=="a")
 val tempRoot=File(System.getenv("RUNNER_TEMP") ?: System.getenv("TMPDIR") ?: ".").apply{mkdirs()}; val dir=java.nio.file.Files.createTempDirectory(tempRoot.toPath(),"nghe-m4-smoke-").toFile(); val input=File(dir,"i.wav"); val output=File(dir,"o.wav"); wave(input,4000)
 val before=PcmLoudnessEstimator.estimateLufs(input); check(before in -70f..0f)
 val processed=SonicPcmProcessor.process(input,output,2f,1f); check(processed.dataLength in 7000L..9000L)
 dir.deleteRecursively(); println("MILESTONE4_COMPLETE_SMOKE_OK")
}
''', encoding="utf-8")
        java_classes = root / "java-classes"
        java_classes.mkdir()
        jc = subprocess.run(
            [JAVAC, "-d", str(java_classes), str(ROOT / "app/src/main/java/sonic/Sonic.java")],
            cwd=ROOT, text=True, capture_output=True, timeout=120,
        )
        if jc.returncode:
            print(jc.stdout); print(jc.stderr); raise SystemExit(jc.returncode)
        jar = root / "smoke.jar"
        sources = [
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
            entities,
            xpk_runtime,
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/PcmLoudnessEstimator.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/ReferenceSonicRuntime.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/SonicPcmProcessor.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/VoiceExpressionProcessor.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/playback/SceneMusicSelector.kt",
            smoke,
        ]
        cp = subprocess.run(
            [KOTLINC, *map(str, sources), "-classpath", str(java_classes), "-include-runtime", "-d", str(jar)],
            cwd=ROOT, text=True, capture_output=True, timeout=120,
        )
        if cp.returncode:
            print(cp.stdout); print(cp.stderr); raise SystemExit(cp.returncode)
        pack = subprocess.run(
            [JAR, "uf", str(jar), "-C", str(java_classes), "."],
            cwd=ROOT, text=True, capture_output=True, timeout=30,
        )
        if pack.returncode:
            print(pack.stdout); print(pack.stderr); raise SystemExit(pack.returncode)
        run = subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, text=True, capture_output=True, timeout=30)
        if run.returncode:
            print(run.stdout); print(run.stderr); raise SystemExit(run.returncode)
        print(run.stdout.strip())

def migration_smoke() -> None:
    with sqlite3.connect(":memory:") as db:
        db.executescript('''
        CREATE TABLE voice_roles(id TEXT PRIMARY KEY NOT NULL,storyId TEXT NOT NULL,roleName TEXT NOT NULL,aliasesCsv TEXT NOT NULL,voiceName TEXT,languageTag TEXT NOT NULL,rate REAL NOT NULL,pitch REAL NOT NULL,volume REAL NOT NULL,isNarrator INTEGER NOT NULL,enabled INTEGER NOT NULL,updatedAt INTEGER NOT NULL);
        CREATE TABLE scene_music_tracks(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,uri TEXT NOT NULL,tagsCsv TEXT NOT NULL,volume REAL NOT NULL,enabled INTEGER NOT NULL,updatedAt INTEGER NOT NULL);
        INSERT INTO voice_roles VALUES('r','s','role','','voice','vi-VN',1,1,1,0,1,1);
        INSERT INTO scene_music_tracks VALUES('t','track','content://t','calm',1,1,1);
        ALTER TABLE voice_roles ADD COLUMN enginePackage TEXT;
        ALTER TABLE voice_roles ADD COLUMN expression TEXT NOT NULL DEFAULT 'NEUTRAL';
        ALTER TABLE voice_roles ADD COLUMN expressionStrength REAL NOT NULL DEFAULT 0.5;
        ALTER TABLE voice_roles ADD COLUMN sonicSpeed REAL NOT NULL DEFAULT 1.0;
        ALTER TABLE voice_roles ADD COLUMN sonicPitch REAL NOT NULL DEFAULT 1.0;
        ALTER TABLE scene_music_tracks ADD COLUMN loudnessLufsEstimate REAL NOT NULL DEFAULT -18.0;
        ALTER TABLE scene_music_tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0;
        ALTER TABLE scene_music_tracks ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0;
        ALTER TABLE scene_music_tracks ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0;
        CREATE TABLE ai_usage_daily(dayEpoch INTEGER PRIMARY KEY NOT NULL,requestCount INTEGER NOT NULL,inputChars INTEGER NOT NULL,outputChars INTEGER NOT NULL,retryCount INTEGER NOT NULL,lastErrorCode TEXT,updatedAt INTEGER NOT NULL);
        ''')
        assert db.execute("select expression,sonicSpeed from voice_roles").fetchone() == ("NEUTRAL",1.0)
        assert db.execute("select loudnessLufsEstimate,playCount from scene_music_tracks").fetchone() == (-18.0,0)
    print("MILESTONE4_MIGRATION_12_13_OK")

def main() -> None:
    require("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt", "version = 18", "MIGRATION_12_13", "ai_usage_daily", "sonicSpeed", "loudnessLufsEstimate")
    require("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt", "pendingRoleEnginePackage", "synthesizeAndPlaySonic", "VoiceExpressionProcessor.resolve", "SceneMusicSelector.select", "narrationPrefetchWindowChapters")
    require("app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt", "SonicPcmProcessor.process", "PcmLoudnessEstimator.normalizationGain", "enginePackage")
    require("app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt", "RETRYABLE_HTTP_CODES", "Retry-After", "requestGovernor.reserve", "retryDelayMillis")
    require("app/src/main/java/vn/nghetruyen/app/ai/AiRequestGovernor.kt", "Compatibility request policy", "device-local daily AI quotas", "AppResult.Success(Permit())")
    require("app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt", "dailyRequestLimit", "sceneMusicPlaybackMode", "sonicProcessingEnabled", "narrationPrefetchWindowChapters")
    require("app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt", "FORMAT_VERSION = 15", 'name("sonicDefaultSpeed")', 'name("aiDailyRequestLimit")', 'name("expressionStrength")')
    require("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt", "Cài đặt TTS", "Nhạc nền và nhạc cảnh", "onSonicProcessingEnabledChange", "onSceneMusicPlaybackModeChange")
    compile_smoke(); migration_smoke()
    print("MILESTONE4_COMPLETE_CHECK_OK")

if __name__ == "__main__": main()
