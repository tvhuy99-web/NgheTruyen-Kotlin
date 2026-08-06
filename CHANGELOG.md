## 2.8.0-ai-narration-priority2-complete (2026-08-06)

- Chặn playback, notification và media-button trong khi auto-translation đang chuẩn bị; khi lỗi phải thử lại hoặc chọn bản gốc rõ ràng.
- Hợp nhất phân vai, biểu cảm và nhạc cảnh vào một yêu cầu AI, có ngữ cảnh phần kết chương trước, cue trước và track đang tiếp nối.
- Thêm `NarrationPlanCoordinator` cache/persist kế hoạch giọng và nhạc đồng bộ cho playback, prefetch và audio export.
- Hoàn thiện editor hồ sơ vai: engine, voice, ngôn ngữ, rate, pitch, volume, expression, Sonic, bật/tắt, sửa tên và nghe thử.
- Bổ sung compiler gate riêng cho coordinator cùng executable gate cho giao thức ROLE/ASSIGN/CUE hợp nhất.
- Giữ Room schema 18 và backup format 15; nâng versionCode lên 28.

## 2.7.0-source-fidelity-priority1-complete (2026-08-06)

- Hoàn tất Priority 1 bằng SourcePack hybrid full-fidelity cho sáu nguồn website: package/quyền/cập nhật thuộc SourcePack, logic live thuộc adapter Kotlin chuyên biệt đã kiểm thử.
- Thêm action `LATEST_CHAPTER`, fixture đầy đủ cho home, genre, search, suggestions, detail, latest, TOC, TOC pages và chapter.
- Nâng Wattpad lên chín fixture replay chạy trực tiếp mã JavaScript bằng Node.
- Source self-test dùng fixture input thực thay vì chuỗi đường dẫn và chạy replay cho cả declarative, vBook và Lua.
- Xây lại bảy `.ntsource` tích hợp, ký bằng trust root Priority 1 P-256 v2; khóa riêng không nằm trong gói nguồn.
- Thêm gate hoàn tất Priority 1 kiểm tra metadata, fixture, parity asset và chữ ký.

## 2.6.0-source-fidelity-priority1 (2026-08-06)

- Sửa registry nguồn: adapter Kotlin chuyên biệt không còn bị SourcePack selector chung cùng ID che khuất.
- Thêm chứng nhận full parity cho SourcePack; metadata tự khai báo không đủ để vượt adapter tích hợp nếu thiếu action hoặc fixture bắt buộc.
- Bổ sung API trang chủ và gợi ý tìm kiếm xuyên suốt StorySource, SourcePack, vBook, health check và giao diện Khám phá.
- Sửa phân trang mục lục SourcePack/vBook bằng URL hoặc continuation token thật, giữ index chương liên tục.
- Bảo toàn thứ tự cập nhật/hot của website trên trang chủ và danh mục; chỉ sắp xếp lại khi người dùng chọn chế độ sort khác.
- Bổ sung `latestChapter` riêng cho TruyenCV và route home tường minh cho sáu adapter Kotlin.
- Mở rộng fixture SourcePack từ 24 lên 36 ca với home/genre, thêm gate coverage/registry/runtime và live source smoke workflow.
- Giữ Room schema 18 và backup format 15; không thay đổi dữ liệu người dùng.

# Changelog

## 2.5.0-xpk-max-compatibility (2026-08-06)

- Nâng Native Source API 2 từ importer một tệp thành package nhiều tệp: giữ module/tài nguyên, `require()` nội bộ, `context.resource` và ngân sách lệnh, thời gian, đầu ra cùng bộ nhớ xấp xỉ.
- Mở rộng vBook theo hành vi công cụ XPK: Response code/data, Document, storage key/length/clear, localConfig/localCookie, Script, Console, Graphics, WebSocket, Crypto/CryptoJS, browser dialog/session/user-agent/blocking và `Qt.translate` qua AI đã cấu hình.
- Nâng trình duyệt chẩn đoán với chế độ miền/tài nguyên, chính sách dialog, đổi user-agent, kiểm tra storage, xóa cookie và xuất request metadata đã khử dữ liệu nhạy cảm.
- Thêm fallback bình luận theo ba tầng: dữ liệu nhúng, HTML trực tiếp và WebView động cho nguồn chưa khai báo action bình luận.
- Nâng backup lên format 15, kèm checksum cho SourcePack/extension, storage nguồn không nhạy cảm và tệp nhạc cảnh vật lý; tiếp tục loại cookie, credential, token và secret.
- Giữ Room schema 18; nâng versionCode lên 25.

## 2.4.0-native-lua-vbook-diagnostic-comments (2026-08-06)

- Thêm module LuaJ sandbox để nhập trực tiếp `.lua` hoặc ZIP Native Source API 2, dựng adapter SourcePack/vBook và chạy pure-Lua hooks qua cầu JSON có instruction/time/output budget.
- Mở rộng vBook host API với Http/Html, DOM, storage aliases, Engine/Browser, cookie/session, request metadata, crypto, WebSocket và logging.
- Thêm trình duyệt chẩn đoán đăng nhập theo nguồn: URL/back/forward/reload, JS/cookie/DOM/request probes, lưu phiên mã hóa, log Basic/Verbose, copy/export/clear và khử dữ liệu nhạy cảm.
- Thêm capability bình luận NONE/EMBEDDED/PAGED/DYNAMIC_BROWSER, fixture count, health-check comments và parser payload lồng/phân trang rộng hơn.
- Giữ Room schema 18 và backup format 14; nâng versionCode lên 24.

