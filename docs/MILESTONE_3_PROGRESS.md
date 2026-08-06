# Cột mốc 3: Đọc truyện, tìm kiếm và ngoại tuyến

## Trạng thái

**Hoàn thành ở cấp mã nguồn và kiểm định ngoại tuyến.**

Phiên bản: `1.6.0-milestone-3-complete`.

## 1. Trình đọc

- Hai chế độ cuộn và phân trang theo đoạn.
- Tự lưu đoạn đầu tiên đang nhìn thấy sau khi thao tác cuộn dừng.
- Cùng một paragraph index được dùng bởi lịch sử, bookmark, ghi chú và TTS.
- Lưu theme, cỡ chữ, giãn dòng, lề ngang, khoảng cách đoạn và chế độ bố cục.
- Ghi chú theo từng đoạn: tạo, sửa, xóa, mở lại từ Tủ truyện.
- Điều hướng đoạn bằng phím âm lượng là tùy chọn opt-in.
- Tìm kiếm trong chương, sao chép đoạn và toàn chương được giữ nguyên.

## 2. Tìm kiếm và mục lục

- Chuẩn hóa tiếng Việt không dấu và chữ `đ`.
- Chấm điểm tiêu đề, tác giả, mô tả và URL.
- Token coverage và Damerau-Levenshtein có giới hạn cho lỗi gõ gần đúng.
- Gộp kết quả trùng, ưu tiên nguồn khỏe hơn.
- Sắp xếp theo liên quan, tên, tác giả hoặc nguồn.
- `ChapterCatalogIndex` tạo chỉ mục bất biến, ánh xạ trực tiếp số chương và normalize tiêu đề một lần.
- Smoke test mục lục 10.000 chương xác nhận tìm số và tìm tiêu đề có dấu/không dấu.

## 3. Ngoại tuyến

- Room schema 9 lưu request `SINGLE`, `RANGE`, `UNREAD`, `ALL`.
- Lưu khoảng chương, Wi-Fi-only, charging-only, chương hiện tại, retry count và request time.
- Pause, resume, retry, cancel qua WorkManager unique work.
- Kiểm tra dung lượng trống trước khi tạo job và ngay trước mỗi batch.
- Lỗi được ghi theo từng chương; retry riêng chương lỗi tạo job `SINGLE` mới.
- Continuation giữ nguyên job ID và điều kiện ban đầu.
- Migration `7 → 8 → 9` giữ tiến độ tải và thêm bảng mới không destructive.

## 4. Nhập sách

- TXT, EPUB và DOCX.
- MOBI, PRC, AZW và AZW3 không mã hóa.
- PalmDOC raw và LZ77.
- MOBI 8/KF8-only text container.
- HUFF/CDIC bounded với giới hạn dictionary, recursion và output.
- UTF-8, UTF-16 và Windows-1252.
- DRM/mã hóa bị từ chối rõ ràng.

## 5. Sao lưu

Backup format 7 lưu:

- Ghi chú theo đoạn.
- Thiết lập bố cục, lề, khoảng cách đoạn và phím âm lượng.
- Dữ liệu cũ vẫn đọc được theo hợp đồng format 1–7.

## Điều kiện nghiệm thu đã đạt

- Gate Kotlin lõi, UI stub, download worker và Kindle importer đều đạt.
- Migration SQL `7 → 8 → 9` đạt với dữ liệu mẫu.
- Hồi quy Cột mốc 1 và Cột mốc 2 đạt, gồm 8 SourcePack ký số và 24 fixture.
- Release validation và workflow YAML đạt.

## Kiểm thử còn phụ thuộc môi trường Android

Đây không phải chức năng còn để trống, nhưng vẫn phải xác nhận trước phát hành:

- Gradle/AGP/Room/Compose build thật với Android SDK Platform 36.
- APK/AAB, lint và instrumentation.
- Mất mạng, reboot, process death và thiếu dung lượng trên thiết bị.
- Phím âm lượng với nhiều ROM/tai nghe.
- Tệp Kindle thực tế đa dạng, đặc biệt KF8/HUFF-CDIC lớn.
