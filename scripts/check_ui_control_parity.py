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
        "label = \"Cao độ\"",
        "label = \"Âm lượng\"",
        "label = \"Tốc độ Sonic mặc định\"",
        "label = \"Mức chuẩn hóa nhạc\"",
        "label = \"Crossfade\"",
    ],
    "ReaderScreen.kt": [
        'ReaderIntSlider("Cỡ chữ"',
        'ReaderIntSlider("Khoảng cách dòng"',
        'ReaderIntSlider("Crossfade"',
        'ReaderFloatSlider("Mức chuẩn hóa"',
        'TtsSlider("Tốc độ đọc"',
        'TtsSlider("Cao độ"',
        'TtsSlider("Âm lượng"',
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
        'CompactVoiceValueRow("Tốc độ đọc"',
        'CompactVoiceValueRow("Cao độ TTS"',
        'label = "Âm lượng"',
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
            raise SystemExit(f"{name}: missing slider/reference token: {token}")

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

print("UI_CONTROL_PARITY=PASS")
