# Kiểm toán parity XPK → Kotlin 0.4.0

Ngày đối chiếu: 2026-08-02

## Cách đọc trạng thái

- **Hoàn tất lõi:** có dữ liệu thật, persistence và xử lý lỗi.
- **Cần xác minh thiết bị:** code/fixture đã có nhưng chưa build hoặc live-test Android trong môi trường này.
- **Một phần:** dùng được ở mức cơ bản, còn thiếu biến thể nâng cao.
- **Contract:** mới khóa interface/model, chưa có engine sản xuất.
- **Chưa triển khai:** chưa có luồng Kotlin tương ứng.

## 1. Giao diện và điều hướng

| Chức năng XPK | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Khám phá/Tủ truyện/Cá nhân | Compose root tabs | Hoàn tất lõi | Tablet, screenshot test, polish responsive |
| Tìm kiếm và danh mục | Theo nguồn + tải thêm | Hoàn tất lõi | Global search đa nguồn, Paging 3 |
| Chi tiết/mục lục | Typed detail + chapter paging | Hoàn tất lõi | Tối ưu mục lục cực dài |
| Reader và 5 nút điều khiển | Compose + TTS queue | Hoàn tất lõi | Theme/font/line-height đầy đủ |
| Accessibility | Semantic tab, touch target cơ bản | Một phần | TalkBack, font scale và contrast test thật |

## 2. Nguồn truyện

| Nguồn | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Truyện Full | Adapter + fixture + trang chương cuối | Hoàn tất lõi | Live regression định kỳ |
| TruyenCV | Adapter + fixture | Cần xác minh thiết bị | Giữ `DEGRADED` đến khi selector live ổn định |
| Truyện Com | Adapter + fixture + trang chương cuối | Cần xác minh thiết bị | Giữ `DEGRADED` đến khi live-test |
| Wikidich | Placeholder typed | Chưa triển khai | Login/cookie nếu cần |
| Sáng Tác Việt | Placeholder typed | Chưa triển khai | Parser + fixture |
| TruyenYY | Placeholder typed | Chưa triển khai | Parser + fixture |
| Wattpad/vBook | Không mang extension Lua sang | Chưa triển khai | Adapter hoặc extension format Kotlin mới |

Mạng đã có HTTPS-only, allowlist trước request và từng redirect, tối đa 5 redirect, HTML tối đa 4 MiB, điều tiết 700 ms/host, LRU 24 trang/90 giây và fail-closed với CAPTCHA/Cloudflare.

## 3. Tủ truyện, theo dõi và dung lượng

| Chức năng XPK | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Đang đọc/đọc tiếp | Room chapter + paragraph | Hoàn tất lõi | Process-death device test |
| Đánh dấu | Thêm/mở/xóa | Hoàn tất lõi | Ghi chú và đổi tên |
| Theo dõi cục bộ | Thêm/bỏ/mở | Hoàn tất lõi | Lọc/sắp xếp nâng cao |
| Kiểm tra chương mới | Opt-in WorkManager 12h + manual | Cần xác minh thiết bị | WorkManager/OEM/device test |
| Công bằng hàng đợi | 30 truyện lâu chưa check nhất | Hoàn tất lõi | Cho phép đổi batch/cadence |
| Notification deep link | Query thẳng Room theo storyId | Cần xác minh thiết bị | Test cold/warm task thực |
| Tải ngoại tuyến | Chained foreground batches 40 chương | Hoàn tất lõi | UIDT evaluation/telemetry |
| Dung lượng từng truyện | Số chương + byte text | Hoàn tất lõi | Tính thêm ảnh/DB overhead |
| Xóa bản tải | Transactional cleanup | Hoàn tất lõi | Multi-select và quota tự động |
| Xóa cache reader | Chỉ xóa truyện không offline | Hoàn tất lõi | Chính sách LRU/dung lượng tối đa |

## 4. TTS và phát nền

