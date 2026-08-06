#!/usr/bin/env python3
"""Compile Android-facing non-Compose wiring with small local stubs.

This catches Kotlin syntax/signature regressions without Android SDK. It does
not replace AGP, Room KAPT, Lint, Compose compiler or device tests.
"""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def write(root: Path, path: str, text: str) -> Path:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    return target


def run(files: list[Path], output: Path, classpath: list[Path] | None = None) -> None:
    assert KOTLINC
    command = [KOTLINC, *(str(item) for item in files)]
    if classpath:
        command += ["-cp", ":".join(str(item) for item in classpath)]
    command += ["-d", str(output)]
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
    if completed.returncode:
        print(completed.stdout)
        print(completed.stderr)
        raise SystemExit(completed.returncode)


def kotlin_lib(name: str) -> Path | None:
    if not KOTLINC:
        return None
    path = Path(KOTLINC).resolve().parents[1] / "lib" / name
    return path if path.is_file() else None


def compile_database(temp: Path, coroutines: Path | None) -> None:
    stubs = temp / "db-stubs"
    files = [
        write(stubs, "android/content/Context.kt", '''package android.content
open class Context { open val applicationContext: Context get() = this }
'''),
        write(stubs, "androidx/room/Room.kt", '''package androidx.room
import android.content.Context
annotation class Dao
annotation class ColumnInfo(val name:String="[field-name]", val typeAffinity:Int=1, val index:Boolean=false, val defaultValue:String="[value-unspecified]", val collate:Int=1)
annotation class Database(val entities:Array<kotlin.reflect.KClass<*>>, val version:Int, val exportSchema:Boolean=true)
annotation class Entity(val tableName:String="", val indices:Array<Index> = [])
annotation class Index(val value:Array<String>, val unique:Boolean=false)
annotation class Insert(val onConflict:Int=0)
object OnConflictStrategy { const val REPLACE:Int=1 }
annotation class PrimaryKey(val autoGenerate:Boolean=false)
annotation class Query(val value:String)
open class RoomDatabase
object Room { fun <T:RoomDatabase> databaseBuilder(c:Context,k:Class<T>,n:String)=Builder<T>(); class Builder<T>{ fun addMigrations(vararg m:androidx.room.migration.Migration)=this; fun build():T=error("stub") } }
suspend fun <T> RoomDatabase.withTransaction(block:suspend()->T):T=block()
'''),
        write(stubs, "androidx/room/migration/Migration.kt", '''package androidx.room.migration
open class Migration(val startVersion:Int,val endVersion:Int){ open fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){} }
'''),
        write(stubs, "androidx/sqlite/db/SupportSQLiteDatabase.kt", '''package androidx.sqlite.db
interface SupportSQLiteDatabase { fun execSQL(sql:String) }
'''),
        ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
        *sorted(
            item for item in (ROOT / "app/src/main/java/vn/nghetruyen/app/ai/vietphrase").glob("*.kt")
            if item.name != "VietPhraseOnlineUpdater.kt"
        ),
        ROOT / "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt",
    ]
    run(files, temp / "database.jar", [coroutines] if coroutines else None)