## 2.3.0-voicecast-vietphrase-online-selective-backup (2026-08-06)

- Thêm cấu hình phân vai AI nâng cao theo truyện: prompt, ghi chú, chỉ lời thoại, người kể chuyện ổn định, prompt biểu cảm và giới hạn tốc độ/cao độ/âm lượng.
- Áp dụng điều chỉnh biểu cảm AI vào playback nền và pipeline xuất WAV/M4A/MP3, có clamp theo hồ sơ truyện.
- Thêm khám phá và cập nhật VietPhrase trực tuyến từ host HTTPS tin cậy, thử nhiều URL ứng viên, kiểm tra redirect, kích thước, nội dung, số mục và commit nguyên tử có snapshot rollback.
- Thêm sao lưu/khôi phục chọn lọc cho cài đặt, thư viện, dữ liệu đọc, AI/giọng, VietPhrase và nhạc cảnh; manifest ghi rõ thành phần trong gói.
- Nâng Room schema từ 17 lên 18 và backup format từ 13 lên 14; giữ tương thích dữ liệu cũ và tiếp tục loại API key khỏi backup.


## 2.2.0-gemini-story-ai-vietphrase (2026-08-06)

- Thêm Gemini Native qua REST `generateContent`, API key riêng, model riêng và tải danh sách model hỗ trợ.
- Thêm hồ sơ AI theo từng truyện: provider, endpoint, model, temperature, prompt dịch, prompt cải thiện và tự chạy khi mở chương.
- Thêm luồng AI đối chiếu bản gốc với bản VietPhrase, kiểm tra kết quả JSON và đưa đề xuất vào hàng chờ duyệt `AI_REPLACE`.
- Nâng Room schema từ 16 lên 17 và backup format từ 12 lên 13 để lưu hồ sơ AI theo truyện.
- Giữ API key ngoài backup và mã hóa riêng theo provider bằng Android Keystore.

## Mốc 2 parity WIP

- Thêm bình luận native từ SourcePack/vBook với tải lười, tải lại và chống kết quả cũ ghi đè.
- Chuẩn hóa payload bình luận và giới hạn tài nguyên trước khi hiển thị.
- Tích hợp gate thực thi `check_milestone2_comments.py` vào cổng Mốc 2.

## 2.1.0-milestone-5-playback-complete (2026-08-06)

- Hoàn tất Mốc 5 playback/TTS ở cấp mã nguồn, giữ nguyên các phần Reader, Source Platform và VietPhrase đã ổn định.
- Thêm recovery hữu hạn cho lỗi submit, Sonic, engine hiện tại và engine mặc định; bổ sung watchdog khởi tạo/chunk TTS.
- Sửa checkpoint khi mất audio focus tạm thời, lỗi submit và process recreation để không đọc lặp hoặc khôi phục phiên đã thất bại.
- Thêm sleep timer bền vững qua service recreation, reboot và package replacement.
- Cho phép cấu hình một/hai/ba lần bấm và nhấn giữ tai nghe; transport key chuyên dụng giữ hành vi chuẩn.
- Thêm cache TTS/Sonic LRU có checksum, atomic commit, chống tệp bị sửa và giới hạn 8–512 MiB.
- Thêm chuẩn hóa âm lượng PCM có giới hạn, fallback engine/voice và chẩn đoán không chứa nội dung chương.
- Nâng Room schema lên 16 với speech chunk checkpoint và hàng đợi phát bền vững tối đa năm chương đã cache.
- Nâng backup format lên 12 cho thiết lập media/cache/normalization; hàng đợi phát tạm thời không được đưa vào backup.
- Thêm gate runtime 100.000 sự kiện, cache tamper/LRU, migration 14→16 và khóa bằng chứng SHA-256.


## Mốc 1 parity, reader-core work in progress (2026-08-05)

- Tách đoạn hiển thị khỏi speech chunk để TTS không làm lệch tiến độ, bookmark, ghi chú, phân vai hoặc cue nhạc.
- Chuẩn hóa chương nhất quán giữa cache, UI, playback service và chuyển chương tự động.
- Sửa điều hướng trước/sau theo vị trí mục lục thay vì giả định index nguồn là offset danh sách.
- Hủy job tải truyện/chương cũ khi người dùng chuyển màn hình, ngăn kết quả mạng chậm ghi đè nội dung mới.
- Thêm executable gate cho đoạn 7.200 ký tự và mục lục có index không liên tục.
- Đây là lát cắt đang triển khai; chưa phải bản hoàn tất Mốc 1 và chưa có APK/AAB được chứng nhận.

## 2.0.0-milestone-4-complete-integrated

