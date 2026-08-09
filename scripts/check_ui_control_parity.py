#!/usr/bin/env python3
from pathlib import Path

ROOT = Path("app/src/main/java/vn/nghetruyen/app/ui")
personal = (ROOT / "screens/PersonalScreen.kt").read_text(encoding="utf-8")
reader = (ROOT / "screens/ReaderScreen.kt").read_text(encoding="utf-8")
story = (ROOT / "screens/StoryDetailScreen.kt").read_text(encoding="utf-8")
role = (ROOT / "components/GlobalVoiceRoleEditorDialog.kt").read_text(encoding="utf-8")

required = {
    "PersonalScreen.kt": [
        "ReferenceFloatSettingsSlider(\n                label = \"Tốc độ đọc\"",
        "steps = 274",
        "steps = 149",
        "label = \"Âm lượng\"",
        "label = \"Tốc độ Sonic mặc định\"",
        "label = \"Mức chuẩn hóa nhạc\"",
        "label = \"Crossfade\"",
        "selectedEngineLabel",
        "selectedVoiceLabel",
        "sceneModeExpanded",
        "ttsCacheExpanded",
        'Text("Bộ đọc TTS"',
        'Text("Giọng đọc"',
        "Bật mặc định cho truyện dùng cấu hình chung",
        "Bộ hồ sơ này là tiêu chuẩn dùng chung.",
    ],
    "ReaderScreen.kt": [
        'ReaderIntSlider("Cỡ chữ"',
        'ReaderIntSlider("Khoảng cách dòng"',
        'ReaderFloatSlider("Mức chuẩn hóa"',
        'ReaderFloatSlider("Giảm nhạc khi giọng đọc phát"',
        'ReaderIntSlider("Attack"',
        'ReaderIntSlider("Release"',
        'TtsSlider("Tốc độ đọc"',
        'TtsSlider("Cao độ"',
        'TtsSlider("Âm lượng"',
        "processingExpanded",
        "sonicQualityExpanded",
        "musicModeExpanded",
        "Android, tối đa 100%",
        "Sonic, tối đa 200%",
        "Chế độ phát khi không dùng nhạc theo cảnh",
    ],
    "StoryDetailScreen.kt": [
        'title = { Text("AI RIÊNG CHO TRUYỆN") }',
        "Tự động dịch khi mở chương",
        "Dùng lời nhắc riêng cho truyện này",
        "Lời nhắc riêng khi dịch",
        "Lời nhắc riêng khi cải thiện VietPhrase",
        "Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}",
        "Tự động phân vai rồi đọc khi mở chương ở chế độ TTS",
        "AI tự điều chỉnh tốc độ, cao độ và âm lượng",
        "Truyện sử dụng một bộ hồ sơ độc lập",
        "Thứ tự luôn là: tải chương → dịch tự động",
        "steps = 99",
        "XEM / SỬA HƯỚNG DẪN THÔNG SỐ",
        "THIẾT LẬP BỘ GIỌNG RIÊNG",
        "Ghi chú chung bổ sung cho AI",
        "Tên, mô tả và cách tổ chức nhân vật do người dùng quyết định.",
        "THÊM VAI HOẶC NHÂN VẬT",
        "SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG",
    ],
    "GlobalVoiceRoleEditorDialog.kt": [
        'val dialogTitle = title ?: "HỒ SƠ GIỌNG TTS"',
        "Tên vai hoặc tên nhân vật",
        "Mô tả để AI nhận biết",
        "Bật hồ sơ này",
        "Người kể chuyện luôn được bật",
        "Bộ đọc TTS",
        "processingExpanded",
        "sonicQualityExpanded",
        "Android, tối đa 100%",
        "Sonic, tối đa 200%",
        'CompactVoiceValueRow("Tốc độ đọc"',
        'CompactVoiceValueRow("Cao độ"',
        'label = "Âm lượng"',
        "steps = (intervals - 1).coerceAtLeast(0)",
        'Text("LƯU HỒ SƠ")',
        "enabled = draft.roleName.isNotBlank() && draft.description.isNotBlank()",
        'Text("XÓA HỒ SƠ")',
    ],
}
texts = {
    "PersonalScreen.kt": personal,
    "ReaderScreen.kt": reader,
    "StoryDetailScreen.kt": story,
    "GlobalVoiceRoleEditorDialog.kt": role,
}
for name, tokens in required.items():
    for token in tokens:
        if token not in texts[name]:
            raise SystemExit(f"{name}: missing reference control token: {token}")

for token in [
    'Text("CHẬM")', 'Text("NHANH")', 'Text("TRẦM")', 'Text("CAO")',
    'Text("TỐC ĐỘ -")', 'Text("TỐC ĐỘ +")', 'Text("CAO ĐỘ -")', 'Text("CAO ĐỘ +")',
    'Text("GIỌNG NHỎ")', 'Text("GIỌNG LỚN")', 'Text("NHỎ HƠN")', 'Text("LỚN HƠN")',
    'Text("-400 ms")', 'Text("+400 ms")', 'Text("CACHE -")', 'Text("CACHE +")',
]:
    if token in personal:
        raise SystemExit(f"PersonalScreen.kt: forbidden numeric button remains: {token}")
if "ValueStepper(" in reader:
    raise SystemExit("ReaderScreen.kt: ValueStepper remains")

# The XPK reader music dialog contains target LUFS/duck/attack/release. Kotlin-only
# extensions such as crossfade/avoid-repeat may remain in Personal advanced settings,
# but must not be required inside the reference Reader workflow.
for token in [
    'TextButton({ ttsDraft = ttsDraft.copy(processingMethod',
    'TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate',
]:
    if token in reader:
        raise SystemExit(f"ReaderScreen.kt: paired selector button remains: {token}")
for token in ['+ "HỆ THỐNG"', '+ "SONIC"', '+ "NHANH"', '+ "CHÍNH XÁC"']:
    if token in role:
        raise SystemExit(f"GlobalVoiceRoleEditorDialog.kt: paired selector button remains: {token}")

for token, message in [
    ('label = { Text("Bí danh") }', "extra Bí danh field remains"),
    ('CompactVoiceValueRow("Tốc độ Sonic"', "extra Tốc độ Sonic slider remains"),
    ('CompactVoiceValueRow("Cao độ Sonic"', "extra Cao độ Sonic slider remains"),
]:
    if token in role:
        raise SystemExit(f"GlobalVoiceRoleEditorDialog.kt: {message}")

print("UI_CONTROL_PARITY=PASS")
