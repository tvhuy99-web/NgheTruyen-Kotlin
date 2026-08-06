# Kiểm toán parity XPK → Kotlin 0.6.0

Ngày đối chiếu: 2026-08-02

## Cách đọc trạng thái

- **Hoàn tất lõi:** có dữ liệu thật, persistence, xử lý lỗi và release gate ngoại tuyến.
- **Cần xác minh thiết bị:** code/fixture đã có nhưng chưa build hoặc live-test Android.
- **Một phần:** luồng cơ bản dùng được, còn thiếu biến thể nâng cao.
- **Contract:** mới khóa interface/model, chưa có engine sản xuất.
- **Chưa triển khai:** chưa có luồng Kotlin tương ứng.

## 1. Giao diện và điều hướng

| Chức năng XPK | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Khám phá/Tủ truyện/Cá nhân | Compose root tabs | Hoàn tất lõi | Tablet, screenshot và accessibility test |
| Tìm kiếm/danh mục | Theo nguồn + tải thêm | Hoàn tất lõi | Global multi-source search, Paging 3 |
| Chi tiết/mục lục/reader | Typed screens + TTS queue | Hoàn tất lõi | Theme/font/line-height nâng cao |
| Quản lý giọng/từ điển/cache | Compose controls | Hoàn tất lõi | Tách màn hình con, import/export dictionary |
| Theo dõi job WAV | Card tiến độ/hủy/mở tệp | Hoàn tất lõi | Lịch sử chi tiết và chia sẻ |

## 2. Nguồn truyện

| Nguồn | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Truyện Full | Adapter + fixture | Hoàn tất lõi | Live regression định kỳ |
| TruyenCV | Adapter + fixture | Cần xác minh thiết bị | Selector/live network |
| Truyện Com | Adapter + fixture | Cần xác minh thiết bị | Selector/live network |
| TruyenYY | Markdown adapter + fixture | Cần xác minh thiết bị | Jina live-test/privacy |
| Wikidich | Placeholder typed | Chưa triển khai | Cookie/login/parser |
| Sáng Tác Việt | Placeholder typed | Chưa triển khai | Parser + fixture |
| Wattpad/vBook | Không mang extension Lua | Chưa triển khai | Adapter/extension Kotlin mới |

## 3. Tủ truyện, tải và theo dõi

| Chức năng | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Đọc tiếp/bookmark/following | Room + deep links | Hoàn tất lõi | Device/process-death test |
| Tải offline | Batch 40 chương, foreground chain | Hoàn tất lõi | Android 16 quota/device test |
| Quản lý dung lượng | Theo truyện + reader quota | Hoàn tất lõi | Ảnh/DB overhead, multi-select |
| Kiểm tra chương mới | Periodic/manual WorkManager | Cần xác minh thiết bị | OEM/notification test |
| Backup | Format 3 + checksum + merge transaction | Hoàn tất lõi | Encryption/replace/cloud |

## 4. TTS và phát nền

| Chức năng XPK | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Foreground TTS/MediaSession | Service + controls | Hoàn tất lõi | OEM/device test |
| Audio focus | Pause/resume có điều kiện | Hoàn tất lõi | Duck tùy chọn |
| Prefetch/tự chuyển | 75% + Room/network fallback | Hoàn tất lõi | UX lỗi chương kế |
| Voice/rate/pitch toàn cục | DataStore | Hoàn tất lõi | Nhiều preset |
| Hồ sơ giọng theo truyện | Room override | Hoàn tất lõi | Import/export và profile templates |
| Voice theo nhân vật | Chưa có timeline/speaker map | Chưa triển khai | Speaker detection/casting editor |
| Từ điển phát âm | Room + deterministic matcher | Hoàn tất lõi | Boundary/case option, CSV |
| Preview voice | Global settings, foreground TTS | Cần xác minh thiết bị | Nút stop preview riêng |
| Hẹn giờ ngủ | 15/30/60 phút | Hoàn tất lõi | Fade-out/cuối chương |

## 5. Xuất sách nói

| Hạng mục | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Xuất một chương WAV | Cached chapter → SAF URI | Hoàn tất lõi | Engine/device matrix |
| Xuất truyện WAV | Ghép các chương cached/downloaded | Hoàn tất lõi | Tự tải chương thiếu, chapter markers |
| Chia đoạn TTS | Engine max input + segment cap | Hoàn tất lõi | Adaptive sentence chunking benchmark |
| WAV validation | RIFF/fmt/data/padding/format match | Hoàn tất lõi | Fuzz corpus lớn |
| Durable job/progress/cancel | Room + foreground WorkManager | Hoàn tất lõi | Resume giữa segment sau process death |
| M4A/AAC/MP3 | Không có encoder | Chưa triển khai | MediaCodec/encoder/license evaluation |
| Cover/metadata | Chưa có | Chưa triển khai | Artwork, title/author/chapter metadata |
| Scene music/voice casting | Contract only | Contract | Mixer, ducking, loudness policy |

Worker chỉ tổng hợp nội dung đã có trong Room. Tệp đích không bị ghi từng phần: segment được tạo trong thư mục tạm, kiểm tra và ghép trước khi copy sang URI đích.

## 6. Nhập sách và AI

| Nhóm | Kotlin 0.6 | Trạng thái | Phần còn lại |
|---|---|---|---|
| TXT/EPUB/DOCX | Bounded import | Hoàn tất lõi | Style/ảnh/table nâng cao |
| MOBI/AZW | Báo không hỗ trợ | Chưa triển khai | Decoder/license/corpus |
| AI dịch online | Typed interface, provider tắt | Contract | Consent/API key/privacy |
| VietPhrase | Không có engine | Chưa triển khai | Dictionary riêng |
| AI offline | Không có runtime/model | Chưa triển khai | RAM/NNAPI/license benchmark |
| Nhập vai | Không có module | Chưa triển khai | Feature module độc lập |

## 7. Chất lượng và build

| Hạng mục | Kết quả 0.6 |
|---|---|
| Không Lua/DEX/SO/XPK | Đạt release gate |
| Fixture parser | 4 nguồn đạt |
| Pure/static Kotlin | Đạt |
| Audio export stub compile | Đạt |
| Room/repository Android stub compile | Đạt |
| WAV functional harness | Đạt |
| JSON/XML/wiring/artifact scan | Đạt |
| Gradle dependency resolution | Chưa chạy |
| Room KAPT/Compose/test/lint/assembleDebug | Chưa chạy vì không có Android SDK/dependency environment |
| Device TTS/SAF/WorkManager test | Chưa chạy |

## Khoảng trống ưu tiên sau 0.6

1. Build thật trên SDK 36 và sửa mọi lỗi AGP/KAPT/Compose/Lint.
2. Test WAV với Google TTS, Samsung TTS và ít nhất một engine offline khác.
3. Live-test TruyenCV/Truyện Com/TruyenYY và cập nhật fixture đã xác minh.
4. Thêm mở/chia sẻ tệp WAV đã xuất và tự tải các chương thiếu theo lựa chọn người dùng.
5. Thêm profile giọng theo nhân vật trước khi triển khai nhạc cảnh.
6. Chỉ thêm M4A/MP3 sau benchmark encoder, metadata và giấy phép.
