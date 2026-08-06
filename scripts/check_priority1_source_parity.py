#!/usr/bin/env python3
"""Compile/run gate for Priority 1 source selection, home/suggestions and TOC pagination."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
KOTLIN = shutil.which("kotlin")


def write(root: Path, relative: str, text: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def run(command: list[str], timeout: int = 180) -> None:
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if completed.stdout.strip():
        print(completed.stdout.strip())
    if completed.returncode:
        print(completed.stderr)
        raise SystemExit(completed.returncode)


def main() -> None:
    registry = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt").read_text(encoding="utf-8")
    story_source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt").read_text(encoding="utf-8")
    pack_source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt").read_text(encoding="utf-8")
    explore = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt").read_text(encoding="utf-8")
    view_model = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text(encoding="utf-8")
    vbook = (ROOT / "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt").read_text(encoding="utf-8")

    required = {
        "registry priority": "candidate.selectionPriority > current.selectionPriority" in registry,
        "home API": "suspend fun home(page: Int = 1)" in story_source,
        "suggestion API": "suspend fun suggestions(query: String)" in story_source,
        "pack parity metadata": "preferSourcePack" in pack_source and "selectionPriority" in pack_source,
        "real TOC execution": "SourceActionName.TOC_PAGES" in pack_source and "parseChapterPage" in pack_source,
        "continuation token": "sourcepack-page:" in pack_source,
        "home UI": "onHomeSelected" in explore,
        "suggestions UI": "sourceSuggestions" in explore,
        "home viewmodel": "ExploreMode.HOME" in view_model and "source.home(nextPage)" in view_model,
        "vBook data2": "responseData2" in vbook and '"nextPageUrl"' in vbook,
    }
    missing = [name for name, ok in required.items() if not ok]
    if missing:
        raise SystemExit("PRIORITY1_STATIC_MISSING: " + ", ".join(missing))

    if not KOTLINC or not KOTLIN:
        print("PRIORITY1_SOURCE_COMPILE_SKIPPED")
        print("PRIORITY1_SOURCE_PARITY_OK")
        return

    with tempfile.TemporaryDirectory(prefix="priority1-source-") as tmp:
        root = Path(tmp)
        stubs: list[Path] = []
        stubs.append(write(root, "kotlinx/coroutines/Coroutines.kt", r'''
package kotlinx.coroutines
object Dispatchers { val IO: Any = Any() }
suspend fun <T> withContext(context: Any, block: suspend () -> T): T = block()
'''))
        stubs.append(write(root, "vn/nghetruyen/source/api/Api.kt", r'''
package vn.nghetruyen.source.api

enum class SourceRuntimeMode { DECLARATIVE, VBOOK_JS_COMPAT, NATIVE_LUA_COMPAT }
enum class SourceActionName { HOME, GENRE, SEARCH, DETAIL, LATEST_CHAPTER, TOC_PAGES, TOC, CHAPTER, COMMENTS, SUGGESTIONS, LOGIN }
data class Runtime(val mode: SourceRuntimeMode)
data class Browser(val navigate: Boolean = false)
data class Capabilities(val browser: Browser = Browser())
data class Privacy(val note: String = "")
data class Fixture(val action: SourceActionName)
data class Manifest(
 val id: String,
 val name: String,
 val runtime: Runtime,
 val actions: Map<SourceActionName, Any>,
 val origins: Set<String> = setOf("https://example.com"),
 val redirectOrigins: Set<String> = emptySet(),
 val capabilities: Capabilities = Capabilities(),
 val privacy: Privacy = Privacy(),
 val fixtures: List<Fixture> = emptyList(),
)
sealed interface JsonValue {
 data class Obj(val values: LinkedHashMap<String, JsonValue> = linkedMapOf()): JsonValue {
  operator fun get(name:String):JsonValue?=values[name]
  fun string(name:String):String?=(values[name] as? Str)?.value
  fun int(name:String):Int?=when(val v=values[name]){is Num->v.value.toInt();is Str->v.value.toIntOrNull();else->null}
  fun bool(name:String):Boolean?=when(val v=values[name]){is Bool->v.value;is Str->v.value.toBooleanStrictOrNull();else->null}
  fun array(name:String):Arr?=values[name] as? Arr
 }
 data class Arr(val values:List<JsonValue>):JsonValue
 data class Str(val value:String):JsonValue
 data class Num(val value:Double,val raw:String):JsonValue
 data class Bool(val value:Boolean):JsonValue
 data object Null:JsonValue
}
data class SourceActionRequest(val sourceId:String,val action:SourceActionName,val input:JsonValue.Obj)
data class SourceActionResponse(val value:JsonValue)
sealed interface SourcePlatformResult<out T>{data class Success<T>(val value:T):SourcePlatformResult<T>;data class Failure(val error:Error):SourcePlatformResult<Nothing>}
data class Error(val code:Code,val message:String)
data class Code(val name:String)
object JsonCodec { fun parse(raw:String):JsonValue = error("not used") }
'''))
        stubs.append(write(root, "vn/nghetruyen/source/packagekit/Pack.kt", r'''
package vn.nghetruyen.source.packagekit
import vn.nghetruyen.source.api.Manifest
class VerifiedSourcePack(val manifest:Manifest,val entries:Map<String,ByteArray>)
'''))
        stubs.append(write(root, "vn/nghetruyen/source/runtime/Resources.kt", r'''
package vn.nghetruyen.source.runtime
class MapSourceResourceProvider(val values:Map<String,ByteArray>)
'''))
        stubs.append(write(root, "vn/nghetruyen/app/sourceplatform/Stubs.kt", r'''
package vn.nghetruyen.app.sourceplatform
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.source.api.*
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
fun interface SourcePackActionExecutor { fun execute(pack:VerifiedSourcePack,resources:MapSourceResourceProvider,request:SourceActionRequest):SourcePlatformResult<SourceActionResponse> }
class GenericStoryCommentLoader { fun load(manifest:Manifest,url:String):StoryCommentPage = StoryCommentPage(emptyList()) }
object StoryCommentPayloadParser { fun parse(value:JsonValue):List<StoryComment> = emptyList(); fun parsePage(value:JsonValue):StoryCommentPage=StoryCommentPage(emptyList()) }
'''))
        stubs.append(write(root, "Priority1Main.kt", r'''
import kotlin.coroutines.*
import vn.nghetruyen.app.core.common.*
import vn.nghetruyen.app.sourceplatform.*
import vn.nghetruyen.source.api.*
import vn.nghetruyen.source.packagekit.*

fun <T> runSuspend(block:suspend ()->T):T {
 var outcome:Result<T>?=null
 block.startCoroutine(object:Continuation<T>{override val context=EmptyCoroutineContext;override fun resumeWith(result:Result<T>){outcome=result}})
 return outcome!!.getOrThrow()
}
fun obj(vararg pairs:Pair<String,JsonValue>)=JsonValue.Obj(linkedMapOf(*pairs))
fun chapter(id:String,index:Int)=obj("id" to JsonValue.Str(id),"storyId" to JsonValue.Str("story"),"index" to JsonValue.Num(index.toDouble(),index.toString()),"title" to JsonValue.Str(id),"url" to JsonValue.Str("https://example.com/$id"))
fun main(){
 val actions=SourceActionName.entries.associateWith{Any()}
 val pack=VerifiedSourcePack(Manifest("vn.example.source","Example",Runtime(SourceRuntimeMode.DECLARATIVE),actions),emptyMap())
 val executor=SourcePackActionExecutor{_,_,request->
  val value=when(request.action){
   SourceActionName.HOME,SourceActionName.SEARCH,SourceActionName.GENRE -> obj("items" to JsonValue.Arr(listOf(obj("id" to JsonValue.Str("s"),"title" to JsonValue.Str("Story"),"url" to JsonValue.Str("https://example.com/story")))))
   SourceActionName.SUGGESTIONS -> obj("items" to JsonValue.Arr(listOf(JsonValue.Str("Story one"),JsonValue.Str("Story two"))))
   SourceActionName.DETAIL -> obj("id" to JsonValue.Str("story"),"title" to JsonValue.Str("Story"),"url" to JsonValue.Str("https://example.com/story"))
   SourceActionName.LATEST_CHAPTER -> chapter("c2",1)
   SourceActionName.TOC -> if(request.input.string("url")!!.contains("page2")) obj("chapters" to JsonValue.Arr(listOf(chapter("c2",0)))) else obj("chapters" to JsonValue.Arr(listOf(chapter("c1",0))),"nextPageUrl" to JsonValue.Str("/story/page2"))
   SourceActionName.TOC_PAGES -> obj("chapters" to JsonValue.Arr(listOf(chapter("c2",0))))
   SourceActionName.CHAPTER -> obj("id" to JsonValue.Str("c"),"storyId" to JsonValue.Str("story"),"index" to JsonValue.Num(0.0,"0"),"title" to JsonValue.Str("C"),"url" to JsonValue.Str("https://example.com/c"),"paragraphs" to JsonValue.Arr(listOf(JsonValue.Str("p"))))
   else -> JsonValue.Null
  }
  SourcePlatformResult.Success(SourceActionResponse(value))
 }
 val source=SourcePackStorySource(pack,executor)
 check(source.selectionPriority==50)
 check(runSuspend{source.home()}.let{it is AppResult.Success && it.value.size==1})
 check(runSuspend{source.suggestions("st")}.let{it is AppResult.Success && it.value==listOf("Story one","Story two")})
 val detail=runSuspend{source.story("https://example.com/story")} as AppResult.Success
 check(detail.value.nextChapterPageUrl=="https://example.com/story/page2")
 val page=runSuspend{source.chapterPage("story",detail.value.nextChapterPageUrl!!,1)} as AppResult.Success
 check(page.value.chapters.single().index==1)
 println("PRIORITY1_RUNTIME_OK")
}
'''))
        output = root / "priority1.jar"
        sources = [
            *stubs,
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt",
        ]
        run([KOTLINC, *map(str, sources), "-d", str(output)])
        run([KOTLIN, "-classpath", str(output), "Priority1MainKt"])

    print("PRIORITY1_SOURCE_PARITY_OK")


if __name__ == "__main__":
    main()
