# Nghe Truyện, bản viết lại Kotlin sạch

## Phiên bản 2.8.0, hoàn tất ưu tiên 2: điều phối AI và hồ sơ giọng

Bản này giữ toàn bộ Priority 1 và hoàn thiện chuỗi AI trước playback:

- Chương có auto-translation được khóa ở trạng thái chuẩn bị; TTS, notification và phím tai nghe chỉ phát sau khi bản dịch sẵn sàng. Khi lỗi, người dùng chủ động thử lại hoặc chọn bản gốc.
- Phân vai, biểu cảm và nhạc cảnh có thể được lập trong một yêu cầu AI thống nhất, mang theo phần kết chương trước, cue trước và track đang tiếp nối.
- Hồ sơ vai giọng có editor đầy đủ cho engine, voice, ngôn ngữ, tốc độ, cao độ, âm lượng, biểu cảm, Sonic, bật/tắt và nghe thử riêng.
- Đổi tên vai giữ đúng bản ghi cũ; nghe thử áp dụng biểu cảm và các hệ số TTS của hồ sơ.
- Room vẫn là schema 18; backup format 15. Phiên bản ứng dụng: `2.8.0-ai-narration-priority2-complete`, versionCode 28.

Xem [báo cáo hoàn tất Priority 2](docs/AI_NARRATION_PRIORITY2_V280_REPORT.md).

## Phiên bản 2.7.0, hoàn tất ưu tiên 1: độ trung thực nguồn

Bản này giữ toàn bộ khả năng v2.5.0 và sửa lớp chọn/điều phối nguồn để ưu tiên logic website chuyên biệt thay vì các selector pack còn thiếu parity:


- Adapter Kotlin chuyên biệt thắng SourcePack cùng ID; SourcePack chỉ được thay thế khi có priority cao và được chứng nhận đủ action/fixture parity.
- Thêm API `home` và `suggestions`, nối vào giao diện Khám phá, health check và runtime vBook.
- Sửa phân trang TOC SourcePack/vBook bằng continuation thật, không còn gọi lại trang đầu rồi `drop(index)`.
- Giữ nguyên thứ tự cập nhật/hot của website trên trang chủ và danh mục.
- Mở rộng fixture declarative từ 24 lên 36 và thêm live source smoke monitor định kỳ.

- Nhập trực tiếp extension Native Source API 2 từ `.lua` hoặc ZIP bằng LuaJ sandbox. Extension được chuyển thành runtime SourcePack/vBook có origin và capability rõ ràng; pure-Lua hooks chỉ trao đổi JSON có giới hạn.
- Bổ sung các host API vBook thường dùng: Http/Html, DOM wrapper, storage, Engine/Browser, cookie/session, request metadata, crypto, WebSocket và log.
- Thêm trình duyệt chẩn đoán đăng nhập chuyên dụng theo nguồn với điều hướng, JS/cookie/DOM/request probes, lưu phiên mã hóa, log khử bí mật và xuất JSON.
- Chuẩn hóa capability bình luận theo từng nguồn, đưa comments vào health check, hiển thị số fixture và mở rộng parser cho payload lồng/phân trang.
- Room vẫn là schema 18; backup format 15. Phiên bản ứng dụng: `2.7.0-source-fidelity-priority1-complete`, versionCode 27.
- Native Lua API 2 hỗ trợ package nhiều tệp, module `require()` nội bộ và tài nguyên package trong sandbox có giới hạn.
- vBook bổ sung Graphics, storage đầy đủ, browser/dialog/session thật, WebSocket, Crypto/CryptoJS tương thích và `Qt.translate` qua AI đã cấu hình.
- Backup chọn lọc có thể mang theo SourcePack/extension, storage nguồn không nhạy cảm và tệp nhạc cảnh vật lý có checksum.

Xem [báo cáo hoàn tất Priority 1 v2.7.0](docs/SOURCE_FIDELITY_PRIORITY1_COMPLETE_V270_REPORT.md) và [báo cáo Priority 1 v2.6.0](docs/SOURCE_FIDELITY_PRIORITY1_V260_REPORT.md) và [báo cáo nền v2.5.0](docs/XPK_MAX_COMPATIBILITY_V250_REPORT.md).

