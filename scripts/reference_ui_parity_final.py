#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
reader = ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
text = reader.read_text(encoding="utf-8")
old = '''                if (state.diagnosticsMode != "off") {
                    ReaderButton(
                        "XEM NHẬT KÝ",
                        { showDiagnosticLogDialog = true },
                        Modifier.weight(1f),
                        normalColor = ReferenceGray,
                        accessibilityLabel = "Xem nhật ký chẩn đoán",
                    )
                }
'''
new = '''                ReaderButton(
                    "XEM NHẬT KÝ",
                    { showDiagnosticLogDialog = true },
                    Modifier.weight(1f),
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Xem nhật ký chẩn đoán",
                )
'''
if old not in text:
    raise SystemExit("missing conditional diagnostic action")
text = text.replace(old, new, 1)
reader.write_text(text, encoding="utf-8")

# Structural assertions for the major reference-placement invariants.
personal = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text(encoding="utf-8")
story = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt").read_text(encoding="utf-8")
browser = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt").read_text(encoding="utf-8")
login = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt").read_text(encoding="utf-8")

def assert_order(haystack, labels, name):
    pos = -1
    for label in labels:
        nxt = haystack.find(label, pos + 1)
        if nxt < 0:
            raise SystemExit(f"{name}: missing {label}")
        if nxt <= pos:
            raise SystemExit(f"{name}: wrong order at {label}")
        pos = nxt

assert_order(reader, [
    '"XEM NHẬT KÝ"', '"DỊCH AI"', '"PHÂN VAI AI"'
], "reader action row")
assert_order(reader, [
    '"TRỞ LẠI DANH SÁCH CHƯƠNG"', '"LƯU VỊ TRÍ ĐỌC"', '"TÌM TRONG CHƯƠNG"',
    '"HIỂN THỊ VĂN BẢN"', '"HẸN GIỜ NGỦ - ${state.sleepTimerStatus}"', '"NHẠC NỀN"',
    '"XUẤT ÂM THANH (CẦN CHẾ ĐỘ TTS)"', '"CHẾ ĐỘ ĐỌC:',
    '"THIẾT LẬP AI CHO TRUYỆN NÀY"', '"PHÂN VAI TTS CHO TRUYỆN NÀY"',
    '"KHÔI PHỤC CHƯƠNG GỐC TRƯỚC AI"', '"TẠO NHẬT KÝ VIETPHRASE"',
    '"CÀI ĐẶT TTS"', '"SAO CHÉP CHƯƠNG"', '"THÔNG TIN CHƯƠNG"'
], "reader options")
assert_order(personal, [
    '"TỪ ĐIỂN PHÁT ÂM TTS"', '"VIETPHRASE / CHUYỂN NGỮ"', '"THIẾT LẬP AI"',
    '"PHÂN VAI TTS BẰNG AI"', '"Mức nhật ký chẩn đoán"', '"CÀI ĐẶT KHÁC"',
    '"SAO LƯU DỮ LIỆU"', '"KHÔI PHỤC DỮ LIỆU"', '"NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC"',
    '"XÓA TRUYỆN ĐÃ TẢI"', '"ĐẶT LẠI ỨNG DỤNG NHƯ MỚI"'
], "settings")
assert_order(story, ['"GIỚI THIỆU"', '"CHƯƠNG"', '"BÌNH LUẬN"', '"NGUỒN"'], "story tabs")
assert_order(story, ['"TẢI CHƯƠNG ĐẦU"', '"CHỌN NHIỀU CHƯƠNG"', '"TẢI TOÀN BỘ TRUYỆN"'], "download scope")
for source, name in [(browser, "diagnostic browser"), (login, "login browser")]:
    assert_order(source, ['"QUAY LẠI"', '"TIẾN TỚI"', '"TÙY CHỌN"', '"ĐI TỚI"'], name)

print("final reference placement assertions passed")