- Hoàn tất Cột mốc 4 trên nền Cột mốc 5, giữ nguyên pipeline audiobook, backup, SourcePack và release tooling đã có.
- Nâng Room schema lên 13; migration `12 → 13` giữ dữ liệu và bổ sung engine/biểu cảm/Sonic theo vai, loudness/lịch sử track và quota AI theo ngày.
- Mỗi vai có engine TTS, voice, biểu cảm, strength, rate, pitch, volume, Sonic speed và Sonic pitch riêng; engine lỗi sẽ fallback an toàn.
- Thêm biểu cảm tiếng Việt cục bộ và AI expression override, không bắt buộc kết nối mạng.
- Thêm Sonic-style PCM16 bounded cho phát trực tiếp và xuất audiobook, thay đổi tốc độ/cao độ độc lập.
- Mở rộng prefetch kế hoạch AI 1–5 chương; thêm quota request/ký tự theo ngày, bộ đếm cục bộ, retry/backoff và `Retry-After` có giới hạn.
- Thêm playlist nhạc tuần tự, shuffle có seed và smart avoid-repeat, lịch sử phát, loudness estimate, target gain, ducking, crossfade và continuity xuyên chương.
- Bổ sung UI cho quota AI, Sonic, playlist/loudness và hồ sơ vai; backup nâng lên format 10.
- Thêm unit soak 20.000 sự kiện media button cùng smoke test expression, Sonic, loudness, playlist và migration schema 13.
- Đưa complete gate Cột mốc 4 vào release validation, build script và CI; nâng versionCode lên 20.


## 1.9.0-milestone-5-complete

- Hoàn thiện Cột mốc 5, giữ ảnh bìa ngoài phạm vi theo yêu cầu.
- Nâng Room schema lên 12; migration `11 → 12` giữ job xuất cũ và bổ sung packaging cùng chapter marker.
- Hoàn thiện hộp thoại xuất sách nói: WAV/M4A/MP3, toàn truyện hoặc khoảng chương, một tệp hoặc mỗi chương một tệp.
- Nối `SceneMusicCueEntity` và thư viện track vào worker; decode URI qua MediaExtractor/MediaCodec, chuẩn hóa PCM16 và trộn streaming bằng fade/loop/gain có giới hạn bộ nhớ.
- Ghi chapter marker `CHAP/CTOC` có thứ tự trong MP3; thêm unit test kiểm tra mục lục và thứ tự chương.
- Hoàn thiện checkpoint/resume theo đoạn, fingerprint bao phủ nội dung, giọng, vai, từ điển, packaging, marker và kế hoạch nhạc.
- Nâng backup format lên 9, sao lưu kế hoạch chuyển đổi, phân vai, track và cue nhạc cảnh; track khôi phục trên thiết bị khác mặc định bị tắt vì quyền URI không thể chuyển máy.
- Thêm benchmark cục bộ cho RAM, heap, pin và mục lục 10.000 chương; hiển thị báo cáo trong màn hình Cá nhân.
- Bổ sung release signing từ biến môi trường, task `verifyReleaseSigning`, release checklist và build/CI complete gates.
- Nâng versionCode lên 19 và versionName thành `1.9.0-milestone-5-complete`.

## 1.8.0-milestone-5-foundation

- Bắt đầu Cột mốc 5 với pipeline audiobook có checkpoint bền vững trong vùng lưu trữ riêng của ứng dụng.
- Nâng Room schema lên 11 và thêm migration `10 → 11` giữ tiến độ xuất cũ.
- Thêm MP3 thật bằng `co.ntbl:lame:1.0.0`, metadata ID3v2.3 cho tên chương/truyện và tác giả.
- Giữ WAV và AAC-LC/M4A; bổ sung MP3 vào document picker, màn hình truyện, trình đọc và danh sách tác vụ.
- Thêm fingerprint nội dung, thiết lập TTS, vai giọng và từ điển để checkpoint cũ không bị dùng nhầm.
- Giữ các đoạn PCM16 hợp lệ sau hủy/lỗi; thêm thao tác tiếp tục từ checkpoint.
- Thêm model xuất theo khoảng chương và metadata tác giả/phase cho job.
- Thêm bộ trộn nhạc cảnh PCM16 streaming, looping, gain và fade có giới hạn 64 MiB mỗi track đã giải mã.
- Thêm unit test Gradle tạo MP3 thật và kiểm tra ID3/frame sync; thêm pure Kotlin mixer/ID3 smoke và Java API compatibility gate.
- Thêm build script/CI Cột mốc 5 và thông báo giấy phép LGPL cho java-lame.
- Nâng versionCode lên 18 và versionName thành `1.8.0-milestone-5-foundation`.

## 1.7.0-milestone-4-foundation

