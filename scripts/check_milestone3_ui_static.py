#!/usr/bin/env python3
from pathlib import Path
import shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p

def main():
 if not K:
  print('MILESTONE3_UI_STATIC_SKIPPED_NO_KOTLINC');return
 with tempfile.TemporaryDirectory(prefix='nghe_m3_ui_') as td:
  r=Path(td); f=[]
  f += [w(r,'androidx/compose/runtime/Runtime.kt',r'''package androidx.compose.runtime
import kotlin.reflect.KProperty
@Target(AnnotationTarget.FUNCTION,AnnotationTarget.TYPE) annotation class Composable
class MutableState<T>(var value:T)
operator fun <T> MutableState<T>.getValue(thisRef:Any?,p:KProperty<*>):T=value
operator fun <T> MutableState<T>.setValue(thisRef:Any?,p:KProperty<*>,v:T){value=v}
fun <T> mutableStateOf(v:T)=MutableState(v)
@Composable fun <T> remember(vararg keys:Any?, calculation:()->T):T=calculation()
''')]
  f += [w(r,'androidx/compose/ui/Modifier.kt','''package androidx.compose.ui
open class Modifier { fun weight(v:Float):Modifier=this; companion object:Modifier() }
''')]
  f += [w(r,'androidx/compose/ui/unit/Units.kt','''package androidx.compose.ui.unit
data class Dp(val v:Float); val Int.dp:Dp get()=Dp(toFloat()); val Float.dp:Dp get()=Dp(this)
''')]
  f += [w(r,'androidx/compose/ui/text/font/Font.kt','''package androidx.compose.ui.text.font
class FontWeight { companion object { val Bold=FontWeight(); val SemiBold=FontWeight() } }
''')]
  f += [w(r,'androidx/compose/foundation/Foundation.kt',r'''package androidx.compose.foundation
import androidx.compose.ui.Modifier
class ScrollState
fun rememberScrollState()=ScrollState()
fun Modifier.horizontalScroll(state:ScrollState)=this
fun Modifier.clickable(onClick:()->Unit)=this
''')]
  f += [w(r,'androidx/compose/foundation/layout/Layout.kt',r'''package androidx.compose.foundation.layout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
@Composable fun Column(modifier:Modifier=Modifier,content:@Composable ()->Unit){content()}
@Composable fun Row(modifier:Modifier=Modifier,horizontalArrangement:Any?=null,content:@Composable ()->Unit){content()}
fun Modifier.fillMaxSize()=this; fun Modifier.fillMaxWidth()=this; fun Modifier.weight(v:Float)=this
fun Modifier.heightIn(min:Dp)=this
fun Modifier.padding(all:Dp)=this
fun Modifier.padding(horizontal:Dp=Dp(0f),vertical:Dp=Dp(0f))=this
fun Modifier.padding(start:Dp=Dp(0f),top:Dp=Dp(0f),end:Dp=Dp(0f),bottom:Dp=Dp(0f))=this
object Arrangement { fun spacedBy(d:Dp):Any=Any() }
''')]
  f += [w(r,'androidx/compose/foundation/lazy/Lazy.kt',r'''package androidx.compose.foundation.lazy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
class LazyListScope {
 fun <T> items(items:List<T>,key:((T)->Any)?=null,itemContent:@Composable (T)->Unit){}
 fun item(key:Any?=null,content:@Composable ()->Unit){}
}
@Composable fun LazyColumn(modifier:Modifier=Modifier,content:LazyListScope.()->Unit){LazyListScope().content()}
fun <T> LazyListScope.items(items:List<T>,key:((T)->Any)?=null,itemContent:@Composable (T)->Unit)=this.items(items,key,itemContent)
''')]
  f += [w(r,'androidx/compose/material3/Material.kt',r'''package androidx.compose.material3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
class Color
class TextStyle
class Colors { val primary=Color() }
class Typography { val bodyMedium=TextStyle(); val labelSmall=TextStyle(); val bodySmall=TextStyle() }
object MaterialTheme { val colorScheme=Colors(); val typography=Typography() }
@Composable fun Text(text:String,modifier:Modifier=Modifier,style:TextStyle=TextStyle(),fontWeight:FontWeight?=null,color:Color=Color()){}
@Composable fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit){content()}
@Composable fun TextButton(onClick:()->Unit,modifier:Modifier=Modifier,content:@Composable ()->Unit){content()}
@Composable fun Card(modifier:Modifier=Modifier,content:@Composable ()->Unit){content()}
@Composable fun OutlinedTextField(value:String,onValueChange:(String)->Unit,label:(@Composable ()->Unit)?=null,singleLine:Boolean=false,modifier:Modifier=Modifier){}
@Composable fun DropdownMenu(expanded:Boolean,onDismissRequest:()->Unit,content:@Composable ()->Unit){content()}
@Composable fun DropdownMenuItem(text:@Composable ()->Unit,onClick:()->Unit){text()}
@Composable fun AlertDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null){title();text();confirmButton();dismissButton?.invoke()}
''')]
  f += [w(r,'androidx/compose/ui/semantics/Semantics.kt',r'''package androidx.compose.ui.semantics
import androidx.compose.ui.Modifier
class Role { companion object { val Tab=Role() } }
class SemanticsPropertyReceiver
var SemanticsPropertyReceiver.role:Role
 get()=Role()
 set(value){}
var SemanticsPropertyReceiver.selected:Boolean
 get()=false
 set(value){}
fun Modifier.semantics(block:SemanticsPropertyReceiver.()->Unit)=this
''')]
  f += [w(r,'vn/nghetruyen/app/data/local/Stubs.kt',r'''package vn.nghetruyen.app.data.local
data class StoryEntity(val id:String,val sourceId:String,val title:String,val author:String="",val isOffline:Boolean=false)
data class BookmarkEntity(val id:String,val label:String="",val paragraphIndex:Int=0)
data class FollowedStoryEntity(val storyId:String,val sourceId:String,val title:String,val latestKnownChapter:String="",val newChapterCount:Int=0)
data class DownloadJobEntity(val id:String,val storyId:String,val selectionMode:String="ALL",val startChapterIndex:Int=0,val endChapterIndex:Int=Int.MAX_VALUE,val state:String="QUEUED",val completedChapters:Int=0,val totalChapters:Int=0,val currentChapterTitle:String="",val retryCount:Int=0,val errorMessage:String?=null)
data class OfflineStoryStorage(val storyId:String,val chapterCount:Int,val bytes:Long)
data class ChapterNoteEntity(val id:String,val storyId:String,val chapterId:String,val paragraphIndex:Int,val text:String)
data class ChapterDownloadFailureEntity(val id:String,val jobId:String,val storyId:String,val sourceId:String,val chapterIndex:Int,val chapterTitle:String,val errorMessage:String,val retryCount:Int=0)
''')]
  f += [w(r,'vn/nghetruyen/app/sources/Stubs.kt',r'''package vn.nghetruyen.app.sources
import vn.nghetruyen.app.core.model.SourceHealth
data class SourceDescriptor(val id:String,val displayName:String,val health:SourceHealth,val supportsHome:Boolean=true)
''')]
  f += [w(r,'vn/nghetruyen/app/ui/State.kt',r'''package vn.nghetruyen.app.ui
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.data.local.*
import vn.nghetruyen.app.sources.SourceDescriptor
enum class LibrarySection { READING, DOWNLOADED, BOOKMARKS, NOTES, FOLLOWING }
enum class ExploreMode { HOME, SEARCH, CATEGORY }
data class MainUiState(
 val sources:List<SourceDescriptor> = emptyList(), val selectedSourceId:String="", val searchAllSources:Boolean=false,
 val categories:List<String> = emptyList(), val query:String="", val sourceSuggestions:List<String> = emptyList(), val loading:Boolean=false,
 val searchSortMode:SearchSortMode=SearchSortMode.RELEVANCE, val totalSearchSourceCount:Int=0,
 val searchedSourceCount:Int=0, val stories:List<StorySummary> = emptyList(), val canLoadMoreStories:Boolean=false,
 val explorePage:Int=1, val exploreMode:ExploreMode=ExploreMode.HOME, val activeCategory:String?=null, val librarySection:LibrarySection=LibrarySection.READING,
 val readingStories:List<StoryEntity> = emptyList(), val downloadedStories:List<StoryEntity> = emptyList(),
 val downloads:List<DownloadJobEntity> = emptyList(), val offlineStorage:Map<String,OfflineStoryStorage> = emptyMap(),
 val bookmarks:List<BookmarkEntity> = emptyList(), val notes:List<ChapterNoteEntity> = emptyList(),
 val downloadFailures:List<ChapterDownloadFailureEntity> = emptyList(), val following:List<FollowedStoryEntity> = emptyList()
)
''')]
  f += [w(r,'vn/nghetruyen/app/ui/components/Stubs.kt',r'''package vn.nghetruyen.app.ui.components
import androidx.compose.runtime.Composable
import vn.nghetruyen.app.core.model.StorySummary
@Composable fun ScreenHeading(text:String){}
@Composable fun LoadingRow(label:String=""){}
@Composable fun StoryCard(story:StorySummary,onClick:()->Unit){}
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt']
  cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True,timeout=120)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('MILESTONE3_UI_STATIC_COMPILE_OK')

if __name__=='__main__': main()
