#!/usr/bin/env python3
from pathlib import Path

reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
story = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/StoryReferenceAdvancedDialogs.kt").read_text()

required_reader = [
    "if (!ttsLoading && state.ttsEngines.isEmpty())",
    "Kho nhạc: ${musicLibraryDraft.size} bài",
    "Ước tính khi gửi danh mục AI: khoảng $estimatedCatalogTokens token",
    "Tên bài gửi cho AI",
    "Mô tả tham khảo cho AI, không bắt buộc AI làm theo",
    "Tối đa 300 ký tự. Chỉ ghi thông tin thực sự giúp AI phân biệt và chọn bài.",
]
for marker in required_reader:
    if marker not in reader:
        raise SystemExit("XPK_FINAL_UI Reader marker missing: " + marker)

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
