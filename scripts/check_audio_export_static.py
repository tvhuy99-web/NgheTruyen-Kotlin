from pathlib import Path
import tempfile, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')
if not K:
 print('AUDIO_EXPORT_STATIC_SKIPPED: kotlinc not found')
 raise SystemExit(0)
lib=Path(K).resolve().parents[1]/'lib/kotlinx-coroutines-core-jvm.jar'
def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
with tempfile.TemporaryDirectory() as d:
 t=Path(d); files=[]
 files += [w(t,'android/content/Core.kt','''package android.content
import java.io.File
import java.io.OutputStream
open class Context { open val applicationContext:Context get()=this; val cacheDir:File=File("/tmp"); val filesDir:File=File("/tmp/files"); val contentResolver=ContentResolver(); fun <T> getSystemService(c:Class<T>):T=error("stub") }
open class Intent { constructor(); constructor(c:Context,k:Class<*>); fun putExtra(k:String,v:String)=this }
class Cursor:java.io.Closeable { fun getColumnIndexOrThrow(n:String)=0; fun moveToNext()=false; fun getString(i:Int)=""; override fun close(){} }
class ContentResolver { fun openOutputStream(uri:android.net.Uri,mode:String):OutputStream?=java.io.ByteArrayOutputStream(); fun query(uri:android.net.Uri,p:Array<String>,s:String?,a:Array<String>?,o:String?):Cursor?=Cursor() }
'''),
 w(t,'android/net/Uri.kt','''package android.net
data class Uri(private val v:String){ companion object { fun parse(v:String)=Uri(v) }; override fun toString()=v }
'''),
 w(t,'android/provider/DocumentsContract.kt','''package android.provider
import android.content.ContentResolver
import android.net.Uri
object DocumentsContract { object Document { const val COLUMN_DOCUMENT_ID="document_id"; const val COLUMN_DISPLAY_NAME="display_name" }; fun getTreeDocumentId(uri:Uri)="root"; fun buildDocumentUriUsingTree(uri:Uri,id:String)=uri; fun buildChildDocumentsUriUsingTree(uri:Uri,id:String)=uri; fun createDocument(r:ContentResolver,p:Uri,m:String,n:String):Uri?=p }
'''),
 w(t,'android/media/Media.kt','''package android.media
import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
object AudioFormat { const val ENCODING_PCM_16BIT=2; const val ENCODING_PCM_FLOAT=4 }
class MediaFormat { private val values=mutableMapOf<String,Any>(); fun getString(k:String)=values[k] as? String; fun getInteger(k:String)=(values[k] as? Int) ?: 44100; fun containsKey(k:String)=values.containsKey(k); companion object { const val KEY_MIME="mime"; const val KEY_SAMPLE_RATE="sample-rate"; const val KEY_CHANNEL_COUNT="channel-count"; const val KEY_PCM_ENCODING="pcm-encoding" } }
class MediaExtractor { val trackCount=1; fun setDataSource(c:Context,u:Uri,h:Map<String,String>?){ }; fun getTrackFormat(i:Int)=MediaFormat(); fun selectTrack(i:Int){}; fun readSampleData(b:ByteBuffer,o:Int)=-1; val sampleTime:Long=0; fun advance()=false; fun release(){} }
class MediaCodec { class BufferInfo { var size=0; var offset=0; var flags=0 }; val outputFormat=MediaFormat(); fun configure(f:MediaFormat,s:Any?,c:Any?,flags:Int){}; fun start(){}; fun stop(){}; fun release(){}; fun dequeueInputBuffer(t:Long)=0; fun getInputBuffer(i:Int)=ByteBuffer.allocate(1024); fun queueInputBuffer(i:Int,o:Int,s:Int,p:Long,f:Int){}; fun dequeueOutputBuffer(info:BufferInfo,t:Long)=BUFFER_FLAG_END_OF_STREAM; fun getOutputBuffer(i:Int)=ByteBuffer.allocate(0); fun releaseOutputBuffer(i:Int,r:Boolean){}; companion object { const val BUFFER_FLAG_END_OF_STREAM=4; const val INFO_TRY_AGAIN_LATER=-1; const val INFO_OUTPUT_FORMAT_CHANGED=-2; fun createDecoderByType(m:String)=MediaCodec() } }
'''),
 w(t,'android/content/pm/ServiceInfo.kt','''package android.content.pm
object ServiceInfo { const val FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING=8192 }
'''),
 w(t,'android/os/Bundle.kt','''package android.os
class Bundle
'''),
 w(t,'android/app/App.kt','''package android.app
import android.content.Context
import android.content.Intent
open class Notification { class Builder(c:Context,id:String){ fun setSmallIcon(i:Int)=this; fun setContentTitle(s:String)=this; fun setContentText(s:String)=this; fun setContentIntent(p:PendingIntent)=this; fun setOnlyAlertOnce(b:Boolean)=this; fun setOngoing(b:Boolean)=this; fun setProgress(m:Int,p:Int,i:Boolean)=this; fun addAction(a:Action)=this; fun build()=Notification() }; class Action { class Builder(i:Any?,s:String,p:PendingIntent){ fun build()=Action() } } }
class NotificationChannel(id:String,name:String,importance:Int)
open class NotificationManager { fun createNotificationChannel(c:NotificationChannel){}; companion object { const val IMPORTANCE_LOW=2 } }
class PendingIntent { companion object { const val FLAG_UPDATE_CURRENT=1; const val FLAG_IMMUTABLE=2; fun getActivity(c:Context,r:Int,i:Intent,f:Int)=PendingIntent() } }
'''),
 w(t,'android/speech/tts/Tts.kt','''package android.speech.tts
import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.Locale
open class UtteranceProgressListener { open fun onStart(id:String?){}; open fun onDone(id:String?){}; open fun onError(id:String?){}; open fun onError(id:String?,code:Int){} }
class Voice(val name:String="")
class TextToSpeech { val voices:Set<Voice>?=emptySet(); constructor(c:Context,listener:(Int)->Unit){listener(SUCCESS)}; constructor(c:Context,listener:(Int)->Unit,enginePackage:String){listener(SUCCESS)}; fun setOnUtteranceProgressListener(l:UtteranceProgressListener){}; fun setLanguage(l:Locale)=0; fun setVoice(v:Voice)=0; fun setSpeechRate(v:Float)=0; fun setPitch(v:Float)=0; fun synthesizeToFile(t:CharSequence,b:Bundle,f:File,id:String)=0; fun stop(){}; fun shutdown(){}; companion object { const val SUCCESS=0; const val ERROR=-1; const val LANG_MISSING_DATA=-1; const val LANG_NOT_SUPPORTED=-2; fun getMaxSpeechInputLength()=4000 } }
'''),
 w(t,'androidx/work/Work.kt','''package androidx.work
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import java.util.UUID
class Data(private val m:Map<String,Any?> = emptyMap()){ fun getString(k:String)=m[k] as? String }
fun workDataOf(vararg p:Pair<String,Any>)=Data(mapOf(*p))
open class WorkerParameters
open class CoroutineWorker(val applicationContext:Context,p:WorkerParameters){ val inputData=Data(); val runAttemptCount=0; val isStopped=false; val id:UUID=UUID.randomUUID(); open suspend fun doWork():Result=Result.success(); suspend fun setForeground(f:ForegroundInfo){}; suspend fun setProgress(d:Data){}; class Result { companion object { fun success(d:Data=Data())=Result(); fun failure(d:Data=Data())=Result(); fun retry()=Result() } } }
class ForegroundInfo(id:Int,n:Notification,type:Int)
enum class ExistingWorkPolicy { REPLACE }
class WorkRequest(val id:UUID=UUID.randomUUID())
class OneTimeWorkRequestBuilder<T>{ fun setInputData(d:Data)=this; fun addTag(t:String)=this; fun build()=WorkRequest() }
class WorkManager { fun enqueueUniqueWork(n:String,p:ExistingWorkPolicy,r:WorkRequest){}; fun cancelUniqueWork(n:String){}; fun createCancelPendingIntent(id:UUID)=PendingIntent(); companion object { fun getInstance(c:Context)=WorkManager() } }
'''),
 w(t,'vn/nghetruyen/app/App.kt','''package vn.nghetruyen.app
import android.content.Context
class MainActivity
class NgheTruyenApplication:Context(){ val container=Container() }
class Container { val libraryRepository=vn.nghetruyen.app.data.repository.LibraryRepository(); val settingsRepository=vn.nghetruyen.app.data.settings.SettingsRepository() }
object R { object drawable { const val ic_stat_reader=1 } }
'''),
 w(t,'vn/nghetruyen/app/ui/reference/ReferenceVoiceRoleExtras.kt','''package vn.nghetruyen.app.ui.reference
import android.content.Context
data class ReferenceVoiceRoleExtra(val processingMethod:String="system",val sonicAccurate:Boolean=false)
object ReferenceVoiceRoleExtras { fun load(context:Context,roleId:String?)=ReferenceVoiceRoleExtra() }
'''),
 w(t,'vn/nghetruyen/app/core/model/Models.kt','''package vn.nghetruyen.app.core.model
enum class DownloadState { QUEUED,RUNNING,COMPLETED,FAILED,CANCELLED }
enum class AudioExportFormat(val extension:String,val mimeType:String){ WAV("wav","audio/wav"), M4A("m4a","audio/mp4"), MP3("mp3","audio/mpeg") }
data class ChapterSummary(val id:String="",val storyId:String="",val index:Int=0,val title:String="",val url:String="")
data class ChapterContent(val chapter:ChapterSummary=ChapterSummary(),val paragraphs:List<String> = emptyList())
enum class VoiceExpression { NEUTRAL,CALM,WARM,SAD,TENSE,ANGRY,EXCITED,WHISPER }
'''),
 w(t,'vn/nghetruyen/app/data/local/Entities.kt','''package vn.nghetruyen.app.data.local
data class PronunciationEntity(val original:String="",val replacement:String="",val enabled:Boolean=true)
data class StoryTtsProfileEntity(val storyId:String,val rate:Float,val pitch:Float,val volume:Float=1f,val enginePackage:String?=null,val voiceName:String?,val languageTag:String,val updatedAt:Long)
data class VoiceRoleEntity(val id:String="",val storyId:String="",val roleName:String="",val aliasesCsv:String="",val enginePackage:String?=null,val voiceName:String?=null,val languageTag:String="vi-VN",val rate:Float=1f,val pitch:Float=1f,val volume:Float=1f,val expression:String="NEUTRAL",val expressionStrength:Float=0.5f,val sonicSpeed:Float=1f,val sonicPitch:Float=1f,val isNarrator:Boolean=false,val enabled:Boolean=true,val updatedAt:Long=0)
data class ChapterVoiceAssignmentEntity(val id:String="",val storyId:String="",val chapterId:String="",val paragraphIndex:Int=0,val roleName:String="",val confidence:Float=0f,val speedAdjustPct:Float=0f,val pitchAdjustPct:Float=0f,val volumeAdjustPct:Float=0f,val updatedAt:Long=0)
data class ChapterTransformEntity(val id:String="",val storyId:String="",val chapterId:String="",val kind:String="",val provider:String="",val model:String="",val sourceSha256:String="",val transformedText:String="",val updatedAt:Long=0)
data class SceneMusicCueEntity(val id:String="",val storyId:String="",val chapterId:String="",val startParagraph:Int=0,val trackId:String="",val volume:Float=0.2f,val mood:String="",val updatedAt:Long=0)
data class SceneMusicTrackEntity(val id:String="",val title:String="",val uri:String="",val tagsCsv:String="",val volume:Float=1f,val enabled:Boolean=true,val loudnessLufsEstimate:Float=-18f,val playCount:Int=0,val lastPlayedAt:Long=0,val orderIndex:Int=0,val updatedAt:Long=0)
data class ChapterEntity(val id:String="",val storyId:String="",val chapterIndex:Int=0,val title:String="",val remoteUrl:String="",val content:String?=null,val downloadedAt:Long?=null)
data class AudioExportJobEntity(val id:String,val storyId:String,val storyTitle:String,val chapterId:String?,val destinationUri:String,val outputFormat:String="WAV",val mimeType:String="audio/wav",val scope:String="CACHED_STORY",val startChapterIndex:Int=-1,val endChapterIndex:Int=Int.MAX_VALUE,val includeSceneMusic:Boolean=false,val packaging:String="SINGLE_FILE",val chapterMarkers:Boolean=true,val author:String="",val sourceFingerprint:String="",val stage:String="QUEUED",val state:String,val completedSegments:Int,val totalSegments:Int,val errorMessage:String?,val createdAt:Long=0,val updatedAt:Long)
'''),
 w(t,'vn/nghetruyen/app/data/settings/Settings.kt','''package vn.nghetruyen.app.data.settings
data class AppSettings(val ttsRate:Float=1f,val ttsPitch:Float=1f,val ttsVolume:Float=1f,val ttsEnginePackage:String?=null,val ttsVoiceName:String?=null,val ttsLanguageTag:String="vi-VN",val backgroundMusicVolume:Float=0.18f,val backgroundMusicDuckFactor:Float=0.25f,val sceneMusicCrossfadeMillis:Int=1600,val sceneMusicTargetLufs:Float=-18f,val sonicProcessingEnabled:Boolean=true,val sonicAccurateMode:Boolean=false,val sonicDefaultSpeed:Float=1f,val sonicDefaultPitch:Float=1f,val autoVoiceCastEnabled:Boolean=true)
class SettingsRepository { suspend fun snapshot()=AppSettings() }
'''),
 w(t,'vn/nghetruyen/app/data/repository/Repo.kt','''package vn.nghetruyen.app.data.repository
import vn.nghetruyen.app.data.local.*
import vn.nghetruyen.app.core.model.DownloadState
class LibraryRepository { suspend fun getAudioExportJob(id:String):AudioExportJobEntity?=null; suspend fun updateAudioExportJob(j:AudioExportJobEntity){}; suspend fun listExportableChapters(s:String)=emptyList<ChapterEntity>(); suspend fun listExportableChapters(s:String,a:Int,b:Int)=emptyList<ChapterEntity>(); suspend fun getChapter(id:String):ChapterEntity?=null; suspend fun listEnabledPronunciations()=emptyList<PronunciationEntity>(); suspend fun getStoryTtsProfile(id:String):StoryTtsProfileEntity?=null; suspend fun listVoiceRoles(id:String)=emptyList<VoiceRoleEntity>(); suspend fun listEffectiveVoiceRoles(id:String,enabled:Boolean)=emptyList<VoiceRoleEntity>(); suspend fun listVoiceAssignments(id:String)=emptyList<ChapterVoiceAssignmentEntity>(); suspend fun getChapterTransform(id:String,kind:String):ChapterTransformEntity?=null; suspend fun loadCachedChapter(id:String):vn.nghetruyen.app.core.model.ChapterContent?=null; suspend fun listSceneMusicCues(id:String)=emptyList<SceneMusicCueEntity>(); suspend fun getSceneMusicTrack(id:String):SceneMusicTrackEntity?=null; suspend fun updateAudioExportProgress(a:String,b:Int,c:Int,d:DownloadState,e:String?){} }
'''),
 w(t,'vn/nghetruyen/app/playback/Playback.kt','''package vn.nghetruyen.app.playback
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
object PronunciationProcessor { fun apply(s:String,r:List<PronunciationEntity>)=s }
object ReaderTextChunker { fun normalize(p:List<String>)=p }
data class ResolvedVoiceRole(val role:VoiceRoleEntity?,val spokenText:String)
object VoiceRoleResolver { fun resolve(text:String,roles:List<VoiceRoleEntity>)=ResolvedVoiceRole(null,text) }
''')]
 files += [w(t,'co/ntbl/lame/mp3/LameStubs.kt','''package co.ntbl.lame.mp3
enum class MPEGMode { STEREO,JOINT_STEREO,DUAL_CHANNEL,MONO,NOT_SET }
class LameGlobalFlags { fun setInNumChannels(v:Int){}; fun setInSampleRate(v:Int){}; fun setMode(v:MPEGMode){}; fun setBitRate(v:Int){}; fun setQuality(v:Int){}; fun setWriteId3tagAutomatic(v:Boolean){}; fun setFindReplayGain(v:Boolean){} }
class Lame { val flags=LameGlobalFlags(); fun initParams()=0; fun encodeBuffer(l:FloatArray,r:FloatArray,n:Int,b:ByteArray)=0; fun encodeFlush(b:ByteArray)=0; fun close(){}; companion object { const val QUALITY_HIGH=2 } }
''')]
 files += [w(t,'vn/nghetruyen/app/audio/P3Stubs.kt','''package vn.nghetruyen.app.audio
import java.io.File
object Pcm16WaveConverter { fun convert(a:File,b:File,g:Float=1f)=WaveFileAssembler.inspect(a) }
object M4aAacEncoder { fun encode(a:File,b:File,bitrate:Int=96000){} }
''')]
 files += [ROOT/'app/src/main/java/vn/nghetruyen/app/ai/ChapterAiWorkflow.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/TtsFileSynthesizer.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/AudioExportScheduler.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/Id3v23Writer.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/Mp3LameEncoder.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/Pcm16SceneMixer.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/ReferenceSonicRuntime.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/SonicPcmProcessor.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/PcmLoudnessEstimator.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/playback/VoiceExpressionProcessor.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/AndroidAudioTrackDecoder.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt']
 cmd=[K,*map(str,files),'-cp',str(lib),'-d',str(t/'out.jar')]
 r=subprocess.run(cmd,text=True,capture_output=True)
 
 if r.stdout: print(r.stdout.strip())
 if r.returncode:
  print(r.stderr)
  raise SystemExit(r.returncode)
 print('AUDIO_EXPORT_STATIC_COMPILE_OK')
