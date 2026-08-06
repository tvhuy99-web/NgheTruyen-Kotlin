#!/usr/bin/env python3
"""Offline P3 gate: PCM normalization, deterministic voice roles, M4A platform wiring, and schema markers."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
KOTLIN = shutil.which("kotlin")

def write(root: Path, rel: str, text: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path

def run(cmd: list[str], cwd: Path | None = None) -> None:
    result = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    if result.stdout:
        print(result.stdout.strip())
    if result.returncode:
        print(result.stderr)
        raise SystemExit(result.returncode)

def pure_gate(temp: Path) -> None:
    stub = write(temp, "vn/nghetruyen/app/data/local/VoiceRoleEntity.kt", '''package vn.nghetruyen.app.data.local
data class VoiceRoleEntity(
 val id:String="", val storyId:String="", val roleName:String, val aliasesCsv:String="",
 val voiceName:String?=null, val languageTag:String="vi-VN", val rate:Float=1f,
 val pitch:Float=1f, val volume:Float=1f, val isNarrator:Boolean=false,
 val enabled:Boolean=true, val updatedAt:Long=0L,
)
''')
    main = write(temp, "P3Smoke.kt", r'''import java.io.File
import java.io.FileOutputStream
import vn.nghetruyen.app.audio.Pcm16WaveConverter
import vn.nghetruyen.app.audio.WaveFileAssembler
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.playback.VoiceRoleResolver

private fun u16(out:FileOutputStream,v:Int){out.write(v and 255);out.write((v ushr 8) and 255)}
private fun u32(out:FileOutputStream,v:Int){repeat(4){out.write((v ushr (8*it)) and 255)}}
private fun wav8(file:File){
 val data=byteArrayOf(0,64,128.toByte(),192.toByte(),255.toByte())
 FileOutputStream(file).use { out ->
  out.write("RIFF".toByteArray());u32(out,36+data.size);out.write("WAVEfmt ".toByteArray())
  u32(out,16);u16(out,1);u16(out,1);u32(out,8000);u32(out,8000);u16(out,1);u16(out,8)
  out.write("data".toByteArray());u32(out,data.size);out.write(data);if(data.size%2==1)out.write(0)
 }
}
fun main(){
 val dir=createTempDir(prefix="p3-")
 val source=File(dir,"source.wav");val output=File(dir,"output.wav");wav8(source)
 val converted=Pcm16WaveConverter.convert(source,output,0.5f)
 check(converted.audioFormat==1 && converted.bitsPerSample==16 && converted.channelCount==1)
 check(converted.sampleRate==8000L && converted.dataLength==10L)
 check(WaveFileAssembler.inspect(output).dataLength==10L)
 val narrator=VoiceRoleEntity(id="n",storyId="s",roleName="Người kể chuyện",isNarrator=true)
 val linh=VoiceRoleEntity(id="l",storyId="s",roleName="Ái Linh",aliasesCsv="Linh")
 val routed=VoiceRoleResolver.resolve("Ái Linh: Xin chào",listOf(narrator,linh))
 check(routed.role?.id=="l" && routed.spokenText=="Xin chào")
 check(VoiceRoleResolver.resolve("Một đoạn kể chuyện",listOf(narrator,linh)).role?.id=="n")
 dir.deleteRecursively()
 println("P3_PURE_AUDIO_CHECK_OK")
}
''')
    jar = temp / "p3-pure.jar"
    run([
        KOTLINC, str(stub),
        str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt"),
        str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/Pcm16WaveConverter.kt"),
        str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/VoiceRoleResolver.kt"),
        str(main), "-d", str(jar),
    ])
    run([KOTLIN, "-classpath", str(jar), "P3SmokeKt"])

def m4a_gate(temp: Path) -> None:
    media = write(temp, "android/media/Media.kt", r'''package android.media
import java.nio.ByteBuffer
class MediaFormat {
 fun setInteger(key:String,value:Int){}
 companion object {
  const val MIMETYPE_AUDIO_AAC="audio/mp4a-latm"; const val KEY_AAC_PROFILE="aac-profile"
  const val KEY_BIT_RATE="bitrate"; const val KEY_MAX_INPUT_SIZE="max-input-size"
  fun createAudioFormat(mime:String,sampleRate:Int,channels:Int)=MediaFormat()
 }
}
class MediaCodecInfo { object CodecProfileLevel { const val AACObjectLC=2 } }
class MediaCodec private constructor(){
 class BufferInfo { var flags:Int=0;var size:Int=0;var offset:Int=0;var presentationTimeUs:Long=0 }
 val outputFormat=MediaFormat()
 fun configure(f:MediaFormat,s:Any?,c:Any?,flags:Int){};fun start(){};fun stop(){};fun release(){}
 fun dequeueInputBuffer(timeout:Long)=-1;fun getInputBuffer(i:Int):ByteBuffer?=ByteBuffer.allocate(65536)
 fun queueInputBuffer(i:Int,o:Int,s:Int,p:Long,f:Int){};fun dequeueOutputBuffer(info:BufferInfo,t:Long)=INFO_TRY_AGAIN_LATER
 fun getOutputBuffer(i:Int):ByteBuffer?=ByteBuffer.allocate(65536);fun releaseOutputBuffer(i:Int,r:Boolean){}
 companion object { const val CONFIGURE_FLAG_ENCODE=1;const val BUFFER_FLAG_END_OF_STREAM=4;const val BUFFER_FLAG_CODEC_CONFIG=2;const val INFO_TRY_AGAIN_LATER=-1;const val INFO_OUTPUT_FORMAT_CHANGED=-2;fun createEncoderByType(m:String)=MediaCodec() }
}
class MediaMuxer(path:String,format:Int){fun addTrack(f:MediaFormat)=0;fun start(){};fun stop(){};fun release(){};fun writeSampleData(i:Int,b:ByteBuffer,info:MediaCodec.BufferInfo){};object OutputFormat{const val MUXER_OUTPUT_MPEG_4=0}}
''')
    run([
        KOTLINC, str(media),
        str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt"),
        str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/M4aAacEncoder.kt"),
        "-d", str(temp / "p3-m4a.jar"),
    ])
    print("P3_M4A_STATIC_COMPILE_OK")

def source_gate() -> None:
    db = (ROOT / "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt").read_text(encoding="utf-8")
    settings = (ROOT / "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt").read_text(encoding="utf-8")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    required = [
        "version = 18", "MIGRATION_4_5", "CREATE TABLE IF NOT EXISTS voice_roles",
        "ALTER TABLE story_tts_profiles ADD COLUMN volume", "ALTER TABLE audio_export_jobs ADD COLUMN outputFormat",
    ]
    for marker in required:
        if marker not in db: raise SystemExit(f"P3 schema marker missing: {marker}")
    for marker in ["ttsEnginePackage", "ttsVolume", "backgroundMusicUri", "audioInterruptionMode"]:
        if marker not in settings: raise SystemExit(f"P3 setting missing: {marker}")
    if 'mediaProcessing' not in manifest:
        raise SystemExit("P3 mediaProcessing foreground service type missing")
    print("P3_SCHEMA_WIRING_CHECK_OK")

def main() -> None:
    if not KOTLINC or not KOTLIN:
        print("P3_FEATURE_CHECK_SKIPPED: Kotlin CLI unavailable")
        return
    with tempfile.TemporaryDirectory(prefix="nghe-p3-") as name:
        temp = Path(name)
        pure_gate(temp)
        m4a_gate(temp)
    source_gate()
    print("P3_FEATURE_CHECK_OK")

if __name__ == "__main__":
    main()