## Phiên bản 2.3.0, phân vai nâng cao, VietPhrase trực tuyến và backup chọn lọc

Bản này hoàn thiện ba nhánh quản trị AI và dữ liệu trên nền v2.2.0:

- Hồ sơ AI theo truyện có prompt phân vai riêng, ghi chú nhân vật/bối cảnh, chế độ chỉ phân vai lời thoại, khóa ổn định người kể chuyện, prompt biểu cảm và giới hạn điều chỉnh tốc độ, cao độ, âm lượng. Các điều chỉnh được áp dụng cho cả playback lẫn xuất audiobook.
- Trình cập nhật VietPhrase tự khám phá bộ dữ liệu khuyến nghị từ các nguồn HTTPS tin cậy, thử nhiều URL/định dạng, kiểm tra redirect, loại HTML giả, giới hạn dung lượng, kiểm tra ngưỡng số mục và chỉ commit nguyên tử sau khi toàn bộ bộ dữ liệu hợp lệ.
- Sao lưu và khôi phục có thể chọn riêng cài đặt, thư viện, tiến độ đọc, AI/giọng đọc, VietPhrase và nhạc cảnh. Bản backup ghi rõ thành phần có trong gói và chỉ khôi phục phần giao nhau giữa lựa chọn người dùng với dữ liệu thực tế.
- Room schema 18 và backup format 14 giữ tương thích nâng cấp từ schema 17 và các backup cũ; API key vẫn không được xuất.
- Phiên bản ứng dụng: `2.3.0-voicecast-vietphrase-online-selective-backup`, versionCode 23.

Xem [báo cáo triển khai v2.3.0](docs/ADVANCED_VOICECAST_VIETPHRASE_ONLINE_SELECTIVE_BACKUP_REPORT.md) để biết thiết kế, lớp bảo vệ và kết quả kiểm tra.


## Phiên bản 2.2.0, Gemini Native, AI theo truyện và cải thiện VietPhrase

Bản này bổ sung ba luồng AI theo yêu cầu, đồng thời giữ nguyên nền playback, SourcePack, reader và xuất audiobook hiện có.

- Gemini Native gọi trực tiếp REST `generateContent`, dùng API key mã hóa riêng, model riêng và có thể tải danh sách model hỗ trợ `generateContent`.
- Mỗi truyện có hồ sơ AI riêng: chế độ dịch hoặc cải thiện VietPhrase, provider, endpoint, model, temperature, prompt tùy chỉnh và tùy chọn tự chạy khi mở chương.
- Luồng cải thiện VietPhrase đối chiếu bản gốc với kết quả VietPhrase cục bộ, giới hạn tối đa 30 đề xuất và đưa chúng vào hàng chờ duyệt; ứng dụng không tự ghi `AI_REPLACE` khi chưa được người dùng chấp nhận.
- Room schema 17 và backup format 13 lưu hồ sơ AI theo truyện; API key vẫn nằm ngoài bản sao lưu.
- Phiên bản ứng dụng: `2.2.0-gemini-story-ai-vietphrase`, versionCode 22.

Xem [báo cáo triển khai ba tính năng AI](docs/AI_GEMINI_STORY_VIETPHRASE_REPORT.md) để biết luồng dữ liệu, giới hạn và kết quả kiểm tra.

Mã nguồn đã vượt các gate tĩnh và hồi quy ngoại tuyến liên quan. Build Android đầy đủ, APK/AAB và kiểm thử thiết bị thật vẫn cần môi trường có Gradle dependencies, Android SDK Platform 36 và phần cứng Android.

## Phiên bản 2.0.0, hoàn thành Cột mốc 4 trên nền Cột mốc 5

Bản này hoàn tất TTS, nút tai nghe, AI phân vai và nhạc cảnh mà không làm mất các chức năng audiobook, backup và phát hành của Cột mốc 5.

