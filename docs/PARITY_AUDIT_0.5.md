# Kiểm toán parity XPK → Kotlin 0.5.0

Ngày đối chiếu: 2026-08-02

## Cách đọc trạng thái

- **Hoàn tất lõi:** có dữ liệu thật, persistence, xử lý lỗi và release gate ngoại tuyến.
- **Cần xác minh thiết bị:** code và fixture đã có nhưng chưa build/live-test Android trong môi trường này.
- **Một phần:** luồng cơ bản dùng được, còn thiếu biến thể nâng cao.
- **Contract:** mới khóa interface/model, chưa có engine sản xuất.
- **Chưa triển khai:** chưa có luồng Kotlin tương ứng.

## 1. Giao diện và điều hướng

| Chức năng XPK | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Khám phá/Tủ truyện/Cá nhân | Compose root tabs | Hoàn tất lõi | Tablet, screenshot test, polish responsive |
| Tìm kiếm và danh mục | Theo nguồn + tải thêm | Hoàn tất lõi | Global search đa nguồn, Paging 3 |
| Chi tiết/mục lục | Typed detail + chapter paging | Hoàn tất lõi | Tối ưu mục lục cực dài |
| Reader và 5 nút điều khiển | Compose + TTS queue | Hoàn tất lõi | Theme/font/line-height đầy đủ |
| Cài đặt giọng/từ điển/cache | Compose, quản lý đầy đủ danh sách | Hoàn tất lõi | Tách thành màn hình con khi dữ liệu lớn |
| Accessibility | Semantic tab, touch target cơ bản | Một phần | TalkBack, font scale và contrast test thật |

## 2. Nguồn truyện

| Nguồn | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Truyện Full | Adapter + fixture + trang chương cuối | Hoàn tất lõi | Live regression định kỳ |
| TruyenCV | Adapter + fixture | Cần xác minh thiết bị | Giữ `DEGRADED` đến khi selector live ổn định |
| Truyện Com | Adapter + fixture + trang chương cuối | Cần xác minh thiết bị | Giữ `DEGRADED` đến khi live-test |
| TruyenYY | Markdown adapter + fixture + mục lục nhiều trang | Cần xác minh thiết bị | Live-test Jina Reader, theo dõi thay đổi format và privacy disclosure |
| Wikidich | Placeholder typed | Chưa triển khai | Login/cookie nếu cần |
| Sáng Tác Việt | Placeholder typed | Chưa triển khai | Parser + fixture |
| Wattpad/vBook | Không mang extension Lua sang | Chưa triển khai | Adapter hoặc extension format Kotlin mới |

Mạng HTML và Markdown đều HTTPS-only, kiểm tra allowlist ở từng redirect, tối đa 5 redirect, giới hạn 4 MiB, điều tiết theo host và cache bounded. Adapter TruyenYY chỉ chấp nhận URL truyện/chương hợp lệ; URL danh mục hoặc HTTP bị từ chối. TruyenYY đi qua Jina Reader nên truy vấn tìm kiếm có thể xuất hiện trong URL gửi tới dịch vụ trung gian; nguồn vẫn ở trạng thái `DEGRADED` cho tới khi người dùng chấp nhận và live-test hoàn tất.

## 3. Tủ truyện, theo dõi và dung lượng

| Chức năng XPK | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Đang đọc/đọc tiếp | Room chapter + paragraph | Hoàn tất lõi | Process-death device test |
| Đánh dấu | Thêm/mở/xóa | Hoàn tất lõi | Ghi chú và đổi tên |
| Theo dõi cục bộ | Thêm/bỏ/mở | Hoàn tất lõi | Lọc/sắp xếp nâng cao |
| Kiểm tra chương mới | Opt-in WorkManager 12h + manual | Cần xác minh thiết bị | WorkManager/OEM/device test |
| Công bằng hàng đợi | 30 truyện lâu chưa check nhất | Hoàn tất lõi | Cho phép đổi batch/cadence |
| Notification deep link | Query thẳng Room theo storyId | Cần xác minh thiết bị | Test cold/warm task thực |
| Tải ngoại tuyến | Chained foreground batches 40 chương | Hoàn tất lõi | UIDT evaluation/telemetry |
| Dung lượng từng truyện | Số chương + byte text | Hoàn tất lõi | Tính thêm ảnh/DB overhead |
| Xóa bản tải | Transactional cleanup | Hoàn tất lõi | Multi-select |
| Cache reader | Quota 16/32/64/128/256 MiB + dọn cũ nhất | Hoàn tất lõi | Chính sách theo truyện và telemetry |

Quota chỉ dọn nội dung cache của truyện mạng chưa đánh dấu tải ngoại tuyến. Chương đang mở và chương đang phát được bảo vệ; nếu riêng các chương được bảo vệ vượt quota thì bộ dọn không xóa chúng.

## 4. TTS và phát nền