| Chức năng XPK | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Foreground TTS | Service + MediaSession | Hoàn tất lõi | OEM battery/device test |
| Audio focus | Pause/resume có điều kiện | Hoàn tất lõi | Duck tùy chọn |
| Prefetch/tự chuyển | 75% + Room/network fallback | Hoàn tất lõi | UX lỗi chương kế |
| Tốc độ/cao độ | DataStore | Hoàn tất lõi | Profile theo truyện |
| Chọn voice | Quét voice, local/network, lưu lựa chọn | Cần xác minh thiết bị | Preview voice và lọc ngôn ngữ |
| Đổi engine/cài dữ liệu | Mở TTS settings hệ thống | Một phần | UI chọn engine trực tiếp không có API chuẩn ổn định |
| Từ điển phát âm | Không có | Chưa triển khai | Rule engine local |
| Hẹn giờ ngủ | 15/30/60 phút | Hoàn tất lõi | Dừng cuối chương/fade-out |
| Sonic/native DSP cũ | Không sao chép | Chủ ý loại bỏ | Chỉ thêm giải pháp mới sau benchmark/license |

## 5. Nhập và sao lưu

| Chức năng | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| TXT | 64 MiB + BOM UTF-8/UTF-16 | Hoàn tất lõi | Chọn charset legacy |
| EPUB | OPF spine | Hoàn tất lõi | Footnote/ảnh/style nâng cao |
| DOCX | Paragraph boundaries | Hoàn tất lõi | Heading/table/style |
| MOBI/AZW | Báo không hỗ trợ | Chưa triển khai | Decoder/license/test corpus |
| ZIP safety | Bounds + traversal/alias/duplicate rejection | Hoàn tất lõi | Fuzz corpus lớn |
| Backup thủ công | Version + SHA-256 + merge transaction | Hoàn tất lõi | Mã hóa, replace-mode, cloud |
| Cài đặt mới trong backup | Voice/language/following opt-in | Hoàn tất lõi | Kiểm thử tương thích nhiều phiên bản |

## 6. AI, phân vai và xuất audio

| Nhóm | Kotlin 0.4 | Trạng thái | Phần còn lại |
|---|---|---|---|
| AI dịch online | Typed interface, provider tắt | Contract | Consent, API key, quota, privacy |
| VietPhrase | Không có engine | Chưa triển khai | Local dictionary nên làm trước AI |
| AI offline | Không có runtime/model | Chưa triển khai | RAM/NNAPI/license benchmark |
| Phân vai/nhạc cảnh | Typed planner contracts | Contract | Speaker detection, voice map, audio policy |
| WAV/M4A/MP3 | `AudioExportEngine` contract | Contract | Durable queue, encoder/muxer, SAF tree |
| Nhập vai | Chưa có module | Chưa triển khai | Feature module độc lập |

## 7. Chất lượng và build

| Hạng mục | Kết quả 0.4 |
|---|---|
| Không Lua/DEX/SO/XPK | Release gate |
| Fixture parser | Truyện Full, TruyenCV, Truyện Com |
| Pure/static Kotlin subset | Đạt |
| Room/repository compile với stub | Đạt |
| Following worker/scheduler compile với stub | Đạt |
| JSON/XML/wiring/artifact scan | Đạt khi chạy release gate |
| Gradle dependency resolution | Chưa chạy |
| Room KAPT/test/lint/assembleDebug | Chưa chạy vì không có Android SDK và mạng |
| Device test | Chưa chạy |

## Ưu tiên tiếp theo

1. Build thật trên SDK 36, sửa mọi lỗi Gradle/KAPT/Lint trước khi thêm feature lớn.
2. Live-test TruyenCV và Truyện Com; ghi fixture mới từ HTML đã xác minh.
3. Thêm từ điển phát âm local và preview voice.
4. Thêm quota/LRU cho cache và bulk management offline.
5. Port một nguồn mới không cần đăng nhập, sau đó mới thiết kế encrypted cookie session.
6. Làm WAV export trước M4A/MP3; AI/phân vai sau khi lõi build và device test ổn định.