- Mỗi vai có thể dùng engine TTS, voice, biểu cảm, tốc độ, cao độ, âm lượng và thông số Sonic riêng.
- Bộ biểu cảm tiếng Việt có fallback cục bộ; kế hoạch AI có thể ghi đè biểu cảm khi người dùng đã bật và đồng ý dùng AI online.
- Sonic-style PCM16 thay đổi tốc độ và cao độ độc lập cho cả phát trực tiếp lẫn xuất WAV/M4A/MP3.
- Nút tai nghe Android 13+ giữ receiver, MediaSession, khử sự kiện trùng, một/hai/ba lần bấm và nhấn giữ; có bài soak 20.000 sự kiện.
- AI có hạn mức theo ngày, bộ đếm cục bộ, retry/backoff giới hạn và hỗ trợ `Retry-After`; không lưu prompt hoặc phản hồi vào bảng quota.
- Prefetch kế hoạch AI theo cửa sổ 1–5 chương, cache kế hoạch và fallback sang TTS thường khi AI lỗi hoặc hết hạn mức.
- Nhạc cảnh có playlist tuần tự, shuffle có seed và smart avoid-repeat, lịch sử phát, ước tính loudness cục bộ, target gain, ducking, crossfade và continuity xuyên chương.
- Room schema 13 lưu hồ sơ vai biểu cảm, Sonic, loudness/lịch sử track và quota AI; backup format 10 giữ các thiết lập mới nhưng loại API key, nội dung request AI và trạng thái playback tạm thời.
- Toàn bộ Cột mốc 1–5, 8 SourcePack và 24 fixture vẫn qua gate hồi quy ngoại tuyến.

Tài liệu hiện hành:

- [Biên bản nghiệm thu Cột mốc 4](docs/MILESTONE_4_ACCEPTANCE.md)
- [Tiến độ Cột mốc 4](docs/MILESTONE_4_PROGRESS.md)
- [Validation Cột mốc 4](docs/MILESTONE_4_VALIDATION.md)
- [Kết quả validation](docs/MILESTONE_4_VALIDATION_RESULTS.txt)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Quyền riêng tư và dữ liệu](docs/PRIVACY_AND_DATA.md)

Mã nguồn và gate ngoại tuyến đã đạt. APK/AAB, Compose compiler, Room KAPT, Android Lint và chứng nhận thiết bị Android 13–15 vẫn phải chạy ở môi trường có Android SDK Platform 36 cùng phần cứng tai nghe thật trước khi phát hành công khai.


## Phiên bản 1.9.0, hoàn thành Cột mốc 5

Bản này hoàn thiện pipeline audiobook, sao lưu mở rộng, benchmark và quy trình phát hành. **Ảnh bìa không được triển khai theo yêu cầu**, nhưng không ảnh hưởng WAV/M4A/MP3, metadata văn bản hoặc chapter marker.

- Xuất WAV, AAC-LC/M4A và MP3 thật bằng LAME thuần Java.
- Chọn toàn truyện hoặc khoảng chương; xuất một tệp liền mạch hoặc mỗi chương một tệp.
- MP3 có ID3v2.3, `CHAP/CTOC`, tên truyện, tác giả, album và tên từng chương.
- Nhạc cảnh được giải mã từ URI bằng MediaExtractor/MediaCodec, chuyển sang PCM16 và trộn theo cue với loop, gain và fade.
- Pipeline streaming và checkpoint theo đoạn, có thể tiếp tục sau hủy/lỗi mà không giữ toàn bộ PCM trong RAM.
- Room schema 12 lưu packaging và chapter marker; backup format 9 giữ kế hoạch AI, phân vai và dữ liệu nhạc cảnh.
- Có benchmark cục bộ cho heap/PSS, trạng thái pin và mục lục 10.000 chương.
- CI chạy release gate, test, lint, APK kiểm thử, AAB và migration test; phát hành thật yêu cầu khóa ký từ biến môi trường.

Tài liệu:

- [Biên bản nghiệm thu Cột mốc 5](docs/MILESTONE_5_ACCEPTANCE.md)
- [Tiến độ Cột mốc 5](docs/MILESTONE_5_PROGRESS.md)
- [Validation Cột mốc 5](docs/MILESTONE_5_VALIDATION.md)
- [Kết quả validation](docs/MILESTONE_5_VALIDATION_RESULTS.txt)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Quyền riêng tư và dữ liệu](docs/PRIVACY_AND_DATA.md)
- [Thông báo thành phần bên thứ ba](THIRD_PARTY_NOTICES.md)

