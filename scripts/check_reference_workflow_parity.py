#!/usr/bin/env python3
"""Guard the XPK-aligned reader, library, and music workflow structure."""

from pathlib import Path

reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
vm = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text()
library = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt").read_text()
settings = Path("app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt").read_text()
narration = Path("app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt").read_text()

required_reader = [
    "TRỞ LẠI DANH SÁCH CHƯƠNG",
    "LƯU VỊ TRÍ ĐỌC",
    "HIỂN THỊ VĂN BẢN",
    "XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)",
    "THIẾT LẬP AI CHO TRUYỆN NÀY",
    "PHÂN VAI TTS CHO TRUYỆN NÀY",
    "StoryReferenceAdvancedDialogs(",
    "AudioDirectionLayerSwitches(",
    "CHUẨN HÓA TOÀN BỘ KHO NHẠC",
    "QUẢN LÝ DANH SÁCH NHẠC",
    "displayFontSizeDraft",
    "displayLineHeightDraft",
]
missing = [item for item in required_reader if item not in reader]
if missing:
    raise SystemExit("REFERENCE_WORKFLOW missing Reader markers: " + repr(missing))

reader_options_start = reader.find('    if (showReaderOptions) {')
reader_options_end = reader.find('    if (showReaderModeDialog) {', reader_options_start)
if reader_options_start < 0 or reader_options_end < 0:
    raise SystemExit("REFERENCE_WORKFLOW standard Reader options block missing")
reader_options = reader[reader_options_start:reader_options_end]
if "AudioDirectionLayerSwitches(" not in reader_options:
    raise SystemExit("REFERENCE_WORKFLOW AI audio controls must live directly in standard Reader options")
for extra in [
    'Text("MỞ RỘNG"',
    'ĐÁNH DẤU ĐOẠN',
    'GHI CHÚ ĐOẠN',
    'SỬA GHI CHÚ ĐOẠN',
    'ÁP DỤNG VIETPHRASE',
    'CẢI THIỆN VIETPHRASE',
    'LẬP NHẠC CẢNH',
    'PHÂN VAI + NHẠC',
    'XEM NHẬT KÝ CHẨN ĐOÁN',
]:
    if extra in reader_options:
        raise SystemExit("REFERENCE_WORKFLOW Kotlin-only action leaked into standard Reader options: " + extra)

for obsolete in [
    'title = { Text("AI & CHUYỂN NGỮ") }',
    'title = { Text("KHÁC") }',
    "musicAdvanced",
    "Trao toàn quyền giữ và đổi nhạc cho AI",
]:
    if obsolete in reader:
        raise SystemExit("REFERENCE_WORKFLOW obsolete Reader navigation/control: " + obsolete)

start = vm.find("private fun openStoryAdvancedOptions")
if start < 0:
    raise SystemExit("REFERENCE_WORKFLOW openStoryAdvancedOptions missing")
end = vm.find("\n    fun ", start + 1)
if end < 0:
    end = vm.find("\n    private fun ", start + 1)
if end < 0:
    end = len(vm)
if "destination = Destination.Story" in vm[start:end]:
    raise SystemExit("REFERENCE_WORKFLOW story settings still force destination change")

for marker in [
    "ĐỌC TIẾP",
    "XÓA KHỎI ĐANG ĐỌC",
    "MỞ TRUYỆN",
    "BỎ ĐÁNH DẤU",
    "BỎ THEO DÕI",
]:
    if marker not in library:
        raise SystemExit("REFERENCE_WORKFLOW missing Library action: " + marker)

for marker in [
    "backgroundMusicAttackMillis: Int = 1850",
    "backgroundMusicReleaseMillis: Int = 2050",
    "sceneMusicTargetLufs: Float = -24.0f",
    "SceneMusicPlaybackMode.SEQUENTIAL",
]:
    if marker not in settings:
        raise SystemExit("REFERENCE_WORKFLOW music default missing: " + marker)

if "tagsCsv.split(',')" in narration:
    raise SystemExit("REFERENCE_WORKFLOW music description is still split as CSV tags")

print("REFERENCE_WORKFLOW_PARITY=PASS")