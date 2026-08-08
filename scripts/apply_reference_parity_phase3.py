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

library = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt').read_text()
story = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt').read_text()
db = Path('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt').read_text()
repo = Path('app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt').read_text()
vm = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt').read_text()

assert 'version = 20' in db
assert 'MIGRATION_19_20' in db
assert 'totalParagraphs INTEGER NOT NULL DEFAULT 0' in db
assert 'fun observeAll(): Flow<List<ReadingProgressEntity>>' in db
assert 'observeReadingProgress' in repo
assert 'totalParagraphs = totalParagraphs.takeIf { it > 0 }' in repo
assert 'readingProgress: Map<String, ReadingProgressEntity>' in vm
assert 'content.paragraphs.size' in vm
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
