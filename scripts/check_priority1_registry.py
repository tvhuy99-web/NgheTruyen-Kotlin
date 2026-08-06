#!/usr/bin/env python3
"""Compile/run the effective-source selection policy."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc'); KR=shutil.which('kotlin')

def w(root:Path, rel:str, text:str)->Path:
 p=root/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text,encoding='utf-8'); return p

def run(cmd:list[str])->None:
 r=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=180)
 if r.stdout.strip(): print(r.stdout.strip())
 if r.returncode:
  print(r.stderr); raise SystemExit(r.returncode)

def main()->None:
 if not K or not KR:
  print('PRIORITY1_REGISTRY_COMPILE_SKIPPED'); return
 with tempfile.TemporaryDirectory(prefix='p1-registry-') as name:
  t=Path(name)
  stubs=w(t,'vn/nghetruyen/app/sources/Sites.kt',r'''package vn.nghetruyen.app.sources
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.*
interface SourceSessionStore
class InMemorySourceSessionStore:SourceSessionStore
open class StubSite(private val sourceId:String):StorySource {
 override val descriptor=SourceDescriptor(sourceId,sourceId,"https://example.com",SourceHealth.READY)
 override suspend fun search(query:String,page:Int)=AppResult.Success(emptyList<StorySummary>())
 override suspend fun category(category:String,page:Int)=AppResult.Success(emptyList<StorySummary>())
 override suspend fun story(url:String)=AppResult.Failure("X","X")
 override suspend fun chapter(url:String)=AppResult.Failure("X","X")
}
class TruyenFullSource:StubSite("truyenfull")
class TruyenCvSource:StubSite("truyencv")
class TruyenComSource:StubSite("truyencom")
class TruyenYySource:StubSite("truyenyy")
class WikidichSource:StubSite("wikidich")
class SangTacVietSource(store:SourceSessionStore):StubSite("sangtacviet")
''')
  main=w(t,'Main.kt',r'''import vn.nghetruyen.app.core.common.*
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.sources.*
private class Fake(private val stableId:String,override val selectionPriority:Int):StorySource {
 override val descriptor=SourceDescriptor(stableId,stableId,"https://example.com",SourceHealth.READY)
 override suspend fun search(query:String,page:Int)=AppResult.Success(emptyList<StorySummary>())
 override suspend fun category(category:String,page:Int)=AppResult.Success(emptyList<StorySummary>())
 override suspend fun story(url:String)=AppResult.Failure("X","X")
 override suspend fun chapter(url:String)=AppResult.Failure("X","X")
}
fun main(){
 val builtin=Fake("same",100); val compatibility=Fake("same",50); val full=Fake("same",200)
 val registry=SourceRegistry(sources=listOf(builtin),sourcePackSources=listOf(compatibility))
 check(registry.get("same")===builtin)
 registry.refreshSourcePacks(listOf(full)); check(registry.get("same")===full)
 println("PRIORITY1_REGISTRY_OK")
}
''')
  jar=t/'out.jar'
  run([K,str(ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt'),str(ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt'),str(ROOT/'app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt'),str(ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt'),str(stubs),str(main),'-d',str(jar)])
  run([KR,'-classpath',str(jar),'MainKt'])
if __name__=='__main__': main()