Mã nguồn đã vượt các gate ngoại tuyến. APK/AAB, MediaCodec trên thiết bị, encoder LAME thật, TalkBack và bài kiểm tra thời lượng dài vẫn phải được xác nhận ở môi trường có Android SDK Platform 36 và thiết bị Android thật trước khi phát hành công khai.

## Phiên bản 1.7.0, nền móng Cột mốc 4

Bản này bắt đầu hoàn thiện TTS, nút tai nghe, AI phân vai và nhạc cảnh:

- Media button receiver và MediaSession xử lý một/hai/ba lần bấm, nhấn giữ và phím transport chuyên dụng, có khử sự kiện trùng.
- Tạm dừng khi tai nghe bị ngắt, checkpoint Room schema 10 và khôi phục phiên nghe sau process death.
- Tự tạo/cache phân vai AI, cấp giọng ổn định cho nhân vật mới và prefetch kế hoạch chương kế tiếp.
- Prompt nhạc dùng danh mục track thật; mixer hai MediaPlayer hỗ trợ crossfade, ducking và giữ nhạc xuyên chương.
- Backup format 8 giữ toàn bộ tùy chọn tự động hóa mới.

Đây là **bản nền móng Cột mốc 4**. Kiểm thử phần cứng Android 13–15, engine riêng theo vai, biểu cảm/Sonic DSP và loudness/playlist nâng cao vẫn nằm trong lượt hoàn thiện tiếp theo.

Tài liệu:

- [Tiến độ Cột mốc 4](docs/MILESTONE_4_PROGRESS.md)
- [Validation Cột mốc 4](docs/MILESTONE_4_VALIDATION.md)
- [Kết quả validation Cột mốc 4](docs/MILESTONE_4_VALIDATION_RESULTS.txt)

## Phiên bản 1.6.0, hoàn thành Cột mốc 3

Bản này hoàn thiện trải nghiệm đọc, tìm kiếm và ngoại tuyến trên nền Source Platform của Cột mốc 2:

- Tìm kiếm đa nguồn có xếp hạng, bỏ dấu, chịu lỗi gõ gần đúng, gộp biến thể trùng và bốn chế độ sắp xếp.
- Trình đọc cuộn/phân trang tự lưu đoạn đang nhìn thấy, ghi chú theo đoạn, tìm trong chương và điều hướng tùy chọn bằng phím âm lượng.
- Mục lục dùng chỉ mục bất biến, tìm số chương trực tiếp và được kiểm tra với 10.000 chương.
- Hàng đợi tải bền vững trong Room/WorkManager, có pause/resume/retry/cancel, điều kiện Wi-Fi/sạc, kiểm tra dung lượng và retry riêng từng chương lỗi.
- Room schema 9 lưu ghi chú và lỗi tải theo chương; backup format 7 giữ cả dữ liệu và thiết lập đọc mới.
- Nhập TXT/EPUB/DOCX cùng MOBI/PRC/AZW/AZW3 không DRM, gồm PalmDOC raw/LZ77, MOBI 8/KF8-only và HUFF/CDIC có giới hạn.
- DRM tiếp tục bị từ chối rõ ràng.

Tài liệu:

- [Tiến độ và nghiệm thu Cột mốc 3](docs/MILESTONE_3_PROGRESS.md)
- [Validation Cột mốc 3](docs/MILESTONE_3_VALIDATION.md)
- [Kết quả validation](docs/MILESTONE_3_VALIDATION_RESULTS.txt)
- [Biên bản nghiệm thu](docs/MILESTONE_3_ACCEPTANCE.md)
- [Thông báo thành phần bên thứ ba](THIRD_PARTY_NOTICES.md)

Mã nguồn đã vượt toàn bộ gate ngoại tuyến và hồi quy Cột mốc 1–2. APK/AAB vẫn phải được build ở môi trường có Android SDK Platform 36 và kiểm thử trên thiết bị thật trước khi phát hành công khai.

## Phiên bản 1.5.0, nền móng Cột mốc 3

Bản này bắt đầu hoàn thiện trải nghiệm đọc, tìm kiếm và ngoại tuyến trên nền Cột mốc 2:

