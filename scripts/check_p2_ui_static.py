#!/usr/bin/env python3
"""Compile the Personal P2 source-management UI against narrow Compose stubs."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; K=shutil.which('kotlinc')
def w(r,p,t):
 q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(t,encoding='utf-8');return q

def main():
 if not K: print('P2_UI_STATIC_SKIPPED'); return
 with tempfile.TemporaryDirectory(prefix='nghe_p2_ui_') as td:
  r=Path(td); f=[]
  f += [w(r,'androidx/compose/runtime/Runtime.kt',r'''package androidx.compose.runtime
import kotlin.reflect.KProperty
@Target(AnnotationTarget.FUNCTION,AnnotationTarget.TYPE) annotation class Composable
class MutableState<T>(var value:T)
operator fun <T> MutableState<T>.getValue(a:Any?,p:KProperty<*>):T=value
operator fun <T> MutableState<T>.setValue(a:Any?,p:KProperty<*>,v:T){value=v}
fun <T> mutableStateOf(v:T)=MutableState(v)
@Composable fun <T> remember(calculation:()->T):T=calculation()
@Composable fun <T> remember(vararg keys:Any?,calculation:()->T):T=calculation()
''')]
  f += [w(r,'androidx/compose/ui/Modifier.kt','package androidx.compose.ui\nopen class Modifier { companion object:Modifier() }\n')]
  f += [w(r,'androidx/compose/ui/unit/Units.kt','''package androidx.compose.ui.unit
data class Dp(val v:Float); val Int.dp:Dp get()=Dp(toFloat()); val Float.dp:Dp get()=Dp(this)
''')]
  f += [w(r,'androidx/compose/ui/text/input/Input.kt','''package androidx.compose.ui.text.input
class PasswordVisualTransformation
''')]
  f += [w(r,'androidx/compose/ui/text/font/Font.kt','''package androidx.compose.ui.text.font
class FontWeight { companion object { val SemiBold=FontWeight(); val Normal=FontWeight() } }
''')]
  f += [w(r,'androidx/compose/ui/Alignment.kt','''package androidx.compose.ui
object Alignment { val CenterVertically:Any=Any() }
''')]
  f += [w(r,'androidx/compose/foundation/layout/Layout.kt',r'''package androidx.compose.foundation.layout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
@Composable fun Column(modifier:Modifier=Modifier,content:@Composable ()->Unit){content()}
@Composable fun Row(modifier:Modifier=Modifier,verticalAlignment:Any?=null,content:@Composable ()->Unit){content()}
fun Modifier.fillMaxSize()=this; fun Modifier.fillMaxWidth()=this; fun Modifier.weight(v:Float)=this
fun Modifier.padding(all:Dp)=this
fun Modifier.padding(horizontal:Dp=Dp(0f),vertical:Dp=Dp(0f))=this
fun Modifier.padding(start:Dp=Dp(0f),top:Dp=Dp(0f),end:Dp=Dp(0f),bottom:Dp=Dp(0f))=this
''')]
  f += [w(r,'androidx/compose/foundation/Scroll.kt',r'''package androidx.compose.foundation
import androidx.compose.ui.Modifier
class ScrollState
fun rememberScrollState()=ScrollState()
fun Modifier.verticalScroll(state:ScrollState)=this
''')]
  f += [w(r,'androidx/compose/material3/Material.kt',r'''package androidx.compose.material3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
class TextStyle
class Color
class Colors { val error=Color() }
class Typography { val titleMedium=TextStyle(); val bodySmall=TextStyle(); val bodyMedium=TextStyle() }
object MaterialTheme { val typography=Typography(); val colorScheme=Colors() }
@Composable fun Text(text:String,modifier:Modifier=Modifier,style:TextStyle=TextStyle(),fontWeight:FontWeight?=null,color:Color=Color()){}
@Composable fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit){content()}
@Composable fun Card(modifier:Modifier=Modifier,content:@Composable ()->Unit){content()}
@Composable fun HorizontalDivider(modifier:Modifier=Modifier){}
@Composable fun OutlinedTextField(value:String,onValueChange:(String)->Unit,label:(@Composable ()->Unit)?=null,singleLine:Boolean=false,visualTransformation:Any?=null,modifier:Modifier=Modifier){}
@Composable fun Switch(checked:Boolean,onCheckedChange:(Boolean)->Unit){}
''')]
  f += [w(r,'vn/nghetruyen/app/core/model/Models.kt',r'''package vn.nghetruyen.app.core.model
enum class DownloadState { QUEUED,RUNNING,COMPLETED,FAILED,CANCELLED }
enum class SourceHealth { READY,NEEDS_LOGIN,DEGRADED,DISABLED,NOT_PORTED }
data class TtsVoiceOption(val name:String,val displayName:String)
data class TtsEngineOption(val packageName:String,val label:String,val isDefault:Boolean=false)
enum class AudioInterruptionMode { PAUSE, CONTINUE_DUCKED }
enum class SceneMusicPlaybackMode { SEQUENTIAL,SHUFFLE,SMART_AVOID_REPEAT }
''')]
  f += [w(r,'vn/nghetruyen/app/data/local/Entities.kt',r'''package vn.nghetruyen.app.data.local
data class AudioExportJobEntity(val id:String="",val storyTitle:String="",val outputFormat:String="WAV",val state:String="",val completedSegments:Int=0,val totalSegments:Int=0,val errorMessage:String?=null,val stage:String="QUEUED",val packaging:String="SINGLE_FILE",val includeSceneMusic:Boolean=false,val chapterMarkers:Boolean=true)
data class VietPhraseEntity(val id:Long=0,val source:String="",val target:String="",val priority:Int=0,val enabled:Boolean=true,val kind:String="VIET_PHRASE",val scope:String="GLOBAL",val storyId:String="",val matchMode:String="LITERAL",val ignoreCase:Boolean=false,val createdAt:Long=0,val updatedAt:Long=0)
data class VietPhraseSnapshotEntity(val id:String="",val label:String="",val checksum:String="",val ruleCount:Int=0,val payload:ByteArray=ByteArray(0),val createdAt:Long=0)
data class VietPhraseDictionaryStateEntity(val id:String="",val kind:String="VIET_PHRASE",val scope:String="GLOBAL",val storyId:String="",val enabled:Boolean=true,val sourceName:String="",val sourceFormat:String="",val checksum:String="",val entryCount:Int=0,val revision:Long=0,val importedAt:Long=0)
data class VietPhraseSuggestionEntity(val id:String="",val source:String="",val proposedTarget:String="",val editedTarget:String="",val reason:String="",val contextText:String="",val storyId:String?=null,val status:String="PENDING",val createdAt:Long=0,val reviewedAt:Long?=null)
data class SceneMusicTrackEntity(val id:String="",val title:String="",val tagsCsv:String="",val enabled:Boolean=true,val loudnessLufsEstimate:Float=-18f,val playCount:Int=0,val orderIndex:Int=0)
data class PronunciationEntity(val id:Long=0,val original:String="",val replacement:String="",val enabled:Boolean=true)
data class StorageUsage(val downloadedChapters:Int=0,val downloadedBytes:Long=0,val cachedChapters:Int=0,val cachedBytes:Long=0)
data class AiUsageDailyEntity(val dayEpoch:Int=0,val requestCount:Int=0,val inputChars:Long=0,val outputChars:Long=0,val retryCount:Int=0,val lastErrorCode:String?=null,val updatedAt:Long=0)
''')]
  f += [w(r,'vn/nghetruyen/app/data/settings/SettingsRepository.kt','''package vn.nghetruyen.app.data.settings
object SettingsRepository { val CACHE_LIMIT_OPTIONS_MIB=listOf(16,32,64,128,256) }
''')]
  f += [w(r,'vn/nghetruyen/app/sources/Models.kt',r'''package vn.nghetruyen.app.sources
import vn.nghetruyen.app.core.model.SourceHealth
enum class SourceCheckStatus { PASS,FAIL,SKIPPED }
data class SourceCheckStep(val name:String,val status:SourceCheckStatus,val detail:String,val elapsedMillis:Long)
data class SourceCheckReport(val resolvedHealth:SourceHealth,val steps:List<SourceCheckStep>){ val passedSteps:Int get()=steps.count{it.status==SourceCheckStatus.PASS}; val totalSteps:Int get()=steps.count{it.status!=SourceCheckStatus.SKIPPED} }
enum class SourceCommentCapability(val label:String){ NONE("Không hỗ trợ"), EMBEDDED("Nhúng"), PAGED("Phân trang"), DYNAMIC_BROWSER("WebView") }
data class SourceDescriptor(val id:String,val displayName:String,val health:SourceHealth,val baseUrl:String="https://example.invalid",val loginUrl:String?=null,val privacyNote:String?=null,val allowedHosts:Set<String> = emptySet(),val commentCapability:SourceCommentCapability=SourceCommentCapability.NONE)
''')]
  f += [w(r,'vn/nghetruyen/app/ai/vietphrase/Models.kt',r'''package vn.nghetruyen.app.ai.vietphrase
enum class VietPhraseDictionaryKind { LUAT_NHAN,PRONOUNS,PHIEN_AM,LAC_VIET,VIET_PHRASE,NAMES,AI_REPLACE }
enum class VietPhraseScope { GLOBAL,STORY }
data class VietPhraseConflict(val severity:Severity,val message:String){ enum class Severity{INFO,WARNING,ERROR} }
data class VietPhraseDiff(val added:List<String> = emptyList(),val changed:List<String> = emptyList(),val removed:List<String> = emptyList())
data class VietPhrasePlan(val diff:VietPhraseDiff=VietPhraseDiff(),val conflicts:List<VietPhraseConflict> = emptyList(),val canCommit:Boolean=true)
''')]
  f += [w(r,'vn/nghetruyen/app/transfer/VietPhraseTransferManager.kt',r'''package vn.nghetruyen.app.transfer
import vn.nghetruyen.app.ai.vietphrase.VietPhrasePlan
enum class BackupComponent(val label:String) { SETTINGS("Cài đặt ứng dụng"), LIBRARY("Thư viện và chương"), READING("Tiến độ, đánh dấu và phát âm"), AI_VOICE("AI, giọng đọc và phân vai"), VIETPHRASE("VietPhrase và đề xuất"), SOURCES_EXTENSIONS("Nguồn, extension và dữ liệu nguồn"), SCENE_MUSIC("Nhạc cảnh") }
class VietPhraseTransferManager { data class ImportPreview(val sourceName:String="",val sourceFormat:String="",val incomingCount:Int=0,val duplicateCount:Int=0,val warnings:List<String> = emptyList(),val plan:VietPhrasePlan=VietPhrasePlan(),val errorCount:Int=0,val warningCount:Int=0) }
''')]
  f += [w(r,'vn/nghetruyen/app/data/settings/Ai.kt',r'''package vn.nghetruyen.app.data.settings
enum class AiProvider { OPENAI_COMPATIBLE, GEMINI }
data class AiOnlineSettings(val provider:AiProvider=AiProvider.OPENAI_COMPATIBLE,val enabled:Boolean=false,val consentGranted:Boolean=false,val endpoint:String="",val model:String="",val temperature:Float=0.2f,val translationInstruction:String="",val dailyRequestLimit:Int=30,val dailyInputCharsLimit:Int=500000,val maxRetries:Int=2,val retryBaseDelayMillis:Int=1500)
''')]
  f += [w(r,'vn/nghetruyen/app/sourceplatform/Models.kt',r'''package vn.nghetruyen.app.sourceplatform
data class SourcePackUiInfo(val id:String="",val name:String="",val version:String="",val enabled:Boolean=true,val installedVersions:List<String> = emptyList(),val canRollback:Boolean=false,val signerKeyId:String="",val runtimeMode:String="",val commentCapability:String="NONE",val commentFixtureCount:Int=0)
data class SourceInstallPreview(val sourceId:String="",val name:String="",val version:String="",val signerKeyId:String="",val permissionSummary:List<String> = emptyList(),val fixtureCount:Int=0)
data class SourceDiagnosticUi(val timestampEpochMs:Long=0,val traceId:String="",val sourceId:String="",val category:String="",val name:String="",val severity:String="INFO",val durationMs:Long?=null,val detail:String="")
data class SourceRepositoryUiInfo(val id:String="",val name:String="",val url:String="",val generatedAtEpochMs:Long=0,val expiresAtEpochMs:Long=0,val packageCount:Int=0,val signerKeyId:String="")
data class SourceRepositoryPackageUiInfo(val repositoryId:String="",val sourceId:String="",val name:String="",val version:String="",val installedVersion:String?=null,val description:String="",val changelog:String="",val packageBytes:Int=0,val status:String="",val canInstall:Boolean=false)
data class SourceTrustKeyUi(val keyId:String="",val algorithm:String="",val fingerprint:String="",val builtin:Boolean=false)
data class SourceTraceUi(val traceId:String="",val sourceId:String="",val eventCount:Int=0,val startedAtEpochMs:Long=0,val endedAtEpochMs:Long=0,val failed:Boolean=false)
data class SourceSelectorInspectionUi(val selector:String="",val matchCount:Int=0,val samples:List<String> = emptyList())
''')]
  f += [w(r,'vn/nghetruyen/app/ui/State.kt',r'''package vn.nghetruyen.app.ui
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.local.*
import vn.nghetruyen.app.sources.*
import vn.nghetruyen.app.sourceplatform.*
import vn.nghetruyen.app.transfer.VietPhraseTransferManager
import vn.nghetruyen.app.transfer.BackupComponent
data class Playback(val rate:Float=1f,val pitch:Float=1f)
data class MainUiState(
 val playback:Playback=Playback(), val autoPlayNextChapter:Boolean=true,
 val headsetMultiClickEnabled:Boolean=true, val headsetSingleClickAction:String="TOGGLE", val headsetDoubleClickAction:String="NEXT",
 val headsetTripleClickAction:String="PREVIOUS", val headsetLongPressAction:String="STOP", val pauseOnHeadsetDisconnect:Boolean=true,
 val restorePlaybackAfterProcessDeath:Boolean=true, val autoVoiceCastEnabled:Boolean=false,
 val autoSceneMusicEnabled:Boolean=false, val prefetchNarrationPlansEnabled:Boolean=true,val narrationPrefetchWindowChapters:Int=2,
 val sceneMusicContinueAcrossChapters:Boolean=true, val sceneMusicCrossfadeMillis:Int=1600,
 val sceneMusicPlaybackMode:SceneMusicPlaybackMode=SceneMusicPlaybackMode.SMART_AVOID_REPEAT,val sceneMusicTargetLufs:Float=-18f,val sceneMusicAvoidRepeatWindow:Int=4,
 val sonicProcessingEnabled:Boolean=true,val sonicDefaultSpeed:Float=1f,val sonicDefaultPitch:Float=1f,
 val ttsCacheEnabled:Boolean=true,val ttsCacheLimitMiB:Int=64,val normalizeTtsVolumeEnabled:Boolean=true,val ttsTargetLufs:Float=-18f,
 val ttsVolume:Float=1f, val ttsEngines:List<TtsEngineOption> = emptyList(), val selectedTtsEnginePackage:String?=null,
 val ttsVoices:List<TtsVoiceOption> = emptyList(), val selectedTtsVoiceName:String?=null,val ttsVoiceLoading:Boolean=false,
 val audioInterruptionMode:AudioInterruptionMode=AudioInterruptionMode.PAUSE,
 val backgroundMusicUri:String?=null,val backgroundMusicEnabled:Boolean=false,val backgroundMusicVolume:Float=0.18f,val backgroundMusicDuckFactor:Float=0.25f,
 val pronunciations:List<PronunciationEntity> = emptyList(), val vietPhraseRules:List<VietPhraseEntity> = emptyList(),
 val vietPhraseSnapshots:List<VietPhraseSnapshotEntity> = emptyList(),val vietPhraseDictionaryStates:List<VietPhraseDictionaryStateEntity> = emptyList(),val vietPhraseSuggestions:List<VietPhraseSuggestionEntity> = emptyList(),val pendingVietPhraseImport:VietPhraseTransferManager.ImportPreview?=null, val vietPhraseOnlineBusy:Boolean=false, val vietPhraseOnlineStatus:String="", val backupComponents:Set<BackupComponent> = BackupComponent.entries.toSet(),
 val sceneMusicTracks:List<SceneMusicTrackEntity> = emptyList(), val aiOnline:AiOnlineSettings=AiOnlineSettings(),val aiUsageRecent:List<AiUsageDailyEntity> = emptyList(), val aiHasApiKey:Boolean=false, val aiAvailableModels:List<String> = emptyList(), val aiModelDiscoveryBusy:Boolean=false, val followingUpdatesEnabled:Boolean=false,
 val storageUsage:StorageUsage=StorageUsage(), val readerCacheLimitMiB:Int=64,
 val audioExports:List<AudioExportJobEntity> = emptyList(), val sources:List<SourceDescriptor> = emptyList(),
 val sourceHealthReports:Map<String,SourceCheckReport> = emptyMap(), val sourceHealthChecking:Set<String> = emptySet(), val sourceSessions:Set<String> = emptySet(),
 val sourcePacks:List<SourcePackUiInfo> = emptyList(), val sourceRepositories:List<SourceRepositoryUiInfo> = emptyList(),
 val sourceRepositoryPackages:List<SourceRepositoryPackageUiInfo> = emptyList(), val sourceRepositoryRefreshing:Boolean=false,
 val pendingSourceInstall:SourceInstallPreview?=null, val pendingSourceInstallWarnings:List<String> = emptyList(),
 val sourceTrustKeys:List<SourceTrustKeyUi> = emptyList(), val sourceDiagnosticCount:Int=0,
 val sourceDiagnostics:List<SourceDiagnosticUi> = emptyList(), val sourceTraces:List<SourceTraceUi> = emptyList(),
 val sourceSelectorInspection:SourceSelectorInspectionUi?=null, val performanceReport:String?=null
)
''')]
  f += [w(r,'vn/nghetruyen/app/ui/components/Heading.kt',r'''package vn.nghetruyen.app.ui.components
import androidx.compose.runtime.Composable
@Composable fun ScreenHeading(text:String){}
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt']
  cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('P2_UI_STATIC_OK')
if __name__=='__main__': main()
