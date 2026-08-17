#!/usr/bin/env python3
"""Offline gate for v2.5.0 maximum XPK compatibility work."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def require(path,*tokens):
    text=(ROOT/path).read_text(encoding='utf-8')
    for token in tokens:
        assert token in text, f'{path} missing token: {token}'

def write(root,path,text):
    p=root/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text,encoding='utf-8'); return p

def run(cmd, timeout=240):
    cp=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=timeout)
    if cp.stdout.strip(): print(cp.stdout.strip())
    if cp.returncode:
        print(cp.stderr); raise SystemExit(cp.returncode)

def main():
    require('source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaArchiveImporter.kt',
            'MAX_ARCHIVE_INFLATED_BYTES','archive.files','chooseEntry','ALLOWED_EXTENSIONS')
    require('source-lua/src/main/kotlin/vn/nghetruyen/source/lua/LuaSandbox.kt',
            'LuaMemoryBudget','resourceHelpers','NATIVE_LUA_RESOURCE_PATH_INVALID','debug.sethook')
    require('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
            'putProperty(scope, "Document"','putProperty(scope, "localCookie"','putProperty(scope, "Script"',
            'putProperty(scope, "Qt"','putProperty(scope, "WebSocket"','var CryptoJS',
            'SourceCryptoCapability.AES_COMPAT','Crypto.aes','JSON.stringify({code:0','SourceTranslationRequest','BufferedWebSocketObject')
    require('app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt',
            'EVALUATE_PAGE_SCRIPT_ASYNC','__ngheAsyncResults','SET_DIALOG_POLICY','SYNC_SESSION')
    require('app/src/main/java/vn/nghetruyen/app/sourceplatform/GenericStoryCommentLoader.kt',
            'ROOT_SELECTORS','ITEM_SELECTORS','DYNAMIC_BROWSER' if False else 'DOM_SNAPSHOT')
    require('app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt',
            'FORMAT_VERSION = 15','SOURCES_EXTENSIONS','attachmentCodec.stage','attachmentCodec.restore')
    require('app/src/main/java/vn/nghetruyen/app/transfer/BackupAttachmentCodec.kt',
            'MAX_TOTAL_ATTACHMENT_BYTES','RESTORE_ATTACHMENT_CHECKSUM_MISMATCH','atomicReplaceDirectory',
            'source-platform-v2','scene-music-restored')
    require('app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceTranslationBroker.kt',
            'SOURCE_TRANSLATION_DISCLOSURE_REQUIRED','TranslationRequest','configured-ai')
    require('app/build.gradle.kts','versionCode = 36','versionName = "3.0.2"')
    if not K:
        print('V250_STATIC_COMPILE_SKIPPED')
        print('V250_TOOL_PARITY_OK'); return
    with tempfile.TemporaryDirectory(prefix='v250-backup-') as td:
        r=Path(td); st=[]
        st += [write(r,'android/net/Uri.kt','''package android.net
class Uri private constructor(private val raw:String){ val scheme:String? get()=raw.substringBefore(":","").ifBlank{null}; val path:String? get()=raw.substringAfter("file://",raw); val lastPathSegment:String? get()=raw.substringAfterLast('/').ifBlank{null}; override fun toString()=raw; companion object{ fun parse(v:String)=Uri(v); fun fromFile(f:java.io.File)=Uri("file://"+f.path) }}
''')]
        st += [write(r,'android/content/Context.kt','''package android.content
import android.net.Uri
import java.io.*
open class Context { open val applicationContext:Context get()=this; open val filesDir:File get()=File("."); open val contentResolver:ContentResolver get()=ContentResolver(); fun getSharedPreferences(n:String,m:Int)=SharedPreferences(); companion object{const val MODE_PRIVATE=0} }
class ContentResolver { fun openInputStream(uri:Uri):InputStream?=ByteArrayInputStream(ByteArray(0)) }
class SharedPreferences { fun getString(k:String,d:String?):String?=d; fun edit()=Editor() }
class Editor { fun putString(k:String,v:String)=this; fun commit()=true }
''')]
        st += [write(r,'org/json/Json.kt','''package org.json
class JSONArray { constructor(); constructor(v:Any?); fun put(v:Any?):JSONArray=this; fun length()=0; fun getJSONObject(i:Int)=JSONObject() }
class JSONObject { constructor(); constructor(v:String); fun put(k:String,v:Any?):JSONObject=this; fun getString(k:String)=""; fun getLong(k:String)=0L; fun optString(k:String)=""; fun isNull(k:String)=true; companion object{val NULL=Any()} }
''')]
        st += [write(r,'vn/nghetruyen/app/data/local/Scene.kt','''package vn.nghetruyen.app.data.local
data class SceneMusicTrackEntity(val id:String,val uri:String)
''')]
        st += [write(r,'vn/nghetruyen/app/transfer/Backup.kt','''package vn.nghetruyen.app.transfer
enum class BackupComponent { SETTINGS,LIBRARY,READING,AI_VOICE,VIETPHRASE,SOURCES_EXTENSIONS,SCENE_MUSIC }
''')]
        run([K,*map(str,st),str(ROOT/'app/src/main/java/vn/nghetruyen/app/transfer/BackupAttachmentCodec.kt'),'-d',str(r/'backup.jar')])
    with tempfile.TemporaryDirectory(prefix='v250-translate-') as td:
        r=Path(td); st=[]
        st += [write(r,'kotlinx/coroutines/Run.kt','''package kotlinx.coroutines
fun <T> runBlocking(block:suspend ()->T):T = throw NotImplementedError()
''')]
        st += [write(r,'vn/nghetruyen/app/core/common/App.kt','''package vn.nghetruyen.app.core.common
sealed interface AppResult<out T>{ data class Success<T>(val value:T):AppResult<T>; data class Failure(val code:String,val message:String):AppResult<Nothing> }
''')]
        st += [write(r,'vn/nghetruyen/app/ai/Ai.kt','''package vn.nghetruyen.app.ai
import vn.nghetruyen.app.core.common.AppResult
data class TranslationRequest(val storyId:String,val chapterId:String,val sourceText:String,val instruction:String)
interface TranslationEngine { suspend fun translate(request:TranslationRequest):AppResult<String> }
''')]
        st += [write(r,'vn/nghetruyen/source/api/Api.kt','''package vn.nghetruyen.source.api
data class Privacy(val sendsContentToThirdParty:Boolean=false)
data class SourceManifest(val id:String,val privacy:Privacy=Privacy())
data class SourceTranslationRequest(val sourceId:String,val text:String,val storyId:String?=null,val chapterId:String?=null,val sourceLanguage:String?=null,val targetLanguage:String="vi",val instruction:String="",val maxOutputBytes:Int=2097152,val traceId:String="",val options:Map<String,String> = emptyMap())
data class SourceTranslationResponse(val translatedText:String,val segments:List<String>,val provider:String?,val traceId:String)
enum class SourceErrorCode{TRANSLATION_UNAVAILABLE}
data class SourcePlatformFailure(val code:SourceErrorCode,val message:String,val traceId:String="",val cause:Throwable?=null)
sealed interface SourcePlatformResult<out T>{ data class Success<T>(val value:T):SourcePlatformResult<T>; data class Failure(val error:SourcePlatformFailure):SourcePlatformResult<Nothing> }
fun interface SourceTranslationBroker { fun translate(manifest:SourceManifest,request:SourceTranslationRequest):SourcePlatformResult<SourceTranslationResponse> }
''')]
        st += [write(r,'vn/nghetruyen/source/vbook/VBook.kt','''package vn.nghetruyen.source.vbook
object VBookTranslationBrokerRouter { val QUICK_TARGETS=setOf("vp","hv") }
''')]
        st += [write(r,'vn/nghetruyen/app/sourceplatform/VBookQuick.kt','''package vn.nghetruyen.app.sourceplatform
import vn.nghetruyen.source.api.*
object AndroidVBookQuickTranslationRegistry:SourceTranslationBroker {
 override fun translate(manifest:SourceManifest,request:SourceTranslationRequest):SourcePlatformResult<SourceTranslationResponse> = throw NotImplementedError()
}
''')]
        run([K,*map(str,st),str(ROOT/'app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceTranslationBroker.kt'),'-d',str(r/'translate.jar')])
    print('V250_TOOL_PARITY_OK')
if __name__=='__main__': main()