- Tìm kiếm đa nguồn có xếp hạng theo truy vấn, bỏ dấu, độ phủ từ, lỗi gõ gần đúng và gộp tên truyện trùng.
- Sắp xếp kết quả theo liên quan, tên, tác giả hoặc nguồn.
- Trình đọc có chế độ cuộn và phân trang theo đoạn; lề ngang và khoảng cách đoạn được lưu trong DataStore.
- Chạm đoạn hoặc chuyển trang cập nhật cùng paragraph index mà lịch sử, bookmark và TTS sử dụng.
- Hàng đợi tải Room schema 8 lưu loại yêu cầu, khoảng chương, điều kiện Wi-Fi/sạc, chương đang xử lý và số lần thử lại.
- Có tải một chương, khoảng chương, phần chưa đọc hoặc toàn bộ truyện; hỗ trợ tạm dừng, tiếp tục, thử lại và hủy.
- Nhập PalmDOC/MOBI/PRC/AZW/AZW3 không mã hóa, gồm nén thô và PalmDOC LZ77; từ chối DRM và HUFF/CDIC rõ ràng.

Đây là **bản nền móng Cột mốc 3**, chưa phải bản hoàn tất. Các phần còn lại gồm tự ghi vị trí cuộn nhìn thấy, tối ưu mục lục 5.000–10.000 chương, storage preflight, retry theo từng chương và kiểm thử APK trên thiết bị.

Tài liệu:

- [Tiến độ Cột mốc 3](docs/MILESTONE_3_PROGRESS.md)
- [Validation Cột mốc 3](docs/MILESTONE_3_VALIDATION.md)
- [Trạng thái Cột mốc 2](docs/MILESTONE_2_PROGRESS.md)

Mã nguồn đã vượt các gate ngoại tuyến. APK/AAB vẫn phải được build ở môi trường có Android SDK Platform 36.


## Phiên bản 1.4.0, hoàn thành Cột mốc 2

Bản này hoàn thiện nền tảng nguồn truyện và tiện ích cho ứng dụng Android Kotlin độc lập:

- SourcePack và repository ký số, update/rollback không cần phát hành APK.
- Network, cookie, browser, storage, crypto và WebSocket capability brokers.
- WebView không có JavaScript bridge; navigation, redirect, subresource và Service Worker đều qua allowlist.
- Cookie partition theo nguồn và mã hóa bằng Android Keystore.
- Trust-key enrollment, revoke và signed key rotation.
- Trace explorer, selector inspector và fixture replay ngoại tuyến.
- Rhino `VBOOK_JS_COMPAT` sandbox, không cho script truy cập Java/Android API trực tiếp.
- Tám gói tích hợp ký số: demo, Truyện Full, Truyện CV, Truyện Com, Truyện YY, WikiDich, Sáng Tác Việt và Wattpad.
- 24 fixture replay cho search/detail/TOC/chapter của sáu nguồn declarative.

Tài liệu:

- [Trạng thái Cột mốc 2](docs/MILESTONE_2_PROGRESS.md)
- [Validation Cột mốc 2](docs/MILESTONE_2_VALIDATION.md)
- [SourcePack v2](docs/SOURCE_PACK_V2_FORMAT.md)
- [Source Repository v1](docs/SOURCE_REPOSITORY_V1_FORMAT.md)
- [Roadmap/trạng thái Source Platform 2](docs/SOURCE_PLATFORM_2_ROADMAP.md)

Mã nguồn đã vượt các gate ngoại tuyến. APK/AAB và kiểm thử WebView/Keystore trên thiết bị vẫn phải chạy ở môi trường có Android SDK Platform 36.

## Phiên bản 1.2.0, nền móng Cột mốc 1

Bản này bắt đầu biến source archive thành một ứng dụng Android có hợp đồng build và nâng cấp dữ liệu rõ ràng:

- Gradle Wrapper xác minh SHA-256 ở mỗi lần chạy và tự tải JAR chính thức khi thiếu hoặc sai, không còn yêu cầu tải toàn bộ Gradle chỉ để tạo wrapper.
- Room schema nâng lên version 7; sửa bảng `download_jobs`, chuẩn hóa default SQL và giữ nguyên dữ liệu version 6.
- Thêm migration instrumentation test cho cả `5 → 6` và `6 → 7`.
- Bỏ phụ thuộc không cần thiết vào SDK minor 36.1; dùng Android SDK Platform 36.
- Thêm CI build, unit test, lint, debug APK, Android-test APK, release bundle và migration test trên emulator API 33.
- Lint trở thành cổng chặn build; schema Room được xuất vào `app/schemas`.

