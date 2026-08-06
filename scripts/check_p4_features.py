#!/usr/bin/env python3
"""Offline P4 gate for local VietPhrase, AI response protocols and safety wiring."""
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

def run(cmd: list[str]) -> None:
    result = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
    if result.stdout: print(result.stdout.strip())
    if result.returncode:
        print(result.stderr)
        raise SystemExit(result.returncode)

def pure_gate(temp: Path) -> None:
    entity = write(temp, "vn/nghetruyen/app/data/local/VietPhraseEntity.kt", '''package vn.nghetruyen.app.data.local
data class VietPhraseEntity(val id:Long=0,val source:String,val target:String,val priority:Int=0,val enabled:Boolean=true,val createdAt:Long=0,val updatedAt:Long=0)
''')
    runner = write(temp, "P4Smoke.kt", r'''import vn.nghetruyen.app.ai.*
import vn.nghetruyen.app.data.local.VietPhraseEntity
fun main(){
 val rules=listOf(
  VietPhraseEntity(1,"Thiên","Trời",1,true,0,0),
  VietPhraseEntity(2,"Thiên Đạo","Đạo Trời",10,true,0,0),
  VietPhraseEntity(3,"Đạo Trời","không cascade",100,true,0,0)
 )
 check(VietPhraseProcessor.apply("Thiên Đạo và RAIL AI",rules+VietPhraseEntity(4,"AI","ây ai",0,true,0,0))=="Đạo Trời và RAIL ây ai")
 val file=VietPhraseFileCodec.decode("Thiên Đạo\tĐạo Trời\t10\ttrue\nKim Đan=Kim Đan")
 check(file.size==2 && file.first().priority==10)
 val roundTrip=VietPhraseFileCodec.decode(VietPhraseFileCodec.encode(listOf(VietPhraseEntity(9,"A\\B","X\tY\nZ",3,true,0,0)))).single()
 check(roundTrip.source=="A\\B" && roundTrip.target=="X\tY\nZ")
 val marked=ChapterAiWorkflow.markedParagraphs(listOf("Một","Hai"))
 check(marked.contains("[[P:0]]") && marked.contains("[[P:1]]"))
 check(ChapterAiWorkflow.parseMarkedParagraphs("[[P:1]] B\n[[P:0]] A",2)==listOf("A","B"))
 check(runCatching{ChapterAiWorkflow.parseMarkedParagraphs("[[P:0]] A",2)}.isFailure)
 check(ChapterAiWorkflow.sha256(listOf("ab","c"))!=ChapterAiWorkflow.sha256(listOf("a","bc")))
 check(ChapterAiWorkflow.translationFingerprint(listOf("A"),"https://a.example/v1/chat","m1","")!=ChapterAiWorkflow.translationFingerprint(listOf("A"),"https://a.example/v1/chat","m2",""))
 val cast=AiLineProtocol.parseVoiceCast("ROLE|Lan|Tiểu Lan\nASSIGN|3|Lan|0.9\nASSIGN|3|Khác|0.8")
 check(cast.roles.any{it.character=="Lan"} && cast.roles.any{it.character.equals("Người kể chuyện",true)})
 check(cast.assignments.size==1 && cast.assignments.single().paragraphIndex==3)
 val cues=AiLineProtocol.parseSceneCues("CUE|8|calm|2|cao\nCUE|2|soft|-1|êm\nCUE|2|dup|0.4|x")
 check(cues.map{it.startParagraph}==listOf(2,8) && cues[0].volume==0f && cues[1].volume==1f)
 println("P4_PURE_FEATURE_CHECK_OK")
}
''')
    out = temp / "p4.jar"
    run([KOTLINC, str(entity),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiLineProtocol.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/ChapterAiWorkflow.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseModels.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/VietPhraseProcessor.kt"),
         str(ROOT / "app/src/main/java/vn/nghetruyen/app/ai/VietPhraseFileCodec.kt"),
         str(runner), "-d", str(out)])
    run([KOTLIN, "-classpath", str(out), "P4SmokeKt"])

def source_gate() -> None:
    checks = {
      "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt": ["version = 18", "MIGRATION_5_6", "viet_phrase", "chapter_transforms", "chapter_voice_assignments", "scene_music_tracks", "scene_music_cues"],
      "app/src/main/java/vn/nghetruyen/app/ai/AiCredentialStore.kt": ["AndroidKeyStore", "AES/GCM/NoPadding", "updateAAD", "apiKey"],
      "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt": ["consentGranted", "AI_CONSENT_REQUIRED", ".dns(AiPublicDns)", "followRedirects(false)", "MAX_RESPONSE_CHARS"],
      "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt": ["applyVietPhraseToCurrentChapter", "aiTranslate", "voiceCast", "planSceneMusic", "sourceSha256"],
      "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt": ["voiceAssignments", "sceneMusicCues", "updateSceneMusicForParagraph"],
      "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt": ["listVoiceAssignments", "paragraphIndex"],
      "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt": ["FORMAT_VERSION = 15", "writeVietPhrase", "consentGranted = false", "enabled = false"],
    }
    for rel, markers in checks.items():
        text=(ROOT/rel).read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text: raise SystemExit(f"P4 marker missing in {rel}: {marker}")
    if "apiKey" in (ROOT/"app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt").read_text(encoding="utf-8"):
        raise SystemExit("P4 backup must not reference API key")
    print("P4_SCHEMA_SECURITY_WIRING_CHECK_OK")

def main() -> None:
    if not KOTLINC or not KOTLIN:
        print("P4_FEATURE_CHECK_SKIPPED: Kotlin CLI unavailable")
    else:
        with tempfile.TemporaryDirectory(prefix="nghe-p4-") as name:
            pure_gate(Path(name))
    source_gate()
    print("P4_FEATURE_CHECK_OK")

if __name__ == "__main__": main()
