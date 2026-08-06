#!/usr/bin/env python3
from pathlib import Path
import shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p

def main():
 if not K: print('MILESTONE3_DOWNLOAD_STATIC_SKIPPED'); return
 with tempfile.TemporaryDirectory(prefix='nghe_m3_download_') as td:
  r=Path(td); f=[]
  f += [w(r,'android/content/Context.kt','''package android.content
open class Context { open val applicationContext:Context get()=this; val filesDir:java.io.File=java.io.File("."); fun <T> getSystemService(c:Class<T>):T=error("stub") }
''')]
  f += [w(r,'android/content/pm/ServiceInfo.kt','''package android.content.pm
object ServiceInfo { const val FOREGROUND_SERVICE_TYPE_DATA_SYNC=1 }
''')]
  f += [w(r,'android/os/Os.kt','''package android.os
object Build { object VERSION { const val SDK_INT=36 }; object VERSION_CODES { const val Q=29 } }
class StatFs(path:String){ val availableBytes:Long=1024L*1024L*1024L }
''')]
  f += [w(r,'android/app/App.kt','''package android.app
import android.content.Context
class PendingIntent
open class Notification { companion object { const val CATEGORY_PROGRESS="progress" }; class Action { class Builder(i:Int,t:String,p:PendingIntent){ fun build()=Action() } }; class Builder(c:Context,id:String){ fun setSmallIcon(i:Int)=this; fun setContentTitle(s:String)=this; fun setContentText(s:String)=this; fun setOnlyAlertOnce(b:Boolean)=this; fun setOngoing(b:Boolean)=this; fun setCategory(s:String)=this; fun setProgress(a:Int,b:Int,c:Boolean)=this; fun addAction(a:Action)=this; fun build()=Notification() } }
class NotificationChannel(id:String,name:String,importance:Int){ var description:String=""; fun setSound(a:Any?,b:Any?){} }
class NotificationManager { fun createNotificationChannel(c:NotificationChannel){}; companion object { const val IMPORTANCE_LOW=1 } }
''')]
  f += [w(r,'androidx/work/Work.kt','''package androidx.work
import android.content.Context
import android.app.Notification
import android.app.PendingIntent
import java.time.Duration
open class WorkerParameters
class Data(private val values:Map<String,Any?> = emptyMap()) { fun getString(k:String)=values[k] as? String; fun getInt(k:String,d:Int)=values[k] as? Int ?: d; fun getBoolean(k:String,d:Boolean)=values[k] as? Boolean ?: d; class Builder { private val m=mutableMapOf<String,Any?>(); fun putString(k:String,v:String?)=apply{m[k]=v}; fun putInt(k:String,v:Int)=apply{m[k]=v}; fun build()=Data(m) } }
fun workDataOf(vararg p:Pair<String,Any?>)=Data(mapOf(*p))
class ForegroundInfo { constructor(id:Int,n:Notification); constructor(id:Int,n:Notification,type:Int) }
open class CoroutineWorker(val applicationContext:Context,p:WorkerParameters){ val inputData=Data(); val runAttemptCount=0; val isStopped=false; val id=java.util.UUID.randomUUID(); open suspend fun doWork():Result=Result.success(); suspend fun setForeground(f:ForegroundInfo){}; suspend fun setProgress(d:Data){}; class Result { companion object { fun success(d:Data=Data())=Result(); fun failure(d:Data=Data())=Result(); fun retry()=Result() } } }
class Constraints { class Builder { fun setRequiredNetworkType(t:NetworkType)=this; fun setRequiresCharging(b:Boolean)=this; fun build()=Constraints() } }
enum class NetworkType { NOT_REQUIRED, UNMETERED, CONNECTED }
enum class BackoffPolicy { EXPONENTIAL }
enum class ExistingWorkPolicy { APPEND }
class WorkRequest
class OneTimeWorkRequestBuilder<T>{ fun setConstraints(c:Constraints)=this; fun setBackoffCriteria(p:BackoffPolicy,d:Duration)=this; fun setInputData(d:Data)=this; fun addTag(s:String)=this; fun build()=WorkRequest() }
class WorkManager { fun createCancelPendingIntent(id:java.util.UUID)=PendingIntent(); fun enqueueUniqueWork(n:String,p:ExistingWorkPolicy,r:WorkRequest){}; companion object { fun getInstance(c:Context)=WorkManager() } }
''')]
  f += [w(r,'vn/nghetruyen/app/App.kt','''package vn.nghetruyen.app
import android.content.Context
class NgheTruyenApplication:Context(){ val container=Container() }
class Container { val libraryRepository=vn.nghetruyen.app.data.repository.LibraryRepository(); val sourceRegistry=vn.nghetruyen.app.sources.Registry() }
object R { object drawable { const val ic_stat_reader=1 } }
''')]
  f += [w(r,'vn/nghetruyen/app/data/local/Entities.kt','''package vn.nghetruyen.app.data.local
data class StoryEntity(val id:String,val remoteUrl:String,val title:String)
data class DownloadJobEntity(val id:String,val storyId:String,val sourceId:String,val selectionMode:String="ALL",val startChapterIndex:Int=0,val endChapterIndex:Int=Int.MAX_VALUE,val wifiOnly:Boolean=false,val chargingOnly:Boolean=false,val state:String="QUEUED",val completedChapters:Int=0,val totalChapters:Int=0)
''')]
  f += [w(r,'vn/nghetruyen/app/data/repository/Repo.kt','''package vn.nghetruyen.app.data.repository
import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.data.local.*
class LibraryRepository {
 suspend fun getStory(id:String)=StoryEntity(id,"url","title")
 suspend fun updateDownloadJob(id:String,storyId:String,sourceId:String,state:DownloadState,completedChapters:Int,totalChapters:Int,errorMessage:String?=null,selectionMode:DownloadSelectionMode?=null,startChapterIndex:Int?=null,endChapterIndex:Int?=null,wifiOnly:Boolean?=null,chargingOnly:Boolean?=null,currentChapterIndex:Int?=null,currentChapterTitle:String?=null,retryCount:Int?=null){}
 suspend fun listDownloadedChapterIds(storyId:String)=emptySet<String>()
 suspend fun markStoryDownloaded(story:StorySummary){}
 suspend fun saveDownloadedChapter(content:ChapterContent){}
 suspend fun getDownloadJob(id:String):DownloadJobEntity?=null
 suspend fun clearDownloadFailures(id:String){}
 suspend fun clearDownloadFailure(id:String,index:Int){}
 suspend fun recordDownloadFailure(jobId:String,storyId:String,sourceId:String,chapterIndex:Int,chapterTitle:String,errorMessage:String,retryCount:Int){}
}
''')]
  f += [w(r,'vn/nghetruyen/app/sources/Sources.kt','''package vn.nghetruyen.app.sources
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.*
interface StorySource { suspend fun story(url:String):AppResult<StoryDetail>; suspend fun chapter(url:String):AppResult<ChapterContent>; suspend fun chapterPage(storyId:String,url:String,startIndex:Int):AppResult<ChapterPage> }
class Registry { fun get(id:String):StorySource?=null }
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt']
  for name in ['DownloadRequest.kt','StoryDownloadPlanner.kt','ChapterRangeSelector.kt','DownloadBatchPlanner.kt','DownloadStorageGuard.kt','ChapterDownloadWorker.kt']:
   f.append(ROOT/'app/src/main/java/vn/nghetruyen/app/downloads'/name)
  cor=Path(K).resolve().parents[1]/'lib/kotlinx-coroutines-core-jvm.jar'
  cmd=[K,*map(str,f)]
  if cor.exists(): cmd += ['-cp',str(cor)]
  cmd += ['-d',str(r/'out.jar')]
  cp=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=180)
  if cp.returncode:
   print(cp.stdout); print(cp.stderr); raise SystemExit(cp.returncode)
 print('MILESTONE3_DOWNLOAD_STATIC_COMPILE_OK')
if __name__=='__main__': main()