- Nâng Room schema lên 10, thêm playback checkpoint và migration `9 → 10`.
- Thêm MediaButton receiver, API 31+ broadcast receiver registration, callback MediaSession và bộ khử sự kiện trùng.
- Thêm một/hai/ba lần bấm, nhấn giữ và transport key mapping.
- Tạm dừng khi tai nghe bị ngắt và khôi phục playback theo trạng thái trước process death.
- Thêm `NarrationPlanCoordinator` tự tạo/cache phân vai và nhạc cảnh, cùng prefetch chương kế tiếp.
- Sửa prompt nhạc để truyền danh mục track thật và sửa fingerprint kế hoạch nhạc khi phát.
- Thêm mixer nhạc cảnh hai MediaPlayer với crossfade, ducking và continuity xuyên chương.
- Thêm màn hình cấu hình tự động hóa; backup format 8 giữ các cài đặt mới.
- Thêm pure media-button smoke, migration smoke, CI và build script Cột mốc 4.
- Nâng versionCode lên 17 và versionName thành `1.7.0-milestone-4-foundation`.

## 1.6.0-milestone-3-complete

- Nâng Room schema lên 9, thêm ghi chú theo đoạn và lỗi tải riêng từng chương, kèm migration `8 → 9` giữ dữ liệu.
- Nâng backup format lên 7, sao lưu/khôi phục ghi chú và thiết lập điều hướng phím âm lượng, vẫn đọc format cũ.
- Tự ghi paragraph index khi cuộn dừng; tránh phát lại TTS khi đoạn hiện tại không đổi.
- Thêm tạo, sửa, xóa và mở ghi chú từ trình đọc hoặc tab Ghi chú trong Tủ truyện.
- Thêm điều hướng đoạn bằng phím âm lượng có opt-in và lưu DataStore.
- Thêm `ChapterCatalogIndex`, tìm số chương trực tiếp, normalize một lần và smoke test mục lục 10.000 chương.
- Thêm kiểm tra dung lượng trước khi xếp hàng và trước mỗi batch WorkManager.
- Lưu lỗi theo từng chương, hiển thị lỗi trong Tủ truyện và cho retry đúng chương mà không tải lại cả job.
- Bổ sung MOBI 8/KF8-only và HUFF/CDIC bounded; giữ từ chối DRM.
- Thêm notice LGPL/libmobi, Kindle smoke gate, download compiler gate và đưa các gate vào CI/build scripts.
- Sửa lỗi ngoặc UI tải/worker và lỗi migration helper có thể làm `ALTER TABLE` trùng.
- Nâng versionCode lên 16 và versionName thành `1.6.0-milestone-3-complete`.

## 1.5.0-milestone-3-foundation

- Nâng Room schema lên version 8 và thêm migration `7 → 8` giữ tiến độ tải cũ.
- Lưu bền vững loại yêu cầu tải, khoảng chương, Wi-Fi/sạc, chương hiện tại, số lần retry và thời điểm yêu cầu.
- Thêm tải một chương, khoảng chương, các chương chưa đọc và toàn bộ truyện; thêm pause/resume/retry/cancel.
- Giữ cùng job identity qua các lượt WorkManager và continuation.
- Thêm search rank theo truy vấn, normalize tiếng Việt, typo tolerance, token coverage, dedupe và bốn chế độ sort.
- Thêm reader cuộn/phân trang, lề ngang, khoảng cách đoạn và cập nhật paragraph index dùng chung với TTS/lịch sử.
- Thêm parser PalmDOC/MOBI/PRC/AZW/AZW3 không mã hóa, raw/PalmDOC LZ77 và nhận diện UTF-8 nghiêm ngặt.
- Từ chối DRM và HUFF/CDIC bằng lỗi rõ ràng.
- Sửa đường migration nhiều bước để `5 → 8` không tạo cột download schema 8 quá sớm rồi ALTER trùng.
- Thêm pure Kotlin smoke, migration smoke, Compose stub compile và script build Cột mốc 3.
- Nâng versionCode lên 15 và versionName thành `1.5.0-milestone-3-foundation`.

## 1.4.0-milestone-2-complete

- Hoàn thiện cookie jar theo host/domain/path/secure/expiry và persistence Android Keystore AES-GCM.
- Thêm browser broker WebView, cookie bridge, DOM snapshot, selector wait/click/input, request metadata và renderer recovery.
- Chặn navigation, redirects, subresources và Service Worker ngoài allowlist; không dùng `addJavascriptInterface`.
- Thêm storage, crypto và WebSocket capability brokers.
- Thêm trust-key enrollment, revoke, fingerprint và signed key rotation.
- Thêm trace explorer, selector inspector và cảnh báo quyền trước cài đặt.
- Thêm module `source-vbook` với Rhino interpreter sandbox và vBook ZIP/plugin importer.
- Thêm declarative HTML operations và port sáu nguồn website thành SourcePack độc lập.
- Đóng gói Wattpad qua `VBOOK_JS_COMPAT`, ưu tiên `homecontent` và `genrecontent`.
- Bootstrap tám SourcePack ký số; ký lại bảy gói Cột mốc 2 bằng trust root riêng.
- Chuẩn hóa selector CSS và sửa fixture Sáng Tác Việt.
- Thêm complete gate xác minh 8 chữ ký/hash và 24 fixture replay.
- Nâng versionCode lên 14 và versionName thành `1.4.0-milestone-2-complete`.

## 1.3.0-milestone-2-network-repository

