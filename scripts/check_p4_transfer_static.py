#!/usr/bin/env python3
"""Compile the bounded VietPhrase SAF preview/commit/export path against JVM stubs."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; K=shutil.which('kotlinc')
def w(r,p,t): q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(t,encoding='utf-8');return q

def run(cmd, timeout=180):
 cp=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=timeout)
 if cp.stdout: print(cp.stdout.strip())
 if cp.returncode:
  if cp.stderr: print(cp.stderr)
  raise SystemExit(cp.returncode)

def main():
 if not K: raise SystemExit('P4_TRANSFER_STATIC_BLOCKED: thiếu kotlinc')
 with tempfile.TemporaryDirectory(prefix='nghe_p4_transfer_') as td:
  r=Path(td)
  pure=sorted((ROOT/'app/src/main/java/vn/nghetruyen/app/ai/vietphrase').glob('*.kt'))
  pure=[p for p in pure if p.name not in {
   'ReferenceVietPhraseRuntime.kt','VietPhraseDiagnosticExporter.kt',
   'VietPhraseEntityMapper.kt','VietPhraseOnlineUpdater.kt',
  }]
  core=r/'core.jar';run([K,*map(str,pure),'-d',str(core)],240)
  f=[]
  f += [w(r,'android/net/Uri.kt','''package android.net
open class Uri(val value:String="") { open val lastPathSegment:String? get()=value.substringAfterLast('/',"").ifBlank{null}; override fun toString()=value }
''')]
  f += [w(r,'android/database/Cursor.kt','''package android.database
interface Cursor:java.io.Closeable { fun moveToFirst():Boolean; fun getString(index:Int):String?; override fun close(){} }
''')]
  f += [w(r,'android/provider/OpenableColumns.kt','''package android.provider
object OpenableColumns { const val DISPLAY_NAME:String="_display_name" }
''')]
  f += [w(r,'android/content/Content.kt',r'''package android.content
import android.net.Uri
import android.database.Cursor
import java.io.*
open class ContentResolver {
 open fun openInputStream(uri:Uri):InputStream?=ByteArrayInputStream(ByteArray(0))
 open fun openOutputStream(uri:Uri,mode:String):OutputStream?=ByteArrayOutputStream()
 open fun query(uri:Uri,projection:Array<String>?,selection:String?,selectionArgs:Array<String>?,sortOrder:String?):Cursor?=null
}
''')]
  f += [w(r,'kotlinx/coroutines/Core.kt',r'''package kotlinx.coroutines
object Dispatchers { object IO }
suspend fun <T> withContext(ctx:Any,block:suspend ()->T):T=block()
''')]
  f += [w(r,'vn/nghetruyen/app/data/local/Viet.kt',r'''package vn.nghetruyen.app.data.local
data class VietPhraseSnapshotEntity(val id:String,val label:String,val checksum:String,val ruleCount:Int,val payload:ByteArray,val createdAt:Long)
''')]
  f += [w(r,'vn/nghetruyen/app/ai/vietphrase/ReferenceVietPhraseRuntime.kt',r'''package vn.nghetruyen.app.ai.vietphrase
object ReferenceVietPhraseRuntime { fun consumeImportKind():VietPhraseDictionaryKind?=null }
''')]
  f += [w(r,'vn/nghetruyen/app/data/repository/Repo.kt',r'''package vn.nghetruyen.app.data.repository
import vn.nghetruyen.app.ai.vietphrase.*
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
class LibraryRepository {
 suspend fun previewVietPhraseImport(incoming:List<VietPhraseRule>,replaceKinds:Set<VietPhraseDictionaryKind>):VietPhraseImportPlanner.Plan = VietPhraseImportPlanner.plan(emptyList(),incoming,replaceKinds)
 suspend fun commitVietPhraseImport(plan:VietPhraseImportPlanner.Plan,sourceName:String,sourceFormat:String,importedStates:List<VietPhrasePersistenceArchiveCodec.DictionaryState> = emptyList()):VietPhraseSnapshotEntity = VietPhraseSnapshotEntity("id","label","0".repeat(64),plan.beforeSnapshot.rules.size,ByteArray(0),0)
 suspend fun listAllVietPhraseRules()=emptyList<VietPhraseRule>()
 suspend fun listVietPhraseDictionaryStates()=emptyList<VietPhrasePersistenceArchiveCodec.DictionaryState>()
}
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/transfer/VietPhraseTransferManager.kt']
  run([K,'-classpath',str(core),*map(str,f),'-d',str(r/'out.jar')],180)
 print('P4_VIETPHRASE_TRANSFER_STATIC_COMPILE_OK')
if __name__=='__main__': main()