Tiến độ chi tiết: [Cột mốc 1](docs/MILESTONE_1_PROGRESS.md).

Project Android Studio này tái tạo các luồng chính của gói XPK bằng **Kotlin, Jetpack Compose, Room, WorkManager và Android TextToSpeech**. Project không chứa Lua, LuaJava, AndroLua, DEX tải động hoặc thư viện native sao chép từ XPK.

## Phiên bản 1.1.0, Source Platform 2 foundation

Bản này bắt đầu thay registry nguồn đóng bằng một nền tảng SourcePack đã ký và có khả năng rollback. Đây là tích hợp chạy thật, không phải interface giữ chỗ:

- Năm module JVM độc lập: `source-api`, `source-package`, `source-store`, `source-runtime`, `source-diagnostics`.
- Cài gói `.ntsource` qua Storage Access Framework.
- ZIP bounded, canonical path, chống traversal/collision/compression bomb.
- `FILES.sha256` bao phủ toàn bộ payload và chữ ký ECDSA P-256/Ed25519.
- Manifest strict, từ chối field lạ và capability ngoài schema.
- Fixture self-test bắt buộc trước khi người dùng được phép cài.
- Hiển thị permission diff trước khi xác nhận cập nhật.
- Staging, atomic activation, enable/disable, giữ nhiều phiên bản và rollback.
- Kiểm tra payload tree hash mỗi lần nạp phiên bản đã cài.
- Declarative runtime có instruction, memory, timeout và output budget.
- Diagnostics có trace, severity, category, redaction và xuất JSON.
- Nguồn mẫu tích hợp sẵn chạy qua SourcePack; adapter hard-code cũ chỉ còn fallback.

Tài liệu:

- [Nền móng Source Platform 2](docs/SOURCE_PLATFORM_2_FOUNDATION.md)
- [Định dạng SourcePack v2](docs/SOURCE_PACK_V2_FORMAT.md)
- [Roadmap network/browser/vBook](docs/SOURCE_PLATFORM_2_ROADMAP.md)
- [Schema JSON](docs/schemas/source-pack-v2.schema.json)
- [SourcePack mẫu](examples/sourcepack-demo/README.md)

**Chưa hoàn tất trong 1.1.0:** repository cập nhật tự động, network capability broker, browser profile/capture, selector inspector/replay và runtime tương thích vBook. Các phần đó không được giả lập hoặc tuyên bố sẵn sàng trước khi có sandbox và diagnostics tương ứng.


## Phiên bản 1.0.0, lõi P4 được giữ nguyên bên dưới

Bản này chỉ bổ sung bốn nhóm người dùng yêu cầu:

1. VietPhrase chạy cục bộ.
2. Dịch chương bằng AI online có consent và API key mã hóa.
3. Phân vai AI cho từng đoạn.
4. Nhạc cảnh AI chọn từ thư viện nhạc cục bộ.

Không triển khai roleplay đầy đủ, AI offline hoặc hệ thống extension động trong bản này.

## VietPhrase cục bộ

- Rule engine deterministic, ưu tiên priority rồi cụm dài hơn.
- So khớp không phân biệt hoa thường, có kiểm tra ranh giới từ.
- Replacement không được đưa trở lại bộ so khớp, tránh cascade và vòng lặp.
- Bản gốc trong Room không bị ghi đè.
- Có thể thêm, bật/tắt và xóa từng quy tắc.
- Nhập tối đa 100.000 quy tắc từ UTF-8 TSV hoặc dạng `nguồn=đích` / `nguồn=>đích`.
- Tệp nhập tối đa 16 MiB, cụm nguồn tối đa 200 ký tự và cụm đích tối đa 400 ký tự.
- Xuất lại TSV có escape cho tab, xuống dòng và dấu gạch chéo.
- VietPhrase được lưu trong Room và có trong backup ZIP format 6.

## AI online có consent và khóa mã hóa

AI online mặc định không hoạt động. Request chỉ được phép khi đồng thời có:

- Người dùng bật công tắc đồng ý gửi nội dung chương.
- Người dùng bật AI online.
- Endpoint HTTPS hợp lệ.
- Tên model không rỗng.
- API key đã được lưu.