- Thêm module `source-network` và hợp đồng request/response có kiểu.
- Thêm broker OkHttp thực thi capability: HTTPS/origin/redirect/method/header, public DNS, rate/concurrency, body quota và timeout theo deadline action.
- Sửa redirect loop để tương thích Kotlin compiler ổn định, không dùng `continue` trong inline lambda.
- Chặn IPv4-mapped IPv6 trỏ vào loopback/private range.
- Thêm declarative operations `template`, `fetch`, `parseJson`, `projectArray`, `projectObject`.
- Thêm HTTP snapshot replay v1; fixture không bao giờ tự fallback sang live network.
- Tách detached signature verifier dùng chung cho SourcePack và repository.
- Thêm module `source-repository` với strict index parser, canonical signature, expiry, package compatibility và tamper rejection.
- Thêm repository HTTPS downloader với public DNS, bounded redirects, exact size và SHA-256.
- Thêm cache repository atomic và loại cache hỏng/hết hạn khi khởi động.
- Thêm UI repository/package và luồng download → verify → self-test → permission approval → install.
- Thêm compiler/smoke gate Cột mốc 2, unit tests cho network policy, runtime fetch và repository signature.
- Gate biên dịch cả test của bảy module và chạy ví dụ thật `sourcepack-network-demo` qua manifest parser/fixture runner.
- Siết repository: cấm danh sách package rỗng, giới hạn signer key/signature và kiểm tra thời gian không âm; thêm builder và ví dụ index chưa ký.
- Thêm script build Cột mốc 2 cho Windows/Linux; Gradle CI chạy cả test JVM modules.
- Nâng versionCode lên 13 và versionName thành `1.3.0-milestone-2-network-repository`.

## 1.2.0-milestone-1-foundation

- Bắt đầu Cột mốc 1 với hợp đồng build Android độc lập và CI.
- Thêm `WrapperDownloader.java`; `gradlew`/`gradlew.bat` xác minh Gradle Wrapper JAR 8.13 ở mỗi lần chạy, tự thay JAR thiếu hoặc sai checksum.
- Thay bootstrap cũ tải cả Gradle distribution bằng bootstrap JAR nhỏ, bounded và atomic.
- Bỏ `compileSdkMinor = 1`, giữ `compileSdk = 36` để giảm yêu cầu SDK không cần thiết.
- Nâng Room schema lên version 7.
- Sửa migration `5 → 6` bị thiếu bảng `download_jobs`; migration `6 → 7` dựng lại ba bảng để chuẩn hóa default SQL mà không mất dữ liệu.
- Khai báo default SQL bằng `@ColumnInfo` cho các cột non-null được thêm qua migration.
- Thêm instrumentation test cho migration `5 → 6` và `6 → 7`.
- Thêm Android CI: unit test, lint, debug APK, Android-test APK, release AAB và migration test thật trên emulator API 33.
- Thêm lint gate, Room schema output, script build một chạm cho Windows/Linux và milestone foundation static check.
- Sửa Wave assembler gate dùng `java -jar` để không treo do Kotlin launcher giữ pipe.
- Nâng versionCode lên 12 và versionName thành `1.2.0-milestone-1-foundation`.

## 1.1.0-source-platform-foundation

- Thêm năm module Source Platform 2: API, package verifier, version store, declarative runtime và diagnostics.
- Thêm định dạng `.ntsource` với strict manifest v2, canonical `FILES.sha256` và chữ ký ECDSA P-256/Ed25519.
- Thêm giới hạn archive/entry/uncompressed/compression ratio, chống path traversal, Unicode/case collision và payload không được hash.
- Thêm trust root pinning, hỗ trợ nhiều khóa cùng thuật toán để chuẩn bị key rotation.
- Thêm fixture self-test bắt buộc trước staging/activation; gói không có fixture hoặc fixture lỗi bị từ chối.
- Thêm permission snapshot/diff, màn hình phê duyệt quyền và số fixture đã đạt.
- Thêm private source store với staging, atomic activation, enable/disable, retention, rollback và payload tree-integrity khi đọc lại.
- Thêm declarative runtime v1 với eight bounded operations, instruction/memory/deadline/output budget.
- Thêm trace diagnostics bounded, redaction secret và xuất JSON từ màn hình Cá nhân.
- Chuyển nguồn mẫu tích hợp sẵn thành SourcePack đã ký; registry Kotlin cũ chỉ còn fallback nếu bootstrap thất bại.
- Thêm schema, payload mẫu và công cụ deterministic đóng gói/ký bằng khóa P-256 nằm ngoài project.
- Nâng versionCode lên 11 và versionName thành `1.1.0-source-platform-foundation`.

## 1.0.0-kotlin-rewrite

