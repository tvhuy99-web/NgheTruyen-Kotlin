from pathlib import Path

phase6 = Path('scripts/apply_reference_parity_phase6.py').resolve()
exec(phase6.read_text(), {'__name__': '__main__', '__file__': str(phase6)})

reader = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt').read_text()
db = Path('app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt').read_text()
exporter = Path('app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt').read_text()

music_tokens = [
    'DANH SÁCH NHẠC NỀN', 'Tìm theo tên bài', 'THÊM BÀI', 'DÁN MÔ TẢ',
    'SAO CHÉP TÊN', 'SAO CHÉP MÔ TẢ', 'DỪNG NGHE THỬ', 'XÓA TẤT CẢ',
    'HỦY', 'LƯU DANH SÁCH', 'DÁN MÔ TẢ HÀNG LOẠT', 'KẾT QUẢ KIỂM TRA',
    'ÁP DỤNG DÒNG HỢP LỆ', 'SAO CHÉP LỖI', 'QUAY LẠI', 'NGHE THỬ',
    'SỬA TÊN VÀ MÔ TẢ', 'TẮT BÀI NÀY', 'BẬT BÀI NÀY', 'DI CHUYỂN LÊN',
    'DI CHUYỂN XUỐNG', 'XÓA KHỎI DANH SÁCH', 'XÓA BÀI NHẠC', 'SỬA THÔNG TIN AI',
]
for token in music_tokens:
    assert token in reader, token
assert 'musicLibraryDraft' in reader
assert 'musicLibraryBaselineIds' in reader
assert 'tagsCsv.length > 300' in reader
assert 'musicLibraryDraft.size > 500' in reader
assert 'orderIndex ASC, title COLLATE NOCASE' in db
assert 'suspend fun deleteAll()' in db

for token in [
    'vietphrase_diagnostic_', 'README.txt', 'summary.txt', 'source.txt', 'translated.txt', 'trace.tsv',
    'NHẬT KÝ VIETPHRASE - NGHE TRUYỆN', 'TRACE_LIMIT = 20_000',
]:
    assert token in exporter, token
for token in [
    'ĐANG TẠO NHẬT KÝ VIETPHRASE', 'FILE ZIP:', '60 QUYẾT ĐỊNH ĐẦU TIÊN:',
    'SAO CHÉP ĐƯỜNG DẪN', 'VietPhraseDiagnosticExporter',
]:
    assert token in reader, token
assert 'showVietPhraseLogDialog' not in reader
print('REFERENCE_READER_MUSIC_VIETPHRASE_ASSERTIONS_OK')
