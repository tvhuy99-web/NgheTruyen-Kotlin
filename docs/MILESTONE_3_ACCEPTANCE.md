# Biên bản nghiệm thu Cột mốc 3

Phiên bản: `1.6.0-milestone-3-complete`  
Ngày: 2026-08-05

## Phạm vi đã đóng

| Nhóm | Tiêu chí | Trạng thái |
|---|---|---|
| Đọc truyện | Cuộn, phân trang, tự lưu vị trí, tìm trong chương | Đạt |
| Cá nhân hóa | Theme, font, giãn dòng, lề, khoảng đoạn | Đạt |
| Ghi chú | Tạo/sửa/xóa/mở theo đoạn | Đạt |
| Điều khiển | Phím âm lượng opt-in | Đạt |
| Tìm truyện | Đa nguồn, bỏ dấu, typo, dedupe, sort | Đạt |
| Mục lục dài | Chỉ mục bất biến và test 10.000 chương | Đạt |
| Ngoại tuyến | SINGLE/RANGE/UNREAD/ALL | Đạt |
| Hàng đợi | Pause/resume/retry/cancel và continuation | Đạt |
| Dung lượng | Preflight trước job và trước batch | Đạt |
| Lỗi chương | Lưu và retry riêng từng chương | Đạt |
| Nhập sách | TXT/EPUB/DOCX/MOBI/PRC/AZW/AZW3 không DRM | Đạt |
| Kindle nâng cao | KF8-only và HUFF/CDIC bounded | Đạt |
| Dữ liệu | Room 9, migration 8→9, backup format 7 | Đạt |
| Hồi quy | Cột mốc 1–2 và P1–P4 | Đạt |

## Ngoài phạm vi có chủ đích

- Không giải mã DRM.
- Không tuyên bố tương thích mọi biến thể Kindle cho tới khi chạy corpus tệp thực tế.
- Không coi static/offline gate là thay thế cho Android Gradle build và kiểm thử thiết bị.

## Điều kiện trước phát hành công khai

- CI phải tạo được debug APK, Android-test APK và release AAB.
- `connectedDebugAndroidTest` phải xanh trên emulator API 33.
- Smoke test thiết bị cho mất mạng, reboot, process death, thiếu dung lượng và phím âm lượng.