- Nâng Room schema lên version 6 với migration `5 → 6` cho VietPhrase, cache chuyển đổi chương, assignment giọng AI và nhạc cảnh.
- Nâng backup ZIP lên format 6; giữ tương thích format 1–5.
- Thêm VietPhrase cục bộ deterministic, không cascade, priority/longest-match và bảo vệ ranh giới từ.
- Thêm import/export VietPhrase UTF-8 dạng TSV hoặc `nguồn=đích`, giới hạn 16 MiB và 100.000 rule.
- Thêm AI online OpenAI-compatible chỉ hoạt động khi có consent, bật AI, endpoint HTTPS, model và API key.
- Thêm API key AES-GCM với Android Keystore, AAD, password masking và loại trừ khỏi mọi backup.
- Thêm public-only DNS trong OkHttp, chặn private/local/multicast, redirect và response vượt giới hạn.
- Thêm dịch chương strict marker `[[P:n]]`, cache theo fingerprint nội dung + endpoint + model + prompt, và nút DỊCH LẠI.
- Thêm phân vai AI bằng protocol dòng bounded; giữ cấu hình vai người dùng và dùng assignment trong TTS trực tiếp lẫn WAV/M4A.
- Thêm thư viện nhạc cảnh local, sửa tên/tag, AI cue theo paragraph index, ducking và đổi track trong foreground service.
- Chỉ dùng voice assignment/cue khi hash nội dung gốc còn khớp; chuyển chương tự nạp lại plan mới.
- Loại database, DataStore, API credential và source-session preference khỏi Android platform backup/device transfer; dùng backup ZIP có kiểm soát.
- Thêm test/gate P4 cho codec VietPhrase, workflow/hash, AI line protocol, DNS/network, Keystore, backup exclusions và SAF transfer.
- Nâng versionCode lên 10 và versionName thành `1.0.0-kotlin-rewrite`.

## 0.9.0-kotlin-rewrite

- Thêm quét và chọn package bộ máy Android TTS; danh sách voice được tải theo engine đã chọn và fallback về engine mặc định khi engine riêng khởi tạo thất bại.
- Thêm âm lượng TTS toàn cục; hồ sơ giọng theo truyện được mở rộng với engine và volume.
- Thêm chế độ xử lý audio interruption: tạm dừng hoặc tiếp tục để Android thực hiện ducking.
- Thêm nhạc nền local qua SAF, phát lặp, volume riêng và duck factor khi TTS đang đọc; xử lý lỗi URI/MediaPlayer không làm crash service.
- Thêm vai giọng thủ công theo truyện, narrator fallback và alias resolver cho `Tên:`, `Tên -`, `Tên —`, `[Tên]`.
- Voice routing chạy cục bộ, deterministic, không gọi AI; thêm unit test cho alias có dấu, bracket và vai bị tắt.
- Nâng Room schema lên version 5 với migration `4 → 5`: mở rộng `story_tts_profiles`, `audio_export_jobs` và thêm bảng `voice_roles`.
- Nâng backup format lên version 5, lưu engine/volume/profile/vai giọng; URI nhạc nền SAF bị loại khỏi backup vì quyền truy cập chỉ có giá trị trên thiết bị hiện tại.
- Thêm chuẩn hóa WAV PCM 8/16/24/32-bit và IEEE float32 sang PCM16, có gain theo profile/vai.
- Thêm xuất M4A AAC-LC bằng Android `MediaCodec` + `MediaMuxer`; giữ xuất WAV và MIME/đuôi tệp đúng theo job.
- Sửa đổi engine khi đang phát để playback được nối lại sau callback khởi tạo TTS mới.
- Sửa preview khi đổi engine không làm mất đoạn nghe thử đang chờ.
- Sửa quản lý audio focus khi refresh cấu hình và tăng khả năng phục hồi lỗi tệp nhạc nền.
- Thêm `check_p3_features.py` cho PCM normalizer, voice resolver, M4A platform wiring và schema markers.

## 0.8.0-kotlin-rewrite

- Hoàn thiện gói P2 theo hướng adapter Kotlin cố định và phiên đăng nhập cô lập, không đưa extension Lua/WebView runtime chung trở lại.
- Port WikiDich ở trạng thái `DEGRADED`: danh sách, tìm kiếm, URL trực tiếp, chi tiết, mục lục phân trang, chương mới nhất, nội dung và điều hướng trước/sau.
- Port Sáng Tác Việt ở trạng thái `DEGRADED`: metadata HTML, API mục lục/nội dung, paging 100 chương và ánh xạ lỗi session/login typed.
- Thêm `SessionHttpClient` hỗ trợ cookie, HTTPS-only, allowlist từng redirect, response bound, rate governor và giới hạn redirect.
- Thêm `EncryptedSourceSessionStore` dùng Android Keystore AES-GCM với AAD theo source ID; không lưu mật khẩu và không sao lưu session.
- Giới hạn cookie phiên ở 128 mục/32 KiB để tránh nguồn làm phình SharedPreferences.
- Thêm `SourceLoginActivity` WebView cô lập: tắt file/content access, third-party cookies và mixed content; bật Safe Browsing; không có JavaScript bridge; chặn host ngoài allowlist.
- Xóa session dọn cả cookie mã hóa và cookie WebView của nguồn.
- Thêm health checker theo pipeline danh sách → chi tiết/mục lục → nội dung, timeout từng bước và trạng thái NEEDS_LOGIN.
- Kiểm tra tất cả nguồn chạy tuần tự; health checker fallback sang danh mục đầu tiên khi tìm kiếm rỗng không được hỗ trợ.
- Thêm fixture/unit test cho WikiDich, Sáng Tác Việt và cookie codec; thêm các compiler gate P2 cho network, health, Android login và Compose source UI.
- Nâng versionCode lên 8 và versionName thành `0.8.0-kotlin-rewrite`.

