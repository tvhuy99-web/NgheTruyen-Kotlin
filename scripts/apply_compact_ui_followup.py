from pathlib import Path
import re


def update(path, fn):
    p = Path(path)
    old = p.read_text(encoding='utf-8')
    new = fn(old)
    if new == old:
        raise SystemExit(f'no changes: {path}')
    p.write_text(new, encoding='utf-8')
    print(path)


def personal(text: str) -> str:
    text, count = re.subn(
        r'PersonalSubPage\(("[^"]+"), "QUAY LẠI [^"]+", \{[^\n]*\}\) \{',
        r'PersonalSubPage(\1) {',
        text,
    )
    if count < 10:
        raise SystemExit(f'expected many PersonalSubPage call replacements, got {count}')

    text = text.replace('            backLabel = "QUAY LẠI CÁ NHÂN",\n            onBack = { personalPage = "home" },\n', '')
    text = text.replace('    backLabel: String? = null,\n    onBack: (() -> Unit)? = null,\n', '')
    text = text.replace('    backLabel: String,\n    onBack: () -> Unit,\n', '')

    replacements = {
        'Text("QUAY LẠI")': 'Text("DANH SÁCH")',
        '"Điều khiển tai nghe và tự động hóa"': '"Tai nghe & tự động"',
        '"Nhận thao tác bấm nhiều lần từ tai nghe"': '"Bấm nhiều lần"',
        '"Tạm dừng khi tai nghe hoặc Bluetooth bị ngắt"': '"Dừng khi ngắt tai nghe"',
        '"Khôi phục phiên nghe sau khi tiến trình bị đóng"': '"Khôi phục phiên nghe"',
        '"Tự phân vai AI khi chương chưa có kế hoạch"': '"Tự phân vai AI"',
        '"Tự lập nhạc cảnh AI khi chương chưa có kế hoạch"': '"Tự lập nhạc cảnh"',
        '"Chuẩn bị trước phân vai và nhạc cảnh"': '"Chuẩn bị AI trước"',
        '"Cửa sổ chuẩn bị AI: ${state.narrationPrefetchWindowChapters} chương"': '"Chuẩn bị trước: ${state.narrationPrefetchWindowChapters} chương"',
        '"Xử lý Sonic để đổi tốc độ/cao độ độc lập"': '"Dùng Sonic"',
        '"Bộ nhớ đệm giọng TTS/Sonic có checksum"': '"Cache TTS/Sonic"',
        '"Chuẩn hóa âm lượng giữa giọng và engine"': '"Chuẩn hóa âm lượng"',
        '"Giữ nhạc phù hợp khi chuyển chương"': '"Giữ nhạc qua chương"',
        '"Chế độ playlist nhạc cảnh"': '"Chế độ nhạc cảnh"',
        '"THÔNG MINH, TRÁNH LẶP"': '"TRÁNH LẶP"',
        '"Mức âm lượng mục tiêu: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS ước tính"': '"Nhạc: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS"',
        '"Tránh lặp ${state.sceneMusicAvoidRepeatWindow} track gần nhất"': '"Tránh lặp: ${state.sceneMusicAvoidRepeatWindow} bài"',
        '"Crossfade nhạc cảnh: ${state.sceneMusicCrossfadeMillis} ms"': '"Crossfade: ${state.sceneMusicCrossfadeMillis} ms"',
        '"Bộ máy và giọng đọc"': '"TTS & giọng đọc"',
        '"Tự chuyển và đọc chương kế tiếp"': '"Tự đọc chương sau"',
        '"Khi âm thanh khác phát xen"': '"Âm thanh khác"',
        '"Giọng của bộ máy đang chọn"': '"Giọng TTS"',
        '"THU GỌN DANH SÁCH GIỌNG"': '"THU GỌN GIỌNG"',
        '"QUẢN LÝ QUY TẮC (${orderedRules.size})"': '"QUY TẮC (${orderedRules.size})"',
        '"GỢI Ý AI CHỜ DUYỆT (${pendingSuggestions.size})"': '"GỢI Ý AI (${pendingSuggestions.size})"',
        '"BẢN KHÔI PHỤC (${latestSnapshots.size})"': '"KHÔI PHỤC (${latestSnapshots.size})"',
        '"NHẬP FILE ZIP"': '"NHẬP ZIP"',
        '"XUẤT TỪ ĐIỂN ZIP"': '"XUẤT ZIP"',
        '"KIỂM TRA CẬP NHẬT"': '"CẬP NHẬT"',
        '"TẢI TỰ ĐỘNG TỪ MẠNG"': '"TẢI TỪ MẠNG"',
        '"Dùng Hán Việt khi không tìm thấy cụm"': '"Hán Việt khi thiếu cụm"',
        '"NHẬP / THAY THẾ (TXT hoặc DIC)"': '"NHẬP / THAY THẾ"',
        '"XÓA DỮ LIỆU FILE NÀY"': '"XÓA FILE"',
        '"Không phân biệt hoa thường"': '"Bỏ qua hoa thường"',
        '"Chỉ áp dụng cho một truyện"': '"Chỉ truyện này"',
        '"Tìm nguồn, đích, loại hoặc truyện"': '"Tìm quy tắc"',
        '"Liên kết HTTPS của kho hoặc plugin.zip"': '"Liên kết HTTPS"',
        '"Tên kho, không bắt buộc"': '"Tên kho (tùy chọn)"',
        '"URL HTTPS của repository index"': '"URL kho"',
        '"ĐỐI CHIẾU & THÊM KHÓA"': '"THÊM KHÓA"',
        '"NHẬP TỆP XOAY KHÓA ĐÃ KÝ"': '"NHẬP XOAY KHÓA"',
        '"Xóa toàn bộ nội dung truyện đã tải khỏi thiết bị? Tiến độ đọc, lịch sử và dấu trang vẫn được giữ lại."': '"Xóa toàn bộ truyện đã tải?"',
        '"Thao tác này sẽ xóa toàn bộ dữ liệu và cài đặt của ứng dụng, gồm tiến độ đọc, dấu trang, truyện đã tải, từ điển, cấu hình AI và tiện ích. Bạn có muốn tiếp tục?"': '"Xóa toàn bộ dữ liệu và cài đặt?"',
        '"Dữ liệu sau khi xóa không thể khôi phục nếu bạn chưa sao lưu. Đặt lại ứng dụng ngay?"': '"Đặt lại ứng dụng ngay?"',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    if 'QUAY LẠI' in text:
        raise SystemExit('QUAY LẠI still present in PersonalScreen')
    return text


def reader(text: str) -> str:
    text = text.replace('Text("QUAY LẠI")', 'Text("SỬA")')
    if 'QUAY LẠI' in text:
        raise SystemExit('QUAY LẠI still present in ReaderScreen')
    return text


update('app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt', personal)
update('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt', reader)