API key:

- Được mã hóa AES-GCM.
- Khóa AES nằm trong Android Keystore.
- Ciphertext được ràng buộc bằng AAD.
- Không xuất trong backup ZIP.
- Bị loại khỏi Android Auto Backup và device transfer.
- Ô nhập khóa dùng password masking.

Endpoint:

- Chỉ chấp nhận HTTPS chat-completions endpoint.
- Chặn localhost, `.local`, loopback, link-local, private/site-local và multicast.
- DNS được kiểm tra lại ngay trong OkHttp để giảm rủi ro DNS rebinding.
- Không tự đi theo redirect.
- Response bị giới hạn 2.000.000 ký tự ngay trong lúc đọc stream.
- Không gửi cookie nguồn truyện hoặc mật khẩu tới endpoint AI.

Provider hiện dùng giao thức **OpenAI-compatible chat completions**. Người dùng tự chịu trách nhiệm với endpoint, model, quota và chính sách dữ liệu của nhà cung cấp đã chọn.

## Dịch chương

- Chỉ gửi chương đang mở khi người dùng bấm **DỊCH AI**.
- Mỗi đoạn có marker `[[P:n]]`; phản hồi thiếu đoạn hoặc sai cấu trúc bị từ chối.
- Kết quả giữ đúng số đoạn và thứ tự để tiến độ đọc, bookmark, phân vai và TTS tiếp tục ổn định.
- Cache bản dịch được ràng buộc với nội dung gốc, endpoint, model và yêu cầu dịch.
- Thay model hoặc prompt sẽ không dùng nhầm cache cũ.
- Khi đang xem bản dịch, nút chuyển thành **DỊCH LẠI** để buộc tạo kết quả mới.
- Có thể chuyển tức thời giữa Bản gốc, VietPhrase và Bản dịch AI.

## Phân vai AI

- AI chỉ trả protocol dòng `ROLE` và `ASSIGN`; JSON hoặc văn bản tự do không được dùng trực tiếp để điều khiển TTS.
- Vai Người kể chuyện luôn tồn tại làm fallback.
- Chỉ số đoạn, tên vai, độ tin cậy, số vai và số assignment đều có giới hạn.
- Assignment trùng đoạn được loại bỏ deterministically.
- Vai/giọng người dùng đã chỉnh trước đó được giữ nguyên; AI chỉ hợp nhất alias và thêm vai còn thiếu.
- Assignment được lưu theo chapter ID và paragraph index.
- TTS trực tiếp và xuất WAV/M4A đều ưu tiên assignment AI, sau đó mới dùng prefix thủ công và Người kể chuyện.
- Plan cũ tự vô hiệu nếu hash nội dung chương không còn khớp.

## Nhạc cảnh

- Người dùng chọn tệp nhạc cục bộ bằng Storage Access Framework.
- Có thể sửa tên và tag của từng track để AI chọn chính xác hơn.
- Chỉ ID, tên và tag được gửi trong request lập nhạc; tệp âm thanh không được tải lên.
- AI chỉ được chọn track ID có trong danh sách được phép.
- Tối đa 12 cue mỗi chương; cue được sắp xếp, loại trùng và giới hạn volume.
- TTS service đổi nhạc khi đi qua paragraph index của cue.
- Nhạc được loop và duck khi TTS nói.
- Trước cue đầu tiên hoặc khi track không còn hợp lệ, nhạc cảnh được dừng thay vì tiếp tục nhạc của chương trước.
- Plan cũ tự vô hiệu khi nội dung chương thay đổi.
- URI nhạc cục bộ không nằm trong backup ZIP; Android platform backup cũng không mang DataStore hoặc secret preferences sang thiết bị khác.

## Nền tảng đã có từ P1 đến P3

