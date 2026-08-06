#!/usr/bin/env python3
from pathlib import Path
import shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc'); J=shutil.which('java')
if not K or not J:
 print('P1_FEATURE_CHECK_SKIPPED')
 raise SystemExit(0)
with tempfile.TemporaryDirectory() as td:
 t=Path(td)
 runner=t/'Runner.kt'
 runner.write_text(r'''import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.downloads.ChapterRangeSelector
import vn.nghetruyen.app.following.FollowingUpdateDetector
fun main(){
 check(StorySearch.normalize("Đấu La Đại Lục") == "dau la dai luc")
 val merged=StorySearch.merge(listOf(
  StorySummary("a","d","Đấu La Đại Lục","Đường Gia Tam Thiếu"),
  StorySummary("b","r","Dau La Dai Luc","Duong Gia Tam Thieu")
 ), mapOf("d" to SourceHealth.DEGRADED,"r" to SourceHealth.READY))
 check(merged.size==1 && merged.single().sourceId=="r")
 val chapters=(0..9).map{ChapterSummary("c$it","s",it,"Chương ${it+1}")}
 check(ChapterRangeSelector.select(chapters,2,4).map{it.id}==listOf("c2","c3","c4"))
 check(FollowingUpdateDetector.newChapterCount("Chương 10",9,"Chương 14",13)==4)
 check(FollowingUpdateDetector.newChapterCount("Chương 20",-1,"Chương 23",-1)==3)
 println("P1_FEATURE_CHECK_OK")
}
''')
 out=t/'out.jar'
 files=[
  ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt',
  ROOT/'app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt',
  ROOT/'app/src/main/java/vn/nghetruyen/app/downloads/ChapterRangeSelector.kt',
  ROOT/'app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateDetector.kt',
  runner,
 ]
 subprocess.run([K,*map(str,files),'-include-runtime','-d',str(out)],check=True,cwd=ROOT,timeout=180)
 subprocess.run([J,'-jar',str(out)],check=True,cwd=ROOT,timeout=60)
