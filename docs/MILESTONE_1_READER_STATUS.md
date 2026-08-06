# Mốc 1, lõi đọc truyện

Ngày bắt đầu lại theo lộ trình parity: 2026-08-05

Trạng thái: **IN PROGRESS**

## Ngoại lệ phụ thuộc

Mốc 0 vẫn chưa được đóng vì môi trường hiện tại không có Android SDK 36/JDK 17 phù hợp để tạo APK/AAB. Theo quyết định của chủ dự án, Mốc 1 được phép triển khai mã nguồn trước, nhưng:

- không được đánh dấu Mốc 1 là hoàn tất;
- mọi tiêu chí cần Gradle, Compose, Room, emulator hoặc thiết bị thật vẫn giữ trạng thái chưa xác minh;
- Mốc 2 chưa được mở cho đến khi Mốc 1 có nghiệm thu riêng.

## Lát cắt đã hoàn thành trong lượt khởi động

### 1. Chỉ số đoạn đọc ổn định

Trước đây `PlaybackQueueStore` chia đoạn dài thành nhiều phần và dùng chính danh sách đã chia làm chỉ số tiến độ. Điều đó có thể làm lệch:

- vị trí đọc;
- bookmark;
- ghi chú;
- phân vai;
- cue nhạc cảnh;
- checkpoint sau process death.

Đã tách hai lớp dữ liệu:

- `paragraphs`: đoạn hiển thị và chỉ số bền vững của người dùng;
- `speechChunks`: mảnh TTS có giới hạn 3.000 ký tự, mỗi mảnh ánh xạ về đoạn gốc.

Việc phát tiếp các mảnh trong cùng một đoạn không còn thay đổi `paragraphIndex`.

### 2. Chuẩn hóa nội dung nhất quán

Thêm `ReaderDocumentNormalizer` để nội dung được chuẩn hóa giống nhau trước khi:

- hiển thị trong trình đọc;
- đưa vào hàng đợi TTS;
- lưu cache;
- chuyển chương tự động;
- khôi phục chương do service mở.

### 3. Điều hướng chương an toàn

Thêm `ReaderChapterNavigation`.

Điều hướng trước/sau giờ dựa vào vị trí thực trong mục lục bằng ID, URL hoặc index, không giả định `chapter.index` luôn bằng offset của danh sách đang có. Fallback URL chỉ được dùng khi mục lục chưa có chương kề.

### 4. Chống tác vụ tải cũ ghi đè màn hình mới

`AppViewModel` có job riêng cho tải truyện và tải chương:

- mở truyện mới hủy yêu cầu cũ;
- mở chương mới hủy yêu cầu chương cũ;
- quay lại hủy tác vụ đang treo;
- quay lại luôn xóa trạng thái loading.

### 5. Chống kết quả khám phá cũ ghi đè trạng thái mới

Tìm kiếm, duyệt thể loại và tải thêm dùng cùng một lane có thể hủy. Khi người dùng đổi nguồn, đổi thể loại hoặc gửi truy vấn mới, yêu cầu cũ bị hủy trước khi yêu cầu mới bắt đầu.

### 6. Cổng kiểm tra thực thi offline

Thêm `scripts/check_milestone1_reader_core.py` và tích hợp vào `validate_release.py`.

Gate biên dịch và chạy mã Kotlin thật để xác nhận:

- đoạn 7.200 ký tự vẫn chỉ có một vị trí đọc;
- TTS chia thành các mảnh không vượt giới hạn;
- phát các mảnh không làm đổi chỉ số đoạn;
- chuyển sang đoạn tiếp theo đặt lại đúng mảnh đầu;
- điều hướng hoạt động với mục lục có index không liên tục.

## Bằng chứng hiện tại

| Cổng | Kết quả |
|---|---|
| `scripts/check_milestone1_reader_core.py` | PASS |
| `scripts/validate_release.py` | PASS |
| `scripts/check_p1_ui_static.py` | PASS |
| `scripts/check_p1_features.py` | PASS |
| Kotlin compile cho reader core | PASS |
| Gradle unit test | Chưa chạy |
| Android Lint | Chưa chạy |
| Compose compiler | Chưa chạy |
| APK/AAB | Chưa tạo |
| Instrumentation test | Chưa chạy |
| Thiết bị thật | Chưa chạy |

## Phạm vi Mốc 1 còn lại

- Kiểm thử UI tự động cho cuộn, phân trang, tìm trong chương và ghi chú.
- Kiểm thử đóng/mở app, process death, reboot và nâng cấp database.
- Kiểm thử điều hướng mục lục 10.000 chương trên thiết bị thật.
- Kiểm thử phím âm lượng trong trình đọc.
- Kiểm thử TalkBack, cỡ chữ lớn và focus order.
- Build Gradle sạch, lint, APK debug, test APK và AAB release.
- Chạy ma trận nguồn thật cho luồng khám phá, tìm kiếm, chi tiết và đọc chương.

## Điều kiện đóng Mốc 1

Mốc 1 chỉ được chuyển sang `ACCEPTED` khi tất cả tiêu chí trong `MILESTONE_1_READER_ACCEPTANCE.md` đạt và nợ Mốc 0 liên quan tới build thực đã được xử lý.
