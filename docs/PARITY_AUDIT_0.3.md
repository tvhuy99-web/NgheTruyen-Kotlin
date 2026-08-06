# Kiểm toán parity XPK → Kotlin 0.3.0

Ngày đối chiếu: 2026-08-02

## Cách đánh giá

- **Hoàn tất lõi:** đã có luồng dữ liệu thật, lưu trạng thái và xử lý lỗi rõ ràng.
- **Dùng được, cần xác minh thiết bị:** mã và fixture đã có nhưng chưa chạy live/Android SDK trong môi trường tạo project.
- **Một phần:** đã có nền móng hoặc thao tác cục bộ, còn thiếu tự động hóa/biến thể nâng cao.
- **Contract:** chỉ có interface/model để khóa kiến trúc, chưa có provider sản xuất.
- **Chưa triển khai:** chưa đưa vào ứng dụng Kotlin.

## 1. Điều hướng và giao diện

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| Khám phá, Tủ truyện, Cá nhân | Compose + root tabs | Hoàn tất lõi | Cần screenshot test và polish responsive tablet |
| Danh sách nguồn và thể loại | SourceRegistry + category chips | Hoàn tất lõi | Cần hiển thị trạng thái nguồn bằng nhãn tiếng Việt đẹp hơn |
| Tìm kiếm | Search theo nguồn, URL truyện trực tiếp | Hoàn tất lõi | Chưa có global search/ranking đa nguồn |
| Paging kết quả | Nút TẢI THÊM | Hoàn tất lõi | Chưa chuyển sang Paging 3/infinite scroll |
| Chi tiết và mục lục | StoryDetail + chapter pages | Hoàn tất lõi | Cần lazy list tối ưu cho hàng chục nghìn chương |
| Reader | Compose reader + điều khiển 5 nút | Hoàn tất lõi | Cần theme/font/line-height đầy đủ như XPK |
| TalkBack/cỡ chữ lớn | Semantic tabs và nút kích thước tối thiểu | Một phần | Chưa có accessibility test trên thiết bị |

## 2. Nguồn truyện và mạng

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| Truyện Full | Adapter Kotlin + fixtures | Hoàn tất lõi | Cần live test định kỳ |
| TruyenCV | Adapter Kotlin + fixtures | Dùng được, cần xác minh thiết bị | Giữ `DEGRADED` cho đến khi live selector ổn định |
| Truyện Com | Adapter Kotlin + fixtures | Dùng được, cần xác minh thiết bị | Nguồn API-v2 trong XPK từng bị bỏ sót; đã phục hồi với endpoint/selector hiện tại |
| Wikidich | Placeholder lỗi rõ ràng | Chưa triển khai | Cần login/cookie nếu nguồn yêu cầu |
| Sáng Tác Việt | Placeholder lỗi rõ ràng | Chưa triển khai | Cần parser, throttling và fixture |
| TruyenYY | Placeholder lỗi rõ ràng | Chưa triển khai | Cần parser và test live |
| Wattpad/vBook | Placeholder lỗi rõ ràng | Chưa triển khai | Không đưa runtime extension Lua sang; cần extension format mới hoặc adapter riêng |
| Source health | READY/DEGRADED/NOT_PORTED | Một phần | Chưa có probe tự động và dashboard lỗi selector |
| HTTPS/allowlist | Per-request và từng redirect | Hoàn tất lõi | Cần certificate/network failure telemetry cục bộ |
| Rate limiting | 700 ms tuần tự theo host | Hoàn tất lõi | Chưa đọc `Retry-After` hay adaptive backoff theo 429 |
| Cache HTML | LRU 24 trang, TTL 90 giây | Hoàn tất lõi | Chưa có ETag/Last-Modified hoặc disk cache |
| CAPTCHA/Cloudflare | Fail closed | Hoàn tất lõi | Không và sẽ không tự vượt biện pháp kiểm soát truy cập |
| Đăng nhập/cookie chung | Không có | Chưa triển khai | Cần encrypted cookie store + WebView login cô lập |

## 3. Tủ truyện và dữ liệu

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| Đang đọc/lịch sử | Room + mở lại truyện | Hoàn tất lõi | Cần xóa lịch sử/chỉnh sắp xếp |
| Tiếp tục đọc | Đúng chapterId + paragraphIndex | Hoàn tất lõi | Cần test process death thật |
| Đánh dấu | Thêm, mở đúng đoạn, xóa | Hoàn tất lõi | Chưa hỗ trợ ghi chú/chỉnh tên |
| Theo dõi | Thêm/bỏ và mở truyện | Một phần | Chưa có worker kiểm tra chương mới định kỳ |
| Tải ngoại tuyến | WorkManager + Room + resume, lô 40 chương | Hoàn tất lõi | Cần xóa từng truyện, thống kê dung lượng và đánh giá UIDT trên Android mới |
| Backup thủ công | ZIP versioned + SHA-256 | Hoàn tất lõi | Restore hiện merge-only, chưa mã hóa |
| Android Auto Backup | Room/DataStore được khai báo | Hoàn tất cấu hình | Cần kiểm thử restore trên thiết bị/Google backup |
| Migration Room | DB version 1 | Chưa cần nhưng chưa có | Bắt buộc thêm migration khi schema đổi |

