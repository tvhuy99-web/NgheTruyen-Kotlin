#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
personal = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text(encoding="utf-8")
reader = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text(encoding="utf-8")
component = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text(encoding="utf-8")
coordinator = (ROOT / "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt").read_text(encoding="utf-8")
debug_strings = (ROOT / "app/src/debug/res/values/strings.xml").read_text(encoding="utf-8")
ui_test = (ROOT / "app/src/androidTest/java/vn/nghetruyen/app/AudioDirectorMusicSettingsUiTest.kt").read_text(encoding="utf-8")


def section(text: str, start: str, end: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise AssertionError(f"missing section start: {start}")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise AssertionError(f"missing section end: {end}")
    return text[start_index:end_index]


music_page = section(
    personal,
    '"settings_music" -> PersonalSubPage("NHẠC NỀN & NHẠC CẢNH")',
    '"settings_following" -> PersonalSubPage',
)
assert "BackgroundMusicCard(" in music_page
assert "SceneMusicLibraryCard(" in music_page
assert "AudioDirectionLayerSwitches(" not in music_page

playback_card = section(
    personal,
    "private fun PlaybackAutomationCard(",
    "private fun ReferenceFloatSettingsSlider(",
)
assert "AudioDirectionLayerSwitches(" not in playback_card
assert 'SettingSwitch("Tự lập nhạc cảnh"' not in playback_card

reader_options = section(
    reader,
    "    if (showReaderOptions) {",
    "    if (showReaderModeDialog) {",
)
assert 'title = { Text("TÙY CHỌN ĐỌC") }' in reader_options
assert 'ReaderMenuButton("NHẠC NỀN")' in reader_options
assert "AudioDirectionLayerSwitches(" not in reader_options

music_dialog = section(
    reader,
    "    if (showMusicDialog) {",
    "    if (showMusicNormalizationProgress) {",
)
assert 'Text("Bật nhạc nền", Modifier.weight(1f))' in music_dialog
assert "AudioDirectionLayerSwitches(" in music_dialog
assert music_dialog.find('Text("Bật nhạc nền"') < music_dialog.find("AudioDirectionLayerSwitches(")
assert 'Text("Chế độ phát"' in music_dialog
assert 'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"' in music_dialog
assert 'ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC")' in music_dialog

for removed in (
    "CÂN BẰNG ÂM THANH",
    "Mỗi bài nhạc được đo một lần",
    "Tên và mô tả của các bài đang bật được gửi cho AI",
):
    assert removed not in music_dialog, f"obsolete background-music explanation still visible: {removed}"

assert "music = appSettings.autoSceneMusicEnabled," in coordinator
assert "backgroundMusicEnabled && appSettings.autoSceneMusicEnabled" not in coordinator

required_component_tokens = (
    'title = "Nhạc cảnh AI"',
    'title = "Âm thanh môi trường AI"',
    'title = "Hiệu ứng âm thanh AI"',
    'title = "Mức chuẩn hóa"',
    'title = "Attack"',
    'title = "Release"',
    'label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC"',
    'label = "QUẢN LÝ NHẠC',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    'Text("THÊM TỆP")',
    'Text("NGHE THỬ")',
    'Text("DỪNG")',
    'Text("CHUẨN HÓA")',
    'Text("LƯU")',
    'Text("XÓA")',
    'Text("ĐÓNG")',
    "ActivityResultContracts.OpenMultipleDocuments()",
)
for token in required_component_tokens:
    assert token in component, f"missing compact audio UI token: {token}"

for removed in (
    'text = "ÂM THANH AI"',
    'text = "Tình trạng: Nhạc cảnh',
    'Text("NGUYÊN TẮC"',
    "AI giữ hoặc đổi nhạc theo cảnh/UNIT",
    "Ambience là nền môi trường kéo dài",
    "SFX là âm one-shot",
    "Mỗi trình quản lý cho phép chọn nhiều tệp",
    "Mô tả cho AI",
    "description: String",
):
    assert removed not in component, f"obsolete audio description still visible: {removed}"

music_library = section(reader, "    if (showMusicLibrary) {", "    selectedMusicTrackId?.let")
for removed in (
    "Ước tính khi gửi danh mục AI",
    "Đã có mô tả",
    'Text("DÁN MÔ TẢ")',
    'Text("SAO CHÉP MÔ TẢ")',
):
    assert removed not in music_library, f"obsolete music-list description/control remains: {removed}"
assert 'Text("THÊM BÀI")' in music_library
assert 'Text("LƯU DANH SÁCH")' in music_library
assert 'Text("HỦY")' in music_library

assert "bringIntoViewRequester" not in component
assert "BuildConfig.DIAGNOSTIC_BUILD_ID" not in component
assert '<string name="app_name">Nghe Truyện</string>' in debug_strings
assert "AI Sound Director" not in debug_strings

for token in (
    'onNodeWithText("CÁ NHÂN"',
    'onNodeWithText("Cài đặt"',
    'hasText("NHẠC NỀN & NHẠC CẢNH")',
    'onNodeWithText("Nhạc nền cục bộ"',
    'onNodeWithText("ÂM THANH AI"',
    'assertDoesNotExist()',
):
    assert token in ui_test, f"music-page regression UI test missing token: {token}"

print("AUDIO_DIRECTION_UI_WIRING=PASS")