- Reader Compose có theme Hệ thống/Sáng/Tối/Giấy, cỡ chữ, giãn dòng, giữ màn hình sáng, tìm và sao chép.
- Tìm đa nguồn, tìm chương, nạp toàn bộ mục lục và tải khoảng chương.
- Theo dõi số chương mới chưa xem.
- Adapter Kotlin cho Truyện Full, TruyenCV, Truyện Com, TruyenYY, WikiDich và Sáng Tác Việt.
- Cookie phiên nguồn mã hóa và WebView đăng nhập cô lập.
- TTS nền, MediaSession, audio focus, wake lock, prefetch, tự chuyển chương và hẹn giờ ngủ.
- Chọn TTS engine/voice, profile theo truyện và nhiều vai.
- Nhạc nền local có ducking.
- Xuất WAV và M4A/AAC bằng foreground WorkManager.
- TXT, EPUB và DOCX importer có giới hạn chống ZIP bomb/path traversal.
- Backup ZIP có SHA-256 và restore merge-only.

## Dữ liệu

- Room schema: **8**.
- Migration: `1 → 2 → 3 → 4 → 5 → 6 → 7 → 8`.
- Backup format: **6**, vẫn đọc format 1 đến 5.
- Bảng mới của P4:
  - `viet_phrase_rules`
  - `chapter_transforms`
  - `chapter_voice_assignments`
  - `scene_music_tracks`
  - `scene_music_cues`

Không backup:

- API key.
- Consent AI hoặc trạng thái bật AI.
- Cookie đăng nhập nguồn.
- URI nhạc cục bộ.
- Cache bản dịch, assignment và cue có thể tái tạo.

## Build

Yêu cầu:

- Android Studio có Android SDK Platform 36.
- JDK 17.
- Kết nối mạng cho lần Gradle sync đầu.

Windows, chạy toàn bộ cổng nền móng Cột mốc 3:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone3.ps1
```

Hoặc chạy từng tác vụ:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

APK debug dự kiến:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Hướng dẫn ký release: [docs/BUILD_WINDOWS.md](docs/BUILD_WINDOWS.md).

## Release gate không cần Android SDK

```bash
python scripts/check_clean_rewrite.py
python scripts/check_truyenfull_fixtures.py
python scripts/check_truyencv_fixtures.py
python scripts/check_truyencom_fixtures.py
python scripts/check_truyenyy_fixtures.py
python scripts/check_kotlin_static.py
python scripts/check_audio_export_static.py
python scripts/check_android_wiring_static.py
python scripts/check_wave_assembler.py
python scripts/check_p1_ui_static.py
python scripts/check_p1_features.py
python scripts/check_p2_sources.py
python scripts/check_p2_network_static.py
python scripts/check_p2_health_static.py
python scripts/check_p2_android_wiring.py
python scripts/check_p2_ui_static.py
python scripts/check_p3_features.py
python scripts/check_p4_features.py
python scripts/check_p4_network_static.py
python scripts/check_p4_android_security.py
python scripts/check_p4_transfer_static.py
python scripts/check_source_platform_foundation.py
python scripts/check_source_platform_android_static.py
python scripts/check_milestone1_foundation.py
python scripts/validate_release.py
```

Các gate dùng Kotlin/JVM harness và Android stubs. Chúng không thay thế Gradle, Room KAPT, Compose compiler, Lint hoặc kiểm thử thiết bị.

## Chưa được xác nhận

Môi trường tạo project không có Android SDK và dependency Gradle đầy đủ, nên chưa chạy được:

- Gradle Sync trong môi trường hiện tại.
- Room KAPT và Compose compiler thật trong môi trường hiện tại.
- `testDebugUnitTest`, `lintDebug`, `assembleDebug` và `bundleRelease` trong môi trường hiện tại.
- Request AI thật tới provider của người dùng.
- Android Keystore sau restore/reset khóa.
- TTS, nhạc cảnh, MediaCodec và process death trên thiết bị thật.

Chi tiết parity: [docs/PARITY_AUDIT_1.0.md](docs/PARITY_AUDIT_1.0.md).

## Milestone 0 build gate

Mọi build dùng để nghiệm thu phải chạy qua cùng một cổng:

```bash
./scripts/m0_gate.sh
```

Trên Windows:

```powershell
.\scripts\m0_gate.ps1
```

Cổng sẽ dừng ngay nếu thiếu JDK 17, Android SDK 36 hoặc package build bắt buộc. Tiêu chí và trạng thái nằm tại:

- `docs/MILESTONE_0_ACCEPTANCE.md`
- `docs/MILESTONE_0_STATUS.md`
- `MILESTONE_0_REPORT.md`
