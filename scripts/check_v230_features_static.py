#!/usr/bin/env python3
"""Static gates for advanced voice cast, online VietPhrase and selective backup."""
from __future__ import annotations
import re, shutil, sqlite3, subprocess, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def text(path:str)->str:
    p=ROOT/path
    if not p.is_file(): raise AssertionError(f'Missing {path}')
    return p.read_text(encoding='utf-8')

def require(path:str,*markers:str)->None:
    data=text(path); missing=[m for m in markers if m not in data]
    if missing: raise AssertionError(f'{path}: missing {missing}')

def w(root:Path,path:str,content:str)->Path:
    p=root/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content,encoding='utf-8'); return p

def compile_online_updater()->None:
    if not K:
        print('V230_ONLINE_COMPILE_SKIPPED_NO_KOTLINC'); return
    with tempfile.TemporaryDirectory(prefix='nghe_v230_online_') as td:
        r=Path(td)
        pure=sorted((ROOT/'app/src/main/java/vn/nghetruyen/app/ai/vietphrase').glob('*.kt'))
        pure=[p for p in pure if p.name not in {'VietPhraseEntityMapper.kt','VietPhraseOnlineUpdater.kt'}]
        core=r/'vp.jar'
        cp=subprocess.run([K,*map(str,pure),'-d',str(core)],cwd=ROOT,text=True,capture_output=True,timeout=240)
        if cp.returncode:
            print(cp.stdout); print(cp.stderr); raise SystemExit(cp.returncode)
        files=[]
        files += [w(r,'kotlinx/coroutines/Core.kt','''package kotlinx.coroutines
object Dispatchers { object IO }
suspend fun <T> withContext(ctx:Any,block:suspend ()->T):T=block()
''')]
        files += [w(r,'okhttp3/Ok.kt',r'''package okhttp3
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
class HttpUrl(private val raw:String) {
 val isHttps:Boolean get()=raw.startsWith("https://")
 val host:String get()=raw.substringAfter("://","").substringBefore('/').substringBefore(':')
 fun resolve(ref:String):HttpUrl? = when { ref.startsWith("https://") -> HttpUrl(ref); ref.startsWith("/") -> HttpUrl(raw.substringBefore("://")+"://"+host+ref); else -> HttpUrl(raw.substringBeforeLast('/',raw+"/")+"/"+ref) }
 override fun toString():String=raw
 companion object { fun String.toHttpUrlOrNull():HttpUrl? = if (startsWith("https://")) HttpUrl(this) else null }
}
class Request private constructor(val url:HttpUrl) {
 fun newBuilder()=Builder().url(url)
 class Builder {
  private var u:HttpUrl=HttpUrl("https://example.invalid/")
  fun url(value:String)=apply { u=HttpUrl(value) }
  fun url(value:HttpUrl)=apply { u=value }
  fun header(name:String,value:String)=this
  fun get()=this
  fun head()=this
  fun build()=Request(u)
 }
}
class ResponseBody {
 fun contentLength():Long=1024
 fun byteStream():InputStream=ByteArrayInputStream("a=b\n".repeat(6000).toByteArray())
}
class Response(val request:Request):AutoCloseable {
 val code:Int=200
 val isSuccessful:Boolean=true
 val body:ResponseBody?=ResponseBody()
 fun header(name:String):String?=null
 override fun close(){}
}
class Call(private val request:Request){ fun execute()=Response(request) }
class OkHttpClient {
 fun newCall(request:Request)=Call(request)
 class Builder {
  fun connectTimeout(v:Long,u:TimeUnit)=this
  fun readTimeout(v:Long,u:TimeUnit)=this
  fun writeTimeout(v:Long,u:TimeUnit)=this
  fun followRedirects(v:Boolean)=this
  fun followSslRedirects(v:Boolean)=this
  fun build()=OkHttpClient()
 }
}
''')]
        files += [w(r,'vn/nghetruyen/app/data/local/Viet.kt','''package vn.nghetruyen.app.data.local
data class VietPhraseSnapshotEntity(val id:String)
''')]
        files += [w(r,'vn/nghetruyen/app/data/repository/Repo.kt',r'''package vn.nghetruyen.app.data.repository
import vn.nghetruyen.app.ai.vietphrase.*
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
class LibraryRepository {
 suspend fun listVietPhraseDictionaryStates()=emptyList<VietPhrasePersistenceArchiveCodec.DictionaryState>()
 suspend fun previewVietPhraseImport(incoming:List<VietPhraseRule>,replaceKinds:Set<VietPhraseDictionaryKind>)=VietPhraseImportPlanner.plan(emptyList(),incoming,replaceKinds)
 suspend fun commitVietPhraseImport(plan:VietPhraseImportPlanner.Plan,sourceName:String,sourceFormat:String,importedStates:List<VietPhrasePersistenceArchiveCodec.DictionaryState>,label:String)=VietPhraseSnapshotEntity("snapshot")
}
''')]
        files += [ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',ROOT/'app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt']
        cp=subprocess.run([K,'-classpath',str(core),*map(str,files),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True,timeout=240)
        if cp.returncode:
            print(cp.stdout); print(cp.stderr); raise SystemExit(cp.returncode)

def main()->None:
    require('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt','version = 18','MIGRATION_17_18','useCustomVoiceCastPrompt','expressionSpeedLimitPct','speedAdjustPct')
    require('app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt','{{STORY_NOTE}}','{{EXISTING_ROLES}}','{{EXPRESSION_RULES}}','voiceCastDialogueOnly','voiceCastStableNarrator','expressionVolumeLimitPct')
    require('app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt','speedAdjustPct','pitchAdjustPct','volumeAdjustPct')
    require('app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt','speedAdjustPct','pitchAdjustPct','volumeAdjustPct')
    require('app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt','https://vietphrase.pages.dev/','https://vietphrase.app/','MAX_DOWNLOAD_BYTES','MAX_INFLATED_BYTES','executeFollowingTrustedRedirects','commitVietPhraseImport','Trước khi cập nhật VietPhrase từ mạng')
    require('app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt','enum class BackupComponent','FORMAT_VERSION = 15','components: Set<BackupComponent>','requested.intersect(archivedComponents)','writeStoryAiProfiles','speedAdjustPct')
    require('app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt','Phân vai AI nâng cao','Chỉ phân vai lời thoại','Giữ ổn định giọng người kể chuyện','Giới hạn tốc độ')
    require('app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt','TÌM BẢN ONLINE','TẢI VÀ CẬP NHẬT','Sao lưu và khôi phục theo thành phần')
    require('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt','checkVietPhraseOnlineUpdates','installRecommendedVietPhrase','setBackupComponentEnabled')

    db=text('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt')
    block=re.search(r'val MIGRATION_17_18.*?override fun migrate\(db: SupportSQLiteDatabase\) \{(.*?)\n\s*\}\n\s*\}',db,re.S)
    if not block: raise AssertionError('Cannot extract migration 17->18')
    con=sqlite3.connect(':memory:')
    con.executescript('''CREATE TABLE story_ai_profiles(storyId TEXT NOT NULL PRIMARY KEY,mode TEXT NOT NULL,overrideProvider INTEGER NOT NULL,provider TEXT NOT NULL,endpoint TEXT NOT NULL,model TEXT NOT NULL,temperature REAL NOT NULL,useCustomPrompts INTEGER NOT NULL,translationPrompt TEXT NOT NULL,improvePrompt TEXT NOT NULL,autoRunOnOpen INTEGER NOT NULL,updatedAt INTEGER NOT NULL);\nCREATE TABLE chapter_voice_assignments(id TEXT NOT NULL PRIMARY KEY,storyId TEXT NOT NULL,chapterId TEXT NOT NULL,paragraphIndex INTEGER NOT NULL,roleName TEXT NOT NULL,confidence REAL NOT NULL,updatedAt INTEGER NOT NULL);''')
    for sql in re.findall(r'db\.execSQL\("([^"]+)"\)',block.group(1)): con.execute(sql)
    profile={r[1]:r[4] for r in con.execute('pragma table_info(story_ai_profiles)')}
    assign={r[1]:r[4] for r in con.execute('pragma table_info(chapter_voice_assignments)')}
    con.close()
    if profile.get('voiceCastDialogueOnly')!='1' or profile.get('expressionSpeedLimitPct')!='10': raise AssertionError(f'Unsafe profile defaults: {profile}')
    if assign.get('speedAdjustPct')!='0.0' or assign.get('volumeAdjustPct')!='0.0': raise AssertionError(f'Unsafe assignment defaults: {assign}')
    compile_online_updater()
    print('V230_ADVANCED_VOICECAST_VIETPHRASE_ONLINE_SELECTIVE_BACKUP_OK')

if __name__=='__main__':
    try: main()
    except AssertionError as e:
        print(f'FAIL: {e}')
        raise SystemExit(1)
