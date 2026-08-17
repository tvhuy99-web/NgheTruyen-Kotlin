#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
personal = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text(encoding="utf-8")
reader = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text(encoding="utf-8")
component = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text(encoding="utf-8")
manager = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt").read_text(encoding="utf-8")
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
assert "musicTrackCount = musicTracks.size" in music_dialog
assert "onManageMusic = {" in music_dialog
assert 'Text("Chế độ phát"' in music_dialog
assert 'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"' in music_dialog
assert 'ReaderMenuButton("QUẢN LÝ DANH SÁCH NHẠC")' not in music_dialog

for removed in (
    "CÂN BẰNG ÂM THANH",
    "Mỗi bài nhạc được đo một lần",
    "Tên và mô tả của các bài đang bật được gửi cho AI",
):
    assert removed not in music_dialog, f"obsolete background-music explanation still visible: {removed}"

assert "music = appSettings.autoSceneMusicEnabled," in coordinator
assert "backgroundMusicEnabled && appSettings.autoSceneMusicEnabled" not in coordinator

required_switch_tokens = (
    'title = "Nhạc cảnh AI"',
    'title = "Âm thanh môi trường AI"',
    'title = "Hiệu ứng âm thanh AI"',
    'title = "Attack"',
    'title = "Release"',
    'label = "CHUẨN HÓA TOÀN BỘ ÂM THANH"',
    'title = { Text("CHUẨN HÓA TOÀN BỘ ÂM THANH") }',
    'title = "Nhạc nền ($musicCount tệp)"',
    'title = "Âm thanh môi trường ($ambienceCount tệp)"',
    'title = "Hiệu ứng âm thanh ($sfxCount tệp)"',
    'label = "QUẢN LÝ NHẠC ($musicTrackCount)"',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    "UnifiedAudioAssetManagerDialog(",
    "normalizationTargetLufs = target",
)
for token in required_switch_tokens:
    assert token in component, f"missing audio switch/manager routing token: {token}"

for obsolete in (
    'title = "Mức chuẩn hóa"',
    'label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC"',
):
    assert obsolete not in component, f"obsolete single-target normalization UI remains: {obsolete}"

music_manager = component.find('label = "QUẢN LÝ NHẠC ($musicTrackCount)"')
ambience_manager = component.find('label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG')
sfx_manager = component.find('label = "QUẢN LÝ HIỆU ỨNG ÂM THANH')
assert 0 <= music_manager < ambience_manager < sfx_manager, "three audio managers must stay together in Music/Ambience/SFX order"

required_manager_tokens = (
    "ActivityResultContracts.OpenMultipleDocuments()",
    'Text("THÊM TỆP")',
    'Text("DÁN MÔ TẢ")',
    'Text("SAO CHÉP TÊN")',
    'Text("SAO CHÉP MÔ TẢ")',
    'draft.joinToString("\\n") { it.title }',
    '"${track.title} || ${assetDescription(kind, track.tagsCsv)}"',
    'Text("XÓA TẤT CẢ")',
    'Text("LƯU DANH SÁCH")',
    'Text("HỦY")',
    'Text("DỪNG NGHE THỬ")',
    'UnifiedAssetActionButton("NGHE THỬ")',
    'UnifiedAssetActionButton("CHUẨN HÓA")',
    'UnifiedAssetActionButton("SỬA TÊN / MÔ TẢ")',
    'UnifiedAssetActionButton("SAO CHÉP TÊN")',
    'UnifiedAssetActionButton("SAO CHÉP MÔ TẢ")',
    '"TẮT TỆP NÀY"',
    '"BẬT TỆP NÀY"',
    'UnifiedAssetActionButton("DI CHUYỂN LÊN")',
    'UnifiedAssetActionButton("DI CHUYỂN XUỐNG")',
    'UnifiedAssetActionButton("XÓA KHỎI DANH SÁCH")',
    'title = { Text("DÁN MÔ TẢ HÀNG LOẠT") }',
    'label = { Text("Tên") }',
    'label = { Text("Mô tả") }',
    "tagsWithDescription(kind, description)",
    'Text("LƯU")',
    'Text("XÓA")',
    'Text("ĐÓNG")',
)
for token in required_manager_tokens:
    assert token in manager, f"missing unified audio asset manager token: {token}"

