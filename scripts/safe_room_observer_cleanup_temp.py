from pathlib import Path
import subprocess

TEMP = {
    Path('scripts/safe_room_observer_cleanup_temp.py'),
    Path('.github/workflows/safe-room-observer-cleanup-temp.yml'),
}
TEXT_EXT = {'.kt','.kts','.java','.xml','.gradle','.properties','.toml','.py','.sh','.ps1','.yml','.yaml','.md','.txt','.json','.lua','.patch','.b64'}


def replace_once(path: str, old: str, new: str = '') -> None:
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exact match once, found {count}: {old!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')
    print('patched', path)


texts = {}
for raw in subprocess.check_output(['git', 'ls-files'], text=True).splitlines():
    p = Path(raw)
    if p in TEMP or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name):
        continue
    if p.suffix.lower() not in TEXT_EXT:
        continue
    try:
        texts[p] = p.read_text(encoding='utf-8', errors='replace')
    except OSError:
        pass

# These DAO calls must exist only in the obsolete LibraryRepository wrappers.
call_guards = {
    'db.progressDao().observeAll()': 1,
    'db.chapterDao().observeOfflineStorage()': 1,
    'db.chapterDao().observeDownloadedIds()': 1,
    'db.chapterDao().observeStorageUsage()': 1,
}
for needle, expected in call_guards.items():
    count = sum(text.count(needle) for text in texts.values())
    print('call_count', needle, count)
    if count != expected:
        raise SystemExit(f'{needle}: expected {expected} tracked occurrence, found {count}; refusing cleanup')

repo = 'app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt'
replace_once(repo, '    fun observeReadingProgress(): Flow<List<ReadingProgressEntity>> = db.progressDao().observeAll()\n')
replace_once(repo, '    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>> = db.chapterDao().observeOfflineStorage()\n')
replace_once(repo, '    fun observeDownloadedChapterIds(): Flow<List<String>> = db.chapterDao().observeDownloadedIds()\n')
replace_once(repo, '    fun observeStorageUsage(): Flow<StorageUsage> = db.chapterDao().observeStorageUsage()\n')

db = 'app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt'
replace_once(
    db,
    '    @Query("SELECT id FROM chapters WHERE downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != \'\'")\n'
    '    fun observeDownloadedIds(): Flow<List<String>>\n\n',
)
replace_once(
    db,
    '''    @Query("""
        SELECT c.storyId AS storyId, COUNT(*) AS chapterCount,
               COALESCE(SUM(LENGTH(CAST(c.content AS BLOB))), 0) AS bytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
        WHERE c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''
        GROUP BY c.storyId
    """)
    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>>

''',
)
replace_once(
    db,
    '''    @Query("""
        SELECT
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS downloadedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS downloadedBytes,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS cachedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS cachedBytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
    """)
    fun observeStorageUsage(): Flow<StorageUsage>

''',
)
replace_once(
    db,
    '    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")\n'
    '    fun observeAll(): Flow<List<ReadingProgressEntity>>\n\n',
)
