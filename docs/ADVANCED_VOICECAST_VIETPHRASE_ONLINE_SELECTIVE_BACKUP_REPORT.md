# Báo cáo triển khai v2.3.0

## Phạm vi

Bản `2.3.0-voicecast-vietphrase-online-selective-backup` triển khai ba hạng mục tiếp theo trên nền v2.2.0: tùy chỉnh AI phân vai nâng cao, tự tìm/cập nhật VietPhrase qua mạng và sao lưu/khôi phục theo thành phần.

## 1. AI phân vai nâng cao

### Dữ liệu theo từng truyện

`StoryAiProfileEntity` được mở rộng với:

- ghi chú nhân vật và bối cảnh;
- prompt phân vai tùy chỉnh;
- chế độ chỉ phân vai đoạn hội thoại;
- chế độ giữ ổn định người kể chuyện;
- prompt biểu cảm tùy chỉnh;
- bật/tắt điều chỉnh biểu cảm;
- giới hạn phần trăm tốc độ, cao độ và âm lượng.

Room nâng từ schema 17 lên 18 bằng migration `17 → 18`. Các cột mới có mặc định an toàn nên hồ sơ cũ tiếp tục hoạt động.

### Giao thức và runtime

Kết quả phân vai có thể mang ba điều chỉnh `speedAdjustPct`, `pitchAdjustPct`, `volumeAdjustPct`. Kết quả AI được clamp theo giới hạn của hồ sơ truyện trước khi lưu. Playback service và audiobook worker cùng dùng các giá trị này, tránh tình trạng nghe trực tiếp một kiểu nhưng tệp xuất ra một kiểu khác.

Prompt tùy chỉnh hỗ trợ các token `{{CHAPTER_TEXT}}`, `{{STORY_NOTE}}`, `{{EXISTING_ROLES}}` và `{{EXPRESSION_RULES}}`. Prompt phân vai không chứa `{{CHAPTER_TEXT}}` bị từ chối để tránh gửi yêu cầu vô nghĩa.

## 2. VietPhrase trực tuyến

`VietPhraseOnlineUpdater` khám phá tài nguyên từ hai root HTTPS tin cậy, quét HTML/JavaScript/JSON và thử các tên tệp phổ biến. Với VietPhrase chia phần, updater ghép cặp phần 1/2 ưu tiên cùng thư mục; nếu một URL hỏng, nó thử tập ứng viên kế tiếp thay vì dừng ngay.

### Lớp bảo vệ

- Chỉ HTTPS và host allow-list.
- Tắt redirect tự động; mỗi redirect được kiểm tra lại.
- Giới hạn tối đa 160 MiB khi tải và 256 MiB sau giải nén.
- Từ chối HTML giả dạng tệp từ điển.
- Hỗ trợ TXT, DIC/DAT cũ, GZIP và ZIP.
- Kiểm tra ngưỡng số mục tối thiểu cho từng lớp từ điển.
- Chỉ commit sau khi toàn bộ lớp bắt buộc hợp lệ.
- Tạo snapshot trước cập nhật để có thể rollback.

Trạng thái nguồn lưu ETag, Last-Modified hoặc SHA-256 để hỗ trợ kiểm tra thay đổi mà không trộn lẫn dữ liệu cũ với bản tải lỗi.

## 3. Backup và restore chọn lọc

Người dùng có thể chọn sáu nhóm:

1. Cài đặt ứng dụng.
2. Thư viện và chương.
3. Tiến độ, bookmark, ghi chú và phát âm.
4. AI, giọng đọc, phân vai và kết quả chuyển đổi.
5. VietPhrase, trạng thái từ điển, snapshot và đề xuất.
6. Nhạc cảnh.

Backup format 14 ghi danh sách thành phần vào `manifest.json`. Khi khôi phục, ứng dụng lấy giao giữa thành phần người dùng chọn và thành phần thực sự có trong gói; dữ liệu không chọn không bị xóa hay ghi đè. Backup cũ không có trường `components` được hiểu là chứa toàn bộ nhóm để giữ tương thích. API key và credential vẫn bị loại khỏi backup.

## Kết quả kiểm tra

Đã chạy thành công các gate tĩnh/ngoại tuyến cho:

- migration schema 17 → 18;
- parser và lưu điều chỉnh biểu cảm;
- compile stub updater VietPhrase;
- UI hồ sơ AI, màn hình Cá nhân và wiring ViewModel;
- playback và audiobook expression adjustment;
- backup format 14 và lựa chọn thành phần;
- các gate hồi quy Kotlin lõi, P4 network/transfer và Android database wiring.

Build Android đầy đủ, Room KAPT, lint, APK/AAB và kiểm thử thiết bị thật chưa được chạy vì môi trường cô lập không có Android SDK Platform 36 và không thể tải Gradle dependencies.