list_text_start = manager.find('Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()))')
list_footer_start = manager.find('confirmButton = {', list_text_start)
assert 0 <= list_text_start < list_footer_start, "unified audio list/body-footer structure missing"
scrolling_list = manager[list_text_start:list_footer_start]
for action in (
    'Text("THÊM TỆP")',
    'Text("DÁN MÔ TẢ")',
    'Text("SAO CHÉP TÊN")',
    'Text("SAO CHÉP MÔ TẢ")',
    'Text("XÓA TẤT CẢ")',
    'Text("LƯU DANH SÁCH")',
):
    assert action not in scrolling_list, f"audio manager action must stay outside scrollable list: {action}"

for removed in (
    'text = "ÂM THANH AI"',
    'text = "Tình trạng: Nhạc cảnh',
    'Text("NGUYÊN TẮC"',
    "AI giữ hoặc đổi nhạc theo cảnh/UNIT",
    "Ambience là nền môi trường kéo dài",
    "SFX là âm one-shot",
    "Mỗi trình quản lý cho phép chọn nhiều tệp",
    "Mô tả cho AI",
):
    assert removed not in component + manager, f"obsolete explanatory audio prose still visible: {removed}"

music_library = section(reader, "    if (showMusicLibrary) {", "    selectedMusicTrackId?.let")
for token in (
    'Text("THÊM BÀI")',
    'Text("MÔ TẢ HÀNG LOẠT")',
    'Text("XÓA TẤT CẢ")',
    'Text("LƯU DANH SÁCH")',
    'Text("HỦY")',
):
    assert token in music_library, f"music-list footer action missing: {token}"

music_track_actions = section(reader, "    selectedMusicTrackId?.let", "    editingTrack?.let")
for token in (
    'Text("Mô tả: ${description.ifBlank { "—" }}")',
    'ReaderMenuButton("NGHE THỬ")',
    'ReaderMenuButton("CHUẨN HÓA")',
    'ReaderMenuButton("SỬA TÊN / MÔ TẢ")',
    '"TẮT BÀI NÀY"',
    '"BẬT BÀI NÀY"',
    'ReaderMenuButton("DI CHUYỂN LÊN")',
    'ReaderMenuButton("DI CHUYỂN XUỐNG")',
    'ReaderMenuButton("XÓA KHỎI DANH SÁCH")',
):
    assert token in music_track_actions, f"music track action missing: {token}"

music_editor = section(reader, "    editingTrack?.let", "    if (showMusicBulkDialog) {")
for token in (
    'title = { Text("CHỈNH SỬA BÀI NHẠC") }',
    'label = { Text("Tên") }',
    'label = { Text("Mô tả") }',
    "musicTagsWithDescription(description)",
):
    assert token in music_editor, f"music metadata editor missing: {token}"

assert "bringIntoViewRequester" not in component
assert "BuildConfig.DIAGNOSTIC_BUILD_ID" not in component
assert '<string name="app_name">Nghe Truyện</string>' in debug_strings
assert "AI Sound Director" not in debug_strings

for token in (
    'onNodeWithText("CÁ NHÂN"',
    'onNodeWithContentDescription("Cài đặt"',
    'onNodeWithText("CÀI ĐẶT ỨNG DỤNG"',
    'assertContentDescriptionDoesNotExist("NHẠC NỀN & NHẠC CẢNH")',
    'assertTextDoesNotExist("Nhạc nền cục bộ")',
    'assertTextDoesNotExist("ÂM THANH AI")',
    'assertTextDoesNotExist("Nhạc cảnh AI")',
    'assertTextDoesNotExist("Âm thanh môi trường AI")',
    'assertTextDoesNotExist("Hiệu ứng âm thanh AI")',
    'private fun waitForComposeRoot()',
    'private fun hasText(text: String): Boolean',
    'private fun hasContentDescription(description: String): Boolean',
    'check(!hasText(text))',
    'check(!hasContentDescription(description))',
):
    assert token in ui_test, f"music-page regression UI test missing token: {token}"

print("AUDIO_DIRECTION_UI_WIRING=PASS")
