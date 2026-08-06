#!/usr/bin/env python3
from pathlib import Path
import tempfile, subprocess, shutil
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p

def main():
 if not K: print('P1_UI_STATIC_SKIPPED'); return
 with tempfile.TemporaryDirectory() as td:
  r=Path(td); files=[]
  files += [w(r,'androidx/compose/runtime/Runtime.kt',r'''package androidx.compose.runtime
import kotlin.reflect.KProperty
@Target(AnnotationTarget.FUNCTION,AnnotationTarget.TYPE) annotation class Composable
class MutableState<T>(var value:T)
operator fun <T> MutableState<T>.getValue(thisRef:Any?,p:KProperty<*>):T=value
operator fun <T> MutableState<T>.setValue(thisRef:Any?,p:KProperty<*>,v:T){value=v}
fun <T> mutableStateOf(v:T)=MutableState(v)
fun mutableIntStateOf(v:Int)=MutableState(v)
@Composable fun <T> remember(vararg keys:Any?, calculation:()->T):T=calculation()
@Composable fun LaunchedEffect(vararg keys:Any?, block:suspend ()->Unit){}
class DisposableEffectScope{ fun onDispose(block:()->Unit)=DisposableEffectResult() }
class DisposableEffectResult
@Composable fun DisposableEffect(vararg keys:Any?, effect:DisposableEffectScope.()->DisposableEffectResult){}
'''), w(r,'kotlinx/coroutines/Coroutines.kt','''package kotlinx.coroutines
suspend fun delay(ms:Long){}
''')]
  files += [w(r,'androidx/compose/ui/Modifier.kt',r'''package androidx.compose.ui
open class Modifier { companion object:Modifier() }
object Alignment { val CenterVertically:Any=Any() }
''')]
  files += [w(r,'androidx/compose/ui/unit/Units.kt',r'''package androidx.compose.ui.unit
data class Dp(val v:Float); val Int.dp:Dp get()=Dp(toFloat()); val Float.dp:Dp get()=Dp(this)
data class TextUnit(val v:Float); val Int.sp:TextUnit get()=TextUnit(toFloat()); val Float.sp:TextUnit get()=TextUnit(this)
''')]
  files += [w(r,'androidx/compose/ui/graphics/Color.kt',r'''package androidx.compose.ui.graphics
class Color(val value:Long){ companion object { val White=Color(0xffffff); val Transparent=Color(0) } }
''')]
  files += [w(r,'androidx/compose/ui/text/Text.kt',r'''package androidx.compose.ui.text
class AnnotatedString(val text:String)
class TextStyle
'''), w(r,'androidx/compose/ui/text/font/Font.kt','''package androidx.compose.ui.text.font
class FontWeight { companion object { val Bold=FontWeight(); val SemiBold=FontWeight() } }
''')]
  files += [w(r,'androidx/compose/foundation/layout/Layout.kt',r'''package androidx.compose.foundation.layout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
@Composable fun Column(modifier:Modifier=Modifier, verticalArrangement:Any?=null, content:@Composable ()->Unit){content()}
@Composable fun Row(modifier:Modifier=Modifier, horizontalArrangement:Any?=null, verticalAlignment:Any?=null, content:@Composable ()->Unit){content()}
fun Modifier.fillMaxSize()=this; fun Modifier.fillMaxWidth()=this; fun Modifier.weight(v:Float)=this
fun Modifier.padding(all:Dp)=this
fun Modifier.padding(horizontal:Dp=Dp(0f), vertical:Dp=Dp(0f))=this
fun Modifier.padding(start:Dp=Dp(0f),top:Dp=Dp(0f),end:Dp=Dp(0f),bottom:Dp=Dp(0f))=this
object Arrangement { fun spacedBy(d:Dp):Any=Any(); val SpaceBetween:Any=Any() }
''')]
  files += [w(r,'androidx/compose/foundation/Background.kt',r'''package androidx.compose.foundation
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
fun Modifier.background(c:Color)=this
fun Modifier.clickable(onClick:()->Unit)=this
''')]
  files += [w(r,'androidx/compose/foundation/lazy/Lazy.kt',r'''package androidx.compose.foundation.lazy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
class LazyListItemInfo(val index:Int)
class LazyListLayoutInfo(val visibleItemsInfo:List<LazyListItemInfo> = emptyList())
class LazyListState {
 val firstVisibleItemIndex:Int=0
 val isScrollInProgress:Boolean=false
 val layoutInfo:LazyListLayoutInfo=LazyListLayoutInfo()
 suspend fun animateScrollToItem(i:Int){}
 suspend fun scrollToItem(i:Int){}
}
@Composable fun rememberLazyListState()=LazyListState()
class LazyListScope {
 fun items(count:Int,key:((Int)->Any)?=null,itemContent:@Composable (Int)->Unit){}
 fun <T> items(items:List<T>,key:((T)->Any)?=null,itemContent:@Composable (T)->Unit){}
 fun item(key:Any?=null,content:@Composable ()->Unit){}
}
@Composable fun LazyColumn(modifier:Modifier=Modifier,state:LazyListState=LazyListState(),content:LazyListScope.()->Unit){LazyListScope().content()}
fun <T> LazyListScope.items(items:List<T>,key:((T)->Any)?=null,itemContent:@Composable (T)->Unit)=this.items(items,key,itemContent)
''')]
  files += [w(r,'androidx/compose/material3/Material.kt',r'''package androidx.compose.material3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
class Colors { val background=Color(0);val surface=Color(0);val onBackground=Color(0);val primaryContainer=Color(0);val tertiaryContainer=Color(0);val primary=Color(0) }
class Typography { val headlineSmall=TextStyle();val labelSmall=TextStyle();val bodySmall=TextStyle() }
object MaterialTheme { val colorScheme=Colors();val typography=Typography() }
class CardColors
object CardDefaults { fun cardColors(containerColor:Color)=CardColors() }
@Composable fun Text(text:String,modifier:Modifier=Modifier,style:TextStyle=TextStyle(),fontWeight:FontWeight?=null,color:Color=Color(0),fontSize:TextUnit=TextUnit(0f),lineHeight:TextUnit=TextUnit(0f)){}
@Composable fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit){content()}
@Composable fun TextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit){content()}
@Composable fun Card(modifier:Modifier=Modifier,colors:CardColors=CardColors(),content:@Composable ()->Unit){content()}
@Composable fun Surface(modifier:Modifier=Modifier,color:Color=Color(0),contentColor:Color=Color(0),content:@Composable ()->Unit){content()}
@Composable fun OutlinedTextField(value:String,onValueChange:(String)->Unit,label:(@Composable ()->Unit)?=null,singleLine:Boolean=false,minLines:Int=1,modifier:Modifier=Modifier){}
@Composable fun Checkbox(checked:Boolean,onCheckedChange:(Boolean)->Unit){}
@Composable fun Switch(checked:Boolean,onCheckedChange:(Boolean)->Unit,enabled:Boolean=true){}
@Composable fun AlertDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null){}
@Composable fun HorizontalDivider(modifier:Modifier=Modifier){}
''')]
  files += [w(r,'androidx/compose/ui/platform/Platform.kt',r'''package androidx.compose.ui.platform
import androidx.compose.ui.text.AnnotatedString
class ClipboardManager { fun setText(t:AnnotatedString){} }
object LocalClipboardManager { val current=ClipboardManager() }
class View { var keepScreenOn:Boolean=false }
object LocalView { val current=View() }
''')]
  files += [w(r,'androidx/compose/ui/semantics/Semantics.kt',r'''package androidx.compose.ui.semantics
import androidx.compose.ui.Modifier
class SemanticsPropertyReceiver
fun SemanticsPropertyReceiver.heading(){}
fun Modifier.semantics(block:SemanticsPropertyReceiver.()->Unit)=this
''')]
  files += [w(r,'vn/nghetruyen/app/data/local/Stubs.kt',r'''package vn.nghetruyen.app.data.local
data class FollowedStoryEntity(val storyId:String,val newChapterCount:Int=0)
data class StoryTtsProfileEntity(val storyId:String)
data class StoryAiProfileEntity(val storyId:String,val mode:String="INHERIT",val overrideProvider:Boolean=false,val provider:String="OPENAI_COMPATIBLE",val endpoint:String="",val model:String="",val temperature:Float=-1f,val useCustomPrompts:Boolean=false,val translationPrompt:String="",val improvePrompt:String="",val autoRunOnOpen:Boolean=false,val useCustomVoiceCastPrompt:Boolean=false,val voiceCastPrompt:String="",val voiceCastNote:String="",val voiceCastDialogueOnly:Boolean=true,val voiceCastStableNarrator:Boolean=true,val expressiveAdjustment:Boolean=true,val expressionPrompt:String="",val expressionSpeedLimitPct:Int=10,val expressionPitchLimitPct:Int=10,val expressionVolumeLimitPct:Int=10,val updatedAt:Long=0)
data class VoiceRoleEntity(val id:String="",val storyId:String="",val roleName:String="",val aliasesCsv:String="",val enginePackage:String?=null,val voiceName:String?=null,val languageTag:String="vi-VN",val rate:Float=1f,val pitch:Float=1f,val volume:Float=1f,val expression:String="NEUTRAL",val expressionStrength:Float=0.5f,val sonicSpeed:Float=1f,val sonicPitch:Float=1f,val isNarrator:Boolean=false,val enabled:Boolean=true)
data class ChapterNoteEntity(val id:String="",val storyId:String="",val chapterId:String="",val paragraphIndex:Int=0,val text:String="")
''')]
  files += [w(r,'vn/nghetruyen/app/data/settings/Stubs.kt',r'''package vn.nghetruyen.app.data.settings
enum class AiProvider { OPENAI_COMPATIBLE, GEMINI }
data class AiOnlineSettings(val provider:AiProvider=AiProvider.OPENAI_COMPATIBLE,val endpoint:String="https://api.openai.com/v1/chat/completions",val model:String="",val temperature:Float=0.2f)
''')]
  files += [w(r,'vn/nghetruyen/app/playback/Stubs.kt',r'''package vn.nghetruyen.app.playback
enum class PlaybackPreparationState { READY, PREPARING, FAILED }
data class PlaybackSnapshot(val paragraphIndex:Int=0,val isPlaying:Boolean=false,val sleepTimerEndsAtMillis:Long?=null,val rate:Float=1f,val pitch:Float=1f,val preparationState:PlaybackPreparationState=PlaybackPreparationState.READY)
''')]
  files += [w(r,'vn/nghetruyen/app/ui/State.kt',r'''package vn.nghetruyen.app.ui
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.data.local.*
import vn.nghetruyen.app.playback.PlaybackSnapshot
import vn.nghetruyen.app.data.settings.AiOnlineSettings
enum class ChapterTextMode { ORIGINAL, VIETPHRASE, AI_TRANSLATION }
data class MainUiState(val chapterContent:ChapterContent?=null,val playback:PlaybackSnapshot=PlaybackSnapshot(),val readerDisplay:ReaderDisplaySettings=ReaderDisplaySettings(),val storyTtsProfiles:Map<String,StoryTtsProfileEntity> = emptyMap(),val storyAiProfiles:Map<String,StoryAiProfileEntity> = emptyMap(),val aiOnline:AiOnlineSettings=AiOnlineSettings(),val voiceRoles:List<VoiceRoleEntity> = emptyList(),val ttsEngines:List<TtsEngineOption> = emptyList(),val roleEditorVoices:List<TtsVoiceOption> = emptyList(),val roleEditorVoiceLoading:Boolean=false,val selectedTtsEnginePackage:String?=null,val selectedTtsVoiceName:String?=null,val selectedTtsLanguageTag:String="vi-VN",val ttsVolume:Float=1f,val storyDetail:StoryDetail?=null,val storyComments:List<StoryComment> = emptyList(),val storyCommentsAvailable:Boolean=false,val storyCommentsRefreshable:Boolean=false,val storyCommentsLoading:Boolean=false,val storyCommentsLoaded:Boolean=false,val storyCommentsNextPageUrl:String?=null,val storyCommentsFromCache:Boolean=false,val storyCommentsMessage:String?=null,val continueAvailable:Boolean=false,val following:List<FollowedStoryEntity> = emptyList(),val loading:Boolean=false,val aiBusy:Boolean=false,val chapterTextMode:ChapterTextMode=ChapterTextMode.ORIGINAL,val notes:List<ChapterNoteEntity> = emptyList())
''')]
  files += [w(r,'vn/nghetruyen/app/ui/components/Stubs.kt',r'''package vn.nghetruyen.app.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@Composable fun LargeActionButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){}
@Composable fun LoadingRow(label:String=""){}
''')]
  files += [ROOT/'app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/sources/ChapterCatalogIndex.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt']
  cp=subprocess.run([K,*map(str,files),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('P1_UI_STATIC_COMPILE_OK')
if __name__=='__main__':main()
