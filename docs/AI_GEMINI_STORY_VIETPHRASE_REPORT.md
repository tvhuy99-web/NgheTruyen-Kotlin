# Báo cáo triển khai Gemini, AI theo truyện và cải thiện VietPhrase

## Phạm vi hoàn thành

### 1. Gemini Native

- Thêm provider `GEMINI` bên cạnh `OPENAI_COMPATIBLE`.
- Gọi trực tiếp Gemini REST `models/{model}:generateContent` với header `x-goog-api-key`.
- Dùng request `contents/parts/text`, `generationConfig`, `temperature`, `maxOutputTokens` và JSON response MIME khi cần dữ liệu có cấu trúc.
- Lưu API key Gemini và OpenAI-compatible ở hai mục Android Keystore/EncryptedSharedPreferences riêng biệt.
- Có màn hình tải danh sách model Gemini hỗ trợ `generateContent` và chọn model.
- Chặn redirect, giới hạn kích thước phản hồi, tên model và thời gian mạng.

### 2. Cấu hình AI riêng cho từng truyện

Room schema 17 thêm bảng `story_ai_profiles`. Mỗi truyện có thể lưu:

- Chế độ kế thừa, dịch AI hoặc cải thiện VietPhrase.
- Có hoặc không ghi đè provider toàn cục.
- Provider, endpoint, model và temperature riêng.
- Prompt dịch riêng với token `{{CHAPTER_TEXT}}`.
- Prompt cải thiện riêng với `{{SOURCE_TEXT}}` và `{{VIETPHRASE_TEXT}}`.
- Tự chạy chế độ đã chọn khi mở chương.

Hồ sơ được sao lưu trong backup format 13. API key và nội dung request/response AI không được đưa vào backup.

### 3. Luồng AI cải thiện VietPhrase

Luồng xử lý:

1. Lấy nội dung gốc của chương hiện tại.
2. Áp dụng các lớp VietPhrase cục bộ đang bật để tạo bản đối chiếu.
3. Gửi cả bản gốc và bản VietPhrase tới provider AI hiệu lực của truyện.
4. Yêu cầu JSON có tối đa 30 cặp thay thế.
5. Kiểm tra kiểu, độ dài, ký tự xuống dòng, chuỗi gốc có thực sự xuất hiện trong bản VietPhrase và loại bỏ trùng lặp.
6. Ghi đề xuất vào hàng chờ duyệt hiện có.
7. Chỉ sau khi người dùng chấp nhận, quy tắc mới được đưa vào lớp `AI_REPLACE`.

Luồng này cố ý không tự sửa từ điển, nhằm tránh một phản hồi AI sai làm ô nhiễm dữ liệu VietPhrase lâu dài.

## Nâng cấp dữ liệu

- Room: `16 → 17` bằng migration tạo bảng `story_ai_profiles` với mặc định an toàn.
- Backup: `12 → 13`, bổ sung provider toàn cục và hồ sơ AI theo truyện.
- Cấu hình model toàn cục được tách theo provider để đổi Gemini/OpenAI không mang nhầm model.
- API key cũ được migration sang mục OpenAI-compatible; khóa Gemini bắt đầu ở mục riêng.

## Kiểm tra đã thực hiện

- Gate riêng `scripts/check_ai_gemini_story_vietphrase.py`.
- Kotlin core và AI settings static compile gate.
- Compose UI static compile cho màn hình Cá nhân, Chi tiết truyện và Trình đọc.
- P4 feature regression gate.
- P4 network static compile gate.
- P4 Android credential/security static gate.
- Milestone 4 complete regression gate.
- Kiểm tra whitespace bằng `git diff --check`.

Build Gradle Android đầy đủ chưa thể chạy trong môi trường cô lập vì wrapper/dependency phải tải từ mạng và Android SDK Platform 36 không có sẵn. Vì vậy báo cáo này xác nhận triển khai và kiểm tra tĩnh/hồi quy mã nguồn, không tuyên bố APK đã được chứng nhận trên thiết bị.
