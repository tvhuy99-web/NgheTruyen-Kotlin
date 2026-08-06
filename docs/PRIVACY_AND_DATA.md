# Quyền riêng tư và dữ liệu

## Dữ liệu lưu cục bộ

Ứng dụng lưu thư viện, lịch sử, bookmark, ghi chú, chương tải, cấu hình TTS, hồ sơ vai, biểu cảm, thông số Sonic, kế hoạch phân vai, kế hoạch nhạc cảnh, lịch sử phát nhạc và tiến độ xuất trong bộ nhớ ứng dụng hoặc vị trí người dùng chọn.

## AI online

AI online mặc định tắt. Nội dung chương chỉ được gửi khi người dùng đã bật AI, chấp thuận truyền dữ liệu và cấu hình endpoint/model. Khi AI bị tắt, hết quota hoặc lỗi, ứng dụng dùng TTS và bộ biểu cảm cục bộ thay vì buộc người dùng gửi dữ liệu.

## Bộ đếm quota AI

Ứng dụng lưu cục bộ theo ngày:

- số request;
- tổng ký tự đầu vào và đầu ra;
- số lần retry;
- mã lỗi cuối đã giới hạn độ dài.

Bảng quota không lưu prompt, nội dung chương, tên nhân vật hoặc response AI. Dữ liệu quota hằng ngày không được đưa vào portable backup.

## Thông tin xác thực

API key được mã hóa bằng Android Keystore. API key, cookie, token đăng nhập, khóa ký, prompt/response AI và checkpoint playback không được đưa vào backup.

## Backup

Backup format 10 có thể chứa nội dung truyện, ghi chú, thiết lập TTS/Sonic/AI, kế hoạch AI, phân vai và metadata nhạc cảnh. Quyền URI của file nhạc không thể chuyển thiết bị, vì vậy track khôi phục mặc định bị tắt cho đến khi người dùng cấp lại quyền.

## Loudness và chẩn đoán

Loudness của track được ước tính cục bộ từ PCM để cân mức nhạc cảnh. Đây không phải phép đo EBU R128 đầy đủ. Báo cáo benchmark gồm RAM/PSS, heap, trạng thái pin và thời gian xử lý mục lục mẫu; ứng dụng không tự động tải báo cáo hoặc số liệu loudness lên máy chủ.
