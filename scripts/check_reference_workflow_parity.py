#!/usr/bin/env python3
"""Guard the XPK-aligned reader, library, and music workflow structure."""

from pathlib import Path

reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
component = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()
audio_manager = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt").read_text()
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
    "StoryAudioSourceMode.LOCAL_MANUAL",
    "StoryAudioSourceMode.AI_LOCAL",
    "StoryAudioSourceMode.AI_FREESOUND",
    'Text("MODE 1 · PHÁT THỦ CÔNG TỪ THƯ VIỆN LOCAL")',
    'Text("MODE 2 · AI CHỌN TỪ THƯ VIỆN")',
    'Text("MODE 3 · AI TỰ ĐỘNG — THƯ VIỆN + FREESOUND")',
    'label = "QUẢN LÝ NHẠC ($musicCount)"',
    'label = "QUẢN LÝ MÔI TRƯỜNG ($ambienceCount)"',
    'label = "QUẢN LÝ SFX ($sfxCount)"',
    'label = "CHUẨN HÓA TOÀN BỘ THƯ VIỆN"',
    "UnifiedAudioAssetManagerDialog(",
    "managedFreesoundOnly = false",
]:
    if marker not in component:
        raise SystemExit("REFERENCE_WORKFLOW audio routing marker missing: " + marker)

for marker in [
    'label = { Text("Mô tả") }',
    'UnifiedAssetActionButton("CHUẨN HÓA")',
    'Text("TÌM TRÊN FREESOUND")',
    'Text("TÌM & TẢI THÊM TRÊN FREESOUND")',
    'Text("CÔNG CỤ FREESOUND NÂNG CAO")',
    'UnifiedAssetActionButton("TÌM ÂM THANH TƯƠNG TỰ")',
    'Text("LƯU")',
    'Text("XÓA")',
]:
    if marker not in audio_manager:
        raise SystemExit("REFERENCE_WORKFLOW audio manager marker missing: " + marker)

manager_positions = [
    component.find('label = "QUẢN LÝ NHẠC ($musicCount)"'),
    component.find('label = "QUẢN LÝ MÔI TRƯỜNG ($ambienceCount)"'),
    component.find('label = "QUẢN LÝ SFX ($sfxCount)"'),
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
    "AudioDirectionLayerSwitches(",
    "onSourceModeChanged = { mode ->",
    'Text("Bật nhạc nền thủ công", Modifier.weight(1f))',
    "musicTrackCount = musicTracks.size",
    "onManageMusic = {",
    'Text("Chế độ phát"',
    'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"',
]:
    if marker not in music_dialog:
        raise SystemExit("REFERENCE_WORKFLOW music control missing: " + marker)
if 'ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC")' in music_dialog:
    raise SystemExit("REFERENCE_WORKFLOW duplicate standalone music manager remains")
if music_dialog.find("AudioDirectionLayerSwitches(") > music_dialog.find('Text("Bật nhạc nền thủ công"'):
    raise SystemExit("REFERENCE_WORKFLOW source-mode controls must precede the manual-only music switch")
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
