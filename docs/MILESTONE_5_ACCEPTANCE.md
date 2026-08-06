> **Lưu ý về hệ đánh số:** tài liệu này là Mốc 5 lịch sử của release audiobook 1.9.0. Roadmap hiện tại về TTS/tai nghe/phát nền được nghiệm thu trong `MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md` và `ROADMAP_MILESTONE_5_PLAYBACK_*`.

# Biên bản nghiệm thu Cột mốc 5

Phiên bản: `1.9.0-milestone-5-complete`

## Kết luận

Cột mốc 5 được xem là **hoàn thành ở cấp mã nguồn**. Ảnh bìa không nằm trong phạm vi theo yêu cầu của chủ dự án.

| Tiêu chí | Trạng thái | Bằng chứng |
|---|---|---|
| WAV/M4A/MP3 | Đạt ở mã nguồn | Worker, encoder và static/unit gates |
| Khoảng chương | Đạt | `AudioExportRequest` và hộp thoại xuất |
| Mỗi chương một tệp | Đạt | SAF tree output, tên tệp ổn định |
| Resume sau gián đoạn | Đạt | Checkpoint theo đoạn và fingerprint |
| Nhạc cảnh trong file xuất | Đạt ở mã nguồn | MediaCodec decoder, resampler và mixer |
| MP3 chapter marker | Đạt | ID3 `CHAP/CTOC` và unit test |
| Metadata chữ | Đạt | ID3 title/artist/album/comment |
| Ảnh bìa | Không áp dụng | Chủ dự án yêu cầu bỏ qua |
| Backup mở rộng | Đạt | Format 9 và static transfer gate |
| Benchmark cục bộ | Đạt | `PerformanceDiagnostics` |
| Release signing | Đạt ở cấu hình | Biến môi trường và `verifyReleaseSigning` |
| APK/AAB/device matrix | Chờ môi trường Android | Không có SDK 36/thiết bị trong môi trường hiện tại |

## Điều kiện phát hành công khai

Không coi bản mã nguồn này là bản production đã chứng nhận cho đến khi toàn bộ mục trong `docs/RELEASE_CHECKLIST.md` được đánh dấu đạt trên CI và thiết bị thật.
