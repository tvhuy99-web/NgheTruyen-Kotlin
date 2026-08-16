from pathlib import Path
import re, subprocess

TEMP={Path('scripts/safe_library_wrapper_cleanup_temp.py'),Path('.github/workflows/safe-library-wrapper-cleanup-temp.yml')}
EXT={'.kt','.kts','.java','.xml','.gradle','.properties','.toml','.py','.sh','.ps1','.yml','.yaml','.md','.txt','.json','.lua','.patch','.b64'}
texts={}
for raw in subprocess.check_output(['git','ls-files'],text=True).splitlines():
    p=Path(raw)
    if p in TEMP or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name) or p.suffix.lower() not in EXT: continue
    try: texts[p]=p.read_text(encoding='utf-8',errors='replace')
    except OSError: pass

def token_count(name):
    rx=re.compile(rf'\b{re.escape(name)}\b')
    return sum(len(rx.findall(t)) for t in texts.values())

def replace1(path,old,new=''):
    p=Path(path); t=p.read_text(encoding='utf-8'); n=t.count(old)
    if n!=1: raise SystemExit(f'{path}: expected exact match once, found {n}: {old!r}')
    p.write_text(t.replace(old,new,1),encoding='utf-8'); print('patched',path)

def try_remove(name,block):
    n=token_count(name); print('token_count',name,n)
    if n!=1:
        print('KEEP',name,'references=',n); return False
    replace1(REPO,block)
    print('REMOVED',name); return True

REPO='app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt'

try_remove('observeAiUsage','    fun observeAiUsage(): Flow<List<AiUsageDailyEntity>> = db.aiUsageDailyDao().observeRecent()\n')
try_remove('clearPlaybackCheckpoint','    suspend fun clearPlaybackCheckpoint() = db.playbackCheckpointDao().clear()\n')
try_remove('listFollowing','    suspend fun listFollowing(): List<FollowedStoryEntity> = db.followingDao().listAll()\n\n')
try_remove('latestDownloadJob','    suspend fun latestDownloadJob(storyId: String): DownloadJobEntity? =\n        db.downloadJobDao().latestForStory(storyId)\n\n')
try_remove('listOfflineChapters','    suspend fun listOfflineChapters(storyId: String): List<ChapterEntity> = db.chapterDao().listForStory(storyId)\n\n')
try_remove('hasDownloadedChapter','    suspend fun hasDownloadedChapter(chapterId: String): Boolean =\n        db.chapterDao().get(chapterId)?.let { it.downloadedAt != null && !it.content.isNullOrBlank() } == true\n\n')
try_remove('deleteAudioExportJob','    suspend fun deleteAudioExportJob(jobId: String) {\n        db.audioExportJobDao().delete(jobId)\n    }\n\n')
try_remove('getChapterAt','    suspend fun getChapterAt(storyId: String, chapterIndex: Int): ChapterEntity? =\n        db.chapterDao().getAt(storyId, chapterIndex)\n\n')
try_remove('getChapterByRemoteUrl','    suspend fun getChapterByRemoteUrl(storyId: String, remoteUrl: String): ChapterEntity? =\n        db.chapterDao().getByRemoteUrl(storyId, remoteUrl)\n\n')
try_remove('loadOfflineChapter','    suspend fun loadOfflineChapter(chapterId: String): ChapterContent? = loadCachedChapter(chapterId)\n\n')

# Fail rather than create a no-op commit: at least one candidate must still be declaration-only.
product_diff=subprocess.check_output(['git','diff','--',REPO],text=True)
if not product_diff.strip():
    raise SystemExit('No declaration-only LibraryRepository wrappers remained; nothing to commit.')