| Chức năng XPK | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| Foreground TTS | Service + MediaSession | Hoàn tất lõi | OEM battery/device test |
| Audio focus | Pause/resume có điều kiện | Hoàn tất lõi | Duck tùy chọn |
| Prefetch/tự chuyển | 75% + Room/network fallback | Hoàn tất lõi | UX lỗi chương kế |
| Tốc độ/cao độ | DataStore | Hoàn tất lõi | Profile theo truyện |
| Chọn voice | Quét voice, local/network, lưu lựa chọn | Cần xác minh thiết bị | Kiểm thử nhiều engine/OEM |
| Nghe thử voice | Foreground TTS, dùng rate/pitch/từ điển hiện tại | Cần xác minh thiết bị | Nút dừng preview riêng |
| Đổi engine/cài dữ liệu | Mở TTS settings hệ thống | Một phần | UI chọn engine trực tiếp không có API chuẩn ổn định |
| Từ điển phát âm | Room + rule engine longest-match, không cascade | Hoàn tất lõi | Boundary/case option và import/export CSV |
| Hẹn giờ ngủ | 15/30/60 phút | Hoàn tất lõi | Dừng cuối chương/fade-out |
| Sonic/native DSP cũ | Không sao chép | Chủ ý loại bỏ | Chỉ thêm giải pháp mới sau benchmark/license |

Từ điển được áp dụng cục bộ ngay trước `TextToSpeech.speak`. Replacement không được đưa lại vào matcher, tránh vòng lặp hoặc thay thế dây chuyền ngoài ý muốn.

## 5. Nhập và sao lưu

| Chức năng | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| TXT | 64 MiB + BOM UTF-8/UTF-16 | Hoàn tất lõi | Chọn charset legacy |
| EPUB | OPF spine | Hoàn tất lõi | Footnote/ảnh/style nâng cao |
| DOCX | Paragraph boundaries | Hoàn tất lõi | Heading/table/style |
| MOBI/AZW | Báo không hỗ trợ | Chưa triển khai | Decoder/license/test corpus |
| ZIP safety | Bounds + traversal/alias/duplicate rejection | Hoàn tất lõi | Fuzz corpus lớn |
| Backup thủ công | Version 2 + SHA-256 + merge transaction | Hoàn tất lõi | Mã hóa, replace-mode, cloud |
| Tương thích backup v1 | Reader chấp nhận format 1..2, field mới có default | Hoàn tất lõi | Fixture archive v1/v2 bằng instrumented test |
| Từ điển/cache trong backup | Có | Hoàn tất lõi | Xung đột rule theo chính sách tùy chọn |

## 6. AI, phân vai và xuất audio

| Nhóm | Kotlin 0.5 | Trạng thái | Phần còn lại |
|---|---|---|---|
| AI dịch online | Typed interface, provider tắt | Contract | Consent, API key, quota, privacy |
| VietPhrase | Không có engine | Chưa triển khai | Local phrase dictionary độc lập với từ điển phát âm |
| AI offline | Không có runtime/model | Chưa triển khai | RAM/NNAPI/license benchmark |
| Phân vai/nhạc cảnh | Typed planner contracts | Contract | Speaker detection, voice map, audio policy |
| WAV/M4A/MP3 | `AudioExportEngine` contract | Contract | Durable queue, encoder/muxer, SAF tree |
| Nhập vai | Chưa có module | Chưa triển khai | Feature module độc lập |

## 7. Chất lượng và build

| Hạng mục | Kết quả 0.5 |
|---|---|
| Không Lua/DEX/SO/XPK | Release gate |
| Fixture parser | Truyện Full, TruyenCV, Truyện Com, TruyenYY |
| Pure/static Kotlin subset | Đạt |
| Room migration/repository compile với stub | Đạt |
| Android worker/scheduler wiring compile với stub | Đạt |
| Pronunciation processor tests/static compile | Đạt |
| JSON/XML/wiring/artifact scan | Đạt khi chạy release gate |
| Gradle dependency resolution | Chưa chạy |
| Room KAPT/test/lint/assembleDebug | Chưa chạy vì không có Android SDK và dependency network |
| Device test | Chưa chạy |

## Khoảng trống ưu tiên sau 0.5

1. Build thật trên SDK 36, sửa mọi lỗi Gradle/KAPT/Lint trước khi thêm feature lớn.
2. Live-test TruyenCV, Truyện Com và TruyenYY; cập nhật fixture từ phản hồi đã xác minh.
3. Làm WAV export bằng Android TTS synthesis + SAF/WorkManager, sau đó mới thêm M4A/MP3.
4. Thêm profile TTS theo truyện và import/export từ điển phát âm.
5. Port Sáng Tác Việt hoặc Wikidich; thiết kế cookie mã hóa trước nguồn cần đăng nhập.
6. MOBI/AZW và AI/phân vai chỉ triển khai sau khi có build/device baseline ổn định.
