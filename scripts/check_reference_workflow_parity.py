#!/usr/bin/env python3
"""Guard the XPK-aligned reader, library, and music workflow structure."""

from pathlib import Path

reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
component = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()
unified_audio_manager = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt")
if unified_audio_manager.exists():
    # MUSIC/AMBIENCE/SFX editing is now hosted by the unified manager. Validate the complete audio
    # workflow surface instead of requiring editor controls to remain in their historical file.
    component += "\n" + unified_audio_manager.read_text()
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
    "onManageMusic = {",
    "SỬA TÊN / MÔ TẢ",
    "displayFontSizeDraft",
    "displayLineHeightDraft",
]
missing = [item for item in required_reader if item not in reader]
if missing:
    raise SystemExit("REFERENCE_WORKFLOW missing Reader markers: " + repr(missing))

for marker in [
    'label = "QUẢN LÝ NHẠC ($musicTrackCount)"',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    'label = { Text("Mô tả") }',
    'Text("LƯU")',
    'Text("XÓA")',
]:
    if marker not in component:
        raise SystemExit("REFERENCE_WORKFLOW audio manager marker missing: " + marker)
if not any(marker in component for marker in [
    'Text("CHUẨN HÓA")',
    'UnifiedAssetActionButton("CHUẨN HÓA")',
]):
    raise SystemExit("REFERENCE_WORKFLOW audio manager marker missing: CHUẨN HÓA")

manager_positions = [
    component.find('label = "QUẢN LÝ NHẠC ($musicTrackCount)"'),
    component.find('label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG'),
    component.find('label = "QUẢN LÝ HIỆU ỨNG ÂM THANH'),
]
if not (0 <= manager_positions[0] < manager_positions[1] < manager_positions[2]):
    raise SystemExit("REFERENCE_WORKFLOW three audio managers must stay adjacent in Music/Ambience/SFX order")

reader_options_start = reader.find('    if (showReaderOptions) {')
reader_options_end = reader.find('    if (showReaderModeDialog) {', reader_options_start)
if reader_options_start < 0 or reader_options_end < 0:
    raise SystemExit("REFERENCE_WORKFLOW standard Reader options block missing")
reader_options = reader[reader_options_start:reader_options_end]
if "AudioDirectionLayerSwitches(" in reader_options:
    raise SystemExit("REFERENCE_WORKFLOW AI audio controls must not spill into standard Reader options")
if 'ReaderMenuButton("NHẠC NỀN")' not in reader_options:
    raise SystemExit("REFERENCE_WORKFLOW Reader options must keep the Background Music entry")
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

music_start = reader.find('    if (showMusicDialog) {')
music_end = reader.find('    if (showMusicNormalizationProgress) {', music_start)
if music_start < 0 or music_end < 0:
    raise SystemExit("REFERENCE_WORKFLOW Background Music dialog missing")
music_dialog = reader[music_start:music_end]
for marker in [
    'Text("Bật nhạc nền", Modifier.weight(1f))',
    "AudioDirectionLayerSwitches(",
    "musicTrackCount = musicTracks.size",
    "onManageMusic = {",
    'Text("Chế độ phát"',
    'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"',
]:
    if marker not in music_dialog:
        raise SystemExit("REFERENCE_WORKFLOW music control missing: " + marker)
if 'ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC")' in music_dialog:
    raise SystemExit("REFERENCE_WORKFLOW duplicate standalone music manager remains")
if music_dialog.find('Text("Bật nhạc nền"') > music_dialog.find("AudioDirectionLayerSwitches("):
    raise SystemExit("REFERENCE_WORKFLOW master Background Music switch must be first")
for obsolete in [
    "CÂN BẰNG ÂM THANH",
    'title = { Text("AI & CHUYỂN NGỮ") }',
    'title = { Text("KHÁC") }',
    "musicAdvanced",
    "Trao toàn quyền giữ và đổi nhạc cho AI",
]:
    if obsolete in music_dialog or obsolete in reader_options:
        raise SystemExit("REFERENCE_WORKFLOW obsolete Reader navigation/control: " + obsolete)

music_library_start = reader.find('    if (showMusicLibrary) {')
music_library_end = reader.find('    selectedMusicTrackId?.let', music_library_start)
music_library = reader[music_library_start:music_library_end]
for marker in [
    'Text("THÊM BÀI")',
    'Text("MÔ TẢ HÀNG LOẠT")',
    'Text("XÓA TẤT CẢ")',
    'Text("LƯU DANH SÁCH")',
    'Text("HỦY")',
]:
    if marker not in music_library:
        raise SystemExit("REFERENCE_WORKFLOW music footer action missing: " + marker)
body_end = music_library.find('confirmButton = { Column(Modifier.fillMaxWidth())')
if body_end < 0:
    raise SystemExit("REFERENCE_WORKFLOW fixed music footer missing")
for marker in ['Text("THÊM BÀI")', 'Text("MÔ TẢ HÀNG LOẠT")', 'Text("XÓA TẤT CẢ")', 'Text("LƯU DANH SÁCH")']:
    if marker in music_library[:body_end]:
        raise SystemExit("REFERENCE_WORKFLOW music action leaked into scrollable body: " + marker)

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
