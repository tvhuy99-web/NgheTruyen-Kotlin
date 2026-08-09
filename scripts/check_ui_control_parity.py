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
        'Text("Bộ đọc TTS"',
        'Text("Giọng đọc"',
    ],
    "ReaderScreen.kt": [
        'ReaderIntSlider("Cỡ chữ"',
        'ReaderIntSlider("Khoảng cách dòng"',
        'ReaderIntSlider("Crossfade"',
        'ReaderFloatSlider("Mức chuẩn hóa"',
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
        "Tự động phân vai rồi đọc khi mở chương ở chế độ TTS",
        "AI tự điều chỉnh tốc độ, cao độ và âm lượng",
        "steps = 99",
        "XEM / SỬA HƯỚNG DẪN THÔNG SỐ",
        "THIẾT LẬP BỘ GIỌNG RIÊNG",
    ],
    "GlobalVoiceRoleEditorDialog.kt": [
        "Tên vai hoặc tên nhân vật",
        "Mô tả để AI nhận biết",
        "Bộ đọc TTS",
        "processingExpanded",
        "sonicQualityExpanded",
        "Android, tối đa 100%",
        "Sonic, tối đa 200%",
        'CompactVoiceValueRow("Tốc độ đọc"',
        'CompactVoiceValueRow("Cao độ TTS"',
        'label = "Âm lượng"',
        "steps = (intervals - 1).coerceAtLeast(0)",
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
    'Text("-400 ms")', 'Text("+400 ms")',
]:
    if token in personal:
        raise SystemExit(f"PersonalScreen.kt: forbidden numeric button remains: {token}")
if "ValueStepper(" in reader:
    raise SystemExit("ReaderScreen.kt: ValueStepper remains")

# Reference uses Spinner-like selectors for processing method/Sonic quality. Do not regress to paired buttons.
for token in [
    'TextButton({ ttsDraft = ttsDraft.copy(processingMethod',
    'TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate',
]:
    if token in reader:
        raise SystemExit(f"ReaderScreen.kt: paired selector button remains: {token}")
for token in ['+ "HỆ THỐNG"', '+ "SONIC"', '+ "NHANH"', '+ "CHÍNH XÁC"']:
    if token in role:
        raise SystemExit(f"GlobalVoiceRoleEditorDialog.kt: paired selector button remains: {token}")

print("UI_CONTROL_PARITY=PASS")