## 0.7.0-kotlin-rewrite

- Hoàn thiện gói P1 cho trải nghiệm đọc truyện, chưa đưa AI hoặc extension runtime vào phạm vi.
- Thêm reader theme Hệ thống/Sáng/Tối/Sepia, cỡ chữ, giãn dòng và tùy chọn giữ màn hình sáng.
- Thêm tìm kiếm trong chương, đếm kết quả, điều hướng trước/sau, highlight và sao chép đoạn hiện tại/toàn bộ chương.
- Thêm tìm chương theo tiêu đề hoặc số trong mục lục đã nạp; thêm nạp toàn bộ mục lục nhiều trang.
- Thêm tải khoảng chương inclusive qua WorkManager; giữ batch 40 chương, resume, retry, skip chương đã có và cancel.
- Thêm chế độ tìm kiếm đa nguồn có hủy, tiến độ, paging, chuẩn hóa tiếng Việt và gom trùng tên+tác giả.
- Ưu tiên nguồn READY khi một truyện xuất hiện ở nhiều nguồn; bỏ qua nguồn NOT_PORTED/DISABLED/chưa đăng nhập.
- Nâng Room schema lên version 4 với migration `3 → 4`, bổ sung `latestKnownChapterIndex` và `newChapterCount`.
- Worker theo dõi cộng dồn số chương mới chưa xem; mở truyện/deep link reset bộ đếm.
- Tủ truyện hiển thị badge số chương mới chưa xem.
- Bỏ tab Bình luận placeholder; chỉ mở URL bình luận gốc khi adapter cung cấp.
- Thêm mở trang truyện gốc bằng external HTTPS intent.
- Nâng backup lên format 4, lưu reader settings và bộ đếm/chỉ số chương theo dõi; restore vẫn nhận format 1..4.
- Thêm `check_p1_features.py` và `check_p1_ui_static.py` cho search merge, range selection, unread count và Compose wiring reader/story detail.
- Nâng versionCode lên 7 và versionName thành `0.7.0-kotlin-rewrite`.

## 0.6.0-kotlin-rewrite

- Nâng Room schema lên version 3 với migration `2 → 3` cho `story_tts_profiles` và `audio_export_jobs`.
- Thêm hồ sơ TTS riêng theo truyện gồm voice, language, rate và pitch; reader service ưu tiên profile rồi fallback cài đặt toàn cục.
- Thêm xuất WAV cho chương hiện tại đã cache hoặc toàn bộ chương đã cache/tải của truyện.
- Thêm `TtsFileSynthesizer` coroutine-safe, timeout, callback theo utterance và giới hạn input theo engine.
- Thêm `WaveFileAssembler` kiểm tra RIFF/WAVE, chunk `fmt `/`data`, padding lẻ, kích thước 4 GiB và format tương thích trước khi nối lossless.
- Thêm foreground `AudioExportWorker` loại `mediaProcessing`, progress, retry, cancel notification, temp cleanup và chỉ commit tệp đích khi hoàn tất.
- Áp dụng từ điển phát âm vào từng đoạn trước khi tổng hợp WAV.
- Lưu lịch sử/trạng thái xuất trong Room, hiển thị tiến độ, cho hủy và mở tệp WAV hoàn tất trong màn hình Cá nhân.
- Backup format tăng lên version 3 và chứa hồ sơ giọng theo truyện; restore vẫn đọc format 1..3.
- Sửa nghe thử giọng khi service đang chạy: preview toàn cục không còn vô tình dùng hồ sơ giọng của truyện hiện tại.
- Thêm fixture/harness cho WAV assembler và static compile gate riêng cho subsystem audio export.
- Khai báo `FOREGROUND_SERVICE_MEDIA_PROCESSING` và nối loại foreground tương ứng cho WorkManager SystemForegroundService.

## 0.5.0-kotlin-rewrite

- Thêm Room schema version 2 và migration `1 → 2` rõ ràng cho bảng từ điển phát âm.
- Thêm từ điển phát âm cục bộ: thêm, bật/tắt, xóa, hiển thị toàn bộ và sao lưu/khôi phục.
- Rule engine áp dụng longest-match từ trái sang phải; replacement không cascade hoặc tự lặp.
- Áp dụng từ điển cho cả đọc truyện và nghe thử giọng TTS.
- Thêm nghe thử giọng bằng foreground TTS service, sử dụng voice/rate/pitch hiện tại.
- Thêm quota cache reader 16/32/64/128/256 MiB và dọn chương cache cũ nhất, bảo vệ chương đang mở/đang phát.
- Lưu giới hạn cache trong DataStore và backup; backup format tăng lên version 2 nhưng restore vẫn nhận version 1.
- Port adapter Kotlin TruyenYY ở trạng thái `DEGRADED` qua Markdown/Jina Reader, có danh sách, tìm kiếm, URL trực tiếp, chi tiết, mục lục nhiều trang, nội dung và điều hướng chương.
- Thêm `HttpTextClient` HTTPS-only, allowlist từng redirect, response bound, rate governor và cache bounded.
- Siết URL TruyenYY: chỉ chấp nhận HTTPS và đường dẫn truyện/chương; URL danh mục hoặc miền khác bị từ chối.
- Thêm fixture/test/gate TruyenYY và cập nhật registry health test.
- Sửa danh sách voice/từ điển bị giới hạn quản lý sau 12/30 mục; thêm nút mở toàn bộ và thu gọn.
- Bố trí lại lựa chọn quota cache để không vỡ trên màn hình hẹp.