def compile_following(temp: Path, coroutines: Path | None) -> None:
    stubs = temp / "following-stubs"
    files = [
        write(stubs, "android/Manifest.kt", 'package android\nobject Manifest { object permission { const val POST_NOTIFICATIONS="post" } }\n'),
        write(stubs, "android/content/Core.kt", '''package android.content
open class Context { open val applicationContext:Context get()=this; fun <T> getSystemService(c:Class<T>):T=error("stub") }
open class Intent { constructor(); constructor(c:Context,k:Class<*>); fun putExtra(k:String,v:String)=this }
'''),
        write(stubs, "android/content/pm/PackageManager.kt", 'package android.content.pm\nobject PackageManager { const val PERMISSION_GRANTED=0 }\n'),
        write(stubs, "android/os/Build.kt", 'package android.os\nobject Build { object VERSION { const val SDK_INT=36 }; object VERSION_CODES { const val TIRAMISU=33 } }\n'),
        write(stubs, "android/app/App.kt", '''package android.app
import android.content.Context
import android.content.Intent
open class Notification { class Builder(c:Context,id:String){ fun setSmallIcon(i:Int)=this; fun setContentTitle(s:String)=this; fun setContentText(s:String)=this; fun setContentIntent(p:PendingIntent)=this; fun setAutoCancel(b:Boolean)=this; fun build()=Notification() } }
class NotificationChannel(id:String,name:String,importance:Int)
open class NotificationManager { fun createNotificationChannel(c:NotificationChannel){}; fun notify(i:Int,n:Notification){}; companion object { const val IMPORTANCE_DEFAULT=3 } }
class PendingIntent { companion object { const val FLAG_UPDATE_CURRENT=1; const val FLAG_IMMUTABLE=2; fun getActivity(c:Context,r:Int,i:Intent,f:Int)=PendingIntent() } }
'''),
        write(stubs, "androidx/core/content/ContextCompat.kt", 'package androidx.core.content\nimport android.content.Context\nobject ContextCompat { fun checkSelfPermission(c:Context,p:String)=0 }\n'),
        write(stubs, "androidx/work/Work.kt", '''package androidx.work
import android.content.Context
import java.time.Duration
open class WorkerParameters
open class CoroutineWorker(val applicationContext:Context,p:WorkerParameters){ val runAttemptCount=0; open suspend fun doWork():Result=Result.success(); suspend fun setProgress(d:Data){}; class Result { companion object { fun success()=Result(); fun retry()=Result() } } }
class Data
fun workDataOf(vararg p:Pair<String,Any>)=Data()
class Constraints { class Builder { fun setRequiredNetworkType(t:NetworkType)=this; fun build()=Constraints() } }
enum class NetworkType { CONNECTED }
enum class ExistingPeriodicWorkPolicy { UPDATE }
enum class ExistingWorkPolicy { KEEP }
class WorkRequest
class PeriodicWorkRequestBuilder<T>(d:Duration){ fun setConstraints(c:Constraints)=this; fun build()=WorkRequest() }
class OneTimeWorkRequestBuilder<T>{ fun setConstraints(c:Constraints)=this; fun build()=WorkRequest() }
class WorkManager { fun cancelUniqueWork(n:String){}; fun enqueueUniquePeriodicWork(n:String,p:ExistingPeriodicWorkPolicy,r:WorkRequest){}; fun enqueueUniqueWork(n:String,p:ExistingWorkPolicy,r:WorkRequest){}; companion object { fun getInstance(c:Context)=WorkManager() } }
'''),
        write(stubs, "vn/nghetruyen/app/Stubs.kt", '''package vn.nghetruyen.app
import android.content.Context
class MainActivity
class NgheTruyenApplication:Context(){ val container=Container() }
class Container { val libraryRepository=vn.nghetruyen.app.data.repository.LibraryRepository(); val sourceRegistry=vn.nghetruyen.app.sources.Registry() }
object R { object drawable { const val ic_stat_reader=1 } }
'''),
        write(stubs, "vn/nghetruyen/app/data/repository/Repo.kt", '''package vn.nghetruyen.app.data.repository
class Followed(val storyId:String,val sourceId:String,val remoteUrl:String,val title:String,val latestKnownChapter:String,val latestKnownChapterIndex:Int=-1)
class LibraryRepository { suspend fun listFollowingForUpdate(n:Int)=emptyList<Followed>(); suspend fun updateFollowCheck(item:Followed,latestChapter:String,latestChapterIndex:Int=-1,additionalNewChapters:Int=0){} }
'''),
        write(stubs, "vn/nghetruyen/app/sources/Sources.kt", '''package vn.nghetruyen.app.sources
import vn.nghetruyen.app.core.common.AppResult
class Chapter(val title:String,val index:Int=-1)
interface Source { suspend fun latestChapter(u:String):AppResult<Chapter?> }
class Registry { fun get(id:String):Source?=null }
'''),
        write(stubs, "vn/nghetruyen/app/core/common/AppResult.kt", '''package vn.nghetruyen.app.core.common
sealed interface AppResult<out T>{ data class Success<T>(val value:T):AppResult<T>; data class Failure(val message:String):AppResult<Nothing> }
'''),
        ROOT / "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateDetector.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateScheduler.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateWorker.kt",
    ]
    run(files, temp / "following.jar", [coroutines] if coroutines else None)


def compile_voice_catalog(temp: Path, coroutines: Path | None) -> None:
    stubs = temp / "voice-stubs"
    files = [
        write(stubs, "android/content/Context.kt", 'package android.content\nopen class Context { open val applicationContext:Context get()=this }\n'),
        write(stubs, "android/speech/tts/Tts.kt", '''package android.speech.tts
import android.content.Context
import java.util.Locale
class Voice(val name:String="voice", val locale:Locale=Locale.ROOT, val isNetworkConnectionRequired:Boolean=false, val quality:Int=0)
class EngineInfo(val name:String="engine", val label:CharSequence?="Engine")
class TextToSpeech {
 val voices:Set<Voice>?=emptySet(); val engines:List<EngineInfo>?=emptyList(); val defaultEngine:String?=null
 constructor(c:Context,l:(Int)->Unit){ l(SUCCESS) }
 constructor(c:Context,l:(Int)->Unit,enginePackage:String){ l(SUCCESS) }
 fun shutdown(){}
 companion object { const val SUCCESS=0 }
}
'''),
        ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
        ROOT / "app/src/main/java/vn/nghetruyen/app/playback/TtsVoiceCatalog.kt",
    ]
    run(files, temp / "voice.jar", [coroutines] if coroutines else None)


def main() -> None:
    if not KOTLINC:
        print("ANDROID_WIRING_STATIC_COMPILE_SKIPPED: kotlinc not found")
        return
    coroutines = kotlin_lib("kotlinx-coroutines-core-jvm.jar")
    with tempfile.TemporaryDirectory(prefix="nghe_android_wiring_") as name:
        temp = Path(name)
        print("ANDROID_WIRING_DATABASE_START", flush=True)
        compile_database(temp, coroutines)
        print("ANDROID_WIRING_FOLLOWING_START", flush=True)
        compile_following(temp, coroutines)
        print("ANDROID_WIRING_VOICE_START", flush=True)
        compile_voice_catalog(temp, coroutines)
    print("ANDROID_WIRING_STATIC_COMPILE_OK")


if __name__ == "__main__":
    main()
