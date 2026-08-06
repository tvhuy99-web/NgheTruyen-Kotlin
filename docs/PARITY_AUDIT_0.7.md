# Kiểm toán parity XPK → Kotlin 0.7.0, gói P1

Ngày đối chiếu: 2026-08-02

## Cách đọc trạng thái

- **Hoàn tất lõi:** có dữ liệu thật, persistence, xử lý lỗi và release gate ngoại tuyến.
- **Cần xác minh thiết bị:** code/fixture đã có nhưng chưa build hoặc live-test Android.
- **Một phần:** luồng cơ bản dùng được, còn thiếu biến thể nâng cao.
- **Contract:** mới khóa interface/model, chưa có engine sản xuất.
- **Chưa triển khai:** chưa có luồng Kotlin tương ứng.

## 1. Gói P1: trải nghiệm đọc

| Chức năng XPK | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Theme reader | System/Sáng/Tối/Sepia | Hoàn tất lõi | AMOLED/custom color, screenshot/device test |
| Cỡ chữ | Điều chỉnh và lưu DataStore | Hoàn tất lõi | Preset, pinch gesture |
| Khoảng cách dòng | Điều chỉnh phần trăm | Hoàn tất lõi | Khoảng đoạn, lề ngang |
| Giữ màn hình sáng | Tùy chọn chỉ trong reader | Hoàn tất lõi | Device/OEM test |
| Tìm trong chương | Tìm bỏ dấu, đếm, trước/sau, highlight | Hoàn tất lõi | Regex, whole-word, lịch sử tìm |
| Sao chép | Đoạn hiện tại hoặc cả chương | Hoàn tất lõi | Chọn vùng văn bản tự do, chia sẻ |
| Tìm chương | Lọc theo tiêu đề/số trong catalog đã nạp | Hoàn tất lõi | Tìm server-side khi catalog chưa nạp |
| Tải toàn mục lục | Nút nạp mọi trang theo yêu cầu | Hoàn tất lõi | Progress chi tiết, giới hạn cực dài |
| Chọn khoảng tải | Start/end inclusive → WorkManager | Hoàn tất lõi | Multi-select rời rạc, tải theo checkbox |
| Bình luận | Bỏ tab giả; mở URL nguồn nếu có | Một phần | Parser/comment UI native |
| Mở trang gốc | HTTPS external intent | Hoàn tất lõi | In-app isolated browser tùy chọn |

## 2. Tìm kiếm đa nguồn

| Chức năng XPK | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Tìm đồng thời nhiều nguồn | Song song READY + DEGRADED | Hoàn tất lõi | Concurrency tuning/device network test |
| Hủy tìm kiếm | Hủy coroutine job và cập nhật UI | Hoàn tất lõi | Hủy request socket tùy client |
| Tiến độ | Đếm nguồn hoàn tất/tổng nguồn | Hoàn tất lõi | Trạng thái lỗi từng nguồn |
| Chuẩn hóa tiếng Việt | Bỏ dấu, đ/d, punctuation/space | Hoàn tất lõi | Fuzzy/typo/initial matching |
| Gom truyện trùng | Tên + tác giả, ưu tiên nguồn khỏe | Hoàn tất lõi | Cho người dùng xem các nguồn thay thế |
| Paging đa nguồn | Nạp trang tiếp theo và merge | Hoàn tất lõi | Paging 3, source exhaustion riêng |
| Xếp hạng thông minh | Sắp tên, ưu tiên health khi trùng | Một phần | Relevance score, Levenshtein, popularity |
| Lịch sử tìm kiếm | Chưa có | Chưa triển khai | Room/DataStore + recent queries |

## 3. Theo dõi và số chương mới

| Chức năng XPK | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Lưu chương mới nhất đã biết | Title + index trong Room | Hoàn tất lõi | Source-specific stable chapter key |
| Đếm chương mới | Delta index/title number, fallback 1 | Hoàn tất lõi | Chính xác tuyệt đối khi nguồn đổi/xóa chương |
| Cộng dồn chưa xem | Persistent `newChapterCount` | Hoàn tất lõi | Badge tổng trên tab chính |
| Đánh dấu đã xem | Reset khi mở truyện/deep link | Hoàn tất lõi | Mark seen không cần mở truyện |
| Kiểm tra định kỳ/thủ công | WorkManager opt-in | Cần xác minh thiết bị | OEM quota/notification test |
| Notification deep link | Mở đúng story từ cold start | Cần xác minh thiết bị | Back-stack/device test |

## 4. Nguồn truyện

| Nguồn | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Truyện Full | Adapter + fixture | Hoàn tất lõi | Live regression định kỳ |
| TruyenCV | Adapter + fixture | Cần xác minh thiết bị | Selector/live network |
| Truyện Com | Adapter + fixture | Cần xác minh thiết bị | Selector/live network |
| TruyenYY | Markdown adapter + fixture | Cần xác minh thiết bị | Reader service live-test/privacy |
| Wikidich | Placeholder typed | Chưa triển khai | Cookie/login/parser |
| Sáng Tác Việt | Placeholder typed | Chưa triển khai | Parser + fixture |
| Wattpad/vBook | Không mang extension Lua | Chưa triển khai | Adapter/extension Kotlin mới |

