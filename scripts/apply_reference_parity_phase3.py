from pathlib import Path

phase5 = Path('scripts/apply_reference_parity_phase5.py').resolve()
exec(phase5.read_text(), {'__name__': '__main__', '__file__': str(phase5)})

validator_path = Path('scripts/validate_release.py')
validator = validator_path.read_text()
old_hint = '        "Nhập tên chương hoặc số chương",\n'
new_hint = '        "Nhập tên, số chương hoặc vài ký tự liên quan",\n        "Hãy nhập tên hoặc số chương.",\n'
if validator.count(old_hint) != 1:
    raise SystemExit(f'validator chapter hint: expected 1 occurrence, found {validator.count(old_hint)}')
validator_path.write_text(validator.replace(old_hint, new_hint, 1))

backup_path = Path('app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt')
backup = backup_path.read_text()
old_write = '''            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("updatedAt").value(item.updatedAt)
'''
new_write = '''            name("paragraphIndex").value(item.paragraphIndex.toLong())
            name("totalParagraphs").value(item.totalParagraphs.toLong())
            name("updatedAt").value(item.updatedAt)
'''
if backup.count(old_write) != 1:
    raise SystemExit(f'backup progress writer: expected 1 occurrence, found {backup.count(old_write)}')
backup = backup.replace(old_write, new_write, 1)
old_read = '''            var storyId = ""; var chapterId = ""; var paragraph = 0; var updated = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "updatedAt" -> updated = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank() && chapterId.isNotBlank()) { "Tiến độ đọc không hợp lệ." }
            ReadingProgressEntity(storyId, chapterId, paragraph, updated)
'''
new_read = '''            var storyId = ""; var chapterId = ""; var paragraph = 0; var totalParagraphs = 0; var updated = 0L
            beginObject()
            while (hasNext()) when (nextName()) {
                "storyId" -> storyId = nextStringSafe("")
                "chapterId" -> chapterId = nextStringSafe("")
                "paragraphIndex" -> paragraph = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "totalParagraphs" -> totalParagraphs = nextLongSafe(0L).toInt().coerceAtLeast(0)
                "updatedAt" -> updated = nextLongSafe(0L)
                else -> skipValue()
            }
            endObject()
            require(storyId.isNotBlank() && chapterId.isNotBlank()) { "Tiến độ đọc không hợp lệ." }
            ReadingProgressEntity(
                storyId = storyId,
                chapterId = chapterId,
                paragraphIndex = paragraph,
                totalParagraphs = totalParagraphs,
                updatedAt = updated,
            )
'''
if backup.count(old_read) != 1:
    raise SystemExit(f'backup progress reader: expected 1 occurrence, found {backup.count(old_read)}')
backup_path.write_text(backup.replace(old_read, new_read, 1))

library = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt').read_text()
story = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt').read_text()
db = Path('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt').read_text()
repo = Path('app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt').read_text()
vm = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt').read_text()
backup = backup_path.read_text()

assert 'version = 20' in db
assert 'MIGRATION_19_20' in db
assert 'totalParagraphs INTEGER NOT NULL DEFAULT 0' in db
assert 'fun observeAll(): Flow<List<ReadingProgressEntity>>' in db
assert 'observeReadingProgress' in repo
assert 'totalParagraphs = totalParagraphs.takeIf { it > 0 }' in repo
assert 'readingProgress: Map<String, ReadingProgressEntity>' in vm
assert 'content.paragraphs.size' in vm
assert 'name("totalParagraphs").value(item.totalParagraphs.toLong())' in backup
assert 'totalParagraphs = totalParagraphs' in backup
assert 'state.bookmarks.size + state.notes.size' not in library
assert 'BookmarkAndNoteList' not in library
assert 'private fun BookmarkList(' in library
assert 'GHI CHÚ •' not in library
assert 'progress.totalParagraphs > 0' in library
assert '(progress.paragraphIndex + 1).toDouble() / progress.totalParagraphs.toDouble()' in library
assert 'Nhập tên, số chương hoặc vài ký tự liên quan' in story
assert 'Hãy nhập tên hoặc số chương.' in story
assert 'RadioButton(selected = !state.chapterSortDescending' in story
assert 'RadioButton(selected = state.chapterSortDescending' in story
print('REFERENCE_FINAL_LIBRARY_CHAPTER_ASSERTIONS_OK')