## 4. Đọc nền và TTS

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| TTS nền | Foreground service | Hoàn tất lõi | Cần test OEM battery restrictions |
| Media controls | MediaSession + notification | Hoàn tất lõi | Chưa có Android Auto/Media3 service |
| Audio focus | Pause và resume có điều kiện sau transient loss/gain | Hoàn tất lõi | `CAN_DUCK` hiện pause thay vì giảm âm lượng để tránh đọc chồng âm |
| Wake lock | Partial wake lock có timeout | Hoàn tất lõi | Cần kiểm tra chương dài hơn timeout |
| Tốc độ/cao độ | DataStore + TTS | Hoàn tất lõi | Chưa có profile theo giọng/truyện |
| Prefetch chương | Bắt đầu tại khoảng 75% | Hoàn tất lõi | Cần đo latency và memory trên máy yếu |
| Tự chuyển chương | Có thể bật/tắt | Hoàn tất lõi | Cần UX khi lỗi tải chương kế |
| Chọn engine/voice | Chưa có UI | Chưa triển khai | XPK có quản lý engine/profile phong phú hơn |
| Từ điển phát âm | Không có | Chưa triển khai | Cần pipeline normalize có rule ưu tiên |
| Hẹn giờ ngủ | 15/30/60 phút trong TTS service | Hoàn tất lõi | Chưa có dừng cuối chương hoặc fade-out |
| Sonic/native DSP | Không sao chép | Chủ ý loại bỏ | Chỉ thêm DSP mới khi benchmark chứng minh Android TTS không đủ |

## 5. Nhập sách

| Định dạng/tính năng | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| TXT | Bounded 64 MiB + BOM | Hoàn tất lõi | Chưa có chọn charset thủ công cho tệp legacy |
| EPUB | OPF spine + HTML chapters | Hoàn tất lõi | Chưa xử lý DRM, ảnh xen kẽ, footnote nâng cao |
| DOCX | Giữ ranh giới đoạn | Hoàn tất lõi | Chưa giữ heading/style/bảng |
| ZIP safety | Entry count, per-entry/total limit, traversal/alias rejection | Hoàn tất lõi | Cần fuzz corpus lớn |
| MOBI/AZW/AZW3 | Báo chưa hỗ trợ | Chưa triển khai | Cần decoder có giấy phép phù hợp và test corpus |

## 6. AI, phân vai, nhạc cảnh và nhập vai

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| Dịch AI online | Interface typed, provider tắt | Contract | Cần privacy consent, khóa API và quota |
| VietPhrase | Chưa có engine | Chưa triển khai | Có thể làm local dictionary trước AI |
| AI offline | Chưa có runtime/model | Chưa triển khai | Cần lựa chọn model, RAM/NNAPI benchmark và license |
| Phân vai | Planner contract | Contract | Cần speaker detection, voice mapping và cache |
| Nhạc cảnh | Planner contract | Contract | Cần policy âm lượng, loop và bản quyền audio |
| Nhập vai | Không có module | Chưa triển khai | Nên là feature module riêng, không trộn vào reader core |

## 7. Xuất sách nói

| Chức năng XPK | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| TTS ra tệp | Interface `AudioExportEngine` | Contract | Cần queue bền vững và resume |
| WAV | Chưa encode | Chưa triển khai | Có thể dùng TTS synthesizeToFile + WAV validation |
| M4A/AAC | Chưa mux/encode | Chưa triển khai | Cần MediaCodec/MediaMuxer và metadata |
| MP3 | Chưa encode | Chưa triển khai | Android không có encoder MP3 chuẩn chung; cần quyết định dependency/license |
| Chia file theo chương | Chưa có | Chưa triển khai | Cần naming, chapter metadata và SAF output tree |

## 8. Chất lượng, bảo mật và build

| Hạng mục | Kotlin 0.3 | Đánh giá | Ghi chú còn lại |
|---|---|---|---|
| Không Lua/DEX/SO XPK | Release gate | Hoàn tất |
| Dependency baseline | AGP 8.13.2, Gradle 8.13, Kotlin 2.3.21 | Cấu hình xong | Chưa resolve bằng Gradle trong môi trường hiện tại |
| SDK baseline | compileSdk 36.1, target 36 | Cấu hình xong | Cần cài Platform 36 và build thật |
| Wrapper integrity | SHA-256 distribution + JAR | Hoàn tất cấu hình | Wrapper JAR được sinh ở máy build |
| Parser fixture | Truyện Full + TruyenCV + Truyện Com | Hoàn tất cơ bản | Cần snapshot fixture định kỳ |
| Static compile gate | Pure Kotlin + TruyenCV stub compile | Đạt | Không thay thế Android compiler |
| Unit test Gradle | Chưa chạy | Chưa xác minh | Android SDK/Gradle dependency chưa có trong môi trường tạo project |
| Lint/assembleDebug | Chưa chạy | Chưa xác minh | Bắt buộc trước khi cài/phát hành |
| Device tests | Chưa chạy | Chưa xác minh | TTS, WorkManager, process death, notification, OEM battery |

## Ưu tiên tiếp theo

1. Chạy Gradle Sync, `test`, `lint` và `assembleDebug` trên SDK 36 để chốt compile thực tế.
2. Live-test TruyenCV và Truyện Com, cập nhật fixture từ HTML đã xác minh rồi mới nâng `DEGRADED` thành `READY`.
3. Thêm worker kiểm tra chương mới cho danh sách theo dõi, có interval hợp lý và thông báo opt-in.
4. Thêm chọn engine/voice TTS và từ điển phát âm; mở rộng timer thành dừng cuối chương/fade-out.
5. Port Wikidich hoặc Sáng Tác Việt bằng cùng template parser/fixture/rate-limit.
6. Hoàn thiện quản lý dung lượng offline và xóa tải.
7. Sau khi lõi ổn định mới triển khai xuất audio, AI, phân vai, nhạc cảnh và nhập vai.