## 5. Tủ truyện, tải và lưu trữ

| Chức năng | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Đọc tiếp/bookmark/following | Room + deep links | Hoàn tất lõi | Device/process-death test |
| Tải toàn truyện | Batch 40 chương, foreground chain | Hoàn tất lõi | Android quota/device test |
| Tải khoảng chương | Range inclusive, resume/skip stored | Hoàn tất lõi | Multi-range/priority queue |
| Quản lý dung lượng | Theo truyện + reader quota | Hoàn tất lõi | Ảnh/DB overhead, multi-select |
| Kiểm tra chương mới | Count + periodic/manual worker | Cần xác minh thiết bị | OEM/notification test |
| Backup | Format 4 + checksum + merge transaction | Hoàn tất lõi | Encryption/replace/cloud |

Lưu ý: một truyện có tải một phần vẫn được đưa vào khu vực ngoại tuyến để người dùng quản lý; chỉ những chương có nội dung trong Room mới đọc được khi mất mạng.

## 6. TTS và phát nền

| Chức năng XPK | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Foreground TTS/MediaSession | Service + controls | Hoàn tất lõi | OEM/device test |
| Audio focus | Pause/resume có điều kiện | Hoàn tất lõi | Duck tùy chọn |
| Prefetch/tự chuyển | 75% + Room/network fallback | Hoàn tất lõi | UX lỗi chương kế |
| Voice/rate/pitch toàn cục | DataStore | Hoàn tất lõi | Chọn engine trong app, preset |
| Hồ sơ giọng theo truyện | Room override | Hoàn tất lõi | Volume/method/Sonic |
| Voice theo nhân vật | Chưa có timeline/speaker map | Chưa triển khai | Speaker detection/casting editor |
| Từ điển phát âm | Room + deterministic matcher | Hoàn tất lõi | Boundary/case option, CSV |
| Preview voice | Global settings, foreground TTS | Cần xác minh thiết bị | Stop preview riêng |
| Hẹn giờ ngủ | 15/30/60 phút | Hoàn tất lõi | Fade-out/cuối chương |

## 7. Xuất sách nói

| Hạng mục | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Xuất một chương WAV | Cached chapter → SAF URI | Hoàn tất lõi | Engine/device matrix |
| Xuất truyện WAV | Ghép các chương cached/downloaded | Hoàn tất lõi | Tự tải chương thiếu, markers |
| WAV validation | RIFF/fmt/data/padding/format match | Hoàn tất lõi | Fuzz corpus lớn |
| Durable job/progress/cancel | Room + foreground WorkManager | Hoàn tất lõi | Resume giữa segment sau process death |
| M4A/AAC/MP3 | Không có encoder | Chưa triển khai | Encoder/license evaluation |
| Cover/metadata | Chưa có | Chưa triển khai | Artwork/title/author/chapter metadata |
| Scene music/voice casting | Contract only | Contract | Mixer, ducking, loudness policy |

## 8. Nhập sách và AI

| Nhóm | Kotlin 0.7 | Trạng thái | Phần còn lại |
|---|---|---|---|
| TXT/EPUB/DOCX | Bounded import | Hoàn tất lõi | Style/ảnh/table nâng cao |
| MOBI/AZW | Báo không hỗ trợ | Chưa triển khai | Decoder/license/corpus |
| AI dịch online | Typed interface, provider tắt | Contract | Consent/API key/privacy |
| VietPhrase | Không có engine | Chưa triển khai | Dictionary riêng |
| AI offline | Không có runtime/model | Chưa triển khai | RAM/NNAPI/license benchmark |
| Nhập vai | Không có module | Chưa triển khai | Feature module độc lập |

## 9. Chất lượng và build

| Hạng mục | Kết quả 0.7 |
|---|---|
| Không Lua/DEX/SO/XPK | Đạt release gate |
| Fixture parser | 4 nguồn đạt |
| Pure/static Kotlin | Đạt |
| P1 pure feature harness | Đạt |
| Reader/StoryDetail Compose stub compile | Đạt |
| Audio export stub compile | Đạt |
| Room/repository Android stub compile | Đạt khi chạy gate riêng |
| WAV functional harness | Đạt |
| JSON/XML/wiring/artifact scan | Đạt |
| Gradle dependency resolution | Chưa chạy được |
| Room KAPT/Compose/test/lint/assembleDebug | Chưa chạy vì thiếu Android SDK và mạng dependency |
| Device reader/clipboard/range/multi-source test | Chưa chạy |

## Khoảng trống ưu tiên sau P1

1. Build thật trên SDK 36 và sửa mọi lỗi AGP/KAPT/Compose/Lint.
2. Chạy device acceptance cho reader theme, clipboard, keep-screen-on, range download và multi-source search.
3. Live-test TruyenCV/Truyện Com/TruyenYY và cập nhật fixture đã xác minh.
4. Thêm tìm kiếm relevance/fuzzy, lịch sử tìm và hiển thị nguồn thay thế cho truyện trùng.
5. Thêm chọn nhiều chương rời rạc và quản lý ưu tiên download.
6. Sau khi P1 ổn định mới chuyển sang nguồn cần cookie/login hoặc audio/AI nâng cao.
