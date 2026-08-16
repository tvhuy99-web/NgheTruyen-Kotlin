from pathlib import Path
import re, subprocess

TEMP={Path('scripts/safe_app_api_reports_temp.py'),Path('.github/workflows/safe-app-api-reports-temp.yml')}
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
    if n!=1: raise SystemExit(f'{path}: {old!r} expected exact match once, found {n}')
    p.write_text(t.replace(old,new,1),encoding='utf-8'); print('patched',path)

def try_remove(name,path,block,normalize_eof=False):
    n=token_count(name); print('token_count',name,n)
    if n!=1:
        print('KEEP',name,'because current tracked references=',n); return False
    replace1(path,block)
    if normalize_eof:
        p=Path(path); p.write_text(p.read_text(encoding='utf-8').rstrip()+"\n",encoding='utf-8')
    print('REMOVED',name); return True

try_remove('AudioExportProgress','app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt','\n\ndata class AudioExportProgress(\n    val completedSegments: Int,\n    val totalSegments: Int,\n    val stage: String,\n)',True)
try_remove('enqueueStory','app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt','''    fun enqueueStory(
        sourceId: String,
        storyId: String,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.ALL,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

''')
try_remove('openLibraryStory','app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt','''    fun openLibraryStory(entity: StoryEntity) {
        if (entity.sourceId == "offline") {
            openOfflineStory(entity)
        } else {
            openRemoteStory(entity.toStorySummary())
        }
    }

''')
container='app/src/main/java/vn/nghetruyen/app/AppContainer.kt'
removed_install=try_remove('installOrUpdateVBook',container,'''    fun installOrUpdateVBook(
        repositoryId: String,
        remoteIdentity: String,
        version: String?,
        packageBytes: ByteArray,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        val result = vBookSourcePlatform.installOrUpdate(
            repositoryId = repositoryId,
            remoteIdentity = remoteIdentity,
            version = version,
            packageBytes = packageBytes,
            trust = trust,
        )
        refreshSourceRegistry()
        return result
    }

''')
removed_rollback=try_remove('rollbackVBook',container,'''    fun rollbackVBook(repositoryId: String, remoteIdentity: String): SourceArtifactDescriptor {
        val restored = vBookSourcePlatform.rollback(repositoryId, remoteIdentity)
        refreshSourceRegistry()
        return restored
    }

''')
if removed_install or removed_rollback:
    p=Path(container); t=p.read_text(encoding='utf-8')
    for symbol,imp in [
      ('SourceArtifactDescriptor','import com.nghetruyen.source.platform.SourceArtifactDescriptor\n'),
      ('SourceTrustState','import com.nghetruyen.source.platform.SourceTrustState\n'),
      ('VBookUpdateResult','import com.nghetruyen.source.repository.VBookUpdateResult\n'),
    ]:
        c=len(re.findall(rf'\b{re.escape(symbol)}\b',t)); print('container_type_count',symbol,c)
        if c==1 and imp in t: t=t.replace(imp,'',1)
    p.write_text(t,encoding='utf-8')

# Exact duplicate root reports: keep docs/ copies. A reference is safe only if it lives under docs/ and is not explicitly parent/root-relative.
for root_name,docs_name in [
 ('MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md','docs/MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md'),
 ('MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md','docs/MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md'),
]:
    root=Path(root_name); docs=Path(docs_name)
    if not root.is_file() or not docs.is_file(): raise SystemExit(f'missing duplicate pair {root_name}')
    if root.read_bytes()!=docs.read_bytes(): raise SystemExit(f'content diverged for {root_name}')
    refs=[]
    for q,text in texts.items():
        if q in {root,docs}: continue
        if root_name in text:
            refs.append(str(q))
            if not q.as_posix().startswith('docs/') or f'../{root_name}' in text:
                raise SystemExit(f'root-specific reference blocks deletion of {root_name}: {q}')
    print('duplicate_refs',root_name,refs)
    root.unlink(); print('REMOVED duplicate root report',root_name)