## 0.4.0-kotlin-rewrite

- Thêm quản lý dung lượng ngoại tuyến theo truyện và tổng dung lượng cache/download.
- Cho phép xóa nội dung tải của truyện mạng nhưng giữ lịch sử; sách nhập được xóa transactionally cùng chương, tiến độ, bookmark, following và job.
- Thêm xóa cache chương đã đọc nhưng chưa đánh dấu tải ngoại tuyến.
- Thêm worker opt-in kiểm tra chương mới khoảng 12 giờ/lần và kiểm tra thủ công bằng unique work.
- Xếp hàng kiểm tra theo `checkedAt ASC`, tối đa 30 truyện/lượt; nguồn lỗi không làm chết đói các truyện còn lại.
- Thông báo chương mới mở thẳng truyện bằng deep link Room-safe, kể cả cold start.
- Thêm quyền thông báo theo ngữ cảnh cho TTS nền và tính năng theo dõi.
- Thêm `latestChapter` tối ưu cho Truyện Full và Truyện Com; fixture test xác nhận trang mục lục cuối.
- Thêm quét/chọn voice TTS đã cài, ưu tiên tiếng Việt/local, nút dùng mặc định và mở cài đặt TTS hệ thống.
- Lưu voice, language tag và opt-in theo dõi trong DataStore/backup.
- Đo dung lượng text bằng `LENGTH(CAST(content AS BLOB))` thay vì số ký tự.
- Ngăn theo dõi sách nhập vì không có nguồn chương trực tuyến.
- Sửa deep link chương mới không phụ thuộc danh sách StateFlow đã nạp xong.

## 0.3.0-kotlin-rewrite

- Đối chiếu lại toàn bộ XPK và lập ma trận parity theo từng nhóm tính năng.
- Sửa luồng đọc tiếp để mở đúng chương và đúng đoạn đã lưu.
- Cho phép mở trực tiếp truyện đang đọc, truyện theo dõi và đánh dấu; thêm xóa đánh dấu.
- Thêm paging kết quả tìm kiếm và danh mục.
- Thêm chuẩn bị chương kế ở mốc 75% và tự chuyển chương trong foreground TTS service.
- Bổ sung cache chương/nội dung và điều hướng lân cận từ Room.
- Thêm sao lưu/khôi phục ZIP có phiên bản, SHA-256, giới hạn mục/kích thước và restore merge-only.
- Thêm rate governor theo host và cache HTML TTL/LRU.
- Siết nhập TXT/EPUB/DOCX: giới hạn RAM, BOM UTF-8/UTF-16, chống path traversal, path alias và ZIP phình.
- Port adapter Kotlin cho TruyenCV ở trạng thái `DEGRADED`, kèm fixture và static compile gate.
- Khóa cleartext traffic bằng manifest và Network Security Config.
- Cố định Gradle bootstrap bằng SHA-256 cho distribution và wrapper JAR.
- Khai báo `compileSdkMinor = 1` để phù hợp AndroidX Core 1.18.0.
- Chia tải truyện dài thành lô tối đa 40 chương, tiếp nối bằng unique WorkManager chain và truy vấn một lần danh sách chương đã có.
- Thêm hẹn giờ ngủ 15/30/60 phút trong foreground TTS service; hết giờ sẽ dừng TTS, nhả audio focus và wake lock.
- Khôi phục nguồn Truyện Com bị bỏ sót trong bản rewrite: adapter Kotlin `DEGRADED`, tìm kiếm slug, danh sách, chi tiết, mục lục, nội dung, fixture và static compile gate.
- Sửa audio focus: tạm dừng khi cuộc gọi/âm thanh khác chen vào và chỉ tự tiếp tục sau `AUDIOFOCUS_GAIN` nếu trước đó đang phát; pause thủ công không tự phát lại.

## 0.2.0-kotlin-rewrite

- Port adapter Kotlin thật đầu tiên cho Truyện Full.
- Thêm tìm kiếm, URL truyện trực tiếp, danh mục, chi tiết, mục lục phân trang và nội dung chương.
- Thêm fixture HTML và test parser/planner.
- Thêm HTTP allowlist theo từng chặng redirect, HTTPS-only, giới hạn số redirect và giới hạn phản hồi.
- Thêm tải toàn truyện ngoại tuyến, tiến độ Room, retry, bỏ qua chương đã có và hủy.
- Nâng worker tải dài thành foreground `dataSync` có notification.
- Bật NIO core-library desugaring và cập nhật jsoup.

## 0.1.0-kotlin-rewrite

- Khởi tạo clean rewrite Kotlin/Compose.
- Thêm Room, DataStore, TTS foreground service và import TXT/EPUB/DOCX.
