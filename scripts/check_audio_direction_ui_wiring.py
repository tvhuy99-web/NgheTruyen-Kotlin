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


# Personal > Music remains the legacy/manual surface and must not duplicate Reader AI controls.
music_page = section(
    personal,
    '"settings_music" -> PersonalSubPage("NHẠC NỀN & NHẠC CẢNH")',
    '"settings_following" -> PersonalSubPage',
)
assert "BackgroundMusicCard(" in music_page, "music settings must keep local background music controls"
assert "SceneMusicLibraryCard(" in music_page, "music settings must keep the existing scene-music library"
assert "AudioDirectionLayerSwitches(" not in music_page, "AI sound controls must not duplicate on settings_music"

playback_card = section(
    personal,
    "private fun PlaybackAutomationCard(",
    "private fun ReferenceFloatSettingsSlider(",
)
assert "AudioDirectionLayerSwitches(" not in playback_card, "AI sound asset managers must not live in playback automation"
assert 'SettingSwitch("Tự lập nhạc cảnh"' not in playback_card, "Music AI switch must not be duplicated in playback automation"

# Reader options expose one Background Music entry. The compact controls live inside that dialog.
reader_options = section(
    reader,
    "    if (showReaderOptions) {",
    "    if (showReaderModeDialog) {",
)
assert 'title = { Text("TÙY CHỌN ĐỌC") }' in reader_options, "reader options dialog must keep its existing title"
assert 'ReaderMenuButton("NHẠC NỀN")' in reader_options, "background-music entry must remain in reader options"
assert "AudioDirectionLayerSwitches(" not in reader_options, "AI sound controls must not spill into reader options"

music_dialog = section(
    reader,
    "    if (showMusicDialog) {",
    "    if (showMusicNormalizationProgress) {",
)
assert 'Text("Bật nhạc nền", Modifier.weight(1f))' in music_dialog, "background-music master switch must exist"
assert "AudioDirectionLayerSwitches(" in music_dialog, "AI sound controls must render inside Background Music"
assert music_dialog.find('Text("Bật nhạc nền"') < music_dialog.find("AudioDirectionLayerSwitches("), "master background-music switch must be first"
assert 'Text("Chế độ phát"' in music_dialog, "compact playback mode selector must remain"
assert 'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"' in music_dialog, "voice ducking control must remain"
assert 'ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC")' in music_dialog, "music list manager must remain"

for removed in (
    "CÂN BẰNG ÂM THANH",
    "Mỗi bài nhạc được đo một lần",
    'ReaderFloatSlider("Mức chuẩn hóa"',
    'ReaderIntSlider("Attack"',
    'ReaderIntSlider("Release"',
    'ReaderMenuButton("CHUẨN HÓA TOÀN BỘ KHO NHẠC")',
    "Tên và mô tả của các bài đang bật được gửi cho AI",
):
    assert removed not in music_dialog, f"obsolete background-music item still visible: {removed}"

# Music AI remains independent from the manual backgroundMusicEnabled switch.
assert "music = appSettings.autoSceneMusicEnabled," in coordinator, "Music AI must follow its own switch"
assert "backgroundMusicEnabled && appSettings.autoSceneMusicEnabled" not in coordinator, "Music AI must not depend on manual background music"

required_component_tokens = (
    'title = "Nhạc cảnh AI"',
    'title = "Âm thanh môi trường AI"',
    'title = "Hiệu ứng âm thanh AI"',
    'label = "QUẢN LÝ NHẠC',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    "ActivityResultContracts.OpenMultipleDocuments()",
)
for token in required_component_tokens:
    assert token in component, f"missing compact AI audio UI token: {token}"

for removed in (
    'text = "ÂM THANH AI"',
    'text = "Tình trạng: Nhạc cảnh',
    'Text("NGUYÊN TẮC"',
    "AI giữ hoặc đổi nhạc theo cảnh/UNIT",
    "Ambience là nền môi trường kéo dài",
    "SFX là âm one-shot",
    "Mỗi trình quản lý cho phép chọn nhiều tệp",
    "description: String",
):
    assert removed not in component, f"obsolete AI audio description still visible: {removed}"

assert "bringIntoViewRequester" not in component, "embedded component must not auto-scroll another settings page"
assert "BuildConfig.DIAGNOSTIC_BUILD_ID" not in component, "embedded component must not expose debug build markers"
assert '<string name="app_name">Nghe Truyện</string>' in debug_strings, "debug APK must keep the product name Nghe Truyện"
assert "AI Sound Director" not in debug_strings, "feature labels must not be appended to the app name"

# Instrumentation keeps guarding Personal > Music so the Reader AI block cannot be duplicated there.
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
