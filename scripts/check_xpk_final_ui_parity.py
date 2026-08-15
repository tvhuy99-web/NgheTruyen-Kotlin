#!/usr/bin/env python3
"""Guard the final XPK-aligned Reader, TTS, music-library, and voice-cast UI details."""

from pathlib import Path

reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
story = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/StoryReferenceAdvancedDialogs.kt").read_text()
component = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()
unified_audio_manager = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt")
if unified_audio_manager.exists():
    component += "\n" + unified_audio_manager.read_text()

required_reader = [
    "if (!ttsLoading && state.ttsEngines.isEmpty())",
    'title = { Text("DANH SÁCH NHẠC NỀN") }',
    'Text("THÊM BÀI")',
    'Text("MÔ TẢ HÀNG LOẠT")',
    'Text("XÓA TẤT CẢ")',
    'Text("LƯU DANH SÁCH")',
    'ReaderMenuButton("NGHE THỬ")',
    'ReaderMenuButton("CHUẨN HÓA")',
    'ReaderMenuButton("SỬA TÊN / MÔ TẢ")',
    'title = { Text("CHỈNH SỬA BÀI NHẠC") }',
    'label = { Text("Tên") }',
    'label = { Text("Mô tả") }',
]
for marker in required_reader:
    if marker not in reader:
        raise SystemExit("XPK_FINAL_UI Reader marker missing: " + marker)

for removed in [
    "Ước tính khi gửi danh mục AI",
    "Đã có mô tả",
    'Text("DÁN MÔ TẢ")',
    'Text("SAO CHÉP MÔ TẢ")',
    "Mô tả tham khảo cho AI, không bắt buộc AI làm theo",
    "Tối đa 300 ký tự. Chỉ ghi thông tin thực sự giúp AI phân biệt và chọn bài.",
    "Tên và mô tả của các bài đang bật được gửi cho AI",
]:
    if removed in reader:
        raise SystemExit("XPK_FINAL_UI obsolete explanatory music prose/control remains: " + removed)

for marker in [
    'title = "Mức chuẩn hóa"',
    'title = "Attack"',
    'title = "Release"',
    'label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC"',
    'label = "QUẢN LÝ NHẠC ($musicTrackCount)"',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    'label = { Text("Tên") }',
    'label = { Text("Mô tả") }',
    'Text("THÊM NHIỀU TỆP")',
    '"NGHE THỬ"',
    '"NGHE LẠI"',
    'Text("DỪNG")',
    'Text("LƯU")',
    'Text("XÓA")',
    'Text("ĐÓNG")',
]:
    if marker not in component:
        raise SystemExit("XPK_FINAL_UI complete audio manager marker missing: " + marker)
if not any(marker in component for marker in [
    'Text("CHUẨN HÓA")',
    'UnifiedAssetActionButton("CHUẨN HÓA")',
]):
    raise SystemExit("XPK_FINAL_UI complete audio manager marker missing: CHUẨN HÓA")

manager_positions = [
    component.find('label = "QUẢN LÝ NHẠC ($musicTrackCount)"'),
    component.find('label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG'),
    component.find('label = "QUẢN LÝ HIỆU ỨNG ÂM THANH'),
]
if not (0 <= manager_positions[0] < manager_positions[1] < manager_positions[2]):
    raise SystemExit("XPK_FINAL_UI three audio managers must stay together in Music/Ambience/SFX order")

for removed in [
    'text = "ÂM THANH AI"',
    'text = "Tình trạng: Nhạc cảnh',
    'Text("NGUYÊN TẮC"',
    "AI giữ hoặc đổi nhạc theo cảnh/UNIT",
    "Ambience là nền môi trường kéo dài",
    "SFX là âm one-shot",
    "Mỗi trình quản lý cho phép chọn nhiều tệp",
    "Mô tả cho AI",
]:
    if removed in component:
        raise SystemExit("XPK_FINAL_UI obsolete explanatory audio prose remains: " + removed)

music_start = reader.find('    if (showMusicLibrary) {')
music_end = reader.find('    selectedMusicTrackId?.let', music_start)
if music_start < 0 or music_end < 0:
    raise SystemExit("XPK_FINAL_UI music library block missing")
music_library = reader[music_start:music_end]
footer_start = music_library.find('confirmButton = { Column(Modifier.fillMaxWidth())')
if footer_start < 0:
    raise SystemExit("XPK_FINAL_UI fixed music footer missing")
scrolling_body = music_library[:footer_start]
for action in ['Text("THÊM BÀI")', 'Text("MÔ TẢ HÀNG LOẠT")', 'Text("XÓA TẤT CẢ")', 'Text("LƯU DANH SÁCH")']:
    if action in scrolling_body:
        raise SystemExit("XPK_FINAL_UI music action is inside scrollable list: " + action)

exact_guidance = "AI xử lý phân vai và ba thông số phần trăm trong cùng một lượt. Không dùng nhãn buồn, vui hay tức giận. Chỉ lời thoại trực tiếp được đổi giọng và thông số; lời kể cùng nội tâm luôn giữ giọng Người kể chuyện ở thông số gốc. Mức AI trả về được áp trực tiếp trong giới hạn bên dưới. Âm lượng chỉ có thể tăng khi mức gốc còn dưới 100%."
if exact_guidance not in story:
    raise SystemExit("XPK_FINAL_UI exact voice guidance missing")

menu_start = reader.find("    if (showReaderOptions) {")
menu_end = reader.find("    if (showReaderModeDialog) {", menu_start)
if menu_start < 0 or menu_end < 0:
    raise SystemExit("XPK_FINAL_UI standard Reader menu missing")
menu = reader[menu_start:menu_end]
for extra in ["MỞ RỘNG", "ĐÁNH DẤU ĐOẠN", "ÁP DỤNG VIETPHRASE", "CẢI THIỆN VIETPHRASE", "LẬP NHẠC CẢNH", "PHÂN VAI + NHẠC", "XEM NHẬT KÝ CHẨN ĐOÁN"]:
    if extra in menu:
        raise SystemExit("XPK_FINAL_UI Kotlin-only Reader menu action remains: " + extra)

print("XPK_FINAL_UI_PARITY=PASS")
