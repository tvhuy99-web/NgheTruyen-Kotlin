#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sqlite3
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} thiếu: {missing}")


def migration_smoke() -> None:
    db = sqlite3.connect(":memory:")
    db.execute(
        """CREATE TABLE audio_export_jobs (
        id TEXT NOT NULL PRIMARY KEY, storyId TEXT NOT NULL, storyTitle TEXT NOT NULL,
        chapterId TEXT, destinationUri TEXT NOT NULL, outputFormat TEXT NOT NULL DEFAULT 'WAV',
        mimeType TEXT NOT NULL DEFAULT 'audio/wav', state TEXT NOT NULL,
        completedSegments INTEGER NOT NULL, totalSegments INTEGER NOT NULL,
        errorMessage TEXT, updatedAt INTEGER NOT NULL)"""
    )
    db.execute(
        "INSERT INTO audio_export_jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        ("j1", "s1", "Truyện", "c1", "content://out", "WAV", "audio/wav", "FAILED", 3, 10, "x", 1234),
    )
    for sql in (
        "ALTER TABLE audio_export_jobs ADD COLUMN scope TEXT NOT NULL DEFAULT 'CACHED_STORY'",
        "ALTER TABLE audio_export_jobs ADD COLUMN startChapterIndex INTEGER NOT NULL DEFAULT -1",
        "ALTER TABLE audio_export_jobs ADD COLUMN endChapterIndex INTEGER NOT NULL DEFAULT 2147483647",
        "ALTER TABLE audio_export_jobs ADD COLUMN includeSceneMusic INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE audio_export_jobs ADD COLUMN author TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE audio_export_jobs ADD COLUMN sourceFingerprint TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE audio_export_jobs ADD COLUMN stage TEXT NOT NULL DEFAULT 'QUEUED'",
        "ALTER TABLE audio_export_jobs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0",
        "UPDATE audio_export_jobs SET createdAt = updatedAt WHERE createdAt = 0",
        "UPDATE audio_export_jobs SET scope = CASE WHEN chapterId IS NULL THEN 'CACHED_STORY' ELSE 'CURRENT_CHAPTER' END",
    ):
        db.execute(sql)
    row = db.execute(
        "SELECT scope,startChapterIndex,endChapterIndex,includeSceneMusic,author,sourceFingerprint,stage,createdAt,completedSegments FROM audio_export_jobs"
    ).fetchone()
    assert row == ("CURRENT_CHAPTER", -1, 2147483647, 0, "", "", "QUEUED", 1234, 3), row
    print("MILESTONE5_MIGRATION_10_11_OK")
    db.execute("ALTER TABLE audio_export_jobs ADD COLUMN packaging TEXT NOT NULL DEFAULT 'SINGLE_FILE'")
    db.execute("ALTER TABLE audio_export_jobs ADD COLUMN chapterMarkers INTEGER NOT NULL DEFAULT 1")
    row = db.execute("SELECT packaging,chapterMarkers,completedSegments FROM audio_export_jobs").fetchone()
    assert row == ("SINGLE_FILE", 1, 3), row
    print("MILESTONE5_MIGRATION_11_12_OK")


def kotlin_smoke() -> None:
    kotlinc = shutil.which("kotlinc")
    kotlin = shutil.which("kotlin")
    if not kotlinc or not kotlin:
        print("MILESTONE5_AUDIO_SMOKE_SKIPPED_NO_KOTLIN")
        return
    smoke = r'''
import vn.nghetruyen.app.audio.*
import java.io.*

fun le16(v:Int)=byteArrayOf(v.toByte(),(v ushr 8).toByte())
fun le32(v:Int)=byteArrayOf(v.toByte(),(v ushr 8).toByte(),(v ushr 16).toByte(),(v ushr 24).toByte())
fun wav(file:File, sample:Int, frames:Int=200) {
  FileOutputStream(file).use { o ->
    val data=ByteArray(frames*2)
    repeat(frames){ i -> data[i*2]=sample.toByte(); data[i*2+1]=(sample ushr 8).toByte() }
    o.write("RIFF".toByteArray()); o.write(le32(36+data.size)); o.write("WAVEfmt ".toByteArray());
    o.write(le32(16)); o.write(le16(1)); o.write(le16(1)); o.write(le32(22050)); o.write(le32(44100)); o.write(le16(2)); o.write(le16(16));
    o.write("data".toByteArray()); o.write(le32(data.size)); o.write(data)
  }
}
fun sample(file:File):Int { val s=WaveFileAssembler.inspect(file); RandomAccessFile(file,"r").use{ it.seek(s.dataOffset); val lo=it.read(); val hi=it.read(); return ((hi shl 8) or lo).toShort().toInt() } }
fun main(args:Array<String>) {
  val dir=File(args[0]); dir.mkdirs(); val voice=File(dir,"voice.wav"); val music=File(dir,"music.wav"); val mixed=File(dir,"mixed.wav")
  wav(voice,1000); wav(music,2000)
  Pcm16SceneMixer.mix(voice,listOf(SceneMixLayer(music,0,200,0.25f,0)),mixed)
  check(sample(mixed) in 1499..1501) { sample(mixed) }
  val out=ByteArrayOutputStream(); Id3v23Writer.write(out,Id3v23Writer.Metadata("Truyện","Tác giả","Truyện",chapters=listOf(Id3v23Writer.Chapter("Chương 1",0,1000)))); val b=out.toByteArray()
  check(String(b.copyOfRange(0,3))=="ID3"); check(String(b).contains("TIT2")); check(String(b).contains("TPE1")); check(String(b).contains("CHAP")); check(String(b).contains("CTOC"))
  println("MILESTONE5_AUDIO_CORE_SMOKE_OK")
}
'''
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        main = td / "Smoke.kt"
        main.write_text(smoke, encoding="utf-8")
        jar = td / "smoke.jar"
        sources = [
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/Id3v23Writer.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/audio/Pcm16SceneMixer.kt",
            main,
        ]
        subprocess.run([kotlinc, *map(str, sources), "-d", str(jar)], check=True, cwd=ROOT)
        subprocess.run([kotlin, "-cp", str(jar), "SmokeKt", str(td / "run")], check=True, cwd=ROOT)



