#!/usr/bin/env python3
from pathlib import Path
import shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root, rel, text):
    p=root/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text,encoding='utf-8'); return p

def main():
    if not K:
        print('PRIORITY2_COORDINATOR_STATIC_SKIPPED'); return
    with tempfile.TemporaryDirectory(prefix='p2-coordinator-') as d:
        r=Path(d)
        files=[
            ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',
            ROOT/'app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt',
            ROOT/'app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt',
            w(r,'vn/nghetruyen/app/core/model/Models.kt','''package vn.nghetruyen.app.core.model
 data class ChapterSummary(val id:String,val storyId:String,val index:Int)
 data class ChapterContent(val chapter:ChapterSummary,val paragraphs:List<String>)
 data class TtsVoiceOption(val name:String,val languageTag:String)
'''),
            w(r,'vn/nghetruyen/app/data/local/Entities.kt','''package vn.nghetruyen.app.data.local
 data class ChapterTransformEntity(val id:String,val storyId:String,val chapterId:String,val kind:String,val provider:String,val model:String,val sourceSha256:String,val transformedText:String,val updatedAt:Long)
 data class ChapterVoiceAssignmentEntity(val id:String,val storyId:String,val chapterId:String,val paragraphIndex:Int,val roleName:String,val confidence:Float,val speedAdjustPct:Float,val pitchAdjustPct:Float,val volumeAdjustPct:Float,val updatedAt:Long)
 data class SceneMusicCueEntity(val id:String,val storyId:String,val chapterId:String,val startParagraph:Int,val trackId:String,val volume:Float,val mood:String,val updatedAt:Long)
 data class SceneMusicTrackEntity(val id:String,val title:String,val tagsCsv:String)
 data class VoiceRoleEntity(val roleName:String,val aliasesCsv:String,val enginePackage:String?,val voiceName:String?,val languageTag:String,val rate:Float,val pitch:Float,val volume:Float,val expression:String,val expressionStrength:Float,val sonicSpeed:Float,val sonicPitch:Float,val enabled:Boolean)
 data class StoryAiProfileEntity(val overrideProvider:Boolean,val provider:String,val model:String)
'''),
            w(r,'vn/nghetruyen/app/data/settings/SettingsRepository.kt','''package vn.nghetruyen.app.data.settings
 data class Provider(val name:String)
 data class Ai(val provider:Provider,val model:String)
 data class Snapshot(val ttsEnginePackage:String?,val ttsVoiceName:String?,val ttsLanguageTag:String,val ttsRate:Float,val ttsPitch:Float,val ttsVolume:Float,val sonicDefaultSpeed:Float,val sonicDefaultPitch:Float,val aiOnline:Ai)
 open class SettingsRepository { open suspend fun snapshot():Snapshot=error("stub") }
'''),
            w(r,'vn/nghetruyen/app/data/repository/LibraryRepository.kt','''package vn.nghetruyen.app.data.repository
 import vn.nghetruyen.app.core.model.ChapterContent
 import vn.nghetruyen.app.data.local.*
 open class LibraryRepository {
  open suspend fun listEnabledSceneMusicTracks():List<SceneMusicTrackEntity> = emptyList()
  open suspend fun getChapterTransform(id:String,kind:String):ChapterTransformEntity?=null
  open suspend fun listVoiceAssignments(id:String):List<ChapterVoiceAssignmentEntity> = emptyList()
  open suspend fun listSceneMusicCues(id:String):List<SceneMusicCueEntity> = emptyList()
  open suspend fun listVoiceRoles(id:String):List<VoiceRoleEntity> = emptyList()
  open suspend fun saveVoiceRole(storyId:String,roleName:String,aliasesCsv:String,voiceName:String?,languageTag:String,rate:Float,pitch:Float,volume:Float,isNarrator:Boolean,enginePackage:String?,expression:String,expressionStrength:Float,sonicSpeed:Float,sonicPitch:Float,enabled:Boolean):Result<String> = Result.success("id")
  open suspend fun replaceVoiceAssignments(storyId:String,chapterId:String,items:List<ChapterVoiceAssignmentEntity>) {}
  open suspend fun saveChapterTransform(item:ChapterTransformEntity) {}
  open suspend fun replaceSceneMusicCues(storyId:String,chapterId:String,items:List<SceneMusicCueEntity>) {}
  open suspend fun loadPreviousCachedChapter(storyId:String,index:Int):ChapterContent?=null
  open suspend fun getStoryAiProfile(storyId:String):StoryAiProfileEntity?=null
 }
'''),
            w(r,'vn/nghetruyen/app/playback/TtsVoiceCatalog.kt','''package vn.nghetruyen.app.playback
 import vn.nghetruyen.app.core.common.AppResult
 import vn.nghetruyen.app.core.model.TtsVoiceOption
 open class TtsVoiceCatalog { open suspend fun load(engine:String?):AppResult<List<TtsVoiceOption>> = AppResult.Success(emptyList()) }
'''),
            w(r,'vn/nghetruyen/app/ai/OnlineAiServices.kt','''package vn.nghetruyen.app.ai
 import vn.nghetruyen.app.core.common.AppResult
 open class OnlineAiServices {
  open suspend fun planNarration(request:NarrationPlanRequest):AppResult<NarrationPlan> = AppResult.Success(NarrationPlan())
  open suspend fun planVoiceCast(storyId:String,chapterId:String,rawText:String):AppResult<VoiceCastPlan> = AppResult.Success(VoiceCastPlan(emptyList(),emptyList()))
 }
'''),
            w(r,'vn/nghetruyen/app/ai/ChapterAiWorkflow.kt','''package vn.nghetruyen.app.ai
 object ChapterAiWorkflow {
  const val KIND_VOICE_CAST="VOICE_CAST"; const val KIND_SCENE_MUSIC="SCENE_MUSIC"
  fun markedParagraphs(values:List<String>)=values.joinToString("\\n")
  fun sha256(values:List<String>)=values.joinToString("|").hashCode().toString()
 }
'''),
        ]
        res=subprocess.run([K,*map(str,files),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True,timeout=180)
        if res.returncode:
            print(res.stdout); print(res.stderr); raise SystemExit(res.returncode)
    print('PRIORITY2_COORDINATOR_STATIC_COMPILE_OK')
if __name__=='__main__': main()
