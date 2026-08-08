from pathlib import Path

phase7 = Path('scripts/apply_reference_parity_phase7.py').resolve()
exec(phase7.read_text(), {'__name__': '__main__', '__file__': str(phase7)})

db = Path('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt').read_text()
repo = Path('app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt').read_text()
vm = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt').read_text()
reader = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt').read_text()
worker = Path('app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt').read_text()

assert 'version = 21' in db
assert 'MIGRATION_20_21' in db
assert "UPDATE chapters SET downloadedAt = NULL" in db
assert "j.completedChapters > 0" in db
assert 'downloadedAt IS NOT NULL' in db
assert 'downloadedAt IS NULL' in db
assert 'fun observeDownloadedIds(): Flow<List<String>>' in db
assert 'observeDownloadedChapterIds' in repo
assert 'existingDownloadedAt' in repo
assert 'toEntity(downloadedAt = System.currentTimeMillis())' in repo
assert 'it.downloadedAt != null' in repo
assert 'private fun ChapterContent.toEntity(downloadedAt: Long? = null)' in repo
assert 'downloadedChapterIds: Set<String>' in vm
assert 'observeDownloadedChapterIds().collect' in vm
assert 'content.chapter.id in state.downloadedChapterIds' in reader
assert 'state.offlineStorage[storyId]?.chapterCount' not in reader
assert 'repository.saveDownloadedChapter' in worker
for token in ['TÌM TRONG CHƯƠNG', 'DỊCH AI', 'PHÂN VAI AI', 'DANH SÁCH NHẠC NỀN', 'VietPhraseDiagnosticExporter']:
    assert token in reader, f'Reader regression: {token}'
print('REFERENCE_PER_CHAPTER_OFFLINE_ASSERTIONS_OK')