def java_api_static() -> None:
    kotlinc = shutil.which("kotlinc")
    javac = shutil.which("javac")
    if not kotlinc or not javac:
        print("MILESTONE5_MP3_JAVA_API_SKIPPED_NO_COMPILER")
        return
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        package = td / "co/ntbl/lame/mp3"
        package.mkdir(parents=True)
        (package / "MPEGMode.java").write_text("package co.ntbl.lame.mp3; public enum MPEGMode { STEREO, JOINT_STEREO, DUAL_CHANNEL, MONO, NOT_SET }", encoding="utf-8")
        (package / "LameGlobalFlags.java").write_text("package co.ntbl.lame.mp3; public class LameGlobalFlags { public void setInNumChannels(int v){} public void setInSampleRate(int v){} public void setMode(MPEGMode v){} public void setBitRate(int v){} public void setQuality(int v){} public void setWriteId3tagAutomatic(boolean v){} public void setFindReplayGain(boolean v){} }", encoding="utf-8")
        (package / "Lame.java").write_text("package co.ntbl.lame.mp3; public class Lame { public static final int QUALITY_HIGH=2; public LameGlobalFlags getFlags(){return new LameGlobalFlags();} public int initParams(){return 0;} public int encodeBuffer(float[] l,float[] r,int n,byte[] b){return 0;} public int encodeFlush(byte[] b){return 0;} public void close(){} }", encoding="utf-8")
        classes = td / "classes"
        subprocess.run([javac, "-d", str(classes), *map(str, package.glob("*.java"))], check=True)
        subprocess.run([
            kotlinc,
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/Id3v23Writer.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/Mp3LameEncoder.kt"),
            "-cp", str(classes), "-d", str(td / "mp3.jar"),
        ], check=True)
    print("MILESTONE5_MP3_JAVA_API_STATIC_OK")

def main() -> None:
    require("app/build.gradle.kts", "versionCode = 28", 'versionName = "2.8.0-ai-narration-priority2-complete"', 'implementation("co.ntbl:lame:1.0.0")', "verifyReleaseSigning")
    require("app/src/main/java/vn/nghetruyen/app/core/model/Models.kt", 'MP3("mp3", "audio/mpeg")')
    require("app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt", "AudioExportPackaging", "ONE_FILE_PER_CHAPTER", "chapterMarkers")
    require("app/src/main/java/vn/nghetruyen/app/audio/Mp3LameEncoder.kt", "Lame()", "encodeBuffer", "encodeFlush", "Id3v23Writer")
    require("app/src/main/java/vn/nghetruyen/app/audio/Id3v23Writer.kt", 'frame("CHAP"', 'frame("CTOC"', "MAX_CHAPTERS")
    require("app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt", "applicationContext.filesDir", "source.sha256", "sourceFingerprint", "ONE_FILE_PER_CHAPTER", "findOrCreateDocument", "mixIfRequested", "chapterMarkers")
    require("app/src/main/java/vn/nghetruyen/app/audio/AndroidAudioTrackDecoder.kt", "MediaExtractor", "MediaCodec", "Pcm16Resampler", "MAX_DECODED_PCM_BYTES")
    require("app/src/main/java/vn/nghetruyen/app/audio/Pcm16SceneMixer.kt", "SceneMixLayer", "Streaming narration/music mixer", "MAX_LAYER_BYTES")
    require("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt", "version = 18", "MIGRATION_11_12", "MIGRATION_12_13", "packaging", "chapterMarkers")
    require("app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt", "FORMAT_VERSION = 15", "writeChapterTransforms", "writeVoiceAssignments", "writeSceneMusicTracks", "writeSceneMusicCues")
    require("app/src/main/java/vn/nghetruyen/app/diagnostics/PerformanceDiagnostics.kt", "Debug.getPss", "chapterSearchP95Millis", "10_000")
    require("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt", "resumeAudioExport", "AudioExportRequest", "runPerformanceDiagnostics")
    require("app/src/main/java/vn/nghetruyen/app/MainActivity.kt", "audioExportDirectoryLauncher", "OpenDocumentTree", "AudioExportRequest")
    require("app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt", "Xuất sách nói", "Mỗi chương một tệp", "Trộn nhạc cảnh", "chapter marker")
    require("THIRD_PARTY_NOTICES.md", "java-lame 1.0.0", "LGPL")
    require("app/src/test/java/vn/nghetruyen/app/audio/Mp3LameEncoderTest.kt", "encodesPcm16WaveWithId3AndMp3Frames", "0xe0")
    require("app/src/test/java/vn/nghetruyen/app/audio/Id3v23WriterChapterTest.kt", "writesOrderedChapterAndTableOfContentsFrames", "CHAP", "CTOC")
    require("docs/RELEASE_CHECKLIST.md", ":app:verifyReleaseSigning", "TalkBack", "Scene music")
    require("docs/PRIVACY_AND_DATA.md", "AI online", "Android Keystore", "không được đưa vào backup")
    migration_smoke()
    java_api_static()
    kotlin_smoke()
    print("MILESTONE5_FOUNDATION_CHECK_OK")
    print("MILESTONE5_COMPLETE_CHECK_OK")

if __name__ == "__main__":
    main()
